# CSD-B — the Buildable Profile

**Profile id**: `buildable`
**Profiles**: `FSD/CSD_STANDARD.md` v1.0 (CIRISAgent, `release/2.11.0`)
**Status**: Draft
**Date**: 2026-09-08
**Namespace registry**: CIRISConstitution `manifests/namespace_registry.json` @ `rc5`
(`cc_version: 1.0-rc5`, `source_sha256: 87aede5012064288fd5ce8770d3e77a8c5131cd61d27799c4c06558507b9a9f5`)

## 0. What this is, and what it is not

A **CIRIS Specification Document (CSD)** is Mission Driven Development applied to
one user-facing surface: MISSION / SCHEMAS / PROTOCOLS / LOGIC for a single
screen, plus a flow in the UI DSL that a machine executes to prove the surface
does what the document says. That standard exists, it is good, and this file does
**not** replace it. `FSD/CSD_STANDARD.md` in CIRISAgent remains the normative
definition of §1–§6, the DSL, and the `check_csd.py` flow-identity rule.

This is a **conformance profile** over it: two additional required sections and
one reversed arrow. A CSD that satisfies the parent standard and both sections
below is a **CSD-B**.

A profile rather than a fork, for the reason this repo states in AGENTS.md — *do
not add a second source for anything that already has one* — and for the reason
OASIS gives for conformance clauses: a clause that strengthens a normative
statement must refer to the original and say plainly that it is strengthening
it.[^oasis] Everything not named here is inherited unchanged. The acronym, the
numbering (`CSD-001`…), the `Flow` line and the byte-identity rule are all the
parent's, deliberately, so a CSD-B is still a CSD and `check_csd.py` still runs.

### The reversed arrow

The parent standard is written **downstream of an implementation**. §6 step 3:

> Fill §2 from the client PR's tag list. **Do not add tags the PR does not name.**

and §6 step 4 offers the literal word `unconfirmed` where a contract is unknown.
Both are correct for documenting a surface someone has already built, and both
make it impossible to *design* one. The goal of this profile is a document a team
can implement **from** — mission, surface, sample outputs, namespaces and flow
decided before the first Kotlin file — so `guaranteed by` in §2 stops citing a PR
that already exists and starts naming the PR that will satisfy it.

The parent's caution survives intact and is the reason §2a exists in the shape it
does: *a guessed tag is the #39 defect in its purest form*. Designing forward is
not licence to guess. It is a promotion of the guess to a **commitment**, written
down, with a checker that fails when the implementation and the commitment
disagree. `unconfirmed` stays available and stays honest.

## 1. Prior and convergent art

This profile is not novel and should not pretend to be. What follows is what it
borrows and, more usefully, where each source stops.

**Specification by Example / Gherkin.** One artifact that is simultaneously the
requirement and the test — the parent standard's core idea, and its
`requires`/`do`/`expect` is Given/When/Then in another coat. The well-documented
failure is over-specification: scenarios that encode interactions rather than
behaviour multiply and break on every UI change, and practitioners converge on
keeping interface-specific terms *out* of the spec.[^gherkin][^gherkin2] The
parent standard already resists this by making the runner, not the flow, own the
navigation hop. **Where it stops:** Gherkin has no vocabulary for *what a value
looks like*. `Then I see the capacity card` is as much as it can say.

**Approval / golden-master testing.** The established answer to "assert a complex
output without predicting it": capture a blessed rendering and diff.[^approval]
The known weakness is decisive here — golden tests verify *consistency, not
correctness*; a flawed baseline keeps passing.[^golden] So §2a takes the *idea*
of a concrete expected output and rejects the mechanism: a CSD-B writes its
examples **by hand, before the code**, where they are a claim someone can be
wrong about, rather than capturing whatever the implementation happened to emit.

**Spec-Driven Development (2025–26).** The now-mainstream position that a
version-controlled, executable spec is the source of truth and code is derived
from it, across GitHub Spec Kit, Kiro, OpenSpec and others.[^sdd][^sdd2] Research
systems push further into dual specs — architecture and behaviour compiled from
one feature description.[^codespec] **Where it stops:** SDD specs are generally
bound to a *codebase*, not to an external normative vocabulary. Nothing in that
lineage answers "which of these strings am I allowed to emit."

**Controlled vocabularies and conformance clauses.** 1EdTech VDEX and Dublin
Core-style term registries make specific fields normative — term IRI, definition,
usage — and define conformance against them.[^vdex][^dcterms] This is the direct
model for §3a: the CIRISConstitution namespace registry already *is* such a
registry (116 families, `reserved` flags, `reserved_rule`, owning component), and
a UI spec that renders constitutional values should cite it the way a metadata
profile cites its vocabulary.

