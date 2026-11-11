# CSD-125 — Grants (access to one thing, from its owner, and never passed on)

**CSD**: CSD-125 · **Standard**: CSD/3 (`CIRISClient/CSD.md`) + CSD/4 (`CIRISServer/FSD/CSD4.md`, catalogue entry T18, `csd:tests` in §3a) · **Origin**: the registry fold, FSD-005
**Flow**: unwritten
**Reads with**: CSD-124 (a `grant`-scoped delegate issues on the owner's behalf), CSD-053/054 (consent, the person's own grants over their data), CSD-107 (file custody; its `access[]` is a door to this list), CSD-007 (`not_granted` on a drive row), `COMMON_CARDS_ANALYSIS.md` (KEEP)
**Vendored from**: CIRISRegistry `FSD/CSD/CSD-125-grants.md`, branch `fold/org-as-affiliation`, commit `14fbcd0`. Changes here are mechanical only: this line, `registry_sha256` re-pinned from `d58715660…` (CC rc12) to this repo's vendored rc6 registry, and the `csd:surface` fence relabelled plain `yaml` because the checker refuses a `proposed:` screen (CSD-108's precedent). Also: the one `csd:shows` row for `key_grant:{axis}` moved, unchanged, into the plain `yaml` block after it, because the pinned rc6 registry does not carry that family; the `csd:tests` fence relabelled plain `yaml` until CIRISServer's `requirements.yaml` carries this card (`evidence/blocked_upstream.tsv`).

```yaml csd:stage
stage: envisioned
owner: CIRISRegistry
```

## 1. Mission (why)

**The owner or steward of a thing — a sealed file, a stream, a community epoch
— can grant a named recipient access to it, for a window, see every grant that
is live over what they own, and see what a recipient holds; and nobody who
received a grant can pass it on.** Serves **Contextual Integrity**, on
CC 2.4.1.2.1: "a grant is non-transferable by default … an onward grant is a
new grant issued by the asset's owner or steward, or by a holder of a
`grant`-scoped delegation from them, never a forwarded one", and "possession of
the data-encryption key is not issuance authority."

