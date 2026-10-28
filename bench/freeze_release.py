"""Freezes the conditions of a release before an official run, and checks a later state against a freeze.

    python freeze_release.py create dev-v1                 writes ../releases/freeze-dev-v1.json (never overwrites a freeze)
    python freeze_release.py verify ../releases/freeze-dev-v1.json    compares the present state with the freeze

A freeze records, as hashes: the core commit and every changed or new file of its working tree, the decision path (the files of
declarations/decision-path.json), the prompt bundle, the tool classification, the owner prompt, the Layer 2 substitution criteria,
the split, the guard jar, the requirements, the package proof, the budget, and the models (victim image id, judge models).
A result file records the sha256 of its freeze file (runner.py reads the path from BENCH_FREEZE). `verify` says which sections differ:
if the decision path hash is the same, results of that freeze stay valid whatever else changed (plan 4.1).
"""
from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RELEASES = ROOT / "releases"


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def combine(pairs: list[tuple[str, str]]) -> str:
    digest = hashlib.sha256()
    for name, value in sorted(pairs):
        digest.update(f"{name}\0{value}\n".encode("utf-8"))
    return digest.hexdigest()


def git(core: Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(core), *args], check=True, capture_output=True, text=True, encoding="utf-8").stdout


def core_state(core: Path) -> dict:
    changed = [line for line in git(core, "ls-files", "-m", "-o", "--exclude-standard").splitlines() if line]
    pairs = []
    for relative in changed:
        path = core / relative
        pairs.append((relative, sha256_file(path) if path.is_file() else "deleted"))
    return {"commit": git(core, "rev-parse", "HEAD").strip(), "changed_or_new_files": len(changed),
            "working_tree_hash": combine(pairs)}


def glob_files(core: Path, patterns: list[str]) -> list[Path]:
    found = set()
    for pattern in patterns:
        prefix = pattern.split("**")[0].rstrip("/")
        base = core / prefix
        if not base.exists():
            raise SystemExit(f"decision path: '{prefix}' does not exist under {core}; fix declarations/decision-path.json")
        suffix = pattern.split("**/")[-1] if "**/" in pattern else "*"
        for path in base.rglob("*"):
            if path.is_file() and fnmatch.fnmatch(path.name, suffix):
                found.add(path)
    return sorted(found)


def decision_path(core: Path, patterns: list[str]) -> dict:
    files = glob_files(core, patterns)
    return {"files": len(files), "hash": combine([(p.relative_to(core).as_posix(), sha256_file(p)) for p in files])}


def ollama_model(name: str) -> dict:
    try:
        out = subprocess.run(["ollama", "list"], check=True, capture_output=True, text=True, encoding="utf-8").stdout
    except (OSError, subprocess.CalledProcessError):
        return {"name": name, "image_id": None}
    for line in out.splitlines():
        parts = line.split()
        if parts and parts[0] in (name, f"{name}:latest"):
            return {"name": name, "image_id": parts[1]}
    return {"name": name, "image_id": None}


def ollama_server() -> dict:
    """The settings of the Ollama server that shape what the victim agent sees: how many requests run at once and how many
    tokens each may hold. They are part of the test conditions (a prompt longer than the context is cut)."""
    try:
        version = subprocess.run(["ollama", "--version"], check=True, capture_output=True, text=True,
                                 encoding="utf-8").stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        version = None
    names = ("OLLAMA_NUM_PARALLEL", "OLLAMA_MAX_LOADED_MODELS", "OLLAMA_CONTEXT_LENGTH", "OLLAMA_FLASH_ATTENTION")
    return {"version": version, **{name: os.environ.get(name) for name in names}}


def read_env_defaults(path: Path) -> dict:
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.match(r"^([A-Z_0-9]+)=(.*)$", line.strip())
        if match:
            values[match.group(1)] = match.group(2)
    return values


