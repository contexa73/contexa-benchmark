"""Proves that the benchmark packages on disk are the published ones, file by file (plan P1, item BI-4).

Every installed Python package has a RECORD file in its dist-info folder that lists each installed file with the SHA-256 the
installer wrote. This hashes the files now and compares. A package whose files all match was not edited after it was installed
from the index. For InjecAgent (a git checkout, not an installed package) the check is `git status` and the commit.

    python verify_packages.py            prints the result and writes ../packages-verified.json
"""
from __future__ import annotations

import base64
import csv
import hashlib
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / ".venv" / "Lib" / "site-packages"
PACKAGES = ["agentdojo", "anthropic", "openai", "nltk", "pydantic", "tenacity"]


def verify(package: str) -> dict:
    matches = sorted(SITE.glob(f"{package}-*.dist-info"))
    if not matches:
        return {"package": package, "found": False}
    info = matches[0]
    record = info / "RECORD"
    checked = bad = missing = 0
    changed: list[str] = []
    with record.open(encoding="utf-8", newline="") as handle:
        for name, digest, _size in csv.reader(handle):
            if not digest or name.endswith((".pyc", ".dist-info/RECORD")) or name.startswith(".."):
                continue
            path = SITE / name
            if not path.exists():
                missing += 1
                continue
            algorithm, _, expected = digest.partition("=")
            actual = base64.urlsafe_b64encode(hashlib.new(algorithm, path.read_bytes()).digest()).rstrip(b"=").decode()
            checked += 1
            if actual != expected:
                bad += 1
                changed.append(name)
    version = info.name[len(package) + 1: -len(".dist-info")]
    return {"package": package, "found": True, "version": version, "files_checked": checked, "files_changed": bad,
            "files_missing": missing, "changed": changed[:10]}


def injecagent() -> dict:
    repo = ROOT / "third_party" / "InjecAgent"
    commit = subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    status = subprocess.run(["git", "-C", str(repo), "status", "--porcelain"], capture_output=True, text=True).stdout.strip()
    return {"package": "InjecAgent (git checkout)", "commit": commit, "modified_files": status.splitlines()}


def main() -> None:
    results = [verify(p) for p in PACKAGES]
    results.append(injecagent())
    clean = all(r.get("files_changed", 0) == 0 and r.get("files_missing", 0) == 0 and r.get("found", True) for r in results[:-1])
    clean = clean and not results[-1]["modified_files"]
    report = {"checked_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "clean": clean, "results": results}
    (ROOT / "packages-verified.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    for r in results:
        if "files_checked" in r:
            print(f"{r['package']:12s} {r['version']:10s} 파일 {r['files_checked']:5d}개 확인, 변경 {r['files_changed']}, 없음 {r['files_missing']}")
        elif r.get("found") is False:
            print(f"{r['package']:12s} 설치 정보 없음")
        else:
            print(f"{r['package']} 커밋 {r['commit'][:8]}, 수정된 파일 {len(r['modified_files'])}개")
    print("공식 패키지 무수정:", "예" if clean else "아니오")
    sys.exit(0 if clean else 1)


if __name__ == "__main__":
    main()
