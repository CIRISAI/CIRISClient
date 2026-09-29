"""Run a declarative UI flow against the client's test-automation surface.

WHY THIS EXISTS. Every UI flow in this harness has been hand-written imperative
Python against the client's HTTP endpoints, and the failures have not been in the
app — they have been in our encoding of it. The canonical case (CIRISClient#39) is
`btn_menu` clicked to reach `menu_logout`: `btn_menu` opens ADVANCED, `menu_logout`
lives under GOVERNANCE. The click succeeded, because clicks always do, and the run
then waited out its timeout on an element that was never going to render. Nothing
was broken. We had encoded an assumption about composition that only the client
knows, and there was no place to state it where anything could check it.

THE LANGUAGE IS NOT OURS TO INVENT. CIRISClient answered #39 with a canonical
form, and it is deliberately a NARROWING of the `interactive_config` language this
repo already has in 83 steps across 22 adapters, not a second one beside it. This
module implements that form for the test direction:

    step_id / title       what the card is and what it is called
    requires:             LINKS — what must be true to reach it (the #39 fix)
    do:                   the interactions themselves
    expect:               what must be true after

`requires` is the half that matters. It is the place where "`menu_logout` requires
the GOVERNANCE menu open" is written down as data, checked BEFORE the step runs,
and reported as a precondition failure rather than as a mystery timeout twenty
seconds later.

WHY A STEP DECLARES BOTH SIDES. A flow that only asserts the end state cannot say
which step broke it. Asserting before and after each step turns "the flow failed"
into "step 3 of 7 failed, its preconditions held, its click succeeded, and the
screen did not change" — which is a bug report rather than a symptom.

STRICT BY CONSTRUCTION. Unknown keys are a load error, not a warning. A spec with
a typo'd key would otherwise assert nothing and pass, which is the vacuous-green
shape this harness has now produced three times (CIRISAgent#1151's absent-screen
check, `without_ai_recorded`, the lsof teardown). A spec that does not parse is
loud; a spec that parses asserts everything it says.
"""

from __future__ import annotations

import json
import fnmatch
import re
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence

import yaml

#: Keys a step may carry. Anything else is a load error — see STRICT above.
_STEP_KEYS = {"step_id", "title", "description", "requires", "do", "expect", "optional_step"}
#: CSD/3 §3 adds the predicates that let a flow check what the UI SHOWS, not
#: merely that something is on screen. Before them the strongest value assertion
#: was `text: substring`, so a CSD could promise "a composite equal to the
#: minimum of five factors" and assert only that a card had rendered.
_COND_KEYS = {
    "screen", "visible", "absent", "text",
    "count", "number", "matches", "one_of", "each", "relation", "state",
}
_ACTION_KEYS = {"click", "input", "scroll_to", "wait", "wait_ms", "when"}
#: LOCAL DELTA (VENDORED.md): `csd` names the CSD a flow tests, so the runner can
#: read that CSD's `shows:` (for `relation` field ids) and `states:` (for `state:`).
_FLOW_KEYS = {"flow", "title", "description", "client", "steps", "csd", "fixture", "cleanup"}

#: LOCAL DELTA: fixtures a flow may ask for with `fixture:`. A flow pays for a
#: fixture only when it names one; `two_node` stands a second ciris-server up
#: beside the leg's node and seeds it (testing/gate/two_node.py).
FIXTURES = {"two_node"}

#: LOCAL DELTA: `${NAME}` in a tag, a text or an input value is a value the
#: flow's fixture produced — a key id no flow could know when it was written.
_VAR = re.compile(r"\$\{([A-Z][A-Z0-9_]*)\}")


_RELATION_OPS = {"eq", "ne", "lt", "lte", "gt", "gte", "min_of", "max_of", "sum_of"}

#: LOCAL DELTA: how long an `expect` may take to hold after the step's actions.
#: `/click` returns before the frame that applies it, so an assertion read in
#: the same instant sees the screen the click is leaving: csd_057's back
#: "succeeded" and `absent: [card_wallet_balance]` failed on the local Linux
#: leg (2026-09-29). The expect is re-read every quarter second until it holds
#: or this runs out; a condition that never holds still fails, in this long.
EXPECT_SETTLE_S = 2.5
_STATES = {"populated", "empty", "loading", "error"}


class SpecError(Exception):
    """The spec itself is wrong. Raised at load time, before anything is driven."""


def _tags_of(cond: "Condition") -> List[str]:
    """Every literal tag a condition names (globs and field ids excluded)."""
    out = list(cond.visible) + list(cond.absent)
    for d in (cond.text, cond.number, cond.matches, cond.one_of):
        out.extend(d.keys())
    return out


