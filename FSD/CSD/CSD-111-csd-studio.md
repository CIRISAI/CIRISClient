# CSD-111 — CSD Studio (explore the CSDs you have, draft the one you need)

**CSD**: CSD-111 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: steward direction 2026-10-18 (CIRISClient#205: a CSD generator combining LLMs with rust/wasm, evaluating a use case against the CSD corpus and the CSD/4 catalogue)
**Pairs with**: CSD-042 (Help, the page this card sits on) · every CSD in `FSD/CSD/` (the corpus it reads)
**Flow**: unwritten. The tags below are the contract the card agents build to

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person describes what they want to do in plain words and sees, in this order: which existing CSDs already cover it and how far along each is; what is not covered, named as states the CSD/4 catalogue knows or lacks and as CEG families the registry knows or lacks; and a draft CSD/3 document for the uncovered part whose every typed block already passes the client's own gate. Where the thing cannot be said in the vocabulary that exists, the card produces a request for a catalogue version bump or a namespace family, never an invented state or family.**

Serves **Integrity** through CC 3.1.7 R1 and R2: a family exists when the registry has a row for it, and a row is admitted only under an authority somebody chose, so a tool that helps people write specifications must not be a way to mint vocabulary. It serves **Transparency** through CSD/3 itself: the specification and its test are one document, so a draft that passes the gate is a claim anyone can check. The reason the deterministic half is rust compiled to wasm is CC 2.6.1.1.1's byte-identity target: the namespace matcher, the signed-object CDDL and vectors (CC 2.6.1.7), the claims resolver and the CSD/3 block validator are one implementation that runs the same in the browser, the app and CI. The language model does only what is not decidable: reading the use case, choosing among candidates, writing prose. The CSD/4 planner stays the server's single implementation (`harness.native`); the card consumes its output and never re-implements it.

It lives in **Help** (decided for the sketch; CIRISClient#205 may move it to a developer area), as a card below the help index, because its first users are the people who write cards. It is not in any circle, and it reads nothing of anyone's content: a use case is text the person types here.

## 2. Surface (what)

```yaml csd:surface
surface: help
screen: Help
```

A card, `proposed:card_csd_studio`, below the help index on the page `nav_map` derives for `Help`.

**Four steps, always in this order, each visible before the next starts.** `proposed:input_use_case` takes the use case. `proposed:list_matching_csds` shows the CSDs that cover it, each with its stage and the fields that matched. `proposed:list_gaps` shows what is uncovered as two lists, atoms and families, each row marked *known* or *missing*. `proposed:text_draft_csd` shows the draft document, with `proposed:text_gate_verdict` above it naming the gate result per block, and `proposed:btn_request_bump` appears only when a gap row is *missing*.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:use_case_text
    use: emit
    type: string
    example: "A parent wants to see which of their child's contacts are vouched for by someone the household trusts"
    renders: "the text field, kept on this device; sent to a language model only where the person has chosen one (CIRISClient#205 decision c), and never to the network"
    tag: "proposed:input_use_case"
  - ceg: x_private:matching_csds
    use: display-only
    type: "list[string]"
    example: "[\"CSD-100 Household (building)\", \"CSD-104 Verify someone's key (building)\", \"CSD-046 Trust graph (building)\"]"
    renders: "one row per covering CSD: id, title, stage, and the ceg fields that matched; tapping opens the document"
    tag: "proposed:list_matching_csds"
    assert: count
  - ceg: x_private:gap_atoms
    use: display-only
    type: "list[string]"
    example: "[\"member:known\", \"guardian.bound:known\", \"contact.vouched_by_household:missing\"]"
    renders: "the CSD/4 atoms the use case needs, each marked known (in the catalogue at its catalogue_version) or missing (a bump request)"
    tag: "proposed:list_gap_atoms"
    assert: each
  - ceg: x_private:gap_families
    use: display-only
    type: "list[string]"
    example: "[\"ownership:{relation}:{target_kind}:{version}:known\", \"consent:state:{stance}:known\"]"
    renders: "the CEG families the draft would read or emit, each matched against the pinned registry by the wasm matcher; a family the registry lacks is marked missing and the draft does not use it"
    tag: "proposed:list_gap_families"
    assert: each
  - ceg: x_private:catalogue_version
    use: display-only
    type: int
    example: 1
    renders: "the CSD/4 catalogue version every atom above was checked against; shown so a reader knows which vocabulary the verdict used"
    tag: "proposed:text_catalogue_version"
  - ceg: x_private:topology_id
    use: display-only
    type: "enum[T0,T1,T2,T3,T4,T5,T6,T7,T8,T9]"
    example: "T4"
    renders: "the catalogue entry the planner chose as the cheapest cover for the draft's tests; never a topology block of the card's own"
    tag: "proposed:text_topology_id"
  - ceg: x_private:draft_csd_markdown
    use: display-only
    type: string
    example: "# CSD-1xx — Household vouching\n\n**Standard**: CSD/3 …"
    renders: "the draft document, with every yaml csd: block typed; copy or save; the card does not commit it anywhere"
    tag: "proposed:text_draft_csd"
  - ceg: x_private:gate_verdict
    use: display-only
    type: "list[string]"
    example: "[\"csd:stage ok\", \"csd:shows ok (7 fields, 0 unconfirmed)\", \"csd:states ok\", \"plan: covered at T4 step 5\"]"
    renders: "one row per typed block of the draft with the validator's verdict, and one row for the planner's answer; a draft is shown only when every block passes"
    tag: "proposed:text_gate_verdict"
    assert: each
  - ceg: x_private:pins
    use: display-only
    type: "list[string]"
    example: "[\"registry f666f334…\", \"claims 3b1c…\", \"signed_object 0616f913…\", \"catalogue v1\", \"corpus e040ae76\"]"
    renders: "the five hashes the verdict was computed against (CC registry, CC claims, CC signed object, the catalogue version, the CSD corpus commit); a draft names them in its header"
    tag: "proposed:text_pins"
  - ceg: x_private:bump_request
    use: emit
    type: string
    example: "CSD/4 catalogue v1 lacks an atom for 'a contact vouched for by a household member'; proposed meaning: …; CC clause: 4.4.3.2 Policy M"
    renders: "an issue draft the person may copy; the button exists only when a gap row is missing, and the card never adds the atom or family itself"
    tag: "proposed:btn_request_bump"
```

```yaml csd:states
populated:
  tag: "proposed:card_csd_studio"
  shows: "a use case, its matches, its gaps and a draft that passed the gate"
empty:
  tag: "proposed:text_csd_studio_empty"
  shows: "no use case typed yet: the field and one line of what the card does"
loading:
  tag: "proposed:text_csd_studio_loading"
  shows: "'reading the corpus…' then 'checking the draft…'; the step names are shown so a slow model is distinguishable from a slow gate"
error:
  tag: "proposed:text_csd_studio_error"
  shows: "the pin that failed by name (registry, claims, catalogue, corpus) or the model's refusal; never a half-checked draft"
```

## 3. Contracts (who)

Nothing in this card talks to a node. The matcher, the CDDL check, the claims resolver and the block validator run in the wasm kit on this device over pinned files; the planner's answer is a JSON file the server's harness produced for the catalogue version in use; the language model is the person's choice.

| value | endpoint | owner | state |
|---|---|---|---|
| `matching_csds`, `gap_families`, `gate_verdict` | wasm kit over `FSD/CSD/` + `namespace_registry.json` + `claims.tsv` (no route) | CIRISClient (CIRISClient#205 item 2–3) | unconfirmed |
| `gap_atoms`, `topology_id`, `catalogue_version` | `harness.native plan --json` output shipped with the catalogue (no route) | CIRISServer (FSD/CSD4.md) | unconfirmed |
| `draft_csd_markdown`, `bump_request` | the language model the person configured (CSD-021 LLM), on-device or hosted per decision c | CIRISClient#205 | unconfirmed |

## 4. Flow (how)

```yaml expect:
- visible: "proposed:card_csd_studio"
- state: "empty"
```

```yaml expect:
- text: { tag: "proposed:input_use_case", set: "A parent wants to see which of their child's contacts are vouched for by someone the household trusts" }
- state: "loading"
- count: { tag: "proposed:list_matching_csds", min: 1 }
- each: { tag: "proposed:list_gap_atoms", one_of: ["known", "missing"] }
- each: { tag: "proposed:list_gap_families", one_of: ["known", "missing"] }
```

```yaml expect:
- each: { tag: "proposed:text_gate_verdict", matches: "ok|covered" }
- visible: "proposed:text_draft_csd"
- visible: "proposed:text_pins"
```

```yaml expect:
- text: { tag: "proposed:input_use_case", set: "A contact vouched for by a household member" }
- each: { tag: "proposed:list_gap_atoms", one_of: ["known", "missing"] }
- visible: "proposed:btn_request_bump"
- absent: "proposed:text_draft_csd"
```

The fourth block is the honesty case: when a needed atom is missing, the card offers the bump request and shows no draft, because a draft that names an atom the catalogue lacks would fail the planner and the gate.

## 5. QA plan

- **Gate parity.** The wasm validator and `packaging/check_csd_v3.py` give the same verdict on all 80 cards of the corpus and on the eight breaking variants `check_csd_v3.py` itself documents; a divergence is a build failure, not a warning.
- **Matcher parity.** The wasm namespace matcher reproduces CC's `manifests/namespace_match_vectors.json` (1,147 vectors at the pinned registry) byte for byte.
- **Planner contract.** For every CSD in `requirements.yaml`, the card's `gap_atoms` and `topology_id` equal the planner's answer for that CSD.
- **No minting.** A use case naming a state outside the vocabulary yields a bump request and no draft (flow block four), and a draft never carries a `ceg:` the registry lacks.
- **Privacy.** The use case leaves the device only to the model the person chose; the network sees nothing (CC 5.2).
- Verified on the platforms the Help page already runs on; floor unreleased.

## 6. Delta — card vs API vs CC

- **Not an API.** No new route; the contracts are files and a model. If a hosted service for the kit ever appears, it is a convenience, and the on-device path remains the one the QA plan tests.
- **CSD/4 fields.** This card describes `topology_id` and the atoms as outputs. The CSD/4 fields on cards themselves (`topology:`, `tests:`) and whether `requirements.yaml` is generated from cards are open on CIRISClient#205 (decision b); this card follows whichever lands.
- **Vocabulary ownership.** Whether CC publishes the atom vocabulary (`manifests/topology_vocabulary.json`, CIRISConstitution#131) or the server's `model.ATOMS` stays the source is decision (a) on CIRISClient#205; the card pins whichever is ruled and shows its version.
- **CC.** CC 3.1.7 R1/R2 (a family exists when the registry has a row); CC 2.6.1.1.1 and 2.6.1.7 (byte identity, the signed object the kit validates against); CC 5.2 (nothing of the person's leaves the device unasked); CIRISConstitution rc9 `review/notes/formal_closure_rc9_2026-10-18.md` (the coverage gate this card is the authoring side of).
