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
   (`purge_selection_digest_mismatch`), whose live count differs from the
   dry-run's (`purge_selection_count_mismatch`), or that carries no adopted
   notice (`purge_unauthorised`). The card shows each refusal by id
   (CIRISConstitution#165, CIRISPersist#1046). **There is no override.** A
   changed selection has a different digest, so the only way forward is a fresh
   dry-run and a fresh notice, and the card offers exactly that.
2. **The order is fixed, and the card draws it in that order.** The steps are
   dry-run (the ledger is written FIRST, because the notice points at it), then
   the cosigned notice, then execute, then the tombstones replicate, then
   *settle*, then hard-delete. Settle is the retraction-round settle of
   CIRISPersist `FSD/HELD_RECORD_SETTLE.md`. Hard-delete is a second, explicit
   `purge_finalize(notice_id)` (CIRISPersist#1046). So *Settled* is a visible
   state with a Finalize action, and a row that has been tombstoned but not
   finalized is never drawn as "deleted".
3. **A purge is not a withdrawal.** The card never calls a purge "withdrawn" or
   "erased by the author". A `withdraws` is the author's or the subject's consent
   act (CC 2.4.1.1 rules 0–4), and it does not claim the row was false. A purge
   says the row never belonged, and it is carried by a tombstone the root cosigns
   (CIRISConstitution#165 §2). CSD-039's erasure is the subject's act. This card
   is the steward's act. The two have different words.
4. **An abort is named.** If a retraction cannot be admitted, the door stops at
   that record (`purge_retraction_inadmissible`, CIRISPersist#1046). Records
   before it stay retracted, nothing after it is touched, and a retry is
   idempotent. The card says exactly that: "N of M retracted; retry continues",
   with the record and its plane. It never shows a partial purge as done.

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
Descend itself moves onto `purge_rows` by actor, with `evict_actor` still the
bytes-plane primitive underneath (#1046 answer 5). It keeps its own community
authority and its own card.

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
    renders: "the first 20 ids in JCS order (deterministic, CIRISPersist#1046), ids short and dates shown, so 'these are the rows I mean' can be checked by eye before anyone signs. It is a view of the ledger, not a separate record, and 'See every id' opens the paged ledger"
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
    type: "enum[dry_run,proposed,authorised,executing,stopped,tombstoned,settling,settled,finalized]"
    example: "settled"
    renders: "one step strip in the order CIRISConstitution#165 fixes: Dry-run → Notice → Authorised → Executing → Tombstoned → Settling → Settled → Finalized. Settled carries the Finalize action. Stopped (an inadmissible retraction) is drawn off the strip, in the danger tone, with its retry. A state this app does not know is shown verbatim and never as Finalized"
    tag: "proposed:strip_purge_state"
  - ceg: x_private:purge_progress
    use: display-only
    type: string
    example: "claims plane — 4,210 of 21,836 retracted"
    renders: "one row per plane (claims, bytes, keys, entities), each with its phase and retracted / total. The door runs in phases per plane and returns a per-plane report; the route may poll or stream it (CIRISPersist#1046). The card draws the same rows either way"
    tag: "proposed:row_purge_progress_${plane}"
  - ceg: x_private:purge_refusal
    use: display-only
    type: "enum[purge_unauthorised,purge_selection_digest_mismatch,purge_selection_count_mismatch]"
    example: "purge_selection_count_mismatch"
    renders: "by id, each with its own sentence, and nothing purged in any of them. Unauthorised: 'No adopted notice names this selection.' Digest mismatch: 'The rows changed since the dry-run, so the notice no longer describes them.' Count mismatch, with its fields {table, expected, found}: 'trace_events: the notice covers 21,836 rows and 21,840 are there now.' The two mismatches end in the same single action, 'Run a fresh dry-run' (a new digest needs a new notice). There is no override"
    tag: "proposed:txt_purge_refusal"
  - ceg: x_private:purge_retraction_inadmissible
    use: display-only
    type: string
    example: "4,210 of 23,335 retracted; retry continues. Stopped at trace 7f21… (claims plane): its retraction could not be admitted."
    renders: "purge_retraction_inadmissible, from its fields {record_id, plane, index, total}: 'index of total retracted; retry continues', then the record and its plane. Earlier records stay retracted, nothing after is touched, and the Retry button re-sends the same notice because a retry is idempotent (CIRISPersist#1046)"
    tag: "proposed:txt_purge_stopped"
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
    example: "Tombstoned 14:20. Waiting for replication peers to acknowledge the tombstones; the rows are still on this node."
    renders: "Settling, then Settled: the retraction-round settle of CIRISPersist FSD/HELD_RECORD_SETTLE.md (tombstones acknowledged by replication peers). Settled shows 'The rows are still on this node until you finalize' and the Finalize button. What the route reports while settling (an ack count, or only the settled flag) is not in the door's answer, so the card claims no percentage until it is"
    tag: "proposed:txt_purge_settling"
  - ceg: x_private:purge_finalized
    use: display-only
    type: string
    example: "Finalized 2026-10-10T09:12Z · 23,335 rows hard-deleted · the tombstones remain"
    renders: "after purge_finalize(notice_id): when, how many rows, and that the tombstones outlive the rows (CIRISConstitution#165 §3). Reached only through the Finalize ConfirmSheet"
    tag: "proposed:txt_purge_finalized"
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
    example: "2026-10-09 · 9f2c40ab77e1 · notice att-4c1d… · 23,335 rows · Finalized"
    renders: "one row per past purge: date, digest, notice id, row total, final state and the ledger path. It opens the per-table counts, the per-plane rows above, and every id in the ledger, paged (the ledger holds all ids; the sample is its first page in JCS order)"
    tag: "proposed:row_purge_ledger_${digest}"
  - ceg: x_private:purge_ledger_id
    use: display-only
    type: "list[string]"
    example: ["7f21… · trace_events · selected", "a03b… · scores · derived"]
    renders: "every id in a purge's ledger, paged in JCS order, each with its table and whether it was selected or derived. The first page is the dry-run's sample"
    tag: "proposed:row_purge_ledger_id_${i}"
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

**The four typed refusals are not the error state.** The error state means the
ledger could not be read. A refusal means the node answered and said no, so
each refusal is drawn on the purge in flight, under its own id, with its own
fields:

| refusal id (CIRISPersist#1046 / CIRISConstitution#165) | fields | where | what it says |
|---|---|---|---|
| `purge_unauthorised` | none | `txt_purge_refusal` | no adopted notice names this selection; nothing purged |
| `purge_selection_digest_mismatch` | none | `txt_purge_refusal` | the selection changed since the notice; nothing purged; offers a fresh dry-run |
| `purge_selection_count_mismatch` | `{table, expected, found}` | `txt_purge_refusal` | one line per table; nothing purged; offers a fresh dry-run. **No override** |
| `purge_retraction_inadmissible` | `{record_id, plane, index, total}` | `txt_purge_stopped` | "index of total retracted; retry continues", plus the record and its plane; offers Retry (idempotent) |

## 3. Contracts (who)

**There is no route yet.** The door is persist's (CIRISPersist#1046, open,
v54.1.0). The HTTP surface is CIRISServer's. Persist has asked the Server
session to file the ask for these routes, citing #1046 and this CSD, and **it is
not filed yet**. Every row below is `missing` and **asked of CIRISServer, not
filed yet**. The shapes are what the card needs, so the server can answer one
ask instead of eight.

| value | endpoint | owner | state |
|---|---|---|---|
| dry-run a selection; write the ledger | proposed: `POST /v1/accord/purge/dry-run` `{selection:{kind, …}, reason}` → `{selection_digest, ledger_ref, counts:[{table, rows, derived}], sample:[20 ids, JCS order], live_count}` | CIRISServer over `Engine::purge_rows` dry-run (CIRISPersist#1046) | **missing; asked of CIRISServer, not filed yet.** The sample is deterministic: the first 20 ids in JCS order, a view of the ledger (#1046 answer 6) |
| propose the notice (holder #1 signs) | proposed: `POST /v1/accord/purge/propose` `{holder{key_id, mldsa_usb_path, pkcs11}, selection_digest, ledger_ref, reason}` → `{partial, scrub_count, quorum_needed, adopted}` | CIRISServer | **missing; asked of CIRISServer, not filed yet.** The flow is the client and server's own propose/cosign, as `/v1/accord/duty/propose` and `/v1/accord/canonical/propose` do it. The door only verifies the notice's cosignatures against the root's `consensus_protocol` (#1046 answer 3) |
| the next holder cosigns | proposed: `POST /v1/accord/purge/cosign` `{holder, partial}` | CIRISServer | **missing; asked of CIRISServer, not filed yet.** The partial goes back byte-identical |
| notices below quorum | proposed: `GET /v1/accord/purge/pending` | CIRISServer | **missing; asked of CIRISServer, not filed yet** |
| execute | proposed: `POST /v1/accord/purge/execute` `{notice_id, selection_digest}` | CIRISServer over `purge_rows(selection, authorisation, now)` | **missing; asked of CIRISServer, not filed yet.** Refusals by id, each with its body: `purge_unauthorised`, `purge_selection_digest_mismatch` (CIRISConstitution#165), `purge_selection_count_mismatch {table, expected, found}`, `purge_retraction_inadmissible {record_id, plane, index, total}` (#1046 answer 2). No override field: a changed selection needs a fresh dry-run and a fresh notice |
| a purge's state and per-plane progress | proposed: `GET /v1/accord/purge/{selection_digest}` | CIRISServer | **missing; asked of CIRISServer, not filed yet.** The door returns a phased report per plane, and the route may poll or stream it (#1046 answer 4). The card draws `row_purge_progress_<plane>` either way, and reads Settling and Settled from the same body |
| settle | the retraction-round settle: tombstones acknowledged by replication peers | CIRISPersist `FSD/HELD_RECORD_SETTLE.md` | **defined; not on any wire yet.** What the route reports while settling (an ack count, or only the settled flag) is still to be named, so `x_private:purge_settle` stays `unconfirmed` |
| finalize (hard-delete) | proposed: `POST /v1/accord/purge/finalize` `{notice_id}` → over `purge_finalize(notice_id)` | CIRISServer over CIRISPersist (#1046 answer 4) | **missing; asked of CIRISServer, not filed yet.** It is a second, explicit act after Settled, never automatic. It sits behind its own three-fact ConfirmSheet (§4) |
| the ledger, every id, paged | proposed: `GET /v1/accord/purge/ledger` and `GET /v1/accord/purge/ledger/{selection_digest}?page=` → the rows of `evidence/purges/<date>-<digest>.tsv` | CIRISServer · CIRISPersist (the TSV, attested at the next tag, #1028's predicate) | **missing; asked of CIRISServer, not filed yet.** The ledger holds every id. The sample is its first page |
| who may call these | loopback plus the owner session, as the other accord ops are gated | CIRISServer | **missing**. The ops act on the canonical's own store. Off the machine the card draws `purge_loopback_only`, never an empty ledger (CSD-105's precedent, CIRISServer#652) |
| the holders, for the picker | `GET /v1/accord-holders` | CIRISServer (`src/accord.rs:2601`) | **live**, already called by CSD-090 and CSD-067 |

**The routes are prose until the server's issue names them.** The proposed
literals sit under `/v1/accord/` because that is where the root's other cosigned
ops live. The server may choose differently. When it does, these rows change to
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
  count:   {of: "proposed:row_purge_sample_*", max: 20}
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
  visible: ["proposed:row_purge_progress_claims", "proposed:row_purge_plane_claims"]
  one_of:  {"proposed:strip_purge_state": [executing, tombstoned, settling]}
  absent:  ["proposed:txt_purge_finalized", "proposed:btn_purge_finalize"]
```

Neither the Finalize button nor "finalized" appears before the round settles.
Once it does:

```yaml
expect:
  visible: ["proposed:txt_purge_settling", "proposed:btn_purge_finalize"]
  one_of:  {"proposed:strip_purge_state": [settled]}
  absent:  ["proposed:txt_purge_finalized"]
```

`proposed:btn_purge_finalize` opens its own destructive ConfirmSheet
(`proposed:sheet_purge_finalize`) with three facts:

* *which rows*: the digest, and the row total from the ledger;
* *what changes*: the rows are hard-deleted from this canonical, and the
  tombstones stay;
* *who acts*: this node, under notice att-4c1d…, with no new signature.

Cancel sends nothing. Confirming draws `proposed:txt_purge_finalized`, and the
strip reads Finalized.

Run the dry-run, let a row be added, then execute with the stale notice:

```yaml
expect:
  visible: ["proposed:txt_purge_refusal", "proposed:btn_purge_fresh_dry_run"]
  text:    {"proposed:txt_purge_refusal": "purge_selection_count_mismatch"}
  absent:  ["proposed:row_purge_progress_claims", "proposed:btn_purge_override"]
```

The absent `btn_purge_override` records the decision: no override path exists,
so none can be drawn.

When a retraction is inadmissible partway through:

```yaml
expect:
  visible: ["proposed:txt_purge_stopped", "proposed:btn_purge_retry"]
  matches: {"proposed:txt_purge_stopped": "^[0-9,]+ of [0-9,]+ retracted; retry continues\\..*"}
  one_of:  {"proposed:strip_purge_state": [stopped]}
```

## 5. QA plan

**Platforms.** Desktop in practice. The ops act on the canonical's own store
behind loopback, as the other accord ops do. The card renders on all five, and
the other four draw `purge_loopback_only`.

**Acceptance (functional).**
1. The digest on the dry-run, the notice, the execute confirm, the finalize
   confirm and the ledger row is the same string.
2. Execute is absent below quorum. Each of the four refusals renders by id with
   its fields, and no override control exists.
3. Tombstoned, Settling and Settled never read as finalized. Finalize is only
   offered at Settled, and only through its ConfirmSheet.
4. A stop reads "N of M retracted; retry continues" and names the record and
   plane. Retry re-sends the same notice.
5. The sample is at most 20 ids and matches the first page of the paged ledger.
6. The word "withdrawn" appears nowhere on the card (claim 3).

**Not tested here, by design.**
* Whether the selection is the right one. That is the quorum's judgement, and
  the card's job is that they saw it.
* Replication of the tombstones to peers, and the settle itself. That is the
  substrate's (`FSD/HELD_RECORD_SETTLE.md`), and a two-node fixture with a purge
  is not on TOPOLOGY.md.
* The ledger's attestation at the next tag (#1028's predicate). That is persist's
  release discipline.

**What this does NOT guarantee, said on the card** (`proposed:txt_purge_not_reached`,
always present, CSD-065's rule):
* Copies that peers hold go only when they apply the tombstone.
* A score over real evidence stands. Only scores over inadmissible evidence go
  with it (CLM-derived-rows-follow-reason).
* A payload sealed in another signed envelope cannot be reached (CC 2.6.1.3,
  CSD-039).

## 6. Questions put to Persist, and the answers (CIRISPersist#1046)

All six were answered on CIRISPersist#1046. They are recorded here so nobody
asks them again, along with what each answer changed on this card.

1. **What the client calls: resolved.** The door is persist's and the HTTP
   surface is CIRISServer's. Persist asked the Server session to file the
   `/v1/accord/purge/*` ask, citing #1046 and this CSD. It is not filed yet.
   §3 marks every route "asked of CIRISServer, not filed yet".
2. **Refusal ids: resolved.** They are `purge_selection_count_mismatch
   {table, expected, found}` and `purge_retraction_inadmissible {record_id,
   plane, index, total}`, beside #165's two. The door stops at the first
   inadmissible retraction. Records before it stay retracted, nothing after it is
   touched, and a retry is idempotent. **There is no override path**, because the
   notice binds the digest. The draft's "override logged" is withdrawn, and the
   card has no override state or control (the refusal table under §2's states, §4).
3. **How the cosign is collected: resolved.** It uses the client and server's
   propose/cosign flow (§3). Persist only verifies the cosignatures against the
   root's `consensus_protocol`. **Still open, on CIRISConstitution#165, not
   here:** which keys may sign, given CC 4.2.1's limits on humanity-accord keys
   (CSD-090 §3).
4. **Progress and settle: resolved.** Progress is a phased report per plane,
   polled or streamed, and the card draws one row per plane. Settle is the
   retraction-round settle of CIRISPersist `FSD/HELD_RECORD_SETTLE.md`.
   Hard-delete is a second, explicit `purge_finalize(notice_id)`, so Settled is a
   visible state with Finalize behind its own ConfirmSheet. **Still to name:**
   what the route carries while settling, so `x_private:purge_settle` stays
   `unconfirmed`.
5. **Descend: resolved.** CSD-065's tier 3 moves onto `purge_rows` by actor,
   and `evict_actor` remains the bytes-plane primitive underneath. CSD-065's
   tier-3 row says so.
6. **The sample: resolved.** It is the first 20 ids in JCS order plus per-table
   counts. The ledger holds every id, and the sample is a view of it, so the
   ledger view pages every id (`row_purge_ledger_id_<i>`).

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
