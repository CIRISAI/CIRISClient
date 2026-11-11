# CSD-124 — Authority delegations (the right to issue, handed down one attenuating step at a time)

**CSD**: CSD-124 · **Standard**: CSD/3 (`CIRISClient/CSD.md`) + CSD/4 (`CIRISServer/FSD/CSD4.md`, catalogue entry T6, `csd:tests` in §3a) · **Origin**: the registry fold, FSD-005 §4–5
**Flow**: unwritten
**Reads with**: CSD-055 (the same primitive for a person's devices and agents; this card adds the two emission scopes and the chain), CSD-121 (roles are these edges), CSD-122 / CSD-125 (what a `license` / `grant` scope lets a delegate issue), `formal/authority_tree/`, `COMMON_CARDS_ANALYSIS.md` (recommends EXTEND CSD-055 §7 on adoption)
**Vendored from**: CIRISRegistry `FSD/CSD/CSD-124-authority-delegations.md`, branch `fold/org-as-affiliation`, commit `14fbcd0`. Changes here are mechanical only: this line, `registry_sha256` re-pinned from `d58715660…` (CC rc12) to this repo's vendored rc6 registry, and the `csd:surface` fence relabelled plain `yaml` because the checker refuses a `proposed:` screen (CSD-108's precedent). Also: the `csd:tests` fence relabelled plain `yaml` until CIRISServer's `requirements.yaml` carries this card (`evidence/blocked_upstream.tsv`).

```yaml csd:stage
stage: envisioned
owner: CIRISRegistry
```

## 1. Mission (why)

