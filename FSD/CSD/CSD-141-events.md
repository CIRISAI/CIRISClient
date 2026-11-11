# CSD-141 — Events (a listing, who is going, and whether it is still on)

**CSD**: CSD-141 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the owner's request (2026-11-10) to fill the card set with the NodeCore and Registry screens
**Reads with**: CSD-135 (Feeds, where an event row opens this card), CSD-102 (Communities, whose members an event is usually for)
**Sources read**: CIRISNodeCore `feat-scale-model-v0.7-fountain` @ `93b94e0` (`src/ingest.rs:870-990`, `SCHEMA.md:1168-1200`); CIRISServer `origin/main` @ `7af5d4eb` (2026-11-09)
**Flow**: unwritten. No route and no screen

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person can see an event's title, time and place, whether it is open,
cancelled, moved or over, how many people say they are going, and can say they
are going themselves.**

Falsifiable:

* **Cancelled and moved are never drawn as "on".** NodeCore carries state on
  existing rows: cancellation is `withdraws` against the listing, a reschedule is
  `supersedes` with `differs_in: ["start_time", "venue"]`, and the lifecycle is
  a separate `event:lifecycle:{state}` score (`open`, `cancelled`, `completed`,
  `superseded`), with admission "implicitly `open`" (`SCHEMA.md:1174-1178`). The
  card draws the state from those rows. A listing with no lifecycle row is shown
  as "Open (no update since it was listed)", which is what NodeCore means, and
  not as a confirmed "Open".
* **A moved event shows the new time, and says it moved.** The superseding
  listing's time and place are drawn, with "Moved from 14 Nov 18:00" beside them.
* **The going count says whose count it is.** `event:rsvp_count` is a
  "publisher-asserted RSVP tally" (registry rc6, CC 3.3.8). Individual RSVPs are
  `scores` rows from attendees (`SCHEMA.md:1174`). The card shows "Organiser says
  42 going" and, separately, "8 people you know are going" from the rows this
  node holds, and never adds the two.
* Saying "I'm going" attests a row, and an attest widens, so it goes through a
  ConfirmSheet naming the event and the circle it is sent to (law L6). Taking it
  back narrows, and takes one step with no sheet.

## 2. Surface (what)

