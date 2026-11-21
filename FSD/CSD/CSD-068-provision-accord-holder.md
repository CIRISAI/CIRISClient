# CSD-068 — Provision Accord Holder (the custody floor, in three steps)

**CSD**: CSD-068 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: `testing/flows/csd-068-provision-accord-holder.yaml` (floor `>=0.5.224`)

```yaml csd:stage
stage: testable
owner: CIRISClient
```

**Code gaps.** Where this card and the client code disagree today, each proven against the tree by `packaging/check_csd_code_gaps.py`. Closing one means deleting its entry in the same commit.

```yaml csd:code_gaps
- id: custody-tier
  card: 'custody is hardware_class + pq_custody: removable_offline + a DER chain, inside the self-signed key:record'
  code: 'the provisioning screen reads custody_tier (portable_2fa) from a separate custody attestation'
  where: client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/viewmodels/ProvisionAccordHolderViewModel.kt
  open_while: 'custody_tier'
  blocked_on: 'O1'
- id: holder-registration
  card: 'there is no registration: a holder is seated only by a bundle that names its record'
  code: 'the client still has POST /v1/accord/holder'
  where: client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/api/CIRISApiClient.kt
  open_while: 'v1/accord/holder\b'
```

> **On v62 (audit of 2026-11-21): stale.** The screen's purpose survives: a
> person mints a hardware-custodied holder key and is told plainly what failed.
> Its custody shape, its next step and its routes change. v62 is a total
> re-genesis, so a holder key or record of the earlier mesh gives no standing
> here. §1.1 states the v62 rules; the rest of the card is the 0.5.x screen
> until it is rebuilt.

## 1. Mission (why)

**A would-be accord holder mints their portable-2FA HUMANITY_ACCORD identity (the 0.5.x shape; v62 in §1.1)
from a FIPS YubiKey and a chosen ML-DSA USB path, and is told in plain language
what failed when it does — and what to do next when it does not.** Serves
**Integrity**.

This is the **custody floor** under CC 4.2: the kill switch is only as real as
the hardware the three holders hold, and CC 4.2.2.1 is explicit that
`hardware_class` is a **producer claim, not a cryptographically-attested fact**.
A flow that mints a holder identity therefore has one job beyond working: it
must not let a person believe they have provisioned hardware custody when they
have provisioned something weaker. The step-1 acknowledgement is the shape that
obligation takes here — the app asks the person to confirm the token is already
FIPS-approved, because the app cannot check.

**The app holds no keys and does no crypto.** One loopback POST; the node opens
the YubiKey, AEAD-wraps the ML-DSA seed to the USB, and mints both artifacts.
Touching the token — PIN and touch — is the real authority.

### 1.1 What a holder mints on v62

* **One composite key and one artifact.** The holder mints one composite key
  (the Ed25519 half on a FIPS YubiKey, the ML-DSA-65 half `removable_offline` on
  separate media) and its self-signed `key:record` {`accord_holder`}, with no
  `valid_until`. The record carries `attestation_evidence` =
  `{hardware_class: "YubiKey_5_FIPS", pq_custody: "removable_offline", chain:
  [9c leaf, f9, PIV Attestation B 1, Attestation Intermediate B 1]}` (DER, leaf
  first). There is no separate `custody_attestation` object; the record's own
  composite COSE signature replaces the July signed envelope (CEREMONY step 1;
  P-CER `holder_key_record_draft`, `CustodyEvidence::yubikey_5_fips`;
  CEREMONY-READINESS §3). `custody_tier: portable_2fa` is the earlier-form
  envelope's vocabulary and does not appear on v62.
* **The class is verified, not just claimed.** At seating the node checks the
  chain to Yubico Attestation Root 1, touch-always (.3.8), FIPS (.3.10), and
  that the leaf SPKI is the composite's Ed25519 half (Verify
  `edition2/genesis.rs:2202` `custody_gate`, applied `:1441`). The step-1
  acknowledgement may stay as UX, but the claim is checked.
* **A fresh attestation per holder.** Each holder exports a fresh attestation
  from their own token, before minting the record (CEREMONY §1).
