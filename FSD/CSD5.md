# CSD/5 — the screen as a function of its inputs

**Status**: draft 1, proposed. Authored in CIRISClient. The proposed owner of the
registry is CIRISConstitution (`manifests/ui_primitives.json`, beside
`namespace_registry.json` and `topology_vocabulary.json`); until CC publishes it,
`packaging/csd5/ui_primitives.json` is the only copy and that is a row in
`evidence/blocked_upstream.tsv`. Draft 1 keys L6 on the `confirm` cell of CC's
`manifests/transitions.json` (rc19 `63b2f06`) and adds L12.
**To write, update or review a card**: [`CSD5_GUIDE.md`](CSD5_GUIDE.md).
**Builds on**: CSD/3 (`CSD.md`: stage, surface, shows, states) and CSD/4
(`FSD/CSD4_EVALUATION.md`, `packaging/csd4/`: what the node holds at one instant).
**Adopted by**: one card, CSD-070 Commons (`FSD/CSD/CSD-070-commons.md` §2.3).
**Checked by**: `packaging/check_csd5.py`. **Model**: `formal/csd5/`.

## 0. What this is and what it is not

CSD/3 says what a card shows. CSD/4 says what the node holds when a test looks.
Neither says which control is drawn, in which state, and with what enabled, for a
given answer from the node and a given thing the person has typed. That part was
in the Kotlin and in nobody's head, and it is where the Commons screen went wrong
(§8).

CSD/5 writes that part down. A card declares its inputs, what it draws from them,
and what the person can do. A tool then walks every state those declarations
allow and checks twelve laws in each one.

It is **not** a proof about the Kotlin. The model reads the card. The only tie to
the code is the tags, which the five-platform flows also drive. A screen whose
code disagrees with its card in some way the tags do not show passes this check
(§6).

Nothing here is native review, a usability claim, or a claim that a flow ran on a
node. A card says separately which of its claims were observed and which were
read from source.

## 1. Three kinds of input and nothing else

What a screen draws is decided by exactly these (law L8):

| input | what it is | values |
|---|---|---|
| **context** | where the screen sits; fixed for a run | `circle` (self, family, community, affiliations, federation), `window` (compact, wide), `mode` (node, agent) |
| **local** | what the person has typed, picked or opened on this screen | `bool`, `text` (the model keeps only blank or filled), `enum` (listed values) |
| **read outcome** | what the node said | `idle` not asked · `pending` asked · `failed` no answer or unreadable · `ok` the node answered |

A refusal is an answer. A non-2xx status with a body is an answer. `failed` means
nothing readable arrived. This is the distinction CSD/3's `error` and `empty`
states already made in prose; here a tool holds it.

An `ok` read **carries** declared values (a bool or an enum each), and a card may
restrict which combinations the node can send with `where:` guards, each citing
the server source it was read from.

Two more things are derived from those three:

* a **preview** is an answer to "what would this write do", taken over (`covers:`)
  named local values;
* a **write** names the local values it `sends:`, the preview that covers it
  (`previewed_by:`) if it has one, the row it puts on the node (`row:`, a family
  and a verb) and whether that row is confirmed (`confirm:`, L6).

## 2. The registry

`packaging/csd5/ui_primitives.json` holds everything a card may refer to:

* **24 primitives.** B01–B22 are the basis of the CEWPOS UX alignment review.
  B23 `Document` and B24 `Media` are proposed here, raised by the NodeCore
  article, news and gallery cards (CSD-135..144) and by Calls (CSD-134).
* **Tag prefixes**, each owned by exactly one primitive:
  `card txt row scope chip btn mi receipt state stat sheet opt pick input step
  ceremony chain code qr scan msg note conscience lens canvas doc media`.
  Four more belong to the shell: `circle tab nav screen`.
* **Slots** per primitive: the fixed parts it has (a ConfirmSheet has `fact_1`,
  `fact_2`, `fact_3`, `note`, `confirm`, `cancel`).
* **13 states** a StateBlock may be in, each tied to the read outcome it is
  allowed under: `idle` · `loading`, `loading_known` · `error` · and, under an
  answer, `populated`, `empty`, `refused`, `gone`, `hidden_by_rules`,
  `unreviewed`, `waiting`, `unknown`, `not_on_this_node`.
