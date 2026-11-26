# `client/ceg/` — the namespace is the design system

`namespace_registry.json` and `namespace_match_vectors.json` are byte copies of
`manifests/` in CIRISAI/CIRISConstitution at tag **`v1.0-rc22`**
(commit `f4671933f2b22b33d2db868d4654036162104123`), re-vendored from rc6
(`3c3e63f`, CIRISClient#150) on 2026-11-25. Vendored from the TAG, not a branch: `~/CIRISConstitution`
`main` is already past it.

| pin | value |
|---|---|
| repo / tag / commit | `CIRISAI/CIRISConstitution` / `v1.0-rc22` / `f4671933f2b22b33d2db868d4654036162104123` |
| `cc_version` | `1.0-rc22` |
| families | 187 (rc6 had 158; rc22 retires 16 and folds 11 of them into rows with a closed final segment) |
| `_meta.registry_sha256` — **what every CSD `registry_sha256:` pins** (CIRISConstitution#112: sha256 of the grammar, i.e. canonical JSON of `families` + `_meta` minus the two hashes and `cc_version`; a Part 3 wording edit does not move it) | `5f8ff71fd1415c88179b73a8b7d9a047613b22cb81f609909ca9ab9ccc2a8d69` |
| `_meta.source_sha256` (hash of `constitution/part_3_the_namespace.md`; the CSD pin until rc5, now informational) | `b7d87df89be792d6dce014aa22c64b724cafb9657a0eb6b07683782988cc67d7` |
| sha256 of `namespace_registry.json` | `1886df7fb71d91bc00b8faea6ae453b21bc189cf404467bd5e63aa57c01e556a` |
| sha256 of `namespace_match_vectors.json` (1334 dimension vectors, 296 admitted; `_meta.registry_sha256` = the above) | `74b1cef61ddea817febd583ef9492c1b0b66f922853287cedbd82c2abdadd7ee` |

`gen_dimension_table.py` and `packaging/check_csd_v3.py` both RECOMPUTE
`registry_sha256` from the file and refuse a registry that misstates it.

Refresh (from a tag):

```
C=<commit the tag points at>
for f in namespace_registry.json namespace_match_vectors.json; do
  curl -sSfL https://raw.githubusercontent.com/CIRISAI/CIRISConstitution/$C/manifests/$f -o client/ceg/$f
done
python3 client/tools/gen_dimension_table.py --write
```

then update this table, every `FSD/CSD/*.md` `registry_sha256:` pin and
`DimensionsTest`, and re-run `packaging/gates.sh`.

**What the vectors are used for.** `MatchVectorReplayTest` (desktopTest)
replays every vector the reference matcher ADMITS through `Dim.forWire` and
requires the same family. The refusal vectors are not replayed: `forWire`
resolves for rendering and is deliberately lenient; refusing malformed forms
needs the reference matcher ported into `check_csd_v3`, which is
CIRISClient#114.

**rc22, what moved for the client.** `holds_bytes:sha256:{prefix}` is retired: a
file's receipt names `file:{version}` and a holding claim is `custody:{kind}`
(`custody:ack:v1`). `delivery_receipt:{stream_id}` is now `delivery_ack:{stream_id}`.
Rows that differed only in their last segment (`capacity:*`, `detection:*`,
`accord:lifecycle:*`, the credits subject) are one row each with a closed list, so
their old wire strings still resolve, to the folded row. `forWire` now checks a closed
list's values and a segment's pattern, which is how it agrees with every admitted
vector. The three `transparency_log:*` families and `lineage_witness:{stage}` are gone
with no replacement row.

## What is generated from it

`client/tools/gen_dimension_table.py` joins the registry with `glosses.json`
(plain-English label + gloss per family the UI touches) and `renderers.json`
(renderer overrides) and emits
`client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/ceg/Dimensions.kt`:
one `Dim.<name>` per family, `Envelope.<member>` for the five CC 2.1 envelope
members the receipt shows, and the pins above as constants.

**A screen names `Dim.consentKind`; there is no string constructor in UI code.**
A family with no registry row cannot be rendered because it cannot be named —
that is the "nothing renders an unregistered family" gate (CC 3.1.7 R2: an
unregistered family admits under an authority nobody chose).

## Polarity → renderer

The registry's ten polarity strings normalise to `Polarity`; the renderer
defaults by polarity and `renderers.json` overrides by prefix. The generator
refuses an override that contradicts polarity (a `-1 only` family can only be
a `VIOLATION_MARKER`, and only such families can be one; a `positive-only`
family is never a signed score). Discrepancies with the design handoff's
sketch, recorded here so nobody "fixes" them back:

- `non_maleficence:{aspect}` is `signed` in the registry, so it is a
  `SIGNED_SCORE`, not the violation marker the handoff grouped it with.
- `+1.0 only` (rc6: the accord invocations and heartbeat, CC 3.4.1) is
  `PLUS_ONE_ONLY` and defaults to `STATE_PILL`: the row is asserted or absent,
  so it is never a score, never an accrual and never the minus-only marker
  (the generator refuses all three). That default is a judgment, recorded so
  it can be argued with; the polarity string itself is mapped exactly.
- `external_content:*`, `settlement:*`, `delegates_to`, `supersedes` are not
  registry families (the last two are CC 2.4 structural row types). rc6 adds
  `image:*` and `topical_relation:{kind}`. `CONTENT_REFERENCE` today covers
  `holds_bytes`; `SETTLEMENT_RECEIPT` covers `delivery_receipt`.
