# Writing, updating and reviewing a card to CSD/5

This is the how-to. The standard is [`CSD5.md`](CSD5.md) and it wins wherever the
two differ. The worked example is CSD-070 Commons
([`CSD/CSD-070-commons.md`](CSD/CSD-070-commons.md) §2.3), the only card adopted
so far. Read its three blocks once before writing your own.

CSD/5 adds one thing to a card: which control is drawn, in which state, with what
enabled, for each answer the node can give and each thing the person can type. A
tool then walks every state your declarations allow and checks twelve laws in
each one. Adoption is per card. A card with no `csd:inputs` block is not checked.

## Where the laws come from, and what a card closes

Read this once. It is why the card is worth writing.

CIRISConstitution proves, in `formal/event_lifecycle` (Lean), that three things
are one function over the rows a node holds: the **queue** (what a person may
do), the **preview** of an act, and the **door** that admits it. That proof stops
at the item offered to the person. Its view has one moving part, the object's
state. It says nothing about values, about an answer going stale, or about what
was on the screen when the person pressed.

A CSD/5 card is the step after that. It models the screen as a function of its
inputs, with the person's edits, picks, presses, confirms and cancels as the
moves, and the node's answers as the other player. So a card is not a test plan.
It is the screen-side half of the constitution's model: the human and the app,
written down and walked state by state.

The intended join, which CC plans and has **not built**, is a refinement:

| in the card | in CC's kernel |
|---|---|
| an Action that is present and enabled | a transition in the queue for this person |
| `issue` of a write | the door's step on that row |
| a ConfirmSheet | the item's `confirm` |

When that join exists, one argument runs from a promise, through the rows a node
holds, to the pixels, and back to the signed row. Until it does, the two models
stand side by side and nothing checks one against the other. Write a card as if
the join were coming; never write that it holds.

### The twelve laws, by where each comes from

