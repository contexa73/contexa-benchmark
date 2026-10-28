"""Shared setup for the Contexa benchmark runners (S track).

The Anthropic key is read from the key file of the Contexa repository and exported only into this process;
it is never printed or written anywhere.
"""
from __future__ import annotations

import os
import re
from pathlib import Path

KEY_FILE = Path("E:/projects/contexa/docs/ANTHROPIC_API_KEY.MD")
AGENT_MODEL_DEFAULT = "claude-haiku-5-5"


def load_anthropic_key() -> None:
    """Exports ANTHROPIC_API_KEY from the repository key file when the environment value is not a real key."""
    current = os.environ.get("ANTHROPIC_API_KEY", "")
    if len(current) > 40:
        return
    text = KEY_FILE.read_text(encoding="utf-8", errors="ignore")
    match = re.search(r"sk-ant-[A-Za-z0-9_-]+", text)
    if not match:
        raise RuntimeError("no Anthropic key found in the key file")
    os.environ["ANTHROPIC_API_KEY"] = match.group(0)
