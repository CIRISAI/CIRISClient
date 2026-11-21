# CSD-069 — Accord Genesis Ceremony (six keys, three humans, one artifact)

**CSD**: CSD-069 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: `testing/flows/drafts/csd-069-accord-ceremony.yaml` (floor `>=0.5.224`)

```yaml csd:stage
stage: building
owner: CIRISClient
```

**Code gaps.** Where this card and the client code disagree today, each proven against the tree by `packaging/check_csd_code_gaps.py`. Closing one means deleting its entry in the same commit.

```yaml csd:code_gaps
- id: family-envelope-ceremony
  card: 'obsolete on v62: no family envelope, no co-signed family, no assembled genesis'
  code: 'the client calls genesis/envelope, genesis/assemble and family/cosign'
  where: client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/api/CIRISApiClient.kt
  open_while: 'v1/accord/(genesis/(envelope|assemble)|family/cosign)'
  blocked_on: 'O1, O2'
```

> **Obsolete on v62.** This card describes the pre-0.5.220 six-key
> family-envelope ceremony (`genesis/envelope`, `family/cosign`,
> `genesis/assemble`, `quorum:2/3`). On v62 every route and most claims below
> are retired; the corrections are inline, marked **v62**. Whether this card is
> rewritten as the v62 ceremony card (CEREMONY §3) or its number is retired with
> the ceremony living on CSD-067's `FinalGenesisSheet` is **OPEN (O2, Eric /
> CIRISClient)**. The stage and number are unchanged until that is decided.
> Audit: the CIRISServer session's trust-root edit list (2026-11-21), read
> against CIRISClient `4806bd5a`.

## 0. v62 (re-genesis) status

**Total re-genesis.** This edition begins a new mesh. Nothing of the earlier
mesh carries: its roots, bundles (v1–v4), holders A1/B1/C1, canonical records
and family rows give no standing here. A node upgrading to it deletes its
earlier store and starts before its genesis, and an earlier-form bundle offered
to it is refused as `earlier_form_object`. (CEREMONY intro and §4; CC 2.6.1.12;
persist `src/store/edition_upgrade.rs:257` `delete_earlier_store`;
`edition2/genesis.rs:1745`.)

**Counts.** The founding genesis is signed by **every holder (a first bundle,
version 1, no prev)** (constants.tsv `charter_change_threshold` row 24; CC
5.3.4; CEREMONY step 4). Every later act counts at **a strict majority of the
standing accord roster (2·yes > N; N is read from the holders of the bundle in
force — 3 at the seed, so 2 today)** (constants.tsv `root_conferral_threshold`
row 8, `accord_holder_count` row 6; CC 4.2.6). The two are never conflated, and
N and m are shown as numbers the node reports, never as a literal.

