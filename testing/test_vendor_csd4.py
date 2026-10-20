"""`packaging/vendor_csd4.py --from-release` against a LOCAL fake release
(no network, no server code): the csd4-* assets are refused unless every one is
in SHA256SUMS and hashes to it; cosign is verified when present and its
absence is SAID, never skipped silently.

The fake release is built from the vendored catalogue itself, with the trace's
generator set to the server's `python -m harness.native trace` string — which
is exactly what a v0.5.232 release asset differs by.
"""
from __future__ import annotations

import hashlib
import json
import pathlib
import stat
import sys

import pytest

pytest.importorskip("yaml")

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "packaging"))

import check_csd4  # noqa: E402
import vendor_csd4  # noqa: E402

SERVER_GENERATOR = "python -m harness.native trace (harness.native.model.traces)"


def _sums(rel: pathlib.Path) -> None:
    lines = [f"{hashlib.sha256((rel / a).read_bytes()).hexdigest()}  {a}" for a in vendor_csd4.ASSETS]
    (rel / "SHA256SUMS").write_text("\n".join(lines) + "\n", encoding="utf-8")


@pytest.fixture
def release(tmp_path: pathlib.Path) -> pathlib.Path:
    rel = tmp_path / "release"
    rel.mkdir()
    for f in vendor_csd4.FILES:
        data = (check_csd4.CATALOGUE_DIR / f).read_bytes()
        if f == "traces.json":
            t = json.loads(data)
            t["_meta"]["generator"] = SERVER_GENERATOR
            data = json.dumps(t, indent=1, sort_keys=True).encode()
        (rel / (vendor_csd4.ASSET_PREFIX + f)).write_bytes(data)
    _sums(rel)
    return rel


