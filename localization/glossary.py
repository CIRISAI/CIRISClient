#!/usr/bin/env python3
"""The CIRIS glossaries: canonical terminology, per language, as pipeline input.

29 files at ``localization/glossaries/{code}_glossary.md`` — 28 languages plus
``TEMPLATE``. Each is a set of markdown tables whose first column is English
and whose second is the target language, followed (in 24 of them) by a
``## Cultural Considerations`` block of prose that is worth more than the
tables: it is where "address users with the formal እርስዎ, not አንተ/አንቺ" lives,
and that is the kind of thing a translator gets wrong once and a glossary
prevents forever.

WHY THIS IS A MODULE AND NOT A PROMPT STRING. Glossary-first is the one
practice CIRISAgent's LESSONS_LEARNED.md credits unreservedly: terminology is
frozen BEFORE translation so ACCORD, the guide, the UI strings and the DMA
prompts cannot disagree about what "Wise Authority" is. Injecting the glossary
into every request is how a pipeline keeps that promise without a human in the
loop, and injecting only the RELEVANT terms is how it keeps doing so without
spending the context window on 3,045 pairs.
"""

from __future__ import annotations

import re
from functools import lru_cache
from pathlib import Path
from typing import Dict, List, Tuple

GLOSSARY_DIR = Path(__file__).resolve().parent / "glossaries"

# Prose sections worth carrying into a request, in the order they are shown.
# `Cultural Considerations` owns its `###` children (Formality Level,
# Honorifics, Script Considerations…) — the parser must not treat those as
# section boundaries, or the block silently reads as empty. It did, once.
PROSE_SECTIONS: Tuple[str, ...] = (
    "Cultural Considerations",
    "Notes for Translators",
    "Formality Level",
    "Script Considerations",
    "Honorifics",
)

_H2 = re.compile(r"^##(?!#)\s+(.*)$")
_WORD = re.compile(r"[A-Za-z][A-Za-z0-9_'-]*")


@lru_cache(maxsize=64)
def _parse(code: str) -> Tuple[Tuple[Tuple[str, str], ...], Tuple[Tuple[str, str], ...]]:
    """(term pairs, prose sections) for one language. Cached: 29 files, many calls."""
    path = GLOSSARY_DIR / f"{code}_glossary.md"
    if not path.is_file():
        return (), ()
    terms: Dict[str, str] = {}
    prose: Dict[str, List[str]] = {}
    section = None
    for line in path.read_text(encoding="utf-8").splitlines():
        head = _H2.match(line)
        if head:
            section = head.group(1).strip()
            prose.setdefault(section, [])
            continue
        if line.startswith("|"):
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if len(cells) < 2 or not cells[0] or not cells[1]:
                continue
            if set(cells[0]) <= set("-: ") or set(cells[1]) <= set("-: "):
                continue  # the table's rule row
            if cells[0].lower() == "english":
                continue  # its header
            # setdefault, not assignment: the first table to define a term wins,
            # and the tables are ordered most-canonical-first (Core Action Verbs,
            # Core Concepts, then the feature-specific ones bolted on later).
            terms.setdefault(cells[0], cells[1])
        elif section is not None:
            prose[section].append(line)
    kept = tuple(
        (name, "\n".join(prose[name]).strip())
        for name in PROSE_SECTIONS
        if prose.get(name) and "\n".join(prose[name]).strip()
    )
    return tuple(terms.items()), kept


def terms(code: str) -> Dict[str, str]:
    """English -> target, for every canonical term this language pins."""
    return dict(_parse(code)[0])


def guidance(code: str) -> List[Tuple[str, str]]:
    """(section title, prose) for the language-specific rules worth sending."""
    return list(_parse(code)[1])


def relevant(code: str, texts: List[str], *, limit: int = 60) -> Dict[str, str]:
    """The glossary terms that actually occur in `texts`.

    Whole-word, case-insensitive, longest-first so "Wise Authority" is matched
    before "Authority". A request carrying 3,045 pairs is a request that has
    spent its attention budget before it reads the strings; a request carrying
    the eleven that appear is one the model can actually obey.
    """
    all_terms = terms(code)
    if not all_terms:
        return {}
    hay = " \n ".join(texts).lower()
    hits: List[Tuple[int, str]] = []
    for english in all_terms:
        probe = english.lower()
        if not probe:
            continue
        if _WORD.fullmatch(english):
            if re.search(rf"\b{re.escape(probe)}\b", hay):
                hits.append((len(probe), english))
        elif probe in hay:
            hits.append((len(probe), english))
    hits.sort(key=lambda p: (-p[0], p[1]))
    return {e: all_terms[e] for _, e in hits[:limit]}


class GlossaryMissing(RuntimeError):
    """No usable glossary for a language the pipeline was asked to translate.

    Not a warning. Every request is documented as carrying the canonical
    terminology, and a language whose glossary is missing, misnamed, or parses
    to nothing would be drafted WITHOUT it while the structural guard — which
    has no opinion about word choice — reported green. That is the shape
    AGENTS.md forbids: a parser that finds nothing where the construct plainly
    exists must fail loudly, not pass.
    """


def require(codes: List[str]) -> None:
    """Refuse before the first model call if any language has no glossary.

    Checked up front, for all of them, rather than per request: discovering it
    on language 19 of 28 means the first 18 were already drafted and paid for.
    """
    empty = sorted(c for c in codes if not terms(c))
    if empty:
        raise GlossaryMissing(
            "no canonical terminology for: " + ", ".join(empty) + ". Expected "
            + ", ".join(f"{GLOSSARY_DIR.name}/{c}_glossary.md" for c in empty)
            + " to exist and to contain at least one English|target table row. "
            "Every request is supposed to carry the glossary; drafting without "
            "one produces terminology drift that no structural check can see."
        )


def block(code: str, texts: List[str]) -> str:
    """The glossary payload for one request, or "" when nothing applies."""
    hits = relevant(code, texts)
    out: List[str] = []
    if hits:
        out.append(
            "CANONICAL TERMINOLOGY (from the CIRIS glossary for this language — "
            "these are decided, not suggestions; use them exactly):"
        )
        out += [f"  {e}  ->  {t}" for e, t in hits.items()]
    for title, text in guidance(code):
        out.append(f"\n{title.upper()} (this language's standing rules):\n{text}")
    return "\n".join(out)


def coverage() -> Dict[str, int]:
    """code -> term count. Used by the CLI's --glossary-report."""
    return {
        p.name.split("_")[0]: len(terms(p.name.split("_")[0]))
        for p in sorted(GLOSSARY_DIR.glob("*_glossary.md"))
    }


if __name__ == "__main__":  # a quick look at what the pipeline will send
    import sys

    code = sys.argv[1] if len(sys.argv) > 1 else "yo"
    probe = sys.argv[2:] or ["Defer to the Wise Authority", "Observe the node"]
    print(f"# {code}: {len(terms(code))} terms, {len(guidance(code))} prose section(s)")
    print(block(code, probe))