@dataclass
class Condition:
    """A `requires` or `expect` block: what must be true at a point in the flow."""

    screen: Optional[str] = None
    visible: List[str] = field(default_factory=list)
    absent: List[str] = field(default_factory=list)
    text: Dict[str, str] = field(default_factory=dict)
    # ── CSD/3 §3 ────────────────────────────────────────────────────────────
    count: Optional[Dict[str, Any]] = None          # {of: glob, eq|min|max: N}
    number: Dict[str, Dict[str, float]] = field(default_factory=dict)
    matches: Dict[str, str] = field(default_factory=dict)
    one_of: Dict[str, List[str]] = field(default_factory=dict)
    each: Optional[Dict[str, Any]] = None           # {of: glob, <predicate>}
    relation: Optional[Dict[str, Any]] = None       # {left, op, of|right}
    state: Optional[str] = None

    @classmethod
    def parse(cls, raw: Any, where: str) -> "Condition":
        if raw is None:
            return cls()
        if not isinstance(raw, dict):
            raise SpecError(f"{where}: expected a mapping, got {type(raw).__name__}")
        unknown = set(raw) - _COND_KEYS
        if unknown:
            raise SpecError(f"{where}: unknown key(s) {sorted(unknown)}; allowed: {sorted(_COND_KEYS)}")
        cond = cls(
            screen=raw.get("screen"),
            visible=list(raw.get("visible") or []),
            absent=list(raw.get("absent") or []),
            text=dict(raw.get("text") or {}),
            count=raw.get("count"),
            number=dict(raw.get("number") or {}),
            matches=dict(raw.get("matches") or {}),
            one_of={k: list(v) for k, v in (raw.get("one_of") or {}).items()},
            each=raw.get("each"),
            relation=raw.get("relation"),
            state=raw.get("state"),
        )
        cond._validate(where)
        return cond

    #: `count: {eq: 0}` is the empty-set trap: it passes against a screen with
    #: nothing on it, which is how a gate reports "everything tagged is drivable"
    #: about an app that never rendered. Legal ONLY against an explicit
    #: `state: empty`, where zero is the claim being made.
    def _validate(self, where: str) -> None:
        for spec, name in ((self.count, "count"), (self.each, "each")):
            if spec is not None:
                if not isinstance(spec, dict) or "of" not in spec:
                    raise SpecError(f"{where}: `{name}` needs an `of:` selector")
        if self.count is not None:
            if not ({"eq", "min", "max"} & set(self.count)):
                raise SpecError(f"{where}: `count` needs one of eq/min/max")
            if self.count.get("eq") == 0 and self.state != "empty":
                raise SpecError(
                    f"{where}: `count: {{eq: 0}}` is satisfied by absence; it is legal "
                    f"only alongside `state: empty`, where zero is the assertion"
                )
        if self.relation is not None:
            r = self.relation
            if "left" not in r or "op" not in r:
                raise SpecError(f"{where}: `relation` needs `left` and `op`")
            if r["op"] not in _RELATION_OPS:
                raise SpecError(f"{where}: relation op {r['op']!r} not in {sorted(_RELATION_OPS)}")
            if not (r.get("of") or r.get("right")):
                raise SpecError(f"{where}: `relation` needs `of:` (a list) or `right:`")
        if self.state is not None and self.state not in _STATES:
            raise SpecError(f"{where}: state {self.state!r} not in {sorted(_STATES)}")

    def is_empty(self) -> bool:
        return not (
            self.screen or self.visible or self.absent or self.text
            or self.count or self.number or self.matches or self.one_of
            or self.each or self.relation or self.state
        )

    def describe(self) -> str:
        bits = []
        if self.screen:
            bits.append(f"screen={self.screen}")
        if self.visible:
            bits.append(f"visible={self.visible}")
        if self.absent:
            bits.append(f"absent={self.absent}")
        if self.text:
            bits.append(f"text={self.text}")
        for name, val in (("count", self.count), ("number", self.number),
                          ("matches", self.matches), ("one_of", self.one_of),
                          ("each", self.each), ("relation", self.relation),
                          ("state", self.state)):
            if val:
                bits.append(f"{name}={val}")
        return ", ".join(bits) or "(nothing)"


@dataclass
class Action:
    """One interaction. Exactly one of click/input/scroll_to/wait per entry."""

    kind: str
    target: str
    value: Optional[str] = None
    wait_ms: int = 500
    #: LOCAL DELTA, `cleanup:` only: run this action only while `when` is on
    #: screen. A cleanup closes what the flow opened, and a control that
    #: TOGGLES (People's `btn_contacts_add_open` opens the add card and closes
    #: it) would open on a flow that failed before it got there. `when:` names
    #: the thing being closed, so "already gone" is decided by the card and
    #: not by the toggle that is always on screen (macOS leg, run 36600766576).
    when: Optional[str] = None

    @classmethod
    def parse(cls, raw: Any, where: str, *, cleanup: bool = False) -> "Action":
        if not isinstance(raw, dict):
            raise SpecError(f"{where}: expected a mapping, got {type(raw).__name__}")
        unknown = set(raw) - _ACTION_KEYS
        if unknown:
            raise SpecError(f"{where}: unknown key(s) {sorted(unknown)}; allowed: {sorted(_ACTION_KEYS)}")
        wait_ms = int(raw.get("wait_ms", 500))
        when = raw.get("when")
        if when is not None and not cleanup:
            # A step's action that quietly does nothing is a step that asserts
            # nothing; only a cleanup may be conditional.
            raise SpecError(f"{where}: `when:` is for `cleanup:` actions only")
        if when is not None and (not isinstance(when, str) or not when.strip()):
            raise SpecError(f"{where}: `when:` names one tag")
        verbs = [k for k in ("click", "input", "scroll_to", "wait") if k in raw]
        if len(verbs) != 1:
            raise SpecError(
                f"{where}: exactly one of click/input/scroll_to/wait per action, got {verbs or 'none'}"
            )
        verb = verbs[0]
        if verb == "input":
            spec = raw["input"]
            if not isinstance(spec, dict) or len(spec) != 1:
                raise SpecError(f"{where}: `input` takes one {{tag: text}} pair")
            tag, text = next(iter(spec.items()))
            return cls("input", str(tag), str(text), wait_ms, when)
        return cls(verb, str(raw[verb]), None, wait_ms, when)

    def describe(self) -> str:
        if self.kind == "input":
            return f"input {self.target!r} = {self.value!r}"
        return f"{self.kind} {self.target!r}"


