# Kernel census — where the client decides what the rules decide

**Status:** census, 2026-11-12, client `main` at `1f6062d5`, compared against CIRISServer `v0.5.246` (`9fe076b0`). Nothing here is built or changed.
**Asked by:** CIRISConstitution, for the evaluation of what edge, server, client and agent each do that the kernel does or should do.

A "site" is a place where the client computes an answer the node's rules own: whether an act is offered, who may take it, how many signatures are enough, whether a window is open, what state a thing is in. The client drawing what the node said is not a site. The client keeping its own promise (a pin, a type cap, a format check) is listed only where it could be mistaken for a node rule.

All paths are under `client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/` unless they start with `client/` or `src/`. Paths starting `src/` are CIRISServer at `v0.5.246`.

## 1. How to read this

**Kernel output** is the thing the site should read instead of computing:

| Output | Meaning here |
|---|---|
| **queue** | one act for this viewer on this subject: present, enabled, reason, cure, deadline, confirm |
| **view** | the state of a subject as this viewer may see it |
| **count** | have / need / met, and who has signed |
| **standing** | who the viewer is here: key, role, duties |
| **none** | the client's own rule; the kernel has nothing to say |

**How far each row was checked.** Three read-only search passes produced about 190 sites, each citing a line it read. I then read 18 of them myself; those are marked **✔**. Seventeen matched. One had a wrong line number, corrected here (I7). Every other row is as the search pass reported it, not re-read. "Copy" means the client's code or comment says it mirrors the node; where I compared the copy against server source, the row says so and gives the server line.

**What the comparison against v0.5.246 found.** Five copies agree with the server today: the 16 KiB message ceiling (`src/contacts_chat.rs:168`), the 15-minute join-request life (`src/add_device.rs:49`), the appeal grounds and outcomes (`src/contacts_chat.rs:6457`, `:6459`), the strict-majority quorum parse (`src/family_api.rs:430`). The server sends `viewer_duties` on the transcript and the drive listing (`src/contacts_chat.rs:5770`, `:5855`; `src/drive.rs:2488`), `max_people` on rooms (`src/communities.rs:1597`), and `withheld` / `revealed` as required fields of the moderation view (`src/room_moderation.rs:74-76`). So several rows below disagree only with an older node, or only after the next server change. Those rows say which.

## 2. The sites, by class

### (a) Offered, enabled, disabled, and the reason shown

