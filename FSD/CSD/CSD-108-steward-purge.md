# CSD-108 — Steward purge (remove rows that never belonged, under the global trust root)

**CSD**: CSD-108 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the operator ruling of
2026-10-09, relayed by the CIRISPersist session: *"We already have a steward purge
for keys, extend to generic rows, create a CSD working with the client session to
go under global trust root moderation."*
**Door**: CIRISPersist#1046 (`Engine::purge_rows`, milestone v54.1.0) ·
**CC reading**: CIRISConstitution#165 (proposed rc8 text, lands on the steward's go)
**Extends**: CSD-065's tier 3 (Descend), the key purge that exists today, and
CSD-067's co-scrub, the quorum cosign that exists today (§1.1). **Placed as** a leaf
of the Accord card (CSD-067), beside CSD-105 and CSD-090
**Flow**: unwritten. Every tag below is `proposed:`; there is no route to drive

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**The stewards of the global trust root can remove a set of rows that never
belonged in the canonical corpus.** The set might be mock-model traces, harness
identities registered on production, or rows no apply door would admit today.
Before anyone signs, a steward sees exactly which rows and how many, table by
table, with a sample. A cosigned notice names the selection by its digest, and
nothing outside that selection can be purged. Every purge leaves a ledger anyone
can read afterwards.

Serves **Integrity** and **Justice**. The falsifiable claims are these four:

