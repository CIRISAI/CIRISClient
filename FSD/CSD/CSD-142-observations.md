# CSD-142 — Observations (a notice others can support, rebut or ask to clarify)

**CSD**: CSD-142 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the owner's request (2026-11-10) to fill the card set with the NodeCore and Registry screens
**Reads with**: CSD-065 (Moderation: a formal allegation, which this is not), CSD-143 (Drafts: a private observation before it is shared), CSD-135 (Feeds)
**Sources read**: CIRISNodeCore `feat-scale-model-v0.7-fountain` @ `93b94e0` (`SCHEMA.md` §4.20–§4.21, `src/payloads/notification.rs`, `src/payloads/notification_response.rs`); CIRISServer `origin/main` @ `7af5d4eb`
**Flow**: unwritten. No route and no screen

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person can post an observation about the world or about something they did,
and others can say they support it, rebut it, or ask for clarification, each with
a reason, without anyone making an accusation.**

NodeCore's `notification` is "peer-to-peer fire-and-forget update about the
environment or the results of an action" (`SCHEMA.md:737-738`), and its
`notification_response` is a peer's `support`, `rebut` or `clarify` with a
required rationale: "peers concur with or dispute an observation without
escalating to a §4.11 `moderation_event` formal accusation" (`SCHEMA.md:772-776`).

Falsifiable:

* **A response is never a verdict.** The card shows the counts of each stance
  and the reasons, and draws no "true" or "false" from them. NodeCore says
  "aggregation is consumer-policy" (`SCHEMA.md:797-798`) and defines none, so the
  card counts and does not weigh.
* **Rebut is not report.** Rebutting an observation sends a
  `notification_response`; reporting it is CSD-065's flow and a different row.
  The two buttons are apart and named differently, because confusing a
  disagreement with an allegation is the error §4.21 exists to prevent.
* **An anomaly is marked as one.** The `anomaly` category "MAY require
  witness-set at consumer policy" (`notification.rs:26-27`); the card labels it
  "Anomaly" and does not hide it, and it states no witness rule the node has not
  sent.
* An expired observation (`expires_at` past) is shown greyed with "No longer
  current (expired 3 Nov)", not removed.

## 2. Surface (what)

