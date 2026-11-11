#!/usr/bin/env python3
"""CSD/5: a card's screen as a function of its inputs, checked by exploring it.

    python3 packaging/check_csd5.py                    # gate: registry, every CSD/5 card, Kotlin tags, baseline, TLA+
    python3 packaging/check_csd5.py FSD/CSD/CSD-070-commons.md   # one card, with its state count
    python3 packaging/check_csd5.py --tags CSD-070     # old tag -> derived tag, for that card
    python3 packaging/check_csd5.py --write-tla        # regenerate formal/csd5/<card>.tla and .cfg
    python3 packaging/check_csd5.py --write-baseline   # re-record packaging/csd5/baseline.json
    python3 packaging/check_csd5.py --self-test        # plant one defect per law; prove each goes red
    python3 packaging/check_csd5.py --tlc tla2tools.jar              # also run TLC on each committed module
    python3 packaging/check_csd5.py --self-test --tlc tla2tools.jar  # and prove TLC goes red on planted twins

WHAT IT IS. CSD/3 says what a card shows and CSD/4 says what the node holds at one
instant. CSD/5 (FSD/CSD5.md) adds the part between: which primitives are drawn, in
which state, with which controls enabled, as a function of three declared inputs
(context, local values, read outcomes) and nothing else. A card writes that down in
three typed blocks, `csd:inputs`, `csd:view` and `csd:acts`. This tool derives every
tag from them, walks every state the card can reach, and checks the twelve laws of
packaging/csd5/ui_primitives.json in each one.

WHAT IT IS NOT. It reads the card, not the Kotlin. The only tie to the code is the
tags: every tag literal in the files a card names must be accounted for by the
model, and every old tag the model names must exist there. A screen whose Kotlin
disagrees with its card in some other way passes this check. The five-platform
flows are what catch that, and they drive the same tags.

The model is explicit-state and small on purpose: a text value is blank or filled,
a collection is one element, a write is answered or it fails. FSD/CSD5.md section 6
lists what that leaves out.
"""

from __future__ import annotations

import argparse
import copy
import itertools
import json
import re
import shutil
import subprocess
import sys
import tempfile
from collections import deque
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.exit("check_csd5 needs PyYAML")

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / "packaging/csd5/ui_primitives.json"
BASELINE = ROOT / "packaging/csd5/baseline.json"
CARDS = ROOT / "FSD/CSD"
FORMAL = ROOT / "formal/csd5"
KOTLIN_ROOTS = [ROOT / "client/shared/src", ROOT / "client/desktopApp/src", ROOT / "client/androidApp/src"]
EN_JSON = ROOT / "client/shared/src/desktopMain/resources/localization/en.json"

BLOCK = re.compile(r"```yaml csd:([a-z_]+)\n(.*?)```", re.S)
IDENT = re.compile(r"^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$")
WORD = re.compile(r"^[a-z]+$")
#: A tag literal handed to one of the functions that register a tag.
TAG_CALL = re.compile(
    r"(?:testable|testableClickable|testTag|rememberTextInputDriver)\(\s*\"([A-Za-z0-9_$.{}]+)\"")
TAG_ARG = re.compile(r"\btag\s*=\s*\"([A-Za-z0-9_$.{}]+)\"")
TAG_PREFIX_ARG = re.compile(r"\btagPrefix\s*=\s*\"([a-z0-9_]+)\"")
OUTCOMES = ("idle", "pending", "failed", "ok")
LAWS = tuple(f"L{i}" for i in range(1, 13))
#: The families a write's `row:` may name. This is the pin CSD/3 reads, not a copy of it.
NAMESPACES = ROOT / "client/ceg/namespace_registry.json"
MAX_STATES = 3_000_000


class CardError(Exception):
    """The card or the registry is malformed: nothing can be explored."""


#: The branches an effect that reaches the node may carry: the node did it, the
#: node answered and declined, or no answer arrived. A card that declares no
#: `on_refused` says a refusal leaves the screen as an answer does not: that
#: branch is then not explored, and it is the card's claim that it cannot differ.
BRANCHES = ("on_ok", "on_refused", "on_fail")


def _kind(e: dict) -> str:
    """The one thing an effect does: its only key that is not a branch."""
    return next(k for k in e if k not in BRANCHES)


# ─── guards ──────────────────────────────────────────────────────────────────
# A guard is a boolean over declared names:  a = v   a != v   a in [v, w]   a
# joined by `and`, `or`, `not` and parentheses. It is parsed here into a tree and
# never handed to an interpreter as written.

TOKEN = re.compile(r"\s*(!=|=|\(|\)|\[|\]|,|[A-Za-z_][A-Za-z0-9_.]*)")


def _tokens(src: str) -> list[str]:
    out, pos = [], 0
    src = src.strip()
    while pos < len(src):
        m = TOKEN.match(src, pos)
        if not m:
            raise CardError(f"guard `{src}`: cannot read at `{src[pos:pos + 12]}`")
        out.append(m.group(1))
        pos = m.end()
    return out


def parse_guard(src) -> tuple:
    if src is True or src is None:
        return ("const", True)
    if src is False:
        return ("const", False)
    if not isinstance(src, str):
        raise CardError(f"guard must be text, got {src!r}")
    toks = _tokens(src)
    pos = 0

    def peek():
        return toks[pos] if pos < len(toks) else None

    def take(want=None):
        nonlocal pos
        t = peek()
        if t is None or (want is not None and t != want):
            raise CardError(f"guard `{src}`: expected `{want or 'a term'}`, found `{t}`")
        pos += 1
        return t

    def p_or():
        left = p_and()
        while peek() == "or":
            take()
            left = ("or", left, p_and())
        return left

    def p_and():
        left = p_not()
        while peek() == "and":
            take()
            left = ("and", left, p_not())
        return left

    def p_not():
        if peek() == "not":
            take()
            return ("not", p_not())
        return p_atom()

    def p_atom():
        t = take()
        if t == "(":
            inner = p_or()
            take(")")
            return inner
        if t in ("true", "false"):
            return ("const", t == "true")
        if t in ("and", "or", "in", ")", "[", "]", ",", "=", "!="):
            raise CardError(f"guard `{src}`: `{t}` where a name was expected")
        if peek() == "=":
            take()
            return ("eq", t, take())
        if peek() == "!=":
            take()
            return ("ne", t, take())
        if peek() == "in":
            take()
            take("[")
            vals = [take()]
            while peek() == ",":
                take()
                vals.append(take())
            take("]")
            return ("in", t, vals)
        return ("name", t)

    tree = p_or()
    if pos != len(toks):
        raise CardError(f"guard `{src}`: unexpected `{toks[pos]}`")
    return tree


def guard_names(tree) -> set[str]:
    kind = tree[0]
    if kind == "const":
        return set()
    if kind in ("and", "or"):
        return guard_names(tree[1]) | guard_names(tree[2])
    if kind == "not":
        return guard_names(tree[1])
    return {tree[1]}


# ─── the registry ────────────────────────────────────────────────────────────

def load_registry(path: Path = REGISTRY) -> dict:
    return json.loads(path.read_text())


def check_registry(reg: dict) -> list[str]:
    """L1, on the registry itself: one word per prefix, one owner per prefix."""
    problems = []
    owner: dict[str, str] = {}
    for p in reg.get("shell_prefixes", {}):
        owner[p] = "shell"
    prims = reg.get("primitives", {})
    if not prims:
        return ["registry: no primitives parsed"]
    for name, prim in prims.items():
        for p in list(prim.get("prefixes", [])) + list(prim.get("legacy_prefixes", [])):
            if not WORD.match(p):
                problems.append(f"L1 registry: prefix `{p}` of {name} is not one lower-case word")
            if p in owner and owner[p] != name:
                problems.append(f"L1 registry: prefix `{p}` has two owners, {owner[p]} and {name}")
            owner[p] = name
        if not prim.get("prefixes"):
            problems.append(f"registry: {name} owns no prefix")
    for st, body in reg.get("states", {}).items():
        if body.get("outcome") not in OUTCOMES:
            problems.append(f"registry: state `{st}` names outcome `{body.get('outcome')}`")
    for law in LAWS:
        if law not in reg.get("laws", {}):
            problems.append(f"registry: law {law} is missing")
    for law in reg.get("laws", {}):
        if law not in LAWS:
            problems.append(f"registry: law {law} is in the registry and this tool does not check it")
    if not reg.get("write_verbs"):
        problems.append("registry: no `write_verbs`")
    return problems


_FAMILIES: set[str] | None = None


def families() -> set[str]:
    """Every family prefix in the pinned namespace registry."""
    global _FAMILIES
    if _FAMILIES is None:
        doc = json.loads(NAMESPACES.read_text())
        _FAMILIES = {f["prefix"] for f in doc.get("families", []) if isinstance(f, dict) and f.get("prefix")}
        if not _FAMILIES:
            raise CardError(f"{NAMESPACES.name}: no family parsed; a write's `row:` cannot be checked")
    return _FAMILIES


# ─── a card, as a model ──────────────────────────────────────────────────────

def read_blocks(path: Path) -> dict:
    out = {}
    for name, body in BLOCK.findall(path.read_text()):
        try:
            out[name] = yaml.safe_load(body)
        except yaml.YAMLError as e:
            raise CardError(f"{path.name}: csd:{name} does not parse: {e}") from e
    return out


def is_csd5(path: Path) -> bool:
    return "```yaml csd:inputs" in path.read_text()


