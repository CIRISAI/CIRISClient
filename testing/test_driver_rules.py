"""The gate's rules, as tests of OUR driver (CIRISClient#31).

CIRISAgent's gate carries these as tests of `desktop_app_helper`. We did not
vendor that module -- `testing/driver.py` already does its job, stdlib-only and
raising on every failure, and two drivers would be two contracts. So the RULES
came across and the tests were rewritten against ours. See
`testing/gate/VENDORED.md`.

Each rule below exists because it already went green while the product was
broken. They run against a real `http.server` on a real socket rather than a
mocked transport, because two of the four defects this driver has met were in
the transport itself -- a body in a second TCP segment, and a response that was
not valid JSON. A mock would have passed both.
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from testing.driver import DriverError, Element, TestAutomationServer


class _Fake(BaseHTTPRequestHandler):
    """A client's automation surface, scripted per test."""

    elements: dict[str, dict] = {}
    posted: list[tuple[str, dict]] = []
    #: What /input does to the element it is given -- the knob each test turns.
    apply_mode = "exact"

    def log_message(self, *a):  # noqa: D102 - silence the default stderr spam
        pass

    def _send(self, obj, status=200):
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        # The real client serves this from the moment the automation server
        # starts — which is BEFORE the UI composes, and is exactly the
        # distinction rule 6 below is about. A fake that 404s here cannot
        # express "server up, app not rendered yet".
        if self.path == "/health":
            return self._send({"status": "ok", "testMode": True})
        if self.path.startswith("/element/"):
            tag = self.path.rsplit("/", 1)[1]
            el = type(self).elements.get(tag)
            return self._send(el) if el else self._send({}, 404)
        if self.path == "/tree":
            return self._send({"elements": list(type(self).elements.values())})
        self._send({}, 404)

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(n) or b"{}")
        type(self).posted.append((self.path, body))
        if self.path == "/input":
            tag, text = body["testTag"], body["text"]
            el = type(self).elements.setdefault(tag, {"testTag": tag})
            mode = type(self).apply_mode
            if mode == "exact":
                el["inputValue"] = text
            elif mode == "wrong":
                el["inputValue"] = "something else"
            elif mode == "never":
                pass  # acknowledged, applied nothing -- the #31 defect
            elif mode == "no_value":
                el.pop("inputValue", None)  # a pre-0.5.200 client
        self._send({"success": True})


@pytest.fixture
def server():
    _Fake.elements, _Fake.posted, _Fake.apply_mode = {}, [], "exact"
    httpd = HTTPServer(("127.0.0.1", 0), _Fake)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield TestAutomationServer(base_url=f"http://127.0.0.1:{httpd.server_port}"), _Fake
    httpd.shutdown()


# ---- rule 1: an input is entered only when the field says so ----------------


def test_input_that_applies_is_accepted(server):
    drv, fake = server
    drv.input("input_username", "qaadmin")
    assert fake.elements["input_username"]["inputValue"] == "qaadmin"


def test_input_acknowledged_but_never_applied_raises(server):
    # THE #31 DEFECT. success:true while the field holds nothing -- the shape
    # that let a green setup step enter no password and the wizard refuse to
    # advance.
    drv, fake = server
    fake.elements["input_confirm_x"] = {"testTag": "input_confirm_x", "inputValue": ""}
    fake.apply_mode = "never"
    with pytest.raises(DriverError, match="did not apply"):
        drv.input("input_confirm_x", "hunter2")


def test_input_applied_as_something_else_raises(server):
    drv, fake = server
    fake.apply_mode = "wrong"
    fake.elements["input_username"] = {"testTag": "input_username", "inputValue": ""}
    with pytest.raises(DriverError, match="rather than"):
        drv.input("input_username", "qaadmin")


def test_a_field_exposing_no_value_is_unverifiable_not_failed(server):
    # Every client before 0.5.200. Failing here would make this driver unusable
    # against exactly the versions worth reproducing a downstream report against.
    drv, fake = server
    fake.apply_mode = "no_value"
    drv.input("input_username", "qaadmin")


def test_a_masked_field_is_not_read_back(server):
    # It cannot be read back, so verifying it would fail every correct run.
    drv, fake = server
    fake.apply_mode = "never"
    drv.input("input_password", "hunter2")
    assert ("/input", {"testTag": "input_password", "text": "hunter2",
                       "clearFirst": True}) in fake.posted


def test_verification_can_be_declined_explicitly(server):
    drv, fake = server
    fake.apply_mode = "never"
    drv.input("input_username", "x", verify=False)


# ---- rule 2: presence is not drivability ------------------------------------


def test_a_stale_entry_is_identifiable_as_a_ghost():
    # CIRISClient#30: an element that outlived its composable looks exactly like
    # a live control in /tree. canClick/canInput are computed live, so both
    # False is the signature -- one poll instead of a screenshot and a person.
    ghost = Element.from_json(
        {"testTag": "input_username", "canClick": False, "canInput": False}
    )
    assert ghost.is_ghost