**Two wire shapes, one card.** A key wrap is `key_grant:{axis}:v1`, one scores
row per recipient (CC 3.3.2; persist, CIRISPersist#989), `{axis}` ∈ content |
epoch | stream, sealer-only, never withdrawn
(forward secrecy is by rotation). A consent grant is `consent:scope:{kind}` over
a person's data. Both answer "who may reach this thing", both are leaves of the
authority tree, and the card renders them in the same rows. **A metered
resource grant is not a grant on this card.** CIRIS sells nothing that would be
metered and CC names no such family, so the card carries no CIRISBilling row.

**A grant is never withdrawn and the card never offers it.** CC 3.1.3: a
`withdraws` naming a `key_grant` row is refused. Ending access is rotation: a
new epoch, a new DEK, and the recipient is not in the new wrap set. The card's
"end access" is therefore "rotate and exclude", and it says so.

**A grant ends with its record.** When the affiliation that owns or stewards the
asset is dissolved (the `withdraws` of its record, CC 4.4.3.2.8 "Succession and dissolution"),
every grant over its assets ends with it, the DEK rotates no more, and no new grant is
issued; what a recipient already fetched stays theirs, which is the same sentence the
end-access confirm already says. `blocked_by: CIRISPersist#1143` (`CLM-affiliation-dissolution`).

**A grant is judged at its signed instant.** Persist v60 judges a `key_grant`
row's sealer chain, and a `grant`-scoped delegate's chain, at the row's
`asserted_at`, so a sealer whose delegation later lapses does not
un-seal what was sealed; a row signed more than 72 h ago whose chain no longer
stands at receipt is `asserted_at_stale`.

## 2. Surface (what)

```yaml
# csd:surface once the screen is declared in CIRISApp.kt
surface: proposed:grants
screen: proposed:Grants
```

Placement: My things › Files (grants over my content) and Communities and
Businesses › Rules (grants over the affiliation's assets). The same screen, two
scopes.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:recipient_key_id
    use: emit
    type: string
    example: "wa-peer-4a19c2"
    renders: "'To' — the recipient's identity occurrence; picked from contacts or the roster, never typed as an email"
    tag: "proposed:input_grant_recipient"
  - ceg: x_private:asset
    use: emit
    type: string
    example: "at_rest_sha256:5ef9e607…"
    renders: "'What' — the thing, named by the identity on its axis (the blob digest, the community + epoch, the stream id)"
    tag: "proposed:txt_grant_asset_*"
  - ceg: x_private:issued_by
    use: display-only
    type: string
    example: "wa-self-88b1"
    renders: "'Granted by' — the sealer; 'under a grant-scoped delegation from {owner}' when the issuer is a delegate (CSD-124; CIRISPersist#1033 admits it)"
    tag: "proposed:txt_grant_issued_by_*"
  - ceg: x_private:key_validity_window
    use: emit
    type: timestamp
    example: "2026-12-31T00:00:00Z"
    renders: "'Until' — optional; a grant past its window opens nothing and the row says 'expired'"
    tag: "proposed:input_grant_until"
  - ceg: x_private:wrap_algorithm
    use: display-only
    type: "enum[v2]"
    example: "v2"
    renders: "'Post-quantum wrapped' — v2 only (CC 4.4.3.4.1); a v1 wrap is shown as refused, never as a grant"
    tag: "proposed:chip_grant_wrap_*"
  - ceg: x_private:onward_grant_refused
    use: display-only
    type: bool
    example: true
    renders: "on a grant I RECEIVED: no 'share' control at all, and the explainer 'You can open this. You cannot pass it on; ask {owner}.' — receipt is not issuance"
    tag: "proposed:txt_grant_no_onward_*"
  - ceg: x_private:end_access_is_rotation
    use: emit
    type: bool
    example: true
    renders: "'End access' on a grant I ISSUED: the confirm says 'This rotates the key. {who} keeps what they already fetched and gets nothing new.' — never 'revoke'"
    tag: "proposed:btn_grant_end_*"
  - ceg: "consent:scope:{kind}"
    bind: {kind: share}
    use: display-only
    type: string
    example: "consent:scope:share:v1"
    renders: "a consent grant over my data in the same list, with its kind; emitted from CSD-053/054, read here"
    tag: "proposed:row_consent_grant_*"
```

```yaml
# Held out of `csd:shows` by the vendoring, unchanged: `key_grant:{axis}` is registered at CIRISConstitution
# HEAD and is not in this repo's pinned rc6 registry (client/ceg/namespace_registry.json), so the
# checker refuses it as an unregistered bare prefix. It moves back when that registry is re-pinned.
fields:
  - ceg: "key_grant:{axis}"
    bind: {axis: content}
    use: display-only
    type: "enum[content,epoch,stream]"
    example: "content"
    renders: "one row per (thing, recipient): 'invoice.pdf — shared with {who}' / 'the Allotment room, epoch 7 — {who}' / 'the Tuesday stream — {who}'"
    tag: "proposed:row_grant_*"
```

**`consent:scope:*` is reserved to CIRISAgent at CC 3.4.5**, so this card reads
it and does not emit it; the emitting card is CSD-053/054. `key_grant:*` is
sealer-only (CIRISPersist's reservation): the card emits by asking the node to
seal, never by composing a wrap itself.

```yaml csd:states
populated: {tag: "proposed:list_grants"}
empty:     {tag: "proposed:txt_grants_empty", renders: "Nothing shared yet. / Nothing has been shared with you that this node holds the key for."}
loading:   {renders: "the frame with a progress affordance and NO sentence"}
error:     {tag: "proposed:txt_grants_error", renders: "Couldn't read grants from this node, so this list is not complete."}
```

## 3. Contracts (who)

| What | Route | Owner | State |
|---|---|---|---|
| grant access to a file (the node seals a wrap to the recipient) | partial: `POST /v1/files` at a cohort places it for a roster, not for one recipient | CIRISServer | `blocked_by: CIRISServer#752` |
| read grants I issued / grants I hold | none as a list (drive rows carry custody, CSD-107; `access[]` on the custody body is parsed and not drawn) | CIRISServer | `blocked_by: CIRISServer#752` |
| end access = rotate and exclude | partial (room epoch rotation exists for communities) | CIRISServer | unconfirmed |
| a `grant`-scoped delegate may seal on the owner's behalf; admission refuses a recipient re-granting; per-recipient `key_grant` rows; judged at `asserted_at` | CIRISPersist | CIRISPersist | confirmed — persist v60 (#1033 the `grant` arm, #989 the rows, `asserted_at`); carriage of per-recipient rows: CIRISEdge#808 |

## 3a. Topology (CSD/4)

The entry is **T18** ("delegation ends; a key grant; a guardian bond absent"),
whose `key_grant` relation is the one that reaches `grant.state.held`
("`key_grant:{axis}:v1` one row per recipient"); the builder stops before it
today (`PENDING_BUILDER`: "persist emits `key_grant:content:v1` per recipient on
a sealed share; no read witnesses it yet"). T6 `serves` this card for the
delegate-issuer case only. `requirements.yaml` carries no CSD-125 yet; the block
is the entry to add. T18 differs between catalogue 3 and 5 only in its
`catalogue_version` field.

```yaml
# `csd:tests` upstream. Relabelled plain `yaml` by the vendoring: CIRISServer's requirements.yaml
# (pinned here at v0.5.233, and still at v0.5.245) carries no CSD-125, and a card may not claim tests
# the server does not carry (evidence/blocked_upstream.tsv). The label returns with the server's entry.
CSD-125:
  catalogue_version: 3
  topology: T18
  tests:
    held:
      at: [grant.state.held]
    issued_by_a_delegate:
      at: [grant.state.held, delegation.chain]
```

**What the vocabulary lacks for this card.** `issued_by_a_delegate` holds in no
entry at one instant today (T18 grants from the owner; T6's chain carries no `grant`
scope): `check_csd4` reports it `uncovered`, which is the honest state. No atom
names a grant's axis or window, the recipient's refused onward grant, or the
rotation that ends access (by design no relation ends a grant); the README's asks
list `grant.axis.content`, `grant.onward_refused` and `grant.ended_by_rotation`.

## 4. Flow

Unwritten at `envisioned`. The chain: owner grants A → A opens it → A's card has
no share control → owner delegates `grant` to B (CSD-124) → B grants C → C's row
names B under the owner's delegation → owner ends access: rotation; A and C open
nothing new.

## 5. Acceptance

Unsigned. Not tested here: the consent grants' own emission (CSD-053/054), the
bytes' custody (CSD-107).
