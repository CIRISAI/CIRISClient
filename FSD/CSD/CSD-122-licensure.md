# CSD-122 — Licensure (any authority, under its own key, says who may practise)

**CSD**: CSD-122 · **Standard**: CSD/3 (`CIRISClient/CSD.md`) + CSD/4 (`CIRISServer/FSD/CSD4.md`, catalogue entry T6, `csd:tests` in §3a) · **Origin**: the registry fold, FSD-005; CIRISRegistry#139
**Flow**: unwritten
**Reads with**: CSD-120 (the authority is an affiliation), CSD-124 (a `license`-scoped delegate issues on its behalf; the worked chain to `ciris-canonical` is there), CSD-123 (the recognition that sits beside a licence), CSD-052 (the attestation ladder — not this), `COMMON_CARDS_ANALYSIS.md` (KEEP)
**Vendored from**: CIRISRegistry `FSD/CSD/CSD-122-licensure.md`, branch `fold/org-as-affiliation`, commit `14fbcd0`. Changes here are mechanical only: this line, `registry_sha256` re-pinned from `d58715660…` (CC rc12) to this repo's vendored rc6 registry, and the `csd:surface` fence relabelled plain `yaml` because the checker refuses a `proposed:` screen (CSD-108's precedent). Also: the `csd:tests` fence relabelled plain `yaml` until CIRISServer's `requirements.yaml` carries this card (`evidence/blocked_upstream.tsv`).

```yaml csd:stage
stage: envisioned
owner: CIRISRegistry
```

## 1. Mission (why)

**An authority — an organization or a single key — can issue a licence to a
subject, put it on probation or restrict it, suspend and reinstate it, revoke it,
record that it lapsed, and attach the duties that come with it; a subject can
see every licence held under every authority with every live status; and a
reader anywhere can look a key up and get the status set, never a blended
scalar.** Serves **Fidelity** and **Justice**, on CC 2.4.1.2.1 and 3.1.1:
`licensure:{authority_id}` is open-emitter — "anyone may be an authority under
their own key" — and `suspended` is reversible while `revoked` is terminal, "a
status a consumer MUST NOT infer from the other."

**Nobody sells a licence, and CIRIS least of all.** The Portal issued CIRIS
licences for a fee by tier (Community, Professional, Enterprise, Safety-Critical)
through Stripe; CIRIS sells no licences and portal.ciris.ai is decommissioned. The
Portal screens this card stood in for — `RegisterPartner`'s licence half, `RevokeEntity(license)`,
`ListExpiringLicenses` — are **retired, not migrated**. The model is fully
decentralised: anyone can be their own licensor; it only matters who trusts
them. A reader chooses how it trusts through its own **Who I trust** set
(CC 4.4.3.8 "Who I trust"): the shipped root is one default,
removable entry in that set, which is how the accord's own licences work out of
the box and why the root is never the only trust knob.

