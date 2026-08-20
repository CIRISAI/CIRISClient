"""readiness — client build gates.

    python -m readiness                    run every gate, print the board
    python -m readiness gates              list the gates and what each asks
    python -m readiness run <id>...        run named gates
    python -m readiness --node http://127.0.0.1:4243   enable the gates that
                                                       need a live node

Exit code is 0 only when every gate passed. `unimplemented` is not a pass.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from grace.gate import Context, registry, render, report, run_gates

from . import client  # importing registers the gates

REPO = "CIRISClient"


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="readiness", description="client build gates")
    p.add_argument("command", nargs="?", default="status", choices=["status", "gates", "run"])
    p.add_argument("ids", nargs="*", help="gate ids, for `run`")
    p.add_argument(
        "--root", type=Path, default=Path.home(), help="directory holding the CIRIS* checkouts"
    )
    p.add_argument(
        "--client-tree",
        type=Path,
        default=None,
        help="client source tree (default: this repo's client/)",
    )
    p.add_argument("--node", default=None, help="base URL of a running node")
    p.add_argument("--offline", action="store_true", help="skip gates needing network")
    p.add_argument("--json", type=Path, default=None, help="write the report here")
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    if args.command == "gates":
        reg = registry()
        width = max(len(k) for k in reg)
        for gid in sorted(reg):
            print(f"  {gid.ljust(width)}  {reg[gid].question}")
        return 0

    ctx = Context(root=args.root, node_url=args.node, offline=args.offline)
    ctx.client_tree = args.client_tree  # type: ignore[attr-defined]
    results = run_gates(ctx, args.ids if args.command == "run" else None)

    tree = args.client_tree or client.client_tree(ctx)
    print(f"\n  {REPO} — build readiness  ({tree})\n")
    print(render(results))
    print()

    rep = report(results, REPO)
    if args.json:
        args.json.write_text(json.dumps(rep, indent=2) + "\n")
        print(f"  report → {args.json}\n")

    return 0 if rep["passed_all_gates"] else 1


if __name__ == "__main__":
    sys.exit(main())