@dataclass
class Step:
    step_id: str
    title: str
    description: str = ""
    requires: Condition = field(default_factory=Condition)
    do: List[Action] = field(default_factory=list)
    expect: Condition = field(default_factory=Condition)
    #: A step that may legitimately not apply on this build. It still asserts
    #: everything it says WHEN its preconditions hold; it is skipped, loudly, when
    #: they do not. Never use this to paper over a step that ought to work.
    optional_step: bool = False

    @classmethod
    def parse(cls, raw: Any, index: int) -> "Step":
        where = f"step[{index}]"
        if not isinstance(raw, dict):
            raise SpecError(f"{where}: expected a mapping, got {type(raw).__name__}")
        unknown = set(raw) - _STEP_KEYS
        if unknown:
            raise SpecError(f"{where}: unknown key(s) {sorted(unknown)}; allowed: {sorted(_STEP_KEYS)}")
        for required_key in ("step_id", "title"):
            if not raw.get(required_key):
                raise SpecError(f"{where}: `{required_key}` is required")
        where = f"step {raw['step_id']!r}"
        step = cls(
            step_id=str(raw["step_id"]),
            title=str(raw["title"]),
            description=str(raw.get("description") or ""),
            requires=Condition.parse(raw.get("requires"), f"{where}.requires"),
            do=[Action.parse(a, f"{where}.do[{i}]") for i, a in enumerate(raw.get("do") or [])],
            expect=Condition.parse(raw.get("expect"), f"{where}.expect"),
            optional_step=bool(raw.get("optional_step", False)),
        )
        # A step that neither acts nor asserts is a comment pretending to be a test.
        if not step.do and step.expect.is_empty():
            raise SpecError(f"{where}: has no `do` and no `expect` — it would assert nothing")
        return step


@dataclass
class FlowSpec:
    flow: str
    title: str
    steps: List[Step]
    description: str = ""
    client_floor: Optional[str] = None
    path: Optional[Path] = None
    #: LOCAL DELTA: the CSD this flow tests, when it names one (`csd: CSD-005`).
    csd_id: Optional[str] = None
    csd: Any = None  # testing.gate.csd_doc.CsdDoc
    #: LOCAL DELTA: the fixture this flow needs (`fixture: two_node`), or None.
    fixture: Optional[str] = None
    #: LOCAL DELTA: actions run AFTER the flow, pass or fail — closing what it
    #: opened. A flow stops at its first failed step, and a card that step left
    #: open is the next flow's failure: csd_092 opened the contact-code card,
    #: failed on its second step, and `people`, `csd_005` and `csd_006` then
    #: failed for its reason on every desktop leg (2026-09-29). Only the flow
    #: knows what it opened; the runner guarantees the closing runs.
    cleanup: List[Action] = field(default_factory=list)

    def variables(self) -> List[str]:
        """Every `${NAME}` the flow names, sorted."""
        names = {n for step in self.steps for n in _step_variables(step)}
        for a in self.cleanup:
            names |= set(_VAR.findall(a.target)) | set(_VAR.findall(a.value or ""))
            names |= set(_VAR.findall(a.when or ""))
        return sorted(names)

    @classmethod
    def load(cls, path: Path, csd_root: Optional[Path] = None) -> "FlowSpec":
        try:
            raw = yaml.safe_load(path.read_text(encoding="utf-8"))
        except yaml.YAMLError as exc:
            raise SpecError(f"{path}: not valid YAML: {exc}") from exc
        if not isinstance(raw, dict):
            raise SpecError(f"{path}: expected a mapping at the top level")
        unknown = set(raw) - _FLOW_KEYS
        if unknown:
            raise SpecError(f"{path}: unknown key(s) {sorted(unknown)}; allowed: {sorted(_FLOW_KEYS)}")
        if not raw.get("flow"):
            raise SpecError(f"{path}: `flow` (the flow's id) is required")
        steps_raw = raw.get("steps") or []
        if not steps_raw:
            raise SpecError(f"{path}: a flow with no steps asserts nothing")
        steps = [Step.parse(s, i) for i, s in enumerate(steps_raw)]
        seen: Dict[str, int] = {}
        for i, s in enumerate(steps):
            if s.step_id in seen:
                raise SpecError(f"{path}: duplicate step_id {s.step_id!r} (steps {seen[s.step_id]} and {i})")
            seen[s.step_id] = i
        spec = cls(
            flow=str(raw["flow"]),
            title=str(raw.get("title") or raw["flow"]),
            description=str(raw.get("description") or ""),
            client_floor=raw.get("client"),
            steps=steps,
            path=path,
        )
        fixture = raw.get("fixture")
        if fixture is not None and fixture not in FIXTURES:
            raise SpecError(f"{path}: `fixture: {fixture!r}` is not one of {sorted(FIXTURES)}")
        spec.fixture = fixture
        cleanup_raw = raw.get("cleanup") or []
        if not isinstance(cleanup_raw, list):
            raise SpecError(f"{path}: `cleanup` is a list of actions (click / input / scroll_to / wait)")
        spec.cleanup = [Action.parse(a, f"{path}: cleanup[{i}]", cleanup=True) for i, a in enumerate(cleanup_raw)]
        # A `${NAME}` with no fixture to fill it would reach the app as the
        # literal text `${NAME}` and fail as "element not found" — the one
        # failure that looks exactly like a broken app. Refused at load.
        names = spec.variables()
        if names and fixture is None:
            raise SpecError(
                f"{path}: names {', '.join('${' + n + '}' for n in names)} but asks for no "
                f"`fixture:` that could supply {'it' if len(names) == 1 else 'them'}"
            )
        if "csd" in raw:
            spec._bind_csd(raw["csd"], csd_root)
        return spec

    def _bind_csd(self, csd_id: Any, csd_root: Optional[Path]) -> None:
        """LOCAL DELTA: load the named CSD and hold the flow to it.

        A CSD that is missing or does not parse is a LOAD ERROR, never a skip —
        the flow would otherwise run with `state:` and `relation:` unanchored.
        And a flow may not name a `proposed:` tag anywhere: no client carries
        it, so driving or asserting it fails as "element not found", which is
        indistinguishable from a broken app.
        """
        from testing.gate.csd_doc import CsdError, load as load_csd  # noqa: PLC0415

        where = f"{self.path}"
        try:
            doc = load_csd(csd_id, csd_root)
        except CsdError as e:
            raise SpecError(f"{where}: `csd:` {e}") from e
        self.csd_id, self.csd = str(csd_id), doc

        for step in self.steps:
            at = f"{where}: step {step.step_id!r}"
            for half, cond in (("requires", step.requires), ("expect", step.expect)):
                for tag in _tags_of(cond):
                    if tag in doc.proposed:
                        raise SpecError(
                            f"{at}.{half} names {tag!r}, which {doc.csd_id} still marks "
                            f"`proposed:` — a flow cannot assert a tag no client carries"
                        )
                # Globs (`count`/`each` `of:`) are NOT checked against the
                # proposed set: CSD-005's real `contacts_row_*` rows share a
                # prefix with its proposed `contacts_row_trust` chip, and
                # refusing the glob would refuse a flow that names no proposed
                # tag. `each` over an empty match already fails at run time.
                if cond.state is not None:
                    if cond.state in doc.proposed_states:
                        raise SpecError(
                            f"{at}.{half} asserts `state: {cond.state}`, whose tag "
                            f"{doc.csd_id} still marks `proposed:`"
                        )
                    if cond.state not in doc.state_tags:
                        raise SpecError(
                            f"{at}.{half} asserts `state: {cond.state}`, but {doc.csd_id}'s "
                            f"`states:` names no tag for it, so it cannot be checked"
                        )
                if cond.relation is not None:
                    r = cond.relation
                    operands = [r["left"]] + list(r.get("of") or []) + (
                        [r["right"]] if r.get("right") else [])
                    for fid in map(str, operands):
                        if fid in doc.proposed_fields:
                            raise SpecError(
                                f"{at}.{half} relates {fid!r}, whose tag {doc.csd_id} "
                                f"still marks `proposed:`"
                            )
                        if ":" in fid and fid not in doc.field_tags:
                            raise SpecError(
                                f"{at}.{half} relates {fid!r}, which is not a field "
                                f"of {doc.csd_id}'s `shows:`"
                            )
            for action in step.do:
                if action.target in doc.proposed:
                    raise SpecError(
                        f"{at}.do drives {action.target!r}, which {doc.csd_id} still "
                        f"marks `proposed:`"
                    )
        for action in self.cleanup:
            for tag in (action.target, action.when):
                if tag in doc.proposed:
                    raise SpecError(
                        f"{where}: cleanup names {tag!r}, which {doc.csd_id} still "
                        f"marks `proposed:`"
                    )


