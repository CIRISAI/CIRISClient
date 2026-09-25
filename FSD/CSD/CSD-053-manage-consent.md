# CSD-053 — Manage Consent (a grant with no way back)

**CSD**: CSD-053 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, Rules tab
**Flow**: unwritten

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person can see which of their own nodes has agreed to replicate what to
which other node, in both directions, and take that agreement back.** Serves
**Autonomy**, and it is the one card in this area where CC states the stake in
one line: *"Autonomy is only real if it remains revocable — **consent that
cannot be withdrawn is not consent**"* (CC 1.5).

**The revoke button on this screen is permanently disabled.**
`revokeEndpointAvailable` defaults to `false` (`ManageConsentScreen.kt:89`), its
`onClick` is `/* TODO: wire when server ships withdraws */` (`:204`), and the
node confirms the reason: there is no route that withdraws a peering grant.
`POST /v1/federation/consent` is grant-only and idempotent, and the entire
CIRISServer codebase has two DELETE routes, neither of them peering
(`src/auth/api_keys.rs:324`, `src/trust_root_api.rs:419`). The screen is honest
about it — it renders `mobile.manage_consent_revoke_todo` in the error colour
under the dead button — and that honesty is why this is a `sketched` CSD with a
concrete upstream ask rather than a defect report.

CC says the primitive is already there: a `withdraws`/`recants` against one's
own row is one of the five universal wire primitives, and every attester may
emit one against a row it signed (CC 2.4.1.1). CC 3.3.7 makes the obligation
explicit — *"on revoke, the granting node MUST cease replicating the named
prefixes to P"*. So the gap is a missing HTTP route over an existing capability,
which is the cheapest kind of gap and the worst kind to leave.

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

**It should be in one place, not five.** The screen's subject is a pair of
*saved node profiles*, `state.nodeA` and `state.nodeB` (`ManageConsentScreen.kt:154`),
fed by `apiClient.getOwnedNodes()` (`ConsentObjectsViewModel.kt:86`). Standing in
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
    renders: "A → B  Granted  ·  B → A  Granted, one row per direction"
    tag: "proposed:row_consent_direction"
  - ceg: x_private:is_ratified
    use: display-only
    type: bool
    example: true
    renders: "Ratified — both directions granted; otherwise Not ratified. Bilateral is the unit, one grant is not."
    tag: "proposed:chip_consent_ratified"
  - ceg: x_private:for_key_id
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Who it is for — the machine this human's grant names. Not carried to the client today."
    tag: "proposed:text_consent_for_key"
  - ceg: x_private:attesting_key_id
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Who granted it — since node 0.5.211 that is the OWNER's fed-ID, not the node. Not carried to the client today."
    tag: "proposed:text_consent_attester"
  - ceg: x_private:trace_consent
    use: display-only
    type: bool
    example: false
    renders: "Send reasoning traces — a switch, with Active / Paused under it and the counterparty named"
    tag: toggle_send_traces
