#!/usr/bin/env python3
"""Every string key: who owns it, who uses it, and which duplicates are real.

    python3 packaging/gen_string_inventory.py --server-src DIR --agent-src DIR   # write
    python3 packaging/gen_string_inventory.py --server-src DIR --check           # gate
    python3 packaging/gen_string_inventory.py --self-test

Stage 1 of FSD/TEXT_REFERENCES.md. The migration moves strings to the node one card
at a time, and it needs to know what exists before anything moves, so the inventory
is GENERATED: FSD/STRING_INVENTORY.tsv has one row per key and
FSD/STRING_INVENTORY.md is the summary. Neither is edited by hand.

THE AGENT COLUMN. CI has the server's emitters at the pin and no agent checkout, so
`--check` cannot recompute who the agent uses. The column is recorded with the commit
it came from, and carried forward until the next `--agent-src` run. Every other
column is recomputed and must match.

DUPLICATES. Equal English does not prove equal meaning: "Cancel" can need different
forms by gender, number or register. So a group is graded by its translations:
`same` means every language renders all its keys alike, a candidate to share a key;
`differs:N` means N languages do not. A short word can differ by context; a sentence of
four words or more that differs is drift until a person reads it and says otherwise.

DEAD ROOTS. Key lookup is strict (`resolve_key`, CIRISServer#366): `mobile.x` never falls
back to a root `x`. So a root key nobody calls, beside a `mobile.` twin, is unreachable.

Stdlib only, like every check in packaging/ (AGENTS.md).
"""
from __future__ import annotations

import argparse
import collections
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

REPO = Path(__file__).resolve().parents[1]
BUNDLE = "client/shared/src/desktopMain/resources/localization"
CLIENT_SRC = "client/shared/src"
SWIFT_SRC = "client/iosApp/iosApp"
HELD = {
    # FSD/TEXT_REFERENCES.md section 3: what the app keeps with no node answering.
    "connect": "ui/screens/ServerConnectionScreen.kt",
    "startup": "ui/screens/StartupScreen.kt",
}
KOTLIN_ROOT = "commonMain/kotlin/ai/ciris/mobile/shared"
TSV = "FSD/STRING_INVENTORY.tsv"
SUMMARY = "FSD/STRING_INVENTORY.md"
COLUMNS = ("key", "owner", "used_by", "reference", "held", "dup_group", "dup_signal", "class")

_DOTTED = re.compile(r"""["']([a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+)["']""")
_KT_LITERAL = re.compile(r'"([a-zA-Z0-9_.]+)"')
_KT_PREFIX = re.compile(
    r'(?:localizedString|getString|l10nOr|localizedOr)\(\s*"([a-zA-Z0-9_.]+)\$'
)
_PY_PREFIX = re.compile(r"""f["']([a-z][a-z0-9_.]*\.)[a-z0-9_]*\{""")
#: CC 4.5.13 derives these from the family model, so their keys go at a card's flip.
_FACT = re.compile(r"(_fact_|_fact$|confirm_(what|who|signs|who_signs|effect)$|\.confirm\.)")
_MIN_DUP_CHARS = 4
_SENTENCE_WORDS = 4


def flatten(node: dict, prefix: str = "") -> Dict[str, str]:
    out: Dict[str, str] = {}
    for key, value in node.items():
        path = f"{prefix}.{key}" if prefix else key
        if isinstance(value, dict):
            out.update(flatten(value, path))
        elif isinstance(value, str) and not path.startswith("_meta"):
            out[path] = value
    return out


def load_languages(root: Path) -> Dict[str, Dict[str, str]]:
    langs = {}
    for path in sorted((root / BUNDLE).glob("*.json")):
        if path.stem != "manifest":
            langs[path.stem] = flatten(json.loads(path.read_text(encoding="utf-8")))
    if "en" not in langs:
        raise SystemExit(f"no en.json under {root / BUNDLE}")
    return langs


def client_refs(root: Path) -> Tuple[Set[str], Set[str]]:
    """Literals and dynamic key prefixes in app code, tests and resources excluded."""
    literals: Set[str] = set()
    prefixes: Set[str] = set()
    for base, suffix in ((CLIENT_SRC, ".kt"), (SWIFT_SRC, ".swift")):
        for path in (root / base).rglob(f"*{suffix}"):
            text = str(path)
            if "/build/" in text or "Test" in path.parent.as_posix():
                continue
            body = path.read_text(encoding="utf-8", errors="replace")
            literals.update(_KT_LITERAL.findall(body))
            prefixes.update(_KT_PREFIX.findall(body))
    return literals, {p for p in prefixes if p}


