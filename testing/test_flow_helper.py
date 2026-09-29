"""`SyncFlowHelper` over a fake driver: a refusal's reason is KEPT, and an
off-screen refusal is answered by scrolling (CIRISClient#33), not by giving up.

Local Linux leg, 2026-09-29: the transport hub's Content tile is composed below
the fold, `/click` refused it as "composed but off screen", and the runner
reported `click 'tile_federation_content' did not succeed` — the reason gone,
and the remedy the client ships for exactly that (`/scroll`) unused. The
session fixture had learned this rule for the wizard (`_reach`); the flow
helper had not.
"""

from __future__ import annotations

import asyncio

from testing.driver import DriverError
from testing.gate.flow_helper import SyncFlowHelper


class _Drv:
    """A driver whose target sits `off_screen_until` scrolls below the fold."""

    def __init__(self, off_screen_until: int = 0, refuse: str = "", bottom_after: int = 99):
        self.scrolls: list = []
        self.clicks: list = []
        self.inputs: list = []
        self.off_screen_until, self.refuse, self.bottom_after = off_screen_until, refuse, bottom_after

    def _gate(self, verb, tag):
        if self.refuse:
            raise DriverError(self.refuse)
        if len(self.scrolls) < self.off_screen_until:
            raise DriverError(f"POST /{verb} -> HTTP 422: {tag} is composed but off screen "
                              f"(inside a closed drawer or sheet?)")

    def click(self, tag):
        self.clicks.append(tag)
        self._gate("click", tag)

    def input(self, tag, text):
        self.inputs.append((tag, text))
        self._gate("input", tag)

    def scroll_to(self, tag, direction="down", amount=300):
        self.scrolls.append((tag, direction))
        if len(self.scrolls) > self.bottom_after:
            return {"success": False, "error": "already at the bottom (900 of 900)"}
        return {"success": True, "text": f"{direction}:{amount} moved 0→300 of 900"}

    def tree(self):
        return []

    def screen(self):
        return "LayerGlobalCommons"

    def state(self):
        return {}


def test_an_off_screen_click_is_scrolled_into_view_and_retried():
    d = _Drv(off_screen_until=2)
    assert asyncio.run(SyncFlowHelper(d).click("tile_federation_content")) is True
    assert len(d.scrolls) == 2, "scrolled exactly until the click landed"
    assert d.clicks.count("tile_federation_content") == 3


def test_an_off_screen_input_is_scrolled_into_view_and_retried():
    d = _Drv(off_screen_until=1)
    assert asyncio.run(SyncFlowHelper(d).input_text("input_provision_holder_pin", "000000")) is True
    assert d.scrolls and d.inputs[-1] == ("input_provision_holder_pin", "000000")


def test_a_refusals_reason_is_kept_for_the_verdict():
    d = _Drv(refuse="POST /click -> HTTP 404: No click handler for 'btn_x'")
    h = SyncFlowHelper(d)
    assert asyncio.run(h.click("btn_x")) is False
    assert "No click handler" in h.last_error
    assert not d.scrolls, "a refusal that is not about the fold is not answered by scrolling"


def test_scrolling_is_bounded_and_the_bottom_is_reported():
    d = _Drv(off_screen_until=10 ** 6, bottom_after=3)
    h = SyncFlowHelper(d)
    assert asyncio.run(h.click("tile_far_away")) is False
    assert len(d.scrolls) < 40, "bounded"
    assert "off screen" in h.last_error and "already at the bottom" in h.last_error


def test_scroll_into_view_keeps_what_the_screen_answered():
    """A `visible:` that fails after scrolling quotes the screen's answer, so
    "composed but off screen" says whether there was anything to scroll."""
    d = _Drv(refuse="")
    d.scroll_to = lambda tag, direction="down", amount=300: {
        "success": False,
        "error": f"nothing on screen 'Contacts' can scroll (no testableVerticalScroll registered)",
    }
    h = SyncFlowHelper(d)
    assert asyncio.run(h.scroll_into_view("contacts_row_peer")) is False
    assert h.last_scroll == [
        "down: nothing on screen 'Contacts' can scroll (no testableVerticalScroll registered)",
        "up: nothing on screen 'Contacts' can scroll (no testableVerticalScroll registered)",
    ], h.last_scroll
