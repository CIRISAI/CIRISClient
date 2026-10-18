"""The optional-step skip ratchet (CIRISClient#168).

    python3 -m testing.gate.optional_skips reports/              # judge every leg's report
    python3 -m testing.gate.optional_skips reports/ --baseline   # re-record the baseline

A QUIET PASS, COUNTED. A flow step marked `optional_step: true` is SKIPPED when
its `requires` do not hold — a camera on a platform with none, a wallet row a
node release has not shipped yet. The runner prints `SKIP (optional)` per step
and the leg stays green. The last runs showed 10, 10, 10, 10 and 9 such skips
per leg with 0 FAIL, and nothing watched the number: a regression that turns a
required precondition into a quiet skip, or a node release that should have
turned a skip into a pass, both leave the board exactly as green as before.

THE RATCHET. `testing/flows/optional_skips_baseline.json` records, per leg and
per flow, how many optional steps were skipped on the last recorded run —
`{leg: {flow: count}}`. Where the leg's board is judged (run_platform's
`optional-skips` step), the run's counts are compared with the baseline:

    RISE   a flow skipped MORE than recorded → the leg FAILS. Something that
           used to hold no longer does, and "optional" is hiding it.
    STALE  a flow skipped FEWER than recorded, or a recorded flow no longer
           exists → the leg FAILS too, as STALE. Debt paid down must be
           re-recorded at once (`--baseline`), exactly as
           `packaging/check_csd_routes.py` fails a stale baseline — otherwise
           the next rise hides inside the slack.

Only flows that RAN are judged (`pass` / `fail`). A flow `refused` by its
`client:` floor or `cannot-start` produced no skip count and is already on
the board under its own verdict; counting its zero as debt paid would report
a fixture failure twice and as the wrong thing. A flow absent from the run
entirely (deleted) is STALE: its row must go.

A leg with no baseline row FAILS, naming the fix: the first run of a new leg
is where its debt is written down, not where it is waived.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Dict, Iterable, List, Mapping, Optional, Sequence, Tuple

REPO = Path(__file__).resolve().parents[2]
BASELINE = REPO / "testing" / "flows" / "optional_skips_baseline.json"

#: The flow verdicts under which a skip count means something (run_flows.PASS/FAIL).
RAN = ("pass", "fail")

Counts = Dict[str, int]


def counts_for(flows: Iterable[Mapping]) -> Tuple[Counts, set]:
    """({flow: optional skips} for flows that ran, {every flow id present})
    from a run_platform report's `flows` (run_flows.FlowOutcome dicts)."""
    ran: Counts = {}
    present: set = set()
    for f in flows:
        present.add(f["flow"])
        if f.get("status") in RAN:
            ran[f["flow"]] = sum(1 for s in f.get("steps", []) if s.get("status") == "skipped")
    return ran, present


def total(counts: Mapping[str, int]) -> int:
    return sum(counts.values())


def describe(counts: Mapping[str, int]) -> str:
    """`10 (csd_057_wallet 3, csd_092_share_contact_code 3, …)` — the per-flow
    breakdown the board and the gallery print; `0` when there are none."""
    by_flow = ", ".join(f"{k} {v}" for k, v in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0])) if v)
    return f"{total(counts)}" + (f" ({by_flow})" if by_flow else "")


def judge(leg: str, now: Mapping[str, int], present: Iterable[str],
          baseline: Mapping[str, Mapping[str, int]]) -> List[str]:
    """Every way the run's counts disagree with the baseline, as sentences.
    Empty means the ratchet holds."""
    problems: List[str] = []
    if leg not in baseline:
        return [f"no optional-skip baseline for leg {leg!r} — record one: "
                f"python3 -m testing.gate.optional_skips <reports dir> --baseline"]
    base = baseline[leg]
    present = set(present)
    for flow, n in sorted(now.items()):
        b = base.get(flow)
        if b is None:
            if n > 0:
                problems.append(f"RISE {flow}: {n} optional step(s) skipped, and the flow has no "
                                f"baseline row — a new flow records its debt before it ships")
        elif n > b:
            problems.append(f"RISE {flow}: {n} optional step(s) skipped, baseline {b} — a "
                            f"precondition that used to hold no longer does, hidden as optional")
        elif n < b:
            problems.append(f"STALE {flow}: {n} optional step(s) skipped, baseline {b} — debt "
                            f"paid down; re-record with --baseline")
    for flow, b in sorted(base.items()):
        if flow not in present:
            problems.append(f"STALE {flow}: baseline records {b} but the flow no longer exists "
                            f"on this leg; re-record with --baseline")
    return problems


def load_baseline(path: Path = BASELINE) -> Dict[str, Counts]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {}
    if not isinstance(data, dict) or not all(
        isinstance(v, dict) and all(isinstance(n, int) for n in v.values()) for v in data.values()
    ):
        raise ValueError(f"{path}: expected {{leg: {{flow: count}}}}")
    return data


def write_baseline(legs: Mapping[str, Mapping[str, int]], path: Path = BASELINE) -> None:
    ordered = {leg: dict(sorted(legs[leg].items())) for leg in sorted(legs)}
    path.write_text(json.dumps(ordered, indent=2, sort_keys=False) + "\n", encoding="utf-8")


def verdict(leg: str, flows: Sequence[Mapping], baseline: Optional[Mapping] = None) -> Tuple[bool, str]:
    """(ok, detail) for a leg's board — the one call run_platform makes."""
    base = load_baseline() if baseline is None else baseline
    now, present = counts_for(flows)
    problems = judge(leg, now, present, base)
    recorded = base.get(leg, {})
    head = f"{describe(now)}; baseline {total(recorded)}"
    if problems:
        return False, head + " — " + "; ".join(problems)
    return True, head + " — ratchet holds"


def reports_in(root: Path) -> Dict[str, dict]:
    """{leg: report} for every run_platform report under `root`, keyed by the
    file's stem (`reports/linux.json` → `linux`), which is the leg."""
    out: Dict[str, dict] = {}
    for p in sorted(root.rglob("*.json")):
        if p.parent.name != "reports":
            continue
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if isinstance(data, dict) and "flows" in data and "platform" in data:
            out[p.stem] = data
    return out


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("root", type=Path, help="a reports/ directory, or a tree of downloaded artifacts")
    ap.add_argument("--baseline", action="store_true",
                    help=f"re-record {BASELINE.relative_to(REPO)} from these reports (legs not "
                         f"in the reports keep their rows)")
    ap.add_argument("--baseline-file", type=Path, default=BASELINE)
    args = ap.parse_args(argv)

    reports = reports_in(args.root)
    if not reports:
        print(f"[FAIL] no run_platform reports under {args.root}")
        return 1
    if args.baseline:
        legs = dict(load_baseline(args.baseline_file))
        for leg, rep in reports.items():
            now, _ = counts_for(rep.get("flows", []))
            legs[leg] = now
            print(f"  {leg:8s} {describe(now)}")
        write_baseline(legs, args.baseline_file)
        print(f"baseline re-recorded: {args.baseline_file}")
        return 0

    base = load_baseline(args.baseline_file)
    bad = 0
    for leg, rep in reports.items():
        ok, detail = verdict(leg, rep.get("flows", []), base)
        print(f"  [{'OK ' if ok else 'FAIL'}] {leg}: {detail}")
        bad += not ok
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
