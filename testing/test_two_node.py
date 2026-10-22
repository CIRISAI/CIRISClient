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


ROW = {"record": {"key_id": "b-node", "transit_kex_pair": "PAIR"}, "signature": "S"}


class _PeeringNode:
    """A fake pair of nodes for `peer(a, b)`: b's self-transport-destination
    answers `routes` in turn (status codes; 200 carries ROW), a's peering
    answers `transit` as `peer_transit_route`."""

    def __init__(self, routes, transit="admitted"):
        self.routes, self.transit = list(routes), transit
        self.gets, self.peering = 0, None

    def __call__(self, method, url, token=None, body=None, timeout=0):
        if url == "http://b" + tn.TRANSPORT_ROUTE:
            self.gets += 1
            status = self.routes.pop(0) if len(self.routes) > 1 else self.routes[0]
            return status, (ROW if status == 200 else ({} if status == 404 else ""))
        if url == "http://a/v1/federation/peering":
            self.peering = body
            out = {"peer_key_id": body["peer_key_id"], "freshly_emitted": True}
            if self.transit is not None:
                out["peer_transit_route"] = self.transit
            return 200, out
        return 500, {}


def _pair():
    a = tn.Party("local", "http://a", "t", node_key_id="a-node")
    b = tn.Party("peer", "http://b", "t", node_key_id="b-node", record={"record": {"key_id": "b-node"}})
    return a, b


def test_peering_waits_out_a_204_then_hands_over_the_transport_row(monkeypatch):
    """CIRISServer#808: on 0.5.232 two directly peered nodes sealed no link
    (`link_seal_refused_no_pair`) because peering carried no transit pair. The
    row is fetched (204 = not published yet, retried) and sent verbatim."""
    clock, node = _Clock(), _PeeringNode([204, 204, 200])
    monkeypatch.setattr(tn, "http", node)
    monkeypatch.setattr(tn, "time", clock)
    a, b = _pair()
    notes: list = []
    tn.peer(a, b, log=lambda m: None, notes=notes)
    assert node.gets == 3
    assert node.peering["peer_transport_destination"] == ROW
    assert node.peering["peer_key_record"] == b.record and node.peering["peer_key_id"] == "b-node"
    assert notes == []


def test_a_204_that_never_ends_is_a_named_unavailable(monkeypatch):
    clock = _Clock()
    monkeypatch.setattr(tn, "http", _PeeringNode([204]))
    monkeypatch.setattr(tn, "time", clock)
    a, b = _pair()
    with pytest.raises(tn.FixtureUnavailable, match=r"still answered 204.*#808"):
        tn.peer(a, b, log=lambda m: None, route_wait=10)
    assert clock.t <= 12, "bounded"


def test_an_older_server_without_the_route_is_peered_as_before_with_a_note(monkeypatch):
    node = _PeeringNode([404], transit=None)
    monkeypatch.setattr(tn, "http", node)
    monkeypatch.setattr(tn, "time", _Clock())
    a, b = _pair()
    notes: list = []
    tn.peer(a, b, log=lambda m: None, notes=notes)
    assert "peer_transport_destination" not in node.peering
    assert set(node.peering) == {"peer_key_id", "peer_key_record", "attestation_prefixes"}
    assert any("#808" in n and "link_seal_refused_no_pair" in n for n in notes), notes


def test_an_admitted_route_peers_cleanly(monkeypatch):
    node = _PeeringNode([200], transit="admitted")
    monkeypatch.setattr(tn, "http", node)
    monkeypatch.setattr(tn, "time", _Clock())
    a, b = _pair()
    said: list = []
    tn.peer(a, b, log=said.append, notes=[])
    assert any("transit_route=admitted" in m for m in said), said


@pytest.mark.parametrize("transit", ["refused: the row is not signed by b-node", "absent", None])
def test_a_route_not_admitted_when_one_was_sent_is_a_named_failure(monkeypatch, transit):
    """`refused: …`, `absent` (the row was dropped), or no answer at all (a
    server that ignored the field) — each would leave a fixture that seals no
    link and exchanges nothing, so each fails by name."""
    monkeypatch.setattr(tn, "http", _PeeringNode([200], transit=transit))
    monkeypatch.setattr(tn, "time", _Clock())
    a, b = _pair()
    with pytest.raises(tn.FixtureUnavailable, match=r"peer_transit_route=.*#808"):
        tn.peer(a, b, log=lambda m: None, notes=[])


