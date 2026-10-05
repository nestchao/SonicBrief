from __future__ import annotations

import atexit
import mimetypes
import os
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import httpx
from mcp.server import MCPServer

PROJECT_DIR = Path(__file__).resolve().parent
BACKEND_DIR = PROJECT_DIR / "backend"
BACKEND_APP = BACKEND_DIR / "app.py"

API_BASE = os.getenv("SONICBRIEF_API_BASE", "http://127.0.0.1:7860").rstrip("/")
_PARSED_API_BASE = urlparse(API_BASE)
if _PARSED_API_BASE.scheme != "http" or _PARSED_API_BASE.hostname not in {
    "127.0.0.1",
    "localhost",
    "::1",
}:
    raise RuntimeError(
        "SONICBRIEF_API_BASE must use http:// and point to a loopback address "
        "so the MCP bridge stays local."
    )

AUTO_START_BACKEND = os.getenv("SONICBRIEF_AUTO_START_BACKEND", "1").strip().lower() not in {
    "0",
    "false",
    "no",
    "off",
}

mcp = MCPServer("SonicBrief")

_BACKEND_PROCESS: subprocess.Popen[Any] | None = None
_BACKEND_STARTED_BY_MCP = False
_BACKEND_LOCK = threading.Lock()

_JOB_OVERVIEW_FIELDS = (
    "id",
    "title",
    "source_type",
    "source_url",
    "creator_name",
    "status",
    "progress",
    "stage",
    "stage_detail",
    "created_at",
    "updated_at",
    "duration",
    "language",
    "engine",
    "model_name",
    "diarization_enabled",
    "warnings",
    "error",
)


def _backend_is_healthy(timeout: float = 1.0) -> bool:
    try:
        response = httpx.get(f"{API_BASE}/api/health", timeout=timeout)
        return response.status_code == 200
    except httpx.HTTPError:
        return False


def _stop_managed_backend() -> None:
    global _BACKEND_PROCESS, _BACKEND_STARTED_BY_MCP
    process = _BACKEND_PROCESS
    if process is None or process.poll() is not None:
        _BACKEND_PROCESS = None
        _BACKEND_STARTED_BY_MCP = False
        return

    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)
    finally:
        _BACKEND_PROCESS = None
        _BACKEND_STARTED_BY_MCP = False


atexit.register(_stop_managed_backend)


def _ensure_backend() -> None:
    global _BACKEND_PROCESS, _BACKEND_STARTED_BY_MCP

    if _backend_is_healthy():
        return
    if not AUTO_START_BACKEND:
        raise RuntimeError(
            "SonicBrief backend is not running and automatic startup is disabled. "
            "Start backend/app.py locally or enable SONICBRIEF_AUTO_START_BACKEND."
        )
    if not BACKEND_APP.is_file():
        raise RuntimeError(f"SonicBrief backend entry point was not found: {BACKEND_APP}")

    with _BACKEND_LOCK:
        if _backend_is_healthy():
            return

        if _BACKEND_PROCESS is None or _BACKEND_PROCESS.poll() is not None:
            env = os.environ.copy()
            host = _PARSED_API_BASE.hostname or "127.0.0.1"
            if host == "localhost":
                host = "127.0.0.1"
            env["SONICBRIEF_HOST"] = host
            env["SONICBRIEF_PORT"] = str(_PARSED_API_BASE.port or 80)

            kwargs: dict[str, Any] = {
                "cwd": str(BACKEND_DIR),
                "env": env,
                "stdin": subprocess.DEVNULL,
                "stdout": subprocess.DEVNULL,
                "stderr": subprocess.DEVNULL,
            }
            if os.name == "nt":
                kwargs["creationflags"] = getattr(subprocess, "CREATE_NO_WINDOW", 0)

            _BACKEND_PROCESS = subprocess.Popen(
                [sys.executable, str(BACKEND_APP)],
                **kwargs,
            )
            _BACKEND_STARTED_BY_MCP = True

        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if _BACKEND_PROCESS.poll() is not None:
                exit_code = _BACKEND_PROCESS.returncode
                _BACKEND_PROCESS = None
                _BACKEND_STARTED_BY_MCP = False
                raise RuntimeError(
                    "SonicBrief backend exited during automatic startup "
                    f"(exit code {exit_code})."
                )
            if _backend_is_healthy(timeout=1.0):
                return
            time.sleep(0.25)

        _stop_managed_backend()
        raise RuntimeError("Timed out while automatically starting the local SonicBrief backend.")


