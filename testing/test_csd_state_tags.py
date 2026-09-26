"""Every state tag a CSD calls real must exist in the client (CSD/3 §2.2).

A CSD's `csd:states` block names the tag for each of a card's states —
`populated`, `empty`, `loading`, `error` — and a flow asserts a state by that
tag. A tag written WITHOUT the `proposed:` prefix is a claim that the client
renders it today. This test holds the claim to the source: if a screen drops or
renames a tag the CSD relies on, the CSD and the code have drifted and a flow
written against the CSD would go red for a reason nobody can see from the card.

`proposed:` tags are skipped by design — they are the CSD saying "not yet".
When one lands in the code, un-propose it in the CSD and this test starts
holding it.

WHAT COUNTS AS "EXISTS"
-----------------------
A string-literal grep cannot tell a test tag from a localization key
(`graph_nodes` is both shapes), so a tag counts only where the source USES it as
a tag:

  * the first argument of `testable` / `testableClickable` / `testableWithHandler`
    (and of a bare Compose `testTag`)
  * a `tag =` / `testTag =` / `testTagName =` argument, and a `const val` in a
    `*Tags` object
  * a positional argument in the slot of a helper's tag-named parameter:
    `StatRow("Total", n, "row_storage_total_nodes")` where the helper is
    `fun StatRow(label: String, value: String, testTag: String)`
  * `tagPrefix = "x"` on a `ReadFailureBlock`, which renders `x_error` and
    `x_not_on_this_node` (ReadFailure.kt)
  * a template (`"chat_msg_${id}"`) matches any tag it can produce, provided it
    has a literal head — `"${prefix}_error"` would match everything and is
    handled by the tagPrefix rule instead.

WHERE THE CSDs COME FROM
------------------------
`$CSD_REF` (default `origin/feat/csds-existing-cards`, where the cards are being
written) read with `git show`; when that ref is not present — a shallow CI
checkout — the CSDs committed under `FSD/CSD/`.
"""

from __future__ import annotations

import os
import pathlib
import re
import subprocess

import pytest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "client" / "shared" / "src"
CSD_DIR = "FSD/CSD"
CSD_REF = os.environ.get("CSD_REF", "origin/feat/csds-existing-cards")

#: Real-named tags whose screens are being edited elsewhere. Each entry says
#: why; an entry whose tag has appeared in the source fails the test, so the
#: list can only shrink.
KNOWN_ABSENT: dict[str, str] = {}

STATES_BLOCK = re.compile(r"```yaml csd:states\n(.*?)```", re.S)
#: `tag: "x"` or `tag: x` (unquoted, ended by `,` or `}`).
STATE_TAG = re.compile(r'\btag:\s*(?:"([^"]+)"|([^\s,}"]+))')