No `csd:surface` block yet (CSD-108's precedent). A detail screen opened from a
feed row; a calendar view is out of scope until a list read exists.

```yaml
# csd:surface once the screen is declared in CIRISApp.kt
surface: null
flow_only: true
screen: Event
entry: "an event row in CSD-135's feeds"
```

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:event_title
    use: display-only
    type: string
    example: "Seed swap at the library"
    renders: "the heading (`title`)"
    tag: "proposed:txt_events_title"
  - ceg: x_private:event_time
    use: display-only
    type: string
    example: "Sat 14 Nov 2026, 10:00 to 13:00"
    renders: "`starts_at` and `ends_at` in the reader's time zone; no `ends_at`: 'from 10:00, no end given'"
    tag: "proposed:txt_events_time"
  - ceg: x_private:event_venue
    use: display-only
    type: "enum[physical,virtual,hybrid]"
    example: "physical"
    renders: "Physical: name and address. Virtual: the URL as text, opened only by a tap that says it leaves the app. Hybrid: both (ingest.rs:898-915). A map is not drawn; `geo` is shown as coordinates only if present"
    tag: "proposed:txt_events_venue"
  - ceg: x_private:event_ticket_policy
    use: display-only
    type: "enum[open,approval_required,invitation_only,paid]"
    example: "approval_required"
    renders: "'Anyone can come' · 'The organiser approves each person' · 'By invitation' · 'Paid'. Paid shows no price: the listing carries none"
    tag: "proposed:chip_events_policy"
  - ceg: x_private:event_capacity
    use: display-only
    type: int
    example: 60
    renders: "'Room for 60'; absent: nothing drawn (None means unlimited or unstated, SCHEMA.md:1191)"
    tag: "proposed:stat_events_capacity"
  - ceg: "event:lifecycle:{state}"
    bind: {state: cancelled}
    use: display-only
    type: "enum[open,cancelled,completed,superseded]"
    example: "cancelled"
    renders: "a state banner: 'Cancelled by the organiser on 12 Nov' (danger tone) · 'Moved' with the new time · 'Over' · 'Open'. A state this app does not know is shown verbatim, never as Open"
    tag: "proposed:txt_events_state"
  - ceg: event:rsvp_count
    use: display-only
    type: int
    example: 42
    renders: "'Organiser says 42 going', with the time it was stated"
    tag: "proposed:stat_events_rsvp_count"
  - ceg: x_private:event_rsvp_rows
    use: display-only
    type: unconfirmed
    example: "8"
    renders: "'8 people you know are going', from attendee `scores` rows this node holds, with their names on tap. Kept apart from the organiser's count"
    tag: "proposed:stat_events_rsvp_known"
  - ceg: x_private:event_rsvp
    use: emit
    type: unconfirmed
    example: "going"
    renders: "'I'm going' behind a ConfirmSheet: the event, the circle the row goes to, and that the organiser and that circle will see it. Withdrawing it is the same sheet"
    tag: "proposed:btn_events_rsvp"
  - ceg: event:attendance
    use: display-only
    type: unconfirmed
    example: "Attended (organiser)"
    renders: "after the event, 'The organiser records that you attended', only when an `event:attendance` row names the reader"
    tag: "proposed:txt_events_attended"
```

`event:lifecycle:{state}`, `event:rsvp_count` and `event:attendance` are
registered (rc6, owned by CIRISNodeCore). The listing's fields are payload
fields, `x_private:` for the reason CSD-135 §2 states. **The RSVP row has no
registered family:** NodeCore says only "`scores` from attendee key_ids on
`entity_key_id`" (`SCHEMA.md:1174`) and names no dimension, so a client cannot
build one. That is NodeCore's to name and **CIRISConstitution**'s to register.

```yaml csd:states
populated: {tag: "proposed:state_events_event", renders: "title, state banner, time, venue, policy, capacity, then the two going counts"}
empty:     {tag: "proposed:state_events_rsvp__empty", renders: "'No one you know has said they are going.' Only when the RSVP read answered with none; an event itself is never 'empty'"}
loading:   {tag: "proposed:state_events_event__loading", renders: "the title from the feed row; no state banner until the lifecycle read answers, because a missing banner would read as Open"}
error:     {tag: "proposed:state_events_event__error", renders: "'Could not read this event', with the node's words. A failed lifecycle read is `state_events_lifecycle__error`: 'Could not check whether this event is still on', in place of the banner, never Open"}
```

## 3. Contracts (who)

**There is no route.** CIRISServer does not link NodeCore (`Cargo.toml:128`).

| value | endpoint | owner | state |
|---|---|---|---|
| the listing | no route; NodeCore library function `build_event_listing_payload` (`src/ingest.rs:1834`), `ingest_event_listing` (`:3427`); Python `build_event_listing_payload` (`python.rs:745`) | CIRISNodeCore; CIRISServer folds NodeCore at Server 1.0 | **missing** |
| lifecycle state (with cancel and move folded in) | no route and **no function**: no compose reads `event:lifecycle:*`, `withdraws` and `supersedes` together for a listing | CIRISNodeCore | **missing** |
| organiser's count; attendance | no route; `scores` rows on `event:rsvp_count`, `event:attendance` | CIRISNodeCore | **missing** |
| RSVP (send, withdraw, list) | no route; an attendee `scores` row with no named dimension | CIRISNodeCore | **missing** |
| ticket transfer | spec only: `delegates_to` (`SCHEMA.md:1177`) | CIRISNodeCore | **missing**; out of scope for this card |

## 4. Flow (how)

Every tag is `proposed:`. Open a cancelled event:

```yaml
expect:
  state: populated
  one_of: {"proposed:txt_events_state": ["Cancelled"]}
  absent: ["proposed:btn_events_rsvp"]
```

## 5. QA plan

Not drivable. When routes exist: one listing cancelled by `withdraws` with no
lifecycle row, one moved by `supersedes`; assert both banners, and assert that a
failed lifecycle read never draws "Open".

## 6. Status and asks

Sketched.

Asks, one repo each:

* **CIRISNodeCore**: name the RSVP dimension; a compose that returns a listing's
  current state from `event:lifecycle:*`, `withdraws` and `supersedes`, with the
  superseding listing's fields; a list read of events by circle and time.
* **CIRISServer**: the event, its state and its RSVPs as routes at Server 1.0,
  and an RSVP write.
* **CIRISConstitution**: register the RSVP dimension once NodeCore names it.
