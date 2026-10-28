"""Evidence that the guard ran in a clean condition during one stage, counted from its own log and its decision records.

  vector store failures   decisions the guard could not store in its vector memory (the embedding server was too slow or down);
                          the engine's memory of earlier decisions is then incomplete
  model retries           'Retry error' lines of the model providers (rate limits, quota, outages)
  technical fallbacks     decisions the engine took by its fallback because analysis failed (decision records)
  embedding server        which Ollama server received the embedding requests (the guard must not share the victim's server)

    python check_guard_health.py --since 2026-10-28T10:05 --decisions ../runs/dev-iso-llm/contexa-decisions.jsonl
`--since` is UTC (the guard log is in UTC). Exit status 1 when anything above is not zero.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--since", required=True, help="UTC time, YYYY-MM-DDTHH:MM")
    parser.add_argument("--until", default="9999-12-31T00:00")
    parser.add_argument("--log", type=Path, default=ROOT / "guard" / "logs" / "guard-C2.log")
    parser.add_argument("--decisions", type=Path, default=None)
    parser.add_argument("--embed-log", type=Path, default=ROOT / "guard" / "logs" / "ollama-embed-11435.log")
    args = parser.parse_args()

    stamp = ""
    failures = retries = 0
    for line in args.log.read_text(encoding="utf-8", errors="replace").splitlines():
        found = re.match(r"(\d{4}-\d\d-\d\dT\d\d:\d\d)", line)
        if found:
            stamp = found.group(1)
        if not (args.since <= stamp < args.until):
            continue
        if re.match(r"\d{4}-", line) and "store document failed" in line:
            failures += 1
        if re.match(r"\d{4}-", line) and "Retry error" in line:
            retries += 1
    fallbacks = unresolved = decisions = 0
    if args.decisions and args.decisions.exists():
        for line in args.decisions.read_text(encoding="utf-8").splitlines():
            if line.strip():
                row = json.loads(line)
                decisions += 1
                detail = row.get("detail") or {}
                fallbacks += 1 if detail.get("technicalFallback") else 0
                unresolved += 1 if detail.get("unresolved") else 0
    embeds = None
    if args.embed_log.exists():
        embeds = sum(1 for line in args.embed_log.read_text(encoding="utf-8", errors="replace").splitlines() if "/api/embed" in line)

    print(f"window from {args.since} UTC")
    print(f"vector store failures: {failures}")
    print(f"model provider retries: {retries}")
    print(f"decisions {decisions}: technical fallbacks {fallbacks}, flagged unresolved {unresolved} (a challenge nobody confirmed counts here)")
    print("embedding requests served by the separate server (11435): " + ("log not found" if embeds is None else str(embeds)))
    sys.exit(1 if failures or retries or fallbacks else 0)


if __name__ == "__main__":
    main()
