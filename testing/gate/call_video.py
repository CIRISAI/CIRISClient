"""Two clients, one call: the five-platform video-call check (CSD-134 §4, §5).

    python3 -m testing.gate.run_platform ... --call-video --peer-client-jar <desktop jar>

A call needs two people, and a flow drives one client, so this is its own step
after the flows, with its own verdict on the board:

NODE LEGS: a second node beside the leg's node (the two-node fixture's peer,
from the same `ciris-server` binary), CLAIMED BY A SECOND CLIENT: a desktop
client on this host in test mode, on its own automation port and home, signed
in through the same first-run wizard the leg's client used. The two nodes are
then seeded exactly as `fixture: two_node` seeds them (announce, peering both
ways, owner keys, contacts, the pair room keyed). Both clients open the pair
room and join its call with video. Test mode sends a moving test pattern for
the camera and a 440 Hz tone for the microphone (`TestModeDevices`), so what
each side receives is a measurement, not a connection:

  * audio: the other person's tile (`row_call_tile_<node>`) counts frames > 0
    and hears ~440 Hz;
  * video: a decoded picture of the other person (`media_call_video_<stream>`,
    value = the publisher's node key) is on screen.

The waits are CIRISServer's av_member harness's: both sides joined, `ready`,
then `GET …/call` shows `joined.legs` on both nodes before anything is judged,
because frames sent before the leg forms are dropped.

VIDEO NEEDS ciris-server 0.5.254. Before it the member join binds no codec map,
so edge frames nothing and a video frame (kilobytes) cannot fit one chunk
(the server session, 2026-11-23). Below the floor the video half is REFUSED,
said on the board, and the audio half is still gated.

AGENT LEGS (with AI): the leg's client reaches its node through ciris-agent,
which forwards the call's WebSockets only from 2.23.2 (CIRISAgent#1373). Below
that, the agent has no WebSocket route and Starlette answers the upgrade with
403, so the client cannot open its frames socket and leaves at once. The probe
here opens that upgrade itself, through the URL the client uses:

  * agent < 2.23.2 and HTTP 403            -> XFAIL: expected, reported, green
  * agent < 2.23.2 and anything else       -> FAIL: not the failure we expect
  * agent >= 2.23.2 and HTTP 403           -> FAIL: forwarding regressed
  * agent >= 2.23.2 and the node's answer  -> PASS: forwarded (a 401 without a ticket)

A two-client call through an agent is not run yet; the agent legs have no peer.

NOT A SECOND MECHANISM: the peer client is the same jar, launched the way
bringup launches the leg's desktop (`java -jar`, under xvfb-run where there is
no display), and signed in by session_fixture.
"""

from __future__ import annotations

import os
import re
import socket
import subprocess
import time
import urllib.parse
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

from testing.driver import DriverError, TestAutomationServer
from testing.gate import two_node
from testing.gate.two_node import FixtureUnavailable, Party, PeerNode, PeerSpec

#: ciris-server that frames video joins (`ready.fragmenting`), CIRISServer 0.5.254.
VIDEO_SERVER_FLOOR = (0, 5, 254)
#: ciris-agent that forwards the call frames WebSocket (CIRISAgent#1373, 2.23.2).
AGENT_WS_FLOOR = (2, 23, 2)

#: The peer client's automation port, and the peer node's transport port (its
#: read API is the next one). Clear of the leg's 9091/4242-4243 and of the
#: two-node fixture's 5242-5243, which has been torn down by now anyway.
PEER_CLIENT_PORT = 9092
PEER_NODE_PORT = 5262

#: Pitch the tone is judged at: TestModeDevices sends 440 Hz. The pitch is a
#: zero-crossing count over decoded Opus frames, so it wanders: run 45304697279
#: read 425-475 Hz from a clean tone. 60 Hz still tells the tone from silence,
#: noise (thousands) and an octave slip (880).
TONE_HZ, TONE_TOLERANCE = 440, 60

#: Where a desktop client lands once it can be driven (run_platform's walk).
ENTRY_SCREENS = {"Login", "Setup", "Interact", "Contacts", "ManageNodes"}

PASS, FAIL, XFAIL, REFUSED = "pass", "fail", "xfail", "refused"
GREEN = {PASS, XFAIL, REFUSED}


@dataclass
class Check:
    """One line on the board: `call-video:<name>`."""

    name: str
    status: str
    detail: str

    @property
    def ok(self) -> bool:
        return self.status in GREEN


@dataclass
class Seen:
    """What one client shows of the other person in the call."""

    frames: int = 0
    hz: int = 0
    picture: bool = False
    tile: str = ""
    audio_unavailable: bool = False


