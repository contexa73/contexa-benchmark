"""Checks that the agent model answers and accepts the arguments AgentDojo's Anthropic element sends."""
import asyncio
import sys

import anthropic

from common import AGENT_MODEL_DEFAULT, load_anthropic_key

load_anthropic_key()
model = sys.argv[1] if len(sys.argv) > 1 else AGENT_MODEL_DEFAULT


async def main() -> None:
    client = anthropic.AsyncAnthropic()
    tool = {
        "name": "get_balance",
        "description": "Returns the account balance.",
        "input_schema": {"type": "object", "properties": {}},
    }
    reply = await client.messages.create(
        model=model,
        max_tokens=256,
        temperature=0.0,
        system="You are a banking assistant.",
        messages=[{"role": "user", "content": "What is my balance? Use the tool."}],
        tools=[tool],
    )
    kinds = [block.type for block in reply.content]
    print("model:", reply.model, "| stop:", reply.stop_reason, "| blocks:", kinds)
    print("usage: in", reply.usage.input_tokens, "out", reply.usage.output_tokens)


asyncio.run(main())
