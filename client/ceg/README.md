# `client/ceg/` — the namespace is the design system

`namespace_registry.json` and `namespace_match_vectors.json` are byte copies of
`manifests/` in CIRISAI/CIRISConstitution at tag **`v1.0-rc6`**
(commit `3c3e63fdef844f2f849e43081a8242e31cfaf30d`), re-vendored for
CIRISClient#150. Vendored from the TAG, not a branch: `~/CIRISConstitution`
`main` is already past it.

| pin | value |
|---|---|
| repo / tag / commit | `CIRISAI/CIRISConstitution` / `v1.0-rc6` / `3c3e63fdef844f2f849e43081a8242e31cfaf30d` |
| `cc_version` | `1.0-rc6` |
| families | 158 (rc5 had 116) |
| `_meta.registry_sha256` — **what every CSD `registry_sha256:` pins** (CIRISConstitution#112: sha256 of the grammar, i.e. canonical JSON of `families` + `_meta` minus the two hashes and `cc_version`; a Part 3 wording edit does not move it) | `f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37` |
| `_meta.source_sha256` (hash of `constitution/part_3_the_namespace.md`; the CSD pin until rc5, now informational) | `459d3ef52bc6d021d8bed58af908f75c11fdaf83b5cb841e100040fbe6437ea9` |
| sha256 of `namespace_registry.json` | `5f53f9776604713cb84468811983e67804b9771d61882a6723be61000897056d` |
| sha256 of `namespace_match_vectors.json` (1040 dimension vectors + 39 `row_type_vectors`, `_meta.registry_sha256` = the above) | `c4226dfb769c8939b608398e9b6b4364dde62b8ac40b2cd25363af9bb4622810` |

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
