FROM pytorch/pytorch:2.6.0-cuda12.4-cudnn9-devel

ENV DEBIAN_FRONTEND=noninteractive PYTHONUNBUFFERED=1 \
    WAN_REPO=/opt/Wan2.2 WAN_WEIGHTS=/workspace/models/Wan2.2-Animate-14B \
    RECAST_DATA_DIR=/workspace/recast MAX_JOBS=4 OMP_NUM_THREADS=4
RUN apt-get update && apt-get install -y --no-install-recommends git ffmpeg libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*
RUN git clone https://github.com/Wan-Video/Wan2.2.git /opt/Wan2.2 \
    && cd /opt/Wan2.2 && git checkout 1ea34ff48f87168174e12956e200b1d908b1c5ff
WORKDIR /opt/forge-recast
COPY constraints-gpu.txt requirements.txt ./
RUN pip install --no-cache-dir packaging ninja wheel \
    && pip install --no-cache-dir -c constraints-gpu.txt -r /opt/Wan2.2/requirements.txt \
       -r /opt/Wan2.2/requirements_animate.txt moviepy==1.0.3 --no-build-isolation \
    && pip install --no-cache-dir -r requirements.txt
COPY app.py engine.py download_models.py ./
EXPOSE 8000
CMD ["uvicorn", "app:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--no-access-log"]
