"""Setup-then-login, so a CSD flow starts from a screen that exists.

    python3 -m testing.gate.session_fixture --username qaadmin --password 'QaAdmin!2345'

WHY A FLOW CANNOT JUST START. Every CSD surface lives in the sidebar, and the
sidebar does not exist on Login. A fresh node has no owner, so there is nothing
to log in as — the session has to be MADE before it can be used, and that is the
wizard.

DISCOVERED BY DRIVING IT, NOT BY READING IT. The sequence below is what the
client actually does on a fresh node, observed step by step through /tree:

    Login (isFirstRun=true)     `btn_local_login`
      (isFirstRun=false: the same button reveals the LOGIN FORM instead —
       the client's own verdict that the node is owned; the fixture takes
       that verdict and signs in, and if the node then refuses, says which
       of the two was wrong)
      -> Setup, step `you`      username / password / confirm / device name,
                                an age band, then `btn_next`
      -> Setup, step `join_federation`   `trace_consent_yes`, consent toggles, `btn_next`
      -> `setup_ownership_claiming`      no advance control and no active step:
                                         the claim is work, not a step, and it
                                         finishes on its own (CLAIM_TIMEOUT)
      -> `setup_ownership_claimed`       or `setup_ownership_error`, which
                                         names why the node refused
      -> Login, now with `txt_owner_hint`
    Login                        `btn_local_login` reveals the form,
                                 username / password, `btn_login_submit`
      -> Contacts                the node home — `homeScreen(hasAgent=false)`,
                                 which is CIRISClient#48's settled answer

Two things worth keeping: on desktop, first-run shows LOGIN and not Setup
("Desktop first-run - showing Login (Google via browser handoff, or local)"), so
a fixture that waits for Screen.Setup waits forever. And the claim step has no
button — polling for a control there is polling for something that will never
appear.

ISOLATION IS TWO DIFFERENT FLAGS, and confusing them cost a CI run earlier:

    the NODE takes `--home <path>`     (Server 0.5 reads no environment at all)
    the CLIENT reads `CIRIS_HOME`      (EnvFileUpdater.desktop, default ~/ciris)

So a throwaway run points each at its own directory and leaves the developer's
own install alone.
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from testing.driver import DriverError, TestAutomationServer  # noqa: E402
from testing.gate.console import utf8_console  # noqa: E402


class SessionUnavailable(RuntimeError):
    """No session could be established. Raised, never swallowed into a skip."""


def _active_step(drv: TestAutomationServer) -> str:
    for e in drv.tree():
        if e.test_tag.startswith("step_indicator_") and (e.text or "") == "active":
            return e.test_tag.removeprefix("step_indicator_")
    return ""


def _tags(drv: TestAutomationServer) -> set[str]:
    return {e.test_tag for e in drv.tree()}


def _wait_clickable(drv: TestAutomationServer, tag: str, timeout: float = 20.0) -> bool:
    """True once `tag` reports can_click (or the server does not report it at all)."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for e in drv.tree():
            if e.test_tag == tag:
                if e.can_click is None or e.can_click:
                    return True
                break
        time.sleep(1.0)
    return False


def _reach(drv: TestAutomationServer, tag: str, act, tries: int = 8):
    """Run `act()` against `tag`, scrolling it into view first when the app
    says it is composed but off screen. A phone's first-run wizard is taller
    than the screen: on the iPhone the account fields sit below the age band
    and the AI choice, and /input and /click refuse what the person could not
    see (CIRISClient#33). Scroll down a step at a time, bounded; anything else
    raises as before."""
    # Down first (the wizard fills top to bottom), then back up past the
    # start: an element the earlier steps scrolled past sits ABOVE the fold.
    notes: list[str] = []
    # Down until the screen says it is at the bottom, then up until the top,
    # trying the act after every step. A phone with the keyboard up has a
    # small viewport, so a fixed number of steps can turn around before it
    # ever reaches the last field.
    for direction in ("down", "up"):
        for _ in range(tries * 3):
            try:
                return act()
            except DriverError as e:
                if "off screen" not in str(e):
                    raise
            try:
                r = drv.scroll_to(tag, direction=direction, amount=300)
                msg = (r or {}).get("error") if isinstance(r, dict) else None
            except DriverError as se:
                msg = str(se)[-160:]
            notes.append(f"{direction}: {msg or 'moved'}")
            time.sleep(0.6)
            if msg and ("already at the" in msg or "NO overflow" in msg):
                break
    try:
        return act()
    except DriverError as e:
        # Say what the scrolls answered: "no overflow" means the wizard's own
        # scrollable is not the one registered, which is a client defect.
        raise DriverError(f"{e} | scrolls: {'; '.join(dict.fromkeys(notes))}") from None


