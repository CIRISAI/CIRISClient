# CSD-012 — Scheduler (what the agent will do later, under This node)

**CSD**: CSD-012 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**The owner can see every future act their agent has committed to, why it will
run and when, cancel any of them — and a count on this screen is a count that
was read.** Scheduling is the only place where an agent acts with nobody
watching, so it is the place where "what it will do" has to be legible in
advance rather than reconstructed from the audit afterwards. Serves the
**Tier-2 decision hierarchy** (CC 3.1.9.7): *nothing is done without a stated
reason for whom it serves* — a scheduled task carries a `goal_description`, and
that field is where the ladder either attaches or does not.

The falsifiable claim, and it fails today: **an empty task list on this screen
means the agent has no scheduled tasks.** `SchedulerViewModel.refresh()` wraps
each of its three reads in its own `try` whose `catch` is a `logDebug`
(`SchedulerViewModel.kt:141, :151, :160`), so a route that 500s yields
`tasks = emptyList()` with `state.error` still null and the screen draws "No
Scheduled Tasks — Create a task to schedule automated actions."

## 2. Surface (what)

```yaml csd:surface
surface: scheduler
screen: Scheduler
```

`nav_map` derives `btn_my_things -> nav_instrument_this_node ->
nav_epistemic_scheduler`. `agentOnly` (`CirclesNav.kt:155`) — correctly, since
`/v1/scheduler/*` exists only on the brain.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:task_name
    use: emit
    type: string
    example: "Morning digest"
    renders: "the row title, and the field the create dialog asks for first"
    tag: "proposed:scheduler_row_name"
  - ceg: approach:{goal_id}
    bind: {goal_id: "morning-digest"}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "WHY IT RUNS — today a free-text `goal_description` the person types (mobile.scheduler_goal). CC 3.1.9.7 wants an approach bound to a Goal object; the route carries a sentence."
    tag: "proposed:scheduler_row_goal"
    blocked_by: CIRISAgent#1208
  - ceg: x_private:schedule_cron
    use: emit
    type: string
    example: "0 9 * * *"
    renders: "Every day at 9:00 AM — cronToHumanReadable renders the expression back in words (CIRISApiClient.kt:13817)"
    tag: "proposed:scheduler_row_schedule"
  - ceg: x_private:task_status
    use: display-only
    type: "enum[PENDING,ACTIVE,COMPLETE,FAILED,CANCELLED]"
    example: "PENDING"
    renders: "a status chip on the row"
    tag: "proposed:scheduler_row_status"
  - ceg: x_private:deferral_count
    use: display-only
    type: int
    example: 3
    renders: "Deferred 3 times — the agent put this off, and how often is a fact about the agent"
    tag: "proposed:scheduler_row_deferrals"
  - ceg: x_private:tasks_completed_total
    use: display-only
    type: int
    example: 41
    renders: "the Completed stat tile"
    tag: "proposed:scheduler_stat_completed"
  - ceg: x_private:cognitive_state
    use: display-only
    type: string
    example: "WORK"
    renders: "the cognitive-state line under the stats (mobile.scheduler_cognitive) — read from telemetry, not from the scheduler"
    tag: "proposed:scheduler_cognitive_state"
  - ceg: commitment_fulfillment:{prior_contribution_id}
    bind: {prior_contribution_id: "unconfirmed"}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "DID IT ACTUALLY RUN — not shown. `last_triggered_at` is on the wire; whether the commitment was kept is the CC 3.1.9.2 family and nothing emits it here."
    tag: "proposed:scheduler_row_fulfilment"
    blocked_by: CIRISAgent#1208
