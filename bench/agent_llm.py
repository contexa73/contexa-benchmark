"""Adapter that lets AgentDojo's own AnthropicLLM element talk to the current Anthropic SDK.

AgentDojo 0.1.35 sends `temperature=0.0` with every request, but the Anthropic API refuses it for the current models
(400 "`temperature` is deprecated for this model", checked 2026-10-28 for claude-haiku-5-5). The adapter drops that one
argument and tallies the token usage of each answer; it changes nothing else, and no AgentDojo file is edited.
The Anthropic SDK is pinned below 1.0 (0.125.0): from 2.0 on `MessageParam` is no longer a class, and AgentDojo builds
its messages by calling it.
"""
from __future__ import annotations

from dataclasses import dataclass

import anthropic
from agentdojo.agent_pipeline.llms.anthropic_llm import AnthropicLLM
from agentdojo.models import MODEL_NAMES


@dataclass
class UsageTally:
    calls: int = 0
    input_tokens: int = 0
    output_tokens: int = 0
    cache_read_tokens: int = 0
    aborts: int = 0

    def add(self, usage) -> None:
        self.calls += 1
        self.input_tokens += getattr(usage, "input_tokens", 0) or 0
        self.output_tokens += getattr(usage, "output_tokens", 0) or 0
        self.cache_read_tokens += getattr(usage, "cache_read_input_tokens", 0) or 0

    def snapshot(self) -> dict:
        return {
            "calls": self.calls,
            "input_tokens": self.input_tokens,
            "output_tokens": self.output_tokens,
            "cache_read_tokens": self.cache_read_tokens,
            "aborts": self.aborts,
        }


class _Stream:
    def __init__(self, stream, tally: UsageTally) -> None:
        self._stream = stream
        self._tally = tally

    async def get_final_message(self):
        message = await self._stream.get_final_message()
        self._tally.add(message.usage)
        return message

    def __getattr__(self, name):
        return getattr(self._stream, name)


class _StreamManager:
    def __init__(self, manager, tally: UsageTally) -> None:
        self._manager = manager
        self._tally = tally

    async def __aenter__(self):
        return _Stream(await self._manager.__aenter__(), self._tally)

    async def __aexit__(self, *exc):
        return await self._manager.__aexit__(*exc)


class _Messages:
    def __init__(self, messages, tally: UsageTally) -> None:
        self._messages = messages
        self._tally = tally

    def stream(self, **kwargs):
        kwargs.pop("temperature", None)
        return _StreamManager(self._messages.stream(**kwargs), self._tally)

    def __getattr__(self, name):
        return getattr(self._messages, name)


class _Client:
    def __init__(self, client, tally: UsageTally) -> None:
        self._client = client
        self.messages = _Messages(client.messages, tally)

    def __getattr__(self, name):
        return getattr(self._client, name)


def build_local_llm(model: str, base_url: str = "http://127.0.0.1:11434/v1"):
    """AgentDojo's own OpenAILLM element on a local Ollama server (OpenAI-compatible, native tool calls)."""
    # The openai library is only the HTTP client of AgentDojo's element; the server is the Ollama on this PC, no OpenAI service.
    import json

    import openai
    from agentdojo.agent_pipeline.errors import AbortAgentError
    from agentdojo.agent_pipeline.llms.openai_llm import OpenAILLM

    tally = UsageTally()

    class _AbortOnMalformedArguments(OpenAILLM):
        """A small model often writes a tool call whose arguments are not JSON; the official element then raises and the whole
        benchmark process dies. AgentDojo's own way to stop one agent run is AbortAgentError; the aborts are counted."""

        def query(self, query, runtime, env=None, messages=(), extra_args=None):
            try:
                return super().query(query, runtime, env, messages, extra_args or {})
            except json.JSONDecodeError as error:
                tally.aborts += 1
                raise AbortAgentError(f"malformed tool-call arguments: {str(error)[:80]}", list(messages), env)

    # The pipeline name becomes a folder name of the logs, and a colon is not allowed in one on Windows.
    name = model.replace(":", "-")
    MODEL_NAMES.setdefault(name, "AI assistant")
    llm = _AbortOnMalformedArguments(openai.OpenAI(api_key="EMPTY", base_url=base_url), model)
    llm.name = name
    return llm, tally


class _Completions:
    def __init__(self, completions, tally: UsageTally) -> None:
        self._completions = completions
        self._tally = tally

    def create(self, **kwargs):
        reply = self._completions.create(**kwargs)
        usage = getattr(reply, "usage", None)
        if usage is not None:
            self._tally.calls += 1
            self._tally.input_tokens += usage.prompt_tokens or 0
            self._tally.output_tokens += usage.completion_tokens or 0
        return reply


class _Chat:
    def __init__(self, chat, tally: UsageTally) -> None:
        self.completions = _Completions(chat.completions, tally)


class _OpenAIClient:
    def __init__(self, client, tally: UsageTally) -> None:
        self._client = client
        self.chat = _Chat(client.chat, tally)

    def __getattr__(self, name):
        return getattr(self._client, name)


def build_openai_llm(model: str):
    """AgentDojo's own OpenAILLM element on the OpenAI API, with the usage tally of its answers."""
    import json

    import openai
    from agentdojo.agent_pipeline.errors import AbortAgentError
    from agentdojo.agent_pipeline.llms.openai_llm import OpenAILLM

    class _AbortOnMalformedArguments(OpenAILLM):
        """A model sometimes streams a runaway, unparsable tool-call argument; the official element then raises and
        the whole benchmark process dies. AgentDojo's own way to stop one agent run is AbortAgentError (the task then
        scores on what the run did so far); the aborts are counted in the usage tally and reported."""

        def query(self, query, runtime, env=None, messages=(), extra_args=None):
            try:
                return super().query(query, runtime, env, messages, extra_args or {})
            except json.JSONDecodeError as error:
                tally.aborts += 1
                raise AbortAgentError(f"malformed tool-call arguments: {str(error)[:80]}", list(messages), env)

    MODEL_NAMES.setdefault(model, "GPT-4")
    tally = UsageTally()
    # gpt-6-luna refuses function tools together with a reasoning effort on chat completions; "none" is the setting it asks
    # for. Models without reasoning (the gpt-4o family) do not take the argument at all.
    effort = "none" if model.startswith("gpt-6") else None
    llm = _AbortOnMalformedArguments(_OpenAIClient(openai.OpenAI(), tally), model, reasoning_effort=effort)
    llm.name = model
    return llm, tally


def build_agent_llm(model: str, max_tokens: int = 1024) -> tuple[AnthropicLLM, UsageTally]:
    """The official AnthropicLLM element for `model`, with the usage tally of its answers."""
    # The attacks address the model by a prose name taken from the pipeline name; a model AgentDojo does not list
    # yet is registered the way the project registers a new model (one line in its model table).
    MODEL_NAMES.setdefault(model, "Claude")
    tally = UsageTally()
    llm = AnthropicLLM(_Client(anthropic.AsyncAnthropic(), tally), model, max_tokens=max_tokens)
    llm.name = model
    return llm, tally