class _PendingNode:
    """A fake leg's node on ciris-server 0.5.232: the owner key has not crossed
    (404 `contacts.unknown_fed_id`); the contact CODE answers 202
    `contacts.awaiting_key_body` — the key body is being pulled from the mesh
    and the contact lands ON ITS OWN (src/contacts_chat.rs pending_contacts) —
    until `lands_at` on the fake clock, after which the row is in
    GET /v1/contacts and the POST answers 201. `lands_at=None`: it never lands."""

    def __init__(self, clock, lands_at):
        self.clock, self.lands_at = clock, lands_at
        self.posts, self.gets = [], 0

    @property
    def landed(self):
        return self.lands_at is not None and self.clock.t >= self.lands_at

    def __call__(self, method, url, token=None, body=None, timeout=0):
        if url.endswith("/v1/federation/peers/peer-user"):
            return 404, {"error": "peer not found"}
        if url.endswith("/v1/contacts") and method == "GET":
            self.gets += 1
            rows = [{"key_id": "peer-user", "contact": True}] if self.landed else []
            return 200, {"contacts": rows, "total": len(rows)}
        if url.endswith("/v1/contacts"):
            self.posts.append(body)
            if body == {"key_id": "peer-user"}:
                return 404, {"reason_id": "contacts.unknown_fed_id"}
            if body == {"key_id": "CODE"}:
                if self.landed:
                    return 201, {"key_id": "peer-user", "reachable_nodes": 1}
                return 202, {"reason_id": "contacts.awaiting_key_body", "key_id": "peer-user",
                             "fed_id": "peer-user", "converges_on_its_own": True}
            return 200, {"key_id": body["key_id"], "reachable_nodes": 0}
        return 500, {}


def test_a_pending_code_contact_is_waited_for_until_it_lands(monkeypatch):
    """ciris-server 0.5.232 (release gate run 39706713027, all three node-only
    legs): `POST /v1/contacts {code}` answered 202 `contacts.awaiting_key_body`
    and the fixture took the 202 as an added contact, opened the pair room at
    once, and got 403 `chat.not_a_contact`. A 202 is "not yet": the fixture
    waits, bounded, for the row to appear in GET /v1/contacts."""
    clock = _Clock()
    node = _PendingNode(clock, lands_at=20)
    monkeypatch.setattr(tn, "http", node)
    monkeypatch.setattr(tn, "time", clock)
    host = tn.Party("local", "http://h", "t")
    guest = tn.Party("peer", "http://g", "t", owner_key_id="peer-user", node_key_id="peer-node")
    notes: list = []
    key, via = tn.add_contact(host, guest, wait=0, notes=notes, code="CODE", log=lambda m: None,
                              reachable_wait=0, land_wait=60)
    assert (key, via) == ("peer-user", "code")
    assert node.landed, f"returned at t={clock.t}s, before the contact landed (t=20s)"
    assert clock.t <= 30, "and not long after it did"
    assert any("contacts.awaiting_key_body" in n and "landed" in n for n in notes), notes


def test_a_pending_code_contact_that_never_lands_is_named_not_chatted_with(monkeypatch):
    """Never lands within `land_wait`: the fixture says so BY NAME and falls to
    the next way in (the peer node, said so) — it never hands back a key the
    node will answer `chat.not_a_contact` for."""
    clock = _Clock()
    node = _PendingNode(clock, lands_at=None)
    monkeypatch.setattr(tn, "http", node)
    monkeypatch.setattr(tn, "time", clock)
    host = tn.Party("local", "http://h", "t")
    guest = tn.Party("peer", "http://g", "t", owner_key_id="peer-user", node_key_id="peer-node")
    notes: list = []
    key, via = tn.add_contact(host, guest, wait=0, notes=notes, code="CODE", log=lambda m: None,
                              reachable_wait=0, land_wait=60)
    assert (key, via) == ("peer-node", "node")
    assert 60 <= clock.t <= 70, "bounded by land_wait"
    assert any("contacts.awaiting_key_body" in n and "never landed" in n for n in notes), notes


def test_a_contact_that_never_lands_anywhere_is_a_named_unavailable(monkeypatch):
    clock = _Clock()

    def pending_only(method, url, token=None, body=None, timeout=0):
        if url.endswith("/v1/contacts") and method == "GET":
            return 200, {"contacts": [], "total": 0}
        if url.endswith("/v1/contacts"):
            return 202, {"reason_id": "contacts.awaiting_key_body", "key_id": body["key_id"],
                         "converges_on_its_own": True}
        return 404, {}

    monkeypatch.setattr(tn, "http", pending_only)
    monkeypatch.setattr(tn, "time", clock)
    host = tn.Party("local", "http://h", "t")
    guest = tn.Party("peer", "http://g", "t", node_key_id="peer-node")
    with pytest.raises(tn.FixtureUnavailable, match="contacts.awaiting_key_body"):
        tn.add_contact(host, guest, wait=0, notes=[], log=lambda m: None, reachable_wait=0, land_wait=30)


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
