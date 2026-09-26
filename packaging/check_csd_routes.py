#!/usr/bin/env python3
"""Which routes does each card call, and which CSD says so?

    python3 packaging/check_csd_routes.py                 # gate: fail on NEW uncited routes / duplicate mutations
    python3 packaging/check_csd_routes.py --report        # the full map, markdown, to stdout
    python3 packaging/check_csd_routes.py --json          # the same, machine-readable
    python3 packaging/check_csd_routes.py --list          # every uncited route and duplicate, exit 0
    python3 packaging/check_csd_routes.py --baseline      # re-record packaging/csd_routes_baseline.json
    python3 packaging/check_csd_routes.py --print CSD-039 # §3 rows for that CSD's screen, from the code
    python3 packaging/check_csd_routes.py --self-test     # plant a defect in a temp copy; prove it goes red

WHY. Cards were being proposed for things that already existed under another
name — SAS verification already lives on NetworkPeerDetail, erasure on
DataManagement (CSD-039), partnership on Consent (CSD-054). A name is a poor
key for "is this the same card"; the ROUTES a card calls are a good one. Two
screens that both POST the same route are either the same card twice or one
action with two doors, and either way a person should decide which.

So this derives, from the code and nothing else:

  API method  -> (verb, route template, host)      CIRISApiClient.kt + api/ + generated-api
  Screen      -> composables / view models         CIRISApp.kt's `when (currentScreen)`
              -> API methods -> routes             transitive, see `Closure`
  CSD         -> screen + every /v1/ route in §3   FSD/CSD/CSD-*.md

and joins them. A CSD's §3 is then CHECKED against the routes its screen
actually calls, rather than written by hand and trusted.

HEURISTIC, AND IT SAYS SO. Kotlin is read with `re` and a small tokenizer, never
a build (AGENTS.md). The closure follows: composables called from the screen's
arm (same file / same directory), view-model / repository members called on a
receiver whose type the parse can see, class `init` + property initialisers of
every class it enters, and bare calls to members of the same class. It does not
follow lambdas passed through navigation, reflection, or a receiver whose type
it cannot see. Every output carries `heuristic: true`.

A PARSER THAT FINDS NOTHING FAILS. Zero API methods, zero Screens, zero `when`
arms or zero CSD routes is an error, not a green board.

Stdlib only.
"""

from __future__ import annotations

import argparse
import bisect
import json
import re
import shutil
import subprocess
import sys
import tempfile
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

SHARED = Path("client/shared/src/commonMain/kotlin")
PKG = SHARED / "ai/ciris/mobile/shared"
API_DIR = PKG / "api"
API_CLIENT = API_DIR / "CIRISApiClient.kt"
APP = PKG / "CIRISApp.kt"
GEN_DIR = Path("client/generated-api/src/commonMain/kotlin/ai/ciris/api/apis")
CSD_DIR = Path("FSD/CSD")
BASELINE = Path("packaging/csd_routes_baseline.json")

MUTATING = {"POST", "PUT", "PATCH", "DELETE"}
#: A §3 state that means "not built yet" — citing a route the screen does not
#: call is then the CSD doing its job (naming the work), not a stale citation.
PENDING_STATE = re.compile(
    r"missing|blocked|proposed|planned|not (?:yet )?(?:wired|called|built|rendered)|unwired|unbuilt|"
    r"wrong-host|wrong verb|different path|no such|does not exist|doesn't exist|not exist|404|405|"
    r"absent|future|todo|to build|deferred|not in (?:the )?client|retired|removed|superseded|"
    # the row itself says this screen does not call it, or names the card that does
    r"uncalled|never called|not called|unused|not driven|does not call|doesn't call|CSD-\d+",
    re.I,
)

# ─── Kotlin tokenizer ─────────────────────────────────────────────────────────


@dataclass
class Src:
    """One Kotlin file: `code` has comments blanked (strings kept); `skel` also
    blanks string CONTENTS, so brace matching and declaration regexes never see
    a brace or a `fun` inside a string or a comment. Same length, same offsets."""

    path: Path
    rel: str
    code: str
    skel: str
    strings: list[tuple[int, int, str]]  # (start, end, raw content) of top-level literals
    nl: list[int] = field(default_factory=list)

    def line(self, off: int) -> int:
        return bisect.bisect_right(self.nl, off) + 1


def tokenize(text: str) -> tuple[str, str, list[tuple[int, int, str]]]:
    n = len(text)
    code = list(text)
    skel = list(text)
    strings: list[tuple[int, int, str]] = []

    def blank(buf: list[str], a: int, b: int) -> None:
        for k in range(a, b):
            if buf[k] != "\n":
                buf[k] = " "

    def skip_string(i: int) -> int:
        """i at the opening quote(s); returns index after the closing quote(s)."""
        raw = text.startswith('"""', i)
        j = i + (3 if raw else 1)
        while j < n:
            if raw and text.startswith('"""', j):
                j += 3
                while j < n and text[j] == '"':  # """...""""" edge
                    j += 1
                return j
            c = text[j]
            if not raw and c == "\\":
                j += 2
                continue
            if not raw and c == '"':
                return j + 1
            if c == "$" and j + 1 < n and text[j + 1] == "{":
                j = skip_template(j + 2)
                continue
            if not raw and c == "\n":  # unterminated; bail
                return j
            j += 1
        return j

    def skip_template(j: int) -> int:
        depth = 1
        while j < n and depth:
            c = text[j]
            if c == '"':
                j = skip_string(j)
                continue
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
            j += 1
        return j

    i = 0
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            blank(code, i, j)
            blank(skel, i, j)
            i = j
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            depth, j = 1, i + 2
            while j < n and depth:
                if text.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif text.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            blank(code, i, j)
            blank(skel, i, j)
            i = j
        elif c == '"':
            j = skip_string(i)
            q = 3 if text.startswith('"""', i) else 1
            strings.append((i, j, text[i + q:max(i + q, j - q)]))
            blank(skel, i + q, max(i + q, j - q))
            i = j
        elif c == "'":
            m = re.match(r"'(?:\\.|\\u[0-9a-fA-F]{4}|[^'\\])'", text[i:i + 8])
            if m:
                blank(skel, i + 1, i + m.end() - 1)
                i += m.end()
            else:
                i += 1
        else:
            i += 1
    return "".join(code), "".join(skel), strings


_SRC_CACHE: dict[Path, Src] = {}


def load(root: Path, path: Path) -> Src:
    if path not in _SRC_CACHE:
        text = path.read_text(encoding="utf-8")
        code, skel, strings = tokenize(text)
        nl = [m.start() for m in re.finditer("\n", text)]
        _SRC_CACHE[path] = Src(path, str(path.relative_to(root)), code, skel, strings, nl)
    return _SRC_CACHE[path]


def match_close(s: str, i: int, open_c: str = "{", close_c: str = "}") -> int:
    """i at an opener; index of its matching closer (or len)."""
    depth = 0
    for k in range(i, len(s)):
        if s[k] == open_c:
            depth += 1
        elif s[k] == close_c:
            depth -= 1
            if depth == 0:
                return k
    return len(s)


# ─── Declarations ─────────────────────────────────────────────────────────────

DECL = re.compile(
    r"^([ \t]*)((?:(?:@\w+(?:\([^)\n]*\))?|public|private|internal|protected|override|open|abstract|"
    r"suspend|inline|actual|expect|operator|infix|tailrec|external|data|sealed|enum|inner|"
    r"companion|annotation|value|const|lateinit)\s+)*)"
    r"(fun|class|object|interface)\b[ \t]*(?:<[^>\n]*>\s*)?(?:([\w.]+(?:<[^>\n]*>)?\??)\.)?(\w+)?",
    re.M,
)


