# CSD-101 — Household members (the roster, on Family › People)

**CSD**: CSD-101 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the plan's B5 Family; CIRISServer 0.5.216 (`/v1/families/{id}/members`)
**Pairs with**: CSD-100 (the household itself, in the Family hub on Family › Rules) · CSD-005 (People: where a person becomes a contact first)
**Flow**: `testing/flows/drafts/csd-101-household-members.yaml` (staged; floor `unreleased`)

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**Standing in Family › People, a person can see who is in the household they
are looking at, with each member's role, and — when the household's rule lets
them — add someone from their contacts, remove a member, or make a member a
founder. When the rule does not let them, the card says who can.**

The roster is CC 3.3.4's `members: [{key_id, joined_at, role}]` as the node's
fold reads it (record minus revocations, `active_members`), so it is who is in
the household now. Serves **Contextual Integrity**: CC 4.4.3.4.1 wraps every
extant `cohort_scope: family` DEK to a new member on admission ("I added Carol
to the household"), so adding someone lets them read what the household
already shared. The confirm says exactly that.

## 2. Surface (what)

```yaml csd:surface
surface: household-members
screen: HouseholdMembers
```

`nav_map` derives `circle_family -> tab_people -> nav_epistemic_household_members`:
Family › People now holds two cards, Contacts (CSD-005) and this one, so the
shell lists them as rows. It is placed in Family only (`CirclesNav.kt`), and is
not agent-only: the node serves it.

**Why a card of its own in People, and not a filter on Contacts.** A contact
is a `consent:replication:v1` grant (CSD-005); a member is a row on a family's
roster. They are different records with different receipts, and one person can
be either without the other. Contacts does no scope filtering today and a
filtered contact list would claim a membership the grant does not carry. The
two meet in one place: **a member is added from your contacts**, because the
node admits only a registered identity (`family.unknown_member_key`) and People
is where you come to know someone.

The switcher at the top is the same one as CSD-100's, driven by the same view
model, so the household chosen in Rules is the one shown here.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:family_members
    use: display-only
    type: "list[string]"
    example: ["wa-ada-88b1", "wa-bo-4a19"]
    renders: "one row per active member: the contact's name if they are one, 'You' for the owner, else a short key; the mono key under it; 'joined 2026-09-20'"
    tag: household_members_list
  - ceg: x_private:member_role
    use: display-only
    type: "enum[founder,member]"
    example: "founder"
    renders: "a chip on the row: Founder (brand) or Member (mute); any other role the node sends is shown as sent. 'You' as a second chip on the owner's own row"
    tag: "household_member_{keyId}"
  - ceg: x_private:member_add
    use: emit
    type: string
    example: "wa-cy-19c2"
    renders: "Add someone → a card listing contacts who are not already members; picking one opens the three-fact confirm. No contacts: 'There's nobody to add. Add them in People first, then come back.'"
    tag: btn_household_member_add_open
  - ceg: x_private:member_remove
    use: emit
    type: string
    example: "wa-bo-4a19"
    renders: "Remove, on each row but your own; the confirm says they get nothing new, keep what they have, and can't be added back"
    tag: "btn_household_member_remove_{keyId}"
  - ceg: x_private:member_role_change
    use: emit
    type: "enum[founder,member]"
    example: "founder"
    renders: "Make founder / Make member, on each row but your own"
    tag: "btn_household_member_role_{keyId}"
```

**No hamburger on a member row.** The node sends one envelope per household,
not per member, and a membership is a fold over rows (the record, its growths
and the revocations) rather than one signed claim. The household's receipt is
on the household row in CSD-100. A member row is a reading of that record, and
drawing a receipt on it would name an attester the node never sent.

**Your own row offers nothing.** Removing yourself is leaving
(`family_api.rs:977-981`), which is the household's act and lives in CSD-100.
When the node would not say which key is the owner's (`GET /v1/setup/owned-nodes`
unreadable), no row can be told apart from yours, so no remove or role control
is offered at all.

```yaml csd:states
populated: {tag: household_members_list, renders: "the switcher, the governance sentence when you cannot change the roster, one row per member, Add someone when you can, and the limits sentence"}
empty:     {tag: household_members_no_household, renders: "You're not in a household yet. Form one in Family › Rules. With btn_household_members_go_rules. A household always has at least its founder, so an empty roster is not a state: the empty state is having no household"}
loading:   {tag: household_members_loading, renders: "a progress affordance and no sentence"}
error:     {tag: household_members_error, renders: "the failed read, or the node's refusal by id; household_members_not_on_this_node for a node without the routes: This node can't form households yet. It needs ciris-server 0.5.216 or newer."}
```

### 2.1 Who may change the roster

The same rule as CSD-100 §2.1 (`routeOf`): a founder of a `founder_only`
household acts in one call; in a `quorum:M/N` household every add, remove and
role change becomes a proposal the others sign (CSD-100 §2.2), and the confirm
says "You propose it. It happens once M of N members have signed."; anyone else
sees "Only a founder can change who is in this household. You can still leave
it." (`household_governance_note`) and no controls.

## 3. Contracts (who)

Node-owned, at the node URL. CIRISServer `origin/main` @ `046e1b39`, `src/family_api.rs`.

| value | endpoint | owner | state |
|---|---|---|---|
| the roster | `members[{key_id, role, joined_at}]` in the list view | CIRISServer | **live** (`view`, `:541-571`); the fold, never the raw record |
| add | `POST /v1/families/{id}/members {key_id, role?}` → view + `dek_rewrap` | CIRISServer | **live** (`:858-921`); founder_only founder only; the target must be a registered identity (`check_addable`, `:925-947`); the node re-wraps existing DEKs to them and reports it |
| remove | `DELETE /v1/families/{id}/members/{key_id}` → view + `removed` | CIRISServer | **live** (`:969-1004`); the last founder cannot be removed (`family.last_founder`) |
| role | `POST /v1/families/{id}/members/{key_id}/role {role}` → view | CIRISServer | **live** (`:1091-1149`); a role is a short non-empty name (`family.bad_role`) |
| quorum add / remove / role | `POST /v1/families/{id}/changes/envelope {action: add\|remove\|role, key_id, role?}` → `{change_envelope, signing_bytes_base64, required_signatures, signers}` | CIRISServer | **live** (`:1244-1386`); the proposal is then signed and applied from the hub (cosign / assemble, §3.2) |
| the households, and which one is shown | `GET /v1/families?after=` → `{families, resume}` | CIRISServer | **live** (`:748-792`); the same read the hub makes, through the shared view model, so the switcher here is the switcher there |
| who to add | `GET /v1/contacts` | CIRISServer | **live** (`src/contacts_chat.rs`); the only source. The v3 contact code (CSD-092) would let a person find someone first; it is unmerged, so this card picks from existing contacts |
| who "you" are | `GET /v1/setup/owned-nodes` → `owner` | CIRISServer | **live**, loopback |
| refusals | `family.unknown_member_key`, `family.already_member`, `family.not_a_member`, `family.last_founder`, `family.readd_unsupported`, `family.bad_role`, `family.not_authorized`, `family.quorum_pending`, … | CIRISServer | **live**; each has an `en.json` key and renders by id (`household_refusal`) |
| leave / dissolve, through the shared view model | `POST /v1/families/{id}/leave`, `DELETE /v1/families/{id}` | CIRISServer | **live**; reachable from this screen's arm because `HouseholdsViewModel.confirm()` dispatches every act and this screen hands it to the shared `ConfirmSheet`. No control here requests them: their door is the hub on Family › Rules (§3.1) |

### 3.1 One door per act, one view model for two screens

The route gate sees six mutating routes on both this screen and the hub. They
are not two doors: leave, dissolve, sign and apply have their controls on the
hub; add, remove and role have theirs here (`btn_household_member_pick_*`,
`btn_household_member_remove_*`, `btn_household_member_role_*`). Both screens
share one `HouseholdsViewModel` so the household picked in one is the one
shown in the other, and its `confirm()` dispatches every act, which the
heuristic closure follows from either screen. Only
`POST /v1/families/{id}/changes/envelope` is two doors by design (a quorum
dissolve from the hub, a quorum roster change from here). Recorded as the
decision in `packaging/csd_routes_baseline.json`; the household CSD's §3.1
carries the full table and the follow-up.

### 3.2 Stated limits

1. **An added member or a new role reaches only this node.** The other
   members' nodes keep the roster they first received. CIRISPersist#910.
2. **Someone removed cannot be added back.** The node refuses
   `family.readd_unsupported`; the confirm for a removal says so before it
   happens. CIRISPersist#910.

## 4. Flow (how)

`testing/flows/drafts/csd-101-household-members.yaml`. Sign in as the owner of a
node ≥ 0.5.216 who has formed a household (CSD-100's flow leaves one); open
Family › People › Household.

```yaml
expect:
  state: populated
  visible: [households_switcher, household_members_list, btn_household_member_add_open, household_limits]
  count: {of: "household_member_*", min: 1}
```

Add someone (`btn_household_member_add_open`) → `card_household_member_add`,
with either a `btn_household_member_pick_*` per contact or
`household_member_add_no_contacts`.

## 5. QA plan

**Platforms.** All five.

**Tested (desktopTest).** The routing rule and the founder / member / quorum
cases (`HouseholdsSupportTest`, `HouseholdsViewModelTest`), and the placement:
the roster is in Family › People beside Contacts and in no other circle, and
Family › Rules gains no second household card (`CirclesNavTest`).

**Untested, and must be established on a live node.** Adding a real contact
(it needs a second identity registered on the node); `dek_rewrap.excluded`,
which the card does not show yet; a role name other than founder or member
arriving from another node.