* **One device per holder, by the leaf serial.** CC 4.2.3: the holders' leaves
  must name distinct device serials (`holder_device_shared`). The screen shows
  the token serial (ext .3.7) so the holders can check that theirs differ. The
  f9 attestation certificate is not the test: the same f9 certificate shared
  across distinct device serials is valid (Eric's ruling).
* **No registration.** A holder is seated only by a genesis or a later bundle
  that names this record; there is no "ask the node owner to register you". The
  record must be minted before the holder's halt (the halt verifies under the
  key the bundle seats) and after the fresh attestation export (CEREMONY steps
  1, 3; CEREMONY-READINESS §4).
* **The recovery key.** Each holder also needs a recovery key, held offline and
  apart: fresh in this edition, a commitment over its key material in the
  charter's `recovery_commitments`, distinct from every other holder's, and
  never a signing key a bundle seats (`recovery_commitment_shared`,
  `recovery_commitment_is_holder_key`; CC 4.2.6 line 343; CC rc22 `85d3e18`).
  Its commitment's hex prefix is shown for the out-of-band compare. **OPEN —
  CIRISClient / CIRISServer (O9):** which screen mints it, this one or the
  ceremony.
* **Routes.** `provision-holder` is re-implemented as an on-device composite
  key + `holder_key_record_draft`: 0.6: pending (CIRISServer, O1).
  `accord/holder` is retired. `yubikey-status` is kept (a host probe).

## 2. Surface (what)

```yaml csd:surface
surface: provision-accord-holder
screen: ProvisionAccordHolder
```

`nav_map` derives `circle_global_commons -> tab_safety ->
nav_epistemic_provision_accord_holder` — Everyone › Safety
(`CirclesNav.kt:115`), beside the Accord card and the Constitutional card. It is
**also** reachable as a button from the Constitutional screen
(`btn_open_provision_holder`, `ConstitutionalScreen.kt:302`), which is the entry
a person actually takes: they are looking at a roster and want to join it.

Two entries, one screen, one nav row — this is a case where the second entry is
a shortcut into a placed surface rather than an orphan, and `CirclesNavTest`'s
no-orphans rule is satisfied by the placement.

**Two doors on `POST /v1/accord/provision-holder`, and why they stay two.**
`packaging/check_csd_routes.py` reports this screen and `AccordCeremony`
(CSD-069) as a duplicate mutation on the same route (the ratchet carries it).
They are not one card under two names. This screen provisions **one** holder
identity for a person joining an accord that already exists — they walk away
with a `key_id` and a next step that belongs to the node owner (`POST
/v1/accord/holder`, which this screen never calls). The ceremony provisions
**six** in sequence for three people founding a NEW mesh, registers each itself,
and continues into the envelope co-sign and the genesis assemble that this
screen has no business with. Same route, same YubiKey touch, different
mission, different done state; folding the ceremony's provision step into this
screen would put a six-key sequencer on a one-key form, and folding this screen
into the ceremony would make a person who only needs a seat walk a genesis
wizard that is offered only when no family exists. CSD-069 §3 says the same
from its side.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: "hardware_custody:{platform}"
    bind: {platform: yubikey_5_fips}
    use: display-only
    type: bool
    example: true
    renders: "the acknowledgement — this YubiKey is inserted and already FIPS-approved"
    tag: chk_provision_holder_fips
  - ceg: "key_boundary:{scope}"
    bind: {scope: removable_media}
    use: display-only
    type: string
    example: "/media/usb/mldsa"
    renders: "ML-DSA USB path — /media/usb/mldsa. The wrapped seed is written here and is unwrappable only by this YubiKey."
    tag: input_provision_holder_usb_path
  - ceg: x_private:holder_key_id
    use: display-only
    type: string
    example: "wa-holder-1c4f"
    renders: "Minted — wa-holder-1c4f. Now ask the node owner to register you. (0.5.x. On v62 there is no registration: a bundle seats the record, §1.1, and a holder is identified by the thumbprint of its composite key)"
    tag: provision_holder_success
  - ceg: x_private:provision_failure
    use: display-only
    type: string
    example: "No FIPS token found on this node."
    renders: "the plain-language reason: no key / wrong PIN / USB not writable / not FIPS-approved — localized from a key the view model chooses, never a rendered string"
    tag: provision_holder_error
  - ceg: x_private:custody_tier
    use: display-only
    type: string
    example: "portable_2fa"
    renders: "Custody class the node recorded: portable_2fa — the minted custody_attestation's tier, shown to the producer (CC 4.2.2.1: a producer claim, and now at least a visible one). On v62 this becomes hardware_class + pq_custody: removable_offline, read from the record's attestation_evidence and verified at seating (§1.1)"
    tag: txt_provision_holder_custody
  - ceg: x_private:token_present
    use: display-only
    type: bool
    example: true
    renders: "the YubiKey readiness banner — detected / FIPS / slot-9c key / PIN tries — from the node, beside the acknowledgement the person still makes"
    tag: yubikey_status_banner