class Model:
    """One card's inputs, instances and acts, with every guard resolved."""

    def __init__(self, card: str, blocks: dict, reg: dict):
        self.card = card
        self.reg = reg
        self.problems: list[str] = []          # static law failures
        inputs = blocks.get("inputs") or {}
        view = blocks.get("view") or {}
        acts = blocks.get("acts") or {}
        surface = (blocks.get("surface") or {}).get("surface") or view.get("flow")
        if not surface:
            raise CardError(f"{card}: no `surface:` in csd:surface and no `flow:` in csd:view")
        self.surface = str(surface).replace("-", "_")
        if not IDENT.match(self.surface):
            raise CardError(f"{card}: surface `{self.surface}` is not a lower-case id")
        self.kotlin = list(view.get("kotlin") or [])

        # ── slots ────────────────────────────────────────────────────────────
        self.slots: list[str] = []
        self.domain: dict[str, list[str]] = {}   # guard name -> values a guard may compare with
        self.kind: dict[str, str] = {}           # guard name -> context|local|read|carry|preview|sheet
        self.init: dict[str, str] = {}
        self.tla: dict[str, str] = {}

        def slot(name, kind, values, init, tla):
            if name in self.kind:
                raise CardError(f"{card}: `{name}` is declared twice")
            self.slots.append(name)
            self.kind[name] = kind
            self.domain[name] = list(values)
            self.init[name] = init
            self.tla[name] = tla

        self.context = list(inputs.get("context") or [])
        for key in self.context:
            vals = reg["context"].get(key)
            if not vals:
                raise CardError(f"{card}: context `{key}` is not in the registry")
            slot(key, "context", vals, vals[0], f"x_{key}")

        self.locals: dict[str, dict] = {}
        for name, body in (inputs.get("local") or {}).items():
            body = dict(body or {})
            typ = body.get("type")
            if typ == "bool":
                vals, init = ["false", "true"], str(body.get("init", False)).lower()
            elif typ == "text":
                vals, init = ["blank", "filled"], "blank"
            elif typ == "enum":
                vals = [str(v) for v in body.get("values") or []]
                init = str(body.get("init", vals[0] if vals else ""))
            else:
                raise CardError(f"{card}: local `{name}` has type `{typ}`; the registry has bool, text, enum")
            if init not in vals:
                raise CardError(f"{card}: local `{name}` starts at `{init}`, which is not one of its values")
            body["type"] = typ
            self.locals[name] = body
            slot(name, "local", vals, init, f"l_{name}")

        self.reads: dict[str, dict] = {}
        self.answers: dict[str, list[str]] = {}   # read or preview -> the locals it was taken over
        for name, body in (inputs.get("reads") or {}).items():
            body = dict(body or {})
            self.reads[name] = body
            slot(name, "read", OUTCOMES, "idle", f"r_{name}")
            for carry, cb in (body.get("carries") or {}).items():
                typ = (cb or {}).get("type")
                if typ == "bool":
                    vals = ["false", "true"]
                elif typ == "enum":
                    vals = [str(v) for v in cb.get("values") or []]
                else:
                    raise CardError(f"{card}: `{name}.{carry}` has type `{typ}`; a carried value is bool or enum")
                slot(f"{name}.{carry}", "carry", vals, "-", f"c_{name}_{carry}")
            self.answers[name] = list(body.get("of") or [])
        self.previews: dict[str, dict] = {}
        for name, body in (inputs.get("previews") or {}).items():
            body = dict(body or {})
            self.previews[name] = body
            slot(name, "preview", ["none", "taken"], "none", f"p_{name}")
            self.answers[name] = list(body.get("covers") or [])
        for ans, over in self.answers.items():
            for v in over:
                if v not in self.locals:
                    raise CardError(f"{card}: `{ans}` is taken over `{v}`, which is not a local value")
                slot(f"{ans}@{v}", "snapshot", [], "-", f"q_{ans}_{v}")
        self.writes: dict[str, dict] = {n: dict(b or {}) for n, b in (inputs.get("writes") or {}).items()}

        # ── instances and acts ───────────────────────────────────────────────
        self.instances: list[dict] = []
        for raw in view.get("instances") or []:
            self.instances.append(dict(raw))
        for raw in acts.get("acts") or []:
            a = dict(raw)
            a.setdefault("primitive", "Action")
            self.instances.append(a)
        self.sheets = [i["id"] for i in self.instances if i.get("primitive") == "ConfirmSheet"]
        slot("sheet", "sheet", ["none"] + self.sheets, "none", "sheet")
        self.index = {n: i for i, n in enumerate(self.slots)}

        # ── defs, then every guard ───────────────────────────────────────────
        self.defs: dict[str, tuple] = {}
        for name, src in (inputs.get("defs") or {}).items():
            if name in self.kind:
                raise CardError(f"{card}: def `{name}` shadows a declared input")
            self.defs[name] = self._resolve(parse_guard(src), f"def {name}")
        self._cache: dict[int, object] = {}

        for name, body in self.locals.items():
            body["_lives"] = self.guard(body.get("lives_while"), f"local {name} lives_while")
        for name, body in self.previews.items():
            body["_lives"] = self.guard(body.get("lives_while"), f"preview {name} lives_while")
        for name, body in self.reads.items():
            body["_where"] = [self.guard(w, f"read {name} where") for w in (body.get("where") or [])]

        seen_ids = set()
        for inst in self.instances:
            iid = inst.get("id")
            prim = inst.get("primitive")
            if not iid or not IDENT.match(str(iid)):
                raise CardError(f"{card}: instance id `{iid}` is not a lower-case id")
            if prim not in reg["primitives"]:
                raise CardError(f"{card}: `{iid}` names primitive `{prim}`, which is not in the registry")
            where = f"{prim} {iid}"
            if (prim, iid) in seen_ids:
                self.problems.append(f"L1 {card}: {where} is declared twice")
            seen_ids.add((prim, iid))
            inst["_present"] = self.guard(inst.get("present"), f"{where} present")
            inst["_enabled"] = self.guard(inst.get("enabled"), f"{where} enabled")
            if prim == "ConfirmSheet":
                inst["_while"] = self.guard(inst.get("while"), f"{where} while")
            if prim == "StateBlock":
                inst["_states"] = {st: self.guard(g, f"{where} state {st}")
                                   for st, g in (inst.get("states") or {}).items()}
            if prim == "Metric":
                inst["_shown"] = self.guard(inst.get("shown"), f"{where} shown")
        self._static()

    # ── guards ───────────────────────────────────────────────────────────────

    def _resolve(self, tree, where):
        """Inline defs and check every name and value against what is declared (L8)."""
        kind = tree[0]
        if kind == "const":
            return tree
        if kind in ("and", "or"):
            return (kind, self._resolve(tree[1], where), self._resolve(tree[2], where))
        if kind == "not":
            return ("not", self._resolve(tree[1], where))
        name = tree[1]
        if kind == "name" and name in self.defs:
            return self.defs[name]
        if name not in self.kind or self.kind[name] == "snapshot":
            self.problems.append(f"L8 {self.card}: {where} names `{name}`, which the card does not declare")
            return ("const", False)
        vals = [tree[2]] if kind in ("eq", "ne") else (tree[2] if kind == "in" else ["true"])
        is_text = self.kind[name] == "local" and self.locals[name]["type"] == "text"
        for v in vals:
            if is_text and v != "blank":
                self.problems.append(
                    f"L8 {self.card}: {where} compares text `{name}` with `{v}`; a guard may only ask whether it is blank")
            elif not is_text and v not in self.domain[name]:
                self.problems.append(f"L8 {self.card}: {where} compares `{name}` with `{v}`, not one of its values")
        if kind == "name":
            return ("eq", name, "true")
        return tree

    def guard(self, src, where):
        return self._resolve(parse_guard(src), where)

    def _py(self, tree) -> str:
        kind = tree[0]
        if kind == "const":
            return "True" if tree[1] else "False"
        if kind in ("and", "or"):
            return f"({self._py(tree[1])} {kind} {self._py(tree[2])})"
        if kind == "not":
            return f"(not {self._py(tree[1])})"
        i = self.index[tree[1]]
        if kind == "eq":
            return f"(s[{i}] == {tree[2]!r})"
        if kind == "ne":
            return f"(s[{i}] != {tree[2]!r})"
        return f"(s[{i}] in {tuple(tree[2])!r})"

    def fn(self, tree):
        """A guard as a Python function of the state tuple. The source handed to
        eval() is built here from slot numbers and values that already matched
        the id pattern; no text from a card reaches it as written."""
        key = id(tree)
        if key not in self._cache:
            self._cache[key] = eval("lambda s: " + self._py(tree))  # noqa: S307
        return self._cache[key]

    # ── tags ─────────────────────────────────────────────────────────────────

    def prefix_of(self, inst) -> str:
        prim = self.reg["primitives"][inst["primitive"]]
        p = inst.get("prefix") or prim["prefixes"][0]
        if p not in prim["prefixes"]:
            self.problems.append(
                f"L1 {self.card}: {inst['primitive']} {inst['id']} uses prefix `{p}`, which {inst['primitive']} does not own")
        return p

    def tags(self, inst) -> list[tuple[str, str]]:
        """Every tag this instance derives, as (slot, tag). Slot `` is the root."""
        base = f"{self.prefix_of(inst)}_{self.surface}_{inst['id']}"
        prim = inst["primitive"]
        if prim == "Choice":
            var = inst.get("var")
            keys = [f".{v}" for v in self.domain.get(var, [])] or [".<option>"]
        elif inst.get("per"):
            keys = [f".<{inst['per']}>"]
        else:
            keys = [""]
        slots = [""]
        if prim == "StateBlock":
            slots += list(inst.get("_states", {}))
        elif prim == "ConfirmSheet":
            slots += [s for s in self.reg["primitives"][prim]["slots"]]
        else:
            allowed = set(self.reg["primitives"][prim].get("slots", []))
            for s in inst.get("slots") or []:
                if s not in allowed:
                    self.problems.append(f"L1 {self.card}: {prim} {inst['id']} has no slot `{s}` in the registry")
                slots.append(s)
        return [(s, base + (f"__{s}" if s else "") + k) for s in slots for k in keys]

    def all_tags(self) -> dict[str, str]:
        out: dict[str, str] = {}
        for inst in self.instances:
            for slot, tag in self.tags(inst):
                if tag in out:
                    self.problems.append(
                        f"L1 {self.card}: tag `{tag}` is derived twice ({out[tag]} and {inst['primitive']} {inst['id']})")
                out[tag] = f"{inst['primitive']} {inst['id']}"
        return out

    def was_map(self) -> list[tuple[str, str]]:
        """(old tag, derived tag) for every `was:` the card records."""
        out = []
        for inst in self.instances:
            was = inst.get("was")
            if was is None:
                continue
            derived = dict(self.tags(inst)) if inst["primitive"] != "Choice" else {}
            root = f"{self.prefix_of(inst)}_{self.surface}_{inst['id']}"
            if inst["primitive"] == "Choice":
                root += ".<option>"
            elif inst.get("per"):
                root += f".<{inst['per']}>"
            entries = was.items() if isinstance(was, dict) else [("", was)]
            for slot, olds in entries:
                slot = "" if slot in ("", "root") else str(slot)
                new = derived.get(slot, root if not slot else None) if inst["primitive"] != "Choice" else root
                if new is None:
                    self.problems.append(f"L1 {self.card}: `was:` of {inst['id']} names slot `{slot}`, which it does not have")
                    continue
                for old in ([olds] if isinstance(olds, str) else olds):
                    out.append((old, new))
        return out

    # ── static laws ──────────────────────────────────────────────────────────

    def _effects(self, inst) -> list[tuple[str, list]]:
        out = []
        for key in ("effect", "on_change", "confirm", "cancel"):
            if inst.get(key):
                out.append((key, inst[key]))
        return out

    def _walk(self, effs):
        for e in effs or []:
            if not isinstance(e, dict) or len([k for k in e if k not in BRANCHES]) != 1:
                raise CardError(f"{self.card}: effect {e!r} must name exactly one thing to do")
            if any(k in e for k in BRANCHES) and _kind(e) not in ("issue", "call"):
                raise CardError(f"{self.card}: effect {e!r} has a branch; only `issue` and `call` reach the node")
            yield e
            for branch in BRANCHES:
                yield from self._walk(e.get(branch))

    def _static(self):
        card = self.card
        self.all_tags()
        self.was_map()
        for inst in self.instances:
            prim, iid = inst["primitive"], inst["id"]
            where = f"{prim} {iid}"
            if inst.get("per") is not None and not IDENT.match(str(inst["per"])):
                raise CardError(f"{card}: {where} `per:` must be an id")
            if self.reg["primitives"][prim].get("keyed") and prim != "Choice" and not inst.get("per"):
                self.problems.append(f"L1 {card}: {where} is one of several and declares no `per:`")
            if prim in ("Field", "Choice"):
                var = inst.get("var")
                if var is None and not (prim == "Field" and inst.get("untracked")):
                    raise CardError(f"{card}: {where} binds no `var:` (a Field the model does not follow says `untracked: true`)")
                if var is not None and var not in self.locals:
                    raise CardError(f"{card}: {where} binds `{var}`, which is not a local value")
                if prim == "Choice" and self.locals[var]["type"] == "text":
                    raise CardError(f"{card}: {where} is a Choice over text")
                if prim == "Field" and var is not None and self.locals[var]["type"] != "text":
                    raise CardError(f"{card}: {where} is a Field over `{var}`, which is not text")
            if prim in ("StateBlock", "Metric"):
                if inst.get("of") not in self.reads:
                    raise CardError(f"{card}: {where} is bound to `{inst.get('of')}`, which is not a read")
            if prim == "StateBlock":
                if not inst.get("_states"):
                    raise CardError(f"{card}: {where} declares no states")
                for st in inst["_states"]:
                    if st not in self.reg["states"]:
                        raise CardError(f"{card}: {where} declares state `{st}`, which is not in the registry")
            if inst.get("of") is not None and inst["of"] not in self.reads and inst["of"] not in self.previews:
                raise CardError(f"{card}: {where} is drawn from `{inst['of']}`, which is neither a read nor a preview")
            if prim == "Metric" and inst.get("sent") is not None and self.kind.get(inst["sent"]) != "carry":
                raise CardError(f"{card}: {where} `sent:` must name a carried value")
            if prim == "Media":
                for need in ("alt", "ai_disclosure"):
                    if not inst.get(need):
                        self.problems.append(f"L10 {card}: {where} declares no `{need}:`")
            if prim == "Action":
                if "enabled" in inst and inst["_enabled"] != ("const", True) and "reason" not in inst:
                    self.problems.append(
                        f"L5 {card}: {where} can be disabled and names no `reason:` "
                        "(a text id, or `null` for one that draws none today)")
                if inst.get("reason") and not _text_exists(str(inst["reason"])):
                    self.problems.append(f"L5 {card}: {where} gives reason `{inst['reason']}`, which is not in en.json")
            for key, effs in self._effects(inst):
                for e in self._walk(effs):
                    kind = _kind(e)
                    arg = e[kind]
                    if kind == "issue":
                        if arg not in self.writes:
                            raise CardError(f"{card}: {where} issues `{arg}`, which is not a write")
                        confirmed = prim == "ConfirmSheet" and key == "confirm"
                        if not confirmed and not (prim == "Action" and key == "effect"):
                            self.problems.append(
                                f"L12 {card}: {where} sends the write `{arg}` from its `{key}:`; a write is sent "
                                "only from a ConfirmSheet's confirm or an Action")
                        elif not confirmed and self.writes[arg].get("confirm") is not False:
                            self.problems.append(
                                f"L6 {card}: {where} sends the write `{arg}`, which is confirmed (it widens or has "
                                "no way back), outside a ConfirmSheet's confirm")
                        elif confirmed and self.writes[arg].get("confirm") is False:
                            self.problems.append(
                                f"L6 {card}: {where} puts a confirm in front of the write `{arg}`, which takes one "
                                "step (it narrows and has a way back)")
                    elif kind == "ask":
                        if arg not in self.reads:
                            raise CardError(f"{card}: {where} asks `{arg}`, which is not a read")
                    elif kind == "call":
                        if arg not in self.previews:
                            raise CardError(f"{card}: {where} calls `{arg}`, which is not a preview")
                    elif kind == "open":
                        if arg not in self.sheets:
                            raise CardError(f"{card}: {where} opens `{arg}`, which is not a ConfirmSheet")
                    elif kind == "drop":
                        for n in arg:
                            if n not in self.reads and n not in self.previews and n not in self.locals:
                                raise CardError(f"{card}: {where} drops `{n}`, which is not declared")
                    elif kind == "set":
                        for var, val in arg.items():
                            if var not in self.locals or str(val).lower() not in self.domain[var]:
                                raise CardError(f"{card}: {where} sets `{var}` to `{val}`")
                            if self.locals[var]["type"] == "text" and str(val) != "blank":
                                raise CardError(f"{card}: {where} sets text `{var}`; an effect may only blank it")
                    elif kind == "toggle":
                        if arg not in self.locals or self.locals[arg]["type"] != "bool":
                            raise CardError(f"{card}: {where} toggles `{arg}`, which is not a bool")
                    elif kind != "leave":
                        raise CardError(f"{card}: {where} has effect `{kind}`; the standard has "
                                        "set, toggle, ask, call, drop, open, issue, leave")
        #: write -> every instance that sends it (a sheet from its confirm, an Action from its effect)
        self.issued_by: dict[str, list[dict]] = {}
        #: local value -> the Fields and Choices that draw it
        self.binders: dict[str, list[dict]] = {}
        for inst in self.instances:
            prim = inst["primitive"]
            if prim in ("Field", "Choice") and inst.get("var"):
                self.binders.setdefault(inst["var"], []).append(inst)
            shows = inst.get("shows")
            if shows is not None:
                if prim not in ("ConfirmSheet", "Action"):
                    raise CardError(f"{card}: {prim} {inst['id']} has `shows:`; only a ConfirmSheet or an Action sends a write")
                for v in shows:
                    if v not in self.locals:
                        raise CardError(f"{card}: {prim} {inst['id']} shows `{v}`, which is not a local value")
            key = "confirm" if prim == "ConfirmSheet" else "effect" if prim == "Action" else None
            for e in self._walk(inst.get(key)) if key else []:
                if "issue" in e and inst not in self.issued_by.setdefault(e["issue"], []):
                    self.issued_by[e["issue"]].append(inst)
        for name, w in self.writes.items():
            for v in list(w.get("sends") or []) + list(w.get("adds") or []):
                if v not in self.locals:
                    raise CardError(f"{card}: write `{name}` sends `{v}`, which is not a local value")
            if not isinstance(w.get("confirm"), bool):
                raise CardError(f"L6 {card}: write `{name}` does not say `confirm: true` or `confirm: false`")
            if "row" not in w:
                raise CardError(f"L6 {card}: write `{name}` names no `row:` (the family and verb of the row it "
                                "lands, or `null` for a write that lands none)")
            row = w["row"]
            if row is not None:
                if not isinstance(row, dict) or set(row) != {"family", "verb"}:
                    raise CardError(f"L6 {card}: write `{name}` `row:` must be {{family, verb}} or null")
                if row["family"] not in families():
                    raise CardError(f"L6 {card}: write `{name}` lands a row in `{row['family']}`, "
                                    f"which is not a family in {NAMESPACES.name}")
                if row["verb"] not in self.reg.get("write_verbs", []):
                    raise CardError(f"L6 {card}: write `{name}` has verb `{row['verb']}`, which is not in the registry")
            if name not in self.issued_by:
                self.problems.append(f"L9 {card}: write `{name}` is issued by nothing")
            prev = w.get("previewed_by")
            if prev is not None:
                if prev not in self.previews:
                    raise CardError(f"{card}: write `{name}` is previewed by `{prev}`, which is not a preview")
                extra = [v for v in w.get("sends") or [] if v not in self.answers[prev]]
                if extra:
                    self.problems.append(
                        f"L7 {card}: write `{name}` sends {extra}, which its preview `{prev}` does not cover")

    def debts(self) -> list[str]:
        """Actions that can be disabled and draw no reason today (L5, counted)."""
        return sorted(i["id"] for i in self.instances
                      if i["primitive"] == "Action" and "reason" in i and i["reason"] is None
                      and i["_enabled"] != ("const", True))


