"""The two-node fixture's command building and bookkeeping — no node, no network.

What is pinned here is what a leg cannot afford to find out at 2am: that the
peer never lands on the leg's ports or home, that the claim targets the peer
and not the leg's node, that a `.exe` is found on Windows, that a value the
seeding did not produce is not handed to a flow, and that `down` really kills
and really deletes."""

from __future__ import annotations

import json
import subprocess
import sys
import time
from pathlib import Path

import pytest

from testing.gate import two_node as tn


def _spec(tmp_path: Path, **kw) -> tn.PeerSpec:
    return tn.PeerSpec(binary=tmp_path / "ciris-server", home=tmp_path / "home",
                       key_id="ciris-gate-peer-abc", **kw)


def test_the_peer_defaults_to_its_own_ports_never_the_legs():
    """4242/4243 are the leg's node (and the iOS app's embedded one); 8080 the agent."""
    s = tn.PeerSpec(binary=Path("b"), home=Path("h"), key_id="k")
    assert (s.port, s.port + 1) == (5242, 5243)
    assert not {4242, 4243, 8080} & {s.port, s.port + 1}
    assert s.read_url == "http://127.0.0.1:5243"


def test_config_sets_the_listen_addr_then_the_dial_set_offline_on_the_peers_home(tmp_path):
    s = _spec(tmp_path, bootstrap_peers=["127.0.0.1:4242"])
    listen, peers = s.config_commands()
    assert listen[:4] == [str(s.binary), "config", "set", "net.listen_addr"]
    assert json.loads(listen[4]) == "127.0.0.1:5242", "the value is JSON: a quoted string"
    assert json.loads(peers[4]) == ["127.0.0.1:4242"]
    for cmd in (listen, peers):
        assert cmd[cmd.index("--home") + 1] == str(tmp_path / "home")
        assert cmd[cmd.index("--key-id") + 1] == "ciris-gate-peer-abc"


def test_no_dial_set_means_no_second_config_call(tmp_path):
    assert len(_spec(tmp_path).config_commands()) == 1


def test_serve_takes_home_and_key_id_as_flags_never_env(tmp_path):
    """Server 0.5 reads no environment; CIRIS_HOME is the CLIENT's variable."""
    s = _spec(tmp_path)
    assert s.serve_command() == [str(s.binary), "--home", str(s.home), "--key-id", s.key_id]


def test_identity_create_carries_no_label(tmp_path):
    """`claim` re-opens the signer under `<key-id>-user`; a label would mint a
    keyset the claim cannot find (the server harness's note)."""
    cmd = _spec(tmp_path).identity_command()
    assert cmd[1:5] == ["identity", "create", "--backend", "software"]
    assert "--label" not in cmd


def test_the_claim_targets_the_peers_read_api_at_self_scope(tmp_path):
    s = _spec(tmp_path, port=6242)
    cmd = s.claim_command("CIRIS-V1-CODE", "123456")
    assert cmd[cmd.index("--target-url") + 1] == "http://127.0.0.1:6243"
    assert cmd[cmd.index("--node-code") + 1] == "CIRIS-V1-CODE"
    assert cmd[cmd.index("--claim-pin") + 1] == "123456"
    assert cmd[cmd.index("--cohort-scope") + 1] == "self"


@pytest.mark.parametrize("url,want", [
    ("http://127.0.0.1:4243", "127.0.0.1:4242"),
    ("http://127.0.0.1:5143/", "127.0.0.1:5142"),
    ("http://localhost:4243", "127.0.0.1:4242"),  # bootstrap_peers parses a SocketAddr
])
def test_the_dial_target_is_one_port_below_the_read_api(url, want):
    assert tn.transport_of(url) == want


def test_a_url_without_a_port_is_refused_not_guessed():
    with pytest.raises(ValueError):
        tn.transport_of("http://127.0.0.1")


def test_the_windows_binary_is_found_by_its_exe(tmp_path):
    (tmp_path / "ciris-server.exe").write_bytes(b"")
    assert tn.resolve_binary(tmp_path / "ciris-server") == tmp_path / "ciris-server.exe"


def test_a_missing_binary_is_loud(tmp_path):
    with pytest.raises(tn.FixtureUnavailable, match="no ciris-server"):
        tn.resolve_binary(tmp_path / "ciris-server")


