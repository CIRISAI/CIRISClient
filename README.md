# CIRISClient

The CIRIS Kotlin Multiplatform client — the surface where a person meets the
mesh — and the gates that say whether it is fit to build.

The client source is now **here**, under [`client/`](client/), vendored from
CIRISAgent with its provenance recorded in
[`client/VENDORING.md`](client/VENDORING.md). It is built once, in two flavors,
and consumed as a dependency. CIRISServer and CIRISAgent still carry their own
copies today; deleting them is what finishes this.

## Install

```bash
pip install ciris-client     # one client; what it shows depends on the node
```

To run the readiness gates from a checkout — their framework lives in
[CIRISGrace](../CIRISGrace) and is not published yet:

```bash
pip install -e ../CIRISGrace
pip install -e ".[readiness]"
```

---

## The consumption contract

**One client. One distribution. One install.**

```bash
pip install ciris-client        # 62.97 MiB, carries the built client
```

There is no node flavor and no agent flavor to choose between, because the
choice was never really the consumer's to make: **a node can be upgraded with a
brain.** The published client carries every surface and decides at *runtime*,
from the node it is attached to, which ones to offer. Install the agent beside
a node and the same client reveals Interact, Tools, Memory and the agent
settings on its next probe — nothing to reinstall, nothing to re-pin.

`CIRISBuild.HAS_AGENT` survives as the build **ceiling** (a deliberately
stripped build stays possible, and CI still compiles it), but it is no longer
what a user's sidebar depends on. That is
`ClientMode` — see [`FSD/ONE_CLIENT_N_NODES.md`](FSD/ONE_CLIENT_N_NODES.md) §4.

### Asking it things

```python
import ciris_client

ciris_client.__version__            # '0.5.186' — pairs with ciris-server 0.5.186
ciris_client.artifacts()            # [{'kind': 'desktop-uber-jar', 'bytes': …, 'sha256': …}]
ciris_client.artifact_path('desktop-uber-jar')
ciris_client.manifest()['vendored_from']   # {'repo': …, 'commit': …}
```

Every failure is loud and actionable. A payload that outran its manifest, a
version split between the package and the bundles it carries, an artifact built
for another OS — each raises and says what to do. The one thing it will never do
is hand back a path to a placeholder.

### The size arithmetic, and why one wheel now fits

