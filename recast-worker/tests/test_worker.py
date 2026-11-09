import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import threading
import time
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import MAX_BODY, Store, create_app
from engine import MODEL, WanEngine, probe, validate_media

TOKEN = "test-token-" + "a" * 40
AUTH = {"Authorization": "Bearer " + TOKEN}
OPTIONS = {"start": 0, "duration": 2, "resolution": "480p", "seed": 123, "keep_audio": True}


@pytest.fixture(scope="session")
def media(tmp_path_factory):
    folder = tmp_path_factory.mktemp("media")
    video = folder / "source.mp4"
    subprocess.run(["ffmpeg", "-nostdin", "-y", "-v", "error", "-f", "lavfi", "-i",
                    "color=c=blue:s=128x128:r=24:d=3", "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
                    "-c:v", "libx264", "-c:a", "aac", "-pix_fmt", "yuv420p", str(video)], check=True)
    image = io.BytesIO()
    Image.new("RGB", (128, 128), "green").save(image, format="JPEG")
    return video.read_bytes(), image.getvalue()


class TestRenderer:
    __test__ = False
    def __init__(self):
        self.calls = 0
        self.ready = True
        self.fail = False
        self.gate = threading.Event()
        self.gate.set()

    def readiness(self):
        return self.ready, "Test worker readiness"

    def render(self, folder, options, stage):
        self.calls += 1
        self.gate.wait(5)
        stage("Test render")
        if self.fail:
            raise RuntimeError("Internal secret details must not reach the client")
        shutil.copyfile(folder / "source.upload", folder / "result.mp4")


@pytest.fixture
def service(tmp_path):
    renderer = TestRenderer()
    app = create_app(tmp_path, TOKEN, renderer)
    with TestClient(app, headers=AUTH) as client:
        yield client, renderer, app.state.store
    renderer.gate.set()


def submit(client, media, job=None, options=None):
    return client.post("/v1/jobs", data={"id": job or str(uuid4()), "options": json.dumps(options or OPTIONS)},
                       files={"video": ("test.mp4", media[0], "video/mp4"),
                              "image": ("character.jpg", media[1], "image/jpeg")})


def finished(client, job):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        row = client.get(f"/v1/jobs/{job}").json()
        if row["status"] in {"COMPLETED", "FAILED"}:
            return row
        time.sleep(0.02)
    pytest.fail("Test queue did not finish")


def test_authenticated_upload_poll_download_and_idempotency(service, media):
    client, renderer, store = service
    health = client.get("/v1/health").json()
    assert health["model"] == MODEL and health["ready"]
    job = str(uuid4())
    assert submit(client, media, job).status_code == 200
    assert submit(client, media, job).status_code == 200
    assert finished(client, job)["status"] == "COMPLETED"
    response = client.get(f"/v1/jobs/{job}/result")
    assert response.status_code == 200 and response.headers["content-type"] == "video/mp4"
    assert response.content == media[0]
    assert renderer.calls == 1
    # No uploaded faces or original footage persist after terminal completion.
    deadline = time.monotonic() + 2
    while (store.root / job / "source.upload").exists() and time.monotonic() < deadline:
        time.sleep(0.01)
    assert set(p.name for p in (store.root / job).iterdir()) == {"result.mp4"}


def test_auth_and_oversize_rejected_before_multipart(service):
    client, _, _ = service
    assert client.get("/v1/health", headers={"Authorization": "Bearer wrong"}).status_code == 401
    assert client.get(f"/v1/jobs/{uuid4()}/result", headers={"Authorization": ""}).status_code == 401
    assert client.post("/v1/jobs", content=b"x", headers={"Content-Length": str(MAX_BODY + 1)}).status_code == 413


@pytest.mark.parametrize("settings", [dict(OPTIONS, start=-1), dict(OPTIONS, start=float("nan")),
    dict(OPTIONS, duration=11), dict(OPTIONS, resolution="4k"), dict(OPTIONS, seed=0), dict(OPTIONS, surprise=True)])
