"""The app's mode-derived decisions are read when they are made, never snapshotted.

`homeTarget`, `shouldWaitForAgent` and `isAgentMode` used to be `val`s in the
CIRISApp composable. A `val` there is the value at the render that computed it,
and the startup coroutine (`LaunchedEffect(phase)`) outlives that render: it
clears and re-probes `clientMode` itself, then decides with the copies from
before. CIRISAgent 2.20's five-platform run 42746576678 logged
"No agent to wait for (clientMode=AGENT)" and landed on Contacts with the agent
up, on linux, windows and ios. As local functions they read the state at the
moment of the decision, in composition and in coroutines alike.

A source check, because the failure is in how Compose captures locals, which no
unit test of the decision itself can see.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

APP = Path(__file__).resolve().parent.parent / "client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/CIRISApp.kt"
LIVE = ("homeTarget", "shouldWaitForAgent", "isAgentMode")


def problems(source: str) -> list[str]:
    found = []
    for name in LIVE:
        if not re.search(rf"^\s*fun {name}\(\)", source, re.M):
            found.append(f"{name} is not a local function")
        if re.search(rf"^\s*val {name}\s*=", source, re.M):
            found.append(f"{name} is a snapshot val")
        # A bare read (no call) outside comments is a stale copy waiting to happen.
        for i, line in enumerate(source.splitlines(), 1):
            if line.lstrip().startswith(("//", "*", "/*")):
                continue
            if re.search(rf"(?<![\w.$]){name}\b(?!\s*\()", line) and not re.search(rf"fun {name}\(", line):
                found.append(f"line {i}: {name} read without a call")
    return found


def test_mode_reads_are_live() -> None:
    assert problems(APP.read_text(encoding="utf-8")) == []


def test_the_check_fails_on_the_snapshot_shape() -> None:
    stale = """
    val homeTarget = homeScreen(hasAgent = clientMode?.isAgent ?: false)
    val shouldWaitForAgent = shouldWaitForAgent(clientMode)
    val isAgentMode = clientMode?.isAgent ?: true
    LaunchedEffect(phase) { currentScreen = homeTarget }
    """
    found = problems(stale)
    assert any("homeTarget is a snapshot val" in f for f in found)
    assert any("read without a call" in f for f in found)


if __name__ == "__main__":
    p = problems(Path(sys.argv[1]).read_text(encoding="utf-8") if len(sys.argv) > 1 else APP.read_text(encoding="utf-8"))
    print("\n".join(p) or "[OK] mode reads are live")
    sys.exit(1 if p else 0)
