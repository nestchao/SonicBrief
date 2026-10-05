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
import gemini_settings
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
    pipeline.register_job(job_id)
    EXECUTOR.submit(pipeline.run_job, job_id, **options)


@app.get("/api/health")
def health() -> dict[str, object]:
    gpu = pipeline.cuda_available()
    return {
        "ok": True,
        "cuda_available": gpu,
        "device": "NVIDIA GPU" if gpu else "CPU mode",
        "ffmpeg_available": pipeline.ffmpeg_available(),
        "gemini_configured": gemini_settings.is_configured(),
        "diarization_configured": bool(config.HF_TOKEN),
        "models": config.ALLOWED_MODELS,
    }


@app.get("/api/settings/gemini")
def gemini_settings_status() -> dict[str, object]:
    return {
        "configured": gemini_settings.is_configured(),
        "model": gemini_settings.get_gemini_model(),
        "models": [],
    }


@app.post("/api/settings/gemini/models")
async def available_gemini_models(payload: dict[str, object]) -> dict[str, object]:
    api_key = payload.get("api_key")
    if api_key is None:
        key = gemini_settings.get_gemini_api_key()
    elif isinstance(api_key, str):
        key = api_key.strip()
    else:
        raise HTTPException(status_code=400, detail="API key must be a string.")

    if not key:
        raise HTTPException(status_code=400, detail="API key is required to load Gemini models.")
    if len(key) > 512:
        raise HTTPException(status_code=400, detail="API key is too long.")

    try:
        models = await asyncio.to_thread(gemini_settings.list_gemini_models, key)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Could not load Gemini models: {exc}") from exc

    if not models:
        raise HTTPException(
            status_code=502,
            detail="Google returned no compatible Gemini models that support generateContent.",
        )

    available_ids = {str(item["id"]) for item in models}
    current_model = gemini_settings.get_gemini_model()
    selected_model = current_model if current_model in available_ids else str(models[0]["id"])
    return {"model": selected_model, "models": models}


@app.post("/api/settings/gemini")
def save_gemini_settings(payload: dict[str, object]) -> dict[str, object]:
    api_key = payload.get("api_key")
    model = payload.get("model", gemini_settings.get_gemini_model())

    if api_key is not None:
        if not isinstance(api_key, str) or not api_key.strip():
            raise HTTPException(status_code=400, detail="API key must be a non-empty string.")
        api_key = api_key.strip()
        if len(api_key) > 512:
            raise HTTPException(status_code=400, detail="API key is too long.")

    if not isinstance(model, str):
        raise HTTPException(status_code=400, detail="Gemini model is required.")

    try:
        selected_model = gemini_settings.save_gemini_model(model)
        if api_key is not None:
            gemini_settings.save_gemini_api_key(api_key)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return {
        "configured": gemini_settings.is_configured(),
        "model": selected_model,
        "models": [],
    }


@app.post("/api/settings/gemini/test")
async def test_gemini_settings(payload: dict[str, object]) -> dict[str, object]:
    api_key = payload.get("api_key")
    model = payload.get("model", gemini_settings.get_gemini_model())

    if api_key is None:
        key = gemini_settings.get_gemini_api_key()
    elif isinstance(api_key, str):
        key = api_key.strip()
    else:
        raise HTTPException(status_code=400, detail="API key must be a string.")

    if not key:
        raise HTTPException(status_code=400, detail="API key is required.")
    if len(key) > 512:
        raise HTTPException(status_code=400, detail="API key is too long.")
    if not isinstance(model, str):
        raise HTTPException(status_code=400, detail="Gemini model is required.")
    try:
        model = gemini_settings.normalize_gemini_model_id(model)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    try:
        from google import genai
        client = genai.Client(api_key=key)
        response = await asyncio.to_thread(
            client.models.generate_content,
            model=model,
            contents="Reply with OK.",
        )
        if not (response.text or "").strip():
            raise RuntimeError("Gemini returned an empty response.")
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Gemini connection failed: {exc}") from exc
    return {"ok": True, "model": model}


@app.delete("/api/settings/gemini")
def clear_gemini_settings() -> dict[str, object]:
    gemini_settings.clear_gemini_api_key()
    return {
        "configured": False,
        "model": gemini_settings.get_gemini_model(),
        "models": [],
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
    storage.save_summary(job_id, summary, summary_language, summary_style, gemini_settings.get_gemini_model())
    return storage.get_job(job_id)


@app.post("/api/jobs/{job_id}/cancel")
def cancel_job(job_id: str) -> dict[str, object]:
    try:
        record = storage.get_job(job_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="Task not found.") from exc
    if record["status"] not in {"queued", "processing"}:
        raise HTTPException(status_code=409, detail="This task is no longer running.")
    pipeline.request_cancel(job_id)
    storage.update_job(job_id, status="cancelled", stage="cancelled", progress=record.get("progress", 0), error="Processing was cancelled by the user.")
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
