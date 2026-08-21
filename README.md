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
pip install "ciris-client[node]"     # the AI-free node client
pip install "ciris-client[agent]"    # the agent build (node + brain)
```

To run the readiness gates from a checkout — their framework lives in
[CIRISGrace](../CIRISGrace) and is not published yet:

```bash
pip install -e ../CIRISGrace
pip install -e ".[readiness]"
```

---

## The consumption contract

One client. Two flavors. Three distributions.

| distribution | what's in it | size |
|---|---|---|
| `ciris-client` | the resolver API and the version. No bundles. | ~25 KB |
| `ciris-client-node` | the built client, `CIRISBuild.HAS_AGENT = false` | 62.45 MiB |
| `ciris-client-agent` | the built client, `CIRISBuild.HAS_AGENT = true` | 62.45 MiB |

Consumers depend on `ciris-client[node]` or `ciris-client[agent]`, never on a
payload distribution directly, and never on both flavors at once.

### Asking it things

```python
import ciris_client

ciris_client.__version__            # '0.5.181' — pairs with ciris-server 0.5.181
ciris_client.installed_flavors()    # ('node',)
ciris_client.artifacts()            # [{'kind': 'desktop-uber-jar', 'bytes': …, 'sha256': …}]
ciris_client.artifact_path('desktop-uber-jar')
ciris_client.manifest()['vendored_from']   # {'repo': …, 'commit': …}
```

Every failure is loud and actionable. A flavor that is not installed, a payload
that outran its manifest, a `ciris-client` and a payload at different versions,
both flavors installed with no flavor named — each raises and says what to do.
The one thing it will never do is hand back a path to a placeholder.

### Why a distribution per flavor

Measured, not estimated. The desktop uber-jar is **66.48 MiB** and compresses to
a **65,488,254-byte** wheel — **62.5% of PyPI's 104,857,600-byte limit**, with
37.55 MiB of headroom. (104,857,600 is 100 MiB, not 100 MB; the 4.8 MiB
difference has been the whole remaining margin before now.) ProGuard would cut
most of the jar and is blocked on ktor 3.x (CIRISServer#379), so treat the size
as fixed.

So one wheel carrying both flavors does not merely run close to the limit — at
2 × 62.45 MiB it **does not fit**, before an Android AAR or anything else. The
split is not a precaution; it is the only arrangement that ships.

**Localization is the product and is never cut to save size.** 29 languages are
29 audiences. If a wheel stops fitting, split a flavor or a target;
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

1. Add `ciris-client[node]` (server) or `ciris-client[agent]` (agent) to
   requirements, pinned to the matching `ciris-server` version.
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
- A client version on the desktop bundle. `desktopApp/build.gradle.kts` still
  carries `packageVersion = "2.9.28"` by hand, which is why the jar is named
  `CIRIS-linux-x64-2.9.28.jar` while `CLIENT_VERSION` is 0.5.181. It is the same
  class of drift as #1 and #2 in the flavor table and has not been migrated.
- `generated-api` regeneration and drift detection: the generator is not in the
  build graph, so spec drift is silent (`client/VENDORING.md` §7).
- Anything reading the substrate's signed locale Merkle root. Until then the
  four-bundle byte-identity check stands in for it.
- Publication. Nothing is on PyPI yet; the wheels are CI artifacts.

## Status

Working, not scaffold. Run
[32414315040](https://github.com/CIRISAI/CIRISClient/actions/runs/32414315040)
is green end to end: both flavors compiled, both passed `:shared:desktopTest`,
both produced a 66.48 MiB desktop uber-jar, and both were packaged into
62.45 MiB wheels that install and resolve through `ciris_client.artifact_path`.

Nothing is published. The gaps above are real and named.