No `csd:surface` block yet (CSD-108's precedent). A list of observations at a
circle, each opening to its responses.

```yaml
# csd:surface once the screen is declared in CIRISApp.kt
surface: observations
screen: Observations
```

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:notification_title
    use: display-only
    type: string
    example: "Reticulum transport degraded on eu-1 region"
    renders: "the row's title, with its category chip and the sender"
    tag: "proposed:row_observations_notice.<contribution_id>"
  - ceg: x_private:notification_category
    use: display-only
    type: "enum[environment,action_result,state_change,anomaly]"
    example: "environment"
    renders: "'About the world' · 'Something I did' · 'My state changed' · 'Anomaly'. An operator-defined category (the `Custom` variant, notification.rs:28-30) is shown verbatim"
    tag: "proposed:chip_observations_category.<contribution_id>"
  - ceg: x_private:notification_context
    use: display-only
    type: string
    example: "Packet loss climbing past 30%…"
    renders: "the observation's text, in full on the detail view"
    tag: "proposed:txt_observations_context"
  - ceg: x_private:notification_subject_ref
    use: display-only
    type: string
    example: "edge_node_eu_1"
    renders: "'About: edge_node_eu_1', verbatim. It is a free-form reference (SCHEMA.md:760); the card links it only when it is a contribution id this node holds"
    tag: "proposed:txt_observations_subject"
  - ceg: x_private:notification_evidence
    use: display-only
    type: "list[string]"
    example: ["trace_01HX…", "trace_01HY…"]
    renders: "one row per evidence reference, verbatim"
    tag: "proposed:row_observations_evidence.<n>"
  - ceg: x_private:notification_expires_at
    use: display-only
    type: timestamp
    example: "2026-11-16T00:00:00Z"
    renders: "'Current until 16 Nov'; past it, the row is greyed with 'No longer current'"
    tag: "proposed:txt_observations_expires"
  - ceg: x_private:notification_stance_count
    use: display-only
    type: unconfirmed
    example: "3 support · 1 rebut · 2 ask to clarify"
    renders: "three counts, never combined into a score"
    tag: "proposed:stat_observations_stance.<stance>"
  - ceg: x_private:notification_response
    use: display-only
    type: unconfirmed
    example: "rebut · key:fc01… · 'Our probes in the same region show no loss.'"
    renders: "one Transcript row per response: the stance, who, when and the required rationale"
    tag: "proposed:msg_observations_response.<contribution_id>"
  - ceg: x_private:notification_response
    use: emit
    type: unconfirmed
    example: "clarify"
    renders: "three actions, Support · Rebut · Ask to clarify, each opening a field for the reason (required, SCHEMA.md:792) and optional evidence, sent from a ConfirmSheet naming the observation and the stance (L6). Report is a separate action that opens CSD-065"
    tag: "proposed:btn_observations_respond.<stance>"
  - ceg: x_private:notification
    use: emit
    type: unconfirmed
    example: "environment · 'Water main burst on 5th Street'"
    renders: "Post an observation: title, text, category, optional subject and evidence, optional expiry; sent from a ConfirmSheet naming the circle it goes to"
    tag: "proposed:btn_observations_post"
```

**No registered family fits, and one must not be borrowed.** `notification` and
`notification_response` are Contribution `subject_kind`s (`SCHEMA.md:219`), not
dimensions, so every field is `x_private:` (CSD-135 §2's finding). The registry's
`observation:` stem is **gated and closed**, "observation:reachability is the
only leaf" (registry `_meta.reserved_stems`, CC 3.1.9.4); it is a reachability
probe and must not be used for these. `moderation:{allegation_type}` is
deliberately not bound: binding it would draw a response as an allegation.

```yaml csd:states
populated: {tag: "proposed:state_observations_list", renders: "observations at this circle, newest first, each with its stance counts"}
empty:     {tag: "proposed:state_observations_list__empty", renders: "'No observations shared at this circle.' Only on an answered empty list"}
loading:   {tag: "proposed:state_observations_list__loading", renders: "the frame with a progress affordance and no sentence"}
error:     {tag: "proposed:state_observations_list__error", renders: "'Could not read observations', with the node's words. A failed responses read under a drawn observation is `state_observations_responses__error`, never '0 support'"}
```

## 3. Contracts (who)

**There is no route.** CIRISServer does not link NodeCore (`Cargo.toml:128`).

| value | endpoint | owner | state |
|---|---|---|---|
| observations at a circle | no route and **no function**: the payload is defined (`NotificationPayload`, `src/payloads/notification.rs:43`) but no compose lists notifications | CIRISNodeCore; CIRISServer folds NodeCore at Server 1.0 | **missing** |
| responses to one observation, with counts | no route and no function; payload `NotificationResponsePayload` (`src/payloads/notification_response.rs:29`) | CIRISNodeCore | **missing** |
| post an observation; respond | no route; Python exports only the generic `build_contribution_envelope` | CIRISNodeCore / CIRISServer | **missing** |
| withdraw one's observation | `cancellation` (§4.28) per `SCHEMA.md:796-797` | CIRISNodeCore | **missing** |

## 4. Flow (how)

Every tag is `proposed:`. Open an observation with responses:

```yaml
expect:
  state: populated
  count: {of: "proposed:stat_observations_stance.*", eq: 3}
  visible: ["proposed:btn_observations_respond.rebut"]
```

## 5. QA plan

Not drivable. When routes exist: assert the three counts match the response rows;
assert Rebut sends a `notification_response` and never a `moderation_event`.

## 6. Status and asks

Sketched. Open question: whether a private local observation (CSD-143) can become
one of these. `promote_payload` changes `sub_kind` within `external_content`
only (`is_promotable_sub_kind`, `src/ingest.rs:2852-2862`), and `notification` is a
different `subject_kind`, so today it cannot; the person would post anew.

Asks, one repo each:

* **CIRISNodeCore**: a compose that lists notifications at a scope and one that
  returns a notification with its responses and stance counts; say whether a
  local `observation` promotes into a `notification`.
* **CIRISServer**: list, post and respond routes at Server 1.0.
