"""The whole-programme fit (plan 7.1, rule 2): do the high-case costs of every stage still to run fit in what is left of the
$100 cap after the reserve? Every input is marked MEASURED or ASSUMED, and a stage whose inputs are not known yet is listed as
"not computed" and is not silently counted as zero.

    python fit_check.py                                     measured run costs of the old development panels
    python fit_check.py --pattern 'dev-first-*'             measured from the qwen development run instead
    python fit_check.py --baseline-dirs ../runs/baseline-qwen25-7b-attack --baseline-pipeline qwen2.5-7b
                                                             take the pairs qwen falls for from its unguarded logs
Stages whose size is not known before a measurement are given as options (--injecagent-attack-cases, --promptfoo-decisions,
--multi-vulnerable, --multi-control).
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from statistics import mean

import aggregate
import cost_model

ROOT = Path(__file__).resolve().parent.parent
QWEN_SECONDS_PER_RUN = 9.8  # measured once on this PC, one run alone (qwen2.5:7b)


def per_run_costs(pattern: str) -> dict:
    """low / mean / high of the judge dollars per run, of the decisions per run and of a run's first decision, over the variants."""
    data = cost_model.measured(pattern)
    if not data:
        raise SystemExit(f"no finished run folder matches runs/{pattern} (a folder needs 20 runs with decisions)")
    dollars = [mean(r["dollars"] for r in runs.values()) for runs in data.values()]
    decisions = [mean(r["decisions"] for r in runs.values()) for runs in data.values()]
    first = [mean(r["first"] or 0.0 for r in runs.values()) for runs in data.values()]
    return {"variants": len(data), "run": (min(dollars), mean(dollars), max(dollars)),
            "decisions": (min(decisions), mean(decisions), max(decisions)), "first": (min(first), mean(first), max(first)),
            "names": sorted(data)}


