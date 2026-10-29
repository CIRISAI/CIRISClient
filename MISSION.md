# MISSION — CIRISClient

> Mission Driven Development (MDD): the FSD names *what* we build; this
> document names *why*. Methodology:
> [`~/CIRISAgent/FSD/MISSION_DRIVEN_DEVELOPMENT.md`](../CIRISAgent/FSD/MISSION_DRIVEN_DEVELOPMENT.md)
> and the overview at [ciris.ai/mdd](https://ciris.ai/mdd).
> This repo's FSD: [`FSD/ONE_CLIENT_N_NODES.md`](FSD/ONE_CLIENT_N_NODES.md);
> the parent FSD remains
> [CIRISConformance#86](https://github.com/CIRISAI/CIRISConformance/issues/86).

**The client's build contract, and the client.** This repo holds the KMP client
source and the gates that say whether the requisites for building it are in
place.

**Status**: The client is built here and published to PyPI as one distribution,
`ciris-client` (plus `ciris-client-wasm` for the browser bundle); the Android
AAR and iOS XCFramework are release assets. CIRISServer and CIRISAgent depend on
it and carry no copy of the source. `client/`'s recorded state is the digest in
`client/VENDORING.md` §1.
**Identifiers**: `ciris-client` (the client) and, in the same distribution, the
readiness gates — importable as `readiness`, run as `python -m readiness`, with
the gate framework from [CIRISGrace](../CIRISGrace) as an optional dependency so
`pip install ciris-client` does not require it.

---

## 1. MISSION (WHY)

Meta-Goal M-1: *promote sustainable adaptive coherence — the living conditions
under which diverse sentient beings may pursue their own flourishing in justice
and wonder.*

The client is the surface where a person meets the mesh. Everything the
substrate proves — signed identity, verifiable audit, admission that answers to
a human — arrives as nothing unless the client that renders it is itself
trustworthy to build.

That takes every requisite of the build having a source, not a person. Each
requisite below has a producer, a consumer and a check, except the last:

- **One source tree.** The client is built here and installed as `ciris-client`;
  no consumer carries a copy.
- **One version.** `CLIENT_VERSION` is a build input derived from `VERSION`.
- **One build.** Which surfaces are offered is decided at runtime from the probed
  `ClientMode` (CIRISServer#479), not by a build constant.
- **Locale bundles.** The four runtime bundles are kept identical by a check in
  this repo's CI.
- **The API surface** the client is judged against is still a file the client
  owns; that requisite is declared, not yet checked (`generated-api-drift`).

**The constraint this repo exists to enforce:** every requisite for building
the client must have a named producer, a named consumer, and a check. Where a
check does not exist yet, it is declared `unimplemented` — never assumed.

## 2. The composition contract (WHO / WHAT / HOW)

**WHO — protocols.** The gates read a client tree, a node's served OpenAPI spec
(when given `--node`), PyPI, and GitHub issue state. They write a
`ciris-readiness/v1` report and change nothing they read. The package reads the
Gradle build's output and writes wheels; it never compiles Kotlin inside pip,
because a consumer should not need a JDK and an Android SDK to install a
client.

**WHAT — schemas.** The requisite classes from
[CIRISConformance#86](https://github.com/CIRISAI/CIRISConformance/issues/86) §4:
*code* (wheels, substrate binaries, `generated-api`), *data* (spec, locale
bundle and its Merkle root, templates), *normative* (CC version, wire
vocabulary, the `SubstrateGate` registry). A gate belongs to exactly one class.

**HOW — logic.** One question per gate. A gate that cannot ask its question
says so. Heuristic gates (`surface-binding`) mark themselves as heuristic in
the report so a worklist is never mistaken for a verdict.

## 3. Mission alignment per gate

| Gate | Class | How it advances M-1 |
|---|---|---|
| `version-alignment` | code | The banner a user sees must reflect the node they actually have — read from `VERSION` in this tree, from the `ClientMode.kt` const in a consumer's copy, because a gate that works on one tree is not done |
| `locale-parity` | data | Thirty languages are thirty audiences; a stale bundle ships raw keys to one of them |
| `spec-drift` | data | The contract must come from the node, not from the client's copy of it |
| `surface-binding` | data | Turns a silently unimplemented endpoint into a counted gap |
| `nav-gate-registry` | normative | A surface still gated on a closed issue is a capability withheld for no reason |
| `substrate-binaries` | code | An artifact missing its substrate is an artifact that fails on a user's device, not in CI |
| `toolchain` | code | Fails at the start of the day rather than twenty minutes into a build |
| `compat-matrix` | normative | "Which client works with which node, and what does it do when they mismatch" must be a published, validated record — not support folklore (`compat/matrix.json`, FSD §6). The record and the code must AGREE: the matrix's `node_min` and the client's `MIN_NODE_VERSION` are one fact written twice, and a banner reading a different floor than the record publishes is folklore with a version number |
| `test-vm-stop` (`client/tools/check_test_vm_stop.py`) | code | A test that leaves a view model running past `resetMain()` fails a DIFFERENT test, on a slow runner only, as `UncaughtExceptionsBeforeTest` — red that names the wrong file is red people learn to re-run, and a re-run suite stops being evidence. Stopping and joining every model before Main is reset keeps a failure in the test that caused it |
| `colour-literals` (`client/tools/check_colour_literals.py`) | code | A colour named in a screen is a colour that fails in the other ground for one audience and nobody checks; sixteen tokens read by name is what makes both grounds one product |
| `dimension-table` (`client/tools/gen_dimension_table.py --check`) | normative | A family the UI renders that the registry does not know admits under an authority nobody chose (CC 3.1.7 R2); generating the table from the pinned registry is what makes that a build failure instead of a review note. It also recomputes the registry's `registry_sha256` (CIRISConstitution#112) and refuses one that misstates it |
| `glyph-table` (`client/tools/gen_glyphs.py --check`) | code | Seventy-one glyphs drawn once, from the design's path data; a hand-edited copy is the drift the generator exists to prevent |
| `csd-registry` (`packaging/check_csd_v3.py`) | normative | A CSD that names a family the pinned registry lacks is a contract about vocabulary nobody ratified. Each CSD pins `_meta.registry_sha256` (the grammar hash, rc6+; `source_sha256` for older registries), and a registry that misstates its own pin is refused |
| `csd-routes` (`packaging/check_csd_routes.py`) | normative | A route a screen calls that no CSD on that screen cites is a contract nobody wrote down, and two screens mutating the same route are one card twice or one act with two doors — a decision either way, not a drift. The closure from `CIRISApp.kt` through composables and view models to `CIRISApiClient` is heuristic and says so; a `reset*`/`clear*`/`forget*`/`dismiss*` call on an app-scoped model the caller did not construct reaches none of that model's routes and charges none of its init-time reads to the caller, so a logout arm is not billed for the revoke probe. A baseline that can only fall |
| `csd4-tests` (`packaging/check_csd4.py`, run per card by `check_csd_v3.py`) | normative | A card's tests and the server's `requirements.yaml` are one fact written twice; a test whose atoms no catalogue entry holds at one instant cannot be scheduled, and the plan reports it `uncovered` at run time instead of at review. The catalogue (T0–T19 and the pinned version's later entries, T20 reserved) and its vocabulary are pinned in `packaging/csd4/` with sha256s, and "at one instant" is the server model's own trace, not a client re-derivation. Cards without a `csd:tests` block are untouched |
| `csd-roster` (`packaging/gen_csd_roster.py --check`) | normative | A card number held only in an issue or another repo is a number the next card can take. The roster is generated from the cards plus `FSD/CSD/numbers.tsv`; a card on a reserved number fails until its row is deleted in the same commit, and a retired number is never reused. |

## 4. Dependencies & gating

- **[CIRISGrace](../CIRISGrace)** — supplies the gate framework and the report
  envelope. This repo owns only its own questions.
- **CIRISConformance** — owns what "conforming" means. When the `client` lane
  exists (#86 §2), these gates become its pre-flight, not its replacement.
- **CIRISServer / CIRISAgent** — the two consumers. Both depend on
  `ciris-client`; the compatible versions are recorded per release in
  `compat/matrix.json`.
- **CIRISVerify / CIRISServer releases** — supply the substrate binaries this
  repo deliberately does not vendor. A device build re-hydrates them from those
  releases (`client/VENDORING.md` §2); this repo owns the client, not the
  substrate it drives.

## 5. Decisions and open questions

1. **Extraction.** The module boundary is **the whole Gradle build, minus the
   substrate**: `shared`, `generated-api` and all three platform shells are
   here; the prebuilt `ciris-server` wheels, `ciris-verify` FFI libraries,
   xcframeworks and iOS Resources tree are not (`client/VENDORING.md` §2). The
   shells belong inside the boundary because they are where the locale bundles
   are assembled; a boundary at `shared` would leave that drift in the consumers.

2. **Flavor.** There is none. An agent is a node with a brain added, so the
   agent surfaces are gated at runtime on the probed `ClientMode`
   (CIRISServer#479): one distribution ships, and a node upgraded with a brain
   shows them without reinstalling. See FSD §4.

3. **Locale root.** When the client's bundle is checked against the substrate's
   signed locale Merkle root, `locale-parity`'s byte-identity half retires. The
   CC-vs-impl domain drift (`v2`/`locale` versus the shipped `v1`/`lang_code`)
   has to close first.

4. **The sixth mirror.** Upstream guards six `en.json` mirrors; two are outside
   this repo — the agent's server-side prompt bundle, and the copy inside the
   un-vendored iOS substrate. Whether the agent's prompt bundle should share the
   client's UI bundle at all, or is a separate corpus that merely looks alike,
   is unanswered. Recorded as an obligation, not assumed either way.

## 6. References

- [CIRISConformance#86](https://github.com/CIRISAI/CIRISConformance/issues/86) — the FSD
- [CIRISGrace](../CIRISGrace) — the framework and the substrate half
- [ciris.ai/mdd](https://ciris.ai/mdd) — the methodology

## Update cadence

On any change to the requisite list or to what a gate asks.
