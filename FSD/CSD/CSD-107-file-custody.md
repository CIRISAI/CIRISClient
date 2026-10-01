# CSD-107 — Where is this file (file custody, one card from every file surface)

**CSD**: CSD-107 · **Standard**: CSD/3 (`CSD.md`) + the CSD/4 `topology:` block (`FSD/CSD4_EVALUATION.md`; CIRISServer `FSD/TOPOLOGY.md`) · **Origin**: Eric, via the CIRISServer session (2026-09-30): "every file a person can open shows which of their devices it is on"
**Pairs with**: CSD-007 (Files — the drive), CSD-008 (Notes to self), CSD-091 (a file in chat). Each of those surfaces reaches THIS card from its row's hamburger; none builds its own.
**Flow**: `testing/flows/drafts/csd-107-file-custody.yaml` (floor `unreleased`)
**Card**: built on this branch — `ui/screens/files/FileCustodySheet.kt` (the card, `CustodyTags`), `viewmodels/FileCustodyViewModel.kt` (the states), `models/drive/Custody.kt` (the provisional wire and every rendering decision as a pure function), `FileCustodyTest` / `FileCustodyViewModelTest` / `DriveWireTest`

The number: CSD-107 was assigned once before, to the partnership queue, and
folded into CSD-054 §7 under the no-duplicate-cards rule (`CSD-054:286`). No
file, open PR branch (`gh pr list --json headRefName,files`, 2026-09-30) or
`PENDING-*` file holds it, so it is reused here.

```yaml csd:stage
stage: building
owner: CIRISClient
```

