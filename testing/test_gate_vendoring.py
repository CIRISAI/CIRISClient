"""The vendored gate must actually run HERE (CIRISClient#31).

WHY THIS FILE EXISTS

Vendoring `testing/gate/platforms.py` I checked it the obvious way — imported
the module, saw no error, and recorded in VENDORED.md that it was "stdlib-only
and imports nothing from CIRISAgent". That was false, and the check was what
made it look true: every dependency on their code is a DEFERRED import inside a
method.

    async def bring_up(self, args) -> int:
        from . import __main__ as web_ui_main      # CIRISAgent's bring-up
        return await web_ui_main.run_android_up(args)

    def _adb(self, *args):
        adb = str(web_ui_main._android_sdk_paths()["adb"])   # and again

A module-level import never touches either, so `import testing.gate.platforms`
passed while four of the five entry points raised `ImportError` on first call —
and they would have raised at the moment a five-platform run tried to start an
emulator, which is the least convenient moment available.

So these tests exercise the vendored surface rather than importing it, and
assert the property VENDORED.md claims instead of trusting the claim.
"""

from __future__ import annotations

import asyncio
import ast
import pathlib

import pytest

GATE = pathlib.Path(__file__).resolve().parent / "gate"

#: Names that exist only in CIRISAgent. A vendored file referring to one is a
#: file that cannot run here, however cleanly it imports.
UPSTREAM_ONLY = ("web_ui_main", "qa_runner", "run_android_up", "run_ios_up",
                 "run_desktop_up", "_android_sdk_paths")


def gate_modules() -> list[pathlib.Path]:
    return sorted(p for p in GATE.glob("*.py") if p.name != "__init__.py")


def test_there_is_something_to_check():
    # A denominator of zero is not a pass: if the glob ever stops matching,
    # every test below would go green having examined nothing.
    assert len(gate_modules()) >= 4


@pytest.mark.parametrize("path", gate_modules(), ids=lambda p: p.name)
def test_no_vendored_module_reaches_back_into_cirisagent(path):
    src = path.read_text(encoding="utf-8")
    # Strip comments/docstrings by parsing: the adaptation notes NAME these
    # symbols on purpose, and a grep would flag its own explanation.
    tree = ast.parse(src)
    code_names = {
        n.id for n in ast.walk(tree) if isinstance(n, ast.Name)
    } | {
        n.attr for n in ast.walk(tree) if isinstance(n, ast.Attribute)
    } | {
        alias.name for n in ast.walk(tree)
        if isinstance(n, (ast.Import, ast.ImportFrom))
        for alias in n.names
    } | {
        n.module or "" for n in ast.walk(tree) if isinstance(n, ast.ImportFrom)
    }
    leaked = sorted(name for name in code_names if name in UPSTREAM_ONLY)
    assert not leaked, (
        f"{path.name} still references CIRISAgent-only names {leaked}. A deferred "
        f"import inside a method passes `import {path.stem}` and fails on first call."
    )


@pytest.mark.parametrize("path", gate_modules(), ids=lambda p: p.name)
def test_no_vendored_module_needs_a_third_party_package(path):
    """CI installs nothing to run these; upstream's httpx would have broken that."""
    tree = ast.parse(path.read_text(encoding="utf-8"))
    third_party = {"httpx", "requests", "aiohttp"}
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for a in node.names:
                assert a.name.split(".")[0] not in third_party, f"{path.name}: {a.name}"
        elif isinstance(node, ast.ImportFrom) and node.module:
            assert node.module.split(".")[0] not in third_party, f"{path.name}: {node.module}"


def test_bring_up_refuses_loudly_rather_than_pretending():
    """A silent no-op bring-up is the worst possible outcome.

    It produces a run against an app that was never started, and reports the
    platform GREEN. Raising is the only honest answer while bring-up lives in
    this repo rather than in the vendored half.
    """
    from testing.gate.platforms import AndroidPlatform, DesktopPlatform, IOSPlatform

    for platform in (
        DesktopPlatform(server_url="http://127.0.0.1:8080"),
        AndroidPlatform(),
        IOSPlatform(),
    ):
        with pytest.raises(NotImplementedError):
            asyncio.run(platform.bring_up(None))


def test_adb_prefers_the_sdk_over_path(monkeypatch, tmp_path):
    """`shutil.which("adb")` finds nothing on a runner, and a quiet miss cost
    them a whole Android gallery tile on their first green run."""
    from testing.gate import platforms

    sdk = tmp_path / "sdk"
    (sdk / "platform-tools").mkdir(parents=True)
    (sdk / "platform-tools" / "adb").write_text("#!/bin/sh\n")
    monkeypatch.setenv("ANDROID_SDK_ROOT", str(sdk))
    assert platforms._resolve_adb() == str(sdk / "platform-tools" / "adb")


def test_adb_falls_back_when_no_sdk_is_configured(monkeypatch):
    from testing.gate import platforms

    monkeypatch.delenv("ANDROID_SDK_ROOT", raising=False)
    monkeypatch.delenv("ANDROID_HOME", raising=False)
    assert platforms._resolve_adb().endswith("adb")
