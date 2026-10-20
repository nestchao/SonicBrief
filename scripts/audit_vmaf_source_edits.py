"""Replay the exact pinned BtbN VMAF *source edits* in an isolated directory.

This is engineering provenance evidence, not a full dependency rebuild, and
never constitutes license or corresponding-source clearance.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import tempfile
import zipfile
from pathlib import Path

VMAF_COMMIT = "86da14d0306a138fd3f01319860b905169746516"
VMAF_ZIP_SHA256 = "db4d65e144fa3d3da6317457dd639ad6a91dccd3d33216d80ae1a78dd90ec847"
BTBN_VMAF_RECIPE_SHA256 = "0bd50c0e08e35b9a8326904f51e8f44468abd78e5fc3da6ee4a3e91d788e5e4d"
BTBN_RECIPE_FILE = "scripts.d/45-vmaf.sh"
PATHS = ("libvmaf/src/svm.cpp", "libvmaf/tools/meson.build")
COMMANDS = (
    "echo > libvmaf/tools/meson.build",
    r"sed -i -E 's/([^.>:_[:alnum:]])swap\(/\1libsvm_swap(/g' libvmaf/src/svm.cpp",
    r"sed -i -E 's/([^.>:_[:alnum:]])min\(/\1libsvm_min(/g' libvmaf/src/svm.cpp",
    r"sed -i -E 's/([^.>:_[:alnum:]])max\(/\1libsvm_max(/g' libvmaf/src/svm.cpp",
)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def exact_recipe_commands(text: str) -> tuple[str, ...]:
    candidates = tuple(line.strip() for line in text.splitlines()
                       if line.strip() == COMMANDS[0]
                       or (line.strip().startswith("sed -i -E ")
                           and "libvmaf/src/svm.cpp" in line))
    if candidates != COMMANDS:
        raise ValueError("VMAF script edits differ from reviewed exact BtbN recipe")
    return candidates


def load_pinned_inputs(source_zip: Path, recipes_zip: Path) -> tuple[dict[str, bytes], tuple[str, ...]]:
    if sha(source_zip.read_bytes()) != VMAF_ZIP_SHA256:
        raise ValueError("Pinned VMAF source ZIP SHA-256 mismatch")
    with zipfile.ZipFile(source_zip) as src:
        roots = {name.split("/", 1)[0] for name in src.namelist() if "/" in name}
        if len(roots) != 1 or not next(iter(roots)).endswith(VMAF_COMMIT):
            raise ValueError("Unexpected VMAF pinned source root")
        prefix = next(iter(roots)) + "/"
        payloads = {path: src.read(prefix + path) for path in PATHS}
    with zipfile.ZipFile(recipes_zip) as recipe:
        roots = {name.split("/", 1)[0] for name in recipe.namelist() if "/" in name}
        if len(roots) != 1:
            raise ValueError("Unexpected BtbN recipe root")
        contents = recipe.read(next(iter(roots)) + "/" + BTBN_RECIPE_FILE)
    if sha(contents) != BTBN_VMAF_RECIPE_SHA256:
        raise ValueError("Exact BtbN VMAF recipe changed; do not execute it")
    return payloads, exact_recipe_commands(contents.decode("utf-8"))


def replay(source_zip: Path, recipes_zip: Path, bash: Path) -> dict:
    payloads, commands = load_pinned_inputs(source_zip, recipes_zip)
    if not bash.is_file():
        raise ValueError(f"GNU Bash not found: {bash}")
    # Use the current repo directory to avoid writing outside the project.
    with tempfile.TemporaryDirectory(prefix=".sonicbrief-vmaf-replay-", dir=Path.cwd()) as temp:
        root = Path(temp)
        for relative, payload in payloads.items():
            dest = root / relative
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(payload)
        for command in commands:
            result = subprocess.run(
                [str(bash.resolve()), "-c", command],
                cwd=root, capture_output=True, text=True, encoding="utf-8",
                errors="replace", timeout=30, check=False,
            )
            if result.returncode:
                raise RuntimeError(f"Exact upstream VMAF source edit failed: {result.stderr}")
        changed = []
        for path, before in payloads.items():
            output = (root / path).read_bytes()
            if sha(before) == sha(output):
                raise ValueError(f"Upstream source edit had no effect: {path}")
            changed.append({"path": path, "upstream_sha256": sha(before),
                            "modified_sha256": sha(output)})
        modified = (root / PATHS[0]).read_text(encoding="utf-8", errors="replace")
        if not all("libsvm_" + token + "(" in modified for token in ("swap", "min", "max")):
            raise ValueError("Expected VMAF substitutions absent")
    return {
        "schema": "sonicbrief-vmaf-source-edit-replay-v1",
        "status": "SOURCE EDITS VERIFIED; BINARY/LGPL REDISTRIBUTION NOT CLEARED",
        "source_commit": VMAF_COMMIT,
        "source_zip_sha256": VMAF_ZIP_SHA256,
        "recipe_file": BTBN_RECIPE_FILE,
        "recipe_sha256": BTBN_VMAF_RECIPE_SHA256,
        "commands": list(commands),
        "changed_files": changed,
        "limitations": [
            "This replays source edits; does not build or match BtbN's released binaries.",
            "The recipe's compiled VMAF configuration and linker steps are not replayed.",
            "Corresponding source, static linking, notices and LGPL obligations remain open.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vmaf-source-zip", required=True, type=Path)
    parser.add_argument("--btbn-recipes-zip", required=True, type=Path)
    parser.add_argument("--bash", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    report = replay(args.vmaf_source_zip, args.btbn_recipes_zip, args.bash)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("VMAF_SOURCE_REPLAY_PASS changed_files=" + str(len(report["changed_files"])))


if __name__ == "__main__":
    main()
