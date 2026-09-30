"""The gate prints UTF-8 whatever code page the host opened its console with.

Windows, run 36600766576 (2026-09-29): the two-node fixture printed its note
about the owner→node binding wait to a cp1252 stdout, `→` is not in cp1252,
and `print` raised `UnicodeEncodeError` inside `up()`. The runner reported the
fixture as one that could not be stood up and three flows never ran — over a
print. `testing/gate/console.py` says why the runner's own `—` survived the
same stream.

Three pins: the offending function on a cp1252 console (red without the
reconfigure, green with it); every gate CLI reconfigures on entry; every
text-mode file under `testing/gate/` names its encoding, so a note written to
a file is not the next code-page crash.
"""

from __future__ import annotations

import ast
import io
import pathlib
import re
import sys

import pytest

from testing.gate import console
from testing.gate import two_node as tn
from testing.test_two_node import _Clock, _contacts_http

GATE = pathlib.Path(__file__).resolve().parent / "gate"

#: The gate's command lines: what the matrix legs invoke, and what a developer
#: runs by hand. Each must put the console in UTF-8 before it prints anything.
CLIS = ("run_platform.py", "run_flows.py", "two_node.py", "session_fixture.py")


def _cp1252_console(monkeypatch) -> io.TextIOWrapper:
    """A stdout opened the way the Windows runner opens it."""
    stream = io.TextIOWrapper(io.BytesIO(), encoding="cp1252", errors="strict")
    monkeypatch.setattr(sys, "stdout", stream)
    return stream


def _binding_wait(monkeypatch):
    """`add_contact` on the path that prints the `→` note: reachable after two asks."""
    posts: list = []
    monkeypatch.setattr(tn, "http", _contacts_http([0, 1], posts))
    monkeypatch.setattr(tn, "time", _Clock())
    host = tn.Party("local", "http://h", "t")
    guest = tn.Party("peer", "http://g", "t", owner_key_id="peer-user", node_key_id="peer-node")
    return lambda: tn.add_contact(host, guest, wait=0, notes=[], reachable_wait=60)


def test_the_binding_note_is_what_a_cp1252_console_cannot_print(monkeypatch):
    """The red half: the crash the Windows leg saw, reproduced without Windows."""
    _cp1252_console(monkeypatch)
    with pytest.raises(UnicodeEncodeError):
        _binding_wait(monkeypatch)()


def test_the_gate_console_prints_the_note_whatever_the_code_page(monkeypatch):
    stream = _cp1252_console(monkeypatch)
    console.utf8_console()
    key, via = _binding_wait(monkeypatch)()
    assert (key, via) == ("peer-user", "owner")
    sys.stdout.flush()
    out = stream.buffer.getvalue().decode("utf-8")
    assert "owner→node binding" in out, out


def test_a_console_that_cannot_be_reconfigured_is_left_alone(monkeypatch):
    """A StringIO (a test's capture) has no `reconfigure`; a leg must not fail there."""
    monkeypatch.setattr(sys, "stdout", io.StringIO())
    console.utf8_console()
    print("still prints →")
    assert "→" in sys.stdout.getvalue()


@pytest.mark.parametrize("cli", CLIS)
def test_every_gate_cli_puts_its_console_in_utf8_on_entry(cli):
    src = (GATE / cli).read_text(encoding="utf-8")
    m = re.search(r"^def main\([^)]*\)[^:]*:\n((?:    .*\n|\n)+?)", src, re.M)
    assert m, f"{cli}: no main()"
    first_lines = [ln.strip() for ln in m.group(1).splitlines() if ln.strip() and not ln.strip().startswith(('"""', "#"))]
    assert first_lines and first_lines[0] == "utf8_console()", (
        f"{cli}: main() must call utf8_console() before anything prints; got {first_lines[:2]}"
    )


def _text_opens_without_encoding(path: pathlib.Path) -> list[str]:
    """`open`/`read_text`/`write_text` calls in text mode with no `encoding=`."""
    out = []
    tree = ast.parse(path.read_text(encoding="utf-8"))
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        func = node.func
        name = func.id if isinstance(func, ast.Name) else (func.attr if isinstance(func, ast.Attribute) else "")
        if name not in ("open", "read_text", "write_text"):
            continue
        kws = {k.arg: k.value for k in node.keywords}
        if "encoding" in kws:
            continue
        # A positional encoding: Path.read_text("utf-8", "replace").
        positional = 1 if name == "read_text" else 2 if name == "write_text" else None
        if positional is not None and len(node.args) >= positional:
            continue
        if name == "open":
            owner = func.value if isinstance(func, ast.Attribute) else None
            # Other modules' `open` (tarfile.open, urllib's urlopen is not named open).
            if isinstance(owner, ast.Name) and owner.id in ("tarfile", "zipfile", "gzip"):
                continue
            mode_index = 1 if isinstance(func, ast.Name) else 0
            mode = kws.get("mode")
            if mode is None and len(node.args) > mode_index:
                mode = node.args[mode_index]
            if isinstance(mode, ast.Constant) and "b" in str(mode.value):
                continue
        out.append(f"{path.name}:{node.lineno} {name}(...) names no encoding")
    return out


def test_every_text_mode_file_under_the_gate_names_its_encoding():
    offenders = [line for p in sorted(GATE.glob("*.py")) for line in _text_opens_without_encoding(p)]
    assert not offenders, "\n".join(offenders)
