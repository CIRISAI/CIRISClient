# CSD-007 — Files (the drive plane)

**CSD**: CSD-007 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, B3 ("Files holds files")
**Pairs with**: CSD-008 (notes to self, the chat of one in Just me › Chats) · CSD-100 (the household whose files Family › Files lists) · `FSD/MEDIA_EDGE.md` (the renderer stack)
**Flow**: `testing/flows/drafts/csd-007-files.yaml` (staged; floor `unreleased`)

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can see the files in a circle, open one, save a copy, and add one —
and every row says where its bytes are, and every opened file is what the node
said it sent.** Before B3 the Files tab held audit entries and the memory
graph. Those are records *about* a person, not files they keep. The drive
plane (CIRISServer 0.5.215+, `src/drive.rs`) gives the client real files, so
Files now holds those and nothing else.

A file whose bytes are on another device, or that this device holds no grant
for, is still listed. The row says so ("On another device", "This device
can't open it"). It is not hidden, and it is not shown as openable. Serves
**Contextual Integrity**: a file's audience is its room, and the receipt names
it.

An opened file's bytes are hashed and compared to the digest the node states
with them (CIRISServer 0.5.217, CC 5.3.2.5) before any renderer sees them. A
mismatch is **unreadable** — said as such, in the error tone, neither shown
nor saved — never an empty preview and never a failed open. Where the node
sends no digest, the sheet says nothing was verified rather than implying it
was.

Notes to self live in **Just me › Chats** (CSD-008), not in Files. The
server's model is that a note is a message in the self room, and the design's
is that a chat is a group of two; a note to self is the group of one.

## 2. Surface (what)

```yaml csd:surface
surface: files
screen: Files
```

`nav_map` derives:

- `circle_agent -> tab_files` (Just me: the self room)
- `circle_family -> tab_files` (Family: the room of the household picked in the Family hub)
- `circle_local_community -> tab_files` and `circle_global_communities -> tab_files` (every community room the person is in, grouped by room)

The same card lists a different cohort per circle (`FilesCohort`: `self`,
`family`, `community`); one view model per cohort, so one circle's files
never show in the next. **Everyone › Files** is the generic empty tab: the
drive has no room for it.

**Family › Files** lists ONE household's room: the one picked in the Family
hub's switcher (CSD-100, `HouseholdsViewModel.selectedId` — the same view
model, so the household you chose there is the one whose files you see here).
The tab shows which (`files_household`) and keeps three zeroes apart:

