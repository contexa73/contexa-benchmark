"""The one element the Contexa runs add to AgentDojo's pipeline: a tool executor that asks the guard first.

`ContexaGuardedToolsExecutor` is AgentDojo's own `ToolsExecutor`; the only difference is that a call is asked of the
guard before the tool runs. A call the guard does not allow does not run, and the agent gets a tool error instead,
the way any refused tool call reaches an agent. Nothing in the AgentDojo package is edited.
"""
from __future__ import annotations

import json
import threading
import time
import uuid
from collections.abc import Callable, Sequence
from pathlib import Path

from agentdojo.agent_pipeline.tool_execution import ToolsExecutor
from agentdojo.functions_runtime import EmptyEnv, Env, FunctionsRuntime
from agentdojo.logging import Logger
from agentdojo.types import ChatMessage

from guards import Guard
from records import owner_context

DENIAL_TEXT = {
    "BLOCK": "This tool call was blocked by the security policy and was not executed.",
    "CHALLENGE": "This tool call needs the user's confirmation, which is not available here; it was not executed.",
    "ESCALATE": "This tool call was held for a security review and was not executed.",
}


class _GuardedRuntime:
    """Wraps the runtime so that `run_function` asks the guard first; everything else passes through."""

    def __init__(self, runtime: FunctionsRuntime, check) -> None:
        self._runtime = runtime
        self._check = check

    def run_function(self, env, function, kwargs, raise_on_error=False):
        refusal = self._check(function, kwargs)
        if refusal is not None:
            return "", refusal
        return self._runtime.run_function(env, function, kwargs, raise_on_error)

    def __getattr__(self, name):
        return getattr(self._runtime, name)


class ContexaGuardedToolsExecutor(ToolsExecutor):
    def __init__(self, guard: Guard, decisions_file: Path, extras_for_suite: Callable[[str], dict] | None,
                 *args, owner=None, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self._guard = guard
        self._extras_for_suite = extras_for_suite
        self._owner = owner
        self._records: dict = {}
        self._file = decisions_file
        self._lock = threading.Lock()
        self._run_id: str | None = None
        self._goal = ""

    def _context(self) -> dict:
        try:
            return dict(Logger.get().context)
        except Exception:  # a null logger has no context
            return {}

    def _start_run_if_new(self, query: str, messages: Sequence[ChatMessage]) -> None:
        assistant_turns = sum(1 for message in messages if message["role"] == "assistant")
        if self._run_id is not None and assistant_turns > 1:
            return
        if self._run_id is not None:
            self._guard.end(self._run_id)
        self._run_id = "run-" + uuid.uuid4().hex[:12]
        self._goal = query
        suite = str(self._context().get("suite_name", ""))
        extras = self._extras_for_suite(suite) if self._extras_for_suite else None
        self._records = owner_context(suite) if self._owner is not None and suite else {}
        self._guard.begin(self._run_id, query, suite, extras)

    def _check(self, tool: str, args: dict) -> str | None:
        run_id = self._run_id
        started = time.perf_counter()
        verdict = self._guard.decide(run_id, tool, dict(args))
        owner_answer = None
        confirmation_id = verdict.detail.get("confirmationId") if verdict.action == "CHALLENGE" else None
        if verdict.action == "CHALLENGE" and not confirmation_id and self._owner is not None:
            # A CHALLENGE decided in the request is answered like a person's step-up; the agent gate asks for the
            # confirmation of the agent's accountable person when the same call comes again, and that answer is the
            # stored decision (no new analysis). The agent sends the call again, as an agent does.
            again = self._guard.decide(run_id, tool, dict(args))
            if again.detail.get("confirmationId"):
                confirmation_id = again.detail["confirmationId"]
        if confirmation_id and self._owner is not None:
            # The person accountable for the agent is asked to confirm; the simulated owner sees what a person sees.
            approved = self._owner.review(self._goal, tool, dict(args), self._records)
            owner_answer = "APPROVE" if approved else "REJECT"
            self._guard.confirm(confirmation_id, approved, "simulated owner")
            if approved:
                first = verdict
                verdict = self._guard.decide(run_id, tool, dict(args), confirmation_id)
                verdict.detail["firstAnswer"] = {"action": first.action, "reason": first.reason}
        wall_ms = (time.perf_counter() - started) * 1000
        context = self._context()
        record = {
            "run_id": run_id,
            "suite": context.get("suite_name"),
            "user_task": context.get("user_task_id"),
            "injection_task": context.get("injection_task_id"),
            "attack": context.get("attack_type"),
            "tool": tool,
            "arguments": args,
            "action": verdict.action,
            "reason": verdict.reason,
            "wall_ms": round(wall_ms, 1),
            "owner": owner_answer,
            "detail": verdict.detail,
        }
        with self._lock, self._file.open("a", encoding="utf-8") as out:
            out.write(json.dumps(record, ensure_ascii=False, default=str) + "\n")
        if verdict.allowed:
            return None
        return DENIAL_TEXT.get(verdict.action, DENIAL_TEXT["BLOCK"])

    def query(
        self,
        query: str,
        runtime: FunctionsRuntime,
        env: Env = EmptyEnv(),
        messages: Sequence[ChatMessage] = [],
        extra_args: dict = {},
    ):
        if messages and messages[-1]["role"] == "assistant" and messages[-1]["tool_calls"]:
            self._start_run_if_new(query, messages)
        _, _, env, messages, extra_args = super().query(
            query, _GuardedRuntime(runtime, self._check), env, messages, extra_args
        )
        return query, runtime, env, messages, extra_args

    def close(self) -> None:
        if self._run_id is not None:
            self._guard.end(self._run_id)
            self._run_id = None
