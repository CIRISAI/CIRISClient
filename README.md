# CIRISClient

The CIRIS Kotlin Multiplatform client — the surface where a person meets the
mesh — and the gates that say whether it is fit to build.

The client source lives here, under [`client/`](client/); its recorded state is
the digest in [`client/VENDORING.md`](client/VENDORING.md) §1. It is built once —
one artifact that narrows itself against the node it is attached to — and
consumed as a dependency: CIRISServer and CIRISAgent install `ciris-client` and
carry no copy of the source.

## Install

```bash
pip install ciris-client            # the desktop client for your OS
pip install ciris-client-wasm       # the browser bundle, on its own (7.4 MiB)
```

**One artifact per wheel.** PyPI's size limit is per file, so the question is
never "does the release fit" but "should this consumer download this payload".

| distribution | carries | size | who wants it |
|---|---|---|---|
| `ciris-client` | that OS's desktop uber-jar | 67–74% of the limit | anyone launching the desktop client |
| `ciris-client-wasm` | the WebAssembly browser bundle | 7.4 MiB | CIRISHome, and any node serving the web UI |

`ciris-client` ships one wheel per OS — Linux x86-64, macOS arm64, macOS
x86-64, Windows x86-64 — because the desktop runtime inside is built per
platform (`compose.desktop.currentOs`), and pip picks the right one. On a
platform with no specific wheel the fallback installs and then refuses with the
remedy, rather than handing over a jar that cannot start.

The **Android AAR** and the **iOS XCFramework** are attached to the GitHub
release rather than shipped as wheels: their consumers are Gradle and Xcode,
not pip.

`ciris_client.artifact_path("wasm-browser")` resolves the web bundle when
`ciris-client-wasm` is installed (`pip install "ciris-client[web]"`), so one
resolver API still covers everything.

### The locale bundle, for gates that read it

```python
ciris_client.locale_bundle()   # -> a directory of en.json + 28 locales + manifest.json
```

CIRISServer emits operator messages as `{id, text}`, where `id` is a
localization key with no Kotlin call site, and its release gates assert those
ids **resolve** against this bundle — the check that stops an operator reading
a raw token. The bundle lives inside the shipped jar; this extracts
it once, caches it keyed by version and jar digest (`CIRIS_CLIENT_CACHE` to
choose where, with a fallback if that is unwritable), and refuses to hand back
a partial bundle — a gate must not read an incomplete extraction as an
absence.

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
pip install ciris-client        # carries the built client for this OS
```

There is no node flavor and no agent flavor to choose between, because the
choice was never really the consumer's to make: **a node can be upgraded with a
brain.** The published client carries every surface and decides at *runtime*,
from the node it is attached to, which ones to offer. Install the agent beside
a node and the same client reveals Interact, Tools, Memory and the agent
settings on its next probe — nothing to reinstall, nothing to re-pin.

There is no compile-time agent switch. What a user's sidebar depends on is
`ClientMode`, probed from the node — see
[`FSD/ONE_CLIENT_N_NODES.md`](FSD/ONE_CLIENT_N_NODES.md) §4.

### Asking it things

```python
import ciris_client

ciris_client.__version__            # e.g. '0.5.234'; the compatible server range is in compat/matrix.json
ciris_client.artifacts()            # [{'kind': 'desktop-uber-jar', 'bytes': …, 'sha256': …}]
ciris_client.artifact_path('desktop-uber-jar')
ciris_client.manifest()['vendored_from']   # {'repo': …, 'commit': …}
```

Every failure is loud and actionable. A payload that outran its manifest, a
version split between the package and the bundles it carries, an artifact built
for another OS — each raises and says what to do. The one thing it will never do
is hand back a path to a placeholder.

### The size arithmetic

PyPI's per-file limit is 104,857,600 bytes (100 MiB). The 0.5.234 platform
wheels are 70.3–77.3 MB, 67–74% of it. ProGuard would cut most of the jar and is
blocked on ktor 3.x (CIRISServer#379), so treat the size as fixed. Two desktop
builds in one wheel would not fit, which is one more reason there is one build
with the agent surfaces gated at runtime.

**Localization is the product and is never cut to save size.** 29 languages are
29 audiences. If a wheel stops fitting, split a target;
`packaging/check_wheel_size.py` fails the build before PyPI does, and prints the
breakdown every time so the number is visible before it is a problem.

### One build, and where its version comes from

There is one Gradle build and one artifact:

```bash
./gradlew -p client :desktopApp:packageUberJarForCurrentOS
```

Which surfaces are offered is never a property of the artifact: a node that
gains a brain shows the agent surfaces on the next probe, with nothing to
reinstall (CIRISServer#479). `stage_artifacts.py` takes `--flavor` only to record
which build produced the staged jar.

`:shared:generateBuildFlavor` writes exactly one file, `ClientVersion.kt`.
`CLIENT_VERSION` comes from the repo-root `VERSION` file — the same file the
wheel version comes from. So `ciris-client==X` pairs with `ciris-server==X`,
and the version-mismatch banner cannot disagree with the package that shipped
it. Rationale: [`client/VENDORING.md`](client/VENDORING.md) §4.

### Localization: three lanes, one direction

This repo owns the localization corpus as well as the client — 29 languages,
5,726 keys, four byte-identical runtime bundles, and the glossaries that decide
what the words are.

```
translate ──► evaluate ──► repair
              ▲             ▲
              └─ enter here └─ enter here
