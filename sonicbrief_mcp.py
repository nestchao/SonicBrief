from __future__ import annotations

import mimetypes
import os
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import httpx
from mcp.server import MCPServer

API_BASE = os.getenv("SONICBRIEF_API_BASE", "http://127.0.0.1:7860").rstrip("/")
_PARSED_API_BASE = urlparse(API_BASE)
if _PARSED_API_BASE.hostname not in {"127.0.0.1", "localhost", "::1"}:
    raise RuntimeError(
        "SONICBRIEF_API_BASE must point to a loopback address so the MCP bridge stays local."
    )

mcp = MCPServer("SonicBrief")

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


def _api_request(
    method: str,
    path: str,
    *,
    data: dict[str, Any] | None = None,
    files: dict[str, Any] | None = None,
    timeout: float = 120.0,
) -> Any:
    try:
        with httpx.Client(base_url=API_BASE, timeout=timeout) as client:
            response = client.request(method, path, data=data, files=files)
    except httpx.ConnectError as exc:
        raise RuntimeError(
            "SonicBrief backend is not running. Start SonicBrief with start.bat "
            "or run backend/app.py locally, then try the MCP tool again."
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


@mcp.tool()
def get_health() -> dict[str, Any]:
    """Check the local SonicBrief backend, Whisper/GPU status, and optional Gemini/diarization setup."""
    health = _api_request("GET", "/api/health")
    return {
        **health,
        "backend_url": API_BASE,
        "processing_location": "local computer",
        "whisper_location": "local SonicBrief backend",
        "mcp_transport": "stdio",
    }


def _submit_media_url(
    url: str,
    language: str,
    whisper_model: str,
    summary_style: str,
    summary_instructions: str,
    summary_language: str,
    identify_speakers: bool,
) -> dict[str, Any]:
    payload = {
        "url": url,
        "language": language,
        "model_name": whisper_model,
        "diarize": str(identify_speakers).lower(),
        **_summary_fields(summary_language, summary_style, summary_instructions),
    }
    job = _api_request("POST", "/api/jobs/url", data=payload)
    return _job_overview(job)


@mcp.tool()
def submit_media_url(
    url: str,
    language: str = "auto",
    whisper_model: str = "turbo",
    summary_style: str = "standard",
    summary_instructions: str = "",
    summary_language: str = "zh-CN",
    identify_speakers: bool = False,
) -> dict[str, Any]:
    """Queue one supported public media URL for local download, Whisper transcription, and summary generation."""
    return _submit_media_url(
        url,
        language,
        whisper_model,
        summary_style,
        summary_instructions,
        summary_language,
        identify_speakers,
    )


@mcp.tool()
def submit_media_urls(
    urls: list[str],
    language: str = "auto",
    whisper_model: str = "turbo",
    summary_style: str = "standard",
    summary_instructions: str = "",
    summary_language: str = "zh-CN",
    identify_speakers: bool = False,
) -> dict[str, Any]:
    """Queue up to 20 media URLs in order. Each URL becomes its own SonicBrief job and summary."""
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
                language,
                whisper_model,
                summary_style,
                summary_instructions,
                summary_language,
                identify_speakers,
            )
            queued.append({"queue_position": index, "url": url, "job": job})
        except Exception as exc:
            queued.append({"queue_position": index, "url": url, "error": str(exc)})

    return {
        "submitted": len(cleaned),
        "queued": sum(1 for item in queued if "job" in item),
        "items": queued,
        "note": "The SonicBrief backend uses one worker, so successfully submitted jobs run in queue order.",
    }


@mcp.tool()
def submit_local_file(
    path: str,
    language: str = "auto",
    whisper_model: str = "turbo",
    summary_style: str = "standard",
    summary_instructions: str = "",
    summary_language: str = "zh-CN",
    identify_speakers: bool = False,
) -> dict[str, Any]:
    """Queue a local audio/video file that is accessible to this MCP process."""
    media_path = Path(path).expanduser().resolve()
    if not media_path.is_file():
        raise ValueError(f"Local media file does not exist: {media_path}")

    content_type = mimetypes.guess_type(media_path.name)[0] or "application/octet-stream"
    payload = {
        "language": language,
        "model_name": whisper_model,
        "diarize": str(identify_speakers).lower(),
        **_summary_fields(summary_language, summary_style, summary_instructions),
    }
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
    """Get lightweight status/progress metadata for one SonicBrief job without returning its full transcript."""
    job = _api_request("GET", f"/api/jobs/{job_id}")
    return _job_overview(job)


@mcp.tool()
def list_jobs(limit: int = 20) -> list[dict[str, Any]]:
    """List recent SonicBrief jobs without loading full transcript or summary content."""
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
def get_summary(job_id: str) -> dict[str, Any]:
    """Return the generated summary for a job, together with status and warnings."""
    job = _api_request("GET", f"/api/jobs/{job_id}")
    return {
        "job_id": job.get("id"),
        "title": job.get("title"),
        "status": job.get("status"),
        "summary": job.get("summary"),
        "summary_language": job.get("summary_language"),
        "summary_style": job.get("summary_style"),
        "summary_model": job.get("summary_model"),
        "warnings": job.get("warnings") or [],
        "error": job.get("error"),
    }


@mcp.tool()
def get_transcript(
    job_id: str,
    offset: int = 0,
    limit: int = 200,
) -> dict[str, Any]:
    """Return one page of timestamped transcript segments so long transcripts do not flood agent context."""
    job = _api_request("GET", f"/api/jobs/{job_id}")
    transcript = job.get("transcript") or []
    safe_offset = max(0, int(offset))
    safe_limit = max(1, min(int(limit), 500))
    page = transcript[safe_offset : safe_offset + safe_limit]
    next_offset = safe_offset + len(page)
    return {
        "job_id": job.get("id"),
        "title": job.get("title"),
        "status": job.get("status"),
        "total_segments": len(transcript),
        "offset": safe_offset,
        "returned": len(page),
        "next_offset": next_offset if next_offset < len(transcript) else None,
        "segments": page,
    }


@mcp.tool()
def regenerate_summary(
    job_id: str,
    summary_style: str = "standard",
    summary_instructions: str = "",
    summary_language: str = "zh-CN",
) -> dict[str, Any]:
    """Regenerate a completed job summary using Brief, Standard, Detailed, Study Notes, Key Points, or Custom."""
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
        "summary_language": job.get("summary_language"),
        "summary_style": job.get("summary_style"),
        "summary_model": job.get("summary_model"),
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
