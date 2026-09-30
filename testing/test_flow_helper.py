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

    def __init__(self, off_screen_until: int = 0, refuse: str = "", bottom_after: int = 99,
                 response=None):
        #: What an ACCEPTED /click answers (the driver returns the body).
        self.response = response if response is not None else {"success": True, "action": "click"}
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
        return self.response

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


# ── click_refused: a disabled control's click does nothing ────────────────
# iOS, run 36733112700, csd_068 `empty_submit_does_nothing`: a disabled
# Provision submit answered /click with a refusal (1dcf0b0c), the driver
# raised, and the step whose claim IS "clicking this does nothing" failed.


def test_a_click_refused_as_disabled_is_the_outcome_click_refused_asks_for():
    d = _Drv(refuse="POST /click -> HTTP 409: btn_submit is disabled: it refuses /click, "
                    "as it refuses a press")
    h = SyncFlowHelper(d)
    assert asyncio.run(h.click_refused("btn_submit")) is True
    assert d.clicks == ["btn_submit"]


def test_an_older_mobile_clients_no_click_handler_is_a_refusal_too():
    d = _Drv(refuse="POST /click -> HTTP 404: No click handler for: btn_submit")
    assert asyncio.run(SyncFlowHelper(d).click_refused("btn_submit")) is True


def test_an_older_desktops_coordinate_click_is_accepted_and_judged_by_the_expect():
    """Desktop before 0.5.225 fell back to a mouse click on a control with no
    handler; Compose ignores it on a disabled button. No handler ran, so the
    action holds and the step's `absent:` judges the effect."""
    d = _Drv(response={"success": True, "action": "mouse-click",
                       "error": "no programmatic handler for btn_submit; used a coordinate click"})
    assert asyncio.run(SyncFlowHelper(d).click_refused("btn_submit")) is True


def test_a_handler_that_fires_behind_a_disabled_control_fails_click_refused():
    d = _Drv(response={"success": True, "action": "click"})
    h = SyncFlowHelper(d)
    assert asyncio.run(h.click_refused("btn_submit")) is False
    assert "handler ran" in h.last_error and "#69" in h.last_error


def test_click_refused_on_a_missing_control_is_a_failure_not_a_refusal():
    d = _Drv(refuse="POST /click -> HTTP 404: Element not found: btn_submit; on screen: []")
    h = SyncFlowHelper(d)
    assert asyncio.run(h.click_refused("btn_submit")) is False
    assert "Element not found" in h.last_error


def test_click_refused_scrolls_an_off_screen_control_first():
    d = _Drv(off_screen_until=2, response={"success": True, "action": "click"})
    d.refuse_after_scroll = True
    real_gate = d._gate

    def gate(verb, tag):
        real_gate(verb, tag)
        raise DriverError(f"POST /click -> HTTP 409: {tag} is disabled")
    d._gate = gate
    assert asyncio.run(SyncFlowHelper(d).click_refused("btn_submit")) is True
    assert len(d.scrolls) == 2


# ── A click refused as disabled waits for the frame, bounded ─────────────
# iOS, run 36733112700, csd_005: the Add submit was clicked before the frame
# that applied the typed key enabled it. With the handler bound to `enabled`
# the click is refused as "is disabled"; the helper waits for the control to
# enable, as `expect:` waits for its frame, and fails if it never does.


class _FakeClock:
    def __init__(self):
        self.t = 0.0

    def monotonic(self):
        return self.t

    def sleep(self, s):
        self.t += s


def _disabled_for(n: int) -> _Drv:
    d = _Drv()
    d.disabled_left = n

    def gate(verb, tag):
        if d.disabled_left > 0:
            d.disabled_left -= 1
            raise DriverError(f"POST /click -> HTTP 409: {tag} is disabled: it refuses /click")
    d._gate = gate
    return d


def test_a_click_refused_as_disabled_is_retried_until_the_control_enables(monkeypatch):
    from testing.gate import flow_helper
    monkeypatch.setattr(flow_helper, "time", _FakeClock())
    d = _disabled_for(3)
    assert asyncio.run(SyncFlowHelper(d).click("btn_contacts_add_submit")) is True
    assert d.clicks.count("btn_contacts_add_submit") == 4


def test_a_control_that_never_enables_fails_with_its_reason_and_is_bounded(monkeypatch):
    from testing.gate import flow_helper
    clock = _FakeClock()
    monkeypatch.setattr(flow_helper, "time", clock)
    d = _disabled_for(10 ** 6)
    h = SyncFlowHelper(d)
    assert asyncio.run(h.click("btn_contacts_add_submit")) is False
    assert "is disabled" in h.last_error
    assert clock.t <= flow_helper.ENABLE_SETTLE_S + 1, "bounded"


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
