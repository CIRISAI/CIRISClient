"""One agent version per with-AI run: the resolver's decisions, without a network."""

from __future__ import annotations

import pytest

from testing.gate import agent_version as av

META = {"version": "2.16.2", "requires_dist": [
    "pydantic<3.0.0,>=2.7.0", "ciris-server==0.5.229", "ciris-client==0.5.228",
    'pytest==9.1.1; extra == "dev"']}


def _pypi(known):
    return lambda v: known.get(v)


def _latest_untouched():
    raise AssertionError("latest was read for a run with an override")


def test_the_latest_release_is_mapped_to_its_pypi_version_and_its_node_pin():
    r = av.resolve(override="", latest=lambda: "v2.16.2-stable\n", pypi_meta=_pypi({"2.16.2": META}))
    assert (r.tag, r.pypi, r.server, r.source) == ("v2.16.2-stable", "2.16.2", "0.5.229", av.LATEST)


@pytest.mark.parametrize("typed", ["2.16.2", "v2.16.2", "v2.16.2-stable", " 2.16.2 "])
def test_the_input_wins_in_any_of_its_spellings_and_never_reads_latest(typed):
    r = av.resolve(override=typed, latest=_latest_untouched, pypi_meta=_pypi({"2.16.2": META}))
    assert (r.pypi, r.source) == ("2.16.2", av.INPUT)


@pytest.mark.parametrize("bad", ["latest", "main", "2.16", "v2.16.2-rc1"])
def test_something_that_is_not_a_release_is_refused_not_guessed(bad):
    with pytest.raises(av.ResolveError, match="is not a release"):
        av.resolve(override=bad, latest=_latest_untouched, pypi_meta=_pypi({}))


def test_a_release_pypi_does_not_carry_fails_here_not_on_three_runners():
    with pytest.raises(av.ResolveError, match="not on PyPI"):
        av.resolve(override="", latest=lambda: "v2.16.3-stable", pypi_meta=_pypi({"2.16.2": META}))


def test_metadata_with_no_single_node_pin_cannot_be_boarded():
    bad = {"version": "2.16.2", "requires_dist": ["ciris-server>=0.5"]}
    with pytest.raises(av.ResolveError, match="pins ciris-server 0 times"):
        av.resolve(override="2.16.2", latest=_latest_untouched, pypi_meta=_pypi({"2.16.2": bad}))


def test_the_cli_writes_every_output_the_workflow_reads(tmp_path, monkeypatch):
    monkeypatch.setattr(av, "gh_latest", lambda: "v2.16.2-stable")
    monkeypatch.setattr(av, "pypi_info", _pypi({"2.16.2": META}))
    out = tmp_path / "gh_output"
    assert av.main(["--github-output", str(out)]) == 0
    got = dict(ln.split("=", 1) for ln in out.read_text().splitlines())
    assert got == {"agent_tag": "v2.16.2-stable", "agent_pypi": "2.16.2",
                   "agent_server": "0.5.229", "agent_source": av.LATEST}