| # | Site | What the client decides | Should be | Reads | Can it disagree; what the person sees |
|---|---|---|---|---|---|
| A1 | `ui/components/Attestation.kt:233-240` | Chat Replace / Withdraw / Recant enabled | queue | node `viewer_ops` | No. This is the model for the rest. |
| A2 | `ui/components/Attestation.kt:263-271` | Reason under a greyed chat act: field absent → "no route", else "not permitted" | queue (reason) | whether `viewer_ops` was sent | The reason is a guess from absence. |
| A3 | `ui/components/Attestation.kt:229-237` | Accord card: Cosign only when Pending; Supersede / Withdraw only when Canonical and Active | queue | client-mapped status | Yes. Greyed with no reason. |
| A4 | `ui/components/Attestation.kt:241` | Delegate is always disabled | queue | hard-coded | Always grey, no reason. |
| A5 | `ui/screens/AccordScreen.kt:269`, `:286` | Found accord only when no family; Confer duty only when there is one | queue | cached accord read | Yes after a failed read (F8): founding is offered where a family exists. Node refuses. |
| A6 | `ui/screens/ChatScreen.kt:1043-1071` | Appeal (`mine` or reviewer), rule (filed and reviewer), flag and clear (moderator), "moderate author" unavailable for others | queue | node flag and appeal state, plus C1/C2 | Follows C1/C2. Non-moderators see nothing, with no reason. |
| A7 | `ui/screens/ChatScreen.kt:984-988` | Which flag classes are offered: none above a live infohazard, only infohazard above reported | queue | hard-coded order | Yes if the node's order changes. Not compared against server source. |
| A8 | `ui/screens/ChatScreen.kt:1071`; `ui/screens/files/FilesScreen.kt:749-754` | Report and Takedown are always unavailable | queue | hard-coded | Stays unavailable after a node adds the route. |
| A9 | `ui/screens/ChatScreen.kt:266`; `CIRISApp.kt:4552` | Call button only with capability `CHAT_CALL` | queue | cached capability probe | Hidden without a word when the probe is stale. |
| A10 | `ui/screens/files/FilesScreen.kt:719-754` | Rename / Move / Withdraw offered to everyone; flag only to moderators and never on `self` files; appeal offered to anyone; rule only to reviewers | queue | C1/C2, node cohort, a client rule ("no flag on self") | Mixed. Files let the node decide who may appeal; chat (A6) decides it locally. |
| A11 ✔ | `ui/screens/HouseholdsSupport.kt:168-181` (`routeOf`); `ui/screens/HouseholdsScreen.kt:238-254`, `:274-276`, `:470` | Each household act is DIRECT, PROPOSE or NOT_ALLOWED; role chips, Invite, Seat, Dissolve shown or hidden from that | queue | C4 | Yes. NOT_ALLOWED sends nothing, so the node is never asked. |
| A12 | `ui/screens/HouseholdsScreen.kt:514` | Sign offered when the viewer has not signed | queue | node signatures + C5 | With `me` unknown, Sign stays offered; a second sign is refused. |
| A13 | `viewmodels/HouseholdsViewModel.kt:233-234`; `ui/screens/CommunitiesScreens.kt:956`, `:997` | Direct add or invite | queue | a 404 with no reason id seen earlier this session | Yes after a node upgrade mid-session. |
| A14 | `ui/screens/CommunitiesScreens.kt:629-644`, `:692-695`, `:928-935` | Role change, Remove, Leave, Dissolve offered on every room to everyone | queue | nothing | Offers what the node refuses; the refusal is shown by id. |
| A15 | `ui/screens/CommunitiesScreens.kt:959-963`; `viewmodels/CommunitiesViewModel.kt:430-434` | Invite hidden when the room is full | queue + count | node `max_people`, else a limit learned from one refusal; open invitations counted locally | v0.5.246 sends `max_people`. On a node that does not, one room's refusal hides Invite on every room this session. |
| A16 | `ui/screens/CommunitiesScreens.kt:953-954` | Community Seat is never offered | queue | hard-coded | Limit line always shown. |
| A17 | `ui/screens/InvitesSupport.kt:126-138` | Withdraw an invite: only the proposer's pending rows; every pending row when `me` is unknown | queue | C6 + node state | Over-offers on purpose; refusal by id. |
| A18 | `viewmodels/SelfRosterViewModel.kt:103-104`; `ui/screens/SelfRosterCard.kt:294-300` | Assent / Object hidden on proposals this device filed | queue | C8 | Yes. Rows already answered still show live buttons. |
| A19 | `ui/screens/DutyConferralScreen.kt:316-339`; `viewmodels/DutyConferralViewModel.kt:76`, `:352` | Duty checkbox disabled for `ACCORD_CANNOT_CONFER`; duty menu falls back to a compiled list | queue | hard-coded set; node `delegation_scope`, else compiled | Forbids what the node may allow; with the fallback, offers what the node does not serve. |
| A20 | `ui/screens/ModerationScreen.kt:438-450`; `viewmodels/AdminLadderViewModel.kt:90` | Ladder rungs offered | queue | node `/v1/operations`, else compiled fallback | With the fallback, offers rungs the node may refuse. |
| A21 | `ui/screens/ModerationProposalSheet.kt:200` | Submit disabled after "not on this node" or "recorded as a minor" | queue | an earlier refusal; I9 | Sticky for the sheet's life. |
| A22 | `ui/screens/RuntimeScreen.kt:276-302` | Pause / Resume / Step only for an admin | queue | C7 | See C7. |
| A23 | `ui/components/PendingApprovalsCard.kt:330-335`; `approvals/BudgetApprovalSeam.kt:454-476` | Budget Approve disabled by headroom, expiry bounds, an unticked over-grant | queue | earlier headroom read; hard-coded bounds (I8) | Yes on stale headroom. |
| A24 | `ui/screens/AddDeviceScreens.kt:183`; `viewmodels/ConsentObjectsViewModel.kt:94-96` | "Existing identity" and consent Revoke disabled after a bare 404 | queue | cached 404 | Sticky until re-probed. |
| A25 | `ui/components/AgentModeSelector.kt:86` | Server mode disabled when `server_eligible` is absent | queue | field, default false | Absent is drawn as "no". |
| A26 | `ui/screens/WalletSend.kt:55-56` | Send blocked until the duplicate check answers "not a duplicate" | queue | agent answer | A hard block on an unanswered check. Agent, not node. |
| A27 | `CIRISApp.kt:4618` | "View node" hidden below node 0.5.245; an unread version counts as too old | queue | `/v1/health` version + hard-coded floor | Hidden without a word when the version read failed. |

About 120 further `enabled =` sites are presentation only (`!busy`, `isNotBlank()`, `!inFlight`) and are not listed.

### (b) Windows, countdowns and expiry on the device clock

