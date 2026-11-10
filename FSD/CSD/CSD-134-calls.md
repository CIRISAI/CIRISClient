# CSD-134 — Calls (a room's live streams, and whether this one is saved)

**CSD**: CSD-134 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: Eric's direction, 2026-11-04, via CIRISEdge
**Reads with**: CSD-091 (the room a call starts from), CSD-103 (Chats), CSD-106 (seating a new member: the quorum item), CSD-088 (the first-run rung that says what a watcher sees of a call)
**Design**: CIRISEdge `FSD/AV_CALL_API.md`; CC 5.3.3.2 (group calls), CC 4.5.13 (the choice as data)
**Scope (Eric, 2026-11-10)**: calls with N participants, audio and video, with every option the routes expose. The routes in §3 are CIRISServer's proposal for 0.5.246 (CIRISServer#859), adopted here with the changes in §6
**Flow**: unwritten — no capture, no route and no screen exist yet

> **SCOPE.** A card, not a section of CSD-091: a call belongs to a room of any
> size, and CSD-091 is the two-person chat. CSD-091 gains one row, the Call
> action in its header. This card owns the call itself and the save choice.

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person in a room can talk and be seen by that room, knows who else is there, and
decides whether their own stream is saved.**

Falsifiable: nobody joins a call without being told, before they publish, whether streams
in this room may be kept; nobody's stream is saved except by them; and a person who asked
to save is never left believing it saved when it did not.

Two rules shape it:

* **CC 5.3.3.2** — a call is each member's `live_stream` in the room. There is no call
  row and no new wire. Joining is presence, not admission: any active member may join.
* **CC 4.5.13** — "save this call" is a `choice` item whose write is the kept stream,
  with benefit, downside and an impact derived from the family model. A saved call has
  `history_on_join: full` (CC 5.1).
* **CC 5.3.3.2.5** — each stream is kept only by its own publisher, because it carries only
  that person's voice and picture. "Save this call" saves YOUR stream and never anyone
  else's. The room's policy may forbid keeping and never requires it. Joining is informed,
  and consents to nothing beyond being in a room where others may keep their own streams.

## 2. Surface (what)

```yaml csd:surface
surface: null
flow_only: true
screen: Call                      # ui/screens/CallScreen.kt; entered from UserChat's btn_chat_call
scopes: [agent, family, local_community, global_communities, global_commons]
entry: "the Call action in a room's header (CSD-091, and the room view of CSD-103)"
exit: "back to the room the call was started from"
```

| Control | What it does | Note |
|---|---|---|
| Start or join | Publishes this device's stream in the room's call (`POST /v1/chat/{room}/call`) | Asks for microphone, and camera for video, at first use. The Call action reads Join while a call is live |
| Tiles | One per publisher, keyed by the publisher's key, never by arrival order: an active-speaker view and a grid | Each tile says video paused or audio only in words, so a frozen face is never the only sign |
| Participants | Everyone attending, and who is publishing audio, video or neither | From the call's attendee list and its join and leave events |
| Mute, camera off | Stops sending that source | Local and immediate; nothing is recorded about it |
| Receive quality, per member | Asks for less of one member's video (`POST …/call/layers`) | Changes only what this device receives, never what others get |
| Leave | Ends this device's stream (`DELETE /v1/chat/{room}/call`) | The call goes on for the others. Rejoining starts a new stream |
| Save my stream | A `choice` item, this person's own stream only; absent where the room forbids keeping | **Listed, not available** until CIRISPersist#1067 |
| Ask others to save | Optional: a request item to each publisher, each answering for their own stream | With saving |

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:call_participants
    use: display-only
    type: unconfirmed
    example: "Ada, Sam"
    renders: "who is in the call now, from the reachable set (CC 5.3.3.4): node-local, never an attestation, never logged"
    tag: "proposed:call_participants"
  - ceg: x_private:call_save_state
    use: display-only
    type: unconfirmed
    example: "not saved"
    renders: "always on screen while in a call: whether MY stream is saved, and which others' are. Never absent, so silence never reads as either"
    tag: txt_call_save_state
    blocked_by: "CIRISPersist#1067"
  - ceg: x_private:call_save_choice
    use: emit
    type: unconfirmed
    example: "dont_save"
    renders: "a choice item with benefit and downside on each option. While unavailable: disabled, with 'Saving calls isn't ready yet.' A refused keep (`keep_deferred_cirispersist_1067`) is said in words, and the call goes on unsaved"
    tag: opt_call_save_mine
    blocked_by: "CIRISPersist#1067"
  - ceg: x_private:call_room_may_keep
    use: display-only
    type: unconfirmed
    example: "Others in this room may save their own streams"
    renders: "stated before this person publishes: whether streams in this room may be kept. A later joiner reads a kept stream only from the epochs whose keys they hold"
    tag: "proposed:txt_call_room_may_keep"
  - ceg: x_private:call_visible_to_watcher
    use: display-only
    type: unconfirmed
    example: "A watcher can see this call"
    renders: "at Hide when I talk, calls run on unhidden connections: stated before joining, as the first-run list states it"
    tag: "proposed:txt_call_not_hidden"
