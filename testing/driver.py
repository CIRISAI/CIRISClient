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

    @classmethod
    def from_json(cls, d: dict[str, Any]) -> "Element":
        return cls(
            test_tag=d.get("testTag") or d.get("test_tag") or "",
            x=int(d.get("x", 0)),
            y=int(d.get("y", 0)),
            width=int(d.get("width", 0)),
            height=int(d.get("height", 0)),
            text=d.get("text"),
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

    def input(self, test_tag: str, text: str, clear_first: bool = True) -> None:
        self._call("POST", "/input", {"testTag": test_tag, "text": text, "clearFirst": clear_first})

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