| # | Site | What the client decides | Should be | Reads | Can it disagree; what the person sees |
|---|---|---|---|---|---|
| B1 ✔ | `viewmodels/ApproveDeviceViewModel.kt:73-76` | Whether the add-device window is open, and seconds left | queue (deadline) | node `window_secs_left`, aged from a local stamp taken when the reply arrived | Yes. A slow reply, a suspended app or a clock change moves it. "Closed, open again" while the node is open, or the reverse. |
| B2 ✔ | `viewmodels/ApproveDeviceViewModel.kt:80-83`; `models/federation/AddDevice.kt:125` | Seconds until a join request expires | queue (deadline) | node `requested_secs_ago`, device clock, hard-coded 15 min | Copy agrees with `src/add_device.rs:49` today. The node sends an age, not an instant, so the client has to age it. |
| B3 ✔ | `ui/screens/AddDeviceScreens.kt:524` | **Hides** requests it computes as expired; shows "none" when all are | queue | B2, ticking each second | Yes. A request the node still holds loses its Approve and Deny. A request the node dropped stays approvable. |
| B4 | `ui/screens/AddDeviceScreens.kt:560`; `models/federation/SelfRoster.kt:85` | Hours of the held-proposal window, 24 h when the roster is not loaded | view | node `w_self_secs`, else hard-coded | Through the default and rounding only. |
| B5 | `viewmodels/SetupViewModel.kt:3089`, `:3173` | After a transport error, decides the device code expired | queue (deadline) | node `expires_in` + device clock | "The code expired" while the grant may be alive. |
| B6 | `viewmodels/InteractViewModel.kt:140-144` | Hours until credits renew, assuming midnight UTC | view | device clock + hard-coded rule | Wrong whenever the billing service renews at another time. Agent, not node. |
| B7 | `viewmodels/SchedulerViewModel.kt:268-279`, `:346-374` | When the device notifies for a task: parses its own request; cron uses minute and hour only; fallback is now + 1 h | view | the request it sent, not the stored task | A weekly cron notifies daily. Agent, not node. |
| B8 | `ui/screens/LLMSettingsScreen.kt:2821-2838` | Token EXPIRED from its `exp` claim | none | token + device clock | Display only. |
| B9 | `viewmodels/CommunitiesViewModel.kt:485-489` | Marks a member "added after founding" (cannot read earlier messages) | view | two node timestamps | No device clock, but the read rule is the client's inference. |

Sites that only format an instant the node sent are not listed. `viewmodels/NodeBindWait.kt` and the startup budgets are the client's own patience, not a rule.

### (c) Role and membership checks

| # | Site | What the client decides | Should be | Reads | Can it disagree; what the person sees |
|---|---|---|---|---|---|
| C1 ✔ | `ui/screens/ChatScreen.kt:966-971` (used `:398`, `:558`, `:583`) | Viewer holds `moderate` | standing | node `viewer_duties`; **when absent, inferred from the viewer's own loaded messages** | v0.5.246 sends the field. On an older node: a moderator who has not posted in the loaded window gets no flag menu; a revoked one still does. |
| C2 | `ui/screens/ChatScreen.kt:402`, `:559` | Viewer holds `review` | standing | as C1 | As C1, for appeal and ruling. |
| C3 | `models/chat/UserChat.kt:188` | Author badge "(moderator)" whenever `author_duties` is not empty | standing | node `author_duties` | A review-only holder is badged moderator. |
| C4 | `ui/screens/HouseholdsSupport.kt:104-111` | Household governance: founder-only (and whether the viewer is the founder) or a valid quorum, else "ungovernable" | standing + queue | node `consensus_protocol`, `my_role`; parse copied from `src/family_api.rs:425-430` | Copy agrees today. `my_role` absent → treated as not the founder, every act off. Absent protocol → `founder_only` (`models/federation/Family.kt:46`). |
| C5 | `viewmodels/HouseholdsViewModel.kt:176-177`; `api/HouseholdsApi.kt:108` | Who "me" is in a household | standing | owner of owned-nodes, cached | Yes if the owner key is not the member key: own row treated as someone else's. |
| C6 | `ui/screens/InvitesSupport.kt:141-143`; `viewmodels/CommunitiesViewModel.kt:228-229` | Who "me" is in a community | standing | node `viewer_key_id`, else as C5 | Only on nodes that omit the field. |
| C7 | `viewmodels/RuntimeViewModel.kt:362`, `:37`, `:88` | Viewer is an agent admin: true after any successful runtime read; any message containing "403" is an admin refusal | standing | inferred | Can stay true after a failure. Agent, not node. |
| C8 | `viewmodels/SelfRosterViewModel.kt:183-198`, `:230`, `:295` | Which roster proposals are the viewer's own | standing | device-local memory, plus a learned refusal | Lost on restart. |
| C9 | `viewmodels/SafetyViewModel.kt:115-117` | 401 → unsigned; any 403 → not a holder | standing | status code | A 403 for another reason reads "not a holder". |
| C10 | `viewmodels/DelegationsViewModel.kt:44-46` | 401 → "sign in as the owner"; 403 → "only the owner can" | standing | status code; node reason dropped | Hard-coded English in place of the node's words. |

### (d) Counts, thresholds, quorum

