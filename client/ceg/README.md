# `client/ceg/` — the namespace is the design system

`namespace_registry.json` is a byte copy of
`CIRISAI/CIRISConstitution@44ae7b2ad34bae2010b5e7d92a69f0de010d6451:manifests/namespace_registry.json`
(rc5 tip on 2026-09-19, the commit that closed CIRISConstitution#104).

| pin | value |
|---|---|
| `cc_version` | `1.0-rc5` |
| families | 116 |
| `_meta.source_sha256` (the CSD `registry_sha256` convention — hash of `constitution/part_3_the_namespace.md`) | `95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839` |
| sha256 of this JSON file | `90b30c61e71fd158291acdac60468f88e4899a22ccc2471243be93670ad01ad3` |

Refresh:

```
curl -sSL https://raw.githubusercontent.com/CIRISAI/CIRISConstitution/<commit>/manifests/namespace_registry.json -o client/ceg/namespace_registry.json
python3 client/tools/gen_dimension_table.py --write
```

then update this table, `FSD/CSD/*.md` `registry_sha256:` pins, and re-run `packaging/gates.sh`.

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

The registry's nine polarity strings normalise to `Polarity`; the renderer
defaults by polarity and `renderers.json` overrides by prefix. The generator
refuses an override that contradicts polarity (a `-1 only` family can only be
a `VIOLATION_MARKER`, and only such families can be one; a `positive-only`
family is never a signed score). Discrepancies with the design handoff's
sketch, recorded here so nobody "fixes" them back:

- `non_maleficence:{aspect}` is `signed` in the registry, so it is a
  `SIGNED_SCORE`, not the violation marker the handoff grouped it with.
- `image:*`, `external_content:*`, `settlement:*`, `topical_relation:*`,
  `delegates_to`, `supersedes` are not registry families (the last three are
  envelope relations). `CONTENT_REFERENCE` today covers `holds_bytes`;
  `SETTLEMENT_RECEIPT` covers `delivery_receipt`. The enum members exist so
  the rows can land when the registry admits them.