def version_tuple(v: str) -> Tuple[int, ...]:
    """`v0.5.253`, `0.5.254-rc1`, `2.23.2-stable` -> (0, 5, 253) ... ; () if unreadable."""
    m = re.search(r"(\d+)\.(\d+)\.(\d+)", v or "")
    return tuple(int(x) for x in m.groups()) if m else ()


def at_least(v: str, floor: Tuple[int, ...]) -> Optional[bool]:
    t = version_tuple(v)
    return None if not t else t >= floor


# ── the agent probe ─────────────────────────────────────────────────────────


def ws_upgrade_status(base_url: str, path: str, timeout: float = 10.0) -> Tuple[int, str]:
    """The HTTP status a server answers a WebSocket upgrade with, and its status
    line. 101 is an accepted upgrade; 0 is no answer. Stdlib only: a raw request."""
    u = urllib.parse.urlparse(base_url)
    host, port = u.hostname or "127.0.0.1", u.port or (443 if u.scheme == "https" else 80)
    req = (f"GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\n"
           f"Connection: Upgrade\r\nSec-WebSocket-Key: Y2lyaXMtZ2F0ZS1wcm9iZQ==\r\n"
           f"Sec-WebSocket-Version: 13\r\n\r\n").encode()
    try:
        with socket.create_connection((host, port), timeout=timeout) as s:
            s.sendall(req)
            head = s.recv(4096).decode("latin-1", errors="replace")
    except OSError as e:
        return 0, f"{type(e).__name__}: {e}"
    line = head.split("\r\n", 1)[0]
    m = re.match(r"HTTP/1\.[01] (\d{3})", line)
    # The first line of the body says WHO refused: run 45312276429's macOS agent
    # answered 400, not the 403 the agent session described.
    rest = head.split("\r\n\r\n", 1)[1].strip() if "\r\n\r\n" in head else ""
    body = rest.splitlines()[0][:160] if rest else ""
    return (int(m.group(1)) if m else 0), (f"{line} — {body}" if body else line)


def agent_verdict(agent_version: str, status: int, line: str) -> Check:
    """The rule CIRISAgent gave (2026-11-23), as a pure function so it is tested."""
    path = "/v1/chat/<room>/call/frames"
    forwards = at_least(agent_version, AGENT_WS_FLOOR)
    if forwards is None:
        return Check("agent-ws", FAIL, f"agent version {agent_version!r} is unreadable, so "
                                       f"the expected result is unknown ({line or status})")
    if not forwards:
        if status == 403:
            return Check("agent-ws", XFAIL,
                         f"expected: ciris-agent {agent_version} has no WebSocket route for {path} "
                         f"and refuses the upgrade ({line}); forwarding ships in 2.23.2 (CIRISAgent#1373)")
        return Check("agent-ws", FAIL,
                     f"ciris-agent {agent_version} answered the {path} upgrade with {line or status}, "
                     f"not the 403 an agent without forwarding gives — a different failure")
    if status == 403:
        return Check("agent-ws", FAIL, f"ciris-agent {agent_version} should forward {path} but refused "
                                       f"the upgrade with 403 — forwarding regressed")
    if status in (0, 502):
        return Check("agent-ws", FAIL, f"ciris-agent {agent_version}: {line or 'no answer'} — the agent "
                                       f"could not reach its node")
    return Check("agent-ws", PASS, f"ciris-agent {agent_version} forwarded the {path} upgrade to the "
                                   f"node, which answered {line} (no ticket was sent)")


def probe_agent(drv: TestAutomationServer, agent_version: str,
                agent_url: str = "http://127.0.0.1:8080") -> List[Check]:
    """Probe the AGENT's port, not the client's node URL. On these legs the
    client reaches its node on :4243 directly, so its own calls never cross the
    agent (run 45304697279 probed :4243 and got the node's 401); the agent's
    forwarding is what a client attached through the agent's port depends on."""
    try:
        client_node = str(drv.state().get("nodeUrl") or "?")
    except DriverError:
        client_node = "?"
    status, line = ws_upgrade_status(agent_url, "/v1/chat/gate-probe/call/frames")
    out = agent_verdict(agent_version, status, line)
    out.detail += f" [probed the agent at {agent_url}; this client's own node URL is {client_node}]"
    return [out, Check("call", REFUSED, "a two-client call through an agent is not run: the agent "
                                        "legs have no peer node yet")]


# ── the peer client ─────────────────────────────────────────────────────────