Measured, not estimated. The desktop uber-jar is **66.99 MiB** and the wheel
carrying it is **66,031,198 bytes — 63.0% of PyPI's 104,857,600-byte limit**,
with 37.03 MiB of headroom. (104,857,600 is 100 MiB, not 100 MB; the 4.8 MiB
difference has been the whole remaining margin before now.) ProGuard would cut
most of the jar and is blocked on ktor 3.x (CIRISServer#379), so treat the size
as fixed.

Two of those in one wheel — which is what shipping a node build *and* an agent
build together would have meant — does **not** fit, and that arithmetic is why
the client shipped as three distributions for a while. Gating the agent surfaces
at runtime instead removed the second copy rather than the limit: one build, one
wheel, comfortably inside.

**Localization is the product and is never cut to save size.** 29 languages are
29 audiences. If a wheel stops fitting, split a target;
`packaging/check_wheel_size.py` fails the build before PyPI does, and prints the
breakdown every time so the number is visible before it is a problem.

### Flavors: how `HAS_AGENT` is selected

`CIRISBuild.HAS_AGENT` decides whether the AI/assistant surfaces exist at all.
It was a `const val` hand-edited to `false` in CIRISServer's copy and `true` in
CIRISAgent's — the same file with two values in two repos, which is a fork with
no name and no way to build the other side.

It is now a Gradle property, the spelling [MISSION.md](MISSION.md) §5.2 already
named:

```bash
./gradlew -p client :desktopApp:packageUberJarForCurrentOS                   # node
./gradlew -p client :desktopApp:packageUberJarForCurrentOS -PhasAgent=true   # agent
```

`:shared:generateBuildFlavor` writes `CIRISBuild.kt` and `ClientVersion.kt` into
a generated source dir. They are still `const val`s in `commonMain`, so dead-code
elimination is exactly as it was: an agent-only surface behind
`if (CIRISBuild.HAS_AGENT)` is still removed from the node build at compile
time. What changed is where the constant comes from, not what it is.

`CLIENT_VERSION` comes from the repo-root `VERSION` file — the same file the
wheel version comes from. So `ciris-client==X` pairs with `ciris-server==X`, and
the version-mismatch banner cannot disagree with the package that shipped it.
Full rationale, including why generating it does not re-open CIRISServer#272:
[`client/VENDORING.md`](client/VENDORING.md) §4.

### Migrating off a vendored copy

For each of CIRISServer and CIRISAgent:

1. Add `ciris-client` to requirements, pinned to the matching `ciris-server`
   version. Both consumers install the same thing.
2. Replace reads of the vendored tree with `ciris_client.artifact_path(...)`.
3. Delete `client/`, and with it the hand-editing of `HAS_AGENT` and
   `CLIENT_VERSION`, and the localization-mirror duplication.
4. Keep the substrate where it belongs: `androidApp/wheels/`, jniLibs, the iOS
   Resources tree and the xcframeworks are `ciris-server` and `ciris-verify`
   release artifacts and are **not** in this repo
   ([`client/VENDORING.md`](client/VENDORING.md) §2). A device build re-hydrates
   them from those releases.

**Until step 3 happens on both sides, this repo is a third tree** — the cost
`AGENTS.md` warned about, worth paying only because it ends. The obligation is a
row in [`evidence/blocked_upstream.tsv`](evidence/blocked_upstream.tsv) with a
scannable predicate, not a note in someone's memory.

---

## Building

```bash
# the client (JDK 17 + Android SDK)
./gradlew -p client :shared:compileKotlinDesktop
./gradlew -p client :shared:desktopTest
./gradlew -p client :desktopApp:packageUberJarForCurrentOS

# the wheels — pip never compiles Kotlin; it packages what Gradle produced
python3 packaging/stage_artifacts.py --flavor node \
    --artifact desktop-uber-jar=client/desktopApp/build/compose/jars/*.jar
python3 -m build --wheel --outdir dist .
python3 -m build --wheel --outdir dist packaging/node
python3 packaging/check_wheel_size.py dist/*.whl
```

Without a Gradle run, `--placeholder "<reason>"` stages a payload that **raises
on every artifact lookup and names the reason**. A build that cannot produce a
client should say so, not produce something that installs and does nothing.

## Checks

| check | asks | cost |
|---|---|---|
| `client/tools/check_localization_sync.py --strict` | do the four bundles agree, and does every key referenced in commonMain resolve in `en.json`? | seconds |
| `packaging/check_vendoring.py` | has anything under `client/` drifted from upstream without a row in `VENDORING.md` §3? | seconds |
| `packaging/check_wheel_size.py` | does each wheel fit under 104,857,600 bytes? | seconds |
| `python -m readiness` | the build-readiness gates below | seconds |

All four run in [`.github/workflows/build.yml`](.github/workflows/build.yml).
Every `apt-get` in this repo goes through
[`.github/actions/apt`](.github/actions/apt/action.yml), which drops
`azure.archive.ubuntu.com` and bounds the update with `timeout 300` and
`Acquire::Retries=3` — an unhardened `apt-get update` is a coin flip that costs
a whole job when it loses.

## Readiness gates

```bash
python -m readiness                               # run every gate
python -m readiness gates                         # list them
python -m readiness run locale-parity toolchain
python -m readiness --client-tree ~/CIRISAgent/client   # grade a consumer's copy
python -m readiness --node http://127.0.0.1:4243       # enable node-dependent gates
python -m readiness --json out.json
```

The default client tree is this repo's `client/`. The two vendored copies still
exist and still diverge, so keep grading them too — a result from one tree is
not a result about the client.

| id | class | asks |
|---|---|---|
| `toolchain` | code | Are the build tools present for the platforms we target? |
| `substrate-binaries` | code | Are the per-platform substrate artifacts present? |
| `version-alignment` | code | Does CLIENT_VERSION match the node it ships against? |
| `generated-api-drift` | code | Does generated-api match its spec? — **not implemented** |
| `locale-parity` | data | Do the runtime locale bundles agree, and how complete are they? |
| `spec-drift` | data | Does the committed OpenAPI spec match what the node serves? (needs `--node`) |
| `surface-binding` | data | Does every documented endpoint reach a client surface? |
| `nav-gate-registry` | normative | Is every `SubstrateGate` pointing at an open issue? |
| `compat-matrix` | normative | Does the compatibility matrix carry this release's row? |

### Reading the board

`pass` · `fail` · `unimplemented` · `error`. `unimplemented` is **not** a pass
and does not count toward `passed_all_gates`.

Three gates need care when you read them:

- **`surface-binding` is a heuristic.** It greps the shared module for each
  documented path literal, so a URL built by string concatenation reads as
  unbound. The output is a worklist to confirm, not a verdict; the report marks
  it `heuristic: true`. It is the noisiest gate here by a wide margin.
- **`locale-parity` duplicates the client's own CI guard** on purpose — that one
  runs after you push, this one runs before you build. It adds a per-locale
  key-coverage number the CI guard does not compute.
- **`substrate-binaries` fails on this repo's tree, by design.** The substrate is
  other repositories' release artifacts and is deliberately not vendored
  (`client/VENDORING.md` §2). It still fails rather than passing on a documented
  absence: this tree cannot produce a device build, and a gate that passes on a
  known-empty directory is a gate that has learned to say yes.

## What is not here yet

- Android AAR and iOS framework artifacts in the wheels. Only the desktop
  uber-jar is staged today; the manifest carries a `kind` per artifact so adding
  them is a staging line, not a schema change.
- `generated-api` regeneration and drift detection: the generator is not in the
  build graph, so spec drift is silent (`client/VENDORING.md` §7).
- Anything reading the substrate's signed locale Merkle root. Until then the
  four-bundle byte-identity check stands in for it.
- Publication. Nothing is on PyPI yet; the wheels are CI artifacts.
  `.github/workflows/publish.yml` is wired for it — Trusted Publishing on a
  `v*` tag, no tokens, refusing outright to publish a placeholder payload —
  and waits on three pending publishers being registered on PyPI
  (workflow `publish.yml`, environment `pypi`).

## Status

Working, not scaffold, and **ready to evaluate** — see
[`EVALUATION.md`](EVALUATION.md) for the runnable path and the decision it asks
for.

The tree is the superset of both consumers' latest tags: CIRISServer
`v0.5.186` and CIRISAgent `v2.9.32-stable`, merged per
[`client/VENDORING.md`](client/VENDORING.md) §8. CI is green end to end — both
flavors compiled, both passed `:shared:desktopTest`, both produced a 66.98 MiB
desktop uber-jar named for the *derived* version (`CIRIS-linux-x64-1.5.186.jar`,
from release 0.5.186), and both were packaged into 62.94 MiB wheels that install
into a clean venv and resolve through `ciris_client.artifact_path`.

Nothing is published to PyPI yet. The gaps above are real and named.