**What a v62 ceremony card would hold** (if O2 puts it here): the CEREMONY §3
steps 1–5 grid (each holder's own `key:record`; each seat's own record and
binding with signed `dial_hints`, offline; one sealed halt per holder; one voice
per holder over the bundle, `sub` = the bundle's `family_key_id`; the anchor);
halt coverage before publish; the recovery-key rules; the anchor (thumbprints,
then the object id); the draft checks (`check_key_record`,
`check_seat_binding`, `check_halt`, `check_genesis_offer`); and the final gate
(`import_offer_into_throwaway`). CSD-067 §4 *The v62 genesis ceremony* spells
these out with sources.

**OPEN, with who decides:** O1 every v62 route shape (CIRISServer 0.6 ceremony
crate); O2 which card hosts the v62 ceremony (Eric / CIRISClient); O9 which
screen mints a holder's recovery key (CIRISClient / CIRISServer).

## 1. Mission (why)

**Three humans standing up a NEW mesh can walk one guided sequence — six keys
provisioned and registered, three primaries co-signing the family envelope, the
genesis assembled — and come out holding an artifact they know they have to
keep.** Serves **Core Identity**: this is where a mesh's kill switch comes from,
and a mesh whose genesis was fumbled has a constitutional layer nobody can
operate.

**v62:** N holders (3 at the seed) each sign their own `key:record`; each seat
signs its own record and binding offline; each holder seals a halt; every holder
signs the bundle voice; the anchor is published. Spares are recovery keys,
committed and not provisioned as seats (CEREMONY §2, §3).

CC 4.2.3 says what is being made: the accord-holder triple **structurally IS an
entrenched `family`** with `consensus_protocol: "quorum:2/3"` and
`consensus_protocol_entrenched: true`. Entrenched means the ceremony's output is
not correctable in the ordinary way later — replacement runs an out-of-band
process. A wizard whose steps can be taken out of order, or whose done-state can
be reached without the artifact being saved, produces a mesh that is wrong
permanently.

**v62:** the genesis is the first `ceg-genesis-bundle/2`, version 1, no prev,
signed by every holder; later roster changes are later bundles at a strict
majority of the standing roster; a lost key is recovered by the holder's
recovery key alone. There is no family row and no `quorum:2/3` (CC 4.2.6,
5.3.4; constants rows 8, 24).

## 2. Surface (what)

```yaml csd:surface
surface: null                    # declared in EpistemicNav, placed in no circle
screen: AccordCeremony           # `object AccordCeremony : Screen()` — CIRISApp.kt:5807
flow_only: true                  # no sidebar row reaches it; the checker asserts that
entry: AccordScreen's `[+ New]` menu, enabled only when no accord family exists; and ConstitutionalScreen's `btn_open_accord_ceremony`
```

**The ceremony is the one card in safety-commons the checker cannot resolve,
and it is a NavSurface that was never placed** — unlike the pre-shell screens,
which never were surfaces. `accord-ceremony` is declared with an id, a label and
a `labelKey` (`EpistemicNav.kt:236`), routed in `CIRISApp.kt:6027`, listed in
`FLOW_ONLY_SURFACES` (`EpistemicNav.kt:368`) and in the gate's own `FLOW_ONLY`
set (`testing/gate/screen_atlas.py:54`), and placed in **no** circle and **no**
instrument. `nav_map.build()` therefore has no entry for
`Screen.AccordCeremony`.

So this block declares `flow_only: true`, and the checker turns that into three
assertions rather than a skip: `Screen.AccordCeremony` must be declared in
`CIRISApp.kt`, it must **not** be sidebar-reachable, and `entry:` must say how a
person arrives, since no hop can. A flow-only screen that quietly gained a
placement fails with *"is sidebar-reachable … so it is not flow_only"*,
which is the right direction for this defect to break in.

**Not `screen_class:`.** `screen_class` is not a key `check_csd_v3.py` reads:
it would leave `screen` unset and **silently disable both reachability
checks**, the hole through which a CSD could claim Nodes is `Screen.Telemetry`
or a typo like `screeen:` could pass. An unknown key is a failure, not a no-op.

**One stale comment to fix.** `screen_atlas.py:52` says *"nav_map
still hands it a one-hop chain, so it read as a screen the atlas kept failing to
reach"*. It does not — `build()` only emits screens whose surface is in
`placements` or an `Instrument`, and `AccordCeremony` is in neither, so
`nav_map.build()` omits it outright. The entry in `FLOW_ONLY` is still correct;
its reason is out of date.

**It is reached by two clicks from inside other screens:**

* `AccordScreen`'s `[+ New]` menu → `onStartCeremony` (`CIRISApp.kt:4209`),
  offered only when no accord family exists yet;
* `ConstitutionalScreen`'s `btn_open_accord_ceremony`
  (`ConstitutionalScreen.kt:288`) → `CIRISApp.kt:4760`.

Both parents are Everyone › Safety, so the ceremony is *in the right place*; it
simply has no row. Whether that should change is §2.3.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: "accord:family"
    use: display-only
    type: string
    example: "humanity-accord"
    renders: "the done card's family line, the id read from the assembled genesis the node returned (family_key_id). v62: the accord is the bundle's lineage, not a family row; m is a strict majority of N, read from the roster, and moves with N"
    tag: txt_ceremony_family
  - ceg: "accord:holders"
    use: display-only
    type: "list[string]"
    example: ["wa-holder-1c4f", "wa-holder-9b02", "wa-holder-33da"]
    renders: "six slots — A1 A2 B1 B2 C1 C2 (0.5.x). v62: one slot per holder — its signing key (seated) and its recovery key (committed, not seated; distinct from every other holder's, never a signing key; CC 4.2.6 line 343, R-85d3e18)"
    tag: "row_ceremony_slot_${slot.label}"
  - ceg: "hardware_custody:{platform}"
    bind: {platform: yubikey_5_fips}
    use: display-only
    type: string
    example: "/media/usb-a1/mldsa"
    renders: "this slot's ML-DSA USB path — one volume per key, six volumes"
    tag: input_ceremony_usb_path
  - ceg: x_private:genesis_artifact
    use: display-only
    type: string
    example: "{\"family_key_id\":\"humanity-accord\",\"members\":[…]}"
    renders: "the assembled genesis, in mono (0.5.x). v62: the artifact is the set of statements (records, bindings, voices, plus the sealed halts kept until publication), identified by the bundle's object id; v62.0 has no bake (v62.1 forward work)"
    tag: accord_ceremony_genesis_json
  - ceg: x_private:ceremony_phase
    use: display-only
    type: "enum[intro,provision,cosign,done]"
    example: "cosign"
    renders: "which of the four phases the trio is in — one line above every phase, so a failed step (error banner, phase unchanged) and an unreached step (a later phase) are different renderings"
    tag: txt_ceremony_phase
```

`accord:*` is **reserved**, `accord_holder-only` at CC 3.4.1, so `display-only`
is enforced here as it is on CSD-067: this screen POSTs to loopback endpoints
and the re-inserted YubiKey signs. The app holds no keys through all six
provisions and all three co-signs.

```yaml csd:states
populated: {tag: accord_ceremony_success, renders: "DONE — the family (txt_ceremony_family), the genesis JSON, and the two ways to keep it"}
empty:     {tag: txt_ceremony_intro, renders: "what you need before you start: 6 FIPS YubiKeys, 6 USB keys, 3 humans in one room — and what you are about to entrench"}
loading:   {tag: spinner_ceremony_step, renders: "the current step's button carries the progress affordance; the token is waiting for a touch"}
error:     {tag: accord_ceremony_error, renders: "which step failed and why, in the error tone — and the phase line does NOT advance"}
```

**The four states are real and tagged.** The error banner is tagged
(`MessageCard(…, "accord_ceremony_error")`, `AccordCeremonyScreen.kt`), and
`txt_ceremony_phase` says which step the wizard is on, so a failed provision of
key B2 and an un-started one are different pixels: "failed" is *error banner +
phase unchanged* and "not reached" is *a later phase*, which a flow can tell
apart.

### 2.1 What the ceremony gets right

* **The slots are named and tagged.** `row_ceremony_slot_$label` gives A1…C2
  their own elements, so "which key are we on" is assertable per slot.
* **The artifact is on screen and tagged.** `accord_ceremony_genesis_json` puts
  the bake artifact in mono where the operator can see it, and
  `accord_ceremony_success` marks the done state.
* **Each phase has one button.** `btn_ceremony_provision` /
  `btn_ceremony_cosign` / `btn_ceremony_assemble` — three verbs, three phases,
  no way to assemble before cosigning.

### 2.2 Keeping the artifact, and what the intro states

* **The artifact can be kept.** `btn_ceremony_genesis_copy`
  puts the genesis on the clipboard; `btn_ceremony_genesis_save` writes it as
  `humanity_accord_genesis.json` to a chosen folder (`writeTextFile`, desktop);
  each says so in the notice banner. The done card also says, in words
  (`txt_ceremony_not_seed`), that this JSON is the entrenched family and NOT the
  portable seed other nodes adopt — §3's finding, put where the person is.
* **The correlated-failure geometry is stated.** `txt_ceremony_diversity`
  on the intro: three people who do not share a household or an employer,
  because a strict majority of the holders from one address is a quorum one
  door can reach — CC 4.2.3's *"the mitigant is diversifying the holder set"*,
  at the one moment it can land. CC 4.2.3 also requires one device per holder,
  judged by the leaf serial (`holder_device_shared`): three distinct token
  serials. The same f9 attestation certificate over distinct device serials is
  valid.
* **`consensus_protocol_entrenched` is shown** (0.5.x). `txt_ceremony_entrenched`
  on the intro. **v62:** there is no family `quorum:2/3`; m is a strict majority
  of N, read from the roster, and moves with N (constants row 8). Replacing a
  seat is a later bundle at a strict majority of the standing roster.
* **Open:** `AccordCeremonyViewModel` composes its notices and plain-language
  errors as English literals (`"Provisioning A1… TOUCH your YubiKey…"`, the
  ten `plainLanguageError` sentences). The fix is the key-plus-detail shape
  `ProvisionAccordHolderViewModel` uses (CSD-068 §2.2).

### 2.3 Should it be placed?

**No, and the reason is the reason it is offered conditionally.** The ceremony
is enabled from the Accord screen *only when no accord family exists yet*
(`AccordScreen.kt:241` gates the sibling duty-conferral action on the same
predicate). A permanent row in Everyone › Safety would be a row that, on every
node that has already federated, opens a wizard for a thing that cannot be done
— which is the class of defect `nav_map`'s no-orphans rule exists to stop, in
the other direction.

**What should change is the CSD standard's reach, not the nav tree.** CSD/3 §2.0
assumes every surface has a derivable hop. A guided one-time ceremony reached
from a parent screen has a real, testable entry — two clicks from a placed
surface — and no way to say so. CSD-080 §5 asks for the same thing from the
other direction (a pre-shell screen with no surface at all), and the two asks
have one answer: **CIRISClient — give `csd:surface` a machine-readable `via:`
form (`via: {surface: accord, tag: mi_new_ceremony}`) so the runner can walk to
a flow-only screen through its parent instead of the flow encoding the hop**,
which `FSD/CSD_STANDARD.md` §5 forbids for exactly the reason it would drift.
Until then `entry:` is prose and this is the only CSD in safety-commons whose
§2 the checker does not read.

## 3. Contracts (who)

Verified against ciris-server 0.5.217. **v62:** every row below except
`yubikey-status` and `/v1/trust-root*` is retired; the rows stay because the
client code still calls them (`check_csd_routes.py`). Shapes on v62 are
**0.6: pending (CIRISServer)** (O1).

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

**v62:** the paragraph below is 0.5.x history. On v62 the ceremony's output
IS the portable genesis (statements + object id), and there is no separate
re-mint (P-CER header).

**This ceremony's artifact is not the portable seed.** `genesis/assemble`
returns and saves the founder-signed family genesis `SignedCegObject`
(`src/accord.rs:890-912`, written to the CEG outbox as
`humanity_accord_genesis.json`, the thing verify bakes). `POST
/v1/trust-root/import` takes a `GenesisBundle` — charter, conferral, serve
nodes, authorizations (CIRISPersist v48.0.0 `genesis/bundle.rs:121-130`) — and
refuses anything else as `trust_root.bad_bundle` (`src/trust_root_api.rs:244-253`).
CC 3.2 T5 says the same thing normatively: the bundle is the **only** genesis
artifact and *"a bare record list is not a seed and MUST fail to parse"*
(`part_3_the_namespace.md`, trust-root operational semantics). The portable
seed is minted by the re-mint on the Accord card (`genesis/{propose,cosign}`,
`src/accord_provision.rs:3744,3748`, saved as `mesh-genesis.json`), which reads
this ceremony's roster. So the done state should not tell anyone this JSON is
what other nodes attach; it is the entrenched family, and attaching happens one
step later (CSD-067 §3.1).

**Two doors on `POST /v1/accord/provision-holder`, by design.** The route
checker reports this screen and `ProvisionAccordHolder` (CSD-068) as a
duplicate mutation; the ratchet carries it and both CSDs say why it stays. This
ceremony provisions six keys in sequence for three founders of a NEW mesh,
registers each (`POST /v1/accord/holder`, which CSD-068's screen never calls),
then co-signs the envelope and assembles the genesis. CSD-068's screen
provisions ONE identity for a person joining an accord that already exists and
hands them a next step that is the node owner's. Same route, same touch,
different mission and done state: a one-key form should not carry a six-key
sequencer, and a person who needs a seat should not walk a genesis wizard that
is only offered when no family exists.

**On 0.5.x every route this ceremony needs is live; on v62 none of the
ceremony routes is** (above). The two amendment rows cannot succeed for the accord family (CIRISServer#682);
on v62 the accord's seats change by a later bundle. `admit-node`
and `announce` are called from the Accord screen and cited in CSD-067 §3. This
document's gap list is client-side: the English literals in the view model
(§2.2), and a surface the checker cannot resolve (§2.3).

## 4. Flow (how)

Real tags only.

Reached from `Accord` on a node with no family: open `btn_accord_new`, take the
ceremony action. *The runner cannot walk this hop* — there is no derived chain
(§2), so a flow would have to encode the two clicks, which
`FSD/CSD_STANDARD.md` §5 forbids. This is the concrete cost of the gap.

Once on the screen:

```yaml
expect:
  state: empty
  visible: [txt_ceremony_phase, txt_ceremony_intro, txt_ceremony_entrenched,
            txt_ceremony_diversity, btn_accord_ceremony_begin, btn_accord_ceremony_back]
  text:
    txt_ceremony_phase: "Before you begin"
```

Begin → the provision phase, slot A1:

```yaml
expect:
  visible: [input_ceremony_holder_name, input_ceremony_key_id,
            input_ceremony_usb_path, input_ceremony_pin, btn_ceremony_provision]
  text:
    txt_ceremony_phase: "Provisioning the six keys"
  absent: [accord_ceremony_error]
```

Provision A1 against a node with no token — the step fails in words and the
sequence does not advance:

```yaml
expect:
  state: error
  visible: [accord_ceremony_error]
  text:
    txt_ceremony_phase: "Provisioning the six keys"
  count: {of: "row_ceremony_slot_*", eq: 0}
```

*Cannot yet assert:* which slot is current — the slot chip is untagged.

Done, against a completed ceremony:

```yaml
expect:
  state: populated
  visible: [accord_ceremony_success, txt_ceremony_family, accord_ceremony_genesis_json,
            btn_ceremony_genesis_copy, btn_ceremony_genesis_save, txt_ceremony_not_seed]
  text:
    txt_ceremony_phase: "Done — the genesis is assembled"
```

**`remint_done_family` / `remint_done_holders` are not on this screen.** They
are tags of the Accord card's re-mint sheet
(`AccordScreen.kt`, `RemintTrustRootSheet`), a different ceremony with a
different artifact: this one entrenches the family, the re-mint mints the
portable seed. They are asserted in CSD-067 §4, where the tags live. This
screen draws no tagged family line, so `accord:family` above is `proposed:`.

## 5. QA plan

Spec complete and flow written (`testing/flows/drafts/csd-069-accord-ceremony.yaml`, floor `>=0.5.224`); it becomes `testable` when it runs on the matrix. **It stays in `drafts/`:** `Screen.AccordCeremony` is flow-only — `nav_map` derives no hop to it, so the runner only waits for it after sign-in lands on Contacts, and the flow would be `cannot-start` (red) on every leg. To promote it, start on Accord (which has a hop) and open the ceremony from there.

**Platforms.** Desktop in practice. Six FIPS YubiKeys and six USB volumes, each
re-inserted, are not a thing any platform runner has; the screen composes on all
five and the ceremony runs on one.

**Acceptance — functional**
1. The sequence cannot be taken out of order.
2. A failed step is visibly a failed step and does not advance.
3. The genesis artifact is on screen and the person is told to keep it.
4. The ceremony is offered only where it can be completed.

**Not tested here.**
* **The v62 ceremony.** It has no 0.6 routes and no card yet (O2).
* **Six provisions and three co-signs.** Physical hardware, PIN and touch;
  there is no endpoint for a touch and a mocked one tests the mock.
* **Acceptance 2 past the first slot** — the no-token failure on A1 is
  asserted (§4); a failure on B2 after five successes needs five touches.
* **Acceptance 3's second half on a runner** — copy and save are tagged; that
  the person then kept the file is theirs.

**Stage.** Every tag is real and §3 has no `unconfirmed`, so `check_csd_v3.py`
would admit `testable`. The draft flow's floor is off `unreleased`
(`client: ">=0.5.224"`): it drives only tags that v0.5.224 carries, and leaves
the later phase line, intro sentences, family line and copy/save unasserted.
The one remaining condition is that the flow runs on the
matrix (#97); the card stays at `building` until it does.
* **The hop.** Unlike every other CSD in this area, the runner cannot walk to
  this screen, so "the entry exists" is asserted by the parent's CSD-067 flow
  and by nothing here.
