# CSD-123 — Partners (recognition, role, forums: the half of a partner that is not a licence)

**CSD**: CSD-123 · **Standard**: CSD/3 (`CIRISClient/CSD.md`) + CSD/4 (`CIRISServer/FSD/CSD4.md`, catalogue entry T6, `csd:tests` in §3a) · **Origin**: the registry fold, FSD-004 §4.2 / FSD-005
**Flow**: unwritten
**Reads with**: CSD-122 (the licence half), CSD-120 (both sides are affiliations), CSD-124 (what the partner may do on the org's behalf is a delegation, never a partner field), `COMMON_CARDS_ANALYSIS.md` (KEEP; CSD-043 §2's placeholder bind of `partner_role:{role}` points here)
**Vendored from**: CIRISRegistry `FSD/CSD/CSD-123-partners.md`, branch `fold/org-as-affiliation`, commit `14fbcd0`. Changes here are mechanical only: this line, `registry_sha256` re-pinned from `d58715660…` (CC rc12) to this repo's vendored rc6 registry, and the `csd:surface` fence relabelled plain `yaml` because the checker refuses a `proposed:` screen (CSD-108's precedent). Also: the `csd:tests` fence relabelled plain `yaml` until CIRISServer's `requirements.yaml` carries this card (`evidence/blocked_upstream.tsv`).

```yaml csd:stage
stage: envisioned
owner: CIRISRegistry
```

## 1. Mission (why)

**An organization can recognise another organization or key as a partner in a
named role, state the forums they share, see every partner it recognises and
every organization that recognises it, and end a recognition — and a reader can
tell recognition apart from licence.** Serves **Fidelity**: CC 2.4.1.2.1 keeps
"who grants the licence", "who vouches for the attester" and "what external force
it carries" apart, and FSD-004 §4.2 split `RegisterPartner` into a recognition
half and a licence half for the same reason.

**A partner is three families, none of them `partner_record`.** The Portal's
`partner_record` bundled recognition (`partner_id`, `org_id`), standing
(`license_type`, `status`), capability (`capabilities_granted/denied`,
`max_autonomy_tier`) and obligation (`requires_supervisor`, limits) in one
quorum-signed row. On CEG those are: the role, `partner_role:{role}:v1`; the
standing, a licence (CSD-122), which now carries the typed payload persist v60
ported from `PartnerRecord` (capabilities, limits, `revision`); the obligations,
`duty:{kind}:v1` on the licence; the capabilities, the `scope` of a
`delegates_to` where they are agency (CSD-124) and the licence where they are
standing; forum participation, `multilateral_participation:{forum}:{kind}:v1`.
The `partner_record` subject kind is **retired**, by amendment (CC 3.3.9) and in
persist v60 (V205, `federation_envelope_kind_retired`).
The monotonic `revision` the record carried lives on in the licence payload.

**A role is not a price.** The Portal's six
`license_type`s — community, community_plus, professional_medical,
professional_legal, professional_financial, professional_full — were tiers with a
fee, a bond and a monthly charge, and `UpgradeToPartner` was the sale. CIRIS sells
no licences and the Portal is decommissioned; the tiers, the
prices and `GetPartnerActivity`'s "health assessment" are **retired, not
migrated**. `partner_role:{role}` is open vocabulary (`{role}` is a `vocab`
segment) naming what the partner IS to the recogniser — `supervising_physician`,
`vendor`, `member_practice`, `auditor` — never what it paid.

**A partner can be found (CC 4.4.3.2.8 "Public face").** Every affiliation's
existence, `community_key_id`, officers' roles and declared licence classes are
Commons-visible; a non-public one carries a `community:listing:v1` row naming those
four things (CSD-120). So the partner picker below resolves an id or a code against
that public face and shows the four things before the recognising act; a social
community may be recognised and may itself license ("Who may license"), the partner's
`declared_cohort` being shown, never a gate.

**"Anyone can define their own partners."** Recognition is a `scores` row by the
recognising affiliation about the partner's key, cosigned under its own
`consensus_protocol`. It needs no steward quorum outside the affiliation's own,
and no registry. Whether a reader weights it is consumer policy (CC 4.4.4).

## 2. Surface (what)

```yaml
# csd:surface once the screen is declared in CIRISApp.kt
surface: proposed:partners
screen: proposed:Partners
```

