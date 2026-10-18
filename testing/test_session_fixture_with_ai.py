"""The with-AI wizard: the fixture answers WITH, fills the AI step from the live
model list, never substitutes a model, and never lets the key into a message.

Driven against a fake agent wizard on a fake clock, the trade
test_session_fixture.py makes for the node wizard.
"""

from __future__ import annotations

import pytest

from testing.driver import DriverError, Element
from testing.gate import session_fixture as sf

KEY = "sk-ant-api03-" + "Qq7" * 20 + "-AA"
MODELS = ["claude-haiku-5-5", "claude-opus-5-5", "claude-sonnet-5-5"]


class _Clock:
    def __init__(self):
        self.t = 0.0

    def monotonic(self):
        return self.t

    def sleep(self, s):
        self.t += s


def _el(tag, text=None, value=None):
    return Element(test_tag=tag, x=0, y=0, width=10, height=10, text=text, can_click=True,
                   input_value=value)


class _AgentWizard:
    """Login -> Setup you (with/without AI) -> join_federation -> ai -> claim -> Interact.

    `models_after` is how many Test Connection presses it takes before the live
    list appears (a warming agent); `verdict` is what txt_llm_verdict says
    meanwhile — and it QUOTES the key, the worst case for a message."""

    def __init__(self, models=MODELS, models_after=1, offers_with_ai=True, skip_ai=False):
        self.on, self.step = "Login", "you"
        self.models, self.models_after = models, models_after
        self.offers_with_ai, self.skip_ai = offers_with_ai, skip_ai
        self.with_ai = None
        self.tests = 0
        self.provider = None
        self.key = None
        self.model_open = self.provider_open = False
        self.model = None
        self.consented = False
        self.clicks: list[str] = []

    def screen(self):
        return self.on

    def _ind(self):
        return [_el(f"step_indicator_{s}", "active" if s == self.step else "")
                for s in ("you", "join_federation", "ai")]

    def tree(self):
        if self.on != "Setup":
            return [_el("btn_local_login")] if self.on == "Login" else [_el("interact_transcript")]
        if self.step == "you":
            base = [_el(t) for t in ("input_username", "input_password", "input_password_confirm",
                                     "input_device_name", "age_band_adult", "btn_next")]
            if self.offers_with_ai:
                base += [_el("opt_run_with_ai"), _el("opt_run_without_ai")]
            return base + self._ind()
        if self.step == "join_federation":
            return [_el("trace_consent_yes"), _el("btn_next")] + self._ind()
        out = [_el("input_llm_provider"), _el("btn_test_connection"), _el("btn_next"),
               _el("txt_llm_verdict", f"401 invalid x-api-key {KEY}" if self.tests else None)]
        if self.provider_open:
            out += [_el("menu_provider_anthropic"), _el("menu_provider_openai")]
        if self.provider:
            # A sensitive sink: present, never echoing what it holds.
            out.append(_el("input_api_key", value=None))
        if self.tests >= self.models_after and self.key:
            out.append(_el("input_llm_model"))
            if self.model_open:
                out += [_el(sf.model_tag(m)) for m in self.models]
        return out + self._ind()

    def click(self, tag):
        self.clicks.append(tag)
        if tag not in {e.test_tag for e in self.tree()}:
            raise DriverError(f"POST /click -> HTTP 404: No click handler for {tag!r}")
        if tag == "btn_local_login":
            self.on = "Setup"
        elif tag in ("opt_run_with_ai", "opt_run_without_ai"):
            self.with_ai = tag == "opt_run_with_ai"
        elif tag == "trace_consent_yes":
            self.consented = True
        elif tag == "input_llm_provider":
            self.provider_open = not self.provider_open
        elif tag.startswith("menu_provider_"):
            self.provider, self.provider_open = tag[len("menu_provider_"):], False
        elif tag == "btn_test_connection":
            self.tests += 1
        elif tag == "input_llm_model":
            self.model_open = True
        elif tag.startswith("menu_model_"):
            self.model, self.model_open = tag, False
        elif tag == "btn_next":
            if self.step == "you":
                self.step = "join_federation"
            elif self.step == "join_federation":
                if self.with_ai and not self.skip_ai:
                    self.step = "ai"
                else:
                    self.on = "Interact"
            elif self.step == "ai":
                if not self.model:
                    raise DriverError("POST /click -> HTTP 409: btn_next is disabled")
                self.on = "Interact"   # the agent signs the new owner in

    def input(self, tag, value, clear_first=True, verify=True):
        if tag == "input_api_key":
            assert verify is False, "a sensitive sink has no value to read back"
            self.key = value

    def scroll_to(self, *a, **k):
        return {"error": "already at the bottom"}


