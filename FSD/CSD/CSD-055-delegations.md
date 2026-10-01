# CSD-055 — Delegations (offer, approve, refuse, revoke)

**CSD**: CSD-055 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, Rules tab
**Flow**: unwritten
**Folded in**: CSD-001 (`Delegation`, the read-only preamble), 2026-09-27 — see §6

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can hand someone a code that lets them act on their behalf within
stated bounds, approve a code someone hands them after narrowing it, see every
grant that is live, and end any of them.** Serves **Core Identity**: a
delegation the owner cannot see is an authority they did not knowingly give. CC
2.4.1 gives the shape — *"`delegates_to` — A authorizes B to sign on A's behalf
within a bounded scope"* — and CC 4.5 gives the two rules this screen has to
honour: *"Every sub-delegation attenuates, never expands: `child.scope ⊆
parent.scope`"*, and *"a `withdraws` against any `delegates_to` in the chain
invalidates everything downstream of it"*.

**This is the best-built card in the area.** Three flows in one screen (offer,
approve, manage), twenty-six test tags, a revoke control that works, and the
approve-side constraints are explicitly **TIGHTEN-ONLY** (`DelegationsScreen.kt`,
the `approve*` state block) — which is CC 4.5's attenuation implemented in the
UI, by that name, before anyone asked for it. What it is missing is the part CC
cares about next: the chain.

**It is also the only delegation card.** CSD-001's `Delegation` screen read the
same list from the same view model, sat in the same tab (Family › Rules), and its
one action opened this screen. The route map scored the pair 100% overlap. It
was one card with a read-only door in front of it, so the door was folded in
(§6): its one true sentence — that authority delegated TO the owner cannot be
read — is now `card_delegation_inbound` on this screen's Manage pane, and
`Screen.Delegation` / `NavSurface.Delegation` are gone. Every caller that opened
it (the Family layer hub card, Moderation's delegate-duty link) opens this
screen.

## 2. Surface (what)

```yaml csd:surface
surface: delegations
screen: Delegations
```

`nav_map` derives `circle_agent -> tab_rules -> nav_epistemic_delegations`, and
the same under the other four circles — `Placement(NavSurface.Delegations,
Tab.RULES, ALL)`.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:delegate_key_id
    use: display-only
    type: string
    example: "wa-agent-7c31"
    renders: "one row per live grant: the delegate's client id, its scope, and the signed record it is"
    tag: delegations_list
  - ceg: x_private:delegation_attestation_id
    use: display-only
    type: string
    example: "att-7f3a…"
    renders: "'Signed record …' under each row — the `delegates_to` attestation a revoke withdraws (GrantSummary.attestation_id; the DTO used to drop it)"
    tag: "text_delegation_record_${clientId}"
  - ceg: x_private:delegated_scope
    use: display-only
    type: "list[string]"
    example: ["read", "write"]
    renders: "What they may do — the allow-list, or read-only (no actions) when it is empty, or every owner verb when unrestricted"
    tag: delegation_permits_title
  - ceg: x_private:delegation_purpose
    use: display-only
    type: string
    example: "run the evening backup"
    renders: "What it is for — the owner's own sentence, free text"
    tag: "proposed:text_delegation_goal"
  - ceg: x_private:delegation_depth
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Who granted this before you — the chain above this grant, capped at 5 (CC 4.1.1). Not carried today."
    tag: "proposed:text_delegation_chain"
    blocked_by: CIRISServer#663
  - ceg: x_private:user_code
    use: display-only
    type: string
    example: "WDJB-MJHT"
    renders: "the one-time offer code, large and copyable, with everything else the delegate needs"
    tag: offer_block
  - ceg: x_private:inbound_delegations
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "'Delegated to you' — and under it that the app cannot read it yet, which is not a report that there is none. UNAVAILABLE, not empty (from CSD-001)"
    tag: txt_delegation_inbound_unavailable
    blocked_by: CIRISServer#663
```

**`delegates_to` is not a registry family and may not be written as one.** It is
one of CC 2.4.1's structural primitives — an envelope relation — and
`client/ceg/README.md` lists it among the things that are *"not registry
families"*. So every field here is `x_private:`, by the same convention CSD-004
set for envelope members. There is also no family for a device-authorization
grant: CC has `identity_occurrence` (CC 3.3.6) for one identity speaking across
devices and owner-binding `delegates_to` (CC 2.4.1.2) for a human stewarding a
machine, and no OAuth-device-code analogue. That is worth stating rather than
inventing a prefix for.

**`delegation_depth` is the constitutional gap.** CC 4.1.1 caps traversal at five
hops and CC 4.5 makes a revocation at any link sever the whole subtree. The
screen shows a flat list of grants with no parent and no depth, so an owner
approving a code cannot see whether they are approving a direct grant or a
sub-delegation of one they already made, and cannot see what their revoke would
take down with it. The node returns what it returns (§3); this row is
`unconfirmed` and blocks `building`.

