# CSD-121 — Members and roles (a role is a delegation, a member is a person who said yes)

**CSD**: CSD-121 · **Standard**: CSD/3 (`CIRISClient/CSD.md`) + CSD/4 (`CIRISServer/FSD/CSD4.md`, catalogue entry T6, `csd:tests` in §3a) · **Origin**: the registry fold, FSD-005
**Flow**: unwritten
**Reads with**: CSD-103 (the roster as every community has one), CSD-106 (invites), CSD-124 (the delegation a role is), CSD-043 (users on a node — not this), `COMMON_CARDS_ANALYSIS.md` (recommends FOLD INTO CSD-103 + CSD-055 §7 on adoption)
**Vendored from**: CIRISRegistry `FSD/CSD/CSD-121-members-and-roles.md`, branch `fold/org-as-affiliation`, commit `14fbcd0`. Changes here are mechanical only: this line, `registry_sha256` re-pinned from `d58715660…` (CC rc12) to this repo's vendored rc6 registry, and the `csd:surface` fence relabelled plain `yaml` because the checker refuses a `proposed:` screen (CSD-108's precedent). Also: the `csd:tests` fence relabelled plain `yaml` until CIRISServer's `requirements.yaml` carries this card (`evidence/blocked_upstream.tsv`).

```yaml csd:stage
stage: envisioned
owner: CIRISRegistry
```

## 1. Mission (why)

**An organization's admin can invite a person, see who has accepted, give a
member a role, narrow or end it, and see — for every role — the delegation it
is, who conferred it, and what it lets the member do; and a member can see the
same about themselves.** Serves **Autonomy** (nobody joins without their own
signed acceptance, CC 3.1.3.2) and **Justice** (a role is a scoped, attenuated,
revocable delegation, CC 4.4.3.2.8 C and CC 4.5, never a flag an administrator
sets on a user row).