def _field_report(drv: TestAutomationServer) -> str:
    """What each tagged element on screen holds: input values (passwords by
    length only), texts, and click/input capability — so a disabled Next
    names the condition it is waiting on instead of just the tag list."""
    parts = []
    for e in drv.tree():
        val = getattr(e, "input_value", None)
        if val is not None and "password" in e.test_tag:
            val = f"<{len(val)} chars>"
        txt = (e.text or "")[:60]
        parts.append(f"{e.test_tag}[v={val!r} t={txt!r} c={e.can_click} i={e.can_input}]")
    return "; ".join(sorted(parts))


#: How a platform refuses a click on a disabled control: every platform from
#: 0.5.225 (DisabledControls, HTTP 409), and iOS/Android before it (HTTP 404).
DISABLED_ANSWERS = ("is disabled", "No click handler")


#: How long a wizard step may take to show the next one after Next. Windows
#: (run 36588619656) went blank for more than the 2 s the fixture used to
#: sleep — the fed-ID mint on Next and the next step's first composition on a
#: cold JVM — and the fixture called a working wizard stuck. A step that has
#: not moved in this long has stalled, and is reported by name.
ADVANCE_TIMEOUT = 90.0


#: The claim's three faces (SetupScreen's completion step). Any of them means
#: the wizard is done asking and the fixture has nothing left to click.
CLAIM_TAGS = frozenset({"setup_ownership_claiming", "setup_ownership_claimed", "setup_ownership_error"})

#: How long the in-progress claim may run before the fixture calls it stuck.
CLAIM_TIMEOUT = 120.0


def _claim_started(tags: set[str]) -> bool:
    return bool(CLAIM_TAGS & tags)


#: Where a failed claim's reason is readable, most specific first.
#: `setup_ownership_error` is the FailurePanel's CONTAINER; the reason itself is
#: the panel's detail, under its title. Android, run 36746575125: the fixture
#: read only the container, found no text there, and printed "(no reason on
#: screen)" beside a list that included `failure_panel_detail`.
CLAIM_REASON_TAGS = ("setup_ownership_error", "failure_panel_title", "failure_panel_detail")


def _claim_failure_reason(drv: TestAutomationServer, tree) -> str:
    """The failed claim's reason as the screen states it. When no element
    carries text, say THAT — with what each element held — because "no
    reason" reads as the node's silence when it is the client's."""
    texts = {e.test_tag: (e.text or "").strip() for e in tree if e.test_tag in CLAIM_REASON_TAGS}
    said = list(dict.fromkeys(t for t in (texts.get(k, "") for k in CLAIM_REASON_TAGS) if t))
    if said:
        return " — ".join(said)
    return ("the error screen registered no text for "
            f"{', '.join(k for k in CLAIM_REASON_TAGS if k in texts)}; fields: {_field_report(drv)}")


def _await_claim(drv: TestAutomationServer, timeout: float = CLAIM_TIMEOUT, poll: float = 1.5) -> None:
    """Wait out `setup_ownership_claiming`. It has no control — Android (run
    36733112700) showed it with the step indicators and no active step, and a
    fixture looking for Next there raised "offers no advance control". Returns
    once the claim resolved (claimed, or the app left Setup); raises with the
    node's reason on `setup_ownership_error`, and with the screen on timeout."""
    deadline = time.monotonic() + timeout
    while True:
        if drv.screen() != "Setup":
            return
        tree = drv.tree()
        tags = {e.test_tag for e in tree}
        if "setup_ownership_error" in tags:
            raise SessionUnavailable(
                f"the ownership claim failed: {_claim_failure_reason(drv, tree)}; "
                f"on screen: {sorted(tags)}"
            )
        if "setup_ownership_claiming" not in tags:
            return
        if time.monotonic() >= deadline:
            raise SessionUnavailable(
                f"the claim did not finish within {timeout:.0f}s (still claiming); "
                f"on screen: {sorted(tags)}"
            )
        time.sleep(poll)


