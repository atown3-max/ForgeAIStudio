# Forge Recast worker (preview)

This is the backend for **Video → Recast** in Forge Android v0.5. It accepts an uploaded video and one character image and runs **Wan-AI/Wan2.2-Animate-14B in replacement mode**, including its relighting LoRA. It is a private, single-owner service with one GPU process and a durable queue. It does not call DreamFort or the normal RunPod `wan-2-5` image-to-video endpoint.

## Current scope

- One visible person, one reference image, a 2–10 second section, 480p/720p **pixel-area targets** preserving the source aspect ratio, output at 30 FPS.
- Full-character replacement. A clear full-body reference is recommended; a face crop alone does not specify the entire outfit/body.
- Optional original audio, matched to the selected start time and trimmed to the selected duration. This does not clone or change the voice.
- 40 MB source upload and 8 MB reference limit. Source must be an MP4/MOV and no larger than 4K. Android resizes reference images before upload.
- Automatic single-person tracking/masking. No manual subject selection or multi-character replacement yet. Crowds, cuts, occlusion and mismatched proportions can produce poor results; the service does not detect/reject every multi-person scene.
- Native upstream pipeline; no extra prompt classifier is added by this adapter. No blanket claim of "uncensored" behavior. Model/dependency licenses and host requirements remain applicable. Existing app consent boundaries remain in place.

## Deployment on a RunPod GPU Pod

The GPU deployment and model quality have **not** been validated by the CPU contract tests. The container is a reproducible deployment recipe to validate on a GPU before enabling this preview for normal use.

1. Build the container from this directory and push it to a container registry you control:

   ```sh
   docker build -t YOUR_REGISTRY/forge-recast:0.5 .
   docker push YOUR_REGISTRY/forge-recast:0.5
   ```

2. Create a GPU Pod using that image. For the initial unquantized validation, budget an 80 GB CUDA GPU, substantial host RAM (128 GB suggested), and at least 120 GB of persistent disk. These are conservative provisioning targets, not measured minimums or a performance guarantee. Pin smaller/quantized configurations only after benchmarking them. Rental/storage costs apply while provisioned.

3. Mount persistent storage at `/workspace`. Expose HTTP port **8000** through RunPod's HTTPS proxy. Set `RECAST_API_TOKEN` to a private random secret of at least 32 characters. Generate it locally with a password manager or `python -c 'import secrets; print(secrets.token_urlsafe(48))'`. This is a dedicated worker secret, **not** a RunPod account API key. Do not commit it.

4. In the Pod terminal, download weights once:

   ```sh
   cd /opt/forge-recast
   python download_models.py
   ```

   This includes the pose/detection/SAM2 checkpoints needed by replacement. It excludes the optional FLUX editor, which is not used. The resolved model revision is written to `forge-model-revision.txt`; set `WAN_MODEL_REVISION` to that SHA to reproduce it on another worker. Source is pinned to Wan commit `1ea34ff48f87168174e12956e200b1d908b1c5ff`.

5. The container starts Uvicorn on port 8000. Use exactly **one** Uvicorn worker and one container per data directory/GPU. `/v1/health` is authenticated and reports `ready: false` until checkpoints and CUDA are available. The health probe checks availability, not whether a full GPU render succeeds.

6. In Forge → More → Settings, enter the Pod's HTTPS proxy origin and the worker token, then **Test**. Example shape: `https://YOUR_POD_ID-8000.proxy.runpod.net`. Enter only the origin, without `/v1`, a query, or credentials. The app rejects plaintext HTTP and redirects, and stores the token using Android Keystore.

7. Validate one 2-second 480p single-person clip with a permitted reference image. Inspect identity, pose, hands, background and audio timing. Record GPU model, model revision, elapsed time, peak memory and actual cost before choosing a production configuration. Then test 5/10 seconds, portrait orientation, silent source, changed start time, disconnect/reopen, and a deliberate invalid input.

## Lifecycle, privacy and costs

Android saves an immutable job ID and input files before submission. Retry/reopen queries the same ID first, so a lost response does not create another paid GPU render. The worker persists accepted jobs in SQLite. Queued jobs survive a worker restart; an in-progress job becomes FAILED on restart and is **not** silently billed/restarted. The phone must remain open until upload is accepted; after that the worker renders independently. Return to Video → Recast to download the result. Keep the original worker address/token until the job finishes.

Uploaded media, preprocessing files and per-job subprocess logs are removed after completion/failure. Results expire after 24 hours while the service is running. Expiry is not immediate if the Pod is stopped; clean the persistent volume before relinquishing it. Job metadata/ID tombstones remain for duplicate protection. The Android app saves the result in History and removes its staging copies. A download uses a temporary file and only becomes visible after it finishes. The service is for a single trusted owner; the bearer token grants access to all jobs on this worker. It is not a multi-tenant public API.

Do not scale this SQLite worker horizontally or add multiple Uvicorn workers. Production scale would need a shared queue, per-user authorization, object storage, cancellation and retention management. This prototype allows at most four queued/running jobs, processes one at a time, and stops on stage timeouts. Failed jobs do consume any GPU time already spent. Leaving the app does not cancel a render. There is no cancel button in this version.

## API contract

All requests require `Authorization: Bearer <worker secret>`.

| Request | Result |
|---|---|
| `GET /v1/health` | Protocol 1, model ID, ready state and limits |
| `POST /v1/jobs` | Multipart `id` (client UUID), `options` JSON, `video`, `image`; returns an accepted immutable job |
| `GET /v1/jobs/{id}` | `QUEUED`, `RUNNING`, `COMPLETED`, or `FAILED`, with stage/error |
| `GET /v1/jobs/{id}/result` | Authenticated MP4; 409 before completion; 410 after expiry |

`options` example:

```json
{"start": 0, "duration": 2, "resolution": "480p", "seed": 12345, "keep_audio": true}
```

POST requires a Content-Length and bounded multipart files. Duplicate job IDs return the original job; to change an input or setting, create a new ID. The client must not treat upload timeout or lost connectivity as permission to allocate another ID. Failed jobs are retained until the user clears them locally. No arbitrary input/output URLs are accepted.

## Tests

```sh
python -m venv .venv
.venv/bin/pip install -r requirements-test.txt
.venv/bin/python -m pytest tests -q
```

FFmpeg/FFprobe must be on PATH. Tests cover authentication, queue bounds, idempotency, media/trim validation, worker restart, expiry and a real FFmpeg timing/audio round-trip. GPU model calls are substituted in these tests; they cannot establish render quality, GPU compatibility, runtime, filtering behavior, or price. Run the GPU acceptance steps above separately.

## Upstream references

- [Official Wan source and replacement commands](https://github.com/Wan-Video/Wan2.2)
- [Animate model weights](https://huggingface.co/Wan-AI/Wan2.2-Animate-14B)
- [Preprocessing and single-person limitation](https://github.com/Wan-Video/Wan2.2/blob/main/wan/modules/animate/preprocess/UserGuider.md)
- [RunPod HTTP port/proxy setup](https://docs.runpod.io/pods/configuration/expose-ports)
