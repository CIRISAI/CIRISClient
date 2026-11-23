# CIRISClient

The CIRIS Kotlin Multiplatform client — the surface where a person meets the
mesh — and the gates that say whether it is fit to build.

The client source lives here, under [`client/`](client/); its recorded state is
the digest in [`client/VENDORING.md`](client/VENDORING.md) §1. It is built once —
one artifact that narrows itself against the node it is attached to — and
consumed as a dependency: CIRISServer and CIRISAgent install `ciris-client` and
carry no copy of the source.

## From ethics to UX

What a person sees on a screen should be traceable back to a promise the
system makes, through every layer in between. Each link below has an
artifact, an owner and a check. Where a link is only partly checked, the
section says so.

| # | Layer | Artifact | Owner | What holds it to the next layer |
|---|---|---|---|---|
| 1 | **Ethics** | The CIRIS Constitution's Charter: Meta-Goal M-1 and the eleven promises (e.g. promise 4: named humans can pause every agent under a root) | [CIRISConstitution](https://github.com/CIRISAI/CIRISConstitution) | each promise names the sections that keep it (`constitution/EXECUTIVE_SUMMARY.md`) |
| 2 | **Rules** | The constitution's numbered sections (e.g. CC 4.2.1.1, the halt; CC 4.2.6, a strict majority of the standing roster) | CIRISConstitution | the register ties each rule to a model, test or proof, or says none exists |
| 3 | **Model** | `formal/`: the life of a mesh as checked TLA+, Lean and Python models (e.g. `accord_halt/AccordHaltFuse.tla`: a lone halt lapses, a majority confirms), summarised as every object's states in `formal/STATE_TABLE.tsv` | CIRISConstitution | model checking (TLC, Lean) in that repo |
| 4 | **Manifests** | `manifests/transitions.json` (every family's transitions: who may sign, under which policy, whether it needs a confirm), `namespace_registry.json` (the families), `ui_primitives.json` (the screen's primitives and laws) | CIRISConstitution | generated from the text and the models there |
| 5 | **Cards** | One CSD per screen in [`FSD/CSD/`](FSD/CSD/README.md) ([`CSD.md`](CSD.md)): its mission (why, in M-1's terms), what it shows, the states it draws, the acts it offers, the routes it calls, its tests | this repo | see "The card's bindings" below |
| 6 | **Code** | The Kotlin client in `client/`: screens, view models, the API client | this repo | the card's bindings, plus unit tests |
| 7 | **Words** | `en.json` and 28 locales, the glossaries, the server's reason ids | this repo | `check_localization_sync.py`, `check_english_fragments.py`, the MQM lanes |
| 8 | **Running proof** | Flows under `testing/flows/`, the desktop e2e walk, the five-platform run with AI, `compat/matrix.json` | this repo | CI on every PR; the five-platform run gates every release |

### The card's bindings

A card is the point where the upstream layers (1–4) meet the code (6). It
cites the upstream layers rather than restating them, and each kind of
citation has its own check:

| Block or section | Binds the card to | Check |
|---|---|---|
| `csd:shows` / `csd:states` | the registry's families (layer 4) | `check_csd_v3.py`, against the pinned `client/ceg/namespace_registry.json` |
| `csd:model` | the model's states and the manifest's transitions (layers 3–4): the card draws exactly the modelled states, and each act's signer, policy and confirm equal the manifest's | `check_csd_model.py`, against `packaging/model/` (CIRISConstitution rc22, pinned by sha256) |
| `csd:tests` | the server's topology catalogue | `check_csd4.py` |
| `csd:inputs` / `csd:view` / `csd:acts` | the twelve screen laws, model-checked with TLC | `check_csd5.py` (CSD-070 so far) |
| §3 routes | the routes the code actually calls (layer 6) | `check_csd_routes.py` |
| `csd:code_gaps` | the code (layer 6), where it does not match the card yet: what the card says, what the code does, and a pattern proving the gap is still open | `check_csd_code_gaps.py` |

The two last-added checks work as a pair.
- **`check_csd_model.py`:** every modelled state a card cites must match something in the code, or be listed as a code gap.
- **`check_csd_code_gaps.py`:** every listed gap must still be true. A gap that has closed fails CI until its entry is deleted.

So the distance between what the model says and what the client does is a list in the card, kept current by CI. The roster's **Code gaps** column shows it per card.

**The model moves the cards.** A daily job ([`model-drift.yml`](.github/workflows/model-drift.yml)) fails when CIRISConstitution's model has moved past the pin. Re-pinning (`check_csd_model.py --vendor <commit>`) then fails every card claim the new model no longer supports, in the same PR as the pin.

### One thread, end to end: the halt

1. **Ethics.** Promise 4: named humans can pause every agent under a root.
2. **Rules.** CC 4.2.1.1:
   - one holder's signed row halts;
   - a lone halt lapses at the fuse unless a strict majority confirms it;
   - a majority resumes.
3. **Model.** `AccordHaltFuse.tla` closes, with `Inv_LoneBounded` (a lone pause never outlives the fuse). `root.halt` has three states: `not_halted`, `latched_lone`, `confirmed`.
4. **Manifest.** `accord:invoke:constitutional:{halt_id}` attest has roles `holder`, policy `mirror` and confirm `true`.
5. **Card.** CSD-067 (the accord) and CSD-003 (the constitutional card) cite `root.halt` and that act in `csd:model`.
6. **Code.** `AccordHaltStatusResponse` types `not_halted`, but keeps the standing latch as raw JSON. The cards therefore declare the gap `halt-standing-untyped` for `latched_lone` and `confirmed`. When the client types them, CI requires the gap's deletion.
7. **Words.** The banner and confirm strings are in all 29 languages.
8. **Running proof.** Missing. No walk test here halts a node yet. The end-to-end drill/halt card, CSD-129 (draft PR #180), has evidence only from the earlier mesh. This is the link to build next.

### What is not bound yet

- **Gaps are declared, not discovered.** A mismatch nobody wrote down is invisible, except where `csd:model` forces it: a cited modelled state the code cannot show must be declared.
- **The code side is pattern-based.** A gap's pattern, and a model state's code pattern, are regular expressions over source files, not a semantic check.
- **Some cards cite nothing yet.** `csd:model` covers six trust-root cards (12 citations) and `csd5` covers one card. Every other card cites the registry and its routes only.
- **The model pin is states and transitions.** Rules in the text that no model or manifest carries are checked by review, not by CI.
- **Live behaviour across many nodes is not checked here.** Each check above runs on source; the walk tests start the app against one or two nodes.

## Install

```bash
pip install ciris-client            # the desktop client for your OS
pip install ciris-client-wasm       # the browser bundle, on its own (7.4 MiB)
```

**One artifact per wheel.** PyPI's size limit is per file, so the question is
never "does the release fit" but "should this consumer download this payload".

| distribution | carries | size | who wants it |
|---|---|---|---|
| `ciris-client` | that OS's desktop uber-jar | 96–100% of the limit (0.5.242) | anyone launching the desktop client |
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
wheels were 67–74% of it; the bundled FFmpeg for calls took them to 96–100% by
0.5.242, whose first macOS x86-64 wheel was 194,232 bytes over. Staging
re-deflates each jar at level 9 (`stage_artifacts.py`, `recompress_jar`): about
0.5% smaller, every entry byte-identical. That was one release of headroom, not
a budget. From 0.5.243 the FFmpeg natives (18.6–24.4 MiB per OS) are not in
the wheel: ciris-client depends on `ciris-holon>=0.1,<0.2`, which carries the
bytedeco jars byte-identical, and the uber-jar's manifest `Class-Path` names
them at `../../ciris_holon/media/jars/`, beside this package in site-packages.
`java -jar` is unchanged. `packaging/check_holon_classpath.py` checks the
installed layout and digests on every OS; `packaging/check_pins.py` checks the
range carries the FFmpeg the build names. ProGuard would cut most of the jar and is
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
there. `repair` corrects what the evaluation rejected. `translate` fills only what is
missing: a selected key that already has a value is kept, reported, and fails
the run, because an existing value may be a hand-written overrule. Replace
existing values with `--overwrite`, or review them against changed English with
`--lane evaluate`. A run enters at one lane
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
| `packaging/check_csd_model.py --self-test` · `--verify-pin` · (gate) | does every card's `csd:model` block agree with the pinned CIRISConstitution model? Each cited variable's states must equal the model's. Each cited act's roles, policy and confirm must equal `transitions.json`. Each modelled state must be matched in the named code or declared as a code gap. `--upstream` (daily, `model-drift.yml`) asks whether the pin is behind; `--vendor <commit>` re-pins; `--self-test` plants 12 defects | seconds |
| `packaging/check_csd_code_gaps.py --self-test` · (gate) | is every `csd:code_gaps` entry still true of the tree? A gap whose pattern says it has closed fails until the entry is deleted, and an entry pointing at no file fails. `--list` prints every gap; `--self-test` plants 11 defects | seconds |
| `packaging/gen_csd_roster.py --check` | is `FSD/CSD/README.md` — every card number, adopted, reserved (`FSD/CSD/numbers.tsv`) or retired — current, and does no card sit on a reserved or retired number? | seconds |
| `packaging/check_csd5.py --self-test` · `packaging/check_csd5.py` | for each card that carries `csd:inputs` / `csd:view` / `csd:acts` (`FSD/CSD5.md`; today CSD-070): does every reachable state hold the twelve laws of `packaging/csd5/ui_primitives.json`, is every tag literal in the card's Kotlin accounted for, and is `formal/csd5/<card>.tla` what the card generates? For the whole client: has any tag appeared under a prefix no primitive owns, or any disabled Action that draws no reason (`packaging/csd5/baseline.json`, which can only fall)? The model reads the card, not the Kotlin. `--self-test` plants 30 defects and requires each to go red; `--tlc tla2tools.jar` also runs TLC on the committed module and requires the explorer's state count and no violated invariant (with `--self-test`, five more plants that TLC must find); `--tags CSD-NNN` prints old tag → derived tag | ~60 s · ~25 s (TLC adds ~5 s a module) |

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
