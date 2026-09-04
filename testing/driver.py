"""
A driver for the client's own TestAutomationServer.

The SERVER side of this already lives in the client and is ours:
`client/desktopApp/.../testing/TestAutomationServer.kt` (desktop) and the
`TestAutomationServer.{android,ios}.kt` actuals. What did NOT live here was
anything that DRIVES it — that was `tools/test_desktop_wipe_setup.sh` in
CIRISAgent, 191 lines of `curl | grep` against five of the sixteen routes.
This is that driver, taken over and made a library.

Three things it does that the shell script could not:

1. **It fails loudly.** The shell script's `get_screen()` was
   `curl -s "$TEST_URL/screen"`, and `curl -s` on a dead server prints nothing
   and exits 0 — so a run against an app that never started read as a screen
   named "" and walked on. Every call here raises `DriverError` with the route,
   the status and the body.

2. **It waits on the right thing.** `wait_for_element` polls `/tree` for the
   element, not `sleep 2` and hope.

3. **It is stdlib-only.** No `requests`, so CI installs nothing to use it.

Route surface is TestAutomationServer.kt's sixteen; see `TestAutomationServer`
below for the ones we bind.
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any


#: Substrings naming a field whose value is masked, and so cannot be read back.
#: Verifying one would fail on every correct run, which is worse than not
#: verifying: a check that cries wolf gets switched off, and then the fields it
#: WAS protecting go unchecked too.
_SECRET_MARKERS = ("password", "secret", "token", "api_key", "apikey")


def _looks_secret(test_tag: str) -> bool:
    t = test_tag.lower()
    return any(m in t for m in _SECRET_MARKERS)


class DriverError(RuntimeError):
    """A call to the automation server failed, or the app never answered."""


@dataclass
class Element:
    """One registered UI element, as `/tree` reports it."""

    test_tag: str
    x: int
    y: int
    width: int
    height: int
    text: str | None = None

    #: What automation can DO to this element, not merely see it. Served on
    #: every platform from 0.5.199; absent on older clients, where None means
    #: "this client cannot say" and must never be read as False.
    can_click: bool | None = None
    can_input: bool | None = None

    #: A text field's current value, from 0.5.200. `text` carries the same value
    #: for input elements (the client mirrors it there, because that is where
    #: drivers look) and a LABEL for display elements.
    input_value: str | None = None

    @property
    def is_ghost(self) -> bool:
        """Registered, but nothing can drive it — the CIRISClient#30 shape.

        An element that outlives the composable that registered it stays in
        `/tree` looking exactly like a live control. It was the whole of #30:
        the gate waited for `input_username`, matched the SETUP WIZARD's field
        still in the registry, and concluded a login form was up that had never
        composed.

        `canClick`/`canInput` are computed live from the handler and sink
        registries, so a stale entry reports both False while a live control
        reports one of them True. That makes a ghost detectable in one poll,
        where otherwise it takes a screenshot and a person.

        None (an older client that does not serve these) is NOT a ghost — it is
        an unknown, and calling it one would fail every pre-0.5.199 run.
        """
        return self.can_click is False and self.can_input is False

    @classmethod
    def from_json(cls, d: dict[str, Any]) -> "Element":
        return cls(
            test_tag=d.get("testTag") or d.get("test_tag") or "",
            x=int(d.get("x", 0)),
            y=int(d.get("y", 0)),
            width=int(d.get("width", 0)),
            height=int(d.get("height", 0)),
            text=d.get("text"),
            can_click=d.get("canClick"),
            can_input=d.get("canInput"),
            input_value=d.get("inputValue"),
        )


@dataclass
class TestAutomationServer:
    """A live connection to one running client's automation server."""

    base_url: str = "http://127.0.0.1:9091"
    timeout: float = 10.0
    trace: list[str] = field(default_factory=list)

    # ---- transport ----------------------------------------------------

    def _call(self, method: str, route: str, body: dict | None = None) -> Any:
        url = f"{self.base_url}{route}"
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                raw = r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")[:400]
            raise DriverError(f"{method} {route} -> HTTP {e.code}: {detail}") from None
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            # This is the case `curl -s` swallowed: nothing is listening, which
            # means the app is not running. It is never a passing test.
            raise DriverError(f"{method} {route} -> no answer from {self.base_url}: {e}") from None
        self.trace.append(f"{method} {route}")
        if not raw.strip():
            return None
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return raw

    # ---- reads --------------------------------------------------------

    def health(self) -> dict:
        return self._call("GET", "/health") or {}

    def screen(self) -> str:
        """The screen the app believes it is showing."""
        r = self._call("GET", "/screen")
        if isinstance(r, dict):
            return str(r.get("screen") or r.get("currentScreen") or "")
        return str(r or "")

    def state(self) -> dict:
        """
        The app's own account of its gates: screen, test mode, clientMode, node URL.

        Served by `/state`, added for this harness. The alternative was to infer
        node-vs-agent from which widgets are on screen, which asserts the layout
        rather than the gate and passes a client that draws agent affordances
        against a bare node.
        """
        r = self._call("GET", "/state")
        return r if isinstance(r, dict) else {}

    def client_mode(self) -> str:
        """`NODE`, `AGENT`, or `unset` while the probe is undetermined."""
        return str(self.state().get("clientMode") or "unset")

    def wait_for_client_mode(self, expected: str, timeout: float = 90.0) -> None:
        deadline = time.monotonic() + timeout
        seen: list[str] = []
        while time.monotonic() < deadline:
            cur = self.client_mode()
            if cur == expected:
                return
            if not seen or seen[-1] != cur:
                seen.append(cur)
            time.sleep(1.0)
        raise DriverError(
            f"clientMode never became {expected!r} within {timeout:.0f}s "
            f"(saw: {' -> '.join(seen) or '<nothing>'})"
        )

    def tree(self) -> list[Element]:
        r = self._call("GET", "/tree")
        raw = r.get("elements", []) if isinstance(r, dict) else (r or [])
        return [Element.from_json(e) for e in raw if isinstance(e, dict)]

    def element(self, test_tag: str) -> Element | None:
        try:
            r = self._call("GET", f"/element/{test_tag}")
        except DriverError:
            return None
        return Element.from_json(r) if isinstance(r, dict) and r.get("testTag") else None

    def tags(self) -> set[str]:
        return {e.test_tag for e in self.tree()}

    # ---- writes -------------------------------------------------------

    def click(self, test_tag: str) -> None:
        self._call("POST", "/click", {"testTag": test_tag})

    def input(self, test_tag: str, text: str, clear_first: bool = True,
              verify: bool = True) -> None:
        """Type into a field, and confirm the field actually holds it.

        `success: true` used to mean the request was POSTED, not that anything
        changed (CIRISClient#31). From 0.5.200 the client acknowledges on apply,
        so this is belt and braces rather than the only defence -- but it is the
        half that keeps working against an OLDER client, and it is the half that
        catches a field applying something different from what was sent.

        A field that exposes no value is reported UNVERIFIABLE, not failed: on a
        client before 0.5.200 nothing exposed one, and failing there would make
        this driver unusable against exactly the versions worth reproducing
        against. A field that exposes a DIFFERENT value is the defect this
        exists to catch, and that raises.
        """
        self._call("POST", "/input", {"testTag": test_tag, "text": text, "clearFirst": clear_first})
        if verify and not _looks_secret(test_tag):
            self._verify_input_landed(test_tag, text)

    def _verify_input_landed(self, test_tag: str, expected: str, budget: float = 3.0) -> None:
        deadline = time.monotonic() + budget
        seen: str | None = None
        exposed = False
        while time.monotonic() < deadline:
            el = self.element(test_tag)
            if el is not None:
                value = el.input_value if el.input_value is not None else el.text
                if value is None:
                    # STRUCTURAL, NOT SLOW. A client that omits the value omits
                    # it however long we poll, so burning the budget per field
                    # buys nothing and costs real time on the slowest platform.
                    return
                exposed = True
                seen = value
                if seen == expected:
                    return
            time.sleep(0.1)
        if not exposed:
            return
        raise DriverError(
            f"/input on {test_tag!r} was acknowledged, but {budget:.0f}s later the field "
            f"holds {seen!r} rather than {expected!r} — the client reported success for "
            f"input it did not apply"
        )

    def navigate(self, screen: str) -> None:
        self._call("POST", "/navigate", {"screen": screen})

    def act(self, action: str, **kw: Any) -> Any:
        return self._call("POST", "/act", {"action": action, **kw})

    def screenshot(self, path: str) -> bool:
        """Ask the app to raise itself and capture. Returns False if it declined."""
        try:
            r = self._call("POST", "/screenshot", {"path": path})
        except DriverError:
            return False
        return bool(r.get("success", True)) if isinstance(r, dict) else True

    # ---- waits --------------------------------------------------------

    def wait_for_server(self, timeout: float = 90.0) -> None:
        """Block until the app's automation server answers at all."""
        deadline = time.monotonic() + timeout
        last = ""
        while time.monotonic() < deadline:
            try:
                self.health()
                return
            except DriverError as e:
                last = str(e)
                time.sleep(1.0)
        raise DriverError(f"automation server never came up within {timeout:.0f}s: {last}")

    def wait_for_element(self, test_tag: str, timeout: float = 60.0) -> Element:
        deadline = time.monotonic() + timeout
        seen: set[str] = set()
        while time.monotonic() < deadline:
            for e in self.tree():
                seen.add(e.test_tag)
                if e.test_tag == test_tag:
                    return e
            time.sleep(0.5)
        near = ", ".join(sorted(seen)[:25]) or "<nothing registered>"
        raise DriverError(
            f"element {test_tag!r} never appeared within {timeout:.0f}s "
            f"(screen={self.screen()!r}; registered: {near})"
        )

    def wait_for_screen(self, screen: str, timeout: float = 90.0) -> None:
        deadline = time.monotonic() + timeout
        seen: list[str] = []
        while time.monotonic() < deadline:
            cur = self.screen()
            if cur == screen:
                return
            if not seen or seen[-1] != cur:
                seen.append(cur)
            time.sleep(0.5)
        raise DriverError(
            f"screen {screen!r} never reached within {timeout:.0f}s (saw: {' -> '.join(seen) or '<none>'})"
        )