* **12 laws** (§4), and the five **write verbs** a row may be written with
  (`attest`, `authorize`, `recant`, `supersede`, `withdraw`), which are CC's.

A new primitive, prefix, slot, state or law, or a changed meaning, bumps the
registry's `draft` number. A new note does not.

## 3. The tag grammar

```
tag        = prefix "_" surface "_" instance [ "__" slot ] [ "." key ]
shell_tag  = shell_prefix "_" name
```

| part | rule |
|---|---|
| `prefix` | one lower-case word, owned by exactly one primitive |
| `surface` | the card's `surface:` id with `-` written `_`; for a card with no surface, the `flow:` id its `csd:view` declares. No surface id is another one followed by `_`, so the split is unique |
| `instance` | declared in the card's `csd:view` or `csd:acts` |
| `slot` | from the primitive's closed slot list |
| `key` | which one of several: a row id, an option, a step. Everything after the first `.` |

**A tag is written by nobody.** It is derived from (primitive, surface,
instance, slot, key). In Kotlin only `ui/primitives` builds one.

Three consequences, all checked:

* An instance drawn once per element of a collection declares `per:` and takes
  that element's id as its key. Two rows never share a tag.
* A StateBlock's root tag is always present and its **value** is the state's
  name. A flow asserts `state_commons_standing` has value `refused`; it does not
  go looking for which of eight tags happens to exist.
* A ConfirmSheet's confirm and cancel are slots of the sheet
  (`sheet_commons_raise__confirm`), not free buttons.

Where the Kotlin still carries an older tag, the card records it under `was:`.
`python3 packaging/check_csd5.py --tags CSD-070` prints old → derived. The
Kotlin is renamed card by card, with the flows and the other repos that drive
those tags; a card's `was:` entries are the list of what that rename touches.

## 4. The twelve laws

| | law | what it refuses |
|---|---|---|
| L1 | **One owner.** Every prefix has one owning primitive; every derived tag names one instance | two primitives claiming `btn`; two rows sharing a tag |
| L2 | **Exactly one state.** A StateBlock is in exactly one declared state in every reachable state of the card | `empty` and `populated` both true; no state at all |
| L3 | **An honest zero.** `error` only when the read failed, `loading` only while pending, never `empty` or `populated` unless the node answered. Anything drawn from a read is present only when it was answered | a failed read drawn as "nothing here"; last answer left on screen under an error |
| L4 | **An honest number.** A Metric shows a value only when its read was answered and the value was sent | a default `0` drawn as the node's number |
| L5 | **A reasoned refusal.** A disabled Action names the reason it shows; an act that is not the reader's to know is absent | a grey button with no explanation |
| L6 | **Confirmed when it widens or has no way back, and only then.** A write is `confirm: true` when CC's `manifests/transitions.json` says so for its family and verb. Such a write is sent only from the confirm of a ConfirmSheet. Every other write takes one step: an Action sends it and no sheet stands in front of it | a button that widens directly; a confirm in front of withdrawing a consent or leaving a room |
| L7 | **The preview is the execution.** A write that has a preview is sent only while that preview is held and sends only values the preview covered | a dry run shown, then different bytes sent |
| L8 | **A function of its inputs.** A guard names only what the card declares | a screen that depends on something unwritten |
| L9 | **Nothing declared is unreachable.** Every instance is present somewhere; every declared state is reached | dead controls and dead states |
| L10 | **Media is never drawn bare.** A Media instance has alt text and an AI-disclosure value or it is not drawn | an unlabelled image |
| L11 | **An answer is about its question.** A read's answer, and a preview, are held only while every input they were taken over still has the value it had when asked | a standing drawn beside a cohort key it was not read for |
| L12 | **Shown is sent.** A write is sent only from something the person answered, a ConfirmSheet's confirm or an Action, and at that moment every value it sends is drawn: by a present Field or Choice bound to it, or by the sheet or Action, which lists it under `shows:` | a write that carries a value the person could not see when they pressed |

Where the laws come from. CIRISConstitution `formal/event_lifecycle` proves that
the queue, the preview and the door are one function over the rows a node holds,
over a view whose only moving part is the object's state (CC 4.5.13,
`manifests/view.cddl`, `manifests/choice_item.cddl`). It says nothing about
values, about an answer going stale, or about what was drawn. The laws sort by
how much of each that work carries. This is the CC session's reading at rc19
`7f79649`, agreed with this repo; it is not a ruling, and no Part states the
laws.

