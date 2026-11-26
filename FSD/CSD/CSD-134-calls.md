# CSD-134 — Calls (a room's live streams, and whether this one is saved)

**CSD**: CSD-134 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: Eric's direction, 2026-11-04, via CIRISEdge
**Reads with**: CSD-091 (the room a call starts from), CSD-103 (Chats), CSD-106 (seating a new member: the quorum item), CSD-088 (the first-run rung that says what a watcher sees of a call)
**Design**: CIRISEdge `FSD/AV_CALL_API.md`; CC 5.3.3.2 (group calls), CC 4.5.13 (the choice as data)
**Scope (Eric, 2026-11-10)**: calls with N participants, audio and video, with every option the routes expose. The routes in §3 are CIRISServer's proposal for 0.5.246 (CIRISServer#859), adopted here with the changes in §6
**Flow**: unwritten. The screen, capture and codecs are built on every platform (§2, Built); no flow file drives a call yet

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
loading:   {tag: state_call_connecting, renders: "'Joining…'. Not built: the room's name here, and the two facts a joiner must see before publishing (whether streams here may be kept, and whether a watcher can see this call)"}
empty:     {tag: state_call_alone, renders: "'Waiting for others to join.' Joining alone is valid; others in the room see the call on their Call action"}
error:     {tag: call_microphone_denied, renders: "the named refusal in words: no microphone permission, not a member, or this device's privacy setting has no calls"}
populated: {tag: call_screen, renders: "the tiles, participants, controls and the save state"}
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
| The app goes to the background | The host app calls the node's `ciris_server.enter_background(deadline)` from its lifecycle callback (Android `onStop`; iOS `sceneDidEnterBackground` inside `beginBackgroundTask`), and the call leave runs in that path; the others see `attendee_left` with `left: backgrounded` (agreed by CIRISServer and CIRISEdge, 2026-11-10). Before joining, the screen says a call ends when the app leaves the screen; coming back offers Rejoin, which is a new stream. Nothing keeps a call running in the background in v1. **Not built: neither host app calls `enter_background` today** |
| Why someone left | `attendee_left.left` is `left` (chose to), `backgrounded`, `removed` (taken out of the room: the epoch rolls, their tiles stop) or `lost` (links died with no leave seen, about 160–240 ms on real links). Each is said in words on their tile. This device's own leave carries the same value as the socket's close reason, so "you were removed" is never shown as "you left" |
| App killed before its callback | Nobody says anything; the others see `lost` once the links close. `backgrounded` is the polite path, `lost` the floor |
| Remote client (the node is elsewhere) | Not backgrounded by its app: its node sees the socket close and reports `left` for a clean close, otherwise `lost` |
| Remote client (the node is elsewhere) | Works over the same route, on that connection's bandwidth; labelled if it is the limit |
| No camera or denied | Audio-only, stated; the call is not refused |

**Built (client 0.5.241, for CIRISServer 0.5.246 on Edge v45.0.0).** The Call action in a
room's header (`btn_chat_call`), shown only when the node declares `chat:call`
(`Capability.CHAT_CALL`). The screen offers a voice or a video call (`btn_call_voice`,
`btn_call_video`) and asks for what that call needs at that moment, never at launch
(`platform/CallMedia.kt`, `callPermissionStep`):

| Call | Asks for | If refused |
|---|---|---|
| Voice | Microphone | No call: `call_microphone_denied`, with how to turn it on and `btn_call_try_again` |
| Video | Microphone, then camera | Microphone refused: no call. Camera refused or missing: a voice call, said (`call_audio_only`) |

| Platform | Microphone | Camera |
|---|---|---|
| Android | `RECORD_AUDIO`, in the library manifest, `android.hardware.microphone` not required | `CAMERA`, already declared for QR |
| iOS | `NSMicrophoneUsageDescription` | `NSCameraUsageDescription`, now naming video calls |
| Desktop | No permission to ask for: the step checks that a microphone line exists (none: `call_unavailable`, `no_device`). macOS shows its own prompt when the device is first opened | Opened only when its picture is sent; none or refused: `txt_call_video_unavailable` |
| Web | `getUserMedia` is the prompt: the microphone is opened once before the call and let go. Refused: no call, and Try again asks again | Asked by the browser when it is first opened to send; refused: `txt_call_video_unavailable` |