```

**`key_boundary:{scope}` is RESERVED** — `substrate-self-report` at CC 3.4.3,
owned by `transport-delivery`/CIRISEdge — so `display-only` is enforced and
correct: the app *names a path* and the node reports where the boundary actually
landed. The field as drawn today is the operator's chosen path, not the node's
report of it, and that distinction is §2.2.

**`hardware_custody:{platform}`'s bind is not the registry's vocabulary.** The
registry's description enumerates `tpm / ios_secure_enclave / android_keystore /
software_fallback`; CC 4.2.2's hardware-class table enumerates
`YubiKey_5_FIPS` among five others. This flow provisions exactly the CC token
and exactly not a registry one, and the casings are incompatible under
CC 3.1.7 R3 (lowercase vocabulary, byte-exact, refuse never fold). The bind
above is the lowercased CC token; the CSD does not pretend the registry knows
it. **Ask: CIRISConstitution — reconcile `hardware_custody:{platform}`'s vocab
with the CC 4.2.2 table, or state that they are two different fields.**

```yaml csd:states
populated: {tag: provision_holder_success, renders: "the minted key_id (txt_provision_holder_key_id, with btn_provision_holder_copy), the custody class the node recorded, and the one next step — ask the node owner to register you"}
empty:     {tag: txt_provision_holder_start, renders: "the three steps, none of them done yet; the provision button is not enabled"}
loading:   {tag: spinner_provision_holder, renders: "the button carries the progress affordance; the token is waiting for a touch"}
error:     {tag: provision_holder_error, renders: "the plain-language reason, in the error tone"}
```

**All four are real, tagged and visually distinct** — success is a
`primaryContainer` block with a check glyph, failure an `errorContainer` text,
the start state the three-step intro, loading the spinner inside the disabled
button. That is the CSD/3 §2.2 requirement met with no `proposed:` tag on any
side.

### 2.1 Why the three steps are gates and not a form

`canProvision` is `fipsAcknowledged && usbPath.isNotBlank()`. Neither is a field
the node validates before the POST — they are the app refusing to send a request
that would fail in a way the person could not interpret. The flow is foolproof
by construction rather than by error message, which is the right shape for a
one-shot ceremony: a wrong PIN is recoverable, a seed wrapped to the wrong
volume is a holder who thinks they have a spare and does not.

### 2.2 What the screen shows after the mint, and what it cannot

* **What was written, and where.** On success the screen shows the minted
  `key_id` and a next step. It does not echo back the USB path the node actually
  wrote to, nor the artifact filenames. A holder who typed one path and had the
  node resolve another has no way to notice — and this is the artifact they will
  reach for during a halt. **Open, upstream** (§3: the node only logs them).
* **The custody class the node recorded** is shown:
  `txt_provision_holder_custody` reads `custody_tier` out of the
  response's `custody_attestation` (`firstStringField`, tolerant of where the
  signed envelope nests it — `CanonicalSupersedeTest.aStringFieldIsFoundWhereverTheEnvelopeNestsIt`),
  and says "not stated" when the attestation names none.
* **A copy affordance**: `btn_provision_holder_copy` puts the
  minted `key_id` on the clipboard and says so.
* **The plain-language errors are localized by the screen.** A view
  model cannot call the composable `localizedString`, so `error` is a key
  plus the node's detail (`ProvisionError`) and the screen localizes it — eleven
  `mobile.provision_holder_err_*` keys. `AccordCeremonyViewModel` and
  `AccordViewModel` still carry the literal form (CSD-067 §2.3, CSD-069 §2.2).

## 3. Contracts (who)

Verified against ciris-server 0.5.217. On v62 the accord routes here are
retired or re-implemented, with no 0.6 shape yet: 0.6: pending (CIRISServer,
O1). The rows stay because the client still calls them.

| value | endpoint | owner | state |
|---|---|---|---|
| provision a holder | `POST /v1/accord/provision-holder` | CIRISServer `src/accord_provision.rs:3697` | **live on 0.5.x**, loopback-only. Retired on v62; re-implemented as an on-device composite key + `holder_key_record_draft`; 0.6: pending (CIRISServer) |
| register the minted holder | `POST /v1/accord/holder` | CIRISServer `src/accord.rs:2600` | **retired on v62** (no registration; a bundle seats the record). On 0.5.x live, and this screen does not call it; it tells the person to ask the node owner |
| token presence before the POST | `GET /v1/accord/yubikey-status` | CIRISServer `src/accord_provision.rs:3770` | **live, called** — `ProvisionAccordHolderViewModel.refreshYubiKeyStatus` on entry and on demand, drawn by the same `YubiKeyStatusBanner` the ceremony uses (`yubikey_status_banner`). The FIPS-approval acknowledgement stays a claim (CC 4.2.2.1); whether a token is plugged in is the node's answer |
| the written path + artifact names | **missing** — the node knows both and only LOGS them | CIRISServer | blocks §2.2's first bullet |
| the recorded custody class | on the wire here (`custody_attestation` → `custody_tier`) | CIRISClient | **rendered** — `txt_provision_holder_custody`; §2.2's second bullet |
| the node's own judgment of a SEATED holder's hardware | `GET /v1/trust-root` → `roots[].verdict.holders_hardware[]` `{key_id, class, layer_a, layer_b, refusal}` | CIRISServer `src/trust_root_api.rs:415` (verdict passed through verbatim, `:76-79`); shape CIRISPersist v48.0.0 `federation/trust_root.rs:372` | **live on this node's own machine, called** by the Accord card's trust-root detail (`row_trust_root_holder_<root>_<holder>`, CSD-067 §3), not by this screen — and only for holders the root's charter counts, so it answers "did my seat's hardware pass" after seating, never "what did I just mint". **Remote reach** `blocked_by: CIRISServer#652` |

