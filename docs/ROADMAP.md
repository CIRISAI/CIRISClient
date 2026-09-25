# Locked Spec: roadmap

The order the client redesign is built in, and where it stands. Updated 2026-09-25.

This file replaces the Gantt artifact, which was deleted twice. The Card Atlas, the
execution plan and the handoff remain the design's own documents. This page records
only what order they are being built in and how far along each part is.

## The order

1. **Bugs first.** No new UI work until this lane is clear. Several of the bugs sit in
   first run and sign-in, the path every new user takes before they see any UI. The
   client side of the lane is fixed on main. What remains is #50 (iOS, waiting on a
   crash log), the agent-side issues, and re-checking #43/#47/#48.
2. **Wave 2, per circle.** Starts with Just me. Files (B3) is in review as #77.
3. **Wave 3.** The 23 new cards.

## Done

| | what | where |
|---|---|---|
| Wave 0 | Theme (16 tokens × 2 grounds), 71 glyphs, the CEG dimension table, nine primitives, Contacts rebuilt as People | #62 |
| Wave 1 | The shell: five circles, seven tabs, My things, Stop, one nav tree, the SOON gating deleted | #63 |
| The spine | Tabs hold what they are named (Files holds only files), the accord together under Everyone › Safety, Settings under This node on every build, one top bar, one back, a rail that closes, a My things page that names its instrument instead of a circle. Codex review: 13 comments across three rounds, all resolved. | #64 |
| G2 | Screen atlas regenerated against the spine: 49 of 49 screens | #64 (`docs/`) |
| G4 | Nav contract: all 49 hops `nav_map` derives, driven live, 49/49 land where named | #64 |
| Hand-off | After a run-without-AI hand-off the client follows the node when the pinned agent goes quiet (fixes #66, #67) | #73 |
| Setup honesty | One press runs the final step once; a skipped claim is not reported as success; a login refusal keeps the server's reason (fixes #68, #69, #70) | #74 |
| Screenshot | Desktop `/screenshot` draws the window itself, not the screen under it (fixes #60) | #75 |

## The bug lane, in order

### 1. Run-without-AI hand-off (desktop)

**Fixed by #73.** Both bugs were on 0.5.223, in the step where the agent hands off to the
node on `:4243`. They are the same family as #43, #47 and #48, which have fixes on main
and stay open until a CIRISAgent build carrying #73 re-verifies all five together.

- **#66** macOS: Startup stalls on "Restarting your node…" with 0/22 services. A
  node-only backend reports no services, so the wait never ends; the screen's automation
  tree is also empty (`elements=0`).
- **#67** Windows: the session created during setup isn't saved, so the user is sent
  back to Login. The watchdog then keeps reviving a node that answers 200, and pressing
  Login does nothing, with no error, while the header says Connected.

### 2. Setup reports things that aren't true

The client rows are **fixed by #74**. The agent rows are open in CIRISAgent.

| | issue | repo |
|---|---|---|
| double submit | #69: the in-flight guard only engages after the up-to-10s claim wait | client |
| double submit | CIRISAgent#1193: `setup/complete` is not idempotent, so a second submit creates a second ROOT with the same name | agent |
| skipped claim | #68: setup reports success after skipping the claim, and the fed-ID mint is skipped with it | client |
| skipped claim | CIRISAgent#1196: `/v1/setup/status` doesn't give `claim_pin_file`, so a client that didn't launch the node guesses the path | agent |
| lost reason | #70: a login refusal shows as "Token exchange failed" and the server's reason is dropped | client |
| two answers | CIRISAgent#1194: `setup/complete` ran on a node whose server had already closed `setup/root` | agent |
| sticky flag | CIRISAgent#1195: `CIRIS_FORCE_FIRST_RUN` is re-read on every check, so setup never reads as done | agent |

### 3. Platform

- **#50** iOS 0.5.212: crash (SIGABRT) when the wizard reaches the AI step. #76 records
  uncaught Kotlin exceptions to `Documents/ciris/logs/kotlin_crash.log`, so the next
  crash leaves its cause behind. The crash itself is not fixed yet.
- **#34** iOS: the raw key `mobile.login_setup_complete_relogin` is shown on the login chooser
- **#60** desktop `/screenshot`: **fixed by #75** (it draws the window's own layer).
- **#51** mobile: a run-without-AI install has no logout. **Addressed by #64,** which puts
  Settings (and its logout) under This node on every build. Verified on desktop only;
  still needs checking on a phone.

### 4. Issues fixed on main but still open

The fix commits cited these as `CIRISClient#N`, not `Fixes #N`, so GitHub never closed
them: #37, #39, #40, #41, #43, #46, #47, #48, #49, #53. #33 is only partly done: the
test-server fixes landed; the one-implementation-per-platform consolidation it asks for
did not. Check each one, then close it.

## Wave 2, once the bug lane is clear

- **B3 Files: in review, #77.** Files reads the drive plane (CIRISServer 0.5.215):
  Just me lists the self room; Neighbours and Communities list every community room,
  grouped. Notes to self are in Just me › Chats. Family › Files says why it is empty
  (CIRISServer#627). The 17 `drive.*` and 5 `notes.*` ids are localized (#65). Live
  testing against a scratch node found two bugs, both fixed in #77. CSD-007 records
  the surface.
- **B4 Just me**, then **B5 Family**. Each circle ships when all seven of its tabs work.
- **S2 / G1**: the Moderation CSD and its test tags come before **B6 Neighbours**.
- Runs alongside: F2 renderer composables, per card; F3 colour-literal baseline
  928 → 0; F7 CSD `state` / `each`; F8 `scopes:` on `csd:surface`.

## Waiting on others

| | ask | state |
|---|---|---|
| CIRISServer#616 | `/v1/contacts` carries the grant's envelope | implemented upstream in 0.5.213; the People receipt can now fill those rows |
| CIRISServer#615 | a blob contract for files | answered by CONTENT_TRANSFER and server 0.5.215 |
| CIRISAgent#1181 | the runner's nav hops | open; needs a note: #64 moved hops (Constitutional → `tab_safety`, Memory → This node, Interact → `circle_agent → tab_chats → nav_epistemic_interact`), and #77 adds Files and Notes |
| CIRISAgent#1182 | one home per process (`CIRIS_HOME=X` means X) | merged 2026-09-21 |
| CIRISAgent#1193–#1196 | the four agent-side setup bugs above | filed 2026-09-24 |

## Deferred on purpose

- Geist / Geist Mono: the type scale ships on system faces until compose-resources
  fonts are proven on the iOS leg.
- The 13 accent themes stay until the per-circle work decides what to do with them.
- Scan in person / show my code: B12, not stubbed.