This sorting was agreed with the CC session at rc19 `7f79649` (its reading, not
a ruling of Eric's). It corrects `CSD5.md` §4, which says more for L7 and L12
than CC proves.

| law | kind | CC source | what is left to the card |
|---|---|---|---|
| L6 confirm iff it widens or has no way back | **carried, proved** | `confirm_is_lookup`, `widening_is_confirmed`, `no_way_back_is_confirmed`, `exit_is_one_step`; the `confirm` and `undo` cells of `transitions.json`; CC 4.5.13 | copy the cell; provisional where `undo` is `gap` |
| L7 the preview is the execution | **half carried** | `preview_is_execution`: the view previewed is the view the door produces | "sent only while the preview is held" and "sends only what it covered". The kernel has no held preview and no values. This half is the screen's own |
| L5 a reasoned refusal | **carried, part proved** | `queue_sound`, `queue_complete`, `door_is_queue` (offered iff admitted); `stranger_sees_nothing` (absent with no standing); `refused_previews_nothing`; `option.available` and `option.unavailable` in `choice_item.cddl` | disabled-with-a-reason versus absent is data, not a theorem |
| L3, L4 honest zero and number | **carried from data, in part** | `fact.provenance` in `choice_item.cddl` and `kind` in `view.cddl`: a `not_sent` fact is not a value | the read-outcome half (error, loading, empty only when answered). A read's transport outcome is the client's |
| L10 media never drawn bare | **carried from text** | CC 3.4.14 (AI-generation disclosure, mandatory, with its fail rule) and CC 3.3.13 (the Source struct). Not G9, which is about an agent | alt text has no CC source found |
| L12 shown is sent | **carried from data, no theorem** | `answer.shown_digest` in `choice_item.cddl` binds an answer to the item the node served | "every value sent is drawn" is the card's claim on top of the digest. No CC text says who checks the digest |
| L11 an answer is about its question | **method** | none; `shown_digest` is its natural anchor, since an answer to a stale item carries another digest | all of it. L7 and L12 mean nothing without it |
| L1, L2, L8, L9 | **method** | none. L8 resembles "the queue is a pure function of view and standing"; that is analogy | all of it |

**Carried** laws are obligations: breaking one breaks something the constitution
already states. **Method** laws are required by no promise. They exist so a
screen can be checked at all, as "state is a fold over held rows and nothing
else" exists so a node can be.

This is the constitution's own method applied to a screen. Its register lets no
guarantee in without a witness, a basis and the observation that would falsify
it, and `formal/ADEQUACY.md` marks every sub-claim proved, measured, stated,
bounded or open. A card is the witness for the screen. Its honest status today:
**stated** for the standard, **bounded** to one card (CSD-070), **open** for the
join and for anyone checking `shown_digest`.

### What this asks of a card author

- For a carried law, cite the CC source in the card where the law bites, and say
  when a cell is provisional.
- Keep each write exportable as (family, verb, scope, confirm, sends, shows),
  with the guard that enables it and the read or choice item each drawn value is
  bound to. That is the data CC needs to build the join, and the gate writes it
  for you: `--write-tla` also generates `formal/csd5/<card>.join.json`
  (`CSD5.md` §7.0) and holds the committed file to the card.
- Mark every node-side claim **observed on a node** or **read from source**.

## What you need open

| file | why |
|---|---|
| `FSD/CSD5.md` | the laws (§4), the block reference (§5), what is not checked (§6) |
| `packaging/csd5/ui_primitives.json` | the 24 primitives, their prefixes and slots, the 13 states. The standard's one copy is CIRISConstitution's `manifests/ui_primitives.json`; see "Who owns what" below |
| the card's Kotlin screen and view model | what is actually drawn and sent |
| the server source for each route the card calls | what the node can actually answer |
| CIRISConstitution `manifests/transitions.json` | the `confirm` cell for each row a write puts down |
| CIRISConstitution `manifests/choice_item.cddl`, `manifests/view.cddl` | what the node serves a choice and a view as: provenance, `available`, `shown_digest` |
| CIRISConstitution `formal/event_lifecycle/EventLifecycle.lean` | the theorems the carried laws cite. Read the names; there is no need to build Lean |
| `client/ceg/namespace_registry.json` | the family each write's `row:` must name |

### Who owns what (CIRISConstitution, as of rc19 `6b676e4`, 2026-11-11)

CC's rc19 refactor does not move these paths and plans no change to the
registry's shape, the five write verbs or the card block grammar. What it does
change is listed here so a card is not written against something stale.

| thing | owner | state |
|---|---|---|
| the registry, `manifests/ui_primitives.json` | CC | landed on rc19, marked `draft`. Same primitives, prefixes, slots, states and laws as this repo's copy. This repo does not pin it yet; until it does, edit neither copy without telling CC |
| the `confirm` cell, `manifests/transitions.json` | CC | landed. One value per (family, verb). Not pinned here yet, so `confirm:` is still copied by hand |
| the families a `row:` may name, `manifests/namespace_registry.json` | CC | rc19 has 184 families. **This repo pins rc6 (158).** rc19 folded families that differed only in their last segment: `capacity:<factor>` is now `capacity:{factor}`, `detection:<detector>` is `detection:{detector}`, `accord:lifecycle:active` is `accord:lifecycle:{kind}`. A `row:` on one of those names the folded row once the pin moves |
| the block grammar (`csd:inputs`, `csd:view`, `csd:acts`, guards, effects) | this repo | unchanged |
| the normative text of the laws | not decided | no Part of the CC states them. **Do not cite a CC section number for CSD/5**: none is assigned, and rc19 re-cuts the decimals. Cite `FSD/CSD5.md` |

To vendor, pin five files by sha256 at one CC commit: `ui_primitives.json`,
`transitions.json`, `choice_item.cddl`, `view.cddl`, `namespace_registry.json`.
Pin `EventLifecycle.lean` by sha256 too and check that each theorem a law cites
is named in it. Not done yet; today only the namespace registry is pinned.

Pin by commit, not by branch. `transitions.json` and `namespace_registry.json`
re-hash whenever Part 3 prose is edited, even when no cell changes. The durable
pin is the `v1.0-rc19` tag, which is not cut.

**Mark `confirm:` provisional** (a comment on the write) when the row is on a
family CC has not settled: a halt invocation, `self:delegates_to`,
`consent:scope`, a ledger row, or any transition whose `undo` cell is `gap`.
Whether a fired halt can be withdrawn, and so whether it is confirmed, is
Eric's to rule.

The one command, run from the repo root:

```
python3 packaging/check_csd5.py FSD/CSD/CSD-NNN-name.md
```

## Part 1. Writing a card's CSD/5 section

Add a section to the card (CSD-070 uses §2.3, "The screen as a function of its
inputs") holding three fenced blocks: ` ```yaml csd:inputs `, ` ```yaml csd:view `
and ` ```yaml csd:acts `. Work in this order. Each step ends with something you
can check.

### Step 1. Pick the surface id

Every tag starts `prefix_surface_`. The surface is the card's `surface:` id with
`-` written `_`, or the `flow:` id in `csd:view` for a card with no surface.

Six surface ids are shared by more than one card (`health_reputation`,
`contacts`, `files`, `nodes`, `identity_management`, `layer_family`). If yours is
one, decide first whether the cards are one screen written twice or two screens
that need two ids. Do not adopt until that is settled.

### Step 2. List the inputs (`csd:inputs`)

A screen is decided by three kinds of input and nothing else (L8).

1. **`context:`** which of `circle`, `window`, `mode` the screen reads. Leave out
   the ones it ignores; each one multiplies the states.
2. **`local:`** everything the person types, picks or opens. Types are `bool`,
   `text` and `enum`. A text is only ever blank or filled in the model. Give a
   value `lives_while:` when the part of the screen holding it can go away: when
   the guard turns false the value resets.
3. **`reads:`** one per question asked of the node. For each:
   - `of:` the inputs the question is asked over. This is what L11 holds the
     answer to: change any of them and the answer must be dropped.
   - `carries:` what an answer can hold that changes the drawing, each a bool or
     an enum. Not the payload: only what a guard needs.
   - `where:` the combinations the node can really send. **Read the server
     source for each one and cite it in a comment.** A `where:` you guessed makes
     the model explore less than the node can do.
4. **`previews:`** a dry run. `covers:` names the local values it was taken over.
5. **`writes:`** for each one:
   - `sends:` the local values it carries; `adds:` values that ride along without
     changing what was previewed.
   - `previewed_by:` if a dry run stands in front of it.
   - `row: {family, verb, scope}` the row it puts on the node. The family must
     exist in the pinned namespace registry; the verb is one of `attest`,
     `authorize`, `recant`, `supersede`, `withdraw`. `scope` is the circle the
     row is put down at: one of `self`, `family`, `community`, `affiliations`,
     `federation`, or the name of the enum local the person picks it with (its
     values must all be circles, and the write must send it). The door judges a
     row at one scope, and a wider scope can change whether it is confirmed.
     `row: null` only for a write that puts down no row.
   - `confirm:` **copied from CC's `transitions.json` for that family and verb.**
     True when the row widens or has no way back. Nothing checks your copy
     against the manifest yet, so a reviewer must.
6. **`defs:`** names for guards you repeat.

A refusal is an answer. A non-2xx with a body is an answer. `failed` means
nothing readable arrived.

### Step 3. Declare what is drawn (`csd:view`)

`kotlin:` lists the files that draw this screen. Then one entry per thing on
screen, each with an `id`, a `primitive` and, unless it is always there, a
`present:` guard.

| the thing on screen | primitive | what it must declare |
|---|---|---|
| what a read looks like in each outcome | `StateBlock` | `of:` the read; `states:` one guard per state |
| a number the node sent | `Metric` | `shown:` and `sent:` |
| a line of text from an answer | `FieldRow` or `Chip` | `of:` the read |
| something the person types | `Field` | `var:`; `on_change:` with what it drops |
| something the person picks | `Choice` | `var:` |
| a confirmation | `ConfirmSheet` | `while:`, `shows:`, and what `confirm:` issues |
| an image or video | `Media` | alt text and an AI-disclosure value (L10) |

Three rules that catch most first drafts:

- **StateBlock guards must be exhaustive and exclusive.** Exactly one holds in
  every reachable state (L2). `loading` only under `pending`, `error` only under
  `failed`, `populated`, `empty` and `refused` only under `ok` (L3).
- **Anything drawn once per row declares `per:`.** The row's id becomes the
  tag's key, so two rows never share a tag (L1).
- **A Field whose value an answer was taken over says so.** Its `on_change:`
  drops that read or preview (L11). This is the defect that shipped on Commons:
  the grounds could be edited while the old dry run stayed on screen.

Where the Kotlin still carries an older tag, record it under `was:`. Do not
rename the Kotlin in the same change unless you are also updating every flow
and every other repo that drives those tags.

### Step 4. Declare what the person can do (`csd:acts`)

One entry per button or menu item: `id`, `present:`, `enabled:`, `reason:`,
`effect:`.

- `reason:` is the localization key drawn while the act is disabled, and it must
  exist in `en.json` (L5). `reason: null` is a recorded debt: it is counted in
  `packaging/csd5/baseline.json` and that count may only fall. Do not add one.
- A write that is `confirm: true` is issued only from a sheet's confirm. Every
  other write is issued straight from an Action, with no sheet in front (L6). A
  confirm in front of leaving, withdrawing or revoking is refused the same way a
  missing one is.
- Whatever a write sends must be on screen at the moment it is sent: in a
  present Field or Choice, or listed under the sheet's or Action's `shows:`
  (L12). Prefer `shows:`; a Field hidden under an open sheet passes but leans on
  §6 item 9.
- Give every node-reaching effect its three branches: `on_ok`, `on_refused`,
  `on_fail`. Leaving out `on_refused` is a claim that a refusal changes nothing.

### Step 5. Run it, and read what it says

```
python3 packaging/check_csd5.py FSD/CSD/CSD-NNN-name.md
```

A failure names the law, the instance, and the shortest sequence of edits,
presses and answers that reaches the bad state. Follow that sequence on the real
screen before changing the card: either the card is wrong about the screen, or
the screen has the defect. Both happened on CSD-070, and the second kind is the
point of the exercise. Fix the Kotlin for those; do not bend the card to match a
screen that is wrong.

| it says | usually means |
|---|---|
| L2: two states hold / no state holds | StateBlock guards overlap or leave a gap |
| L3 / L4: drawn without an answer | a `present:` or `shown:` guard forgot `read = ok` |
| L7: sent without a held preview | the submit is enabled before the dry run, or the preview's `lives_while` is too long |
| L9: never reached | a dead state or control, or a `where:` that is too tight |
| L11: held after an input changed | a Field is missing `on_change: [{drop: …}]` |
| L12: sent but not drawn | a sent value is on no present Field and not under `shows:` |
| a Kotlin tag the model does not account for | an instance is missing, or a `was:` is |

### Step 6. Generate the model and finish

```
python3 packaging/check_csd5.py --tags CSD-NNN      # old tag -> derived tag; this is the rename list
python3 packaging/check_csd5.py --write-tla         # writes formal/csd5/CSDNNN.tla, .cfg and .join.json
python3 packaging/check_csd5.py                     # everything, as CI runs it
bash packaging/gates.sh                             # every release gate
```

Commit the card, `formal/csd5/CSDNNN.tla`, `.cfg` and `.join.json` together, and add the
module's row to `formal/csd5/README.md`. Never edit a `.tla` by hand. CI's
`csd5 (TLC)` job runs TLC on the committed module and requires the same number
of distinct states the explorer counted.

In the card's QA section, say which claims were **observed on a node** and which
were **read from source**. A `where:` clause is read from source until a flow
has seen it.

## Part 2. Updating an adopted card

| you changed | you must also |
|---|---|
| anything in the three blocks | re-run the check, then `--write-tla`, and commit the regenerated module and join export |
| the Kotlin screen (a control, a guard, a tag) | change the card in the same commit; the tag scan fails otherwise |
| a route's answer shape on the server | re-read the source and correct `carries:` and `where:` |
| the namespace pin moved (rc6 today) | re-check every adopted card's `row:` against the new families |
| a row a write puts down, or CC changed a `confirm` cell | update `row:` and `confirm:`, then move the write behind or out from behind a sheet |
| an English string used as a `reason:` | keep the key; changed meaning is a new key ([`TEXT_REFERENCES.md`](TEXT_REFERENCES.md)) |
| a tag's name | list every flow and every repo that drives it (CIRISAgent, CEWPOS), and land the rename with them |
| the registry (a primitive, prefix, slot, state or law) | bump its `draft` number; that is CIRISConstitution's file once it publishes one |

When a count in `baseline.json` falls, the gate says so. Run `--write-baseline`
and commit it. Never raise it by hand.

## Part 3. Reviewing a CSD/5 card

A green check proves the card is consistent with itself and with the tags in the
Kotlin. It does not prove the card is true. The reviewer checks what the tool
cannot.

**Is the card true about the node?**
- [ ] Each `where:` cites server source, and the source says that.
- [ ] Each read's `carries:` covers everything that changes the drawing.
- [ ] Each `row:` is the row the route really writes.
- [ ] Each `confirm:` matches CC's `transitions.json` for that family and verb, and is marked provisional on a family CC has not settled.
- [ ] No CC section number is given as the home of a CSD/5 law (none is assigned). Citing the existing CC rule a law is carried from is right, and those decimals will be re-cut in rc19, so cite the theorem or manifest member beside the number.
- [ ] Any effect with no `on_refused` really is unchanged by a refusal.

**Is the card true about the screen?**
- [ ] `kotlin:` lists every file that draws the screen. A file left out is not scanned.
- [ ] Follow two or three paths through the real screen and find each control in `csd:view`.
- [ ] Every `was:` tag is what the Kotlin has today.

**Does it hold up a person's choice?**
- [ ] Nothing that widens or cannot be undone is one tap away.
- [ ] Nothing that is a way out (leave, withdraw, revoke) has a confirm in front of it.
- [ ] Each sheet's `shows:` states what it sends, rather than relying on a field underneath.
- [ ] No new `reason: null`.
- [ ] No "cannot be undone" copy drawn from an `undo` cell CC marks `gap`.

**Does it say where its obligations come from?**
- [ ] Each carried law the card leans on names its CC source.
- [ ] Nothing in the card claims the join with CC's kernel holds.
- [ ] L7 is shown both ways: the preview is what the door will do (CC's), and nothing is sent that the held preview did not cover (the card's).

**Is it finished?**
- [ ] `check_csd5.py` and `gates.sh` pass, and the PR records their output.
- [ ] The `.tla`, `.cfg` and `.join.json` are regenerated, and `csd5 (TLC)` is green on the head commit.
- [ ] The card says what was observed on a node and what was read from source.
- [ ] A rename lists the flows and repos it touches.

**What a pass never tells you** (`CSD5.md` §6): timing, more than one row at
once, text content beyond blank or filled, layout, wording, translation, and
whether the Kotlin behaves as the card says beyond the tags they share. Those
are covered by view-model unit tests and the five-platform flows, or not at all.