_EN: dict | None = None


def _text_exists(key: str) -> bool:
    global _EN
    if _EN is None:
        _EN = json.loads(EN_JSON.read_text()) if EN_JSON.exists() else {}
    node = _EN
    for part in key.split("."):
        if not isinstance(node, dict) or part not in node:
            return False
        node = node[part]
    return isinstance(node, str)


# ─── exploring ───────────────────────────────────────────────────────────────

class Explorer:
    """Breadth-first over every state the card can reach, checking the laws in each."""

    def __init__(self, m: Model):
        self.m = m
        ix = m.index
        self.ix = ix
        self.sheet_i = ix["sheet"]
        self.violations: dict[str, tuple] = {}     # message -> state it was first seen in
        self.seen_flags: set[tuple] = set()
        self.norm_passes = 1
        self.parent: dict[tuple, tuple] = {}
        # combinations of carried values a read may be answered with
        self.combos: dict[str, list[dict]] = {}
        for r, body in m.reads.items():
            names = [f"{r}.{c}" for c in (body.get("carries") or {})]
            combos = []
            base = [m.init[n] for n in m.slots]
            for vals in itertools.product(*[m.domain[n] for n in names]):
                s = list(base)
                for n, v in zip(names, vals):
                    s[ix[n]] = v
                s[ix[r]] = "ok"
                if all(m.fn(w)(s) for w in body["_where"]):
                    combos.append(dict(zip(names, vals)))
            if not combos:
                raise CardError(f"{m.card}: read `{r}` has no answer its `where:` allows")
            self.combos[r] = combos
        # normalisation rules, applied in this order in one pass
        self.rules = []
        for name, body in m.locals.items():
            if body["_lives"] != ("const", True):
                self.rules.append(("local", name, m.fn(body["_lives"])))
        for name, body in m.previews.items():
            if body["_lives"] != ("const", True):
                self.rules.append(("preview", name, m.fn(body["_lives"])))
        for inst in m.instances:
            if inst["primitive"] == "ConfirmSheet" and inst["_while"] != ("const", True):
                self.rules.append(("sheet", inst["id"], m.fn(inst["_while"])))

    # ── state edits (on a list) ──────────────────────────────────────────────

    def _is_text(self, v):
        return self.m.locals[v]["type"] == "text"

    def _held(self, s, ans):
        return s[self.ix[ans]] != ("idle" if ans in self.m.reads else "none")

    def _snap(self, s, ans):
        for v in self.m.answers[ans]:
            cur = s[self.ix[v]]
            s[self.ix[f"{ans}@{v}"]] = ("blank" if cur == "blank" else "same") if self._is_text(v) else cur

    def _unsnap(self, s, ans):
        for v in self.m.answers[ans]:
            s[self.ix[f"{ans}@{v}"]] = "-"

    def _reset_local(self, s, v):
        s[self.ix[v]] = self.m.init[v]
        if self._is_text(v):
            for ans, over in self.m.answers.items():
                if v in over:
                    q = self.ix[f"{ans}@{v}"]
                    if s[q] in ("same", "diff"):
                        s[q] = "diff"

    def _drop(self, s, name):
        m = self.m
        if name in m.reads:
            s[self.ix[name]] = "idle"
            for c in m.reads[name].get("carries") or {}:
                s[self.ix[f"{name}.{c}"]] = "-"
            self._unsnap(s, name)
        elif name in m.previews:
            s[self.ix[name]] = "none"
            self._unsnap(s, name)
        else:
            self._reset_local(s, name)

    def matches(self, s, ans) -> bool:
        for v in self.m.answers[ans]:
            q, cur = s[self.ix[f"{ans}@{v}"]], s[self.ix[v]]
            if self._is_text(v):
                if not ((q == "blank" and cur == "blank") or q == "same"):
                    return False
            elif q != cur:
                return False
        return True

    def apply(self, e, s) -> list[tuple]:
        kind = _kind(e)
        arg = e[kind]
        t = list(s)
        if kind == "set":
            for var, val in arg.items():
                val = str(val).lower()
                if self._is_text(var):
                    self._reset_local(t, var)
                else:
                    t[self.ix[var]] = val
        elif kind == "toggle":
            t[self.ix[arg]] = "false" if t[self.ix[arg]] == "true" else "true"
        elif kind == "ask":
            self._drop(t, arg)
            t[self.ix[arg]] = "pending"
            self._snap(t, arg)
        elif kind == "call":
            # A preview is asked of the node: whatever was held is let go first,
            # then it is taken, or it fails or is refused and none is held.
            self._drop(t, arg)
            none = tuple(t)
            t[self.ix[arg]] = "taken"
            self._snap(t, arg)
            out = self.run(e.get("on_fail") or [], none) + self.run(e.get("on_ok") or [], tuple(t))
            if "on_refused" in e:
                out += self.run(e["on_refused"] or [], none)
            return out
        elif kind == "drop":
            for n in arg:
                self._drop(t, n)
        elif kind == "open":
            t[self.sheet_i] = arg
        elif kind == "issue":
            # A write is answered or it fails; the card says what each leaves.
            self.seen_flags.add(("issue", arg))
            out = self.run(e.get("on_fail") or [], tuple(t)) + self.run(e.get("on_ok") or [], tuple(t))
            if "on_refused" in e:
                out += self.run(e["on_refused"] or [], tuple(t))
            return out
        return [tuple(t)]

    def run(self, effs, s) -> list[tuple]:
        out = [s]
        for e in effs:
            nxt = []
            for t in out:
                nxt.extend(self.apply(e, t))
            out = nxt
        return out

    def norm(self, s) -> tuple:
        t = list(s)
        passes = 0
        while True:
            changed = False
            for kind, name, alive in self.rules:
                if alive(t):
                    continue
                if kind == "local" and t[self.ix[name]] != self.m.init[name]:
                    self._reset_local(t, name)
                    changed = True
                elif kind == "preview" and t[self.ix[name]] != "none":
                    self._drop(t, name)
                    changed = True
                elif kind == "sheet" and t[self.sheet_i] == name:
                    t[self.sheet_i] = "none"
                    changed = True
            if not changed:
                break
            passes += 1
        self.norm_passes = max(self.norm_passes, passes)
        return tuple(t)

    # ── successors ───────────────────────────────────────────────────────────

    def successors(self, s):
        m, ix = self.m, self.ix
        out = []
        for r, body in m.reads.items():
            if s[ix[r]] == "pending":
                t = list(s)
                t[ix[r]] = "failed"
                out.append((f"{r} fails", tuple(t)))
                for combo in self.combos[r]:
                    t = list(s)
                    t[ix[r]] = "ok"
                    for n, v in combo.items():
                        t[ix[n]] = v
                    label = ", ".join(f"{n.split('.')[1]}={v}" for n, v in combo.items())
                    out.append((f"{r} answered ({label})", tuple(t)))
        open_sheet = s[self.sheet_i]
        if open_sheet != "none":
            inst = next(i for i in m.instances if i["primitive"] == "ConfirmSheet" and i["id"] == open_sheet)
            closed = list(s)
            closed[self.sheet_i] = "none"
            closed = tuple(closed)
            for t in self.run(inst.get("confirm") or [], closed):
                out.append((f"confirm {open_sheet}", t))
            for t in self.run(inst.get("cancel") or [], closed):
                out.append((f"cancel {open_sheet}", t))
            return [(lab, self.norm(t)) for lab, t in out]
        for inst in m.instances:
            prim = inst["primitive"]
            if prim not in ("Field", "Choice", "Action"):
                continue
            if not m.fn(inst["_present"])(s) or not m.fn(inst["_enabled"])(s):
                continue
            iid = inst["id"]
            if prim == "Action":
                self.seen_flags.add(("enabled", iid))
                for t in self.run(inst.get("effect") or [], s):
                    out.append((f"press {iid}", t))
                continue
            var = inst.get("var")
            if var is None:
                continue
            self.seen_flags.add(("enabled", iid))
            if prim == "Choice":
                for val in m.domain[var]:
                    if val == s[ix[var]]:
                        continue            # picking what is already picked changes nothing
                    t = list(s)
                    t[ix[var]] = val
                    for u in self.run(inst.get("on_change") or [], tuple(t)):
                        out.append((f"pick {iid}.{val}", u))
                continue
            over = [a for a, vs in m.answers.items() if var in vs]
            before = tuple(s[ix[f"{a}@{var}"]] for a in over)
            for cur in ("blank", "filled"):
                options = []
                for a in over:
                    q = s[ix[f"{a}@{var}"]]
                    if q in ("-", "blank"):
                        options.append([q])
                    elif cur == "blank":
                        options.append(["diff"])
                    else:
                        options.append(["same", "diff"])
                for rels in itertools.product(*options):
                    # An edit changes the text. Blank to blank does not, and neither
                    # does a filled value still equal to one an answer was asked with.
                    if cur == s[ix[var]] and (cur == "blank" or (rels == before and "same" in rels)):
                        continue
                    t = list(s)
                    t[ix[var]] = cur
                    for a, rel in zip(over, rels):
                        t[ix[f"{a}@{var}"]] = rel
                    what = "clear" if cur == "blank" else ("retype" if "diff" in rels else "type")
                    for u in self.run(inst.get("on_change") or [], tuple(t)):
                        out.append((f"{what} {iid}", u))
        return [(lab, self.norm(t)) for lab, t in out]

    # ── the laws, in one state ───────────────────────────────────────────────

    def flag(self, msg, s):
        if msg not in self.violations:
            self.violations[msg] = s

    def can_send(self, s, inst) -> bool:
        """This instance could send its write from state `s`: its sheet is open,
        or it is an Action that is present and enabled with no sheet over it."""
        if inst["primitive"] == "ConfirmSheet":
            return s[self.sheet_i] == inst["id"]
        return (s[self.sheet_i] == "none" and self.m.fn(inst["_present"])(s)
                and self.m.fn(inst["_enabled"])(s))

    def check(self, s):
        m, ix, card = self.m, self.ix, self.m.card
        for ans in m.answers:
            if self._held(s, ans) and not self.matches(s, ans):
                what = "read" if ans in m.reads else "preview"
                self.flag(f"L11 {card}: the {what} `{ans}` is held after an input it was taken over changed", s)
        if s[self.sheet_i] != "none":
            self.seen_flags.add(("open", s[self.sheet_i]))
        for w, issuers in m.issued_by.items():
            prev = m.writes[w].get("previewed_by")
            sent = list(m.writes[w].get("sends") or []) + list(m.writes[w].get("adds") or [])
            for by in issuers:
                if not self.can_send(s, by):
                    continue
                if prev is not None and (s[ix[prev]] != "taken" or not self.matches(s, prev)):
                    self.flag(f"L7 {card}: `{w}` can be sent without a held preview `{prev}` of what it sends", s)
                for v in sent:
                    if v in (by.get("shows") or []):
                        continue
                    if not any(m.fn(f["_present"])(s) for f in m.binders.get(v, [])):
                        self.flag(f"L12 {card}: `{w}` can be sent from {by['primitive']} {by['id']} "
                                  f"while `{v}`, which it sends, is not drawn", s)
        for inst in m.instances:
            prim, iid = inst["primitive"], inst["id"]
            present = m.fn(inst["_present"])(s) if prim != "ConfirmSheet" else s[self.sheet_i] == iid
            if not present:
                continue
            self.seen_flags.add(("present", iid))
            of = inst.get("of")
            if prim == "StateBlock":
                on = [st for st, g in inst["_states"].items() if m.fn(g)(s)]
                if len(on) != 1:
                    self.flag(f"L2 {card}: StateBlock {iid} is in {len(on)} states at once "
                              f"({', '.join(on) or 'none'})", s)
                for st in on:
                    self.seen_flags.add(("state", iid, st))
                    want = m.reg["states"][st]["outcome"]
                    if s[ix[of]] != want:
                        self.flag(f"L3 {card}: StateBlock {iid} shows `{st}` while the read `{of}` is {s[ix[of]]}", s)
            elif prim == "Metric":
                if m.fn(inst["_shown"])(s):
                    self.seen_flags.add(("shown", iid))
                    sent = inst.get("sent")
                    if s[ix[of]] != "ok" or (sent and s[ix[sent]] != "true"):
                        self.flag(f"L4 {card}: Metric {iid} shows a value the read `{of}` did not send", s)
            elif of is not None:
                if of in m.reads and s[ix[of]] != "ok":
                    self.flag(f"L3 {card}: {prim} {iid} is drawn from `{of}` while that read is {s[ix[of]]}", s)
                if of in m.previews and s[ix[of]] != "taken":
                    self.flag(f"L3 {card}: {prim} {iid} is drawn from the preview `{of}` while none is held", s)

    def explore(self, until: str | None = None):
        """Every reachable state. `until` stops at the first violation whose message
        starts with it; the self-test uses that, the gate never does."""
        m = self.m
        ctx_names = m.context
        inits = []
        for vals in itertools.product(*[m.domain[c] for c in ctx_names]):
            s = [m.init[n] for n in m.slots]
            for c, v in zip(ctx_names, vals):
                s[self.ix[c]] = v
            inits.append(self.norm(tuple(s)))
        queue = deque(inits)
        for s in inits:
            self.parent[s] = (None, "start")
        while queue:
            s = queue.popleft()
            self.check(s)
            if until and any(v.startswith(until) for v in self.violations):
                break
            for label, t in self.successors(s):
                if t not in self.parent:
                    self.parent[t] = (s, label)
                    queue.append(t)
                    if len(self.parent) > MAX_STATES:
                        raise CardError(f"{m.card}: more than {MAX_STATES} states; the model is too large to check")
        return len(self.parent)

    def unreachable(self) -> list[str]:
        """L9: everything declared is seen somewhere."""
        m, out = self.m, []
        for inst in m.instances:
            prim, iid = inst["primitive"], inst["id"]
            if ("present", iid) not in self.seen_flags:
                out.append(f"L9 {m.card}: {prim} {iid} is never present")
                continue
            if prim == "StateBlock":
                for st in inst["_states"]:
                    if ("state", iid, st) not in self.seen_flags:
                        out.append(f"L9 {m.card}: StateBlock {iid} never reaches `{st}`")
            if prim == "Metric" and ("shown", iid) not in self.seen_flags:
                out.append(f"L9 {m.card}: Metric {iid} never shows its value")
            if prim in ("Action", "Choice") or (prim == "Field" and inst.get("var")):
                if ("enabled", iid) not in self.seen_flags:
                    out.append(f"L9 {m.card}: {prim} {iid} is never enabled")
            if prim == "ConfirmSheet" and ("open", iid) not in self.seen_flags:
                out.append(f"L9 {m.card}: ConfirmSheet {iid} is never opened")
        for w in m.writes:
            if w in m.issued_by and ("issue", w) not in self.seen_flags:
                out.append(f"L9 {m.card}: write `{w}` is never issued")
        return out

    def trace(self, s) -> list[str]:
        steps = []
        while s is not None:
            prev, label = self.parent[s]
            steps.append(label)
            s = prev
        return list(reversed(steps))[1:]