**The Portal's `org_membership.role` enum becomes four presets over delegation
scopes** (FSD-005 §4; CC 3.3.9: "`OrgAdmin` / `KeyManager` / `Operator` /
`Viewer` are non-normative presets over scope sets, and admin scopes are open
vocabulary, their canonical presets published as data with the family model
manifest"): `OrgAdmin` carries `sub_delegation` +
`license` + `grant` + the affiliation's admin verbs; `KeyManager` the
key-binding right; `Operator` the operational verbs; `Viewer` nothing but
membership. The card offers the presets; the wire carries the scopes; the
receipt shows the scopes. An admin who wants a role no preset names picks the
scopes. Persist v60 (V205) dropped the `org_membership` table; the Portal's
user screens (`*OrgUser*`, `UpdateUserOrgRole`, `ListOrgMembers`) are retired,
not migrated.

**A member is a self, human or agent; the root is a human.** An agent joins through
its partnership (CC 4.4.3.4.3) and may hold a role (ciris.ai/philosophy: one
Constitution for NHI). The chain every role hangs from roots in the founders, who are
people (CC 3.4.7.3); a node is never a member of record and never a root.

**A role has a term, always.** `delegation_valid_until` is required on every edge
in an affiliation's `hierarchy` (CC 4.4.3.2.8 C); the
charter's `role_term` (CSD-120, integer days 1..=365) bounds it; re-conferral
renews it (ciris.ai/constitutional-mesh: authority "decays like everyone else's").
An edge with no term, past the ceiling, or past the charter's term is refused at
the door — persist v60 `federation_delegation_term_required` with the rule named
(`term_missing`, `term_unreadable`, `over_ceiling`, `over_role_term`; CIRISPersist#1048)
— so the card does not offer one: the founders are the roster root, not
an edge, and re-confer when terms lapse. A lapsed `moderate` role is a moderator
lapse under CC 4.5.4: the room auto-promotes or fails secure in the same step,
never an unmoderated window.

**What CC says for this card.** Admission is two consents in both kinds and an
`OrgAdmin` cannot seat anyone alone (CC 3.1.3.2; CC 3.3.9); the presets are
non-normative over scope sets (CC 3.3.9); in an affiliation every role edge is termed,
in a community only duty-bearing edges are (CC 2.4.1; CC 4.4.3.2.8 C). The admin
presets are DATA, the family model manifest carrying the config record's schema
(CC 4.4.3.2.8 "The record"), not a closed set in the text, so the four preset names
below are what the manifest ships and an affiliation may name its own; succession is
the charter's ("Succession and dissolution"): a successor seat or `dissolves_at`, and when the
last deciding seat lapses with neither the affiliation is dissolved at that instant
(every role edge ends with it; a community whose last founder leaves continues under
its protocol and may seat a founder by it).

**The floors bind power over members, not the count of voices (CC 4.4.3.2.8).**
Appointment is
"the body's signature under its protocol together with the appointee's own
`membership:acceptance` — two consents, as CC 3.1.3.2 already requires — so a
one-voice body may hire." Where the body has one deciding seat, an act against a
member — `membership:removal`, quarantine, slash, a takedown of the member's content —
"is admitted only when countersigned by a key outside the deciding seat: the designated
official where that key is not a seat, or a review-holder of the body that conferred or
oversees the affiliation; without one the act stands as a proposal in the
reconsideration path of CC 4.5.5, never as a decision." "The named-moderator duty of
CC 4.5.4 is discharged by the designated official." Two or more deciding seats decide
under their own protocol as a community does. Governance table row
`affiliation_member_action` (`governance_policy.tsv`: cosigned, the deciding seat +
one key outside it, 2/2); model `formal/affiliation/Affiliation.lean`
(`a2_one_voice_appoints_with_acceptance`, `a3_one_voice_removes_nobody_alone`,
`a3_seat_cannot_countersign_itself`, `a3_outside_countersign_admits`,
`a5_two_voices_decide_as_a_community`); wire door `CLM-affiliation-one-voice-countersign`
staged on CIRISPersist#1143. The card therefore has two sheets: the
appointment confirm names the appointee's acceptance as the second consent, and the
**member-action countersign sheet** (below) names, for a one-seat body, the outside key
that must countersign or the act files as a reconsideration proposal.

**Membership and role are two rows and two lists.** Membership is the
`membership:{stage}` sequence onto the roster (CSD-103 renders it). A role is a
`delegates_to` edge from the affiliation's root, or from a holder of
`sub_delegation`, to the member, carrying `community_id` so the term resolves
through the charter. Removing a role leaves the membership; removing the
membership (CSD-103) cascades every role, because the roster no longer names the
key the edges point at.

## 2. Surface (what)

```yaml
# csd:surface once the screen is declared in CIRISApp.kt
surface: proposed:affiliation-roles
screen: proposed:AffiliationRoles
```

Placement: Communities and Businesses › People, beside CSD-103's roster
(`AffiliationsRoster`). One row per member; the role chips on the row open this
card's role sheet.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: "membership:{stage}"
    bind: {stage: acceptance}
    use: display-only
    type: timestamp
    example: "2026-10-08T14:02:11Z"
    renders: "'Member since' — the instant of THEIR acceptance row, never the inviter's proposal"
    tag: "proposed:txt_member_since_*"
  - ceg: x_private:role_preset
    use: emit
    type: "enum[OrgAdmin,KeyManager,Operator,Viewer,custom]"
    example: "KeyManager"
    renders: "the preset chip; picking one fills the scope set below and shows it before signing"
    tag: "proposed:select_role_preset_*"
  - ceg: x_private:delegated_scope
    use: emit
    type: "list[string]"
    example: ["sub_delegation", "license"]
    renders: "'May do' — the scope set on the edge, each scope glossed in the CC 4.4.3.4.3 words; TIGHTEN-ONLY against the conferrer's own scope (CC 4.5)"
    tag: "proposed:list_role_scope_*"
  - ceg: x_private:conferred_by
    use: display-only
    type: string
    example: "wa-admin-2b1c"
    renders: "'Conferred by' — the delegator's key; 'the founders' when the edge is cosigned by the quorum"
    tag: "proposed:txt_role_conferred_by_*"
  - ceg: x_private:delegation_depth
    use: display-only
    type: int
    example: 2
    renders: "'2 steps below the founders' — the chain above this role, capped at 5 (CC 4.1.1); an admin approving a role sees whether it is direct or a sub-delegation"
    tag: "proposed:txt_role_depth_*"
    assert:
      number: {"proposed:txt_role_depth_*": {min: 1, max: 5}}
  - ceg: x_private:delegation_valid_until
    use: emit
    type: timestamp
    example: "2027-01-06T00:00:00Z"
    renders: "'Until' — required, at most the charter's role_term days from now (ceiling 365); an expired role confers nothing and reads 'expired — renew?'; renewal is a new edge; the door's refusal is shown by its rule name"
    tag: "proposed:input_role_until_*"
  - ceg: x_private:member_action_countersign
    use: emit
    type: "enum[not_needed,designated_official,review_holder,proposal_only]"
    example: "designated_official"
    renders: "on removal / quarantine / slash / takedown of a member in a ONE-seat body: 'This organization has one deciding seat, so this act needs a countersign from outside it: {designated official} / {review-holder of the overseeing body}' with the signer picked; with none available the sheet says 'This will be filed as a reconsideration proposal (CC 4.5.5), not decided' — never a silent refusal; `not_needed` for two or more seats (their protocol carries the second voice)"
    tag: "proposed:sheet_member_action_countersign_*"
  - ceg: x_private:role_revoke_cascade
    use: display-only
    type: "list[string]"
    example: ["wa-clerk-77a0", "wa-clerk-9c13"]
    renders: "in the revoke confirm: 'Ending this role also ends 2 roles it conferred' — the subtree a withdrawal severs (CC 4.5), named before the act"
    tag: "proposed:sheet_role_revoke_cascade_*"
```

**The depth row is the one CSD-055 could not fill** (`delegation_depth:
unconfirmed, blocked_by CIRISServer#663`). This card has the same dependency and
says so in §3; it cannot pass `building` until a chain read exists. CSD-055 §3
also records that `GET /v1/auth/device/grants` carries no `expires_at` although
every edge has a term; the required-term rule above depends on that wire gap
closing in the same read.

```yaml csd:states
populated: {tag: "proposed:list_affiliation_roles"}
empty:     {tag: "proposed:txt_roles_empty", renders: "No roles conferred yet — every member is a member only. The founders hold the root."}
loading:   {renders: "the frame with a progress affordance and NO sentence"}
error:     {tag: "proposed:txt_roles_error", renders: "Couldn't read roles from this node, so this list is not complete."}
```

## 3. Contracts (who)

| What | Route | Owner | State |
|---|---|---|---|
| invite / accept / remove a member | CSD-106 / CSD-103 routes (`/v1/communities/{id}/…`) | CIRISServer | confirmed by those cards; an affiliation's invitations ride `GroupScope::Affiliations` (0.5.225) |
| confer a role (write a `delegates_to` with `community_id`, from a member with `sub_delegation`, or cosigned by the founders' quorum) | none at affiliation scope today (`POST /v1/auth/device/delegate` keeps one scope and a 600 s term) | CIRISServer | `blocked_by: CIRISServer#752` (the doors) |
| read roles with chain and depth | none (`GET /v1/auth/device/grants` is a flat list of device grants) | CIRISServer | `blocked_by: CIRISServer#663` |
| end a role (a `withdraws` on the edge) | none | CIRISServer | `blocked_by: CIRISServer#752` |
| scope attenuation, depth cap, cycle refusal and the term enforced at admission | CIRISPersist | CIRISPersist | confirmed — persist v60 (#1031 cycle, #1032 term lens, #1048 `federation_delegation_term_required`); `CLM-delegation-term` staged on CIRISServer#752 / CIRISEdge#896 |
| the three CC 3.3.9 kinds retired | CIRISPersist V205 | CIRISPersist | confirmed — persist v60; CC 3.3.9: "retired by amendment (CC 4.5.1): their names stay in this section so citations resolve, no substrate admits them as record kinds" |
| a one-seat body's act against a member admitted only with an outside countersign (`affiliation_member_action`, 2/2); appointment = protocol signature + acceptance | CIRISPersist | CIRISPersist | `blocked_by: CIRISPersist#1143` (`CLM-affiliation-one-voice-countersign` staged there) |

## 3a. Topology (CSD/4)

T6 is the entry (CSD4.md §2). `requirements.yaml` carries no CSD-121 yet; the
block is the entry the server must add (README, asks of CIRISServer) before the
card can claim it, and `check_csd4` reports the absence by name until then.

```yaml
# `csd:tests` upstream. Relabelled plain `yaml` by the vendoring: CIRISServer's requirements.yaml
# (pinned here at v0.5.233, and still at v0.5.245) carries no CSD-121, and a card may not claim tests
# the server does not carry (evidence/blocked_upstream.tsv). The label returns with the server's entry.
CSD-121:
  catalogue_version: 3
  topology: T6
  tests:
    roster:
      at: [community.formed, community.member]
    role_is_a_chain:
      at: [delegation.chain, community.member]
    term_lapsed:
      at: [delegation.link.expired]
```

**What the vocabulary lacks for this card.** `term_lapsed` holds only in T18
(`delegation_expire`, known red on CIRISServer#751); the planner reselects it and
says so. The role edge is T6's `delegation` relation, refused today because
"`POST /v1/auth/device/delegate` writes owner→actor only; a chain needs the
delegate's own act". No atom says a delegation was *refused* (a scope not held, a
missing term): CSD4_EVALUATION §4.2's `cannot_act(person, op, reason)` negative is
the missing piece, listed in the README's asks.

## 4. Flow

Unwritten at `envisioned`. The chain a flow will drive: founders confer
`OrgAdmin` on A → A confers `KeyManager` on B → A tries to confer a scope A does
not hold and is refused → A tries a term of 400 days and is refused
`over_ceiling` → the founders end A's role → B's role is gone, by cascade, and
the card showed that it would be before the act.

## 5. Acceptance

Unsigned. Not tested here: the roster's DEK rotation on removal (CSD-102), the
user accounts on a node (CSD-043, a different thing: a login, not a membership).