* **L6 is carried and proved.** It is `confirm_is_lookup`, with its halves
  `widening_is_confirmed` and `no_way_back_is_confirmed`, and `exit_is_one_step`.
  Which writes are confirmed is not decided here. CC's
  `manifests/transitions.json` gives every transition a `confirm` cell, true
  when the row widens or the table offers no way back, and a card copies that
  cell for the row its write puts down. The law runs both ways on purpose: a
  confirm in front of an exit is friction on the way out, and the gate refuses
  it as it refuses a missing one. Where a transition's `undo` cell is `gap`, the
  `confirm` value is provisional.
* **L7 is half carried.** `preview_is_execution` proves that the view an item
  previews is the view the door produces when the row arrives. It does not prove
  that a write is sent only while its preview is held, or that it sends only
  what the preview covered: the kernel has no held preview and no values. That
  half is this standard's own, and it is the half CSD-070 broke (§8).
* **L5 is carried, in part proved.** `queue_sound`, `queue_complete` and
  `door_is_queue` give "offered if and only if admitted";
  `stranger_sees_nothing` gives absence for a reader with no standing;
  `refused_previews_nothing` says a transition not offered produces nothing at
  the door. That an Action is disabled with a reason and not absent is data,
  `option.available` and `option.unavailable` in `choice_item.cddl`, and no
  theorem.
* **L3 and L4 are carried from data, in part.** `fact.provenance` in
  `choice_item.cddl` and `kind` in `view.cddl` say a fact is `wire`, `by_rule`
  or `not_sent`, and a `not_sent` fact is not a value. The read-outcome half of
  L3 (`error`, `loading`, `empty` only when the node answered) has no CC source:
  a read's transport outcome is the client's.
* **L10 is carried from text**: CC 3.4.14, the AI-generation disclosure, which
  is mandatory and has its own fail rule, and CC 3.3.13, the Source struct. The
  alt text has no CC source found.
* **L12 is carried from data and no theorem.** `answer.shown_digest` in
  `choice_item.cddl` is the hash of the item as the node served it: it binds an
  answer to that item, not to what was drawn. "Every value it sends is drawn" is
  this standard's claim on top of the digest, and no CC text says who checks
  the digest.
* **L1, L2, L8, L9 and L11 are method.** No promise requires them. They are
  what lets a screen be checked at all, as "state is a fold over the held rows
  and nothing else" lets a node be. L11 is what makes L7 and L12 mean anything
  on a screen, and `shown_digest` is its natural anchor, since an answer to a
  stale item carries another digest.