# ─── the tie to the Kotlin: tags ─────────────────────────────────────────────

def kotlin_literals(path: Path) -> set[str]:
    src = path.read_text()
    out = set(TAG_CALL.findall(src)) | set(TAG_ARG.findall(src))
    for p in TAG_PREFIX_ARG.findall(src):
        out |= {f"sheet_{p}", f"{p}_note", f"btn_{p}_confirm", f"btn_{p}_cancel",
                f"{p}_fact_1", f"{p}_fact_2", f"{p}_fact_3"}
    return out


def check_kotlin(m: Model) -> list[str]:
    problems = []
    if not m.kotlin:
        return [f"{m.card}: csd:view names no `kotlin:` file, so no tag is tied to the code"]
    literals: set[str] = set()
    for rel in m.kotlin:
        f = ROOT / rel
        if not f.exists():
            problems.append(f"{m.card}: csd:view names `{rel}`, which does not exist")
            continue
        found = kotlin_literals(f)
        if not found:
            problems.append(f"{m.card}: no tag literal parsed from `{rel}`; the scan is broken or the file has none")
        literals |= found
    mine = {t for t in literals if f"_{m.surface}_" in f"_{t}_" or t.split("_", 1)[-1].startswith(m.surface + "_")}
    olds = {old for old, _ in m.was_map()}
    derived = set(m.all_tags())
    for t in sorted(mine - olds - derived):
        problems.append(f"{m.card}: Kotlin has tag `{t}` and the model accounts for it nowhere")
    for t in sorted(olds - literals):
        problems.append(f"{m.card}: the model says the old tag is `{t}` and no named Kotlin file has it")
    return problems


