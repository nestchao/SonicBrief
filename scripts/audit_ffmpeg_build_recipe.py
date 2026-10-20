"""Cross-reference FFmpeg build configuration against pinned BtbN recipe sources.

This is a *triage aid*, NOT proof that individual recipe stages ran, an SBOM,
a legal opinion, or evidence that corresponding source is complete.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path

ENABLE_RE = re.compile(r"(?<!\w)--enable-([a-z0-9_-]+)\b")
CONFIGURE_FUNCTION_RE = re.compile(r"(?ms)^ffbuild_configure\(\)\s*\{(.*?)^\}")
VARIABLE_RE = re.compile(
    r'^\s*(SCRIPT_REPO|SCRIPT_COMMIT|SCRIPT_REV|SCRIPT_TAG|SCRIPT_VERSION)='
    r'["\']([^"\']+)["\']',
    re.MULTILINE,
)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def inspect_recipe_archive(archive_path: Path) -> tuple[dict[str, list[dict]], list[str]]:
    with zipfile.ZipFile(archive_path) as archive:
        names = archive.namelist()
        roots = {name.split("/", 1)[0] for name in names if "/" in name}
        if len(roots) != 1:
            raise ValueError("Unexpected build recipes root layout")
        root = next(iter(roots)) + "/"
        files = {name[len(root):]: name for name in names if name.startswith(root)}
        required = (
            "variants/win64-lgpl-shared.sh",
            "variants/defaults-lgpl-shared.sh",
            "variants/defaults-lgpl.sh",
            "build.sh",
        )
        for item in required:
            if item not in files:
                raise ValueError(f"Missing BtbN source file {item}")
        recipes: dict[str, list[dict]] = {}
        for relative, full in sorted(files.items()):
            if not (relative.startswith("scripts.d/") and relative.endswith(".sh")):
                continue
            source = archive.read(full).decode("utf-8", "replace")
            # Inspect only FFmpeg's configure hook. Other build stages may
            # configure entirely different libraries with the same flags.
            configure_hooks = CONFIGURE_FUNCTION_RE.findall(source)
            selectors = sorted(set(
                flag for hook in configure_hooks for flag in ENABLE_RE.findall(hook)
            ))
            if not selectors:
                continue
            declarations = dict(VARIABLE_RE.findall(source))
            data = {
                "recipe_file": relative,
                "source_repo": declarations.get("SCRIPT_REPO"),
                "source_commit": declarations.get("SCRIPT_COMMIT"),
                "source_revision": declarations.get("SCRIPT_REV"),
                "source_tag": declarations.get("SCRIPT_TAG"),
                "source_version": declarations.get("SCRIPT_VERSION"),
                "conditional_build": "ffbuild_enabled()" in source,
            }
            for selector in selectors:
                recipes.setdefault(selector, []).append(data)
        return recipes, sorted(files)


def inspect_ffmpeg_flags(binary: Path) -> list[str]:
    result = subprocess.run(
        [str(binary.resolve()), "-hide_banner", "-buildconf"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=30,
        check=True,
    )
    return sorted(set(ENABLE_RE.findall(result.stdout + result.stderr)))


def audit(ffmpeg_binary: Path, recipes_zip: Path) -> dict:
    flags = inspect_ffmpeg_flags(ffmpeg_binary)
    if not flags:
        raise ValueError("No FFmpeg --enable flags detected")
    recipes, files = inspect_recipe_archive(recipes_zip)
    pinned_required = {
        "variants/win64-lgpl-shared.sh",
        "variants/defaults-lgpl-shared.sh",
        "variants/defaults-lgpl.sh",
    }
    with zipfile.ZipFile(recipes_zip) as archive:
        root = archive.namelist()[0]
        contents = {
            rel: archive.read(root + rel).decode("utf-8", "replace")
            for rel in pinned_required
        }
        for rel in pinned_required:
            if rel not in files:
                raise ValueError(f"Missing {rel}")
    combined = "\n".join(contents.values())
    if "COPYING.LGPLv3" not in combined or "--enable-shared --disable-static" not in combined:
        raise ValueError("Expected LGPLv3 shared-build declarations not present")
    variant_flags = set(ENABLE_RE.findall(combined))
    entries = []
    for flag in flags:
        recipe_candidates = recipes.get(flag, [])
        entries.append({
            "enabled_flag": "--enable-" + flag,
            "recipe_candidates": recipe_candidates,
            "declared_in_lgpl_variant": flag in variant_flags,
            "source_revisions_declared": bool(recipe_candidates)
                and all(any(r.get(k) for k in ("source_commit", "source_revision", "source_tag", "source_version")) for r in recipe_candidates),
            "interpretation": (
                "possible BtbN recipe link; must verify actual build execution and license"
                if recipe_candidates else
                "declared by selected LGPL variant"
                if flag in variant_flags else
                "no recipe/variant flag hint; investigate build conditions"
            ),
        })
    return {
        "schema": "sonicbrief-ffmpeg-recipe-evidence-v1",
        "ffmpeg_binary_sha256": sha256_file(ffmpeg_binary),
        "btbn_recipes_sha256": sha256_file(recipes_zip),
        "expected_variant": "win64-lgpl-shared",
        "declared_license": "LGPLv3",
        "declared_linking": "FFmpeg shared DLLs (external components may be static)",
        "enabled_flag_count": len(flags),
        "matching_recipe_flag_count": sum(bool(e["recipe_candidates"]) for e in entries),
        "variant_flag_count": sum(bool(e["declared_in_lgpl_variant"]) for e in entries),
        "unmatched_flag_count": sum(not (e["recipe_candidates"] or e["declared_in_lgpl_variant"]) for e in entries),
        "flags": entries,
        "disclaimer": (
            "Static inspection; the archive may contain disabled/conditional/unused stages. "
            "Actual build trace, complete transitive source, source modifications, "
            "relinkability and third-party licenses remain subject to review. "
            "Do not treat this as redistribution approval."
        ),
    }


def make_markdown(data: dict) -> str:
    lines = [
        "# FFmpeg to BtbN recipe comparison — engineering audit",
        "",
        "**Status: INCOMPLETE; NOT CLEARED FOR PUBLIC BINARY DISTRIBUTION.**",
        "",
        f"- Variant: `{data['expected_variant']}` (declares {data['declared_license']}, FFmpeg shared)",
        f"- Actual FFmpeg flags: {data['enabled_flag_count']}",
        f"- With matching recipe hints: {data['matching_recipe_flag_count']}",
        f"- Declared by LGPL variant: {data['variant_flag_count']}",
        f"- Without matching recipe or variant hints: {data['unmatched_flag_count']}",
        f"- BtbN source ZIP SHA-256: `{data['btbn_recipes_sha256']}`",
        f"- FFmpeg binary SHA-256: `{data['ffmpeg_binary_sha256']}`",
        "",
        "A recipe reference is only a **candidate**: it does not prove the stage executed "
        "or that its license, dependency sources, and linking obligations are satisfied.",
        "",
        "| FFmpeg flag | Matching recipe(s) | Declared external revision(s) |",
        "|---|---|---|",
    ]
    for entry in data["flags"]:
        candidates = entry["recipe_candidates"]
        names = (", ".join(c["recipe_file"] for c in candidates)
                 or ("LGPL variant" if entry.get("declared_in_lgpl_variant") else "**none**"))
        versions = ", ".join(
            c["source_commit"] or c["source_revision"] or c["source_tag"] or c["source_version"] or "missing"
            for c in candidates
        ) or "not matched"
        lines.append(f"| `{entry['enabled_flag']}` | {names} | {versions} |")
    lines.extend([
        "",
        "## Items not resolved by this automated comparison",
        "- Confirm every enabled external library's license (including static linking), exact source and patches.",
        "- Recover the actual CI/build trace and full build command, not merely plausible source recipes.",
        "- Validate corresponding-source archives and LGPL replacement/relinking rights for both FFmpeg DLL sets.",
        "- Audit non-FFmpeg native wheel dependencies separately.",
        "",
        data["disclaimer"],
        "",
    ])
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ffmpeg", type=Path, required=True)
    parser.add_argument("--recipes", type=Path, required=True)
    parser.add_argument("--json", type=Path, required=True)
    parser.add_argument("--markdown", type=Path, required=True)
    args = parser.parse_args()
    report = audit(args.ffmpeg, args.recipes)
    for dest, content in (
        (args.json, json.dumps(report, indent=2) + "\n"),
        (args.markdown, make_markdown(report)),
    ):
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(content, encoding="utf-8")
    print(
        f"FLAGS={report['enabled_flag_count']} "
        f"RECIPE_HINTS={report['matching_recipe_flag_count']} "
        f"VARIANT={report['variant_flag_count']} "
        f"UNMATCHED={report['unmatched_flag_count']} "
        "CLEARANCE=NOT_CLEARED"
    )


if __name__ == "__main__":
    main()
