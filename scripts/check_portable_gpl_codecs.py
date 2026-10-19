"""Block LGPL-only Windows portable releases with known GPL-associated codec DLLs.

Presence is a release-review blocker, not a legal conclusion about the full app.
"""
from __future__ import annotations

import argparse
from pathlib import Path

BLOCKED_PREFIXES = ("libx264-", "libx265-", "libx264.", "libx265.")


def find_flagged_codecs(root: Path) -> list[str]:
    if not root.is_dir():
        raise FileNotFoundError(f"Portable runtime not found: {root}")
    return sorted(
        item.relative_to(root).as_posix()
        for item in root.rglob("*")
        if item.is_file()
        and item.suffix.lower() in (".dll", ".so", ".dylib")
        and item.name.lower().startswith(BLOCKED_PREFIXES)
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("runtime", type=Path)
    args = parser.parse_args()
    found = find_flagged_codecs(args.runtime)
    if found:
        print("BLOCKED: LGPL-only portable release contains GPL-associated codec libraries:")
        for name in found:
            print("  " + name)
        print("Review/rebuild the PyAV wheel and verify real transcription before releasing.")
        raise SystemExit(1)
    print("PASS: no known libx264/libx265 libraries found; further license review still required.")


if __name__ == "__main__":
    main()
