"""The spend ledger of the benchmark: what the runs cost, from the numbers they recorded, against the caps in ../budget.json.

Sources (every one is written by the run itself, none is estimated here):
  agent         summary-*.json of a run: agent_usage tokens of the agent model (AgentDojo, InjecAgent)
  owner         the same files: the simulated owner's tokens (claude-haiku-5-5)
  contexa       contexa-decisions.jsonl: every model call of every guard decision (model, input, cache read, output)
  guard ledger  guard/logs/model-calls.jsonl, when the guard writes it: every model call the guard made, whoever asked

Prices are USD per million tokens (input, cache read, output) from the providers' price pages, checked 2026-10-28. The agent's
input tokens are priced in full (no cache split is recorded for the OpenAI agent), so the agent figure is a slight over-estimate.
Runs that crashed before they wrote a summary are not in the ledger; their cost is listed by hand in budget.json under "unrecorded".
The provider's console is the authority; this is the early warning.

    python spend.py                 what has been spent, by stage and by kind
    python spend.py --total         the total in USD, one number (for a watcher)
    python spend.py --check dev     exit status 1 when stage "dev" is over its cap
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUNS = ROOT / "runs"
PRICES = {
    "claude-haiku-5-5": (0.10, 0.01, 0.50),
    "claude-sonnet-5-5": (2.00, 0.10, 10.00),
    "claude-haiku-4-5-20251001": (1.00, 0.10, 5.00),
    "claude-sonnet-4-5-20250929": (3.00, 0.30, 15.00),
    "gpt-4o-mini-2024-07-18": (0.15, 0.075, 0.60),
    "gpt-4o-2024-08-06": (2.50, 1.25, 10.00),
    "gpt-6-luna": (0.10, 0.01, 0.50),
}


def cost(model: str | None, prompt: int, cached: int, completion: int) -> float:
    price = PRICES.get(model or "")
    if price is None:
        return 0.0
    return ((prompt - cached) * price[0] + cached * price[1] + completion * price[2]) / 1e6


def provider_of(model: str | None) -> str:
    return "openai" if (model or "").startswith("gpt-") else "anthropic"


def stage_of(path: Path) -> str:
    """The stage a run folder belongs to, from its name: baseline-* and injecagent-baseline-* are the unguarded baselines,
    panel-* the development panel, dev-* the development split, test-* the official test part, anything else a spike."""
    return stage_of_name(path.relative_to(RUNS).parts[0])


def stage_of_name(name: str) -> str:
    for prefix, stage in (("baseline", "baseline"), ("injecagent-baseline", "baseline"), ("headroom", "spike"),
                          ("spike", "spike"), ("injecagent-smoke", "spike"), ("panel", "dev"), ("dev", "dev"),
                          ("layer2-swap", "dev"),
                          ("test", "test"), ("official", "test")):
        if name.startswith(prefix):
            return stage
    return "other"


def collect() -> dict:
    ledger: dict = defaultdict(lambda: defaultdict(float))
    by_provider: dict = defaultdict(float)
    ledger["_provider"] = by_provider
    seen_summaries = set()
    for summary in RUNS.rglob("summary-*.json"):
        try:
            data = json.loads(summary.read_text(encoding="utf-8"))
        except ValueError:
            continue
        key = (summary.parent, data.get("agent_model"), data.get("suite"), data.get("pipeline_name"), data.get("attack"),
               data.get("setting"))
        if key in seen_summaries:
            continue
        seen_summaries.add(key)
        stage = stage_of(summary)
        usage = data.get("agent_usage") or {}
        agent_cost = cost(data.get("agent_model"), usage.get("input_tokens", 0), 0, usage.get("output_tokens", 0))
        ledger[stage]["agent"] += agent_cost
        by_provider[provider_of(data.get("agent_model"))] += agent_cost
        owner = data.get("owner")
        if owner:
            owner_cost = cost("claude-haiku-5-5", owner.get("input_tokens", 0), 0, owner.get("output_tokens", 0))
            ledger[stage]["owner"] += owner_cost
            by_provider["anthropic"] += owner_cost
    # replays of recorded Layer 2 requests to a model (layer2_swap.py): one row per answer with the usage the API reported
    for replay_file in RUNS.rglob("replay-*.jsonl"):
        stage = stage_of(replay_file)
        for line in replay_file.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            replay_cost = cost(row.get("model"), (row.get("input") or 0) + (row.get("cache_read") or 0)
                               + (row.get("cache_write") or 0), row.get("cache_read") or 0, row.get("output") or 0)
            ledger[stage]["replay"] += replay_cost
            by_provider[provider_of(row.get("model"))] += replay_cost
    decision_cost: dict[str, float] = defaultdict(float)
    for decisions in RUNS.rglob("contexa-decisions.jsonl"):
        stage = stage_of(decisions)
        top = decisions.relative_to(RUNS).parts[0]
        for line in decisions.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            for call in (row.get("detail") or {}).get("modelCallList") or []:
                call_cost = cost(call.get("model"), call.get("promptTokens") or 0, call.get("cachedTokens") or 0,
                                 call.get("completionTokens") or 0)
                ledger[stage]["contexa"] += call_cost
                decision_cost[top] += call_cost
                by_provider[provider_of(call.get("model"))] += call_cost
    # The guard writes every model call of a run to guard/cache-<run>/model-calls.jsonl, whoever asked (a decision, template
    # learning, an operation). What the decisions' own records do not account for is added here, so no call is missing.
    # A cache-read figure of -1 means unknown and is priced as no cache read (the dearer way).
    for ledger_file in (ROOT / "guard").glob("cache-*/model-calls.jsonl"):
        label = ledger_file.parent.name[len("cache-"):]
        total = 0.0
        for line in ledger_file.read_text(encoding="utf-8").splitlines():
            if line.strip():
                row = json.loads(line)
                # The ledger's promptTokens are the input WITHOUT the cache read (checked: ledger input + cache read equals the
                # decisions' input), while cost() takes the total input and subtracts the cached part.
                cache_read = max(row.get("cacheReadTokens") or 0, 0)
                total += cost(row.get("model"), (row.get("promptTokens") or 0) + cache_read, cache_read,
                              row.get("completionTokens") or 0)
        outside = max(0.0, total - decision_cost.get(label, 0.0))
        ledger[stage_of_name(label)]["outside_decisions"] += outside
        by_provider["anthropic"] += outside
    return ledger


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--total", action="store_true")
    parser.add_argument("--check", metavar="STAGE", default=None)
    parser.add_argument("--can-finish", metavar="USD", type=float, default=None,
                        help="exit 0 only when a stage whose high-case cost is USD fits in what is left of the programme cap after the "
                             "reserve; a stage that cannot finish is not started")
    args = parser.parse_args()
    budget = json.loads((ROOT / "budget.json").read_text(encoding="utf-8"))
    ledger = collect()
    by_provider = ledger.pop("_provider")
    recorded = sum(sum(kinds.values()) for kinds in ledger.values())
    unrecorded = sum(item["usd"] for item in budget.get("unrecorded", []))
    if args.total:
        print(f"{recorded + unrecorded:.2f}")
        return
    if args.can_finish is not None:
        program_cap = budget["program_cap_usd"]
        left = program_cap - (recorded + unrecorded) - budget.get("reserve_usd", 0)
        print(f"left for stages: ${left:.2f} (cap ${program_cap}, spent ${recorded + unrecorded:.2f}, reserve ${budget.get('reserve_usd', 0)}); "
              f"this stage needs up to ${args.can_finish:.2f}")
        sys.exit(0 if args.can_finish <= left else 4)
    if args.check:
        spent = sum(ledger[args.check].values())
        cap = budget["caps"].get(args.check)
        if cap is None:
            print(f"no cap is set for stage '{args.check}'", file=sys.stderr)
            sys.exit(2)
        program_cap = budget.get("program_cap_usd")
        stop_at = program_cap * budget.get("hard_stop_fraction", 1.0) if program_cap else None
        total = recorded + unrecorded
        print(f"stage {args.check}: spent ${spent:.2f} of ${cap:.2f}; programme ${total:.2f} of ${program_cap} (hard stop at ${stop_at:.0f})")
        if stop_at is not None and total >= stop_at:
            print("PROGRAMME HARD STOP: the ledger total reached the stop line; nothing more is run without the user's decision",
                  file=sys.stderr)
            sys.exit(3)
        sys.exit(1 if spent >= cap else 0)
    print(f"{'stage':10s}{'agent':>9s}{'owner':>9s}{'contexa':>10s}{'replay':>9s}{'outside':>9s}{'stage total':>13s}{'cap':>9s}")
    for stage in sorted(ledger):
        k = ledger[stage]
        cap = budget["caps"].get(stage)
        print(f"{stage:10s}{k['agent']:9.2f}{k['owner']:9.2f}{k['contexa']:10.2f}{k['replay']:9.2f}{k['outside_decisions']:9.2f}"
              f"{sum(k.values()):13.2f}"
              f"{('%.0f' % cap) if cap is not None else '-':>9s}")
    print(f"\nrecorded by the runs: ${recorded:.2f}")
    if unrecorded:
        print(f"not recorded (crashed or stopped runs, estimated by hand, budget.json): ${unrecorded:.2f}")
        for item in budget["unrecorded"]:
            print(f"   {item['usd']:6.2f}  {item['what']}")
    print(f"total, by this ledger: ${recorded + unrecorded:.2f}   (the providers' consoles are the authority)")
    hand = defaultdict(float)
    for item in budget.get("unrecorded", []):
        hand[item.get("provider", "anthropic")] += item["usd"]
    for name in ("openai", "anthropic"):
        print(f"  {name:10s} recorded ${by_provider.get(name, 0):6.2f} + by hand ${hand.get(name, 0):6.2f} = ${by_provider.get(name, 0) + hand.get(name, 0):6.2f}")


if __name__ == "__main__":
    main()
