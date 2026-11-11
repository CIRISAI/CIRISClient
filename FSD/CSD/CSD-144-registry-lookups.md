# CSD-144 — Registry lookups (a build, a partner, a revocation, the transparency witnesses)

**CSD**: CSD-144 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the owner's request (2026-11-10) to fill the card set with the NodeCore and Registry screens
**Reads with**: CSD-087 (Verify an agent build: the build-by-hash lookup, which this card does not repeat), CSD-105 (Trust root: `/v1/trust-root/bundle`, live on CIRISServer), CSD-122 and CSD-123 (licences and partners, the write side), CSD-071 (Audit: `transparency_log:inclusion`)
**Sources read**: CIRISRegistry `origin/main` @ `f04b149` (`rust-registry/ciris-registry-core/src/api/http.rs`); CIRISServer `origin/main` @ `7af5d4eb` (2026-11-09)
**Flow**: unwritten. The client cannot reach these routes, and no screen exists

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person can ask the federation's registry four public questions and get an
answer they can act on: what is registered for this version of a build, what a
key's partner standing is, whether something has been revoked, and which keys
witness the transparency log. When the registry cannot answer, the card says it
could not ask, never "no".**

**What is already covered, and so not here.** Build lookup **by hash** is
CSD-087's, against the missing `/v1/registry/lookup` (CIRISServer#669). The
trust root's bundle is CSD-105's, and CIRISServer serves it
(`src/trust_root_api.rs:680` on `origin/main`). The steward key
(`/v1/steward-key`) is the same handler as the bundle in the Registry's fold
(`src/fold.rs:77-78`) and needs no card of its own. This card holds the four
reads no card cites: `/v1/builds/{version}`, `/v1/partner/{key_id}`,
`/v1/revocation/{target_id}` and `/v1/transparency/witnesses`.

Falsifiable:

* **"Not revoked" is drawn only when the registry read its revocations.** Today
  it cannot be: `check_revocation` turns a database error into
  `revoked: false` (`http.rs:1053-1085`: the query ends `.ok().flatten()` and
  `None` answers `revoked: false`). The card therefore shows the answer as
  "The registry reports no revocation", with the time it checked, and the
  CIRISRegistry ask below is to make a failed read an error. Until then the card
  cannot tell the two apart and says so in one line under the answer.
* **The same for partner standing.** `partner_composition` turns a failed
  partner lookup into "no partner row" (`http.rs:450`, `.ok().flatten()`),
  answering no role, no licence and `revocation_active: false`.
* **A licence status is shown in its own word.** `suspended` is reversible and
  `revoked` is terminal, and the registry keeps them apart on purpose
  (`licensure_status`, `http.rs:418-440`). `unspecified` is shown as "Not stated",
  never as issued.
* **A witness is shown with its keys and since when**, so a reader can compare a
  fingerprint out of band. The card claims nothing about a witness's
  independence, which the list does not carry.

## 2. Surface (what)

