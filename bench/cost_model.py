"""The cost of the whole test programme, computed from what the runs measured, not from guesses.

The victim agent is a local model (Ollama): no money. Everything else is Anthropic: the judge (Layer 1 claude-haiku-5-5, Layer 2
claude-sonnet-5-5 when the engine escalates), the simulated owner (claude-haiku-5-5), the red-team generator and grader of Promptfoo.

Measured inputs, read from the run folders:
  c_run        dollars of judge calls per guarded run, from every contexa-decisions.jsonl of the development panels (per variant)
  decisions    guard decisions per run
  structure    the number of runs of each stage, from panels/split-v2.json and the benchmark's own counts

Inputs that have not been measured yet are named SCENARIO and printed as such; each is a plain parameter of this script:
  --fix-iterations N     how many times the guard is fixed and re-run in development (a fix iteration re-runs the failing cases F and a
                         regression sample R = 20% of the development runs)
  --failing F            the failing cases per iteration
  --vulnerable V         pairs of the test part the local victim complies with without a defence (replaced by the measured number when
                         the unguarded baseline of the local model is done)

    python cost_model.py --fix-iterations 3 --failing 30 --vulnerable 203
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from statistics import mean

ROOT = Path(__file__).resolve().parent.parent
PRICES = {"claude-haiku-5-5": (0.10, 0.01, 0.50), "claude-sonnet-5-5": (2.00, 0.10, 10.00)}


def call_cost(call: dict) -> float:
    price = PRICES.get(call.get("model") or "")
    if price is None:
        return 0.0
    prompt, cached = call.get("promptTokens") or 0, call.get("cachedTokens") or 0
    return ((prompt - cached) * price[0] + cached * price[1] + (call.get("completionTokens") or 0) * price[2]) / 1e6


def measured(pattern: str = "panel-dev1-C2*") -> dict:
    """Judge dollars and decisions per run, by owner variant, from the run folders matching `pattern` (finished runs only).
    `first` is what the first decision of a run cost: a run of one call (InjecAgent, a Promptfoo test) pays that and no more."""
    result = {}
    for folder in sorted((ROOT / "runs").glob(pattern)):
        file = folder / "contexa-decisions.jsonl"
        if not file.exists():
            continue
        per_run: dict[tuple, dict] = {}
        for line in file.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            key = (row.get("suite"), row.get("user_task"), row.get("injection_task"), row.get("run_id"))
            run = per_run.setdefault(key, {"dollars": 0.0, "decisions": 0, "owner": 0, "first": None,
                                           "attack": row.get("injection_task") is not None})
            run["decisions"] += 1
            run["owner"] += 1 if row.get("owner") else 0
            this_row = 0.0
            for call in (row.get("detail") or {}).get("modelCallList") or []:
                this_row += call_cost(call)
            run["dollars"] += this_row
            if run["first"] is None:
                run["first"] = this_row
        if len(per_run) >= 20:
            result[folder.name] = per_run
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fix-iterations", type=int, default=3)
    parser.add_argument("--failing", type=int, default=30)
    parser.add_argument("--vulnerable", type=int, default=203)
    parser.add_argument("--owner-per-question", type=float, default=0.0007,
                        help="dollars of one simulated-owner question (measured: 65 ordinary calls cost $0.014 in total => about 0.0001; "
                             "0.0007 is 7x that)")
    args = parser.parse_args()

    split = json.loads((ROOT / "panels" / "split-v2.json").read_text(encoding="utf-8"))
    dev_pairs, test_pairs = split["totals"]["dev_pairs"], split["totals"]["test_pairs"]
    dev_benign, test_benign = split["totals"]["dev_tasks"], split["totals"]["test_tasks"]
    dev_runs, test_runs = dev_pairs + dev_benign, test_pairs + test_benign

    data = measured()
    print("== 측정값: 판정 비용(Anthropic)과 판정 수, 개발 패널 변형별 (피해 에이전트 gpt-4o-mini의 궤적 기준) ==")
    per_variant = {}
    for name, runs in data.items():
        dollars = [r["dollars"] for r in runs.values()]
        decisions = [r["decisions"] for r in runs.values()]
        per_variant[name] = (mean(dollars), mean(decisions), len(runs))
        print(f"  {name:22s} 실행 {len(runs):3d}개  실행당 ${mean(dollars):.4f}  판정 {mean(decisions):.1f}건/실행")
    lo = min(v[0] for v in per_variant.values())
    hi = max(v[0] for v in per_variant.values())
    mid = mean(v[0] for v in per_variant.values())
    print(f"  실행당 판정 비용: 최소 ${lo:.4f} / 평균 ${mid:.4f} / 최대 ${hi:.4f}")

    print("\n== 구조(분할 파일, 공식 벤치마크 정의) ==")
    print(f"  개발용 {dev_runs}실행(공격 {dev_pairs} + 정상 {dev_benign}), 시험용 {test_runs}실행(공격 {test_pairs} + 정상 {test_benign})")

    print(f"\n== 시나리오 입력(측정 전): 수정 반복 {args.fix_iterations}회, 반복당 실패 {args.failing}건, 시험용에서 넘어가는 조합 {args.vulnerable} ==")
    reg = round(0.2 * dev_runs)
    stages = {}
    for label, c in (("낮음", lo), ("평균", mid), ("높음", hi)):
        owner = args.owner_per_question
        run = c + owner * 0.0  # the owner is paid per question, counted inside c only for variants that asked
        stages[label] = {
            "D-3 개발용 첫 실행": dev_runs * run,
            f"D-5 수정 반복 {args.fix_iterations}회": args.fix_iterations * (args.failing + reg) * run,
            "S-1 시험용 소유자 없음 + 모의 소유자": 2 * test_runs * run,
            "S-1 전부 승인(넘어간 조합 + 대조 10% + 정상)": (args.vulnerable + 0.1 * (test_pairs - args.vulnerable) + test_benign) * run,
            "S-1 개발용을 최종 판으로 한 번 더(2변형)": 2 * dev_runs * run,
        }
    keys = list(stages["평균"])
    print(f"{'항목':44s}{'낮음':>9s}{'평균':>9s}{'높음':>9s}")
    for k in keys:
        print(f"{k:44s}{stages['낮음'][k]:9.2f}{stages['평균'][k]:9.2f}{stages['높음'][k]:9.2f}")
    sub = {l: sum(stages[l].values()) for l in stages}
    print(f"{'AgentDojo 소계(Anthropic 판정 비용)':44s}{sub['낮음']:9.2f}{sub['평균']:9.2f}{sub['높음']:9.2f}")


if __name__ == "__main__":
    main()