def test_a_live_control_is_not_a_ghost():
    live = Element.from_json({"testTag": "input_username", "canInput": True})
    assert not live.is_ghost


def test_an_older_client_that_cannot_say_is_not_a_ghost():
    # None means "this client does not serve drivability", not "not drivable".
    # Reading absence as a negative would fail every pre-0.5.199 run -- the same
    # distinct-zeroes mistake the client itself made in #21 and #34.
    old = Element.from_json({"testTag": "input_username"})
    assert old.can_input is None
    assert not old.is_ghost


# ---- rule 6: the server answering is not the app having a UI ----------------
#
# `wait_for_server` proves the automation SERVER responds. On desktop that is
# true about two seconds before the app has composed anything: Main.kt starts
# the server before `application { Window { … } }`. Measured on the 0.5.217 jar,
# server at 1.0s and a nine-element tree at 3.1s.
#
# A gate that walks in that window asserts nothing. "Everything tagged is
# drivable" and "no ghosts" are both trivially TRUE of an empty tree, so three
# of the five-platform walk's steps passed vacuously and only the screenshot —
# the one assertion that needs a window — failed. That is how the gate produced
# five greens and one red against an app with no interface.


def test_wait_for_ui_returns_zero_when_nothing_ever_composes(server):
    drv, fake = server
    fake.elements = {}
    # Short timeout: the point is that it RETURNS the count rather than raising,
    # so the caller can report "started but never rendered" as the real failure
    # it is instead of losing it inside an exception.
    assert drv.wait_for_ui(timeout=1.0) == 0


def test_wait_for_ui_returns_the_count_once_the_tree_fills(server):
    drv, fake = server
    fake.elements = {
        "btn_login_submit": {"testTag": "btn_login_submit"},
        "input_username": {"testTag": "input_username"},
    }
    assert drv.wait_for_ui(timeout=5.0) == 2


def test_wait_for_ui_does_not_confuse_a_live_server_for_a_composed_app(server):
    drv, fake = server
    # The server answers /tree perfectly well — with nothing in it. This is the
    # exact state the five-platform gate walked in, and the distinction this
    # method exists to make.
    fake.elements = {}
    drv.wait_for_server(timeout=5.0)  # the server IS up
    assert drv.wait_for_ui(timeout=1.0) == 0, (
        "a reachable automation server was mistaken for a composed app"
    )


def test_a_body_that_says_it_failed_raises_even_on_http_200():
    """iOS answered a failed /input with 200 {"success": false}; the walk then
    'typed' into fields that took nothing (CSD flows on the matrix, #97)."""
    import http.server, threading, json as _json
    from testing.driver import TestAutomationServer, DriverError

    class H(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            n = int(self.headers.get("Content-Length", 0)); self.rfile.read(n)
            body = _json.dumps({"success": False, "error": "no text sink is listening for input_x"}).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
        def log_message(self, *a): pass

    srv = http.server.HTTPServer(("127.0.0.1", 0), H)
    t = threading.Thread(target=srv.serve_forever, daemon=True); t.start()
    try:
        drv = TestAutomationServer(base_url=f"http://127.0.0.1:{srv.server_address[1]}")
        try:
            drv.input("input_x", "hello", verify=False)
        except DriverError as e:
            assert "no text sink" in str(e)
        else:
            raise AssertionError("a success:false body was accepted as typed")
    finally:
        srv.shutdown()


class _El:
    def __init__(self, tag):
        self.test_tag = tag


def test_wait_for_element_rides_out_a_failing_tree_read(monkeypatch):
    """Android, run 36910751218: one /tree read failed mid-transition and the
    wait returned at once, though the tag composed a beat later."""
    import testing.driver as d
    drv = d.TestAutomationServer.__new__(d.TestAutomationServer)
    calls = {"n": 0}

    def tree():
        calls["n"] += 1
        if calls["n"] == 1:
            raise d.DriverError("GET /tree -> HTTP 500: composing")
        return [_El("input_peer_search")]

    drv.tree = tree
    drv.screen = lambda: "NetworkContent"
    monkeypatch.setattr(d.time, "sleep", lambda s: None)
    assert drv.wait_for_element("input_peer_search", timeout=5).test_tag == "input_peer_search"
    assert calls["n"] == 2


def test_wait_for_element_names_the_last_tree_error_at_the_deadline(monkeypatch):
    import pytest
    import testing.driver as d
    drv = d.TestAutomationServer.__new__(d.TestAutomationServer)
    clock = {"t": 0.0}

    def tree():
        raise d.DriverError("GET /tree -> HTTP 500: composing")

    monkeypatch.setattr(d.time, "monotonic", lambda: clock["t"])
    monkeypatch.setattr(d.time, "sleep", lambda s: clock.__setitem__("t", clock["t"] + s))
    drv.tree = tree
    drv.screen = lambda: "NetworkContent"
    with pytest.raises(d.DriverError) as e:
        drv.wait_for_element("input_peer_search", timeout=2)
    assert "last /tree error" in str(e.value) and "HTTP 500" in str(e.value)
