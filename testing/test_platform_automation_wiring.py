"""Every mobile platform must actually START its automation server (CIRISClient#31).

`CIRISApp` calls `startTestAutomationServer()` on every non-desktop platform once
test mode is armed. That is an `expect fun`, so each target supplies an `actual`
— and Android's was:

    actual fun startTestAutomationServer() {
        // TODO: Android test automation server (Ktor CIO)
        // For now, no-op — Android uses adb + Espresso for UI testing
    }

The comment was out of date by an entire implementation:
`AndroidTestAutomationServer` is a complete Ktor CIO server with routes, a
readiness thread, an idempotent `start()` and a `stop()`. Only the call was
missing, and nothing failed loudly — a no-op `actual` compiles perfectly.

What it cost: the five-platform gate's Android leg installed the APK, armed the
sentinel, forwarded the port, launched the app, and then waited its full 120s for
a server nobody had started. The error it reported —

    GET /health -> Remote end closed connection without response

— is adb accepting on the HOST socket and finding nothing on the device, which
is precisely the confusion `bringup.py`'s invariant 3 exists to name. So the leg
could never pass, and the reason was a TODO rather than anything about the app.

Parsed with `re` rather than compiled, per AGENTS.md: a readiness check that
needs a build has already lost — and this one additionally needs an Android SDK,
which not every developer machine has (mine does not, which is why this file
exists instead of a compile).
"""

from __future__ import annotations

import pathlib
import re

import pytest

SHARED = pathlib.Path(__file__).resolve().parents[1] / "client" / "shared" / "src"

#: The mobile targets whose `actual` must start a server. Desktop is excluded on
#: purpose: it starts its own from `Main.kt` before Compose, which is why
#: `CIRISApp` guards the call with `!isDesktop()`.
PLATFORMS = {
    "androidMain": "AndroidTestAutomationServer",
    "iosMain": "IOSTestAutomationServer",
}


def _actual_body(source_set: str) -> str:
    """The body of `actual fun startTestAutomationServer()` for one target."""
    hits = list((SHARED / source_set).rglob("Platform.*.kt"))
    assert hits, f"no Platform actual file under {source_set}"
    for path in hits:
        text = path.read_text(encoding="utf-8")
        m = re.search(
            r"actual fun startTestAutomationServer\(\)\s*\{(.*?)\n\}",
            text,
            re.S,
        )
        if m:
            return m.group(1)
    pytest.fail(f"{source_set} declares no actual startTestAutomationServer()")


@pytest.mark.parametrize("source_set,server", sorted(PLATFORMS.items()))
def test_the_actual_starts_the_server_it_has(source_set, server):
    body = _actual_body(source_set)
    # COMMENTS STRIPPED FIRST. The stub's body was entirely comments naming the
    # server it did not start, so a substring match over the raw body would have
    # found the name and passed — the same trap that caught three checks today
    # (a version regex matching the comment explaining the version, a CIRIS_HOME
    # guard passing on a mention, and an XCFramework task guard finding the task
    # name in the paragraph about it).
    code = "\n".join(
        ln for ln in body.splitlines()
        if ln.strip() and not ln.strip().startswith("//")
    )
    assert f"{server}.startIfEnabled()" in code, (
        f"{source_set}'s actual does not start {server} — it compiles, and the "
        f"gate then waits its whole budget for a server nobody launched. Body:\n{code!r}"
    )


@pytest.mark.parametrize("source_set", sorted(PLATFORMS))
def test_the_actual_is_not_an_empty_stub(source_set):
    """A no-op `actual` is the failure mode: it satisfies the compiler and nothing else."""
    body = _actual_body(source_set)
    code = [
        ln for ln in body.splitlines()
        if ln.strip() and not ln.strip().startswith("//")
    ]
    assert code, f"{source_set}'s startTestAutomationServer() is a comment-only stub"
