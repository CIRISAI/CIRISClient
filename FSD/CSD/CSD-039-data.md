# CSD-039 — Data (My things › Everything I shared › Data)

**CSD**: CSD-039 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: the erasure half is staged at `testing/flows/drafts/csd-039-data-erasure.yaml`
(`client: unreleased`); the sharing half is unwritten — its tags are the contract the flow will drive

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can see what this node has shared outward on their behalf, turn that
sharing off, ask for what was already sent to be deleted, and — separately and
with a much louder warning — erase the account and the signing key on this
device.**

Serves **CC 3.3.1**, subject-side consent authority: "the person a Contribution
is *about* can grant, scope, and revoke its use, not only the person who produced
it." The lifecycle this card drives is CC 3.3.1's, in order:
`consent:state:granted` → `withdraws` → the producer's
`consent:deletion_sla:{days}` clock → `consent:deletion_complete`, or the
substrate's `hard_case:consent_sla_breach` when the clock runs out.

The instrument is named **Everything I shared** and that name is a promise this
card does not yet keep (§6): it can stop and delete, and it cannot *show*.

**Erasure, and proof that it happened.** This card is also where a person
erases, and it is the only place: the lens-trace delete, the account reset and
the key wipe were already here, and the node's trace erasure and the agent's
deletion receipts were added here rather than as a second card (a new card for
the same act under another name is the duplication this repo measures). GDPR
Art. 17 is the right it serves, and four CC clauses shape what it may say:
CC 3.3.1 D1 — *proof of deletion is the absence of the row, not a new
artifact*; CC 6.1.5 N5 and CC 6.1.2 — the erasure guarantee is "not
individually recoverable", and an implementation MUST NOT present a surviving
aggregate as an erasure guarantee (*aggregation is not anonymization*), which
is why the card says detections are *unlinked*, never *erased*; CC 2.6.1.3's
redaction ruling (CIRISConstitution#78) — a payload sealed in a signed envelope
cannot be redacted after the fact — which is why the card must say what cannot
be erased at all; and CC 2.4.1.1's carve-out, which forbids a subject-authority
`withdraws` of third-party `capacity:*` / `detection:*` rows. The
falsifiable claim: **after an erasure the card shows exactly what the host
reported — its counts, its time, its scope note, and whether a receipt exists —
and never "done" on a partial, refused or ambiguous answer.** And before any
erasure it says, in three rows, what erasure reaches, what it does not, and
that erasing the *person* is not possible from this app today. Deleting traces
must never read as deleting a person.

## 2. Surface (what)

```yaml csd:surface
surface: data
screen: DataManagement
```

`nav_map` derives `btn_my_things -> nav_instrument_everything_i_shared ->
nav_epistemic_data`.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "consent:{kind}"
    bind: {kind: state}
    use: display-only
    type: "enum[granted,revoked]"
    example: "granted"
    renders: "the accord-sharing switch — on is `consent:state:granted` to the canonical servers; off is the withdraw"
    tag: switch_consent
  - ceg: "consent:{kind}"
    bind: {kind: replication}
    use: display-only
    type: string
    example: "peered"
    renders: "Community trust — the bilateral peering state with this node's federation peers"
    tag: community_peer_state
  - ceg: x_private:pending_peer_requests
    use: display-only
    type: int
    example: 2
    renders: "2 pending"
    tag: community_pending
  - ceg: x_private:consent_state_summary
    use: display-only
    type: string
    example: "granted · 3 peers"
    renders: "the one-line consent summary under Community trust"
    tag: community_consent_state
  - ceg: x_private:lens_identifier
    use: display-only
    type: string
    example: "a4f1…9c02"
    renders: "mobile.data_agent_hash_label — the pseudonymous id the traces were filed under"
    tag: data_row_lens_identifier
  - ceg: x_private:events_sent
    use: display-only
    type: int
    example: 412
    renders: "Events sent — 412"
    tag: data_row_events_sent
  - ceg: x_private:events_queued
    use: display-only
    type: int
    example: 0
    renders: "Events queued — 0 (drawn only when non-zero)"
    tag: data_row_events_queued
  - ceg: "consent:{kind}"
    bind: {kind: deletion_sla}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Deadline — 'None reported.' After a lens-deletion request the card draws the agent's answer as rows that stay (data_lens_deletion_requested: Requested — not done; CIRISLens accepted / did not; local consent revoked / still on) and this row says the SLA the agent does not return. REQUESTED is the most the card may say, and it never says done."
    tag: data_row_deletion_sla
    blocked_by: CIRISAgent#1212
  - ceg: "consent:{kind}"
    bind: {kind: scope}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "NOT RENDERED. CC 3.3.1's scopes — retain / share / analyze / train / publish — are collapsed here into one on/off switch."
    tag: "proposed:data_row_consent_scope"
    blocked_by: CIRISAgent#1212
  - ceg: x_private:trace_erasure_outcome
    use: display-only
    type: "enum[erased,nothing_there,not_confirmed]"
    example: "erased"
    renders: "Result — Erased on this node | Nothing to erase | Not confirmed. `erased` ONLY when the node said erased:true with a non-zero count (Erasure.kt TraceErasureResult.outcome)"
    tag: data_erase_result_outcome
  - ceg: x_private:trace_events
    use: display-only
    type: int
    example: 412
    renders: "Traces erased — 412 (the node's `trace_events`, verbatim; 0 is the node's zero, not a default)"
    tag: data_erase_result_trace_events
  - ceg: x_private:trace_llm_calls
    use: display-only
    type: int
    example: 1830
    renders: "Model calls erased — 1830"
    tag: data_erase_result_llm_calls
  - ceg: x_private:detection_events_tombstoned
    use: display-only
    type: int
    example: 7
    renders: "Detections unlinked — 7 (tombstoned: the numbers survive, the link to the agent is cut)"
    tag: data_erase_result_detections
  - ceg: x_private:erased_at
    use: display-only
    type: timestamp
    example: "2026-09-25T18:02:11+00:00"
    renders: "Erased at — the node's `erased_at`, mono"
    tag: data_erase_result_erased_at
  - ceg: x_private:erasure_scope_note
    use: display-only
    type: string
    example: "traces only — payloads in attestation/registration envelopes … are NOT reached by any erasure primitive (CIRISPersist#573)"
    renders: "The node's note — its own `scope_note`, verbatim"
    tag: data_erase_result_scope_note
  - ceg: hard_case:{kind}
    bind: {kind: trace_erasure}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Receipt — 'None. This node does not issue one; it records the erasure as hard_case:trace_erasure.' The row persist emits is not returned, so the card names it and cannot link it."
    tag: data_erase_result_receipt
    blocked_by: CIRISServer#677
  - ceg: x_private:erasable_payloads
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Does not reach — chats, notes, files, consent records, anything in a signed envelope, every copy on another node or in CIRISLens. Nothing minted today is erasable."
    tag: data_erase_does_not_reach
    blocked_by: CIRISPersist#914
  - ceg: x_private:withdraws_failed
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Erasing you — 'Not possible from this app yet.' `/v1/auth/erasure` is fail-honest (`withdraws_failed`, `incomplete`) and the card's classifier is written and tested (ActorErasureReport.outcome: any failed withdraw or `incomplete` is Partial, never done), but the route takes only a subject-signed request."
    tag: data_erase_self_blocked
    blocked_by: CIRISServer#677
  - ceg: x_private:deletion_signing_key_id
    use: display-only
    type: string
    example: "agent-7f3c…"
    renders: "Signing key — the agent's current Ed25519 key id, mono; Public key — its base64, or — when the .pub download fails"
    tag: data_receipts_key_id
  - ceg: x_private:deletion_proof_valid
    use: display-only
    type: bool
    example: true
    renders: "Signature — 'Checks: signed by this agent's key' | 'Does not check' | 'Cannot be checked here' (the proof names a key other than the agent's current one: the agent verifies against its CURRENT key only and ignores the key id the receipt names, CIRISAgent#1220 — so a genuine receipt from before a rotation must not read as forged; data_receipt_key_not_current says why)"
    tag: data_receipt_verdict
  - ceg: x_private:deletion_proof_records
    use: display-only
    type: int
    example: 15
    renders: "Records deleted — 15 when the signature checks (the agent's `total_records`); 'Records it claims (not verified)' from the pasted proof otherwise"
    tag: data_receipt_records
  - ceg: consent:{kind}
    bind: {kind: deletion_complete}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Receipts issued — 'This agent does not issue receipts yet.' `sign_deletion_proof` (verification.py:106) has no caller on CIRISAgent main; a completed DSAR emits nothing."
    tag: data_receipts_none_issued
    blocked_by: CIRISAgent#1207
```

The erasure section's four states, which are not the sharing half's:
`data_erase_working` (loading, inline progress); `data_erase_nothing` (the node
answered all-zero counts — EMPTY, and a fact about that id on this node);
`data_erase_partial` / `data_erase_refused` / `data_erase_error` (three
different failures, three tags); `data_erase_not_on_this_host` (a node without
the route — said in words, never an empty card). The receipt section mirrors
them: `data_receipts_key_loading`, `data_receipts_key_not_on_this_host`,
`data_receipts_key_refused` (e.g. the agent's 503 "Signing substrate not
available"), `data_receipt_unreadable`, `data_receipt_checking`.

`consent:{kind}` is **reserved** (CC 3.4.5) and owned by CIRISAgent, so every row
above is `display-only`. The client never emits a consent row; it asks the node
to, which is the same posture as the contacts and delegation cards.

```yaml csd:states
populated: {tag: data_loaded, renders: "the sharing card, the erasure section, the receipt section (agent attached), reset and wipe"}
empty:     {tag: "proposed:data_empty", renders: "Nothing has been shared from this node. — for the accord block when `eventsSent == 0`; today the counters render 0, which reads as data. On a build WITHOUT an agent the block is data_accord_not_on_this_node — 'Sharing settings live with an agent, and this node runs without one. Nothing has been shared from here.' — which is that build's true empty, and no Enable button is drawn over it"}
loading:   {tag: data_loading, renders: "mobile.data_loading beside a progress affordance"}
error:     {tag: data_error, renders: "the read or the act failed — a StateBlock at the top of the card that stays until the next refresh. It was a snackbar until 2026-09-28, an error state with a timer on it. An accord read that failed for a reason other than an absent route is data_accord_error in the sharing card's place"}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the pseudonymous trace id | `GET /v1/my-data/lens-identifier` | **CIRISAgent** (`routes/my_data.py:379`) and CIRISServer (`src/system_data.rs:387`) | live on both |
| accord sharing settings + counters | `GET /v1/my-data/accord-settings` | **CIRISAgent only** (`routes/my_data.py:610` on main, re-read 2026-09-25) | agent-only — no `accord-settings` route in CIRISServer `src/*.rs` on 0.5.217. Without an agent the card says so (`data_accord_not_on_this_node`) instead of drawing "Enable" over a read nobody got |
| the sharing switch — **this card OWNS the write** | `PUT /v1/my-data/accord-settings` (`switch_consent` → `updateAccordConsent`) | **CIRISAgent only** (`routes/my_data.py:753`) | live. Until 2026-09-28 this PUT was issued from THREE screens — here, Manage Consent's traces switch (CSD-053) and Add Federation ID's step 4 (CSD-086) — and the route checker's duplicate-mutation ratchet listed all three. Folded to this card, which is the one that reads the setting back; the other two link here (`btn_open_data_sharing`, `txt_fedid_traces_elsewhere`). One act, one door |
| load the accord adapter | `POST /v1/system/adapters/{}` (`btn_enable_accord` → `enableAccordMetrics`, `ciris_accord_metrics` with `consent_given`), then `authorFederationConsent` | CIRISAgent | live; drawn only with an agent attached and the adapter unloaded. Also called by Adapters (CSD-020) — the same adapter load under its general form; two doors by design, recorded in the baseline |
| delete the traces already sent | `DELETE /v1/my-data/lens-traces` | **CIRISAgent only** (`routes/my_data.py:489` on main) | **wrong-host** — no such route in CIRISServer |
| **erase one agent's traces on this node** | `POST /v1/federation/erase-agent-traces` `{agent_id_hash, reason}` (both mandatory) → `{erased, agent_id_hash, trace_events, trace_llm_calls, detection_events_tombstoned, erased_at, scope_note}` | CIRISServer (`src/federation_admin.rs` handler ~:477, body ~:533-548, mounted ~:907; `origin/main` 046e1b39, 0.5.217) | **live; now called** — `CIRISApiClient.eraseAgentTraces` to the NODE URL. Owner-binding + owner session + `CapabilityVerb::Peer`; 403/401 `{error}`. One persist transaction, idempotent. TRACES ONLY |
| erase the person (right to be forgotten) | `POST /v1/auth/erasure` `{attesting_key_id}` → `{blobs_evicted, withdraws_emitted, withdraws_failed, incomplete}` | CIRISServer (`src/auth/erasure.rs:118`, response `:53-60`) | **unreachable from the product** — authorizes by `verify::verify_request`, a SUBJECT-SIGNED request; the client does no crypto. `blocked_by: CIRISServer#677` (owner-session path + a receipt) |
| the agent's receipt key | `GET /v1/verification/keys/current` → `data{public_key_id, download_url, algorithm}`; `GET /v1/verification/keys/{key_id}.pub` → base64 text | CIRISAgent (`routes/verification.py:432`, `:349`; main 29371660) | live; public; **now called**. `.pub` 404s for any key but the current one |
| check a receipt | `POST /v1/verification/deletion` `{deletion_proof}` → `data{valid, …, total_records, message, verified_at}` (HTTP 200 either way) | CIRISAgent (`verification.py:184`) | live; public; **now called**. Verifies against the CURRENT key only (`:144`) — the proof's own `public_key_id` is never consulted |
| same check, flat fields | `POST /v1/verification/verify-signature` | CIRISAgent (`verification.py:390`) | live; **deliberately not called** — the same `_verify_proof` as `/deletion` with the fields unwrapped; a second door to one check |
| a human-readable receipt page | `GET /v1/verification/public/{deletion_id}` | CIRISAgent (`verification.py:218`) | live; **not called** — static HTML that echoes the id and looks nothing up, so linking it would imply a verification that page does not do |
| **a receipt to check** | — (`sign_deletion_proof`, `verification.py:106`, has no caller on main) | CIRISAgent | **missing** — `blocked_by: CIRISAgent#1207`. The card verifies a pasted receipt; nothing mints one |
| an erasable payload | — (the disclosure store is unbuilt) | CIRISPersist | **missing** — `blocked_by: CIRISPersist#914`: nothing minted today is erasable |
| peers / peering state | `GET /v1/federation/peers` | CIRISServer | live — `src/federation_peers.rs` |
| grant federation consent | `GET /v1/accord/canonical/servers` (`src/accord_provision.rs:3711`), then `POST /v1/federation/consent` (owner-gated) — `authorFederationConsent`, `CIRISApiClient.kt` ~5270 | CIRISServer | live. (`/v1/accord/canonical-servers`, the path this row first named, 404s — the client's own comment records it) |
| erase the account | `POST /v1/system/data/reset-account` | CIRISServer | live — `src/system_data.rs:379`, owner + `CapabilityVerb::Wipe`, non-delegable |
| erase the signing key | `POST /v1/system/data/wipe-signing-key` | CIRISServer | live — `src/system_data.rs:383`, same gate. Both of these go to `baseUrl`, the front door: the node's route on a node-only install, the agent's on an agent build (the route checker attributes them to the agent for that reason) |
| after a reset or a wipe: stop the runtime | `POST /v1/system/local-shutdown`, then `GET /v1/system/health` | CIRISAgent | live; **not this card's act** — reached through `onResetSetup` (CIRISApp's `Screen.DataManagement` arm): on desktop the app asks the runtime to shut down and polls its health before Startup relaunches it. Cited here because the route checker's closure follows the arm; the card itself never calls either |
| **see what was shared** | — | — | **missing everywhere** — see §6 |
| `consent:deletion_sla` / `consent:deletion_complete` | — | CIRISAgent | **missing** — blocks `building` for the two rows above |

CIRISAgent's tree here is dated 2026-08-15; the three `my-data` routes may have
moved since, and this row should be re-read before the stage advances.

## 4. Flow (how)

Sign in on a node with a brain; open My things › Everything I shared › Data.

```yaml
expect:
  state: populated
  visible: [switch_consent, community_consent_state, "proposed:data_row_events_sent"]
  number: {"proposed:data_row_events_sent": {min: 0}}
```

Turn sharing off: click `switch_consent`.

```yaml
expect:
  text: {community_consent_state: "revoked"}
```

Ask for the traces already sent to be deleted: type a reason into
`input_delete_traces_reason`, click `btn_delete_traces`. Three facts, and the
third names who signs — nobody:

```yaml
expect:
  visible: [sheet_delete_traces, delete_traces_fact_1, delete_traces_fact_2, delete_traces_fact_3]
  text: {delete_traces_fact_3: "Nobody. The agent files the request under your session; it does not sign it as you (CIRISAgent#1212)."}
```

Click `btn_delete_traces_confirm`. What comes back stays on the card, and reads
REQUESTED — never done:

```yaml
expect:
  visible: [data_lens_deletion_requested, data_lens_deletion_lens, data_lens_deletion_local, data_row_deletion_sla]
  text: {data_lens_deletion_status: "Requested — not done", data_row_deletion_sla: "None reported. The agent returns no deletion deadline and no completion (CIRISAgent#1212), so this stays at requested."}
```

That last block used to fail; it passes now and says "none reported". The
number in that row is the fix CIRISAgent#1212 owes.

**Reset and wipe** each confirm with three facts (`sheet_reset` / `sheet_wipe_key`,
`*_fact_1..3`), the third being *"Your owner session on this node. The node does
this itself; nothing is signed as you, and it cannot be undone."* — and a flow
cancels both (`btn_reset_cancel`, `btn_wipe_key_cancel`), because both succeed.

**Erasure on this node** (every build; staged as
`testing/flows/drafts/csd-039-data-erasure.yaml`). Scroll to
`data_erase_traces_section`:

```yaml
expect:
  visible: [data_erase_reaches, data_erase_does_not_reach, data_erase_self_blocked,
            input_erase_agent_id_hash, input_erase_reason, btn_erase_traces]
```

Enter an id and a reason, click `btn_erase_traces`:

```yaml
expect:
  visible: [sheet_erase_traces, erase_traces_fact_1, erase_traces_fact_2, erase_traces_fact_3]
```

Click `btn_erase_traces_cancel` — nothing is erased and no result renders. On a
throwaway node only, confirm with an id nothing was filed under:

```yaml
expect:
  visible: [data_erase_nothing, data_erase_result_outcome, data_erase_result_receipt]
  number: {data_erase_result_trace_events: {eq: 0}}
```

**Receipts** (agent attached): paste something that is not a receipt, click
`btn_check_receipt`:

```yaml
expect:
  visible: [data_receipts_none_issued, data_receipt_unreadable]
  absent: [data_receipt_verdict]
```

On a **node-only**
build, where `accord-settings` and `lens-traces` have no host, the sharing
block says so — a fact about this build, not an error, and not "Enable":

```yaml
expect:
  visible: [data_accord_not_on_this_node, data_erase_traces_section]
  absent: [btn_delete_traces, btn_enable_accord, switch_consent, data_receipts_section]
```

## 5. QA plan

**Platforms.** All five. The destructive pair — `btn_reset_account`,
`btn_wipe_signing_key` — runs **last**, on a throwaway node, because both
succeed.

**Erasure.** The confirm-then-cancel steps run everywhere; the erasing step
runs only on a throwaway node and uses an id nothing was filed under, so even
there it removes nothing. A real erasure with non-zero counts is not driven by
any flow: the fixture has no agent whose traces it may destroy. The receipt
verdict (`data_receipt_verdict`) is **disclaimed**: no route mints a receipt
(CIRISAgent#1207), so a flow can only paste one it forged, and the honest
assertion for a forgery is `data_receipt_unreadable` or "does not check" — the
classifier's three verdicts are pinned by `DataErasureControllerTest` instead.

**Not tested here.** `btn_wipe_signing_key`'s actual effect (it destroys the
key the rest of the matrix signs with); the deletion SLA, which has no route;
whether CIRISLens honoured the deletion, which is by construction not observable
from this client.

## 6. Delta — card vs API vs CC

* **"Everything I shared" cannot show anything shared.** The card has a switch,
  two counters and two deletion buttons, and no list. CC 4.5.2.2 names the query
  shape for GDPR Art. 20 — `attestations.where(s ∈ subject_key_ids)` — and marks
  the mapping *informational*; CIRISAgent has `/v1/dsar/*` routers. **Ask
  (CIRISServer or CIRISAgent, whichever owns the corpus):** serve that query as a
  paged route, so this card can list the Contributions naming this person and the
  instrument's name stops over-promising. This is the single largest gap in my
  area.
* **CC 3.3.1's scopes are collapsed to a switch.** CC 3.3.1 defines
  `consent:scope:{kind}` over `retain / share / analyze / train / publish`, with
  sub-scoping in the token (`retain:90d`, `share:cohort:family`). The card offers
  on/off. A person who will accept `retain` and refuse `train` has no way to say
  so, and CC 3.4.5 is explicit that bundling voids the grant: a grant "MUST NOT
  condition admission to **unrelated** functions … bundling the grant across
  functions voids it." **Ask (CIRISClient + CIRISAgent):** per-scope switches
  behind the one summary line.
* **Deletion has no receipt.** CC 3.3.1's D1 says a producer has proven deletion
  "iff, before the deadline, the row was withdrawn, superseded, or hard-deleted",
  and deliberately declines a separate `deletion_proof` artifact. That makes the
  SLA clock the only thing a person can watch, and the card does not show it.
  **Ask (CIRISAgent):** return `consent:deletion_sla:{days}` from
  `DELETE /v1/my-data/lens-traces` and expose `consent:deletion_complete` so the
  row can flip from "requested" to "done".
* **Two of the three read routes are on the brain — said now (2026-09-28).** On a
  node-only build the accord block and the trace-deletion button have no host at
  all; the screen used to render a snackbar that vanished. The sharing block now
  says the agent is not here (`data_accord_not_on_this_node`) and draws no
  control; a failed read that is not an absent route is `data_accord_error`; and
  the card's own errors are a persistent, tagged block (`data_error`), the same
  fix as Settings (CSD-022).
* **Every irreversible act here confirms with three facts (2026-09-28).** Reset,
  wipe and the lens-deletion request were `AlertDialog`s with a generic body;
  each is a `ConfirmSheet` now (`sheet_reset`, `sheet_wipe_key`,
  `sheet_delete_traces`), keeping its `btn_*_confirm` / `btn_*_cancel` tags. The
  third fact is honest about the signer: the node's own acts are authorised by
  the owner session and signed by nobody as the person; the lens request is
  filed by the agent under the session (CIRISAgent#1212). The erasure confirm
  already said so.
* **CC guardrail this card must never cross.** CC 2.4.1.1 carves out that a
  subject-authority `withdraws` "MUST NOT withdraw a third-party `capacity:*` or
  `detection:*` row about itself; selective erasure of adverse evidence is
  reputation laundering". If the list asked for above ever ships, it must render
  those rows as **contestable** (CC 4.5.5 `reconsideration:{grounds}`) and not
  deletable. Writing that here now is cheaper than discovering it in review.
* **Erasure reaches traces and nothing else, and the card says so.** The
  node's only reachable erasure is `erase-agent-traces`: `trace_events` and
  `trace_llm_calls` hard-deleted, `detection_events` tombstoned, in one persist
  transaction. Everything else the node holds — chats, notes, files, consent
  rows, anything sealed in a signed envelope — is minted unerasable today:
  CIRISPersist#573 ruled that erasability is decided at mint, and the disclosure
  store that would make a payload erasable is unbuilt (**CIRISPersist#914**).
  The node's own `scope_note` still cites #573 as if it had not landed. The card
  states the limit in the section header rows AND in the confirm's "Not erased"
  fact, because a person who erases their agent's traces and reads "erased" has
  been told their data is gone when it is not.
* **The person's own right to be forgotten is unreachable.** `/v1/auth/erasure`
  is correct and fail-honest (`withdraws_failed`, `incomplete`: "re-invoke until
  zero"), and needs a subject-signed request the client cannot make.
  **CIRISServer#677** asks for an owner-session path and a receipt. The card's
  classifier for that response is written and tested now, so the "never done on
  a partial" rule exists before the route becomes reachable.
* **Receipts: the verifier exists, the signer has no caller.** The agent's
  `/v1/verification/*` verifies an Ed25519/JCS proof; `sign_deletion_proof` is
  called only by its test. So the card can check a receipt and cannot show one,
  and says so (**CIRISAgent#1207**). Two server defects the card works around,
  both filed as **CIRISAgent#1220**: (1) verification uses the agent's CURRENT
  key and ignores the proof's `public_key_id`, so every receipt signed before a
  key rotation reads invalid — the card shows "cannot be checked here" instead of
  "does not check" when the key ids differ (`data_receipt_uncheckable`,
  `data_receipt_key_not_current`; pinned by
  `DataErasureControllerTest.aReceiptSignedByAnOlderKeyIsFlaggedNotCalledForged`);
  (2) `GET /v1/verification/public/{deletion_id}` looks nothing up, so the card
  does not link it.
* **DSAR is still split across cards, and this one does not submit one.**
  `/v1/dsar` (`routes/dsar.py:378`) files a ticket and starts a 90-day decay; the
  requests land in Tickets (CSD-013), where "completed" is a status word and not
  `consent:deletion_complete`. This card does not add a third DSAR door; the
  decay's own progress route (`/v1/dsar/{id}/deletion-status`, `dsar.py:670`)
  answers `no_active_decay` for both "finished" and "never started", which is a
  reading no card should render as either.
* **Placement.** Correct. This is the person's data, not a circle's, and
  Everything I shared is exactly where CC 3.3.1's subject-side authority belongs.
  The node erasure shows on every build; the receipt section only with an agent
  attached, because the routes are the agent's.
