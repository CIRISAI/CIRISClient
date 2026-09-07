#!/usr/bin/env bash
# THE RELEASE GATES, RUNNABLE BEFORE THE TAG EXISTS.
#
#     bash packaging/gates.sh              # clones the pinned emitters if needed
#     bash packaging/gates.sh .emitters    # use an existing emitter checkout (CI)
#
# WHY THIS FILE. 0.5.204 and 0.5.205 were tagged, built every wheel, and
# published nothing: the `Gates` step in publish.yml failed on the vendoring
# digest, because `client/` had changed in three commits without
# `client/VENDORING.md` §1 being re-recorded. main's own CI had said so since
# the first of them, and nobody read it before tagging. The gates lived only
# in the workflow, so "run the gates" was not a thing a person could do at the
# keyboard. Now it is, and publish.yml runs THIS file, so the list cannot
# drift between the two.
#
# The emitter checkout is CIRISServer at the ref VENDORING.md §1 records as
# the last merged state -- the Rust that emits the ids our bundles must
# define. It is pinned there, not to main, so the grade is against what this
# tree was reconciled with.
set -euo pipefail
cd "$(dirname "$0")/.."

EMITTERS="${1:-${CIRIS_EMITTERS:-${TMPDIR:-/tmp}/ciris-emitters}}"
ref="$(python3 packaging/check_vendoring.py --merged-ref CIRISServer)"

# `--merged-ref` is a commit sha, so this fetches the sha itself (GitHub serves
# reachable commits by sha) rather than guessing a tag name for it.
fetch_pin() {
    git -C "$EMITTERS" fetch -q --depth 1 origin "$ref"
    git -C "$EMITTERS" checkout -q --detach FETCH_HEAD
}
if [ ! -d "$EMITTERS/.git" ]; then
    echo "emitters: fetching CIRISAI/CIRISServer@$ref -> $EMITTERS"
    mkdir -p "$EMITTERS"
    git -C "$EMITTERS" init -q
    git -C "$EMITTERS" remote add origin https://github.com/CIRISAI/CIRISServer
    fetch_pin
elif [ -z "${1:-}" ] && [ "$(git -C "$EMITTERS" rev-parse HEAD)" != "$ref" ]; then
    echo "emitters: $EMITTERS is not at the pin $ref -- refetching"
    fetch_pin
fi

python3 packaging/check_vendoring.py
python3 compat/validate.py
python3 packaging/check_pins.py
python3 client/tools/check_localization_sync.py --self-test --server-src "$EMITTERS"
python3 client/tools/check_localization_sync.py --server-src "$EMITTERS" --strict
echo "[OK] every release gate passed (emitters: CIRISServer@$ref)"
