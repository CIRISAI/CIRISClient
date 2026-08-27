"""
The desktop end-to-end runner.

    python3 -m testing.run_e2e --corner all --jar <uber.jar> --report out.json

For each corner it stands up the node the corner describes, launches the real
desktop app against it under a real display, drives it through the automation
server, and writes a machine-readable report.

WHY THIS EXISTS AT ALL: until now CIRISClient's CI compiled the client, unit
tested `:shared`, built a jar and a wheel -- and never once started the app.
Every defect that reached the server team (a Reset that exits instead of
returning to setup, an Android element that registers a handler but not itself,
a debug export written where no file manager can see it) is a defect that only
exists once the app is RUNNING. This is the missing half.

HOW IT FAILS: loudly and with evidence. On any case failure it captures a
screenshot through `/screenshot`, dumps the element tree and the node log into
the report, and exits non-zero. A corner that could not be stood up is an
ERROR, never a skip -- the one thing a test harness must never do is decline to
run and report success.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import traceback
from dataclasses import asdict, dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from testing import cases as case_mod  # noqa: E402
from testing.cases import (  # noqa: E402
    ALL_CORNERS,
    LOCAL_NODE,
    REMOTE_AGENT,
    REMOTE_NODE,
    REMOTE_UNDETERMINED,
    Context,
)
from testing.driver import DriverError, TestAutomationServer  # noqa: E402
from testing.node_fixture import (  # noqa: E402
    AGENT_REWRITES,
    LOCAL_NODE_URL,
    NODE_API_PORT,
    NODE_REWRITES,
    UNDETERMINED_REWRITES,
    BrainFacade,
    RealNode,
    _health_ok,
    find_node_binary,
    free_port,
    wait_until_down,
)

TEST_PORT_DEFAULT = 9091


@dataclass
class CaseResult:
    name: str
    status: str  # passed | failed | skipped
    detail: str = ""
    notes: list[str] = field(default_factory=list)
    seconds: float = 0.0


@dataclass
class CornerResult:
    corner: str
    status: str  # passed | failed | error
    cases: list[CaseResult] = field(default_factory=list)
    screen: str = ""
    elements: list[str] = field(default_factory=list)
    node_log_tail: str = ""
    app_log_tail: str = ""
    screenshot: str = ""
    error: str = ""
    seconds: float = 0.0


def _pids_on_port(port: int) -> list[int]:
    """PIDs listening on a local TCP port, via /proc -- no lsof dependency."""
    inodes = set()
    for tcp in ("/proc/net/tcp", "/proc/net/tcp6"):
        try:
            lines = Path(tcp).read_text().splitlines()[1:]
        except OSError:
            continue
        for line in lines:
            f = line.split()
            if len(f) < 10 or f[3] != "0A":  # 0A = LISTEN
                continue
            try:
                if int(f[1].split(":")[1], 16) == port:
                    inodes.add(f[9])
            except (ValueError, IndexError):
                continue
    if not inodes:
        return []
    pids = []
    for entry in Path("/proc").iterdir():
        if not entry.name.isdigit():
            continue
        try:
            for fd in (entry / "fd").iterdir():
                target = os.readlink(fd)
                if target.startswith("socket:[") and target[8:-1] in inodes:
                    pids.append(int(entry.name))
                    break
        except (OSError, PermissionError):
            continue
    return pids


def _port_busy(port: int) -> bool:
    with socket.socket() as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", port)) == 0


def _wrap_display(cmd: list[str]) -> list[str]:
    """Give the app a display. CI has no X server; xvfb-run supplies one."""
    if os.environ.get("DISPLAY"):
        return cmd
    xvfb = shutil.which("xvfb-run")
    if not xvfb:
        raise RuntimeError(
            "no DISPLAY and no xvfb-run. The desktop app needs a display; "
            "install xvfb (`sudo apt-get install -y xvfb`) or run under one."
        )
    # -a picks a free server number; the screen must be big enough that the
    # window is not clipped, or elements register at coordinates off-screen.
    return [xvfb, "-a", "-s", "-screen 0 1920x1200x24"] + cmd



def _ciris_server_pids() -> list[int]:
    """Every live `ciris-server`, by PID. /proc only -- no psutil dependency."""
    out = []
    for entry in Path("/proc").iterdir():
        if not entry.name.isdigit():
            continue
        try:
            cmd = (entry / "cmdline").read_bytes().replace(b"\0", b" ").decode("utf-8", "replace")
        except (OSError, PermissionError):
            continue
        if "ciris-server" in cmd and "--home" in cmd:
            out.append(int(entry.name))
    return out


# The app's own record of the spawn. Polling for a live process is racy in
# exactly the case that matters: a node binary that dies on startup exists for
# less than one poll interval, so a client that DID try to launch one reads as a
# client that never tried. The log line is durable evidence of the same event.
NODE_LAUNCH_MARKER = "Started ciris-server (PID:"


def app_logged_a_node_launch(app_log: Path) -> bool:
    if not app_log.exists():
        return False
    return NODE_LAUNCH_MARKER in app_log.read_text("utf-8", "replace")


def app_spawned_a_node(app_proc: subprocess.Popen | None, app_log: Path | None = None) -> bool:
    """
    Did the APP start a node, as opposed to one merely existing?

    "A node is answering on the local port" cannot answer this: the remote
    corners deliberately run a real node as the substrate behind the facade, so
    that check was true for a client that did nothing wrong. This asks the
    precise question instead. The app is launched with `start_new_session=True`,
    so anything it spawns -- through xvfb-run, through the JVM, at any depth --
    inherits its session id, and nothing else on the machine shares it.
    """
    if app_log is not None and app_logged_a_node_launch(app_log):
        return True
    if not app_proc:
        return False
    try:
        app_sid = os.getsid(app_proc.pid)
    except (ProcessLookupError, PermissionError):
        return False
    for pid in _ciris_server_pids():
        try:
            if os.getsid(pid) == app_sid:
                return True
        except (ProcessLookupError, PermissionError):
            continue
    return False


class Corner:
    """One row of the matrix, stood up and torn down."""

    def __init__(self, name: str, jar: Path, node_bin: str, test_port: int, workdir: Path):
        self.name = name
        self.jar = jar
        self.node_bin = node_bin
        self.test_port = test_port
        self.workdir = workdir
        self.node: RealNode | None = None
        self.facade: BrainFacade | None = None
        self.app: subprocess.Popen | None = None
        self.app_log = workdir / "app.log"
        self.node_home = workdir / "nodehome"
        self.api_url: str | None = None

    # -- setup ---------------------------------------------------------

    def start_nodes(self) -> None:
        if self.name == LOCAL_NODE:
            # Nothing pre-started ON PURPOSE: the app must launch it, and that
            # launch is the thing under test.
            if _health_ok(LOCAL_NODE_URL, timeout=1.5):
                raise RuntimeError(
                    f"something is already answering at {LOCAL_NODE_URL}; the "
                    f"local corner cannot test self-launch against it. "
                    f"Stop it first: pkill -f 'ciris-server [-]-home'"
                )
            return

        # Remote corners need a real node behind the facade.
        self.node = RealNode(self.node_bin, self.node_home).start()
        rewrites = {
            REMOTE_NODE: NODE_REWRITES,
            REMOTE_AGENT: AGENT_REWRITES,
            REMOTE_UNDETERMINED: UNDETERMINED_REWRITES,
        }[self.name]
        self.facade = BrainFacade(rewrites, upstream=self.node.url).start()
        self.api_url = self.facade.url

    def app_env(self) -> dict[str, str]:
        env = dict(os.environ)
        env["CIRIS_TEST_MODE"] = "true"
        env["CIRIS_TEST_PORT"] = str(self.test_port)
        env["CIRIS_HOME"] = str(self.workdir / "cirishome")
        env["HOME"] = str(self.workdir / "fakehome")
        Path(env["CIRIS_HOME"]).mkdir(parents=True, exist_ok=True)
        Path(env["HOME"]).mkdir(parents=True, exist_ok=True)
        if self.api_url:
            # Both spellings: CIRIS_NODE_URL is upstream's, CIRIS_API_URL is ours.
            env["CIRIS_API_URL"] = self.api_url
            env["CIRIS_NODE_URL"] = self.api_url
        if self.name == LOCAL_NODE:
            # The app finds the node by PATH lookup; give it exactly the binary
            # this run downloaded, not whatever the developer has installed.
            env["PATH"] = f"{Path(self.node_bin).parent}{os.pathsep}{env.get('PATH','')}"
            env.pop("CIRIS_API_URL", None)
            env.pop("CIRIS_NODE_URL", None)
        return env

    def start_app(self) -> None:
        cmd = _wrap_display(["java", "-jar", str(self.jar)])
        with open(self.app_log, "wb") as fh:
            # Its own session, so we can signal the WHOLE tree. Under xvfb-run
            # the JVM is a grandchild; terminating the wrapper alone leaves it
            # holding the test port, and the next run then (correctly) refuses
            # to start against an app it does not own.
            self.app = subprocess.Popen(
                cmd, env=self.app_env(), stdout=fh, stderr=subprocess.STDOUT,
                start_new_session=True,
            )

    # -- teardown ------------------------------------------------------

    def stop(self) -> None:
        if self.app:
            self._signal_app_group(signal.SIGTERM)
            try:
                self.app.wait(timeout=20)
            except subprocess.TimeoutExpired:
                self._signal_app_group(signal.SIGKILL)
                self.app.wait(timeout=10)
            self.app = None
            # Prove the port is free. "I sent SIGTERM" is not that claim, and
            # the next corner binds this port.
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline and _port_busy(self.test_port):
                time.sleep(0.5)
        if self.facade:
            self.facade.stop()
            self.facade = None
        if self.node:
            self.node.stop()
            self.node = None
        # The local corner's node was started by the APP; it is our job to see
        # it gone before the next corner claims the port.
        if _health_ok(LOCAL_NODE_URL, timeout=1.5):
            # The bracket keeps the pattern from matching the shell that runs
            # it -- `pkill -f 'ciris-server --home'` matches its own command
            # line and kills the caller. Cost me one silent exit-144 run.
            subprocess.run(["pkill", "-f", "ciris-server [-]-home"], check=False)
            try:
                wait_until_down(LOCAL_NODE_URL, timeout=30)
            except RuntimeError:
                pass

    def _discover_node_home(self) -> Path | None:
        """Where the APP told its node to live -- read off the node's cmdline."""
        for pid in _ciris_server_pids():
            try:
                if os.getsid(pid) != os.getsid(self.app.pid):  # type: ignore[union-attr]
                    continue
                parts = (Path("/proc") / str(pid) / "cmdline").read_bytes().split(b"\0")
                args = [a.decode("utf-8", "replace") for a in parts if a]
                if "--home" in args:
                    return Path(args[args.index("--home") + 1])
            except (OSError, PermissionError, ProcessLookupError, IndexError, AttributeError):
                continue
        return None

    def _signal_app_group(self, sig: int) -> None:
        if not self.app:
            return
        try:
            os.killpg(os.getpgid(self.app.pid), sig)
        except (ProcessLookupError, PermissionError):
            try:
                self.app.send_signal(sig)
            except ProcessLookupError:
                pass

    def tail(self, p: Path, n: int = 40) -> str:
        if not p.exists():
            return "<no log>"
        return "\n".join(p.read_text("utf-8", "replace").splitlines()[-n:])

    # -- run -----------------------------------------------------------

    def run(self) -> CornerResult:
        started = time.monotonic()
        res = CornerResult(corner=self.name, status="passed")
        self.workdir.mkdir(parents=True, exist_ok=True)
        try:
            self.start_nodes()
            self.start_app()
            app = TestAutomationServer(f"http://127.0.0.1:{self.test_port}")
            try:
                app.wait_for_server(timeout=180)
            except DriverError as e:
                raise RuntimeError(
                    f"the app never exposed its automation server. "
                    f"{e}\n--- app log ---\n{self.tail(self.app_log)}"
                ) from None

            ctx = Context(
                corner=self.name,
                app=app,
                node_url=self.api_url or LOCAL_NODE_URL,
                node_was_prestarted=self.node is not None,
                node_home=self.node_home if self.name == LOCAL_NODE else None,
            )
            if self.name == LOCAL_NODE:
                # The app launches its node lazily; give it the same window the
                # client's own startServer() health loop uses before deciding
                # it never happened.
                deadline = time.monotonic() + 90
                while time.monotonic() < deadline and not app_spawned_a_node(self.app, self.app_log):
                    time.sleep(1.0)
                # It writes its home under the app's CIRIS_HOME, not ours.
                ctx.node_home = self._discover_node_home() or ctx.node_home

            for c in case_mod.for_corner(self.name):
                t0 = time.monotonic()
                # Re-read each time: the local corner's answer changes as the
                # app gets around to launching its node.
                ctx.app_spawned_node = app_spawned_a_node(self.app, self.app_log)
                before = list(ctx.notes)
                try:
                    c.fn(ctx)
                    res.cases.append(
                        CaseResult(c.name, "passed",
                                   notes=[n for n in ctx.notes if n not in before],
                                   seconds=round(time.monotonic() - t0, 2))
                    )
                except Exception as e:
                    res.status = "failed"
                    res.cases.append(
                        CaseResult(c.name, "failed", detail=str(e),
                                   notes=[n for n in ctx.notes if n not in before],
                                   seconds=round(time.monotonic() - t0, 2))
                    )

            for c in case_mod.skipped_for_corner(self.name):
                res.cases.append(CaseResult(c.name, "skipped", detail=f"not applicable to {self.name}"))

            res.screen = app.screen()
            res.elements = sorted(app.tags())
            if res.status == "failed":
                shot = self.workdir / "failure.png"
                if app.screenshot(str(shot)) and shot.exists():
                    res.screenshot = str(shot)
        except Exception as e:
            res.status = "error"
            res.error = f"{e}\n{traceback.format_exc(limit=3)}"
        finally:
            if self.node:
                res.node_log_tail = self.node.tail()
            res.app_log_tail = self.tail(self.app_log)
            self.stop()
        res.seconds = round(time.monotonic() - started, 1)
        return res


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--corner", default="all",
                    help=f"one of {', '.join(ALL_CORNERS)}, or 'all'")
    ap.add_argument("--jar", required=True, help="the desktop uber-jar")
    ap.add_argument("--node-bin", default=None, help="released ciris-server binary")
    ap.add_argument("--test-port", type=int, default=TEST_PORT_DEFAULT)
    ap.add_argument("--report", default=None, help="write a JSON report here")
    ap.add_argument("--workdir", default=None)
    ap.add_argument("--reclaim", action="store_true",
                    help="kill a leftover TEST-MODE app holding the test port "
                         "(safe: a test-mode app is a previous run's artefact)")
    args = ap.parse_args()

    jar = Path(args.jar).resolve()
    if not jar.is_file():
        print(f"no such jar: {jar}", file=sys.stderr)
        return 2
    node_bin = find_node_binary(args.node_bin)
    corners = list(ALL_CORNERS) if args.corner == "all" else [args.corner]
    unknown = [c for c in corners if c not in ALL_CORNERS]
    if unknown:
        print(f"unknown corner(s): {', '.join(unknown)}", file=sys.stderr)
        return 2

    root = Path(args.workdir) if args.workdir else Path(tempfile.mkdtemp(prefix="ciris-e2e-"))
    print(f"jar      : {jar}")
    print(f"node     : {node_bin}")
    print(f"workdir  : {root}")
    print(f"corners  : {', '.join(corners)}\n")

    if _port_busy(args.test_port) and args.reclaim:
        # Only ever a TEST-MODE CIRIS app: that is a disposable artefact of a
        # previous run by construction, and killing it is safe. Anything else
        # on the port still stops the run.
        probe = TestAutomationServer(f"http://127.0.0.1:{args.test_port}")
        try:
            if probe.health().get("testMode") is True:
                for pid in _pids_on_port(args.test_port):
                    try:
                        os.killpg(os.getpgid(pid), signal.SIGKILL)
                    except (ProcessLookupError, PermissionError):
                        pass
                deadline = time.monotonic() + 20
                while time.monotonic() < deadline and _port_busy(args.test_port):
                    time.sleep(0.5)
                print(f"reclaimed port {args.test_port} from a leftover test-mode app")
        except DriverError:
            pass

    if _port_busy(args.test_port):
        who = ""
        try:
            who = f" It answers /screen with {TestAutomationServer(f'http://127.0.0.1:{args.test_port}').screen()!r}, so it is a CIRIS app -- most likely one a previous run left behind."
        except DriverError:
            who = " It is not answering as a CIRIS automation server."
        print(
            f"port {args.test_port} is busy; the driver would drive the wrong app.{who}\n"
            f"Either stop it, or pass --test-port with a free one.",
            file=sys.stderr,
        )
        return 2

    results: list[CornerResult] = []
    for name in corners:
        print(f"=== {name} ".ljust(70, "=") + "\n")
        r = Corner(name, jar, node_bin, args.test_port, root / name).run()
        results.append(r)
        for c in r.cases:
            if c.status == "skipped":
                continue
            mark = "PASS" if c.status == "passed" else "FAIL"
            print(f"  [{mark}] {c.name} ({c.seconds}s)")
            for n in c.notes:
                print(f"         - {n}")
            if c.detail and c.status == "failed":
                print(f"         ! {c.detail}")
        n_skip = sum(1 for c in r.cases if c.status == "skipped")
        if n_skip:
            print(f"  ({n_skip} case(s) not applicable to this corner)")
        if r.status == "error":
            print(f"  [ERROR] {r.error.splitlines()[0] if r.error else '?'}")
            print(f"  --- app log ---\n{r.app_log_tail}")
        print(f"  -> {r.status.upper()} in {r.seconds}s\n")

    if args.report:
        Path(args.report).write_text(json.dumps(
            {"corners": [asdict(r) for r in results]}, indent=2))
        print(f"report: {args.report}")

    bad = [r for r in results if r.status != "passed"]
    print("\n" + "=" * 70)
    for r in results:
        print(f"  {r.corner:24s} {r.status}")
    print("=" * 70)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