def scan_prefixes(reg: dict) -> dict[str, int]:
    """Tag literals in the client's Kotlin whose first word no primitive owns."""
    owned = set(reg["shell_prefixes"])
    for prim in reg["primitives"].values():
        owned |= set(prim["prefixes"])
    counts: dict[str, int] = {}
    files = 0
    for root in KOTLIN_ROOTS:
        for f in root.rglob("*.kt") if root.exists() else []:
            if "/build/" in str(f) or "Test/" in str(f):
                continue
            files += 1
            src = f.read_text()
            for tag in TAG_CALL.findall(src) + TAG_ARG.findall(src):
                first = re.split(r"[_$.{]", tag, maxsplit=1)[0]
                if first and first not in owned:
                    counts[first] = counts.get(first, 0) + 1
    if files and not counts:
        raise CardError("prefix scan: Kotlin files were read and no unowned tag was found; the scan is broken")
    return dict(sorted(counts.items()))


# ─── TLA+ ────────────────────────────────────────────────────────────────────

def _q(v: str) -> str:
    return '"' + v + '"'


def _set(vals) -> str:
    return "{" + ", ".join(_q(v) for v in vals) + "}"


class Tla:
    """The same model as one self-contained TLA+ module. Every value is a string
    and the whole state is one record, so each transition is a function."""

    def __init__(self, m: Model, ex: Explorer):
        self.m, self.ex = m, ex
        self.name = m.card.replace("-", "")

    def g(self, tree, s="s", carry_as=None) -> str:
        kind = tree[0]
        if kind == "const":
            return "TRUE" if tree[1] else "FALSE"
        if kind == "and":
            return f"({self.g(tree[1], s, carry_as)} /\\ {self.g(tree[2], s, carry_as)})"
        if kind == "or":
            return f"({self.g(tree[1], s, carry_as)} \\/ {self.g(tree[2], s, carry_as)})"
        if kind == "not":
            return f"(~{self.g(tree[1], s, carry_as)})"
        name = tree[1]
        if carry_as and self.m.kind[name] == "carry":
            term = f"{carry_as}.{name.split('.')[1]}"
        elif carry_as and self.m.kind[name] == "read":
            term = '"ok"'
        else:
            term = f"{s}.{self.m.tla[name]}"
        if kind == "eq":
            return f"({term} = {_q(tree[2])})"
        if kind == "ne":
            return f"({term} # {_q(tree[2])})"
        return f"({term} \\in {_set(tree[2])})"

    def f(self, name):
        return self.m.tla[name]

    def _snap_pairs(self, ans, s="s"):
        m = self.m
        out = []
        for v in m.answers[ans]:
            if m.locals[v]["type"] == "text":
                out.append(f'!.{self.f(f"{ans}@{v}")} = IF {s}.{self.f(v)} = "blank" THEN "blank" ELSE "same"')
            else:
                out.append(f'!.{self.f(f"{ans}@{v}")} = {s}.{self.f(v)}')
        return out

    def _unsnap_pairs(self, ans):
        return [f'!.{self.f(f"{ans}@{v}")} = "-"' for v in self.m.answers[ans]]

    def _reset_pairs(self, v):
        m = self.m
        out = [f"!.{self.f(v)} = {_q(m.init[v])}"]
        if m.locals[v]["type"] == "text":
            for ans, over in m.answers.items():
                if v in over:
                    out.append(f'!.{self.f(f"{ans}@{v}")} = IF @ \\in {{"same", "diff"}} THEN "diff" ELSE @')
        return out

    def _drop_pairs(self, name):
        m = self.m
        if name in m.reads:
            return ([f'!.{self.f(name)} = "idle"']
                    + [f'!.{self.f(f"{name}.{c}")} = "-"' for c in m.reads[name].get("carries") or {}]
                    + self._unsnap_pairs(name))
        if name in m.previews:
            return [f'!.{self.f(name)} = "none"'] + self._unsnap_pairs(name)
        return self._reset_pairs(name)

    @staticmethod
    def _ex(s, pairs):
        return f"[{s} EXCEPT " + ", ".join(pairs) + "]" if pairs else s

    def eff(self, e, s) -> str:
        """One deterministic effect applied to the state expression `s`."""
        kind = _kind(e)
        arg = e[kind]
        if kind == "set":
            for var, val in arg.items():
                val = str(val).lower()
                s = f"Drop_{var}({s})" if self.m.locals[var]["type"] == "text" else f"Set_{var}_{val}({s})"
                self.used.add(("drop", var) if self.m.locals[var]["type"] == "text" else ("set", var, val))
            return s
        if kind == "toggle":
            self.used.add(("toggle", arg))
            return f"Toggle_{arg}({s})"
        if kind == "ask":
            self.used.add(("ask", arg))
            return f"Ask_{arg}({s})"
        if kind == "drop":
            for n in arg:
                self.used.add(("drop", n))
                s = f"Drop_{n}({s})"
            return s
        if kind == "open":
            self.used.add(("open", arg))
            return f"Open_{arg}({s})"
        return s    # leave

    def seq(self, effs, s) -> str:
        """The set of states a list of effects can end in, starting from `s`."""
        effs = list(effs or [])
        for i, e in enumerate(effs):
            if "issue" in e or "call" in e:
                rest = effs[i + 1:]
                bad, good = s, s
                if "call" in e:
                    self.used.add(("drop", e["call"]))
                    self.used.add(("call", e["call"]))
                    bad, good = f"Drop_{e['call']}({s})", f"Call_{e['call']}({s})"
                parts = [self.seq(list(e.get("on_fail") or []) + rest, bad),
                         self.seq(list(e.get("on_ok") or []) + rest, good)]
                if "on_refused" in e:
                    parts.append(self.seq(list(e["on_refused"] or []) + rest, bad))
                return "(" + " \\cup ".join(parts) + ")"
            s = self.eff(e, s)
        return "{" + s + "}"

    def module(self, states: int) -> str:
        m, ex = self.m, self.ex
        self.used: set[tuple] = set()
        L: list[str] = []
        add = L.append
        actions: list[str] = []
        norm = "st"
        k = max(1, ex.norm_passes)

        def nk(expr):
            for _ in range(k):
                expr = f"Norm({expr})"
            return expr

        def into(setexpr):
            return "st' \\in {" + nk("t") + " : t \\in " + setexpr + "}"

        body: list[str] = []
        # reads answered by the node
        for r, rb in m.reads.items():
            carries = list(rb.get("carries") or {})
            fields = ", ".join(f"{c} : {_set(m.domain[f'{r}.{c}'])}" for c in carries)
            where = " /\\ ".join(self.g(w, "s", carry_as="a") for w in rb["_where"]) or "TRUE"
            body.append(f"Answers_{r} == {{a \\in [{fields}] : {where}}}")
            sets = ", ".join([f'!.{self.f(r)} = "ok"'] + [f"!.{self.f(f'{r}.{c}')} = a.{c}" for c in carries])
            body.append(
                f"Answer_{r} ==\n"
                f"    /\\ st.{self.f(r)} = \"pending\"\n"
                f"    /\\ \\/ st' = {nk(f'[st EXCEPT !.{self.f(r)} = \"failed\"]')}\n"
                f"       \\/ \\E a \\in Answers_{r} : st' = {nk(f'[st EXCEPT {sets}]')}")
            actions.append(f"Answer_{r}")
        # sheets
        for inst in m.instances:
            if inst["primitive"] != "ConfirmSheet":
                continue
            iid = inst["id"]
            body.append(f"Confirm_{iid} ==\n    /\\ st.sheet = {_q(iid)}\n    /\\ "
                        + into(self.seq(inst.get("confirm"), "Close(st)")))
            body.append(f"Cancel_{iid} ==\n    /\\ st.sheet = {_q(iid)}\n    /\\ "
                        + into(self.seq(inst.get("cancel"), "Close(st)")))
            actions += [f"Confirm_{iid}", f"Cancel_{iid}"]
        # the person
        for inst in m.instances:
            prim, iid = inst["primitive"], inst["id"]
            if prim not in ("Field", "Choice", "Action") or (prim == "Field" and not inst.get("var")):
                continue
            pre = (f"    /\\ st.sheet = \"none\"\n    /\\ {self.g(inst['_present'], 'st')}\n"
                   f"    /\\ {self.g(inst['_enabled'], 'st')}\n")
            if prim == "Action":
                body.append(f"Press_{iid} ==\n{pre}    /\\ " + into(self.seq(inst.get("effect"), "st")))
                actions.append(f"Press_{iid}")
            elif prim == "Choice":
                var = inst["var"]
                start = f"[st EXCEPT !.{self.f(var)} = v]"
                body.append(f"Pick_{iid} ==\n{pre}    /\\ \\E v \\in {_set(m.domain[var])} \\ {{st.{self.f(var)}}} :\n         "
                            + into(self.seq(inst.get("on_change"), start)))
                actions.append(f"Pick_{iid}")
            else:
                var = inst["var"]
                over = [a for a, vs in m.answers.items() if var in vs]
                binds = "".join(f" \\E r{i} \\in Rel(st.{self.f(f'{a}@{var}')}, c) :" for i, a in enumerate(over))
                pairs = [f"!.{self.f(var)} = c"] + [f"!.{self.f(f'{a}@{var}')} = r{i}" for i, a in enumerate(over)]
                start = self._ex("st", pairs)
                kept = [f"r{i} = st.{self.f(f'{a}@{var}')}" for i, a in enumerate(over)]
                pinned = (" \\/ (" + " /\\ ".join(kept) + " /\\ \"same\" \\in {"
                          + ", ".join(f"r{i}" for i in range(len(over))) + "})") if over else ""
                changed = f"~(c = st.{self.f(var)} /\\ (c = \"blank\"{pinned}))"
                body.append(f"Edit_{iid} ==\n{pre}    /\\ \\E c \\in {{\"blank\", \"filled\"}} :{binds}\n"
                            f"         /\\ {changed}\n         /\\ "
                            + into(self.seq(inst.get("on_change"), start)))
                actions.append(f"Edit_{iid}")

        # helper operators, only the ones something uses
        helpers: list[str] = []
        for u in sorted(self.used):
            if u[0] == "set":
                helpers.append(f"Set_{u[1]}_{u[2]}(s) == [s EXCEPT !.{self.f(u[1])} = {_q(u[2])}]")
            elif u[0] == "toggle":
                helpers.append(f'Toggle_{u[1]}(s) == [s EXCEPT !.{self.f(u[1])} = IF @ = "true" THEN "false" ELSE "true"]')
            elif u[0] == "ask":
                pairs = self._drop_pairs(u[1])
                pairs[0] = f'!.{self.f(u[1])} = "pending"'
                pairs = [p for p in pairs if not p.startswith("!.q_")] + self._snap_pairs(u[1])
                helpers.append(f"Ask_{u[1]}(s) == " + self._ex("s", pairs))
            elif u[0] == "call":
                helpers.append(f"Call_{u[1]}(s) == "
                               + self._ex("s", [f'!.{self.f(u[1])} = "taken"'] + self._snap_pairs(u[1])))
            elif u[0] == "open":
                helpers.append(f"Open_{u[1]}(s) == [s EXCEPT !.sheet = {_q(u[1])}]")
        dropped = {u[1] for u in self.used if u[0] == "drop"}
        for kind, name, _ in ex.rules:
            if kind in ("local", "preview"):
                dropped.add(name)
        for n in sorted(dropped):
            helpers.append(f"Drop_{n}(s) == " + self._ex("s", self._drop_pairs(n)))

        # normalisation
        steps = []
        prev = "s"
        for i, (kind, name, _) in enumerate(ex.rules):
            cur = f"n{i + 1}"
            if kind == "local":
                alive = self.g(m.locals[name]["_lives"], prev)
                steps.append(f"{cur} == IF {alive} THEN {prev} ELSE Drop_{name}({prev})")
            elif kind == "preview":
                alive = self.g(m.previews[name]["_lives"], prev)
                steps.append(f"{cur} == IF {alive} THEN {prev} ELSE Drop_{name}({prev})")
            else:
                inst = next(x for x in m.instances if x["primitive"] == "ConfirmSheet" and x["id"] == name)
                alive = self.g(inst["_while"], prev)
                steps.append(f"{cur} == IF {alive} \\/ {prev}.sheet # {_q(name)} THEN {prev} ELSE Close({prev})")
            prev = cur
        if steps:
            norm_def = "Norm(s) ==\n    LET " + "\n        ".join(steps) + f"\n    IN  {prev}"
        else:
            norm_def = "Norm(s) == s"

        # matching
        match_defs = []
        for ans, over in m.answers.items():
            terms = []
            for v in over:
                q, cur = f"s.{self.f(f'{ans}@{v}')}", f"s.{self.f(v)}"
                if m.locals[v]["type"] == "text":
                    terms.append(f'(({q} = "blank" /\\ {cur} = "blank") \\/ {q} = "same")')
                else:
                    terms.append(f"({q} = {cur})")
            match_defs.append(f"Match_{ans}(s) == " + (" /\\ ".join(terms) or "TRUE"))

        # invariants
        invs: list[tuple[str, str]] = []
        l11 = []
        for ans in m.answers:
            held = f'st.{self.f(ans)} # "idle"' if ans in m.reads else f'st.{self.f(ans)} = "taken"'
            l11.append(f"({held} => Match_{ans}(st))")
        if l11:
            invs.append(("Inv_L11_answer_is_about_its_question", "\n    /\\ ".join(l11)))
        def can_send(by):
            if by["primitive"] == "ConfirmSheet":
                return f"st.sheet = {_q(by['id'])}"
            return (f'(st.sheet = "none" /\\ {self.g(by["_present"], "st")} /\\ {self.g(by["_enabled"], "st")})')

        for w, issuers in m.issued_by.items():
            prev_name = m.writes[w].get("previewed_by")
            if prev_name:
                pre = " \\/ ".join(can_send(by) for by in issuers)
                pre = f"({pre})" if len(issuers) > 1 else pre
                invs.append((f"Inv_L7_{w}_preview_is_execution",
                             f'{pre} => (st.{self.f(prev_name)} = "taken" /\\ Match_{prev_name}(st))'))
            sent = list(m.writes[w].get("sends") or []) + list(m.writes[w].get("adds") or [])
            for by in issuers:
                drawn_now = []
                for v in sent:
                    if v in (by.get("shows") or []):
                        continue
                    fields = [self.g(f["_present"], "st") for f in m.binders.get(v, [])]
                    drawn_now.append("(" + " \\/ ".join(fields) + ")" if fields else "FALSE")
                if drawn_now:
                    invs.append((f"Inv_L12_{w}_from_{by['id']}_shown_is_sent",
                                 f"{can_send(by)} =>\n    /\\ " + "\n    /\\ ".join(drawn_now)))
        drawn = []
        for inst in m.instances:
            prim, iid, of = inst["primitive"], inst["id"], inst.get("of")
            present = self.g(inst["_present"], "st")
            if prim == "StateBlock":
                gs = {st: self.g(g, "st") for st, g in inst["_states"].items()}
                names = list(gs)
                some = " \\/ ".join(gs[n] for n in names)
                pairs = [f"~({gs[a]} /\\ {gs[b]})" for a, b in itertools.combinations(names, 2)]
                invs.append((f"Inv_L2_{iid}_exactly_one_state",
                             f"{present} =>\n    /\\ ({some})\n    /\\ " + "\n    /\\ ".join(pairs)))
                honest = [f"({gs[n]} => st.{self.f(of)} = {_q(m.reg['states'][n]['outcome'])})" for n in names]
                invs.append((f"Inv_L3_{iid}_honest_zero", f"{present} =>\n    /\\ " + "\n    /\\ ".join(honest)))
            elif prim == "Metric":
                sent = inst.get("sent")
                need = f'st.{self.f(of)} = "ok"' + (f' /\\ st.{self.f(sent)} = "true"' if sent else "")
                invs.append((f"Inv_L4_{iid}_honest_number",
                             f"({present} /\\ {self.g(inst['_shown'], 'st')}) => ({need})"))
            elif of is not None and prim != "ConfirmSheet":
                want = '"ok"' if of in m.reads else '"taken"'
                drawn.append(f"({present} => st.{self.f(of)} = {want})")
        if drawn:
            invs.append(("Inv_L3_drawn_only_when_answered", "\n    /\\ ".join(drawn)))

        # init
        fields = []
        binders = []
        for n in m.slots:
            if m.kind[n] == "context":
                fields.append(f"{self.f(n)} |-> {n}0")
                binders.append(f"{n}0 \\in {_set(m.domain[n])}")
            else:
                fields.append(f"{self.f(n)} |-> {_q(m.init[n])}")
        rec = "[" + ",\n         ".join(fields) + "]"
        init = (f"Init == st \\in {{\n        {rec} :\n        " + ", ".join(binders) + "}"
                if binders else f"Init == st =\n        {rec}")

        bar = "-" * 28
        add(f"{bar} MODULE {self.name} {bar}")
        add(f"(* {m.card}, the screen as a function of its inputs. GENERATED by")
        add("   packaging/check_csd5.py --write-tla from the card's csd:inputs, csd:view and")
        add("   csd:acts blocks; edit the card, not this file. The state is one record of")
        add("   strings: x_ context, l_ local values, r_ read outcomes, c_ what an answer")
        add("   carries, p_ previews, q_ the inputs an answer was taken over, and `sheet`.")
        add(f"   The Python explorer reaches {states} distinct states with this model; TLC")
        add("   should report the same number. See formal/csd5/README.md. *)")
        add("VARIABLE st")
        add("")
        add("\\* What a text input's relation to an answer taken over it can become when the")
        add("\\* person edits it to blank or filled: `-` no answer is held, `blank` the asked")
        add("\\* value was blank, `same` and `diff` the asked value was filled.")
        add('Rel(q, c) == IF q = "-" THEN {"-"} ELSE IF q = "blank" THEN {"blank"}')
        add('             ELSE IF c = "blank" THEN {"diff"} ELSE {"same", "diff"}')
        add('Close(s) == [s EXCEPT !.sheet = "none"]')
        L.extend(helpers)
        L.extend(match_defs)
        add("")
        add("\\* A value that lives only while its part of the screen is drawn returns to its")
        add(f"\\* start when it is not. Applied {k} time(s): the explorer never needed more.")
        add(norm_def)
        add("")
        add(init)
        add("")
        for b in body:
            add(b)
            add("")
        add("Next ==\n    \\/ " + "\n    \\/ ".join(actions))
        add("Spec == Init /\\ [][Next]_st")
        add("")
        for name, expr in invs:
            add(f"{name} ==\n    {expr}")
        add("=" * (58 + len(self.name)))
        cfg = "SPECIFICATION Spec\nCHECK_DEADLOCK FALSE\nINVARIANTS\n" + "".join(f"    {n}\n" for n, _ in invs)
        return "\n".join(L) + "\n", cfg


