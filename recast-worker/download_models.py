"""Run once on the GPU host; model files are never bundled into the APK/image."""
import os
from pathlib import Path
from huggingface_hub import HfApi, snapshot_download
from engine import MODEL

if __name__ == "__main__":
    destination = Path(os.getenv("WAN_WEIGHTS", "/workspace/models/Wan2.2-Animate-14B"))
    # Resolve once, pin the entire download to that revision, and record it for reproduction.
    revision = os.getenv("WAN_MODEL_REVISION") or HfApi().model_info(MODEL).sha
    snapshot_download(MODEL, revision=revision, local_dir=destination,
                      ignore_patterns=["assets/*", "process_checkpoint/FLUX.1-Kontext-dev/*"])
    (destination / "forge-model-revision.txt").write_text(revision + "\n")
    print(f"Downloaded {MODEL} at {revision} into {destination}")