The two models are **not composed**. CC plans a refinement from a card to its
kernel (an Action that is present and enabled is a transition in the queue; a
card's `issue` is the door's step; a ConfirmSheet is the item's confirm) and
has not started it. Until it exists nothing checks one model against the other.
[`CSD5_GUIDE.md`](CSD5_GUIDE.md) has the same table for a card's author.

The gate checks that a write's `row:` names a family in the namespace registry
pinned at `client/ceg/namespace_registry.json` and one of the five verbs. It
does **not** check the card's `confirm:` against CC's manifest: this repo pins
no copy of `transitions.json`. Until it does, `confirm:` is the card author's
reading of that cell, and a wrong one passes (§6).

L5 has a recorded debt. A card writes `reason: null` for a disabled Action that
draws no reason today. Each is counted in `packaging/csd5/baseline.json` and the
count may only fall.

## 5. The three blocks

A card adopts CSD/5 by adding three typed blocks. The worked example is CSD-070
§2.3; this section is the reference.

### 5.1 `csd:inputs`

```yaml
context: [window]                       # which context keys this screen reads
local:
  cohort_key:  {type: text}
  lifting:     {type: bool, lives_while: "has_objections or dry_run = taken"}
reads:
  standing:
    of: [cohort, cohort_key, action]    # the inputs the question is asked over (L11)
    carries:
      refused: {type: bool}
      arm:     {type: enum, values: [quiet, objected, stood, reversed]}
    where:                              # combinations the node can actually send
      - "not standing.refused or standing.arm = unreadable"
previews:
  dry_run:
    covers: [cohort, cohort_key, action, dismiss_grounds]
    lives_while: "standing != ok or has_objections"
writes:
  dismiss:
    sends: [cohort, cohort_key, action, dismiss_grounds]
    adds: [cosigners]
    previewed_by: dry_run
    row: {family: "objection:{state}", verb: attest, scope: cohort}   # the row this write puts on the node
    confirm: true                                      # CC transitions.json, that family and verb
defs:
  has_objections: "standing = ok and not standing.refused and standing.objections"
```

`lives_while` is how a value held by a part of the screen that is not drawn is
written down: when the guard is false the value returns to its initial value.
`adds:` names values a write carries that the preview did not need to cover,
because they do not change what is signed (co-signatures over the previewed
bytes). Every write declares `confirm:` and `row:`. A row names its family, its verb
and its `scope:`, which is one circle of the registry or the enum local the
person picks the circle with (that local's values are all circles, and the
write sends it). The door judges a row at one scope, and a wider scope can
change whether it is confirmed. `row: null` is for a write
that puts no row on the node, and says so.

### 5.2 `csd:view`

```yaml
kotlin: [client/shared/src/commonMain/kotlin/.../CommonsScreen.kt]
instances:
  - id: standing
    primitive: StateBlock
    of: standing
    states:
      loading: "standing = pending"
      error:   "standing = failed"
      refused: "standing = ok and standing.refused"
      # ...one guard per state; L2 says exactly one holds, everywhere
  - {id: raise_price, primitive: Metric, of: standing, shown: "standing = ok and standing.prices", sent: standing.prices}
  - {id: cohort_key, primitive: Field, var: cohort_key, on_change: [{drop: [standing, dry_run]}]}
  - {id: ballot_grounds, primitive: Field, var: ballot_grounds, per: objection, present: has_objections}
  - id: raise
    primitive: ConfirmSheet
    while: "standing = ok and raise_grounds != blank"
    shows: [action, cohort_key]     # what the sheet itself draws of what is sent (L12)
    confirm:
      - issue: raise
        on_ok:      [{set: {raise_grounds: blank}}, {ask: standing}]
        on_refused: [{set: {result: answer}}]
        on_fail:    [{set: {write_error: true}}]
```

Every instance has `present:` (default: always). A Field or Choice names the
local `var:` it edits. A Metric names `shown:` and what must have been `sent:`.
A ConfirmSheet names the guard it stays open `while:` and what its confirm does.
A ConfirmSheet or an Action that sends a write lists under `shows:` the sent
values it draws itself; every other sent value must be in a Field or Choice that
is present at that moment (L12). A Field under an open sheet counts as drawn.

### 5.3 `csd:acts`

```yaml
acts:
  - {id: read, enabled: "question_filled and standing != pending", reason: null, effect: [{ask: standing}]}
  - {id: dismiss_submit, per: objection, present: "lifting and has_objections",
     enabled: "dry_run = taken", reason: surfaces.commons.dismiss_gate, effect: [{open: dismiss}]}
```

An act is an Action plus a transition. `reason:` is the localization key drawn
while it is disabled, and the key must exist in `en.json`.

### 5.4 Guards and effects

A guard is `a = v`, `a != v`, `a in [v, w]` or a bare bool name, joined by `and`,
`or`, `not` and parentheses. It is parsed into a tree; it is never handed to an
interpreter as written.

| effect | does |
|---|---|
| `set` | give a bool or enum local a value, or blank a text |
| `toggle` | flip a bool |
| `ask` | start a read; the old answer is dropped first |
| `call` | ask the node for a preview; branches `on_ok` / `on_refused` / `on_fail` |
| `drop` | let go of a read, a preview or a local value |
| `open` | open a ConfirmSheet |
| `issue` | send a write: from a sheet's confirm when the write is `confirm: true`, from an Action's effect when it is not (L6, L12); same three branches |
| `leave` | leave the screen |

A node-reaching effect that declares no `on_refused` claims a refusal leaves the
screen exactly as an answer does. That branch is then not explored, and it is
the card's claim that it cannot differ.

## 6. What the model leaves out

The model is explicit-state and small on purpose. These are not checked, and a
card that passes says nothing about them:

1. **Time.** How long anything takes; ordering between two requests in flight
   beyond "an answer for an older question is not drawn".
2. **What a write's answer says** beyond done, refused, or unanswered.
3. **More than one element.** A `per:` instance is checked for one element. That
   two elements do not interfere is carried by the key in the tag, not by the
   exploration.
4. **Text content.** A text is blank or filled. Against each answer taken over
   it the model also keeps whether the text still equals what was asked, which
   is what two distinct non-blank values would show, and no more.
5. **Layout, colour, wording, translation.**
6. **The Kotlin.** The check ties the card to the code only through tags: every
   tag literal in the files the card names is accounted for by the model, and
   every old tag the model names exists there. Behaviour is tied by unit tests
   on the view model and by the five-platform flows, which drive the same tags.
7. **The node.** `where:` and the three branches are the card's reading of the
   server's source. A card says which of them were observed on a node.
8. **Whether `confirm:` is CC's.** The cell is copied by hand from
   `manifests/transitions.json` and nothing here compares the two (§4).
9. **What a covering sheet hides.** L12 counts a Field as drawn while a sheet is
   open over it. A sheet that states everything it sends under `shows:` does not
   lean on that; CSD-070's three do not state the grounds, and lean on it.
10. **That the hash a preview shows is the hash that lands.** L7 holds the values
   sent to the values previewed. The node may still stamp a new instant and id
   onto them (§8, last row).

## 7. The gate

```
python3 packaging/check_csd5.py                 # registry, every CSD/5 card, Kotlin tags, baseline, TLA+ drift
python3 packaging/check_csd5.py FSD/CSD/CSD-070-commons.md
python3 packaging/check_csd5.py --tags CSD-070  # old tag -> derived tag
python3 packaging/check_csd5.py --write-tla     # regenerate formal/csd5/<card>.tla, .cfg and .join.json
python3 packaging/check_csd5.py --write-baseline
python3 packaging/check_csd5.py --self-test     # plant defects; each must go red
python3 packaging/check_csd5.py --tlc tla2tools.jar              # also run TLC on each committed module
python3 packaging/check_csd5.py --self-test --tlc tla2tools.jar  # and plant defects TLC must find
```

What the gate fails on:

* the registry breaks its own rules (a prefix with two owners or of more than
  one word, a primitive that owns no prefix, a state tied to no read outcome,
  a missing law, no `write_verbs`);
* a write that does not say `confirm:` and `row:`, or whose row names a family
  the pinned namespace registry does not have, a verb outside the five, or a
  scope that is neither a circle nor a local whose values are circles;
* a CSD/5 card breaks any law in any reachable state. The failure prints the
  shortest sequence of edits, presses and answers that reaches it;
* a tag literal in the card's Kotlin files that the model does not account for,
  or a `was:` tag the Kotlin does not have;
* `formal/csd5/<card>.tla`, `.cfg` or `.join.json` differs from what the card
  generates;
* with `--tlc`: TLC does not finish the search, finds an invariant violated, or
  counts a different number of distinct states than the explorer did;
* **the ratchet.** `packaging/csd5/baseline.json` records, for the whole
  client, the tag literals whose first word no primitive owns (recorded at
  adoption: 669 tags under 114 prefixes) and the disabled Actions that draw no
  reason (6, all on CSD-070). A new unowned prefix, one more tag under an
  existing one, or one more silent Action fails. Both counts may only fall;
  when one has, the gate says how many can be lowered and `--write-baseline`
  re-records them.

`--self-test` plants 30 defects, one or more per law plus the Kotlin, TLA+ and
ratchet checks, and fails unless every one goes red. It also plants one thing
that must stay green: an exit sent in one step from an Action, with no sheet.
The first planted defect is the one that shipped: editing the dismissal grounds
while the dry run stays held. With `--tlc` it plants five more, 35 in all: four
modules, each generated from the card with one defect planted (L2, L7, L11,
L12), where TLC must name an invariant of that law as violated; and the clean
module held against a state count that is off by one.

A card with no `csd:inputs` block is untouched. Adoption is per card; the
ratchet is what holds the rest of the client still while that happens.

### 7.0 The join export

`--write-tla` also writes `formal/csd5/<card>.join.json`: the card as data, for
the model that will join it to CIRISConstitution's kernel (§4). It holds each
write as family, verb, scope, confirm, what it sends and adds, the preview that
covers it, and every sender: the ConfirmSheet or Action that issues it, the
guards under which it can, the Actions that open the sheet, the tags a flow
presses, and what the sender itself `shows:`. It also lists every drawn
instance with the read, preview or local value it is bound to. Guards are the
card's own text. `scope` is one circle, or the local that carries it with the circles it can hold.

It is an export, not a proof. Nothing reads it yet. CC asked for this data to
build the join, and the gate holds it to the card so that it cannot go stale
while it waits.

### 7.1 The TLA+ module

`--write-tla` emits one module per card from the same three blocks: one record
variable, one action per edit, press, answer, confirm and cancel, and one
invariant per law instance. The module's header states how many distinct states
the Python explorer reached (CSD-070: 134,928). `--tlc` runs TLC 2.19
(`tla2tools.jar` v1.7.4, the release CIRISConstitution's `formal/` uses) on the
committed module and requires the same number and no violated invariant. That
agreement is the cross-check between two independent readings of the card: the
explorer walks the card's blocks directly, and TLC walks the module generated
from them. On CSD-070 TLC reports 134,928 distinct states and no error. CI runs
both on every push. See `formal/csd5/README.md` for the output.

## 8. What adoption found on its first card

CSD-070 Commons is where one cohort member raises a brake on a pending commons
act and the cohort's m-of-n lifts it. Every write on it is signed on the owner's
behalf, replicates to every peer and cannot be taken back. Writing its three
blocks, and reading the server source the `where:` clauses cite, found:

| law | what the screen did | status |
|---|---|---|
| L7 | A co-signed dismissal did not echo the dry run's envelope, which the server has required since CIRISServer#402; the node refuses it `stamped_envelope_required` | fixed; read from source, not run on a node |
| L11 | The grounds, cohort key and action could be edited after the dry run; the submit sent the current values under the old hash | fixed |
| L3 | A failed re-read or failed write left the previous standing drawn in full under an error line | fixed |
| L4 | A refused standing drew a raise price and a floor of `0` that the node did not send | fixed |
| L1 | Tags inside the per-objection card are not keyed by objection id | Compose cards keyed; tags wait for the rename |
| L5 | Six Actions can be disabled and draw no reason | counted in the baseline |
| L7 | A dismissal with no co-signer: the node ignores the echoed envelope and stamps again, so the row that lands has another instant, id and hash than the dry run showed. Nothing false is sent and the result card draws the hash that landed; the two hashes differ | open: CIRISServer is asked to honour the echoed envelope on every path (`evidence/blocked_upstream.tsv`) |

The card's §5 lists what is still not asserted.

## 9. Ownership, and what is asked of other repos

* **CIRISConstitution** is asked to own the registry as
  `manifests/ui_primitives.json` and this standard's normative text, as it owns
  the namespace registry and the topology vocabulary. This repo then pins a
  copy by sha256, the way `packaging/csd4/` pins the catalogue, and
  `packaging/csd5/ui_primitives.json` stops being a source. With it comes a pin
  of `manifests/transitions.json`, so that a card's `confirm:` is compared with
  the cell instead of copied from it. Two things in that manifest a screen must
  not turn into copy: 31 of its `undo` cells are marked `gap`, which means
  nobody has written the way back down, not that there is none, so a screen
  draws no "cannot be undone" from one; and the halt
  (`accord:invoke:constitutional:{halt_id}`, attest) is `confirm: true` there,
  so a card that draws a halt puts a sheet in front of it unless CC changes the
  cell.
* **The 22's owners (CEWPOS)** are asked about three things the first card
  raised: `idle` and `refused` as StateBlock states; a state for "the node
  answered that this is not governed", which CSD-070 draws as `populated`
  today; and B23 `Document` and B24 `Media`.
* **Six surface ids are shared by more than one card** (`health_reputation`,
  `contacts`, `files`, `nodes`, `identity_management`, `layer_family`). The
  grammar needs a surface id to name one card's screen; each pair is either one
  card twice or two screens that need two ids. Decided card by card at adoption.
* **CIRISAgent and CEWPOS** drive this client's tags. A card's rename lands
  with its `was:` list, and nothing is renamed ahead of the card that owns it.
