# CSD-039 — Data (My things › Everything I shared › Data)

**CSD**: CSD-039 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
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
    tag: "proposed:data_row_lens_identifier"
  - ceg: x_private:events_sent
    use: display-only
    type: int
    example: 412
    renders: "Events sent — 412"
    tag: "proposed:data_row_events_sent"
  - ceg: x_private:events_queued
    use: display-only
    type: int
    example: 0
    renders: "Events queued — 0"
    tag: "proposed:data_row_events_queued"
  - ceg: "consent:{kind}"
    bind: {kind: deletion_sla}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "NOT RENDERED. A deletion request returns no SLA and no completion, so the card can say 'requested' and never 'done'."
    tag: "proposed:data_row_deletion_sla"
  - ceg: "consent:{kind}"
    bind: {kind: scope}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "NOT RENDERED. CC 3.3.1's scopes — retain / share / analyze / train / publish — are collapsed here into one on/off switch."
    tag: "proposed:data_row_consent_scope"
```

`consent:{kind}` is **reserved** (CC 3.4.5) and owned by CIRISAgent, so every row
above is `display-only`. The client never emits a consent row; it asks the node
to, which is the same posture as the contacts and delegation cards.

```yaml csd:states
populated: {tag: "proposed:data_loaded"}
empty:     {tag: "proposed:data_empty", renders: "Nothing has been shared from this node. — for the accord block when `eventsSent == 0`; today the counters simply render 0, which reads as data"}
loading:   {tag: "proposed:data_loading", renders: "mobile.data_loading beside a progress affordance (DataManagementScreen.kt:310-322) — untagged"}
error:     {tag: "proposed:data_error", renders: "the read failed — a SNACKBAR today (:87-92), which is an error state with a timer on it"}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the pseudonymous trace id | `GET /v1/my-data/lens-identifier` | **CIRISAgent** (`routes/my_data.py:379`) and CIRISServer (`src/system_data.rs:387`) | live on both |
| accord sharing settings + counters | `GET /v1/my-data/accord-settings`, `PUT` the same | **CIRISAgent only** (`routes/my_data.py:579`, `:688`) | **wrong-host** — no `accord-settings` route in CIRISServer `src/*.rs` on 0.5.217 |
| delete the traces already sent | `DELETE /v1/my-data/lens-traces` | **CIRISAgent only** (`routes/my_data.py:458`) | **wrong-host** — no such route in CIRISServer |
| peers / peering state | `GET /v1/federation/peers` | CIRISServer | live — `src/federation_peers.rs` |
| grant federation consent | `GET /v1/accord/canonical/servers` (`src/accord_provision.rs:3711`), then `POST /v1/federation/consent` (owner-gated) — `authorFederationConsent`, `CIRISApiClient.kt` ~5270 | CIRISServer | live. (`/v1/accord/canonical-servers`, the path this row first named, 404s — the client's own comment records it) |
| erase the account | `POST /v1/system/data/reset-account` | CIRISServer | live — `src/system_data.rs:379`, owner + `CapabilityVerb::Wipe`, non-delegable |
| erase the signing key | `POST /v1/system/data/wipe-signing-key` | CIRISServer | live — `src/system_data.rs:383`, same gate |
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

Ask for the traces already sent to be deleted: click `btn_delete_traces`, give a
reason, click `btn_delete_traces_confirm`.

```yaml
expect:
  visible: ["proposed:data_row_deletion_sla"]
```

That last block fails today and is the assertion the fix owes. On a **node-only**
build, where `accord-settings` and `lens-traces` have no host:

```yaml
expect:
  state: error
  visible: ["proposed:data_error"]
  absent: [btn_delete_traces]
```

## 5. QA plan

**Platforms.** All five. The destructive pair — `btn_reset_account`,
`btn_wipe_signing_key` — runs **last**, on a throwaway node, because both
succeed.

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
* **Two of the three read routes are on the brain.** On a node-only build the
  accord block and the trace-deletion button have no host at all, and the screen
  does not say so — it renders a snackbar that vanishes. Same fix as CSD-038: a
  tagged, persistent error.
* **CC guardrail this card must never cross.** CC 2.4.1.1 carves out that a
  subject-authority `withdraws` "MUST NOT withdraw a third-party `capacity:*` or
  `detection:*` row about itself; selective erasure of adverse evidence is
  reputation laundering". If the list asked for above ever ships, it must render
  those rows as **contestable** (CC 4.5.5 `reconsideration:{grounds}`) and not
  deletable. Writing that here now is cheaper than discovering it in review.
* **Placement.** Correct. This is the person's data, not a circle's, and
  Everything I shared is exactly where CC 3.3.1's subject-side authority belongs.
