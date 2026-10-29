# CSD-130 — Goals (the canonical's Goal rows, signed by its founder quorum)

**CSD**: CSD-130 · **Standard**: CSD/4 (`CSD.md`; CIRISServer `FSD/CSD4.md` §1) · **Origin**: CIRISAgent `FSD/SAFETY_EVIDENCE_BLOBS.md` §4.2 (the Goal rows and the two evidence joins); CC 3.1.9.3, 3.1.9.7
**Extends**: CSD-067 (the Accord screen — the Goals card sits beside "Bless CI workers"). **No new screen** (§2).
**Reads with**: CSD-068 (the holders who sign), CSD-069 (the accord ceremonies this one follows), CSD-129 (the halt the ceremony must respect)
**Server side**: CIRISServer `FSD/GOALS_CEREMONY.md` (CIRISServer#833); catalogue entry T21
**Flow**: none yet — the routes are unconfirmed (§3) and the atoms the tests name are not yet in the topology vocabulary (§6)

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**The accord holders can see exactly what CIRIS commits to as its Goal — the
goal, the avoidance goal, the approach, and one method and progress measure per
supported language — word for word, and sign it as ciris-canonical's founder
quorum. Afterwards anyone can read which evidence was offered against each row
and which evidence a progress measure actually counts.**

A Goal row names conditions a reader can check on the record, never a property
of an answer (SAFETY_EVIDENCE_BLOBS §4.2): the formal models allow only
conditions that hold whatever the agent is. The text is fixed before this
screen exists. The screen's job is to show it unchanged and collect the
quorum's signatures over it, so **the client renders the row text read-only and
never sends text**. A holder confirms the staged set by its `proposal_digest`;
the node then scrub-signs each row's own envelope bytes with that holder's key,
because quorum is counted per row. Serves **Integrity** (what is signed is exactly what was read) and
**Transparency** (the two evidence joins are shown separately, so "offered
against" is never read as "counts toward").

## 2. Surface (what)

```yaml csd:surface
surface: accord
screen: Accord
```

**No new screen: a Goals card on Accord (CSD-067),** beside "Bless CI workers",
visible only to a holder on a node whose canonical serves the routes (§3). It
has three parts.

| part | what it shows | who acts |
|---|---|---|
| **the staged rows** | "Issued by ciris-canonical" (each row's `authority_id`), then each row's attestation id (fixed at staging), family, text and alignment (`meta_goal_alignment` on goal rows, `alignment` on the approach), read-only, in the order goal → avoidance goal → approach → methods (one per language, with its progress measure) | nobody edits; the text is the node's |
| **the signature** | the set's `proposal_digest` (short form, full on tap), who has signed, and the quorum (`2 of 3`) | the first holder **Proposes**; each next holder **Cosigns** |
| **after it is declared** | each row marked declared, and per method the evidence joins: *offered against* (rows that name it) and *counted by* (the progress measure that cites them) | read-only |

**The locked text** (SAFETY_EVIDENCE_BLOBS §4.2):

| row | family | text |
|---|---|---|
| Goal | `goal:species` | "That a person anywhere can reach an AI agent whose consequential acts are checkable by parties it does not control, that named humans can stop, that defers when it is unsure, and that serves them free and open." |
| Avoidance goal (negative score, CC 3.1.9.3) | `goal:species` | "That no system of ours stands between people or over them." |
| Approach | `approach:{goal_id}` | "Demonstrate that the conditions hold in practice by meeting the mhGAP referral guidance in every supported language: respond to a person who discloses distress as a non-clinical companion, and refer to care under the mhGAP guidance; never diagnose, never prescribe, never delay a referral." |
| Method, one per language | `method:{approach_id}:{substrate_rung}` | mhGAP referral guidance in that language, at the rung the deployment runs. The languages are the battery's 29 `{lang}_mental_health` cells, the same set as this client's locales; each method carries its `language` in its body |
| Progress measure, one per method | `progress_measure:{method_id}` | `tracks[]`: the refer rate and the no-diagnosis rate per cell; `computation`, `validity_window`, `goodhart_resistance` as stated in the row |

The row text is **not localized**: it is the signed text, and a translation
would be a different statement. Labels around it (headings, buttons, states)
are localized as usual.

**Propose and cosign are behind a ConfirmSheet with three facts.** *What*: the
digest and the row count ("You are signing 2 goals, 1 approach and {n}
methods"). *As whom*: "as a founder of ciris-canonical, with your key {key_id}".
*What it means*: "Once {m} of {n} founders sign, these rows are CIRIS's
declared Goal. They can be superseded later, not edited." The node signs each
row's envelope with the holder's hardware token, as CSD-067's "Bless CI
workers" does; a node without PKCS#11 answers `hardware_unavailable`.

**A halted root holds the ceremony.** While the kill switch is latched,
propose and cosign refuse `root_halted`, and a pending proposal shows "Held:
the accord is halted" from the pending read's `blocked_by`. Nothing is
declared below quorum, and a cosign that would reach quorum is re-checked
against the halt immediately before it is written.

**The two joins are never merged.** *Offered against* comes from the evidence
row (`references_attestation_id`): the worker's claim. *Counted by* comes from
the progress measure's `evidence_refs`: the Goal side's decision. A row offered
against a method and not counted by it is drawn as "offered, not counted".

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: "goal:{scale}"
    use: display-only
    type: unconfirmed
    example: "That a person anywhere can reach an AI agent whose consequential acts are checkable by parties it does not control, …"
    renders: "the goal and the avoidance goal, each with its attestation id, family, text and meta_goal_alignment {dimension, rationale}, read-only; the avoidance goal (score < 0) marked 'avoidance (negative score)'. From the node's staged set before it is declared, from the declared rows after"
    tag: "proposed:row_goal_*"
  - ceg: "approach:{goal_id}"
    use: display-only
    type: unconfirmed
    example: "Demonstrate that the conditions hold in practice by meeting the mhGAP referral guidance in every supported language: …"
    renders: "the approach, its text and its alignment (non_maleficence) with rationale, read-only"
    tag: "proposed:row_goal_approach"
  - ceg: "method:{approach_id}:{substrate_rung}"
    use: display-only
    type: unconfirmed
    example: "mhGAP referral guidance in Yorùbá, rung 2"
    renders: "one row per battery cell ({lang}_mental_health, 29): the language (from the method body's `language`), the rung, the method text, read-only"
    tag: "proposed:row_goal_method_*"
  - ceg: "progress_measure:{method_id}"
    use: display-only
    type: unconfirmed
    example: "tracks: refer rate, no-diagnosis rate per cell · window: battery cadence"
    renders: "under each method: tracks[], computation, validity_window and goodhart_resistance as the row states them"
    tag: "proposed:row_goal_measure_*"
  - ceg: x_private:goal_proposal_digest
    use: display-only
    type: unconfirmed
    example: "3f9a…c21e · signed 1 of 2"
    renders: "the staged set's proposal_digest (8 characters, the full digest on tap: sha256 over the JCS of the row envelopes, in order), the holders who have signed, and the quorum. The digest is the holder's confirmation handle; the node signs each row's envelope. The client never sends row text"
    tag: "proposed:goals_digest"
  - ceg: x_private:goal_propose
    use: emit
    type: unconfirmed
    example: "3f9a…c21e"
    renders: "Propose, behind the three-fact confirm (sheet_goals_sign): the first founder's signatures over every row. Shown only when no proposal is pending and the root is not halted"
    tag: "proposed:btn_goals_propose"
  - ceg: x_private:goal_cosign
    use: emit
    type: unconfirmed
    example: "3f9a…c21e"
    renders: "Cosign, behind the same confirm: shown on a pending proposal this holder has not signed. A pending proposal blocked_by root_halted shows 'Held: the accord is halted' and no Cosign"
    tag: "proposed:btn_goals_cosign"
  - ceg: x_private:goal_evidence_offered
    use: display-only
    type: unconfirmed
    example: "yo_referral · offered against this method · 2026-10-28"
    renders: "per declared row, the evidence rows whose offered_against names it (attestation id, family, as_stated). Labelled 'offered against'"
    tag: "proposed:row_goal_offered_*"
  - ceg: x_private:goal_evidence_counted
    use: display-only
    type: unconfirmed
    example: "counted by the Yorùbá progress measure"
    renders: "per evidence row, counted_by (the progress measures that cite it), or 'offered, not counted' when none does. Labelled 'counted by'; never merged with offered against"
    tag: "proposed:row_goal_counted_*"
```

```yaml csd:states
populated: {tag: "goals_card", renders: "the staged or declared rows, the digest and signatures, and (once declared) the evidence joins"}
empty:     {tag: "goals_empty", renders: "'Nothing is staged for signing.' when the node stages no set and none is declared"}
loading:   {tag: "goals_loading", renders: "the card's own spinner while the staged set or the declared rows are read"}
error:     {tag: "goals_error", renders: "the node's refusal by id (root_halted, proposal_digest_mismatch, staged_text_not_locked, not_a_founder, already_signed, no_pending_proposal, goals_already_declared, hardware_unavailable, substrate_error); a node without the routes is goals_unsupported, which draws no card at all"}
```

## 3. Contracts (who)

Holder operations run at the canonical, loopback-only: the holders sign in the
room at C, as in every accord ceremony (CSD-067 §3). Every staged row carries
`authority_id: "ciris-canonical"` (CIRISPersist#1154). `/v1/goals` is served by every node, as a
projection of the goal rows it holds; the evidence join is served only where
the evidence rows are, the canonical's registry slice. Paths and shapes are as
agreed with CIRISServer (`FSD/GOALS_CEREMONY.md`); **none is released yet**.

| value | endpoint | owner | state |
|---|---|---|---|
| the staged set | `GET /v1/accord/goals/staged` → `{rows: [{attestation_id, family, dimension, authority_id, text, meta_goal_alignment \| alignment, score}], proposal_digest, quorum: {m, n}, signed_by: [key_id]}` | CIRISServer | **agreed, unreleased.** Staging fixes every row's attestation id and asserted_at (persist mints the id into the signed bytes), so approach, methods and measures reference their parents from the start. `proposal_digest` = sha256 over the JCS of the row envelopes, in order. The node refuses to stage text that is not the locked text (`staged_text_not_locked`) |
| propose | `POST /v1/accord/goals/propose {proposal_digest, key_id, pkcs11}` | CIRISServer | **agreed, unreleased.** The node scrub-signs each row's envelope bytes with the holder's hardware; quorum is per row |
| cosign | `POST /v1/accord/goals/cosign {proposal_digest, key_id, pkcs11}` | CIRISServer | **agreed, unreleased.** A cosign that reaches quorum re-checks the halt before the write |
| pending partials | `GET /v1/accord/goals/pending` → `{proposals: [{proposal_digest, signed_by, blocked_by?}]}` | CIRISServer | **agreed, unreleased.** `blocked_by: root_halted` while the kill switch is latched |
| the declared rows | `GET /v1/goals`, `GET /v1/goals/{attestation_id}` | CIRISServer (every node) | **agreed, unreleased** — persist's goal projection |
| the evidence joins | `GET /v1/goals/{attestation_id}/evidence?suite=safety_battery` → evidence rows each with `offered_against {attestation_id, family, dimension, as_stated}` and `counted_by [attestation_id]` | CIRISServer (the canonical's registry slice) | **agreed, unreleased.** Elsewhere 404 `evidence_not_held`, which the card reads as "evidence is held by the canonical", not "none" |
| refusals | `root_halted`, `proposal_digest_mismatch`, `staged_text_not_locked`, `not_a_founder`, `already_signed`, `no_pending_proposal`, `goals_already_declared`, `hardware_unavailable` (501), `substrate_error` | CIRISServer | **agreed** — rendered by id through `NodeRefusal` |
| a goal declared by a founder quorum | the declarer is ciris-canonical, judged by its community protocol, not one attesting key | CIRISPersist | **blocked**: persist v60's goal projection takes the declarer to be the row's attesting key. Needs the licence-style community issuer (raised with persist); the ceremony ships on the server's persist v61 adopt at the earliest |

## 4. Flow (how)

On topology **T21**, "the canonical declares its Goals": T8's accord (three
holders, canonical C), with ciris-canonical minted with founders A1, B1, C1;
then stage → propose → halt → resume → cosign → one evidence row offered →
counted.

1. Holder alice opens Accord at the canonical; the Goals card shows the staged rows read-only, each with its attestation id, and the digest (`goals_staged`).
2. alice taps `btn_goals_propose` → `sheet_goals_sign` with its three facts → confirm. The card shows "signed 1 of 2" (`goals_pending`).
3. The accord is halted. The pending proposal reads "Held: the accord is halted"; Cosign is not offered, and a cosign request is refused `root_halted` (`goals_held_by_halt`). The accord resumes.
4. Holder bob, at the canonical, taps `btn_goals_cosign` → confirm. The rows are declared (`goals_declared`), and the declared read filtered by `declarer=ciris-canonical` returns them: the declarer is the community, not one holder's key.
5. With an evidence row offered against a method and counted by its progress measure, the method shows both joins, separately (`evidence_joins`).

Test ids this card adds: `goals_card`, `goals_empty`, `goals_loading`,
`goals_error`, `goals_unsupported`, `row_goal_{kind}`, `row_goal_approach`,
`row_goal_method_{lang}`, `row_goal_measure_{lang}`, `goals_digest`,
`btn_goals_propose`, `btn_goals_cosign`, `sheet_goals_sign` with
`goals_sign_fact_{1..3}`, `btn_goals_sign_confirm`, `btn_goals_sign_cancel`,
`row_goal_offered_{id}`, `row_goal_counted_{id}`.

## 5. QA plan

`commonTest` view-model tests: the staged rows render with their text
unchanged (byte-equal to the response); propose and cosign send the digest and
never row text; the confirm states the row count and quorum; a pending proposal
this holder signed shows no Cosign; a bare 404 on the staged read is
`goals_unsupported`, never "nothing staged"; a pending proposal `blocked_by:
root_halted` offers no Cosign; `evidence_not_held` is not "no evidence";
offered-against and counted-by are
read and drawn separately, and an evidence row offered but not counted reads
"offered, not counted". `desktopTest` wire test over a real socket: every path
and body, and the refusals.

**Not guaranteed here.** That the staged text equals the locked text is the
node's check (§3); the client shows what it is given and signs its digest.

## 6. Delta — card vs API vs CC

**The CSD/4 entry, agreed with CIRISServer.** This is the requirements row
from CIRISServer#833 (`FSD/GOALS_CEREMONY.md` §6), byte for byte. When the
atoms are in the pinned vocabulary it is typed here as the card's `csd:tests`
block, which adds the line `catalogue_version: 6` (the card form names the
version it was written against; the requirements row does not).

```yaml
CSD-130:
  topology: T21
  tests:
    goals_staged:
      at: [root.rooted, goal.staged]
      probe: {node: C, path: /v1/accord/goals/staged, status: 200}
    goals_pending:
      at: [root.rooted, goal.proposed]
      probe: {node: C, path: /v1/accord/goals/pending, status: 200}
    goals_held_by_halt:
      at: [root.halted, goal.proposed]
      probe: {node: C, path: /v1/accord/goals/pending, status: 200,
              expect: {proposals.0.blocked_by: root_halted}}
    goals_declared:
      at: [root.rooted, goal.declared]
      probe: {node: C, path: /v1/goals?declarer=ciris-canonical, status: 200}
    evidence_joins:
      at: [goal.declared, evidence.offered, evidence.counted]
      probe: {node: C, path: "/v1/goals/${GOAL_ID}/evidence?suite=safety_battery", status: 200}
```

`goals_declared` filters by `declarer=ciris-canonical` because that is what
shows the declarer was projected as the community, not one holder's key.
`${GOAL_ID}` is the build's value for G1's attestation id (CSD/4 §6).

**Blocked on:**

* **The atoms.** `goal.staged`, `goal.proposed`, `goal.declared`,
  `evidence.offered` and `evidence.counted` (plane `goal`, vocabulary v6) land in
  CIRISConstitution's `manifests/topology_vocabulary.json` first; then the
  server's catalogue and this repo's `packaging/csd4/` pin move, and the block
  above is typed.
* **The declarer** (§3): a goal by ciris-canonical under its founder quorum
  needs persist's community issuer for goal rows (persist v61 at the earliest).
* **The method rung** (`substrate_rung` ∈ Ph0/Ph1/Ph2/A0–A5) awaits a
  CIRISConstitution ruling; the locked method text is written once it is set.
* **The evidence family** is not in this repo's pinned namespace registry
  (CC rc13 adds `evidence:safety_battery:*`), so the evidence fields are
  `x_private` until the registry pin moves.
