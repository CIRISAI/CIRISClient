# CSD-106 — Invitations to a household or a community (nobody joins without saying yes)

**CSD**: CSD-106 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the maintainer's ruling of 2026-09-30 (CIRISConstitution#133; CIRISPersist#955; CIRISServer#700)
**Extends**: CSD-100 (the household hub) · CSD-101 (the household roster) · CSD-102 (the community hub) · CSD-103 (the community roster). **No new card** (§2).
**Flow**: none. Nothing it needs is built, so the stage is `envisioned`

```yaml csd:stage
stage: envisioned
owner: CIRISClient
```

## 1. Mission (why)

**A person is added to a household or a community only by accepting an
invitation themselves. The person invited sees every invitation addressed to
them, with who sent it, to which group, in which role and until when, and
accepts or declines it. The person who invited sees it waiting until it is
answered or expires.**

The maintainer ruled on 2026-09-30 that adding someone to a family or a
community requires their consent, **founding members named when the group is
formed included**. The normative text is CIRISConstitution#133. Today every
roster-growing door admits a new member on the existing members' signatures
alone (CC 4.4.3.2.3's admit predicate), so a founder can enrol any key they can
name, and the enrolled person's node begins receiving the group's rows and key
wraps. Serves **Contextual Integrity** and **Integrity**: joining a
`cohort_scope: family` or `community` roster changes who can read what, in both
directions (CC 4.4.3.4.1 wraps every extant family DEK to a new member), so it
must be the joiner's own signed act.

**Reverse quorum is not a membership rule.** It stays CSD-070's commons
objection brake and is not offered here.

## 2. Surface (what)

```yaml csd:surface
surface: layer-family
screen: LayerFamily
scopes: [layer-family, layer-local-community, layer-global-communities]
```

**No new card: this extends four that exist, found by the routes they call**
(`packaging/check_csd_routes.py`). The cards that call the roster-growing
routes today are the places the invitation replaces them:

| role | where it goes | the card it extends | the route it replaces |
|---|---|---|---|
| **the inviter's pending state** — "Invited, waiting for them to accept, expires {date}" as a row beside the members, and withdrawing it | Family › People › Household; Neighbours / Communities and Businesses › People | CSD-101 (`HouseholdMembers`), CSD-103 (`CommunityRoster`) | `POST /v1/families/{id}/members`, `POST /v1/communities/{id}/members`, and the quorum add through `…/changes/envelope` |
| **founding with other people** — found alone, then invite | Family › Rules; the two community Rules hubs | CSD-100 (`LayerFamily`), CSD-102 (`LayerLocalCommunity` / `LayerGlobalCommunities`) | `members` on `POST /v1/families` and `POST /v1/communities` |
| **the invitee's inbox** — accept or decline | the same hubs, above the household or community list | CSD-100, CSD-102 | none; it is new |

The inbox sits on the hub because the person invited is by definition not in
the group yet: its roster screen has nothing to show them, while the hub is
where "You're not in a household yet" and "You are not in any community yet"
already stand. A household invitation shows on Family › Rules and a community
one on the Rules hub of its tier (`community` → Neighbours, `affiliations` →
Communities and Businesses, `CohortScope`'s fold). The server's design serves
one inbox across both kinds (§3). The client splits it by circle rather than
drawing one list that claims to be neither. The `csd:surface` names the Family
hub; `scopes` names the other two.

**Accept is behind a ConfirmSheet with three facts**: *which group* (name, kind,
who invited you), *what changes* ("You'll be able to read what this household
already shares, and they'll see what you share with it"; under a quorum rule,
"You're accepting. You join once {M} of {N} members have signed"), and *who
signs* ("You, with your own key"). Decline is one tap and says it is final:
inviting again is a new invitation.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:membership_invitation
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "the invitee's inbox row: 'Ada invited you to The Okafors (household) as a member. Until 2026-10-30.' The group, the inviter, the role offered and the expiry come from the proposal row (`membership:proposal:v1`, CIRISPersist#955); nothing is inferred from a roster the invitee cannot read"
    tag: "proposed:invitation_row_{proposalId}"
    blocked_by: CIRISPersist#955
  - ceg: x_private:membership_acceptance
    use: emit
    type: unconfirmed
    example: "unconfirmed"
    renders: "Accept, behind the three-fact confirm, signed with the invitee's own key (`membership:acceptance:v1`). Under a quorum rule the row then reads 'Accepted. Waiting for the members to sign', never 'Joined', until the admitting record lands"
    tag: "proposed:btn_invitation_accept_{proposalId}"
    blocked_by: CIRISPersist#955
  - ceg: x_private:membership_decline
    use: emit
    type: unconfirmed
    example: "unconfirmed"
    renders: "Decline, signed with the invitee's own key (`membership:decline:v1`). Final: the row goes, and a new invitation is needed to join"
    tag: "proposed:btn_invitation_decline_{proposalId}"
    blocked_by: CIRISPersist#955
  - ceg: x_private:membership_invitation_pending
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "on the inviter's roster (CSD-101 / CSD-103): one row per open invitation, 'Invited: waiting for Cy to accept · expires 2026-10-30', then 'Declined' or 'Expired' as the proposal's state says, and never counted as a member"
    tag: "proposed:invitation_pending_{proposalId}"
    blocked_by: CIRISPersist#955
  - ceg: x_private:membership_invitation_expires_at
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "'expires {date}' on both sides. At most 30 days after the proposal was signed; judged on the signed instants, never on this device's clock"
    tag: "proposed:invitation_expires_{proposalId}"
    blocked_by: CIRISPersist#955
```

```yaml csd:states
populated: {tag: "proposed:invitations_list", renders: "one row per open invitation addressed to this person on this hub, newest first"}
empty:     {tag: "proposed:invitations_empty", renders: "nothing: no invitations is the normal case, and the hub's own empty state speaks"}
loading:   {tag: "proposed:invitations_loading", renders: "a progress affordance and no sentence"}
error:     {tag: "proposed:invitations_error", renders: "'Couldn't read your invitations.' with the node's refusal by id; a node without the routes says 'This node can't carry invitations yet', never 'you have none'"}
```

## 3. Contracts (who)

**Nothing here is built.** The persist rows and gate are CIRISPersist#955
(target v52.0.0, open). The server routes are designed in CIRISServer's
`FSD/MEMBERSHIP_INVITES.md` §3, which exists only on #700's branch
`fix/evict-device-0.5.218` (open, unreleased). This card cites no route path:
until a server branch serves one, a path written here would be a guess.

| value | endpoint | owner | state |
|---|---|---|---|
| the proposal, acceptance and decline rows | `membership:proposal:v1`, `membership:acceptance:v1`, `membership:decline:v1` on the attestation plane; no new EnvelopeKind | CIRISPersist#955 | **not built** (design posted on #955; v52) |
| the admission gate | every roster growth needs the joiner's live acceptance, matching group and role, signed no later than `expires_at`; every protocol, `founder_only` included; on the local put and on replication apply | CIRISPersist#955 | **not built** |
| the quorum | stays on the admitting record: one inviter proposes, and the widening that admits the invitee carries the group's M-of-N as today. Under a quorum rule, accepting does not by itself admit | CIRISPersist#955 | **not built** |
| expiry | `expires_at` required, later than `asserted_at`, and at most 30 days after it | CIRISPersist#955 | **not built** |
| founding members | admitted only if they signed the founding record; a listed member who did not sign is refused (`membership_founding_member_unsigned`). The server signs a create with the founder alone, so a founding roster beyond the founder is refused in the meantime | CIRISPersist#955 | **not built** |
| invite, list pending, withdraw; the invitee's inbox, accept, decline | server routes, one set for families and communities | CIRISServer (design in `FSD/MEMBERSHIP_INVITES.md` §3, #700's branch) | **not built**; no path is cited until one is served |
| the interim | every roster-growing door answers **409 `membership.consent_required`**: a direct add, a quorum envelope, cosign or assemble that adds, and a create naming anyone but the founder | CIRISServer#700 | **open, unreleased**. The client renders it by id (`membership.consent_required` in `en.json`) |

## 4. Flow (how)

Not written. It needs two people on two nodes, a route to invite with and a
route to answer with, and none of those exists.

## 5. QA plan

At `sketched`: a `commonTest` that an accepted invitation under a quorum rule
never renders as joined until the roster read says so, and that an expired one
offers no Accept. Expiry is the proposal's signed instant, so the test feeds
instants, not a clock.

**Not guaranteed here.** Delivery of a proposal to the invitee's node is
persist's replication, which a client fixture cannot observe.

## 6. Delta — card vs API vs CC

* **Interim, refuse rather than hold (the maintainer's choice).** Until v52 the
  server cannot deliver a proposal to someone outside the group, so the door is
  closed, not queued. The existing add controls on CSD-101 and CSD-103, and the
  founding-member chips on CSD-100 and CSD-102, will meet a 409 once #700
  ships. Those CSDs record that change in their own §3; they are not rewritten
  ahead of a release.
* **Accepted is not joined.** Under `quorum:M/N` an invitee can accept and
  still not be admitted if the quorum never assembles. The card must never
  report the acceptance as membership.
* **Pair rooms are out of scope.** A two-person chat keeps its own consent, the
  contact grant each side authors (CSD-005, CSD-091).
