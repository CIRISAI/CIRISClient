# Client 0.5.244: harden, and ship installers

**Status:** draft, 2026-11-27. Asked of CIRISAgent and CIRISServer the same day; their answers
replace the "OPEN" lines below. **Owner of this file:** CIRISClient.

Eric asked, after the first real day on 0.5.243 (2026-11-26/27): the next release hardens against
what happened, and it is time for dedicated platform installers. This file is the plan for that
release. [ROADMAP.md](ROADMAP.md) points here.

## 1. What happened

| # | What Eric saw | Cause | Fixed so far |
|---|---|---|---|
| 1 | A call that looked open and carried nothing, both ways, for 48 s | The other person's node had been off the network since 11-21. No leg came up, so the node never sent `ready`, and the client never started its microphone or camera. 0.5.242 had no way to say so | 0.5.243 shows the can't-connect panel (from `media_path_failed`), "Connecting…", and "hasn't reached anyone" after 20 s (#323, #334) |
| 2 | No picture of himself | The camera opened only once a picture was being sent | 0.5.243: self-view from the join (#334) |
| 3 | Two tiles reading "Someone in this room" | The join answer's `attendee_people` was not read | 0.5.243 (#334) |
| 4 | The desktop window froze: no click did anything | A room's "Files in this room" panel was a `ModalBottomSheet`, and the file view inside it opened a second one. Compose Desktop then routed every click to an invisible top layer. The JVM was healthy (no blocked thread) | branch `fix/room-files-nested-sheet`: the panel is no longer a sheet |
| 4a | Calls with Francesco could not work even when his machine was on | Two Pythons on his machine: `pip install -U` upgraded 3.15's copy while his shortcut ran 3.14's, with agent 2.23.1 and server 0.5.250 (CIRISAgent, 2026-11-27) | CIRISAgent#1385 (2.23.4, not merged) reports the install; client item 2.6 |
| 4b | (Found 2026-11-27, not yet seen by a person) A desktop launched by the agent or server has no FFmpeg, so a call there has no video either way | The jar finds FFmpeg through its manifest Class-Path `../../ciris_holon/media/jars/`, relative to its own folder. `ciris_server.upgrade.private_jar` and the agent's fallback run a copy from `$CIRIS_HOME/runtime/desktop/`, where that path leads nowhere, and the JVM ignores it silently. Checked on Eric's install: present in place, both jars missing from the copy | Reported to CIRISServer and CIRISAgent: copy `ciris_holon/media/jars` beside the jar, or launch with an explicit `-cp`. Client item 3.6 |
| 5 | Video crossed nowhere on the first call check against 0.5.254 | The encoder was opened at 640x480 and the test camera sent 320x180. Then phones and desktops shared no codec | 0.5.243 (#330, #333: AV1 both ways by libaom) |

**The pattern:** each failure left the person with a screen that looked fine while nothing
worked, and no test caught it before a person did. The release hardens both halves: what the
person is told, and what the matrix proves.

## 2. Harden: the client

1. **No modal inside a modal, by rule.** A sheet or dialog may open only from a screen, never from
   inside another sheet or dialog. Content that opens sheets is hosted in `RoomPanel` (a popup) or
   inline. A desktop test drives a click inside each sheet opened from each host
   (`NestedSheetClickTest`, started with the fix). A source check in `readiness/` flags a
   `ModalBottomSheet` or `AlertDialog` call reachable inside another's content lambda. It is a
   heuristic gate and says so.
2. **A window that stops answering says so.** The desktop counts input events that reached no
   handler while a modal layer is open. Past a threshold it closes the top layer and logs
   `[ui] input swallowed by an overlay`. It is a safety net that tells us about the bug, not a fix
   for it.
3. **Reach before the call.** The contact's reach (CSD-005, `contacts.reach`, "not heard from
   since") shows on the call button and the call screen. "Francesco's device was last reachable 5
   days ago" comes before anyone waits.
4. **Every "ready" sentence is earned.** "Your microphone and camera are ready" only after the
   devices actually opened. The pre-join screen reads what the devices did, not what was asked.
5. **The call check runs the real shape.** The five-platform call check adds a leg where the peer
   node is OFFLINE and asserts the screen says so within 30 s, and a leg through ciris-agent 2.23.2's
   call forwarding (today an expected failure on 2.23.1).
6. **Show what is installed, and upgrade the right Python.** From ciris-agent 2.23.4 (CIRISAgent#1385,
   not merged), `GET /v1/system/health` carries `install`: `{agent_version, server_version,
   client_version, python_version}` for everyone, and for a signed-in person `{python_executable,
   upgrade_argv, upgrade_command}`. This node and Help show the versions and the Python path. When
   PyPI has a newer ciris-agent, a banner offers "Upgrade now", which runs `upgrade_argv` exactly as
   given, and a copy button for `upgrade_command`. The client never builds a command from bare `pip`.
   Absent on agents before 2.23.4; null where the agent could not describe itself.
7. **A heartbeat for real hangs.** The desktop writes `$CIRIS_HOME/run/desktop_ui.heartbeat`
   (epoch ms and pid) from the UI thread every 5 s. CIRISAgent's `desktop_launcher.py` logs "client
   window unresponsive" past 30 s and offers a restart. Note that this would NOT have caught today's
   freeze: the UI thread was idle and healthy, and only input was lost. Item 2 catches that.
8. **Ringing (Eric: yes, 2026-11-27; server 0.5.255).** Someone not on the call screen is told a
   call started. The node sends `call_ringing` and `call_ring_ended {why: answered|declined|
   cancelled|timeout|answered_elsewhere}` on its event stream; `POST /v1/chat/{room}/call/decline
   {reason: declined|busy}`; the caller's socket gets `declined` or `no_answer` (server timeout 45 s).
   Never stored, room members only, rate-limited. The client: an incoming-call banner and sound,
   Answer and Decline, busy while already in a call, and "Ringing …", declined and no answer for the
   caller. The call check gains a ring-answer and a ring-decline step. **Limit:** it rings only while
   the app is running. Waking a backgrounded phone is CIRISServer#24 (after 2.24; the wake carries
   only "wake", and the device fetches over the mesh).


## 2a. Harden: the server (CIRISServer's proposals, 2026-11-27; for its next cut, with Eric)

- **Warn at join, never refuse.** The person may come online mid-call, and the room is still theirs.
  The join answer and the call state gain `reach`: per attendee to be, `{member, person,
  last_heard_ms, reachable_now}`. `last_heard_ms` is the server's existing "last heard from" read
  (the one behind "not heard from since"). `reachable_now` (a live link or a known path) is edge's
  fact, which the server is asking edge for. `media_path_failed` stays the later, definitive word.
  Client 2.3 reads `reach` where it is sent, and falls back to the contact's reach where it is not.
- **`connecting` before `ready`.** On socket open the node sends `connecting {members, since_ms}`.
  After 20 s with no `ready` it sends `connecting` again, with `reach`. The client's "hasn't
  reached anyone" then gives the node's reason, not only a timer.
- **The join answer's shape.** `call_id` as hex, not Rust Debug (`call_routes.rs:693`);
  `service_tier` and state as tokens; `frame_budget_bytes` null, not 0, until a leg is up; people
  for every attendee.
- `call.full` and `call.shaped_only` leave the server's KNOWN_UNLOCALIZED, with its client floor
  at 0.5.243.

## 3. Harden: the release itself

1. **A tag needs a five-platform run on the release commit.** 0.5.243 was tagged on two earlier
   runs, at Eric's request, and the compat row says so. From 0.5.244 the publish workflow refuses a
   tag whose commit has no green Five-Platform Live QA with the with-AI legs on the latest agent. The
   rule already in memory becomes a check.
2. **The iOS node-only leg is green.** It failed setup on its 0.5.254 node in run 45832940789 and is
   not investigated. It is a release blocker.
3. **An iOS call runs.** libaom on iOS is built and unit-tested (`AomIosTest`). A two-client call on
   the simulator has not run.
4. **A real-network call.** One leg calls a node that reaches the runner only through the canonical,
   the path Eric's call needed.
5. **Publish survives a cancelled leg.** Twice now GitHub cancelled a macOS job (no runner, or
   stopped under its limit). The rerun path (`gh run rerun --failed`) worked; the workflow should
   retry the leg itself, once.
6. **The matrix launches the jar the way people get it.** A desktop leg starts the client through the
   agent's `desktop_launcher.py` (the private copy under `$CIRIS_HOME/runtime/desktop/`), and
   `packaging/check_holon_classpath.py` runs against that copy. 0.5.243's CI ran the jar only where
   it was built, which is how item 4b shipped.

## 4. Installers

**Today:** the desktop client reaches people as an uber-jar inside the ciris-agent fat wheel.
`ciris_engine/desktop_launcher.py` starts it with a jlinked JRE, else `JAVA_HOME`, else `java` on
the `PATH`. Phones ship as the agent's apps, built from our AAR and XCFramework. The node ships as
per-OS tarballs on CIRISServer's releases. Compose `nativeDistributions` (dmg, msi, deb) is
configured in `client/desktopApp/build.gradle.kts` and has never been built in CI.

**Proposed for 0.5.244:**

| Platform | Package | Built by | Notes |
|---|---|---|---|
| macOS (arm64, x86_64) | `.dmg` | Compose `packageDmg` on the macOS runners | Unsigned for 0.5.244 (tester build); Developer ID and notarization later |
| Windows x86_64 | `.msi` | `packageMsi` on windows | **`upgradeUuid` is a placeholder (`a1b2c3d4-…`). Replace it with a real one before the first MSI ships, and never change it after**, or upgrades install side by side. Unsigned for 0.5.244; Authenticode later |
| Linux x86_64 | `.deb` and `.AppImage` | `packageDeb`; AppImage from the distributable | One for Debian and Ubuntu, one for everyone else |

Each installer carries its own runtime (jpackage), so no system Java. FFmpeg comes from the
`ciris-holon` jars, as the uber-jar's Class-Path does today.

**What exists elsewhere (CIRISServer, 2026-11-27):** a Windows desktop installer workflow
(`.github/workflows/windows-installer.yml`, `installers/windows`), green for v0.5.254. Other
platforms have only the tarballs and the PyPI wheels. CIRISServer recommends: **a client installer
bundles no node of its own and installs the agent's (one node per machine, one version pin, the
agent's).** A node-only installer would reuse the Windows one per OS, and is on no roadmap.

**CIRISAgent's answer (2026-11-27):**
- **One installer per OS installs everything together:** the agent with a Python of its own, the
  node, and the desktop client. A client-only installer that "finds the agent" brings back exactly
  the two-Pythons bug. CIRISAgent suggests CIRISServer owns the installer shell (it already builds
  `CIRIS-Server-Setup-*.exe` with PyInstaller, Inno Setup and a trimmed JRE, for the node and client
  but not the agent), and the agent supplies the Python payload and the start and upgrade entry
  points.
- **Do not freeze the agent** (PyInstaller, Briefcase): it finds adapters through entry points,
  ships data files, and could not upgrade itself. Bundle a relocatable CPython
  (python-build-standalone 3.12 or 3.13) and pip-install the wheels into it at build time.
  `upgrade_argv` then points at that Python. Mobile already does the same (Chaquopy; the iOS bundle).
- **Updates go through the agent** (`upgrade_argv`), the same for installer and pip users. An
  installer version bump is needed only for a Python or JRE change.
- **Signing:** unsigned means SmartScreen and Gatekeeper warnings most people stop at. Unsigned
  works as a tester build only.

**The client's part of the payload (answered to CIRISServer, 2026-11-27):** the client jar (from the
`ciris_client` wheel), the `ciris-holon` jars for that OS at `../../ciris_holon/media/jars/`
relative to wherever the jar runs from, and a Java 17+ runtime. Nothing else: no entry point
outside the wheels, no other assets. It writes only under `$CIRIS_HOME`. Any copy the installer or
launcher makes must keep the holon jars at that relative place (item 4b).

**Decided (Eric to CIRISServer, 2026-11-27, relayed):** CIRISServer's §2a items are approved for its
next cut. The agent supplies one relocatable Python with the agent, server and client inside;
CIRISServer owns the Windows, macOS and Debian installer shells.

**Decided (Eric, 2026-11-27):**
- **This release ships UNSIGNED, for testing.** There is no Apple Developer ID or Windows
  code-signing certificate yet. Every installer leaves a marked, skipped signing step, and its
  release notes say plainly that Gatekeeper and SmartScreen will warn, and how to open it anyway.
  Signing is the gate between a tester build and a public one.
- **What the client contributes:** the desktop jar and its `ciris-holon` jars at the relative
  place, as answered to CIRISServer above. No jpackage'd app image of our own; CIRISServer's shells
  carry the runtime. The `upgradeUuid` placeholder is still replaced now, so that if a Compose MSI
  is ever built it upgrades in place.

## 5. Order

1. Merge the nested-sheet fix (`fix/room-files-nested-sheet`), with its test.
2. Section 2's client items, each with its test. 2.1 and 2.3 first.
3. Section 3.1, the tag check, before any other 0.5.244 work merges, so the release is held to it.
4. Section 3.2 and 3.3, the iOS legs.
5. Section 4, unsigned. The UUID fix goes in now.
   Client 2.6 waits on CIRISAgent#1385 merging, and 2.3 reads the server's `reach` once it ships.