class UnresolvedVariable(Exception):
    """A `${NAME}` the fixture did not produce. The message says why, from the
    fixture's own notes, so the failure names the missing value's cause."""


def _strings_of(step: "Step") -> List[str]:
    out: List[str] = []
    for a in step.do:
        out.append(a.target)
        if a.value is not None:
            out.append(a.value)
    for cond in (step.requires, step.expect):
        out += list(cond.visible) + list(cond.absent)
        for d in (cond.text, cond.matches):
            out += list(d.keys()) + list(d.values())
        out += list(cond.number.keys())
        for k, v in cond.one_of.items():
            out += [k, *v]
        for sel in (cond.count, cond.each):
            if sel:
                out.append(str(sel.get("of", "")))
    return out


def _step_variables(step: "Step") -> set:
    return {m for text in _strings_of(step) for m in _VAR.findall(text)}


def substitute(text: str, values: Dict[str, str], why: Sequence[str] = (), *,
               escape: bool = False) -> str:
    """`${NAME}` -> values[NAME]. A name with no value raises UnresolvedVariable
    quoting `why` (the fixture's notes). `escape` for a regex (`matches:`)."""
    def one(m: "re.Match[str]") -> str:
        name = m.group(1)
        val = values.get(name)
        if not val:
            reason = "; ".join(why) or "the fixture produced no such value"
            raise UnresolvedVariable(f"${{{name}}} was not provided by the fixture: {reason}")
        return re.escape(val) if escape else val
    return _VAR.sub(one, text)


def resolve_step(step: "Step", values: Dict[str, str], why: Sequence[str] = ()) -> "Step":
    """A copy of `step` with every `${NAME}` replaced. The original is untouched,
    so one loaded spec can run again against another fixture."""
    import copy  # noqa: PLC0415

    if not _step_variables(step):
        return step

    def sub(t: str) -> str:
        return substitute(t, values, why)

    out = copy.deepcopy(step)
    for a in out.do:
        a.target = sub(a.target)
        if a.value is not None:
            a.value = sub(a.value)
    for cond in (out.requires, out.expect):
        cond.visible = [sub(t) for t in cond.visible]
        cond.absent = [sub(t) for t in cond.absent]
        cond.text = {sub(k): sub(v) for k, v in cond.text.items()}
        cond.matches = {sub(k): substitute(v, values, why, escape=True)
                        for k, v in cond.matches.items()}
        cond.number = {sub(k): v for k, v in cond.number.items()}
        cond.one_of = {sub(k): [sub(x) for x in v] for k, v in cond.one_of.items()}
        for sel in (cond.count, cond.each):
            if sel and "of" in sel:
                sel["of"] = sub(str(sel["of"]))
    return out