A second refusal in one attempt counts as a refusal, because Android reports a
once-refused permission as still askable. Until 0.5.241 desktop and web answered "no
capture on this platform" at this step, so the capture behind it was unreachable from the
screen. An agent build's own iOS host (`apps/ios`) needs the same two Info.plist keys.

**The call itself.** Joining shows `state_call_connecting`, then the call: one line per
attendee (`row_call_tile_<member>`, inside `card_call_tiles`) saying video, audio or
quiet; one picture per stream (`media_call_video_<stream>`), a member's camera and their
screen being two; `state_call_alone` while nobody else is there; `btn_call_mute`,
`btn_call_camera` (video calls), `btn_call_leave`. A refused join is the server's reason
in words (`state_call_refused`). After leaving, `state_call_left` says why, with
`btn_call_rejoin` unless this person was removed; each other member who left has a line
(`row_call_departed_<member>`). No microphone or speaker once in the call:
`txt_call_audio_unavailable`. Leaving the screen, or the app going to the background,
leaves the call.

**Capture and codecs.** Audio is Opus on every platform. Video:

| Platform | Capture | Sends | Decodes |
|---|---|---|---|
| Android | Camera2, MediaProjection for the screen | AV1: a hardware encoder that runs this size and rate, else libaom in software. H.264 (MediaCodec) only where libaom does not load | AV1: hardware, else libaom, else the platform's software decoder (Android 10+); H.264 |
| iOS | AVFoundation; ReplayKit for the screen, this app only | H.264 (VideoToolbox). **Being built:** AV1 by libaom, as on Android | H.264; AV1 where the device has a hardware decoder. **Being built:** AV1 by libaom |
| Desktop | The bundled FFmpeg: camera, a screen or one window. No screen share on Wayland | AV1 (SVT-AV1); H.264 only with Cisco's library, below | AV1; H.264 only with Cisco's library |
| Web | `getUserMedia`, `getDisplayMedia` | AV1 where WebCodecs encodes it here, otherwise H.264 | Both, where WebCodecs has them |

**AV1 both ways (Eric, 2026-11-26).** A desktop sends AV1 and shows H.264 only with
Cisco's opt-in library, so a phone that sent H.264 was shown as no picture on a desktop,
and a phone without hardware AV1 showed no picture from one (the call check against
0.5.254, run 45832940789: Android and the Linux desktop, frames both ways, no picture
either way). Phones therefore send and show AV1 whatever their hardware: libaom 3.15.3,
real-time and 8-bit only, built from the signed release by `packaging/aom/build_libaom.sh`
and linked into `libciris_aom.so` (Android: about 2.3 to 3.2 MB per ABI). Every client
must decode AV1 (CC 5.3.3.2.1), and now every one does. H.264 is still decoded where a
device can, for clients before 0.5.243.

**The camera is open only while its picture is sent.** Camera off, a call over the video
limit, no encoder, leaving, and closing the screen each release the device, so its light
is on only when someone can see the picture. Turning the camera back on opens it again
(a browser may ask again), and the first frame sent is a keyframe.

