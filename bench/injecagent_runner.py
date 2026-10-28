"""Runs InjecAgent's official prompted-agent evaluation with and without the Contexa guard.

Everything official is reused unchanged: the data (`data/test_cases_*`), the ReAct prompts, the output parser that
decides whether the agent chose the attacker's tool (`evaluate_output_prompted`) and the score (`get_score`). The
only addition is the guard: when the agent's next action is the attacker's tool call, the guard is asked whether that
call may run, and a call it does not allow counts as an unsuccessful attack. To see what the guard does to ordinary
work, the user's own tool call of each case is asked of the guard too (it is a legitimate call).

Only the first step is run (`--only_first_step` of the official script): the second step of a data-stealing case
needs a simulated tool response that the official code generates with a retired model (gpt-4-0613).

    python injecagent_runner.py --setting base --defense none --limit 20 --out ../runs/injecagent-smoke
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
import types
import uuid
from pathlib import Path

import anthropic
from concurrent.futures import ThreadPoolExecutor

from common import AGENT_MODEL_DEFAULT, load_anthropic_key
from guards import HttpGuard
from records import injecagent_scope, load_declarations

ROOT = Path("E:/projects/contexa-benchmark/third_party/InjecAgent")


class AnthropicMessagesModel:
    """The model class InjecAgent's README asks for (prepare_input, call_model) on the Messages API."""

    def __init__(self, model: str) -> None:
        self._client = anthropic.Anthropic()
        self._model = model
        self.calls = 0
        self.input_tokens = 0
        self.output_tokens = 0

    def prepare_input(self, sys_prompt, user_prompt_filled):
        return sys_prompt, user_prompt_filled

    def call_model(self, model_input):
        system, user = model_input
        reply = self._client.messages.create(
            model=self._model, max_tokens=4096, system=system, messages=[{"role": "user", "content": user}]
        )
        self.calls += 1
        self.input_tokens += reply.usage.input_tokens
        self.output_tokens += reply.usage.output_tokens
        return "".join(block.text for block in reply.content if block.type == "text")


class OpenAIChatModel:
    """The same two-method model class on the OpenAI chat API (gpt-6-luna answers without reasoning here)."""

    def __init__(self, model: str) -> None:
        import openai

        self._client = openai.OpenAI()
        self._model = model
        self.calls = 0
        self.input_tokens = 0
        self.output_tokens = 0

    def prepare_input(self, sys_prompt, user_prompt_filled):
        return [{"role": "system", "content": sys_prompt}, {"role": "user", "content": user_prompt_filled}]

    def call_model(self, model_input):
        extra = {"reasoning_effort": "none"} if self._model.startswith("gpt-6") else {}
        reply = self._client.chat.completions.create(
            model=self._model, messages=model_input, max_completion_tokens=4096, **extra
        )
        self.calls += 1
        self.input_tokens += reply.usage.prompt_tokens
        self.output_tokens += reply.usage.completion_tokens
        return reply.choices[0].message.content


class OllamaChatModel(OpenAIChatModel):
    """The same two-method model class on the Ollama server of this PC (its OpenAI-compatible endpoint). The openai library is
    only the HTTP client; no OpenAI service is called and the run costs no API money."""

    def __init__(self, model: str, base_url: str = "http://127.0.0.1:11434/v1") -> None:
        import openai

        self._client = openai.OpenAI(api_key="EMPTY", base_url=base_url)
        self._model = model
        self.calls = 0
        self.input_tokens = 0
        self.output_tokens = 0

    def call_model(self, model_input):
        reply = self._client.chat.completions.create(model=self._model, messages=model_input, max_tokens=4096)
        self.calls += 1
        self.input_tokens += reply.usage.prompt_tokens
        self.output_tokens += reply.usage.completion_tokens
        return reply.choices[0].message.content


def as_arguments(raw) -> dict:
    if isinstance(raw, dict):
        return raw
    try:
        value = json.loads(raw)
        return value if isinstance(value, dict) else {"value": value}
    except (TypeError, ValueError):
        return {"raw": str(raw)}