@pytest.fixture
def no_cosign(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(vendor_csd4.shutil, "which", lambda name: None)


def test_a_good_release_vendors_green_and_says_cosign_was_not_run(
        release: pathlib.Path, tmp_path: pathlib.Path, no_cosign: None) -> None:
    out = tmp_path / "out"
    status = vendor_csd4.from_release("v0.5.232", out, release_dir=release)
    assert status.startswith("NOT verified: `cosign` is not on PATH")
    for f in vendor_csd4.FILES:
        assert (out / f).read_bytes() == (release / (vendor_csd4.ASSET_PREFIX + f)).read_bytes()
    pin = (out / "PIN.md").read_text(encoding="utf-8")
    assert "`v0.5.232`" in pin and "NOT verified: `cosign` is not on PATH" in pin
    cat = check_csd4.load_catalogue(out)  # the server's generator string is accepted
    assert cat["version"] == 3


def test_a_tampered_asset_is_refused_and_nothing_is_written(
        release: pathlib.Path, tmp_path: pathlib.Path, no_cosign: None) -> None:
    p = release / "csd4-requirements.yaml"
    p.write_text(p.read_text(encoding="utf-8") + "\n# edited after SHA256SUMS\n", encoding="utf-8")
    out = tmp_path / "out"
    with pytest.raises(vendor_csd4.ReleaseError, match=r"csd4-requirements.yaml: sha256 .* but SHA256SUMS says"):
        vendor_csd4.from_release("v0.5.232", out, release_dir=release)
    assert not out.exists()


def test_an_asset_missing_from_sha256sums_is_refused(release: pathlib.Path, tmp_path: pathlib.Path,
                                                     no_cosign: None) -> None:
    sums = release / "SHA256SUMS"
    sums.write_text("".join(l + "\n" for l in sums.read_text().splitlines() if "csd4-traces.json" not in l))
    with pytest.raises(vendor_csd4.ReleaseError, match="SHA256SUMS has no entry for csd4-traces.json"):
        vendor_csd4.from_release("v0.5.232", tmp_path / "out", release_dir=release)


def test_an_empty_sha256sums_is_refused_loudly(release: pathlib.Path, tmp_path: pathlib.Path,
                                               no_cosign: None) -> None:
    (release / "SHA256SUMS").write_text("\n")
    with pytest.raises(vendor_csd4.ReleaseError, match="parsed ZERO"):
        vendor_csd4.from_release("v0.5.232", tmp_path / "out", release_dir=release)


def test_a_release_whose_trace_disagrees_with_its_vocabulary_is_refused(
        release: pathlib.Path, tmp_path: pathlib.Path, no_cosign: None) -> None:
    p = release / "csd4-traces.json"
    t = json.loads(p.read_bytes())
    t["_meta"]["catalogue_version"] = 2
    p.write_text(json.dumps(t, indent=1, sort_keys=True))
    _sums(release)  # a CONSISTENT SHA256SUMS: the content check must catch it
    with pytest.raises(vendor_csd4.ReleaseError, match="traces.json is catalogue version 2"):
        vendor_csd4.from_release("v0.5.232", tmp_path / "out", release_dir=release)


def _fake_cosign(tmp_path: pathlib.Path, rc: int) -> str:
    exe = tmp_path / "bin" / "cosign"
    exe.parent.mkdir(exist_ok=True)
    exe.write_text(f"#!/bin/sh\necho 'fake cosign' >&2\nexit {rc}\n")
    exe.chmod(exe.stat().st_mode | stat.S_IXUSR)
    return str(exe)


def _signed(release: pathlib.Path) -> None:
    for f in (*vendor_csd4.ASSETS, "SHA256SUMS"):
        (release / f"{f}.sig").write_text("sig")
        (release / f"{f}.pem").write_text("pem")


def test_cosign_present_and_refusing_refuses(release: pathlib.Path, tmp_path: pathlib.Path,
                                             monkeypatch: pytest.MonkeyPatch) -> None:
    _signed(release)
    exe = _fake_cosign(tmp_path, 1)
    monkeypatch.setattr(vendor_csd4.shutil, "which", lambda name: exe)
    with pytest.raises(vendor_csd4.ReleaseError, match="cosign verify-blob refused it"):
        vendor_csd4.from_release("v0.5.232", tmp_path / "out", release_dir=release)


def test_cosign_present_and_no_signature_refuses(release: pathlib.Path, tmp_path: pathlib.Path,
                                                 monkeypatch: pytest.MonkeyPatch) -> None:
    exe = _fake_cosign(tmp_path, 0)
    monkeypatch.setattr(vendor_csd4.shutil, "which", lambda name: exe)
    with pytest.raises(vendor_csd4.ReleaseError, match=r"no csd4-requirements.yaml.sig"):
        vendor_csd4.from_release("v0.5.232", tmp_path / "out", release_dir=release)


def test_cosign_present_and_accepting_says_verified(release: pathlib.Path, tmp_path: pathlib.Path,
                                                    monkeypatch: pytest.MonkeyPatch) -> None:
    _signed(release)
    exe = _fake_cosign(tmp_path, 0)
    monkeypatch.setattr(vendor_csd4.shutil, "which", lambda name: exe)
    out = tmp_path / "out"
    assert vendor_csd4.from_release("v0.5.232", out, release_dir=release).startswith("verified: cosign verify-blob")
    assert "verified: cosign verify-blob" in (out / "PIN.md").read_text(encoding="utf-8")


def test_download_refuses_a_non_empty_directory(tmp_path: pathlib.Path) -> None:
    (tmp_path / "stray").write_text("x")
    with pytest.raises(vendor_csd4.ReleaseError, match="not empty"):
        vendor_csd4.download_release("v0.5.232", tmp_path)


def test_from_release_runs_no_server_code(release: pathlib.Path, tmp_path: pathlib.Path,
                                          monkeypatch: pytest.MonkeyPatch, no_cosign: None) -> None:
    calls: list = []
    real = vendor_csd4.subprocess.run
    monkeypatch.setattr(vendor_csd4.subprocess, "run", lambda *a, **k: calls.append(a) or real(*a, **k))
    vendor_csd4.from_release("v0.5.232", tmp_path / "out", release_dir=release)
    assert calls == [], calls
