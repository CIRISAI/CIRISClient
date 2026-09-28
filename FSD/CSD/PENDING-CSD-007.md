# Pending for CSD-007 (Files): contract rows to apply when #77 lands

**Why this is a separate file.** `CSD-007-files.md` is not on `main`. It lives on
`origin/feat/b3-files` (PR #77, head `20eb1af`), and editing a CSD that is not on
the branch would create a second copy of it. These rows belong in its §3
contracts table and in two of its "not built" lines. Whoever merges #77 (or
rebases it onto the citation pass) applies them and deletes this file in the
same commit. The name deliberately does not match `CSD-*.md`, so
`packaging/gates.sh` does not try to validate it as a CSD.

Sources: CIRISServer `origin/main` 046e1b39 (0.5.217). `src/drive.rs` and
`src/media_gate.rs` are byte-identical on `origin/integ/0.5.218` (97900cf5), so
every line below holds for both. Client lines are `origin/feat/b3-files`.

## 1. Rows to add to §3

| value | endpoint | owner | state |
|---|---|---|---|
| everything about a file except its bytes, without opening them, **including `content_digest`** | `GET /v1/files/{attestation_id}/meta` | CIRISServer `src/drive.rs:3017` (handler `:1872`; digest `:1951-1952`) | live, **not called** — the card opens the bytes to learn what a meta read would say |
| rename | `POST /v1/files/{attestation_id}/rename` | CIRISServer `src/drive.rs:3018` (handler `:2272`) — a new row over the SAME bytes, old row withdrawn, author only | live, **not called** |
| move to another circle ("going out asks": this call IS the ask) | `POST /v1/files/{attestation_id}/move` `{…, keep_source}` | CIRISServer `src/drive.rs:3019` (handler `:2465`) — reseals at the target room's tier; author only, and a member of the target | live, **not called**. This is the cross-circle move the Files tab needs; `keep_source: true` is "share to" |
| replace a file's bytes | `PUT /v1/files/{attestation_id}` | CIRISServer `src/drive.rs:3013` (handler `:2186`) — publishes the new row THEN withdraws the old | live, **not called** |
| withdraw a file | `DELETE /v1/files/{attestation_id}` | CIRISServer `src/drive.rs:3014` (handler `:2408`) — persist then refuses every read of the bytes (CC 2.3); holders drop it on their next pass | live, **not called** — the card has no delete |
| edit a note | `PUT /v1/notes/{attestation_id}` | CIRISServer `src/drive.rs:3023` (handler `:2793`) — new row, old withdrawn | live, **not called** |
| withdraw a note | `DELETE /v1/notes/{attestation_id}` | CIRISServer `src/drive.rs:3023` (handler `:2843`) | live, **not called** |
| fetch content from a named peer by SHA-256 | `POST /v1/federation/content/{content_id}` | CIRISServer `src/federation_surface.rs:699`, owner-gated | live — called (`CIRISApiClient.kt:1856` on the branch) but from the network hub's Content tile (`NetworkContentViewModel`), not from Files. CIRISServer#651's "fetch" half; the directory half is the filed gap. Cited on `main` in CSD-051 §3 |

`PUT`/`DELETE /v1/files/{id}` were not in the route-coverage report's list of
four: CSD-007 names `GET /v1/files/{id}`, and the other two verbs on the same
path went unnoticed because the path was "covered".

## 2. Two stale lines to correct

**§3 row "the node's render policy": `GET /v1/media/policy` is built.**
`src/drive.rs:3009`, handler `:2978`, returning `media_gate::policy()`, which
states `"renditions": false` (`src/media_gate.rs:325`, doc `:292`). Ungated.
Shipped in 0.5.217 (CIRISServer#643). The client still uses
`MediaPolicy.RECOMMENDED` and does not read it (no `/v1/media/policy` call site on
the branch). New state: **live, not called**.

**§3 row "the descriptor (digest, …)" and the "Not done: full-SHA verification" paragraph at `:118-121` ("Neither
`/v1/drive` nor `/v1/files/{id}` carries a digest, so the client has nothing to
verify against").** 0.5.217 ships `content_digest` + `content_digest_alg:
"sha-256"` on `GET /v1/files/{id}` (`src/drive.rs:2088-2089`) and on `/meta`
(`:1951-1952`), plus an RFC 9530 `Repr-Digest` header on a whole-representation
read (`:178`). CIRISServer#641 is closed. The digest is over plaintext; a digest
signed into the row itself still waits on CIRISEdge#638 (`:170-172`), and the
listing (`GET /v1/drive`) is not claimed here. So: the verification the CSD says
cannot happen can now happen per file, and does not yet happen because nothing
compares.

## 3. What this does not change

The stage. None of these rows is a `csd:shows` field on the branch, and whether
the §3 row that says CIRISServer#641 "blocks `building` for `receipt_dimension`"
(`:132`) still blocks anything is a decision for the card's owner with the
checker, not for a citation pass.