| # | Site | What the client decides | Should be | Reads | Can it disagree; what the person sees |
|---|---|---|---|---|---|
| D1 ✔ | `ui/screens/HouseholdsScreen.kt:524` | **Enables Apply**: node `quorum_met`, or pasted signatures ≥ required | count | signatures carried in, unverified | Yes. Junk or duplicate pastes enable Apply; node refuses. |
| D2 | `viewmodels/HouseholdsViewModel.kt:419-436` | For an imported change: `required` and the signer list | count | client's parse of the protocol + cached members | Yes. "N of M signed" is the client's. |
| D3 ✔ | `ui/screens/CommunitiesScreens.kt:770-772`, `:796` | **Enables Assemble**: pasted signatures ≥ required | count | local list; the node's own `valid` count (`models/federation/CommunityRoom.kt:134`) is not used | Yes, as D1. |
| D4 ✔ | `viewmodels/SelfRosterViewModel.kt:108`, `:114-116` | Signatures so far (1 + approvers) and needed; for rows not pending, a majority `rosterSize / 2 + 1` | count | node `needs`; else computed | Yes for every non-pending row. |
| D5 | `viewmodels/SelfRosterViewModel.kt:99-100` | Nav badge: pending minus own minus answered-this-session | count | node outcome + in-memory sets | After a restart, counts answered proposals again. |
| D6 | `viewmodels/SelfRosterViewModel.kt:126-133` | Whether a closed change landed, was cancelled or refused | view | roster as last read | Briefly, on roster lag. |
| D7 | `viewmodels/CommunitiesViewModel.kt:496-501`; `ui/screens/CommunitiesScreens.kt:548-572` | Builds `quorum:M/N` with N = picked members + 1; M unchecked | count | local pick | Node refuses after confirm when M > N or not a majority. |
| D8 | `viewmodels/AccordCeremonyViewModel.kt:355`, `:369-370`; `ui/screens/AccordCeremonyScreen.kt:455` | Genesis Assemble allowed at ≥ 2 cosigns | count | local list + hard-coded 2 | Yes if the threshold is not 2. Not compared. |
| D9 | `viewmodels/AccordViewModel.kt:49`, `:324`, `:604`, `:964` | Holder threshold 2 before a read lands; notices say "2-of-3" | count | hard-coded until read | Shows 2-of-N until a read succeeds, and after one fails. |
| D10 ✔ | `ui/components/Attestation.kt:515-521`; `ui/screens/AccordScreen.kt:690-709`, `:757` | Absent threshold → "1of1"; absent `signed` → n/n **met**; holder and canonical rows hard-coded Active, threshold 1 | count + view | absent fields | Yes. Absence is drawn as quorum met. |
| D11 | `models/NodeCatalogue.kt:133`; `models/AdminLadder.kt:582` | Ladder quorum 2 when the node serves no catalogue | count | hard-coded | Display only. |
| D12 | `viewmodels/SetupState.kt:1669-1693`, `:1813`; `ui/screens/TrustPage.kt:582-584`, `:715-716` | The trust level 0–5, from per-check flags; **three different "2 of 3" rules for L3** | none today | verify's booleans | The page can disagree with itself: debug row, tier card and summary. |
| D13 | `viewmodels/InteractViewModel.kt:130-134`, `:1112` | **Blocks sending** at zero credit | queue | possibly stale balance (H5) | A stale zero blocks a send the agent would take. Agent, not node. |

### (e) Confirm, or one step

Confirmed today (one sheet each, about 40 writes): chat withdraw and recant; reveal, flag, appeal, rule (chat and files); file withdraw; household leave, dissolve, add, invite, remove, role, withdraw-invite; community leave, dissolve, remove, invite, withdraw-invite; invite accept and decline; roster assent and object; device revoke and release; delegation revoke; contact accept, decline, remove; wallet send; ladder commit (typed word when irreversible); commons object, ballot, dismissal; reset, wipe, erase; halt; sign out; accord supersede; genesis.

One step today, beside a confirmed sibling. Whether each should confirm is the kernel's `confirm` with its `when`, not a client choice:

| # | Site | Write | Sibling that confirms |
|---|---|---|---|
| E1 | `ui/screens/ChatScreen.kt:406`; `ui/screens/files/FilesScreen.kt:378` | Clear a moderator's flag | Flag |
| E2 | `ui/screens/CommunitiesScreens.kt:652-657` | Change a member's role (free text) | Remove member |
| E3 | `ui/screens/CommunitiesScreens.kt:997` | Add a member directly (older node) | Invite |
| E4 | `ui/screens/HouseholdsScreen.kt:514`, `:520-525`; `ui/screens/CommunitiesScreens.kt:794-797`, `:825-828` | Sign, apply, assemble a change | The proposal |
| E5 | `ui/screens/federation/NetworkPeerDetailScreen.kt:358-361` | Set a peer BLOCKED or UNTRUSTED | SAS mismatch |
| E6 | `ui/screens/ModerationScreen.kt:256`, `:309` | File a moderation report; flag or clear a subject | Ladder commit |
| E7 | `ui/screens/WiseAuthorityScreen.kt:278-289` | Resolve a deferral; not gated on `pending` | — |
| E8 | `ui/screens/DutyConferralScreen.kt:484-493` | Propose or co-sign a duty | — |
| E9 | `ui/screens/files/FilesScreen.kt:777-799` | Rename; move (picking a room moves at once) | Withdraw |
| E10 | `ui/screens/RuntimeScreen.kt:274-306` | Pause, resume, single-step the agent | Halt |

