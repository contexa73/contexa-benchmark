#!/usr/bin/env bash
# Stops the guard application that listens on 127.0.0.1:${BENCH_GUARD_PORT:-19300} (Windows: taskkill of its java process)
# and waits until the port is free. A running jar must be stopped before it is rebuilt.
set -uo pipefail
port="${BENCH_GUARD_PORT:-19300}"
pid=$(netstat -ano | grep LISTENING | grep "127.0.0.1:${port} " | awk '{print $5}' | head -1)
if [ -z "$pid" ]; then
  echo "nothing listens on 127.0.0.1:${port}"
  exit 0
fi
taskkill //PID "$pid" //F > /dev/null
while netstat -ano | grep LISTENING | grep -q "127.0.0.1:${port} "; do sleep 1; done
echo "stopped pid ${pid}"
