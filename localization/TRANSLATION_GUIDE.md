# Translating CIRIS strings by hand

For the strings the pipeline cannot render acceptably. It is not a fallback for
"the model was slow" — it is what happens when the **evaluate lane rejects every
rung of the ladder**, which so far has meant one thing: a low-resource language
where no reachable model produced text a reviewer would accept.

That is not a rare edge. CIRIS ranks languages by **inverse model support**
(Meta-Goal M-1: need, not market size), so the languages at the top of the
priority list are exactly the ones models are worst at. Amharic, Hausa, Burmese
and Yoruba are Tier 0. Expect to be here.

---

## 1. The corpus is the reference, not a dictionary

**~3,853 keys are already translated in all 29 languages.** That is a parallel
corpus of this product's own vocabulary, written for these screens, and it beats
any external glossary for one reason: it is what users are already reading. A
new string that disagrees with it is wrong even when it is defensible.

Read it with a concordance rather than by guessing:

```python
# how does this product already say "key" in Yoruba?
import sys, re, pathlib
sys.path.insert(0, "client/tools")
import check_localization_sync as g

CANON = pathlib.Path(g.CANONICAL_BUNDLE)
EN  = g.flat_values(g.load_json(CANON / "en.json"))
TGT = g.flat_values(g.load_json(CANON / "yo.json"))

rx = re.compile(r"\bkey\w*\b", re.I)
for k, v in EN.items():
    if len(str(v)) < 95 and rx.search(str(v)) and TGT.get(k):
        print(f"{v}\n  -> {TGT[k]}\n")
```

Sort by source length and read the SHORT strings first: `Key`, `Key ID`,
`Revoke`, `Delete` are label-length and unambiguous, so they show the term
without a sentence's worth of noise around it.

### What that produced for the four Tier 0 languages

| concept | `yo` | `ha` | `am` | `my` |
|---|---|---|---|---|
| key | `kọ́kọ́rọ́` | `maɓalli` | `ቁልፍ` | `key` / `သော့` |
| signing | `ìfọwọ́sí` | `sa hannu` | `መፈረም` | `လက်မှတ်ထိုးခြင်း` |
| identity | `ìdánimọ̀` | `shaida` ¹ | `ማንነት` | `အထောက်အထား` |
| peer | `ẹlẹ́gbẹ́` | `abokai` | `አጋሮች` | `peer` |
| refuse | `kọ̀` | `ƙi` | `ተከልክሏል` | `ငြင်းပယ်` |
| attestation | `ẹ̀rí` | `tabbatarwa` | `አረጋገጥ` | `attestation` |
| register | `forúkọsílẹ̀` | `rajista` | `መመዝገብ` | `မှတ်ပုံတင်` |
| replace | `rọ́pò` | `maye gurbi` | `ተካ` | `အစားထိုး` |
| node | `node` | `kumburi` | `ኖድ` | `node` |

¹ **The one place the corpus was overruled.** It renders *Identity* as `Asali`,
and the reviewer's finding was that `asali` means *origin / essence*, not a
credential. Confirmed against the language: `shaida` (< Arabic *shahāda*,
"testimony") is the credential sense — `katin shaida` is an identity **card**,
`takardar shaida` is a certificate. A federation identity is a signed credential,
so `shaida` is right and `Asali` is the imprecise incumbent.

**Overruling the corpus is a decision that gets written down, here.** It is not
a licence to prefer your own taste; it took an independent reviewer's finding
plus a checkable etymology to justify one word.

---

## 2. Read the MQM findings before you write anything

The evaluate lane says exactly what it rejected and why. Download the report:

```bash
gh run download <run-id> -n i18n-report-translate
```

Every finding carries `category`, `severity`, `span`, `note`, `suggestion`. The
notes are specific and usually correct — treat them as the brief.

**Findings cluster two ways, and the difference decides who fixes it:**

- **Many languages, one key** → the ENGLISH is broken. Fix the source; do not
  hand-translate around it. When `identity_repair_needs` drew 20 findings across
  11 languages, the cause was `"Minting a fresh one"` — `one` had no antecedent,
  and Persian, Marathi and Urdu each resolved it *differently and wrongly*.
  Divergent wrong guesses are the signature of an ambiguous source.
