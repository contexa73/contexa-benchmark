"""Checks, in the Ollama server log, whether the victim agent always saw its whole input.

The log marks a request `truncated = 1` for two different things, and only the first one matters for fairness:
  input cut     the input (task.n_tokens) did not fit the per-slot context, so the agent did not see all of its prompt
  runaway       the input fitted, but the model kept generating (thousands of tokens, minutes of GPU) until the context was full

The check counts the requests served while a model with the given per-slot context was loaded, over ALL such loads from `--since`
on (the model is unloaded when idle and loaded again; a count from the last load only would miss everything before it).

    python check_truncation.py                              every load with a slot context of 16384, from its first one
    python check_truncation.py --since 2026-10-28T17:00     only the log lines from that local time on (the log's own clock, KST)
Exit status 1 when any input was cut.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

STAMP = re.compile(r"(?:^time=|\[GIN\] )(\d{4})[-/](\d{2})[-/](\d{2})[T -]+(\d{2}):(\d{2}):(\d{2})")
NEW_PROMPT = re.compile(r"slot\s+operator\(\): id\s+(\d+) \| task (\d+) \| new prompt, n_ctx_slot = (\d+), n_keep = \d+, task\.n_tokens = (\d+)")
RELEASE = re.compile(r"slot\s+release: id\s+(\d+) \| task (\d+) \| stop processing: n_tokens = (\d+), truncated = (\d)")
LOAD = re.compile(r"n_ctx_slot = (\d+), kv_unified")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--context", type=int, default=16384, help="per-slot context of the loads to count")
    parser.add_argument("--since", default=None, help="local time in the log's clock, YYYY-MM-DDTHH:MM[:SS]")
    parser.add_argument("--output-cap", type=int, default=2048, help="the victim's output limit (num_predict of its model)")
    parser.add_argument("--log", type=Path, default=Path(os.environ.get("LOCALAPPDATA", ".")) / "Ollama" / "server.log")
    args = parser.parse_args()

    since = None
    if args.since:
        digits = re.findall(r"\d+", args.since)
        since = tuple(int(d) for d in (digits + ["0", "0"])[:6])
    current_context = None
    stamp = (0, 0, 0, 0, 0, 0)
    prompt_of: dict[int, int] = {}
    prompts: list[int] = []
    cut_inputs: list[int] = []
    near_limit: list[int] = []
    runaways: list[int] = []
    loads = 0
    for line in args.log.read_text(encoding="utf-8", errors="replace").splitlines():
        found = STAMP.search(line)
        if found:
            stamp = tuple(int(g) for g in found.groups())
        load = LOAD.search(line)
        if load and "new prompt" not in line:
            current_context = int(load.group(1))
            if current_context == args.context and (since is None or stamp >= since):
                loads += 1
            continue
        if current_context != args.context or (since is not None and stamp < since):
            continue
        new = NEW_PROMPT.search(line)
        if new:
            prompt_of[int(new.group(2))] = int(new.group(4))
            continue
        released = RELEASE.search(line)
        if released:
            task = int(released.group(2))
            prompt = prompt_of.pop(task, None)
            if prompt is None:
                continue
            prompts.append(prompt)
            if released.group(4) != "0":
                if prompt >= args.context:
                    cut_inputs.append(prompt)
                elif prompt > args.context - args.output_cap:
                    near_limit.append(prompt)
                else:
                    runaways.append(prompt)
    if not prompts and not loads:
        raise SystemExit(f"no load with a slot context of {args.context} in {args.log}")
    prompts.sort()
    print(f"loads with a slot context of {args.context}: {loads}; requests served: {len(prompts)}")
    if prompts:
        print(f"input length (tokens): median {prompts[len(prompts) // 2]}, p95 {prompts[int(len(prompts) * 0.95) - 1]}, max {prompts[-1]}")
    print(f"requests whose INPUT was cut (input >= {args.context}): {len(cut_inputs)}" + (f"  inputs {cut_inputs}" if cut_inputs else ""))
    print(f"inputs so near the limit that the answer had little room (input > {args.context - args.output_cap}): {len(near_limit)}"
          + (f"  inputs {near_limit}" if near_limit else ""))
    print(f"runaway generations (input left room for the whole answer, output still filled the context): {len(runaways)}"
          + (f"  inputs {runaways}" if runaways else ""))
    sys.exit(1 if cut_inputs else 0)


if __name__ == "__main__":
    main()