def server_ids(server_src: Path) -> Set[str]:
    src = server_src / "src" if (server_src / "src").is_dir() else server_src
    found: Set[str] = set()
    for path in src.rglob("*.rs"):
        found.update(_DOTTED.findall(path.read_text(encoding="utf-8", errors="replace")))
    if not found:
        raise SystemExit(f"no server ids found under {src}: a parser that finds nothing must fail")
    return found


def agent_refs(agent_src: Path, ref: str) -> Tuple[Set[str], Set[str], str]:
    def run(*args: str) -> str:
        return subprocess.run(
            ["git", "-C", str(agent_src), *args], capture_output=True, text=True, check=False
        ).stdout

    commit = run("rev-parse", "--short=9", ref).strip()
    if not commit:
        raise SystemExit(f"{agent_src} has no ref {ref}")
    body = run(
        "grep", "-hE", r"""["'][a-z][a-z0-9_]*\.[a-z0-9_.{}]+["']""", ref, "--",
        "ciris_engine", "ciris_adapters",
    )
    literals = set(_DOTTED.findall(body))
    prefixes = set(_PY_PREFIX.findall(body))
    if not literals:
        raise SystemExit(f"no agent ids found at {ref}: a parser that finds nothing must fail")
    return literals, prefixes, commit


def recorded_agent(root: Path) -> Tuple[Set[str], str]:
    """The agent column and its commit, carried forward from the committed files."""
    used: Set[str] = set()
    tsv = root / TSV
    if tsv.is_file():
        for line in tsv.read_text(encoding="utf-8").splitlines()[1:]:
            cells = line.split("\t")
            if "agent" in cells[2].split(","):
                used.add(cells[0])
    commit = "none"
    summary = root / SUMMARY
    if summary.is_file():
        match = re.search(r"CIRISAgent `([0-9a-f]+|none)`", summary.read_text(encoding="utf-8"))
        commit = match.group(1) if match else commit
    return used, commit


def held_keys(root: Path) -> Dict[str, str]:
    held: Dict[str, str] = {}
    for name, rel in HELD.items():
        path = root / CLIENT_SRC / KOTLIN_ROOT / rel
        if not path.is_file():
            continue
        body = path.read_text(encoding="utf-8", errors="replace")
        for key in re.findall(r'(?:localizedString|l10nOr)\(\s*"([a-zA-Z0-9_.]+)"', body):
            held.setdefault(key, name)
    return held


def _norm(text: str) -> str:
    return re.sub(r"[\s\W]+", " ", text.strip().casefold()).strip()


def duplicate_groups(langs: Dict[str, Dict[str, str]]) -> Dict[str, Tuple[int, str]]:
    en = langs["en"]
    by_text: Dict[str, List[str]] = collections.defaultdict(list)
    for key in sorted(en):
        norm = re.sub(r"\s+", " ", en[key].strip().casefold())
        if len(norm) >= _MIN_DUP_CHARS:
            by_text[norm].append(key)
    groups = sorted((keys for keys in by_text.values() if len(keys) > 1), key=lambda k: k[0])
    out: Dict[str, Tuple[int, str]] = {}
    for number, keys in enumerate(groups, 1):
        split = 0
        for lang, values in langs.items():
            if lang == "en":
                continue
            if len({_norm(values.get(key, "")) for key in keys}) > 1:
                split += 1
        signal = "same" if split == 0 else f"differs:{split}"
        for key in keys:
            out[key] = (number, signal)
    return out


