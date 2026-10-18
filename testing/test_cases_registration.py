"""elements_are_registered must tolerate the first frame, not an empty screen.

PR #192's desktop e2e failed with the tree read 0.01 s after the client settled
on Login: the screen had composed, its elements had not yet registered. A
screen that never registers anything (CIRISClient#7) must still fail.
"""

import pytest

from testing import cases


class _App:
    def __init__(self, answers):
        self._answers = list(answers)

    def tags(self):
        return self._answers.pop(0) if len(self._answers) > 1 else self._answers[0]

    def screen(self):
        return "Login"


def _ctx(app):
    return cases.Context(corner=cases.LOCAL_NODE, app=app, node_url="http://x", node_was_prestarted=False)


def test_registration_that_lands_a_moment_late_passes(monkeypatch):
    monkeypatch.setattr(cases, "REGISTRATION_POLL_S", 0.0)
    ctx = _ctx(_App([[], [], ["btn_login"]]))
    cases.elements_are_registered(ctx)
    assert ctx.notes == ["1 elements registered"]


def test_a_screen_that_never_registers_still_fails(monkeypatch):
    monkeypatch.setattr(cases, "REGISTRATION_POLL_S", 0.0)
    monkeypatch.setattr(cases, "REGISTRATION_GRACE_S", 0.05)
    with pytest.raises(AssertionError, match="CIRISClient#7"):
        cases.elements_are_registered(_ctx(_App([[]])))
