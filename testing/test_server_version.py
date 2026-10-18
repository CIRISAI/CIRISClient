"""One server version per QA run (CIRISClient#166): the resolver's decisions.

`resolve()` is fed the GitHub reads as values, so every branch below runs with
no network — including the one that matters most, the pin that is NOT a
released tag, which is the live case while VENDORING.md §1 records a branch.
"""

from __future__ import annotations

import pytest

from testing.gate import server_version as sv

PIN = "f2bbb63f5bc4d9ba091590bcf9acd6d1b015a387"
TAGS = [("v0.5.230", "aaaa0230" + "0" * 32), ("v0.5.231", "bbbb0231" + "0" * 32),
        ("rc-7", PIN), ("v0.5.229", "cccc0229" + "0" * 32)]


def _latest_untouched():
    raise AssertionError("latest was consulted for a run that can use the pin")


def test_a_pin_at_a_released_tag_is_that_tag_and_never_asks_for_latest():
    r = sv.resolve(pin_sha="bbbb0231", pin_label="v0.5.231", tags=TAGS,
                   is_release=lambda t: True, latest=_latest_untouched)
    assert (r.version, r.source, r.pypi) == ("v0.5.231", sv.PIN, "0.5.231")
    assert r.warning == ""


def test_a_pin_on_a_branch_commit_falls_back_to_latest_AND_SAYS_SO():
    # The live case: §1 records `fix/client-copy-0.5.232` (f2bbb63f). No
    # release tag points there, so there is no binary; the run uses the latest
    # release and the board carries a warning naming the pin it did not test.
    r = sv.resolve(pin_sha=PIN, pin_label="fix/client-copy-0.5.232", tags=TAGS,
                   is_release=lambda t: t.startswith("v"), latest=lambda: "v0.5.231\n")
    assert (r.version, r.source) == ("v0.5.231", sv.LATEST_FALLBACK)
    assert "f2bbb63f" in r.warning and "fix/client-copy-0.5.232" in r.warning
    assert "v0.5.231" in r.warning and "not a released tag" in r.warning


def test_a_tag_with_no_release_is_not_a_version_a_leg_can_download():
    r = sv.resolve(pin_sha="bbbb0231", pin_label="v0.5.231", tags=TAGS,
                   is_release=lambda t: False, latest=lambda: "v0.5.230")
    assert r.source == sv.LATEST_FALLBACK and r.version == "v0.5.230"
    assert "no release carries a binary" in r.warning


def test_the_dispatch_input_wins_and_is_checked_against_the_releases():
    r = sv.resolve(pin_sha=PIN, pin_label="x", tags=TAGS, is_release=lambda t: t == "v0.5.229",
                   latest=_latest_untouched, override=" v0.5.229 ")
    assert (r.version, r.source, r.pypi) == ("v0.5.229", sv.INPUT, "0.5.229")
    with pytest.raises(sv.ResolveError, match="not a CIRISAI/CIRISServer release"):
        sv.resolve(pin_sha=PIN, pin_label="x", tags=TAGS, is_release=lambda t: False,
                   latest=_latest_untouched, override="v0.5.9999")


@pytest.mark.parametrize("bad", ["0.5.231", "latest", "main", "v0.5"])
def test_an_input_that_is_not_a_release_tag_is_refused_not_guessed(bad):
    with pytest.raises(sv.ResolveError, match="not a release tag"):
        sv.resolve(pin_sha=PIN, pin_label="x", tags=TAGS, is_release=lambda t: True,
                   latest=_latest_untouched, override=bad)


def test_a_latest_that_is_not_a_tag_fails_rather_than_downloads_nothing():
    with pytest.raises(sv.ResolveError, match="not a vX.Y.Z tag"):
        sv.resolve(pin_sha=PIN, pin_label="x", tags=TAGS, is_release=lambda t: True,
                   latest=lambda: "")


def test_the_pin_row_is_the_one_check_vendoring_reads():
    # The same §1 row `--merged-ref CIRISServer` prints: one place, not two.
    import re
    label, sha = sv.pinned()
    row = re.search(r"`CIRISAI/CIRISServer`\s*\|\s*`([^`]+)`\s*\(`([0-9a-f]{7,40})`\)",
                    (sv.REPO / "client" / "VENDORING.md").read_text(encoding="utf-8"))
    assert row and (label, sha) == (row.group(1), row.group(2))


def test_the_cli_writes_every_output_the_workflow_reads(tmp_path, monkeypatch):
    monkeypatch.setattr(sv, "pinned", lambda: ("fix/x", PIN))
    monkeypatch.setattr(sv, "gh_tags", lambda: TAGS)
    monkeypatch.setattr(sv, "gh_is_release", lambda t: t.startswith("v"))
    monkeypatch.setattr(sv, "gh_latest", lambda: "v0.5.231")
    out = tmp_path / "out"
    assert sv.main(["--github-output", str(out)]) == 0
    got = dict(ln.split("=", 1) for ln in out.read_text().splitlines())
    assert got == {"version": "v0.5.231", "pypi": "0.5.231", "source": "latest-fallback",
                   "pin": PIN, "pin_label": "fix/x"}
