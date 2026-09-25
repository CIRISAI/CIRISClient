# CSD-055 — Delegations (offer, approve, revoke)

**CSD**: CSD-055 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, Rules tab
**Flow**: unwritten

```yaml csd:stage
stage: sketched
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
    renders: "one row per live grant: the delegate's label, then its key"
    tag: "proposed:row_delegation_key"
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
  - ceg: x_private:user_code
    use: display-only
    type: string
    example: "WDJB-MJHT"
    renders: "the one-time offer code, large and copyable, with everything else the delegate needs"
    tag: offer_block
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

**Sixteen of the twenty-six tags are real and value-bearing**, including
`row_delegation_${clientId}`, `btn_revoke_${clientId}`, `offer_block`,
`delegation_permits_title`, `delegations_relationship_explainer`,
`delegation_created_card`, `input_delegation_code`, `input_delegation_key_id`,
`${prefix}_constraints_section` and `${prefix}_never_delegated_note`. That is why
two `shows:` rows above are already real and why this is the card closest to
`testable` in the area.

```yaml csd:states
populated: {tag: "proposed:delegations_list", renders: "one row_delegation_* per live grant, each with a revoke control"}
empty:     {tag: "proposed:text_delegations_empty", renders: "mobile.delegations_empty — the real branch at DelegationsScreen.kt:1113, `delegations.isEmpty() && !loading`, today untagged"}
loading:   {tag: "proposed:delegations_loading", renders: "the inline progress affordance beside the section title (:1107), NOT the empty sentence — the branch already guards on !loading"}
error:     {tag: delegations_error, renders: "the error bar at :273 — distinct from delegations_notice at :258, which is the neutral one"}
```

`error` and the neutral notice are already two different tags on two different
bars, which is the distinction the standard asks for, drawn correctly. `empty`
is correctly guarded against `loading` at the source (`isEmpty() && !loading`),
so the three only need tags.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| live grants | `GET /v1/auth/device/grants` | CIRISServer | live (`src/auth/device_grant.rs:1356`) |
| offer a code | `POST /v1/auth/device/delegate` | CIRISServer | live (`:1351`) |
| the device code itself | `POST /v1/auth/device/code` | CIRISServer | live (`:1350`) |
| approve a code, with tighten-only constraints | `POST /v1/auth/device/approve` | CIRISServer | live (`:1353`) |
| revoke | `POST /v1/auth/device/revoke` | CIRISServer | live (`:1357`) |
| the chain above a grant | — | CIRISServer | **missing**. The ask: does `GET /v1/auth/device/grants` carry the parent `delegates_to` and the depth? CC 4.1.1 caps it at 5 and CC 4.5 makes revocation cascade, so an owner who cannot see the chain cannot see what a revoke does. Blocks `building` for `text_delegation_chain`. |

All five routes are node-only and all five are live — the strongest contract
table in this area. **The old claim that `/v1/self/delegations` serves this is
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
* `Screen.Delegations`'s back target is hard-coded to `Screen.Interact`
  (`CIRISApp.kt:4143`) while every placed surface answers for itself through
  `placedBackTarget` (`:568`). `Interact` is `agentOnly`, so on a node build back
  from Delegations targets a screen that is not in the tree.
* This card and `Delegation` (CSD-001) read the *same list from the same view
  model* and sit in the same tab in the Family circle. See CSD-001 §1.
