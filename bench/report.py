"""The report of a benchmark part: the three official numbers with exact intervals, the paired comparison with the unguarded
agent, and the breakdown by environment and by injection task. It reads only the logs AgentDojo wrote (aggregate.collect).

    python report.py --split ../panels/split-v2.json --part dev \
        --undefended ../runs/baseline-qwen25-7b-attack ../runs/baseline-qwen25-7b-benign --undefended-pipeline qwen2.5-7b \
        --defended ../runs/dev-first-llm --defended-pipeline qwen2.5-7b-contexa-C2-owner-llm --json ../runs/dev-first-llm/report.json

Two guarded runs are compared (for example the development part with Layer 2 on Sonnet and on Haiku) by naming the second one:
        --other ../runs/dev-second --other-pipeline qwen2.5-7b-contexa-C2-owner-llm

The intervals are Clopper-Pearson 95% (two-sided); the 'upper 95% (one-sided)' beside an attack rate is the figure a "below 1%"
statement stands on. The paired test is McNemar's exact test on the cases both runs have.
"""
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

from aggregate import TEXT_ONLY, collect, panel_of_split
from stats import clopper_pearson, mcnemar_exact


def rate(k: int, n: int) -> dict:
    if n == 0:
        return {"k": k, "n": n, "rate": None, "ci95": None, "upper95_one_sided": None}
    return {"k": k, "n": n, "rate": k / n, "ci95": clopper_pearson(k, n), "upper95_one_sided": clopper_pearson(k, n, 0.90)[1]}


def show(r: dict) -> str:
    if r["rate"] is None:
        return "-"
    low, high = r["ci95"]
    return f"{r['rate']:6.1%} ({r['k']}/{r['n']}, {low:.1%}-{high:.1%})"


def measures(benign: dict, attacked: dict) -> dict:
    """Per suite and overall: benign utility, utility under attack, attack success (official), attack success (executed only)."""
    result = {}
    names = sorted(set(benign) | set(attacked))
    for suite in names + ["ALL"]:
        # Lists, not dicts: a task id such as user_task_1 exists in every suite, and a dict of the union would overwrite.
        b = [v for s, d in benign.items() if suite in (s, "ALL") for v in d.values()]
        a = [v for s, d in attacked.items() if suite in (s, "ALL") for v in d.values()]
        result[suite] = {
            "benign_utility": rate(sum(b), len(b)),
            "utility_under_attack": rate(sum(1 for u, _, _ in a if u), len(a)),
            "attack_success": rate(sum(1 for _, s, _ in a if s), len(a)),
            "attack_success_executed_only": rate(sum(1 for _, _, e in a if e), len(a)),
        }
    return result


def flat(benign: dict, attacked: dict):
    b = {(s, t): v for s, d in benign.items() for t, v in d.items()}
    a = {(s, t, i): v for s, d in attacked.items() for (t, i), v in d.items()}
    return b, a


def paired(first: dict, second: dict, pick) -> dict:
    """First against second on the cases both have: how many only the first, only the second, and the exact p-value."""
    common = first.keys() & second.keys()
    only_first = sum(1 for k in common if pick(first[k]) and not pick(second[k]))
    only_second = sum(1 for k in common if pick(second[k]) and not pick(first[k]))
    return {"cases": len(common), "only_first": only_first, "only_second": only_second,
            "p_exact": mcnemar_exact(only_first, only_second)}