**Video is for two people (Eric, 2026-11-10; CIRISServer `fe0a93ec`; lifted at Edge 45.1).**
The node carries video and screen share only while the call has at most two people,
counted per person. The join answer and the `video_allowed` event carry `video_allowed`
and `video_max_people`; a `publish_refused` with `call.video_two_people_only` says the
same if the event was lost. Over the limit this device sends audio only, releases its
camera, says so (`txt_call_video_limited`, in this device's language, with the number),
and offers no share button. When the call is back to two and the camera is still on, it
sends again without being asked. Eric did not rule on resuming; the server's commit
describes it and the client follows that.

**H.264 on desktop comes from Cisco, and is the person's choice.** The desktop app does
not use the H.264 code inside the bundled FFmpeg. A switch on the call screen
(`card_call_h264`: `txt_call_h264_state`, `btn_call_h264_on`, `btn_call_h264_off`) is off
until the person turns it on. Turning it on downloads Cisco's OpenH264 2.7.0 library for
this machine from `ciscobinary.openh264.org`, checks it against the SHA-256 Cisco
publishes, and loads it; turning it off deletes it. The request carries nothing about the
person. Cisco's binary licence sets three conditions this screen meets: the person can
turn the codec on and off here; Cisco's sentence is shown beside the switch, in English
in every language (`txt_call_h264_attribution`); and the licence can be read in full
(`btn_call_h264_licence`, `txt_call_h264_licence`). With the switch off a desktop still
sends and shows AV1. It shows no picture from a member who sends H.264, and says so
(`txt_call_h264_needed`). Not run: loading the library on macOS or Windows.

**Sharing.** `btn_call_share` lists what can be shared (`btn_call_share_pick_<id>`; phones
and the browser use the system's own picker), `txt_call_sharing` and `btn_call_share_stop`
while sharing. CIRISServer 0.5.246 refuses a share by name (`call.share_unavailable`,
shown at `txt_call_share_refused`) until Edge 45.1. Where this device cannot share:
`txt_call_share_unavailable_here`.

**Not built.** The participants list as its own element, receive quality per member
(`…/call/layers`), the two facts before joining, an active-speaker view, and saving
(`opt_call_save_mine` disabled, `txt_call_save_unavailable`, until CIRISPersist#1067).
Desktop brings its own echo cancellation, since Java Sound has none: a partitioned frequency-domain adaptive filter with Valin's double-talk step (the filter SpeexDSP and Mumble use), a per-bin residual-echo suppressor that never sends a bin louder than the microphone heard it, and a delay estimator that finds the speaker-to-microphone lag from the two energy envelopes (`EchoCanceller.kt`). The speaker is one line the app mixes, so the canceller knows exactly what was played. Measured on a simulated room (`EchoCancellerTest`): 41.5 dB of echo removed after 6 s, 26.4 dB by the third second, the near end kept at 0.85 of its amplitude while both talk. On the Linux runner's virtual device (`DesktopAudioLoopbackTest`) it must take 25 dB off. Not measured: real rooms, real laptop speakers, Bluetooth latency. Headphones still beat any canceller.

### 2.1 A call that can't connect says why

**Ruling (Eric, 2026-11-22, for the LTS calls cut, relayed by CIRISServer):** when the media
path to someone cannot be set up, the person is told the actual reason, not a silent
failure and not a generic leave. The facts are edge's: the token set frozen for 45.x in
edge 45.2.0 (`docs/calls_relay_tokens.md`; later 45.x only adds tokens, and an unknown one
is shown as the generic case). CIRISServer 0.5.254 forwards them on the frames socket (§3).
The event fires only when there is neither a direct leg nor a member relaying, and only
about people actually in the call.

| What happens | What the screen shows |
|---|---|
| No path to a member | A panel for that member (`row_call_path.<member>`, value = the cause): a headline naming them, then the relay clause, both networks, a port held on this router if there is one, and what the person can do. The member is **not** removed from the call; a leg can still come up |
| The same member again, with changed facts | The panel is replaced: the latest report per member stands |
| The path comes up | The panel goes; a short line says how (`row_call_path_restored.<member>`, value = `via`): "Connected to Ada through Ben." when another member relays it (`via: peer_relay`, `through` named from the node's `through_person` or the attendee map, else "another member"); every other path kind reads "directly", `direct_lan` as "over your local network". Re-sent when the relaying member changes |
| Edge says trying again could help (`retryable`) | **Try again** (`row_call_path__act.<member>`) leaves and rejoins, as a new stream. Never offered where edge says it cannot help |
| Rate-limited (`budget_refused`) | "Try again in {seconds} s." from `retry_after_s`, or "Try again shortly." when edge gives none |
| Details | The node's tokens, verbatim and untranslated (cause, `detail`, relay, both NAT reports axis by axis, each attempt with its counts), behind a Details toggle and in the call log |

**The sentences are built from translated parts, never from the node's English**
(`calls/MediaPathText.kt`, each key written out literally): one headline per cause, one
clause per relay value, one word per network class, one line of advice. A cause or class
this client does not know reads as the generic one ("Couldn't connect to {person}." /
"unknown"), with the raw token still under Details, so nothing is dropped silently.

**What the words may and may not claim:**

- **The other person's network is their own claim.** `peer_nat.source` is
  `self_reported`, on the peer's clock, so it reads "as they report it". With no claim
  it reads "The network type of {person} isn't known", never a guess.
- **A refusal is never worded as the person's choice.** Edge cannot tell a person's
  choice from their node's policy, so `refused_by_peer_policy` reads "Direct connections
  aren't accepted by the node of {person}" and `peer_unsupported` "their app doesn't
  support it". No sentence says "{person} isn't accepting direct calls".
- **`refused_by_policy` is the canonical's rule, and only the canonical's** (Eric,
  2026-11-22: "we are only denying relaying on canonicals"): "Calls aren't relayed through
  the CIRIS public servers", not "this node". "The CIRIS public servers" is the
  person-facing name for any canonical seat (glossary row, all 28 languages), never a
  family's canonical node. A member who can't reach another directly gets media through a
  third member; only when none could is there a panel, and `no_member_could_relay` says so
  ("…and no one else in the call could pass the connection along"), with `relay:
  none_available` as its second line. `peer_relay_in_use` is reserved and never on a
  failure, so it, and any standing this client does not know, adds no line.
- **Anonymous mode is the reason, not the network** (`anonymous_no_direct`): anonymous mode
  never connects directly, because a direct path reveals the address, so the panel gives
  no network advice.
- **Advice is about the side that blocks.** This network is symmetric or port-restricted:
  "Try a different network, for example Wi-Fi instead of mobile data." Only theirs: "The
  network of {person} blocks direct calls", with nothing to press. The open-a-port
  wizard is 0.6 work (`/v1/self/network/port-mapping` is refactor-cut), so the LTS
  never offers it. A held router mapping is said only when `router_mapping` is
  `held_*`, which is always `not_tried` on the LTS.
- `nat_undetermined` says plainly that this network's type couldn't be fully
  determined; `both_nats_random` says at once that a direct call isn't possible
  between these two networks.

**A setting that refuses calls** (edge 45.2.0). Anonymous mode (`call.anonymous_mode`) and
"Always hide when I talk" (`call.shaped_only`, the CSD-088 rung-3 option, named as the
person chose it; "shaped" never reaches the screen) refuse a join before anything starts.
Turning either on mid-call ends the call: `call_ended {ended, reason_id}`, then the socket
closes with the bare token, and the screen says what the join refusal says, with no
Rejoin. `ended: hung_up | revoked | expired` read as "you left", "you were removed" and
`call.end.expired`. The close that follows never blurs a stated reason to "lost".

**Relaying for others (Eric, 2026-11-22; per node, 2026-11-25).** Relay is meant on by
default on a desktop, a home node and a phone on Wi-Fi; a phone on metered or mobile data
does not relay unless the person turns it on. A device is a node, so the choice belongs to
**the node that carries the call** (`calls/RelayChoice.kt`, `RelayNode`): normally this
device's own, and another where the person switched the app to a different node or uses a
browser. Every loopback address is this device's one node; any other is kept by its address.

| The call runs on | What the client says |
|---|---|
| a phone's own node | always `relay`: the person's choice, else `true` where the network is **known** not to be metered or mobile, else `false`. A network not known yet, a choice not read yet, and a choice that could not be read are all `false` |
| any other node (a desktop's own, a home node, the node behind a browser) | `relay` only where the person chose for that node; else nothing, and the node decides (CIRISServer 0.5.254: never on a CIRIS public server; on elsewhere, except a phone's node). This device's network is not that node's and is not read |

The join waits up to 3 s for the stored choice; one that comes later is sent then. A phone
watches its network for the whole call, and a change is sent with
`POST /v1/chat/{room}/call/relay {relay}`, one request at a time, newest first. A request
that fails is repeated (2 s, doubling to 30 s) until the node answers, refuses, or the call
ends: a lost "off" must not leave a phone relaying over mobile data. A refusal is not
repeated. The switch (`row_call_relay__act.self`) reads "Help others in this call connect
through this device", or "…through the node that runs the call" where that is another; the
node's answer, not the ask, sets it, from the join answer's `relay` object on, and a
`serve_seat: true` answer removes it. While relaying for anyone, `relay_status.relaying_for`
(people, mapped by the node; `relaying_for_nodes` is diag) shows as "People connected
through this device: N" (`row_call_relay.self`), or through the node that runs the call.
Metered is per platform: Android's default network (cellular, or without
`NET_CAPABILITY_NOT_METERED`), iOS `NWPathMonitor` (`isExpensive`, `isConstrained`, or over
cellular); a desktop and a browser are never asked.

**The sent picture follows the source's size.** The camera's encoder opens at
640x480 and the share's at the size asked for; a frame of another size closes it and
opens one at that size, and that frame is a keyframe (`ResizingVideoEncoder`). A frame
that still will not encode is dropped, logged once, and never ends the stream. Found by
the call check's first run against 0.5.254 (run 45810495792): the runner's 320x180 test
pattern was refused by an encoder opened at 640x480, the exception ended the sending
loop, and no picture crossed on any desktop leg.

**A full call (CIRISServer 0.5.254).** A call carries at most `max_people` people
(four at 0.5.254; a room still seats more), and the number is the node's, never this
client's. A join into a full call is refused 409 `call.full` with `max_people` and
`people`; a join that made the call too big, learned in its first seconds, ends with
`call_ended {ended: call_full, reason_id: call.full, max_people, people}` and then a close
`call_full`, and the people already there stay. Both read "This call already has
{max_people} people, the most a call can have." (`state_call_refused` / `state_call_left`,
value `call.full` / `call_full`). Rejoin stays offered after the second: two who joined in
the same seconds can both be turned away. Not observed on a node; 0.5.254 is not tagged.

**Built (client 0.5.243), not observed on a node:** CIRISServer 0.5.254 and edge 45.2.0
are not released. The parser, the state and the sentence choice are read from the frozen
token set and the server's wire shapes (`MediaPathFailedTest`: 45.1.11's top-level form
and 0.5.254's `diag` form, a sparse event, latest per member, restore direct and through a
member, one sentence per cause, retry only where edge says, advice by side and none for
anonymous mode, the peer's claim, relay standings, Details, `relay_status`, `call_ended`
and the close after it, the end tokens, the switch's answer; `RelayChoiceTest`: whose node,
what is said to it, the mid-call network, the late, unread and tapped choice, the retry). The
Android and iOS network watches are compiled in CI and not observed on a device. A
node that never sends these events changes nothing: an unknown event type is skipped.

**CSD/5:** this card is CSD/3. The panel's tags are already in CSD/5 form (`row_` is the
ItemRow prefix, keyed by member, `__act` its slot), so adopting the card later renames
none of them. The rest of the screen's tags predate CSD/5 and are driven by the
five-platform flows in CIRISAgent; converting them is its own change.

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
| capture, encode, decode, render | platform code in the client (§2, Built) | CIRISClient | **built** on Android, iOS, desktop and web; no call between two real devices has been run |
| video only for two people | `video_allowed`, `video_max_people` on the join answer and the `video_allowed` event; `publish_refused {reason: call.video_two_people_only}` | CIRISServer | **live** on release/0.5.246 (`fe0a93ec`); lifted at Edge 45.1 |
| why a member can't be reached directly | frames socket text events: `media_path_failed {member, person, cause, reason_id: call.media_path_failed.<cause>, retryable, retry_after_s, relay, relay_reason_id: call.relay_standing.<relay>, diag: {detail, tried: [{kind, outcome, attempts, elapsed_ms, knocks_sent, sockets}], local_nat: NatReport, peer_nat: {report: NatReport, source: self_reported} \| null}}` (45.1.11's top-level diagnostics are still read); `media_path_restored {member, person, via: <path_kind>, direct, through: <node key id> \| null, through_person}`. `cause` ∈ both_nats_random, punch_no_answer, punch_one_way, nat_undetermined, no_facilitator, refused_by_peer_policy, peer_unsupported, budget_refused, not_trusted, no_member_could_relay, anonymous_no_direct, other. `relay` ∈ none_available, refused_by_policy, peer_relay_in_use (reserved). NatReport: RFC 4787 axes plus `legacy_class` ∈ open, full_cone, restricted, port_restricted, symmetric, unknown | CIRISEdge 45.2.0 · CIRISServer 0.5.254 | **frozen for 45.x by edge** (`docs/calls_relay_tokens.md`, release/45.2.0 @60218cf), **not released**; the wire shapes are the server session's (2026-11-23), its vectors in `tests/vectors/call_frames_v1.json` not yet landed |
| relaying for others | `POST …/call {video?, relay?}` (omitted: the node decides) → the join answer's `relay {relaying, relaying_for, relaying_for_nodes, serve_seat}`; `POST /v1/chat/{room}/call/relay {relay}` → 200 `{room, relaying, relaying_for, relaying_for_nodes, serve_seat}`, 409 `call.not_joined`; event `relay_status {relaying, relaying_for, relaying_for_nodes}` on change, at most every ~250 ms | CIRISEdge 45.2.0 (`Call::set_relay`) · CIRISServer 0.5.254 | **not released**; built to the server's shapes (§2.1) |
| why the call ended | event `call_ended {ended: hung_up \| revoked \| expired \| shaped_only_no_media \| onion_anonymous_no_media, reason_id: call.end.<token> \| call.shaped_only \| call.anonymous_mode}`, then the socket closes with the bare token; join refusal 403 `call.shaped_only` / `call.anonymous_mode` with edge's `token` | CIRISEdge 45.2.0 · CIRISServer 0.5.254 | **not released**; built to the server's shapes |