@dataclass
class Sym:
    kind: str  # fun | class
    name: str
    src: Src
    start: int
    end: int
    indent: int
    header_end: int  # end of the signature (params) / class header
    parent: "Sym | None" = None
    supers: list[str] = field(default_factory=list)

    @property
    def key(self) -> tuple[str, int]:
        return (self.src.rel, self.start)

    @property
    def dir(self) -> str:
        return str(Path(self.src.rel).parent)

    def where(self) -> str:
        return f"{self.src.rel}:{self.src.line(self.start)}"


def _stmt_end(skel: str, i: int, indent: int) -> int:
    """From i, the end of an expression body: the first newline at bracket depth
    zero whose next non-blank line is indented no deeper than `indent`."""
    depth, n = 0, len(skel)
    while i < n:
        c = skel[i]
        if c in "({[":
            depth += 1
        elif c in ")}]":
            depth -= 1
            if depth < 0:
                return i
        elif c == "\n" and depth == 0:
            j = i + 1
            while j < n and skel[j] in " \t\n":
                j += 1
            nxt = skel[skel.rfind("\n", 0, j) + 1:j]
            if len(nxt.expandtabs()) <= indent and not skel.startswith((".", "?.", "?:"), j):
                return i
        i += 1
    return n


def declarations(src: Src) -> list[Sym]:
    s = src.skel
    out: list[Sym] = []
    for m in DECL.finditer(s):
        kind_kw, name = m.group(3), m.group(5)
        indent = len(m.group(1).expandtabs())
        after = m.end()
        if kind_kw == "fun":
            if not name:
                continue
            p = s.find("(", after)
            if p < 0 or s[after:p].strip():
                continue
            pe = match_close(s, p, "(", ")")
            # after the params: optional `: Type`, then `{`, `=`, or nothing.
            k = pe + 1
            depth = 0
            end = hdr = pe + 1
            while k < len(s):
                c = s[k]
                if c in "(<[":
                    depth += 1
                elif c in ")>]":
                    depth = max(0, depth - 1)
                elif depth == 0 and c == "{":
                    end = match_close(s, k) + 1
                    break
                elif depth == 0 and c == "=" and s[k + 1:k + 2] != ">" and s[k - 1:k] not in "!<>=":
                    end = _stmt_end(s, k + 1, indent)
                    break
                elif depth == 0 and c == "\n":
                    j = k + 1
                    while j < len(s) and s[j] in " \t":
                        j += 1
                    if s[j:j + 1] not in (":", "{", "=", "w"):  # `where`, `: Type` continuation
                        end = k
                        break
                k += 1
            else:
                end = len(s)
            out.append(Sym("fun", name, src, m.start(), end, indent, pe + 1))
        else:
            if kind_kw == "object" and not name:
                name = "Companion"
            if not name:
                continue
            # header: up to the first `{` at paren depth 0 on the header, else line end.
            k, depth = after, 0
            end = hdr = None
            while k < len(s):
                c = s[k]
                if c == "(":
                    depth += 1
                elif c == ")":
                    depth -= 1
                elif depth == 0 and c == "{":
                    hdr = k
                    end = match_close(s, k) + 1
                    break
                elif depth == 0 and c == "\n":
                    j = k + 1
                    while j < len(s) and s[j] in " \t":
                        j += 1
                    if s[j:j + 1] not in (":", ",", "{", ")") and not s.startswith("where", j):
                        hdr = end = k
                        break
                k += 1
            if end is None:
                hdr = end = len(s)
            header = s[m.end():hdr]
            # supertypes: after the constructor's `)` (or the name), a `:`.
            hp = header.find("(")
            tail = header[match_close(header, hp, "(", ")") + 1:] if hp >= 0 and ":" not in header[:hp] else header
            supers = re.findall(r"(?:^|[:,])\s*(?:[\w.]*\.)?([A-Z]\w*)", tail.split(":", 1)[1]) if ":" in tail else []
            out.append(Sym("class", name, src, m.start(), end, indent, hdr, supers=supers))
    # parents: innermost enclosing class.
    classes = sorted((x for x in out if x.kind == "class"), key=lambda x: x.start)
    for sym in out:
        best = None
        for c in classes:
            if c is sym:
                continue
            if c.header_end <= sym.start < c.end and (best is None or c.start > best.start):
                best = c
        sym.parent = best
    return out


# ─── Routes ───────────────────────────────────────────────────────────────────


def norm(route: str) -> str:
    """`/v1/federation/peers/$keyId/sas` and `/v1/federation/peers/{key_id}/sas`
    are one route. Query and fragment dropped; any templated segment is `{}`."""
    route = re.split(r"[?#]", route, 1)[0].rstrip("/")
    segs = []
    for seg in route.split("/"):
        if seg == "*":
            segs.append("*")
        elif "$" in seg or "{" in seg or (seg.startswith("<") and seg.endswith(">")) or seg.startswith(":"):
            segs.append("{}")
        else:
            segs.append(seg)
    return "/".join(segs) or "/"


def namespace(route: str) -> tuple[str, str]:
    parts = route.split("/")
    first = "/".join(parts[:3]) if len(parts) > 2 else route
    second = "/".join(parts[:4]) if len(parts) > 3 and parts[3] not in ("{}", "*") else first
    return first, second


def host_of(var: str) -> str:
    v = var.replace("this.", "").strip("{}")
    if "node" in v.lower():
        return "node"
    if v in ("baseUrl", "serverUrl", "agentUrl", "apiUrl"):
        return "agent"
    if v in ("hintUrl",):
        return "node|agent"
    return "?"


@dataclass(frozen=True)
class Route:
    verb: str
    route: str  # normalised
    host: str
    where: str  # file:line of the literal or call

    @property
    def key(self) -> str:
        return f"{self.verb} {self.route}"


URL_LIT = re.compile(r"^\$(?:\{\s*(?:this\.)?(\w+)\s*\}|(\w+))(.*)$", re.S)
HTTP_CALL = re.compile(
    r"(?:[\w)\]]\s*)\??\.\s*(get|post|put|patch|delete|head|options|request|prepareGet|preparePost|"
    r"preparePut|prepareDelete|preparePatch|prepareRequest)\s*\(",
)
VERB_OF = {
    "get": "GET", "prepareGet": "GET", "post": "POST", "preparePost": "POST", "put": "PUT",
    "preparePut": "PUT", "patch": "PATCH", "preparePatch": "PATCH", "delete": "DELETE",
    "prepareDelete": "DELETE", "head": "HEAD", "options": "OPTIONS",
}


@dataclass
class ApiMethod:
    name: str
    cls: str
    where: str
    routes: set[Route] = field(default_factory=set)
    calls: set[str] = field(default_factory=set)  # other API methods it calls
    gen_calls: set[str] = field(default_factory=set)


def parse_generated(root: Path) -> dict[str, set[tuple[str, str]]]:
    """generated-api: fun name -> {(verb, route)}. Every one is on the agent."""
    out: dict[str, set[tuple[str, str]]] = {}
    gdir = root / GEN_DIR
    if not gdir.is_dir():
        return out
    for f in sorted(gdir.glob("*.kt")):
        src = load(root, f)
        for sym in declarations(src):
            if sym.kind != "fun":
                continue
            body = src.code[sym.start:sym.end]
            for m in re.finditer(r"RequestMethod\.(\w+)\s*,\s*\"([^\"]+)\"", body):
                out.setdefault(sym.name, set()).add((m.group(1).upper(), norm(m.group(2))))
    return out


