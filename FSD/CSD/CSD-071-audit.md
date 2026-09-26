# CSD-071 — Audit (one card, five homes, and one list)

**CSD**: CSD-071 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can read what happened — what acted, on what, with what outcome —
and see that the record is a chain rather than a list somebody typed.** Serves
**Transparency**.

Record is the tab that answers *what happened here*, and it is the only one of
the seven placed identically in all five circles (`CirclesNav.kt:129`). The
comment above it reads "one card, five homes". This CSD's finding is that the
homes are five and the card is one **list** — the same list, unscoped, in all
five circles (§2.2) — and that on a node build that list is empty for a reason
the screen reports as the person's filtering mistake (§2.1).

## 2. Surface (what)

```yaml csd:surface
surface: audit
screen: Audit
```

`nav_map` derives `circle_agent -> tab_record` — the tab's only card in every
circle, so the shell shows it directly and the chain ends on the tab.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:audit_action
    use: display-only
    type: string
    example: "SPEAK"
    renders: "SPEAK — the action, colour-coded by severity"
    tag: "item_audit_entry_${entry.id.take(8)}"
  - ceg: x_private:audit_actor
    use: display-only
    type: string
    example: "wa-self-88b1"
    renders: "by wa-self-88b1"
    tag: "proposed:row_audit_actor"
  - ceg: x_private:audit_outcome
    use: display-only
    type: "enum[success,failure]"
    example: "success"
    renders: "the outcome chip — success / failure"
    tag: "proposed:chip_audit_outcome"
  - ceg: x_private:audit_timestamp
    use: display-only
    type: timestamp
    example: "2026-09-04T11:02:19Z"
    renders: "4 Sep, 11:02"
    tag: "proposed:row_audit_timestamp"
  - ceg: audit_chain:hash_continuity
    use: display-only
    type: string
    example: "e3b0c44298fc1c149afb…"
    renders: "Hash Chain — e3b0c44298fc1c149afb… (the first 24 characters)"
    tag: "proposed:row_audit_hash_chain"
  - ceg: transparency_log:inclusion
    use: display-only
    type: bool
    example: true
    renders: "Included in the log — verified"
    tag: "proposed:chip_audit_inclusion"
  - ceg: x_private:audit_total
    use: display-only
    type: int
    example: 1284
    renders: "Showing 100 of 1,284"
    tag: "proposed:txt_audit_stats"
