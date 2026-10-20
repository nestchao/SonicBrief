"""Bounded, configurable startup grace period for SonicBrief's managed backend."""
from __future__ import annotations

import math
import os
from collections.abc import Mapping


def backend_startup_timeout_seconds(
    frozen: bool, environ: Mapping[str, str] | None = None
) -> float:
    """Allow slower first launches in packaged Windows runtimes.

    The source/Docker path retains the old 30-second behavior.
    End users may override the packaged default using the env variable.
    """
    env = os.environ if environ is None else environ
    raw = env.get("SONICBRIEF_BACKEND_STARTUP_TIMEOUT_SECONDS", "").strip()
    if not raw:
        return 90.0 if frozen else 30.0
    try:
        seconds = float(raw)
    except ValueError as exc:
        raise RuntimeError(
            "SONICBRIEF_BACKEND_STARTUP_TIMEOUT_SECONDS must be a number "
            "between 5 and 300."
        ) from exc
    if not math.isfinite(seconds) or not 5.0 <= seconds <= 300.0:
        raise RuntimeError(
            "SONICBRIEF_BACKEND_STARTUP_TIMEOUT_SECONDS must be "
            "between 5 and 300."
        )
    return seconds
