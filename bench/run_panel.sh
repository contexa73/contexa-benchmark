#!/usr/bin/env bash
# Runs the development panel (panels/dev-v1.json) once for one variant of how a challenged call is confirmed, on a fresh engine
# database so that the variants do not learn from each other:
#
#   bash run_panel.sh none    nobody confirms: a challenged call is refused
#   bash run_panel.sh all     an owner who confirms every challenged call
#   bash run_panel.sh llm     a simulated owner (claude-haiku-5-5) who sees only what a person sees on the confirmation screen
#
# The agent is gpt-4o-mini-2024-07-18 and the stage is C2. Results go to ../runs/panel-dev1-C2-<variant>/.
set -uo pipefail
variant="${1:?variant: none, all or llm}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"
owner_args=()
if [ "$variant" != "none" ]; then owner_args=(--owner "$variant"); fi

for name in "bench_engine_${variant}" "bench_vector_${variant}"; do
  docker exec contexa-bench-postgres psql -U bench -d postgres -c "CREATE DATABASE ${name} OWNER bench" 2>&1 | head -n 1
done
docker exec contexa-bench-postgres psql -U bench -d "bench_vector_${variant}" -c "CREATE EXTENSION IF NOT EXISTS vector" 2>&1 | head -n 1

cd "$root/guard"
bash scripts/stop-guard.sh
set -a; . ./llm-anthropic.env; set +a
export BENCH_ENGINE_DB_URL="jdbc:postgresql://127.0.0.1:46500/bench_engine_${variant}"
export BENCH_VECTOR_DB_URL="jdbc:postgresql://127.0.0.1:46500/bench_vector_${variant}"
export BENCH_TIER=C2 BENCH_CACHE_MODE=LIVE BENCH_CACHE_DIR="$root/guard/cache-${variant}"
bash scripts/start-guard.sh &
bash scripts/wait-guard.sh 600 || exit 1

cd "$here"
out="$root/runs/panel-dev1-C2-${variant}"
mkdir -p "$out"
export PYTHONUTF8=1
for suite in slack banking travel workspace; do
  # The spend ledger is read before every run; a stage at its cap stops the chain (budget.json).
  "$root/.venv/Scripts/python.exe" -X utf8 spend.py --check dev || { echo "development cap reached, stopping"; exit 1; }
  "$root/.venv/Scripts/python.exe" runner.py --suite "$suite" --provider openai --model gpt-4o-mini-2024-07-18 \
    --attack important_instructions --defense contexa --tier C2 "${owner_args[@]}" --panel "$root/panels/dev-v1.json" \
    --logdir "$out" > "$out/$suite-attack.out" 2>&1
  "$root/.venv/Scripts/python.exe" runner.py --suite "$suite" --provider openai --model gpt-4o-mini-2024-07-18 \
    --defense contexa --tier C2 "${owner_args[@]}" --panel "$root/panels/dev-v1.json" \
    --logdir "$out" > "$out/$suite-benign.out" 2>&1
done
echo "panel ${variant} done"