class PeerClient:
    """A second desktop client, in test mode, on its own port, home and prefs."""

    def __init__(self, jar: Path, node_url: str, work: Path, xvfb: bool) -> None:
        self.jar, self.node_url, self.work, self.xvfb = Path(jar), node_url, Path(work), xvfb
        self.proc: Optional[subprocess.Popen] = None
        self.log = self.work / "peer-client.log"
        self._fh = None

    def start(self) -> TestAutomationServer:
        # NOTHING MAY ALREADY ANSWER ON OUR PORT. Run 45312276429: the Linux leg's
        # second client outlived its `xvfb-run` wrapper, and the Android leg then
        # drove THAT client (found on 'UserChat') instead of its own.
        if ws_upgrade_status(f"http://127.0.0.1:{PEER_CLIENT_PORT}", "/health", timeout=2)[0]:
            raise FixtureUnavailable(f"something already answers on 127.0.0.1:{PEER_CLIENT_PORT} — "
                                     f"a client from an earlier leg is still running")
        self.work.mkdir(parents=True, exist_ok=True)
        home, prefs = self.work / "client-home", self.work / "client-prefs"
        home.mkdir(exist_ok=True)
        prefs.mkdir(exist_ok=True)
        env = dict(os.environ)
        env.update({
            "CIRIS_TEST_MODE": "true",
            "CIRIS_TEST_PORT": str(PEER_CLIENT_PORT),
            "CIRIS_NODE_URL": self.node_url,
            "CIRIS_API_URL": self.node_url,
            "CIRIS_HOME": str(home),
            "CIRIS_RUN_WITHOUT_AI": "true",
        })
        # Its own java.util.prefs root, so its stored session is not the leg
        # client's (honoured where prefs are files: Linux; elsewhere the two
        # clients read their sessions at start, the leg's long before this one).
        # A BOUNDED HEAP. Uncapped, the JVM may take a quarter of the runner's
        # RAM, and on the Android leg it shares the machine with the emulator and
        # two nodes: run 45304697279 lost that runner ("lost communication with
        # the server") during the Android step. A call client needs far less.
        env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") +
                                    f" -Xmx1g -Djava.util.prefs.userRoot={prefs}").strip()
        cmd = ["java", "-jar", str(self.jar)]
        if self.xvfb:
            cmd = ["xvfb-run", "-a"] + cmd
        kwargs: Dict[str, Any] = {}
        if os.name == "nt":
            kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP  # type: ignore[attr-defined]
        else:
            kwargs["start_new_session"] = True
        self._fh = self.log.open("ab")
        self.proc = subprocess.Popen(cmd, stdout=self._fh, stderr=subprocess.STDOUT, env=env, **kwargs)
        drv = TestAutomationServer(base_url=f"http://127.0.0.1:{PEER_CLIENT_PORT}")
        drv.wait_for_server(timeout=120)
        drv.wait_for_ui(timeout=120)
        # COMPOSED IS NOT ARRIVED. The first screen is Startup, with nothing to
        # press; run 45298429162 clicked `btn_local_login` there on all three
        # desktops. Wait for the screens the leg's own walk waits for.
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            if self.proc is not None and self.proc.poll() is not None:
                raise FixtureUnavailable(f"the peer client exited with {self.proc.returncode}; see {self.log}")
            if (drv.screen() or "") in ENTRY_SCREENS:
                return drv
            time.sleep(1.0)
        raise FixtureUnavailable(f"the peer client never reached Login or Setup (on {drv.screen()!r}); see {self.log}")

    def stop(self) -> None:
        if self.proc is not None:
            if os.name != "nt":
                # The whole process GROUP: under xvfb-run the JVM is a grandchild,
                # and TERM to the wrapper alone leaves it serving.
                import signal  # noqa: PLC0415
                for sig in (signal.SIGTERM, signal.SIGKILL):
                    try:
                        os.killpg(self.proc.pid, sig)
                    except (ProcessLookupError, PermissionError):
                        break
                    try:
                        self.proc.wait(timeout=10)
                        if sig is signal.SIGTERM:
                            # The wrapper is gone; give its children the same grace.
                            time.sleep(2)
                    except subprocess.TimeoutExpired:
                        continue
            else:
                two_node._terminate(self.proc.pid, self.proc)  # taskkill /T: the whole tree
            self.proc = None
        if self._fh is not None:
            self._fh.close()
            self._fh = None


# ── driving a client into the call ──────────────────────────────────────────