**A CIRIS licence is a row under the root community (CC 2.4.1.2.1, 3.3.9,
4.4.3.4.3).** The CIRIS L3C licensing authority
is the humanity accord trust root acting through the root community it founded,
`ciris-canonical`, whose founders are the accord holders A1/B1/C1 (CC 3.2,
`CLM-canonical-founders-accord`). So the dimension is
**`licensure:ciris-canonical:v1`** — the root community's `community_key_id`,
NOT `licensure:humanity-accord:v1`: the accord family is a witnessed lineage and
a kill switch, not a licensor. The row is a **cosigned row** admitted under the
cosigned-row rule (CC 2.1: "the family declares its `consensus_protocol`
source … for `licensure:{community_key_id}` the authority named in the
dimension") at its signed `asserted_at`, cosigned by that community's quorum
(`quorum:2/3`, a strict majority of three founders) **or** signed by a key
holding a plain `license`-scoped `delegates_to` from `ciris-canonical`, itself
cosigned under that quorum (CC 2.4.1.2.1, 3.3.9, 4.4.3.4.3). **There is no
`infra:license` and there must be none**: `license` is an agency scope in the
CC 4.4.3.4.3 table, and a node key (identity_type ⊇ node) may carry only
`infra:*` scopes, so a licence can never be issued by an install. The `license`
delegation is an ordinary cosigned row the reader walks, never a member of the
GenesisBundle (CC 3.2 T5, 5.3.4: the bundle carries the charter, the holders,
the serve nodes and the birth record, and nothing else is a root).

**Any affiliation is its own licensor, on the same rule.** A state board, a
clinic, a guild issues `licensure:{its community_key_id}:v1` cosigned under its
own `consensus_protocol` (CC 2.4.1.2.1: "Anyone"). **A community may
license too** (CC 4.4.3.2.8 "Who may license"): "Anyone may be an authority under
their own key, a community included; the licence classes an authority issues are its
charter's declaration, and a community whose charter declares a class with legal
weight has taken on the affiliation's duties for it — the data limbs of this record
bind the issuer of such a licence whatever its `declared_cohort` says." So the issuing
side of this card is offered to every community, and a class with legal weight brings
CSD-120's limbs (and CSD-132's records duties) with it. A Sovereign key scoring
`licensure:CA_medical_board` is wire-identical to a steward scoring it
(CC 4.4.4).

**The reader half: a licence is honoured iff the reader's own Who-I-trust walk
reaches its authority for its class (CC 4.4.3.8 "Who I trust").** The
reader's trust set is its OWN `delegates_to(reader → authority)` rows bearing
`trust:accepts:v1`, which it signs, adds and removes freely; the shipped root is
one such entry, written at first run. Each entry names the authority **as a
licensor** (every class its charter declares) or **for a class set** (medical,
legal, financial: the classes listed on the edge and no other), and **how far**
(`trust_depth`: `0` honours that authority's own licences; `n` honours, within
the five-hop cap, the licensors that authority itself trusts for the class, `n`
more hops — licensors of licensors, which is a walk over the authorities' own
acceptance edges, NOT a `license`-scoped delegation chain, which only conveys
issuing under one authority). The reader honours a `licensure:{authority_id}:v1`
row iff its breadth-first walk from its own edges reaches the issuing authority
for the row's class and that authority's charter declares the class; at each hop
the depth is the lesser and the classes the intersection, so the walk only
narrows. A licence confers no trust; removing an entry removes exactly what
reached through it; an empty set honours nothing. The proofs are CC
`formal/trust_set/TrustSet.lean`: `no_trust_without_my_edge`,
`empty_set_honours_nothing`, `licence_confers_no_trust`, `removal_only_removes`,
`reaches_mono_budget`. A licence that fails the walk is **read, not refused**:
`licence_issuer_not_trusted` or `licence_class_not_trusted`, and the card says
which. The wire members `trust_classes` and `trust_depth` ride inside the reader's
own signature on the edge (CIRISPersist#1142: refusals
`trust_accepts_classes_malformed`, `trust_accepts_depth_out_of_range`). The
reader's surface for the set is the Who-I-trust extension of CSD-105
(`COMMON_CARDS_ANALYSIS.md`); this card only shows the verdict and which entry
carried it.

**A licence does not chain.** Holding one confers no power to issue one. The only
way a key other than the authority issues `licensure:A` is a `license`-scoped
delegation from A (CSD-124), refused at admission otherwise
(`licensure_delegator_not_authority`). The card never offers "delegate this
licence"; it offers "delegate the right to issue".