**Design by Contract.** Meyer's contracts between software elements are the
ancestor of §3's owner column.[^contract] The registry's `owning_repo` turns that
column from an assertion into a lookup.

**The gap this profile fills.** None of the above binds *sample rendered output*
and *normative vocabulary* into the same document as the executable flow. That
combination is the whole point: it is what lets a designer write a card, a
developer build it, and a gate check it, from one file.

## 2a. Sample output (REQUIRED)

> **Strengthens** parent §2 "Collects / shows", which specifies `field | source |
> required` and no more. That is enough to test that *something* rendered and not
> enough to build anything.

Today CSD-004's entire data specification is one row:

| field | source (§3) | required |
|---|---|---|
| capacity attestations | capacity read | shows |

A developer cannot build a card from that: not the field names, not the value
types, not what "none yet" looks like. Worse, CSD-004's own mission forbids
exactly the failure this vagueness invites — *"a placeholder that looks like data
is the metric-dishonesty MDD's anti-Goodhart measures name"* — while giving no
way to say what the real thing renders as.

### 2a.1 Required content

For every field in §2 "Collects / shows", a CSD-B gives:

| column | meaning | rule |
|---|---|---|
| `field` | the same name used in §2 | must match §2 exactly |
| `type` | `string` · `int` · `float` · `bool` · `enum[…]` · `list[…]` · `timestamp` | one of these; a new type is a change to this profile |
| `example` | a **literal** value as it would arrive | real-shaped, not `foo`; a redacted real value beats an invented one |
| `renders as` | what the user sees for that example | the sentence a designer would write |
| `tag` | the §2 tag this value appears in | must be a tag §2 declares |

### 2a.2 The three states, all required

A field table with only a happy path is incomplete, and the omission is where
safety surfaces fail quietly. Every CSD-B states, per surface:

* **empty** — the value is legitimately absent. *What is on screen?* "Nothing
  yet" and "we could not ask" are different and must render differently.
* **loading** — the read is in flight.
* **error** — the read failed. **Naming the failure is mandatory**: a surface
  that renders an error as an empty state is the metric-dishonesty case in its
  most literal form, and CSD-003 says why in its mission — *a safety surface that
  silently fails to render is worse than one that is absent, because absence is
  at least visible.*

### 2a.3 What the flow must then assert

§2a is not decoration; it is a promise §4 has to keep. Each of the three states
gets either:

* a step in the flow that asserts it, or
* a line in §5's **"Untested and must be established"** saying it does not.

A CSD-B whose §2a lists an error state that neither §4 asserts nor §5 disclaims
fails the checker.

**Known limitation, stated rather than hidden.** The parent DSL's strongest
value assertion is `text: {tag: substr}` — substring containment. It cannot check
a type, a count, or a range, so today §4 can assert *"the card says
`sustained_coherence`"* but not *"six capacities each with a score in 0..1"*.
§2a is therefore currently richer than §4 can verify. That is a deliberate,
temporary asymmetry: the specification is the thing worth having first, and the
DSL extension (`count:`, `matches:`, `each:`) is a change to the parent standard
that must be proposed there, not smuggled in here. Until it lands, the gap goes
in §5.

## 3a. Namespaces (REQUIRED)

> **Adds to** parent §3 "Contracts", which names endpoints and owners. It does not
> name the constitutional vocabulary those endpoints carry.

**Measured, in the current corpus:** across `CSD_STANDARD.md` and all four CSDs
there is **one** CC reference — `CC 4.2`, in CSD-003 §1, in prose, wrapped across
a line break — and **zero** namespace prefixes. Meanwhile CSD-004 renders
"capacity attestations" and records its contract as `unconfirmed`, while the RC5
registry already defines its six families normatively.

### 3a.1 The table

| column | meaning |
|---|---|
| `prefix` | the family exactly as the registry spells it, e.g. `capacity:composite` |
| `CC` | `cc_section` from the registry, e.g. `3.1.8.1` |
| `use` | `read` · `display-only` · `emit` (below) |
| `why` | one clause: what this surface does with the value |

### 3a.2 `use`, and why it is three values

* **`read`** — the surface fetches values in this family.
* **`display-only`** — it renders them and cannot originate them. The honest
  answer for most cards, and distinct from `read` because it is a *promise not to
  write*.
* **`emit`** — the surface can cause a value in this family to be written.

