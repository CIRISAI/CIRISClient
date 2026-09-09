"""The async surface `FlowRunner` drives, over this repo's synchronous driver.

`flow_spec.FlowRunner` was written against CIRISAgent's `DesktopAppHelper`, which
is async and shaped around their app shells. This repo's `testing/driver.py` is
synchronous and shaped around the client's own TestAutomationServer. The two
speak about the same things — a tree of elements, a screen name, click, input,
scroll — in different tenses.

ADAPTED, NOT REWRITTEN, and the distinction is the point. Re-implementing the
runner here would give this repo a second definition of what `visible` means, and
two definitions of visibility is how a harness scrolls 300px of a 3142px form and
reports success (CIRISClient#30). The vendored runner keeps its own answer; this
file only changes the tense.

WHAT `visible` MEANS HERE, AND THE FIDELITY GAP IT LEAVES — the one thing a shim
can quietly get wrong, so it is stated rather than assumed.

The runner reads an element's `visible` flag and says why: `/tree` lists
everything ever composed (the registry never forgets), so a PRESENCE list would
name elements the user cannot see. **This client serves no such flag.**
`testing.driver.Element` carries geometry, `text`, `can_click`/`can_input` and
`input_value` — no visibility. So the runner's documented fallback applies here
permanently rather than occasionally: an element counts as on screen when
`width > 0 and height > 0`.

That is weaker than the flag, in a specific way worth naming: a composed element
BELOW THE FOLD has non-zero size, so geometry calls it visible where the flag
would not. `scroll_into_view` masks it for `visible:` checks, but a `count:` of a
glob can over-count off-screen rows. `is_ghost` does not close this — it answers
DRIVABILITY (a stale registry entry reports can_click and can_input both False),
which is CIRISClient#30's shape and a different question.

Closing it properly means the client serving a `visible` flag, which is a client
change and not a shim's business to fake. Until then, treat `count:` results on a
long screen as an upper bound.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional

from testing.driver import DriverError, TestAutomationServer


@dataclass
class _Element:
    """The element shape `FlowRunner` reads: test_tag, text, visible, w/h."""

    test_tag: str
    text: Optional[str]
    visible: Optional[bool]
    width: int
    height: int


class SyncFlowHelper:
    """`FlowRunner`'s helper contract, backed by [TestAutomationServer].

    Every method is `async` because the runner awaits them; none of them
    actually suspends. That is deliberate: making the driver async would change
    a file two other gates already depend on, to satisfy a caller that never
    needs concurrency.
    """

    def __init__(self, drv: TestAutomationServer) -> None:
        self._drv = drv

    # ---- reads --------------------------------------------------------------

    async def get_elements(self) -> List[_Element]:
        try:
            return [
                _Element(
                    test_tag=e.test_tag,
                    text=getattr(e, "text", None),
                    visible=getattr(e, "visible", None),
                    width=getattr(e, "width", 0) or 0,
                    height=getattr(e, "height", 0) or 0,
                )
                for e in self._drv.tree()
            ]
        except DriverError:
            # Diagnosis must never raise — the runner's own rule for _drivable.
            return []

    async def get_element(self, tag: str) -> Optional[_Element]:
        for e in await self.get_elements():
            if e.test_tag == tag:
                return e
        return None

    async def get_screen(self) -> str:
        try:
            return self._drv.screen()
        except DriverError:
            return "unknown"

    async def is_element_visible(self, tag: str) -> bool:
        e = await self.get_element(tag)
        if e is None:
            return False
        # The flag first, if a client ever serves one; geometry otherwise —
        # which is today's every case. See the module docstring's fidelity note.
        return e.visible if e.visible is not None else (e.width > 0 and e.height > 0)

    # ---- actions ------------------------------------------------------------

    async def click(self, tag: str, timeout: int = 2000) -> bool:
        try:
            self._drv.click(tag)
            return True
        except DriverError:
            return False

    async def input_text(self, tag: str, text: str) -> bool:
        try:
            self._drv.input(tag, text)
            return True
        except DriverError:
            return False

    async def scroll_into_view(self, tag: str) -> bool:
        try:
            self._drv.scroll_to(tag)
            return True
        except (DriverError, AttributeError):
            # A client without /scroll is not a failed scroll: the runner will
            # re-ask `is_element_visible` and report honestly either way.
            return False

    async def wait_for_element(self, tag: str, timeout: int = 2000) -> bool:
        try:
            self._drv.wait_for_element(tag, timeout=timeout / 1000.0)
            return True
        except DriverError:
            return False