`building`, not `testable`: the card is built and every §3 row is answered,
but the route (CIRISServer#704) is in v0.5.218, which is TAGGED at 405acc17
and not released: no GitHub release and no PyPI 0.5.218 as of 2026-10-01. The
latest release, 0.5.217, does not mount it, so the flow's floor stays
`unreleased` and the one state the matrix can reach is the version fact.

## 1. Mission (why)

**Every file a person can open says which of their devices it is on: here,
received by another device, not on a device (only when that device said so),
or unknown — and never "not there" when the honest answer is "can't tell".**

A person with a laptop and a phone writes a file on one and wants to know if
the other has it. Today nothing answers. The drive row says only whether the
bytes open HERE (`bytes: here | not_fetched | not_granted`, CSD-007); it cannot
say whether the phone received them.

Three limits shape what the card may say, and each is a sentence on it rather
than a silence:

* **A receipt proves delivery, not current holding.** A device that received a
  file may have evicted it since, and eviction does not retract the receipt yet.
  So "received" is a past event with a time, never "has it".
* **Small files carry no receipts yet.** Inline files (1 MiB or less) get
  receipts with persist v52 / edge v38. Until then `receipts_supported: false`,
  and another device's copy is **unknown**, never "not on".
* **Copies of your own and family files can't be counted, by design.** CC 5.2
  forbids a `holds_bytes` directory row for `cohort_scope: self | family`
  (the same absence CSD-027 §2 reads as load-bearing). With
  `copies_observable: false` the card says copies elsewhere can't be counted,
  and shows no number at all: the one number available (what this node can
  see) would read as "the only copy".

Serves **Contextual Integrity**: the card answers "where is my file" without
making a device roster any wider than the file's own audience.

## 2. Surface (what)

```yaml csd:surface
surface: files
screen: Files
```

One card, `FileCustodySheet` (a bottom sheet around one CardShell), reached
from the row hamburger on every file surface. It is placed on Files because
that is where most files are; the same composable and the same view model
(`FileCustodyViewModel`, one per app) serve the other two.

| surface | CSD | the entry | tag |
|---|---|---|---|
| Files (the drive, every circle) | CSD-007 | the row's receipt hamburger (`btn_receipt_<id>`) → the receipt's acts → **Where is this file** | `btn_receipt_act_where_<attestation_id>` |
| Notes to self | CSD-008 | each note now carries the same receipt hamburger ItemRow gives a drive row (`btn_receipt_<id>`) → **Where is this file** | `btn_receipt_act_where_<attestation_id>` |
| A file in chat | CSD-091 | the message card's `⋮` (`card_menu_<id>`) → **Where is this file**, after the uniform ops | `mi_op_where_<attestation_id>` |

**The chat entry is dormant, and says so.** The chat plane carries text only:
`POST /v1/chat/{id}/messages` refuses any other content type
(`chat.unsupported_content_type`, CIRISServer `src/contacts_chat.rs:2921` on
`main`, `:3606` on `integ/0.5.218`). The item is added to a message's menu only
when its `content_type` is not `text/*` (`isFileContentType`,
`chatCustodyTarget`), so on every row a node sends today it is absent. It
appears with the first file row, at no further client change. The uniform
`AttOp` menu is unchanged for every other card: the item is an `ExtraOp`
appended after it.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:custody_summary
    use: display-only
    type: int
    example: 2
    renders: "'On 2 of your 2 devices' — N counts devices whose holds is here or received; M is `devices_total` (else the rows listed). Any unknown device, or one counted but not listed, turns it into 'On at least 1 of your 2 devices. For 1 of them, this device has no way to know yet.' (`custodySummary`)"
    tag: text_custody_summary
    assert:
      matches: {text_custody_summary: "^On (at least )?[0-9]+ of your [0-9]+ devices"}
  - ceg: x_private:custody_device
    use: display-only
    type: "list[string]"
    example: ["Laptop", "D2…"]
    renders: "one ItemRow per `devices[]` entry: its `label`, else its `node_key_id` middle-truncated; a 'This device' chip on `this_device: true`"
    tag: "row_custody_device_*"
  - ceg: x_private:custody_holds
    use: display-only
    type: "enum[here,received,none,unknown]"
    example: "received"
    renders: "'Holds it here' / 'Received it 2026-09-30 10:11' (from `received.at`; 'Received it' with no time while `at` is null, until persist v52) / 'Not on this device' ONLY for `none` (the device said it has no copy; the server lets that override an older receipt, the client does not re-derive it) / 'Unknown' for `unknown`, a token this client does not know, and a `received` claim while `receipts_supported` is false (`custodyHolds`). When a device's `reported_at` is present: 'The device reported this at <time>' (`custody_reported_<key>`) — the `custody:ack:v1` report, CC 3.1.3.3, null until persist v53"
    tag: "custody_holds_*"
  - ceg: x_private:custody_can_open
    use: display-only
    type: bool
    example: true
    renders: "'Can open it: yes' / 'no' / 'can't tell' — `can_open: null` (e.g. on a device with no copy) is can't tell, never no"
    tag: "custody_can_open_*"
  - ceg: x_private:custody_held_here
    use: display-only
    type: bool
    example: true
    renders: "FieldRow 'Held here — Yes'; absent reads 'This node did not send this.' in the error tone"
    tag: custody_held_here
  - ceg: x_private:custody_copies
    use: display-only
    type: int
    example: 2
    renders: "FieldRow 'Copies — 2 known', ONLY when `copies_observable` is true. Otherwise (false, or not sent): 'Copies elsewhere can't be counted for your own and family files. That is by design, and it doesn't mean there is only one copy.' (`custodyCopies`)"
    tag: custody_copies
  - ceg: x_private:custody_receipts_supported
    use: display-only
    type: bool
    example: false
    renders: "when false: the footnote 'Small files (1 MiB or less) don't carry receipts yet, so another device's copy shows as unknown, not as missing.' Absent is not false: no footnote"
    tag: custody_footnote_receipts_unsupported
  - ceg: x_private:custody_author
    use: display-only
    type: string
    example: "D1"
    renders: "FieldRow 'Device that wrote it — This device. It collects the receipts, so this is the fullest answer.' (`this_device_is_author`), or '<label>. That device collects the receipts, so it has the fullest answer.' (`author_device`)"
    tag: custody_author
  - ceg: x_private:custody_why
    use: display-only
    type: "list[string]"
    example: ["custody.receipt_is_delivery_not_holding"]
    renders: "one footnote per `why[]` entry `{reason_id, detail}`: the reason id localized from the bundle, the node's `detail` under it in mono small print (`custody_why_<id>_detail`); with no key, the detail, else the raw id (`custodyWhyLines`)"
    tag: "custody_why_*"
```

**The card's own footnotes.** The receipt footnote
(`custody_footnote_receipts`, dropped when the node sends `custody.receipt_is_delivery_not_holding` itself: a receipt shows delivery, not that the device
still has it, and removing a file does not take its receipt back yet) and the
Remove footnote (`custody_footnote_remove`: when Remove arrives it will refuse
to take away the last copy of a file you haven't withdrawn).

**The next cut, shown disabled.** Under each device row, "Copy to <device>:
coming next" (`btn_custody_copy_<key>`, on a device that does not hold it
here) and "Remove from <device>: coming next" (`btn_custody_remove_<key>`, on a
device that holds or received it). Both are `testableClickable(enabled =
false)`: no click handler is registered, so `/click` is refused as it would be
for a person. `testableWithHandler` does not take `enabled` on `main` yet (the
flows branch changes that); this follows the existing disabled pattern
(`bindClickHandler`, CIRISClient#69) instead. `<key>` is the device's
`node_key_id`, or `idx<n>` when the node sent none.

```yaml csd:states
populated: {tag: card_file_custody, renders: "the summary, a row per device, Held here, Copies, the footnotes, and btn_custody_close"}
empty:     {tag: custody_empty, renders: "'This node named none of your devices for this file.' — `devices_total` 0 and no rows"}
loading:   {tag: custody_loading, renders: "the card frame with a progress affordance; no summary and no rows"}
error:     {tag: custody_error, renders: "'Couldn't find out where this file is.' with the node's reason by id. A device with the row and not the bytes is NOT this state: it answers 200 with its own `holds: none` and `custody.no_copy_here`, and renders as a populated card with access, size and can-open unknown (§7). A bare 404 from a node without the route is its own tag, custody_node_too_old: 'This node is running {version}, which can't say which of your devices a file is on. That needs a newer ciris-server.' — the version fact, as CSD-092's NodeTooOld"}
```

## 3. Contracts (who)

The route is **CIRISServer#704** at `d1a15286` (`feat/file-custody-0.5.218`, open into
`chore/adopt-edge-v33`, which is headed for `integ/0.5.218`): `src/drive.rs`
(the handler), `src/file_custody.rs` (the reasons), `FSD/FILE_CUSTODY.md` (the
contract). It is in tag `v0.5.218` (405acc17; `src/drive.rs:3921`, handler
`:2548`, the same `FileQuery` as every per-file route), which is tagged and not
released (no GitHub release, no PyPI 0.5.218, as of 2026-10-01), so the floor
stays `unreleased`. The model cites it in its KDoc (`models/drive/Custody.kt`).
Every field is optional and parsed by hand over a `JsonObject`: a renamed or
retyped member reads as absent (then "not sent"), never as a crash.

**Doors.** #704 §1.1: the same auth and cohort handling as
`/v1/files/{id}/meta`, and the same doors as the byte read in the same order —
owner session, cohort named and membership-checked, the row through edge's
gated reader, `410 drive.withdrawn`, then persist's custody door as the
drive's viewer key. A viewer who cannot open the bytes gets the byte read's own
refusal (`drive.not_granted`, `drive.evicted`, …). No new refusal id. Since
`d1a15286` (the maintainer's ruling) a device that holds the row and not the
bytes is NOT refused: it answers 200 with its own entry `holds: "none"` +
`checked_at`, `held_here: false`, and `access`, `size_bytes`, `can_open` null,
with `custody.no_copy_here` in `why`. Byte reads there still answer
`409 drive.not_fetched`, which is not this card's concern.

**The host rule, mirrored from the drive.** The brief said to mirror how the
client calls `GET /v1/files/{id}/meta`. It does not call it: CSD-007 §3 lists
`/meta` as "live, not called". So this mirrors the per-file drive reads it does
make (`readFile`): the NODE URL through `ClientDrive` (the active node, read at
call time; never `$baseUrl`, CIRISAgent#1213), the owner bearer, and the
per-file `FileQuery` every `/v1/files/{id}/…` handler takes — `cohort`
(`self | family | community`, `Cohort::parse`, `src/drive.rs:107`) and
`room_id` for family and community (`room_for`, `:373`), omitted for self
(`CustodyTarget.queryRoom`).

<!-- generated: python3 packaging/check_csd_routes.py --print CSD-107 (screen Files; heuristic) — the custody row; the other rows it prints are CSD-007's -->
| value | endpoint | owner | state |
|---|---|---|---|
| where the file is | `GET /v1/files/{id}/custody?cohort&room_id` | CIRISServer#704, in tag v0.5.218 (405acc17, tagged, not released) at `src/drive.rs:3921` (handler `:2548`; same auth and `FileQuery` as `/v1/files/{id}/meta`) | called — `api/DriveApi.kt:60` (`ClientDrive.readCustody` → `CIRISApiClient.readFileCustody`, query from `CIRISApiClient.fileQuery`: `cohort` always, `room_id` unless `self`), opened from `ui/screens/files/FilesScreen.kt:332` (drive), `ui/screens/files/NotesScreen.kt:138` (notes), `ui/screens/ChatScreen.kt:397` (chat); built, **unreleased**: 0.5.217, the latest release, answers a bare 404, rendered as `custody_node_too_old` |
| the response | `{attestation_id, cohort, room_id, tier, size_bytes\|null, at_rest_sha256, author_device, this_device_is_author, checked_at, devices_total, devices[{node_key_id, label?, this_device, can_open\|null, received{epoch,k,at\|null}\|null, holds: here\|received\|none\|unknown, checked_at (this device), reported_at\|null}], held_here, copies_known, copies_observable, announced_holders[{node_key_id,size_bytes}], access[{person_key_id,devices,via}]\|null, receipts_supported, receipts_unsupported_reason, receipts_from_other_keys[], why[{reason_id,detail}]}` | CIRISServer#704 `d1a15286`, `FSD/FILE_CUSTODY.md` §1.1 | parsed leniently by `FileCustody.fromWire`. Rendered: every device row, held here, copies (§1), the author device ("Device that wrote it", `custody_author`), the count of receipts from keys that aren't your devices (`custody_receipts_other_keys`, when > 0), every `why[]`. Read and not rendered: `announced_holders`, `access`, `tier`, `size_bytes`, `at_rest_sha256`, `checked_at` |
| a remote device saying it has no copy | `holds: "none"` + `reported_at` from `custody:ack:v1` (here\|none, live 72 h) | CC 3.1.3.3 (CIRISConstitution#130); CIRISPersist v53. Not in the vendored namespace registry, so cited by CC section, not as a registry family | today only THIS device can say `none`; other devices read `unknown` with `custody.no_copy_reports_pending`. The row shows 'The device reported this at <time>' once `reported_at` arrives |
| the receipt time | `received.at` | CIRISPersist v52 | `null` until then; the row says "Received it" with no time, never a blank or an epoch |
| the drive row's hint | `GET /v1/drive` rows gain `custody: {devices_total, received_on}` (`received_on: null` for inline) | CIRISServer#704 §1.2 | **not rendered** on the drive row this cut (optional in the brief); the card is the answer. When it is, a null `received_on` is "unknown", never 0 |
| receipts for inline files | `receipts_supported: false` for files of 1 MiB or less | CIRISPersist v52 / CIRISEdge v38 | the card says unknown, never "not on" |
| copies of self and family files | `copies_observable: false` | CC 5.2 (no `holds_bytes` row below community) | by design; the card says they can't be counted |
| copy to a device / remove from a device | none yet | CIRISServer (next cut) | shown disabled, "coming next"; Remove will refuse the last copy of an unwithdrawn file |

**`why[]`: ids, localized, with the node's detail as small print.** At #704
each entry is `{reason_id, detail}` (`src/file_custody.rs`, one `msg` per id;
CIRISServer counts them as localization debt for this bundle, ratchet 139 →
147). The client localizes `reason_id` as a key, keyed as server reason ids
already are (`custody.inline_no_receipt` is `{"custody": {"inline_no_receipt":
…}}`, like `membership.consent_required`, #137), and shows `detail` under it in
mono small print (`custody_why_<id>`, `custody_why_<id>_detail`). With no key
in the bundle it shows `detail`, else the raw id (`custodyWhyLines`). All
ten ids are in the bundle: `custody.inline_no_receipt`,
`custody.copies_unobservable_by_design`,
`custody.receipt_is_delivery_not_holding`, `custody.receipt_time_unknown`,
`custody.receipts_admitted_on_author_device`,
`custody.receipt_signer_not_your_device`, `custody.receipts_unreadable`,
`custody.commons_readable_by_holders`, `custody.no_copy_reports_pending`,
`custody.no_copy_here`. A pre-#704 bare string still parses (an
id-shaped one as the id, a sentence as the detail). The card's own receipt and
small-file footnotes are dropped when the node sent the same thing by id
(`custodyWhyHas`), so nothing is said twice.

**The author device.** Receipts are collected on the device that wrote the
file (#704 §3), so that device's view is the fullest. The card says which
device wrote it (`author_device`, by label when it is one of the listed
devices); on any other device the node sends
`custody.receipts_admitted_on_author_device`, which says why this view is
partial.

## 3a. Topology (CSD/4)

The fixture the server harness builds for this card: one person, two devices,
peered, sharing a self room, and a 24 MiB file (above the inline cap, so
receipts are supported) written on D1.

```yaml csd:topology
topology:
  roots: [{id: R, kind: key, holders: 1, custody: software_test, lifecycle: {recipe: {}, verdict: rooted}}]
  canonicals: [{id: C, holds: R, serves: [infra:serve, infra:attest]}]
  nodes: [{id: D1, dials: [C], accepts: R, announced: true}, {id: D2, dials: [C, D1], accepts: R, announced: true}]
  persons: [{id: one, owns: [D1, D2], accepts: R}]
  relations: [{rel: peered, between: [D1, D2]}, {rel: room, kind: self, person: one}, {rel: file, person: one, device: D1, size: 25165824}, {rel: custody, person: one, device: D1, file: last}]
  actor: {person: one, device: D1}
  negatives: [{check: no_wider_self_rows, person: one}]
```

**`custody` is a new relation the server is adding**: CIRISServer#704 adds
it to `FSD/TOPOLOGY.md` §2.5 and ships the same fixture as
`harness/native/topologies/csd-107-file-custody.yaml` (with a `name:` on the
file relation; the body above is the brief's, verbatim). It asserts that, from the author device D1's view,
`GET /v1/files/{last}/custody?cohort=self` names **D2 as `received`** and
reports **`devices_total == 2`**. `no_wider_self_rows` the builder already runs (`selffiles.yaml`), though
TOPOLOGY.md §2.6 lists only `cannot_list_room` and `holds_no_row`: a self file
leaves no row wider than self. Realizability rule 4 holds: D2 dials D1, the
author, so the self room's body reaches it directly.

**How the checker treats it.** `packaging/check_csd_v3.py` now validates a
`csd:topology` block **structurally** (`check_topology`): known layers only;
every id a layer names is declared; each relation and negative is one
TOPOLOGY.md names, or one listed in `TOPOLOGY_PENDING` with the CSD that asked
for it and where the server stands (`custody`, `no_wider_self_rows`); realizability rule 2;
and the actor on a device their person owns. It does NOT check rules 1 and 3-7
or buildability, which are the builder's. `testing/test_csd_topology.py`
plants eight breaks against this block and each fails.

## 4. Flow (how)

On the released line (no route): write a note, open its receipt, choose
**Where is this file**.

```yaml
expect:
  state: error
  visible: [sheet_file_custody, custody_node_too_old, btn_custody_close]
  absent: [card_file_custody, text_custody_summary]
  text: {custody_node_too_old: "newer ciris-server"}
```

On a node with the route, built from §3a (actor `one` on D1):

```yaml
expect:
  state: populated
  visible: [card_file_custody, text_custody_summary, custody_held_here, custody_copies, custody_author, custody_why_custody_receipt_is_delivery_not_holding, custody_footnote_remove]
  matches: {text_custody_summary: "^On (at least )?[0-9]+ of your [0-9]+ devices"}
  count: {of: "row_custody_device_*", eq: 2}
  text: {custody_copies: "can't be counted"}
```

Pressing a next-cut control does nothing, and `/click` on it is refused
(no handler): `btn_custody_copy_*`, `btn_custody_remove_*`.

## 5. QA plan

Spec complete and flow written (`testing/flows/drafts/csd-107-file-custody.yaml`,
floor `unreleased`). Promotes to `testable` when the route is on a released
node and the populated steps run on the matrix against the §3a fixture.

**Pinned in Kotlin now.** `FileCustodyTest`: the full #704 body (why objects, author, lists where counts were planned), a receipt with `at: null`, the reason-id / detail / raw-id fallback, a `none` device (the only "not on", counted as known), a no-copy-here 200 (access, size and can-open null; a card, not an error), `can_open: null`, a partial
and a retyped body, the §3a actor view (D2 received, 2 of 2),
`receipts_supported: false` → unknown (never "not on") and the footnote,
counted-but-unlisted devices → "at least", `copies_observable: false` → no
count, the `why[]` id/sentence split, and the chat file test.
`FileCustodyViewModelTest`: bare 404 → node too old; 404 with an id → refused
by name; the query carries cohort and room, and self names no room.
`DriveWireTest`: the call leaves for the node URL with `cohort` and `room_id`,
and the api base sees nothing. Two of those tests were shown red against a
planted break (a count shown when not observable; `received` rendered while
receipts are unsupported) before being believed.

**Not tested here.** Whether a receipt is ever retracted on eviction (it is
not, yet; the footnote says so). The chat entry on a real file row (the chat
plane carries none). Copy and Remove (not built).

## 6. Delta — card vs API vs CC

* **Card vs API.** The route is CIRISServer#704, not merged; field names may change.
  The lenient parse and the "cite the PR" comment are the client's side of
  that. `announced_holders` is parsed and not shown: the card has no sentence
  for it that isn't a guess.
* **API vs CC.** `copies_observable: false` for self and family is CC 5.2 working
  as intended, not a gap: the card must never turn it into "1 copy".
* **Card vs card.** One card from three doors. The drive's receipt and the
  notes' new receipt reach it through the same `ReceiptAct`; the chat card
  through an `ExtraOp` on the uniform menu. A fourth file surface adds a door,
  not a card.

## 7. Known gaps

* **Resolved: a device with the row but not the bytes.** Until CIRISServer#704
  `d1a15286` it got `409 drive.not_fetched` from this route. The maintainer's
  ruling made it a 200 whose own entry is `holds: "none"` with
  `custody.no_copy_here`; the card renders that normally. (Byte reads there
  still 409; not this card's concern.)
* **Other devices can't say "none" yet.** That needs `custody:ack:v1`
  (CC 3.1.3.3) reports, with persist v53; until then they read `unknown` and
  the node says so (`custody.no_copy_reports_pending`).
* **Receipt times** are null until CIRISPersist v52; **inline files** carry no
  receipt at this pin (CIRISPersist#953); **eviction** does not withdraw a
  receipt yet. Each is a sentence on the card, not a silence.
