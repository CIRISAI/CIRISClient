"""Setup-then-login, so a CSD flow starts from a screen that exists.

    python3 -m testing.gate.session_fixture --username qaadmin --password 'QaAdmin!2345'

WHY A FLOW CANNOT JUST START. Every CSD surface lives in the sidebar, and the
sidebar does not exist on Login. A fresh node has no owner, so there is nothing
to log in as — the session has to be MADE before it can be used, and that is the
wizard.

DISCOVERED BY DRIVING IT, NOT BY READING IT. The sequence below is what the
client actually does on a fresh node, observed step by step through /tree:

    Login (isFirstRun=true)     `btn_local_login`
      -> Setup, step `you`      username / password / confirm / device name,
                                an age band, then `btn_next`
      -> Setup, step `join_federation`   consent toggles, `btn_next`
      -> `setup_ownership_claimed`       no advance control: the claim is work,
                                         not a step, and it finishes on its own
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


class SessionUnavailable(RuntimeError):
    """No session could be established. Raised, never swallowed into a skip."""


def _active_step(drv: TestAutomationServer) -> str:
    for e in drv.tree():
        if e.test_tag.startswith("step_indicator_") and (e.text or "") == "active":
            return e.test_tag.removeprefix("step_indicator_")
    return ""


def _tags(drv: TestAutomationServer) -> set[str]:
    return {e.test_tag for e in drv.tree()}


def _settle(drv: TestAutomationServer, want: str, timeout: float = 90.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if drv.screen() == want:
            return True
        time.sleep(1.5)
    return False


def run_setup(drv: TestAutomationServer, username: str, password: str,
              device: str = "gate") -> None:
    """Drive the first-run wizard until the node has an owner."""
    if "txt_owner_hint" in _tags(drv):
        return  # already owned; nothing to do

    drv.click("btn_local_login")
    if not _settle(drv, "Setup", timeout=30):
        raise SessionUnavailable(
            f"btn_local_login did not reach Setup (on {drv.screen()!r}); on a node "
            f"that already has an owner this fixture should have seen txt_owner_hint"
        )

    for tag, value in (("input_username", username),
                       ("input_password", password),
                       ("input_password_confirm", password),
                       ("input_device_name", device)):
        try:
            drv.input(tag, value)
        except DriverError as e:
            raise SessionUnavailable(f"wizard: {tag} would not accept input ({e})") from e
    drv.click("age_band_adult")

    # Advance until the claim takes over. Bounded: a wizard that stops advancing
    # must say so rather than spin.
    for _ in range(12):
        tags = _tags(drv)
        if "setup_ownership_claimed" in tags or drv.screen() != "Setup":
            break
        nxt = "btn_wizard_complete" if "btn_wizard_complete" in tags else "btn_next"
        if nxt not in tags:
            raise SessionUnavailable(
                f"wizard step {_active_step(drv)!r} offers no advance control; "
                f"on screen: {sorted(tags)}"
            )
        before = (drv.screen(), _active_step(drv))
        drv.click(nxt)
        time.sleep(2.0)
        if (drv.screen(), _active_step(drv)) == before and "setup_ownership_claimed" not in _tags(drv):
            raise SessionUnavailable(f"wizard did not advance past {before[1]!r}")

    # The claim has no button; it completes and the app returns to Login.
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
    raise SessionUnavailable(f"still on Login after {timeout:.0f}s")


def establish(drv: TestAutomationServer, username: str, password: str) -> str:
    """Whatever it takes to get from a cold client to a signed-in one."""
    drv.wait_for_server(timeout=120)
    if drv.wait_for_ui(timeout=120) == 0:
        raise SessionUnavailable("the app started but never composed a UI")
    run_setup(drv, username, password)
    return log_in(drv, username, password)


def main(argv: list[str]) -> int:
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