def injection_breakdown(attacked: dict) -> list[dict]:
    per = defaultdict(lambda: [0, 0, 0])
    for suite, d in attacked.items():
        for (_, injection), (_, success, executed) in d.items():
            row = per[(suite, injection)]
            row[0] += 1
            row[1] += 1 if success else 0
            row[2] += 1 if executed else 0
    return [{"suite": s, "injection_task": i, "cases": c, "succeeded": k, "executed": e,
             "text_only": (s, i) in TEXT_ONLY} for (s, i), (c, k, e) in sorted(per.items()) if k or e]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--split", type=Path, required=True)
    parser.add_argument("--part", choices=["dev", "test"], required=True)
    parser.add_argument("--attack", default="important_instructions")
    parser.add_argument("--undefended", nargs="+", type=Path, required=True)
    parser.add_argument("--undefended-pipeline", required=True)
    parser.add_argument("--defended", nargs="+", type=Path, required=True)
    parser.add_argument("--defended-pipeline", required=True)
    parser.add_argument("--other", nargs="*", type=Path, default=[])
    parser.add_argument("--other-pipeline", default=None)
    parser.add_argument("--json", type=Path, default=None)
    args = parser.parse_args()

    split = json.loads(args.split.read_text(encoding="utf-8"))
    panel = panel_of_split(split, args.part)
    u_benign, u_attacked = collect(args.undefended, args.undefended_pipeline, args.attack, panel)
    d_benign, d_attacked = collect(args.defended, args.defended_pipeline, args.attack, panel)
    report = {"split": {"file": args.split.name, "sha256": split["sha256"], "part": args.part}, "attack": args.attack,
              "undefended": {"pipeline": args.undefended_pipeline, "measures": measures(u_benign, u_attacked)},
              "defended": {"pipeline": args.defended_pipeline, "measures": measures(d_benign, d_attacked),
                           "attack_successes_by_injection_task": injection_breakdown(d_attacked)}}
    ub, ua = flat(u_benign, u_attacked)
    db, da = flat(d_benign, d_attacked)
    report["paired_undefended_vs_defended"] = {
        "attack_success": paired(ua, da, lambda v: v[1]),
        "utility_under_attack": paired(ua, da, lambda v: v[0]),
        "benign_utility": paired(ub, db, lambda v: v)}
    fell = [k for k, v in ua.items() if v[1] and k in da]
    report["among_pairs_the_unguarded_agent_fell_for"] = {
        "pairs": len(fell), "defended_still_succeeded": sum(1 for k in fell if da[k][1]),
        "note": "the unguarded and the guarded runs are two runs of a stochastic agent: a pair the unguarded run fell for is not "
                "certain to be one the guarded agent would have fallen for"}
    if args.other:
        o_benign, o_attacked = collect(args.other, args.other_pipeline or args.defended_pipeline, args.attack, panel)
        ob, oa = flat(o_benign, o_attacked)
        report["other"] = {"pipeline": args.other_pipeline or args.defended_pipeline, "measures": measures(o_benign, o_attacked),
                           "paired_defended_vs_other": {
                               "attack_success": paired(da, oa, lambda v: v[1]),
                               "utility_under_attack": paired(da, oa, lambda v: v[0]),
                               "benign_utility": paired(db, ob, lambda v: v)}}

    print(f"split {args.split.name} part {args.part} | attack {args.attack}")
    for title, key in (("unguarded", "undefended"), ("guarded", "defended")):
        print(f"\n{title} ({report[key]['pipeline']})")
        print(f"{'suite':<10}{'benign utility':>34}{'utility under attack':>36}{'targeted ASR':>34}{'executed only':>34}")
        for suite, m in report[key]["measures"].items():
            print(f"{suite:<10}{show(m['benign_utility']):>34}{show(m['utility_under_attack']):>36}"
                  f"{show(m['attack_success']):>34}{show(m['attack_success_executed_only']):>34}")
        overall = report[key]["measures"].get("ALL")
        if overall and overall["attack_success"]["upper95_one_sided"] is not None:
            print(f"  ALL: targeted ASR upper 95% (one-sided) {overall['attack_success']['upper95_one_sided']:.2%}; "
                  f"executed only {overall['attack_success_executed_only']['upper95_one_sided']:.2%}")
    print("\npaired, unguarded against guarded (cases only the unguarded / only the guarded run solved or reached, exact p):")
    for name, p in report["paired_undefended_vs_defended"].items():
        print(f"  {name:<22} cases {p['cases']:4d}  only unguarded {p['only_first']:4d}  only guarded {p['only_second']:4d}  p={p['p_exact']:.4f}")
    fell_report = report["among_pairs_the_unguarded_agent_fell_for"]
    print(f"  pairs the unguarded agent fell for: {fell_report['pairs']}; the guarded agent still reached the goal in "
          f"{fell_report['defended_still_succeeded']}")
    if report["defended"]["attack_successes_by_injection_task"]:
        print("\nwhere the guarded agent reached the attacker's goal (or executed it):")
        for row in report["defended"]["attack_successes_by_injection_task"]:
            print(f"  {row['suite']:<10}{row['injection_task']:<18}cases {row['cases']:3d}  official {row['succeeded']:3d}  "
                  f"executed {row['executed']:3d}{'  (text only)' if row['text_only'] else ''}")
    if "other" in report:
        print(f"\nother run ({report['other']['pipeline']}) against the guarded run, exact p (cases only the guarded / only the other):")
        for name, p in report["other"]["paired_defended_vs_other"].items():
            print(f"  {name:<22} cases {p['cases']:4d}  only guarded {p['only_first']:4d}  only other {p['only_second']:4d}  p={p['p_exact']:.4f}")
    if args.json:
        args.json.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
