"""UTF-8 on the gate's console, whatever code page the host opened it with.

Windows, run 36600766576 (2026-09-29): the two-node fixture had just seen the
peer become reachable and printed its note about the owner→node binding wait.
The runner's Python had opened stdout as cp1252 — the default when nothing
sets `PYTHONUTF8` — and `→` (U+2192) is not in cp1252, so `print` raised
`UnicodeEncodeError` inside `up()` and the runner reported

    fixture two_node: UNAVAILABLE — the `two_node` fixture could not be stood up:
    UnicodeEncodeError: 'charmap' codec can't encode character '\\u2192' in position 69

which read as a node problem. Three flows could not start over a print.

The runner's own `—` and `§` had survived the same stream only because cp1252
happens to hold those two code points; they rendered as `�` in the UTF-8 job
log rather than crashing, which is why the crash looked like it came from
somewhere other than stdout. It did not: the whole difference was which
characters the code page has.

THE MECHANISM, NOT THE CHARACTER. Stripping the arrow would fix one note and
leave every future one a coin flip. Every gate CLI reconfigures its console to
UTF-8 on entry instead (`sys.stdout.reconfigure`, Python 3.7+); the in-process
runner, the fixture and the flow printer all inherit it. `errors="replace"`
keeps a lone surrogate from being the next crash.
"""

from __future__ import annotations

import sys


def utf8_console() -> None:
    """Put stdout and stderr in UTF-8. A stream that cannot be reconfigured
    (a test's StringIO, a closed pipe) is left as it is: this must never be the
    thing that fails a leg."""
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:
            continue
        try:
            reconfigure(encoding="utf-8", errors="replace")
        except (ValueError, OSError):
            continue