def _url_literals(src: Src, a: int, b: int) -> list[tuple[int, str, str]]:
    """(offset, base var, rest) for every literal in [a,b) that starts with `$var`."""
    out = []
    for s, e, raw in src.strings:
        if a <= s < b:
            m = URL_LIT.match(raw)
            if m:
                out.append((s, m.group(1) or m.group(2), m.group(3)))
    return out


ROUTE_PROP = re.compile(r"\broute\s*=\s*\"(/v1/[^\"]+)\"")


def route_props(root: Path) -> list[str]:
    """`route = "/v1/…"` literals in commonMain — what `${op.route}` can be."""
    out = []
    for f in sorted((root / SHARED).rglob("*.kt")):
        src = load(root, f)
        out += [norm(m.group(1)) for m in ROUTE_PROP.finditer(src.code)]
    return sorted(set(out))


def parse_api(root: Path, gen: dict[str, set[tuple[str, str]]]) -> dict[str, ApiMethod]:
    """Every function under api/ that reaches a route. Keyed `Class.method`;
    CIRISApiClient's are also the bare-name call targets (`apiClient.foo(`)."""
    props = None
    methods: dict[str, ApiMethod] = {}
    helpers: dict[str, tuple[str, str, str]] = {}  # name -> (verb, host, param)
    syms: list[Sym] = []
    for f in sorted((root / API_DIR).glob("*.kt")):
        syms += [x for x in declarations(load(root, f)) if x.kind == "fun"]

    for sym in syms:
        src = sym.src
        a, b = sym.start, sym.end
        cls = sym.parent.name if sym.parent else "<top>"
        am = ApiMethod(sym.name, cls, sym.where())
        lits = _url_literals(src, a, b)
        # locals: val x = <expr containing url literal(s)>
        local_urls: dict[str, list[tuple[int, str, str]]] = {}
        for vm in re.finditer(r"\b(?:val|var)\s+(\w+)\s*(?::[^=\n]+)?=", src.skel[a:b]):
            vs = a + vm.end()
            ve = _stmt_end_local(src.skel, vs)
            got = [lt for lt in lits if vs <= lt[0] < ve]
            if got:
                local_urls[vm.group(1)] = got
        # base = a local holding a url: "$base?refresh=true" is that route again.
        resolved: list[tuple[int, str, str]] = []
        for off, base, rest in lits:
            if base in local_urls:
                for _, b2, r2 in local_urls[base]:
                    resolved.append((off, b2, r2 + (rest.split("?")[0] if rest.startswith("/") else "")))
            else:
                resolved.append((off, base, rest))
        used: set[int] = set()

        def emit(verb: str, off: int, base: str, rest: str, at: int) -> None:
            nonlocal props
            where = f"{src.rel}:{src.line(at)}"
            if rest.startswith("/"):
                am.routes.add(Route(verb, norm(rest), host_of(base), where))
            elif re.match(r"\$\{\s*\w+\.route\s*\}", rest):
                if props is None:
                    props = route_props(root)
                for r in props:
                    am.routes.add(Route(verb, r, host_of(base), where))
            else:
                pm = re.match(r"\$\{?(\w+)\}?$", rest)
                params = re.findall(r"(\w+)\s*:", src.skel[sym.start:sym.header_end])
                if pm and pm.group(1) in params:
                    helpers[sym.name] = (verb, host_of(base), pm.group(1))

        for cm in HTTP_CALL.finditer(src.skel, a, b):
            verb = VERB_OF.get(cm.group(1), "?")
            p = cm.end() - 1
            pe = match_close(src.skel, p, "(", ")")
            if cm.group(1) in ("request", "prepareRequest"):
                hm = re.search(r"HttpMethod\.(\w+)", src.code[p:min(b, pe + 600)])
                verb = hm.group(1).upper() if hm else "?"
            arg = src.code[p + 1:pe]
            hit = [x for x in resolved if p < x[0] < pe]
            if not hit:
                idm = re.match(r"\s*(\w+)(?:\.toString\(\))?\s*(?:[,)]|$)", arg)
                if idm and idm.group(1) in local_urls:
                    offs = {o for o, _, _ in local_urls[idm.group(1)]}
                    hit = [x for x in resolved if x[0] in offs]
            for off, base, rest in hit:
                used.add(off)
                emit(verb, off, base, rest, cm.start())
        # literals no call claimed (a local passed through a wrapper, a stream)
        verbs = {VERB_OF.get(m.group(1), "?") for m in HTTP_CALL.finditer(src.skel, a, b)}
        for off, base, rest in resolved:
            if off not in used and rest.startswith("/"):
                emit(next(iter(verbs)) if len(verbs) == 1 else "?", off, base, rest, off)
        for gm in re.finditer(r"\b\w+Api\s*\.\s*(\w+)\s*\(", src.skel[a:b]):
            if gm.group(1) in gen:
                am.gen_calls.add(gm.group(1))
                for verb, r in gen[gm.group(1)]:
                    am.routes.add(Route(verb, r, "agent", f"{src.rel}:{src.line(a + gm.start())}"))
        methods[f"{cls}.{sym.name}"] = am
        am._sym = sym  # type: ignore[attr-defined]

    # helper wrappers: postSelfAct(path = "/v1/admin/self/shed", …)
    for key, am in methods.items():
        sym = am._sym  # type: ignore[attr-defined]
        src = sym.src
        for h, (verb, host, param) in helpers.items():
            for cm in re.finditer(rf"(?<![\w.]){h}\s*\(", src.skel[sym.start:sym.end]):
                p = sym.start + cm.end() - 1
                pe = match_close(src.skel, p, "(", ")")
                for s, e, raw in src.strings:
                    if p < s < pe and raw.startswith("/"):
                        am.routes.add(Route(verb, norm(raw), host, f"{src.rel}:{src.line(s)}"))
    # API method -> API method (bare or this.), same class
    by_cls: dict[str, set[str]] = defaultdict(set)
    for key, am in methods.items():
        by_cls[am.cls].add(am.name)
    for key, am in methods.items():
        sym = am._sym  # type: ignore[attr-defined]
        body = sym.src.skel[sym.header_end:sym.end]
        for cm in re.finditer(r"(?<![\w.])(?:this\.)?(\w+)\s*\(", body):
            if cm.group(1) in by_cls[am.cls] and cm.group(1) != am.name:
                am.calls.add(f"{am.cls}.{cm.group(1)}")
    changed = True
    while changed:
        changed = False
        for am in methods.values():
            for c in list(am.calls):
                extra = methods[c].routes - am.routes
                if extra:
                    am.routes |= extra
                    changed = True
    return {k: v for k, v in methods.items() if v.routes}


def _stmt_end_local(skel: str, i: int) -> int:
    """End of a `val x = …` right-hand side: newline at depth 0 not followed by a
    continuation (`.`, `?:`, `else`, `+`)."""
    depth, n = 0, len(skel)
    while i < n:
        c = skel[i]
        if c in "({[":
            depth += 1
        elif c in ")}]":
            depth -= 1
            if depth < 0:
                return i
        elif c == "\n" and depth == 0:
            j = i + 1
            while j < n and skel[j] in " \t\n":
                j += 1
            if not skel.startswith((".", "?.", "?:", "else", "+", "&&", "||"), j):
                return i
        i += 1
    return n