```

**`use: display-only` on `consent:{kind}` is load-bearing, not bookkeeping.**
The family is **reserved** (CC 3.1.5, `accord-agent`/CIRISAgent), so
`check_csd_v3.py` refuses `use: emit` here — and that refusal states the true
architecture: `btn_consent_setup_peering` asks the node to author a grant
(`POST /v1/federation/peering`, `CIRISApiClient.kt:2328`); the app holds no keys
and mints nothing.

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
* The traces switch is documented in code as writing
  `consent:community_trust:v1` (`ManageConsentScreen.kt:75`). **No such leaf
  exists.** CC 3.3.1's catalogue is `state / stream / deletion_sla /
  deletion_complete / decay / partnership_grant / partnership_accept / scope /
  replication`, and `community_trust` is not among them; the registry has one
  `consent:{kind}` row and no gloss for that kind. Per `client/ceg/README.md`, a
  family with no registry row cannot be named and therefore cannot be rendered
  (CC 3.1.7 R2). The switch drives a real endpoint (§3) and the endpoint is fine;
  it is the dimension name in the comment that is unbacked, and a CSD is where
  that gets caught before a `Dim.` constant is written against it.

```yaml csd:states
populated: {tag: "proposed:row_consent_direction", renders: "both direction rows plus the ratified chip"}
empty:     {tag: "proposed:text_consent_need_two_nodes", renders: "mobile.manage_consent_need_two_nodes — fewer than two saved nodes, so there is no pair to peer. The branch is real (ManageConsentScreen.kt:154) and untagged."}
loading:   {tag: "proposed:consent_peering_running", renders: "the progress affordance inside btn_consent_setup_peering (state.isRunning, :183)"}
error:     {tag: "proposed:bar_consent_error", renders: "the errorContainer MessageBar (:127) — distinct from the neutral tertiaryContainer message bar on the same line"}
```

`empty` and `error` are already visually distinct (`errorContainer` vs
`tertiaryContainer`, `ManageConsentScreen.kt:349`), and `empty` is the *error*
colour today (`:156` uses `colorScheme.error` for "need two nodes"), which is a
third thing: a normal, unremarkable state drawn in the danger tone. Worth one
line in the same tagging PR.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the saved node pair | `getOwnedNodes()` — local profiles | CIRISClient | live |
| each node's key record | `GET /v1/federation/self-key-record` | CIRISServer | live (`src/federation_admin.rs:900`), node-only |
| grant a direction | `POST /v1/federation/peering` | CIRISServer | live (`src/federation_admin.rs:903`), node-only |
| **withdraw a grant** | `POST /v1/federation/peering/revoke`, signed by the person | CIRISServer | **built, unmerged** (0.5.218, CIRISServer#657, still open). Missing on `main` @ `046e1b39` (every `routing::delete` and the federation admin router checked; see #657's own table). Not readable: no branch or PR carrying it is pushed. Request and response keys other than `remaining_grants` are **unconfirmed** until CIRISClient#78 |
| grants that cannot be withdrawn | `remaining_grants` on the withdraw response: grants this node wrote before the person re-signed them, which stay live | CIRISServer | **built, unmerged.** Each renders as a row in `proposed:consent_remaining_grants` reading "Still active: this node wrote it before you signed, so it can't be withdrawn here", in the normal tone, never struck through or greyed as if gone. The success line appears only when `remaining_grants` is empty |
| un-contact a person | `DELETE /v1/contacts/{key_id}` | CIRISServer | built, unmerged (#657); the People side is CSD-005 |
| the grant's envelope (attester, `for_key_id`, scope, dimension) | not requested by the client; `grant_receipt` exists node-side (`src/peer.rs:1525`) | CIRISServer + CIRISClient | **unconfirmed** — blocks `building` for `text_consent_attester` and `text_consent_for_key`. CIRISServer#616. |
| the traces opt-in | `GET`/`PUT /v1/my-data/accord-settings` | **CIRISAgent** (`routes/my_data.py:580, 689`) | live on the agent — **wrong-host**: the node serves no `accord-settings` (its `/v1/my-data/*` is `lens-identifier` and `capacity` only, `src/system_data.rs:387,390`). The card is not marked `agentOnly`, so on a node build the switch reads a 404. |

## 4. Flow (how)

Unwritten. The four controls (`btn_consent_setup_peering`,
`btn_consent_revoke_peering`, `btn_open_user_consent`, `toggle_send_traces`) and
`btn_manage_consent_back` are real and drivable; every value is `proposed:`, so a
flow today would assert that the buttons exist and not what they say.

The step this CSD is waiting on is the one that cannot be written:

```yaml
# blocked until 0.5.218 ships the withdraw route (#657); then it lands as is
expect:
  state: populated
  text: {chip_consent_ratified: "Not ratified"}
```

Once the route ships: click `btn_consent_revoke_peering` on a grant the person
signed. It leaves the list. On a node with a grant the node wrote before the
person re-signed, the same click leaves that grant standing:

```yaml
expect:
  visible: ["proposed:consent_remaining_grants"]
  text: {"proposed:consent_remaining_grants": "Still active"}
```

## 5. QA plan

**Platforms.** All five. The peering flow needs two reachable nodes, so it runs
where a second profile can be saved — desktop by default.

**Not tested here.**
* Revocation, until 0.5.218 ships: the route is built but not released, so
  there is no red path yet, and by this project's own rule a check whose red
  path has never run is half a check. The `expect:` blocks above are written and
  land with the release. The `remaining_grants` case needs a node carrying a
  pre-re-sign grant, which only an upgraded node has; a fresh CI node does not.
* That a grant made on this screen is the grant the node signed. The client never
  reads the envelope back (§3).

**The recommendation, so it is on the record.** Move to **My things › Devices &
keys**. The card's subject is a pair of the owner's own machines; CC 2.3.3 makes
`cohort_scope` a property of contributions, not of the device pair that
replicates them, so there is no circle this belongs to and five is the wrong
answer to that. Keep `btn_open_user_consent` pointing at CSD-054, which IS a
per-person card — and rename this one so the two words stop competing: this is
*Replication between my nodes*, and CC 3.3.7 already writes the distinction.