def vulnerable_pairs(dirs: list[Path], pipeline: str, split: dict, part: str) -> tuple[int, int]:
    """(pairs the unguarded agent fell for, pairs run) in one part of the split, from the unguarded agent's own logs."""
    _, attacked = aggregate.collect(dirs, pipeline, "important_instructions", aggregate.panel_of_split(split, part))
    pairs = [v for d in attacked.values() for v in d.values()]
    return sum(1 for v in pairs if v[1]), len(pairs)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pattern", default="panel-dev1-C2*")
    parser.add_argument("--baseline-dirs", nargs="*", type=Path, default=[])
    parser.add_argument("--baseline-pipeline", default="qwen2.5-7b-16k-p2k")
    parser.add_argument("--fix-iterations", type=int, default=3)
    parser.add_argument("--failing", type=int, default=30)
    parser.add_argument("--injecagent-attack-cases", type=int, default=None,
                        help="InjecAgent cases (both settings) where the unguarded agent chose the attacker's tool")
    parser.add_argument("--injecagent-benign-sample", type=int, default=400)
    parser.add_argument("--promptfoo-decisions", type=int, default=None, help="guard decisions of the Promptfoo run")
    parser.add_argument("--multi-vulnerable", type=int, default=None, help="(pair, attack variant) cases the unguarded agent falls for")
    parser.add_argument("--multi-control", type=int, default=None, help="control sample of cases it does not fall for")
    args = parser.parse_args()

    split = json.loads((ROOT / "panels" / "split-v2.json").read_text(encoding="utf-8"))
    totals = split["totals"]
    dev_runs = totals["dev_pairs"] + totals["dev_tasks"]
    test_runs = totals["test_pairs"] + totals["test_tasks"]
    costs = per_run_costs(args.pattern)
    c = costs["run"]
    first = costs["first"]

    marks = []
    marks.append(f"MEASURED  cost per guarded run: low {c[0]:.4f} / mean {c[1]:.4f} / high {c[2]:.4f} USD "
                 f"({costs['variants']} variant folders: {', '.join(costs['names'])}); decisions per run "
                 f"{costs['decisions'][0]:.1f}-{costs['decisions'][2]:.1f}")
    marks.append(f"MEASURED  cost of a run's FIRST decision (what a one-call run pays): mean {first[1]:.4f}, high {first[2]:.4f} USD")
    if args.baseline_dirs:
        v_test, n_test = vulnerable_pairs(args.baseline_dirs, args.baseline_pipeline, split, "test")
        v_dev, n_dev = vulnerable_pairs(args.baseline_dirs, args.baseline_pipeline, split, "dev")
        marks.append(f"MEASURED  unguarded {args.baseline_pipeline} falls for {v_test}/{n_test} test pairs and {v_dev}/{n_dev} development pairs "
                     f"({'COMPLETE' if n_test == totals['test_pairs'] and n_dev == totals['dev_pairs'] else 'INCOMPLETE: logs are still being written'})")
        vulnerable = v_test
    else:
        vulnerable = 203
        marks.append("ASSUMED   pairs the agent falls for in the test part: 203 (the old gpt-4o-mini figure; replace with the qwen baseline)")
    marks.append(f"ASSUMED   development: {args.fix_iterations} fix iterations, {args.failing} failing cases each, regression sample 20% of the "
                 "development runs")
    marks.append(f"MEASURED  qwen seconds per run alone: {QWEN_SECONDS_PER_RUN} (guard waits are extra wall time, not GPU time)")
    regression = round(0.2 * dev_runs)

    # (name, qwen runs, cost low/mean/high or None when not computed, note)
    def times(runs: float, scale=(1, 1, 1), per=c):
        return tuple(runs * per[i] * scale[i] for i in range(3))

    stages = []
    stages.append(("D-3 development part, simulated owner", dev_runs, times(dev_runs), "in progress"))
    stages.append(("D-3 control, nobody confirms", dev_runs, times(dev_runs), "plan D-3: control variant"))
    stages.append(("D-2 Layer 2 comparison (100 Haiku + 40 Sonnet replays)", 0, (0.3, 0.6, 1.0), "ESTIMATE from request size 2.3k tokens"))
    stages.append((f"D-5 fix iterations x{args.fix_iterations}", args.fix_iterations * (args.failing + regression),
                   times(args.fix_iterations * (args.failing + regression)), "ASSUMED iterations"))
    stages.append(("S-1 test part, nobody confirms + simulated owner", 2 * test_runs, times(2 * test_runs), ""))
    all_runs = vulnerable + 0.1 * (totals["test_pairs"] - vulnerable) + totals["test_tasks"]
    stages.append(("S-1 test part, owner approves all (pairs fallen for + 10% control + benign)", all_runs, times(all_runs), ""))
    stages.append(("final development run again, 2 variants", 2 * dev_runs, times(2 * dev_runs), ""))
    if args.injecagent_attack_cases is None:
        stages.append(("S-2 InjecAgent first step, both settings", 2108, None, "not computed: needs the number of cases where the unguarded "
                                                                             "qwen chose the attacker's tool (--injecagent-attack-cases)"))
    else:
        calls = args.injecagent_attack_cases + args.injecagent_benign_sample
        stages.append(("S-2 InjecAgent guard calls (attack choices + benign sample)", 2108, times(calls, per=first),
                       "one-call runs pay the first-decision cost"))
    if args.promptfoo_decisions is None:
        stages.append(("S-3 Promptfoo owasp:agentic", 0, None, "not computed: the sample app and the generation mode are not decided (plan 3.1)"))
    else:
        stages.append(("S-3 Promptfoo guard decisions", 0, times(args.promptfoo_decisions, per=first), "ASSUMED decisions"))
    if args.multi_vulnerable is None:
        stages.append(("S-4 multi-attempt", 5225, None, "not computed: needs the (pair, variant) cases the unguarded qwen falls for "
                                                       "(--multi-vulnerable, --multi-control)"))
    else:
        runs = args.multi_vulnerable + (args.multi_control or 0)
        stages.append(("S-4 multi-attempt, guarded runs", 5225 + runs, times(runs), "unguarded runs are free but use the GPU"))

    spent = float(subprocess.run([sys.executable, "-X", "utf8", str(ROOT / "bench" / "spend.py"), "--total"], check=True,
                                 capture_output=True, text=True, encoding="utf-8").stdout.strip())
    budget = json.loads((ROOT / "budget.json").read_text(encoding="utf-8"))
    left = budget["program_cap_usd"] - spent - budget.get("reserve_usd", 0)

    print("inputs")
    for m in marks:
        print("  " + m)
    print(f"\n{'stage':<76}{'low':>8}{'mean':>8}{'high':>8}{'qwen runs':>11}")
    sums = [0.0, 0.0, 0.0]
    gpu_runs = 0.0
    open_stages = []
    for name, runs, cost, note in stages:
        gpu_runs += runs
        if cost is None:
            open_stages.append(name)
            print(f"{name:<76}{'-':>8}{'-':>8}{'-':>8}{runs:>11.0f}   {note}")
            continue
        for i in range(3):
            sums[i] += cost[i]
        print(f"{name:<76}{cost[0]:>8.2f}{cost[1]:>8.2f}{cost[2]:>8.2f}{runs:>11.0f}   {note}")
    print(f"{'sum of the stages with a computed cost':<76}{sums[0]:>8.2f}{sums[1]:>8.2f}{sums[2]:>8.2f}")
    print(f"\nspent {spent:.2f} of {budget['program_cap_usd']}; reserve {budget.get('reserve_usd', 0)}; left for the stages {left:.2f}")
    print(f"GPU time of the qwen runs listed: {gpu_runs * QWEN_SECONDS_PER_RUN / 3600:.1f} hours (runs x {QWEN_SECONDS_PER_RUN} s, one at a time)")
    if open_stages:
        print(f"NOT COMPUTED: {len(open_stages)} stage(s): " + "; ".join(open_stages))
    verdict = "FITS" if sums[2] <= left else "DOES NOT FIT"
    print(f"HIGH-CASE SUM {sums[2]:.2f} against {left:.2f}: {verdict}"
          + (" for the computed stages; the verdict is final only when every stage is computed" if open_stages else ""))
    print(f"headroom for the not-computed stages at the high case: {left - sums[2]:.2f}")


if __name__ == "__main__":
    main()