**Where frames run (recommended).** Capture and encode in the client, then encoded frames
over a local server socket to the node, which hands them to Edge.

* Edge's `Frame` is already encoded bytes with a `codec_id`, so Edge never sees a camera.
* It is one path for every platform and for a remote client. A native surface would be
  UniFFI for Swift and Kotlin plus JNI for desktop, and would not serve a remote node.
* Phones already run a node on loopback, so the phone case is a local socket.
* Keys stay at the node, as for chat. Its cost: frames cross one more boundary, and a remote
  client sends them to its node over that link. **Loopback latency is unmeasured; measure it
  before committing, with audio as the test.** If it fails, native surfaces are the fallback.

**Client work.** Built; see §2. Desktop capture and AV1 come from the FFmpeg the wheel
already carries. Desktop H.264 is not in the wheel: it is Cisco's library, downloaded by
the person's choice (§2). JNA, which loads it, adds about 2 MiB to a wheel with a 100 MiB limit.

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

**The call check** (`testing/gate/call_video.py`, `run_platform --call-video`, on every five-platform leg): a call needs two people and a flow drives one client, so it is its own step after the flows. On a node leg, a second node beside the leg's (the two-node fixture's peer) is claimed by a SECOND desktop client in test mode, the two nodes are seeded as `fixture: two_node` seeds them, and both clients join the pair room's call with video. Test mode sends a moving test pattern and a 440 Hz tone, so each side's verdict is a measurement: the other's tile counts frames at about 440 Hz, and a decoded picture of the other (`media_call_video_<stream>`, value = their node key) is on screen. The waits are CIRISServer's av_member harness's (both joined, `ready`, then `joined.legs` on both nodes). **Video is refused below ciris-server 0.5.254**: before it a member join binds no codec map, so edge frames nothing and a video frame cannot fit one chunk (CIRISServer, 2026-11-23); the audio half is still gated. On the with-AI legs the check opens the call-frames WebSocket upgrade through the agent: a 403 from a ciris-agent below 2.23.2 (no WebSocket route; CIRISAgent#1373 adds the forwarding) is an expected failure, shown green as XFAIL; any other failure there, or a 403 from 2.23.2 on, is red. A two-client call through an agent is not run yet. The unit tests cover the session, the codecs, the playout buffer, the camera's holding rule and the frames vectors. For those: three real nodes, mirroring CIRISServer's `av_room` harness
scenario (late join, add mid-call, leave and rejoin), with a test source in place of a camera,
since a CI runner has none. Assert frames arrive decoded, not just connected, and that each tile
is the publisher it claims. Assert video drops before audio when the tier sheds. Assert the save state is
in the tree on every call, and that a refused keep leaves `Not saved` with the words on screen.

