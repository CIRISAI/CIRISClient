"""The session fixture waits for the wizard; it does not sleep through it.

Windows, run 36588619656 (2026-09-29): Next on step `you` was clicked, the
form went blank while the next step composed (the on-screen list two seconds
later was `btn_next` and the step indicators, nothing else — the indicator
still said `you`), and the fixture, which slept a fixed 2 s and then judged,
raised "wizard did not advance past 'you'". The wizard was not stuck; the
fixture's clock was wrong. Driven here against a fake wizard on a fake clock,
so the red path (a slow step) and the honest path (a step that truly stalls)
both run in milliseconds.
"""

from __future__ import annotations

import pytest

from testing.driver import DriverError, Element
from testing.gate import session_fixture as sf


class _Clock:
    def __init__(self):
        self.t = 0.0

    def monotonic(self):
        return self.t

    def sleep(self, s):
        self.t += s


def _el(tag, text=None, can_click=True):
    return Element(test_tag=tag, x=0, y=0, width=10, height=10, text=text, can_click=can_click)


class _SlowWizard:
    """A desktop first run. Login -> (btn_local_login) -> Setup step `you`;
    Next takes `advance_after` seconds to show `join_federation`, with a BLANK
    body meanwhile — the shape the Windows leg showed. The consent step
    answers, Next again claims, and the claim returns the app to Login."""

    def __init__(self, clock: _Clock, advance_after: float, claim_takes: float = 0.0,
                 claim_error: str | None = None):
        self.clock, self.advance_after = clock, advance_after
        # The claim is WORK: `setup_ownership_claiming` for `claim_takes`
        # seconds (step indicators up, no step active, no advance control),
        # then claimed — or the error panel, which never returns to Login.
        self.claim_takes, self.claim_error = claim_takes, claim_error
        self.on = "Login"
        self.step = "you"
        self.next_at: float | None = None
        self.consented = False
        self.claimed_at: float | None = None
        self.inputs: dict[str, str] = {}
        self.clicks: list[str] = []

    def _tick(self):
        if self.step == "you" and self.next_at is not None and self.clock.t - self.next_at >= self.advance_after:
            self.step, self.next_at = "join_federation", None

    def _claim_phase(self) -> str:
        if self.claimed_at is None:
            return ""
        spent = self.clock.t - self.claimed_at
        if spent < self.claim_takes:
            return "claiming"
        if self.claim_error is not None:
            return "error"
        return "Login" if spent >= self.claim_takes + 1.0 else "claimed"

    def screen(self):
        if self._claim_phase() == "Login":
            return "Login"
        return self.on

    def tree(self):
        self._tick()
        if self.screen() == "Login":
            return [_el("btn_local_login")]
        if self.step == "claimed":
            phase = self._claim_phase()
            # Android, run 36733112700: the indicators stay, none is active.
            indicators = [_el("setup_step_indicators"), _el("step_indicator_you", ""),
                          _el("step_indicator_join_federation", "")]
            if phase == "claiming":
                return [_el("setup_ownership_claiming")] + indicators
            if phase == "error":
                return [_el("setup_ownership_error", self.claim_error)] + indicators
            return [_el("setup_ownership_claimed")]
        indicators = [_el("setup_step_indicators"),
                      _el("step_indicator_you", "active" if self.step == "you" else ""),
                      _el("step_indicator_join_federation", "active" if self.step == "join_federation" else "")]
        if self.step == "you" and self.next_at is not None:
            return [_el("btn_next")] + indicators  # blank body: the next step is composing
        if self.step == "you":
            return [_el(t) for t in ("input_username", "input_password", "input_password_confirm",
                                     "input_device_name", "age_band_adult", "btn_next")] + indicators
        return [_el("trace_consent_yes"), _el("btn_next")] + indicators

    def click(self, tag):
        self.clicks.append(tag)
        if tag not in {e.test_tag for e in self.tree()}:
            raise DriverError(f"POST /click -> HTTP 404: No click handler for {tag!r}")
        if tag == "btn_local_login":
            self.on = "Setup"
        elif tag == "trace_consent_yes":
            self.consented = True
        elif tag == "btn_next":
            if self.step == "you":
                self.next_at = self.clock.t
            elif self.step == "join_federation" and self.consented:
                self.step, self.claimed_at = "claimed", self.clock.t

    def input(self, tag, text):
        self.inputs[tag] = text

    def scroll_to(self, tag, direction="down", amount=300):
        return {"error": "NO overflow"}


def _drive(monkeypatch, advance_after: float, **kw):
    clock = _Clock()
    monkeypatch.setattr(sf, "time", clock)
    w = _SlowWizard(clock, advance_after, **kw)
    return clock, w


def test_a_slow_step_is_waited_for_not_slept_through(monkeypatch):
    clock, w = _drive(monkeypatch, advance_after=6.0)
    sf.run_setup(w, "qaadmin", "QaAdmin!2345")
    assert w.step == "claimed" and w.screen() == "Login"
    assert w.inputs["input_username"] == "qaadmin"
    assert w.clicks.count("btn_next") == 2, "Next once per step, not hammered while the step composed"


