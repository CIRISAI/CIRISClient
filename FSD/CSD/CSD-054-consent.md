# CSD-054 — Consent (the subject's half, offered on a host that does not serve it)

**CSD**: CSD-054 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, Rules tab
**Flow**: `testing/flows/drafts/csd-054-partnership-queue.yaml` (§7); the not-on-this-node
state and the stream-change confirm are written in §4 and not yet staged as a flow

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can see what they have agreed may happen to records that name them,
change it, and read the history of every change — and the screen says which of
those it could not ask about.** Serves **Autonomy**. This is the half CC 2.3
exists for: *"Consent is incomplete if only the producer of data has authority
over it … `subject_key_ids` adds the missing half — subject authority."* CC
2.3.5 gives the shape: *"A consent record is a signed declaration by a subject
about the substrate's continued processing of content where that subject
appears."*

**One defect used to define this card, and it was the flattering kind.** Every
route the screen calls is served by the **agent** and by nothing else
(`CIRISAgent routes/consent.py:162, 237, 309, 353, 379, 419`); CIRISServer has
zero axum registrations for `/v1/consent/*` — its own contract note records the
node's equivalent as a different, single endpoint, `POST /v1/auth/consent`
(`FSD/QA_AGAINST_RUST.md:38`). The card is placed in all five circles with no
`agentOnly` flag (`CirclesNav.kt`, `Placement(NavSurface.Consent, Tab.RULES,
ALL)`). So on a node build every call 404s — and `ConsentViewModel` used to
catch the 404 and log *"No consent record found (404), normal for new users"*,
returning `null`: **a node that cannot answer and a person who has never
granted anything rendered the same screen.** That was CSD/3 §2.2's prohibition
stated exactly, with "you have no consent on file" as the flattery.

**Closed.** The 404 is no longer swallowed: the agent answers "no record" with
`200 + has_consent=false`, so a 404 means *this host has no consent route*, and
`ConsentViewModel.loadConsentData` now fails the load with a typed
`ReadFailure` that the screen draws ALONE (`ReadFailureBlock`, tag
`consent_not_on_this_node` for the absent route, `consent_error` for a failed
read) — nothing below it may render, because "no consent record" is a claim
about the person and nobody asked. The true empty (`consent_none`) is reached
only when the agent answers and `hasConsent` is false.

**A second defect of the same class, closed 2026-09-28.** The model is
app-scoped and outlives the session, and the screen's spinner shows only while
the model holds NO record — so after a logout the next signer-in read the
previous owner's stream, expiry, audit trail and partnership queue for the whole
reload. `ConsentViewModel.resetSession()` is now called from CIRISApp's one
token effect, keyed and conditioned on `consentSessionAuthenticated(token,
isHAAddonMode)` exactly as Manage Consent's reset is (CSD-053 §2.1, PR #116):
every way out of a session resets it, and a Home Assistant add-on session — the
token null by design — does not. `ConsentSessionTest` pins both.

## 2. Surface (what)

```yaml csd:surface
surface: consent
screen: Consent
```

