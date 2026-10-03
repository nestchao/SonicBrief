from __future__ import annotations

import json
import sqlite3
import threading
import uuid
from datetime import datetime, timezone
from typing import Any

from config import DATABASE_PATH, ensure_directories

_WRITE_LOCK = threading.Lock()


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def connect() -> sqlite3.Connection:
    connection = sqlite3.connect(DATABASE_PATH, timeout=30)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA foreign_keys=ON")
    return connection


def initialize() -> None:
    ensure_directories()
    with connect() as connection:
        connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS jobs (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                source_type TEXT NOT NULL,
                source_url TEXT,
                status TEXT NOT NULL,
                progress INTEGER NOT NULL DEFAULT 0,
                stage TEXT NOT NULL DEFAULT 'acquire',
                stage_detail TEXT,
                stage_progress INTEGER NOT NULL DEFAULT 0,
                processed_duration REAL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                duration REAL,
                language TEXT,
                model_name TEXT NOT NULL,
                engine TEXT,
                diarization_enabled INTEGER NOT NULL DEFAULT 0,
                transcript_json TEXT,
                summary TEXT,
                error TEXT,
                warnings_json TEXT NOT NULL DEFAULT '[]'
            );

            CREATE TABLE IF NOT EXISTS summaries (
                id TEXT PRIMARY KEY,
                job_id TEXT NOT NULL,
                content TEXT NOT NULL,
                language TEXT NOT NULL,
                style TEXT NOT NULL,
                model TEXT,
                created_at TEXT NOT NULL,
                FOREIGN KEY(job_id) REFERENCES jobs(id) ON DELETE CASCADE
            );
            CREATE INDEX IF NOT EXISTS idx_jobs_updated_at ON jobs(updated_at DESC);
            CREATE INDEX IF NOT EXISTS idx_summaries_job_id ON summaries(job_id, created_at DESC);
            """
        )
        existing_columns = {row[1] for row in connection.execute("PRAGMA table_info(jobs)").fetchall()}
        migrations = [
            ("stage_detail", "ALTER TABLE jobs ADD COLUMN stage_detail TEXT"),
            ("stage_progress", "ALTER TABLE jobs ADD COLUMN stage_progress INTEGER NOT NULL DEFAULT 0"),
            ("processed_duration", "ALTER TABLE jobs ADD COLUMN processed_duration REAL"),
        ]
        for column, statement in migrations:
            if column not in existing_columns:
                connection.execute(statement)


def create_job(
    *, title: str, source_type: str, source_url: str | None,
    model_name: str, diarization_enabled: bool,
) -> dict[str, Any]:
    job_id = uuid.uuid4().hex
    now = utc_now()
    with _WRITE_LOCK, connect() as connection:
        connection.execute(
            """INSERT INTO jobs (
                id, title, source_type, source_url, status, progress, stage,
                created_at, updated_at, model_name, diarization_enabled
            ) VALUES (?, ?, ?, ?, 'queued', 0, 'acquire', ?, ?, ?, ?)""",
            (job_id, title, source_type, source_url, now, now, model_name, int(diarization_enabled)),
        )
    return get_job(job_id)


def update_job(job_id: str, **fields: Any) -> None:
    allowed = {
        "title", "status", "progress", "stage", "stage_detail", "stage_progress", "processed_duration", "duration", "language", "engine",
        "transcript_json", "summary", "error", "warnings_json",
    }
    updates: list[str] = []
    values: list[Any] = []
    for key, value in fields.items():
        if key not in allowed:
            raise ValueError(f"Unsupported job field: {key}")
        if key in {"transcript_json", "warnings_json"} and not isinstance(value, str):
            value = json.dumps(value, ensure_ascii=False)
        updates.append(f"{key} = ?")
        values.append(value)
    if not updates:
        return
    updates.append("updated_at = ?")
    values.extend([utc_now(), job_id])
    with _WRITE_LOCK, connect() as connection:
        connection.execute(f"UPDATE jobs SET {', '.join(updates)} WHERE id = ?", values)


def add_warning(job_id: str, message: str) -> None:
    job = get_job(job_id)
    warnings = list(job.get("warnings") or [])
    if message not in warnings:
        warnings.append(message)
        update_job(job_id, warnings_json=warnings)


def save_summary(job_id: str, content: str, language: str, style: str, model: str | None) -> None:
    with _WRITE_LOCK, connect() as connection:
        connection.execute(
            "INSERT INTO summaries (id, job_id, content, language, style, model, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            (uuid.uuid4().hex, job_id, content, language, style, model, utc_now()),
        )
        connection.execute(
            "UPDATE jobs SET summary = ?, updated_at = ? WHERE id = ?",
            (content, utc_now(), job_id),
        )


def _row_to_job(row: sqlite3.Row, include_content: bool = True) -> dict[str, Any]:
    job = dict(row)
    job["diarization_enabled"] = bool(job["diarization_enabled"])
    job["warnings"] = json.loads(job.pop("warnings_json") or "[]")
    transcript_json = job.pop("transcript_json")
    if include_content:
        job["transcript"] = json.loads(transcript_json or "[]")
    else:
        job.pop("summary", None)
    return job


def get_job(job_id: str) -> dict[str, Any]:
    with connect() as connection:
        row = connection.execute("SELECT * FROM jobs WHERE id = ?", (job_id,)).fetchone()
    if row is None:
        raise KeyError(job_id)
    return _row_to_job(row)


def list_jobs(limit: int = 50) -> list[dict[str, Any]]:
    with connect() as connection:
        rows = connection.execute(
            "SELECT * FROM jobs ORDER BY updated_at DESC LIMIT ?", (max(1, min(limit, 200)),)
        ).fetchall()
    return [_row_to_job(row, include_content=False) for row in rows]


def delete_job(job_id: str) -> bool:
    with _WRITE_LOCK, connect() as connection:
        cursor = connection.execute("DELETE FROM jobs WHERE id = ?", (job_id,))
    return cursor.rowcount > 0
