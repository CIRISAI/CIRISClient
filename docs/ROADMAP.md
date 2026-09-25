# Locked Spec: roadmap

The order the client redesign is built in, and where it stands. **Updated 2026-09-25.**

This file is the plan. It tracks dependencies, not dates. The two Gantt artifacts were deleted,
and the plan now lives here and in backlog issue #71. The Card Atlas, the execution plan and the handoff are still the design's own
documents; this page records only what order they are built in and how far along each part is.
The item IDs (F, S, N, B, G) are the execution plan's.

## Where we are

- **Done:** foundation, shell and spine, and the bug lane (released in **0.5.224**).
- **In review:** B3 Files (#77). It needs one pass for server 0.5.217 (below).
- **Next:** B4 Just me, then B5 Family. Everything they need upstream has shipped.
- **The CSD track is behind the build track.** B1 and B2 shipped ahead of the S1 receipt CSD they
  were meant to wait on, and 7 of 62 CSDs exist. Each circle's CSDs get written with the circle,
  and G3 holds the line: no circle is called shipped until its CSDs verify.

## Dependencies

Arrows read "must come before". Upstream items (red hexagons, dotted arrows) are what another repo still owes:
the lane can start, but cannot fully close without it. Done work is folded into the one green box.

```mermaid
flowchart TD
    classDef done fill:#1D7F45,stroke:#1D7F45,color:#fff
    classDef part fill:#F8EEDC,stroke:#B8791C,color:#000
    classDef todo fill:#fff,stroke:#6E6875,color:#000
    classDef next fill:#E3EEF3,stroke:#2B6E8C,stroke-width:3px,color:#000
    classDef up fill:#F6E2E2,stroke:#B23A3A,color:#000

    BASE[Done: F1 F3 F4 F5 foundation, N1-N10 shell and spine, G2 atlas, G4 nav contract, B1 People, B2 receipt sheet]:::done
    F2[F2 renderers, per card]:::part
    F6[F6 invariant guards]:::part
    F7[F7 CSD DSL]:::todo
    F8[F8 scopes on the surface]:::todo
    S1[S1 receipt CSD]:::part
    S2[S2 moderation CSD]:::todo
    G1[G1 moderation exposure contract]:::todo
    S3[S3 universal cards x9, 1 written]:::part
    S4[S4 divergent cards x7]:::todo
    S5[S5 new cards x16]:::todo
    S6[S6 identity, rosters, ledgers x14, 1 written]:::part
    S7[S7 instruments and flows x14]:::todo
    B3[B3 Files, #77]:::next
    B4[B4 Just me]:::next
    B5[B5 Family]:::todo
    B6[B6 Neighbours]:::todo
    B7[B7 Communities and Businesses]:::todo
    B8[B8 Everyone]:::todo
    B9[B9 instruments]:::part
    B10[B10 setup wizard]:::todo
    B11[B11 multi-self]:::todo
    B12[B12 contacts, groups, rosters]:::todo
    G3[G3 per-circle CSD verification]:::todo

    U614{{Server 614: renditions}}:::up
    U646{{Edge 646: bytes on own devices}}:::up
    U675{{Edge 675: file signed by the person}}:::up
    U910{{Persist 910: family roster replication}}:::up
    U907{{Persist 907: late community members}}:::up
    UB7{{No routes: terms, ledgers, group book}}:::up
    U248{{Server 248: signed trust root}}:::up
    UB8{{No route: shared knowledge}}:::up

    BASE --> F2 & F6 & B3 & B9 & B10
    F7 --> F8 --> S1 & S2
    F7 --> G3
    S1 --> S3 & S4 & S5 & S6 & S7
    S3 --> B3 --> B4 --> B5 --> B6 --> B7 --> B8
    S2 --> G1 --> B6
    S4 --> B6
    F6 --> B6
    S6 --> B7 & B11 & B12
    S5 --> B8
    S7 --> B9
    B4 --> B11
    B5 --> B12
    U614 -.-> B3
    U646 -.-> B3
    U675 -.-> B3
    U646 -.-> B11
    U910 -.-> B5
    U907 -.-> B6
    UB7 -.-> B7
    U248 -.-> B8
    UB8 -.-> B8
```

Green = done · amber = partial · blue outline = next · white = not started · red hexagon = owed upstream.

## Plan against actual

| | item | status | where |
|---|---|---|---|
| F1 | Bind the namespace (122-entry dimension table, generated) | **done** | #62 |
| F2 | Eleven renderers | **partial**: the `Renderer` enum and mapping exist; the composables are written per card as circles land | #62 |
| F3 | Token set + colour-literal lint | **done**; the baseline ratchets toward 0 per circle | #62 |
| F4 | Icon set (71 glyphs) | **done** | #62 |
| F5 | Nine primitives | **done** | #62 |
| F6 | Invariant guards | **partial**: CardShell never nests, error never looks like empty, no hamburger without a receipt | #62 |
| F7 | CSD DSL (`state`, `each`) | not started | — |
| F8 | `scopes:` on `csd:surface` | not started (after F7) | — |
| N1–N4 | One nav tree, the rail, the seven-tab frame, gating deleted | **done** | #63 |
| N5–N10 | The spine | **done** | #64 |
| S1 | Receipt CSD | **envisioned** (CSD-006) | — |
| S2 | Moderation CSD | not started; must come before B6 | — |
| S3–S7 | Card CSDs | **2 of 60**: Files (CSD-007, S3) and People (CSD-005, S6), plus four pre-redesign card CSDs (001–004) to rebind | #62, #77 |
| B1 | Proof screen: People | **done** | #62 |
| B2 | Receipt sheet | **done** | #62 |
| B3 | Files | **in review** | #77 |
| B4–B8 | The five circles | not started | — |
| B9 | Instruments | **partial**: My things pages exist and name their instrument | #64 |
| B10 | Setup wizard, new shape | not started; the bug fixes (#73, #74) landed in the old shape | — |
| B11 | Multi-self | not started, **now unblocked** | — |
| B12 | Contacts, groups, rosters | not started, **now unblocked** | — |
| G1 | Moderation exposure contract | not started | — |
| G2 | Atlas against the new IA | **done** | #64 |
| G3 | Per-circle CSD verification | not started (needs F7) | — |
| G4 | Nav contract (49/49 hops) | **done** | #64 |

## CSDs: 7 written, 62 planned

The plan is one CSD per card: S1 receipt 1 · S2 moderation 1 · S3 universal ×9 · S4 divergent ×7 ·
S5 new ×16 · S6 identity, rosters, ledgers ×14 · S7 instruments and flows ×14 = **62**.

| CSD | card | stage | plan slot |
|---|---|---|---|
| 001 | Delegation | sketched | pre-redesign; rebind to its circle |
| 002 | Environment | sketched | pre-redesign; rebind |
| 003 | Constitutional (the accord) | sketched | pre-redesign; lives under Everyone › Safety |
| 004 | Capacity attestations | sketched | pre-redesign; rebind |
| 005 | People | sketched | S6 |
| 006 | Receipt | envisioned | **S1** |
| 007 | Files | sketched | S3 |

None is at `building`. The rule stands: a circle is not shipped until its CSDs verify (G3), so each
circle's CSDs are written with the circle, not in a batch ahead of it.

## Has the other side shipped what each lane needs?

**Server: mostly yes, as of 0.5.216 and 0.5.217 (2026-09-25).**

| lane | needs | state |
|---|---|---|
| B3 Files | drive plane, CRUD (rename, replace, move, withdraw, `/meta`, paging, 64 MiB uploads), write gate, `/v1/media/policy`, plaintext `content_digest` | **shipped** (0.5.215–0.5.217). Open: renditions (CIRISServer#614), a digest signed into the row (#641, CIRISEdge#638), bytes on the owner's other devices (CIRISEdge#646, CIRISServer#604) |
| B4 Just me | notes, own devices (`/v1/self/occurrences`, labels, release), self files | **shipped** (0.5.216) |
| B5 Family | `/v1/families` (create, roster, roles, quorum changes), family files | **shipped** (0.5.216). Gaps: roster changes after creation don't replicate, and a removed member can't be re-added (CIRISPersist#910) |
| B6 Neighbours | `/v1/communities`, `/v1/safety/moderation`, `/v1/safety/watchlist`, named moderators | **routes exist**. Gap: a member added after creation is refused reads (CIRISPersist#907). The client-side S2/G1 come first |
| B7 Communities and Businesses | affiliations tier, commons standing | **partial**: `tier: affiliations` and `/v1/commons/standing` exist; **no routes for terms, ledgers or the group book** |
| B8 Everyone | trust roots, the accord, commons ballots and objections | **routes exist**. Open: `/v1/accord-holders` serves the trust root unsigned (CIRISServer#248). **No route for shared knowledge** |
| B11 multi-self | own devices, self content on each device | rows **shipped** (CIRISPersist#884 closed); bytes wait on CIRISEdge#646 |
| B12 contacts, groups | contacts with their `grant` envelope, families, communities, the cosign ceremony | **shipped**. Holder count: CIRISServer#616 |
| People receipt | the grant's envelope on `/v1/contacts` | **shipped** in 0.5.213; the client doesn't read it yet |
| file authorship | a file signed by the person, not the node | open: CIRISEdge#675 ("author only" works only from the writing device) |

**Agent: what the client needs is in flight.** CIRISAgent#1184 pins server 0.5.217 and client
0.5.224, and its five-platform run is pending. That run closes the hand-off family (#43, #47,
#48). Also open: CIRISAgent#1181 (the runner's nav hops for the new shell) and #1193–#1196 (the
four setup bugs on the agent side). None of them blocks B3–B5.

## B3: one pass before merge

Server 0.5.217 changed what #77 should do:
1. The write gate refuses a file whose bytes contradict its declared type, including a real JPEG
   sent as `application/octet-stream`. That is what #77 sends when a picker reports no type. Fix:
   declare the type sniffed from the bytes.
2. Uploads go up to 64 MiB. Read the caps from `/v1/media/policy`, not the compiled-in 1 MiB.
3. `content_digest` is on the open-file response, so verify it (CC 5.3.2.5) before anything renders.
4. Name the note-only `unreadable` state.
5. Bundle the 0.5.216 and 0.5.217 message ids (#78; #65 is already in #77).

## Deferred on purpose

- Geist / Geist Mono: the type scale ships on system faces until compose-resources fonts are proven
  on the iOS leg.
- The 13 accent themes stay until the per-circle work decides what to do with them.
- Image, audio and video previews: renditions only (`FSD/MEDIA_EDGE.md` §7), after CIRISServer#614.