```

```yaml csd:states
loading:   {tag: "proposed:call_connecting", renders: "'Connecting…' with the room's name, and before publishing the two facts a joiner must see: whether streams here may be kept, and whether a watcher can see this call"}
empty:     {tag: "proposed:call_alone", renders: "'Waiting for others to join.' Joining alone is valid; others in the room see the call on their Call action"}
error:     {tag: call_microphone_denied, renders: "the named refusal in words: no microphone permission, not a member, or this device's privacy setting has no calls"}
populated: {tag: "proposed:call_screen", renders: "the tiles, participants, controls and the save state"}
```

| State | Behaviour |
|---|---|
| Always hide when I talk, or an anonymous identity | Calls do not exist there: no Call action, and no empty shape where it would go. A refusal token from a stale route is said in words |
| Hide when I talk | Calls run, unhidden; the line above says so |
| Traffic limit, or the service ladder shedding | Video drops before audio. The tile says video paused, so a frozen face is never the only sign |
| A receiver asks for less | `set_layer_policy` per member; never changes what others receive |
| Member removed mid-call | The room's Remove rolls every stream's key: that person's tiles stop, and they are told |
| Late joiner | Joins a call already live and sees every current publisher's tile; earlier frames are not replayed |
| Member added mid-call | The room seats them (CSD-106; in a room of three or more, a majority of current members must approve), the epoch rolls, and nobody sees a gap |
| A leaver rejoins | A new stream: a new tile, never the old one resumed |
| Service tier changes | The tier is shown; video drops before audio, tile by tile |
| Remote client (the node is elsewhere) | Works over the same route, on that connection's bandwidth; labelled if it is the limit |
| No camera or denied | Audio-only, stated; the call is not refused |

**Built (client 0.5.239).** The Call action in a room's header (`btn_chat_call`), shown
only when the node declares `chat:call` (`Capability.CHAT_CALL`); no node does yet,
so it is hidden everywhere today. The screen offers a voice or a video call
(`btn_call_voice`, `btn_call_video`) and asks for what that call needs at that
moment, never at launch (`platform/CallMedia.kt`, `callPermissionStep`):

| Call | Asks for | If refused |
|---|---|---|
| Voice | Microphone | No call: `call_microphone_denied`, with how to turn it on and `btn_call_try_again` |
| Video | Microphone, then camera | Microphone refused: no call. Camera refused or missing: a voice call, said (`call_audio_only`) |

| Platform | Microphone | Camera |
|---|---|---|
| Android | `RECORD_AUDIO`, in the library manifest, `android.hardware.microphone` not required | `CAMERA`, already declared for QR |
| iOS | `NSMicrophoneUsageDescription` | `NSCameraUsageDescription`, now naming video calls |
| Desktop, web | No capture yet: `call_unavailable` (`platform`) | Same |

A second refusal in one attempt counts as a refusal, because Android reports a
once-refused permission as still askable. A ready call says it cannot connect yet
(`call_not_connecting`): capture, encoding and the connection to the node are not
built, and the node has no call routes (CIRISServer#859). Saving shows as not ready
(`opt_call_save_mine` disabled, `txt_call_save_unavailable`). An agent build's own iOS
host (`apps/ios`) needs the same two Info.plist keys.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| join, publish, frames, layer policy, leave | `Edge::join_call` and its PyO3 twins | CIRISEdge | **live** at the v44 candidate; live and unkept only. 2-person calls verified (300/300 frames each way, p50 8–13 ms, direct legs) |
| is a call live, before joining | `GET /v1/chat/{room}/call` → `{live, attendees[], my_state, frame_budget_bytes, service_tier, save_state, room_may_keep: {allowed, says}, visible_to_watcher: {visible, says}}` | CIRISServer | **agreed for 0.5.246** (CIRISServer#859). `live` = a `chat:call:v1` row seen within 60 s, re-issued while attending. Both facts are present before join; each `says` is a `call.*` id with English, localized like the refusals |
| join | `POST /v1/chat/{room}/call {video, relay?, default_layer?}` → `{call_id, stream_id, epoch, frame_budget_bytes, service_tier, attendees[], legs[]}`; refusals `call.not_a_member`, `call.anonymous_mode`, `call.privacy_rung`, `call.room_locked`, `call.keep_refused` (the call goes on unkept), `call.plumbing` | CIRISServer | **agreed for 0.5.246**; the person's signer goes to `join_call_as` |
| leave | `DELETE /v1/chat/{room}/call` | CIRISServer | **agreed for 0.5.246** |
| an encoded frame in and out | `WS /v1/chat/{room}/call/frames` v1. One binary message is one frame, integers big-endian. Out: `ver u8 (=1) \| codec_id u8 \| spatial u8 \| temporal u8 \| quality u8 \| payload`. In: `ver u8 (=1) \| codec_id u8 \| spatial u8 \| temporal u8 \| quality u8 \| pub_len u16 \| publisher (UTF-8 key_id) \| stream_id [32] \| epoch u64 \| chunk_seq u64 \| payload`. A payload over `frame_budget_bytes` is refused by event, never cut. Text events, one JSON object each, members as key ids only (never display names): `attendee_joined {member, stream_id}`, `attendee_left {member, stream_id}`, `epoch_rolled {epoch}`, `tier_changed {tier}`, `video_paused {member, reason}`, `keep_refused {reason_id}`, `publish_refused {reason, budget?}` | CIRISServer | **agreed for 0.5.246**. Golden vectors at CIRISServer `tests/vectors/call_frames_v1.json`, pinned on both sides; a `ver` other than 1 is refused by name |
| what this device receives from one member | `POST /v1/chat/{room}/call/layers {member: key_id, policy: {max_spatial, max_temporal, max_quality} \| "audio_only"}` → Edge's `ReceiverLayerPolicy` | CIRISServer | **agreed for 0.5.246**. Applied on this node's leg only; nothing goes to the member or anyone else |
| the Call action exists at all | capability `chat:call` on the node's served capabilities, once the routes exist | CIRISServer | **agreed for 0.5.246**; the client already gates on it |
| who is here | the attendee list and its events, above | CIRISServer | **agreed for 0.5.246** |
| seating a third member | `GET /v1/self/decisions?circle=`, `POST /v1/self/decisions/{id}/answer`, kind `quorum_cosign` first | CIRISServer | **proposed for 0.5.246**. Without it a third invitee accepts and is never seated; the item belongs to CSD-106 and the served queue (CC 4.5.13) |
| a call is live in this room, N in it | `join_call` writes this attendee's `chat:call:v1` row at room scope, and the server folds those rows | CIRISEdge **live**; CIRISServer **missing** | the Call action becomes Join. A leaver's row stands and no row marks the end, so "live" needs a server rule (§6) |
| waking a closed app | push-to-wake on that row, content-free | CIRISServer#859 | **missing**: until then a person with the app closed sees nothing |
| keeping a stream | `CallOptions::keep = Kept` | CIRISEdge · CIRISPersist#1067 | **refused by name** today; #1068, #1069 also wait |
| the save choice item, and whether this room forbids keeping | the room's policy row | CIRISServer | **missing** |
| capture, encode, decode, render | platform code in the client | CIRISClient | **missing**, every platform |

**Where frames run (recommended).** Capture and encode in the client, then encoded frames
over a local server socket to the node, which hands them to Edge.

* Edge's `Frame` is already encoded bytes with a `codec_id`, so Edge never sees a camera.
* It is one path for every platform and for a remote client. A native surface would be
  UniFFI for Swift and Kotlin plus JNI for desktop, and would not serve a remote node.
* Phones already run a node on loopback, so the phone case is a local socket.
* Keys stay at the node, as for chat. Its cost: frames cross one more boundary, and a remote
  client sends them to its node over that link. **Loopback latency is unmeasured; measure it
  before committing, with audio as the test.** If it fails, native surfaces are the fallback.

**Client work today: none exists.** QR scanning is the only camera use. Android declares neither
`CAMERA` nor `RECORD_AUDIO`; iOS declares camera, not microphone. There is no codec, and no
camera or microphone API in the JVM, so desktop needs a native library. That library's size
counts against the wheel limit and PyPI's project limit, which has about three releases of room.

## 4. Flow (how)

Three members of a room, all online, each with a test source in place of a camera:

```yaml
expect:
  state: populated
  visible: ["proposed:call_screen", "proposed:call_participants", "proposed:txt_call_save_state"]