def test_key_ids_are_unique_per_run():
    assert len({tn.unique_key_id() for _ in range(50)}) == 50


def test_the_claims_session_and_owner_are_read_after_its_log_lines():
    out = "INFO something\nINFO more\n" + json.dumps(
        {"access_token": "tok", "identity_key_id": "peer-user-x", "other": 1}, indent=2)
    assert tn.parse_claim_output(out) == ("tok", "peer-user-x")


def test_a_claim_with_no_session_is_loud():
    with pytest.raises(tn.FixtureUnavailable, match="did not yield a session"):
        tn.parse_claim_output("ERROR claim refused: bad pin\n")


def test_values_hand_flows_only_what_the_seeding_produced():
    v = tn.FixtureValues(peer_key_id="p-user", peer_node_key_id="p-node", room_id="chat:pair:x",
                         message_attestation_id="att-1", message_text="hi")
    got = v.as_vars()
    assert got["PEER_KEY_ID"] == "p-user" and got["ROOM_ID"] == "chat:pair:x"
    assert "PEER_CONTACT_CODE" not in got, "empty is absent, so the flow says why"
    # Sent but not arrived: a local-only row must not look delivered.
    assert "MESSAGE_ATTESTATION_ID" not in got and "MESSAGE_TEXT" not in got
    v.message_arrived = True
    assert v.as_vars()["MESSAGE_ATTESTATION_ID"] == "att-1"


def test_add_contact_falls_back_to_the_node_and_says_so(monkeypatch):
    """The owner key never crossed: the contact is the peer NODE, recorded."""
    calls = []

    def fake_http(method, url, token=None, body=None, timeout=0):
        calls.append((method, url, body))
        if url.endswith("/v1/federation/peers/peer-user"):
            return 404, {"error": "peer not found"}
        if url.endswith("/v1/contacts") and body == {"key_id": "peer-user"}:
            return 404, {"reason_id": "contacts.unknown_fed_id"}
        if url.endswith("/v1/contacts"):
            return 200, {"key_id": body["key_id"], "reachable_nodes": 0}
        return 500, {}

    monkeypatch.setattr(tn, "http", fake_http)
    # The node contact answers reachable_nodes=0 too; the bounded reachability
    # wait below runs on a fake clock, so this stays a millisecond test.
    monkeypatch.setattr(tn, "time", _Clock())
    host = tn.Party("local", "http://h", "t")
    guest = tn.Party("peer", "http://g", "t", owner_key_id="peer-user", node_key_id="peer-node")
    notes: list = []
    key, via = tn.add_contact(host, guest, wait=0, notes=notes, log=lambda m: None)
    assert (key, via) == ("peer-node", "node")
    assert any("via owner refused" in n for n in notes)


class _Clock:
    def __init__(self):
        self.t = 0.0

    def monotonic(self):
        return self.t

    def sleep(self, s):
        self.t += s


def _contacts_http(answers, posts):
    def fake_http(method, url, token=None, body=None, timeout=0):
        if url.endswith("/v1/federation/peers/peer-user"):
            return 200, {"key_id": "peer-user"}
        if url.endswith("/v1/contacts"):
            posts.append(body)
            n = answers.pop(0) if answers else 1
            return 200, {"key_id": body["key_id"], "reachable_nodes": n, "consent_prefixes": ["chat:"]}
        return 500, {}
    return fake_http


def test_add_contact_waits_for_the_binding_to_land_before_calling_it_reachable(monkeypatch):
    """CIRISServer#699: a contact added while `reachable_nodes=0` keys a room
    whose bodies read `not_granted` for good. The owner KEY crossing (what the
    fixture waited for) is earlier than the owner->node BINDING (what
    `reachable_nodes` counts); TOPOLOGY §2.5 defines `reachable(A, q)` by the
    POST itself, so the fixture re-asks until it is >= 1, bounded, and says
    how long it waited."""
    posts, notes, clock = [], [], _Clock()
    monkeypatch.setattr(tn, "http", _contacts_http([0, 0, 1], posts))
    monkeypatch.setattr(tn, "time", clock)
    host = tn.Party("local", "http://h", "t")
    guest = tn.Party("peer", "http://g", "t", owner_key_id="peer-user", node_key_id="peer-node")
    key, via = tn.add_contact(host, guest, wait=0, notes=notes, log=lambda m: None,
                              reachable_wait=60)
    assert (key, via) == ("peer-user", "owner")
    assert len(posts) == 3, "re-asked until the binding was held"
    assert any("reachable_nodes" in n and "TOPOLOGY" in n and "waited" in n for n in notes), notes


