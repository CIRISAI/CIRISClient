#!/usr/bin/env bash
# A virtual microphone and speaker for call tests on Linux (CSD-134).
#
#     bash packaging/virtual_audio.sh run -- <command…>   # up, the command, then down — always
#     eval "$(bash packaging/virtual_audio.sh up)"         # exports PULSE_SINK / PULSE_SOURCE
#     bash packaging/virtual_audio.sh down
#
# ON A DESKTOP USE `run`. It puts the devices back when the command ends, however
# it ends, the way `xvfb-run` does for a display. `up` without its `down` leaves
# this machine's sound going nowhere, and a `down` run from another directory does
# not find this script (that happened, 2026-11-10). `up`/`down` are for a CI
# runner, which is thrown away.
#
# A PulseAudio (or PipeWire-Pulse) null sink is the speaker, and its monitor is the
# microphone: whatever the call plays comes back in as what it hears. CI runners
# have no audio hardware, so this is the device the five-platform runner's Linux
# legs talk to.
#
# IT CHANGES THE DEFAULT DEVICES while it is up, and `down` restores the previous
# ones. Java Sound opens ALSA's default device, and under PipeWire that ignores
# PULSE_SINK/PULSE_SOURCE (only the PulseAudio ALSA plugin honours them), so the
# defaults are the one routing every sound server respects. On a desktop that
# means this machine's own sound goes to the null sink until `down`.
set -euo pipefail
NAME="${CIRIS_VIRTUAL_AUDIO_NAME:-ciris_call_loop}"

case "${1:-}" in
  up)
    if ! pactl info >/dev/null 2>&1; then
      # A runner with no sound server: start one without a session bus.
      pulseaudio --start --exit-idle-time=-1 >/dev/null 2>&1 || true
      for _ in 1 2 3 4 5 6 7 8 9 10; do pactl info >/dev/null 2>&1 && break; sleep 0.5; done
    fi
    pactl info >/dev/null 2>&1 || { echo "echo 'no PulseAudio server could be started' >&2; exit 1"; exit 1; }
    if ! pactl list short sinks | awk '{print $2}' | grep -qx "$NAME"; then
      pactl load-module module-null-sink sink_name="$NAME" \
        rate=48000 channels=1 sink_properties=device.description="$NAME" >/dev/null
    fi
    STATE="${XDG_RUNTIME_DIR:-/tmp}/$NAME.defaults"
    if [ ! -f "$STATE" ]; then
      { pactl get-default-sink; pactl get-default-source; } > "$STATE"
    fi
    pactl set-default-sink "$NAME"
    pactl set-default-source "$NAME.monitor"
    echo "export PULSE_SINK=$NAME"
    echo "export PULSE_SOURCE=$NAME.monitor"
    ;;
  down)
    STATE="${XDG_RUNTIME_DIR:-/tmp}/$NAME.defaults"
    if [ -f "$STATE" ]; then
      pactl set-default-sink "$(sed -n 1p "$STATE")" || true
      pactl set-default-source "$(sed -n 2p "$STATE")" || true
      rm -f "$STATE"
    fi
    for id in $(pactl list short modules | awk -v n="sink_name=$NAME" 'index($0, n) {print $1}'); do
      pactl unload-module "$id" || true
    done
    ;;
  run)
    shift
    [ "${1:-}" = "--" ] && shift
    [ $# -gt 0 ] || { echo "usage: $0 run -- <command…>" >&2; exit 2; }
    SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
    trap 'bash "$SELF" down' EXIT INT TERM
    eval "$(bash "$SELF" up)"
    "$@"
    ;;
  *)
    echo "usage: $0 up|down|run -- <command…>" >&2
    exit 2
    ;;
esac