def build_rows(
    langs: Dict[str, Dict[str, str]],
    literals: Set[str],
    prefixes: Set[str],
    server: Set[str],
    agent: Set[str],
    held: Dict[str, str],
) -> List[Tuple[str, ...]]:
    dups = duplicate_groups(langs)
    rows = []
    for key in sorted(langs["en"]):
        by_client = key in literals or key in held
        dynamic = not by_client and any(key.startswith(p) for p in prefixes)
        users = []
        if by_client or dynamic:
            users.append("client")
        if key in server:
            users.append("server")
        if key in agent:
            users.append("agent")
        owner = (
            "server" if key in server
            else "agent" if key in agent
            else "client" if users
            else "unclaimed"
        )
        reference = "literal" if by_client else "dynamic" if dynamic else "-"
        dead_root = not users and "." not in key and f"mobile.{key}" in langs["en"]
        group, signal = dups.get(key, (0, "-"))
        rows.append((
            key, owner, ",".join(users) or "-", reference, held.get(key, "-"),
            str(group) if group else "-", signal,
            "dead_root" if dead_root else "fact" if _FACT.search(key) else "-",
        ))
    return rows


def render_tsv(rows: List[Tuple[str, ...]]) -> str:
    return "\t".join(COLUMNS) + "\n" + "".join("\t".join(r) + "\n" for r in rows)


def render_summary(
    rows: List[Tuple[str, ...]], langs: Dict[str, Dict[str, str]], agent_commit: str
) -> str:
    en = langs["en"]
    owners = collections.Counter(r[1] for r in rows)
    held = collections.Counter(r[4] for r in rows if r[4] != "-")
    groups: Dict[str, List[Tuple[str, ...]]] = collections.defaultdict(list)
    for row in rows:
        if row[5] != "-":
            groups[row[5]].append(row)
    same = [g for g in groups.values() if g[0][6] == "same"]
    split = [g for g in groups.values() if g[0][6] != "same"]
    facts = [r for r in rows if r[7] == "fact"]
    fact_dups = [r for r in facts if r[5] != "-"]
    dead = [r for r in rows if r[7] == "dead_root"]
    is_sentence = lambda g: len(en[g[0][0]].split()) >= _SENTENCE_WORDS
    drift = [g for g in split if is_sentence(g)]
    words = [g for g in split if not is_sentence(g)]
    unclaimed = collections.Counter(
        r[0].split(".")[0] for r in rows if r[1] == "unclaimed" and r[7] != "dead_root"
    )
    lines = [
        "# String inventory",
        "",
        "<!-- GENERATED by packaging/gen_string_inventory.py. Do not edit by hand. -->",
        "",
        "Stage 1 of [`TEXT_REFERENCES.md`](TEXT_REFERENCES.md). Every key is a row in",
        "[`STRING_INVENTORY.tsv`](STRING_INVENTORY.tsv). The usage columns are found by",
        "searching source, so they are evidence, not proof: `unclaimed` means a key to read,",
        "not a key to delete.",
        "",
        f"Agent usage recorded at CIRISAgent `{agent_commit}`. CI carries it forward.",
        "",
        f"**{len(rows)} keys in {len(langs)} languages.**",
        "",
        "| Owner | Keys |",
        "|---|---|",
    ]
    lines += [f"| {name} | {owners[name]} |" for name in ("client", "server", "agent", "unclaimed")]
    lines += [
        "",
        "## Held in the app",
        "",
        "Keys on the screens the app keeps with no node answering. `startup` is an upper",
        "bound: the node serves that screen once it answers.",
        "",
        "| Set | Keys |",
        "|---|---|",
    ]
    lines += [f"| {name} | {held[name]} |" for name in sorted(held)]
    lines += [
        "",
        "## Duplicates",
        "",
        f"{len(groups)} English strings sit under {sum(len(g) for g in groups.values())} keys.",
        "",
        f"- **Same in every language:** {len(same)} groups, {sum(len(g) for g in same)} keys.",
        "  Candidates to share a key, for a person to confirm.",
        f"- **Short, and differ in at least one language:** {len(words)} groups, "
        f"{sum(len(g) for g in words)} keys. Context can explain these: read before merging.",
        f"- **Sentences that differ:** {len(drift)} groups, {sum(len(g) for g in drift)} keys.",
        "  Identical English, translated differently: drift until read.",
        f"- **Confirmation facts:** {len(facts)} keys, {len(fact_dups)} of them duplicates.",
        "  CC 4.5.13 derives these, so they go when their card flips.",
        "",
        "Sentences that differ, most languages first:",
        "",
        "| English | Keys | Languages |",
        "|---|---|---|",
    ]
    for group in sorted(drift, key=lambda g: (-int(g[0][6].split(":")[1]), g[0][0])):
        text = en[group[0][0]].replace("|", "\\|").replace("\n", " ")[:64]
        lines.append(f"| {text} | {len(group)} | {group[0][6].split(':')[1]} |")
    lines += [
        "",
        "## Dead root keys",
        "",
        f"{len(dead)} root keys nobody calls, each beside a `mobile.` twin. Lookup never falls",
        "back to them, so they are unreachable and still translated. Marked `dead_root`.",
    ]
    lines += ["", "## Unclaimed, by section", "", "| Section | Keys |", "|---|---|"]
    lines += [f"| `{name}` | {count} |" for name, count in sorted(unclaimed.items(), key=lambda x: (-x[1], x[0]))]
    return "\n".join(lines) + "\n"