No `csd:surface` block yet (CSD-108's precedent). CSD-087 §3 recommends placing
registry facts under Everyone › Record; this card takes that placement, as one
screen with four lookups.

```yaml
# csd:surface once the screen is declared in CIRISApp.kt
surface: registry-lookup
screen: RegistryLookup
```

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: "build:registered:{target}"
    bind: {target: python-source-tree}
    use: display-only
    type: string
    example: "2.12.1 · python-source-tree · registered 2026-10-02 · active"
    renders: "for a version (and optional project and target, `BuildVersionQuery`, http.rs:1149-1154): version, target, status, registered-at, source repo and commit, the build hash and the file-manifest hash with its file count (`BuildRecordResponse`, http.rs:1351-1374). A status this app does not know is shown verbatim"
    tag: "proposed:card_registry_lookup_build"
  - ceg: "provenance:slsa:{level}"
    bind: {level: l2}
    use: display-only
    type: unconfirmed
    example: "SLSA level 2"
    renders: "from `federation_provenance` when present; absent: 'No provenance recorded', not a level"
    tag: "proposed:txt_registry_lookup_build_provenance"
  - ceg: "partner_role:{role}"
    bind: {role: professional_medical}
    use: display-only
    type: string
    example: "PROFESSIONAL_MEDICAL"
    renders: "'Partner role: professional medical'. The registry sends the role in capitals (http.rs:456-463) while the family's vocabulary is lowercase and case-exact (registry rc6: refuse, never fold). The card shows the string as sent and does not fold it (§6)"
    tag: "proposed:txt_registry_lookup_partner_role"
  - ceg: "licensure:{authority_id}"
    bind: {authority_id: 3f1c9a2e-0b4d-4e8f-9a61-2d7c5e0f8b13}
    use: display-only
    type: "enum[issued,probation,restricted,suspended,revoked,lapsed,surrendered,reduced,unspecified]"
    example: "suspended"
    renders: "one row per licence: the authority id, the status in words, and the expiry. Suspended and revoked in different tones, with 'can be restored' only on suspended. The registry emits four of these today (issued, suspended, revoked, unspecified; http.rs:433-440); the rest are CC's vocabulary, accepted when sent"
    tag: "proposed:row_registry_lookup_licence.<authority_id>"
  - ceg: "bond_posted:{currency}"
    bind: {currency: USD}
    use: display-only
    type: unconfirmed
    example: "no bond reported"
    renders: "the bond when sent. The handler always sends `bond_posted: None` (http.rs:466-470), so today the row reads 'No bond reported' and never 'No bond'"
    tag: "proposed:txt_registry_lookup_bond"
  - ceg: "revocation:{entity_type}:{reason}"
    bind: {entity_type: license, reason: superseded}
    use: display-only
    type: string
    example: "revoked 2026-09-14 · 'key compromise reported by holder'"
    renders: "'Revoked on 14 Sep 2026' with the registry's reason text, or 'The registry reports no revocation (checked 09:12)'. The reason is free text (`reason_detail`), not the family's `{reason}` vocabulary, so it is shown as text and never mapped to a reason code"
    tag: "proposed:card_registry_lookup_revocation"
  - ceg: "transparency_log:cosigned:{tree_size}"
    use: display-only
    type: unconfirmed
    example: "witness-eu-1 · SHA256:9c1f… · YubiHSM · since 2026-06-01"
    renders: "one row per witness from `/v1/transparency/witnesses`: key id, fingerprint, hardware class, trusted since (`WitnessEntry`, http.rs:575-582). The family is reserved to witness emitters; this card only displays"
    tag: "proposed:row_registry_lookup_witness.<witness_key_id>"
  - ceg: x_private:registry_lookup_query
    use: emit
    type: string
    example: "2.12.1"
    renders: "one field per lookup: a version (with project and target pickers), a key id, a target id"
    tag: "proposed:input_registry_lookup_query"
```

`build:registered:{target}`, `provenance:slsa:{level}`, `partner_role:{role}`,
`licensure:{authority_id}`, `bond_posted:{currency}`,
`revocation:{entity_type}:{reason}` and `transparency_log:cosigned:{tree_size}`
are registered (rc6). Only the query field is `x_private:`.

```yaml csd:states
populated: {tag: "proposed:state_registry_lookup_result", renders: "the answer card for the lookup asked"}
empty:     {tag: "proposed:state_registry_lookup_result__empty", renders: "'The registry has no build for 2.12.1 on python-source-tree.' A 404 with a body is an answer. For a revocation there is no empty state: 'no revocation' is the answer, drawn as such"}
loading:   {tag: "proposed:state_registry_lookup_result__loading", renders: "the submit control disabled; the previous answer cleared as soon as the query changes, as CSD-087 does"}
error:     {tag: "proposed:state_registry_lookup_result__error", renders: "'Could not ask the registry', with the reason: this node does not serve registry lookups (`state_registry_lookup_result__not_on_this_node`), or the registry did not answer. Never drawn as 'not found' or 'not revoked'"}
```

## 3. Contracts (who)

**The routes exist on the Registry's service, not on the client's node.** The
client talks to its own CIRISServer node, and `origin/main` serves none of these
four. CIRISRegistry is folding into CIRISServer (CSD-087 §1), and the fold has
moved `/v1/steward-key` and `/v1/trust-root/bundle` so far.

| value | endpoint | owner | state |
|---|---|---|---|
| a build by version | `GET /v1/builds/{version}?project=&target=` | CIRISRegistry (`http.rs:2905`, handler `:1663`) | **served by the Registry; not on CIRISServer** |
| a key's partner standing | `GET /v1/partner/{key_id}` → `{key_id, partner_role, bond_posted, licensure[], revocation_active, revocation_reason, timestamp}` | CIRISRegistry (`http.rs:2884`, handler `:444`) | **served by the Registry; not on CIRISServer.** A failed lookup answers as "no partner" (§1) |
| whether something is revoked | `GET /v1/revocation/{target_id}` → `{license_id, revoked, revoked_at, reason, checked_at}` | CIRISRegistry (`http.rs:2895`, handler `:1053`) | **served by the Registry; not on CIRISServer.** A failed read answers `revoked: false` (§1). The field is `license_id` whatever the target is |
| the transparency witnesses | `GET /v1/transparency/witnesses` → `{witnesses[], timestamp, note}` | CIRISRegistry (`http.rs:2890`, handler `:832`) | **served by the Registry; not on CIRISServer** |
| build by hash | `GET /v1/registry/lookup` | CIRISServer | **missing**, CIRISServer#669; CSD-087's |
| the trust-root bundle | `GET /v1/trust-root/bundle` | CIRISServer (`src/trust_root_api.rs:680`) | **live**; CSD-105's |

## 4. Flow (how)

Every tag is `proposed:`. Ask whether a target is revoked on a node that does not
serve the lookup:

```yaml
expect:
  visible: ["proposed:state_registry_lookup_result__not_on_this_node"]
  absent:  ["proposed:card_registry_lookup_revocation"]
```

## 5. QA plan

Not drivable until CIRISServer serves the four routes. Then: stop the
registry's database and ask for a revocation; the card must show the error, which
requires the CIRISRegistry fix first. Until that fix, this test is expected to
fail, and it is the test that proves the fix.

## 6. Status and asks

Sketched.

Asks, one repo each:

* **CIRISRegistry**: make a failed read an error in `check_revocation` and
  `partner_composition` (both use `.ok().flatten()`), so "no" and "could not
  read" differ on the wire; send `partner_role` in the family's lowercase
  vocabulary; send `bond_posted` or drop it; name the revocation response field
  for what it holds (`target_id`, not `license_id`).
* **CIRISServer**: serve the four reads as the Registry folds in, keeping the
  same error distinction.
