"""Bounded adapter for the official Wan2.2-Animate-14B replacement CLI."""
import json
import math
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import threading
import time

from PIL import Image, ImageOps

MODEL = "Wan-AI/Wan2.2-Animate-14B"
WAN_REVISION = "1ea34ff48f87168174e12956e200b1d908b1c5ff"
FPS = 30  # Upstream generate.py writes Animate output at sample_fps=30.
Image.MAX_IMAGE_PIXELS = 24_000_000


class InputError(ValueError):
    pass


def probe(path):
    try:
        result = subprocess.run(
            ["ffprobe", "-v", "error", "-protocol_whitelist", "file,pipe", "-format_whitelist", "mov", "-show_streams",
             "-show_format", "-of", "json", str(path)],
            check=True, capture_output=True, text=True, timeout=30,
        )
        info = json.loads(result.stdout)
        video = next(s for s in info["streams"] if s.get("codec_type") == "video")
        duration = float(video.get("duration") or info["format"]["duration"])
        width, height = int(video["width"]), int(video["height"])
        if not math.isfinite(duration) or duration <= 0 or min(width, height) < 64:
            raise ValueError()
        if max(width, height) > 4096 or width * height > 4096 * 2160:
            raise InputError("Video must be 4K or smaller. Export a smaller copy first.")
        return {"duration": duration, "width": width, "height": height,
                "audio": any(s.get("codec_type") == "audio" for s in info["streams"])}
    except InputError:
        raise
    except (ValueError, KeyError, StopIteration, subprocess.SubprocessError):
        raise InputError("Could not read the video. Use an MP4 or MOV with a video track.") from None


def validate_media(folder, options):
    info = probe(folder / "source.upload")
    if options["start"] + options["duration"] > info["duration"] + 0.1:
        raise InputError("The selected section extends beyond the video. Choose a shorter section.")
    try:
        with Image.open(folder / "reference.upload") as image:
            if image.format not in {"JPEG", "PNG", "WEBP"} or min(image.size) < 64 or image.width * image.height > 24_000_000:
                raise ValueError()
            image = ImageOps.exif_transpose(image).convert("RGB")
            image.thumbnail((2048, 2048))
            image.save(folder / "reference.png")
    except (OSError, ValueError, Image.DecompressionBombError, Image.DecompressionBombWarning):
        raise InputError("Use a readable JPG, PNG, or WebP character image, at least 64 pixels wide and tall.") from None
    return info


class WanEngine:
    def __init__(self):
        self.repo = Path(os.getenv("WAN_REPO", "/opt/Wan2.2"))
        self.weights = Path(os.getenv("WAN_WEIGHTS", "/workspace/models/Wan2.2-Animate-14B"))
        self.stopping = threading.Event()

    def stop(self):
        self.stopping.set()

    def readiness(self):
        required = ["Wan2.1_VAE.pth", "models_t5_umt5-xxl-enc-bf16.pth", "relighting_lora.ckpt",
                    "process_checkpoint/det/yolov10m.onnx",
                    "process_checkpoint/pose2d/vitpose_h_wholebody.onnx",
                    "process_checkpoint/sam2/sam2_hiera_large.pt"]
        if not (self.repo / "generate.py").is_file():
            return False, "Install the pinned Wan source on the worker."
        if any(not (self.weights / item).is_file() for item in required):
            return False, "Download the full Animate weights and preprocessing checkpoints."
        if not list(self.weights.glob("*.safetensors")):
            return False, "Animate diffusion weights are missing."
        if not shutil.which("ffmpeg") or not shutil.which("ffprobe"):
            return False, "Install FFmpeg on the worker."
        try:
            import torch
            if not torch.cuda.is_available():
                return False, "A CUDA GPU is required."
        except ImportError:
            return False, "Install the GPU dependencies on the worker."
        return True, "Wan-Animate worker ready."

    def commands(self, folder, options):
        area = (832, 480) if options["resolution"] == "480p" else (1280, 720)
        preprocess = [sys.executable, str(self.repo / "wan/modules/animate/preprocess/preprocess_data.py"),
                      "--ckpt_path", str(self.weights / "process_checkpoint"),
                      "--video_path", str(folder / "clip.mp4"),
                      "--refer_path", str(folder / "reference.png"),
                      "--save_path", str(folder / "processed"),
                      "--resolution_area", str(area[0]), str(area[1]),
                      "--fps", str(FPS), "--replace_flag"]
        generate = [sys.executable, str(self.repo / "generate.py"), "--task", "animate-14B",
                    "--ckpt_dir", str(self.weights), "--src_root_path", str(folder / "processed"),
                    "--refert_num", "1", "--replace_flag", "--use_relighting_lora",
                    "--offload_model", "True", "--convert_model_dtype", "--t5_cpu",
                    "--base_seed", str(options["seed"]), "--save_file", str(folder / "render.mp4")]
        return preprocess, generate

    def render(self, folder, options, stage):
        def run(command, timeout=1800, cwd=None):
            # Input content and model output are never returned as API error/log text.
            with (folder / "worker.log").open("ab") as log:
                if self.stopping.is_set():
                    raise RuntimeError("Worker stopping")
                process = subprocess.Popen(command, stdout=log, stderr=log, cwd=cwd, start_new_session=True)
                deadline = time.monotonic() + timeout
                try:
                    while process.poll() is None:
                        if self.stopping.wait(0.2) or time.monotonic() >= deadline:
                            raise RuntimeError("Worker stopped or stage timed out")
                    if process.returncode:
                        raise subprocess.CalledProcessError(process.returncode, command)
                finally:
                    if process.poll() is None:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait()

        stage("Preparing clip")
        run(["ffmpeg", "-nostdin", "-y", "-v", "error", "-protocol_whitelist", "file,pipe", "-format_whitelist", "mov",
             "-i", str(folder / "source.upload"), "-ss", str(options["start"]),
             "-t", str(options["duration"]), "-map", "0:v:0", "-map", "0:a:0?",
             "-vf", "scale='min(1920,iw)':-2,fps=30", "-c:v", "libx264", "-pix_fmt", "yuv420p",
             "-c:a", "aac", "-map_metadata", "-1", str(folder / "clip.mp4")], timeout=120)
        preprocess, generate = self.commands(folder, options)
        stage("Tracking motion and masking")
        run(preprocess, cwd=self.repo)
        stage("Replacing character")
        run(generate, timeout=3600, cwd=self.repo)
        stage("Restoring audio")
        command = ["ffmpeg", "-nostdin", "-y", "-v", "error", "-i", str(folder / "render.mp4")]
        if options["keep_audio"]:
            command += ["-i", str(folder / "clip.mp4"), "-map", "0:v:0", "-map", "1:a:0?"]
        else:
            command += ["-map", "0:v:0", "-an"]
        # Upstream may pad to a temporal segment; trim back to the chosen source duration.
        command += ["-t", str(options["duration"]), "-c:v", "libx264", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-movflags", "+faststart", "-map_metadata", "-1",
                    str(folder / "result.mp4")]
        run(command, timeout=120)
        result = probe(folder / "result.mp4")
        if abs(result["duration"] - options["duration"]) > 0.15:
            raise RuntimeError("Output duration did not match the selected clip.")
