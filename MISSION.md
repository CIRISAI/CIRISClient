# MISSION — CIRISClient

> Mission Driven Development (MDD): the FSD names *what* we build; this
> document names *why*. Methodology:
> [`~/CIRISAgent/FSD/MISSION_DRIVEN_DEVELOPMENT.md`](../CIRISAgent/FSD/MISSION_DRIVEN_DEVELOPMENT.md)
> and the overview at [ciris.ai/mdd](https://ciris.ai/mdd).
> This repo's FSD: [`FSD/ONE_CLIENT_N_NODES.md`](FSD/ONE_CLIENT_N_NODES.md);
> the parent FSD remains
> [CIRISConformance#86](https://github.com/CIRISAI/CIRISConformance/issues/86).

**The client's build contract, and the client.** The KMP client lived vendored
in two repositories at once. This repo holds the gates that say whether the
requisites for building it are in place — and, since the gates reported, the
source itself.

**Status**: Source extracted. `client/` is vendored from CIRISAgent@6083bdf
with its provenance and every local delta in `client/VENDORING.md`; the default
`--client-tree` is that tree. Both flavors are buildable from one source
(§5.2). **The extraction is not finished until both consumers depend on the
package and delete their copies** — until then this repo is a third tree, which
is the cost, tracked as a row in `evidence/blocked_upstream.tsv`.
**Identifiers**: `ciris-client` (the client, published as
`ciris-client[node]` / `ciris-client[agent]`) and, in the same distribution,
the readiness gates — still importable as `readiness`, still run as
`python -m readiness`, with the gate framework from
[CIRISGrace](../CIRISGrace) as an optional dependency so
`pip install ciris-client` does not require it.
**Last updated**: 2026-08-20 (client extracted; §5.1 and §5.2 answered).

---

## 1. MISSION (WHY)

Meta-Goal M-1: *promote sustainable adaptive coherence — the living conditions
under which diverse sentient beings may pursue their own flourishing in justice
and wonder.*

The client is the surface where a person meets the mesh. Everything the
substrate proves — signed identity, verifiable audit, admission that answers to
a human — arrives as nothing unless the client that renders it is itself
trustworthy to build.

Today it is not, in a specific and fixable sense. The same ~200k lines exist in
two repositories, kept aligned by hand. One integer is enforced in three
places by a script. Thirty locales are held in four byte-identical committed
copies. The API surface the client is judged against is a file the client
itself owns. None of these are correctness bugs; all of them are places where
a human is the transport, and where an error is silent rather than loud.

Three of those four now have a producer, a consumer and a check rather than a
person: `CLIENT_VERSION` is a build input derived from one source, `HAS_AGENT`
turned out not to be a build question at all and was deleted in favour of the
probed `ClientMode` (CIRISServer#479), and the locale mirrors are guarded in
this repo's CI rather than in one consumer's. The fourth — the API surface — is unchanged and still
tracked. And the first of them, the two hand-aligned copies, is only half
closed: the source is here, but both consumers still carry theirs. A third
copy is worse than two, so that row in the manifest is the one that matters.

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

## 4. Dependencies & gating

- **[CIRISGrace](../CIRISGrace)** — supplies the gate framework and the report
  envelope. This repo owns only its own questions.
- **CIRISConformance** — owns what "conforming" means. When the `client` lane
  lands (#86 §2), these gates become its pre-flight, not its replacement.
- **CIRISServer / CIRISAgent** — the two consumers. Each still holds its own
  copy of the client source; both are read, neither is written. The extraction
  is finished when they depend on `ciris-client[node]` / `ciris-client[agent]`
  and delete those copies, and not before.
- **CIRISVerify / CIRISServer releases** — supply the substrate binaries this
  repo deliberately does not vendor. A device build re-hydrates them from those
  releases (`client/VENDORING.md` §2); this repo owns the client, not the
  substrate it drives.

## 5. Open questions

1. **Extraction — ANSWERED 2026-08-20.** The source moved here. The module
   boundary is **the whole Gradle build, minus the substrate**: `shared`,
   `generated-api` and all three platform shells are vendored; the prebuilt
   `ciris-server` wheels, `ciris-verify` FFI libraries, xcframeworks and iOS
   Resources tree are not (`client/VENDORING.md` §2 — 2537 files, ~235 MB).

   Shipping only `shared` + `generated-api` was the tidier boundary and is the
   wrong one: the shells are where `HAS_AGENT` and the locale bundles land, so
   cutting there would have left the two drifts that motivated this in the
   consumers. What the gates reported, and what decided it, is that the shells
   reach OUT — `desktopApp` and `androidApp` sync localization from a path
   outside `client/`, and `iosApp`'s Xcode phase rsyncs `ciris_engine` and
   `ciris_adapters` from the agent repo root. Those references are the real
   boundary, and each one is now either removed (§5 of VENDORING.md) or recorded.

2. **Flavor — ANSWERED 2026-08-20, then RETIRED 2026-08-24.** The first
   answer was one source and two variants selected by `-PhasAgent`, shipped as
   two Python distributions because PyPI's per-file limit forces the split.
   The better answer, which CIRISServer#479 forced, is that there is no flavor:
   an agent IS a node that has had a brain added, so the agent surfaces are
   gated at RUNTIME on the probed `ClientMode`. ONE distribution ships, a node
   upgraded with a brain reveals them without reinstalling, and the constant
   `-PhasAgent` selected is deleted rather than deprecated — a build constant
   nothing reads is how the next compile-time branch gets written. See FSD §4.

3. **Locale root.** When the client's bundle is checked against the substrate's
   signed locale Merkle root, `locale-parity`'s byte-identity half retires. The
   CC-vs-impl domain drift (`v2`/`locale` versus the shipped `v1`/`lang_code`)
   has to close first.

4. **The sixth mirror.** Upstream guards six `en.json` mirrors; two are outside
   this repo — the agent's server-side prompt bundle, and the copy inside the
   un-vendored iOS substrate. Whether the agent's prompt bundle should share the
   client's UI bundle at all, or is a separate corpus that merely looks alike,
   is unanswered. Recorded as an obligation, not assumed either way.

5. **Publication.** Nothing is on PyPI. The wheels are CI artifacts, and the
   consumption contract is real code that has been installed and exercised, but
   the name `ciris-client` is unclaimed.

## 6. References

- [CIRISConformance#86](https://github.com/CIRISAI/CIRISConformance/issues/86) — the FSD
- [CIRISGrace](../CIRISGrace) — the framework and the substrate half
- [ciris.ai/mdd](https://ciris.ai/mdd) — the methodology

## Update cadence

On any change to the requisite list or to what a gate asks.