# ─── The closure: Screen -> API methods ───────────────────────────────────────


class Index:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.funs: dict[str, list[Sym]] = defaultdict(list)
        self.classes: dict[str, list[Sym]] = defaultdict(list)
        self.members: dict[tuple[str, str], list[Sym]] = defaultdict(list)
        self.implementors: dict[str, list[Sym]] = defaultdict(list)
        self.by_file: dict[str, list[Sym]] = defaultdict(list)
        for f in sorted((root / SHARED).rglob("*.kt")):
            src = load(root, f)
            for sym in declarations(src):
                self.by_file[src.rel].append(sym)
                if sym.kind == "class":
                    self.classes[sym.name].append(sym)
                    for s in sym.supers:
                        self.implementors[s].append(sym)
                else:
                    self.funs[sym.name].append(sym)
                    if sym.parent is not None:
                        self.members[(sym.parent.name, sym.name)].append(sym)

    def class_skeleton(self, cls: Sym) -> list[tuple[int, int]]:
        """Class body minus its member functions (init blocks, property
        initialisers) — what runs when the class is constructed."""
        inner = sorted(
            (s.start, s.end) for s in self.by_file[cls.src.rel]
            if s.kind == "fun" and s.parent is cls
        )
        out, at = [], cls.header_end
        for a, b in inner:
            if a > at:
                out.append((at, a))
            at = max(at, b)
        if at < cls.end:
            out.append((at, cls.end))
        return out


TYPE_DECL = re.compile(r"\b(\w+)\s*:\s*((?:[\w]+\.)*[A-Z]\w*)")
CTOR_DECL = re.compile(
    r"\b(?:val|var)\s+(\w+)\s*(?::\s*[\w.<>?]+\s*)?(?:=|by)\s*(?:remember\s*(?:\([^)]*\))?\s*\{\s*|viewModel\s*\{\s*|lazy\s*\{\s*)?"
    r"(?:[\w]+\.)*([A-Z]\w*)\s*\("
)
NAME_CALL = re.compile(r"\b(\w+)\s*\(")
_RECV = re.compile(r"(\w+)\s*\??\.\s*$")


def calls(text: str):
    """(receiver, name, offset) per `name(` in text. receiver is None for a bare
    call and "" for a dotted call whose receiver is an expression (`a().b(`)."""
    for m in NAME_CALL.finditer(text):
        head = text[max(0, m.start() - 80):m.start()].rstrip()
        if head.endswith("."):
            if re.search(r"(?:^|[^\w.])[a-z_]\w*(?:\.[a-z_]\w*)+\.$", head) and m.group(1)[:1].isupper():
                yield None, m.group(1), m.start(1)  # fully qualified: `ai.ciris…federation.NetworkIdentityScreen(`
                continue
            r = _RECV.search(head)
            yield (r.group(1) if r else ""), m.group(1), m.start(1)
        elif head.endswith("fun") or re.search(r"\bfun(?:\s+[\w.<>]+)?$", head):
            continue  # a declaration, not a call
        else:
            yield None, m.group(1), m.start(1)
FOLLOW_CLASS = re.compile(r"(ViewModel|Repository|Repo|Api|Store|Service|Seam|Manager|Source|Client|Controller|Stream)$")
NOT_FOLLOWED = {"CIRISApiClient", "CIRISApiClientProtocol", "HttpClient"}


@dataclass
class Hit:
    method: str
    where: str  # call site file:line


class Closure:
    def __init__(self, idx: Index, api: dict[str, ApiMethod]) -> None:
        self.idx = idx
        self.api = api
        # bare-name targets: CIRISApiClient's methods (Protocol has the same names)
        self.client_names: dict[str, str] = {}
        for key, am in api.items():
            if am.cls in ("CIRISApiClient",):
                self.client_names[am.name] = key
        non_api_funs = {
            name for name, syms in idx.funs.items()
            if any(not s.src.rel.startswith(str(API_DIR)) for s in syms)
        }
        self.colliding = {n for n in self.client_names if n in non_api_funs}
        self.api_by_sym: dict[tuple[str, int], str] = {am._sym.key: k for k, am in api.items()}  # type: ignore[attr-defined]

    def _types(self, src: Src, a: int, b: int, into: dict[str, str]) -> None:
        text = src.skel[a:b]
        for m in TYPE_DECL.finditer(text):
            into.setdefault(m.group(1), m.group(2).split(".")[-1])
        for m in CTOR_DECL.finditer(text):
            into.setdefault(m.group(1), m.group(2))

    def reach(self, src: Src, a: int, b: int, types: dict[str, str]) -> list[Hit]:
        hits: list[Hit] = []
        seen: set[tuple[str, int, int]] = set()
        work: list[tuple[Src, int, int, dict[str, str], Sym | None]] = [(src, a, b, dict(types), None)]
        while work:
            s, a, b, ty, owner = work.pop()
            k = (s.rel, a, b)
            if k in seen:
                continue
            seen.add(k)
            ty = dict(ty)
            self._types(s, a, b, ty)
            text = s.skel[a:b]
            for recv, name, off in calls(text):
                at = f"{s.rel}:{s.line(a + off)}"
                rtype = ty.get(recv) if recv else None
                if recv and rtype is None and recv.endswith("ViewModel"):
                    rtype = recv[0].upper() + recv[1:]
                # 1. an API call
                if name in self.client_names and (recv or s.rel.startswith(str(API_DIR))):
                    if name not in self.colliding or rtype in NOT_FOLLOWED or (
                        recv and re.search(r"api|client", recv, re.I)
                    ):
                        hits.append(Hit(self.client_names[name], at))
                        continue
                # 2. a member on a receiver whose type we can see
                if recv and rtype and rtype not in NOT_FOLLOWED:
                    for cls in self._classes_for(rtype):
                        for mem in self.idx.members.get((cls.name, name), []):
                            work.append(self._enter(mem, cls))
                        work += self._skel_items(cls)
                    continue
                if recv is not None:
                    continue
                # 3. a bare call: same class member, same-file/dir function, a constructor
                if owner is not None:
                    for mem in self.idx.members.get((owner.name, name), []):
                        work.append(self._enter(mem, owner))
                for fs in self.idx.funs.get(name, []):
                    if fs.parent is not None:
                        continue
                    if fs.src.rel == s.rel or name[:1].isupper():
                        work.append(self._enter(fs, None))
                if name[:1].isupper() and name not in NOT_FOLLOWED:
                    for cls in self.idx.classes.get(name, []):
                        if FOLLOW_CLASS.search(cls.name):
                            work += self._skel_items(cls)
            # API functions reached directly (a member of a class under api/)
            api_key = self.api_by_sym.get((s.rel, a))
            if api_key:
                hits.append(Hit(api_key, f"{s.rel}:{s.line(a)}"))
        return hits

    def _classes_for(self, tname: str) -> list[Sym]:
        out = list(self.idx.classes.get(tname, []))
        for c in list(out):
            out += self.idx.implementors.get(c.name, [])
        return [c for c in out if c.name not in NOT_FOLLOWED]

    def _enter(self, fn: Sym, owner: Sym | None):
        ty: dict[str, str] = {}
        if owner is not None:
            self._types(owner.src, owner.start, owner.header_end, ty)
            for a, b in self.idx.class_skeleton(owner):
                self._types(owner.src, a, b, ty)
        return (fn.src, fn.start, fn.end, ty, owner)

    def _skel_items(self, cls: Sym) -> list:
        """What runs when `cls` is constructed: its init blocks and property
        initialisers, one work item per range between member functions."""
        ty: dict[str, str] = {}
        self._types(cls.src, cls.start, cls.header_end, ty)
        ranges = self.idx.class_skeleton(cls)
        for a, b in ranges:
            self._types(cls.src, a, b, ty)
        return [(cls.src, a, b, ty, cls) for a, b in ranges]


