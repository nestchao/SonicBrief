FROM python:3.11-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    SONICBRIEF_DATA_DIR=/data \
    HF_HOME=/data/huggingface \
    XDG_CACHE_HOME=/data/cache \
    SONICBRIEF_API_BASE=http://127.0.0.1:7860 \
    SONICBRIEF_AUTO_START_BACKEND=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg libgomp1 ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY backend/requirements.txt /app/backend/requirements.txt
RUN python -m pip install --no-cache-dir -r /app/backend/requirements.txt

COPY backend /app/backend
COPY sonicbrief_mcp.py /app/sonicbrief_mcp.py

RUN mkdir -p /data

VOLUME ["/data"]

ENTRYPOINT ["python", "/app/sonicbrief_mcp.py"]
