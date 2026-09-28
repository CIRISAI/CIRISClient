# CSD-104 — Verify someone's key (the peer detail's short-code ceremony)

**CSD**: CSD-104 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the route-coverage report ("the strongest trust act the client can perform", called and uncited)
**Covers**: the existing peer detail, `Screen.NetworkPeerDetail` (`NetworkPeerDetailScreen.kt` + `NetworkPeerDetailViewModel.kt`). No CSD owned that screen before this one. It is not a new card.
**Reads with**: CSD-051 (the Everyone hub that opens the peer list), CSD-050 (cites `GET /v1/federation/peers` for the circle hub), CSD-005 (People: where the ceremony should also be reachable, §2)
**Flow**: `testing/flows/drafts/csd-104-key-verification.yaml` (floor `unreleased`)

```yaml csd:stage
stage: building
owner: CIRISClient
```

**Why `building`, and why not `testable` (§3).** Every contract the screen
uses is confirmed against CIRISServer source. The three fields no route serves
each name the issue that asks for them: a distinct mismatch record and the
peer list's verification state (CIRISServer#684), and the person × person code
that the People entry needs (CIRISServer#683). For node keys, the only keys the
screen is reached from today, the node side works. For a person's key it does
not: the two sides compute their codes over different key pairs, so two honest
people would see different codes.

## 1. Mission (why)

**Two people each read a short code off their own screen and compare them
over a channel they already trust: in person, or on a call. Each then records
whether the codes matched.** Only after that does "the key my node holds for
them" become "the key I checked with them". When the codes differ, the app
treats it as a safety event, never as a cancel. Someone may be between the two
nodes, or the key the node holds belongs to someone else.

Serves **Integrity**. What the ceremony establishes is narrow, and the screen
has to say so in words:

* **It establishes** that the Ed25519 public key my node holds under their
  `key_id` is the key their node holds for itself, and the reverse. The code is
  `ciris_edge::sas` over the *sorted* pair `(local_pub, peer_pub)` plus the
  protocol constant `ciris-edge::peer-sas::v1` (CIRISServer
  `src/federation_peers.rs:29-33, 825-836`). So both sides derive the same five
  words and six digits exactly when they hold the same two keys.
* **It does not establish** who they are, what they may do, or how they
  behave. It confers no `trust:{job}:{version}` (CC 3.1.1, reserved), no
  `licensure:*` and no `capacity:*`. It also says nothing about the **ML-DSA-65
  half**. The code is computed over the 32-byte Ed25519 keys only (`:867-898`),
  while CC 5.3.2.4.3.1 requires a key to be hybrid. A matching code verifies
  the classical half of a hybrid key, and the screen says so
  (`network.peer_detail.sas_covers`).

**Who can see that you checked: only you.** The outcome is written as one
signed `config:*` row per peer, `federation.peer_sideband.<key_id>`
(`src/federation_peers.rs:220-329`). Every config row is authored at
`cohort_scope: self` with `witness_relation: self`
(`src/graph_config.rs:133-153, 274-293`). CC 3.4.5.1 requires exactly this:
*"a leaf whose content is exactly what structural invisibility protects —
`admission`, `transport` (bootstrap peers, **peer sideband**) — MUST be emitted
at `self`"*. The row does not replicate, is not directory-advertised, and is
never sent to the person you checked. The screen says so on the card and in
every confirm, because people who know the Signal-style ceremony expect the
other side to be told.

**It can be withdrawn.** `PUT …/sas {"verified": false}` clears
`verified_at` (`:807-808`). The config plane's latest-wins fold
(CC 3.4.5.1 ¶3) makes the new row supersede the old one. Superseding only works
forward: the earlier "verified" row stays in the record and stops being live.

**What CC does not have, so this CSD does not invent it.** No registry family
means "I compared codes with X". `config:{scope}` is the right family for a
*private* note (CC 3.1.9, "only ever about the emitting node"): the row's
subject is the node, and the peer's id appears only in the key name. A
*shareable* verification that others could weight would be a `scores`
attestation about another party. That would be consent-gated (CC 3.1.5) and
would need a family of its own. It is out of scope here, and the ask would go
to CIRISConstitution.

## 2. Surface (what)

```yaml csd:surface
surface: null
screen: NetworkPeerDetail
flow_only: true
entry: Everyone › Rules › Global Commons (the Reticulum hub, CSD-051) → the Peers tile (Screen.NetworkPeers) → a peer row, or a node in the trust graph (Screen.NetworkTrustGraph) — both call `onPeerClick(keyId)` (CIRISApp.kt, the `Screen.NetworkPeers` / `Screen.NetworkTrustGraph` arms)
exit: back to `Screen.NetworkPeers`; `screenToSurface` keeps `layer-global-commons` lit while it is open
```

**This is the existing screen, fixed in place.** Nothing here adds a surface,
a screen or a view model. The peer detail already read the code
(`getFederationPeerSAS`), already set trust, and already edited appearance. It
could not finish the ceremony (§3a). The fix lives in `NetworkPeerDetailScreen.kt`
and `NetworkPeerDetailViewModel.kt`, and the ceremony card now sits directly
under the header, above trust, because it is the act that should decide trust.

**Placement: two ways in, one screen.**

* **Now: the Everyone peer list.** The peer list is
  `GET /v1/federation/peers`, the federation directory: nodes, stewards, accord
  holders, wise authorities, partners, witnesses and agents
  (`src/federation_peers.rs:99-107`). For those node keys today's code is
  honest. Entry is unchanged.
* **Next: a person in People (CSD-005), in every circle, routed to this same
  screen.** A person checks a *person* while talking to them, so the action
  belongs on that person's row. **It was deliberately not added.** For a
  person's key, the two sides can never compute the same code (§3, CIRISServer#683).
  An entry there today would send two honest people into the "they don't
  match" safety flow every time. Once CIRISServer#683 lands, the People row gets a
  control that sets `Screen.NetworkPeerDetail(contact.keyId)`: one line in
  `CIRISApp.kt`, no copy of this screen.
* **Rejected: a Safety-tab card.** A mismatch is a safety event, and Safety is
  where a frightened person looks (`CirclesNav.kt`: *"one place, because in an
  emergency…"*). But a Safety card would be a second list of the same
  directory keys the peer list already shows, under a different name. The
  mismatch result stays on this screen in the error tone until the person
  leaves, and it lands as an `untrusted` override that the peer list shows.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:sas_words
    use: display-only
    type: "list[string]"
    example: ["abandon", "ribbon", "gravity", "orbit", "velvet"]
    renders: "abandon · ribbon · gravity · orbit · velvet (mono)"
    tag: text_sas_words
    assert:
      matches: {text_sas_words: "^[a-z]+( · [a-z]+){4}$"}
  - ceg: x_private:sas_digits
    use: display-only
    type: string
    example: "482915"
    renders: "482 915"
    tag: text_sas_digits
    assert:
      matches: {text_sas_digits: "^[0-9]{3} [0-9]{3}$"}
  - ceg: config:{scope}
    bind: {scope: federation.peer_sideband.9f3c2a71e0b4d815}
    use: read
    type: bool
    example: true
    renders: "Checked — Yes — you checked this key on 2026-09-25 (ok tone), or: Not checked yet"
    tag: text_sas_state
  - ceg: x_private:sas_verified_at
    use: display-only
    type: timestamp
    example: "2026-09-25T18:04:11Z"
    renders: "the date on the Checked line"
    tag: text_sas_state
  - ceg: x_private:cohort_scope
    use: display-only
    type: "enum[self]"
    example: "self"
    renders: "Who can see this — Only you. Your node keeps it; they are not told."
    tag: text_sas_scope
  - ceg: x_private:sas_outcome_mismatch
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Codes did not match on 2026-09-25 — a record that outlives the screen and differs from 'never checked'. Today the node can store only verified:false, the same row a withdrawal writes, so the mismatch lives only as the result on screen plus the untrusted override."
    tag: "proposed:text_sas_state_mismatch"
    blocked_by: CIRISServer#684
  - ceg: x_private:sas_words_person
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "the same ceremony reached from a person in People, over (my identity key, their identity key)"
    tag: "proposed:btn_people_verify_key"
    blocked_by: CIRISServer#683
  - ceg: x_private:peer_list_sas_verified
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "a Checked chip on each row of the peer list, so you can see which keys you have compared without opening each one"
    tag: "proposed:chip_peer_sas_checked"
    blocked_by: CIRISServer#684
```

**The Checked line never comes from `trust`.** A directory row reads
`"trusted"` by default (`src/federation_peers.rs:411-413`: *"a directory row is
an admitted key ⇒ trusted"*). So on the trust control, a key nobody has checked
looks the same as one checked in person. The Checked line is the only place
that reads a verification.

```yaml csd:states
populated: {tag: card_sas_verify, renders: "the code (text_sas_words, text_sas_digits), the Checked line, Who can see this, and the two answers btn_sas_match / btn_sas_mismatch (btn_sas_withdraw when checked)"}
empty:     {tag: sas_not_in_directory, renders: "Your node has heard this key but hasn't admitted it, so there is nothing to compare yet. — the 404 PEER_SAS_UNAVAILABLE (:856-864), a fact about the KEY, in the mute tone"}
loading:   {tag: sas_loading, renders: "the progress affordance inside the card and NO sentence"}
error:     {tag: sas_error, renders: "Could not read — danger tone, glyph and border, the node's words under it; never the not-in-directory sentence, and never an unending spinner"}
```

Four more states on this screen. Each is its own tag, so a flow can tell them
apart without reading copy:

| state | tag | renders |
|---|---|---|
| route absent | `sas_not_on_this_node` | a 404 with no `error` id: "This node can't compare keys yet. It needs ciris-server 0.5.115 or newer." |
| **mismatch recorded** | `sas_result_mismatch` | danger tone, above the card, until the screen is left: "The codes did not match. Don't share anything private with this key…" |
| write refused | `sas_write_error` | the reason by remedy: not the owner (401/403), key not in the directory (404 `PEER_NOT_FOUND`), route absent (bare 404), failed. If a mismatch's untrust landed but its record did not, it says the untrust landed. |
| peer not readable | `peer_detail_error` vs `peer_detail_not_found` | a failed peer read and a 404 no longer render the same "Peer not found" sentence |

**A mismatch is not a cancel.** Each answer has its own button, tag,
ConfirmSheet and result. `btn_sas_mismatch` uses the danger fill. Dismissing
any sheet writes nothing. Confirming a mismatch first writes
`trust: untrusted`, then `verified: false`. The protective write goes first so
that, if only one lands, it is that one. The danger result then stays up.

## 3. Contracts (who)

All node-owned, all on the node URL (`LOCAL_NODE_URL`), none proxied by an
agent. CIRISServer `origin/main` 046e1b39 (0.5.217).

| value | endpoint | owner | state |
|---|---|---|---|
| one peer | `GET /v1/federation/peers/{key_id}` → `{peer, reachability:null}` | CIRISServer | live. `:629-655`. A miss is `404 {"error":"peer not found"}` (`:637`) with no id. That includes an announced key the list shows (`:461-500`), because `lookup_public_key` misses it. |
| the code + recorded outcome | `GET /v1/federation/peers/{key_id}/sas` → `{data:{key_id, words[5], digits, verified, verified_at}}` | CIRISServer | live since 0.5.115 (CIRISServer#261). `:825-936`. Unauthenticated (`:837-839`): the code is a pure function of two public keys, so reading it proves nothing, and the security is the human comparison. A directory miss is `404 {"error":"PEER_SAS_UNAVAILABLE",…}` (`:856-864`). |
| record the outcome | `PUT /v1/federation/peers/{key_id}/sas` `{"verified": bool}` → `{data:{key_id, verified, verified_at}}` | CIRISServer | live. `:784-823`. **Had no caller in this client until now.** Needs the owner session (SYSTEM_ADMIN + FullAccess, `:169-201`) on an owner-bound node (`:203-218`), else `401`/`403`. `404 PEER_NOT_FOUND` for a directory miss (`:143-153`). |
| stop trusting after a mismatch | `PUT /v1/federation/peers/{key_id}/trust` `{"trust":"untrusted"}` | CIRISServer | live. `:707-749`. Same gates. |
| "rename a peer" | `PUT /v1/federation/peers/{key_id}/appearance` | CIRISServer | live, `:751-782`, but **not a rename**. The body is `{icon?, fg_color?, bg_color?}` (`:248-259`). `alias_override` is hard-coded `None` (`:416`), and no route writes it. |
| **a distinct mismatch record** | `PUT …/sas` | CIRISServer | **missing.** `verified:false` is the only other value, so a mismatch, a withdrawal and "never checked" all read the same. CIRISServer#684. |
| **the person pair** | `GET …/sas` | CIRISServer | **wrong for people.** `local_pub` is the NODE's signer (`:881-898`). A contact is a *person's* identity key, and a chat pairs `owner.key_id` with the contact (`src/contacts_chat.rs:1693`). A computes `sas(node_A, person_B)` and B computes `sas(node_B, person_A)`: different pairs, so the codes never match. CIRISServer#683. |
| verification on the peer list | `GET /v1/federation/peers` | CIRISServer | **missing.** `LocalPeerState` has no `sas_verified` (`:331-353`), so the list cannot say which keys you have checked without one request per row. CIRISServer#684. |

### 3a. What the code got wrong, and what this CSD's change fixes

1. **Fixed — the ceremony never finished.** `SASModal` showed the words and
   digits with one button, Close. `PUT …/sas` had no caller in the client, so
   this app had never recorded a verification. The modal is gone. The card now
   has "They match" / "They don't match" / "Withdraw my check", each behind a
   ConfirmSheet naming three facts: whose key, what changes, who signs and who
   sees.
2. **Fixed — a recorded verification was invisible.**
   `FederationPeerSASResponse` dropped `verified` / `verified_at`, which the
   node sends (`:930-931`). It now decodes them, and the Checked line reads
   them.
3. **Fixed — the instructions told the person to do the wrong thing.**
   `network.peer_detail.sas_instructions` read *"If both sides match, the peer
   is verified — flip trust to TRUSTED."* Every directory key is already
   `trusted` (`:411-413`), and trust is not verification. The screen now uses
   `sas_ceremony_instructions`, and the old key is deleted from every bundle.
4. **Fixed — a failed code read spun forever.** The error went to a banner
   behind the dialog, and the dialog's `sas == null` branch was a spinner. The
   read is now classified (`sasReadFailureOf`): not in the directory, route
   absent, or failed. Each has its own tag.
5. **Fixed — a failed peer read said "Peer not found".** `detail == null &&
   !loading` rendered one sentence whatever the cause. Now a 404 renders
   `peer_detail_not_found`, which explains announced-but-unadmitted keys, and
   anything else renders `peer_detail_error` in the danger tone.
6. **Fixed — the node's refusal was flattened.** `getFederationPeerSAS` threw
   `RuntimeException("… 404 …")`, which made `PEER_SAS_UNAVAILABLE`
   indistinguishable from a missing route. It now throws `NodeRefusal`.
7. **Fixed — a false comment.** `CIRISApiClient.kt`'s federation header said
   `peers/{key_id}/sas`, `/trust`, `/appearance`, `identity`, `metrics`,
   `content` and `events` had "NO node route" and that their methods "throw
   immediately". Both claims were false: all seven have been served since
   0.5.115 (CIRISServer#261), and every method beneath the comment calls the
   node. That comment is how a called route came to have no card. It now lists
   the routes, and names only what is still missing (#683, #684).
8. **Not fixed — "rename" is not a rename.** The header shows `aliasOverride`,
   which the node never sets, so every peer is titled by its key id. The
   appearance editor takes free-text hex colours. That is a server gap (no
   alias write, CIRISServer#684) plus a client choice. Neither belongs to the
   ceremony.
9. **Not fixed — the block confirm is an `AlertDialog`, not a ConfirmSheet.**
   `confirm_block_*` on the trust control predates the primitives and names
   no facts.

### 3b. Filed issues

| gap | issue | what it blocks |
|---|---|---|
| **A** — a SAS mismatch has no record of its own. `PUT …/sas` takes `{verified: bool}`, and `false` is what both a withdrawal and a failed comparison write (`src/federation_peers.rs:807-808`). Until it lands the client writes `trust:untrusted` + `verified:false`, and the mismatch survives only as the trust override. | CIRISServer#684 | `text_sas_state_mismatch` |
| **B** — a person's code is derived over node × person. `get_peer_sas` takes `local_pub` from the node's signer (`:881-898`), while a chat pairs `owner.key_id` with the contact (`src/contacts_chat.rs:1693`), so two people never see the same code. | CIRISServer#683 | the People entry (`btn_people_verify_key`) |
| **C** — `LocalPeerState` has no `sas_verified` / `sas_verified_at`, though the sideband row holds them (`:240-245`) and `to_peer` already overlays trust and appearance from it (`:411-415`). | CIRISServer#684 | `chip_peer_sas_checked` |
| the appearance route is not a rename: `alias_override` is always `None` (`:416`) and nothing writes it | CIRISServer#684 | the header's title (§3a.8) |

## 4. Flow (how)

`Screen.NetworkPeerDetail` is `flow_only`: the runner reaches it by opening a
peer from `NetworkPeers`, which `nav_map` cannot express as a hop. Precondition:
an owner-bound node that has admitted at least one other key.

```yaml
expect:
  screen: NetworkPeerDetail
  visible: [card_sas_verify, text_sas_words, text_sas_digits, text_sas_state, text_sas_scope, btn_sas_match, btn_sas_mismatch]
  matches: {text_sas_digits: "^[0-9]{3} [0-9]{3}$"}
```

"They don't match" opens its own sheet, never the match sheet. Cancelling it
writes nothing and leaves the ceremony as it was.

```yaml
do:
  - click: btn_sas_mismatch
expect:
  visible: [sheet_sas_mismatch, sas_mismatch_fact_1, sas_mismatch_fact_2, sas_mismatch_fact_3, btn_sas_mismatch_confirm]
  absent: [sheet_sas_match]
```

```yaml
do:
  - click: btn_sas_mismatch_cancel
expect:
  absent: [sheet_sas_mismatch, sas_result_mismatch]
  visible: [btn_sas_mismatch]
```

Confirming it is optional on the matrix, because it writes a trust override on
the fixture node. It shows `sas_result_mismatch`, and `text_sas_state` reads
"Not checked yet".

## 5. QA plan

**Platforms.** All five. Every contract is a node route, so a bare node
exercises the whole screen. The agent build adds nothing.

**Tested in `commonTest` (`NetworkPeerDetailSasTest`, 14 tests)**:
* The outcome → writes table. Match writes `verified:true` only. Mismatch
  writes `trust:untrusted` and then `verified:false`. Withdraw writes
  `verified:false` only. Mismatch ≠ withdraw.
* The run: all writes landed means recorded. The first refusal stops it and is
  reported with what already landed. A refusal is never shown as recorded.
* Read classification: 404 `PEER_SAS_UNAVAILABLE` → not in the directory;
  bare 404 → route absent; anything else → failed.
* Write classification by remedy: 401/403 → owner, 404 `PEER_NOT_FOUND` →
  key, bare 404 → route.
* Peer-read classification: 404 ≠ failed.
* The decode of the node's real `…/sas` and PUT bodies.

**Negative-tested**: with three planted defects (a mismatch that writes what a
withdrawal writes, every 404 read as failed, and a refused write swallowed),
5 of 14 went red.

**Not tested here.**
* That two real nodes derive the same code. That property belongs to
  CIRISServer and CIRISEdge (`peer_sas` is test-proven byte-identical upstream,
  `:829-834`), and the matrix has one node.
* The QR variant. Scanning the other side's code removes the reading aloud and
  the transcription errors that come with it, which makes it the obvious next
  step. It waits for the QR primitives (#99, unmerged), and nothing here
  depends on them.
* The People entry, which is blocked on CIRISServer#683.

**What this does not guarantee, every time**: that the person on the call is
who you think they are (the ceremony moves trust onto the channel you compared
over), that the ML-DSA-65 half matches, or that anyone else learns you
checked.
