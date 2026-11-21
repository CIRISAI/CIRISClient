# CSD-069 — The genesis ceremony (v62): one sitting per holder, offline

**CSD**: CSD-069 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1; rewritten for v62 on 2026-11-21
**Normative source**: CIRISConstitution `formal/ceremony/CEREMONY.md` at rc22-pre2 `bab36531`, steps 1–5 (§3), with steps 8–10 as "after the genesis"
**Tool**: `genesis-ceremony` (CIRISServer `tools/genesis-ceremony`, branch `feat/genesis-ceremony-v62`, commit `0dabc27e`, `--features pkcs11`)
**Flow**: none yet (§5)

```yaml csd:stage
stage: building
owner: CIRISClient
```

**Code gaps.** Where this card and the client code disagree today, each proven against the tree by `packaging/check_csd_code_gaps.py`. Closing one means deleting its entry in the same commit.

```yaml csd:code_gaps
- id: family-envelope-ceremony
  card: 'on v62 the genesis is the offline tool this card drives; there is no family envelope, co-signed family or assembled genesis'
  code: 'the client still calls genesis/envelope, genesis/assemble and family/cosign, for edition-1 nodes only; a v62 or unknown node gets the offline-ceremony note instead of the screen (AccordEditionGate)'
  where: client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/api/CIRISApiClient.kt
  open_while: 'v1/accord/(genesis/(envelope|assemble)|family/cosign)'
  blocked_on: 'dropping 0.5.x (edition 1) support'
```

**O2 is decided.** This card hosts the v62 ceremony: "the client drives the v62 genesis ceremony" (Eric,
2026-11-21, relayed by the CIRISServer session). Until this rewrite the card described the 0.5.x
family-envelope ceremony. That screen still exists for edition-1 nodes, and §3.2 keeps its routes.

## 0. What changed, and why

This edition begins a new mesh (CEREMONY.md intro; CC 2.6.1.12). Nothing of the earlier mesh carries over: its roots,
bundles, holders, canonical records and family rows give no standing here. The ceremony row is the first
`ceg-genesis-bundle/2`, version 1, no `prev`, and **every holder signs it** (CC 5.3.4). After the genesis, the
roster's acts count at a strict majority of the standing roster, N read from the bundle in force.

The v62 ceremony has **no server routes**. It is offline and edge-free. Every statement is drafted by persist's
pure builders, signed by one composite signature (ML-DSA-65 + Ed25519, CC 2.6.1.7) and self-checked, all inside the
`genesis-ceremony` binary. The client is a screen over that binary. It runs each step as a subprocess over a
ceremony directory and shows what will be signed. It relays the person's answers and records everything. It never
signs, never holds a key, and never re-implements any cryptography.

## 1. Mission (why)

**The holders of a new mesh can perform its genesis from one screen, each in one sitting, and see exactly what each
signature is over before they give it, with a record of every step that travels with the ceremony.** Serves
**Integrity** and **Core Identity**. The halt is fireable from the first moment after the genesis (CEREMONY.md §3,
"Step 4 is published only once step 3 exists for every holder"; model P8, TLA+ `Inv_HaltAvailable`).

## 2. Surface (what)

```yaml csd:surface
surface: null                    # declared in EpistemicNav, placed in no circle
screen: AccordCeremony           # `object AccordCeremony : Screen()` — CIRISApp.kt:5807
flow_only: true                  # no sidebar row reaches it; the checker asserts that
entry: AccordScreen's `[+ New]` menu, enabled only when no accord family exists; and ConstitutionalScreen's `btn_open_accord_ceremony`
```

The `csd:surface` block above is the **edition-1** screen (`Screen.AccordCeremony`), kept because it still calls the
0.5.x routes in §3.2. **The v62 ceremony is not a `Screen`.** It is `GenesisCeremonyScreen`, drawn over the screen
that opened it (`screen_genesis_ceremony`). While it is open, `/screen` reports the screen underneath, so a flow waits
on `screen_genesis_ceremony`. It is offered only when the build carries the ceremony tool (§2.3), and then from:

* the Accord screen, on every edition: `btn_accord_open_genesis_ceremony`, with a one-line note that the ceremony is
  offline and uses no node;
* Login and Setup: `btn_genesis_ceremony_corner`, because a fresh, unclaimed desktop cannot reach the Accord screen.

A build without the tool, which includes every release build, shows none of these.

### 2.1 The steps, and what the screen runs

