"""Teaches the guard what the agent usually does, from the agent's own successful ordinary work (stage C3 of plan 13.11).

A deployed agent has a history before an attack: the requests of the days it has worked. The benchmark has none, so this
replays a fixed set of ordinary runs through the guard before the evaluation: the user's request as the delegation's
objective and the calls the unguarded agent made when it solved the task, and the engine learns what it allows.

Fair-play (plan 13.11, rule 3): only ordinary runs (no attack anywhere in them) of user tasks that are NOT evaluated
afterwards are replayed, and only the ones the agent solved. For the development panel those are the tasks outside it.

    python warmup.py --suite banking --panel ../panels/dev-v1.json \
        --source ../runs/baseline-gpt4omini-benign --pipeline gpt-4o-mini-2024-07-18
"""
from __future__ import annotations

import argparse
import json
import uuid
from pathlib import Path

from guards import HttpGuard
from records import begin_payload_extras


def trajectories(source: Path, pipeline: str, suite: str, excluded: set[str]) -> list[dict]:
    found = []
    for path in sorted((source / pipeline / suite).glob("user_task_*/none/none.json")):
        log = json.loads(path.read_text(encoding="utf-8"))
        task = log["user_task_id"]
        if task in excluded or not log.get("utility") or log.get("error"):
            continue
        goal = next((m["content"][0]["content"] if isinstance(m["content"], list) else m["content"]
                     for m in log["messages"] if m["role"] == "user"), "")
        calls = [{"tool": c["function"], "arguments": c["args"]}
                 for m in log["messages"] if m["role"] == "assistant" for c in (m.get("tool_calls") or [])]
        if goal and calls:
            found.append({"task": task, "goal": goal, "calls": calls})
    return found


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--suite", required=True)
    parser.add_argument("--panel", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True, help="run folder of the ordinary (benign) baseline")
    parser.add_argument("--pipeline", required=True)
    parser.add_argument("--guard-url", default="http://127.0.0.1:19300")
    parser.add_argument("--tier", default="C2")
    parser.add_argument("--limit", type=int, default=0, help="at most this many runs (0: all)")
    args = parser.parse_args()

    panel = json.loads(args.panel.read_text(encoding="utf-8"))
    excluded = set(panel["suites"][args.suite]["user_tasks"])
    runs = trajectories(args.source, args.pipeline, args.suite, excluded)
    if args.limit:
        runs = runs[: args.limit]
    guard = HttpGuard(args.guard_url)
    allowed = refused = 0
    for run in runs:
        run_id = "warm-" + uuid.uuid4().hex[:10]
        guard.begin(run_id, run["goal"], args.suite, {"tier": args.tier, **begin_payload_extras(args.suite)})
        try:
            for call in run["calls"]:
                verdict = guard.decide(run_id, call["tool"], call["arguments"])
                if verdict.allowed:
                    allowed += 1
                else:
                    refused += 1
        finally:
            guard.end(run_id)
    print(json.dumps({"suite": args.suite, "runs": len(runs), "calls_allowed": allowed, "calls_refused": refused,
                      "excluded_tasks": sorted(excluded)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