**The response shape is established in the client:** the node returns
`json!({ "key_id", "holder_record", "custody_attestation" })`
(`src/accord_provision.rs:487-491`, returned `:506`, module doc `:23`) and
`AccordProvisionResponse` models all three (`models/federation/Accord.kt:221-228`).
What is genuinely absent is the USB path and the artifact filenames: the node has
them and writes them only to `tracing::info!`
(`src/accord_provision.rs:481-482`, `:503-504`). The precedent for the fix is one
route over — `AdmitNodeResponse` carries `saved_to` (`Accord.kt:245-246`). **Ask:
add `saved_to` / `artifacts[]` to `provision-holder`'s body.**

**The custody class needs no upstream change.** It arrives on this response
inside `custody_attestation`, minted with `CUSTODY_TIER_PORTABLE_2FA`
(`src/accord_custody.rs:134-141`; constant at `ciris-verify-core
accord_custody_attestation.rs:70`, envelope key `custody_tier` at `:235`), and
the screen reads it from there (§2.2). What is missing upstream is the same
fact on `GET /v1/accord-holders` — and that is CSD-067's row, not a second one.

**Where this identity goes after it is minted, on v62:** seated by a bundle
(the genesis or a later bundle that names its record); the custody gate runs at
seating. **OPEN — persist / CIRISServer (O7):** whether `holders_hardware`
keeps the Layer A / Layer B naming below on v62.

