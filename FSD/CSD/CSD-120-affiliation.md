# CSD-120 — Affiliation (an organization is a community gathered by necessity)

**CSD**: CSD-120 · **Standard**: CSD/3 (`CIRISClient/CSD.md`) + CSD/4 (`CIRISServer/FSD/CSD4.md`, catalogue entry T6, `csd:tests` in §3a) · **Origin**: the registry fold, FSD-005
**Flow**: unwritten
**Reads with**: CSD-102 (founding and governance of any community — this card is the affiliation-specific charter on top of it), CSD-121 (who is in it), CSD-124 (the hierarchy under it), `COMMON_CARDS_ANALYSIS.md` (recommends EXTEND CSD-102 §2.3 on adoption)
**Vendored from**: CIRISRegistry `FSD/CSD/CSD-120-affiliation.md`, branch `fold/org-as-affiliation`, commit `14fbcd0`. Changes here are mechanical only: this line, `registry_sha256` re-pinned from `d58715660…` (CC rc12) to this repo's vendored rc6 registry, and the `csd:surface` fence relabelled plain `yaml` because the checker refuses a `proposed:` screen (CSD-108's precedent). Also: the one `csd:shows` row for `community:listing` moved, unchanged, into the plain `yaml` block after it, because the pinned rc6 registry does not carry that family.

```yaml csd:stage
stage: envisioned
owner: CIRISRegistry
```

## 1. Mission (why)

**A person can found an organization under their own key, declare what kind of
institution it is and how it handles what it holds, see those declarations as
every member sees them on joining, nest it under a parent, and dissolve it — and
no administrator anywhere is asked.** Serves **Justice** and **Contextual
Integrity**, on CC 4.4.3.2.8: an affiliation gathers by necessity, shares the
community machinery, and adds institutional governance "through one declared
config record … visible to members on joining — there are no undeclared
institutional powers."