def to_contacts(drv: TestAutomationServer) -> None:
    """Walk to People the way the flow runner does (nav_map's derived hop):
    the automation server serves no /navigate, so a screen is reached by clicks."""
    if drv.screen() == "Contacts":
        return
    import asyncio  # noqa: PLC0415
    from testing.gate import run_flows  # noqa: PLC0415
    from testing.gate.flow_helper import SyncFlowHelper  # noqa: PLC0415
    hops, _ = run_flows.nav_hops(has_agent=False)
    err = asyncio.run(run_flows.navigate(SyncFlowHelper(drv), "Contacts", hops.get("Contacts", [])))
    if err:
        raise DriverError(err)


def open_call(drv: TestAutomationServer, contact_key: str, timeout: float = 120.0) -> None:
    """People -> the contact's row (the pair chat) -> Call -> Video."""
    to_contacts(drv)
    deadline = time.monotonic() + timeout
    row = f"contacts_row_{contact_key}"
    while time.monotonic() < deadline:
        if row in drv.tags():
            break
        if "btn_contacts_refresh" in drv.tags():
            drv.click("btn_contacts_refresh")
        time.sleep(3.0)
    drv.click(drv.wait_for_element(row, timeout=10).test_tag)
    drv.click(drv.wait_for_element("btn_chat_call", timeout=60).test_tag)
    drv.click(drv.wait_for_element("btn_call_video", timeout=30).test_tag)


def in_call(drv: TestAutomationServer) -> Optional[str]:
    """None while joining; else the state tag the call screen shows."""
    tags = drv.tags()
    for t in ("state_call_refused", "state_call_left"):
        if t in tags:
            el = drv.element(t)
            return f"{t}={el.text if el else ''}"
    if "card_call_tiles" in tags or "state_call_alone" in tags:
        return "in_call"
    return None


def seen_of(drv: TestAutomationServer, other_node: str) -> Seen:
    s = Seen()
    for e in drv.tree():
        if e.test_tag == f"row_call_tile_{other_node}":
            s.tile = e.text or ""
            m = re.search(r"frames=(\d+)\s+hz=(\d+)", s.tile)
            if m:
                s.frames, s.hz = int(m.group(1)), int(m.group(2))
        elif e.test_tag.startswith("media_call_video_") and (e.text or "") == other_node:
            s.picture = True
        elif e.test_tag == "txt_call_audio_unavailable":
            s.audio_unavailable = True
    return s


def legs_joined(party: Party, room: str) -> bool:
    status, body = two_node.http("GET", f"{party.url}/v1/chat/{room}/call", party.token)
    joined = body.get("joined") if isinstance(body, dict) else None
    return bool(isinstance(joined, dict) and joined.get("legs"))


def judge(side: str, seen: Seen, video_gated: bool, server: str) -> List[Check]:
    out: List[Check] = []
    # THE MEASUREMENT FIRST. MeasuringSink counts what was decoded BEFORE the
    # speaker, so a runner with no audio output still measures the tone (run
    # 45304697279, Windows: frames=82 hz=425 beside "no audio").
    if seen.frames > 0 and abs(seen.hz - TONE_HZ) <= TONE_TOLERANCE:
        out.append(Check(f"{side}-audio", PASS, f"{side} heard the other side's tone: {seen.tile}"))
    elif seen.audio_unavailable and seen.frames == 0:
        out.append(Check(f"{side}-audio", REFUSED, f"{side}: this device reports no audio output and "
                                                   f"decoded nothing to measure ({seen.tile})"))
    else:
        out.append(Check(f"{side}-audio", FAIL, f"{side} did not hear a {TONE_HZ} Hz tone: "
                                                f"{seen.tile or 'no tile for the other person'}"))
    if not video_gated:
        out.append(Check(f"{side}-video", REFUSED,
                         f"video needs ciris-server >= 0.5.254 (video joins are framed there); this "
                         f"run's node is {server or 'unknown'}. Picture seen anyway: {seen.picture}"))
    elif seen.picture:
        out.append(Check(f"{side}-video", PASS, f"{side} shows a decoded picture of the other person"))
    else:
        out.append(Check(f"{side}-video", FAIL, f"{side} shows no decoded picture of the other person"))
    return out


