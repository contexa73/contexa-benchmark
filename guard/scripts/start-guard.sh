#!/usr/bin/env bash
# Starts the guard application (port 19300, 127.0.0.1) from the boot jar. Keys are read from the environment and
# the contexa docs key file and are never printed.
#
#   OPENAI_API_KEY          Layer 1 (gpt-6-luna) and the embeddings; read from the environment.
#   BENCH_ANTHROPIC_API_KEY Layer 2 (claude-sonnet-5-5). When unset it is taken from ANTHROPIC_API_KEY if that is a real
#                           key (longer than 40 characters; the user's variable is a 22-character placeholder), else from
#                           the sk-ant-... value inside E:/projects/contexa/docs/ANTHROPIC_API_KEY.MD.
#   BENCH_TIER=C1|C2|C3     the stage (default C1); the log is logs/guard-<tier>.log.
#   BENCH_BUILD=1           builds the boot jar first (a running jar must not be rebuilt).
#
# The database and model settings have defaults in src/main/resources/application.yml; every BENCH_* variable listed in
# README.md overrides one.
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="$here/build/libs/contexa-bench-guard-0.1.0.jar"

if [ -z "${BENCH_ANTHROPIC_API_KEY:-}" ]; then
  key_file=E:/projects/contexa/docs/ANTHROPIC_API_KEY.MD
  if [ -n "${ANTHROPIC_API_KEY:-}" ] && [ "${#ANTHROPIC_API_KEY}" -gt 40 ]; then
    export BENCH_ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY"
  elif [ -f "$key_file" ]; then
    export BENCH_ANTHROPIC_API_KEY="$(grep -o 'sk-ant-[A-Za-z0-9_-]*' "$key_file" | head -1)"
  fi
fi

# The key decision records and sealed evidence are signed with: generated once, kept in guard/.evidence-signing-key
# (git-ignored), so that the evidence stays verifiable across restarts.
if [ -z "${BENCH_EVIDENCE_SIGNING_KEY:-}" ]; then
  key_store="$here/.evidence-signing-key"
  if [ ! -s "$key_store" ]; then
    head -c 32 /dev/urandom | base64 -w0 > "$key_store"
  fi
  export BENCH_EVIDENCE_SIGNING_KEY="$(cat "$key_store")"
fi

# Stages C2 and C3: the secret of the engine's internal OAuth2 client must stay the same across restarts (the client is
# stored with it): generated once, kept in guard/.oauth2-client-secret (git-ignored).
if [ -z "${BENCH_OAUTH2_CLIENT_SECRET:-}" ]; then
  secret_store="$here/.oauth2-client-secret"
  if [ ! -s "$secret_store" ]; then
    head -c 32 /dev/urandom | base64 -w0 | tr '+/' '-_' | tr -d '=' > "$secret_store"
  fi
  export BENCH_OAUTH2_CLIENT_SECRET="$(cat "$secret_store")"
fi
export BENCH_TIER="${BENCH_TIER:-C1}"

port="${BENCH_GUARD_PORT:-19300}"
if netstat -ano | grep LISTENING | grep -q "127.0.0.1:${port} "; then
  echo "something already listens on 127.0.0.1:${port}; stop it first" >&2
  exit 1
fi

if [ "${BENCH_BUILD:-}" = "1" ] || [ ! -f "$jar" ]; then
  (cd "$here" && ./gradlew bootJar -q) || exit 1
fi

mkdir -p "$here/logs"
exec java -Duser.timezone=UTC -Dfile.encoding=UTF-8 -Xmx2g -jar "$jar" >> "$here/logs/guard-${BENCH_TIER}.log" 2>&1
