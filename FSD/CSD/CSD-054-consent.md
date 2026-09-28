# CSD-054 — Consent (the subject's half, offered on a host that does not serve it)

**CSD**: CSD-054 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, Rules tab
**Flow**: unwritten

```yaml csd:stage
stage: sketched
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

**One defect defines this card, and it is the flattering kind.** Every route the
screen calls is served by the **agent** and by nothing else
(`CIRISAgent routes/consent.py:162, 237, 309, 353, 379, 419`); CIRISServer has
zero axum registrations for `/v1/consent/*` — its own contract note records the
node's equivalent as a different, single endpoint, `POST /v1/auth/consent`
(`FSD/QA_AGAINST_RUST.md:38`). The card is placed in all five circles with no
`agentOnly` flag (`CirclesNav.kt`, `Placement(NavSurface.Consent, Tab.RULES,
ALL)`), and `getConsentStatus` has no `nodeSkip` guard
(`CIRISApiClient.kt:9110`). So on a node build every call 404s — and
`ConsentViewModel.kt:121-127` catches a 404 and logs *"No consent record found
(404), normal for new users"*, returning `null`.

**A node that cannot answer and a person who has never granted anything render
the same screen.** That is CSD/3 §2.2's prohibition stated exactly, and CSD-003's
principle — a failed read shown as an empty one tells the user a different and
more flattering thing than the truth — with "you have no consent on file" as the
flattery. CSD-005 already has the honest shape for this: a node-too-old sentence
naming the version, in the error tone.

## 2. Surface (what)

```yaml csd:surface
surface: consent
screen: Consent
```

`nav_map` derives `circle_agent -> tab_rules -> nav_epistemic_consent`, and the
same under the other four circles.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "consent:{kind}"
    bind: {kind: state}
    use: display-only
    type: "enum[granted,revoked,expired]"
    example: "granted"
    renders: "Your consent — granted, with when it was granted and when it expires"
    tag: "proposed:card_consent_status"
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
    type: unconfirmed
    example: "unconfirmed"
    renders: "What your consent made possible — shown only for partnered and anonymous"
    tag: "proposed:card_consent_impact"
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

**Four tags exist on a 699-line screen**: `btn_consent_back`,
`btn_consent_refresh`, `btn_stream_cancel`, `btn_stream_confirm`. Every value the
screen renders is untagged, which is why every `shows:` row above is `proposed:`
and why this CSD cannot advance past `sketched` on client work alone.

```yaml csd:states
populated: {tag: "proposed:card_consent_status", renders: "the current stream, its dates, and the stream rows"}
empty:     {tag: "proposed:text_consent_none", renders: "You have not set a consent preference yet. — a true empty, reached when the agent answers and hasConsent is false"}
loading:   {tag: "proposed:consent_loading", renders: "the frame with a progress affordance and NO sentence"}
error:     {tag: "proposed:consent_unsupported", renders: "This node doesn't hold your consent record. Consent lives with an agent, and this is a node running {version}. — the danger tone, the version named, NEVER the empty sentence"}
```

**The `error` row is the whole point of this file.** It does not exist yet, and
the sentence is written here so the PR that adds it has a text to add rather than
a decision to make. It follows `contacts_unsupported` (CSD-005 §2) verbatim in
shape: name the host, name the version, say which component owns the thing.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| consent status | `GET /v1/consent/status` | **CIRISAgent** (`routes/consent.py:162`) | live on the agent; **404 on a node**, and swallowed as empty |
| available streams | `GET /v1/consent/streams` | CIRISAgent (`:379`) | same |
| change stream | `POST /v1/consent/grant` | CIRISAgent (`:237`) | same |
| impact report | `GET /v1/consent/impact` | CIRISAgent (`:309`) | same; already `try`-guarded to null |
| audit trail | `GET /v1/consent/audit` | CIRISAgent (`:353`) | same; already `try`-guarded to empty |
| partnership status | `GET /v1/consent/partnership/status` | CIRISAgent (`:419`) | same; already `try`-guarded to null |
| the node's own consent surface | `POST /v1/auth/consent` — CEG-native, hybrid-signed, one endpoint with `granted: bool` for grant and withdraw | CIRISServer | live, **and not called by this screen**. Whether the node's single endpoint should back this card on a node build is the open design question; it is a different contract, not a drop-in. |

**`openapi.json` in CIRISServer lists `/v1/consent/*` and the node does not serve
them.** Anyone checking this card against the node's spec file would conclude the
routes are live. They are the agent's contract, cross-referenced there for QA.
That is a second source for a question one file already answers, and it is
currently answering it wrong.

## 4. Flow (how)

Unwritten. Four real tags, all controls.

## 5. QA plan

**Platforms.** All five, on an agent build. **On a node build the card must be
driven too**, and that run is the one that matters: it is the only way the
`consent_unsupported` state above gets a red path, and this repo does not believe
a check whose red path has never run.

**Not tested here.**
* Anything the screen displays — no value carries a tag.
* Whether `hasConsent: false` from the agent and a 404 from a node are told apart.
  They are not, today; that is the defect, and asserting it green would pin it.

**The recommendation, so it is on the record.** Either mark the placement
`agentOnly = true` — which is what `CirclesNav`'s own flag is for and what
`Interact` already does in Chats — or add the `consent_unsupported` state and
keep it everywhere. Marking it `agentOnly` is the smaller change and the worse
one: a person's consent record is theirs whether or not a brain is attached, and
hiding the card on a node says the opposite. Add the state.

**And it should be one card, not five.** A consent grant is about the person, not
about a circle: `cohort_scope` and `subject_key_ids` are *independent* envelope
concerns — CC 2.3.3 keeps *"visibility, revocability, and delivery as three
independent concerns so they can be reasoned about — and enforced — separately"*.
Showing the same revocability card once per visibility scope implies they vary
together. **My things › Everything I shared** already holds `Data` and `Storage`
and is where this belongs.