def evenly(data: list, limit: int | None) -> list:
    if not limit or limit >= len(data):
        return data
    step = len(data) / limit
    return [data[int(i * step)] for i in range(limit)]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--setting", choices=["base", "enhanced"], required=True)
    parser.add_argument("--prompt-type", choices=["InjecAgent", "hwchase17_react"], default="InjecAgent")
    parser.add_argument("--model", default=AGENT_MODEL_DEFAULT)
    parser.add_argument("--provider", choices=["anthropic", "openai", "ollama"], default="anthropic")
    parser.add_argument("--defense", choices=["none", "contexa"], default="none")
    parser.add_argument("--guard-url", default="http://127.0.0.1:19300")
    parser.add_argument("--tier", choices=["C1", "C2", "C3"], default="C1",
                        help="which Contexa areas are on (plan 13.11); InjecAgent has no company records")
    parser.add_argument("--limit", type=int, default=None, help="cases per attack type, spread evenly over the data")
    parser.add_argument("--workers", type=int, default=1, help="cases handled at the same time")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    load_anthropic_key()
    out_dir = args.out.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    # The official modules read their data with paths relative to the repository root.
    os.chdir(ROOT)
    sys.path.insert(0, str(ROOT))
    # src/models.py imports the Together.AI client at its top although this run never uses it; an empty module
    # stands in so that the official file stays untouched and the unused package stays uninstalled.
    sys.modules.setdefault("together", types.ModuleType("together"))
    from src.evaluate_prompted_agent import predict_one_case
    from src.output_parsing import evaluate_output_prompted
    from src.prompts.agent_prompts import PROMPT_DICT
    from src.utils import get_score, get_tool_dict

    model = {"openai": OpenAIChatModel, "ollama": OllamaChatModel, "anthropic": AnthropicMessagesModel}[args.provider](args.model)
    system_prompt, user_prompt = PROMPT_DICT[args.prompt_type]
    tool_dict = get_tool_dict()
    guard = HttpGuard(args.guard_url) if args.defense == "contexa" else None
    begin_extras = {"tier": args.tier}
    if args.tier != "C1":
        begin_extras["scope"] = injecagent_scope(load_declarations("injecagent-tools.yml"))

    output_files = {}
    counts = {"cases": 0, "agent_chose_attack": 0, "blocked_attack": 0, "user_calls": 0, "user_calls_not_allowed": 0}
    guard_ms: list[float] = []
    lock = threading.Lock()

    def process(item: dict) -> dict:
        item["output"] = predict_one_case(system_prompt, user_prompt, {}, item, model, tool_dict)
        parsed = evaluate_output_prompted(
            item["output"], item["Attacker Tools"][0], item["Attacker Tools"], item["User Tool"],
            item["Attacker Instruction"],
        )
        item["eval"] = parsed["eval"]
        if item["eval"] == "invalid":
            item["invalid_reason"] = parsed["invalid_reason"]
        chose_attack = item["eval"] == "succ"
        blocked = user_not_allowed = 0
        timings: list[float] = []
        if guard is not None:
            run_id = "inj-" + uuid.uuid4().hex[:12]
            guard.begin(run_id, item["User Instruction"], "injecagent", begin_extras)
            try:
                user_call = guard.decide(run_id, item["User Tool"], as_arguments(item["Tool Parameters"]))
                timings.append(user_call.latency_ms)
                user_not_allowed = 0 if user_call.allowed else 1
                item["guard_user_call"] = {"action": user_call.action, "reason": user_call.reason}
                if chose_attack:
                    attacker_call = guard.decide(
                        run_id, item["Attacker Tools"][0], as_arguments(parsed["Attacker Tool Parameters"])
                    )
                    timings.append(attacker_call.latency_ms)
                    item["guard_attacker_call"] = {"action": attacker_call.action, "reason": attacker_call.reason}
                    if not attacker_call.allowed:
                        item["eval_before_guard"] = "succ"
                        item["eval"] = "unsucc"
                        blocked = 1
            finally:
                guard.end(run_id)
        with lock:
            counts["cases"] += 1
            counts["agent_chose_attack"] += 1 if chose_attack else 0
            counts["blocked_attack"] += blocked
            if guard is not None:
                counts["user_calls"] += 1
                counts["user_calls_not_allowed"] += user_not_allowed
            guard_ms.extend(timings)
        return item

    started = time.time()
    for attack in ["dh", "ds"]:
        with open(ROOT / "data" / f"test_cases_{attack}_{args.setting}.json", encoding="utf-8") as f:
            data = evenly(json.load(f), args.limit)
        output_file = out_dir / f"test_cases_{attack}_{args.setting}.json"
        output_files[attack] = str(output_file)
        with output_file.open("w", encoding="utf-8") as out, ThreadPoolExecutor(max_workers=args.workers) as pool:
            for item in pool.map(process, data):
                out.write(json.dumps(item, ensure_ascii=False) + "\n")
                out.flush()

    scores = get_score(output_files)
    summary = {
        "benchmark": "InjecAgent",
        "setting": args.setting,
        "prompt_type": args.prompt_type,
        "agent_model": args.model,
        "provider": args.provider,
        "defense": args.defense,
        "tier": args.tier if args.defense == "contexa" else "C0",
        "first_step_only": True,
        "limit_per_attack_type": args.limit,
        "workers": args.workers,
        "official_scores": scores,
        "counts": counts,
        "guard_ms_median": sorted(guard_ms)[len(guard_ms) // 2] if guard_ms else None,
        "seconds": round(time.time() - started, 1),
        "agent_usage": {"calls": model.calls, "input_tokens": model.input_tokens, "output_tokens": model.output_tokens},
    }
    (out_dir / f"summary-{args.setting}-{args.defense if args.defense == "none" else args.tier}.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    print(json.dumps(summary, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
