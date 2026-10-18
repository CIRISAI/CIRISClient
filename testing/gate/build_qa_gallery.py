#!/usr/bin/env python3
"""Assemble the five platform screenshots into one page you can scan in a glance.

WHY A SELF-CONTAINED FILE RATHER THAN INLINE IMAGES IN THE JOB SUMMARY.
GitHub's step summary renders Markdown but strips `data:` image URIs and cannot
reference the contents of an artifact — there is no URL for a file that only
exists inside a zip. So the summary gets a TABLE (which platforms passed, which
produced a shot), and the pictures get a single `index.html` with every image
inlined as base64. One artifact, one file, no unpacking into a directory of PNGs
and clicking through them.

WHY THE PAGE REPORTS MISSING SHOTS LOUDLY.
A gallery with four pictures reads as "four platforms" unless the fifth is
visibly absent. Every expected platform gets a tile whether or not it produced an
image, because the interesting failure — the one this whole gate exists for — is
a platform that quietly did not run. That is how v2.9.42 shipped with no APK: not
a red tick, an absent artifact.

LOCAL DELTA (was vendored verbatim from CIRISAgent tools/dev/build_qa_gallery.py).
It read `chat-*.json` reports and `shots/<platform>.png` — the AGENT's runner's
output. This repo's legs write `reports/<leg>.json` (testing/gate/run_platform.py's
Report) and `shots/<platform>-screen.png`, so on run 39023854089 — every leg green
— the gallery said "did not run" five times. It reads this repo's reports now,
and each row carries what CIRISClient#166 and #168 asked the gallery to say: the
server version the leg ran against, and how many optional steps it skipped.
"""

from __future__ import annotations

import argparse
import base64
import html
import json
import sys
from pathlib import Path
from typing import Dict, List, NamedTuple, Optional

from testing.gate import optional_skips

#: Every leg the gate covers. A tile is rendered for each, present or not.
EXPECTED = ["linux", "macos", "windows", "android", "ios"]

PRETTY = {
    "linux": "Linux",
    "macos": "macOS",
    "windows": "Windows",
    "android": "Android",
    "ios": "iOS",
    # The with-AI legs (a `with_ai` dispatch only): tiled after the five
    # when their reports are present, and never "did not run" otherwise.
    "linux-ai": "Linux, with AI",
    "android-ai": "Android, with AI",
    "windows-ai": "Windows, with AI",
}


class Tile(NamedTuple):
    platform: str
    image: Optional[Path]
    passed: Optional[bool]
    detail: str
    server: str = ""          # the node the leg ran against (run_platform --node-version)
    embedded: str = ""        # iOS: the node the app embeds (the Agent's pin)
    skips: Optional[Dict[str, int]] = None   # optional skips per flow, for flows that ran


def _find_reports(root: Path) -> Dict[str, dict]:
    """{leg: run_platform report}, keyed by the file's stem (`reports/linux.json`)."""
    return optional_skips.reports_in(root)


def _shot_for(root: Path, leg: str, report: Optional[dict]) -> Optional[Path]:
    """The leg's smoke-walk screenshot: run_platform's `screenshot` step names
    it (`shots/<platform>-screen.png`), and it sits beside the report's
    `reports/` directory in the same artifact. Three desktops write the same
    file name, so the artifact directory — not the name — says which leg."""
    if report is None:
        return None
    shot = next((s.get("shot") for s in report.get("steps", []) if s.get("name") == "screenshot" and s.get("shot")), None)
    name = Path(str(shot).replace("\\", "/")).name if shot else f"{report.get('platform', leg)}-screen.png"
    for rep_file in root.rglob(f"reports/{leg}.json"):
        candidate = rep_file.parent.parent / "shots" / name
        if candidate.exists():
            return candidate
    return None


