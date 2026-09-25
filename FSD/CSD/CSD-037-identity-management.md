# CSD-037 — My Identity (My things › Devices & keys › My Identity)

**CSD**: CSD-037 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person can see every device that is currently *them*, add another one, and
evict a lost or stolen one from any device they still hold — and the screen tells
them the truth about what eviction does and does not undo.**

Serves **CC 3.3.6** (`identity_occurrence`, the legacy §5.6.8.8): the roster is
"what makes 'this is me, on another device' a cryptographic fact rather than a
guess". Its revocation rule is symmetric with its admission rule — "a `withdraws`
against an `identity_occurrence` Contribution issued by `identity_key_id` (or by
any current occurrence) evicts the occurrence" — which is the laptop-loss answer:
your phone evicts your stolen laptop, with no quorum and no Wise Authority.

And it serves **CC 4.5.12.1**, which is the part a device-management screen is
most tempted to soften: eviction stops the substrate wrapping *new* key grants;
"**No DEK rotation; no re-encryption**". CC 3.3.6.1 names the consequence as a
gap under the CC 1.13.3 honesty discipline — "Do not represent KEM rotation as
recovering the confidentiality of previously-wrapped content." The revoke
confirmation must say that, and today it does not (§6).

## 2. Surface (what)

```yaml csd:surface
surface: identity-management
screen: IdentityManagement
```

`nav_map` derives `btn_my_things -> nav_instrument_devices_keys ->
nav_epistemic_identity_management`. Three roles on one page: the roster and
eviction (on a device already enrolled), enrolling *this* device (on a new one),
and the portable-ID repair path.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:identity_key_id
    use: display-only
    type: string
    example: "eric-moore-v1"
    renders: "the self fed-ID the roster belongs to — the node's BOUND OWNER, not the node key"
    tag: identity_self_key_id
  - ceg: x_private:occurrence_key_id
    use: display-only
    type: string
    example: "ciris-phone-4a19c2"
    renders: "ciris-pho…19c2 (mono, middle-truncated) — one row per device"
    tag: "identity_row_{occurrenceKeyId}"
  - ceg: x_private:device_class
    use: display-only
    type: "enum[phone,laptop,agent]"
    example: "laptop"
    renders: "laptop"
    tag: "proposed:identity_row_device_class"
  - ceg: x_private:has_encryption_pubkeys
    use: display-only
    type: bool
    example: true
    renders: "a chip (mobile.identity_has_encryption) when the occurrence is a Self-DEK recipient; nothing when it is not — an occurrence without them is fail-secure EXCLUDED from the cascade, and that absence is currently drawn as an absence"
    tag: "proposed:identity_row_encryption"
  - ceg: "hardware_custody:{platform}"
    bind: {platform: tpm}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "NOT RENDERED. `SelfOccurrence.hardwareAttestation` is decoded and never drawn — the one field CC 4.5.12.3 names as where 'the security gradient lives'."
    tag: "proposed:identity_row_hardware"
  - ceg: x_private:asserted_at
    use: display-only
    type: timestamp
    example: "2026-09-01T11:02:44Z"
    renders: "2026-09-01T11:02:44Z (raw RFC-3339, unformatted)"
    tag: "proposed:identity_row_asserted_at"
  - ceg: x_private:occurrence_label
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "NOT RENDERED — the node has served `label` since 0.5.216 and the Kotlin model has no field for it, so every row is a key id"
    tag: "proposed:identity_row_label"
  - ceg: x_private:occurrence_revoked
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "NOT RENDERED — the node serves `revoked` under `include_revoked=1` and the client never asks, so an evicted device leaves no visible trace"
    tag: "proposed:identity_row_revoked"
  - ceg: "ownership:{relation}:{target_kind}:{version}"
    bind: {relation: responsible_party, target_kind: node, version: v1}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "on every row, 'Reachable' (announced: people can find you through this device) or 'Private' (only you see it). Announce is PER DEVICE (#655): it widens that node's owner-binding to federation, and strangers see only the reachable rows. The per-row field name is unconfirmed until CIRISClient#78"
    tag: "proposed:identity_row_reachability_{occurrenceKeyId}"
  - ceg: x_private:minted_fedcode
    use: display-only
    type: string
    example: "ciris1q…"
    renders: "this new device's fedcode, as text and QR, for the primary to enroll"
    tag: identity_enroll_fedcode
