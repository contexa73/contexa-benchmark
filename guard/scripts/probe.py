"""Probes of single engine areas of a running guard (real model calls, a few).

    python scripts/probe.py lowered              # a run of stage C1 on a C2 server: none of C2's data-fed areas
    python scripts/probe.py call-limit --calls 5 # needs a guard started with a low call limit (see below)

call-limit: start the guard with
    CONTEXA_SECURITY_ZEROTRUST_AGENT_CALLLIMIT_PERDELEGATIONPERMINUTE=2 BENCH_TIER=C2 scripts/start-guard.sh
then the third call of a minute of one delegation is refused by the engine's agent call guard (AGENT_CALL_LIMIT) before any
model is asked.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.request
import uuid


def post(base: str, path: str, body: dict) -> dict:
    request = urllib.request.Request(base + path, data=json.dumps(body).encode("utf-8"),
                                     headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=300) as response:
        return json.loads(response.read().decode("utf-8") or "{}")


def show(answer: dict, started: float) -> None:
    print(f"  -> {answer.get('action'):9} engine={answer.get('engineAction')} layer={answer.get('layer')} "
          f"model={answer.get('model')} tier={answer.get('tier')} analysed={answer.get('analysed')} "
          f"unresolved={answer.get('unresolved')} {(time.perf_counter() - started) * 1000:.0f} ms")
    print("     facts:", json.dumps(answer.get("facts"), ensure_ascii=False)[:400])


def lowered(base: str) -> None:
    run = "probe-lowered-" + uuid.uuid4().hex[:6]
    begin = post(base, "/guard/begin", {"runId": run, "goal": "Check my current account balance.", "suite": "banking",
                                        "tier": "C1", "records": {"known_counterparties": ["GB29NWBK60161331926819"]},
                                        "scope": {"family": "PERSONAL_BANKING", "allowedOperations": ["READ"]}})
    print("begin:", begin)
    started = time.perf_counter()
    show(post(base, "/guard/decide", {"runId": run, "tool": "get_balance", "arguments": {}}), started)
    post(base, "/guard/end", {"runId": run})


def call_limit(base: str, calls: int) -> None:
    run = "probe-limit-" + uuid.uuid4().hex[:6]
    begin = post(base, "/guard/begin", {"runId": run, "goal": "Check my current account balance.", "suite": "banking"})
    print("begin:", begin)
    for number in range(1, calls + 1):
        started = time.perf_counter()
        print(f"call {number}")
        show(post(base, "/guard/decide", {"runId": run, "tool": "get_balance", "arguments": {}}), started)
    post(base, "/guard/end", {"runId": run})


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("probe", choices=["lowered", "call-limit"])
    parser.add_argument("--guard", default="http://127.0.0.1:19300")
    parser.add_argument("--calls", type=int, default=5)
    options = parser.parse_args()
    base = options.guard.rstrip("/")
    if options.probe == "lowered":
        lowered(base)
    else:
        call_limit(base, options.calls)
    return 0


if __name__ == "__main__":
    sys.exit(main())