# ─── running ─────────────────────────────────────────────────────────────────

def check_card(path: Path, reg: dict, verbose=False):
    """(problems, model, explorer, state count)."""
    card = path.name[:7]
    m = Model(card, read_blocks(path), reg)
    ex = Explorer(m)
    n = ex.explore()
    problems = list(m.problems)
    for msg, s in ex.violations.items():
        steps = ex.trace(s)
        problems.append(msg + "\n      reached by: " + (" -> ".join(steps) if steps else "(the first state)"))
    problems += ex.unreachable()
    return problems, m, ex, n


def write_tla(m: "Model", ex: "Explorer", n: int, formal: Path) -> None:
    t = Tla(m, ex)
    tla, cfg = t.module(n)
    formal.mkdir(parents=True, exist_ok=True)
    (formal / f"{t.name}.tla").write_text(tla)
    (formal / f"{t.name}.cfg").write_text(cfg)


def tla_drift(m: "Model", ex: "Explorer", n: int, formal: Path) -> list[str]:
    """The committed module is the generated one, byte for byte, or the gate fails."""
    t = Tla(m, ex)
    tla, cfg = t.module(n)
    for name, want in ((f"{t.name}.tla", tla), (f"{t.name}.cfg", cfg)):
        path = formal / name
        if not path.exists() or path.read_text() != want:
            return [f"TLA+ {m.card}: {name} under {formal.name}/ is not what the card generates; "
                    "run packaging/check_csd5.py --write-tla"]
    return []