**On 0.5.x** (CSD-067 §3.1): registered
by the owner (`POST /v1/accord/holder`), seated by the genesis ceremony
(CSD-069) or a re-mint, and only then counted by `trust_root_valid` — Layer A
(the evidence parses and names an accepted class) and Layer B (the attestation chain walk
against the vendor root this node pins, `yubico_root_der`) per holder. That verdict is what turns this
flow's CC 4.2.2.1 *producer claim* into something the node checked, and it is
the first place the person could see it. It is loopback-only like the rest of
this flow, which here costs nothing: provisioning already needs the YubiKey on
the node's own host.

**`/v1/accord/yubikey-status` answers "is a token plugged in at all"**, in the
banner above the checkbox. The acknowledgement stays — CC 4.2.2.1 means
FIPS-approval remains a claim.

## 4. Flow (how)

Real tags only. Every tag on this screen is real, which is why this is the one
flow in this area that can be written today.

Land on `ProvisionAccordHolder` (Everyone › Safety, derived).

```yaml
expect:
  state: empty
  visible: [txt_provision_holder_start, yubikey_status_banner,
            chk_provision_holder_fips, input_provision_holder_key_id,
            input_provision_holder_usb_path, input_provision_holder_pin,
            btn_provision_holder_usb_browse, btn_provision_holder_submit]
  absent:  [provision_holder_success, provision_holder_error, spinner_provision_holder]
```

Press `btn_provision_holder_submit` with nothing filled in — the button is
disabled, so nothing happens and no banner appears.

```yaml
expect:
  absent: [provision_holder_success, provision_holder_error]
```

Tick `chk_provision_holder_fips`, type a USB path, submit against a node with no
token:

```yaml
expect:
  state: error
  visible: [provision_holder_error]
  absent:  [provision_holder_success]
```

*Cannot yet assert:* the happy path — `provision_holder_success`,
`txt_provision_holder_key_id`, `txt_provision_holder_custody`,
`btn_provision_holder_copy`. It needs a physical FIPS YubiKey with a PIN and a
touch, which no platform runner has; §5 says so rather than mocking it.

**Stage.** Every tag is real and nothing in §3 is `unconfirmed`. The flow's
floor is `client: ">=0.5.224"`, and every tag it drives is a literal at
v0.5.224 (the custody row, copy button and token banner are not in it). It runs
on the matrix (§5), so the card is `testable`.

## 5. QA plan

**`testable`.** The flow (`testing/flows/csd-068-provision-accord-holder.yaml`, floor `>=0.5.224`) passed on all five legs in five-platform run 36775704425 (node v0.5.217) — Linux, macOS and Windows desktop, the Android emulator and the iOS simulator: the form with no banner, the disabled submit doing nothing, the FIPS acknowledgement and the two fields taking input, the no-token refusal landing on `provision_holder_error` and not on success, and back. What the run holds the client to: each of the three fields has an input sink; `txt_provision_holder_start` is drawn only in the start state, so a refused submit never shows error and empty at once; the submit's `testableClickable` carries the Button's `enabled`, so a `/click` on the disabled submit runs nothing (CIRISClient#69's shape; `testing/test_platform_automation_wiring.py` pins the mirror for every tag a flow clicks); and the disabled-submit step is `click_refused:`, which holds when the click is refused and fails if a handler runs — every platform answers a disabled control the same way (409, "is disabled").

**Platforms.** Desktop and Android in practice — the flow needs a USB path and a
physical token, and the iOS/browser corners have neither. The screen composes on
all five and the hop is derived on all five; only the ceremony itself is bounded.

**Acceptance — functional**
1. The provision action is unreachable until both gates are satisfied.
2. Failure states a reason a person can act on, not an HTTP status.
3. Success names the minted key and the single next step.
4. Success and failure are never the same rendering.

**Not tested here.**
* **The ceremony itself.** PIN entry, touch, AEAD wrap and the write to
  removable media all happen on the node against hardware. There is no
  `/touch-the-yubikey` endpoint and a mocked one would be testing the mock.
* **That the seed is unwrappable only by that token.** A cryptographic property
  of the node's wrap, asserted here nowhere.
* **Acceptance 3's second half** — the path and artifact names are not echoed
  (§2.2), so "the next step" is tested and "what you now hold" is not.
