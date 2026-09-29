# CSD-053 — Manage Consent (a grant, and the way back)

**CSD**: CSD-053 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, Rules tab
**Flow**: `testing/flows/drafts/csd-053-manage-consent.yaml` (`client: unreleased`) drives the
empty state and the two pointer doors; the revoke path is written in §4 and its red half is
driven by `WithdrawConsentTest`; the set-up path needs two reachable nodes

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can see which of their own nodes has agreed to replicate what to
which other node, in both directions, and take that agreement back.** Serves
**Autonomy**, and it is the one card in this area where CC states the stake in
one line: *"Autonomy is only real if it remains revocable — **consent that
cannot be withdrawn is not consent**"* (CC 1.5).

**Revoke is live.** Until #112 the button was hard-disabled behind a
compile-time flag (`revokeEndpointAvailable = false`) with a TODO for an
endpoint the node did not have. ciris-server 0.5.218 shipped the route
(CIRISServer#657) and the flag is gone: the control now drives
`POST /v1/federation/peering/revoke` on node A, and **whether node A has the
route is asked of node A at runtime**, never decided by a build. The primitive
underneath is the one CC always said was there — a `withdraws` against one's
own row (CC 2.4.1.1), and *"on revoke, the granting node MUST cease replicating
the named prefixes to P"* (CC 3.3.7). The node signs the `withdraws` **as the
person** (consent is by humans, CIRISPersist#857); the app does no crypto.

What the screen renders after a revoke is what the node SAID, never what was
asked for. That is CC 1.5's flip side, and it is the reason the still-active
outcome (§2) exists at all.

**0.5.218 builds the route (CIRISServer#657).** The maintainer's brief update
(2026-09-25) reports `POST /v1/federation/peering/revoke` and
`DELETE /v1/contacts/{key_id}` as built and tested, **always signed by the
person, never the node**. So the disabled control can be enabled once the
release ships. There is one limit, and the screen must carry it: grants the node
wrote before the person re-signed them cannot be withdrawn and stay live. The
route lists them as `remaining_grants`, and each must render as **still
active**. A withdraw that leaves any of them standing is not reported as
complete.

## 2. Surface (what)

```yaml csd:surface
surface: manage-consent
screen: ManageConsent
```

`nav_map` derives `circle_agent -> tab_rules -> nav_epistemic_manage_consent`,
and the same under all four other circles — `Placement(NavSurface.ManageConsent,
Tab.RULES, ALL)`.

**It should be in one place, not five.** The screen's subject is a pair of the
owner's own nodes, `state.nodeA` and `state.nodeB`, fed by
`apiClient.getOwnedNodes()` (`ConsentObjectsViewModel.loadNodes`). Standing in
Family or in Everyone changes nothing about it. Its natural home is **My things
› Devices & keys**, beside `IdentityManagement`; see §5.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "consent:{kind}"
    bind: {kind: replication}
    use: display-only
    type: "enum[granted,in_progress,failed,idle]"
    example: "granted"
    renders: "A → B  Granted  ·  B → A  Granted, one row per direction (`row_consent_a_to_b`, `row_consent_b_to_a`; the tag's text is the direction's state). A direction leaves Granted only when the node says it withdrew that grant."
    tag: row_consent_a_to_b
  - ceg: x_private:is_ratified
    use: display-only
    type: bool
    example: true
    renders: "Ratified — both directions granted; otherwise Not ratified. Bilateral is the unit, one grant is not. A label, not a chip: it is not a control, and check_ui_drivable holds a `chip_` prefix to be one."
    tag: txt_consent_ratified
  - ceg: x_private:remaining_grants
    use: display-only
    type: "list[string]"
    example: ["att-7f3a…"]
    renders: "Still active — the grant ids the node reported it did NOT withdraw (node-authored, so not the person's to withdraw), each with why, in the ordinary tone; never struck through and never under a Withdrawn line"
    tag: consent_remaining_grants
  - ceg: x_private:withdrawn_by
    use: display-only
    type: string
    example: "att-9c21…"
    renders: "Withdrawn. The node recorded your withdrawal ({id}) — the `withdraws` row the node signed as the person; shown only when nothing remains"
    tag: text_consent_withdrawn
  - ceg: x_private:for_key_id
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Who it is for — the machine this human's grant names. Not carried to the client today."
    tag: "proposed:text_consent_for_key"
    blocked_by: CIRISServer#657
  - ceg: x_private:attesting_key_id
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Who granted it — since node 0.5.211 that is the OWNER's fed-ID, not the node. Not carried to the client today."
    tag: "proposed:text_consent_attester"
    blocked_by: CIRISServer#657
```

**The traces switch is gone from this card (2026-09-28).** `toggle_send_traces`
drove `PUT /v1/my-data/accord-settings` — the same write the Data card
(CSD-039) and the Add Federation ID catch-up (CSD-086) also issued: one act,
three doors, and the route checker's duplicate-mutation ratchet listed all
three. The Data card is where the setting is READ BACK (`switch_consent`,
`data_row_events_sent`), so it owns the write; this card and the catch-up now
point at it (`btn_open_data_sharing` here). The card that did the write here
also documented it as `consent:community_trust:v1`, a leaf CC 3.3.1 does not
have (below), which is the other reason it went rather than stayed.

**`use: display-only` on `consent:{kind}` is load-bearing, not bookkeeping.**
The family is **reserved** (CC 3.1.5, `accord-agent`/CIRISAgent), so
`check_csd_v3.py` refuses `use: emit` here — and that refusal states the true
architecture: `btn_consent_setup_peering` asks the node to author a grant
(`POST /v1/federation/peering`), `btn_consent_revoke_peering` asks it to
withdraw one, and the app holds no keys and mints nothing.

### 2.1 The revoke control's states

`btn_consent_revoke_peering` is enabled by `ConsentObjectsState.canRevoke`:
node A is known, a grant is **named** (`aToBGrantId`), the route is not known
to be missing, and nothing is running. Each way it is not enabled says why
(`revokeNote`, pure and tested without Compose):

| state | how the screen knows | renders (tag) |
|---|---|---|
| **route missing** | the GET probe answered a bare 404, or the POST did (`RevokeRoute.MISSING`) | "This node can't withdraw consent yet (needs ciris-server 0.5.218)." — `text_consent_revoke_unsupported`. Outranks everything: no grant id changes what a node without the route can do. |
| **no named grant** | `aToBGrantId` null and no outcome to show | "Revoke names one grant, and this node doesn't list its peering grants yet, so it works on a grant set up here in this session." — `text_consent_revoke_needs_grant`. The node serves no read of its peering grants, so a grant made elsewhere cannot be named here. |
| **live** | a grant this screen saw granted, route `MOUNTED` or `UNKNOWN` | the button, enabled. `UNKNOWN` stays live on purpose: the POST itself decides. |
| **revoking** | `isRevoking` | the progress affordance inside the button |

And what the last revoke DID — every line here is something the **node** said:

| outcome | node's answer | renders (tag) |
|---|---|---|
| **withdrawn** | 2xx with `withdraws` set and `remaining_grants` empty, `attestation_id` matching | A → B leaves Granted; "Withdrawn. The node recorded your withdrawal ({id})" — `text_consent_withdrawn` |
| **still active** | 409 `consent.grant_not_owner_authored` with `grants: [...]`, or 2xx with `remaining_grants` non-empty | A → B **stays Granted, because it is**; the ids under "Still active" — `consent_remaining_grants` |
| **refused** | any other non-2xx that carries a `reason_id` | the refusal, localized by id — `consent_revoke_refusal` |
| **route missing** | a bare 404 (no `reason_id`) on the POST | flips to the route-missing state above; nothing was withdrawn |

The row moves on the strength of the node's **answer to the POST** — not of
the click, and not of a re-read: the node serves no read of its peering grants
(§3), so there is nothing to re-read the row from. What the screen does re-read
afterwards (`loadNodes` and the route probe) is the node pair and whether the
route is mounted, which are readable. The outcome
lines are cleared when a new set-up starts and again when its A→B grant is
accepted — a fresh grant under "Withdrawn" would be reporting its
predecessor's fate as its own — and the whole session (grant id, rows,
outcome, route answer, node A's token) is forgotten on **every** way out of a
session, because the ViewModel is app-scoped and outlives the owner. Verified
against the code after #116: the reset is `resetSession()`, called from
CIRISApp's one token effect, keyed and conditioned on
`consentSessionAuthenticated(currentAccessToken, isHAAddonMode)` — so the
three logout menus, a session expiring under Interact and Billing's
sign-in-again all reset it, and a Home Assistant add-on session (where the
token stays null by design) does NOT; the next session reloads the pair
once. `WithdrawConsentTest.logoutForgetsTheSessionsGrantOutcomeAndToken`,
`…homeAssistantAddOnModeIsASessionNotALogout` and the four in-flight cases pin
it. The person's own consent record (CSD-054) is reset from the same effect.

**`for_key_id` and the attester are the two facts this screen most needs and does
not have.** Since 0.5.211 the node signs a replication grant as *the owner's
fed-ID, not the node* — *"consent is by humans", CIRISPersist#857*
(`CIRISServer src/peer.rs:955`, and `:1519`: *"`attesting_key_id` is whoever
actually signed — since 0.5.211 that is the OWNER's fed-ID, not this node
(consent is by humans), which is exactly why the client must be sent it rather
than assume CC 3.3.7's `G` is the node"*). The node already builds a
`grant_receipt` with exactly these members (`src/peer.rs:1525`). The client
never asks for it, so a screen about a human's consent shows two machine names
and no human. That is CIRISServer#616's client half, and it is the same ask
CSD-005 §3 files for `GET /v1/contacts`.

**Two notes on what this card is NOT.**

* CC calls a node-to-node replication grant "consent" and immediately fences it:
  it names *"a fabric **node's** standing grant to replicate a class of its own
  attestations to a **named peer node**, **as distinct from a subject's consent
  over a target Contribution**"* (CC 3.3.7). This screen renders it under the
  bare title "Manage Consent" and points at the *other* consent — the subject's
  — through `btn_open_user_consent` as a secondary card. A person reading the
  tab sees one word covering two things CC's own drafters kept apart.
* The traces switch this card used to carry was documented in code as writing
  `consent:community_trust:v1`. **No such leaf exists.** CC 3.3.1's catalogue is
  `state / stream / deletion_sla / deletion_complete / decay / partnership_grant
  / partnership_accept / scope / replication`, and `community_trust` is not
  among them; the registry has one `consent:{kind}` row and no gloss for that
  kind. Per `client/ceg/README.md`, a family with no registry row cannot be
  named and therefore cannot be rendered (CC 3.1.7 R2). The endpoint was fine;
  the dimension name was unbacked. The switch and its comment are gone with the
  fold above, and the Data card (CSD-039) binds the same write to
  `consent:state` — a leaf that exists.

```yaml csd:states
populated: {tag: row_consent_a_to_b, renders: "both direction rows, the ratified label (txt_consent_ratified), and the revoke control in whichever of its §2.1 states applies"}
empty:     {tag: text_consent_need_two_nodes, renders: "mobile.manage_consent_need_two_nodes — fewer than two owned nodes, so there is no pair to peer. In the ordinary tone: it was drawn in the danger colour until 2026-09-28"}
loading:   {tag: consent_peering_running, renders: "the progress affordance inside btn_consent_setup_peering (state.isRunning); the revoke's is consent_revoking, inside btn_consent_revoke_peering"}
error:     {tag: consent_revoke_refusal, renders: "the node's typed refusal of the last revoke, localized by reason_id, in the error colour; the set-up path's errorContainer MessageBar is bar_consent_error, and the ratified / partial line after a set-up is bar_consent_message (localized: mobile.manage_consent_msg_ratified / _partial — they were English literals in the view model)"}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the owned node pair | `GET /v1/setup/owned-nodes` (`getOwnedNodes()`, local node only) | CIRISServer | live; owned REMOTE nodes carry no reachable endpoint yet, so B's `baseUrl` is empty until mesh addressing lands |
| each node's key record | `GET /v1/federation/self-key-record` | CIRISServer | live (`src/federation_admin.rs`), node-only |
| grant a direction | `POST /v1/federation/peering` → `grant_attestation_id` | CIRISServer | live (`src/federation_admin.rs`), node-only. The response carries no `granted` member; `PeeringResponse.isGranted` reads the id (#112). |
| **withdraw a grant** | `POST /v1/federation/peering/revoke {attestation_id}` → `{attestation_id, withdraws, peer_key_ids, cohort_scope, remaining_grants}` | CIRISServer | **live** since ciris-server 0.5.218 (CIRISServer#657). Signed as the person. 409 `consent.grant_not_owner_authored {grants}` is the same fact with nothing withdrawn and is read as `remaining_grants`, not as an error. |
| does node A mount the revoke route | `GET /v1/federation/peering/revoke` — the probe | CIRISClient | 405 = mounted, bare 404 = missing, anything else = not known (the POST decides). A GET, so it writes nothing. Asked, like the POST, **as node A's own session** — the client's token is the active node's, which after a switch is not A. |
| un-contact a person | `DELETE /v1/contacts/{key_id}` | CIRISServer | live since 0.5.218 (#657; the client side is #112); the People side is CSD-005 |
| the grant's envelope (attester, `for_key_id`, scope, dimension) | not requested by the client; `grant_receipt` exists node-side (`src/peer.rs:1525`) | CIRISServer + CIRISClient | **unconfirmed** — blocks `building` for `text_consent_attester` and `text_consent_for_key`. CIRISServer#616. |
| a read of node A's peering grants | none | CIRISServer | **missing.** Revoke names a grant by id and the node lists none, so the control works only on a grant set up here in this session. |
| the traces opt-in | — (was `GET`/`PUT /v1/my-data/accord-settings`, CIRISAgent) | CIRISAgent, via **CSD-039** | **not this card's, since 2026-09-28.** The write was issued from three screens (this one, Data, Add Federation ID) and the Data card is the one that reads it back, so it owns it; `btn_open_data_sharing` opens it. Folded rather than documented as three doors: on a node-only build this card drew the switch over a 404, and the fold removes that reading here without adding a state for it. |

## 4. Flow (how)

The controls (`btn_consent_setup_peering`, `btn_consent_revoke_peering`,
`btn_open_user_consent`, `toggle_send_traces`, `btn_manage_consent_back`) and
the outcome lines (§2.1) are real and drivable. The set-up path needs two
reachable nodes and is not written here.

**Revoke, on a node without the route** (the packaged APK node lags one release):

```yaml
expect:
  state: populated
  visible: [text_consent_revoke_unsupported]
  disabled: [btn_consent_revoke_peering]
```

**Revoke, on a node with the route, with a grant set up in this session.** Tap
`btn_consent_revoke_peering`.

```yaml
expect:
  visible: [sheet_consent_revoke, consent_revoke_fact_1, consent_revoke_fact_2, consent_revoke_fact_3]
  text: {consent_revoke_fact_3: "You. The node signs the withdrawal with your key, never its own."}
```

Tap `btn_consent_revoke_confirm`. One of two lines, and only the node decides which:

```yaml
# the node withdrew and signed
expect:
  state: populated
  visible: [text_consent_withdrawn]
  absent: [consent_remaining_grants]
  text: {txt_consent_ratified: "Not ratified", row_consent_a_to_b: "not set"}
```

```yaml
# the grant is node-authored: nothing withdrawn, and the row does not move
expect:
  state: populated
  visible: [consent_remaining_grants]
  absent: [text_consent_withdrawn]
  text: {txt_consent_ratified: "Ratified — both grants present", row_consent_a_to_b: "granted"}
```

**The empty state, and the two doors** — the one path every fixture can drive,
because a single owned node is the normal case (staged as
`testing/flows/drafts/csd-053-manage-consent.yaml`):

```yaml
expect:
  state: empty
  visible: [text_consent_need_two_nodes, btn_open_user_consent, btn_open_data_sharing]
  absent: [btn_consent_setup_peering, btn_consent_revoke_peering, toggle_send_traces]
```

`toggle_send_traces` is asserted absent on purpose: if it reappears, someone
has re-opened the third door on `PUT /v1/my-data/accord-settings` (§3).

## 5. QA plan

**Platforms.** All five. The empty state and the two pointer doors run on every
fixture (the staged flow). The peering flow needs two reachable nodes, so it runs
where a second profile can be saved — desktop by default. The revoke path's red
half — the bare 404, the 409 with `grants`, a refusal with a `reason_id` — is
driven through `ConsentWithdrawApi` by a fake in `WithdrawConsentTest`, and the
node-too-old sentence is asserted there without Compose.

**Not tested here.**
* That a grant made on this screen is the grant the node signed. The client never
  reads the envelope back (§3).
* A grant made anywhere but this session. There is no read of the node's
  peering grants (§3), so there is nothing to name.

**The recommendation, so it is on the record.** Move to **My things › Devices &
keys**. The card's subject is a pair of the owner's own machines; CC 2.3.3 makes
`cohort_scope` a property of contributions, not of the device pair that
replicates them, so there is no circle this belongs to and five is the wrong
answer to that. Keep `btn_open_user_consent` pointing at CSD-054, which IS a
per-person card — and rename this one so the two words stop competing: this is
*Replication between my nodes*, and CC 3.3.7 already writes the distinction.