```

Seven of the ten rows are `proposed:` and four are `unconfirmed`. The roster
itself is tagged (`identity_device_list`, `identity_row_{occurrenceKeyId}`,
`identity_self_key_id`: `IdentityManagementScreen.kt:170, 228, 236`; and the enrolled fedcode, `identity_enroll_fedcode` at `:423`), but
nothing inside a row is, so a flow can count devices and click one it cannot
read.

**Reachable or private, per device (0.5.218).** The maintainer ruled on #655
that announce is per device, and that the public roster is exactly the devices
a person chose to announce on. This page is the owner's view, so it shows
every device and says which kind each is. The words are "Reachable" and
"Private", not "announced", because that is what the choice means to the
person. Making a device reachable from here is planned, not built: this
device's own announce is `POST /v1/federation/announce` (live), and a
per-device "make this device reachable" on the approving side is #678, still
building. A private row therefore offers "Make reachable" only when it is the
device in hand.

**Approve a new device** (CSD-094) sits directly below the roster, because it
adds a device to this person's identity (CC 3.3.6).

```yaml csd:states
populated: {tag: identity_device_list}
empty:     {tag: "proposed:identity_roster_empty", renders: "mobile.identity_roster_empty (IdentityManagementScreen.kt:223) — untagged today"}
loading:   {tag: "proposed:identity_loading", renders: "a 14dp progress affordance beside the roster heading and NO empty sentence (:217)"}
error:     {tag: identity_error, renders: "the error banner at :202 — a real tag, and the only one of the four that exists"}
```

`identity_notice` (:187) is the success counterpart and must never carry a
failure; the two banners share a frame and differ only by container colour, which
is the `errorNeverLooksLikeEmpty` rule applied to the wrong pair — a notice and
an error also must not look alike.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the self fed-ID | `GET /v1/setup/owned-nodes` → `owner`, falling back to `GET /v1/federation/self-key-record` | CIRISServer | live — `bootstrap.rs:1489`, `federation_admin.rs:900` |
| the roster | `GET /v1/self/occurrences?identity_key_id=…` | CIRISServer | live — `src/auth/occurrence.rs:668`, **unauthenticated** on `main`. **0.5.218 (#655), built, unmerged:** the owner's session sees every device (labels, and revoked rows under `include_revoked=true`); anyone else sees only announced devices, and an empty list when nothing is announced (the same answer as for an unknown person). Not readable: no branch or PR is pushed. The owner's view here does not change |
| reachable / private on a row | same route, owner session | CIRISServer | **unconfirmed** field name, until CIRISClient#78 |
| make a device reachable | this device: `POST /v1/federation/announce` (`src/claim_remote.rs:1250`, live, owner-gated, loopback; the RNS announce follows on next boot). Another device: planned on the approving side | CIRISServer | this device **live**; another device **not built** (#678, still building) |
| approve a new device | `POST /v1/setup/claim-remote` | CIRISServer | live; specified in CSD-094 |
| add a device | `POST /v1/self/occurrence` | CIRISServer | live — `occurrence.rs:663`, hybrid request-signature |
| evict a device | `POST /v1/self/occurrence/revoke` | CIRISServer | live — `occurrence.rs:665` |
| mint this device's fed-ID | `POST /v1/self/identity` | CIRISServer | live — `identity.rs:1945`, loopback-only |
| portable ID / repair | `POST /v1/self/occurrence/portable`, `POST /v1/self/associate` | CIRISServer | live — `portable_occurrence.rs:1152`, `:1155`, loopback-only. **0.5.218 (#639), built, unmerged:** `associate {device: "tpm"}` is refused by name when no TPM is available, instead of silently minting a software key. The card shows the refusal and offers software custody as an explicit choice |
| `revoked` on a row | `GET /v1/self/occurrences?include_revoked=1` | CIRISServer | live; **wired in #94** (before it, the client never sent the parameter) |
| `label` on a row | same route; owner-session only | CIRISServer | live; **wired in #94** (before it, `SelfOccurrence.kt` had no field) |
| name a device | `POST /v1/self/occurrence/label` | CIRISServer | live (`src/self_devices.rs:473`); **wired in #94** |
| `hardware_attestation` rendering | already on the wire | CIRISClient | **decoded and not drawn** |
| sign out of this device | `btn_logout` in the **This device** block: "Signed in on this device as {identity}", plus "via Google/Apple" or "with a password on this node" only when the client recorded the method at sign-in (`models/SignInMethod.kt`) | CIRISClient | **in #93**: the "Account" row is deleted; there is no account in CIRIS, only identities, so sign-out lives with the identity it ends (#51 still holds: a bare node reaches it here). Settings keeps a second `btn_logout` until CIRISAgent#1181's gate routes here |

## 4. Flow (how)

Sign in on an enrolled device; open My things › Devices & keys › My Identity.

```yaml
expect:
  state: populated
  visible: [identity_self_key_id, identity_device_list]
  count: {of: "proposed:identity_row_*", min: 1}
  each: {of: "proposed:identity_row_device_class", one_of: [phone, laptop, agent]}