1. **Nothing is purged that the quorum did not see.** The notice names the
   SHA-256 of the JCS-sorted id list, derived ids included. The node refuses an
   execution whose live selection hashes differently
   (`purge_selection_digest_mismatch`) or that carries no adopted notice
   (`purge_unauthorised`). The card shows both refusals by id
   (CIRISConstitution#165).
2. **The order is fixed, and the card draws it in that order.** The steps are
   dry-run (the ledger is written FIRST, because the notice points at it), then
   the cosigned notice, then execute, then the tombstones replicate, then
   *settle*, then hard-delete. Settle is its own visible state. A row that has
   been tombstoned but not deleted is never drawn as "deleted".
3. **A purge is not a withdrawal.** The card never calls a purge "withdrawn" or
   "erased by the author". A `withdraws` is the author's or the subject's consent
   act (CC 2.4.1.1 rules 0–4), and it does not claim the row was false. A purge
   says the row never belonged, and it is carried by a tombstone the root cosigns
   (CIRISConstitution#165 §2). CSD-039's erasure is the subject's act. This card
   is the steward's act. The two have different words.
4. **An abort is named.** If a retraction cannot be admitted, execution stops
   (I18's contract on CIRISPersist#1046). The card says which record stopped it,
   on which plane, and how far the purge got before it stopped. It never shows
   a partial purge as done.

### 1.1 What this extends, and why it is still its own card

Following `cards-keyed-by-routes`, we looked for the purge that already exists by
the routes and primitives it uses, not by its name.

* **The key purge is CSD-065's tier 3, Descend.** `POST /v1/admin/descend`
  previews a selection and returns a `selection_hash` and row counts
  (`txt_ladder_selection_hash`, `txt_ladder_preview_counts`: "9,811 rows across
  3 keys"). It refuses a commit whose hash differs, requires a quorum of 2
  distinct authority roots, and calls `Engine::evict_actor` for each target
  (CIRISServer `src/admin_ops.rs`, `descend`, the `evict_actor(target, now)` arm).
  The same primitive is behind `POST /v1/auth/erasure` (CSD-039 §3), but that
  route is the subject's own request and the client cannot reach it today
  (CIRISServer#677).
* **The quorum cosign is CSD-067's co-scrub.** The pattern is propose, hand the
  byte-identical partial to the next holder, cosign until quorum, and read the
  partials still below quorum. It already carries the canonical-server propose,
  cosign and withdraw ops (`withdraw_canonical_role`'s door, behind a
  2-of-3 proposal digest) and CSD-090's duty conferral.

The generic door makes **by actor/key** one selection kind among four. That kind
is descend's selection, widened from one actor's bytes to every row attested by
or about the key. The other three are **by predicate over a plane**, **by
structural classification**, and **by explicit id list** (CIRISPersist#1046).

It is a **new card and not a fifth rung on CSD-065's ladder**, because the
authority is different. Descend is a *community's* `slash` duty, re-derived
along a `delegates_to` chain (CC 4.5.5). That is why it lives in
Neighbours › Safety. A steward purge is the *global trust root's* `takedown`
duty over the canonical that root anchors. It is authorised by a cosigned
`takedown_notice` under the root's `consensus_protocol`
(CIRISConstitution#165 §1: "no new family, no new power"). The second authority
lives at Everyone › Safety › Accord, and so does this card. Putting both on one
ladder would draw two authorities as one brake. CSD-065 §2.1 already records
that confusion once, between filing a report and pulling a brake.

It is **not CSD-105** either. CSD-105 answers whether *this node* trusts a root,
and its two levers act on this node's own acceptance edge. A purge is an act the
root's roster authorises over the canonical's corpus, and its tombstones
replicate to every peer.

What carries over from both parents, unchanged:

* from the ladder: the preview-hash commit (what was previewed is what runs),
  the always-present "what this does NOT reach" block, and refusals by id;
* from co-scrub: the holder picker from `GET /v1/accord-holders`, the
  byte-identical partial, the `1 of 2` count, and the pending list.

## 2. Surface (what)

No `csd:surface` block yet. The screen does not exist, and the checker refuses a
`flow_only` screen that `sealed class Screen` does not declare. The design is
**`Screen.StewardPurge`**, flow-only, a leaf of Accord in the same shape as
`Screen.TrustRoot` and `Screen.DutyConferral`:

* **entry:** `proposed:btn_accord_open_steward_purge` on the Accord card
  (Everyone › Safety › Accord), shown when the node answers the dry-run route at
  all;
* **exit:** back to `Screen.Accord`. `screenToSurface` keeps `NavSurface.Accord`
  lit, as it does for the other two leaves.

When the screen is declared, this section gains the block below, and the stage
can then be checked against it:

```yaml
surface: null
flow_only: true
screen: StewardPurge
entry: Accord's `btn_accord_open_steward_purge`
exit: back to `Screen.Accord`
```

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:purge_selection_kind
    use: emit
    type: "enum[by_actor,by_predicate,by_classification,by_ids]"
    example: "by_predicate"
    renders: "four chips: By key · By predicate · By classification · By id list. By key is CSD-065's descend selection, widened from one actor's bytes to every row attested by or about the key"
    tag: "proposed:chip_purge_kind_${kind}"
  - ceg: x_private:purge_selection_input
    use: emit
    type: string
    example: "trace_llm_calls.model = 'mock-model'"
    renders: "the selection's own input for the chosen kind: a key id, a plane and predicate, a classification from the node's closed list, or an id list (one per line). The node parses it; the card sends it as typed"
    tag: "proposed:input_purge_selection"
  - ceg: x_private:purge_reason
    use: emit
    type: string
    example: "Mock-LLM traces admitted 08-01 to 09-18 (CIRISPersist#1040)"
    renders: "Reason. Required. It is carried into the notice and the ledger verbatim"
    tag: "proposed:input_purge_reason"
  - ceg: x_private:purge_table_count
    use: display-only
    type: int
    example: 21836
    renders: "one row per table from the dry-run: 'trace_events: 21,836'. A table the selection does not reach is not listed. The sum appears once, under the rows"
    tag: "proposed:row_purge_count_${table}"
  - ceg: x_private:purge_derived_count
    use: display-only
    type: int
    example: 1499
    renders: "'scores: 1,499 derived. Their evidence is in this selection, so they go with it (CLM-derived-rows-follow-reason)'. Drawn apart from the selected rows, so a steward sees what the predicate pulled in"
    tag: "proposed:row_purge_derived_${table}"
  - ceg: x_private:purge_sample
    use: display-only
    type: "list[string]"
    example: ["trace 7f21… · mock-model · 2026-08-14", "trace 0c9e… · mock-model · 2026-09-02"]
    renders: "a sample of the selected rows, ids short and dates shown, so 'these are the rows I mean' can be checked by eye before anyone signs"
    tag: "proposed:row_purge_sample_${i}"
  - ceg: x_private:purge_selection_digest
    use: display-only
    type: string
    example: "9f2c40ab77e1d0c3…"
    renders: "Selection digest 9f2c40ab77e1…, the SHA-256 over the JCS-sorted id list with derived ids included. The same value appears on the dry-run, the notice, the execute confirm and the ledger row"
    tag: "proposed:txt_purge_selection_digest"
  - ceg: x_private:purge_ledger_ref
    use: display-only
    type: string
    example: "evidence/purges/2026-10-09-9f2c40ab77e1.tsv"
    renders: "Ledger: evidence/purges/2026-10-09-9f2c40ab77e1.tsv, written by the dry-run before anything is signed. The notice's evidence_refs point here"
    tag: "proposed:txt_purge_ledger_ref"
  - ceg: "duty:{kind}"
    bind: {kind: takedown}
    use: display-only
    type: string
    example: "takedown"
    renders: "Authority: the takedown duty this trust root holds over the canonical it anchors (CC 4.5.5). This is not a community's slash duty, and the card says so in one line"
    tag: "proposed:txt_purge_authority"
  - ceg: x_private:purge_authorisation
    use: display-only
    type: unconfirmed
    blocked_by: CIRISConstitution#165
    example: "takedown_notice · basis steward_purge · claimant humanity-accord-community · notice att-4c1d…"
    renders: "the cosigned takedown_notice (CC 3.3.2 shape) with basis steward_purge, claimant_key_id = the root's community_key_id, the digest, the reason and evidence_refs → the ledger. The steward_purge basis is proposed rc8 text and is not in rc6"
    tag: "proposed:txt_purge_notice"
  - ceg: x_private:purge_quorum
    use: display-only
    type: string
    example: "1 of 2 signatures (roster 3)"
    renders: "'1 of 2 signatures (roster 3)', counted from the root's consensus_protocol. Below quorum: 'Not authorised yet. Hand the partial to the next holder to cosign.'"
    tag: "proposed:txt_purge_quorum"
  - ceg: x_private:purge_cosigners
    use: display-only
    type: "list[string]"
    example: ["holder-a1 · YubiKey 5 FIPS · signed 2026-10-09T14:02Z"]
    renders: "one row per holder who has signed, with their custody class and when, and one row per seat still owed. A signer is never shown without the time they signed"
    tag: "proposed:row_purge_cosigner_${holder}"
  - ceg: x_private:purge_state
    use: display-only
    type: "enum[dry_run,proposed,authorised,executing,aborted,tombstoned,settling,hard_deleted]"
    example: "settling"
    renders: "one step strip in the order CIRISConstitution#165 fixes: Dry-run → Notice → Authorised → Executing → Tombstoned → Settling → Deleted. Aborted is drawn off the strip, in the danger tone. A state this app does not know is shown verbatim and never as Deleted"
    tag: "proposed:strip_purge_state"
  - ceg: x_private:purge_progress
    use: display-only
    type: unconfirmed
    example: "4,210 of 23,335 records tombstoned"
    renders: "progress as tombstoned / total, per plane. The door reports what happened (CIRISPersist#1046), but whether it streams progress or answers once is not specified"
    tag: "proposed:txt_purge_progress"
  - ceg: x_private:purge_refusal
    use: display-only
    type: "enum[purge_unauthorised,purge_selection_digest_mismatch]"
    example: "purge_selection_digest_mismatch"
    renders: "by id, each with its own sentence. Unauthorised: 'No adopted notice names this selection. Nothing was purged.' Digest mismatch: 'The rows changed since the dry-run, so the notice no longer describes them. Nothing was purged. Run the dry-run again and re-sign.'"
    tag: "proposed:txt_purge_refusal"
  - ceg: x_private:purge_abort
    use: display-only
    type: unconfirmed
    example: "Stopped at trace 7f21… (claims plane): its tombstone could not be admitted. 4,210 records were tombstoned before it; none after."
    renders: "the abort, named: which record, which plane, the count before it, and that the count after it is zero. The count-mismatch and inadmissible-retraction refusal ids are not named on the door yet"
    tag: "proposed:txt_purge_aborted"
  - ceg: x_private:claims_tombstone
    use: display-only
    type: unconfirmed
    blocked_by: CIRISConstitution#165
    example: "tombstone by digest · notice att-4c1d…"
    renders: "on the ledger row, per claim-row plane (traces, scores, attestations): 'Tombstoned by digest, cosigned by the root, citing notice att-4c1d…'. Not a withdraws. No registered family carries it in rc6"
    tag: "proposed:row_purge_plane_claims"
  - ceg: x_private:bytes_tombstone
    use: display-only
    type: unconfirmed
    blocked_by: [CIRISPersist#1007, CIRISConstitution#165]
    example: "bytes tombstone · holders re-ack none"
    renders: "the bytes plane: the CC 2.3 tombstone door that takedown and evict_actor move onto (CIRISPersist#1007). Until it exists, the card says the bytes leg is not available, not that it succeeded"
    tag: "proposed:row_purge_plane_bytes"
  - ceg: x_private:key_revocation
    use: display-only
    type: unconfirmed
    blocked_by: CIRISConstitution#165
    example: "key:revocation:v1 · revoke-all · 14 keys"
    renders: "keys: 'Revoked (key:revocation:v1), 14 keys'. Harness identities revoke-all. key:revocation:v1 is not a family in the vendored rc6 registry"
    tag: "proposed:row_purge_plane_keys"
  - ceg: "revocation:{entity_type}:{reason}"
    bind: {entity_type: agent, reason: steward_purge}
    use: display-only
    type: string
    example: "steward_purge"
    renders: "entities: 'Revoked: agent · steward_purge', one row per entity type, with the count"
    tag: "proposed:row_purge_plane_entities"
  - ceg: x_private:purge_settle
    use: display-only
    type: unconfirmed
    example: "Tombstoned 14:20. Waiting for the retraction round to settle; the rows are still on this node."
    renders: "its own state between Tombstoned and Deleted. Neither the door nor the CC reading says what 'settled' is measured by, so the card shows the waiting state and the time the tombstones went out, and claims no percentage"
    tag: "proposed:txt_purge_settling"
  - ceg: "reconsideration:{grounds}"
    bind: {grounds: procedural_error}
    use: display-only
    type: string
    example: "procedural_error"
    renders: "on every ledger row: 'Contest: a subject may ask for reconsideration (new evidence · procedural error · quorum compromise)'. This is the steward_purge basis's contest path (CC 4.5.5, CIRISConstitution#165 §1)"
    tag: "proposed:txt_purge_contest"
  - ceg: x_private:purge_ledger_row
    use: display-only
    type: string
    example: "2026-10-09 · 9f2c40ab77e1 · notice att-4c1d… · 23,335 rows · Deleted"
    renders: "one row per past purge: date, digest, notice id, row total, final state and the ledger path. It opens the per-table counts and the per-plane rows above, read back from the ledger"
    tag: "proposed:row_purge_ledger_${digest}"
```

**Registered families are bound where rc6 has them, and nowhere else.** Three
of the values here are families in the vendored registry, and each is bound as
the registry spells it:

* `duty:{kind}` with `takedown`, the CC 4.5.5 duty the root exercises. CSD-065
  binds the same family for `moderate`.
* `revocation:{entity_type}:{reason}`, which covers entities.
* `reconsideration:{grounds}`, which covers the contest path.

Four values are unregistered in rc6, and each says why:

* the `steward_purge` basis on the `takedown_notice`;
* the claims-plane tombstone;
* the bytes tombstone;
* `key:revocation:v1`.

Those four are `x_private:` with `blocked_by: CIRISConstitution#165`, the rc8
text that would add them. The bytes tombstone is also `blocked_by:
CIRISPersist#1007`, the door. The registry has no `tombstone` family at all, and
CIRISConstitution#165 §1 says the authorisation needs "no new family". So
whether the claims tombstone becomes a family or stays a structural CC 2.3
primitive is the open half of that issue. Until it is decided, this card names
the value privately and binds nothing.

`moderation:{allegation_type}` is **deliberately not bound**. It is a
ModerationEvent (CC 3.1.9.2), an allegation filed under a duty. The reading
puts the purge's authority in a `takedown_notice` instead, and binding a
ModerationEvent here would draw the purge as a report.

```yaml csd:states
populated: {tag: "proposed:card_purge_ledger", renders: "the selection card first, then past purges as ledger rows, newest first. A purge in flight is pinned above the history with its step strip"}
empty:     {tag: "proposed:purge_ledger_empty", renders: "'No purges on this canonical.' This sentence is a ledger read that returned zero rows. A failed read never draws it"}
loading:   {tag: "proposed:purge_ledger_loading", renders: "the StateBlock spinner while the ledger read is in flight. The selection card stays usable, and the empty sentence is not drawn"}
error:     {tag: "proposed:purge_ledger_error", renders: "'Could not read this canonical's purge ledger', with the node's detail. Two other blocks have their own tags: purge_not_on_this_node (a node without the door, in the neutral tone) and purge_loopback_only (off the node's machine, as CSD-105 draws it)"}
```

## 3. Contracts (who)

**There is no route.** The door is persist's (CIRISPersist#1046, open, v54.1.0).
The node needs to serve it, and **no CIRISServer issue asks for that yet**
(§6, Q1). Every row below is `missing`. The shapes are what the card needs, so
the server can answer one ask instead of seven.

| value | endpoint | owner | state |
|---|---|---|---|
| dry-run a selection; write the ledger | proposed: `POST /v1/accord/purge/dry-run` `{selection:{kind, …}, reason}` → `{selection_digest, ledger_ref, counts:[{table, rows, derived}], sample:[…], live_count}` | CIRISServer over `Engine::purge_rows` dry-run (CIRISPersist#1046) | **missing**. The door spec says it "prints counts per table and writes the would-purge list". The card needs that list as data (counts per table, derived counts apart, a sample) and needs the ledger path, because the notice cites it |
| propose the notice (holder #1 signs) | proposed: `POST /v1/accord/purge/propose` `{holder{key_id, mldsa_usb_path, pkcs11}, selection_digest, ledger_ref, reason}` → `{partial, scrub_count, quorum_needed, adopted}` | CIRISServer | **missing**. The shape is co-scrub's (`/v1/accord/duty/propose`, `/v1/accord/canonical/propose`), so the client can reuse the holder picker and the partial sheet. Whether it IS co-scrub is §6, Q3 |
| the next holder cosigns | proposed: `POST /v1/accord/purge/cosign` `{holder, partial}` | CIRISServer | **missing**. The partial goes back byte-identical |
| notices below quorum | proposed: `GET /v1/accord/purge/pending` | CIRISServer | **missing** |
| execute | proposed: `POST /v1/accord/purge/execute` `{notice_id, selection_digest}` | CIRISServer over `purge_rows(selection, authorisation, now)` | **missing**. Refusals by id: `purge_unauthorised` and `purge_selection_digest_mismatch` (CIRISConstitution#165). The count-mismatch refusal and the inadmissible-retraction abort have no id yet (§6, Q2) |
| a purge's state, progress, settle | proposed: `GET /v1/accord/purge/{selection_digest}` | CIRISServer | **missing**. The card polls this from Executing until Deleted. Whether persist reports per-plane progress, and what settle is measured by, are §6, Q4 |
| the ledger | proposed: `GET /v1/accord/purge/ledger` → rows of `evidence/purges/<date>-<digest>.tsv` | CIRISServer · CIRISPersist (the TSV, attested at the next tag, #1028's predicate) | **missing** |
| who may call these | loopback plus the owner session, as the other accord ops are gated | CIRISServer | **missing**. The ops act on the canonical's own store. Off the machine the card draws `purge_loopback_only`, never an empty ledger (CSD-105's precedent, CIRISServer#652) |
| the holders, for the picker | `GET /v1/accord-holders` | CIRISServer (`src/accord.rs:2601`) | **live**, already called by CSD-090 and CSD-067 |

**The routes are prose until a server issue names them.** The proposed literals
sit under `/v1/accord/` because that is where the root's other cosigned ops
live. The server may choose differently. When it does, these rows change to
match, and the card cites what the server ships.

## 4. Flow (how)

Every tag below is `proposed:`, so this section is the shape a flow will take
and does not run yet (CSD.md §1, `sketched`). Its order is the order
CIRISConstitution#165 fixes.

On the canonical's own machine, open Accord, then
`proposed:btn_accord_open_steward_purge`:

```yaml
expect:
  state: populated
  visible: ["proposed:card_purge_select", "proposed:chip_purge_kind_by_actor",
            "proposed:chip_purge_kind_by_predicate", "proposed:chip_purge_kind_by_classification",
            "proposed:chip_purge_kind_by_ids", "proposed:card_purge_ledger"]
  absent:  ["proposed:purge_ledger_error", "proposed:purge_loopback_only"]
```

Choose By predicate, type `trace_llm_calls.model = 'mock-model'`, give a
reason, then `proposed:btn_purge_dry_run`. The ledger is written first:

```yaml
expect:
  visible: ["proposed:txt_purge_selection_digest", "proposed:txt_purge_ledger_ref",
            "proposed:row_purge_sample_0", "proposed:txt_purge_not_reached"]
  count:   {of: "proposed:row_purge_count_*", min: 1}
  each:    {of: "proposed:row_purge_count_*", number: {min: 1}}
  one_of:  {"proposed:strip_purge_state": [dry_run]}
```

`proposed:btn_purge_propose` opens a three-fact confirm (`proposed:sheet_purge_notice`).
The facts say which canonical's corpus and how many rows, what changes (the
notice names this digest and nothing else), and who signs (this holder's token,
then the next). Cancel sends nothing. Confirming with one holder of two:

```yaml
expect:
  visible: ["proposed:txt_purge_notice", "proposed:txt_purge_quorum",
            "proposed:row_purge_cosigner_holder_a1"]
  one_of:  {"proposed:strip_purge_state": [proposed]}
  absent:  ["proposed:btn_purge_execute"]
```

After the second holder cosigns, `proposed:btn_purge_execute` appears. Its
destructive confirm needs the digest's first twelve characters typed into
`proposed:input_purge_confirm_digest`, as `withdraw canonical` already does
(CSD-067). Executing, then tombstoned, then settling:

```yaml
expect:
  visible: ["proposed:txt_purge_progress", "proposed:row_purge_plane_claims"]
  one_of:  {"proposed:strip_purge_state": [executing, tombstoned, settling]}
  absent:  ["proposed:txt_purge_deleted"]
```

The last line is the claim behind settle: in these three states, Deleted is not
drawn.

Run the dry-run, let a row change, then execute with the stale notice:

```yaml
expect:
  visible: ["proposed:txt_purge_refusal"]
  text:    {"proposed:txt_purge_refusal": "purge_selection_digest_mismatch"}
  absent:  ["proposed:txt_purge_progress"]
```

## 5. QA plan

**Platforms.** Desktop in practice. The ops act on the canonical's own store
behind loopback, as the other accord ops do. The card renders on all five, and
the other four draw `purge_loopback_only`.

**Acceptance (functional).**
1. The digest on the dry-run, the notice, the execute confirm and the ledger row
   is the same string.
2. Execute is absent below quorum, and a stale notice is refused by id.
3. Tombstoned and settling never read as deleted.
4. An abort names its record and plane, and the count after it is zero.
5. The word "withdrawn" appears nowhere on the card (claim 3).

**Not tested here, by design.**
* Whether the selection is the right one. That is the quorum's judgement, and
  the card's job is that they saw it.
* Replication of the tombstones to peers. That is the substrate's, and a
  two-node fixture with a purge is not on TOPOLOGY.md.
* The ledger's attestation at the next tag (#1028's predicate). That is persist's
  release discipline.

**What this does NOT guarantee, said on the card** (`proposed:txt_purge_not_reached`,
always present, CSD-065's rule):
* Copies that peers hold go only when they apply the tombstone.
* A score over real evidence stands. Only scores over inadmissible evidence go
  with it (CLM-derived-rows-follow-reason).
* A payload sealed in another signed envelope cannot be reached (CC 2.6.1.3,
  CSD-039).

## 6. Open questions for Persist (and Server), to answer on CIRISPersist#1046

1. **What the client calls.** `purge_rows` is an engine method. Nothing on the
   node serves it, and no CIRISServer issue asks for it. The client needs the
   seven calls in §3 or their equivalent. Who files the server ask: persist, as
   the door's owner, or this session?
2. **Refusal ids.** CIRISConstitution#165 names `purge_unauthorised` and
   `purge_selection_digest_mismatch`. The door also refuses a count mismatch
   (live ≠ dry-run, unless an override is logged) and aborts on an inadmissible
   retraction. Those two need wire ids too, and the abort needs a body naming
   the record, the plane and the count before it. The "override logged" path
   needs a decision: does the card offer an override, and who signs it?
3. **How the cosign is collected.** Is the notice a co-scrub partial, held
   byte-identical and handed from holder to holder like
   `/v1/accord/duty/{propose,cosign}`? Are the signing keys the root roster's
   hardware tokens? CSD-090 §3 records that CC 4.2.1 bounds what the
   humanity-accord keys may sign. CIRISConstitution#165 says the signature is
   under the root's `consensus_protocol` as a CC 4.5.5 `takedown`. So does the
   same holder token sign in a different capacity, or does a different key sign?
4. **Progress and settle.** Does `PurgeReport` stream per-plane progress, or
   answer once? What does "the retraction round settles" mean as something the
   card can read: a time, a round count, or peer acks? Is the hard-delete
   automatic after settle, or a second operator act?
5. **The by-actor kind and descend.** Once the generic door ships, does
   CSD-065's descend (community `slash`, quorum 2) route its payload leg through
   `purge_rows` by actor, under the community's authority? Or does `purge_rows`
   accept only the trust root's notice, so descend stays on `evict_actor`? The
   card keeps them apart either way. The answer decides whether the ladder's
   "what this does NOT reach" sentence for descend changes.
6. **The sample.** How many rows, chosen how (first N, random, one per table),
   and is the sample itself in the ledger so a cosigner can check it later?

## 7. What this card is not

* **Not the subject's erasure.** That is CSD-039, `POST /v1/auth/erasure`, a
  consent act.
* **Not a community's brake.** That is CSD-065's ladder, under `slash`.
* **Not un-trusting a root.** That is CSD-105, one acceptance edge on this node.
* **Not the "manifest superseded" state.** CIRISPersist#1029's wrong-hash
  CanonicalBuild manifests are re-posted to CIRISRegistry, which upserts them. They
  are not purged here, and a re-registered manifest is CC 3.1.2.1's `supersedes`,
  not a tombstone. How it reads is a note on the card that shows build verdicts,
  CSD-052 §3 ("A superseded manifest").
