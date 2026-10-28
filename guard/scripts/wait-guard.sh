#!/usr/bin/env bash
# Waits until the guard answers /guard/health (default 240 seconds), then prints the health answer.
set -uo pipefail
port="${BENCH_GUARD_PORT:-19300}"
limit="${1:-240}"
for ((i = 0; i < limit; i++)); do
  if answer=$(curl -s -m 3 "http://127.0.0.1:${port}/guard/health") && [ -n "$answer" ]; then
    echo "$answer"
    exit 0
  fi
  sleep 1
done
echo "the guard did not answer within ${limit} seconds" >&2
exit 1