```

**`approach:{goal_id}` is bound to show what the shape would be, and it is
`unconfirmed` because the wire has a sentence where CC has an identifier.**
CC 3.1.9.7 rules that `goal_id` denotes a `Goal` object's identifier and a
composer MUST NOT read a scale out of it. `ScheduledTaskData.goalDescription`
(`CIRISApiClient.kt:13799`) is free text the person typed into
`mobile.scheduler_goal`. So the card can render a reason and cannot join it to
anything — which is exactly the split-truth that ruling closes.

**Three stat tiles read 0 for a failed read.** `completedTotal =
statsData?.tasksCompletedTotal ?: 0` (`SchedulerViewModel.kt:171`) and its two
neighbours substitute a zero for an absent stats envelope. CSD/3 §3 names this:
reading a missing value as a zero is a score where there is none.

```yaml csd:states
populated: {tag: "proposed:scheduler_list", renders: "the stat row, the cognitive-state line, then one card per task"}
empty:     {tag: "proposed:scheduler_empty", renders: "No Scheduled Tasks — Create a task to schedule automated actions (SchedulerScreen.kt:211)"}
loading:   {tag: "proposed:scheduler_loading", renders: "a progress indicator INSTEAD of the list (SchedulerScreen.kt:135); the stat tiles are not drawn at all"}
error:     {tag: "proposed:scheduler_error", renders: "the error card at SchedulerScreen.kt:141 — which EXISTS and which the load path can never reach, because refresh() swallows all three reads"}
```

`empty` and `error` are drawn differently and the difference is unreachable.
The renderer is not the defect; `catch { logDebug(...) }` is.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the task list | `GET /v1/scheduler/tasks?limit=` | CIRISAgent (`routes/scheduler.py:182`) | live — `CIRISApiClient.kt:12173` |
| the stat tiles | `GET /v1/scheduler/stats` | CIRISAgent (`routes/scheduler.py:245`) | live — `CIRISApiClient.kt:12231` |
| create a task | `POST /v1/scheduler/tasks` | CIRISAgent (`routes/scheduler.py:286`) | live — `CIRISApiClient.kt:12304` |
| cancel a task | `DELETE /v1/scheduler/tasks/{task_id}` | CIRISAgent (`routes/scheduler.py:368`) | live — `CIRISApiClient.kt:12358` |
| the cognitive-state line | `GET /v1/telemetry/overview` | CIRISAgent (`routes/telemetry.py`) | live — `CIRISApiClient.kt:9690` |
| whether a past run happened | **no route** — `commitment_fulfillment:*`, CC 3.1.9.2 | CIRISNodeCore | **missing**; blocks `building` for `proposed:scheduler_row_fulfilment` |
| a Goal object to bind the task to | **no route** — `goal:{scale}` / `approach:{goal_id}`, CC 3.1.9.7 | CIRISNodeCore | **missing**; blocks `building` for `proposed:scheduler_row_goal` |

**Host check.** All five live routes are the brain's. `CIRISServer origin/main`
has no `/v1/scheduler` literal anywhere in `src/`. The notification and
calendar options in `SchedulerScreenState` (`SchedulerViewModel.kt:22`) are the
device's, not any host's.

**CIRISAgent working tree is dated 2026-08-15**, five weeks behind today.

## 4. Flow (how)

My things → This node → Scheduler, on an agent with at least one task.

```yaml
expect:
  state: populated
  visible: [btn_scheduler_create, btn_scheduler_refresh]
  count: {of: "proposed:scheduler_row_*", min: 1}
```

Create a recurring task: `btn_scheduler_create`, fill name and goal, pick a cron
preset, confirm.

```yaml
expect:
  count: {of: "proposed:scheduler_row_*", min: 2}
  matches: {proposed:scheduler_row_schedule: "Every day at 9:00 AM"}
```

Point the client at an agent whose scheduler service is down and refresh.

```yaml
expect:
  state: error
  visible: [proposed:scheduler_error]
  absent: [proposed:scheduler_empty]
```

**The third step fails today** and is the reason this CSD exists.

## 5. QA plan

**Platforms.** All five. The notification and calendar toggles branch per
platform and are asserted only as present, not as effective.

**Not tested here.** Whether a created task fires at its cron time — the suite
cannot wait a day, and nothing on the wire reports a past firing (see the
`commitment_fulfillment` row). Notification permission grants, which are a
platform dialog.

**Upstream ask.** CIRISAgent: carry a `last_run_outcome` on
`GET /v1/scheduler/tasks`, or say in the response that the agent does not track
one. Today `last_triggered_at` says when it started and nothing says what
happened, so a person who scheduled something cannot find out whether it worked.