def screen_arms(root: Path) -> tuple[dict[str, tuple[int, int]], Src]:
    """Screen name -> (start, end) of its arm in the biggest `when (currentScreen)`."""
    src = load(root, root / APP)
    best: dict[str, tuple[int, int]] = {}
    for wm in re.finditer(r"\bwhen\s*\(\s*currentScreen\s*\)\s*\{", src.skel):
        open_at = wm.end() - 1
        close = match_close(src.skel, open_at)
        body_a = open_at + 1
        arms = list(re.finditer(
            r"^([ \t]*)((?:(?:is\s+)?Screen\.\w+\s*,\s*)*(?:is\s+)?Screen\.\w+)\s*->",
            src.skel[body_a:close], re.M,
        ))
        if not arms:
            continue
        ind = min(len(m.group(1)) for m in arms)
        arms = [m for m in arms if len(m.group(1)) == ind]
        found: dict[str, tuple[int, int]] = {}
        for i, m in enumerate(arms):
            a = body_a + m.end()
            b = body_a + arms[i + 1].start() if i + 1 < len(arms) else close
            for name in re.findall(r"Screen\.(\w+)", m.group(2)):
                found[name] = (a, b)
        if len(found) > len(best):
            best = found
    return best, src


SCREEN_MEMBER = re.compile(r"^\s+(?:data\s+)?(?:object|class)\s+(\w+)", re.M)


def screen_classes(root: Path) -> set[str]:
    """`sealed class Screen`'s members — the same brace-matched parse as
    check_csd_v3._screen_classes, rooted at `root` so --self-test can use it."""
    src = load(root, root / APP)
    start = src.skel.find("sealed class Screen")
    open_at = src.skel.find("{", start)
    if start < 0 or open_at < 0:
        return set()
    return set(SCREEN_MEMBER.findall(src.skel[open_at + 1:match_close(src.skel, open_at)]))


# ─── CSDs ─────────────────────────────────────────────────────────────────────


@dataclass
class Cite:
    verb: str | None
    route: str
    state: str  # the row's state cell, or "prose"
    line: int
    wildcard: bool


@dataclass
class Csd:
    id: str
    path: str
    screen: str | None
    stage: str
    cites: list[Cite]


CSD_ROUTE = re.compile(
    r"(?:\b(GET|POST|PUT|PATCH|DELETE)\b[`\s]*)?(/v1(?:/[A-Za-z0-9_\-.{}*:<>$]+)+|/v1\b)"
)


def parse_csds(root: Path) -> list[Csd]:
    out = []
    for f in sorted((root / CSD_DIR).glob("CSD-*.md")):
        t = f.read_text(encoding="utf-8")
        sm = re.search(r"```yaml csd:surface\n(.*?)```", t, re.S)
        screen = None
        if sm:
            m = re.search(r"^screen:\s*([A-Za-z]\w*)", sm.group(1), re.M)
            screen = m.group(1) if m else None
        stg = re.search(r"```yaml csd:stage\n.*?^stage:\s*(\w+)", t, re.S | re.M)
        sec = re.search(r"^## 3\..*?(?=^## |\Z)", t, re.M | re.S)
        cites: list[Cite] = []
        if sec:
            base_line = t[:sec.start()].count("\n") + 1
            state_col = None
            for i, line in enumerate(sec.group(0).splitlines()):
                cells = None
                if line.lstrip().startswith("|"):
                    cells = [c.strip() for c in line.strip().strip("|").split("|")]
                    if re.match(r"^[-: ]+$", "".join(cells)):
                        continue
                    low = [c.lower() for c in cells]
                    if "state" in low and "endpoint" in low:
                        state_col = low.index("state")
                        continue
                state = (cells[state_col] if cells and state_col is not None and state_col < len(cells)
                         else "prose")
                for m in CSD_ROUTE.finditer(line):
                    raw = m.group(2).rstrip(".,:;)`")
                    r = norm(raw)
                    if r.count("/") < 2 or any((x.verb, x.route, x.line) == (m.group(1), r, base_line + i)
                                               for x in cites):
                        continue  # bare `/v1`, or the same route twice on one line
                    cites.append(Cite(m.group(1), r, state, base_line + i, "*" in raw))
        out.append(Csd(f.name[:7], str(f.relative_to(root)), screen,
                       stg.group(1) if stg else "?", cites))
    return out


def cite_matches(c: Cite, verb: str, route: str) -> bool:
    if c.verb and verb not in ("?", c.verb):
        return False
    if c.wildcard:
        # A wildcard in PROSE is commentary (`/v1/system/*` covers fifty routes)
        # and cites nothing; in a §3 row it is a contract for the family.
        if c.state == "prose":
            return False
        pat = "^" + re.escape(c.route).replace(r"\*", ".*") + "$"
        return re.match(pat, route) is not None or (c.route.endswith("/*") and route == c.route[:-2])
    # segment-wise: a `{}` on either side covers one concrete segment on the
    # other. CSD-024 cites `/runtime/pause` where the code calls `/runtime/{action}`;
    # CSD-036 cites `/admin/self/{action}` where the code calls `/admin/self/shed`.
    a, b = c.route.split("/"), route.split("/")
    return len(a) == len(b) and all(x == y or "{}" in (x, y) for x, y in zip(a, b))


# ─── The map ──────────────────────────────────────────────────────────────────