def generate(root: Path, server: Path, agent_src: Optional[Path], agent_ref: str):
    langs = load_languages(root)
    literals, prefixes = client_refs(root)
    if agent_src:
        agent_literals, agent_prefixes, commit = agent_refs(agent_src, agent_ref)
        en = langs["en"]
        agent = {k for k in en if k in agent_literals or any(k.startswith(p) for p in agent_prefixes)}
    else:
        agent, commit = recorded_agent(root)
    rows = build_rows(langs, literals, prefixes, server_ids(server), agent, held_keys(root))
    return render_tsv(rows), render_summary(rows, langs, commit)


def self_test() -> int:
    langs = {
        "en": {"a.x": "Cancel", "a.y": "Cancel", "b.x": "Stop", "b.y": "Stop", "c": "Used",
               "d": "Nobody uses this", "e": "Server line", "f.confirm_what": "What changes",
               "g": "Old", "mobile.g": "New"},
        "de": {"a.x": "Abbrechen", "a.y": "Abbrechen", "b.x": "Halt", "b.y": "Stopp", "c": "x",
               "d": "y", "e": "z", "f.confirm_what": "w", "g": "a", "mobile.g": "b"},
    }
    rows = {r[0]: r for r in build_rows(langs, {"c", "a.x"}, {"b."}, {"e"}, set(), {"c": "connect"})}
    checks = [
        (rows["a.x"][6] == "same", "same translations did not grade same"),
        (rows["b.x"][6] == "differs:1", "split translations did not grade differs"),
        (rows["d"][1] == "unclaimed", "an unused key was claimed"),
        (rows["e"][1] == "server", "a server id was not owned by the server"),
        (rows["b.y"][3] == "dynamic", "a dynamic prefix was not seen"),
        (rows["c"][4] == "connect", "a held key was not marked"),
        (rows["f.confirm_what"][7] == "fact", "a confirmation fact was not marked"),
        (rows["g"][7] == "dead_root", "an unreachable root key was not marked"),
    ]
    with tempfile.TemporaryDirectory() as tmp:
        try:
            server_ids(Path(tmp))
            checks.append((False, "an empty server tree did not fail"))
        except SystemExit:
            pass
    failed = [message for ok, message in checks if not ok]
    for message in failed:
        print(f"[FAIL] {message}")
    if not failed:
        print(f"[OK] self-test: {len(checks)} planted cases")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--server-src", type=Path, help="a CIRISServer checkout at the pin")
    ap.add_argument("--agent-src", type=Path, help="a CIRISAgent checkout (git)")
    ap.add_argument("--agent-ref", default="origin/main")
    ap.add_argument("--check", action="store_true", help="fail if the files are stale")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if not args.server_src:
        ap.error("--server-src is required")
    tsv, summary = generate(REPO, args.server_src, args.agent_src, args.agent_ref)
    if args.check:
        stale = [
            rel for rel, want in ((TSV, tsv), (SUMMARY, summary))
            if not (REPO / rel).is_file() or (REPO / rel).read_text(encoding="utf-8") != want
        ]
        if stale:
            print(f"[FAIL] stale: {', '.join(stale)}. Run packaging/gen_string_inventory.py")
            return 1
        print(f"[OK] string inventory current: {tsv.count(chr(10)) - 1} keys")
        return 0
    (REPO / TSV).write_text(tsv, encoding="utf-8")
    (REPO / SUMMARY).write_text(summary, encoding="utf-8")
    print(f"wrote {TSV} and {SUMMARY}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