def check_client_floor(floor: Optional[str], actual: Optional[str]) -> Optional[str]:
    """Return a refusal message if `actual` is below `floor`, else None.

    VERSION-LEGIBLE, NOT VERSION-PINNED (CIRISClient#39). A flow states the client
    it was written against. Running it on an older one is refused LOUDLY, because a
    tag that does not exist yet fails as "element not found" — indistinguishable
    from a broken app. A PEP 440 local segment (`0.5.208+preview.gNNNNNNN`) is a
    build of that release, so it satisfies the release's floor.
    """
    if not floor:
        return None
    # THREE FORMS.
    #   `>=X`        written against release X.
    #   `>X`         no released client at or below X carries the surface.
    #   `unreleased` NO release carries it at all -- the surface comes from an
    #                unmerged PR (the CSD's Origin names it). This is the honest
    #                form for that state and the one that does not chase: `>X`
    #                stops refusing the moment X+1 ships, which on 0.5.213 turned
    #                CIRISClient#45's flows into binding verdicts against a client
    #                that still does not carry them, and reddened the gate.
    #                Replaced by `>=<carrying release>` when the PR ships.
    if str(floor).strip().lower() == "unreleased":
        return (
            "this flow drives a surface that no released client carries yet "
            "(`client: unreleased` -- see the CSD's Origin); it runs when the CSD names "
            "the release that ships it"
        )
    m = re.match(r"^(>=|>)\s*([0-9]+(?:\.[0-9]+)*)$", str(floor).strip())
    if not m:
        return f"`client: {floor!r}` is not understood; use e.g. \">=0.5.208\", \">0.5.212\" or \"unreleased\""
    if not actual:
        return None  # cannot tell; do not invent a refusal
    strict = m.group(1) == ">"
    want = [int(x) for x in m.group(2).split(".")]
    have = [int(x) for x in actual.split("+", 1)[0].split(".") if x.isdigit()]
    if strict and have <= want:
        return (
            f"this flow drives a surface no released client at or below {m.group(2)} carries "
            f"(`client: {floor}`), and {actual} is installed — it cannot start here until the "
            "floor names the release that ships the surface"
        )
    if have < want:
        return (
            f"this flow is written against ciris-client {floor}, but {actual} is installed — "
            "tags it drives may not exist yet, which would fail as 'element not found'"
        )
    return None


@dataclass
class StepResult:
    step_id: str
    title: str
    status: str  # "pass" | "fail" | "skipped"
    phase: str = ""  # which half failed: "requires" | "do" | "expect"
    detail: str = ""
    duration_ms: int = 0
    screenshot: Optional[str] = None
    drivable: List[str] = field(default_factory=list)


def _match_glob(tags: Sequence[str], pattern: str) -> List[str]:
    """Tags matching a `row_capacity_*` style selector, sorted."""
    return sorted(t for t in tags if fnmatch.fnmatchcase(t, pattern))


