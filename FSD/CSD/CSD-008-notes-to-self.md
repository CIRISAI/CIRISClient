# CSD-008 — Notes to self (the chat of one, in Just me › Chats)

**CSD**: CSD-008 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, B3 ("Files holds files") and B4 (Just me)
**Pairs with**: CSD-007 (Files: the drive plane these notes are rows of) · CSD-010 (Interact: the other card in Just me › Chats, when an agent is attached)
**Flow**: `testing/flows/drafts/csd-008-notes-to-self.yaml` (staged; floor `unreleased`)

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can write themselves a note and read it back on any of their
devices, with no agent involved.** The server's model is that a note is a
message in the self room (`POST /v1/notes` writes an UNNAMED `text/plain` row
there); the design's is that a chat is a group of two, so a note to self is
the group of one and belongs in Chats, not Files. Serves **Contextual
Integrity**: a note's audience is the person's own devices and nothing else,
and the drive lists it under `self` for that reason.

## 2. Surface (what)

```yaml csd:surface
surface: notes
screen: Notes
```

`nav_map` derives `circle_agent -> tab_chats -> nav_epistemic_notes`. It is
placed in Just me on every build (`CirclesNav.kt`: no `agentOnly`), so with no
agent attached Just me › Chats holds Notes alone; with one it holds Interact
and Notes, and the shell lists them as rows.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:byte_state
    use: display-only
    type: "enum[here,not_fetched,not_granted,unreadable,unopened]"
    example: "here"
    renders: "the note's body when this device can open it; else where the bytes are, in words"
    tag: "notes_row_*"
```

A note this device cannot open is still a note that exists: the row says
"On another device" rather than vanishing. `Note.byteState` reads the node's
`state` through `ByteState.of`, mapping the pre-0.5.217 `open` to `here`
(CIRISServer#644); an unknown token is never `here`.

```yaml csd:states
populated: {tag: notes_list}
empty:     {tag: notes_empty, renders: "No notes yet. Notes stay in Just me, on your own devices."}
loading:   {tag: notes_loading, renders: "the frame with a progress affordance and NO sentence"}
error:     {tag: notes_error, renders: "the node's refusal, in words; notes_node_too_old when the route 404s with no reason id"}
```

Writing: `input_note` and `btn_note_save`; a blank note is not sent; a refused
write is `notes_write_error` in words, beside the field, and the draft stays.

## 3. Contracts (who)

Sources: CIRISServer `origin/main` 046e1b39 (0.5.217), `src/drive.rs`. Every
call goes to the node URL (`ClientDrive`), never `$baseUrl`.

| value | endpoint | owner | state |
|---|---|---|---|
| the notes | `GET /v1/notes` | CIRISServer `src/drive.rs:3020` (handler `read_notes`); owner session only; `state` is one of `drive::BYTE_STATES` (`:193-201`) | called — `viewmodels/NotesViewModel.kt:43` |
| write a note | `POST /v1/notes` `{body}` | CIRISServer `src/drive.rs:3020` (handler `write_note`); an empty body is `notes.empty`; since 0.5.217 the body goes through the same write gate as a file (CIRISServer#642) | called — `viewmodels/NotesViewModel.kt:62` |
| edit a note | `PUT /v1/notes/{id}` | CIRISServer `src/drive.rs:3023` (handler `:2793`) — new row, old withdrawn | live, **not called** |
| withdraw a note | `DELETE /v1/notes/{id}` | CIRISServer `src/drive.rs:3023` (handler `:2843`) | live, **not called** |

Refusals arrive as `{error: <id>, detail}`; the `notes.*` ids are localized.

## 4. Flow (how)

Sign in on a ≥0.5.215 node as its owner; open **Just me › Chats › Notes**.
Type into `input_note`, click `btn_note_save`.

```yaml
expect:
  state: populated
  count: {of: "notes_row_*", min: 1}
  visible: [notes_list, input_note, btn_note_save]
```

The new note is **not** listed under Files (CSD-007: `DriveEntry.isNote`).

## 5. QA plan

Spec complete and flow written (`testing/flows/drafts/csd-008-notes-to-self.yaml`, floor `unreleased`); promotes to `testable` when the floor is released and the flow runs on the matrix (#97).

**Verified live** (desktop, scratch ciris-server 0.5.215, 2026-09-24): writing
a note from the UI and reading it back from `/v1/notes`; a readable note
rendering its body (the `open` / `here` token bug, fixed with a test).
`NotesViewModelTest` covers the blank note and the trimmed write with a
re-read. **Not tested:** a note on a second device (`not_fetched`), edit and
withdraw (not wired).