```

**`audit_chain:hash_continuity` is RESERVED** — `substrate-self-report` at
CC 3.4.3, owned by `persist`/CIRISPersist, CC 3.1.3 — so `display-only` is
enforced and correct: the client draws a chain value and does not compute one.

**And that is exactly the problem with how it is drawn.** The card prints
`hash.take(24) + "..."` under the label "Hash Chain"
(`AuditScreen.kt:566`) and never asks whether the chain is *continuous*. The
family is named `hash_continuity`; continuity is a predicate, and the surface
shows the input to it. A truncated hex string tells a reader "this is
cryptographic" and nothing about whether it verifies — which is the shape of
claim the anti-Goodhart rules name: a signal that reads as assurance and carries
none.

The agent serves the answer (`POST /v1/audit/verify/{entry_id}`, §3) and the
client never calls it, which is why `transparency_log:inclusion` above is bound
to a `proposed:` tag on an element that does not exist.

```yaml csd:states
populated: {tag: "proposed:list_audit_entries", renders: "newest first, one card per entry, with the stats bar above"}
empty:     {tag: "proposed:txt_audit_empty", renders: "Nothing has happened here yet. (Not: try adjusting your filters — see §2.1.)"}
loading:   {tag: "proposed:spinner_audit", renders: "a progress affordance in place of the list; the stats bar is not drawn"}
error:     {tag: "proposed:txt_audit_error", renders: "Could not read the record. This is NOT a report that nothing happened."}
unsupported: {tag: "proposed:txt_audit_unsupported", renders: "This node does not keep an audit feed. Nothing has been hidden from you — there is nothing here to read."}
```

**A fifth state is declared, and it is the one this surface is actually in on
half the builds.** CSD-005 established the precedent: `contacts_unsupported` for
a node too old to serve `GET /v1/contacts` is a distinct fact from an empty list
and gets its own tag and its own sentence. Audit needs the same and needs it
more, because the capability is not merely missing on old nodes — it is missing
on **every** node (§3).

All four required states exist as pixels (`AuditScreen.kt:125` error,
`:154` loading, `:165` empty, `:187` populated) and **none of them has a tag**.
The error card and the empty block are visually distinct — `errorContainer`
versus centred grey text — so the CSD/3 §2.2 requirement is met in the rendering
and unassertable in the harness.

### 2.1 The empty state blames the reader for a build decision

`getAuditEntries` opens with `if (nodeSkip(method)) return AuditEntriesData(
entries = emptyList(), total = 0, …)` (`CIRISApiClient.kt:10621`). `nodeSkip`
returns true whenever `clientMode?.isNode == true` (`:249`). So on a node build
the call never leaves the device, no error is raised, and `auditState.entries`
is empty with `auditState.error == null`.

The screen then draws, in every one of the five circles:

> **No audit entries found**
> *Try adjusting your filters*

There are no filters to adjust. The feed does not exist on this build, and the
one sentence the person is given tells them the absence is their own doing. This
is the failure mode AGENTS.md pins for the wheels placeholder in a different
register — *"a wheel that installs and silently contains no client"* — and the
remedy is the same: the absent thing must raise or announce itself, never return
a plausible empty.

**Fix shape:** `nodeSkip` should not fabricate a successful empty result for a
user-facing feed. Either the call raises a typed `unsupported` and the screen
renders the fifth state above, or `Audit`'s placement carries `agentOnly = true`
so the node build has no Record card and the tab falls back to
`nav.empty.record` ("Nothing has happened in this circle yet.") — which is
itself only honest if nothing is being kept, and it is.

Of the two, **the typed `unsupported` is right and `agentOnly` is not**: a node
*does* record — the attestation corpus is the record — and what is missing is a
read route for it (§3), not the events.

### 2.2 Five circles, one unscoped list

`getAuditEntries` takes `severity`, `outcome`, `actor`, `eventType`, `limit`,
`offset`. It takes no cohort. `CIRISApp.kt:3618` composes `AuditScreen` with no
circle argument and `CirclesNav` places the card in `ALL`
(`CirclesNav.kt:129`).

So Just me › Record and Everyone › Record render the **same rows**. Four of the
five circles are making a claim about scope that the read does not support, and
a person reasonably reads "Record, inside Family" as *what happened in my
family*.

CSD-006 makes `cohort_scope` one of the five receipt facts precisely because
"who can see it" is the question a circle *is*. An audit entry as served carries
no cohort at all.

**Recommended:** either the read gains a cohort filter (§3) and each circle
shows its own, or the card moves to a single home — and the honest single home
is **My things › Everything I shared**, next to Data and Storage, which is where
"what this device did" already lives. Five identical lists is the worst of the
three options, because it is the only one that implies a filter nobody applied.

## 3. Contracts (who)

Agent routes read from the `~/CIRISAgent` working tree, whose last commit is
**2026-08-15** — six weeks stale at the time of writing, so the agent rows below
are "true as of that tree" rather than "true of a running agent".

| value | endpoint | owner | state |
|---|---|---|---|
| audit entries | `GET /v1/audit/entries` | CIRISAgent `routes/audit.py:781` | **live on the agent**; the client reaches it through the generated SDK (`queryAuditEntriesV1AuditEntriesGet`) |
| one entry | `GET /v1/audit/entries/{entry_id}` | `routes/audit.py:919` | live, unused by this screen |
| **verify an entry** | `POST /v1/audit/verify/{entry_id}` | `routes/audit.py:1006` | **live and never called.** This is the answer to `audit_chain:hash_continuity` and the card shows the hash without it |
| export | `POST /v1/audit/export` | `routes/audit.py:1030` | live, unused |
| **any audit feed on a node** | **missing — there is no route.** `git grep` over `origin/main -- 'src/*.rs'` finds no `/v1/*audit*` among the node's routes | CIRISServer | this is why §2.1 happens |
| a cohort filter | **missing** on both | CIRISAgent + CIRISServer | blocks §2.2 |

**The node gap is the substantive ask.** A node holds an attestation corpus with
`asserted_at`, `attesting_key_id`, dimension and `cohort_scope` on every row —
everything Record needs and more than the agent's feed carries, including the
circle scope §2.2 wants. **Ask: CIRISServer — serve the corpus as a record feed
(`GET /v1/attestations?cohort=…&since=…`), so the Record tab has something to
read on a node build and each circle can read its own.** Until then Record is
agent-only in fact while being placed as though it were not.

## 4. Flow (how)

Real tags only. The screen's fourteen tags are the back button, refresh, load
more, the filter toggle, ten filter chips and the per-entry row.

Land on `Audit` (Just me › Record, derived).

```yaml
expect:
  visible: [btn_audit_refresh, btn_audit_toggle_filters]
```

Open the filters:

```yaml
expect:
  visible: [btn_filter_severity_all, btn_filter_severity_info,
            btn_filter_severity_warning, btn_filter_severity_error,
            btn_filter_outcome_all, btn_filter_outcome_success,
            btn_filter_outcome_failure, btn_filter_clear]
```

Against an agent with history:

```yaml
expect:
  count: {of: "item_audit_entry_*", min: 1}
```

Against a node build — **this is the step that should fail and cannot.**

```yaml
expect:
  state: unsupported
  visible: ["proposed:txt_audit_unsupported"]
```

Today the same run produces zero `item_audit_entry_*` elements and a screen the
harness reads as healthy, because `count: {eq: 0}` is legal only against an
explicit `state: empty` (CSD/3 §3) and nothing here declares one. The empty-set
trap, on the surface whose whole job is the record.

## 5. QA plan

**Platforms.** All five. The hop is derived and identical on each. The
**corners** are what matter here rather than the platforms: `LOCAL_NODE` and
`REMOTE_NODE` are where §2.1 bites, and `testing/cases.py` has no audit case in
either.

**Acceptance — functional**
1. "Nothing happened" / "could not read" / "this build keeps no feed" are three
   renderings.
2. The hash chain is shown with its verification, or not shown.
3. Record in a circle shows that circle's record, or the card has one home.
4. Filter chips change the list and `btn_filter_clear` restores it.

**Not tested here.**
* **The chain itself.** Continuity is a persist property reported by the
  substrate (`audit_chain:hash_continuity`, CC 3.4.3 substrate-self-report). A
  client assertion over a truncated hex prefix would be theatre.
* **Acceptance 1, 2 and 3**, entirely — each is blocked on a different thing
  (§2 tags, an uncalled route, and a missing filter).
* **Load-more pagination.** `hasMore` drives `btn_audit_load_more` and the
  offset paging behaviour differs between the agent's feed and anything a node
  might serve; nothing pins it.
