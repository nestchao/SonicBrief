from __future__ import annotations

import asyncio
import json
import os
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import PlainTextResponse

import config
import pipeline
import storage

EXECUTOR = ThreadPoolExecutor(max_workers=1, thread_name_prefix="sonicbrief-worker")


@asynccontextmanager
async def lifespan(_: FastAPI):
    storage.initialize()
    yield
    EXECUTOR.shutdown(wait=False, cancel_futures=False)


app = FastAPI(title="SonicBrief Local API", version="0.1.0", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://127.0.0.1:3000", "http://localhost:3000",
        "http://127.0.0.1:4173", "http://localhost:4173",
    ],
    allow_credentials=False,
    allow_methods=["GET", "POST", "DELETE"],
    allow_headers=["*"],
)


def validate_options(model_name: str, language: str) -> None:
    if model_name not in config.ALLOWED_MODELS:
        raise HTTPException(status_code=400, detail="Unsupported Whisper model.")
    if model_name == "distil-large-v3" and language not in {"auto", "en"}:
        raise HTTPException(status_code=400, detail="Distil-Whisper Large v3 supports English. Choose English/auto or another model.")


def submit_pipeline(job_id: str, **options: object) -> None:
    EXECUTOR.submit(pipeline.run_job, job_id, **options)


@app.get("/api/health")
def health() -> dict[str, object]:
    gpu = pipeline.cuda_available()
    return {
        "ok": True,
        "cuda_available": gpu,
        "device": "NVIDIA GPU" if gpu else "CPU mode",
        "ffmpeg_available": pipeline.ffmpeg_available(),
        "gemini_configured": bool(config.GEMINI_API_KEY),
        "diarization_configured": bool(config.HF_TOKEN),
        "models": config.ALLOWED_MODELS,
    }


@app.get("/api/jobs")
def jobs(limit: int = 50) -> list[dict[str, object]]:
    return storage.list_jobs(limit)


@app.get("/api/jobs/{job_id}")
def job(job_id: str) -> dict[str, object]:
    try:
        return storage.get_job(job_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="Task not found.") from exc


@app.post("/api/jobs/upload", status_code=202)
async def create_upload_job(
    file: UploadFile = File(...),
    model_name: str = Form("turbo"),
    language: str = Form("auto"),
    diarize: bool = Form(True),
    summary_language: str = Form("zh-CN"),
    summary_style: str = Form("detailed"),
) -> dict[str, object]:
    validate_options(model_name, language)
    filename = Path(file.filename or "recording").name
    suffix = Path(filename).suffix.lower()
    if suffix not in config.ALLOWED_EXTENSIONS:
        raise HTTPException(status_code=400, detail=f"Unsupported audio type: {suffix or 'unknown'}")

    record = storage.create_job(
        title=filename, source_type="upload", source_url=None,
        model_name=model_name, diarization_enabled=diarize,
    )
    destination = config.UPLOAD_DIR / f"{record['id']}{suffix}"
    size = 0
    try:
        with destination.open("wb") as output:
            while chunk := await file.read(1024 * 1024):
                size += len(chunk)
                if size > config.MAX_UPLOAD_BYTES:
                    raise HTTPException(status_code=413, detail=f"File exceeds the {config.MAX_UPLOAD_BYTES // 1024 // 1024} MB limit.")
                output.write(chunk)
    except Exception:
        destination.unlink(missing_ok=True)
        storage.delete_job(record["id"])
        raise
    finally:
        await file.close()

    submit_pipeline(
        record["id"], upload_path=str(destination), source_url=None,
        model_name=model_name, language=language, diarize=diarize,
        summary_language=summary_language, summary_style=summary_style,
    )
    return storage.get_job(record["id"])


@app.post("/api/jobs/url", status_code=202)
def create_url_job(
    url: str = Form(...),
    model_name: str = Form("turbo"),
    language: str = Form("auto"),
    diarize: bool = Form(True),
    summary_language: str = Form("zh-CN"),
    summary_style: str = Form("detailed"),
) -> dict[str, object]:
    validate_options(model_name, language)
    try:
        source_type = pipeline.classify_url(url)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    label = "YouTube video" if source_type == "youtube" else "Bilibili video"
    record = storage.create_job(
        title=label, source_type=source_type, source_url=url.strip(),
        model_name=model_name, diarization_enabled=diarize,
    )
    submit_pipeline(
        record["id"], upload_path=None, source_url=url.strip(),
        model_name=model_name, language=language, diarize=diarize,
        summary_language=summary_language, summary_style=summary_style,
    )
    return storage.get_job(record["id"])


@app.post("/api/jobs/{job_id}/summaries")
async def regenerate_summary(
    job_id: str,
    summary_language: str = Form("zh-CN"),
    summary_style: str = Form("detailed"),
) -> dict[str, object]:
    try:
        record = storage.get_job(job_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="Task not found.") from exc
    transcript = record.get("transcript") or []
    if not transcript:
        raise HTTPException(status_code=409, detail="This task has no transcript to summarize.")
    try:
        summary = await asyncio.to_thread(pipeline.make_summary, transcript, summary_language, summary_style)
    except Exception as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    storage.save_summary(job_id, summary, summary_language, summary_style, config.GEMINI_SUMMARY_MODEL)
    return storage.get_job(job_id)


@app.delete("/api/jobs/{job_id}", status_code=204, response_model=None)
def remove_job(job_id: str) -> None:
    if not storage.delete_job(job_id):
        raise HTTPException(status_code=404, detail="Task not found.")


def srt_timestamp(seconds: float) -> str:
    milliseconds = max(0, int(round(seconds * 1000)))
    hours, remainder = divmod(milliseconds, 3_600_000)
    minutes, remainder = divmod(remainder, 60_000)
    secs, millis = divmod(remainder, 1000)
    return f"{hours:02}:{minutes:02}:{secs:02},{millis:03}"


@app.get("/api/jobs/{job_id}/export/{format_name}")
def export_job(job_id: str, format_name: str) -> PlainTextResponse:
    try:
        record = storage.get_job(job_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="Task not found.") from exc
    segments = record.get("transcript") or []
    safe_name = "".join(char if char.isalnum() or char in "-_" else "_" for char in record["title"])[:80] or "transcript"
    if format_name == "srt":
        parts = []
        for index, segment in enumerate(segments, 1):
            speaker = f"{segment.get('speaker')}: " if segment.get("speaker") else ""
            parts.append(f"{index}\n{srt_timestamp(segment['start'])} --> {srt_timestamp(segment['end'])}\n{speaker}{segment['text']}\n")
        content, extension, media_type = "\n".join(parts), "srt", "application/x-subrip"
    elif format_name == "json":
        content, extension, media_type = json.dumps(record, ensure_ascii=False, indent=2), "json", "application/json"
    elif format_name == "txt":
        content = "\n".join(f"[{srt_timestamp(item['start']).replace(',', '.')}]{' ' + item['speaker'] if item.get('speaker') else ''} {item['text']}" for item in segments)
        extension, media_type = "txt", "text/plain"
    else:
        raise HTTPException(status_code=400, detail="Export format must be txt, srt, or json.")
    return PlainTextResponse(content, media_type=media_type, headers={"Content-Disposition": f'attachment; filename="{safe_name}.{extension}"'})


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app:app", host=config.HOST, port=config.PORT, reload=False)