```

Enroll a second device: on the new device click `btn_identity_enroll_this_device`,
copy `identity_enroll_fedcode`, paste it into
`input_identity_device_code` on the primary, click `btn_identity_add_device`.

```yaml
expect:
  count: {of: "proposed:identity_row_*", eq: 2}
  visible: [identity_notice]
```

Evict it: click `btn_identity_revoke_<occurrenceKeyId>`, then
`btn_identity_revoke_confirm`.

```yaml
expect:
  count: {of: "proposed:identity_row_*", eq: 1}
  text: {"proposed:identity_revoke_dek_note": "already shared"}
```

The last line asserts a sentence that does not exist yet (§6). On a node that is
down:

```yaml
expect:
  state: error
  visible: [identity_error]
```

## 5. QA plan

**Platforms.** All five, and this is one of the few screens where the *pair* is
the test: the enroll flow needs two clients against one node. Desktop + Android
is the cheapest pair.

**Not tested here.** The QR scan (camera capture is a platform affordance with no
endpoint); the portable-ID repair path (`btn_identity_portable_create`,
`btn_identity_associate_dir`) needs a removable volume and a damaged key to
repair; hardware attestation, which no CI device produces.

## 6. Delta — card vs API vs CC

* **The 0.5.216 device-identity work is entirely unconsumed.** The node serves
  `label`, serves `revoked` under `include_revoked`, and accepts
  `POST /v1/self/occurrence/label` and `POST /v1/self/nodes/{id}/release`. The
  client calls none of them and its `SelfOccurrence` (`SelfOccurrence.kt:29-46`)
  has no field for the first two. **Ask (CIRISClient):** add `label: String?` and
  `revoked: Boolean = false` to `SelfOccurrence`, pass `include_revoked` behind a
  "show evicted devices" toggle, and add `labelOccurrence()` driving
  `POST /v1/self/occurrence/label`. A roster of bare key ids is a roster a person
  cannot act on: "evict the laptop" requires knowing which key id is the laptop.
* **CC → card, the honesty gap.** The revoke confirmation says nothing about
  CC 4.5.12.1. A person evicting a stolen laptop is entitled to read, at that
  moment, that history already shared stays shared and only new content is
  withheld. **Ask (CIRISClient):** put that sentence in the confirm dialog, in
  CC 3.3.6.1's own terms, before the destructive button.
* **CC → card, the missing gradient.** CC 4.5.12.3 says the security gradient of
  single-vouch admission "lives in the optional `hardware_attestation` field, not
  in the admission rule". The client decodes that field and never draws it, so
  the one thing that distinguishes a hardware-rooted device from a software one
  is invisible on the screen whose whole job is to distinguish devices.
* **A CC conflict to escalate, not to paper over.** The roster route is
  unauthenticated and the client's own comment calls it "public §5.6.8.8 binding
  metadata". CC 3.1.3.1 says the opposite about scope: publishing a self's
  occurrence set broadly is "an attendance map of a person's devices … the CC 5.2
  structural-invisibility promise inverted". CC 3.3.6 nowhere grants the roster
  `cohort_scope: federation`. **Ask (CIRISServer):** state the roster's
  `cohort_scope` on the route and gate it accordingly; an unauthenticated global
  read of *which devices a named person owns* needs a written constitutional
  basis, and I could not find one.
* **A narrowing worth naming.** CC 3.3.6 closes `device_class` over six values
  (`phone | laptop | server | embedded | agent | service`); persist's
  `check_device_class` and the client both allow three. Not a defect — a
  substrate narrowing of a CC vocabulary — but the CSD records it so that
  "embedded" arriving one day is a known widening and not a parse failure.
* **Placement.** Correct, and the instrument's name is doing real work: this is
  *devices and keys*, the one place a person's identity is a list of things they
  hold.
