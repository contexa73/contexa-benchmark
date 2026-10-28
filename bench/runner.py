"""Runs AgentDojo's official benchmark with and without the Contexa guard.

The pipeline is AgentDojo's own `AgentPipeline.from_config` result. The Contexa run differs in exactly one element: the
`ToolsExecutor` inside the `ToolsExecutionLoop` is replaced by `ContexaGuardedToolsExecutor`. Tasks, attacks, scoring and
logging are AgentDojo's, unchanged.

    python runner.py --suite banking --attack important_instructions --defense none --logdir ../runs/spike
    python runner.py --suite banking --attack important_instructions --defense contexa --guard-url http://127.0.0.1:19300
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import time
import warnings
from pathlib import Path

from agentdojo.agent_pipeline.agent_pipeline import AgentPipeline, PipelineConfig
from agentdojo.agent_pipeline.tool_execution import ToolsExecutionLoop, ToolsExecutor
from agentdojo.attacks.attack_registry import load_attack
from agentdojo.benchmark import (
    benchmark_suite_with_injections,
    benchmark_suite_without_injections,
    run_task_with_injection_tasks,
)
from agentdojo.logging import OutputLogger
from agentdojo.task_suite.load_suites import get_suite

from agent_llm import build_agent_llm, build_local_llm, build_openai_llm
from common import AGENT_MODEL_DEFAULT, load_anthropic_key
from guarded_executor import ContexaGuardedToolsExecutor
from records import begin_payload_extras
from guards import HttpGuard
from owner import ApproveAllOwner, SimulatedOwner


def build_pipeline(model: str, provider: str, guard_url: str | None, logdir: Path, tier: str = "C1",
                   builtin_defense: str | None = None, owner=None, owner_kind: str | None = None):
    if provider == "ollama":
        llm, tally = build_local_llm(model)
    elif provider == "openai":
        llm, tally = build_openai_llm(model)
    else:
        llm, tally = build_agent_llm(model)
    pipeline = AgentPipeline.from_config(
        PipelineConfig(llm=llm, model_id=None, defense=builtin_defense, system_message_name=None, system_message=None)
    )
    executor = None
    if guard_url is not None:
        loop = next(element for element in pipeline.elements if isinstance(element, ToolsExecutionLoop))
        index = next(i for i, element in enumerate(loop.elements) if isinstance(element, ToolsExecutor))
        executor = ContexaGuardedToolsExecutor(
            HttpGuard(guard_url),
            logdir / "contexa-decisions.jsonl",
            (lambda suite: {"tier": tier, **begin_payload_extras(suite)}) if tier != "C1" else (lambda suite: {"tier": tier}),
            loop.elements[index].output_formatter,
            owner=owner,
        )
        loop.elements[index] = executor
        # AgentDojo skips a task whose log already exists under the same pipeline name; a guarded run is a different
        # pipeline and must not pick up the logs of the unguarded one.
        pipeline.name = f"{pipeline.name}-contexa-{tier}{'-owner-' + owner_kind if owner_kind else ''}"
    return pipeline, tally, executor


def freeze_record() -> dict | None:
    """The freeze file this run belongs to (BENCH_FREEZE), as its path and sha256; None when the run is not under a freeze."""
    path = os.environ.get("BENCH_FREEZE")
    if not path:
        return None
    data = Path(path).read_bytes()
    return {"file": Path(path).name, "sha256": hashlib.sha256(data).hexdigest()}


def summarize(results, with_attack: bool) -> dict:
    utility = list(results["utility_results"].values())
    summary = {"utility": sum(utility) / len(utility) if utility else None, "utility_cases": len(utility)}
    if with_attack:
        security = list(results["security_results"].values())
        summary["targeted_asr"] = sum(security) / len(security) if security else None
        summary["attack_cases"] = len(security)
        summary["injection_tasks_as_user_tasks"] = {
            key: bool(value) for key, value in results["injection_tasks_utility_results"].items()
        }
    return summary


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--suite", required=True, choices=["workspace", "banking", "slack", "travel"])
    parser.add_argument("--benchmark-version", default="v1.2.2")
    parser.add_argument("--model", default=AGENT_MODEL_DEFAULT)
    parser.add_argument("--provider", choices=["anthropic", "openai", "ollama"], default="anthropic")
    parser.add_argument("--attack", default=None, help="attack name, for example important_instructions; omit for no attack")
    parser.add_argument("--defense", choices=["none", "contexa"], default="none")
    parser.add_argument("--owner", choices=["llm", "all"], default=None,
                        help="who confirms a challenged call: llm = a simulated owner (claude-haiku-5-5, sees only the "
                             "owner's request, the call and the owner's own records), all = an owner who confirms everything; "
                             "without it a challenged call is refused")
    parser.add_argument("--builtin-defense", default=None,
                        choices=["tool_filter", "spotlighting_with_delimiting", "repeat_user_prompt", "transformers_pi_detector"],
                        help="one of AgentDojo's own published defenses; combined with --defense contexa when both are given")
    parser.add_argument("--guard-url", default="http://127.0.0.1:19300")
    parser.add_argument("--tier", choices=["C1", "C2", "C3"], default="C1",
                        help="which Contexa areas are on (plan 13.11): C1 minimal, C2 configured, C3 full")
    parser.add_argument("--logdir", type=Path, required=True)
    parser.add_argument("--user-tasks", nargs="*", default=[])
    parser.add_argument("--injection-tasks", nargs="*", default=[])
    parser.add_argument("--force-rerun", action="store_true")
    parser.add_argument("--panel", type=Path, default=None,
                        help="a fixed panel file (panels/dev-v1.json): runs only its user tasks of --suite, each against the "
                             "panel's injection tasks, without the injection tasks run as user tasks first, then the panel's "
                             "benign tasks when --attack is not given")
    parser.add_argument("--split", type=Path, default=None,
                        help="the development/test split of the benchmark (panels/split-v2.json); needs --part")
    parser.add_argument("--part", choices=["dev", "test"], default=None,
                        help="with --split: dev runs the development user tasks of --suite, test the held-out ones; both against "
                             "the suite's injection tasks listed in the split file")
    args = parser.parse_args()
    if (args.split is None) != (args.part is None):
        parser.error("--split and --part go together")
    if args.split is not None and args.panel is not None:
        parser.error("--split and --panel are two ways to choose the tasks; give one")

    load_anthropic_key()
    warnings.filterwarnings("ignore", category=UserWarning)
    args.logdir.mkdir(parents=True, exist_ok=True)

    suite = get_suite(args.benchmark_version, args.suite)
    guard_url = args.guard_url if args.defense == "contexa" else None
    owner = {"llm": SimulatedOwner, "all": ApproveAllOwner}[args.owner]() if args.owner else None
    pipeline, tally, executor = build_pipeline(args.model, args.provider, guard_url, args.logdir, args.tier,
                                               args.builtin_defense, owner, args.owner)
    user_tasks = args.user_tasks or None
    injection_tasks = args.injection_tasks or None
    panel_part = None
    if args.panel is not None:
        panel_part = json.loads(args.panel.read_text(encoding="utf-8"))["suites"][args.suite]
        user_tasks, injection_tasks = panel_part["user_tasks"], panel_part["injection_tasks"]
    split_sha = None
    if args.split is not None:
        split_data = json.loads(args.split.read_text(encoding="utf-8"))
        split_sha = split_data["sha256"]
        section = split_data["suites"][args.suite]
        user_tasks, injection_tasks = section[f"{args.part}_user_tasks"], section["injection_tasks"]
        # the same branch as a panel: each user task against the listed injection tasks, the injection tasks not run as user tasks
        panel_part = {"user_tasks": user_tasks, "injection_tasks": injection_tasks}

    started = time.time()
    with OutputLogger(str(args.logdir), live=None):
        if panel_part is not None and args.attack is not None:
            attack = load_attack(args.attack, suite, pipeline)
            utility_results, security_results = {}, {}
            for user_task_id in user_tasks:
                utility, security = run_task_with_injection_tasks(
                    suite, pipeline, suite.get_user_task_by_id(user_task_id), attack, args.logdir, args.force_rerun,
                    injection_tasks, args.benchmark_version,
                )
                utility_results.update(utility)
                security_results.update(security)
            results = {"utility_results": utility_results, "security_results": security_results,
                       "injection_tasks_utility_results": {}}
        elif args.attack is None:
            results = benchmark_suite_without_injections(
                pipeline, suite, args.logdir, args.force_rerun, user_tasks=user_tasks,
                benchmark_version=args.benchmark_version,
            )
        else:
            attack = load_attack(args.attack, suite, pipeline)
            results = benchmark_suite_with_injections(
                pipeline, suite, attack, args.logdir, args.force_rerun, user_tasks=user_tasks,
                injection_tasks=injection_tasks, benchmark_version=args.benchmark_version,
            )
    if executor is not None:
        executor.close()

    summary = summarize(results, args.attack is not None)
    summary.update(
        {
            "suite": args.suite,
            "benchmark_version": args.benchmark_version,
            "agent_model": args.model,
            "provider": args.provider,
            "attack": args.attack,
            "defense": args.defense,
            "tier": args.tier if args.defense == "contexa" else "C0",
            "builtin_defense": args.builtin_defense,
            "owner": None if owner is None else {"asked": owner.asked, "approved": owner.approved,
                                                 "input_tokens": owner.input_tokens, "output_tokens": owner.output_tokens},
            "pipeline_name": pipeline.name,
            "user_tasks": user_tasks,
            "injection_tasks": injection_tasks,
            "split": None if split_sha is None else {"file": args.split.name, "sha256": split_sha, "part": args.part},
            "freeze": freeze_record(),
            "seconds": round(time.time() - started, 1),
            "agent_usage": tally.snapshot(),
        }
    )
    (args.logdir / f"summary-{args.suite}-{"attack" if args.attack else "benign"}-{args.defense if args.defense == "none" else args.tier + ("-owner" if owner else "")}.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    print(json.dumps(summary, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
