"""Guards the benchmark can put in front of the agent's tool calls.

A guard answers one question per tool call: may the call run? The no-defense baseline uses no guard at all;
`HttpGuard` asks the Contexa guard application, which decides with the Contexa engine.
"""
from __future__ import annotations

import json
import time
import urllib.request
from dataclasses import dataclass, field
from typing import Protocol


@dataclass
class Verdict:
    action: str  # ALLOW, CHALLENGE, BLOCK or ESCALATE
    reason: str = ""
    latency_ms: float = 0.0
    detail: dict = field(default_factory=dict)

    @property
    def allowed(self) -> bool:
        return self.action == "ALLOW"


class Guard(Protocol):
    def begin(self, run_id: str, goal: str, suite: str, extras: dict | None = None) -> None: ...

    def decide(self, run_id: str, tool: str, args: dict, confirmation_id: str | None = None) -> Verdict: ...

    def confirm(self, confirmation_id: str, approve: bool, note: str = "") -> dict: ...

    def end(self, run_id: str) -> None: ...


class HttpGuard:
    """Asks the Contexa guard application over HTTP (contract: guard/README.md)."""

    def __init__(self, base_url: str, timeout_s: float = 300.0) -> None:
        self._base = base_url.rstrip("/")
        self._timeout = timeout_s

    def _post(self, path: str, body: dict) -> dict:
        request = urllib.request.Request(
            self._base + path,
            data=json.dumps(body, ensure_ascii=False, default=str).encode("utf-8"),
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=self._timeout) as response:
            return json.loads(response.read().decode("utf-8") or "{}")

    def begin(self, run_id: str, goal: str, suite: str, extras: dict | None = None) -> None:
        self._post("/guard/begin", {"runId": run_id, "goal": goal, "suite": suite, **(extras or {})})

    def confirm(self, confirmation_id: str, approve: bool, note: str = "") -> dict:
        return self._post("/guard/confirm", {"confirmationId": confirmation_id, "approve": approve, "note": note})

    def decide(self, run_id: str, tool: str, args: dict, confirmation_id: str | None = None) -> Verdict:
        started = time.perf_counter()
        body = {"runId": run_id, "tool": tool, "arguments": args}
        if confirmation_id:
            body["confirmationId"] = confirmation_id
        answer = self._post("/guard/decide", body)
        elapsed = (time.perf_counter() - started) * 1000
        return Verdict(
            action=str(answer.get("action", "BLOCK")).upper(),
            reason=str(answer.get("reason", "")),
            latency_ms=elapsed,
            detail=answer,
        )

    def end(self, run_id: str) -> None:
        self._post("/guard/end", {"runId": run_id})
