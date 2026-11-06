# FSD — Text references: one namespace, one translation pipeline

**Status:** proposed, for review by CIRISServer and CEWPOS. Nothing here is built.
**Author:** CIRISClient, 2026-11-05
**Direction:** Eric, 2026-11-05: keep translation in this repo, "separation of duties is
useful regardless, and the UX is the final consumer regardless of who serves it up."
**Normative source:** CC 4.5.13 (rc17), `manifests/choice_item.cddl` (`text-ref`).
**Reads with:** `docs/FSD-ui-language.md` (rendering and tags), `localization/TRANSLATION_GUIDE.md`,
CSD-088 (the pilot), CIRISServer#858 and #851.

## 1. What this decides

1. **One id namespace.** The keys of this repo's bundle are the ids of every `text-ref`, on every
   surface: client, node, agent and CEWPOS.
2. **One translation pipeline.** UX strings are authored and translated in `localization/` here, and
   a surface consumes the result. CIRISAgent translates its own content (accord text, prompts) with
   a vendored copy of this same pipeline, gated by `check_localize_vendoring.py`; that stays. No
   repo builds a second pipeline, and nobody else translates a UX string.
3. **Migration in stages, one card at a time,** measured by the distance CC 4.5.13 defines: what a
   consumer holds and a node does not serve, and the reverse.
4. **A string is checked against what it describes,** not only against another copy of itself (§4).

**Not decided here:** the exact form of item keys (§5), which CIRISServer proposes in #858.

## 2. Why

Measured by stage 1: [`STRING_INVENTORY.md`](STRING_INVENTORY.md) summarises the generated table, and
`packaging/gen_string_inventory.py --check` keeps it current. Usage is found by searching source, so
it is evidence, not proof.

| Finding | Number | What it means |
|---|---|---|
| Keys | 5,881 in 29 languages: 4,661 client, 551 server, 216 agent, 453 unclaimed | A shared namespace partly exists already |
| English strings under more than one key | 382 strings, 1,037 keys | Equal English does not prove equal meaning, so translations grade each group |
| Same in every language | 67 groups, 142 keys | Candidates to share a key, for a person to confirm |
| Short strings that differ | 288 groups, 836 keys | Context can explain these |
| Sentences that differ | 27 groups, 59 keys | Identical English, translated differently: drift until read |
| Confirmation facts | 129 keys, 43 of them duplicates | CC 4.5.13 derives these, so they go when their card flips |
| Unreachable root keys | 47 | Lookup never falls back to them, and they are still translated |

**CEWPOS holds no locale files or i18n layer** (`~/CEWPOS` at 8e93039), and its README cites the
client's 29 locales. So the risk there is a second translation effort starting from zero. It avoids
that by consuming this one.

**A wrong string has already shipped through this gap.** `mesh.announce_requirement` said "no service
access on the mesh and no agent services" while CC 5.4.6 stated different costs. Client and server
agreed with each other, and both were wrong.

## 3. Who owns what

Separation of duties: whoever states what a thing does is not who renders it to a person, and neither
judges the translation alone.

| Duty | Owner | Note |
|---|---|---|
| What a thing means, its costs and limits | CC, and the family model for impact | The source of truth for meaning |
| The source-language sentence | Whoever emits the id: CIRISServer for what it serves, CIRISClient for the rest | One author per id |
| Whether it reads right to a person | CIRISClient: `docs/five-year-old-test.md`, glossaries | The UX is the final consumer, whoever serves it |
| Translation and review of UX strings | CIRISClient: `localization/`, drafter and judge from different model families | Only here |
| Distribution to a node | The installed `ciris-client` package, whose base wheel carries the bundle (`ciris_client/_artifacts/localization/`), or a path its host hands in. **A node never fetches text at run time:** login has to work offline and on a mesh-only network | `ciris-server` depends on `ciris-client>=0.5.237,<0.6` |
| Distribution elsewhere | The release's `ciris-client-X-localization.zip`, for consumers without the package such as CEWPOS and the agent's string check | Same data, so one pipeline still holds |
| Serving | The node, and the agent's thin routes | Resolves id + locale, loading one locale on demand: the bundle is about 20 MB unpacked, too much to hold in a phone's memory |