**An organization IS this card.** The Portal's `CreateOrganization` was a
`SYSTEM_ADMIN` act writing an `organization` row; FSD-005 §1 rules the
organization is the affiliation, and CC 3.3.9 says so in its own words: "The
Portal's `Organization` is a community at `cohort_scope: affiliations` and nothing
about an organisation is a record kind of its own."
Persist v60 (migration V205) dropped the `organization` table and retired the
replication kind; the Portal screen that wrote it is **retired, not migrated**
(portal.ciris.ai is decommissioned). Founding is
self-founding (CC 3.2: a community is born by its founders' signatures), and
`org_id` is the affiliation's `community_key_id`, which is also the
`authority_id` of every licence it issues (CSD-122).

**CIRIS is one such affiliation, and uses this card.** The root community
`ciris-canonical`, founded by the accord holders A1/B1/C1 (CC 3.2,
`CLM-canonical-founders-accord`), is the affiliation whose licences are
`licensure:ciris-canonical:v1` (CSD-122). It has a charter like any other; its
birth record rides the GenesisBundle (CC 5.3.4, served by this registry since
4.1.0) only because it is the shipped default root, never because it is a
different kind of thing.

**Mandatory-with-default, not mandatory-to-author.** CC 4.4.3.2.8 ships a no-op
default and archetype presets, so a five-person committee sets one field
(`affiliation_archetype: informal_adhoc`) and is served by construction. The card
must make the small case small: a founder who picks an archetype and nothing
else has a complete charter. Persist v60 types the record
(`AffiliationConfig` at `policy_blob.affiliation_config`, CIRISPersist#1034)
and validates `licence_audiences` at the community door
(`licence_audience_invalid`).

## 2. Surface (what)

```yaml
# csd:surface once the screen is declared in CIRISApp.kt
surface: proposed:affiliation-charter
screen: proposed:AffiliationCharter
```

Placement: Communities and Businesses › Rules, mounted on the hub beside
CSD-102's governance section (the community IS the circle's group; the charter
is the affiliation's addition; CSD-102's `affiliations_declared_record` row,
`blocked_by: CIRISServer#649`, is the placeholder this card fills). The founding
flow is CSD-102's founding flow with `cohort_scope: affiliations` and one more
step, the charter.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:community_key_id
    use: display-only
    type: string
    example: "ciris-canonical"
    renders: "the affiliation's id, shown as the id its licences will carry: 'Licences you issue are signed as licensure:ciris-canonical:v1'"
    tag: "proposed:txt_affiliation_id"
  - ceg: x_private:declared_cohort
    use: emit
    type: "enum[community,affiliations]"
    example: "affiliations"
    renders: "set at the found door and shown read-only after: 'This record is an affiliation' — the one member that names the kind (CC 4.4.3.2.8 'The record'); a key that wants both founds two records; the card never offers to flip it"
    tag: "proposed:txt_declared_cohort"
  - ceg: x_private:affiliation_archetype
    use: emit
    type: "enum[informal_adhoc,nonprofit_board,healthcare_provider,legal_practice,gov_body,igo,custom]"
    example: "healthcare_provider"
    renders: "'What kind of institution' — a preset that pre-fills everything below; `custom` opens the matrix"
    tag: "proposed:select_affiliation_archetype"
  - ceg: x_private:membership_basis
    use: emit
    type: "enum[voluntary-total,ascriptive,role-assigned,employment-contract]"
    example: "employment-contract"
    renders: "'Why people belong' — the necessity axis, orthogonal to the tier (the kibbutz catch)"
    tag: "proposed:select_membership_basis"
  - ceg: x_private:classification_scheme
    use: emit
    type: "list[string]"
    example: ["internal"]
    renders: "'Classes of what we hold' — default one class, `internal`; each class binds a crypto tier, retention, disclosure bar"
    tag: "proposed:list_classification"
  - ceg: x_private:retention_policy
    use: emit
    type: "list[string]"
    example: ["internal: rotate-forward"]
    renders: "'How long, per class' — floor and ceiling per class; the default rides the noise floor unchanged"
    tag: "proposed:list_retention"
  - ceg: x_private:disclosure_posture
    use: emit
    type: "enum[privacy-seeking,transparency-seeking]"
    example: "privacy-seeking"
    renders: "'Who can read what we publish' — whole cohort under the DEK, or per-class promotion to Commons"
    tag: "proposed:select_disclosure"
  - ceg: x_private:consensus_protocol
    use: emit
    type: string
    example: "quorum:2/3"
    renders: "'How this organization decides' — the full range of persist's quorum models, chosen at founding (CSD-102 offers it for any community) and changeable later by a decision of the active quorum; the same rule signs a licence, a role, a charter change. ciris-canonical decides by a strict majority of its three founders, which is this value"
    tag: "proposed:select_consensus_protocol"
  - ceg: x_private:role_term
    use: emit
    type: int
    example: 90
    renders: "'How long a role lasts, in days' — the charter's term for every role and issuance edge, integer days 1..=365 (CC 4.4.3.2.8 C, the constitutional ceiling); absent means the 365-day ceiling applies, and a supersession may lower it and never raise it (CC 4.4.3.2.8 'Charter defaults'); a small affiliation may choose ninety; renewal is a fresh conferral; an edge past it is refused `federation_delegation_term_required` (`over_role_term`)"
    tag: "proposed:select_role_term"
    assert:
      number: {"proposed:select_role_term": {min: 1, max: 365}}
  - ceg: x_private:licence_audiences
    use: emit
    type: "list[string]"
    example: ["practice: federation", "internal_credential: affiliations"]
    renders: "'The kinds of licence we issue, and who can check each' — the charter ENUMERATES the licence classes (CC 4.4.3.2.8 'Charter defaults') and, per class, the cohort_scope the licence and its authority head are placed at (`affiliation_config.licence_audiences`, persist v60); the smallest that fits (CC 1.13.3.4); no public default; a class not declared public cannot be issued at commons scope (`licence_audience_refused: class_not_declared_public`); a row in a class not listed here is admitted and honoured by no reader (`licence_class_not_trusted`), and the sheet says so before an issuer tries"
    tag: "proposed:list_licence_audiences"
  - ceg: x_private:parent_affiliation
    use: emit
    type: string
    example: "aff-ciris-l3c-9f2a"
    renders: "'Part of' — nests this affiliation under a parent (CC 4.4.3.2.8 C: hierarchy may nest sub-affiliations); replaces parent_org_id"
    tag: "proposed:input_parent_affiliation"
  - ceg: x_private:succession
    use: emit
    type: "enum[successor_seat,dissolves_at]"
    example: "successor_seat"
    renders: "'If the last deciding seat lapses' — a successor seat, or a wind-down date; with neither, the affiliation is dissolved the instant its last seat lapses (CC 4.4.3.2.8 'Succession and dissolution'), and the sheet says so"
    tag: "proposed:select_succession"
  - ceg: x_private:dissolves_at
    use: emit
    type: timestamp
    example: "2027-01-01T00:00:00Z"
    renders: "'Winds down on' — optional; a committee that ends with its event leaves no zombie cohort"
    tag: "proposed:input_dissolves_at"
  - ceg: x_private:dissolution_facts
    use: display-only
    type: "list[string]"
    example: ["seats lapse", "roles and grants end", "licences stand, read lapsed", "custody stands", "no merge, no split"]
    renders: "the dissolve confirm's facts (CC 4.4.3.2.8 'Succession and dissolution'): this is a `withdraws` of the record; every seat lapses at its effective_at; every role edge and grant ends with it; what the organization issued STANDS and reads `lapsed` from this instant (nobody is left to revoke); custody rows and the record itself stand; members keep what they can already read and may re-sign it into a new cohort; there is no merge and no split — a successor founds a new record"
    tag: "proposed:sheet_dissolve_facts"
  - ceg: x_private:public_face
    use: display-only
    type: "list[string]"
    example: ["exists", "ciris-canonical", "officers: A1 founder, B1 founder, C1 founder", "classes: practice"]
    renders: "'What strangers can see' — existence, the id, officers' roles, declared licence classes (CC 4.4.3.2.8 'Public face'): Commons-visible for every affiliation, with no privacy presumption; the roster beyond the officers reaches members only"
    tag: "proposed:txt_public_face"
  - ceg: x_private:listing_row
    use: emit
    type: bool
    example: true
    renders: "'Publish our listing' — for an affiliation that is not `cohort_subkind: public`: a federation-scope `community:listing:v1` row (reserved family, founder-signed; the founders' quorum signs it at the found door) naming the four public-face things and nothing else; a public affiliation needs none"
    tag: "proposed:btn_publish_listing"
  - ceg: x_private:charter_version
    use: display-only
    type: int
    example: 3
    renders: "'Charter version 3 — changed by {who} on {when}'; a change is a supersession of the record, never an edit"
    tag: "proposed:txt_charter_version"
  - ceg: "membership:{stage}"
    bind: {stage: acceptance}
    use: display-only
    type: int
    example: 12
    renders: "'12 members have accepted this charter' — a member sees the declarations on joining; their acceptance row is the evidence"
    tag: "proposed:txt_charter_accepted_by"
```

```yaml
# Held out of `csd:shows` by the vendoring, unchanged: `community:listing` is registered at CIRISConstitution
# HEAD and is not in this repo's pinned rc6 registry (client/ceg/namespace_registry.json), so the
# checker refuses it as an unregistered bare prefix. It moves back when that registry is re-pinned.
fields:
  - ceg: "community:listing"
    use: display-only
    type: string
    example: "community:listing:v1"
    renders: "'Listed since {when}' — the row strangers find the organization by; absent for a `public` affiliation, which is the listing"
    tag: "proposed:txt_listing_row"
```

**What CC 4.4.3.2.8 says for this card.** The charter's limbs are the affiliation's
and a social community declares none of them ("The assumption each tier carries",
"One member is enough", C); `dissolves_at` is the affiliation's wind-down
("Lifecycle"); the scope is a `cohort_scope` value sharing the community crypto tier
and feed band (CC 2.1; CC 4.4.3.2.1; CC 4.4.3.3). **The record** declares its kind in
one member, `declared_cohort` (`community` or `affiliations`), one record is one kind,
a key that wants both founds two, and the config record's schema rides the family
model manifest as data ("The record"); **charter defaults**: an absent `role_term`
means the 365-day ceiling, a charter may lower it and never raise it; the charter
enumerates the licence classes it issues, and a row in an undeclared class is
admitted and honoured by no reader ("Charter defaults"); **public face**: existence,
`community_key_id`, officers' roles and declared licence classes are
Commons-visible, `cohort_subkind: public` is admissible, and a non-public affiliation
carries a federation-scope `community:listing:v1` row naming those four things and
nothing else ("Public face"); **succession and dissolution**: the charter names a
successor seat or `dissolves_at`; dissolution is the record's `withdraws`; seats
lapse at its `effective_at`, hierarchy edges and grants end with it, custody rows and
the record stand, content stays readable to its key holders and may be re-signed
into a new cohort, an issued licence stands and reads `lapsed`; there is no merge and
no split ("Succession and dissolution"); **the scopes**: `affiliations` sits beside
`community`, neither wider nor narrower ("The scopes"). Register rows
`CLM-affiliation-public-face` and `CLM-affiliation-dissolution` are staged on
CIRISPersist#1143.

**One member is enough (CC 4.4.3.2.8).** "An affiliation may have one member — a
sole practitioner — and holds, processes and licenses under this record's limbs from
its creation; the limbs attach to the record and its subjects, never to a head-count.
Members of an affiliation hold no governance vote unless its charter grants one; the
deciding seats are its officers under its `consensus_protocol`." So founding a
one-person affiliation is allowed and the card offers it: a sole founder with
`founder_only` has a complete charter and may issue licences at once (CC 4.5.12.5:
`founder_only` is "admissible for an affiliation only under the one-voice countersign
rule of CC 4.4.3.2.8"). What a one-voice body may not do alone is act against a member
(CSD-121). The worked world is CC `formal/affiliation/Affiliation.lean`
(`a1_data_regime_at_any_size`, `solo_is_bound_by_the_limbs`, `solo_hires`).

**Every emitted field is a member of one signed record.** The charter is the
affiliation's config record, superseded as a whole (CC 4.4.3.2.8: "DECLARED at
creation"). A card that saved fields one at a time would produce N versions for
one edit and N receipts for one act. A roster or config change is cosigned under
the protocol; a delegated holder never stands in for it (CC 3.3.9).

**Nothing here costs money.** The Portal charged an issuance fee and a bond per
key by tier; that schedule went with the Portal. Founding an affiliation is a
signed row, and the only Sybil cost is the one the Constitution already states
for any community: a quorum of accountable people (CC 3.2 T7).

```yaml csd:states
populated: {tag: "proposed:card_affiliation_charter"}
empty:     {tag: "proposed:txt_charter_none", renders: "This organization has declared nothing beyond the default — one class, rotate-forward, privacy-seeking. That is a complete charter."}
loading:   {renders: "the frame with a progress affordance and NO sentence"}
error:     {tag: "proposed:txt_charter_error", renders: "Couldn't read this organization's charter from this node. This is not a report that it has none."}
```

## 3. Contracts (who)

| What | Route | Owner | State |
|---|---|---|---|
| found an affiliation | `POST /v1/communities` with `tier: affiliations` | CIRISServer | confirmed as a tier label (0.5.225: invitations ride `GroupScope::Affiliations`); the charter step is below |
| write / supersede the charter (typed config record) | none today | CIRISServer | `blocked_by: CIRISServer#649` — the storage side is persist v60 (CIRISPersist#1034: `AffiliationConfig`, declared cohort, V089 CHECK fix); the door is CIRISServer#752 |
| read the charter | none today | CIRISServer | `blocked_by: CIRISServer#649` |
| nest under a parent | charter member `hierarchy` | CIRISPersist | confirmed — typed in `AffiliationConfig` (v60) |
| dissolve (the record's `withdraws`; seats lapse, edges and grants end, custody and licences stand, licences read `lapsed`) | CSD-102's `DELETE /v1/communities/{id}` for the door | CIRISServer; CIRISPersist for the semantics | door confirmed by CSD-102 §3; semantics `blocked_by: CIRISPersist#1143` (`CLM-affiliation-dissolution`) |
| `declared_cohort` set at the found door; one record is one kind | `POST /v1/communities` | CIRISServer; persist `declared_cohort` (v60, #1034) | `blocked_by: CIRISServer#752` (the door takes `tier`, not the record member) |
| the public face: the four Commons-visible things; `cohort_subkind: public`; the `community:listing:v1` row for a non-public affiliation | none | CIRISServer; CIRISPersist | `blocked_by: CIRISPersist#1143` (`CLM-affiliation-public-face`) |
| succession: a successor seat or `dissolves_at`; dissolution at the last seat's lapse with neither | charter member | CIRISPersist | `blocked_by: CIRISPersist#1143` |
| `licence_audiences` validated at the community door | `licence_audience_invalid` | CIRISPersist | confirmed — persist v60 |

## 3a. Topology (CSD/4)

The catalogue entry this card tests on is **T6** (`CIRISServer/FSD/CSD4.md` §2:
"an affiliation, a licensing authority, a delegation chain", `serves: CSD-120–126`).
The block is the card's entry in the server's `requirements.yaml`, which it already
carries for CSD-120; `check_csd4` compares the two. `catalogue_version` is the
client's pin (`packaging/csd4/PIN.md`, 3); the server's catalogue is at 5, and T6
differs between 3 and 5 only in its own `catalogue_version` field.

```yaml csd:tests
CSD-120:
  catalogue_version: 3
  topology: T6
  tests:
    affiliation:
      at: [community.formed, community.member]
    licensing:
      at: [authority.licensing, community.member]
    chain:
      at: [delegation.chain]
```

**What the vocabulary lacks for this card.** T6 builds the affiliation with a lone
founder (`founder_only`), so `community.formed` at the affiliations tier is reachable
today; `authority.licensing` is past the `authority` relation the builder refuses
("a licensing authority affiliation has no route", CSD4.md §5). The charter itself is
a transition the flow writes (no config record, then version 1, then version 2 after
one supersession) and no atom names it: the README's asks list `affiliation.charter.declared`
and `affiliation.charter.superseded` as the two atoms to add, and a cosigned
supersession under `quorum:2/3` needs T5's `quorum_change` + `cosign` at the
affiliations tier, which no entry builds.

## 4. Flow

Unwritten at `envisioned`. The steps a flow will drive: found with an archetype →
read back the charter → supersede one field → the version increments and the
previous version is still readable → a second person joins and the acceptance row
names the charter version they accepted.

## 5. Acceptance

Unsigned. What this card will not test: the DEK cascade and forward secrecy on
removal (CSD-102's), lawful-access declarations (CC 4.4.3.2.8 D — CSD-130, never
covert, always `hard_case:*`). The public face is this card's (the four things are
members of the record, CC 4.4.3.2.8 "Public face"; `COMMON_CARDS_ANALYSIS.md`).
