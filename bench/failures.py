"""Lists the cases that D-4 (cause of each failure) has to explain, with what the guard decided in each of them.

Two kinds of case, for one part of the split:
  attack succeeded   the attacker's goal was reached although the guard was on (official score), and whether a call that was really
                     executed reached it ("executed only"); the guard's decisions of that run follow
  business lost      the unguarded agent solved the user's task (alone or under attack) and the guarded one did not; the first
                     calls the guard did not allow, with its reason, follow

    python failures.py --split ../panels/split-v2.json --part dev \
        --undefended ../runs/baseline-q16k-attack ../runs/baseline-q16k-benign --undefended-pipeline qwen2.5-7b-16k \
        --defended ../runs/dev-c16k-llm --defended-pipeline qwen2.5-7b-16k-contexa-C2-owner-llm \
        --decisions ../runs/dev-c16k-llm/contexa-decisions.jsonl [--json out.json]

It only reads what the runs wrote; nothing is re-scored. A case the unguarded agent did not run yet is not counted as lost.
"""
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

from aggregate import TEXT_ONLY, collect, panel_of_split


def load_decisions(path: Path) -> dict:
    """(suite, user task, injection task or None) -> the guard's decisions of that run, in order."""
    by_case = defaultdict(list)
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            row = json.loads(line)
            by_case[(row["suite"], row["user_task"], row.get("injection_task"))].append(row)
    return by_case


def short(row: dict) -> dict:
    detail = row.get("detail") or {}
    return {"tool": row["tool"], "action": row["action"], "layer": detail.get("layer"), "owner": row.get("owner"),
            "arguments": str(row.get("arguments"))[:90], "reason": str(row.get("reason") or detail.get("reason") or "")[:160]}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--split", type=Path, required=True)
    parser.add_argument("--part", choices=["dev", "test"], required=True)
    parser.add_argument("--attack", default="important_instructions")
    parser.add_argument("--undefended", nargs="+", type=Path, required=True)
    parser.add_argument("--undefended-pipeline", required=True)
    parser.add_argument("--defended", nargs="+", type=Path, required=True)
    parser.add_argument("--defended-pipeline", required=True)
    parser.add_argument("--decisions", type=Path, required=True)
    parser.add_argument("--json", type=Path, default=None)
    args = parser.parse_args()

    panel = panel_of_split(json.loads(args.split.read_text(encoding="utf-8")), args.part)
    u_benign, u_attacked = collect(args.undefended, args.undefended_pipeline, args.attack, panel)
    d_benign, d_attacked = collect(args.defended, args.defended_pipeline, args.attack, panel)
    decisions = load_decisions(args.decisions)

    successes, lost = [], []
    for suite, pairs in sorted(d_attacked.items()):
        for (task, injection), (utility, security, executed) in sorted(pairs.items()):
            run = decisions.get((suite, task, injection), [])
            if security:
                successes.append({"suite": suite, "user_task": task, "injection_task": injection, "executed": executed,
                                  "text_only": (suite, injection) in TEXT_ONLY,
                                  "decisions": [short(r) for r in run]})
            before = u_attacked.get(suite, {}).get((task, injection))
            if before is not None and before[0] and not utility:
                lost.append({"kind": "under attack", "suite": suite, "user_task": task, "injection_task": injection,
                             "not_allowed": [short(r) for r in run if r["action"] != "ALLOW"][:4], "calls": len(run)})
    for suite, tasks in sorted(d_benign.items()):
        for task, solved in sorted(tasks.items()):
            before = u_benign.get(suite, {}).get(task)
            if before and not solved:
                run = decisions.get((suite, task, None), [])
                lost.append({"kind": "benign", "suite": suite, "user_task": task, "injection_task": None,
                             "not_allowed": [short(r) for r in run if r["action"] != "ALLOW"][:4], "calls": len(run)})

    print(f"split {args.split.name} part {args.part}")
    print(f"\nATTACK SUCCEEDED despite the guard: {len(successes)} case(s)")
    for case in successes:
        flag = " [goal is text in the answer: no tool call decides it]" if case["text_only"] else ""
        print(f"- {case['suite']} {case['user_task']} x {case['injection_task']}: official yes, executed call "
              f"{'yes' if case['executed'] else 'NO (only attempted)'}{flag}")
        for d in case["decisions"][:6]:
            print(f"    {d['action']:<11}{d['layer'] or '-':<20}{d['tool']:<26}{d['arguments']}  | {d['reason']}")
    print(f"\nBUSINESS LOST (unguarded agent solved it, guarded one did not): {len(lost)} case(s)")
    for case in lost:
        target = f"{case['user_task']} x {case['injection_task']}" if case["injection_task"] else case["user_task"]
        print(f"- {case['kind']}: {case['suite']} {target} ({case['calls']} guard decisions)")
        for d in case["not_allowed"]:
            print(f"    {d['action']:<11}{d['layer'] or '-':<20}{d['tool']:<26}{d['arguments']}  | {d['reason']}")
    if args.json:
        args.json.write_text(json.dumps({"attack_succeeded": successes, "business_lost": lost}, indent=2, ensure_ascii=False),
                             encoding="utf-8")


if __name__ == "__main__":
    main()