KT_STRING = r'"((?:[^"\\]|\\.)*)"'
TAG_CALL = re.compile(r"\.(?:testable(?:Clickable|WithHandler)?|testTag)\(\s*(?:" + KT_STRING + r"|([A-Za-z_][\w.]*))")
TAG_ARG = re.compile(r"\b(?:tag|testTag|testTagName)\s*=\s*" + KT_STRING)
TAG_PARAM = re.compile(r"^(?:tag|testTag|testTagName)$")
FUN_DECL = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?([A-Z]\w*)\s*\(")
TAG_PREFIX = re.compile(r"\btagPrefix\s*=\s*" + KT_STRING)
CONST_VAL = re.compile(r"\bconst\s+val\s+([A-Z_][A-Z0-9_]*)\s*=\s*" + KT_STRING)
TAGS_OBJECT = re.compile(r"\bobject\s+\w*Tags\b[^{]*\{(.*?)\n\}", re.S)
TEMPLATE_PART = re.compile(r"\$\{[^}]*\}|\$[A-Za-z_]\w*")

#: What `ReadFailureBlock(tagPrefix = p)` renders (ReadFailure.kt).
READ_FAILURE_SUFFIXES = ("_error", "_not_on_this_node")


def _git(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True)


def load_csds() -> dict[str, str]:
    """name -> text, from `CSD_REF` when it resolves, else the working tree."""
    if _git("rev-parse", "--verify", "--quiet", f"{CSD_REF}^{{commit}}").returncode == 0:
        listing = _git("ls-tree", "--name-only", f"{CSD_REF}:{CSD_DIR}").stdout.split()
        return {
            name: _git("show", f"{CSD_REF}:{CSD_DIR}/{name}").stdout
            for name in listing
            if name.startswith("CSD-") and name.endswith(".md")
        }
    return {p.name: p.read_text() for p in sorted((ROOT / CSD_DIR).glob("CSD-*.md"))}


def real_state_tags(text: str) -> list[tuple[str, str]]:
    """(state, tag) for every non-`proposed:` tag in a CSD's csd:states blocks."""
    found = []
    for block in STATES_BLOCK.findall(text):
        for line in block.splitlines():
            state = line.split(":", 1)[0].strip()
            for quoted, bare in STATE_TAG.findall(line):
                tag = quoted or bare
                if not tag.startswith("proposed:"):
                    found.append((state, tag))
    return found


def _source_files(src: pathlib.Path) -> list[pathlib.Path]:
    return [p for p in src.rglob("*.kt") if "Test" not in p.parts[len(src.parts)]]


def _args(text: str, open_paren: int) -> list[str]:
    """Top-level comma-separated arguments of the call whose `(` is at
    `open_paren`. String- and nesting-aware."""
    depth, i, start, out = 0, open_paren, open_paren + 1, []
    while i < len(text):
        c = text[i]
        if c == '"':
            i += 1
            while i < len(text) and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
        elif c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                out.append(text[start:i])
                return [a.strip() for a in out if a.strip()]
        elif c == "," and depth == 1:
            out.append(text[start:i])
            start = i + 1
        i += 1
    return []


def _tag_param_slots(texts: list[str]) -> dict[str, int]:
    """Composable helper name -> index of its tag-named parameter."""
    slots: dict[str, int] = {}
    for text in texts:
        for m in FUN_DECL.finditer(text):
            for index, param in enumerate(_args(text, m.end() - 1)):
                head = param.split(":", 1)[0].split()
                if ":" in param and head and TAG_PARAM.match(head[-1]):
                    slots[m.group(1)] = index
    return slots


def source_tags(src: pathlib.Path = SRC) -> tuple[set[str], list[re.Pattern]]:
    """(literal tags, template patterns) the client source uses AS tags."""
    literals: set[str] = set()
    templates: list[re.Pattern] = []
    consts: dict[str, str] = {}
    const_refs: set[str] = set()

    def add(value: str) -> None:
        if not TEMPLATE_PART.search(value):
            literals.add(value)
            return
        head = TEMPLATE_PART.split(value, 1)[0]
        if len(head) < 3:
            return  # "${prefix}_error" — see the tagPrefix rule
        pattern = "".join(
            ".+" if TEMPLATE_PART.fullmatch(part) else re.escape(part)
            for part in re.split(f"({TEMPLATE_PART.pattern})", value)
            if part
        )
        templates.append(re.compile(pattern))

    texts = {path: path.read_text(errors="replace") for path in _source_files(src)}
    slots = _tag_param_slots(list(texts.values()))
    call = re.compile(r"\b(" + "|".join(map(re.escape, slots)) + r")\(") if slots else None

    for path, text in texts.items():
        if call is not None:
            for m in call.finditer(text):
                if text[max(0, m.start() - 4):m.start()] == "fun ":
                    continue  # the declaration, not a call
                params = _args(text, m.end() - 1)
                slot = slots[m.group(1)]
                if slot < len(params) and "=" not in params[slot].split('"', 1)[0]:
                    literal = re.fullmatch(KT_STRING, params[slot])
                    if literal:
                        add(literal.group(1))
        for literal, ref in TAG_CALL.findall(text):
            if literal:
                add(literal)
            elif ref:
                const_refs.add(ref.rsplit(".", 1)[-1])
        for literal in TAG_ARG.findall(text):
            add(literal)
        for prefix in TAG_PREFIX.findall(text):
            for suffix in READ_FAILURE_SUFFIXES:
                literals.add(prefix + suffix)
        for body in TAGS_OBJECT.findall(text):
            for _, value in CONST_VAL.findall(body):
                add(value)
        # A tag-returning helper file (ChatTranscriptTags.kt) builds its tags
        # in code; every string it returns is a tag.
        if path.name.endswith("Tags.kt"):
            for value in re.findall(KT_STRING, text):
                add(value)
        consts.update(CONST_VAL.findall(text))

    for name in const_refs:
        if name in consts:
            add(consts[name])
    return literals, templates


def tag_exists(tag: str, literals: set[str], templates: list[re.Pattern]) -> bool:
    if TEMPLATE_PART.search(tag):
        # The CSD itself names a template (`card_accordfamily_${id}`). Fill
        # its holes with a sample id and ask whether the source can render
        # that: `card_${kind}_${id}` in Attestation.kt can, for an AccordFamily.
        tag = TEMPLATE_PART.sub("x0", tag)
    return tag in literals or any(p.fullmatch(tag) for p in templates)


CSDS = load_csds()
LITERALS, TEMPLATES = source_tags()
CASES = [
    pytest.param(name, state, tag, id=f"{name.split('-', 2)[1]}-{state}-{tag}")
    for name, text in sorted(CSDS.items())
    for state, tag in real_state_tags(text)
]


def test_the_csds_were_found():
    """An empty corpus would pass every case below by having none."""
    assert CSDS, f"no CSDs under {CSD_REF}:{CSD_DIR} or ./{CSD_DIR}"
    assert CASES, "CSDs found but no real csd:states tags parsed — the parser is broken, not the corpus clean"


def test_the_source_scan_finds_tags():
    """A scanner that finds nothing would fail every case for the wrong reason; one
    that finds too little would hide behind KNOWN_ABSENT. Pin a few known tags."""
    for known in ("chat_transcript", "screen_storage", "contacts_list", "config_error"):
        assert tag_exists(known, LITERALS, TEMPLATES), f"scanner lost {known!r}"


@pytest.mark.parametrize("name,state,tag", CASES)
def test_a_real_csd_state_tag_exists_in_the_client(name: str, state: str, tag: str):
    exists = tag_exists(tag, LITERALS, TEMPLATES)
    if tag in KNOWN_ABSENT:
        assert not exists, f"{tag} is in the source now — remove it from KNOWN_ABSENT"
        pytest.xfail(KNOWN_ABSENT[tag])
    assert exists, (
        f"{name} says its `{state}` state is tagged `{tag}`, and no screen renders that tag. "
        f"Either the code dropped it (restore it) or the CSD is wrong (mark it `proposed:`)."
    )


def test_a_localization_key_is_not_mistaken_for_a_tag():
    """The distinction the audit paid for: `graph_nodes` is a string resource at
    GraphMemoryScreen.kt, not a tag, and must not satisfy a CSD."""
    assert not tag_exists("graph_nodes", LITERALS, TEMPLATES)


def test_a_read_failure_prefix_yields_its_two_tags():
    for tag in ("audit_error", "audit_not_on_this_node"):
        assert tag_exists(tag, LITERALS, TEMPLATES)