**The authority of an organization is its affiliation id** (CC 3.3.9:
"a partner is `licensure:{community_key_id}:v1` with `partner_role:{role}` for
the tier and `duty:{kind}` for obligations"). The Portal's `license_type` enum
is gone from the wire: the practised domain is the authority, the obligations are
`duty:{kind}`, and the typed licence payload persist v60 carries
(`partner_record` as an envelope member on the `licensure:` row, with the
`revision` anti-rollback against the authority's own held licences) replaces the
Portal's `PartnerRecord`.

## 2. Surface (what)

```yaml
# csd:surface once the screen is declared in CIRISApp.kt
surface: proposed:licensure
screen: proposed:Licensure
```

Placement: Communities and Businesses › Rules for the issuing side (an
affiliation's licences), and My things › Devices & keys for the holding side (my
licences). One screen, two scopes.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: "licensure:{authority_id}"
    bind: {authority_id: ciris-canonical}
    use: emit
    type: "enum[issued,probation,restricted,suspended,revoked,lapsed,surrendered,reduced]"
    example: "issued"
    renders: "one row per (subject, authority, status): 'Issued by ciris-canonical on {when}, until {valid_until}'; several statuses may be live at once and each is its own row; the bind is the worked example (a CIRIS licence), any affiliation's id goes in its place"
    tag: "proposed:row_licence_*"
    assert:
      one_of: {"proposed:row_licence_*": [issued, probation, restricted, suspended, revoked, lapsed, surrendered, reduced]}
  - ceg: x_private:licence_status_set
    use: display-only
    type: "list[string]"
    example: ["issued", "probation"]
    renders: "'Standing: issued, on probation' — the SET, composed (persist `licensure::status_set_for`); never 'active' as a scalar; `revoked` present ⇒ terminal, shown first"
    tag: "proposed:txt_licence_standing_*"
  - ceg: x_private:authority_id
    use: display-only
    type: string
    example: "ciris-canonical"
    renders: "'Authority' — the affiliation (named) or the key; the licence's provenance (CC 2.4.1.2.1); for ciris-canonical the card says 'the root community, founded by the accord holders' and nothing grander"
    tag: "proposed:txt_licence_authority_*"
  - ceg: x_private:cosigned_under
    use: display-only
    type: string
    example: "quorum:2/3 — A1, B1 signed"
    renders: "'Signed by' — the scrub set that met the authority's protocol at asserted_at (persist v60: the full scrub set ∩ the roster at the instant, evaluated under the community's own protocol); one member's signature alone reads 'testimony, not a licence'"
    tag: "proposed:txt_licence_cosigned_*"
  - ceg: x_private:issued_under_delegation
    use: display-only
    type: string
    example: "att-7f3a…"
    renders: "'Issued by {key}, under a licence-scoped delegation from {authority}' when the issuer is not the authority; the delegation's receipt opens (CSD-124)"
    tag: "proposed:txt_licence_issued_under_*"
  - ceg: x_private:asserted_at
    use: display-only
    type: timestamp
    example: "2026-10-28T09:00:00Z"
    renders: "'As of' — authority is judged at the row's signed asserted_at (CC 2.4.1.2.1 'Authority is judged at issuance'; persist v60); a later withdrawal of the issuer's delegation stops future issuance and does not reclassify this row; a row signed more than 72 h ago whose standing no longer holds at receipt is `asserted_at_stale`, shown as refused"
    tag: "proposed:txt_licence_as_of_*"
  - ceg: "duty:{kind}"
    bind: {kind: inform}
    use: emit
    type: "list[string]"
    example: ["inform", "review_policy"]
    renders: "'Comes with' — the duties attached (ODRL kinds: compensate, attribute, inform, obtain_consent, ensure_exclusivity, delete, next_policy, anonymize, review_policy); supervision-required becomes `duty:review_policy` with the supervisor named"
    tag: "proposed:list_licence_duties_*"
  - ceg: x_private:valid_until
    use: emit
    type: timestamp
    example: "2027-10-01T00:00:00Z"
    renders: "'Until' on the row; past it, the row is not live (CC 2.1) and the card says 'expired' — NOT `lapsed`, which only the authority can say"
    tag: "proposed:txt_licence_until_*"
  - ceg: x_private:reinstate
    use: emit
    type: bool
    example: true
    renders: "'Reinstate' on a suspended row — a cosigned `withdraws` on the suspended row, never a new `issued`; absent on a revoked row"
    tag: "proposed:btn_licence_reinstate_*"
  - ceg: x_private:licence_class
    use: display-only
    type: string
    example: "medical"
    renders: "'Class: medical' — the class the row carries; honoured only if the authority's charter declares it (TrustSet.lean `undeclared_class_never_honoured`; CC 4.4.3.2.8 'Charter defaults': the charter enumerates the classes it issues, and a row in an undeclared class is admitted as a row and honoured by no reader, `licence_class_not_trusted`; the charter is read at read, so a class a later amendment drops stops matching)"
    tag: "proposed:txt_licence_class_*"
  - ceg: "trust:{job}:{version}"
    bind: {job: accepts, version: v1}
    use: display-only
    type: "enum[honoured,licence_issuer_not_trusted,licence_class_not_trusted]"
    example: "honoured"
    renders: "'Honoured — through your entry for {authority} (medical, depth 0)' / 'through {authority} → {licensor} (2 hops)' / 'Not honoured: you do not trust this issuer' / 'Not honoured: you trust {authority} for legal, not medical' — the reader's own walk, never the node's opinion; the entry named opens the Who-I-trust list (CSD-105 extension). The family is reserved (CC 3.1.1) and this card only reads it"
    tag: "proposed:chip_licence_trust_*"
    assert:
      one_of: {"proposed:chip_licence_trust_*": [honoured, licence_issuer_not_trusted, licence_class_not_trusted]}
  - ceg: x_private:licence_audience
    use: display-only
    type: "enum[federation,affiliations,named_readers]"
    example: "federation"
    renders: "'Who can verify this' — the cohort_scope the licence's CLASS declares in the charter (`licence_audiences`, CSD-120), the smallest that fits (CC 1.13.3.4); a practice licence meant for strangers is federation-scope by its nature, an internal credential is not. There is no public default. The authority head (roster, protocol, `license` edges) travels on its own planes at a scope a verifier can reach"
    tag: "proposed:txt_licence_visibility"
  - ceg: x_private:re_emitted_to
    use: emit
    type: "list[string]"
    example: ["wa-state-board-verifier-3c0e"]
    renders: "'Readers' — present only for a class not declared public; the re-emission is a NEW cosigned row (`reemission_of` = the held licence's id) at the same scope with the readers in subject_key_ids, payload and status unchanged (CC 2.4.1.2.1; persist v60), never a carriage of the public row; refusals shown by name: `re_emission_names_no_reader`, `re_emission_target_unknown`, `re_emission_diverges`"
    tag: "proposed:list_licence_readers"
  - ceg: x_private:subject_view
    use: display-only
    type: "list[string]"
    example: ["ciris-canonical: issued", "aff-state-board-77c1: suspended"]
    renders: "'Licences about you' — every licence row this node holds whose subject is me, by authority, with its status set (CC 4.5.5 'Subject standing': a subject can see every licence about them); a claim about a person they cannot see fails ciris.ai/values (informed agency)"
    tag: "proposed:list_my_licences"
  - ceg: x_private:appeal
    use: emit
    type: string
    example: "reconsideration:suspension_unfounded"
    renders: "'Contest' on a row about me — files a `reconsideration:{grounds}` (CC 3.1.9.2; CC 2.4.1.1; CC 4.5.5) addressed to the authority; a subject's own `withdraws` against a licence is refused (persist v60 `SUBJECT_WITHDRAWS_CARVE_OUT`); the row stays as it is until the authority's set, plus any `review`-scoped chain from it, acts"
    tag: "proposed:btn_licence_contest_*"
  - ceg: "attestation:license_validity"
    use: display-only
    type: "enum[verified,single_source,unknown]"
    example: "single_source"
    renders: "'Verify has not co-signed this licence (confidence ≤ 0.5)' — CC 3.4.9 composition for a licence under the default root only; absent for every other authority"
    tag: "proposed:chip_licence_cosigned_*"
```

**No card ranks authorities.** A reader decides which authorities to trust (CC 4.4.4;
CC 4.4.3.8; ciris.ai/constitutional-mesh: "every client chooses which roots to
trust"). The card lists what is held and who said it; it never sorts a state board
above a sole practitioner, and never sorts `ciris-canonical` above either. The root
is not the only trust knob: a reader that removes it keeps every licensor entry it
added, and the licences that reached through those entries stay honoured
(`removal_only_removes`); what it loses is federation at the server tier until it
attaches another root (CC 4.4.3.8 items 4–5: licensor trust is not root trust).
What of an affiliation is Commons-visible (CC 4.4.3.2.8 "Public face"):
its existence, its `community_key_id`, its officers' roles and the licence classes its
charter declares; "a licence keeps no public default, being a claim about a person
scoped per class in the charter". So a verifier can always find the authority and learn
which classes it issues, and never learns who holds a licence except at the class's
declared scope (CSD-120 carries the public face).

**`lapsed` and `expired` are different words on purpose.** A row whose
`valid_until` has passed is no longer live; `lapsed` is the authority saying so
on the record — or the record saying so: when the authority is dissolved (the
`withdraws` of its record, CC 4.4.3.2.8 "Succession and dissolution") "an issued licence
stands, authority having been judged at issuance, and reads `lapsed` from the
dissolution instant for every later use, nobody being left to suspend or revoke it;
a reader's trust walk no longer reaches a dissolved authority." The card renders
that `lapsed` with 'the authority was dissolved on {when}' and never fabricates a
`lapsed` or a `revoked` from a date alone (FSD-004 §4.3).

**Nothing on this card has a price.** No fee, no bond, no tier, no "contact
sales"; a licence class is a line in the charter, and whether anyone honours it
is the reader's trust.

```yaml csd:states
populated: {tag: "proposed:list_licences"}
empty:     {tag: "proposed:txt_licences_empty", renders: "No licences issued by this organization yet. / You hold no licences this node knows of."}
loading:   {renders: "the frame with a progress affordance and NO sentence"}
error:     {tag: "proposed:txt_licences_error", renders: "Couldn't read licences from this node. This is not a report that none are held."}
```

## 3. Contracts (who)

| What | Route | Owner | State |
|---|---|---|---|
| issue / change status (write a `licensure:{authority_id}:v1` scores row about the subject) as an act of the affiliation under its current `consensus_protocol` — the `/changes/{envelope,cosign,assemble}` pattern; one signer only where the protocol says so | none today | CIRISServer | `blocked_by: CIRISServer#752` (the doors) |
| re-emit a licence to named readers (`reemission_of`, `subject_key_ids`) | none | CIRISServer | `blocked_by: CIRISServer#752`; admission confirmed (persist v60); carriage `blocked_by: CIRISEdge#895` (`CLM-licence-audience`) |
| reinstate (a cosigned `withdraws` on the suspended row) | none | CIRISServer | `blocked_by: CIRISServer#752` |
| attach a duty (`duty:{kind}:v1` row referencing the licence) | none | CIRISServer | `blocked_by: CIRISServer#752` |
| read a subject's licences per authority, as a status set; list by authority | none (FSD-004 proposed `GET /v1/licensure/{key_id}?authority=`) | CIRISServer | `blocked_by: CIRISServer#752`; the persist read and list-by-authority exist (persist v60, CIRISPersist#1035) |
| admission refuses an issuer that is neither the authority's quorum nor a `license`-scoped delegate (`licensure_delegator_not_authority`); authority judged at `asserted_at`; `asserted_at_stale` past 72 h | CIRISPersist | CIRISPersist | confirmed — persist v60 (CIRISPersist#1036) |
| the authority of an affiliation resolves to whatever its active `consensus_protocol` admits | `admission::licence_authority_set` | CIRISPersist | confirmed — persist v60 |
| the typed licence payload (`partner_record` member), `revision` anti-rollback, skew bound | CIRISPersist | CIRISPersist | confirmed — persist v60 |
| a subject's `withdraws` against a licence refused; `reconsideration:{grounds}` is the contest | CIRISPersist | CIRISPersist | confirmed — persist v60 (CC 2.4.1.1; CC 4.5.5) |
| the `license` delegation from `ciris-canonical` to an issuing key (the CIRIS worked chain) | CSD-124 | CIRISServer | `blocked_by: CIRISServer#752`; no genesis-bundle change is asked and none is wanted |
| the reader's Who-I-trust walk at read (`honoured` / `licence_issuer_not_trusted` / `licence_class_not_trusted`); `trust_classes` + `trust_depth` on `trust:accepts:v1` | persist | CIRISPersist | `blocked_by: CIRISPersist#1142` |
| add / remove a Who-I-trust entry (any authority, a class set, a depth), the root included | none; `POST /v1/trust-root/import` and `DELETE /v1/trust-root/{id}` write and remove a ROOT's edge only | CIRISServer | CSD-105 extension (`COMMON_CARDS_ANALYSIS.md`); the door is in the README's asks of CIRISServer |
| authority judged at issuance; contest by reconsideration | — | — | CC 2.4.1.2.1 "Authority is judged at issuance"; CC 4.5.5 "Subject standing" |
| a licence under a dissolved authority reads `lapsed` from the dissolution instant; a class the charter does not declare is admitted and honoured by no reader | persist fold / reader walk | CIRISPersist | `blocked_by: CIRISPersist#1143` (`CLM-affiliation-dissolution`) and #1142 (the walk) |

## 3a. Topology (CSD/4)

T6 is the entry: its `authority` relation ("a licensing authority is affiliated",
`authority.licensing`) is the one CSD4.md §5 says waits on this card set.
`requirements.yaml` carries no CSD-122 yet; the block is the entry to add.

```yaml
# `csd:tests` upstream. Relabelled plain `yaml` by the vendoring: CIRISServer's requirements.yaml
# (pinned here at v0.5.233, and still at v0.5.245) carries no CSD-122, and a card may not claim tests
# the server does not carry (evidence/blocked_upstream.tsv). The label returns with the server's entry.
CSD-122:
  catalogue_version: 3
  topology: T6
  tests:
    issued_by_the_authority:
      at: [authority.licensing, community.member]
    issued_by_a_delegate:
      at: [authority.licensing, delegation.chain]
```

**What the vocabulary lacks for this card.** `authority.licensing` says an
authority is affiliated; no atom says a *licence row* stands, nor its status set
(`issued` → `suspended` → reinstated → `revoked`), nor that a reader honoured or
did not honour it (`licence_issuer_not_trusted`, `licence_class_not_trusted`, CC
4.4.3.8). The README's asks list `licence.issued`, `licence.status.suspended`,
`licence.status.revoked`, `licence.honoured` and `licence.not_trusted` as the atoms
this card needs, and a reader-side relation for the Who-I-trust edge (`trust_accepts`
with `trust_classes` / `trust_depth`, CIRISPersist#1142). The CIRIS case needs an
`infrastructure` root with seated founders (`quorum: 2`), which TOPOLOGY.md §2.1
types and the builder refuses until the ceremony can mint it; the card tests the
CIRIS chain on T6's key root with the affiliation standing for `ciris-canonical`
and `founder_only` standing for `quorum:2/3`, and says so.

## 4. Flow

Unwritten at `envisioned`. The chain: the affiliation issues → suspends → the
status set reads `issued, suspended` → reinstates (withdraws) → reads `issued` →
a `license`-scoped delegate issues a second licence and the row names the
delegation → a member without the scope is refused at the door → the subject
contests one row and the row is unchanged → the authority revokes and the row
is terminal. The reader half (TrustSet.lean's worked world): a reader with only
the shipped root honours the CIRIS licence and not the board's → adds the board
for `medical`, depth 0 → the clinician's licence is honoured, the board's legal
licence reads `licence_class_not_trusted`, a stranger's medical licence reads
`licence_issuer_not_trusted` → removes the root → the CIRIS licence is no longer
honoured, the clinician's still is, and the un-federated warning shows.

## 5. Acceptance

Unsigned. Not tested here: Verify's co-signature of a licence under the default
root (CC 3.4.9), the attestation ladder (CSD-052), the accord's halt (CSD-129).