- **One language, several keys** → that language needs a hand. This guide.

---

## 3. The failure modes that actually occur

Drawn from three production runs, in order of how often they bit:

**Direction reversal.** `"every peer it reaches refuses it"` became *"it refuses
every peer that reaches it"* in Yoruba. Two participants, one verb — check who
is doing what to whom, in the target's own word order.

**Dangling referent.** `"both of its signing keys"` needs *both* to attach to
*keys*. Languages that mark number or repeat the head noun will strand it if you
translate word-for-word.

**Half-loan compounds.** Amharic's reviewer rejected `"registration envelope"`
rendered as one translated word plus one loan: *"unnatural half-loan,
half-translation … word-salad"*. Either take the whole term as a loan or express
the whole concept natively. Here the metaphor was dropped entirely — ` የተፈረመበት
የምዝገባ መዝገብ`, "the registration record it was signed over" — because the crypto
sense of *envelope* has no Amharic idiom and the postal word would mislead.

**Register drift.** The glossaries' *Cultural Considerations* are binding:
Amharic formal `እርስዎ` (never `አንተ/አንቺ`); Bengali `আপনি`; Yoruba respectful forms.

**Orthography.** Yoruba tone marks and sub-dots carry meaning — `okun` is beach,
rope or strength depending on marks, so unmarked text is ambiguous, not merely
informal. Every vowel gets its mark. Amharic uses Ethiopian punctuation: `።`
full stop, `፣` comma. Hausa needs its hooked letters `ɓ ɗ ƙ`.

**"Minted".** A term of art. `am` read it as *choosing*, `my` as *creating*.
Prefer the language's word for **issued** where one exists.

---

## 4. Writing them in

Never hand-edit the JSON. Four mirrors must stay byte-identical and key order
must match `en.json`, so use the pipeline's own writer:

```python
import sys, json, pathlib
sys.path.insert(0, "client/tools"); sys.path.insert(0, "localization")
import check_localization_sync as g, localize as L

canon = pathlib.Path(g.CANONICAL_BUNDLE)
en = json.loads((canon / "en.json").read_text(encoding="utf-8"))
values = {"mobile.some_key": "…"}

bad = L.structural_problems(list(values), g.flat_values(en), values)
assert not bad, bad                      # placeholders, before anything is written
L.insert("yo", values, en, overwrite=True)   # all four mirrors, correct positions
```

Then the gates:

```bash
python3 client/tools/check_localization_sync.py --server-src ~/CIRISServer
python3 packaging/check_vendoring.py --print   # re-record the digest in the same commit
```

Hand-written languages drop out of the lane's work automatically — it is
diff-driven, so a key that exists is a key it does not translate. Do these
first, then let the lane fill the rest.

---

## 5. What you may and may not claim

Unchanged from `localization/CLAUDE.md`, and it applies to hand work exactly as
it applies to the pipeline:

**Guaranteed** — terminology consistency, structural completeness, functional
correctness, meaning preservation, placeholder integrity.

**NOT guaranteed** — native fluency, dialect coverage, cultural adaptation of
metaphor, legal review.

Everything stays `status: draft` / `review_status: needs_native_review` until a
speaker signs off. **Hand-translating does not upgrade that status.** It buys
correct terminology, a resolvable referent and the right script conventions —
which is what the reviewer rejected the machine output for — and it does not buy
a native ear. Say so.

---

## 6. Sources

- The shipped corpus, `client/shared/src/desktopMain/resources/localization/*.json`
  — primary, and the only one that reflects what users read today.
- `localization/glossaries/{code}_glossary.md` — canonical terms and each
  language's standing rules. Deprecated rows (`[DEPRECATED]`) are history, not
  authority: see CIRISClient#5, where one row answered for two concepts and
  produced four strategies across 28 languages.
- MQM findings from the evaluate lane, per run.
- [ISO 704](https://www.iso.org/obp/ui/#iso:std:iso:704:ed-3:en) — one concept,
  one entry; two concepts sharing a designation get two.
- [Microsoft Language Portal](https://www.microsoft.com/en-us/language/Search) —
  covers am/ha/yo; useful for OS-level UI vocabulary the corpus lacks.
- [ANLoc](https://africanlocalisation.net/terminology) — FOSS ICT term lists for
  African languages.