`nav_map` derives `circle_agent -> tab_rules -> nav_epistemic_consent`, and the
same under the other four circles.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: "consent:{kind}"
    bind: {kind: state}
    use: display-only
    type: "enum[granted,revoked,expired]"
    example: "granted"
    renders: "Your consent — granted, with when it was granted and when it expires"
    tag: card_consent_status
  - ceg: "consent:{kind}"
    bind: {kind: stream}
    use: display-only
    type: "enum[temporary,partnered,anonymous]"
    example: "temporary"
    renders: "one selectable row per stream, with its duration, what it forgets, and what it enables"
    tag: "proposed:row_consent_stream"
  - ceg: "consent:{kind}"
    bind: {kind: partnership_grant}
    use: display-only
    type: "enum[pending,accepted,none]"
    example: "pending"
    renders: "Partnership requested — waiting on the other side"
    tag: "proposed:text_consent_partnership"
  - ceg: x_private:consent_audit
    use: display-only
    type: "list[string]"
    example: ["2026-09-01 granted temporary"]
    renders: "What changed and when — the last ten entries"
    tag: "proposed:list_consent_audit"
  - ceg: x_private:consent_impact
    use: display-only
    type: "list[int]"
    example: [412, 7, 3]
    renders: "What your consent made possible — interactions, patterns contributed, users helped, and a score; shown only for partnered and anonymous (`GET /v1/consent/impact`, live on the agent)"
    tag: "proposed:card_consent_impact"
  - ceg: "consent:{kind}"
    bind: {kind: stream}
    use: display-only
    type: "list[string]"
    example: ["You — your consent record with this agent (currently PARTNERED).", "Your partnership ends…", "Nobody…"]
    renders: "the three-fact confirm before a stream change — who (you, and the stream you are on), what changes (leaving PARTNERED: identity severed at once, what it learned fades over 90 days, anonymised safety patterns may be kept — NOT an undo; to TEMPORARY: 14-day auto-forget; to ANONYMOUS: identity removed), who signs (nobody: the agent files an unsigned record under your sign-in id, CIRISServer#423). sheet_stream, stream_fact_1..3, btn_stream_confirm, btn_stream_cancel"
    tag: sheet_stream
  # §7 — the partnership queue: requests waiting on this agent's answer.
  - ceg: "consent:{kind}"
    bind: {kind: partnership_grant}
    use: display-only
    type: string
    example: "discord:4471"
    renders: "one row per request waiting on the agent: who asked (`user_id`), what it would cover in the meta line, their reason underneath"
    tag: partnership_request_0
  - ceg: "consent:{kind}"
    bind: {kind: partnership_accept}
    use: display-only
    type: string
    example: "your agent"
    renders: "Waiting on: your agent — on every row, because CC gives the accepting half to the agent"
    tag: partnership_request_decider_0
  - ceg: x_private:partnership_age
    use: display-only
    type: "enum[normal,warning,critical]"
    example: "warning"
    renders: "Waiting 9 days — a chip, mute under 7 days, brand 7-14, danger over 14 (`aging_status`)"
    tag: partnership_request_age_0
  - ceg: x_private:partnership_history
    use: display-only
    type: "list[string]"
    example: ["Deferred by agent · 2026-09-20T09:00:00Z"]
    renders: "tapping a row opens that person's earlier answers: outcome, who decided, when, the reason given"
    tag: partnership_history_0
  - ceg: x_private:partnership_metrics
    use: display-only
    type: int
    example: 2
    renders: "2 waiting · 12 accepted · 4 declined · 2 deferred"
    tag: partnership_metrics
  - ceg: x_private:partnership_options
    use: display-only
    type: "list[string]"
    example: ["interaction", "preference", "improvement"]
    renders: "What partnering means, in the agent's words: what it always covers, what it may add, how it is ended"
    tag: partnership_options
  - ceg: x_private:partnership_signed_by
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Signed by: nobody — the agent keeps partnerships as unsigned records filed under a sign-in id (CIRISServer#423)"
    tag: partnership_unsigned
    blocked_by: CIRISServer#423
```

**`consent:{kind}` is reserved (CC 3.1.5) and every row is `display-only`,
including the streams row that the user changes.** `btn_stream_confirm` does not
mint a grant; it calls `POST /v1/consent/grant` and the agent authors the row.
The checker enforces this — `use: emit` on a reserved family is a load error —
and that is the constitutional fact, not a client convention: CC forecloses
third-party authorship of a consent grant so one cannot be produced on your
behalf.

**Three of the four `shows:` rows bind real CC 3.3.1 leaves; two do not exist as
families.** `consent:state:{granted|revoked|expired}`, `consent:stream:{kind}`
and `consent:partnership_grant` are in the catalogue. The audit trail and the
impact report are derived views, not wire dimensions, so they are `x_private:`.

**The tags that exist.** Controls: `btn_consent_back`, `btn_consent_refresh`,
`btn_stream_{id}` (one per stream card), `btn_stream_confirm`,
`btn_stream_cancel`. Values and states: `card_consent_status`, `consent_none`,
`consent_loading`, `consent_not_on_this_node` / `consent_error`, the confirm's
`sheet_stream` + `stream_fact_1..3`, and the §7 partnership tags. Still
`proposed:`: the stream rows' values, the partnership-pending line, the audit
list and the impact card — real pixels, untagged. The stage moved to `building`
on the states and the contracts (every §3 row is live or names its blocker),
not on those four; `testable` waits on them and on a release carrying the tags.

```yaml csd:states
populated: {tag: card_consent_status, renders: "the current stream, its dates, and the stream rows"}
empty:     {tag: consent_none, renders: "Consent Record Not Yet Created — a true empty, reached ONLY when the agent answers and hasConsent is false (mobile.consent_not_created)"}
loading:   {tag: consent_loading, renders: "the frame with a progress affordance and NO sentence; shown only while the model holds no record and no failure"}
error:     {tag: consent_not_on_this_node, renders: "This node doesn't hold consent records. Consent is kept by an agent, and this node runs without one. — drawn ALONE, nothing below it; a failed read that is not an absent route is consent_error (ReadFailureBlock, tagPrefix consent). Neither is ever the empty sentence"}
```

**The `error` row was the whole point of this file, and it exists now.** It
follows `contacts_unsupported` (CSD-005 §2) in shape — name the host, say which
component owns the thing — without naming a version, because the route is not
"too old" on a node: no node version serves it. `ReadFailure.of` classifies a
served 404 as `NotOnThisNode` and everything else as `Failed`, so the two tags
cannot collapse into one rendering.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| consent status | `GET /v1/consent/status` | **CIRISAgent** (`routes/consent.py:162`) | live on the agent; **404 on a node** — no longer swallowed: `ReadFailure.NotOnThisNode`, drawn alone as `consent_not_on_this_node` |
| available streams | `GET /v1/consent/streams` | CIRISAgent (`:379`) | same |
| change stream | `POST /v1/consent/grant` | CIRISAgent (`:237`) | same |
| impact report | `GET /v1/consent/impact` | CIRISAgent (`:309`) | same; already `try`-guarded to null |
| audit trail | `GET /v1/consent/audit` | CIRISAgent (`:353`) | same; already `try`-guarded to empty |
| partnership status | `GET /v1/consent/partnership/status` | CIRISAgent (`:419`) | same; already `try`-guarded to null |
| the node's own consent surface | `POST /v1/auth/consent` — CEG-native, hybrid-signed, one endpoint with `granted: bool` for grant and withdraw | CIRISServer | live, **and not called by this screen**. Whether the node's single endpoint should back this card on a node build is the open design question; it is a different contract, not a drop-in. |
| what partnering means (§7) | `GET /v1/partnership/options` | CIRISAgent (`routes/partnership.py:380`) | live; any signed-in user — `partnershipOptions`, called from `ConsentViewModel.loadPartnershipQueue` |
| requests waiting on the agent (§7) | `GET /v1/partnership/pending` | CIRISAgent (`routes/partnership.py:434`) | live; **admin only** (`:447`) — a 403 renders `partnership_admin_only`, never the empty queue |
| partnership totals (§7) | `GET /v1/partnership/metrics` | CIRISAgent (`routes/partnership.py:471`) | live; admin only — read only after `/pending` answered |
| one person's earlier answers (§7) | `GET /v1/partnership/history/{user_id}` | CIRISAgent (`routes/partnership.py:498`) | live; admin only — read when a row is opened |
| answer a request (§7) | `POST /v1/partnership/decide` | CIRISAgent (`routes/partnership.py:524`) | live, **deliberately not called** — the accepting half is the agent's (CC 3.3.1 `consent:partnership_accept`, producer-emitted), and the route admits the requester and any admin instead (`:583`; CIRISAgent#1221) |

**`openapi.json` in CIRISServer lists `/v1/consent/*` and the node does not serve
them.** Anyone checking this card against the node's spec file would conclude the
routes are live. They are the agent's contract, cross-referenced there for QA.
That is a second source for a question one file already answers, and it is
currently answering it wrong.

## 4. Flow (how)

**On a node without an agent** — the run that matters, because it is the only
red path this card has:

```yaml
expect:
  state: error
  visible: [consent_not_on_this_node]
  absent: [consent_none, card_consent_status, btn_stream_temporary]
```

**On an agent build**, signed in. The record, or the true empty:

```yaml
expect:
  visible: [card_consent_status]
  absent: [consent_not_on_this_node, consent_error]
```

**Changing the stream is confirmed with three facts, and leaving PARTNERED
says it is not an undo.** Click `btn_stream_temporary` (or `btn_stream_anonymous`):

```yaml
expect:
  visible: [sheet_stream, stream_fact_1, stream_fact_2, stream_fact_3, btn_stream_confirm, btn_stream_cancel]
  text: {stream_fact_3: "Nobody. The agent files the change as an unsigned record under your sign-in id, not under your key (CIRISServer#423)."}
```

When the current stream is `partnered`, `stream_fact_2` reads the decay: *"Your
partnership ends and your stream becomes {stream}. This is not an undo: the
agent severs your identity at once and lets what it learned from you fade over
90 days; anonymised safety patterns may be kept."* (`routes/consent.py:293-297`
— the same fact `partnership_options_revocation` carries in §7). Click
`btn_stream_cancel`: nothing is sent. `btn_stream_confirm` sends
`POST /v1/consent/grant` and the card re-reads.

`btn_stream_partnered` does not open the sheet: it files a request
(`requestPartnership`), which is reversible and waits on the agent (§7).

## 5. QA plan

**Platforms.** All five, on an agent build. **On a node build the card must be
driven too**, and that run is the one that matters: it is the only way the
`consent_unsupported` state above gets a red path, and this repo does not believe
a check whose red path has never run.

**Not tested here.**
* Anything the screen displays — no value carries a tag.
* Whether `hasConsent: false` from the agent and a 404 from a node are told apart.
  They are not, today; that is the defect, and asserting it green would pin it.

**The decision, so it is on the record.** The two options were to mark the
placement `agentOnly = true` — what `CirclesNav`'s own flag is for and what
`Interact` does in Chats — or to add the error state and keep the card
everywhere. Marking it `agentOnly` was the smaller change and the worse one: a
person's consent record is theirs whether or not a brain is attached, and hiding
the card on a node says the opposite. The state was added
(`consent_not_on_this_node`); the placement is unchanged.

**And it should be one card, not five.** A consent grant is about the person, not
about a circle: `cohort_scope` and `subject_key_ids` are *independent* envelope
concerns — CC 2.3.3 keeps *"visibility, revocability, and delivery as three
independent concerns so they can be reasoned about — and enforced — separately"*.
Showing the same revocability card once per visibility scope implies they vary
together. **My things › Everything I shared** already holds `Data` and `Storage`
and is where this belongs.

## 7. The partnership queue — requests waiting on your agent

**Added with the partnership-decisions work (numbered CSD-107 at assignment,
folded in here under the no-duplicate-cards rule).** The route-coverage report
listed `/v1/partnership/*` as "a consent decision with no UI". It is not a
second card: the Consent card already shows the person's own partnership
status and files their request (`ConsentViewModel.requestPartnership` →
`POST /v1/consent/grant` with `stream: partnered`). What was missing is the
other side of the same object — the requests waiting on the agent — and it
lives on this card, under the stream list, in `ConsentPartnershipSection.kt`.

Read from CIRISAgent **main** at `2937166` (2026-09-26).

**What is being decided.** Whether a person's consent moves to the
`partnered` stream: learning from their interactions with no end date instead
of the 14-day default. A request is filed when someone asks for that stream
(`routes/consent.py:237` → `service.py:288-290` →
`PartnershipManager.create_partnership_request`, `partnership.py:108`), as a
task for the agent (`partnership_utils.py:58`). Accept writes
`consent/{user_id}` with `stream: partnered` and `expires_at: None`
(`routes/partnership.py:131-231`); reject leaves the stream alone; defer keeps
the request pending (`:233-374`).

**Whose answer it is — and why this card gives none.** CC 3.3.1 gives the
pair two halves: `consent:partnership_grant`, emitted by the subject, and
`consent:partnership_accept`, emitted by the producer — the agent
(`part_3_the_namespace.md:693-694`); CC 3.4.7 makes the emitter normative per
leaf (`part_3:1611`), and CC 4.4.3.5.3 counts a pair only when the producer
half is signed by a key other than the subject's (`part_4:963-965`). Every
request on this queue was made by a person, so every answer is the agent's,
given in its own reasoning (`partnership_utils.py:67-73`).
`POST /v1/partnership/decide` would let two other parties answer
(`routes/partnership.py:583`): the requester (one party signing both halves)
and any administrator (answering in the agent's name, which the route's own
header forbids at `:8`, and CC 1.13.2 forbids of any principal). **So the
card calls `/decide` for nobody, and each row says "Waiting on: your agent".**

**Placement.** Unchanged — it is this card's section, in Rules. A partnership
is a `consent:*` object on the revocability axis (CC 2.3.3), not the agent's
machinery; and Just me › Decisions is honestly empty ("There is nobody here to
decide with"), which on this section is literally so.

**Reversibility.** The section decides nothing, so it has no ConfirmSheet. The
decision it shows is **revocable, not undoable**: either side may end a
partnership (`/options` `revocation`, `routes/partnership.py:426`), but
revoking starts decay — identity severed at once, a 90-day pattern decay,
anonymised safety patterns possibly kept (`routes/consent.py:293-297`).

**Contracts.**

| value | endpoint | owner | state |
|---|---|---|---|
| what partnering means | `GET /v1/partnership/options` | CIRISAgent (`routes/partnership.py:380`) | live; any signed-in user. `data`: `required_categories[]`, `optional_categories[]`, `approval_process`, `benefits[]`, `responsibilities[]`, `revocation` (`:403-427`) |
| requests waiting | `GET /v1/partnership/pending` | CIRISAgent (`:434`) | live; **admin only** (`:447`). `data`: `requests[]` of `PartnershipRequest` (`schemas/consent/core.py:240-254`), `total`, `by_status` |
| totals | `GET /v1/partnership/metrics` | CIRISAgent (`:471`) | live; admin only. `PartnershipMetrics` (`core.py:271-286`) |
| one person's history | `GET /v1/partnership/history/{user_id}` | CIRISAgent (`:498`) | live; admin only. `PartnershipHistory` (`core.py:289-302`) |
| answer a request | `POST /v1/partnership/decide` | CIRISAgent (`:524`) | live; **deliberately not called** |

**States, and the three kinds of silence.** `partnership_pending_list`
(populated) · `partnership_pending_empty` "Nobody is waiting on your agent for
an answer." (empty) · `partnership_loading` (loading) · `partnership_error` /
`partnership_not_on_this_node` (error, via `ReadFailureBlock`) — and
`partnership_admin_only`, "Only this agent's administrators can see who is
waiting on it.", for the 403 the three admin routes give everyone else. A 403
is a fact about who is looking; drawing it as the empty queue would tell a
non-administrator "nobody is waiting" about a list they were never shown.
`PartnershipQueueTest.aForbiddenQueueIsNeverTheEmptyQueue` pins that and was
shown red against a planted `403 → empty` defect.

**Standing tags.** `partnership_section`, `partnership_options`,
`partnership_options_revocation`, `partnership_who_decides`,
`partnership_unsigned`, `partnership_request_<i>` (tap opens the history),
`partnership_request_age_<i>`, `partnership_request_decider_<i>`,
`partnership_history_<i>`, `partnership_metrics`. No accept/decline/defer tag
exists; the flow asserts their absence.

**Flow.** `testing/flows/drafts/csd-054-partnership-queue.yaml` (floor
`unreleased`).

**The delta.**
1. **`/decide` admits the wrong parties** (`routes/partnership.py:583`). Ask
   (CIRISAgent, draft in the PR): refuse the subject on a request the subject
   made, and refuse an admin on a request addressed to the agent.
2. **A request does not say who made it** (`core.py:240-254`). Until it does,
   no row can carry a person's button. Ask: `initiated_by: subject|agent`.
3. **The agent's own ask skips the ask.** `upgrade_relationship` writes the
   `partnered` stream directly (`service.py:1262`) and replies
   `"PENDING_APPROVAL"` (`:1271`) — the one path where a person would have a
   half of their own to give writes theirs for them, and says it did not.
4. **Nothing is signed** (`routes/partnership.py:187-205` writes a plain
   `GraphNode`) — CIRISServer#423 names the setup-time case; the decide path
   needs the same fix.
5. **The queue forgets.** `_pending_partnerships` and `_partnership_history`
   are in-memory dicts (`partnership.py:51-52`); a restart empties the queue
   and orphans its tasks (`finalize_partnership_approval` then finds nothing,
   `:208-210`). The section says so under the list.
6. **Categories are not CC scopes.** `interaction|preference|improvement|
   research|sharing` (`core.py:34-41`) has no mapping to CC 3.3.1's
   `retain|share|analyze|train|publish`, so they are `x_private:` here.

**What this does not guarantee.** English copy is written here; every other
language is machine-translated and MQM-reviewed by a judge of a different
model family, with no native-reviewer pass.