`emit` is checkable and must be checked. 35 of the 116 families are `reserved`
and carry a `reserved_rule` naming who may emit — `accord:*` is
`accord_holder-only` per CC 3.4.1, and all six `capacity:*` families are reserved
to `lens`. **A CSD-B claiming `emit` on a reserved family it does not own is a
checker failure, not a review comment.**

### 3a.3 Rules

1. Every prefix must exist in the pinned registry, or carry the
   `x_private:` prefix the registry reserves for exactly this
   (`_meta.private_use_prefix`). An unregistered bare prefix fails the load.
2. The registry is **pinned by `source_sha256`** in the CSD header, as the
   registry pins its own source. A registry bump is a deliberate edit, not a
   silent drift — the same argument the parent makes for embedding the flow
   rather than linking it.
3. `owning_component` / `owning_repo` come from the registry, never from the
   author. This is what turns §3's owner column from an assertion into a lookup.
4. A surface that renders **no** constitutional values writes `none` and says
   why. An empty §3a and an absent §3a are different, and the parent standard's
   own instinct applies: absent means false, so absence must be impossible to
   reach by accident.

## 4. Checker requirements

`check_csd.py` gains, in the same spirit as its existing flow-identity diff:

* §2a exists, and every `field` matches a §2 field and every `tag` a §2 tag;
* the three states are all present for every surface;
* each state is asserted in §4 or disclaimed in §5;
* §3a exists; every prefix resolves in the pinned registry or is `x_private:`;
* the header's `source_sha256` matches the registry it validated against;
* no `emit` on a `reserved` family whose `reserved_rule` excludes this component.

Each is a load error, not a convention — the parent's rule, for the parent's
reason: *a spec with a typo'd key would otherwise assert nothing and pass.*

## 5. Worked example

`FSD/CSD/CSD-004-capacity-attestations.md` in this repo is CSD-004 retrofitted to
this profile. It was chosen because it fails both gaps at once: its data spec is a
single row, and its contract is `unconfirmed` while the registry already names its
six capacities.

## 6. Status and what must go upstream

This profile is **drafted here because #45's surfaces are ours**, and it is not
finished until:

1. §2a and §3a are proposed into `FSD/CSD_STANDARD.md` in CIRISAgent, where the
   parent standard and `check_csd.py` live. Two standards for one artifact is the
   drift this repo exists to measure.
2. The DSL gains the value assertions §2a currently outruns (`count:`, `matches:`,
   `each:`), or §2a's scope is cut to what the DSL can check. The asymmetry is
   acceptable as a draft and not as a steady state.
3. The retrofitted CSD-004's `unconfirmed` contract is resolved with CIRISServer /
   CIRISLensCore — the registry names the families, but not the route that serves
   them.

---

[^oasis]: OASIS, *Guidelines to Writing Conformance Clauses* — https://docs.oasis-open.org/templates/TCHandbook/ConformanceGuidelines.html
[^gherkin]: *Why Gherkin (Cucumber, SpecFlow…) Always Failed with UI Test Automation* — https://medium.com/swlh/why-gerkin-cucumber-specflow-always-failed-with-ui-test-automation-c85a8030c07d
[^gherkin2]: *Writing Great Specifications: Using Specification by Example and Gherkin* — https://livebook.manning.com/book/writing-great-specifications/chapter-1/v-12/
[^approval]: ApprovalTests — https://github.com/approvals/ApprovalTests.Python
[^golden]: *Golden Tests in AI: Ensuring Reliability Without Slowing Innovation* — https://tullie.ai/blog/golden-tests-in-ai
[^sdd]: *Spec-Driven Development in 2026: What It Is, the Tooling, and How Teams Actually Use It* — https://dev.to/krlz/spec-driven-development-in-2026-what-it-is-the-tooling-and-how-teams-actually-use-it-2fk2
[^sdd2]: *Spec-Driven Development (SDD): The Definitive 2026 Guide* — https://www.thebcms.com/blog/spec-driven-development/
[^codespec]: *CodeSpec: Dual Executable Specifications for Agentic Long-Horizon Feature Development* — https://arxiv.org/html/2607.26777v1
[^vdex]: 1EdTech *VDEX Conformance Requirements v1.0* — https://www.imsglobal.org/vdex/vdexv1p0/imsvdex_confv1p0.html
[^dcterms]: Audiovisual Core, *Controlled Vocabulary for Dublin Core format* — https://ac.tdwg.org/format/
[^contract]: OASIS conformance guidelines, on "contract" and programming-by-contract (Meyer) — https://docs.oasis-open.org/templates/TCHandbook/ConformanceGuidelines.html