def build(root: Path) -> dict:
    _SRC_CACHE.clear()
    errors: list[str] = []
    gen = parse_generated(root)
    api = parse_api(root, gen)
    idx = Index(root)
    clos = Closure(idx, api)
    arms, app = screen_arms(root)
    screens = screen_classes(root)
    csds = parse_csds(root)

    if root == REPO:
        try:  # the oracle: check_csd_v3's own parse must agree with ours
            sys.path.insert(0, str(REPO / "packaging"))
            from check_csd_v3 import _screen_classes  # type: ignore
            theirs = _screen_classes()
            if theirs and theirs != screens:
                errors.append(f"Screen parse disagrees with check_csd_v3._screen_classes: "
                              f"{sorted(theirs ^ screens)}")
        except SystemExit:  # PyYAML absent; check_csd_v3 exits on import
            pass

    if not api:
        errors.append("parsed ZERO API methods from client/shared/.../api — the parser is broken, not the tree")
    if not screens:
        errors.append("parsed ZERO Screens from `sealed class Screen` in CIRISApp.kt")
    if not arms:
        errors.append("parsed ZERO arms of `when (currentScreen)` in CIRISApp.kt")
    if not any(c.cites for c in csds):
        errors.append("parsed ZERO /v1 routes from FSD/CSD/CSD-*.md §3")

    app_types: dict[str, str] = {}
    for m in re.finditer(r"\b(?:val|var)\s+(\w+)\s*:\s*((?:\w+\.)*[A-Z]\w*)", app.skel):
        app_types.setdefault(m.group(1), m.group(2).split(".")[-1])
    app_types.setdefault("apiClient", "CIRISApiClient")

    screen_hits: dict[str, list[Hit]] = {}  # filled below; checked after
    for sc in sorted(screens):
        if sc not in arms:
            screen_hits[sc] = []
            continue
        a, b = arms[sc]
        screen_hits[sc] = clos.reach(app, a, b, app_types)

    # screen -> routes (with call sites)
    screen_routes: dict[str, dict[str, dict]] = {}
    for sc, hits in screen_hits.items():
        rs: dict[str, dict] = {}
        for h in hits:
            for r in api[h.method].routes:
                e = rs.setdefault(r.key, {"verb": r.verb, "route": r.route, "hosts": set(),
                                          "methods": set(), "sites": set()})
                e["hosts"].add(r.host)
                e["methods"].add(h.method)
                e["sites"].add(h.where)
        screen_routes[sc] = rs

    # all code routes
    code_routes: dict[str, dict] = {}
    for key, am in api.items():
        for r in am.routes:
            e = code_routes.setdefault(r.key, {"verb": r.verb, "route": r.route, "hosts": set(),
                                               "methods": set(), "screens": set(), "csds": set()})
            e["hosts"].add(r.host)
            e["methods"].add(key)
    for sc, rs in screen_routes.items():
        for k in rs:
            code_routes[k]["screens"].add(sc)
    for c in csds:
        for ci in c.cites:
            for k, e in code_routes.items():
                if cite_matches(ci, e["verb"], e["route"]):
                    e["csds"].add(c.id)

    reached = {h.method for hits in screen_hits.values() for h in hits}
    if api and arms and not reached:
        errors.append("ZERO Screens reach an API method — the closure is broken, not the tree")

    # alignment per CSD; uncited is judged per SCREEN against every CSD on it
    by_screen: dict[str, list[Csd]] = defaultdict(list)
    for c in csds:
        if c.screen:
            by_screen[c.screen].append(c)
    uncited: dict[str, list[str]] = {}
    for sc, rs in screen_routes.items():
        cites = [ci for c in by_screen.get(sc, []) for ci in c.cites]
        miss = sorted(k for k, e in rs.items() if not any(cite_matches(ci, e["verb"], e["route"]) for ci in cites))
        if miss:
            uncited[sc] = miss
    alignment = []
    for c in csds:
        rs = screen_routes.get(c.screen or "", {})
        unused_pending, unused = [], []
        for ci in c.cites:
            if any(cite_matches(ci, e["verb"], e["route"]) for e in rs.values()):
                continue
            label = f"{ci.verb or '*'} {ci.route}"
            (unused_pending if (PENDING_STATE.search(ci.state) or ci.state == "prose") else unused).append(
                {"cite": label, "state": ci.state, "line": ci.line})
        alignment.append({
            "csd": c.id, "path": c.path, "screen": c.screen, "stage": c.stage,
            "cites": len({(ci.verb, ci.route) for ci in c.cites}),
            "screen_routes": len(rs),
            "uncited": uncited.get(c.screen or "", []) if c.screen else [],
            "unused_citation": unused,
            "cited_pending": unused_pending,
            "shared_screen_with": [o.id for o in by_screen.get(c.screen or "", []) if o.id != c.id],
        })

    # duplication signals
    dup_mut = []
    for k, e in sorted(code_routes.items()):
        if e["verb"] in MUTATING and len(e["screens"]) > 1:
            dup_mut.append({"route": k, "screens": sorted(e["screens"]),
                            "hosts": sorted(e["hosts"]), "methods": sorted(e["methods"])})
    cite_sets = {c.id: {ci.route for ci in c.cites} for c in csds}
    dup_csd = []
    for i, c1 in enumerate(csds):
        for c2 in csds[i + 1:]:
            if not c1.screen or not c2.screen or c1.screen == c2.screen:
                continue
            A, B = cite_sets[c1.id], cite_sets[c2.id]
            if min(len(A), len(B)) < 2:
                continue
            inter = A & B
            ratio = len(inter) / min(len(A), len(B))
            if len(inter) >= 2 and ratio >= 0.5:
                dup_csd.append({"a": c1.id, "a_screen": c1.screen, "b": c2.id, "b_screen": c2.screen,
                                "overlap": round(ratio, 2), "shared": sorted(inter)})
    dup_csd.sort(key=lambda d: (-d["overlap"], -len(d["shared"])))
    screenless = []
    for c in csds:
        if c.screen:
            continue
        routes = {ci.route for ci in c.cites}
        if not routes:
            continue
        owners = defaultdict(set)
        for ci in c.cites:
            for k, e in code_routes.items():
                if cite_matches(ci, e["verb"], e["route"]):
                    for sc in e["screens"]:
                        owners[sc].add(ci.route)
        full = sorted(sc for sc, rs in owners.items() if rs == routes)
        covered = {r for rs in owners.values() for r in rs}
        screenless.append({"csd": c.id, "routes": sorted(routes), "all_used_by": full,
                           "used_somewhere": sorted(covered), "not_called": sorted(routes - covered)})

    orphans_methods = sorted(k for k in api if k not in reached)
    orphans_routes = sorted(k for k, e in code_routes.items() if not e["csds"])

    def jsonable(x):
        if isinstance(x, set):
            return sorted(x)
        if isinstance(x, dict):
            return {k: jsonable(v) for k, v in x.items()}
        if isinstance(x, list):
            return [jsonable(v) for v in x]
        return x

    return jsonable({
        "heuristic": True,
        "errors": errors,
        "counts": {
            "api_methods": len(api), "generated_methods": len(gen), "code_routes": len(code_routes),
            "screens": len(screens), "screens_with_arm": len(arms), "screens_with_routes":
                sum(1 for v in screen_routes.values() if v),
            "csds": len(csds), "csd_citations": sum(len(c.cites) for c in csds),
        },
        "screens_without_arm": sorted(screens - set(arms)),
        "routes": code_routes,
        "screen_routes": screen_routes,
        "api": {k: {"where": v.where, "routes": sorted(r.key for r in v.routes),
                    "hosts": sorted({r.host for r in v.routes})} for k, v in api.items()},
        "csds": [{"id": c.id, "screen": c.screen, "stage": c.stage,
                  "cites": [{"verb": ci.verb, "route": ci.route, "state": ci.state, "line": ci.line}
                            for ci in c.cites]} for c in csds],
        "uncited": uncited,
        "alignment": alignment,
        "duplicate_mutations": dup_mut,
        "duplicate_csds": dup_csd,
        "screenless_csds": screenless,
        "orphan_methods": orphans_methods,
        "orphan_routes": orphans_routes,
    })


# ─── Output ───────────────────────────────────────────────────────────────────


