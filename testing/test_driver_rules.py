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