| the person has… | state | tag | says |
|---|---|---|---|
| no household at all | `NoRoom` | `files_no_household` | "You're not in a household yet. Form one in Family › Rules…" |
| households, none picked | `NoRoom` | `files_no_household` | "No household is picked. Choose one in Family › Rules…" |
| a household with no files | `Empty` | `files_empty` | "This household has no files yet…" |
| a household read that failed | (the households' failure) | `files_households_error` / `files_households_not_on_this_node` | the node's refusal, or that this node has no household routes |

With no room nothing is asked of the node (`FilesViewModel.refresh(null)`);
"no files" is only ever said about a room that was read.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "holds_bytes:sha256:{prefix}"
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "What it is — This node did not send this (the LISTING carries no content hash; the opened file's digest is checked on the sheet, not here)"
    tag: receipt_dimension
    blocked_by: CIRISEdge#638
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
    type: "enum[here,not_fetched,not_granted,unreadable,unopened]"
    example: "here"
    renders: "the row's third line: On this device / On another device / This device can't open it / Opened, but not readable text / Can't be opened here"
    tag: "files_row_*"
```

`bytes` is read through `ByteState.of`, and **an unknown token is never
`here`**. 0.5.217 made the token list one word per fact across every drive
surface (`drive::BYTE_STATES`, `src/drive.rs:193-201`, CIRISServer#644):
`here`, `not_fetched`, `not_granted`, `withdrawn`, `evicted`, `seal_mismatch`.
The client names the first three, `unreadable` (the one note-only fact: the
bytes opened and are not UTF-8 text, `:2945`) as "Opened, but not readable
text", and renders the rest as "Can't be opened here" with the node's own
`detail`. `/v1/notes` used to say `open` for
`here`; `Note.byteState` still maps it, for a 0.5.215/216 node.

**A note is not a file.** `/v1/drive?cohort=self` lists notes too. The client
applies the node's own predicate from `read_notes`: an unnamed `text/plain`
row in the self room is a note, and Files leaves it out
(`DriveEntry.isNote`). A named `.txt` stays a file.

```yaml csd:states
populated: {tag: files_list}
empty:     {tag: files_empty, renders: "Nothing here yet — files you add to Just me stay on your own devices (family: This household has no files yet; community: nothing has been shared in these rooms yet)"}
loading:   {tag: files_loading, renders: "the frame with a progress affordance and NO sentence"}
error:     {tag: files_error, renders: "the node's refusal, in words; and files_node_too_old when the route 404s with no reason id: This node doesn't hold files yet — it is running {version}; files need ciris-server 0.5.215 or newer"}
```

### What an opened file shows (CC 5.3.2.5 and 5.3.2.6)

**First the digest (CC 5.3.2.5).** `GET /v1/files/{id}` carries
`content_digest` (lowercase hex SHA-256 of the PLAINTEXT) and
`content_digest_alg: "sha-256"` (CIRISServer `src/drive.rs:2088-2089`;
`plaintext_digest` at `:173`). `DigestCheck.of` hashes the decoded bytes
(`platform/util/Sha256.kt`, the same implementation the node code uses) and:

| the node sent | the sheet |
|---|---|
| a matching digest | opens; `file_digest_verified` says "Verified: SHA-256 2cf24dba5fb0…" |
| a digest the bytes do not hash to | **`file_unreadable`**: "Unreadable — the bytes this node sent don't match the digest it sent with them. They aren't shown or saved." No preview, no Save a copy. |
| no digest (a 0.5.215/216 node) | opens; `file_digest_not_sent` says "Not verified: this node sent no digest with the bytes. That needs ciris-server 0.5.217 or newer." |
| a digest in another algorithm | opens; `file_digest_not_sent` says which algorithm, and that this app can't check it |

The digest is over the plaintext this node opened. It is the node's word, not
a signature: a digest SIGNED into the row itself waits on CIRISEdge#638
(`src/drive.rs:170-172`), and the LISTING (`GET /v1/drive`) carries none, so
the receipt's `receipt_dimension` still says the node did not send it.

**Then the tier (CC 5.3.2.6).** The sheet never decides from the declared
`media_type`. `RenderTier.decide` works from the bytes:

1. Sniff the first 2 KB with a masked-prefix table and the `ftyp` brand.
2. Refuse a **polyglot**: a ZIP end-of-central-directory record in the last 64 KB, or bytes after PNG `IEND` or JPEG `FFD9`.
3. Require the sniffed essence to **equal** the declared one. A mismatch is a refusal, not a correction.
4. Apply the policy table.

**The policy table is the node's.** `GET /v1/media/policy` (0.5.217,
CIRISServer#643; handler `src/drive.rs:2978`, body `src/media_gate.rs:294`)
publishes `tier_a` with per-essence caps, `inline_max_bytes`,
`whole_read_max_bytes` and `renditions`. The client reads it once per Files
model and uses `MediaPolicy.RECOMMENDED.narrowedBy(node)`: the formats BOTH
tables allow, at the smaller cap. A node may narrow the recommended set (CC
5.3.2.6, `FSD/MEDIA_EDGE.md` §3) and nothing it publishes can widen this
client. Where the node has no such route (404, no id) or the read fails, the
compiled-in table stands and the sheet says so (`file_policy_builtin`). The
upload gate is the node's too: a file over `whole_read_max_bytes` — the write
door's own refusal, `drive.too_large` at `src/drive.rs:728` — is refused
before upload with the node's number in the sentence.

**What is declared downstream is the sniffed type, never the label.** The
sheet's header and the platform's "Save a copy" get `RenderTier.typeToDeclare`
(the sniffed essence); the row's `media_type` is only ever compared against it.

| sniffed | shows | save a copy |
|---|---|---|
| `text/plain`, valid UTF-8, ≤ the cap | the text, with every bidi control shown as a mark (`⟨RLO⟩`) | yes |
| `text/plain`, not UTF-8 | "not shown", no guessed rendering | yes |
| Tier A image / audio / video | **waiting for the node's rendition**: a client renders only bytes a memory-safe encoder we control produced (MEDIA_EDGE §7); the node says `renditions: false` and CIRISServer#614 has not built it | yes |
| PDF, Tier B raw (HEIC, AVIF, WebM, MOV, Ogg, FLAC, WAV), over-cap, unknown binary | no preview | yes |
| HTML, SVG, archives, executables, scripts | refused | no if it runs code (sniffed, or by the saved name's extension) |
| mismatch or polyglot | refused, in the error tone | **no** |

Tags: `file_preview_text`, `file_not_rendered`, `file_save_blocked`,
`file_unreadable`, `file_digest_verified`, `file_digest_not_sent`,
`file_policy_builtin`.

## 3. Contracts (who)

Sources: CIRISServer `origin/main` 046e1b39 (0.5.217), `src/drive.rs` and
`src/media_gate.rs`; client lines are this branch. Every drive route is the
node's and every call goes to the node URL (`ClientDrive`, read at call time;
never `$baseUrl`). Reach through the agent works from agent 2.12.1
(CIRISAgent#1213, closed by #1215); an older agent 404s them, and the direct
node URL works on every agent version, so the client keeps calling it. Rows marked "not called" are live on the node and not yet wired here.

| value | endpoint | owner | state |
|---|---|---|---|
| the listing, one cohort, one room for family | `GET /v1/drive?cohort&room_id&limit` | CIRISServer `src/drive.rs:3006` (handler `:1656`; unfiltered = the whole drive, Codex CIRISServer#628); owner session only | called — `viewmodels/FilesViewModel.kt:132` |
| one file's bytes, with the plaintext digest | `GET /v1/files/{id}?room_id` | CIRISServer `src/drive.rs:3011` (handler `:2052`; `content_digest` `:2088-2089`; `Repr-Digest` on a raw read `:2136`); 409 `drive.not_fetched`, 403 `drive.not_granted` are answers, not failures | called — `viewmodels/FilesViewModel.kt:170`; the digest is checked before render |
| add a file | `POST /v1/files` | CIRISServer `src/drive.rs:3005` (handler `:1551`); refuses over `WHOLE_READ_CAP` as `drive.too_large` (`:728`); since 0.5.217 the write gate requires an RFC 6838 type whose leading bytes agree (`drive.bad_media_type` 400, `drive.format_mismatch` 415) and a filename cleaned per RFC 6266 §4.3 (`drive.bad_filename`), CIRISServer#642 | called — `viewmodels/FilesViewModel.kt:204`; the size gate runs here first, with the node's cap, and the type declared is the SNIFFED one (`FilesViewModel.typeToDeclare`): a picker that reports no type never makes the node refuse a real PNG as `octet-stream` |
| the node's render policy | `GET /v1/media/policy` | CIRISServer `src/drive.rs:3009` (handler `:2978`, body `src/media_gate.rs:294`; `inline_max_bytes` `:323`, `whole_read_max_bytes` `:324`, `renditions: false` `:325`); public, CIRISServer#643 | called — `viewmodels/FilesViewModel.kt:156`, once per model; narrows `MediaPolicy.RECOMMENDED` |
| the households, for which room Family › Files lists | `GET /v1/families` | CIRISServer `src/family_api.rs` (CSD-100 §3) | called — `viewmodels/HouseholdsViewModel.kt:136`, the shared household model |
| the contacts the household model names people from | `GET /v1/contacts` | the api base (CSD-005, CSD-100 §3) | called — `api/HouseholdsApi.kt:85`, by the shared household model; Files reads nothing from it |
| this owner's key, so the roster can say "you" | `GET /v1/setup/owned-nodes` | CIRISServer (CSD-100 §3) | called — `api/HouseholdsApi.kt:86`, by the shared household model |
| everything about a file except its bytes, **including `content_digest`** | `GET /v1/files/{id}/meta` | CIRISServer `src/drive.rs:3017` (handler `:1872`; digest `:1917-1921`, `:1951-1952`; costs a whole read, so it is computed here and on open, never per listed row) | live, **not called** — the card opens the bytes to learn what a meta read would say |
| rename | `POST /v1/files/{id}/rename` | CIRISServer `src/drive.rs:3018` (handler `:2272`) — a new row over the SAME bytes, old row withdrawn, author only | live, **not called** |
| move to another circle ("going out asks": this call IS the ask) | `POST /v1/files/{id}/move` `{…, keep_source}` | CIRISServer `src/drive.rs:3019` (handler `:2465`) — reseals at the target room's tier; author only, and a member of the target | live, **not called**. This is the cross-circle move the Files tab needs; `keep_source: true` is "share to" |
| replace a file's bytes | `PUT /v1/files/{id}` | CIRISServer `src/drive.rs:3013` (handler `:2186`) — publishes the new row THEN withdraws the old | live, **not called** |
| withdraw a file | `DELETE /v1/files/{id}` | CIRISServer `src/drive.rs:3014` (handler `:2408`) — persist then refuses every read of the bytes (CC 2.3); holders drop it on their next pass | live, **not called** — the card has no delete |
| fetch content from a named peer by SHA-256 | `POST /v1/federation/content/{content_id}` | CIRISServer `src/federation_surface.rs:699`, owner-gated | live — called from the network hub's Content tile (`NetworkContentViewModel`, CSD-051 §3), not from Files. CIRISServer#651's "fetch" half; the directory half is the filed gap |
| a digest signed into the row; the listing's descriptor (digest, size, renditions, placeholder) | `GET /v1/drive` rows | CIRISEdge `blocked_by: CIRISEdge#638` (`src/drive.rs:170-172`) | blocks `receipt_dimension` on the receipt; the opened file is verified without it |
| renditions (display / thumb / poster) | the ingest pipeline; `derived_from` index (V149) | CIRISServer `blocked_by: CIRISServer#614`; the node says so itself (`renditions: false`) | Tier A media waits on it, and the sheet says so |

Refusals arrive as `{error: <id>, detail: <english>}`; `NodeRefusal.fromBody`
reads the id from `error` when it is dotted. The `drive.*` ids are localized.

## 4. Flow (how)

Sign in on a ≥0.5.217 node as its owner; open **Just me › Files**.

```yaml
expect:
  state: populated
  count: {of: "files_row_*", min: 1}
  visible: [files_list, btn_files_add, btn_files_refresh]
```

Open a file by clicking `files_row_<id>`.

```yaml
expect:
  visible: [sheet_file, file_digest_verified, btn_file_save_copy, btn_file_close]
```

A text file shows `file_preview_text`. On a 0.5.215/216 node the sheet shows
`file_digest_not_sent` instead of `file_digest_verified`, and `file_policy_builtin`.
Open its receipt with `btn_receipt_<id>`:

```yaml
expect:
  visible: [sheet_receipt, receipt_attester, receipt_scope, receipt_dimension, btn_receipt_close]
  text: {receipt_dimension: "did not send"}
```

**Family › Files**, in a household: `files_household` names it, and the list
is that room's (`GET /v1/drive?cohort=family&room_id=<household>`).

```yaml
expect:
  visible: [files_household]
  any_of: [files_list, files_empty]
```

With no household picked: `files_no_household`, and no `files_empty`.

## 5. QA plan

**Verified live** (desktop, scratch ciris-server 0.5.215 on an isolated home,
2026-09-24): empty state, listing, opening a text file with preview, the
receipt, writing a note from the UI and reading it back from `/v1/notes`.
Live testing found two bugs, and both are now fixed with tests:

- Notes were listed as "Untitled" files.
- Readable notes rendered "Can't be opened here" because of the `open` / `here` token.

**Verified by test, not yet live** (the 0.5.217 pass, 2026-09-28): the digest
check (`DigestCheckTest`: the FIPS vectors, the wire shape, a mismatch is
`Unreadable`, no digest is `NotSent`), the policy read (`FilesViewModelTest`,
`RenderTierTest`: one read per model, narrows and never widens, the node's cap
gates the upload, a 404 leaves the built-in table with its reason), the family
room (`FilesViewModelTest`: no room asks nothing; the room is on the line; an
add goes there), and that every drive call reaches the node's socket while an
agent at the api base sees none of it (`DriveWireTest`, two real sockets).

**Not tested here.** The native file picker (a platform dialog, not drivable
over `/act`). Upload was exercised through the view model and `POST /v1/files`
directly. Also untested: community rooms, since no community room existed on
the scratch node, and the not-fetched / not-granted states, which need a
second device (view-model tests cover them). A 0.5.217 node has not been run
against this build; the digest and policy behaviour is asserted against the
handler source cited in §3.