def report(m: dict) -> str:
    L = []
    c = m["counts"]
    L.append("# CSD route map\n")
    L.append("*Generated by `packaging/check_csd_routes.py --report`. heuristic: true — the "
             "Screen→route closure is a regex walk, not a compiler (see the tool's docstring).*\n")
    L.append(f"API methods with routes: **{c['api_methods']}** · distinct code routes: **{c['code_routes']}** · "
             f"Screens: **{c['screens']}** ({c['screens_with_arm']} with a `when` arm, "
             f"{c['screens_with_routes']} reaching a route) · CSDs: **{c['csds']}** "
             f"({c['csd_citations']} §3 citations)\n")
    if m["errors"]:
        L.append("## ERRORS\n")
        L += [f"- {e}" for e in m["errors"]]
        L.append("")

    L.append("## 1. Duplication signals\n")
    L.append("### 1a. Two Screens call the same mutating route\n")
    if not m["duplicate_mutations"]:
        L.append("None.\n")
    else:
        L.append("| route | host | screens | API method(s) |\n|---|---|---|---|")
        for d in m["duplicate_mutations"]:
            L.append(f"| `{d['route']}` | {', '.join(d['hosts'])} | {', '.join(d['screens'])} | "
                     f"{', '.join('`' + x.split('.')[-1] + '`' for x in d['methods'])} |")
        L.append("")
    L.append("### 1b. CSDs on different screens whose cited routes overlap ≥50%\n")
    L.append("(overlap = |shared| / min(|A|,|B|); only pairs sharing ≥2 routes, each citing ≥2)\n")
    if not m["duplicate_csds"]:
        L.append("None.\n")
    else:
        L.append("| A | B | overlap | shared routes |\n|---|---|---|---|")
        for d in m["duplicate_csds"]:
            L.append(f"| {d['a']} ({d['a_screen']}) | {d['b']} ({d['b_screen']}) | {int(d['overlap']*100)}% | "
                     f"{', '.join('`' + r + '`' for r in d['shared'])} |")
        L.append("")
    L.append("### 1c. Several CSDs on one screen\n")
    shared = defaultdict(list)
    for a in m["alignment"]:
        if a["screen"]:
            shared[a["screen"]].append(a["csd"])
    multi = {k: v for k, v in shared.items() if len(v) > 1}
    L += [f"- **{k}**: {', '.join(v)}" for k, v in sorted(multi.items())] or ["None."]
    L.append("")
    L.append("### 1d. CSDs with no screen\n")
    if not m["screenless_csds"]:
        L.append("None.\n")
    for d in m["screenless_csds"]:
        L.append(f"- **{d['csd']}** cites {len(d['routes'])} route(s). "
                 f"All already called by: {', '.join(d['all_used_by']) or '—'}. "
                 f"Called by some screen: {len(d['used_somewhere'])}; called by none: "
                 f"{', '.join('`' + r + '`' for r in d['not_called']) or '—'}")
    L.append("")

    L.append("## 2. Alignment per CSD\n")
    L.append("**uncited** = the screen calls it, no CSD on that screen cites it. "
             "**unused-citation** = cited, the screen does not call it, and the row's state does not say "
             "why (missing / blocked / proposed / wrong-host / uncalled / another CSD's card). "
             "**acknowledged** = cited, not called, and the row says so. A `{}` segment on either side "
             "matches one concrete segment on the other.\n")
    L.append("| CSD | screen | stage | cites | screen routes | uncited | unused-citation | acknowledged |\n"
             "|---|---|---|---|---|---|---|---|")
    for a in sorted(m["alignment"], key=lambda a: -len(a["uncited"])):
        L.append(f"| {a['csd']} | {a['screen'] or '—'} | {a['stage']} | {a['cites']} | {a['screen_routes']} | "
                 f"{len(a['uncited'])} | {len(a['unused_citation'])} | {len(a['cited_pending'])} |")
    L.append("")
    for a in m["alignment"]:
        if not (a["uncited"] or a["unused_citation"]):
            continue
        share = f" (shares {a['screen']} with {', '.join(a['shared_screen_with'])})" if a["shared_screen_with"] else ""
        L.append(f"### {a['csd']} — {a['screen'] or 'no screen'}{share}\n")
        if a["uncited"]:
            L.append("uncited: " + ", ".join(f"`{r}`" for r in a["uncited"]) + "\n")
        if a["unused_citation"]:
            L.append("unused-citation: " + ", ".join(
                f"`{u['cite']}` (L{u['line']}, state: {u['state'][:60]})" for u in a["unused_citation"]) + "\n")
    no_csd = sorted(sc for sc in m["uncited"] if not any(x["screen"] == sc for x in m["alignment"]))
    if no_csd:
        L.append("### Screens with routes and no CSD\n")
        for sc in no_csd:
            L.append(f"- **{sc}**: " + ", ".join(f"`{r}`" for r in m["uncited"][sc]))
        L.append("")

    L.append("## 3. Routes by namespace\n")
    groups: dict[str, dict[str, list]] = defaultdict(lambda: defaultdict(list))
    for k, e in m["routes"].items():
        first, second = namespace(e["route"])
        groups[first][second].append((k, e))
    for first in sorted(groups):
        L.append(f"### `{first}`\n")
        L.append("| route | host | screens | CSDs |\n|---|---|---|---|")
        for second in sorted(groups[first]):
            for k, e in sorted(groups[first][second], key=lambda x: (x[1]["route"], x[1]["verb"])):
                L.append(f"| `{k}` | {', '.join(e['hosts'])} | {', '.join(e['screens']) or '—'} | "
                         f"{', '.join(e['csds']) or '—'} |")
        L.append("")

    L.append("## 4. Orphans\n")
    L.append(f"### API methods no Screen reaches ({len(m['orphan_methods'])})\n")
    for k in m["orphan_methods"]:
        L.append(f"- `{k}` — {', '.join(m['api'][k]['routes'])}")
    L.append(f"\n### Code routes no CSD cites ({len(m['orphan_routes'])})\n")
    for k in m["orphan_routes"]:
        e = m["routes"][k]
        L.append(f"- `{k}` — screens: {', '.join(e['screens']) or 'none'}")
    if m["screens_without_arm"]:
        L.append(f"\n### Screens with no `when (currentScreen)` arm\n\n{', '.join(m['screens_without_arm'])}")
    return "\n".join(L) + "\n"


def print_rows(m: dict, csd_id: str) -> int:
    c = next((x for x in m["csds"] if x["id"].upper() == csd_id.upper()), None)
    if c is None:
        print(f"no such CSD: {csd_id}", file=sys.stderr)
        return 2
    if not c["screen"]:
        print(f"{c['id']} names no screen in csd:surface; nothing to derive", file=sys.stderr)
        return 2
    rs = m["screen_routes"].get(c["screen"], {})
    owner = {"node": "CIRISServer", "agent": "CIRISAgent (front door)", "node|agent": "node, else agent"}
    print(f"<!-- generated: python3 packaging/check_csd_routes.py --print {c['id']} "
          f"(screen {c['screen']}; heuristic) -->")
    print("| value | endpoint | owner | state |")
    print("|---|---|---|---|")
    for k in sorted(rs, key=lambda k: (rs[k]["route"], rs[k]["verb"])):
        e = rs[k]
        meth = ", ".join(f"`{x.split('.')[-1]}`" for x in sorted(e["methods"]))
        site = ", ".join(s.replace(str(PKG) + "/", "") for s in sorted(e["sites"])[:2])
        print(f"| {meth} | `{e['verb']} {e['route']}` | "
              f"{', '.join(owner.get(h, h) for h in sorted(e['hosts']))} | called — `{site}` |")
    if not rs:
        print(f"<!-- the closure reached no route from Screen.{c['screen']} -->")
    return 0


# ─── Gate ─────────────────────────────────────────────────────────────────────


def gate_state(m: dict) -> dict:
    return {
        "uncited": {sc: sorted(v) for sc, v in sorted(m["uncited"].items())},
        "duplicate_mutations": sorted(f"{d['route']} :: {', '.join(d['screens'])}" for d in m["duplicate_mutations"]),
    }


