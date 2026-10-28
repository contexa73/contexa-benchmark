"""The owner who confirms a request the guard challenged, simulated by a model (plan 13.11, area A5-2 of Contexa).

A CHALLENGE of Contexa asks the person accountable for the agent to confirm the request on a confirmation screen. The
benchmark has no person; without one every challenged call would count as refused, which is the worst case for work.
This asks claude-haiku-5-5 to play the owner, and shows it only what a person sees there: their own request, the call the
agent wants to make, and what they know from their own records (the clean initial environment). It is never shown the
attack, the attacker's goal, the ground truth of the task, the security system's reason or any tool result. Its prompt is
the frozen file declarations/owner-prompt.txt (hash in freeze.json). Its answers are counted in the result files, and the
numbers are also published without any owner (every CHALLENGE refused).
"""
from __future__ import annotations

import json
from pathlib import Path

import anthropic

PROMPT = (Path(__file__).resolve().parent.parent / "declarations" / "owner-prompt.txt").read_text(encoding="utf-8")
MODEL = "claude-haiku-5-5"


def known_text(records: dict[str, list[str]]) -> str:
    if not records:
        return "(nothing recorded)"
    return "\n".join(f"- {kind.replace('known_', '')}: {', '.join(values[:60])}" for kind, values in records.items() if values)


class SimulatedOwner:
    def __init__(self, model: str = MODEL) -> None:
        self._client = anthropic.Anthropic()
        self._model = model
        self.asked = 0
        self.approved = 0
        self.input_tokens = 0
        self.output_tokens = 0

    def review(self, goal: str, tool: str, arguments: dict, records: dict[str, list[str]]) -> bool:
        prompt = PROMPT.format(goal=goal, tool=tool, arguments=json.dumps(arguments, ensure_ascii=False, default=str)[:1500],
                               known=known_text(records))
        reply = self._client.messages.create(model=self._model, max_tokens=16,
                                             messages=[{"role": "user", "content": prompt}])
        self.asked += 1
        self.input_tokens += reply.usage.input_tokens
        self.output_tokens += reply.usage.output_tokens
        answer = "".join(block.text for block in reply.content if block.type == "text").strip().upper()
        approved = answer.startswith("APPROVE")
        self.approved += 1 if approved else 0
        return approved


class ApproveAllOwner:
    """The other end of the range: an owner who confirms everything the security system asks (a person who clicks through).
    With no owner at all, every challenged call is refused; the real owner lies between the two."""

    def __init__(self) -> None:
        self.asked = 0
        self.approved = 0
        self.input_tokens = 0
        self.output_tokens = 0

    def review(self, goal: str, tool: str, arguments: dict, records: dict[str, list[str]]) -> bool:
        self.asked += 1
        self.approved += 1
        return True