`models/NodeCatalogue.kt:123` lets a compiled `irreversible` flag override the node's, and infers it for unknown operations (`reverses == null && reversedBy == null`).

### (f) State mapping: what the client infers versus what the node said

| # | Site | What the client decides | Should be | Reads | Can it disagree; what the person sees |
|---|---|---|---|---|---|
| F1 ✔ | `ui/screens/ReadFailure.kt:32`, `:42` | Any failed read whose **message text** contains "404" is "not on this node" | view | exception text, not `reason_id` | Yes. `family.not_found` reads "this node does not have this", in the empty style. |
| F2 | about 20 sites, e.g. `ui/screens/InvitesSupport.kt:73-74`, `viewmodels/FilesViewModel.kt:262`, `viewmodels/SetupViewModel.kt:1727`, `viewmodels/FinalGenesisViewModel.kt:343` | 404 with no reason id means an older node | view | status + absent id | A proxy 404 reads "older node" and switches doors (A13). |
| F3 | `viewmodels/NetworkPeerDetailViewModel.kt:266`, `:289-296` | "404" in text, or two hard-coded `detail` strings, mean "not in the directory" | view | text | Breaks when the node renames a string. |
| F4 | `viewmodels/RemoteNodeViewModel.kt:113-114` | 403 containing "not a relayable" → needs update; any other 403 → not the owner | view | the node's English | A wording change turns "needs update" into "not the owner". |
| F5 | `models/federation/TrustRoot.kt:403-407` | 403 with no id → loopback only | view | absent id | Any bare 403 reads "use the loopback". |
| F6 | `ui/screens/ChatScreen.kt:958-963` | Unknown message status → Active | view | node `status` | A new status draws as an ordinary message. |
| F7 | `models/chat/UserChat.kt:344-353`; `viewmodels/UserChatViewModel.kt:392` | Pair room is OPEN when the roster shows two members, over the node's `state`; **a send in any other phase is dropped without a word** | view + queue | node `state` and members | By design, but the send is refused on the client's inference. |
| F8 ✔ | `viewmodels/AccordViewModel.kt:157-180`; `ui/screens/AccordScreen.kt:436-441`, `:472-477` | A failed read of canonical servers, withdrawals or pending co-scrubs is an empty list | view | exception | Yes. "No canonical servers yet." when the read failed. |
| F9 ✔ | `viewmodels/AccordViewModel.kt:143-147` | **A failed halt read keeps the earlier halt status**, no stale mark | view | earlier answer | Yes. The halt banner can show an old answer. |
| F10 ✔ | `viewmodels/FilesViewModel.kt:194` | A transport failure is a refusal by the node | view | exception | Yes. A network fault reads "the node refused". |
| F11 ✔ | `models/chat/RoomModeration.kt:24-26` | `withheld` and `revealed` default false | view | node `moderation` | v0.5.246 always sends both. A node that omitted `withheld` would get no Reveal button. |
| F12 | `ui/screens/RevealConfirm.kt:11-13`, `:18-23` | Any class but infohazard is "reported"; `flag == null` is "cleared" | view | node `flag` | Null also means reversed on appeal, which is not "cleared". |
| F13 | `ui/screens/CommunitiesScreens.kt:739-743`, `:946` | Unknown protocol → "other", treated as a quorum room | view | node protocol | Changes the wording of accepted invites. |
| F14 | `viewmodels/HouseholdsViewModel.kt:150-156`; `viewmodels/FilesViewModel.kt:274-280` | Stops after 20 pages and shows the list as complete | view | page count | Cut off with no sign. |
| F15 | `viewmodels/FilesViewModel.kt:260` | Empty after the client's own cohort filter | view | node entries | Rows with an unexpected cohort vanish; the screen says empty. |
| F16 | `models/federation/Contact.kt:118-122` | Unknown reach token → "waiting to reach" | view | node `reach` | A new word is shown as the most negative claim. |
| F17 | `viewmodels/ContactsViewModel.kt:383-390`; `viewmodels/ConsentViewModel.kt:252-275` | Failed pair-invite read → none; failed audit read → empty audit | view | exception | Failure looks like nothing there. |
| F18 ✔ | `viewmodels/ModerationViewModel.kt:144-150` | A failed age read means **not a minor** | standing | exception | Failure is treated as allowed (I9). |
| F19 | `viewmodels/NodeSwitcherViewModel.kt:882-884` | A failed local probe means this device cannot claim | view | exception | A slow probe reads "cannot claim". |
| F20 | `models/ClientMode.kt:293`, `:357-374`; `CIRISApp.kt:2928`, `:6970` | Version banner against a hard-coded floor 0.5.168; the node's own floor is never passed | view | `/v1/health` | Banner text is hard-coded English. |
| F21 | `ui/screens/LLMSettingsScreen.kt:513`; `ui/components/PendingApprovalsCard.kt:335` | Absent breaker state → healthy; absent validation → ok | view | absent fields | Absence is drawn as good. Agent, not node. |

