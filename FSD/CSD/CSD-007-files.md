# CSD-007 — Files and Notes to self (the drive plane)

**CSD**: CSD-007 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, B3 ("Files holds files")
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person can see the files in a circle, open one, save a copy, and add one —
and every row says where its bytes are.** Before B3 the Files tab held audit
entries and the memory graph. Those are records *about* a person, not files
they keep. The drive plane (CIRISServer 0.5.215, `src/drive.rs`) gives the
client real files, so Files now holds those and nothing else.

A file whose bytes are on another device, or that this device holds no grant
for, is still listed. The row says so ("On another device", "This device
can't open it"). It is not hidden, and it is not shown as openable. Serves
**Contextual Integrity**: a file's audience is its room, and the receipt names
it.

Notes to self live in **Just me › Chats**, not in Files. The server's model is
that a note is a message in the self room, and the design's is that a chat is
a group of two; a note to self is the group of one.

## 2. Surface (what)

```yaml csd:surface
surface: files
screen: Files
```

`nav_map` derives:

- `circle_agent -> tab_files` (Just me: the self room)
- `circle_local_community -> tab_files` and `circle_global_communities -> tab_files` (every community room the person is in, grouped by room)
- `circle_agent -> tab_chats` lands on **Notes** when no agent is attached (`nav_epistemic_interact` / `nav_epistemic_notes` when both are there)

**Family › Files** has no drive surface. Family files cannot be written in
production yet (CIRISServer#627), so the tab says why it is empty
(`nav.empty.files_family`) rather than showing an empty list that suggests
nobody has shared anything. **Everyone › Files** is the generic empty tab.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "holds_bytes:sha256:{prefix}"
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "What it is — This node did not send this (the listing carries no content hash)"
    tag: receipt_dimension
  - ceg: x_private:attesting_key_id
    use: display-only
    type: string
    example: "b3-scratch-node-o5u6o24243"
    renders: "Who sent it — b3-scrat…4243"
    tag: receipt_attester
  - ceg: x_private:cohort_scope
    use: display-only
    type: "enum[self,family,community]"
    example: "self"
    renders: "Who can see it — Just me"
    tag: receipt_scope
  - ceg: x_private:byte_state
    use: display-only
    type: "enum[here,not_fetched,not_granted,unopened]"
    example: "here"
    renders: "the row's third line: On this device / On another device / This device can't open it / Can't be opened here"
    tag: "files_row_*"
```

`bytes` is read through `ByteState.of`, and **an unknown token is never
`here`**. `/v1/notes` says `open` where `/v1/drive` says `here` for the same
fact. `Note.byteState` maps `open` to `HERE`; every other token is shared.

**A note is not a file.** `/v1/drive?cohort=self` lists notes too. The client
applies the node's own predicate from `read_notes`: an unnamed `text/plain`
row in the self room is a note, and Files leaves it out
(`DriveEntry.isNote`). A named `.txt` stays a file.

```yaml csd:states
populated: {tag: files_list}
empty:     {tag: files_empty, renders: "Nothing here yet — files you add to Just me stay on your own devices (community: nothing has been shared in these rooms yet)"}
loading:   {tag: files_loading, renders: "the frame with a progress affordance and NO sentence"}
error:     {tag: files_error, renders: "the node's refusal, in words; and files_node_too_old when the route 404s with no reason id: This node doesn't hold files yet — it is running {version}; files need ciris-server 0.5.215 or newer"}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the listing | `GET /v1/drive?cohort&room_id&limit` | CIRISServer | live since 0.5.215; owner session only |
| one file's bytes | `GET /v1/files/{id}?room_id` | CIRISServer | live; 409 `drive.not_fetched`, 403 `drive.not_granted` are answers, not failures |
| add a file | `POST /v1/files` | CIRISServer | live; inline cap 1 MiB, checked by the client **before** upload |
| notes | `GET`/`POST /v1/notes` | CIRISServer | live since 0.5.215 |
| family files | `POST /v1/files {cohort: family}` | CIRISServer | **not in production** — CIRISServer#627 |
| the content hash on the listing | `GET /v1/drive` — **unconfirmed** | CIRISServer | blocks `building` for `receipt_dimension` |

Refusals arrive as `{error: <id>, detail: <english>}`; `NodeRefusal.fromBody`
reads the id from `error` when it is dotted. The `drive.*` and `notes.*` ids
are localized.

## 4. Flow (how)

Sign in on a ≥0.5.215 node as its owner; open **Just me › Files**.

```yaml
expect:
  state: populated
  count: {of: "files_row_*", min: 1}
  visible: [files_list, btn_files_add, btn_files_refresh]
```

Open a file by clicking `files_row_<id>`.

```yaml
expect:
  visible: [sheet_file, btn_file_save_copy, btn_file_close]
```

A text file shows `file_preview_text`. Open its receipt with
`btn_receipt_<id>`:

```yaml
expect:
  visible: [sheet_receipt, receipt_attester, receipt_scope, receipt_dimension, btn_receipt_close]
  text: {receipt_dimension: "did not send"}
```

**Just me › Chats**: type into `input_note`, click `btn_note_save`; a new
`notes_row_<id>` appears with the text, and the note is **not** listed under
Files.

## 5. QA plan

**Verified live** (desktop, scratch ciris-server 0.5.215 on an isolated home,
2026-09-24): empty state, listing, opening a text file with preview, the
receipt, writing a note from the UI and reading it back from `/v1/notes`.
Live testing found two bugs, and both are now fixed with tests:

- Notes were listed as "Untitled" files.
- Readable notes rendered "Can't be opened here" because of the `open` / `here` token.

**Not tested here.** The native file picker (a platform dialog, not drivable
over `/act`). Upload was exercised through the view model and `POST /v1/files`
directly. Also untested: community rooms, since no community room existed on
the scratch node, and the not-fetched / not-granted states, which need a
second device (view-model tests cover them).
