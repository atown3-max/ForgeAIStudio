"""Private, single-owner, single-GPU Recast service. Run exactly one Uvicorn worker."""
import asyncio
from contextlib import asynccontextmanager
import hmac
import fcntl
import json
import logging
import os
from pathlib import Path
import shutil
import sqlite3
import threading
import time
from uuid import UUID

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from typing import Literal

from engine import InputError, MODEL, WanEngine, validate_media

MAX_VIDEO = 40 * 1024 * 1024
MAX_IMAGE = 8 * 1024 * 1024
MAX_BODY = MAX_VIDEO + MAX_IMAGE + 65536
TERMINAL = ("COMPLETED", "FAILED")


class Options(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    start: float = Field(default=0, ge=0, le=600)
    duration: int = Field(ge=2, le=10)
    resolution: Literal["480p", "720p"] = "480p"
    seed: int = Field(ge=1, le=2147483646)
    keep_audio: bool = True


class Store:
    def __init__(self, root):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.db = self.root / "jobs.sqlite"
        with self.connect() as db:
            db.execute("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, status TEXT, stage TEXT, "
                       "options TEXT, created REAL, updated REAL, error TEXT)")

    def connect(self):
        db = sqlite3.connect(self.db, timeout=30)
        db.row_factory = sqlite3.Row
        return db

    def get(self, job):
        with self.connect() as db:
            row = db.execute("SELECT * FROM jobs WHERE id=?", (job,)).fetchone()
        return dict(row) if row else None

    def update(self, job, status, stage, error=None):
        with self.connect() as db:
            db.execute("UPDATE jobs SET status=?,stage=?,error=?,updated=? WHERE id=?",
                       (status, stage, error, time.time(), job))

    def public(self, row):
        return {"id": row["id"], "status": row["status"], "stage": row["stage"], "error": row["error"],
                "model": MODEL, "options": json.loads(row["options"])}

    def clear_inputs(self, job):
        folder = self.root / job
        if folder.exists():
            for path in folder.iterdir():
                if path.name != "result.mp4":
                    if path.is_dir():
                        shutil.rmtree(path)
                    else:
                        path.unlink(missing_ok=True)

    def recover(self):
        with self.connect() as db:
            interrupted = db.execute("SELECT id FROM jobs WHERE status='RUNNING'").fetchall()
        for row in interrupted:
            self.update(row["id"], "FAILED", "Interrupted",
                        "Worker restarted during rendering. Start a new render to retry.")
            self.clear_inputs(row["id"])
        # Remove incomplete uploads left by a process restart before a job was accepted.
        for folder in self.root.iterdir():
            if folder.is_dir() and not self.get(folder.name):
                shutil.rmtree(folder)

    def expire(self):
        # Keep a tombstone for idempotency; retain downloadable results for 24 hours only.
        with self.connect() as db:
            rows = db.execute("SELECT id FROM jobs WHERE status IN ('COMPLETED','FAILED') AND updated<?",
                              (time.time() - 86400,)).fetchall()
        for row in rows:
            shutil.rmtree(self.root / row["id"], ignore_errors=True)


def create_app(data_dir=None, token=None, engine=None):
    secret = token if token is not None else os.getenv("RECAST_API_TOKEN", "")
    if len(secret) < 32:
        raise RuntimeError("Set RECAST_API_TOKEN to a random secret of at least 32 characters.")
    store = Store(data_dir or os.getenv("RECAST_DATA_DIR", "/workspace/recast"))
    renderer = engine or WanEngine()
    stopped = threading.Event()
    upload_lock = asyncio.Lock()

    def work():
        while not stopped.is_set():
            store.expire()
            with store.connect() as db:
                row = db.execute("SELECT * FROM jobs WHERE status='QUEUED' ORDER BY created LIMIT 1").fetchone()
            if row is None:
                stopped.wait(0.5)
                continue
            job = row["id"]
            store.update(job, "RUNNING", "Starting")
            try:
                renderer.render(store.root / job, json.loads(row["options"]),
                                lambda stage: store.update(job, "RUNNING", stage))
                if not (store.root / job / "result.mp4").is_file():
                    raise RuntimeError("No result file")
                store.update(job, "COMPLETED", "Ready to download")
            except Exception:
                logging.exception("Recast job %s failed", job)
                (store.root / job / "result.mp4").unlink(missing_ok=True)
                store.update(job, "FAILED", "Render failed",
                             "Rendering failed. Check worker GPU memory, model installation, and a clear single-person clip.")
            finally:
                store.clear_inputs(job)

    @asynccontextmanager
    async def lifespan(_):
        with (store.root / ".worker.lock").open("w") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise RuntimeError("Only one worker may use this Recast data directory.") from None
            store.recover()
            thread = threading.Thread(target=work, daemon=True, name="wan-recast")
            thread.start()
            try:
                yield
            finally:
                stopped.set()
                if hasattr(renderer, "stop"):
                    renderer.stop()
                await asyncio.to_thread(thread.join, 5)

    app = FastAPI(title="Forge Recast", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.store = store

    @app.middleware("http")
    async def protect(request: Request, call_next):
        authorization = request.headers.get("authorization", "")
        if not hmac.compare_digest(authorization.encode(), ("Bearer " + secret).encode()):
            return JSONResponse({"detail": "Invalid Recast worker token."}, status_code=401)
        if request.method == "POST":
            try:
                length = int(request.headers.get("content-length", ""))
            except ValueError:
                return JSONResponse({"detail": "Content-Length is required."}, status_code=411)
            if not 0 < length <= MAX_BODY:
                return JSONResponse({"detail": "Upload too large. Video limit: 40 MB; image limit: 8 MB."}, status_code=413)
        return await call_next(request)

    @app.get("/v1/health")
    def health():
        ready, message = renderer.readiness()
        return {"protocol": 1, "model": MODEL, "ready": ready, "message": message,
                "max_seconds": 10, "max_video_bytes": MAX_VIDEO, "max_image_bytes": MAX_IMAGE,
                "single_person_only": True}

    def job_id(value):
        try:
            parsed = str(UUID(value))
            if parsed != value:
                raise ValueError()
            return parsed
        except ValueError:
            raise HTTPException(422, "Invalid job ID.") from None

    @app.post("/v1/jobs")
    async def submit(request: Request):
        if upload_lock.locked():
            raise HTTPException(429, "Another upload is being prepared. Retry shortly with the same job ID.")
        async with upload_lock:
            async with request.form(max_files=2, max_fields=2, max_part_size=MAX_IMAGE) as form:
                job = job_id(str(form.get("id", "")))
                existing = store.get(job)
                if existing:
                    return store.public(existing)
                try:
                    options = Options.model_validate_json(str(form.get("options", ""))).model_dump()
                except ValueError:
                    raise HTTPException(422, "Invalid clip settings: select 2–10 seconds, 480p/720p and a valid seed.") from None
                ready, message = renderer.readiness()
                if not ready:
                    raise HTTPException(503, message)
                with store.connect() as db:
                    count = db.execute("SELECT COUNT(*) FROM jobs WHERE status IN ('QUEUED','RUNNING')").fetchone()[0]
                if count >= 4:
                    raise HTTPException(429, "Worker queue is full. Try again after a render finishes.")
                folder = store.root / job
                folder.mkdir()
                try:
                    for field, filename, limit in [("video", "source.upload", MAX_VIDEO),
                                                    ("image", "reference.upload", MAX_IMAGE)]:
                        upload = form.get(field)
                        if not hasattr(upload, "read"):
                            raise HTTPException(422, "Both a video and character image are required.")
                        size = 0
                        with (folder / filename).open("wb") as output:
                            while chunk := await upload.read(1024 * 1024):
                                size += len(chunk)
                                if size > limit:
                                    raise HTTPException(413, f"{field.capitalize()} exceeds its upload limit.")
                                output.write(chunk)
                        if size == 0:
                            raise HTTPException(422, f"{field.capitalize()} is empty.")
                    await asyncio.to_thread(validate_media, folder, options)
                    now = time.time()
                    with store.connect() as db:
                        db.execute("INSERT INTO jobs VALUES (?,?,?,?,?,?,?)",
                                   (job, "QUEUED", "Waiting for GPU", json.dumps(options), now, now, None))
                except InputError as error:
                    shutil.rmtree(folder, ignore_errors=True)
                    raise HTTPException(422, str(error)) from None
                except BaseException:
                    shutil.rmtree(folder, ignore_errors=True)
                    raise
                return store.public(store.get(job))

    @app.get("/v1/jobs/{job}")
    def status(job: str):
        row = store.get(job_id(job))
        if row is None:
            raise HTTPException(404, "Job not found on this worker.")
        return store.public(row)

    @app.get("/v1/jobs/{job}/result")
    def result(job: str):
        row = store.get(job_id(job))
        if row is None:
            raise HTTPException(404, "Job not found.")
        if row["status"] != "COMPLETED":
            raise HTTPException(409, "Result is not ready.")
        path = store.root / job / "result.mp4"
        if not path.exists():
            raise HTTPException(410, "This result expired. Worker results are kept for 24 hours.")
        return FileResponse(path, media_type="video/mp4", filename="recast.mp4")

    return app