Placement: Communities and Businesses › People (partners are the organizations
this one works with) with the issuing controls behind the affiliation's role
gate.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: "partner_role:{role}"
    bind: {role: supervising_physician}
    use: emit
    type: string
    example: "supervising_physician"
    renders: "one row per partner: '{name} — supervising physician, recognised {when}'; open vocabulary, the recogniser's own words; no tier, no price"
    tag: "proposed:row_partner_*"
  - ceg: x_private:partner_key_id
    use: emit
    type: string
    example: "aff-mercy-clinic-41d0"
    renders: "'Partner' — the other affiliation's id or a key; added by code or id, never by email"
    tag: "proposed:input_partner_key"
  - ceg: x_private:recognised_by
    use: display-only
    type: string
    example: "aff-ciris-l3c-9f2a"
    renders: "'Recognised by' — the row's attester, so an inbound recognition reads as THEIR claim about us"
    tag: "proposed:txt_partner_recognised_by_*"
  - ceg: "multilateral_participation:{forum}:{kind}"
    bind: {forum: ciris-medical-compact, kind: voting}
    use: emit
    type: "list[string]"
    example: ["ciris-medical-compact:voting"]
    renders: "'Forums' — membership / voting / proposal_filing / observer_status per named body"
    tag: "proposed:list_partner_forums_*"
  - ceg: x_private:licence_summary
    use: display-only
    type: "list[string]"
    example: ["issued"]
    renders: "'Licence: issued' — CSD-122's status set for this partner under THIS authority, opened from the row; absent when no licence is held, which is not an error"
    tag: "proposed:txt_partner_licence_*"
  - ceg: x_private:recognition_direction
    use: display-only
    type: "enum[outbound,inbound,mutual]"
    example: "mutual"
    renders: "'Mutual' when both affiliations recognise each other; CC 2.3.2.4: bilateral ratification is consumer policy, so this is a display fold, not a wire state"
    tag: "proposed:chip_partner_direction_*"
  - ceg: "revocation:{entity_type}:{reason}"
    bind: {entity_type: partner, reason: ended}
    use: emit
    type: string
    example: "revocation:partner:ended:v1"
    renders: "'End recognition' — a withdraws on the recognition row, plus this row when the reason is to be stated; forward-only, never a resurrect"
    tag: "proposed:btn_partner_end_*"
```

```yaml csd:states
populated: {tag: "proposed:list_partners"}
empty:     {tag: "proposed:txt_partners_empty", renders: "No partners recognised yet, and no organization has recognised this one that this node knows of."}
loading:   {renders: "the frame with a progress affordance and NO sentence"}
error:     {tag: "proposed:txt_partners_error", renders: "Couldn't read partners from this node, so this list is not complete."}
```

## 3. Contracts (who)

| What | Route | Owner | State |
|---|---|---|---|
| recognise (write `partner_role:{role}:v1` about the partner, cosigned by the affiliation) | none today | CIRISServer | `blocked_by: CIRISServer#752` |
| forums (`multilateral_participation:*` rows) | none | CIRISServer | `blocked_by: CIRISServer#752` |
| read outbound and inbound recognitions | none (FSD-004 proposed reads over `partner_record`, now retired) | CIRISServer | `blocked_by: CIRISServer#752` |
| end (withdraws + optional `revocation:partner:*`) | none | CIRISServer | `blocked_by: CIRISServer#752` |
| the `partner_record` subject kind retired | CIRISPersist V205 | CIRISPersist | confirmed — persist v60; CC 3.3.9: "The three kinds below are retired by amendment (CC 4.5.1): their names stay in this section so citations resolve, no substrate admits them as record kinds" |

## 3a. Topology (CSD/4)

T6 is the entry. `requirements.yaml` carries no CSD-123 yet; the block is the
entry to add.

```yaml
# `csd:tests` upstream. Relabelled plain `yaml` by the vendoring: CIRISServer's requirements.yaml
# (pinned here at v0.5.233, and still at v0.5.245) carries no CSD-123, and a card may not claim tests
# the server does not carry (evidence/blocked_upstream.tsv). The label returns with the server's entry.
CSD-123:
  catalogue_version: 3
  topology: T6
  tests:
    recognised:
      at: [community.formed, community.member]
    licence_beside_it:
      at: [authority.licensing, community.member]
```

**What the vocabulary lacks for this card.** A recognition is a scores row by one
affiliation about another, and T6 builds one community, so a second affiliation to
recognise does not exist in any entry; no atom names a recognition row or its
direction. The README's asks list `recognition.outbound`, `recognition.inbound` and
`recognition.mutual` (a display fold, so the last may be a derived atom) and a second
`community` relation at the affiliations tier on T6.

## 4. Flow

Unwritten at `envisioned`. The chain: A recognises B in a role → B's card shows
an inbound recognition → B recognises A → both read `mutual` → A issues B a
licence (CSD-122) and B's row shows it → A ends the recognition and the licence
is untouched, because they are two things.

## 5. Acceptance

Unsigned. Not tested here: the licence itself (CSD-122), capability intersection
for a deployment (`VerifyDeployment`, FSD-004 §6.3, still open).