def _api_request(
    method: str,
    path: str,
    *,
    data: dict[str, Any] | None = None,
    files: dict[str, Any] | None = None,
    timeout: float = 120.0,
) -> Any:
    _ensure_backend()
    try:
        with httpx.Client(base_url=API_BASE, timeout=timeout) as client:
            response = client.request(method, path, data=data, files=files)
    except httpx.ConnectError as exc:
        raise RuntimeError(
            "The local SonicBrief backend became unavailable while handling the MCP request."
        ) from exc
    except httpx.HTTPError as exc:
        raise RuntimeError(f"Could not reach the local SonicBrief backend: {exc}") from exc

    if response.status_code >= 400:
        detail: Any = None
        try:
            payload = response.json()
            if isinstance(payload, dict):
                detail = payload.get("detail")
        except ValueError:
            detail = None
        message = str(detail or response.text or f"HTTP {response.status_code}").strip()
        raise RuntimeError(f"SonicBrief API error ({response.status_code}): {message}")

    if response.status_code == 204 or not response.content:
        return {"ok": True}
    try:
        return response.json()
    except ValueError as exc:
        raise RuntimeError("SonicBrief returned an unexpected non-JSON response.") from exc


def _job_overview(job: dict[str, Any]) -> dict[str, Any]:
    return {key: job.get(key) for key in _JOB_OVERVIEW_FIELDS if key in job}


def _summary_fields(
    summary_language: str,
    summary_style: str,
    summary_instructions: str,
) -> dict[str, str]:
    return {
        "summary_language": summary_language,
        "summary_style": summary_style,
        "summary_custom_instructions": summary_instructions,
    }


def _job_options(
    *,
    language: str,
    whisper_model: str,
    identify_speakers: bool,
    allow_gemini_fallback: bool,
    generate_gemini_summary: bool,
    summary_language: str,
    summary_style: str,
    summary_instructions: str,
) -> dict[str, str]:
    return {
        "language": language,
        "model_name": whisper_model,
        "diarize": str(identify_speakers).lower(),
        "allow_gemini_fallback": str(allow_gemini_fallback).lower(),
        "generate_summary": str(generate_gemini_summary).lower(),
        **_summary_fields(summary_language, summary_style, summary_instructions),
    }


@mcp.tool()
def get_health() -> dict[str, Any]:
    """Check the local SonicBrief STT backend, Whisper/GPU status, and optional cloud features."""
    health = _api_request("GET", "/api/health")
    return {
        **health,
        "backend_url": API_BASE,
        "processing_location": "local computer",
        "whisper_location": "local SonicBrief backend",
        "backend_started_by_mcp": _BACKEND_STARTED_BY_MCP,
        "auto_start_backend": AUTO_START_BACKEND,
        "mcp_transport": "stdio",
        "default_mcp_mode": "local transcript only",
    }


def _submit_media_url(
    url: str,
    *,
    language: str,
    whisper_model: str,
    identify_speakers: bool,
    allow_gemini_fallback: bool,
    generate_gemini_summary: bool,
    summary_style: str,
    summary_instructions: str,
    summary_language: str,
) -> dict[str, Any]:
    payload = {
        "url": url,
        **_job_options(
            language=language,
            whisper_model=whisper_model,
            identify_speakers=identify_speakers,
            allow_gemini_fallback=allow_gemini_fallback,
            generate_gemini_summary=generate_gemini_summary,
            summary_language=summary_language,
            summary_style=summary_style,
            summary_instructions=summary_instructions,
        ),
    }
    job = _api_request("POST", "/api/jobs/url", data=payload)
    return _job_overview(job)