def test_a_step_that_truly_stalls_is_still_reported_by_name(monkeypatch):
    clock, w = _drive(monkeypatch, advance_after=10 ** 6)
    with pytest.raises(sf.SessionUnavailable) as e:
        sf.run_setup(w, "qaadmin", "QaAdmin!2345")
    assert "'you'" in str(e.value)
    assert clock.t < 300, "bounded: a stalled wizard is reported in minutes, not hours"


def test_a_claim_in_progress_is_waited_for_not_asked_for_a_control(monkeypatch):
    """Android, run 36733112700: after the last Next the wizard showed
    `setup_ownership_claiming` with no step active and no advance control, and
    the fixture raised "wizard step '' offers no advance control". The claim
    is work that finishes on its own; the fixture waits for it."""
    clock, w = _drive(monkeypatch, advance_after=1.0, claim_takes=40.0)
    sf.run_setup(w, "qaadmin", "QaAdmin!2345")
    assert w.screen() == "Login"
    assert w.clicks.count("btn_next") == 2


def test_a_claim_that_never_finishes_is_reported_with_the_screen(monkeypatch):
    clock, w = _drive(monkeypatch, advance_after=1.0, claim_takes=10 ** 6)
    with pytest.raises(sf.SessionUnavailable) as e:
        sf.run_setup(w, "qaadmin", "QaAdmin!2345")
    msg = str(e.value)
    assert "claim did not finish" in msg and "setup_ownership_claiming" in msg, msg
    assert clock.t < 300, "bounded"


def test_a_refused_claim_is_reported_with_its_reason(monkeypatch):
    clock, w = _drive(monkeypatch, advance_after=1.0, claim_takes=5.0,
                      claim_error="claim PIN was not captured")
    with pytest.raises(sf.SessionUnavailable) as e:
        sf.run_setup(w, "qaadmin", "QaAdmin!2345")
    assert "claim PIN was not captured" in str(e.value)
    assert clock.t < 60, "a refused claim is reported when it is refused, not at the timeout"


class _OwnedLogin:
    """A client that has judged the node OWNED: `btn_local_login` reveals the
    login FORM (LoginScreen: `if (isFirstRun) onLocalLogin() else
    showLoginForm`), never the wizard, and no `txt_owner_hint` composes
    (the node serves no owner hint). The Android leg's shape, run
    36600766576."""

    def __init__(self):
        self.form = False
        self.clicks: list[str] = []
        self.inputs: dict[str, str] = {}

    def screen(self):
        return "Login"

    def tree(self):
        if self.form:
            return [_el(t) for t in ("input_username", "input_password", "btn_login_submit")]
        return [_el("btn_local_login")]

    def click(self, tag):
        self.clicks.append(tag)
        if tag == "btn_local_login":
            self.form = True

    def input(self, tag, text):
        self.inputs[tag] = text

    def state(self):
        return {"screen": "Login", "clientMode": "NODE", "nodeUrl": "http://127.0.0.1:4243"}


def test_the_login_form_is_the_clients_verdict_that_the_node_is_owned(monkeypatch):
    """No wizard and no owner hint, but a password form: the client holds the
    node to be owned. That is not "did not reach Setup"; it is "sign in"."""
    monkeypatch.setattr(sf, "time", _Clock())
    w = _OwnedLogin()
    sf.run_setup(w, "qaadmin", "QaAdmin!2345")  # must not raise
    assert w.clicks == ["btn_local_login"]
    assert w.form, "the form the client offered is what the fixture judged by"


def test_a_client_that_shows_neither_wizard_nor_form_is_still_reported(monkeypatch):
    monkeypatch.setattr(sf, "time", _Clock())
    w = _OwnedLogin()
    w.click = lambda tag: w.clicks.append(tag)  # the click lands nowhere
    with pytest.raises(sf.SessionUnavailable) as e:
        sf.run_setup(w, "qaadmin", "QaAdmin!2345")
    assert "neither Setup" in str(e.value) and "login form" in str(e.value)


def test_a_refused_sign_in_says_which_side_was_wrong(monkeypatch):
    """The Android leg's actual state: the node has NO owner (its own
    /v1/setup/status says setup_required=true) and the client offered a
    password form anyway. The fixture names the client's first-run check,
    not its credentials."""
    monkeypatch.setattr(sf, "time", _Clock())
    monkeypatch.setattr(sf, "_node_setup_required", lambda url: True)
    w = _OwnedLogin()
    w.form = True
    with pytest.raises(sf.SessionUnavailable) as e:
        sf.log_in(w, "qaadmin", "QaAdmin!2345", timeout=10)
    msg = str(e.value)
    assert "setup_required=true" in msg and "first-run check is wrong" in msg, msg
    assert "127.0.0.1:4243" in msg

    monkeypatch.setattr(sf, "_node_setup_required", lambda url: False)
    with pytest.raises(sf.SessionUnavailable) as e:
        sf.log_in(w, "qaadmin", "QaAdmin!2345", timeout=10)
    assert "has an owner, and these are not its credentials" in str(e.value)
