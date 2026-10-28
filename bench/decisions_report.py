"""Reads the guard's decisions of a run folder (contexa-decisions.jsonl) and says what they cost and how they were made.

Prices in USD per million tokens (official page, checked 2026-10-28): claude-haiku-5-5 input 0.10, cache read 0.01,
output 0.50 (prompts up to 100,000 tokens); claude-sonnet-5-5 input 2, cache read 0.10, output 10. The tokens the guard
reports are input tokens including the cached part, so the cached part is priced at the cache-read price and the rest at
the input price (a cache write costs 1.25x the input price once per five minutes and is not separated here).

    python decisions_report.py ../runs/panel-dev1-C2
"""
from __future__ import annotations

import json
import statistics
import sys
from collections import Counter
from pathlib import Path

PRICES = {
    "claude-haiku-5-5": (0.10, 0.01, 0.50),
    "claude-sonnet-5-5": (2.00, 0.10, 10.00),
}


def call_cost(call: dict) -> float:
    price = PRICES.get(call.get("model") or "")
    if price is None:
        return 0.0
    prompt, cached = call.get("promptTokens") or 0, call.get("cachedTokens") or 0
    return ((prompt - cached) * price[0] + cached * price[1] + (call.get("completionTokens") or 0) * price[2]) / 1e6


def main() -> None:
    folder = Path(sys.argv[1])
    rows = [json.loads(line) for line in (folder / "contexa-decisions.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    details = [row["detail"] for row in rows]
    analysed = [d for d in details if d.get("analysed")]
    layers = Counter(d.get("layer") for d in details)
    actions = Counter(d.get("action") for d in details)
    cost_by_model: Counter = Counter()
    calls = 0
    for d in analysed:
        for call in d.get("modelCallList") or []:
            cost_by_model[call.get("model")] += call_cost(call)
            calls += 1
    ms = sorted(d.get("decisionMs") or 0 for d in analysed)

    def pct(p: float) -> float:
        return ms[min(len(ms) - 1, int(len(ms) * p))] if ms else 0

    print(f"판정 {len(rows)}건 (분석 {len(analysed)}건, 저장된 제한·통과로 답한 것 {len(rows) - len(analysed)}건), 모델 호출 {calls}회")
    print("행동:", dict(actions))
    print("층:", dict(layers), f"| Layer 2 비율(분석 중) {layers.get('LAYER2', 0) / max(1, len(analysed)) * 100:.0f}%")
    if ms:
        print(f"분석 지연: 중앙값 {statistics.median(ms) / 1000:.1f}초, p95 {pct(0.95) / 1000:.1f}초, 최대 {ms[-1] / 1000:.1f}초")
    total = sum(cost_by_model.values())
    print("모델 비용:", {k: round(v, 3) for k, v in cost_by_model.items()}, f"합계 약 ${total:.3f}",
          f"| 분석 판정당 ${total / max(1, len(analysed)):.4f}")
    by_tool = Counter((row["tool"], row["detail"].get("action")) for row in rows)
    print("도구별 행동 상위:")
    for (tool, action), n in by_tool.most_common(12):
        print(f"  {tool:32s} {action:10s} {n}")


if __name__ == "__main__":
    main()