def test_invalid_recipe_never_queues(service, media, settings):
    client, renderer, _ = service
    assert submit(client, media, options=settings).status_code == 422
    assert renderer.calls == 0


def test_invalid_media_and_trim_never_queues(service, media):
    client, renderer, store = service
    assert submit(client, (b"not video", media[1])).status_code == 422
    assert submit(client, (media[0], b"not image")).status_code == 422
    assert submit(client, media, options=dict(OPTIONS, start=2)).status_code == 422
    assert renderer.calls == 0
    assert not any(p.is_dir() for p in store.root.iterdir())


def test_unready_worker_does_not_accept_job(service, media):
    client, renderer, _ = service
    renderer.ready = False
    assert client.get("/v1/health").json()["ready"] is False
    assert submit(client, media).status_code == 503
    assert renderer.calls == 0


def test_queue_bound_and_in_progress_result(service, media):
    client, renderer, _ = service
    renderer.gate.clear()
    try:
        jobs = [str(uuid4()) for _ in range(5)]
        for job in jobs[:4]:
            assert submit(client, media, job).status_code == 200
        assert submit(client, media, jobs[4]).status_code == 429
        assert client.get(f"/v1/jobs/{jobs[0]}/result").status_code == 409
    finally:
        renderer.gate.set()


def test_failure_hides_internal_details_and_cleans_upload(service, media):
    client, renderer, store = service
    renderer.fail = True
    job = submit(client, media).json()["id"]
    row = finished(client, job)
    assert row["status"] == "FAILED"
    assert "secret" not in row["error"]
    assert client.get(f"/v1/jobs/{job}/result").status_code == 409


def test_restart_does_not_rebill_running_job(tmp_path):
    store = Store(tmp_path)
    running, queued = str(uuid4()), str(uuid4())
    with store.connect() as db:
        for job, state in [(running, "RUNNING"), (queued, "QUEUED")]:
            (tmp_path / job).mkdir()
            (tmp_path / job / "source.upload").write_bytes(b"private")
            db.execute("INSERT INTO jobs VALUES (?,?,?,?,?,?,?)", (job, state, "stage", json.dumps(OPTIONS), 1, 1, None))
    store.recover()
    assert store.get(running)["status"] == "FAILED"
    assert not (tmp_path / running / "source.upload").exists()
    assert store.get(queued)["status"] == "QUEUED"
    assert (tmp_path / queued / "source.upload").exists()


def test_expired_result_keeps_idempotency_tombstone(service, media):
    client, _, store = service
    job = submit(client, media).json()["id"]
    finished(client, job)
    with store.connect() as db:
        db.execute("UPDATE jobs SET updated=? WHERE id=?", (time.time() - 90000, job))
    store.expire()
    assert client.get(f"/v1/jobs/{job}/result").status_code == 410
    assert submit(client, media, job).json()["status"] == "COMPLETED"


@pytest.mark.parametrize("keep_audio", [True, False])
def test_real_ffmpeg_preserves_timing_and_audio(tmp_path, media, keep_audio):
    (tmp_path / "source.upload").write_bytes(media[0])
    (tmp_path / "reference.upload").write_bytes(media[1])
    options = dict(OPTIONS, start=0.5, keep_audio=keep_audio)
    validate_media(tmp_path, options)
    engine = WanEngine()
    engine.repo = tmp_path
    # Only GPU inference is substituted: run the real trim, normalization and mux code.
    preprocess, generate = engine.commands(tmp_path, options)
    assert "--replace_flag" in preprocess and "--replace_flag" in generate
    assert "--use_relighting_lora" in generate
    assert preprocess[preprocess.index("--fps") + 1] == "30"
    engine.commands = lambda folder, _: (
        [sys.executable, "-c", "pass"],
        ["ffmpeg", "-nostdin", "-y", "-v", "error", "-i", str(folder / "clip.mp4"),
         "-an", "-c:v", "copy", str(folder / "render.mp4")])
    engine.render(tmp_path, options, lambda _: None)
    result = probe(tmp_path / "result.mp4")
    assert abs(result["duration"] - 2) < 0.1
    assert result["audio"] is keep_audio