@pytest.fixture(autouse=True)
def _clock(monkeypatch):
    c = _Clock()
    monkeypatch.setattr(sf.time, "monotonic", c.monotonic)
    monkeypatch.setattr(sf.time, "sleep", c.sleep)
    return c


def _llm(model="claude-sonnet-5-5"):
    return sf.LlmChoice(provider="anthropic", model=model, key=KEY)


def test_a_with_ai_leg_answers_with_fills_the_ai_step_and_picks_the_named_model():
    w = _AgentWizard(models_after=3)
    sf.run_setup(w, "qaadmin", "pw-QaAdmin!2345", llm=_llm())
    assert w.with_ai is True and w.provider == "anthropic" and w.key == KEY
    assert w.model == sf.model_tag("claude-sonnet-5-5")
    assert w.tests == 3, "Test Connection is retried while the agent warms"
    assert w.on == "Interact"
    assert sf.log_in(w, "qaadmin", "pw") == "Interact", "already signed in: no login form to fill"


def test_a_model_the_provider_does_not_list_is_refused_not_substituted():
    w = _AgentWizard()
    with pytest.raises(sf.SessionUnavailable, match="not in the live model list") as e:
        sf.run_setup(w, "qaadmin", "pw", llm=_llm("claude-nonexistent-1"))
    assert w.model is None
    assert KEY not in str(e.value)


def test_a_key_that_never_yields_a_list_fails_naming_the_verdict_without_the_key():
    w = _AgentWizard(models_after=99)
    with pytest.raises(sf.SessionUnavailable, match="never produced a live model list") as e:
        sf.run_setup(w, "qaadmin", "pw", llm=_llm())
    msg = str(e.value)
    assert "401 invalid x-api-key" in msg and KEY not in msg and "[redacted]" in msg


def test_a_client_that_offers_no_with_ai_answer_is_a_failure_not_a_node_run():
    w = _AgentWizard(offers_with_ai=False)
    with pytest.raises(sf.SessionUnavailable, match="offers no `opt_run_with_ai`"):
        sf.run_setup(w, "qaadmin", "pw", llm=_llm())


def test_a_wizard_that_never_shows_the_ai_step_did_not_configure_the_agent():
    w = _AgentWizard(skip_ai=True)
    with pytest.raises(sf.SessionUnavailable, match="without ever showing the AI step"):
        sf.run_setup(w, "qaadmin", "pw", llm=_llm())


def test_without_an_llm_the_fixture_still_answers_without_ai():
    w = _AgentWizard(skip_ai=True)
    sf.run_setup(w, "qaadmin", "pw")
    assert w.with_ai is False and w.provider is None


def test_the_choice_never_shows_its_key(monkeypatch):
    monkeypatch.setenv("QA_KEY", KEY)
    c = sf.LlmChoice.from_env("anthropic", "claude-sonnet-5-5", "QA_KEY")
    assert KEY not in repr(c) and KEY not in str(c)
    monkeypatch.setenv("QA_KEY", " ")
    with pytest.raises(sf.SessionUnavailable, match=r"\$QA_KEY"):
        sf.LlmChoice.from_env("anthropic", "m", "QA_KEY")


def test_the_field_report_masks_every_secret_looking_field():
    class D:
        def tree(self):
            return [_el("input_api_key", value=KEY), _el("input_password", value="hunter22"),
                    _el("input_username", value="qaadmin")]
    rep = sf._field_report(D())
    assert KEY not in rep and "hunter22" not in rep and "'qaadmin'" in rep