def run_node_call(drv: TestAutomationServer, *, binary: Path, jar: Path, node_url: str,
                  username: str, password: str, work: Path, server_version: str, xvfb: bool,
                  shots: Path, leg: str, measure_for: float = 90.0,
                  log: Callable[[str], None] = print) -> List[Check]:
    video_gated = bool(at_least(server_version, VIDEO_SERVER_FLOOR))
    spec = PeerSpec(binary=two_node.resolve_binary(binary), home=work / "node",
                    key_id=two_node.unique_key_id("ciris-gate-call"), port=PEER_NODE_PORT,
                    bootstrap_peers=[two_node.transport_of(node_url)])
    node = PeerNode(spec, work)
    client = PeerClient(jar, spec.read_url, work, xvfb)
    checks: List[Check] = []
    peer_drv: Optional[TestAutomationServer] = None
    try:
        node.start()
        peer_drv = client.start()
        from testing.gate import session_fixture  # noqa: PLC0415
        session_fixture.run_setup(peer_drv, username, password, device="gate-peer")
        session_fixture.log_in(peer_drv, username, password)
        remote = Party("peer", spec.read_url, two_node.login(spec.read_url, username, password))
        local = Party("local", node_url.rstrip("/"), two_node.login(node_url, username, password))
        v = two_node.seed(local, remote, log=log)
        for n in v.notes:
            log(f"  note: {n}")
        if not v.room_id or not v.local_key_on_peer or not v.peer_key_id:
            return [Check("seed", FAIL, "the two nodes were not seeded into a keyed pair room: "
                                        + "; ".join(v.notes or ["no room"]))]
        checks.append(Check("seed", PASS, f"room {v.room_id}; peer node {v.peer_node_key_id}"))

        open_call(peer_drv, v.local_key_on_peer)
        open_call(drv, v.peer_key_id)
        deadline = time.monotonic() + 120
        states: Dict[str, Optional[str]] = {"leg": None, "peer": None}
        while time.monotonic() < deadline and not all(states.values()):
            states = {"leg": in_call(drv), "peer": in_call(peer_drv)}
            time.sleep(2.0)
        bad = {k: s for k, s in states.items() if s != "in_call"}
        if bad:
            return checks + [Check("join", FAIL, f"not in the call: {bad}")]
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline and not (legs_joined(local, v.room_id) and legs_joined(remote, v.room_id)):
            time.sleep(2.0)
        checks.append(Check("join", PASS, "both clients joined, and both nodes show the leg"))

        # Measure: give the decoders and the playout buffer time, then read.
        end = time.monotonic() + measure_for
        leg_seen, peer_seen = Seen(), Seen()
        while time.monotonic() < end:
            leg_seen = seen_of(drv, v.peer_node_key_id)
            peer_seen = seen_of(peer_drv, v.local_node_key_id)
            done_a = leg_seen.frames > 0 and leg_seen.hz and (leg_seen.picture or not video_gated)
            done_b = peer_seen.frames > 0 and peer_seen.hz and (peer_seen.picture or not video_gated)
            if done_a and done_b:
                break
            time.sleep(3.0)
        shots.mkdir(parents=True, exist_ok=True)
        for who, d in (("leg", drv), ("peer", peer_drv)):
            try:
                d.screenshot(str(shots / f"{leg}-call-{who}.png"))
            except Exception:  # noqa: BLE001 — a missing shot must not hide the verdict
                pass
        checks += judge("leg-hears-peer", leg_seen, video_gated, server_version)
        checks += judge("peer-hears-leg", peer_seen, video_gated, server_version)
        for d in (drv, peer_drv):
            try:
                if "btn_call_leave" in d.tags():
                    d.click("btn_call_leave")
            except DriverError:
                pass
        return checks
    except (FixtureUnavailable, DriverError, OSError) as e:
        return checks + [Check("call", FAIL, f"{type(e).__name__}: {e} (peer node log {node.log}, "
                                             f"peer client log {client.log})")]
    finally:
        client.stop()
        node.stop()


def run(drv: TestAutomationServer, args, leg: str) -> List[Check]:
    """The step run_platform calls after the flows."""
    if getattr(args, "with_ai", False):
        return probe_agent(drv, getattr(args, "agent_version", ""),
                           getattr(args, "agent_url", None) or "http://127.0.0.1:8080")
    binary = getattr(args, "node_binary", None)
    jar = getattr(args, "peer_client_jar", None) or getattr(args, "jar", None)
    if not binary or not jar:
        return [Check("call", FAIL, "the call check needs --node-binary and a desktop jar for the "
                                    "peer client (--peer-client-jar, or --jar on a desktop leg)")]
    import tempfile  # noqa: PLC0415
    work = Path(getattr(args, "call_work", None) or tempfile.mkdtemp(prefix=f"ciris-call-{leg}-"))
    xvfb = os.name != "nt" and not os.environ.get("DISPLAY") and os.uname().sysname == "Linux"
    return run_node_call(drv, binary=Path(binary), jar=Path(jar), node_url=args.node_url,
                         username=args.username, password=args.password, work=work,
                         server_version=getattr(args, "node_version", ""), xvfb=xvfb,
                         shots=args.shots, leg=leg)