```

The first two start; the third joins late and sees both tiles; a fourth is seated mid-call and
the others see no gap; the first leaves and the call goes on; the first rejoins as a new tile. A
receiver lowers one member's quality and the others' tiles are unchanged. A person who tries to
save while it is unavailable sees why, and the state stays `Not saved`.

## 5. QA plan

**Not drivable yet.** When built: three real nodes, mirroring CIRISServer's `av_room` harness
scenario (late join, add mid-call, leave and rejoin), with a test source in place of a camera,
since a CI runner has none. Assert frames arrive decoded, not just connected, and that each tile
is the publisher it claims. Assert video drops before audio when the tier sheds. Assert the save state is
in the tree on every call, and that a refused keep leaves `Not saved` with the words on screen.

**Not tested here.** Call quality on a real network, and battery. Hiding is Edge's to prove.

## 6. Delta — card vs API vs CC

**Audio, agreed with CIRISEdge 2026-11-10.** `0xFF` (Edge's `CODEC_OPAQUE`, `src/transport/realtime_av.rs:181-198` at v44.1.1) carries **Opus, 20 ms frames, 48 kHz mono, VOIP application, VBR, about 24–32 kbit/s**: exactly one Opus packet (RFC 6716) per frame at layer `{0,0,0}`, with no inner header and, in v1, no DTX and no FEC (late frames are dropped by Edge and the app conceals the loss), about 60–80 B, inside the 244 B budget at 50 frames a second. Edge treats `0xFF` as audio (base layer only, audio TTL, kept under shedding) and drops audio older than 300 ms, so packets stay short. The server's frames-v1 vectors pin it in their header as `audio_payload: opus/48000/1/20ms`. Phones encode and decode it with the platform (Android MediaCodec, API 29+; iOS AudioToolbox).

**Video, open.** Edge registers `0x01` AV1 SVC, `0x02` JPEG XS and `0x03` MDC (both reserved) and `0xFF`; it has **no `0x04` and no H.264 or VP8 id**, while the paragraph below, from CC 5.3.3.2.1, names `0x04`. AV1 hardware encode is on few phones. An H.264 or VP8 id with its layer rule is Eric's and CC's to decide; CIRISEdge is raising it. Until then video is not buildable to a wire both ends agree on.

**Codec (CC 5.3.3.2.1, as the card read it).** Audio is `0xFF`, so a call never falls silent. Video is `0x01` where
the device can encode it in real time, otherwise `0x04`, the temporal-layered class
(`ChunkLayer {0, t, 0}`) for H.264, VP8, VP9 and their kind; the stream's binding names the
codec. Every client decodes `0xFF` and `0x01` and SHOULD decode `0x04`. A receiver that can't
decode a stream's video keeps its audio and says so. Shedding reads the layer, never the id,
so Edge's `is_base_or_audio` must agree. Device coverage is unmeasured.

| # | Open question | Whose |
|---|---|---|
| 1 | **When is a call over?** Proposed: `live` = a `chat:call:v1` row seen within 60 s, re-issued while attending | CIRISServer proposed 2026-11-10; adopted |
| 6 | **The frames socket's byte layout.** Agreed 2026-11-10 (§3), with a leading version byte and golden vectors at CIRISServer `tests/vectors/call_frames_v1.json` | Closed; pin the vectors when they ship |
| 7 | **The two facts before publishing.** Agreed 2026-11-10: on the GET, each as a flag and words | Closed |
| 8 | **Frame budget per call.** 244 B on Edge v44.1.1; about 1,140 B on a punched direct link at v45, only if every member runs v45. Read `frame_budget_bytes` per call, never assume | CIRISEdge |
| 10 | **The video codec.** Edge has no `0x04`, H.264 or VP8 id; the CC names `0x04`. Which ids, and their layer rule | Eric, CC, CIRISEdge |
| 9 | **Capture on desktop.** The JVM has no camera or microphone API; a native library is needed, and its size counts against PyPI's project limit | CIRISClient |
| 2 | Does a saved call count against the room's files and quota, and where does it show? | CIRISServer |
| 3 | Screen sharing is the same wire (CC 5.3.3.2) | Later |
| 4 | No ad-hoc call outside a room: `Edge::ring` is documented but not in the code | Later |
| 5 | The row travels the room's normal path, so at Hide when I talk it waits for the gap. Don't promise "rings instantly" until measured | Measure |

**Measured (CIRISServer native harness, 2026-11-10, edge v44.1.1, three standalone nodes, no retries).** Per publisher, delay p50 7–14 ms, p95 18–24 ms; every phase-2 frame received bar about three, 0 forged, 0 unbound; a late joiner's first frame in 2.1 s. The frames socket adds a loopback hop, which the route's own test measures with audio.

**Proposed order.** (1) Measure the frames route with audio. (2) Audio calls on desktop and
phones, unsaved, with the state shown; these can ship even if video lags. (3) Video. (4) Saving, after CIRISPersist#1067.