@mcp.tool()
def submit_media_url(
    url: str,
    language: str = "auto",
    whisper_model: str = "turbo",
    identify_speakers: bool = False,
    allow_gemini_fallback: bool = False,
    generate_gemini_summary: bool = False,
    summary_style: str = "standard",
    summary_instructions: str = "",
    summary_language: str = "zh-CN",
) -> dict[str, Any]:
    """Queue one public media URL. By default SonicBrief uses local Whisper and returns a transcript without calling Gemini."""
    return _submit_media_url(
        url,
        language=language,
        whisper_model=whisper_model,
        identify_speakers=identify_speakers,
        allow_gemini_fallback=allow_gemini_fallback,
        generate_gemini_summary=generate_gemini_summary,
        summary_style=summary_style,
        summary_instructions=summary_instructions,
        summary_language=summary_language,
    )


@mcp.tool()
def submit_media_urls(
    urls: list[str],
    language: str = "auto",
    whisper_model: str = "turbo",
    identify_speakers: bool = False,
    allow_gemini_fallback: bool = False,
    generate_gemini_summary: bool = False,
    summary_style: str = "standard",
    summary_instructions: str = "",
    summary_language: str = "zh-CN",
) -> dict[str, Any]:
    """Queue up to 20 URLs in order. Transcript-only local Whisper processing is the default."""
    cleaned = [item.strip() for item in urls if item and item.strip()]
    if not cleaned:
        raise ValueError("Provide at least one media URL.")
    if len(cleaned) > 20:
        raise ValueError("A SonicBrief batch can contain at most 20 URLs.")

    queued: list[dict[str, Any]] = []
    for index, url in enumerate(cleaned, start=1):
        try:
            job = _submit_media_url(
                url,
                language=language,
                whisper_model=whisper_model,
                identify_speakers=identify_speakers,
                allow_gemini_fallback=allow_gemini_fallback,
                generate_gemini_summary=generate_gemini_summary,
                summary_style=summary_style,
                summary_instructions=summary_instructions,
                summary_language=summary_language,
            )
            queued.append({"queue_position": index, "url": url, "job": job})
        except Exception as exc:
            queued.append({"queue_position": index, "url": url, "error": str(exc)})

    return {
        "submitted": len(cleaned),
        "queued": sum(1 for item in queued if "job" in item),
        "items": queued,
        "note": (
            "The SonicBrief backend uses one worker, so successfully submitted jobs run in queue order. "
            "The default MCP mode is local transcript-only processing."
        ),
    }


@mcp.tool()
def submit_local_file(
    path: str,
    language: str = "auto",
    whisper_model: str = "turbo",
    identify_speakers: bool = False,
    allow_gemini_fallback: bool = False,
    generate_gemini_summary: bool = False,
    summary_style: str = "standard",
    summary_instructions: str = "",
    summary_language: str = "zh-CN",
) -> dict[str, Any]:
    """Queue a local media file accessible to the MCP process. Local Whisper transcript-only mode is the default."""
    media_path = Path(path).expanduser().resolve()
    if not media_path.is_file():
        raise ValueError(f"Local media file does not exist: {media_path}")

    content_type = mimetypes.guess_type(media_path.name)[0] or "application/octet-stream"
    payload = _job_options(
        language=language,
        whisper_model=whisper_model,
        identify_speakers=identify_speakers,
        allow_gemini_fallback=allow_gemini_fallback,
        generate_gemini_summary=generate_gemini_summary,
        summary_language=summary_language,
        summary_style=summary_style,
        summary_instructions=summary_instructions,
    )
    with media_path.open("rb") as handle:
        job = _api_request(
            "POST",
            "/api/jobs/upload",
            data=payload,
            files={"file": (media_path.name, handle, content_type)},
            timeout=600.0,
        )
    return _job_overview(job)


@mcp.tool()
def get_job(job_id: str) -> dict[str, Any]:
    """Get lightweight status/progress metadata without returning the full transcript."""
    job = _api_request("GET", f"/api/jobs/{job_id}")
    return _job_overview(job)