**Not tested here.** Call quality on a real network, and battery. Hiding is Edge's to prove.

## 6. Delta — card vs API vs CC

**Audio, agreed with CIRISEdge 2026-11-10.** `0xFF` (Edge's `CODEC_OPAQUE`, `src/transport/realtime_av.rs:181-198` at v44.1.1) carries **Opus, 20 ms frames, 48 kHz mono, VOIP application, VBR, about 24–32 kbit/s**: exactly one Opus packet (RFC 6716) per frame at layer `{0,0,0}`, with no inner header and, in v1, no DTX and no FEC (late frames are dropped by Edge and the app conceals the loss), about 60–80 B, inside the 244 B budget at 50 frames a second. Edge treats `0xFF` as audio (base layer only, audio TTL, kept under shedding) and drops audio older than 300 ms, so packets stay short. The server's frames-v1 vectors pin it in their header as `audio_payload: opus/48000/1/20ms`. Phones encode and decode it with the platform (Android MediaCodec, API 29+; iOS AudioToolbox).

**What a receiver gets (CIRISEdge, `av_call.rs:69-151` at v44.1.1).** Frames come out in `chunk_seq` order: Edge holds a per-stream reorder window that adapts between 40 ms and 400 ms to the measured path skew. A chunk still missing at its deadline is skipped and counted (`frames_skipped`), and nothing re-fills it: live media is never retransmitted (leviculum#94). So the client sees in-order frames with holes. Its jitter buffer stays light, and every gap is concealed locally (Opus PLC). Opus FEC and DTX would soften gaps but stay off in frames v1, as agreed; turning them on is a v2 change both ends make together.

**AV1 SVC layers (`0x01`, CC 5.3.3.2.4).** `ChunkLayer {spatial, temporal, quality}` is a monotonic prefix on each axis: the AV1 operating point's `spatial_id` is `spatial`, its `temporal_id` is `temporal`, and `quality` is 0 unless the encoder makes SNR layers. `{0,0,0}` is the base. One chunk carries one layer's OBUs for one frame, and a receiver rebuilds from every chunk in `0..=s × 0..=t × 0..=q`.

**Video, by device (Eric, with CC, 2026-11-10).** A device that can encode AV1 in real time publishes `0x01`; one that cannot publishes `0x04` (H.264 or kin, temporal layers at spatial 0, the stream's binding naming the codec). Every client MUST decode `0xFF` and `0x01` and SHOULD decode `0x04`. Two resolutions are two streams. Open with CC: whether MUST-decode `0x01` stands, since most phones have no AV1 hardware decoder; and the presence floor, proposed as a separate ~96×96, 1 fps `0x04` stream at about 0.5 kB/s.

**What a video payload is (client, 2026-11-10).** `0x04` H.264: one access unit in Annex B form, SPS and PPS before every keyframe. `0x01` AV1: one temporal unit's low-overhead OBUs for that layer (`obu_has_size_field = 1`), the sequence header OBU before every keyframe. So a late joiner starts at any keyframe. From Edge 45.0.0 a whole frame goes to the node and Edge fragments it (`ready.fragmenting`): at most 4,096 fragments and 1 MiB a frame.

**Sharing (agreed, Edge 45.1).** A share is a second own stream with no `replaces` (`Call::add_stream`, signed `StreamAdded {kind: share}`): `POST /v1/chat/{room}/call/share` → `{stream_id, frames_ticket, frame_budget_bytes}`, `DELETE` to stop, the sharer's frames on `WS …/call/share/frames`. Receivers keep one socket, see `share_started` / `share_stopped` and the share's frames told apart by `stream_id`: a tile for "Ada's screen" beside Ada's camera. Desktop shares a screen or one window; phones share their screen.

**Who a tile is.** A frame's `publisher` and an event's `member` are the sender's NODE key; every event also carries `person`, the node's owner, or null until bound (then "someone in this room"). Tiles are keyed by stream, grouped and named by person from contacts.

**Testing without cameras (five-platform runner).** CI runners have no camera and mostly no microphone, so every platform can take a test source in test mode: a 440 Hz tone and a moving test pattern, pure Kotlin and identical everywhere. OS virtual devices are used where a runner has them (PulseAudio virtual sources on Linux, the Android emulator's camera). One leg calls the server's echo node (`POST /v1/qa/call/echo`, frames returned byte-identical); two legs in one room call each other across platforms.

**Video, the earlier reading.** Edge registers `0x01` AV1 SVC, `0x02` JPEG XS and `0x03` MDC (both reserved) and `0xFF`; it has **no `0x04` and no H.264 or VP8 id**, while the paragraph below, from CC 5.3.3.2.1, names `0x04`. AV1 hardware encode is on few phones. An H.264 or VP8 id with its layer rule is Eric's and CC's to decide; CIRISEdge is raising it. Until then video is not buildable to a wire both ends agree on.

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
| 11 | **A codec change mid-call.** CC is writing "one codec per stream" (Edge 45.1's v2 binding names a codec per class: `opus` under `0xFF`; `av1`; `h264`, `vp8`, `vp9`). One stream per publisher carries audio and video. Agreed by CC 2026-11-10: a codec change is an in-call stream replacement, the publisher signing which stream it replaces; a second stream that replaces nothing (screen share) stays separate. A codec change is an in-call stream replacement (make-before-break, `Call::replace_stream`), with `stream_replaced {member, old_stream_id, new_stream_id}` on the frames socket, so the tile swaps in place and nobody sees "left" | CC, CIRISEdge 45.1, CIRISServer |
| 10 | **The video codec.** Edge has no `0x04`, H.264 or VP8 id; the CC names `0x04`. Which ids, and their layer rule | Eric, CC, CIRISEdge |
| 9 | **Capture on desktop.** Closed: Java Sound for audio, the bundled FFmpeg for the camera and the screen. Echo cancellation: built in the client (above). Open: screen share on Wayland (needs the PipeWire portal), which waits with share for 45.1 | CIRISClient |
| 2 | Does a saved call count against the room's files and quota, and where does it show? | CIRISServer |
| 3 | Screen sharing is the same wire (CC 5.3.3.2) | Built in the client; refused by CIRISServer 0.5.246 until Edge 45.1 |
| 4 | No ad-hoc call outside a room: `Edge::ring` is documented but not in the code | Later |
| 5 | The row travels the room's normal path, so at Hide when I talk it waits for the gap. Don't promise "rings instantly" until measured | Measure |

**Measured (CIRISServer native harness, 2026-11-10, edge v44.1.1, three standalone nodes, no retries).** Per publisher, delay p50 7–14 ms, p95 18–24 ms; every phase-2 frame received bar about three, 0 forged, 0 unbound; a late joiner's first frame in 2.1 s. The frames socket adds a loopback hop, which the route's own test measures with audio.

**Proposed order.** (1) Measure the frames route with audio. (2) Audio calls on desktop and
phones, unsaved, with the state shown; these can ship even if video lags. (3) Video. (4) Saving, after CIRISPersist#1067.