def _tiles(root: Path) -> List[Tile]:
    reports = _find_reports(root)
    tiles: List[Tile] = []
    for leg in EXPECTED + sorted(k for k in reports if k not in EXPECTED):
        rep = reports.get(leg)
        image = _shot_for(root, leg, rep)
        if rep is None:
            tiles.append(Tile(leg, image, None, "did not run"))
            continue
        passed = bool(rep.get("ok"))
        failed = [s for s in rep.get("steps", []) if not s.get("ok")]
        flows_step = next((s for s in rep.get("steps", []) if s.get("name") == "flows"), None)
        skips, _ = optional_skips.counts_for(rep.get("flows", []))
        agent = str(rep.get("agent_version", ""))
        if passed:
            detail = (f"ciris-agent {agent} · " if agent else "") + f"walk OK · flows {flows_step.get('detail', '?') if flows_step else 'not run'}"
            if not image:
                detail += " · no screenshot captured"
        else:
            detail = "FAILED: " + "; ".join(f"{s.get('name')}: {str(s.get('detail', ''))[:160]}" for s in failed)
        tiles.append(Tile(leg, image, passed, detail, server=str(rep.get("node_version", "")),
                          embedded=str(rep.get("embedded_node_version", "")), skips=skips))
    return tiles


def _embed(path: Path) -> str:
    return base64.b64encode(path.read_bytes()).decode("ascii")


def _server_cell(t: Tile) -> str:
    if t.passed is None:
        return "—"
    s = t.server or "unknown"
    if t.embedded:
        s += f" (peer); embeds {t.embedded} — the Agent's pin"
    return s


def _skips_cell(t: Tile) -> str:
    if t.skips is None:
        return "—"
    return optional_skips.describe(t.skips)


