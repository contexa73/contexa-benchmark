#!/usr/bin/env bash
# Runs one part (dev or test) of the benchmark split (panels/split-v2.json) once, for one way of confirming a challenged call,
# with the local victim model, on a fresh engine database so that runs do not learn from each other:
#
#   bash run_part.sh dev llm dev-first-llm      a simulated owner (claude-haiku-5-5) confirms or refuses
#   bash run_part.sh dev none dev-first-none    nobody confirms: a challenged call is refused
#   bash run_part.sh dev all dev-first-all      an owner who confirms every challenged call
#
# The third word names the run; it starts with dev- or test- so that the spend ledger files it under that stage.
# Environment:
#   VICTIM_MODEL        the local Ollama model (default qwen2.5:7b)
#   STAGE_COST_HIGH     the high-case cost in USD of this run; when given, the run is not started unless it fits in what is
#                       left of the programme budget after the reserve (spend.py --can-finish)
#   STORE_PROMPT        true (default for dev) keeps every Layer 2 request as sent, for the Layer 2 comparison
#   REBUILD             1 builds the guard jar first (the guard is stopped before)
set -uo pipefail
part="${1:?part: dev or test}"
variant="${2:?variant: none, all or llm}"
label="${3:?name of the run, for example dev-first-llm}"
victim="${VICTIM_MODEL:-qwen2.5:7b}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"
py="$root/.venv/Scripts/python.exe"
case "$label" in "$part"-*) ;; *) echo "the name must start with ${part}-" >&2; exit 2;; esac
store_default=false; if [ "$part" = "dev" ]; then store_default=true; fi
db="$(echo "$label" | tr '-' '_')"

if [ -n "${STAGE_COST_HIGH:-}" ]; then
  PYTHONUTF8=1 "$py" -X utf8 "$here/spend.py" --can-finish "$STAGE_COST_HIGH" || { echo "this run does not fit in the budget, not started"; exit 4; }
fi

owner_args=()
if [ "$variant" != "none" ]; then owner_args=(--owner "$variant"); fi

for name in "bench_engine_${db}" "bench_vector_${db}"; do
  docker exec contexa-bench-postgres psql -U bench -d postgres -c "CREATE DATABASE ${name} OWNER bench" 2>&1 | head -n 1
done
docker exec contexa-bench-postgres psql -U bench -d "bench_vector_${db}" -c "CREATE EXTENSION IF NOT EXISTS vector" 2>&1 | head -n 1

cd "$root/guard"
bash scripts/stop-guard.sh
set -a; . ./llm-anthropic.env; set +a
export BENCH_ENGINE_DB_URL="jdbc:postgresql://127.0.0.1:46500/bench_engine_${db}"
export BENCH_VECTOR_DB_URL="jdbc:postgresql://127.0.0.1:46500/bench_vector_${db}"
export BENCH_TIER=C2 BENCH_CACHE_MODE=LIVE BENCH_CACHE_DIR="$root/guard/cache-${label}"
export BENCH_CACHE_STORE_PROMPT="${STORE_PROMPT:-$store_default}"
if [ "${REBUILD:-}" = "1" ]; then export BENCH_BUILD=1; fi
bash scripts/start-guard.sh &
bash scripts/wait-guard.sh 600 || exit 1

cd "$here"
out="$root/runs/${label}"
mkdir -p "$out"
export PYTHONUTF8=1
for suite in slack banking travel workspace; do
  # The spend ledger is read before every run; a stage at its cap, or the programme at its stop line, stops the chain.
  "$py" -X utf8 spend.py --check "$part" || { echo "cap reached, stopping"; exit 1; }
  "$py" runner.py --suite "$suite" --provider ollama --model "$victim" --attack important_instructions \
    --defense contexa --tier C2 "${owner_args[@]}" --split "$root/panels/split-v2.json" --part "$part" \
    --logdir "$out" > "$out/$suite-attack.out" 2>&1
  "$py" runner.py --suite "$suite" --provider ollama --model "$victim" \
    --defense contexa --tier C2 "${owner_args[@]}" --split "$root/panels/split-v2.json" --part "$part" \
    --logdir "$out" > "$out/$suite-benign.out" 2>&1
done
echo "run ${label} done"
