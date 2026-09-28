# CSD-068 — Provision Accord Holder (the custody floor, in three steps)

**CSD**: CSD-068 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A would-be accord holder mints their portable-2FA HUMANITY_ACCORD identity
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

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
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
    renders: "Minted — wa-holder-1c4f. Now ask the node owner to register you."
    tag: provision_holder_success
  - ceg: x_private:provision_failure
    use: display-only
    type: string
    example: "No FIPS token found on this node."
    renders: "the plain-language reason: no key / wrong PIN / USB not writable / not FIPS-approved"
    tag: provision_holder_error
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
populated: {tag: provision_holder_success, renders: "the minted key_id and the one next step — ask the node owner to register you"}
empty:     {tag: "proposed:txt_provision_holder_start", renders: "the three steps, none of them done yet; the provision button is not enabled"}
loading:   {tag: "proposed:spinner_provision_holder", renders: "the button carries the progress affordance; the token is waiting for a touch"}
error:     {tag: provision_holder_error, renders: "the plain-language reason, in the error tone"}
```

**Populated and error are real, tagged and visually distinct** — success is a
`primaryContainer` block with a check glyph (`:122`), failure an
`errorContainer` text (`:180`). That is the CSD/3 §2.2 requirement met, and this
is the only surface in safety-commons where it is met without a `proposed:` tag
on either side.

### 2.1 Why the three steps are gates and not a form

`canProvision` is `fipsAcknowledged && usbPath.isNotBlank()`. Neither is a field
the node validates before the POST — they are the app refusing to send a request
that would fail in a way the person could not interpret. The flow is foolproof
by construction rather than by error message, which is the right shape for a
one-shot ceremony: a wrong PIN is recoverable, a seed wrapped to the wrong
volume is a holder who thinks they have a spare and does not.

### 2.2 What the screen does not show that it should

* **What was written, and where.** On success the screen shows the minted
  `key_id` and a next step. It does not echo back the USB path the node actually
  wrote to, nor the artifact filenames. A holder who typed one path and had the
  node resolve another has no way to notice — and this is the artifact they will
  reach for during a halt.
* **The custody class the node recorded.** `provisionAccordHolder` returns the
  minted key; `AccordHolderDto` has no custody field either (CSD-067 §3). So the
  claim CC 4.2.2.1 warns is only a producer claim is not even displayed to the
  producer.
* **A copy affordance.** The minted `key_id` must be handed to the node owner
  out of band and there is no copy button on it; the Accord screen has three
  (`btn_coscrub_export_copy` and friends) for a less consequential artifact.

## 3. Contracts (who)

Verified against ciris-server `origin/main` at 0.5.217 (2026-09-25).

| value | endpoint | owner | state |
|---|---|---|---|
| provision a holder | `POST /v1/accord/provision-holder` | CIRISServer `src/accord_provision.rs:3697` | **live**, loopback-only |
| register the minted holder | `POST /v1/accord/holder` | CIRISServer `src/accord.rs:2600` | **live** — but this screen does not call it; it tells the person to ask the node owner |
| token presence before the POST | `GET /v1/accord/yubikey-status` | CIRISServer `src/accord_provision.rs:3770` | **live and unused here** — the ceremony screen calls it, this one does not |
| the written path + artifact names | **missing** — the node knows both and only LOGS them | CIRISServer | blocks §2.2's first bullet |
| the recorded custody class | **on the wire here, and discarded by the client** | CIRISClient | §2.2's second bullet is ours, not upstream |
| the node's own judgment of a SEATED holder's hardware | `GET /v1/trust-root` → `roots[].verdict.holders_hardware[]` `{key_id, class, layer_a, layer_b, refusal}` | CIRISServer `src/trust_root_api.rs:415` (verdict passed through verbatim, `:76-79`); shape CIRISPersist v48.0.0 `federation/trust_root.rs:372` | **live on this node's own machine, not called** — and only for holders the root's charter counts, so it answers "did my seat's hardware pass" after seating, never "what did I just mint". **Remote reach** `blocked_by: CIRISServer#652` |

**The fourth row said `unconfirmed` and its premise was false.** The response
shape IS established in the client: the node returns
`json!({ "key_id", "holder_record", "custody_attestation" })`
(`src/accord_provision.rs:487-491`, returned `:506`, module doc `:23`) and
`AccordProvisionResponse` models all three (`models/federation/Accord.kt:221-228`).
What is genuinely absent is the USB path and the artifact filenames: the node has
them and writes them only to `tracing::info!`
(`src/accord_provision.rs:481-482`, `:503-504`). The precedent for the fix is one
route over — `AdmitNodeResponse` carries `saved_to` (`Accord.kt:245-246`). **Ask:
add `saved_to` / `artifacts[]` to `provision-holder`'s body.**

**The fifth row was filed against the wrong repo.** The custody class arrives on
this very response inside `custody_attestation`, minted with
`CUSTODY_TIER_PORTABLE_2FA` (`src/accord_custody.rs:134-141`; constant at
`ciris-verify-core accord_custody_attestation.rs:70`, envelope key `custody_tier`
at `:235`). The client parses it as an opaque `JsonElement` it never inspects
(`Accord.kt:227`) and `CIRISApiClient.kt:3877` only logs `custody != null`. So
this screen can render the class today with no upstream change. What is missing
upstream is the same fact on `GET /v1/accord-holders` — and that is CSD-067's
row, not a second one.

**Where this identity goes after it is minted** (CSD-067 §3.1): registered
by the owner (`POST /v1/accord/holder`), seated by the genesis ceremony
(CSD-069) or a re-mint, and only then counted by `trust_root_valid` — Layer A
(the evidence parses and names an accepted class) and Layer B (the attestation chain walk
against the vendor root this node pins, `yubico_root_der`) per holder. That verdict is what turns this
flow's CC 4.2.2.1 *producer claim* into something the node checked, and it is
the first place the person could see it. It is loopback-only like the rest of
this flow, which here costs nothing: provisioning already needs the YubiKey on
the node's own host.

**`/v1/accord/yubikey-status` is the cheapest fix on this screen.**
`AccordCeremonyViewModel` already calls it (`getYubiKeyStatus`); this flow asks
the person to *acknowledge* a token is inserted when the node can be asked. The
acknowledgement should stay — CC 4.2.2.1 means FIPS-approval remains a claim —
but "is a token plugged in at all" does not have to be one.

## 4. Flow (how)

Real tags only. Every tag on this screen is real, which is why this is the one
flow in this area that can be written today.

Land on `ProvisionAccordHolder` (Everyone › Safety, derived).

```yaml
expect:
  visible: [chk_provision_holder_fips, input_provision_holder_key_id,
            input_provision_holder_usb_path, input_provision_holder_pin,
            btn_provision_holder_usb_browse, btn_provision_holder_submit]
  absent:  [provision_holder_success, provision_holder_error]
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

*Cannot yet assert:* the happy path. It needs a physical FIPS YubiKey with a PIN
and a touch, which no platform runner has; §5 says so rather than mocking it.

## 5. QA plan

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