**What a node has on disk.** Read from source, not tested on a device.

| Host | Locale data | Consequence |
|---|---|---|
| Wheel-installed node | The `ciris-client` package | Reads it. A range, so it can lag the server's ids; `lang` covers that |
| Android embedded | Declared by `ciris-server`, so pip likely installs it, unconfirmed. The APK's `assets/localization` is not a Python path | Confirm in stage 3 |
| iOS embedded | No `ciris-client` package; files at `Resources/app/localization` | The host hands the node that path |
| Bare `ciris-server` binary | None | English with `lang` marked |

So the node's lookup order is a path its host hands in, then the installed package, then source
language marked with `lang`. A phone's host passes the path at start-up.

**The end state (Eric, 2026-11-05).** A client without a node cannot exist: it is impossible to render
the epistemic state without one. So the node serves every string, login and startup included. The
app holds only what it must say when no node has answered:

| Held in the app | Why |
|---|---|
| "Waiting for node" and similar at startup | Shown before any node has answered |
| Error messages for a node that never comes up | Nothing else can say so |
| Browsing and connecting to nodes (`ServerConnection`: the address form, recent nodes, connect, remove, status) | Eric, 2026-11-05: the client must be able to find a node with no node answering. Pinned by `testing/test_node_connect_strings.py` |

Everything else leaves the app, including the first-run wizard, sign-in and the startup screen once a
node answers. Those are served before any session exists, so they are public text and stay outside
the operator gate. This matches CC 4.5.13's "the client holds no copy of its own". Authoring here is not
holding a copy at run time: the app's bundle shrinks to that list, this repo's pipeline stays, and a
node's locale data comes from this repo's pipeline.

**What this does not cost.** No translation output is ever unreviewed, and no surface falls back to
English silently: `lang` marks it, per CC.

## 4. Veracity: why one key is not enough

Today, two copies of a string can disagree, and a check sees it. That is useful, and a shared key
removes it: a wrong sentence becomes wrong everywhere, with nothing to compare against. So each stage
keeps a source that is not a copy.

| Check | Compares | When |
|---|---|---|
| Source text | bundle against the server's emitted text at the pin (`check_localization_sync`, exists) | Every build |
| Impact | a `text-ref`'s claim against the item's derived `impact` and `writes` | From stage 4 |
| CC | sentences that state a cost or limit, each citing the CC paragraph they restate | From stage 1, on the costs list |
| Plain words | `docs/five-year-old-test.md`, internal-word list on en.json | Every card |
| Distance | ids the client holds against ids a node serves | Every release, reported |

An `impact` that disagrees with its own sentence fails the card. This is the check that would have
caught `mesh.announce_requirement`, because the costs would have been derived, not written.

## 5. The contract

Use the CC's shape as it stands; do not design another.

```
text-ref = { id: tstr, text: tstr, ? params: { * tstr => tstr / int }, ? lang: tstr }
```

- `lang` is present only when `text` is not in the requested locale.
- Placeholders are `{name}`. The English carries the same names as `params`, and parity is enforced
  across all 29 languages. Numbers go raw and the client formats them for locale.
- `DisclosureString` is `{id, text}` today. It gains `params` and `lang`, and old clients ignore them.

**Resolution in the client, for one reference:**

1. The node's `text` when it is in the requested locale.
2. Otherwise the bundle's value for `id` in that locale.
3. Otherwise the node's `text`, shown with its `lang` marked.

Never a raw key, and never an invented string.

