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

# CUMULATIVE CPU SECONDS OF THE BUILD AND EVERYTHING IT SPAWNED.
#
# Silence is not the signal. A Kotlin/Native release link is one whole-program
# LLVM pass that prints NOTHING for tens of minutes — this script's own header
# says so — and the 0.5.191 iOS build was killed at 1650s of quiet while it was
# working perfectly. Raising the number just moves the guess; the question was
# never "how long has it been quiet" but "is it DOING anything".
#
# A wedged JVM burns no CPU. A linking one pegs a core. So a build is hung only
# when it is BOTH silent AND has stopped consuming CPU — which needs no
# per-target calibration and holds for every stall this exists to catch:
# deadlock, a lock nobody holds, a socket that will never answer.
#
# Walks the process TREE from the command's pid rather than matching a process
# group. Job control (`set -m`) would give a clean group, but it also echoes the
# whole backgrounded pipeline into the log — noise in CI output, and noise in
# the very stream the quiet check reads.
#
# `ps -A -o pid=,ppid=,time=` on both GNU and BSD; TIME is [dd-]hh:mm:ss.
cpu_seconds() {
  ps -A -o pid=,ppid=,time= 2>/dev/null | awk -v root="$1" '
    {
      ppid[$1] = $2
      t = $3
      gsub("-", ":", t)
      n = split(t, p, ":")
      s = 0
      for (i = 1; i <= n; i++) s = s * 60 + p[i]
      cpu[$1] = s
      pids[NR] = $1
    }
    END {
      # Mark the root, then repeatedly adopt any process whose parent is
      # already marked. Bounded by the tree depth, which is small.
      intree[root] = 1
      changed = 1
      while (changed) {
        changed = 0
        for (i = 1; i <= NR; i++) {
          pid = pids[i]
          if (!intree[pid] && intree[ppid[pid]]) { intree[pid] = 1; changed = 1 }
        }
      }
      for (i = 1; i <= NR; i++) if (intree[pids[i]]) total += cpu[pids[i]]
      print total + 0
    }
  '
}

"$@" > >(tee "$log") 2>&1 &
cmd_pid=$!
cmd_pgid=$(ps -o pgid= -p "$cmd_pid" 2>/dev/null | tr -d ' ')
cmd_pgid=${cmd_pgid:-$cmd_pid}
last_cpu=$(cpu_seconds "$cmd_pid")

(
  while kill -0 "$cmd_pid" 2>/dev/null; do
    sleep "$heartbeat"
    kill -0 "$cmd_pid" 2>/dev/null || break
    now=$(date +%s)
    quiet=$(( now - $(mtime "$log") ))
    last=$(tail -n 1 "$log" 2>/dev/null | cut -c1-100)
    cpu=$(cpu_seconds "$cmd_pid")
    cpu_moved=$(( cpu - last_cpu ))
    last_cpu=$cpu
    # Quiet AND not burning CPU. Either alone is normal: a link is quiet while
    # pegged, and a chatty build can be between tasks.
    # ZERO, not "a little". A wedged process advances no CPU at all; a working
    # one advances SOME, and how much depends on how loaded the runner is —
    # a busy process on a contended box advanced only 1s per 2s of wall clock
    # in testing, so any positive threshold re-introduces the guess this check
    # exists to remove.
    if [ "$quiet" -ge "$idle" ] && [ "$cpu_moved" -le 0 ]; then
      echo "::error::$label produced NO OUTPUT for ${quiet}s AND consumed no CPU in the last ${heartbeat}s (elapsed $(elapsed)). Treating as hung."
      echo "── last 30 lines ──"; tail -n 30 "$log"
      echo "── JVM stacks ──"
      for p in $(pgrep -f 'java|GradleDaemon' 2>/dev/null); do
        echo "-- pid $p"; jstack "$p" 2>/dev/null | head -60 || echo "   (no jstack)"
      done
      kill -TERM "$cmd_pid" 2>/dev/null; sleep 10; kill -KILL "$cmd_pid" 2>/dev/null
      exit 0
    fi
    if [ "$quiet" -ge "$idle" ]; then
      # The case that used to be a kill. Say it plainly, because a silent build
      # that IS working is exactly what a reader needs told.
      echo "[watchdog] $label quiet ${quiet}s but burning CPU (+${cpu_moved}s) — working, not wedged"
    fi
    echo "[watchdog] $label alive — elapsed $(elapsed), quiet ${quiet}s, cpu +${cpu_moved}s, last: ${last:-<nothing yet>}"
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
