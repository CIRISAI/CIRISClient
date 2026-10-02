# CSD-069 — Accord Genesis Ceremony (six keys, three humans, one artifact)

**CSD**: CSD-069 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: `testing/flows/drafts/csd-069-accord-ceremony.yaml` (floor `>=0.5.224`)

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**Three humans standing up a NEW mesh can walk one guided sequence — six keys
provisioned and registered, three primaries co-signing the family envelope, the
genesis assembled — and come out holding an artifact they know they have to
keep.** Serves **Core Identity**: this is where a mesh's kill switch comes from,
and a mesh whose genesis was fumbled has a constitutional layer nobody can
operate.

CC 4.2.3 says what is being made: the accord-holder triple **structurally IS an
entrenched `family`** with `consensus_protocol: "quorum:2/3"` and
`consensus_protocol_entrenched: true`. Entrenched means the ceremony's output is
not correctable in the ordinary way later — replacement runs an out-of-band
process. A wizard whose steps can be taken out of order, or whose done-state can
be reached without the artifact being saved, produces a mesh that is wrong
permanently.

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
placement now fails with *"is sidebar-reachable … so it is not flow_only"*,
which is the right direction for this defect to break in.

**The earlier `screen_class:` encoding was wrong and this document carried it.**
CSD-080 §2 introduced `surface: null` / `screen_class:` / `entry:` for the
pre-shell screens and this CSD followed it. `screen_class` is not a key
`check_csd_v3.py` reads, so it left `screen` unset and **silently disabled both
reachability checks** — the same hole that let a CSD claim Nodes is
`Screen.Telemetry`, and that let the typo `screeen:` pass. An unknown key is now
a failure rather than a no-op, which is how this file was caught.

**One stale comment to fix while here.** `screen_atlas.py:52` says *"nav_map
still hands it a one-hop chain, so it read as a screen the atlas kept failing to
reach"*. It does not any more — `build()` only emits screens whose surface is in
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
    renders: "'Family humanity-accord — entrenched, quorum:2/3' on the done card, the id read from the assembled genesis the node returned (family_key_id)"
    tag: txt_ceremony_family
  - ceg: "accord:holders"
    use: display-only
    type: "list[string]"
    example: ["wa-holder-1c4f", "wa-holder-9b02", "wa-holder-33da"]
    renders: "six slots — A1 A2 B1 B2 C1 C2, primaries SEAT and spares VAULT"
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
    renders: "the assembled genesis, in mono — the cold-start bake artifact"
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

**`error` was said to be the gap, and the finding was half right.** The
error banner existed and was tagged (`MessageCard(…, "accord_ceremony_error")`,
`AccordCeremonyScreen.kt`) — this CSD said there was no error tag, and there
was. What was genuinely missing was the *phase*: a failed provision of key B2
and an un-started provision of key B2 were the same pixels because nothing on
the screen said which step the wizard was on. `txt_ceremony_phase` (accord
review) is that line; with it, "failed" is *error banner + phase unchanged* and
"not reached" is *a later phase*, which a flow can tell apart. The four states
are real and tagged.

### 2.1 What the ceremony gets right

* **The slots are named and tagged.** `row_ceremony_slot_$label` gives A1…C2
  their own elements, so "which key are we on" is assertable per slot.
* **The artifact is on screen and tagged.** `accord_ceremony_genesis_json` puts
  the bake artifact in mono where the operator can see it, and
  `accord_ceremony_success` marks the done state.
* **Each phase has one button.** `btn_ceremony_provision` /
  `btn_ceremony_cosign` / `btn_ceremony_assemble` — three verbs, three phases,
  no way to assemble before cosigning.

### 2.2 What it did not, and does now (accord review)

* ~~**Nothing asserts the artifact was kept.**~~ `btn_ceremony_genesis_copy`
  puts the genesis on the clipboard; `btn_ceremony_genesis_save` writes it as
  `humanity_accord_genesis.json` to a chosen folder (`writeTextFile`, desktop);
  each says so in the notice banner. The done card also says, in words
  (`txt_ceremony_not_seed`), that this JSON is the entrenched family and NOT the
  portable seed other nodes adopt — §3's finding, put where the person is.
* ~~**The correlated-failure geometry is not stated.**~~ `txt_ceremony_diversity`
  on the intro: three people who do not share a household or an employer,
  because two of three from one address is a quorum one door can reach — CC
  4.2.3's *"the mitigant is diversifying the holder set"*, at the one moment it
  can land.
* ~~**`consensus_protocol_entrenched` is not shown.**~~ `txt_ceremony_entrenched`
  on the intro: the family's `quorum:2/3` cannot be lowered later by anyone
  inside the federation; replacing a seat is a new ceremony and a re-minted
  seed, not an edit.
* **Open:** `AccordCeremonyViewModel` composes its notices and plain-language
  errors as English literals (`"Provisioning A1… TOUCH your YubiKey…"`, the
  ten `plainLanguageError` sentences). The fix is the key-plus-detail shape
  `ProvisionAccordHolderViewModel` now uses (CSD-068 §2.2).

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