**The tags.** Since the fold every state has a tag (below), and the Manage pane
adds `card_delegation_outbound`, `card_delegation_inbound`,
`btn_delegation_refresh`, `btn_delegation_deny` (Approve pane) and the revoke
ConfirmSheet `sheet_revoke_delegation` / `btn_revoke_delegation_confirm`.
Before it, **sixteen of the twenty-six tags were real and value-bearing**, including
`row_delegation_${clientId}`, `btn_revoke_${clientId}`, `offer_block`,
`delegation_permits_title`, `delegations_relationship_explainer`,
`delegation_created_card`, `input_delegation_code`, `input_delegation_key_id`,
`${prefix}_constraints_section` and `${prefix}_never_delegated_note`. That is why
two `shows:` rows above are already real and why this is the card closest to
`testable` in the area.

```yaml csd:states
populated: {tag: delegations_list, renders: "one row_delegation_* per live grant, each with its signed record and a revoke control"}
empty:     {tag: text_delegations_empty, renders: "mobile.delegations_empty — only when the list was READ and is empty, and not while loading"}
loading:   {tag: delegations_loading, renders: "the inline progress affordance beside the section title, NOT the empty sentence"}
error:     {tag: delegations_list_error, renders: "'Couldn't read your delegations from this node, so this list is not complete: …' — in place of the empty sentence, or above stale rows"}
```