def _render(tiles: List[Tile], run_url: str = "", server: str = "", source: str = "") -> str:
    cards = []
    for t in tiles:
        if t.passed is True:
            badge, cls = "PASS", "pass"
        elif t.passed is False:
            badge, cls = "FAIL", "fail"
        else:
            badge, cls = "DID NOT RUN", "absent"

        if t.image and t.image.exists():
            img = f'<img src="data:image/png;base64,{_embed(t.image)}" alt="{html.escape(PRETTY.get(t.platform, t.platform))} screen">'
        else:
            img = '<div class="noshot">no screenshot</div>'

        cards.append(
            f"""      <figure class="card {cls}">
        <figcaption>
          <span class="name">{html.escape(PRETTY.get(t.platform, t.platform))}</span>
          <span class="badge {cls}">{badge}</span>
        </figcaption>
        {img}
        <p class="detail">{html.escape(t.detail)}</p>
        <p class="detail">server {html.escape(_server_cell(t))} &middot; optional skips {html.escape(_skips_cell(t))}</p>
      </figure>"""
        )

    passed = sum(1 for t in tiles if t.passed is True)
    ran = sum(1 for t in tiles if t.passed is not None)
    head = f" &middot; server {html.escape(server)} ({html.escape(source)})" if server else ""
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Five-Platform Live QA</title>
<style>
  :root {{ color-scheme: light dark; --bg:#faf9f7; --fg:#1b1a18; --muted:#6b6862;
           --line:#e2ded7; --card:#fff; --pass:#2f7d4f; --fail:#b3372b; --absent:#8a8478; }}
  @media (prefers-color-scheme: dark) {{
    :root {{ --bg:#16171a; --fg:#e9e7e3; --muted:#9a958c; --line:#2c2e33; --card:#1e2024; }}
  }}
  * {{ box-sizing: border-box; }}
  body {{ margin:0; padding:2rem 1.25rem 3rem; background:var(--bg); color:var(--fg);
          font:16px/1.55 ui-sans-serif,-apple-system,"Segoe UI",Roboto,sans-serif; }}
  header {{ max-width:1400px; margin:0 auto 1.75rem; }}
  h1 {{ font-size:1.5rem; margin:0 0 .35rem; letter-spacing:-.01em; }}
  .sub {{ color:var(--muted); font-size:.9rem; margin:0; }}
  .grid {{ max-width:1400px; margin:0 auto; display:grid; gap:1.25rem;
           grid-template-columns:repeat(auto-fit,minmax(260px,1fr)); }}
  .card {{ margin:0; background:var(--card); border:1px solid var(--line);
           border-radius:10px; overflow:hidden; display:flex; flex-direction:column; }}
  .card.fail {{ border-color:var(--fail); }}
  figcaption {{ display:flex; align-items:center; justify-content:space-between;
                gap:.5rem; padding:.7rem .85rem; border-bottom:1px solid var(--line); }}
  .name {{ font-weight:600; }}
  .badge {{ font-size:.7rem; font-weight:700; letter-spacing:.06em;
            padding:.2rem .45rem; border-radius:4px; color:#fff; background:var(--absent); }}
  .badge.pass {{ background:var(--pass); }}
  .badge.fail {{ background:var(--fail); }}
  img {{ width:100%; height:auto; display:block; background:#000; }}
  .noshot {{ padding:3.5rem 1rem; text-align:center; color:var(--muted);
             font-size:.85rem; background:repeating-linear-gradient(45deg,
             transparent,transparent 8px,rgba(128,128,128,.07) 8px,rgba(128,128,128,.07) 16px); }}
  .detail {{ margin:0; padding:.6rem .85rem; font-size:.82rem; color:var(--muted); }}
</style>
</head>
<body>
<header>
  <h1>Five-Platform Live QA</h1>
  <p class="sub">{passed}/{len(tiles)} passed &middot; {ran}/{len(tiles)} ran{head} &middot;
     each tile is the entry screen after the smoke walk, then the CSD flows.{run_url}</p>
</header>
<div class="grid">
{chr(10).join(cards)}
</div>
</body>
</html>
"""


def _summary(tiles: List[Tile], server: str = "", source: str = "") -> str:
    lines = ["## Five-Platform Live QA", ""]
    if server:
        lines += [f"One server version for every leg: **{server}** ({source}).", ""]
    lines += [
        "| Platform | Result | Server | Optional skips | Screenshot |",
        "|---|---|---|---|---|",
    ]
    for t in tiles:
        result = "PASS" if t.passed is True else "FAIL" if t.passed is False else "did not run"
        shot = "yes" if (t.image and t.image.exists()) else "—"
        lines.append(f"| {PRETTY.get(t.platform, t.platform)} | {result} | {_server_cell(t)} | {_skips_cell(t)} | {shot} |")
    lines += [
        "",
        "_Optional skips are `SKIP (optional)` steps per flow, judged on each leg against "
        "`testing/flows/optional_skips_baseline.json` (CIRISClient#168). Download the "
        "**live-qa-gallery** artifact and open `gallery.html` to see all five side by side — "
        "GitHub cannot render artifact images inline._",
    ]
    return "\n".join(lines) + "\n"


def parser() -> argparse.ArgumentParser:
    """The CLI, as a function so the workflow test can parse the step's argv:
    run 36588619656's gallery job died on `unrecognized arguments: --shots`."""
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("root", type=Path, help="Directory the artifacts were downloaded into")
    ap.add_argument("--out", type=Path, required=True, help="Where to write index.html")
    ap.add_argument("--summary", type=Path, default=None, help="Append a table here (GITHUB_STEP_SUMMARY)")
    ap.add_argument("--server-version", default="", help="the run's one server version (the `server` job)")
    ap.add_argument("--server-source", default="", help="pin | input | latest-fallback")
    return ap


def main() -> int:
    args = parser().parse_args()

    if not args.root.exists():
        print(f"gallery: {args.root} does not exist", file=sys.stderr)
        return 1

    tiles = _tiles(args.root)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(_render(tiles, server=args.server_version, source=args.server_source), encoding="utf-8")

    table = _summary(tiles, args.server_version, args.server_source)
    print(table)
    if args.summary:
        try:
            with args.summary.open("a", encoding="utf-8") as fh:
                fh.write(table)
        except OSError as exc:
            print(f"gallery: could not append summary: {exc}", file=sys.stderr)

    ran = sum(1 for t in tiles if t.passed is not None)
    print(f"gallery: {args.out} ({ran}/{len(tiles)} platforms reported)")
    # The gallery REPORTS; the live-qa jobs decide red or green. Failing here
    # would double-count a failure already counted, and would hide the gallery
    # behind its own error exactly when it is needed.
    return 0


if __name__ == "__main__":
    sys.exit(main())
