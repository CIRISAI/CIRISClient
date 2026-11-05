"""The strings a client must hold to find a node with no node answering.

FSD/TEXT_REFERENCES.md section 3: the node serves every string, except what the app
needs before any node has answered. Browsing and connecting to nodes is on that list
(Eric, 2026-11-05), so this set can never move to the node, and it must be readable in
every language: the person using this screen has nothing else to go on.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[1]
SCREEN = (
    REPO
    / "client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/ui/screens"
    / "ServerConnectionScreen.kt"
)
BUNDLES = REPO / "client/shared/src/desktopMain/resources/localization"

#: `manifest.json` lives beside the languages and is not one.
LANGUAGES = sorted(p.stem for p in BUNDLES.glob("*.json") if p.stem != "manifest")

#: Text that reads the same in every language: a shape, not a sentence.
NOT_TRANSLATED = {"mobile.server_url_placeholder"}


def _keys_on_screen() -> list[str]:
    return sorted(set(re.findall(r'localizedString\(\s*"([a-zA-Z0-9_.]+)"', SCREEN.read_text())))


def _value(lang: str, key: str):
    node = json.loads((BUNDLES / f"{lang}.json").read_text(encoding="utf8"))
    for part in key.split("."):
        node = node.get(part) if isinstance(node, dict) else None
    return node


def test_the_set_was_found():
    # A parser that finds nothing must fail loudly, not pass (AGENTS.md).
    assert len(_keys_on_screen()) >= 16
    assert len(LANGUAGES) == 29


def test_the_screen_has_no_hard_coded_english():
    found = re.findall(r'(?:Text\(\s*|text\s*=\s*)"([A-Z][^"]*)"', SCREEN.read_text())
    assert found == [], f"hard-coded text on the node screen: {found}"


@pytest.mark.parametrize("lang", LANGUAGES)
def test_every_string_exists_and_is_translated(lang):
    for key in _keys_on_screen():
        value = _value(lang, key)
        assert isinstance(value, str) and value.strip(), f"{lang}: {key} is missing"
        if lang != "en" and key not in NOT_TRANSLATED:
            english = _value("en", key)
            # A short word can match by chance in one language; a sentence cannot.
            if len(english) > 12:
                assert value != english, f"{lang}: {key} is still English"