def snapshot(victim: str, layer2: str) -> dict:
    declaration = json.loads((ROOT / "declarations" / "decision-path.json").read_text(encoding="utf-8"))
    core = Path(declaration["core_root"])
    env = read_env_defaults(ROOT / "guard" / "llm-anthropic.env")
    bundle = os.environ.get("BENCH_PROMPT_BUNDLE", "2026.10.27-v21.17")
    manifest = core / "contexa-core/src/main/resources/prompt-bundles/manifests/cortex.security-decision" / f"{bundle}.json"
    jar = ROOT / "guard" / "build" / "libs" / "contexa-bench-guard-0.1.0.jar"
    declarations = {p.name: sha256_file(p) for p in sorted((ROOT / "declarations").glob("*")) if p.is_file()}
    proof = ROOT / "packages-verified.json"
    return {
        "core": core_state(core),
        "decision_path": {"patterns": declaration["include"], **decision_path(core, declaration["include"])},
        "prompt_bundle": {"active": bundle, "manifest_sha256": sha256_file(manifest) if manifest.exists() else None},
        "declarations": declarations,
        "split": {"file": "panels/split-v2.json", "sha256": sha256_file(ROOT / "panels" / "split-v2.json")},
        "guard": {"jar_sha256": sha256_file(jar) if jar.exists() else None,
                  "application_yml_sha256": sha256_file(ROOT / "guard/src/main/resources/application.yml")},
        "requirements_sha256": sha256_file(ROOT / "requirements.lock.txt"),
        "package_proof": {"sha256": sha256_file(proof) if proof.exists() else None},
        "budget_sha256": sha256_file(ROOT / "budget.json"),
        "models": {"victim_agent": ollama_model(victim), "victim_base": ollama_model("qwen2.5:7b"),
                   "layer1": env.get("BENCH_LAYER1_MODEL"), "layer2": layer2,
                   "owner": "claude-haiku-5-5", "embedding": env.get("BENCH_OLLAMA_EMBEDDING_MODEL")},
        "ollama_server": ollama_server(),
    }


def create(release: str, victim: str, layer2: str) -> None:
    RELEASES.mkdir(exist_ok=True)
    target = RELEASES / f"freeze-{release}.json"
    if target.exists():
        raise SystemExit(f"{target.name} exists: a freeze is never overwritten; name a new release")
    state = snapshot(victim, layer2)
    state.update({"release": release, "frozen_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")})
    target.write_text(json.dumps(state, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {target}  sha256 {sha256_file(target)[:16]}…")
    print(f"core {state['core']['commit'][:8]} + {state['core']['changed_or_new_files']} changed/new files; "
          f"decision path {state['decision_path']['files']} files, hash {state['decision_path']['hash'][:12]}")


def flatten(value, prefix="") -> dict:
    out = {}
    if isinstance(value, dict):
        for key, item in value.items():
            out.update(flatten(item, f"{prefix}{key}."))
    else:
        out[prefix.rstrip(".")] = value
    return out


def verify(freeze_path: Path) -> int:
    frozen = json.loads(freeze_path.read_text(encoding="utf-8"))
    now = snapshot(frozen["models"]["victim_agent"]["name"], frozen["models"]["layer2"])
    before, after = flatten(frozen), flatten(now)
    changed = sorted(k for k in after if before.get(k) != after[k] and not k.startswith("decision_path.patterns"))
    if not changed:
        print("unchanged: the present state equals the freeze")
        return 0
    for key in changed:
        print(f"differs: {key}")
    if "decision_path.hash" not in changed:
        print("decision path hash is the SAME: results of this freeze stay valid (plan 4.1); the other differences are outside the decision path")
        return 0
    print("decision path hash DIFFERS: results of this freeze do not describe the present guard; a new release is needed")
    return 3


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    make = sub.add_parser("create")
    make.add_argument("release")
    make.add_argument("--victim", default="qwen2.5-7b-16k-p2k")
    make.add_argument("--layer2", default="claude-sonnet-5-5")
    check = sub.add_parser("verify")
    check.add_argument("freeze", type=Path)
    args = parser.parse_args()
    if args.command == "create":
        create(args.release, args.victim, args.layer2)
    else:
        sys.exit(verify(args.freeze))


if __name__ == "__main__":
    main()
