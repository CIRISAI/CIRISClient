# The walk tests

CI compiled this client, unit-tested `:shared`, built a jar and a wheel, checked
facts about PyPI — and never once started the app.

Every defect this repo has handed to the server team lived past all of that. A
Reset that exits instead of returning to the wizard. An Android element that
registered a click handler but not itself, so it was invisible to automation
while looking fine to a human. A debug export written where no file manager on
the platform can see it. A `PlatformLogger` that never fed `DebugLogBuffer`.
None of those are visible to a compiler, and all of them are obvious within
three seconds of the app running.

This is the missing half: it starts the real app against real nodes and drives
it through the automation server the client already ships.

## The matrix

The client is meant to be complete against a node that is **local or remote**,
and that is **carrying a brain or not**. Those are independent axes resolved by
two different mechanisms, so a harness that points the app at one auto-started
node tests one corner and calls it done.

| corner | node | brain | what only this corner sees |
|---|---|---|---|
| `local-node` | the app launches it | none | the self-launch path, and the claim-PIN read the shipped wheel depends on |
| `remote-node` | pre-started, via `CIRIS_API_URL` | none | that the client does **not** quietly start a local node it wasn't asked for |
| `remote-agent` | pre-started | folded, answering | the agent surface against a node that declares one |
| `remote-undetermined` | pre-started | folded, **not** answering | that the client does not *latch* — undetermined is a retry signal, not a verdict |

**Location** is not "which URL". `PythonRuntime.desktop.startServer()` probes
its configured URL and only launches `ciris-server` if nothing answers, so the
axis is *who started the node* — and the remote corners assert the negative by
session id, not by port liveness (they run a real node themselves as the
facade's substrate, so "a node is answering" is true for a blameless client).

**Brain** is `ClientMode`, derived from `/v1/system/health` (CIRISServer#390).
A real bare node gives NODE for free — the released binary boots in ~2s. A real
agent would need a brain and an LLM bill, and still could not produce
`undetermined` on demand, because that state is a race. So the brain axis is
served by `BrainFacade`: a proxy that rewrites **only** the routes the mode gate
reads, exactly as the contract documents them, and passes everything else
through to the real node untouched. Nothing the client actually calls is
stubbed.

The facade presents *coherent* nodes, and that is load-bearing rather than
tidiness. `clientModeFrom` demotes an answering brain to NODE when the brain
reports itself unconfigured (CIRISAgent#1075), and `undetermined` requires
`!brainUnconfigured`. Both the agent and undetermined corners initially failed a
**blameless client** because the facade folded a brain onto a node whose
`/v1/setup/status` still said `setup_required: true`. An agent presents both, or
it is not an agent.

### What the "remote" corners do NOT test

The facade runs on `127.0.0.1`, so the node sees a **loopback** peer and its
loopback-only routes answer normally. `GET /v1/setup/status` and
`/v1/setup/owned-nodes` are localhost-only and return 403 to a genuinely off-host
client (measured against the released binary; see
`docs/FSD-remote-first-run-claim.md` §3.1).

So these corners exercise the client's remote *configuration* path -- it is
pointed at another URL, it must not launch a node, it derives its mode from that
URL -- but not remote *reachability*. A real off-host client meets 403s this
harness never produces. Until a corner makes the node observe a non-loopback
source address, no result here is evidence about off-host behaviour.

### The corner this cannot cover

**local × agent.** The released node binds 4242/4243 with no port override —
`ciris-server [--home <path>] [--key-id <name>]` is the entire usage — so the
facade cannot sit where a self-launched node must be. Folding a real brain onto
a local node is the downstream mobile/manual test. It is not faked here, and it
is not silently absent: `run_e2e.py` prints every case it skips.

## Running it

```bash
# one corner, against a node you already have
python3 -m testing.run_e2e --corner remote-agent \
  --jar client/desktopApp/build/compose/jars/CIRIS-linux-x64-*.jar

# the whole matrix, with the node CI uses
gh release download v0.5.190 -R CIRISAI/CIRISServer \
  -p 'ciris-server-*-x86_64-unknown-linux-gnu.tar.gz' -D node
tar xzf node/ciris-server-*.tar.gz -C node && chmod +x node/ciris-server
python3 -m testing.run_e2e --corner all --node-bin node/ciris-server \
  --jar client/desktopApp/build/compose/jars/CIRIS-linux-x64-*.jar \
  --report e2e-report.json
```

Needs a display. With `DISPLAY` set it uses it; without one it wraps the app in
`xvfb-run`, which is how CI runs (`xvfb` **and** `xauth` — `xvfb-run` fails with
a bare "xauth: not found" otherwise).

`--reclaim` kills a leftover **test-mode** app holding the test port. That is
safe by construction — a test-mode app is a previous run's artefact — and
nothing else on the port is ever killed; the run stops instead.

Whole matrix: about four minutes. Three corners take ~5s each; the undetermined
corner takes ~145s because the client spends its full 60s retry budget on the
probe, which is the behaviour under test.

## The pieces

| file | what it is |
|---|---|
| `driver.py` | the automation-server client — `/health`, `/tree`, `/screen`, `/state`, `/click`, `/input`, `/screenshot`. stdlib only |
| `node_fixture.py` | `RealNode` (a released `ciris-server`) and `BrainFacade` (the brain axis) |
| `cases.py` | the assertions, each declaring the corners it applies to |
| `run_e2e.py` | stands up each corner, launches the app, drives it, writes the report |

The **server** side of the automation surface was already ours
(`client/desktopApp/.../testing/TestAutomationServer.kt`, and the Android and
iOS actuals). What lived only in CIRISAgent was the thing that drives it:
`tools/test_desktop_wipe_setup.sh`, 191 lines of `curl | grep` against five of
the sixteen routes. This is that, taken over and made a library.

`/state` is new here. Inferring node-vs-agent from which widgets are on screen
asserts the *layout* rather than the *gate*, and passes a client that renders
agent affordances against a bare node — so the app publishes its own account of
`clientMode` and the node URL it settled on, and the harness asserts that.

## How it fails

Loudly, with evidence. A failing case captures a screenshot, the element tree,
the app log and the node log into the report. A corner that could not be stood
up is an **error**, never a skip.

Some of that is scar tissue from building it. `curl -s` against a dead server
prints nothing and exits 0, so the shell script this replaces read a missing app
as a screen named `""` and walked on. The fixture refuses to adopt a node it did
not start, because a leftover on the fixed port answers exactly like a fresh
one. An explicit `--node-bin` that does not exist is fatal rather than falling
back to PATH, because there are two different programs called `ciris-server` in
this ecosystem and resolving to the wrong one runs the whole suite and reports
on it as though it were right.

Each of those was found by this harness's own mutation tests, which are the
reason to trust a green run: a node binary that cannot start turns the local
corner red, and a brain that answers when the corner says it should not turns
the latch detector red. A gate that cannot fail is not a gate.
