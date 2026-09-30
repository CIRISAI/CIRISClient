# Locked Spec: roadmap

The order the client redesign is built in, and where it stands. **Updated 2026-09-29.**

This file is the plan. It tracks dependencies, not dates. The two Gantt artifacts were deleted,
and the plan now lives here and in backlog issue #71. The Card Atlas, the execution plan and the
handoff are still the design's own documents; this page records only what order they are built in
and how far along each part is. The item IDs (F, S, N, B, G) are the execution plan's.

## Where we are

- **Released:** client **0.5.224** is still the latest tag. **0.5.225 is PR #131** (the `VERSION`
  bump and a validated `compat/matrix.json` row: node_min 0.5.168, node_max_tested 0.5.217,
  agent_min_tested 2.12.1, 12 new capability ids, 29 languages × 5,059 keys); the tag follows once
  `build.yml` is green on the merge. It carries everything below. Server **0.5.217** is the latest
  server release; **0.5.218 is not cut**. Agent **2.12.1** shipped CIRISAgent#1213 (the node proxy
  forwards every `/v1` path the agent does not serve itself), and six CSDs say so (#128).
- **79 CSDs** (`FSD/CSD`): **7 testable, 65 building, 5 sketched, 2 envisioned**. New since the
  last pass: CSD-106 membership invitations (envisioned, #137) and CSD-107 Where is this file (#139).
  Before that: CSD-007 Files and CSD-008 Notes to self (#77), CSD-105 This node's
  trust root (#126), CSD-100/101 household and members (#122). CSD-092 (share contact code) is
  now building. **Sketched:** 037 My Identity, 042 Help, 044 Health & Reputation, and 093/094
  (the second-device cards, which wait for server 0.5.218). **Envisioned:** 030 Telemetry, 106 membership invitations.
- **Both review batches are merged.** #124 was runtime, identity, interact, setup, network,
  moderation. **#126** is batch 2: **accord / trust root** (the two unpushed trust-root branches
  folded in, a `TrustRoot` detail with posture, import, un-trust and family history, all at the
  node URL; `canonical/supersede` wired as a real rotate; CSD-105 written), **people / chats** (chat
  and contacts calls at the node URL, rooms of more than two open by id, 0.5.218's
  `unopened_reason` handled), **consent / data** (one owner for the accord-settings write, logout
  clears the previous owner's consent record, four-state watchlist, every irreversible act behind
  a three-fact confirm), **adapters / hubs** (the households dispatcher split so each act has one
  screen; moderators "could not read" distinct from "none"). #129 fixed Codex's five findings on it
  (consent reset races, owner-lookup failure, Enable after a failed read, chat pinned to its node,
  YubiKey probe states); #127 replaced English left in six locale bundles.
- **The cards that were in review are in:** #77 Files and Notes (B3), #121 communities, #122
  households, #123 erasure. `PENDING-CSD-007.md` is folded into CSD-007 and gone.
- **The checks:** route gate on `main` — **uncited routes 2** (121 at #111, 14 after #124),
  duplicate mutating routes 23, baseline current, exit 0. 302 API methods, 294 routes, 79 screens
  (70 reach a route), 824 CSD citations. `:shared:desktopTest` 1276/1276 (#129); `pytest testing/`
  323 (#126).
- **The flow runner is on the matrix (#97)** and **a two-node fixture stands beside it (#130)**:
  a Docker-free second `ciris-server` on 5242/5243 per leg, claimed, announced, peered both ways,
  each owner a contact of the other. Both ran **all five legs green in one run**; `main`'s latest
  Five-Platform Live QA is green (2026-09-29). One flow is live (`testing/flows/people.yaml`,
  CSD-005); **37 drafts** wait under `testing/flows/drafts/`.
- **Seven CSDs are `testable`** (#133): 005 People, 006 Receipt, 008 Notes to self, 047 Network
  content, 057 Wallet, 068 Provision an accord holder, 092 Share contact code. Their flows passed on
  all five legs in run 36775704425 (Linux, macOS, Windows desktop; Android emulator; iOS simulator)
  against released floors `>=0.5.224`/`>=0.5.225`. CSD-101's flow passed too; it stays `building`
  on `x_private:membership_invitation` (CIRISPersist#955). Getting there took seven matrix runs and
  fixed real client bugs on the way: a stale-circle tab hop, a disabled button that swallowed
  automation clicks, a receipt sheet whose Close was below the fold on a phone, and a claim PIN the
  Android app could not reach.
- **Next: the 30 drafts** under `testing/flows/drafts/` — the same route: a released floor, a nav
  hop, a green matrix run.
- Open since the bug lane: #50 (iOS SIGABRT; 0.5.224 writes a Kotlin crash log to read on the
  next failure), #51 (logout on a phone with no agent; addressed by #64, needs a phone check), #39,
  #33, #65 and #78 (bundle keys for 0.5.215/0.5.216; the routes are now called, the ids are not
  all bundled).
- **Transferred in from CIRISServer (#80–#89, #91):** stale LLM checks, the dead-`:8080` Play
  Integrity post, failing vendored unit tests, English picker titles, the FOREIGN_ALPHABET ratchet,
  node trust from CEG state, the test-automation audit, the §4.5.13 reveal loop and content hooks,
  secure storage surviving an identity wipe, and the mesh app parity item (#88). Not yet triaged
  into lanes.
- **Filed from the CSD and review work:** #96 (Announce step copy), #102 (nothing signs the 29
  locale bundles), #103 (CSD-037 fields rendered, still tagged `proposed:`), #104 (Help shows the
  app's version, never the node's), #105 (Health & Reputation reads the same in two circles),
  #110 (Login infers instead of reading `signin-state`; batch 1 reads it, the issue stays open
  until the card is re-checked), #114 (rc5: the reference matcher into `check_csd_v3`).
- From the CSDs, not yet in a PR: Account vs Settings (a separate `Screen.Account`), the text
  fields without an input sink (drivable offenders 151 after #124), `nav_map` hops so staged flows
  can start off `Contacts`, and the five sketched cards above.

## Deferred on purpose

- Geist / Geist Mono: the type scale ships on system faces until compose-resources fonts are proven
  on the iOS leg.
- The 13 accent themes stay until the per-circle work decides what to do with them.
- Image, audio and video previews: renditions only (`FSD/MEDIA_EDGE.md` §7), after CIRISServer#614.