Done correctly, for contrast: `models/drive/Drive.kt:42-49` (an unknown byte token is "unopened", never "here"); `ui/screens/CommunitiesScreens.kt:729-736` (moderators unreadable, not sent, and none are three states); `ui/screens/ChildSafetyScreen.kt:181` (no age band is "unknown").

### (g) Reveal

| Site | Fact |
|---|---|
| `ui/screens/ChatScreen.kt:902-906`; `ui/screens/files/FilesScreen.kt:716-717` | Reveal is drawn only when `withheld` is true (F11). |
| `api/CIRISApiClient.kt:2836-2852` | The client sends `POST /v1/chat/{room}/items/{id}/reveal` with **no body**: a bearer token, no signature, no key id. |
| `src/contacts_chat.rs:7004` ✔ | The node signs the consent: `room_moderation::reveal(&st.engine, pen.local_signer(), …)`. The signer is the person's key as the node holds it. |
| `ui/screens/RevealConfirm.kt:111-117`; `client/iosApp/iosApp/localization/en.json:4523-4525` | The sheet says "It opens for you, on your own devices. Nobody else sees it because of this." and "Your own key, as your consent to view this one item". |
| `api/CIRISApiClient.kt:2759-2763` | The comment describing reveal sits above `moderateRoomItem`, and says the consent "stays on the viewer's own devices (0.5.243)". |

What the client decides here: nothing about whether to reveal; the node gates it. What it **asserts**: who signs, and that nobody else learns of it. It checks neither. The answer returns no signer and the client reads none. The same holds for the flag, appeal and rule sheets (`ui/screens/RevealConfirm.kt:47`, `:72`, `:94`). Whether a viewer's Reveal is a signed row at all is open with Eric; the sheet's wording should follow that ruling, not precede it.

### (h) Optimistic updates and stale answers

| # | Site | What the client keeps or changes | Should be | Can it disagree; what the person sees |
|---|---|---|---|---|
| H1 | `viewmodels/InteractViewModel.kt:1170`, `:1261-1283` | Adds the person's bubble before the send; **keeps it after a failed send**; on a timeout assumes it landed | view | The transcript shows a message the agent never got. |
| H2 | `viewmodels/InteractViewModel.kt:1753-1758` | Merges history by text + 30 s bucket, sorted on mixed device and node clocks | view | A repeated "ok" collapses; skew reorders. |
| H3 | `viewmodels/ApproveDeviceViewModel.kt:155-161` | A failed list read keeps earlier requests and the window, still ticking | view | B1–B3 run on an old read beside an error. |
| H4 | `viewmodels/ApproveDeviceViewModel.kt:231-235`; `viewmodels/SelfRosterViewModel.kt:272-283` | Removes or patches a row locally, then re-reads | view | Briefly. |
| H5 | `viewmodels/InteractViewModel.kt:1558-1560`; `viewmodels/BillingViewModel.kt:403-411` | A failed credit read keeps the old balance, unmarked | view | Feeds D13. |
| H6 | `viewmodels/CommunitiesViewModel.kt:317-327` | Pasted signatures are appended and never checked until Assemble | count | Feeds D3. |
| H7 | `viewmodels/WiseAuthorityViewModel.kt:643` | "Approved N" uses the typed amount, not the granted one the node returned | view | Names the wrong figure if they differ. |
| H8 | `viewmodels/DelegationsViewModel.kt:176-179` | Deny says "the code can no longer be used" without re-reading | view | Low. |
| H9 | `viewmodels/UserChatViewModel.kt:562-568` | A failed transcript re-read keeps the old messages beside a refusal | view | Low. |
| H10 | learned for the session: `CommunitiesViewModel.kt:380` (room limit), `InviteSupport.LEGACY`, "too old" flags (A24) | An earlier refusal stands in for the rule | queue | Controls stay hidden after the node's answer changes. |

Checked and clean: the commons dismissal dry-run drops its hash when the grounds change (`viewmodels/CommonsViewModel.kt:214-263`); household `sign()` uses the node's cosign answer; ticket acts use the re-read copy.

### (i) Input validation that copies the node's rules

