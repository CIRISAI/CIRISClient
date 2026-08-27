#!/usr/bin/env bash
# Run a long build so that STALLED and SLOW stop looking the same.
#
#   packaging/run_with_watchdog.sh <idle_seconds> <heartbeat_seconds> <label> -- cmd...
#
# GitHub Actions offers only a WALL-CLOCK timeout, which cannot tell a build
# that is working from one that is wedged: both end at the limit, both report
# "cancelled", and neither says why. That is not academic here —
# ios-xcframework legitimately runs FOUR HOURS (0.5.189: 4.21h, succeeded), so
# its budget is 350 minutes, and a genuine hang inside it would burn all 350
# before producing a single line of evidence.
#
# So this watches PROGRESS instead of elapsed time:
#
#   * idle_seconds  — no new output at all for this long is a HANG. Dump every
#                     JVM's stacks, print the last lines, kill it, fail. Minutes
#                     with evidence, instead of hours with none.
#   * heartbeat     — Kotlin/Native prints nothing during a long link, so from
#                     outside "still linking" and "wedged" are identical. A line
#                     with elapsed time and the last task makes a four-hour
#                     build legible WHILE it runs.
#
# The exit code is the command's own. A watchdog that swallowed it would be a
# new way to report green.
set -uo pipefail

idle="$1"; heartbeat="$2"; label="$3"; shift 3
[ "${1:-}" = "--" ] && shift

log="$(mktemp -t watchdog.XXXXXX)"
started=$(date +%s)

elapsed() { printf '%dm%02ds' $((($(date +%s) - started) / 60)) $((($(date +%s) - started) % 60)); }

# GNU first, then BSD. Getting this backwards is not a fallback, it is a silent
# pass: `stat -f %m` on GNU coreutils does not fail, it answers a DIFFERENT
# question (filesystem format), so the watchdog computed a nonsense age and
# never fired. A hang detector that cannot fire is the thing it is guarding
# against, one level up. Both matter here — android runs on ubuntu, ios on
# macos-14.
mtime() {
  stat -c %Y "$1" 2>/dev/null || stat -f %m "$1" 2>/dev/null || echo 0
}

"$@" > >(tee "$log") 2>&1 &
cmd_pid=$!

(
  while kill -0 "$cmd_pid" 2>/dev/null; do
    sleep "$heartbeat"
    kill -0 "$cmd_pid" 2>/dev/null || break
    now=$(date +%s)
    quiet=$(( now - $(mtime "$log") ))
    last=$(tail -n 1 "$log" 2>/dev/null | cut -c1-100)
    if [ "$quiet" -ge "$idle" ]; then
      echo "::error::$label produced NO OUTPUT for ${quiet}s (elapsed $(elapsed)). Treating as hung."
      echo "── last 30 lines ──"; tail -n 30 "$log"
      echo "── JVM stacks ──"
      for p in $(pgrep -f 'java|GradleDaemon' 2>/dev/null); do
        echo "-- pid $p"; jstack "$p" 2>/dev/null | head -60 || echo "   (no jstack)"
      done
      kill -TERM "$cmd_pid" 2>/dev/null; sleep 10; kill -KILL "$cmd_pid" 2>/dev/null
      exit 0
    fi
    echo "[watchdog] $label alive — elapsed $(elapsed), quiet ${quiet}s, last: ${last:-<nothing yet>}"
  done
) &
watch_pid=$!

wait "$cmd_pid"; rc=$?
kill "$watch_pid" 2>/dev/null || true

if [ "$rc" -ne 0 ]; then
  echo "::error::$label failed after $(elapsed) (exit $rc)"
else
  echo "[watchdog] $label finished in $(elapsed)"
fi
rm -f "$log"
exit "$rc"
