"""No artifact leaves a with-AI leg holding the provider key.

    python3 -m testing.gate.secret_scan --env ANTHROPIC_API_KEY shots/ reports/ *.log

THE KEY IS TYPED INTO THE PRODUCT. The with-AI legs drive the setup wizard's
AI step with a real `ANTHROPIC_API_KEY`, so the key passes through the client,
the agent, and every log either of them writes — and then the leg uploads its
logs, reports and screenshots as an artifact anyone with read access can
download. GitHub masks a secret in the JOB LOG; it masks nothing inside an
uploaded file.

So before every upload this runs over everything the upload would carry. A file
holding the key — verbatim, URL-encoded or base64'd — is OVERWRITTEN with a
note naming what was removed (never the key), and the step fails, naming the
file: a leak is a defect to fix at its source, not a thing to quietly scrub.

WHAT IT CANNOT SEE. Pixels. A screenshot of a text field showing the key would
pass this scan; the harness never presses `btn_toggle_api_key`, the field draws
`PasswordVisualTransformation`, and the client marks the sink sensitive so
`/tree` never echoes it (SetupApiKeySinkTest). Say so, rather than claim
screenshots are scanned.

Stdlib only.
"""

from __future__ import annotations

import argparse
import base64
import os
import sys
import urllib.parse
from pathlib import Path
from typing import Iterable, List, Sequence

#: Shorter than this is not a provider key, and scanning for it would match
#: ordinary text and destroy evidence for nothing.
MIN_SECRET = 16

REDACTED = "[REDACTED: a provider API key was found in this file and removed by testing.gate.secret_scan]\n"


def forms(secret: str) -> List[bytes]:
    """The spellings a key travels in: verbatim, URL-encoded, base64 (of the
    key alone — a basic-auth header or a JSON body is still caught by the
    verbatim form)."""
    raw = secret.encode("utf-8")
    out = {raw, urllib.parse.quote(secret, safe="").encode("ascii"),
           base64.b64encode(raw), base64.urlsafe_b64encode(raw)}
    return [f for f in out if len(f) >= MIN_SECRET]


def redact(text: str, secrets: Iterable[str]) -> str:
    """`text` with every spelling of every secret replaced — for messages the
    harness itself composes (error details that quote a screen's text)."""
    for s in secrets:
        if s and len(s) >= MIN_SECRET:
            for f in forms(s):
                text = text.replace(f.decode("utf-8", "replace"), "[redacted]")
    return text


def files_under(paths: Sequence[Path]) -> Iterable[Path]:
    for p in paths:
        if p.is_file():
            yield p
        elif p.is_dir():
            yield from (f for f in sorted(p.rglob("*")) if f.is_file())


def scan(paths: Sequence[Path], secret: str) -> List[Path]:
    """Every file holding the secret, each overwritten with REDACTED."""
    needles = forms(secret)
    hit: List[Path] = []
    for f in files_under(paths):
        try:
            data = f.read_bytes()
        except OSError:
            continue
        if any(n in data for n in needles):
            f.write_text(REDACTED, encoding="utf-8")
            hit.append(f)
    return hit


def main(argv: Sequence[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--env", action="append", required=True,
                    help="the environment variable holding a secret (repeatable)")
    ap.add_argument("paths", nargs="*", type=Path)
    args = ap.parse_args(argv)

    secrets = [(name, os.environ.get(name, "")) for name in args.env]
    leaks = 0
    for name, value in secrets:
        if len(value) < MIN_SECRET:
            print(f"  {name}: not set (or too short to be a key) — nothing to scan for")
            continue
        hit = scan([p for p in args.paths if p.exists()], value)
        for f in hit:
            print(f"::error::{f} held {name}; its contents were replaced — find what wrote it")
        leaks += len(hit)
        n = sum(1 for _ in files_under([p for p in args.paths if p.exists()]))
        print(f"  {name}: scanned {n} file(s), {len(hit)} held it")
    return 1 if leaks else 0


if __name__ == "__main__":
    sys.exit(main())