TLC_DISTINCT = re.compile(r"(\d+) distinct states found, 0 states left on queue")
TLC_VIOLATED = re.compile(r"Invariant (\w+) is violated")


def run_tlc(jar: Path, formal: Path, name: str) -> tuple[int | None, list[str], str]:
    """TLC on `<name>.tla` under `formal`, run in a copy so nothing is written
    beside the module. Returns (distinct states when the search closed,
    invariants violated, the tail of the output)."""
    if shutil.which("java") is None:
        raise CardError("TLC: no `java` on the path")
    if not jar.is_file():
        raise CardError(f"TLC: {jar} is not a file")
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp) / "m"
        work.mkdir()
        for ext in ("tla", "cfg"):
            shutil.copy(formal / f"{name}.{ext}", work / f"{name}.{ext}")
        r = subprocess.run(
            ["java", "-XX:+UseParallelGC", "-cp", str(jar.resolve()), "tlc2.TLC", "-workers", "auto",
             "-metadir", str(Path(tmp) / "meta"), "-config", f"{name}.cfg", f"{name}.tla"],
            cwd=work, capture_output=True, text=True, timeout=1800)
    out = r.stdout + r.stderr
    found = TLC_DISTINCT.search(out)
    closed = found and "No error has been found" in out
    return (int(found.group(1)) if closed else None), TLC_VIOLATED.findall(out), "\n".join(out.splitlines()[-12:])


def tlc_agrees(jar: Path, m: "Model", n: int, formal: Path) -> list[str]:
    """TLC closes the committed module with no violation and the explorer's state count."""
    name = m.card.replace("-", "")
    distinct, violated, tail = run_tlc(jar, formal, name)
    if violated:
        return [f"TLC {m.card}: {name}.tla violates {', '.join(violated)}"]
    if distinct is None:
        return [f"TLC {m.card}: the search of {name}.tla did not close:\n{tail}"]
    if distinct != n:
        return [f"TLC {m.card}: TLC finds {distinct} distinct states and the explorer {n}; "
                "the generator and the explorer disagree about the card"]
    print(f"[OK] TLC {m.card}: {distinct} distinct states, no invariant violated")
    return []


def ratchet(unowned: dict[str, int], debts: dict[str, list[str]], baseline: dict) -> list[str]:
    """Both recorded counts may only fall."""
    out = []
    base = baseline.get("unowned_prefixes", {})
    for p, n in unowned.items():
        if p not in base:
            out.append(f"L1 ratchet: {n} tag(s) start with `{p}`, a prefix no primitive owns and the baseline does not list")
        elif n > base[p]:
            out.append(f"L1 ratchet: `{p}` tags rose from {base[p]} to {n}; an unowned prefix may only shrink")
    for card, ids in debts.items():
        new = sorted(set(ids) - set(baseline.get("undrawn_reasons", {}).get(card, [])))
        if new:
            out.append(f"L5 ratchet: {card} adds disabled Actions that draw no reason: {new}")
    return out


def csd5_cards() -> list[Path]:
    return [p for p in sorted(CARDS.glob("CSD-*.md")) if is_csd5(p)]


def gate(args) -> int:
    reg = load_registry()
    problems = check_registry(reg)
    cards = [Path(args.card)] if args.card else csd5_cards()
    if not cards:
        problems.append("no card carries a csd:inputs block; CSD/5 checks nothing")
    baseline = json.loads(BASELINE.read_text()) if BASELINE.exists() else {"unowned_prefixes": {}, "undrawn_reasons": {}}
    debts: dict[str, list[str]] = {}
    for path in cards:
        if not is_csd5(path):
            # Asked for by name: saying nothing would read as a pass.
            problems.append(f"{path.name}: no csd:inputs block. This card has not adopted CSD/5, so nothing was checked")
            continue
        try:
            probs, m, ex, n = check_card(path, reg)
        except CardError as e:
            problems.append(str(e))
            continue
        problems += probs
        problems += check_kotlin(m)
        debts[m.card] = m.debts()
        if args.write_tla:
            write_tla(m, ex, n, FORMAL)
        else:
            drift = tla_drift(m, ex, n, FORMAL)
            problems += drift
            if args.tlc and not drift and not probs:
                try:
                    problems += tlc_agrees(Path(args.tlc), m, n, FORMAL)
                except (CardError, subprocess.TimeoutExpired) as e:
                    problems.append(str(e))
        tags = m.all_tags()
        print(f"[{'OK' if not probs else 'FAIL'}] {m.card} {m.surface}: {n} states, "
              f"{len(m.instances)} instances, {len(tags)} tags, {len(m.debts())} undrawn reasons")
    try:
        unowned = scan_prefixes(reg)
    except CardError as e:
        problems.append(str(e))
        unowned = {}
    if args.write_baseline:
        BASELINE.write_text(json.dumps({
            "_meta": "Recorded by packaging/check_csd5.py --write-baseline. Both counts may only fall. "
                     "`unowned_prefixes` is tag literals in the client's Kotlin whose first word no primitive "
                     "owns; `undrawn_reasons` is Actions that can be disabled and draw no reason (L5).",
            "unowned_prefixes": unowned, "undrawn_reasons": debts}, indent=1) + "\n")
        print(f"baseline written: {sum(unowned.values())} tags under {len(unowned)} unowned prefixes")
    else:
        base = baseline.get("unowned_prefixes", {})
        problems += ratchet(unowned, debts, baseline)
        lower = sum(1 for p, n in base.items() if unowned.get(p, 0) < n)
        print(f"unowned prefixes: {len(unowned)} prefixes, {sum(unowned.values())} tags "
              f"(baseline {sum(base.values())}{'; ' + str(lower) + ' can be lowered' if lower else ''})")
    for p in problems:
        print("FAIL", p)
    print("csd5:", "FAIL" if problems else "ok", f"({len(cards)} card(s))")
    return 1 if problems else 0


def show_tags(card_id: str) -> int:
    reg = load_registry()
    path = next((p for p in csd5_cards() if p.name.startswith(card_id)), None)
    if path is None:
        print(f"no CSD/5 card matches {card_id}")
        return 1
    m = Model(path.name[:7], read_blocks(path), reg)
    was = m.was_map()
    width = max(len(o) for o, _ in was) if was else 10
    for old, new in sorted(was):
        print(f"{old:{width}}  {'=' if old == new else '->'}  {new}")
    renamed = {n for o, n in was if o != n}
    print(f"\n{len(was)} old tags; {sum(1 for o, n in was if o == n)} unchanged, {len(renamed)} derived tags replace the rest.")
    new_only = sorted(set(m.all_tags()) - {n for _, n in was})
    print(f"{len(new_only)} derived tags have no old tag: " + ", ".join(new_only))
    return 0


# ─── self-test ───────────────────────────────────────────────────────────────

