#!/usr/bin/env bash
# Runs the TEST part of the split (the held-out user tasks) only under a freeze (plan P3, P4): the freeze file must exist, the
# decision path hash in it must still hold for the present core, and the run records the sha256 of the freeze (runner.py).
#
#   BENCH_FREEZE=../releases/freeze-<release>.json STAGE_COST_HIGH=<usd> bash run_test_part.sh llm test-<release>-llm
#
# The test part is run once per release. A name that was used before is refused, so a run cannot be silently repeated.
set -uo pipefail
variant="${1:?variant: none, all or llm}"
label="${2:?name of the run, starting with test-}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"
py="$root/.venv/Scripts/python.exe"

if [ -z "${BENCH_FREEZE:-}" ] || [ ! -f "$BENCH_FREEZE" ]; then
  echo "the test part needs BENCH_FREEZE=<freeze file>" >&2; exit 2
fi
if [ -z "${STAGE_COST_HIGH:-}" ]; then
  echo "the test part needs STAGE_COST_HIGH=<high-case cost in USD> for the completion check" >&2; exit 2
fi
if [ -e "$root/runs/$label" ]; then
  echo "runs/$label exists: the test part is run once per release; name a new release" >&2; exit 2
fi
PYTHONUTF8=1 "$py" -X utf8 "$here/freeze_release.py" verify "$BENCH_FREEZE" || { echo "the guard no longer matches the freeze, not started" >&2; exit 3; }
export BENCH_FREEZE STORE_PROMPT="${STORE_PROMPT:-true}"
exec bash "$here/run_part.sh" test "$variant" "$label"
