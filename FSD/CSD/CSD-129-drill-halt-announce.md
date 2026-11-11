# CSD-129 — Drill, halt, announce: end to end

**CSD**: CSD-129 · **Standard**: CSD/4 (`CSD.md` v4 — `derives:` + `topology:` as a catalogue id; CIRISClient#179) · **Origin**: the kill-switch cut (CIRISServer 0.5.225, CIRISConstitution#146 rc7/rc8) · **Companion**: CSD-067 (Accord), CSD-003 (Constitutional card), CSD-105 (This node's trust root) — the surfaces this card's scenarios assert on · **Harness**: CIRISServer `harness/native` scenario `halt` on catalogue entry T8

```yaml csd:stage
stage: testable
owner: CIRISServer (scenarios) · CIRISClient (surfaces) · CIRISConstitution (clauses, claims)
derives: [4a, 4b, 4c, 4d, 4e, 2a]
topology: T8
```

## 1. Mission (why)

**A single seated holder can pause every AI agent in the federation in one step, with
nothing else disturbed; no single holder can keep them paused alone; only a strict
majority of the standing roster keeps or ends the pause; and a person can tell a
drill, a notice, a halt and a resumption apart at a glance.** Serves **Core Identity**
and **Integrity** (CSD-067 §1); the end-to-end here is what CSD-067's card shows.

## 2. Surface (what)

No new screen. The scenarios observe `GET /v1/accord/halt-status` on every node and
assert the states CSD-003 and CSD-105 render:

```yaml csd:shows
fields:
  - ceg: "accord:halt_status"
    type: "enum[halted,lapsed,not_halted,unknown]"
    renders: "ACTIVE HALT — agents paused by invocation {halt_id}; lapses at {lapses_at} unless confirmed"
  - ceg: "accord:halt_confirmed"
    type: "bool"
    renders: "CONFIRMED by {signers} — stands until a majority resumes"
  - ceg: "accord:invocation"
    type: "enum[constitutional,notify,drill,lifecycle:confirmed,lifecycle:active]"
```

## 3. Scenarios (CSD/4: a catalogue entry and a set of scenarios over it)

Topology **T8**: an infrastructure root (the humanity accord) with three seated software
holders at `quorum:2/3`, a canonical booted on the ceremony-minted bundle, two people's
nodes claimed, announced, peered and Rooted through it; the charter's `halt_fuse_secs`
overridden short for the ladder.

| id | steps | expect (clause · claim · predicate · observe) | refusals |
|---|---|---|---|
| S1 drill | holder A signs `drill` on the canonical | CC 4.2.1.2 · `CLM-halt-one-row` · `quorum_met=true` at one signature; surfaced in `/v1/accord/events`; **no latch on any node** | — |
| S2 announce | holder B signs `notify` with a message | CC 4.2.1.2 · — · surfaced as an announcement; no latch | — |
| S3 fire | holder A signs `constitutional` on node A; the object is handed to the other nodes out of band | CC 4.2.1.1 · `CLM-halt-one-row`, `CLM-accord-reach` · every node `halted=true`, each with its own `received_at` and `lapses_at`; `/health` 200 on every node (zero data disruption); the ingest door answers **423 `accord_halt_in_effect`**; a replay re-latches idempotently | — |
| S4 confirm | holder B opens `lifecycle:confirmed` (`confirms_halt_id`), holder C concurs; the object handed to every node | CC 4.2.1.3 · `CLM-halt-authority` · one signature confirms nothing; the majority sets `lapses_at=null` everywhere | — |
| S5 resume | B+C `lifecycle:active` naming another halt; then naming this one | CC 4.2.1.3 · `CLM-halt-authority` · the wrong id releases nothing; the right one clears every latch; ingest opens again | `resumes_unknown_halt` |
| S6 fuse | holder C fires a second halt; wait `fuse` | CC 4.2.1.1 "the fuse" · `CLM-halt-authority` · every node reads `halted=false, lapsed=true` after `fuse` s from ITS receipt | — |
| S7 late confirm | B+C confirm the lapsed halt | CC 4.2.1.3 · — · confirms nothing; a fresh halt is fired instead | `halt_fuse_lapsed` |

Negatives (asserted by the scenario, not the build layer): the node never exits under
a constitutional halt; no operator tier releases it; a lone `lifecycle:active`
releases nothing.

**Ladder evidence:** CIRISServer `harness/native` scenario `halt` on T8 ran green 2026-10-13 (26 steps, 20 s fuse, `first_failure: null`) on branch `feat/225-kill-switch`.

Not covered yet: an agent process on a node whose act gate reads the status
(CIRISAgent#1231); `rows.jsonl` per node for CC's offline replay (T8 iii) and
`roster_fold_digest` (T8 vii); a real YubiKey holder.

## 4. Routes

`POST /v1/accord/{halt,drill,announce,invocation,invocation/concur,message}`,
`GET /v1/accord/{halt-status,events}`, `POST /v1/ingest/accord-events` (the gate).
Wire: CIRISServer `FSD/CONSTITUTIONAL_HALT.md`.