@mcp.tool()
def list_jobs(limit: int = 20) -> list[dict[str, Any]]:
    """List recent SonicBrief transcription jobs without loading full transcript content."""
    safe_limit = max(1, min(int(limit), 200))
    jobs = _api_request("GET", f"/api/jobs?limit={safe_limit}")
    return [_job_overview(job) for job in jobs]


@mcp.tool()
def search_jobs(
    query: str = "",
    creator: str = "",
    source: str = "",
    limit: int = 50,
) -> list[dict[str, Any]]:
    """Search recent SonicBrief history by title, creator/channel, source, URL, or status."""
    needle = query.strip().lower()
    creator_needle = creator.strip().lower()
    source_needle = source.strip().lower()
    candidates = list_jobs(limit=max(1, min(int(limit), 200)))

    matches: list[dict[str, Any]] = []
    for job in candidates:
        haystack = " ".join(
            str(job.get(key) or "")
            for key in ("title", "creator_name", "source_type", "source_url", "status")
        ).lower()
        if needle and needle not in haystack:
            continue
        if creator_needle and creator_needle not in str(job.get("creator_name") or "").lower():
            continue
        if source_needle and source_needle not in str(job.get("source_type") or "").lower():
            continue
        matches.append(job)
    return matches


@mcp.tool()
def get_transcript(
    job_id: str,
    offset: int = 0,
    limit: int = 200,
) -> dict[str, Any]:
    """Return one page of timestamped transcript segments for agent-side analysis or summarization."""
    job = _api_request("GET", f"/api/jobs/{job_id}")
    transcript = job.get("transcript") or []
    safe_offset = max(0, int(offset))
    safe_limit = max(1, min(int(limit), 500))
    page = transcript[safe_offset : safe_offset + safe_limit]
    next_offset = safe_offset + len(page)
    return {
        "job_id": job.get("id"),
        "title": job.get("title"),
        "creator_name": job.get("creator_name"),
        "status": job.get("status"),
        "language": job.get("language"),
        "engine": job.get("engine"),
        "total_segments": len(transcript),
        "offset": safe_offset,
        "returned": len(page),
        "next_offset": next_offset if next_offset < len(transcript) else None,
        "segments": page,
    }


@mcp.tool()
def get_summary(job_id: str) -> dict[str, Any]:
    """Return a stored built-in summary if one was explicitly generated."""
    job = _api_request("GET", f"/api/jobs/{job_id}")
    return {
        "job_id": job.get("id"),
        "title": job.get("title"),
        "status": job.get("status"),
        "summary": job.get("summary"),
        "warnings": job.get("warnings") or [],
        "error": job.get("error"),
    }


@mcp.tool()
def generate_summary_with_gemini(
    job_id: str,
    summary_style: str = "standard",
    summary_instructions: str = "",
    summary_language: str = "zh-CN",
) -> dict[str, Any]:
    """Explicitly send a completed transcript to the user's configured Gemini API and store the returned summary."""
    job = _api_request(
        "POST",
        f"/api/jobs/{job_id}/summaries",
        data=_summary_fields(summary_language, summary_style, summary_instructions),
        timeout=600.0,
    )
    return {
        "job_id": job.get("id"),
        "title": job.get("title"),
        "status": job.get("status"),
        "summary": job.get("summary"),
        "warnings": job.get("warnings") or [],
    }


@mcp.tool()
def cancel_job(job_id: str) -> dict[str, Any]:
    """Cancel a queued or currently processing SonicBrief job."""
    job = _api_request("POST", f"/api/jobs/{job_id}/cancel")
    return _job_overview(job)


@mcp.tool()
def delete_job(job_id: str) -> dict[str, Any]:
    """Delete a SonicBrief job and its stored transcript/summary history."""
    result = _api_request("DELETE", f"/api/jobs/{job_id}")
    return {"job_id": job_id, **result}


if __name__ == "__main__":
    mcp.run()