def test_add_contact_says_when_the_binding_never_lands(monkeypatch):
    posts, notes, clock = [], [], _Clock()
    monkeypatch.setattr(tn, "http", _contacts_http([0] * 50, posts))
    monkeypatch.setattr(tn, "time", clock)
    host = tn.Party("local", "http://h", "t")
    guest = tn.Party("peer", "http://g", "t", owner_key_id="peer-user", node_key_id="peer-node")
    key, via = tn.add_contact(host, guest, wait=0, notes=notes, log=lambda m: None,
                              reachable_wait=20)
    assert (key, via) == ("peer-user", "owner"), "unreachable is not a refusal"
    assert clock.t <= 30 and 2 <= len(posts) <= 8, "bounded"
    assert any("reachable_nodes=0" in n and "#699" in n for n in notes), notes


def test_down_kills_the_peer_by_its_pidfile_and_deletes_its_home(tmp_path):
    """The `if: always()` path: another process, only the work dir to go on."""
    home = tmp_path / "home"
    (home / "identity").mkdir(parents=True)
    proc = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(120)"],
                            start_new_session=True)
    try:
        (tmp_path / "peer.pid").write_text(f"{proc.pid}\n{home}\n", encoding="utf-8")
        said = tn.down(tmp_path)
        assert str(proc.pid) in said
        deadline = time.monotonic() + 10
        while proc.poll() is None and time.monotonic() < deadline:
            time.sleep(0.1)
        assert proc.poll() is not None, "the peer is still running"
        assert not home.exists(), "the peer's home was left behind"
        assert not (tmp_path / "peer.pid").exists()
    finally:
        if proc.poll() is None:
            proc.kill()


def test_down_with_nothing_running_is_a_no_op(tmp_path):
    assert "nothing" in tn.down(tmp_path)


def test_the_client_claimed_owner_is_read_off_the_announce_bundle(monkeypatch):
    """The wizard's user is `qaadmin`, not `root:<fed-ID>`; the announce bundle
    names the owner key whoever claimed."""
    monkeypatch.setattr(tn, "http", lambda *a, **k: (200, {"username": "qaadmin"}))
    p = tn.Party("local", "http://h", "t", announce={"bundle": [
        {"role": "node_key", "key_id": "n"}, {"role": "owner_key", "key_id": "o-user"}]})
    tn.owner_of(p)
    assert p.owner_key_id == "o-user"


def test_a_console_root_owner_is_read_off_its_username(monkeypatch):
    monkeypatch.setattr(tn, "http", lambda *a, **k: (200, {"username": "root:o-user-2"}))
    p = tn.Party("local", "http://h", "t")
    tn.owner_of(p)
    assert p.owner_key_id == "o-user-2"


def test_standalone_up_leaves_the_peer_running(monkeypatch, tmp_path):
    """`python -m testing.gate.two_node up` must not kill the peer on exit (Codex, #130)."""
    import atexit
    from testing.gate import two_node

    stopped = []

    class FakeNode:
        def __init__(self, spec, work): pass
        def start(self): pass
        def claim(self): return object()
        def stop(self, keep_home=False): stopped.append(True)

    registered = []
    monkeypatch.setattr(two_node, "PeerNode", FakeNode)
    monkeypatch.setattr(two_node, "resolve_binary", lambda b: b)
    monkeypatch.setattr(two_node, "login", lambda *a, **k: "tok")
    monkeypatch.setattr(two_node, "seed", lambda *a, **k: two_node.FixtureValues(peer_key_id="p"))
    monkeypatch.setattr(atexit, "register", lambda f: registered.append(f))
    monkeypatch.setattr(atexit, "unregister", lambda f: registered.remove(f))
    rc = two_node.main(["up", "--binary", str(tmp_path / "ciris-server"), "--work", str(tmp_path)])
    assert rc == 0
    assert registered == [], "the exit cleanup is still armed after a standalone up"
    for f in registered: f()
    assert stopped == []
