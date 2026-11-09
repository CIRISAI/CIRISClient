#!/usr/bin/env python3
"""No English left inside a translation.

    python3 packaging/check_english_fragments.py              # gate
    python3 packaging/check_english_fragments.py --list       # every finding, allowed or not
    python3 packaging/check_english_fragments.py --self-test

A value can pass every structural check (placeholders, length, the judge's batch)
and still carry a clause of English: a sentence the drafter never translated, or a
half-translated one. A reader of that language meets a phrase they may not read, and
nothing else in CI sees it. FSD/TEXT_REFERENCES.md §7 asked for this check.

WHAT COUNTS. A run of consecutive Latin-script words in a translation that also
appears, word for word and in order, in the English value of the SAME key, where
every word of the run starts lowercase. Requiring the copy to come from that key's
English is what separates a leftover from a loanword; requiring lowercase leaves out
names ("CIRIS ID", "Secure Enclave", "Home Assistant"), which the glossaries keep in
English on purpose. The run must be 2 words in a language not written in Latin script
and 3 words in one that is, because short Latin-script runs collide with the
language's own words ("a", "on", "de").

WHAT IS LEFT OUT. `prompts.*` feeds the agent's language guidance, not a screen
(FSD/TEXT_REFERENCES.md §8 q3). Placeholders, URLs, `code` and identifier-shaped
tokens (any containing `_`, `=` or `/`, such as `include_balance=true`) are removed first.

KEPT TERMS. A glossary row whose rendering keeps the English ("Wise Authority" in
Hindi) is a decision, not a leftover: those terms are removed from the value first.

ALLOWED. A finding that is right as it stands goes in
localization/english_fragments_allow.tsv with the reason. A row that no longer
matches anything fails too, so the list cannot outlive what it excuses.

Stdlib only, like every check in packaging/ (AGENTS.md).
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUNDLE = ROOT / "client/shared/src/desktopMain/resources/localization"
ALLOW = ROOT / "localization/english_fragments_allow.tsv"
SKIP_PREFIXES = ("prompts.",)
sys.path.insert(0, str(ROOT / "localization"))

_STRIP = re.compile(r"\{[^}]*\}|https?://\S+|`[^`]*`|\S*[_=/]\S*")
_WORD = re.compile(r"[A-Za-z][A-Za-z']*")
_RUN = re.compile(r"[A-Za-z][A-Za-z' ]*[A-Za-z]")


def flatten(d: dict, prefix: str = ""):
    for k, v in d.items():
        key = f"{prefix}.{k}" if prefix else k
        if isinstance(v, dict):
            yield from flatten(v, key)
        elif isinstance(v, str):
            yield key, v


def is_latin_script(values) -> bool:
    letters = [c for v in values for c in _STRIP.sub(" ", v) if c.isalpha()]
    return bool(letters) and sum(c.isascii() for c in letters) / len(letters) >= 0.5


def _ngrams(words: list[str], n: int):
    return [tuple(words[i:i + n]) for i in range(len(words) - n + 1)]


def fragments(english: str, value: str, n: int) -> list[str]:
    """The runs of `value` that copy at least `n` lowercase words of `english` in order."""
    source = {tuple(w.lower() for w in g) for g in _ngrams(_WORD.findall(_STRIP.sub(" ", english)), n)}
    found = []
    for run in _RUN.findall(_STRIP.sub(" ", value)):
        words = _WORD.findall(run)
        for g in _ngrams(words, n):
            if (all(w[0].islower() and w.isalpha() and len(w) > 1 for w in g)
                    and any(len(w) > 3 for w in g)
                    and tuple(w.lower() for w in g) in source):
                found.append(run.strip())
                break
    return found


def kept_terms(lang: str) -> list[str]:
    """Glossary terms this language keeps in English, longest first."""
    import glossary  # localization/glossary.py, stdlib only
    kept = [e for e, t in glossary.terms(lang).items() if e.lower() in t.lower() and _WORD.search(e)]
    return sorted(kept, key=len, reverse=True)


def scan(en: dict[str, str], locales: dict[str, dict[str, str]],
         kept: dict[str, list[str]] | None = None) -> list[tuple[str, str, str]]:
    out = []
    for lang, values in sorted(locales.items()):
        n = 3 if is_latin_script(values.values()) else 2
        terms = (kept or {}).get(lang) if kept is not None else kept_terms(lang)
        strip = re.compile("|".join(r"\b" + re.escape(t) + r"\b" for t in terms), re.I) if terms else None
        for key, value in values.items():
            english = en.get(key)
            if not english or value == english or key.startswith(SKIP_PREFIXES):
                continue
            if strip:
                value = strip.sub(" ", value)
            for frag in fragments(english, value, n):
                out.append((lang, key, frag))
    return out


def load_bundle(directory: Path) -> tuple[dict[str, str], dict[str, dict[str, str]]]:
    files = sorted(p for p in directory.glob("*.json") if re.fullmatch(r"[a-z]{2,3}", p.stem))
    if not files:
        raise SystemExit(f"[FAIL] no locale files under {directory}")
    data = {p.stem: dict(flatten(json.loads(p.read_text(encoding="utf-8")))) for p in files}
    en = data.pop("en", None)
    if not en:
        raise SystemExit(f"[FAIL] no en.json under {directory}")
    return en, data


def load_allow(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        return []
    rows = [r for r in csv.DictReader(
        (l for l in path.read_text(encoding="utf-8").splitlines() if l and not l.startswith("#")),
        delimiter="\t")]
    for r in rows:
        if not (r.get("reason") or "").strip():
            raise SystemExit(f"[FAIL] {path.name}: {r.get('lang')} {r.get('key')} has no reason")
    return rows


def judge(findings, allow) -> tuple[list, list]:
    allowed = {(r["lang"], r["key"], r["fragment"]) for r in allow}
    unexcused = [f for f in findings if f not in allowed]
    stale = sorted(allowed - set(findings))
    return unexcused, stale


def self_test() -> int:
    en = {
        "a.rotated": "The server key has rotated. Please sign in again.",
        "a.name": "Your CIRIS ID is ready",
        "a.short": "Not available",
        "a.place": "Hello {name}, see https://ciris.ai/x for more",
        "a.tool": "Run get_statement include_balance=true include_history=true now",
        "prompts.x": "keep this english text here",
    }
    locales = {
        "fr": {
            "a.rotated": "The server key has rotated. Veuillez vous reconnecter.",   # planted
            "a.name": "Votre CIRIS ID est prêt",                                      # a name
            "a.short": "Non disponible",
            "a.place": "Bonjour {name}, voir https://ciris.ai/x pour plus",
            "a.tool": "Lancez get_statement include_balance=true include_history=true maintenant",
            "prompts.x": "keep this english text here mais",
        },
        "th": {
            "a.rotated": "คีย์เซิร์ฟเวอร์ถูกเปลี่ยน",
            "a.name": "CIRIS ID ของคุณพร้อมแล้ว",
            "a.short": "not available แล้ว",                                       # planted, 2 words
            "a.place": "สวัสดี {name} ดู https://ciris.ai/x",
            "a.tool": "เรียก get_statement include_balance=true include_history=true",
            "prompts.x": "ภาษาไทย",
        },
    }
    findings = scan(en, locales, kept={})
    kept_run = scan(en, {"th": {"a.short": "not available แล้ว"}}, kept={"th": ["not available"]})
    checks = [
        ("a term the glossary keeps in English is not a finding", kept_run == []),
        ("an English sentence left in a Latin-script language is found",
         ("fr", "a.rotated", "The server key has rotated") in findings),
        ("two English words in a non-Latin script are found", ("th", "a.short", "not available") in findings),
        ("a capitalised name is not a finding", not any(f[1] == "a.name" for f in findings)),
        ("placeholders and URLs are not findings", not any(f[1] == "a.place" for f in findings)),
        ("identifiers and arguments are not findings", not any(f[1] == "a.tool" for f in findings)),
        ("prompts.* is out of scope", not any(f[1].startswith("prompts.") for f in findings)),
        ("an allow row excuses its finding",
         judge(findings, [{"lang": "th", "key": "a.short", "fragment": "not available", "reason": "r"}])[0]
         == [f for f in findings if f[0] == "fr"]),
        ("a stale allow row is reported",
         judge(findings, [{"lang": "fr", "key": "a.name", "fragment": "x", "reason": "r"}])[1]
         == [("fr", "a.name", "x")]),
    ]
    bad = [name for name, ok in checks if not ok]
    for name in bad:
        print(f"[FAIL] self-test: {name}")
    if bad:
        print(f"       findings were: {findings}")
        return 1
    print(f"[OK] self-test: {len(checks)} planted cases")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--bundle", type=Path, default=BUNDLE)
    ap.add_argument("--allow", type=Path, default=ALLOW)
    ap.add_argument("--list", action="store_true", help="print every finding and exit 0")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    en, locales = load_bundle(args.bundle)
    findings = scan(en, locales)
    if args.list:
        for lang, key, frag in findings:
            print(f"{lang}\t{key}\t{frag}")
        return 0
    unexcused, stale = judge(findings, load_allow(args.allow))
    for lang, key, frag in unexcused:
        print(f"[FAIL] {lang} {key}: English left in the translation: \"{frag}\"")
    for lang, key, frag in stale:
        print(f"[FAIL] {args.allow.name}: {lang} {key} \"{frag}\" no longer matches; remove the row")
    if unexcused or stale:
        print(f"[FAIL] {len(unexcused)} English fragment(s), {len(stale)} stale allow row(s). "
              "Re-translate with localization/localize.py --lane translate --overwrite --keys KEY --lang LANG, "
              "or, if the English is right as it stands, add a row with the reason.")
        return 1
    print(f"[OK] no English left in {len(locales)} translations ({len(findings)} allowed)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