**A principal — an organization's founders, or anyone holding `sub_delegation`
from them — can hand a member the right to issue licences or grants on the
organization's behalf, bounded to a subset of what the principal holds, for a
term, with or without the right to hand it on; everyone involved can see the
whole chain above any edge and how deep it is; and ending an edge shows, before
the act, every edge and every issuance right below it that ends with it.**
Serves **Core Identity** and **Integrity**, on the one rule FSD-005 §5 settles:
**only delegation chains**. A licence and a grant are leaves; the right to issue
them is the delegable thing, and it rides delegation's rules — attenuation
(CC 4.5), the depth cap of five (CC 4.1.1), cycle refusal at admission
(persist v60, CIRISPersist#1031), revocation cascade, and `sub_delegation` as the
only way a chain grows (CC 4.4.3.4.3).

**The root is a human, and authority decays.** Every chain this card shows ends at
an accountable person: the founders of an affiliation, or the person themselves
(CC 3.4.7.3; ciris.ai/constitutional-mesh). Every edge carries a term, the charter's
`role_term` under the constitutional ceiling of 365 days (CC 4.4.3.2.8 C; persist
v60 `federation_delegation_term_required`), and is renewed by
re-conferral; a perpetual edge is refused at the door, and the card shows an edge's
remaining term beside its depth.

**The worked example: how a CIRIS licence gets issued (CC 2.4.1.2.1, 3.3.9,
4.4.3.4.3).** The root community `ciris-canonical` decides by a
strict majority of its three founders, the accord holders A1/B1/C1
(`quorum:2/3`). Two of them cosign one ordinary `delegates_to` row: from
`ciris-canonical` (`community_id: ciris-canonical`) to a registrar's key,
`scope: [license]`, `delegation_valid_until` within the charter's `role_term`.
That registrar then signs `licensure:ciris-canonical:v1` rows alone, and
persist admits them because the `license` chain roots at an edge the quorum
cosigned naming the affiliation (persist v60). Three things the example is
not: it is **not `infra:license`** (there is no such scope and must be none; a
node key may carry only `infra:*`, CC 4.4.3.4.3, so an install can never
issue); it is **not a genesis-bundle member** (CC 3.2 T5 and 5.3.4 fix what the
bundle carries — charter, holders, serve nodes, birth record — and a `license`
edge is a row the reader walks like any other); and it is **not a sale** (the
registrar is a role, with a term; nothing on the edge or the licence is paid
for). Any affiliation does the same under its own protocol with its own id.

**What CC says for this card.** `license` issues on behalf of a delegator that
holds licence authority by quorum, refused `licensure_delegator_not_authority`
otherwise, and the chain roots at that quorum (CC 4.4.3.4.3 "Enforced-admission
rule"; CC 2.4.1.2.1); every edge in a hierarchy is termed (CC 2.4.1; CC 4.4.3.2.8 C).
A `withdraws` of the affiliation's record ends every hierarchy edge and grant with it
(CC 4.4.3.2.8 "Succession and dissolution"), "standing under a withdrawn record is
gone for every later use" (CC 3.2 T4), while licences already issued stand and read
`lapsed`; the cascade sheet names dissolution as the one event that ends every edge
at once. An edge whose scope names a licence class the charter does not declare
confers the right to issue a row no reader honours ("Charter defaults"), and the
sheet says so when the scope is picked.

**Why this is not CSD-055.** CSD-055 is the device-code flow: a person offers a
code, a device approves it, the scopes are a person's own verbs. This card is
the same `delegates_to` primitive with two scopes CSD-055 does not offer —
`license` and `grant` — a delegator that is an affiliation's quorum rather than a
person, `community_id` on the edge, and the chain view CSD-055 marks `blocked_by:
CIRISServer#663`. The two should become one card when the chain read exists
(`COMMON_CARDS_ANALYSIS.md`: EXTEND CSD-055 §7); until then this document carries
the affiliation half so the Portal's org-admin acts have a home. The Portal's
org-admin role gate (`authorize_org_access`) is retired with the Portal; the
door is the edge.

**The enforced-admission rule is the card's whole contract.** CC 4.4.3.4.3: "an
issuance whose delegator does not itself hold the underlying authority is
refused at admission, not merely unweighted." The card therefore never lets a
delegator pick a scope it does not hold (TIGHTEN-ONLY, as CSD-055 already
implements for its scopes), and shows a refusal from the door as a refusal, by
its token, never as a success with a weaker row.

## 2. Surface (what)

```yaml
# csd:surface once the screen is declared in CIRISApp.kt
surface: proposed:authority-delegations
screen: proposed:AuthorityDelegations
```

Placement: Communities and Businesses › Rules (the affiliation's chain), and the
same card under My things for a person's own `license`/`grant` delegations
(a sole practitioner is their own authority).

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:delegated_scope
    use: emit
    type: "list[string]"
    example: ["license", "sub_delegation"]
    renders: "'May issue' — license (licences under this authority), grant (grants over assets this principal owns or stewards), sub_delegation (hand this on, one step deeper); each glossed in CC 4.4.3.4.3's words; TIGHTEN-ONLY against the delegator's own effective scope; `infra:*` scopes are never offered here"
    tag: "proposed:list_delegation_scope_*"
  - ceg: x_private:delegate_key_id
    use: emit
    type: string
    example: "wa-registrar-3e90"
    renders: "'To' — a member's key (must be on the roster for an affiliation's edge); never a node key, which the door refuses for any agency scope"
    tag: "proposed:input_delegate_key"
  - ceg: x_private:delegator
    use: display-only
    type: string
    example: "ciris-canonical (quorum:2/3 — A1, C1 signed)"
    renders: "'From' — the person, or the affiliation with the cosigners that met its protocol; a root edge from an affiliation reads as the quorum's act, never as one founder's"
    tag: "proposed:txt_delegation_from_*"
  - ceg: x_private:delegation_depth
    use: display-only
    type: int
    example: 3
    renders: "'3 of 5' — this edge's depth below the root; at 5 the card offers no sub_delegation, because the door will refuse it (CC 4.1.1)"
    tag: "proposed:txt_delegation_depth_*"
    assert:
      number: {"proposed:txt_delegation_depth_*": {min: 1, max: 5}}
  - ceg: x_private:delegation_chain
    use: display-only
    type: "list[string]"
    example: ["ciris-canonical", "wa-registrar-3e90"]
    renders: "'From the founders, through {admin}, to {registrar}' — the chain above this edge, each link's receipt openable; the effective scope is the INTERSECTION along it, shown as such"
    tag: "proposed:list_delegation_chain_*"
    assert:
      count: {of: "proposed:list_delegation_chain_*", max: 6}
  - ceg: x_private:effective_scope
    use: display-only
    type: "list[string]"
    example: ["license"]
    renders: "'Effective: license' — what the delegate can actually issue after attenuation along the chain, which may be less than this edge's own scope says"
    tag: "proposed:txt_delegation_effective_*"
  - ceg: x_private:delegation_valid_until
    use: emit
    type: timestamp
    example: "2027-01-26T00:00:00Z"
    renders: "'Until' — required, within the charter's role_term (ceiling 365 days from delegation_valid_from, which defaults to the edge's asserted_at); an expired edge confers nothing and the card says 'expired', never hides it"
    tag: "proposed:input_delegation_until"
  - ceg: x_private:revoke_cascade
    use: display-only
    type: "list[string]"
    example: ["wa-clerk-77a0 (license)", "3 licences issued under this edge stay issued"]
    renders: "in the revoke confirm: every edge below this one that ends with it, AND the statement that issuances already made under it stay (a licence is revoked by its authority, not by the delegate's fall — CC 2.4.1.2.1: authority is judged at the licence's asserted_at); a root edge from an affiliation is withdrawn only by a cosigned withdraws (persist v60)"
    tag: "proposed:sheet_delegation_revoke_cascade_*"
  - ceg: x_private:admission_refusal
    use: display-only
    type: "enum[licensure_delegator_not_authority,scope_not_held,depth_exceeded,cycle,not_on_roster,federation_delegation_term_required]"
    example: "federation_delegation_term_required"
    renders: "'The node refused this: term_missing.' — the door's own token, with persist's rule (`term_missing`, `term_unreadable`, `over_ceiling`, `over_role_term`), shown as a refusal"
    tag: "proposed:txt_delegation_refused"
```

**Issued things outlive the edge they were issued under.** Withdrawing a
`license`-scoped edge stops the delegate issuing; it does not un-issue the
licences already admitted (their authority revokes them, CSD-122). The cascade
sheet says both, because an admin who thinks revoking a registrar revokes the
registrar's licences will act on the wrong belief.

```yaml csd:states
populated: {tag: "proposed:list_authority_delegations"}
empty:     {tag: "proposed:txt_authority_delegations_empty", renders: "Nobody has been delegated the right to issue for this organization. Only the founders can issue."}
loading:   {renders: "the frame with a progress affordance and NO sentence"}
error:     {tag: "proposed:txt_authority_delegations_error", renders: "Couldn't read delegations from this node, so this list is not complete."}
```

## 3. Contracts (who)

| What | Route | Owner | State |
|---|---|---|---|
| write a `delegates_to` with scope ⊆ {license, grant, sub_delegation, …}, `community_id`, and a term, from a person or an affiliation's quorum (cosigned) | none for `license`/`grant` scopes today; `POST /v1/auth/device/delegate` keeps the first scope only and sets a 600 s term | CIRISServer | `blocked_by: CIRISServer#752` (the doors; CIRISServer#751 is the walk cap 4 → 5 and the durable scoped door) |
| read inbound and outbound edges with chain and depth | none (`GET /v1/auth/device/grants` is flat, outbound-only, no `valid_until`) | CIRISServer | `blocked_by: CIRISServer#663` |
| withdraw an edge; the read reflects the cascade | partial (device grants revoke) | CIRISServer | `blocked_by: CIRISServer#663` |
| admission enforces attenuation, depth 5, cycle refusal, `sub_delegation`, the term, and the `license`/`grant` underlying-authority rule | CIRISPersist | CIRISPersist | confirmed — persist v60 (#1031 cycle, #1032 term lens + `as_of`, #1033 `grant` arm, #1048 term gate); `CLM-delegation-term` staged on CIRISServer#752 / CIRISEdge#896 for the doors and carriage |
| the affiliation's quorum as the chain's root (a root edge cosigned under the protocol, naming the affiliation) | `admission::licence_authority_set` | CIRISPersist | confirmed — persist v60 (CIRISPersist#1036) |
| edge's default depth 4 → 5 | CIRISEdge | CIRISEdge | `blocked_by: CIRISEdge#851` |

## 3a. Topology (CSD/4)

T6 is the entry: `delegation.chain` ("a delegation chain of length ≥ 2 stands")
is its last promise, refused by the builder today because "`POST
/v1/auth/device/delegate` writes owner→actor only; a chain needs the delegate's
own act" (CSD4.md §5). T18 carries the two ending atoms. `requirements.yaml`
carries no CSD-124 yet; the block is the entry to add.

```yaml
# `csd:tests` upstream. Relabelled plain `yaml` by the vendoring: CIRISServer's requirements.yaml
# (pinned here at v0.5.233, and still at v0.5.245) carries no CSD-124, and a card may not claim tests
# the server does not carry (evidence/blocked_upstream.tsv). The label returns with the server's entry.
CSD-124:
  catalogue_version: 3
  topology: T6
  tests:
    chain:
      at: [delegation.chain, community.member]
    withdrawn:
      at: [delegation.link.withdrawn]
    expired:
      at: [delegation.link.expired]
```

**What the vocabulary lacks for this card.** `withdrawn` and `expired` hold only in
T18 (`delegation_withdraw` / `delegation_expire`, both `known_red: CIRISServer#751`);
the planner reselects T18 for them. `delegation.chain` says a chain of length ≥ 2
stands and nothing about its scopes, so a `license`-scoped chain from an
affiliation's quorum is indistinguishable from a device grant chain; the README's
asks list `delegation.scope.license`, `delegation.scope.grant` and
`delegation.refused` (with the door's token: `scope_not_held`, `depth_exceeded`,
`cycle`, `federation_delegation_term_required`) as the atoms, and `cannot_act` as
the negative. `formal/authority_tree/` already enumerates the chain the flow drives.

## 4. Flow

Unwritten at `envisioned`. The chain `formal/authority_tree/` enumerates: root →
A (license, sub) → B (license) → B tries to issue to C and is refused (no
sub_delegation) → A tries to hand `grant` on and is refused (not held) → A tries
an edge with no term and is refused `term_missing` → root withdraws A → B's edge
is dead, the depth read says so, B's earlier licence issuance stands.

## 5. Acceptance

Unsigned. Not tested here: the device-code offer/approve flow (CSD-055), moderation
duties (CSD-090), the accord's own conferral ceremony (CSD-069).