| # | Site | Rule | Should be | Compared | Can it disagree |
|---|---|---|---|---|---|
| I1 | `ui/screens/ChatScreen.kt:93`, `:502-503` | Message ≤ 16 KiB; Send disabled above | queue (limit) | agrees, `src/contacts_chat.rs:168` | When the node's number moves. |
| I2 | `ui/screens/ChatScreen.kt:974-975`; `ui/screens/ModerationScreen.kt:294` | Flag classes are `reported`, `infohazard` | queue | not compared | A new class cannot be chosen. |
| I3 | `ui/screens/RevealConfirm.kt:57-58` | Appeal grounds and outcomes | queue | agrees, `src/contacts_chat.rs:6457`, `:6459` | When the lists move. |
| I4 | `models/chat/UserChat.kt:255-256` | Withdraw and recant reason codes | queue | not compared (edge) | — |
| I5 | `models/drive/MediaPolicy.kt:54`; `models/drive/Drive.kt:165`; `viewmodels/FilesViewModel.kt:351-355` | Upload cap from the node's policy, else 1 MiB | queue (limit) | — | A failed policy read refuses files over 1 MiB; the streamed path checks no size. |
| I6 | `ui/screens/HouseholdsSupport.kt:109` | Quorum valid only when `2m > n` | count | agrees, `src/family_api.rs:430` | — |
| I7 ✔ | `viewmodels/NodeSwitcherViewModel.kt:61`, `:787-790` vs `:921-923` | Claim refused unless the scope is self, family or community, in hard-coded English | queue | — | **The client contradicts itself:** `cohortChoices` offers whatever scopes the node declares, then the claim refuses any outside the three. |
| I8 | `approvals/BudgetApprovalSeam.kt:139-142`, `:463` | Grant expiry 1–8760 h | queue (limit) | not compared | Agent, not node. |
| I9 ✔ | `viewmodels/ModerationViewModel.kt:110-113` | **A moderation proposal is refused locally for a recorded minor** | standing | a text search of `src/safety/moderation.rs` for a matching check found none; the file was not read through | If the node has no such check, this rule holds only in this client, and a failed age read lets it through (F18). |
| I10 | `models/federation/Delegation.kt:153-163` | Verbs that can be delegated, and three that never can | queue | not compared | New verbs cannot be granted. |
| I11 | `ui/screens/ModerationScreen.kt:216-220`; `viewmodels/CommonsViewModel.kt:95` | Duty and cohort vocabularies | view | not compared | — |
| I12 | `viewmodels/SetupState.kt:1114`, `:1144-1147` | Password ≥ 8; username `admin` refused | queue | not compared (agent) | — |

The client's own rules, not the kernel's (**none**): the Tier-A render table that the node may narrow and never widen (`models/drive/MediaPolicy.kt:59-71`); refusing to claim an unpinned node (`viewmodels/NodeSwitcherViewModel.kt:774`); content-id, port and wallet-address formats.

## 3. What the client would delete if the node sent a complete queue item

- `routeOf` and `governanceOf` (`ui/screens/HouseholdsSupport.kt:104-181`), and with them every household show/hide in A11.
- `holdsDuty` and the inference from past messages (C1, C2); the "moderates" badge rule (C3).
- `flagClassesOffered`, `APPEAL_GROUNDS`, `APPEAL_OUTCOMES`, the flag-class list, `ACCORD_CANNOT_CONFER`, the delegatable-verb list, the compiled duty and ladder fallbacks.
- `MAX_MESSAGE_BYTES`, the 1 MiB upload fallback, the 1–8760 h expiry bounds.
- `roomIsFull`, `learnedLimit`, `TWO_PEOPLE`, `InviteSupport.LEGACY` and every session-sticky "too old" flag: each exists because an act arrived without its `enabled` and `reason`.
- `requestExpiresInSecs`, `windowSecsLeftAt`, `ADD_DEVICE_REQUEST_TTL_SECS`, and the filter at `AddDeviceScreens.kt:524`.
- Local signature counting as a gate (D1, D3), the derived `required` and signers (D2), the roster majority (D4), `protocolFor`'s N (D7), the genesis `>= 2` (D8), the `threshold ?: 1` and `signed ?: n` defaults (D10).
- `ownProposals` and `answered` (C8, D5); "me" from owned-nodes (C5, C6).
- The always-unavailable Report and Takedown rows (A8) and the never-offered Seat (A16): the queue either carries the act or does not.
- The per-screen choice of confirm or one step (class e).
- Every status-code and message-text reading that stands in for a reason: F1–F5, C7, C9, C10.

## 4. What the client must keep

- **Drawing.** Layout, order, wording by id, the reason and cure under a disabled act.
- **Its own promises.** The identity pin and the refusal to claim an unpinned node; the render table it will not widen; sealing and signing on the device where the device holds the key.
- **Format checks that save a round trip** and state no rule: hex length, port range, non-empty.
- **Telling failure from answer.** "The read failed", "the node did not say", "the node said none" and "the node said no" are four things. No queue item fixes a client that draws the first two as the third (F8, F9, F10, D10). This part is the client's to fix whatever the kernel sends.
- **Ticking a deadline.** Given a signed instant, the device clock still draws the countdown. It must not hide or disable on that clock: an act stays offered until the node says it lapsed.
- **Local state that is the person's**, not the room's: a draft, a paste not yet sent, which tab is open. A paste is not a signature until the node has counted it.
- **Agent-side surfaces.** Credits, scheduler, runtime control, wallet and setup talk to the agent. They show the same faults (D13, B6, B7, C7, H1) but the node's kernel does not cure them unless the agent serves the same shapes.

## 5. Top five disagreements

Ranked by what the person loses when client and node differ.