def _advanced(drv: TestAutomationServer, before: tuple, timeout: float, poll: float = 1.0) -> bool:
    """True once the wizard is past `before` (screen, active step) or the claim
    has taken over; False when it is still there after `timeout`."""
    deadline = time.monotonic() + timeout
    while True:
        if (drv.screen(), _active_step(drv)) != before or _claim_started(_tags(drv)):
            return True
        if time.monotonic() >= deadline:
            return False
        time.sleep(poll)


def _settle(drv: TestAutomationServer, want: str, timeout: float = 90.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if drv.screen() == want:
            return True
        time.sleep(1.5)
    return False


#: The login FORM: what "Local login" reveals when the client has judged the
#: node OWNED (LoginScreen: `if (isFirstRun) onLocalLogin() else showLoginForm`).
LOGIN_FORM = frozenset({"input_username", "input_password", "btn_login_submit"})


def _login_form_shown(drv: TestAutomationServer) -> bool:
    return drv.screen() == "Login" and LOGIN_FORM <= _tags(drv)


def _after_local_login(drv: TestAutomationServer, timeout: float) -> str:
    """Where `btn_local_login` took the client: "Setup" (the wizard — the
    client read the node as fresh), "form" (the login form — the client read
    it as owned), or "" when neither showed within `timeout`."""
    deadline = time.monotonic() + timeout
    while True:
        if drv.screen() == "Setup":
            return "Setup"
        if _login_form_shown(drv):
            return "form"
        if time.monotonic() >= deadline:
            return ""
        time.sleep(1.5)


def run_setup(drv: TestAutomationServer, username: str, password: str,
              device: str = "gate") -> None:
    """Drive the first-run wizard until the node has an owner — or find that
    the client already holds the node to be owned, and leave it to `log_in`.

    THE CLIENT'S VERDICT, NOT A HINT. `txt_owner_hint` composes only when
    `/v1/auth/owner-hint` returns a hint, which a node claimed by this very
    fixture need not serve; the Android leg (run 36600766576) showed Login
    without it, "Local login" opened the login FORM (the client's isFirstRun
    was false), and the fixture — waiting for Setup — called that "did not
    reach Setup" and blamed a missing hint. What the form says is that the
    client judged the node owned; that is the thing to act on, and if the
    node then refuses the credentials, `log_in` says which side was wrong."""
    if "txt_owner_hint" in _tags(drv) or _login_form_shown(drv):
        return  # the client holds the node to be owned; sign in

    # Desktop's first run shows Login; a client whose first run opens the wizard
    # directly is already where this click would take it, and clicking a
    # `btn_local_login` that is not on screen fails the fixture for nothing.
    if drv.screen() != "Setup":
        drv.click("btn_local_login")
    landed = _after_local_login(drv, timeout=30)
    if landed == "form":
        return
    if landed != "Setup":
        raise SessionUnavailable(
            f"btn_local_login reached neither Setup (the wizard) nor the login form "
            f"within 30s (on {drv.screen()!r}); on screen: {sorted(_tags(drv))}"
        )

    for tag, value in (("input_username", username),
                       ("input_password", password),
                       ("input_password_confirm", password),
                       ("input_device_name", device)):
        try:
            _reach(drv, tag, lambda t=tag, v=value: drv.input(t, v))
        except DriverError as e:
            raise SessionUnavailable(f"wizard: {tag} would not accept input ({e})") from e
        # iOS needs ~2 s between fields for the value to reach the ViewModel's
        # StateFlow (client/CLAUDE.md, "Important iOS notes"). Without it the
        # fields read empty and Next never enables: the first iOS run of this
        # fixture stopped at "wizard did not advance past 'you'".
        time.sleep(2.0)
    # The fed-ID label: desktop mints/admits the identity itself, but a client
    # that asks (iOS) keeps Next disabled until the label is valid
    # (SetupState.canProceedFromCurrentStep: YOU -> fedIdOk; generic words
    # like "me" are refused, so use the device name, which is specific).
    if "input_fedid_label" in _tags(drv):
        try:
            _reach(drv, "input_fedid_label",
                   lambda: drv.input("input_fedid_label", f"{device} gate identity"))
        except DriverError as e:
            raise SessionUnavailable(f"wizard: input_fedid_label would not accept input ({e})") from e
        time.sleep(2.0)
    _reach(drv, "age_band_adult", lambda: drv.click("age_band_adult"))
    # The legs run against a bare node with no LLM, so answer "run without AI":
    # it removes the AI step, whose Next waits for a usable LLM choice
    # (SetupState: AI -> hasUsableLlmChoice). Desktop already defaults there;
    # iOS asks, and the walk stopped at 'ai' with Next disabled.
    if "opt_run_without_ai" in _tags(drv):
        _reach(drv, "opt_run_without_ai", lambda: drv.click("opt_run_without_ai"))
        time.sleep(1.0)

    # Advance until the claim takes over. Bounded: a wizard that stops advancing
    # must say so rather than spin.
    for _ in range(12):
        tags = _tags(drv)
        if _claim_started(tags) or drv.screen() != "Setup":
            break
        # Screen 2 asks whether to send traces and will not advance until
        # answered (no default, like the age band above). Yes is the fixture's
        # answer for the same reason age_band_adult is: the unrestricted path.
        if "trace_consent_yes" in tags:
            _reach(drv, "trace_consent_yes", lambda: drv.click("trace_consent_yes"))
            # The step advances only once the answer has reached the ViewModel
            # (`SetupState.canProceedFromCurrentStep`: JOIN_FEDERATION ->
            # traceConsentAnswered). A Next clicked in the same instant as the
            # answer raced it on Windows; give the answer a beat to land.
            time.sleep(1.5)
            tags = _tags(drv)
        nxt = "btn_wizard_complete" if "btn_wizard_complete" in tags else "btn_next"
        if nxt not in tags:
            raise SessionUnavailable(
                f"wizard step {_active_step(drv)!r} offers no advance control; "
                f"on screen: {sorted(tags)}"
            )
        # A disabled advance control is present but has no click handler (a
        # `testableClickable(enabled = false)`), and clicking it is a 404 that
        # says nothing about WHY. On iOS the fields reach the ViewModel a beat
        # after they are typed, so Next enables late: wait for it, bounded, and
        # if it never enables say which step and what was on screen.
        if not _wait_clickable(drv, nxt, timeout=20.0):
            raise SessionUnavailable(
                f"wizard step {_active_step(drv)!r}: {nxt} never became clickable; "
                f"on screen: {sorted(_tags(drv))}"
            )
        before = (drv.screen(), _active_step(drv))
        # iOS's /tree omits `canClick` when it is false (defaults are not
        # serialized), so `_wait_clickable` cannot see a disabled Next there.
        # A disabled control refuses the click — 409 "is disabled" from 0.5.225
        # on every platform, 404 "No click handler" on older mobile clients:
        # treat either as "not yet", bounded, and name the step if it stays so.
        clicked = False
        for _ in range(15):
            try:
                _reach(drv, nxt, lambda: drv.click(nxt))
                clicked = True
                break
            except DriverError as e:
                if not any(w in str(e) for w in DISABLED_ANSWERS):
                    raise
                time.sleep(2.0)
        if not clicked:
            raise SessionUnavailable(
                f"wizard step {before[1]!r}: {nxt} stayed disabled for 30s; "
                f"fields: {_field_report(drv)}"
            )
        # WAITED FOR, NOT SLEPT AT. The step advances when the app is ready,
        # not two seconds after the click (see ADVANCE_TIMEOUT).
        if not _advanced(drv, before, ADVANCE_TIMEOUT):
            # One retry when the step's question is still on screen: the answer
            # may not have landed before Next was clicked.
            if "trace_consent_yes" in _tags(drv):
                drv.click("trace_consent_yes")
                time.sleep(2.0)
                drv.click(nxt)
                if _advanced(drv, before, 30.0):
                    continue
            # Say what was on screen: a required field the fixture doesn't fill
            # (the with-AI wizard asks for more than the node one) shows up here.
            raise SessionUnavailable(
                f"wizard did not advance past {before[1]!r} within {ADVANCE_TIMEOUT:.0f}s; "
                f"on screen: {sorted(_tags(drv))}"
            )

    # The claim has no button; it completes and the app returns to Login.
    _await_claim(drv)
    if not _settle(drv, "Login", timeout=180):
        raise SessionUnavailable(
            f"setup never returned to Login (on {drv.screen()!r}) — the claim did not finish"
        )


def log_in(drv: TestAutomationServer, username: str, password: str,
           timeout: float = 90.0) -> str:
    """Sign in and return the screen the client lands on."""
    if "btn_local_login" in _tags(drv):
        drv.click("btn_local_login")
        time.sleep(1.5)
    for tag, value in (("input_username", username), ("input_password", password)):
        try:
            drv.input(tag, value)
        except DriverError as e:
            raise SessionUnavailable(f"login: {tag} would not accept input ({e})") from e
    drv.click("btn_login_submit")

    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        screen = drv.screen()
        if screen != "Login":
            return screen
        time.sleep(1.5)
    raise SessionUnavailable(f"still on Login after {timeout:.0f}s{_why_login_failed(drv)}")


def _node_setup_required(node_url: str) -> bool | None:
    """The NODE's own first-run predicate (`GET /v1/setup/status`), or None
    when it cannot be read. Best effort: this is a diagnosis, never a gate."""
    import json  # noqa: PLC0415
    import urllib.request  # noqa: PLC0415
    try:
        with urllib.request.urlopen(f"{node_url.rstrip('/')}/v1/setup/status", timeout=5) as r:
            body = json.loads(r.read().decode("utf-8", "replace"))
    except Exception:  # noqa: BLE001 — unreadable is "cannot say"
        return None
    data = body.get("data", body) if isinstance(body, dict) else {}
    value = data.get("setup_required") if isinstance(data, dict) else None
    return value if isinstance(value, bool) else None


def _why_login_failed(drv: TestAutomationServer) -> str:
    """Which side was wrong when the client's login form refused the fixture's
    owner: the node (it has an owner and these are not its credentials) or
    the client (the node says setup is required — it has NO owner — and the
    client offered a password form instead of the wizard). Read off the
    node's own predicate, from the node URL the client reports in `/state`."""
    try:
        node_url = str(drv.state().get("nodeUrl") or "")
    except Exception:  # noqa: BLE001 — an older client serves no /state
        node_url = ""
    if not node_url:
        return ""
    required = _node_setup_required(node_url)
    if required is True:
        return (f"; the node at {node_url} says setup_required=true (it has no owner), yet the "
                f"client offered a login form instead of the wizard: the client's first-run "
                f"check is wrong, not these credentials (CIRISApp.checkFirstRunStatus, NODE-only branch)")
    if required is False:
        return f"; the node at {node_url} has an owner, and these are not its credentials"
    return f"; the node at {node_url} could not say whether it has an owner"


def establish(drv: TestAutomationServer, username: str, password: str) -> str:
    """Whatever it takes to get from a cold client to a signed-in one."""
    drv.wait_for_server(timeout=120)
    if drv.wait_for_ui(timeout=120) == 0:
        raise SessionUnavailable("the app started but never composed a UI")
    run_setup(drv, username, password)
    return log_in(drv, username, password)


def main(argv: list[str]) -> int:
    utf8_console()
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", default="http://127.0.0.1:9091")
    ap.add_argument("--username", default="qaadmin")
    ap.add_argument("--password", default="QaAdmin!2345")
    args = ap.parse_args(argv)

    drv = TestAutomationServer(base_url=args.url)
    try:
        landed = establish(drv, args.username, args.password)
    except (SessionUnavailable, DriverError) as e:
        print(f"::error::{e}", file=sys.stderr)
        return 1
    print(f"  signed in — landed on {landed}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