class FlowRunner:
    """Executes a FlowSpec against a connected DesktopAppHelper."""

    def __init__(self, helper, platform=None, artifacts: Optional[Path] = None,
                 field_tags: Optional[Dict[str, str]] = None,
                 state_tags: Optional[Dict[str, str]] = None,
                 variables: Optional[Dict[str, str]] = None,
                 variable_notes: Sequence[str] = ()) -> None:
        self.helper = helper
        #: LOCAL DELTA: `${NAME}` -> value, from the flow's fixture, and the
        #: fixture's notes — quoted when a flow names a value it did not produce.
        self.variables: Dict[str, str] = dict(variables or {})
        self.variable_notes: List[str] = list(variable_notes)
        self.platform = platform
        self.artifacts = Path(artifacts) if artifacts else None
        self.results: List[StepResult] = []
        #: LOCAL DELTA: what the flow's `cleanup:` could not do, one line each.
        self.cleanup_failures: List[str] = []
        #: CSD `ceg:` field id -> the tag carrying it, from the CSD's `shows:`
        #: block. `relation` operands are field ids, so without this a flow
        #: could only relate boxes rather than constitutional values.
        self.field_tags: Dict[str, str] = dict(field_tags or {})
        #: LOCAL DELTA: state -> tag, from the CSD's `states:` block. Filled
        #: from the spec's CSD at `run()` when the caller does not pass it.
        self.state_tags: Dict[str, str] = dict(state_tags or {})

    async def _drivable(self) -> List[str]:
        """Tags actually ON SCREEN. The failure message's most useful sentence.

        Uses the element's `visible` flag, not its presence: /tree lists everything
        ever composed (registry-never-forgets), so a presence list would name
        elements the user cannot see and send the reader hunting the wrong bug.
        """
        try:
            elements = await self.helper.get_elements()
        except Exception:  # noqa: BLE001 -- diagnosis must never raise
            return []
        out = []
        for e in elements:
            on_screen = e.visible if e.visible is not None else (e.width > 0 and e.height > 0)
            if on_screen:
                out.append(e.test_tag)
        return sorted(out)

    async def _not_on_screen(self, tag: str) -> str:
        """LOCAL DELTA: WHY a `visible:` tag is not on screen — never composed,
        or composed but off screen after the scroll budget. The two send a
        reader to different places: the first to the client (the tag is not
        drawn), the second to the flow before this one (what it left open) or
        to the screen's scroll container (what `/scroll` answered). The macOS
        leg's csd_006 (run 36600766576) said "is not on screen" for a row that
        WAS composed, below an add card the previous flow had left open, on a
        screen the harness cannot scroll; the words above are what it took a
        screenshot to learn."""
        if await self.helper.get_element(tag) is None:
            return f"{tag!r} is not composed (not in /tree)"
        scrolls = getattr(self.helper, "last_scroll", None) or []
        answered = f" (scrolls: {'; '.join(scrolls)})" if scrolls else ""
        return f"{tag!r} is composed but off screen after scrolling{answered}"

    async def _check(self, cond: Condition, label: str) -> Optional[str]:
        """None if the condition holds, else the FIRST failure, named precisely."""
        if cond.screen:
            actual = await self.helper.get_screen()
            if actual != cond.screen:
                return f"{label}: expected screen {cond.screen!r}, on {actual!r}"
        for tag in cond.visible:
            # THE RUNNER SCROLLS (FSD/CSD_STANDARD.md §3.3): a card below the fold
            # of a long screen is composed and reachable, not missing. Only a tag
            # that stays off screen after the scroll budget is "not on screen".
            if not await self.helper.is_element_visible(tag):
                await self.helper.scroll_into_view(tag)
            if not await self.helper.is_element_visible(tag):
                return f"{label}: {await self._not_on_screen(tag)}"
        for tag in cond.absent:
            if await self.helper.is_element_visible(tag):
                return f"{label}: {tag!r} is on screen but should not be"
        for tag, want in cond.text.items():
            elem = await self.helper.get_element(tag)
            if elem is None:
                return f"{label}: {tag!r} not found, so its text cannot be checked"
            if want not in (elem.text or ""):
                return f"{label}: {tag!r} text {elem.text!r} does not contain {want!r}"

        # ── CSD/3 §3: what the UI SHOWS ─────────────────────────────────────
        on_screen = await self._drivable()

        if cond.state:
            # The four states are declared per surface in the CSD's `states:`
            # block; here the flow asserts which one it is in by the tag that
            # state's row names. Kept as a plain visibility check rather than a
            # new mechanism: the CSD already had to name a tag per state, and a
            # second way to say the same thing is how two spellings drift.
            #
            # LOCAL DELTA. Upstream this was `pass`, so `state:` asserted
            # NOTHING — a vacuous green in the one predicate CSD/3 makes
            # mandatory. It now reads the CSD's `states:` map: that state's tag
            # must be on screen and every OTHER state's tag must not be, so an
            # error cannot pass for an empty list. With no map it FAILS rather
            # than passing: an unanchored `state:` is unchecked, not true.
            want = self.state_tags.get(cond.state)
            if not want:
                return (f"{label}: `state: {cond.state}` cannot be checked — no CSD "
                        f"`states:` tag for it (does the flow name its `csd:`?)")
            if not await self.helper.is_element_visible(want):
                await self.helper.scroll_into_view(want)
            if not await self.helper.is_element_visible(want):
                return f"{label}: state {cond.state!r} — its tag {await self._not_on_screen(want)}"
            for other, tag in sorted(self.state_tags.items()):
                if other != cond.state and tag != want and await self.helper.is_element_visible(tag):
                    return (f"{label}: state {cond.state!r} expected, but {other!r}'s "
                            f"tag {tag!r} is on screen")

        if cond.count is not None:
            got = _match_glob(on_screen, cond.count["of"])
            n = len(got)
            for key, ok in (("eq", n == cond.count.get("eq")),
                            ("min", n >= cond.count.get("min", n)),
                            ("max", n <= cond.count.get("max", n))):
                if key in cond.count and not ok:
                    return (f"{label}: count of {cond.count['of']!r} is {n}, "
                            f"{key} {cond.count[key]} — on screen: {got}")

        for tag, bounds in cond.number.items():
            val = await self._number(tag)
            if val is None:
                # A MISSING VALUE IS NOT ZERO. Reading it as one is a score
                # where there is none, which on a capacity card is a claim.
                return f"{label}: {tag!r} text is not a number, so it cannot be in range"
            for key, ok in (("eq", val == bounds.get("eq")),
                            ("min", val >= bounds.get("min", val)),
                            ("max", val <= bounds.get("max", val))):
                if key in bounds and not ok:
                    return f"{label}: {tag!r} is {val}, {key} {bounds[key]}"

        for tag, pattern in cond.matches.items():
            elem = await self.helper.get_element(tag)
            if elem is None:
                return f"{label}: {tag!r} not found, so its text cannot be matched"
            if not re.search(pattern, elem.text or ""):
                return f"{label}: {tag!r} text {elem.text!r} does not match /{pattern}/"

        for tag, allowed in cond.one_of.items():
            elem = await self.helper.get_element(tag)
            got = (elem.text or "") if elem else None
            if got not in allowed:
                return f"{label}: {tag!r} is {got!r}, not one of {allowed}"

        if cond.each is not None:
            tags = _match_glob(on_screen, cond.each["of"])
            if not tags:
                # An empty selector must FAIL, not pass vacuously.
                return f"{label}: `each` selector {cond.each['of']!r} matched nothing on screen"
            for tag in tags:
                if "number" in cond.each:
                    val = await self._number(tag)
                    b = cond.each["number"]
                    if val is None:
                        return f"{label}: each — {tag!r} text is not a number"
                    if "min" in b and val < b["min"]:
                        return f"{label}: each — {tag!r} is {val}, min {b['min']}"
                    if "max" in b and val > b["max"]:
                        return f"{label}: each — {tag!r} is {val}, max {b['max']}"

        if cond.relation is not None:
            err = await self._relation(cond.relation, label)
            if err:
                return err
        return None

    async def _settled(self, cond: Condition, label: str, budget: float = EXPECT_SETTLE_S) -> Optional[str]:
        """LOCAL DELTA: `_check`, re-read until it holds or `budget` runs out —
        an assertion made in the instant of a click is a race, not a test (the
        rule `run_flows.navigate` already states for hops)."""
        import asyncio  # noqa: PLC0415

        deadline = time.monotonic() + budget
        while True:
            err = await self._check(cond, label)
            if err is None or time.monotonic() >= deadline:
                return err
            await asyncio.sleep(0.25)

    async def _number(self, tag: str) -> Optional[float]:
        """The element's text as a number, or None if it is not one."""
        elem = await self.helper.get_element(tag)
        if elem is None:
            return None
        m = re.search(r"-?\d+(?:\.\d+)?", elem.text or "")
        return float(m.group()) if m else None

    async def _relation(self, rel: Dict[str, Any], label: str) -> Optional[str]:
        """A value's relationship to other values.

        Operands are CSD `ceg:` field ids, resolved to their tags by the spec's
        `field_tags` map — the relationship is between VALUES and the meaning
        travels with the constitutional family, not with the box it is drawn in.
        `capacity:composite` being the minimum of five factors is a fact about
        the vocabulary; a UI rendering it as an average misstates the
        Constitution rather than merely looking wrong.
        """
        resolve = self.field_tags.get if self.field_tags else (lambda k, d=None: d)
        left_tag = resolve(rel["left"], rel["left"])
        left = await self._number(left_tag)
        if left is None:
            return f"{label}: relation left {rel['left']!r} ({left_tag}) is not a number"

        op = rel["op"]
        if op in {"min_of", "max_of", "sum_of"}:
            vals = []
            for fid in rel["of"]:
                tag = resolve(fid, fid)
                v = await self._number(tag)
                if v is None:
                    return f"{label}: relation operand {fid!r} ({tag}) is not a number"
                vals.append(v)
            want = {"min_of": min, "max_of": max, "sum_of": sum}[op](vals)
            if left != want:
                return (f"{label}: {rel['left']} is {left}, but {op} {rel['of']} is {want} "
                        f"(values {vals})")
            return None

        right_tag = resolve(rel.get("right"), rel.get("right"))
        right = await self._number(right_tag)
        if right is None:
            return f"{label}: relation right {rel.get('right')!r} is not a number"
        ok = {"eq": left == right, "ne": left != right, "lt": left < right,
              "lte": left <= right, "gt": left > right, "gte": left >= right}[op]
        return None if ok else f"{label}: {left} {op} {right} is false"

    def _why(self) -> str:
        """LOCAL DELTA: the driver's own reason for a refusal, when the helper
        kept one (`last_error`) — "did not succeed" alone sent csd_047's reader
        to the wrong place."""
        why = getattr(self.helper, "last_error", "")
        return f" ({why})" if why else ""

    async def _do(self, action: Action) -> Optional[str]:
        if hasattr(self.helper, "last_error"):
            self.helper.last_error = ""
        try:
            if action.kind == "click":
                ok = await self.helper.click(action.target, timeout=action.wait_ms * 4)
                return None if ok else f"click {action.target!r} did not succeed{self._why()}"
            if action.kind == "input":
                ok = await self.helper.input_text(action.target, action.value or "")
                return None if ok else f"input into {action.target!r} did not succeed{self._why()}"
            if action.kind == "scroll_to":
                ok = await self.helper.scroll_into_view(action.target)
                return None if ok else f"could not bring {action.target!r} on screen"
            if action.kind == "wait":
                ok = await self.helper.wait_for_element(action.target, timeout=action.wait_ms * 4)
                return None if ok else f"{action.target!r} never appeared"
        except Exception as exc:  # noqa: BLE001 -- an action's failure is a result, not a crash
            return f"{action.describe()} raised {type(exc).__name__}: {exc}"
        return f"unknown action kind {action.kind!r}"

    def _shot(self, spec: FlowSpec, step: Step) -> Optional[str]:
        if not (self.platform and self.artifacts):
            return None
        dest = self.artifacts / "shots" / f"flow-{spec.flow}-{step.step_id}.png"
        dest.parent.mkdir(parents=True, exist_ok=True)
        try:
            got = self.platform.capture("screenshot", dest)
        except Exception:  # noqa: BLE001
            return None
        return str(got) if got else None

    async def run(self, spec: FlowSpec) -> bool:
        """The steps, then — pass, fail or crash — the flow's `cleanup:`."""
        try:
            return await self._steps(spec)
        finally:
            await self._cleanup(spec)

    async def _cleanup(self, spec: FlowSpec) -> None:
        """LOCAL DELTA: close what the flow opened, whatever its verdict. A
        failure here is recorded, never raised: it is not this flow's verdict,
        and hiding the verdict behind it would help nobody."""
        for action in spec.cleanup:
            try:
                action = Action(action.kind,
                                substitute(action.target, self.variables, self.variable_notes),
                                substitute(action.value, self.variables, self.variable_notes)
                                if action.value is not None else None,
                                action.wait_ms,
                                substitute(action.when, self.variables, self.variable_notes)
                                if action.when is not None else None)
            except UnresolvedVariable as exc:
                self.cleanup_failures.append(str(exc))
                print(f"     cleanup: {exc}")
                continue
            # Already gone is nothing to close: a flow whose own last step shut
            # the card it opened must not then report its cleanup as a failure.
            # `when:` says what "gone" means for a control that would otherwise
            # open the thing it is there to close.
            if action.when is not None and await self.helper.get_element(action.when) is None:
                print(f"     cleanup: nothing to close \u2014 {action.when!r} is not on screen")
                continue
            if action.kind in ("click", "input") and await self.helper.get_element(action.target) is None:
                print(f"     cleanup: nothing to close \u2014 {action.target!r} is not on screen")
                continue
            err = await self._do(action)
            if err:
                self.cleanup_failures.append(err)
                print(f"     cleanup: {err}")
            else:
                print(f"     cleanup: {action.describe()}")

    async def _steps(self, spec: FlowSpec) -> bool:
        if spec.csd is not None:
            # LOCAL DELTA: a flow that names its CSD carries its own maps.
            self.field_tags = self.field_tags or dict(spec.csd.field_tags)
            self.state_tags = self.state_tags or dict(spec.csd.state_tags)
        print(f"\n FLOW {spec.flow} — {spec.title}")
        if spec.description:
            print(f"   {spec.description}")
        print(f"   {len(spec.steps)} steps, from {spec.path}")

        for step in spec.steps:
            started = time.monotonic()
            print(f"\n  [{step.step_id}] {step.title}")

            # LOCAL DELTA: fill `${NAME}` from the fixture. A value the fixture
            # did not produce FAILS the step, optional or not: the flow asked
            # for it, and a skip would report a two-node claim as untested
            # rather than as broken.
            try:
                step = resolve_step(step, self.variables, self.variable_notes)
            except UnresolvedVariable as exc:
                print(f"     [FAIL] {exc}")
                self.results.append(StepResult(step.step_id, step.title, "fail", "requires", str(exc)))
                return False

            # BEFORE. A precondition failure is reported as one -- the difference
            # between "this flow cannot start here" and "this element is broken".
            pre = await self._check(step.requires, "requires")
            if pre:
                drivable = await self._drivable()
                if step.optional_step:
                    print(f"     SKIP (optional): {pre}")
                    self.results.append(
                        StepResult(step.step_id, step.title, "skipped", "requires", pre, drivable=drivable)
                    )
                    continue
                print(f"     [FAIL] {pre}")
                print(f"            on screen and drivable now: {drivable}")
                self.results.append(
                    StepResult(
                        step.step_id, step.title, "fail", "requires", pre,
                        int((time.monotonic() - started) * 1000),
                        self._shot(spec, step), drivable,
                    )
                )
                return False
            if not step.requires.is_empty():
                print(f"     requires ok: {step.requires.describe()}")

            for action in step.do:
                err = await self._do(action)
                if err:
                    drivable = await self._drivable()
                    print(f"     [FAIL] {err}")
                    print(f"            on screen and drivable now: {drivable}")
                    self.results.append(
                        StepResult(
                            step.step_id, step.title, "fail", "do", err,
                            int((time.monotonic() - started) * 1000),
                            self._shot(spec, step), drivable,
                        )
                    )
                    return False
                print(f"     did: {action.describe()}")

            post = await self._settled(step.expect, "expect")
            drivable = await self._drivable()
            shot = self._shot(spec, step)
            if post:
                print(f"     [FAIL] {post}")
                print(f"            on screen and drivable now: {drivable}")
                self.results.append(
                    StepResult(
                        step.step_id, step.title, "fail", "expect", post,
                        int((time.monotonic() - started) * 1000), shot, drivable,
                    )
                )
                return False
            ms = int((time.monotonic() - started) * 1000)
            print(f"     [OK] {step.expect.describe()} ({ms}ms)")
            self.results.append(
                StepResult(step.step_id, step.title, "pass", "", "", ms, shot, drivable)
            )
        return True

    def write_report(self, spec: FlowSpec) -> Optional[Path]:
        """The machine-readable half. The console half is printed as it goes."""
        if not self.artifacts:
            return None
        dest = self.artifacts / "flows" / f"{spec.flow}.json"
        dest.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "flow": spec.flow,
            "title": spec.title,
            "spec": str(spec.path),
            "csd": spec.csd_id,
            "client_floor": spec.client_floor,
            "passed": all(r.status != "fail" for r in self.results),
            "cleanup_failures": list(self.cleanup_failures),
            "steps": [
                {
                    "step_id": r.step_id, "title": r.title, "status": r.status,
                    "phase": r.phase, "detail": r.detail, "duration_ms": r.duration_ms,
                    "screenshot": r.screenshot, "drivable": r.drivable,
                }
                for r in self.results
            ],
        }
        dest.write_text(json.dumps(payload, indent=2), encoding="utf-8")
        return dest

    def summary(self, spec: FlowSpec) -> str:
        p = sum(1 for r in self.results if r.status == "pass")
        s = sum(1 for r in self.results if r.status == "skipped")
        f = sum(1 for r in self.results if r.status == "fail")
        tail = f", {s} skipped" if s else ""
        return f"{spec.flow}: {p}/{len(spec.steps)} passed{tail}" + (f", {f} FAILED" if f else "")


def discover(paths: Sequence[str]) -> List[Path]:
    """Spec files from an explicit list, or every .yaml under a directory."""
    out: List[Path] = []
    for raw in paths:
        p = Path(raw)
        if p.is_dir():
            out.extend(sorted(p.glob("*.yaml")) + sorted(p.glob("*.yml")))
        elif p.exists():
            out.append(p)
        else:
            raise SpecError(f"no such spec or directory: {p}")
    return out