def gate(root: Path, m: dict) -> int:
    if m["errors"]:
        for e in m["errors"]:
            print(f"::error::{e}")
        return 1
    now = gate_state(m)
    bpath = root / BASELINE
    base = json.loads(bpath.read_text()) if bpath.exists() else {"uncited": {}, "duplicate_mutations": []}
    new_unc = {sc: sorted(set(v) - set(base["uncited"].get(sc, []))) for sc, v in now["uncited"].items()}
    new_unc = {k: v for k, v in new_unc.items() if v}
    new_dup = sorted(set(now["duplicate_mutations"]) - set(base["duplicate_mutations"]))
    n_now = sum(len(v) for v in now["uncited"].values())
    n_base = sum(len(v) for v in base["uncited"].values())
    c = m["counts"]
    print(f"  api methods {c['api_methods']} · routes {c['code_routes']} · screens {c['screens']} "
          f"({c['screens_with_routes']} reach a route) · CSD citations {c['csd_citations']}  [heuristic]")
    print(f"  uncited routes:            {n_now}  (baseline {n_base})")
    print(f"  duplicate mutating routes: {len(now['duplicate_mutations'])}  "
          f"(baseline {len(base['duplicate_mutations'])})")
    rc = 0
    if new_unc:
        print("\n::error::a screen calls a route no CSD on that screen cites — add it to the CSD's §3 "
              "(generate the rows: --print CSD-NNN), or write the CSD")
        for sc, v in new_unc.items():
            print(f"\n  Screen.{sc}")
            for r in v:
                print(f"      {r}")
        rc = 1
    if new_dup:
        print("\n::error::two screens now call the same MUTATING route — the same card twice, or one "
              "action with two doors; decide which, then re-record the baseline")
        for d in new_dup:
            print(f"      {d}")
        rc = 1
    if rc == 0:
        if n_now < n_base or len(now["duplicate_mutations"]) < len(base["duplicate_mutations"]):
            print("\n  fewer than the baseline. Re-record it:\n    python3 packaging/check_csd_routes.py --baseline")
        print("\n  no new uncited routes, no new duplicate mutations")
    return rc


# ─── Self-test ────────────────────────────────────────────────────────────────

PLANT_FN = '''
    suspend fun plantedSelfTestProbe(): Boolean {
        val client = federationHttpClient()
        val response = client.post("$nodeUrl/v1/zz-self-test/planted")
        return response.status.isSuccess()
    }
'''


def self_test() -> int:
    tmp = Path(tempfile.mkdtemp(prefix="csd-routes-"))
    try:
        for rel in (SHARED, GEN_DIR, CSD_DIR):
            shutil.copytree(REPO / rel, tmp / rel)
        (tmp / BASELINE).parent.mkdir(parents=True, exist_ok=True)
        if (REPO / BASELINE).exists():
            shutil.copy(REPO / BASELINE, tmp / BASELINE)

        def run() -> tuple[int, str]:
            p = subprocess.run([sys.executable, __file__, "--root", str(tmp)], capture_output=True, text=True)
            out = p.stdout + p.stderr
            for line in out.splitlines():  # show the red, not a description of it
                if line.startswith("::error::") or "zz-self-test" in line:
                    print(f"      | {line[:150]}")
            return p.returncode, out

        rc, out = run()
        if rc != 0:
            print(out)
            print("SELF-TEST FAIL: the unplanted copy is already red")
            return 1
        print("  clean copy: green")

        # 1. a new API method, called from one screen's arm -> NEW uncited route
        api = tmp / API_CLIENT
        t = api.read_text()
        i = t.index("class CIRISApiClient(")
        i = t.index("\n    suspend fun ", i)
        api.write_text(t[:i] + "\n" + PLANT_FN + t[i:])
        app = tmp / APP
        t = app.read_text()
        arm = re.search(r"\n(\s+)Screen\.Consent -> \{\n", t)
        assert arm, "no Screen.Consent arm to plant into"
        call = f"{arm.group(1)}    LaunchedEffect(Unit) {{ apiClient.plantedSelfTestProbe() }}\n"
        app.write_text(t[:arm.end()] + call + t[arm.end():])
        rc, out = run()
        if rc == 0 or "/v1/zz-self-test/planted" not in out:
            print(out)
            print("SELF-TEST FAIL: a planted uncited route did not go red")
            return 1
        print("  planted uncited POST /v1/zz-self-test/planted on Screen.Consent: red")

        # 2. the same mutating call from a second screen -> NEW duplicate mutation
        t = app.read_text()
        arm = re.search(r"\n(\s+)Screen\.Wallet -> \{\n", t)
        assert arm, "no Screen.Wallet arm to plant into"
        app.write_text(t[:arm.end()] + f"{arm.group(1)}    LaunchedEffect(Unit) {{ apiClient.plantedSelfTestProbe() }}\n"
                       + t[arm.end():])
        rc, out = run()
        if rc == 0 or "same MUTATING route" not in out or "Consent, Wallet" not in out:
            print(out)
            print("SELF-TEST FAIL: a planted duplicate mutation did not go red")
            return 1
        print("  planted the same POST on Screen.Wallet: red (duplicate mutation)")

        # 3. a parser that finds nothing must fail loudly
        for f in (tmp / CSD_DIR).glob("CSD-*.md"):
            f.write_text(re.sub(r"/v1", "/vX", f.read_text()))
        rc, out = run()
        if rc == 0 or "ZERO /v1 routes" not in out:
            print(out)
            print("SELF-TEST FAIL: zero CSD routes did not fail loudly")
            return 1
        print("  zero CSD routes parsed: red (loud)")
        print("[OK] self-test: every planted defect went red")
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--root", type=Path, default=REPO, help="tree to read (default: this repo)")
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--report", action="store_true", help="markdown map to stdout")
    g.add_argument("--json", action="store_true", help="the map as JSON")
    g.add_argument("--list", action="store_true", help="every uncited route and duplicate; exit 0")
    g.add_argument("--baseline", action="store_true", help="re-record the ratchet baseline")
    g.add_argument("--print", metavar="CSD-NNN", help="§3 rows for that CSD's screen, from the code")
    g.add_argument("--self-test", action="store_true", help="plant defects in a temp copy; prove red")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    root = args.root.resolve()
    m = build(root)
    if args.report:
        sys.stdout.write(report(m))
        return 1 if m["errors"] else 0
    if args.json:
        json.dump(m, sys.stdout, indent=1, sort_keys=True)
        print()
        return 1 if m["errors"] else 0
    if args.print:
        return print_rows(m, args.print)
    if args.list:
        st = gate_state(m)
        for sc, v in st["uncited"].items():
            print(f"\nScreen.{sc}")
            for r in v:
                print(f"    {r}")
        print("\nduplicate mutating routes:")
        for d in st["duplicate_mutations"]:
            print(f"    {d}")
        print(f"\n{sum(len(v) for v in st['uncited'].values())} uncited route(s) on "
              f"{len(st['uncited'])} screen(s); {len(st['duplicate_mutations'])} duplicate mutation(s)")
        return 0
    if args.baseline:
        if m["errors"]:
            for e in m["errors"]:
                print(f"::error::{e}")
            return 1
        st = gate_state(m)
        st["_note"] = ("Ratchet for packaging/check_csd_routes.py. Uncited routes per Screen and duplicate "
                       "mutating routes, recorded; the gate fails only on NEW entries. Re-record with --baseline.")
        (root / BASELINE).write_text(json.dumps(st, indent=2, sort_keys=True) + "\n")
        print(f"baseline re-recorded: {sum(len(v) for v in st['uncited'].values())} uncited, "
              f"{len(st['duplicate_mutations'])} duplicate mutation(s)")
        return 0
    return gate(root, m)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