Verified against ciris-server `origin/main` at 0.5.217 (2026-09-25).

| value | endpoint | owner | state |
|---|---|---|---|
| is a token present | `GET /v1/accord/yubikey-status` | CIRISServer `src/accord_provision.rs:3770` | **live** |
| provision one of six | `POST /v1/accord/provision-holder` | `src/accord_provision.rs:3697` | **live**, loopback-only |
| register the minted key | `POST /v1/accord/holder` | `src/accord.rs:2600` | **live** |
| the family envelope | `POST /v1/accord/genesis/envelope` | `src/accord.rs:2621` | **live** |
| a primary's co-sign | `POST /v1/accord/family/cosign` | `src/accord_provision.rs:3701` | **live** |
| assemble the genesis | `POST /v1/accord/genesis/assemble` | `src/accord.rs:2625` | **live** |
| did this node end up rooted | `GET /v1/trust-root` → `posture` (`entrenched` / `pre_genesis` + the missing `leg`) and `banner` | `src/trust_root_api.rs:415`, `:55-66` | **live on this node's own machine, called** from the Accord card's trust-root detail (`txt_trust_posture_state`, CSD-067 §3), not from this ceremony; **remote reach** `blocked_by: CIRISServer#652` |
| adopt the ceremony's output on another node | `POST /v1/trust-root/import` | `src/trust_root_api.rs:416` | **live, and it does NOT take this ceremony's artifact** — see below |
| change the family after genesis — build the quorum envelope | `POST /v1/accord/family/change/envelope` | `src/accord.rs:2637` | live, **not called, and cannot succeed for this family**: it hardcodes `humanity-accord` (`:2151`) while supersede refuses that id — CIRISServer#682 |
| replace the family | `POST /v1/accord/family/supersede` | `src/accord.rs:2641` | live, **not called**: always refuses `humanity-accord` by design (`:2306-2315`); its refusal names a route that does not exist — CIRISServer#682. The accord changes seats by re-running the ceremony and re-minting the seed (CSD-067 §3.1). `GET /v1/accord/family/history` (`:2645`) is read on the Accord card |

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

**Every route this ceremony needs is live.** The two amendment rows cannot succeed for the accord family (CIRISServer#682);
the accord's seats change by a new ceremony and a re-minted seed. The route-coverage report also filed
`admit-node` and `announce` here; both are called from the Accord screen and are
cited in CSD-067 §3. The whole of this document's gap
list is client-side, and after the accord review it is: the English literals in
the view model (§2.2), and a surface the checker cannot resolve (§2.3).

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

**`remint_done_family` / `remint_done_holders` were asserted here and are not on
this screen.** They are tags of the Accord card's re-mint sheet
(`AccordScreen.kt`, `RemintTrustRootSheet`), a different ceremony with a
different artifact: this one entrenches the family, the re-mint mints the
portable seed. The assertion moved to CSD-067 §4, where the tags live. This
screen draws no tagged family line, so `accord:family` above is `proposed:`.

## 5. QA plan

Spec complete and flow written (`testing/flows/drafts/csd-069-accord-ceremony.yaml`, floor `>=0.5.224`); promotes to `testable` when it runs on the matrix. **Not moved to `testing/flows/` on the 0.5.225 run (2026-09-29):** `Screen.AccordCeremony` is flow-only — `nav_map` derives no hop to it, so the runner only waits for it after sign-in lands on Contacts, and the flow would be `cannot-start` (red) on every leg. To move it: start on Accord (which has a hop) and open the ceremony from there.

**Platforms.** Desktop in practice. Six FIPS YubiKeys and six USB volumes, each
re-inserted, are not a thing any platform runner has; the screen composes on all
five and the ceremony runs on one.

**Acceptance — functional**
1. The sequence cannot be taken out of order.
2. A failed step is visibly a failed step and does not advance.
3. The genesis artifact is on screen and the person is told to keep it.
4. The ceremony is offered only where it can be completed.

**Not tested here.**
* **Six provisions and three co-signs.** Physical hardware, PIN and touch;
  there is no endpoint for a touch and a mocked one tests the mock.
* **Acceptance 2 past the first slot** — the no-token failure on A1 is
  asserted (§4); a failure on B2 after five successes needs five touches.
* **Acceptance 3's second half on a runner** — copy and save are tagged; that
  the person then kept the file is theirs.

**Stage.** Every tag is real and §3 has no `unconfirmed`, so `check_csd_v3.py`
would admit `testable`. The draft flow's floor is already off `unreleased`
(`client: ">=0.5.224"`): it drives only tags that v0.5.224 carries, and leaves
the later phase line, intro sentences, family line and copy/save unasserted.
As for CSD-068, the one remaining condition is that the flow runs on the
matrix (#97); the card stays at `building` until it does.
* **The hop.** Unlike every other CSD in this area, the runner cannot walk to
  this screen, so "the entry exists" is asserted by the parent's CSD-067 flow
  and by nothing here.