```

`translate` brings English into 28 locales. `evaluate` grades what is already
there. `repair` corrects what the evaluation rejected. A run enters at one lane
and flows through the rest, so nothing reaches a shipped bundle unreviewed.

```bash
python3 localization/localize.py --lane translate --keys 'mesh_config.*' --dry-run
python3 localization/localize.py --lane evaluate  --keys 'commons_surface.*' --lang yo
```

In CI they are `i18n-translate`, `i18n-evaluate` and `i18n-repair`
(`workflow_dispatch`, any key pattern), plus `localize`, which runs the same
pipeline by itself whenever a PR changes `en.json`. All four call one reusable
workflow, and all four are decided by the same strict guard.

Three things are worth knowing before reading the code:

- **Evaluation is MQM**, not a score out of ten — span-level errors with a
  category and a severity, reference-free, in the shape
  [GEMBA-MQM](https://arxiv.org/pdf/2310.13988) established for LLM judging. The
  judge is a *different model family* from the drafter, because a judge that
  shares the drafter's weights shares its blind spots.
- **Refusals escalate; they never ship.** A model that cannot render a string is
  asked to say so rather than invent one, and that key — not the batch — goes up
  a ladder ending in a different model family. If the ladder is exhausted the
  build fails. Low-resource languages are the whole point: CIRIS ranks languages
  by *inverse* model support, so Yoruba, Hausa and Amharic come before Spanish,
  and they are exactly where a fallback to English would be invisible.
- **What it does not claim.** Terminology, structure, placeholders and meaning
  are guaranteed. Native fluency is not. Everything the pipeline writes is
  `draft` / `machine_reviewed` — machine-translated, MQM-reviewed by a judge of a
  different model family than the writer. There is no native-reviewer pipeline.

### Consumers

CIRISServer and CIRISAgent depend on `ciris-client` and resolve artifacts through
`ciris_client.artifact_path(...)`; neither carries a `client/` tree. The
substrate stays where it belongs: `androidApp/wheels/`, jniLibs, the iOS
Resources tree and the xcframeworks are `ciris-server` and `ciris-verify` release
artifacts and are **not** in this repo
([`client/VENDORING.md`](client/VENDORING.md) §2). A device build re-hydrates
them from those releases.

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

### The iOS XCFramework

CI builds it: `publish.yml`'s `ios-xcframework` leg runs on `macos-15-intel` and
is most of a publish's wall clock (about three hours). Release linking is
whole-program LLVM optimisation over all of Compose Multiplatform, bound by
single-core speed and memory. Building it on an Apple-silicon desktop first is an
optional shortcut:

```bash
packaging/build_xcframework_local.sh     # ~45 min on Apple silicon; run it before pushing the tag
```

The script builds the same Gradle task and uploads the zip to the
`xcframework-prebuilt` prerelease under a content-addressed name from
`packaging/xcframework_key.py`. `publish.yml`'s `ios-xcframework` leg looks for
that key and restores instead of linking, so the publish finishes in minutes.

Two things to know:

- **Run it before pushing the tag.** `VERSION` is in the key — on purpose,
  because `generateBuildFlavor` compiles `CLIENT_VERSION` into `commonMain`, so
  a new version genuinely is a different binary. Run it after the tag and you
  are racing the job you meant to spare.
- **Nothing depends on it.** A miss just builds. There is no
  per-job cancel on GitHub — `gh run cancel` takes the wheels and the release
  assets with it — so the leg has to decline the work itself, and a prebuilt is
  how you let it.

## Checks

| check | asks | cost |
|---|---|---|
| `client/tools/check_localization_sync.py --strict` | do the four bundles agree, and does every key referenced in commonMain resolve in `en.json`? | seconds |
| `packaging/check_vendoring.py` | does `client/` match the state digest in `VENDORING.md` §1, with no never-vendor class (§2) present? | seconds |
| `packaging/check_wheel_size.py` | does each wheel fit under 104,857,600 bytes? | seconds |
| `python -m readiness` | the build-readiness gates below | seconds |
| `client/tools/check_test_vm_stop.py` | does every test that builds a view model and resets `Dispatchers.Main` call `stopViewModels(...)` (cancel AND join) first, or carry `// lint: test-scheduler-main` because its Main is a `StandardTestDispatcher`? A hard zero; `--self-test` plants the defect | seconds |
| `client/tools/check_colour_literals.py` | has any file outside `ui/theme/CirisTokens.kt` gained a colour literal? A baseline that can only fall | seconds |
| `client/tools/gen_dimension_table.py --check` | is `ceg/Dimensions.kt` byte-identical to what the pinned registry (CC v1.0-rc6, 158 families) + glosses + renderer overrides generate, does the registry hash to its own `registry_sha256`, and does every emitted localization key resolve? | seconds |
| `client/tools/gen_glyphs.py --check` | is `ui/glyphs/CirisGlyphs.kt` byte-identical to what `client/design/icon-paths.json` generates? | seconds |
| `packaging/check_csd_v3.py FSD/CSD/*.md --registry client/ceg/namespace_registry.json` | does every CSD's typed block validate against the registry it pins (`registry_sha256` = the grammar hash)? | seconds |
| `packaging/check_csd_routes.py` | does every route a screen calls (heuristic closure over `CIRISApp.kt` → composables → view models → `CIRISApiClient`) appear in §3 of a CSD on that screen or a sibling screen (same host composable, e.g. the four `LayerHubScreen` hubs), and has no new pair of cards started calling the same mutating route? A baseline that can only fall and must be re-recorded when it does; `--report` is the route map, `--print CSD-NNN` generates §3 rows | seconds A `reset*`/`clear*`/`forget*`/`dismiss*` member call does not charge the model's init-time reads to the caller. |
| `packaging/check_csd4.py --self-test` · `--verify-pin` (and, per card, through `check_csd_v3.py`) | does a card's CSD/4 `csd:tests` block name a catalogue entry in T0–T19, atoms in the pinned vocabulary, atoms some entry holds at one instant (the server model's own trace), and exactly the topology, tests, atoms and probes the server's `requirements.yaml` carries for that CSD? Catalogue pinned in `packaging/csd4/PIN.md` (re-vendor with `packaging/vendor_csd4.py --from-release <tag>`, which checks the `csd4-*` assets against the release's `SHA256SUMS` and cosign and runs no server code); `--self-test` plants each defect | seconds |
| `packaging/gen_csd_roster.py --check` | is `FSD/CSD/README.md` — every card number, adopted, reserved (`FSD/CSD/numbers.tsv`) or retired — current, and does no card sit on a reserved or retired number? | seconds |

All of them run in [`.github/workflows/build.yml`](.github/workflows/build.yml).
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

The default client tree is this repo's `client/`; `--client-tree` grades any
other checkout.

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
| `compat-matrix` | normative | Does the compatibility matrix carry this release's row, and does the client's `MIN_NODE_VERSION` agree with it? |

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

## Known limits

- The Android AAR and the iOS XCFramework are GitHub release assets, not wheels.
  The manifest carries a `kind` per artifact, so staging them in a wheel is a
  staging line, not a schema change.
- `generated-api` regeneration and drift detection: the generator is not in the
  build graph, so spec drift is silent (`client/VENDORING.md` §7).
- Nothing reads the substrate's signed locale Merkle root; the four-bundle
  byte-identity check stands in for it.
- Translations are `draft` / `machine_reviewed`: machine-translated and
  MQM-reviewed by a judge of a different model family. Native fluency, dialect
  coverage, cultural adaptation and legal review are not claimed.

Every release is gated on a five-platform run with AI and recorded, one row per
release, in [`compat/matrix.json`](compat/matrix.json).