| CEREMONY.md | Who | Tool step | What the person does on this screen |
|---|---|---|---|
| (before 1) | coordinator | `init --dir D --t0 T` | picks the ceremony directory (a USB stick) and the ceremony instant |
| 1, first half | each holder, **no PIN, no touch** | `holder-prep --dir D --name N --mldsa-seed S [--keygen] --intermediate <PIV Attestation B 1> --intermediate <Attestation Intermediate B 1> --backup-mldsa-seed B [--backup-keygen]` | mounts BOTH sticks first (the holder's medium and the backup medium); swaps the YubiKeys when the tool asks. The tool reads the token's public key, attestation chain and serial, generates the seeds on the media, and records the recovery PUBLIC key |
| 2 | the seat's operator | `seat --dir D --name canonical-1 --seat-seed S --transport T --dial tcp:canonical-1.ciris-services-1.ai:4242 --dial tcp:108.61.242.236:4242 --dial tcp:2001:19f0:5c00:4b0c:5400:5ff:fed2:3667:4242` | the dial hints are signed by the seat, most preferred first, at least one `tcp` (CC 2.6.8) |
| (fixes 4) | coordinator | `terms --dir D` | the bundle is fixed from public data alone; every holder confirms the summary (`code_genesis_bundle`) |
| 1, 4, 3 | each holder, **one PIN, three touches** | `holder-sign --dir D --name N --mldsa-seed S --sealed-dir H [--module M]` | one PIN, one `yes` to all three signatures (the key:record, the voice on the bundle, the pre-signed halt), then ">>> Touch the YubiKey now (n of 3) <<<" three times (`state_genesis_touch`). The halt is written ONLY to `H`, a folder on the holder's own medium |
| 3 → 4 | coordinator, all three sticks mounted | `check --dir D --halts H1 --halts H2 --halts H3` | refuses unless every holder has exactly one halt against this bundle |
| 5 (gate) | coordinator | `final-gate --dir D --halts H1 --halts H2 --halts H3` | the offer through a throwaway node's real import door, then the Constitution's model check; the verdict is `txt_genesis_verdict` |

The argv are built in one place (`ceremony/CeremonyCommands.kt`) and pinned by `CeremonyCommandsTest` against the tool's
`--help` at `0dabc27e`. The tool's dry-run and mock flags (`--yes`, `--mock-*`) are refused by the ceremony build and
are never offered here.

**After the genesis, not in this screen yet** (CEREMONY.md §3):
- step 8: each owner's acceptance edge;
- step 9: each canonical seat's owner-binding by its named operator's person key, at federation scope, with
  `infra:attest`, standing before the seat serves (`seat-owner`);
- step 10: each community's founding record, the canonical room's among them.

Step 5's publication of the object id is the steward's, and step 11, the first drill within 7 days, is CSD-067's.

### 2.2 What the screen promises

- **The PIN never passes through anything but the binary's own prompt.**
  - The tool reads it from its TTY (rpassword), and the screen runs the tool under a pseudo-terminal (`script -qefc`).
  - The person types the PIN into a masked field (`input_genesis_pin`, reported to automation with an empty value).
    It is written to the terminal only after the tool's exact prompt `YubiKey PIV PIN (slot NN): ` has appeared, and
    then wiped.
  - It is never an argument, an environment variable, a log line or a stored value.
- **Only expected answers reach the tool.** The answer field relays `yes`, or the token-swap acknowledgement, only
  when the tool asks. Anything else, such as a YubiKey OTP burst or a PIN typed in the wrong field, is refused before it
  reaches the tool (`note_genesis_input_rejected`). It is recorded as `<rejected input>`, never verbatim.
- **The tool is the one rehearsed.** The binary and Yubico's two intermediate certificates are bundled at build time.
  Their sha256 is baked into the build and checked before every run, and a mismatch is refused by name. The start
  screen shows the tool's commit and sha256 (`code_genesis_tool_commit`, `code_genesis_tool_sha256`).
- **Every step is recorded,** in `kmp_app.log` and in an append-only `ceremony-log.jsonl` at the root of the
  ceremony directory, so the record travels with the ceremony. Each record holds:
  - the argv;
  - start and end time, and exit code;
  - the tool's full output;
  - every prompt, and every answer relayed (the PIN only as `<pin entered>`);
  - the serials and identity URNs it printed;
  - the sha256 of every file written, and a listing of the directory after the step.

  The tool ignores unknown files in the directory. A sealed-halt folder is recorded by path and file hash only, and
  nothing is ever written into it.
- **Refusals are said in words** (`note_genesis_refusal`, `code_genesis_error`), using the tool's own text and exit
  code. The ones this screen names:
  - two tokens with one serial (CC 4.2.3, one device per holder by the leaf serial);
  - a recovery key shared between holders, or equal to a signing key (`recovery_commitment_shared`,
    `recovery_commitment_is_holder_key`);
  - a seat with no `tcp` hint;
  - a holder's halt missing;
  - a YubiKey OTP typed at a prompt.
- **Where the ceremony stands is read from the directory,** not remembered (`card_genesis_status`): `ceremony.json`,
  `holders/<name>/`, `seats/<name>/`, `terms.json`, `voices/<name>.cose`. A restart picks up where it was.

### 2.3 Packaging

The bundled binary is for the genesis build only. A build carries it only when `-PceremonyBinary` is given, and
release builds never give it. It is about 54 MB, which no PyPI wheel has room for. The released form is server 0.6's
in-process ceremony (`ciris-server ceremony <step>`), reached through the same `CeremonyRunner` interface on desktop
and mobile. On mobile, the Ed25519 half is signed by the existing `YubiKeyPivSigner` over NFC or USB-C. It cannot be
done on 0.5.x, which is persist v61. Linux only for now: macOS needs other `script` arguments, and Windows has no
`script`.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:ceremony_tool
    use: display-only
    type: string
    example: "0dabc27e · 7c8d1fb1567bd656d1a49befa9f35c2925a66ae04158b421a20cc9cbdf7dd507"
    renders: "the bundled tool's commit and sha256, verified before every run; a build without the tool says so and runs nothing"
    tag: code_genesis_tool_commit
  - ceg: x_private:ceremony_status
    use: display-only
    type: string
    example: "terms fixed; A1 signed; B1, C1 to sign"
    renders: "where the ceremony stands, read from the ceremony directory's files, never remembered"
    tag: txt_genesis_status
  - ceg: x_private:ceremony_bundle
    use: display-only
    type: string
    example: "bundle 9f1c… (3 holders, 1 seat)"
    renders: "the bundle every holder signs, fixed by terms from public data; shown to every holder before they sign"
    tag: code_genesis_bundle
  - ceg: x_private:ceremony_argv
    use: display-only
    type: string
    example: "holder-sign --dir /media/ceremony --name A1 --mldsa-seed /media/A1/mldsa65.seed --sealed-dir /media/A1/sealed"
    renders: "the exact command line the step runs; never a PIN"
    tag: code_genesis_argv
  - ceg: x_private:ceremony_touch
    use: display-only
    type: string
    example: "Touch the YubiKey now (2 of 3)"
    renders: "the tool's touch request, as a large banner with n of 3"
    tag: state_genesis_touch
```

```yaml csd:states
populated: {tag: txt_genesis_verdict, renders: "the final gate's verdict, in the tool's words: the offer imported through the real door and the model check passed, or which check refused"}
empty:     {tag: txt_genesis_status, renders: "no ceremony directory yet, or one before init: what to prepare (the sticks, the tokens, the seat's seeds) and the first step"}
loading:   {tag: txt_genesis_tool_checking, renders: "the bundled tool is being extracted and its sha256 checked; nothing can run until it is"}
error:     {tag: note_genesis_refusal, renders: "the step refused: the tool's own text and exit code, and the named refusal when it is one this card lists"}
```

## 3. Contracts (who)

### 3.1 v62: the tool, not routes

| value | where | owner | state |
|---|---|---|---|
| every ceremony step | the `genesis-ceremony` binary, run as a subprocess under a PTY | CIRISServer `tools/genesis-ceremony` (`0dabc27e`) | **live in the genesis build**; argv pinned by `CeremonyCommandsTest` |
| the chain's intermediates | bundled Yubico PIV Attestation B 1 and Attestation Intermediate B 1 | Yubico (sha256 `7e2cdd32…`, `e3f1b94e…`) | **live in the genesis build**; required by the bundled tool, optional from its next build |
| the released form | `ciris-server ceremony <step>`, in process | CIRISServer 0.6 | **0.6: pending (CIRISServer)** |

The screen calls no HTTP route (`check_csd_routes.py`: none added).

### 3.2 Edition 1 (0.5.x): the earlier screen, still served

These are the routes the edition-1 screen (`Screen.AccordCeremony`) still calls. They are offered only when the node
reports no `edition` (AccordEditionGate). **v62: every row except `yubikey-status` and `/v1/trust-root*` is retired.**

| value | endpoint | owner | state |
|---|---|---|---|
| is a token present | `GET /v1/accord/yubikey-status` | CIRISServer `src/accord_provision.rs:3770` | **live**; v62: kept (host probe) |
| provision one of six | `POST /v1/accord/provision-holder` | `src/accord_provision.rs:3697` | **v62:** re-implemented as an on-device composite key + `holder_key_record_draft`; **0.6: pending (CIRISServer)**. **live**, loopback-only |
| register the minted key | `POST /v1/accord/holder` | `src/accord.rs:2600` | **v62: retired** — a holder is seated only by a bundle that names its record; there is no registration. **live** |
| the family envelope | `POST /v1/accord/genesis/envelope` | `src/accord.rs:2621` | **v62: retired.** **live** |
| a primary's co-sign | `POST /v1/accord/family/cosign` | `src/accord_provision.rs:3701` | **v62: retired.** **live** |
| assemble the genesis | `POST /v1/accord/genesis/assemble` | `src/accord.rs:2625` | **v62: retired.** The ceremony's output IS the portable genesis (statements + object id), imported by `Engine::put_genesis_bundle(statements, anchor)` (P-CER header). **live** |
| did this node end up rooted | `GET /v1/trust-root` → `posture` (`entrenched` / `pre_genesis` + the missing `leg`) and `banner` | `src/trust_root_api.rs:415`, `:55-66` | **v62:** kept and re-implemented; shape **0.6: pending (CIRISServer)** (CSD-105). **live on this node's own machine, called** from the Accord card's trust-root detail (`txt_trust_posture_state`, CSD-067 §3), not from this ceremony; **remote reach** `blocked_by: CIRISServer#652` |
| adopt the ceremony's output on another node | `POST /v1/trust-root/import` | `src/trust_root_api.rs:416` | **v62:** kept; the import is statements + an anchor; shape **0.6: pending (CIRISServer)** (CSD-105). **live, and it does NOT take this ceremony's artifact** — see below |
| change the family after genesis — build the quorum envelope | `POST /v1/accord/family/change/envelope` | `src/accord.rs:2637` | **v62: retired** with the family rows. live, **not called, and cannot succeed for this family**: it hardcodes `humanity-accord` (`:2151`) while supersede refuses that id — CIRISServer#682 |
| replace the family | `POST /v1/accord/family/supersede` | `src/accord.rs:2641` | **v62: retired** with the family rows; a seat changes by a later bundle. live, **not called**: always refuses `humanity-accord` by design (`:2306-2315`); its refusal names a route that does not exist — CIRISServer#682. The accord changes seats by re-running the ceremony and re-minting the seed (CSD-067 §3.1). `GET /v1/accord/family/history` (`:2645`) is read on the Accord card |


## 4. Flow (how)

Real tags only. Reached by `btn_genesis_ceremony_corner` (Login) or `btn_accord_open_genesis_ceremony` (Accord), in
a build that carries the tool:

```yaml
expect:
  visible: [screen_genesis_ceremony, card_genesis_tool, txt_genesis_tool_ready, code_genesis_tool_commit,
            code_genesis_tool_sha256, input_genesis_dir]
```

A step runs only when its fields pass the preflight (`note_genesis_preflight`): the directory exists; for
holder-prep both media are mounted; for holder-sign the sealed folder's parent exists on the holder's medium; for
check and final-gate every listed sealed folder exists.

## 5. QA plan

- **Unit, desktopTest** (30 tests). They cover:
  - the argv for every step, holder-prep's two intermediates in order;
  - the status reader over a fixture directory;
  - the touch-banner and prompt parsers, and the refusal mapper;
  - the PIN held back until the tool's prompt and absent from both logs, even when echoed;
  - rejected input never logged verbatim, and the log appended, never truncated;
  - a wrong-hash tool or certificate refused, with nothing left to run;
  - the entry gate (shown whenever the tool is bundled, hidden otherwise);
  - a smoke run of the real bundled tool's `--help`.

  Each test went red on a planted defect.
- **Not tested here:**
  - a real signing step: that is the rehearsal on real tokens, run by the holders;
  - macOS and Windows;
  - a flow under `testing/flows/`: the ceremony needs real tokens, and a dry run would need the tool's `dry-run`
    build, which this screen never offers.
- **Translations:** the screen's strings are English only. They are translated before any push.