**Error no longer looks like empty.** A failed `GET /v1/auth/device/grants`
used to set the top error bar and leave the list at `emptyList()`, so the Manage
pane said "No active delegations" under it — a read that never happened,
reported as nothing to read. `DelegationsViewModel.listError` is the list's own
failure, kept apart from `error` (the last act's) and from `notice`
(`DelegationsViewModelTest.aListThatFailedToLoadIsNotAnEmptyList`).

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| live grants | `GET /v1/auth/device/grants` | CIRISServer | live (`src/auth/device_grant.rs:1356`, handler `:1211`) — `{grants: [{client_id, scope, attestation_id}]}`; `attestation_id` was dropped by `DelegationDto` and is now kept and shown. There is no `expires_at` on the wire (the DTO's field is always null) although every edge carries `delegation_valid_until` = issue + `GRANT_TTL_SECS` (600 s, `:61`); the list cannot say when a grant lapses |
| pick an existing fed-ID to delegate to | `GET /v1/contacts`, `GET /v1/federation/peers` | CIRISAgent front door / CIRISServer | live — reached through the Contacts picker (`btn_delegation_choose_identity` → `Screen.Contacts`, CSD-005), which owns both reads and returns the chosen key id here; this card does not call them itself |
| offer a code | `POST /v1/auth/device/delegate` | CIRISServer | live (`:1351`) |
| the device code itself | `POST /v1/auth/device/code` | CIRISServer | live (`:1350`) |
| approve a code, with tighten-only constraints | `POST /v1/auth/device/approve` | CIRISServer | live (`:1353`) |
| revoke | `POST /v1/auth/device/revoke` | CIRISServer | live (`:1357`, handler `:1247`) — signs a `withdraws` against each live `delegates_to(owner → client)` edge with the OWNER's key, permanent (`None` expiry); answers `{revoked, client_id}`. Behind a ConfirmSheet since this review: who (the client id), what changes (it can no longer act for you; permanent), who signs (you, your identity key on this node) |
| the agent redeems the offered PIN | `POST /v1/auth/device/claim` | CIRISServer (`:1352`, handler `:750`) — **PUBLIC**, the PIN is the secret; consumes the grant | live, and **correctly not called by this client**: the AGENT calls it. The card's part is to show it — `delegate`'s response carries `claim_url: "/v1/auth/device/claim"` (`:732`) and the card renders it with the PIN (`CreateDelegationResponse.claimUrl`, `models/federation/Delegation.kt:42`; drawn at `DelegationsScreen.kt:617`) |
| **refuse a pending code** | `POST /v1/auth/device/deny` | CIRISServer (`:1354`, handler `:1024`) — owner-gated (`require_owner`, `:1029`); body `{user_code}`, answers `{status: "denied", user_code}` or 404 | **called** — `denyDeviceCode` (`CIRISApiClient.kt`), `btn_delegation_deny` beside Approve. It was the owner's missing "no": a code they did not want could only be approved or left to expire. Server gap, not ours: deny checks neither expiry nor status, so denying an ALREADY-APPROVED code flips the in-memory grant to Denied while its signed `delegates_to` edge stays live in `/grants`, and `approve` refuses only an Approved grant, so a denied code can still be approved (draft issue in the review report) |
| the device's poll leg | `POST /v1/auth/device/token` | CIRISServer (`:1355`, handler `:1059`) | live; RFC 8628 polling by the requesting device, not an owner act — no card |
| the chain above a grant | — | CIRISServer | **missing**. The ask: does `GET /v1/auth/device/grants` carry the parent `delegates_to` and the depth? CC 4.1.1 caps it at 5 and CC 4.5 makes revocation cascade, so an owner who cannot see the chain cannot see what a revoke does. Blocks `building` for `text_delegation_chain`. |
| (read, not shown) the pair-room invitations | `GET /v1/self/invites` | CIRISServer | **0.5.218, called by the shared `ContactsViewModel`** whose contact list the picker reaches; the picker renders no invitation (CSD-005 does). Cited here because this screen's view model makes the call |

All five routes are node-only and all five are live — the strongest contract
table in this area. The three rows under them are the rest of
`device_grant.rs:1349-1357` (identical on `integ/0.5.218`): `claim` and `token`
are the other party's legs, and `deny` is the owner's missing "no". **The old claim that `/v1/self/delegations` serves this is
wrong**: zero hits in CIRISServer `src/*.rs` and zero in CIRISAgent; the node's
`/v1/self/*` routes are `nodes/{key_id}/release` and `occurrence/label`
(`src/self_devices.rs:469, 473`). CSD-001 carried that row and is corrected in
the same change as this file.

## 4. Flow (how)

Not written, and it is the one in this area that could be. The offer→approve→
revoke round trip runs entirely on real tags:

```yaml
# candidate — needs `delegations_list` and `text_delegations_empty` tagged first
expect:
  state: populated
  count: {of: "row_delegation_*", min: 1}
  visible: [delegation_permits_title]
```

## 5. QA plan

**Platforms.** All five. The offer code is copied to the clipboard, so the copy
control (`btn_offer_copy_all`, `${testTag}_copied`) is per-platform.

**Not tested here.**
* The chain. There is no data (§3).
* That a revoke cascades. CC 4.5 says it must; the client cannot see the subtree,
  so it cannot assert it. This belongs to a node test, not a client flow, and
  saying so is better than writing a client assertion that cannot fail.

**Two smaller findings, recorded rather than fixed here.**
* ~~`Screen.Delegations`'s back target is hard-coded to `Screen.Interact`~~ —
  fixed: back now answers through `placedBackTarget(Screen.Delegations, …)` like
  every placed surface, so a node build no longer backs into a screen that is
  not in its tree.
* ~~This card and `Delegation` (CSD-001) read the same list~~ — folded (§6).
* **Request bodies are encoded, not concatenated.** `delegate`/`approve`/`deny`/
  `revoke` bodies were hand-built strings, so a `"` in a label, key id or goal
  gave a 400 or a body carrying a field the owner never chose
  (`…","sub_delegation":true,…`). Owner-only, so a correctness bug and not an
  exploit; `DelegationBodies` now encodes them
  (`DelegationsViewModelTest.whatTheOwnerTypedCannotAddTermsToTheGrant`).

## 6. CSD-001, folded in (2026-09-27)

CSD-001 described `Screen.Delegation`, a read-only view of the same
`DelegationsViewModel.delegations` this card manages, placed only under Family ›
Rules, whose one action (`btn_delegation_manage_grants`) navigated here. The
route map (`packaging/check_csd_routes.py`) scored the pair 100%: every route
CSD-001 cited, this card cites, and `Screen.Delegation` called a strict subset of
what `Screen.Delegations` calls. It had eight tags to this card's twenty-six, no
act, and hard-coded English headings.

What survived: the inbound half. `card_delegation_inbound` and
`txt_delegation_inbound_unavailable` render on this card's Manage pane with the
same sentence, now localized, and `card_delegation_outbound` wraps the grant
list. What did not: `screen_delegation`, `card_delegation_overview`,
`btn_delegation_manage_grants`, `btn_delegation_back` (this screen's back is
`btn_delegations_back`). `Screen.Delegation`, `NavSurface.Delegation` and
`federation/DelegationScreen.kt` are deleted; the Family layer hub card and
Moderation's "delegate the moderate duty" link open `Screen.Delegations`.

**Upstream edit needed:** CIRISAgent's `tools/qa_runner/flows/delegation_card.yaml`
(`client: "unreleased"`, so it refuses rather than fails today) drives
`screen: Delegation`, `screen_delegation`, `card_delegation_overview` and
`btn_delegation_manage_grants`. It should open `Screen.Delegations`, press
`btn_delegation_pane_manage`, and assert `card_delegation_outbound`,
`card_delegation_inbound`, `txt_delegation_inbound_unavailable` and
`btn_delegation_refresh`. The canonical CSD-001 is CIRISAgent's; this repo's
copy was a profile demonstration marked "must go upstream and be deleted here",
and it is deleted.