def self_test(tlc: str | None = None) -> int:
    reg = load_registry()
    path = next((p for p in csd5_cards() if p.name.startswith("CSD-070")), None)
    if path is None:
        print("self-test needs CSD-070, the worked card")
        return 1
    clean = read_blocks(path)

    def inst(blocks, iid):
        pool = (blocks["view"]["instances"]) + (blocks["acts"]["acts"])
        return next(i for i in pool if i["id"] == iid)

    def strip_drop(effs, name):
        return [dict(e, drop=[n for n in e["drop"] if n != name]) if "drop" in e else e for e in effs]

    def run(mutate, regm=None, law=None):
        """Problems of the mutated card. With `law`, stop as soon as one under it is found."""
        blocks = copy.deepcopy(clean)
        r = copy.deepcopy(reg)
        if regm:
            regm(r)
        problems = check_registry(r)
        try:
            mutate(blocks)
            m = Model("CSD-070", blocks, r)
            problems += m.problems + check_kotlin(m)
            if law and any(p.startswith(law) or (law == "Kotlin" and "Kotlin" in p) for p in problems):
                return problems
            ex = Explorer(m)
            ex.explore(until=law)
            problems += list(ex.violations)
            if not (law and ex.violations):
                problems += ex.unreachable()
        except CardError as e:
            problems.append(str(e))
        return problems

    def edits_keep_preview(b):
        f = inst(b, "dismiss_grounds")
        f["on_change"] = strip_drop(f["on_change"], "dry_run")

    def edits_keep_read(b):
        f = inst(b, "cohort_key")
        f["on_change"] = strip_drop(f["on_change"], "standing")

    def submit_without_preview(b):
        inst(b, "dismiss_submit")["enabled"] = "dismiss_grounds != blank"

    def two_states(b):
        inst(b, "standing")["states"]["empty"] = "answered and standing.arm = quiet"

    def error_as_idle(b):
        st = inst(b, "standing")["states"]
        st["error"], st["idle"] = st["idle"], st["error"]

    def number_never_sent(b):
        inst(b, "lift_price")["shown"] = "true"

    def stale_chip(b):
        inst(b, "arm")["present"] = "true"

    def write_without_sheet(b):
        inst(b, "object")["effect"] = [{"issue": "raise"}]

    def undeclared_name(b):
        inst(b, "back")["present"] = "window = wide and moon = full"

    def unreachable(b):
        inst(b, "escalation")["present"] = "standing = failed and standing.escalation"

    def duplicate_tag(b):
        b["acts"]["acts"].append({"id": "read", "effect": [{"leave": True}]})

    def silent_refusal(b):
        inst(b, "read").pop("reason")

    def bare_media(b):
        b["view"]["instances"].append({"id": "poster", "primitive": "Media", "per": "item"})

    def phantom_old_tag(b):
        inst(b, "read")["was"] = "btn_commons_reed"

    def unsent_write_value(b):
        b["inputs"]["writes"]["dismiss"]["sends"].append("ballot_grounds")

    def two_owners(r):
        r["primitives"]["Chip"]["prefixes"].append("btn")

    def confirm_unsaid(b):
        b["inputs"]["writes"]["raise"].pop("confirm")

    def row_in_no_family(b):
        b["inputs"]["writes"]["raise"]["row"]["family"] = "objection:{mood}"

    def ballot_way_not_shown(b):
        inst(b, "ballot")["shows"].remove("ballot")

    def cosigners_not_shown(b):
        inst(b, "dismiss")["shows"].remove("cosigners")

    def grounds_hidden_at_confirm(b):
        # The field goes away once the dry run is held, and the sheet does not show the grounds either.
        f = inst(b, "dismiss_grounds")
        f["present"] = "lifting and has_objections and dry_run != taken"

    def write_on_a_keystroke(b):
        inst(b, "objection_grounds")["on_change"] = [{"issue": "raise"}]

    def exit_without_a_confirm(b):
        # A withdrawal: it narrows and has a way back, so the Action sends it in one
        # step (L6), and everything it sends is in a field that is on screen (L12).
        b["inputs"]["writes"]["withdraw"] = {
            "sends": ["cohort", "cohort_key", "action"],
            "row": {"family": "objection:{state}", "verb": "withdraw"}, "confirm": False}
        b["acts"]["acts"].append({"id": "withdraw", "present": "has_objections", "effect": [
            {"issue": "withdraw", "on_ok": [{"ask": "standing"}]}]})

    def exit_behind_a_confirm(b):
        exit_without_a_confirm(b)
        inst(b, "withdraw")["effect"] = [{"open": "withdraw"}]
        b["view"]["instances"].append({"id": "withdraw", "primitive": "ConfirmSheet", "while": "has_objections",
                                       "confirm": [{"issue": "withdraw"}]})

    def exit_sends_what_is_not_drawn(b):
        exit_without_a_confirm(b)
        b["inputs"]["writes"]["withdraw"]["sends"].append("ballot")

    planted = [
        ("L11", "editing the dismissal grounds keeps the dry run (what shipped before this card)", edits_keep_preview, None),
        ("L11", "editing the cohort key keeps the standing that was read for the old one", edits_keep_read, None),
        ("L7", "the dismissal can be submitted with no dry run", submit_without_preview, None),
        ("L7", "the dismissal sends a value its dry run did not cover", unsent_write_value, None),
        ("L2", "`empty` and `populated` both hold when `quiet` arrives without counts", two_states, None),
        ("L3", "`error` is drawn before anything was asked", error_as_idle, None),
        ("L3", "the standing chip stays drawn while the read is loading or failed", stale_chip, None),
        ("L4", "the lift price shows a number the node did not send", number_never_sent, None),
        ("L5", "a button that can be disabled says nothing about why", silent_refusal, None),
        ("L6", "the raise button sends a write that must be confirmed without a ConfirmSheet", write_without_sheet, None),
        ("L6", "an exit is put behind a confirm", exit_behind_a_confirm, None),
        ("L6", "a write that does not say whether it is confirmed", confirm_unsaid, None),
        ("L6", "a write whose row is in no family of the pinned registry", row_in_no_family, None),
        ("L12", "the ballot confirm no longer says which way the ballot goes", ballot_way_not_shown, None),
        ("L12", "the dismissal confirm no longer says how many co-signed", cosigners_not_shown, None),
        ("L12", "the dismissal grounds are off screen when the dismissal is confirmed", grounds_hidden_at_confirm, None),
        ("L12", "a write is sent on a keystroke", write_on_a_keystroke, None),
        ("L12", "an exit sends a value nothing draws", exit_sends_what_is_not_drawn, None),
        ("L8", "a guard reads something the card does not declare", undeclared_name, None),
        ("L9", "an instance that can never be present", unreachable, None),
        ("L10", "a Media instance with no alt text or AI disclosure", bare_media, None),
        ("L1", "two instances derive one tag", duplicate_tag, None),
        ("L1", "a prefix with two owners", lambda b: None, two_owners),
        ("Kotlin", "the model names an old tag the Kotlin does not have", phantom_old_tag, None),
    ]
    bad = 0
    base = run(lambda b: None)
    if base:
        print("self-test: the unplanted card is not clean, so nothing below can be believed:")
        for p in base:
            print("   ", p)
        return 1
    for law, what, mutate, regm in planted:
        got = [p for p in run(mutate, regm, law) if p.startswith(law) or (law == "Kotlin" and "Kotlin" in p)]
        mark = "red" if got else "STAYED GREEN"
        bad += 0 if got else 1
        print(f"[{mark}] {law}: {what}")
        if got:
            print(f"        {got[0].splitlines()[0]}")
    # The other half of L6: an exit must NOT be made to pass through a confirm.
    got_exit = run(exit_without_a_confirm)
    bad += 1 if got_exit else 0
    print(f"[{'REFUSED' if got_exit else 'accepted'}] L6: a write that takes one step is sent by an Action with no confirm")
    for line in got_exit:
        print(f"        {line.splitlines()[0]}")
    accepted = 1
    # The three checks that compare against something on disk, each run on a tampered copy.
    probs, m, ex, n = check_card(path, reg)
    extra = []
    with tempfile.TemporaryDirectory() as tmp:
        formal = Path(tmp) / "csd5"
        write_tla(m, ex, n, formal)
        fresh = tla_drift(m, ex, n, formal)
        mod = formal / f"{Tla(m, ex).name}.tla"
        text = mod.read_text()
        cut = "\n".join(l for l in text.splitlines() if not l.startswith("Inv_L7")) + "\n"
        mod.write_text(cut)
        extra.append(("TLA+", "the committed module has lost its L7 invariant",
                      [] if fresh or cut == text else tla_drift(m, ex, n, formal)))
    unowned = scan_prefixes(reg)
    held = {"unowned_prefixes": dict(unowned), "undrawn_reasons": {m.card: m.debts()}}
    if ratchet(unowned, {m.card: m.debts()}, held):
        extra.append(("L1 ratchet", "the ratchet is not quiet against its own baseline", []))
    else:
        some = next(iter(unowned), None)
        extra.append(("L1 ratchet", "one more tag under a prefix no primitive owns",
                      ratchet({**unowned, some: unowned[some] + 1}, {}, held) if some else []))
        extra.append(("L1 ratchet", "a new prefix no primitive owns",
                      ratchet({**unowned, "zzz": 1}, {}, held)))
        extra.append(("L5 ratchet", "one more disabled Action that draws no reason",
                      ratchet(unowned, {m.card: m.debts() + ["zzz"]}, held)))
    if tlc:
        # TLC must go red on a twin of the module, by the invariant of the law that was broken.
        twins = [("L11", "Inv_L11_", edits_keep_preview), ("L7", "Inv_L7_", submit_without_preview),
                 ("L12", "Inv_L12_", grounds_hidden_at_confirm), ("L2", "Inv_L2_", two_states)]
        for law, inv, mutate in twins:
            blocks = copy.deepcopy(clean)
            mutate(blocks)
            tm = Model("CSD-070", blocks, reg)
            tex = Explorer(tm)
            tn = tex.explore()
            with tempfile.TemporaryDirectory() as tmp:
                write_tla(tm, tex, tn, Path(tmp))
                try:
                    _, violated, tail = run_tlc(Path(tlc), Path(tmp), "CSD070")
                except (CardError, subprocess.TimeoutExpired) as e:
                    violated, tail = [], str(e)
            hit = [v for v in violated if v.startswith(inv)]
            extra.append(("TLC", f"TLC on a module generated from a card that breaks {law}",
                          [f"TLC: {hit[0]} is violated"] if hit else []))
            if not hit:
                print(f"        TLC said: {tail}")
        # And on the clean module it must disagree with a state count that is not the explorer's.
        try:
            off = tlc_agrees(Path(tlc), m, n + 1, FORMAL)
        except (CardError, subprocess.TimeoutExpired) as e:
            off = [f"error: {e}"]
        extra.append(("TLC", "the explorer's state count is off by one from TLC's", off))
    for law, what, got in extra:
        got = [p for p in got if p.startswith(law)]
        bad += 0 if got else 1
        print(f"[{'red' if got else 'STAYED GREEN'}] {law}: {what}")
        if got:
            print(f"        {got[0].splitlines()[0]}")
    total = len(planted) + len(extra)
    red = total - (bad - (1 if got_exit else 0))
    print(f"self-test: {red}/{total} planted defects went red; "
          f"{accepted - (1 if got_exit else 0)}/{accepted} permitted exits were accepted")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("card", nargs="?", help="one card file; default is every CSD/5 card")
    ap.add_argument("--tags", metavar="CSD-NNN", help="print old tag -> derived tag for one card")
    ap.add_argument("--write-tla", action="store_true")
    ap.add_argument("--write-baseline", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--tlc", metavar="JAR", help="tla2tools.jar: also run TLC on each committed module")
    args = ap.parse_args()
    if args.self_test:
        return self_test(args.tlc)
    if args.tags:
        return show_tags(args.tags)
    return gate(args)


if __name__ == "__main__":
    sys.exit(main())