1. **A join request disappears on the device clock** (B1–B3, H3). Approve and Deny for a new device are hidden by a locally aged count against a hard-coded life, and keep ticking from an old read after a failed one. The person is deciding whether a device joins their identity.
2. **Failure and absence drawn as an answer** (F8, F9, F10, F1, D10, F18). A failed halt read leaves the old halt status up, unmarked. A failed accord read says "No canonical servers yet." A network fault says the node refused. A missing threshold says "1 of 1", and a missing signature count says quorum met. A failed age read says "not a minor".
3. **Who may act, computed by the client** (C4, A11, C5, C1, C8). Household governance is re-derived and NOT_ALLOWED never asks the node. "Me" is the node's owner, not the member. On a node older than 0.5.246, moderator and reviewer are guessed from the person's own past messages.
4. **A count the client made enables the act** (D1, D2, D3, D4). Apply and Assemble light up on unverified pasted signatures; an imported change gets a `required` the client derived; non-pending roster rows show a majority the client computed.
5. **A rule that exists only here, or that the client breaks itself** (I9, I7, D12). The minor gate on moderation proposals was found only in this client. The claim screen offers scopes the node declares and then refuses them. The trust page states L3 by three different rules.

Close behind: the same act confirms in one place and not in its sibling (class e), and the reveal sheet names a signer the client cannot see (class g).

## 6. What a queue item must carry for the client to compute nothing

Per act, for this viewer, on this subject:

| Field | Replaces |
|---|---|
| `act` id, and the subject it applies to | per-screen menus |
| **present** — an act not listed is not drawn | A8, A14, A16, hidden-without-reason rows |
| **enabled** | every row in class a |
| **reason** — a string id, when not enabled | status-code and text readings; A2's guess |
| **cure** — the act or route that would enable it, when there is one | "open again", "ask the founder", update prompts |
| **deadline** — a signed instant, with the node's `now` in the same answer | B1–B3, B5; ages and seconds-left |
| **confirm** — whether, and `when` (`always`, `by_scope`, `by_scope_change`, `if_widens`), and the facts the sheet shows: effect, who learns of it, **who signs** | class e; the claims in class g |
| **irreversible**, and what undoes it | `NodeCatalogue.kt:123` |
| **choices** — the allowed values for each input: classes, grounds, outcomes, reason codes, roles, scopes, protocols | I2, I3, I4, I7, I10, I11, A7, A19 |
| **limits** — bytes, people, hours, with the unit | I1, I5, I8, A15 |
| **route** — direct, or by proposal | `routeOf` |

Beside the queue, once per subject:

| Output | Fields | Replaces |
|---|---|---|
| **standing** | viewer key id on this subject, role, duties, age band where a rule turns on it | C1–C8, I9 |
| **count** | have, need, met, signers with each one's state, and which pasted or carried signatures the node counts as valid | class d |
| **view** | a state from a closed list; `as_of`; and for every field that can be absent, whether absent means "none" or "not said" | class f |

Two things the item cannot carry and the answer envelope must: a **reason id on every refusal including 404**, so that a bare 404 means only "no such route"; and a **dry run for a carried change** (given this change and these signatures, what is the count), so the client never counts a paste.

## 7. Not covered

- **Not opened:** `auth/` (token expiry and "billing credential stale" reach the UI through `CIRISApp.kt:867-881`), `platform/`, `services/`, `backend/`, `ceg/`, `config/`, `diagnostics/`, `utils/`, `ui/nav/`, `ui/screens/graph/`, `ui/components/setup/`, `approvals/` except `BudgetApprovalSeam.kt`.
- **`api/CIRISApiClient.kt`** (17.8k lines): only the reveal, moderation, appeal, attachment-policy and agent-mode parts were read. Its per-route `catch` blocks and JSON defaults are where more of class f lives, and they are unaudited.
- **`calls/`**: a text search only. It found the call's video rule read from the node (`video_max_people`, `call.video_two_people_only` at `calls/CallSession.kt:165-170`, `:402`) and no local role or count rule; who may start, join or end a call was not traced.
- **Searched but not read through:** `CIRISApp.kt`, Setup, Interact, Trust, Wallet, Settings, LLM settings, System, Tickets, Users, Adapters, identity management, final genesis, `ui/primitives/` except `CeremonyBlock.kt`, `ui/shell/` except the two confirm sheets, `ModerationScreen.kt` lines 700–1597.
- **Class h** was found by pattern (local list edits, notice setters, failed-read handlers), not by reading every write path.
- **Platform source sets** (`androidMain`, `iosMain`, `desktopMain`, `wasmJsMain`) were not searched. All paths here are `commonMain`.
- **Server comparison** covers the five copies named in §1 and the reveal signer. Every other "copy" is the client's own claim. The flag severity order (A7), the genesis threshold (D8), the delegatable verbs (I10) and the minor gate (I9) were not confirmed against server source.
- **Nothing was run.** No row was observed on a screen against a node; each is a reading of the code.
- **The count is not exact.** The three passes overlap (the 16 KiB cap and the household rule each appear in several classes); this document lists each once, about 130 rows from about 190 reported.
