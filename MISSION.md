# MISSION — CIRISClient

> Mission Driven Development (MDD): the FSD names *what* we build; this
> document names *why*. Methodology:
> [`~/CIRISAgent/FSD/MISSION_DRIVEN_DEVELOPMENT.md`](../CIRISAgent/FSD/MISSION_DRIVEN_DEVELOPMENT.md)
> and the overview at [ciris.ai/mdd](https://ciris.ai/mdd).

**The client's build contract.** The KMP client currently lives vendored in two
repositories at once. This repo holds the gates that say whether the requisites
for building it are actually in place — before, and as a precondition of, any
extraction of the source itself.

**Status**: Gates only. **No client source here yet.** The tree under test is
`CIRISServer/client` (mirrored in `CIRISAgent/client`), given by
`--client-tree`. Extraction is deliberately deferred — see §5.
**Identifier**: `ciris-client-readiness` (Python; the gate framework comes from
[CIRISGrace](../CIRISGrace)).
**Last updated**: 2026-08-16 (repo created).

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

**The constraint this repo exists to enforce:** every requisite for building
the client must have a named producer, a named consumer, and a check. Where a
check does not exist yet, it is declared `unimplemented` — never assumed.

## 2. The composition contract (WHO / WHAT / HOW)

**WHO — protocols.** Reads a client tree, a node's served OpenAPI spec (when
given `--node`), PyPI, and GitHub issue state. Writes a `ciris-readiness/v1`
report. It changes nothing it reads.

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
| `version-alignment` | code | The banner a user sees must reflect the node they actually have |
| `locale-parity` | data | Thirty languages are thirty audiences; a stale bundle ships raw keys to one of them |
| `spec-drift` | data | The contract must come from the node, not from the client's copy of it |
| `surface-binding` | data | Turns a silently unimplemented endpoint into a counted gap |
| `nav-gate-registry` | normative | A surface still gated on a closed issue is a capability withheld for no reason |
| `substrate-binaries` | code | An artifact missing its substrate is an artifact that fails on a user's device, not in CI |
| `toolchain` | code | Fails at the start of the day rather than twenty minutes into a build |

## 4. Dependencies & gating

- **[CIRISGrace](../CIRISGrace)** — supplies the gate framework and the report
  envelope. This repo owns only its own questions.
- **CIRISConformance** — owns what "conforming" means. When the `client` lane
  lands (#86 §2), these gates become its pre-flight, not its replacement.
- **CIRISServer / CIRISAgent** — hold the client source today. Both are read;
  neither is written.

## 5. Open questions

1. **Extraction.** Whether the source moves here, and with which module
   boundary (`shared` + `generated-api` only, versus the platform shells), is
   deliberately unanswered. The gates should report what the two trees actually
   support first; deciding before that is guessing.
2. **Flavor.** Two Maven variants (`-node`, `-agent`) compiled from one source
   with different `-PhasAgent` is the current intent — it preserves the
   dead-code elimination that keeps agent surfaces out of the node build.
3. **Locale root.** When the client's bundle is checked against the substrate's
   signed locale Merkle root, `locale-parity`'s byte-identity half retires. The
   CC-vs-impl domain drift (`v2`/`locale` versus the shipped `v1`/`lang_code`)
   has to close first.

## 6. References

- [CIRISConformance#86](https://github.com/CIRISAI/CIRISConformance/issues/86) — the FSD
- [CIRISGrace](../CIRISGrace) — the framework and the substrate half
- [ciris.ai/mdd](https://ciris.ai/mdd) — the methodology

## Update cadence

On any change to the requisite list or to what a gate asks.