**Item keys are derived, not listed.** From CC's `manifests/transitions.json`, which gives family and
verb: `item.<family_slug>.<verb>.title`, `.why`, `.option.<value>.title|benefit|downside`. Family
names contain colons, so the slug is a defined function, never a hand edit. Anything about a
particular instance goes in `params`, never in the key. The exact form is proposed in #858.

**Changing a sentence means a new id**, with a stated suffix (`_2`). A changed value under an old id leaves 28 stale translations
stating the old meaning, and the gates point at each other: the client checks the server's released
text while the server checks the released bundle. A new id avoids both. An older client falls back to
correct English, and the server's debt list carries the id until a bundle has it. This is how
`mesh.announce_costs` is landing. Spelling fixes that keep the meaning may stay under the old id.

**Pins.** Client pins server emitters and server pins a client floor, so a new id goes in this order:

1. Server emits the id, adds it to `KNOWN_UNLOCALIZED` and raises `MAX_UNCOVERED` in
   `tests/localization_gate.rs`. Without the raise the ratchet goes red.
2. Server tags.
3. Client pins the tag, translates the id, and tags.
4. Server raises the floor, drops the entry and lowers `MAX_UNCOVERED`. Its gate fails on a stale
   debt entry, so these go together.

## 6. Stages

Each has an exit test. None depends on renaming shipped keys, and most keys never move.

| # | Stage | Exit test | Needs |
|---|---|---|---|
| 1 | **Inventory.** Each key: owner, used or not, surfaces, and for duplicates whether they mean the same | Done: `STRING_INVENTORY.tsv`, kept current by CI. Reading the 27 drifted sentences and the 406 other unclaimed keys is still open | Nothing; read-only |
| 2 | **One pipeline, stated.** CIRISServer and CEWPOS consume the zip; no other translation | Written into both repos' agent guides; CEWPOS's first strings use bundle ids | Their agreement |
| 3 | **Wire shape.** `text-ref` with `params` and `lang` in #858; the client resolves per §5 | One served item renders through the resolver, in a test with a locale gap | CIRISServer#858 |
| 4 | **Pilot.** CSD-088's new items use the item key scheme (§5), agreed in #858. Nothing shipped is renamed | CSD-088 renders from served items in 29 languages | #853, client 0.5.239 at the earliest |
| 5 | **Flip card by card.** Confirmation facts first: their keys go once impact is derived. A card is flipped when its action list is the served queue filtered by object (`formal/event_lifecycle`) | The distance report falls; each card's test passes | #851 |
| 6 | **Retire.** Delete a client string once the minimum server version serves it. Prune dead keys in small batches, each with its translations | The app's bundle holds only §3's list; no key without an owner | Stage 1, the floor |

**Do not merge duplicates on English alone.** "Cancel" and "Unknown" read the same here and can need
different forms in other languages, by gender, number or register. A merge is justified by the same
meaning in the same role. The confirmation facts qualify because they become one primitive; stage 1
marks the rest.

## 7. Risks

| Risk | Handling |
|---|---|
| Two teams each author a key scheme | #858 is the one place; this repo reviews |
| Node and client ship out of order | §5: the resolver falls back, new ids debt-list |
| A bundle grows while the migration runs | New item strings use the scheme from stage 4; stage 1's check reports growth |
| Translation cost | A flipped card's keys are removed and not re-translated; a moved key carries its value and is not re-translated |
| One shared wrong string | §4 |
| A node with a stale locale set | `lang` marks it; the client's step 2 still applies until stage 6 |

## 8. Open questions

| # | Question | Whose |
|---|---|---|
| 1 | Does CEWPOS render text only through `text-ref`, with no held bundle? | CEWPOS |
| 2 | The exact slug function and suffix for derived item keys (§5) | #858 |
| 3 | `prompts.*` (153 keys) feed the agent's language guidance, not a person's screen. Are they in scope? | Stage 1. Proposed: out, kept where they are |
| 4 | Does Android's embedded Python get `ciris-client`, or must its host hand in a path? | Stage 3, on a device |
