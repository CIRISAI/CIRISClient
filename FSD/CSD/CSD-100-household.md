# CSD-100 — The household (the Family hub, on Family › Rules)

**CSD**: CSD-100 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the plan's B5 Family; CIRISServer 0.5.216 (`/v1/families`, #627)
**Pairs with**: CSD-101 (the roster, on Family › People) · CSD-050 (the layer hub frame this renders inside)
**Flow**: `testing/flows/drafts/csd-100-household.yaml` (staged; floor `unreleased`)

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person standing in Family can form a household, see which household they
are looking at and switch between the ones they are in, read how it decides
(`founder_only`, or M of N members), and leave or dissolve it. A change a quorum
household must sign shows who has signed and who has not. What the network
cannot do yet is said on the card rather than promised.**

A household is a CC 3.3.4 `family`: "a group of trusted nodes", "the wire-format
primitive for `cohort_scope: family` visibility scoping". "One identity MAY
belong to multiple families. Each family has its own DEK and its own membership
roster", so the card has a switcher and never assumes one. Serves **Contextual
Integrity**: `cohort_scope: family` content is structurally invisible outside
the roster (CC 5.2), so the roster and the rule that changes it are exactly
what decides who can read what the household shares.

Until 0.5.216 nothing in production could form one (CIRISServer#627); the node
has served nine routes since then and the client called none of them.

## 2. Surface (what)

```yaml csd:surface
surface: layer-family
screen: LayerFamily
```

**No new card: the Family hub IS the household.** Family › Rules already opens
on the Family layer hub (`LayerFamily`, CSD-050), whose mission is "who is in
this circle and the standing rules that decide trust here". For Family, it drew
three description-only sections about data it never fetched (CSD-050 §1), and
its subtitle described the self-collective, not a household. A second
"Household" card beside it would have been the same idea under another name.
So `LayerHubScreen` takes a `familyContent` slot, and for the Family scope the
household panel (`HouseholdPanel`) comes first and replaces those three
sections. The delegations card below it is unchanged. The frame tag
`layer_hub_family` and `nav_epistemic_layer_family` are unchanged.

**The roster is not here.** Who is in the household is a People fact, and lives
on Family › People (CSD-101). This card names only the **founders**, because
under `founder_only` they are the rule. Tapping the household row opens the
roster.

**The accord family is not a household.** `AccordScreen`'s "accord family" is
the entrenched `humanity-accord` `quorum:2/3` family (CC 4.2). The node
reserves that id and answers 404 for it on every household route
(`family_api.rs:508-511`), and this card never shows it.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:family_id
    use: display-only
    type: string
    example: "family:v1:9f2c4e…"
    renders: "one chip per household in the switcher (its name; the id only when the name is blank), the selected one marked; and the household row's mono meta line, middle-truncated"
    tag: households_switcher
  - ceg: x_private:family_name
    use: display-only
    type: string
    example: "The Okafors"
    renders: "Household — The Okafors"
    tag: household_name
  - ceg: x_private:consensus_protocol
    use: display-only
    type: string
    example: "quorum:2/3"
    renders: "Who decides — 2 of 3 members must sign (consensus_protocol: quorum:2/3). founder_only reads 'A founder decides'. Any other string reads '<it>. This node can't make changes under that rule. You can still leave.'"
    tag: household_protocol
  - ceg: x_private:founders
    use: display-only
    type: "list[string]"
    example: ["You", "Bo"]
    renders: "Founders — You, Bo: the members whose role is founder, by contact name, else a short key"
    tag: household_founders
  - ceg: x_private:my_role
    use: display-only
    type: "enum[founder,member]"
    example: "founder"
    renders: "You are — Founder. Absent: 'This node did not send this.' in the error tone"
    tag: household_my_role
  - ceg: x_private:member_count
    use: display-only
    type: int
    example: 3
    renders: "Members — 3 members"
    tag: household_member_count
  - ceg: x_private:founded_at
    use: display-only
    type: timestamp
    example: "2026-09-20T10:00:00Z"
    renders: "Formed — 2026-09-20"
    tag: household_founded
  - ceg: x_private:subject_key_ids
    use: display-only
    type: string
    example: "family:v1:9f2c4e…"
    renders: "the receipt on the household row: Who it is about — the family id (Wire)"
    tag: receipt_subject
  - ceg: x_private:attesting_key_id
    use: display-only
    type: string
    example: "wa-ada-88b1"
    renders: "Who sent it — the key that signed the current record (Wire). The node sends null for a row with no signed read, and the sheet then says 'This node did not send this.' rather than guessing the founder"
    tag: receipt_attester
  - ceg: x_private:cohort_scope
    use: display-only
    type: string
    example: "family"
    renders: "Who can see it — the Family pill (Wire)"
    tag: receipt_scope
  - ceg: x_private:required_signatures
    use: display-only
    type: int
    example: 2
    renders: "the ceremony's '1 of 2 signed' line"
    tag: household_change_signed_of
  - ceg: x_private:signers
    use: display-only
    type: "list[string]"
    example: ["me", "bo", "cy"]
    renders: "one chip per active member: 'You · proposed', 'Bo · signed', 'Cy · not yet'"
    tag: ceremony_household_change
  - ceg: x_private:household_name_draft
    use: emit
    type: string
    example: "The Okafors"
    renders: "the name field when forming a household (at most 200 characters, as the node allows)"
    tag: input_household_name
  - ceg: x_private:consensus_protocol_choice
    use: emit
    type: "enum[founder_only,majority,unanimous]"
    example: "founder_only"
    renders: "three chips: A founder decides (default, sent as no protocol) / Most members sign / Everyone signs. The node stores the last two as quorum:M/N"
    tag: opt_household_protocol_founder_only
```

**The receipt's rule row is `NotSent`.** No `consent:scope` travels with a family
record, so the fifth fact is the node's silence and the sheet says so. The
dimension row shows the wire's own `"dimension": "family"`; the record has no
registry dimension (a family is a CC 3.3.4 `subject_kind`, not a CC 3.1 family),
which is why every row above is `x_private:`.

```yaml csd:states
populated: {tag: card_household, renders: "the switcher, the household row with its receipt, the rule card (name, who decides, founders, you are, members, formed), the governance sentence, Leave, Dissolve when this person's rule allows it, and the limits sentence"}
empty:     {tag: households_empty, renders: "You're not in a household yet. Form one here, then add people from your contacts. The form card (card_household_create) is shown above it, because an empty list has one useful next move"}
loading:   {tag: households_loading, renders: "a progress affordance and no sentence"}
error:     {tag: households_error, renders: "Couldn't read this (a failed read), or 'The node wouldn't list your households.' with the node's refusal by id (family.owner_session_required, …). A node without the routes renders households_not_on_this_node: This node can't form households yet. It needs ciris-server 0.5.216 or newer."}
```

### 2.1 Governance decides what is offered

`governanceOf` reads the stored protocol the way the node's `Protocol::of` does
(`family_api.rs:416-424`): `founder_only`, or `quorum:M/N` with `2M > N`.
`routeOf` then decides what each act becomes:

| act | founder_only, you are a founder | founder_only, you are not | quorum:M/N | any other protocol |
|---|---|---|---|---|
| dissolve (here), add, remove, role (CSD-101) | one call | not offered; the sentence says only a founder can | a **proposal** others must sign | not offered |
| leave | one call | one call | one call | one call |

A quorum household is never offered a single call: the node would refuse it
`family.quorum_pending` (`needs_quorum`, `family_api.rs:842-847`) after the person had already
confirmed. Leaving is always the person's own act (FSD §1 rule 5).

Every act goes behind a `ConfirmSheet` (`sheet_confirm_household`) with three
facts: **which household**, **what changes**, **who signs** ("You, as a
founder" / "You propose it. It happens once 2 of 3 members have signed." /
"You. Leaving is always your own choice.").

### 2.2 A change waiting on signatures

The quorum flow is `envelope → cosign → assemble`, and the node is
**stateless** about it: "the envelope travels with the caller, each member
cosigns on THEIR OWN node with THEIR OWN pen, and any member assembles"
(`family_api.rs:1219-1226`). So the pending change lives on the device that is
carrying it:

* proposing shows a `CeremonyBlock` (`ceremony_household_change`): the proposal
  ("Remove Cy"), who proposed it, each active member as proposed / signed /
  not yet, and "1 of 2 signed";
* **Sign it** (`btn_household_change_sign`) cosigns with this node's owner;
  **Say no** (`btn_household_change_refuse`) drops it from this device;
* **Copy for the others** (`btn_household_change_copy`) puts
  `{change_envelope, signatures}` on the clipboard; another member pastes it
  into `input_household_change_paste` and opens it (`btn_household_change_import`)
  on their own node, signs, and sends it back;
* **Make the change** (`btn_household_change_apply`) assembles once the node
  said the quorum is met, or the count reaches M. The node verifies the quorum,
  not the client.

A pasted change for a household this person is not in on this node, or for a
`founder_only` household, is refused on the card
(`household_change_note`) and changes nothing.

## 3. Contracts (who)

All node-owned, all at the node URL (`ClientHouseholds(apiClient, nodeBaseUrl)`),
never `$baseUrl`. CIRISServer `origin/main` @ `046e1b39`, `src/family_api.rs`,
routes mounted at `:1624-1632`.

| value | endpoint | owner | state |
|---|---|---|---|
| my households | `GET /v1/families?after=` → `{families, resume}` | CIRISServer | **live** (`:748-792`); owner session, delegates may read; the fold, accord family excluded; paged 100 (max 500) by id, `resume` = next `after`. The client follows `resume` (max 20 pages) |
| one household's view | `{family_id, name, consensus_protocol, founded_at, members[{key_id, role, joined_at}], my_role, envelope{subject, attester, cohort_scope, dimension, persist_row_hash}}` | CIRISServer | **live** (`view`, `:541-571`); `attester` is null for a row with no signed read (`authority_of`, `:529-539`) |
| read one | `GET /v1/families/{id}` | CIRISServer | **live** (`:796-808`); a non-member gets `family.not_found` 404, identical to an unknown id (`:508-527`). The card reads the list and does not call this |
| form | `POST /v1/families {name, consensus_protocol?, members?}` → 201 + view | CIRISServer | **live** (`:661-736`); `majority`/`unanimous` stored as `quorum:M/N` over the founding roster (`normalize_protocol`, `:430-456`) |
| dissolve | `DELETE /v1/families/{id}` → `{family_id, dissolved}` | CIRISServer | **live** (`:1153-1183`); founder_only one call, quorum through the envelope |
| leave | `POST /v1/families/{id}/leave` → `{family_id, left}` | CIRISServer | **live** (`:1008-1082`); any protocol; the last founder of a family with others is refused `family.last_founder` |
| propose | `POST /v1/families/{id}/changes/envelope {action, key_id?, role?}` → `{change_envelope, signing_bytes_base64, required_signatures, signers}` | CIRISServer | **live** (`:1244-1386`); quorum only |
| sign | `POST /v1/families/{id}/changes/cosign {change_envelope, signatures}` → `{signature, signatures, required_signatures, quorum_met}` | CIRISServer | **live** (`:1420-1473`); this node's owner signs with their own pen |
| apply | `POST /v1/families/{id}/changes/assemble {change_envelope, signatures}` | CIRISServer | **live** (`:1492-1612`); the node verifies the quorum, the client only counts |
| who to name | `GET /v1/contacts` | CIRISServer (`src/contacts_chat.rs`) | **live**; the founding-member picker on the form card, and the names on the founders row and the ceremony's signer chips. Called through `listContacts(nodeBaseUrl)` (`ClientHouseholds.contacts`), at the node URL like the household routes themselves — it built its URL from `$baseUrl` until the People review (CSD-005 §3) |
| who "you" are | `GET /v1/setup/owned-nodes` → `owner` | CIRISServer | **live**, loopback; so the ceremony can say whether you have signed and the founders row can say "You" |
| add / remove / role, through the shared view model | `POST /v1/families/{id}/members`, `DELETE /v1/families/{id}/members/{key_id}`, `POST /v1/families/{id}/members/{key_id}/role` | CIRISServer | **live**; reachable from this screen's arm because `HouseholdsViewModel.confirm()` dispatches every act and the hub hands it to the shared `ConfirmSheet`. No control on the hub requests them: their door is the roster on Family › People (§3.1) |
| refusals | `{error, reason_id, detail}`; 17 `family.*` ids | CIRISServer | **live**; every id has an `en.json` key under `family.*`, rendered by id through `NodeRefusal` |
| a store for pending changes | none — the envelope is carried by hand | CIRISServer | **not a route.** Stated in §2.2 and on the card, not faked. Draft ask in the PR report |
| rename | none | CIRISServer | **not a route.** A household keeps the name it was formed with; the card says so and offers no rename. Draft ask in the PR report |

### 3.1 One door per act, one view model for two screens

The route gate (`packaging/check_csd_routes.py`) sees six mutating routes on
both `LayerFamily` and `HouseholdMembers`: dissolve, leave, envelope, add,
remove, role. **They are not two doors.** Each act has exactly one control:

| act | the one control | screen |
|---|---|---|
| form, leave, dissolve, sign, apply | `btn_household_create_submit`, `btn_household_leave`, `btn_household_dissolve`, `btn_household_change_sign`, `btn_household_change_apply` | the hub (this card) |
| add, remove, make founder / member | `btn_household_member_pick_*`, `btn_household_member_remove_*`, `btn_household_member_role_*` | the roster (Family › People) |

The gate sees both because the two screens share one `HouseholdsViewModel`
(so the household picked in one is the one shown in the other), and its
`confirm()` → `direct()` / `propose()` dispatches every act after the shared
`ConfirmSheet`. The heuristic closure follows `viewModel::confirm` from each
screen into the whole dispatcher, which is a fact about the closure, not about
the UI. The one route that IS two doors by design is
`POST /v1/families/{id}/changes/envelope`: a quorum dissolve is proposed from
the hub, a quorum add / remove / role from the roster.

Recorded as the decision in `packaging/csd_routes_baseline.json` (the six
`:: HouseholdMembers, LayerFamily` rows). The follow-up that removes five of
them is splitting `confirm()` into a household half and a roster half so the
closure reflects the controls; it is a Kotlin change with a test run behind it,
not a documentation one.

### 3.2 Stated limits (on the card, `household_limits`, and here)

These are the network's, not the card's, and the card says them rather than
promising otherwise:

1. **A roster change after forming reaches only this node.** Creation and
   removals replicate; an added member, a role change and a quorum change do
   not reach members' other nodes, and a member whose node holds the stale
   record gets `family.bad_change` on cosign. CIRISPersist#910 (`put_family` is
   a plain INSERT; `supersede_group_row` never re-indexes).
2. **Someone removed cannot be added back.** The family fold has no
   re-admission; the node refuses `family.readd_unsupported` (409). CIRISPersist#910.
3. **The name is fixed at forming.** No rename route (§3).

## 4. Flow (how)

`testing/flows/drafts/csd-100-household.yaml`. Sign in on a node ≥ 0.5.216 as
its owner; open Family › Rules (the hub).

On a node where the owner is in no household:

```yaml
expect:
  state: empty
  visible: [layer_hub_family, households_empty, card_household_create, input_household_name, btn_household_create_submit]
```

Form one (`input_household_name` → "Flow household", `btn_household_create_submit`):

```yaml
expect:
  state: populated
  visible: [households_switcher, household_record, card_household, household_protocol, household_founders, household_my_role, household_limits, btn_household_leave, btn_household_dissolve]
  text: {household_protocol: "founder decides", household_my_role: "Founder"}
```

Open the receipt on the household row (the `btn_receipt_*` hamburger) — five
facts, the rule row `NotSent`. Dissolve → `sheet_confirm_household` with three
facts → confirm → the household is gone from the switcher.

## 5. QA plan

**Platforms.** All five; the card calls only the node.

**Tested (desktopTest).** `HouseholdsSupportTest`: the node's view decodes;
protocols parse the way the node parses them; a quorum household is never
offered a single call; a founder acts in one call and nobody else acts at all;
leaving is always direct; signer states; a change survives the trip between
members; the receipt says what the node sent and what it did not.
`HouseholdsViewModelTest`: every page is read; a node without the routes is not
an empty household; a refused read keeps its id; a quorum removal is a proposal,
sign and apply carry the signatures; a pasted change for another household is
refused.

**Untested, and must be established on a live node.** The ceremony across two
real nodes (it needs a quorum household whose record is on both, which #910
makes fragile); `family.author_signer_unavailable` on a node whose owner pen
cannot open; the pager past 100 households.

**Not tested here.** Clipboard contents (no `/clipboard` endpoint); the flow
asserts the button, not what was copied.
