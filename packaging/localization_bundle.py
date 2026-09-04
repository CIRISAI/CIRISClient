#!/usr/bin/env python3
"""Publish the localization bundle, and let a consumer check theirs against it.

    python3 packaging/localization_bundle.py --build dist/ciris-client-<v>-localization.zip
    python3 packaging/localization_bundle.py --verify /path/to/their/localization

WHY THIS EXISTS (CIRISClient#34, second occurrence)

The client ships a localization LOADER and no localization DATA. Both mobile
loaders read the HOST application's resources -- `NSBundle.mainBundle` on iOS,
`context.assets.open("localization/<code>.json")` on Android -- and neither
published artifact carries a single string:

    ciris-client-<v>.aar              4 entries, no assets/
    ciris-client-<v>.xcframework.zip  18 entries, zero .json

So every consumer that embeds this client has to vendor 29 bundles themselves,
and nothing we published said so, said which copy was authoritative, or gave
them any way to notice theirs had drifted.

They vendored the nearest plausible copy -- the node's
`ciris_engine/data/localized/en.json`, which is a DIFFERENT bundle serving a
different surface. It has 3,739 keys to our 3,892. The 185-key gap contains
`mobile.login_setup_complete_relogin`, which is why exactly one string on the
iOS login chooser rendered as a raw key while the other 41 on the same screen
resolved: the loader was working perfectly against a bundle that was short.

That is the same failure shape as the missing AAR POM (CIRISClient#31 item 4):
we publish a component whose requirements are invisible, so a mismatch can only
be discovered as a defect on a device. A raw key is never a true thing to show a
user, and "the consumer vendored the wrong file" is a cause we created.

The fix has two halves and this file is both:

  --build   attaches the canonical bundle to the release, so there IS an
            authoritative copy, versioned with the client that expects it.
  --verify  answers "is mine current?" in one command, naming the missing keys
            rather than leaving them to be found one screenshot at a time.

The four in-repo copies are already held byte-identical by
`client/tools/check_localization_sync.py`; this reads the canonical one and adds
nothing to that contract.
"""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

#: The canonical bundle, per check_localization_sync.py. The other three are
#: byte-identical mirrors held that way by CI; reading one keeps a single source.
CANONICAL = ROOT / "client" / "shared" / "src" / "desktopMain" / "resources" / "localization"


def flatten(obj: dict, prefix: str = "") -> set[str]:
    """Dot-notation key set, matching how the runtime resolves a key."""
    out: set[str] = set()
    for k, v in obj.items():
        name = f"{prefix}.{k}" if prefix else k
        if isinstance(v, dict):
            out |= flatten(v, name)
        else:
            out.add(name)
    return out


def keys_of(path: Path) -> set[str]:
    return flatten(json.loads(path.read_text(encoding="utf-8")))


def build(dest: Path) -> int:
    files = sorted(CANONICAL.glob("*.json"))
    if not files:
        raise SystemExit(f"no bundle at {CANONICAL}")
    dest.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as z:
        for f in files:
            # A FLAT `localization/` PREFIX, because that is the path both
            # loaders look under. A consumer should be able to unzip this
            # straight into their assets/ or bundle resources and be done.
            z.write(f, f"localization/{f.name}")
    langs = [f.stem for f in files if f.stem != "manifest"]
    print(f"{dest}  {dest.stat().st_size / 1048576:.1f} MB  "
          f"{len(langs)} languages, {len(keys_of(CANONICAL / 'en.json'))} keys")
    return 0


def verify(theirs: Path) -> int:
    """Compare a consumer's bundle against the canonical one. Missing keys fail."""
    ours_en = CANONICAL / "en.json"
    their_en = theirs / "en.json"
    if not their_en.exists():
        print(f"::error::{their_en} does not exist -- this client's loaders read "
              f"localization/<code>.json from the HOST app's resources, and nothing "
              f"is there. Every string will render as its raw key.")
        return 1

    ours, them = keys_of(ours_en), keys_of(their_en)
    missing = sorted(ours - them)
    extra = sorted(them - ours)

    print(f"  canonical: {len(ours)} keys")
    print(f"  yours:     {len(them)} keys  ({theirs})")

    if extra:
        # NOT a failure. A host may carry its own strings in the same file, and
        # saying "you have keys we do not" as an error would train people to
        # ignore this. Report it, because it is also how you notice you vendored
        # a bundle meant for a different surface.
        print(f"\n  {len(extra)} key(s) present in yours and not ours "
              f"(harmless unless you expected this to be our bundle):")
        for k in extra[:5]:
            print(f"      {k}")
        if len(extra) > 5:
            print(f"      ... and {len(extra) - 5} more")

    if missing:
        print(f"\n::error::{len(missing)} key(s) this client renders are missing from your "
              f"bundle -- each one reaches a user as a raw dotted key (CIRISClient#34)")
        for k in missing[:20]:
            print(f"      {k}")
        if len(missing) > 20:
            print(f"      ... and {len(missing) - 20} more")
        print(f"\n  Take the published bundle instead: the "
              f"`ciris-client-<version>-localization.zip` asset on the matching release, "
              f"unzipped into your app's resources so `localization/<code>.json` resolves.")
        return 1

    print("\n  no missing keys")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--build", type=Path, metavar="ZIP", help="write the publishable bundle")
    g.add_argument("--verify", type=Path, metavar="DIR", help="check a consumer's bundle")
    args = ap.parse_args()
    return build(args.build) if args.build else verify(args.verify)


if __name__ == "__main__":
    sys.exit(main())
