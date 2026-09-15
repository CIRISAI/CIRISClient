"""Turn an atlas capture into a browsable page for the UX team.

The atlas is a flat directory of PNGs; what a designer needs is the SHAPE — which
surface sits under which group, what a person passes through to reach it, and
what is missing. `nav_map` already derives that hierarchy, and `screen_atlas`
records each screen's chain alongside its picture, so the tree here is rebuilt
from the capture rather than hand-maintained. Rename a nav group in Kotlin and
this page follows on the next run; nothing to update by hand.

Screens the tree cannot reach (login, wizards, in-flow surfaces) are listed
rather than dropped: "we did not photograph this" is information, and a blank
where a screen should be is the kind of gap a redesign needs to see.
"""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

#: Turn `nav_group_commons-layers` / `nav_epistemic_agent_settings` into words a
#: designer reads, not tags an engineer greps.
def label(tag: str) -> str:
    for prefix in ("nav_group_", "nav_epistemic_"):
        if tag.startswith(prefix):
            tag = tag[len(prefix):]
    return tag.replace("-", " ").replace("_", " ").title()


def tree(screens: list[dict]) -> dict:
    """group -> parent (or '') -> [screen records], in nav order."""
    out: dict[str, dict[str, list[dict]]] = {}
    for rec in screens:
        chain = rec.get("chain") or []
        group = label(chain[0]) if chain else "Unrouted"
        parent = label(chain[1]) if len(chain) > 2 else ""
        out.setdefault(group, {}).setdefault(parent, []).append(rec)
    return out


PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>CIRIS Screen Atlas</title>
<style>
:root{
  --bg:#f6f7f9; --panel:#fff; --ink:#15181d; --muted:#666e7a; --line:#e3e6ea;
  --accent:#2f6df6; --miss:#c2410c; --missbg:#fff4ed;
  color-scheme:light;
}
:root[data-theme="dark"], :root:not([data-theme="light"]){}
@media (prefers-color-scheme: dark){ :root:not([data-theme="light"]){
  --bg:#12141a; --panel:#181b22; --ink:#e8eaee; --muted:#98a0ad; --line:#272b34;
  --accent:#6f9bff; --miss:#ff9a6b; --missbg:#2a1a12; color-scheme:dark;
}}
:root[data-theme="dark"]{
  --bg:#12141a; --panel:#181b22; --ink:#e8eaee; --muted:#98a0ad; --line:#272b34;
  --accent:#6f9bff; --miss:#ff9a6b; --missbg:#2a1a12; color-scheme:dark;
}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);
  font:14px/1.5 ui-sans-serif,system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
header{padding:18px 20px;border-bottom:1px solid var(--line);background:var(--panel);
  display:flex;gap:16px;align-items:baseline;flex-wrap:wrap;position:sticky;top:0;z-index:5}
h1{font-size:16px;margin:0;letter-spacing:-.01em}
.meta{color:var(--muted);font-size:12.5px}
.wrap{display:grid;grid-template-columns:290px 1fr;gap:0;min-height:calc(100vh - 62px)}
@media (max-width:820px){.wrap{grid-template-columns:1fr}
  nav{border-right:0!important;border-bottom:1px solid var(--line)}}
nav{border-right:1px solid var(--line);background:var(--panel);padding:14px 10px;
  max-height:calc(100vh - 62px);overflow:auto;position:sticky;top:62px}
main{padding:20px;min-width:0}
input[type=search]{width:100%;padding:8px 10px;border:1px solid var(--line);border-radius:8px;
  background:var(--bg);color:var(--ink);margin-bottom:10px;font-size:13px}
details{margin:2px 0}
summary{cursor:pointer;padding:5px 8px;border-radius:7px;font-weight:600;font-size:13px;
  list-style:none;display:flex;justify-content:space-between;gap:8px}
summary::-webkit-details-marker{display:none}
summary:hover{background:var(--bg)}
summary .n{color:var(--muted);font-weight:400;font-size:12px}
.sub{font-weight:600;color:var(--muted);font-size:11.5px;text-transform:uppercase;
  letter-spacing:.05em;padding:8px 8px 3px}
a.item{display:block;padding:5px 8px 5px 14px;border-radius:7px;color:var(--ink);
  text-decoration:none;font-size:13px}
a.item:hover{background:var(--bg)}
a.item.on{background:var(--accent);color:#fff}
a.item.missing{color:var(--miss)}
a.item.on.missing{color:#fff}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(250px,1fr));gap:16px}
.card{background:var(--panel);border:1px solid var(--line);border-radius:12px;overflow:hidden;
  cursor:pointer;display:flex;flex-direction:column}
.card:hover{border-color:var(--accent)}
.card img{width:100%;display:block;aspect-ratio:16/10;object-fit:cover;object-position:top center;
  background:var(--bg)}
.card .cap{padding:9px 11px;border-top:1px solid var(--line)}
.card .cap b{font-size:13px;display:block}
.card .cap span{color:var(--muted);font-size:11.5px}
.gap{aspect-ratio:16/10;display:flex;align-items:center;justify-content:center;
  background:var(--missbg);color:var(--miss);font-size:12px;text-align:center;padding:12px}
.detail{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:18px}
.detail img{max-width:100%;border:1px solid var(--line);border-radius:8px;display:block;margin:12px 0}
.chain{display:flex;gap:6px;flex-wrap:wrap;margin:10px 0}
.hop{background:var(--bg);border:1px solid var(--line);border-radius:999px;
  padding:3px 10px;font-size:12px;color:var(--muted)}
.hop.last{color:var(--ink);border-color:var(--accent)}
button.back{background:none;border:1px solid var(--line);border-radius:8px;padding:6px 12px;
  cursor:pointer;color:var(--ink);font-size:13px;margin-bottom:14px}
code{background:var(--bg);padding:1px 6px;border-radius:5px;font-size:12px}
.note{color:var(--muted);font-size:12.5px;margin:14px 0 0}
.tabs{display:flex;gap:4px;margin-bottom:16px;flex-wrap:wrap}
.tab{padding:7px 14px;border:1px solid var(--line);background:var(--panel);
  border-radius:999px;cursor:pointer;font-size:13px;color:var(--ink)}
.tab.on{background:var(--accent);color:#fff;border-color:var(--accent)}
.ia{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:6px 4px}
.ia .grp{padding:12px 14px 6px;font-weight:700;font-size:13px;letter-spacing:.04em;
  text-transform:uppercase;color:var(--accent)}
.ia .row{display:flex;align-items:center;gap:8px;padding:5px 14px;font-size:13.5px;
  border-left:2px solid transparent;cursor:pointer}
.ia .row:hover{background:var(--bg)}
.ia .row.kid{padding-left:40px;border-left-color:var(--line)}
.ia .row.kid::before{content:"└";color:var(--muted);margin-left:-14px;margin-right:4px}
.ia .row .nm{font-weight:500}
.ia .row.noshot .nm{color:var(--miss)}
.tag{font-size:10.5px;padding:2px 7px;border-radius:999px;border:1px solid var(--line);
  color:var(--muted);white-space:nowrap}
.tag.cross{border-color:var(--accent);color:var(--accent)}
.tag.orphan{border-color:var(--miss);color:var(--miss)}
.why{display:grid;gap:14px}
.doc{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:16px 18px}
.doc h3{margin:0 0 4px;font-size:14.5px}
.doc .where{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11.5px;
  color:var(--muted);margin-bottom:9px;word-break:break-all}
.doc p{margin:7px 0;font-size:13.5px;line-height:1.55}
.doc .mu{color:var(--muted)}
.figs{display:flex;gap:10px;flex-wrap:wrap;margin:10px 0 2px}
.fig{background:var(--bg);border:1px solid var(--line);border-radius:9px;padding:9px 13px}
.fig b{display:block;font-size:19px;line-height:1.2}
.fig span{font-size:11.5px;color:var(--muted)}
</style></head><body>
<header>
  <h1>CIRIS Screen Atlas</h1>
  <span class="meta" id="meta">loading…</span>
</header>
<div class="wrap">
  <nav><input type="search" id="q" placeholder="Filter screens…"><div id="tree"></div></nav>
  <main>
    <div class="tabs">
      <div class="tab on" data-view="screens">Screens</div>
      <div class="tab" data-view="ia">How the routes relate</div>
      <div class="tab" data-view="why">Why it is shaped this way</div>
    </div>
    <div id="main"></div>
  </main>
</div>
<script>
const LBL = t => t.replace(/^nav_(group|epistemic)_/,'').replace(/[-_]/g,' ')
  .replace(/\\b\\w/g, c => c.toUpperCase());
let DATA = null, CURRENT = null;

fetch('atlas.json').then(r => r.json()).then(d => { DATA = d; boot(); })
  .catch(e => { document.getElementById('main').textContent = 'Could not load atlas.json: ' + e; });

function boot(){
  const ok = DATA.screens.filter(s => s.ok).length;
  const routable = DATA.routable || DATA.screens.length;
  document.getElementById('meta').textContent =
    `${ok}/${routable} screens · mode ${DATA.mode} · ${DATA.jar || ''}`;
  document.querySelectorAll('.tab').forEach(t => t.onclick = () => {
    document.querySelectorAll('.tab').forEach(x => x.classList.toggle('on', x === t));
    const v = t.dataset.view;
    if (v === 'ia') renderIA(); else if (v === 'why') renderWhy();
    else renderGrid(DATA.screens, 'All screens');
  });
  renderTree(); renderGrid(DATA.screens, 'All screens');
  document.getElementById('q').addEventListener('input', e => {
    const term = e.target.value.toLowerCase();
    renderTree(term);
    if (term) renderGrid(DATA.screens.filter(s => s.screen.toLowerCase().includes(term)),
                         `Matching “${term}”`);
  });
}

function groups(){
  const g = {};
  for (const s of DATA.screens){
    const c = s.chain || [];
    const grp = c.length ? LBL(c[0]) : 'Unrouted';
    const par = c.length > 2 ? LBL(c[1]) : '';
    ((g[grp] = g[grp] || {})[par] = g[grp][par] || []).push(s);
  }
  return g;
}

function renderTree(filter){
  const host = document.getElementById('tree'); host.innerHTML = '';
  const g = groups();
  for (const [grp, parents] of Object.entries(g).sort()){
    const all = Object.values(parents).flat()
      .filter(s => !filter || s.screen.toLowerCase().includes(filter));
    if (!all.length) continue;
    const d = document.createElement('details'); d.open = !!filter || true;
    d.innerHTML = `<summary>${grp}<span class="n">${all.length}</span></summary>`;
    for (const [par, list] of Object.entries(parents).sort()){
      const shown = list.filter(s => !filter || s.screen.toLowerCase().includes(filter));
      if (!shown.length) continue;
      if (par){ const h = document.createElement('div'); h.className='sub'; h.textContent=par; d.appendChild(h); }
      for (const s of shown.sort((a,b)=>a.screen.localeCompare(b.screen))){
        const a = document.createElement('a');
        a.className = 'item' + (s.ok ? '' : ' missing') + (CURRENT===s.screen ? ' on' : '');
        a.textContent = s.screen; a.href = '#' + s.screen;
        a.onclick = ev => { ev.preventDefault(); show(s); };
        d.appendChild(a);
      }
    }
    host.appendChild(d);
  }
}

function renderGrid(list, title){
  CURRENT = null;
  const m = document.getElementById('main');
  m.innerHTML = `<h2 style="font-size:15px;margin:0 0 14px">${title} <span class="meta">(${list.length})</span></h2>`;
  const grid = document.createElement('div'); grid.className = 'grid';
  for (const s of list.sort((a,b)=>a.screen.localeCompare(b.screen))){
    const card = document.createElement('div'); card.className = 'card';
    card.onclick = () => show(s);
    card.innerHTML = (s.ok
        ? `<img loading="lazy" src="shots/${s.shot}" alt="${s.screen}">`
        : `<div class="gap">${s.flow_only ? 'no nav route' : 'not captured'}<br><small>${(s.detail||'').slice(0,70)}</small></div>`)
      + `<div class="cap"><b>${s.screen}</b><span>${(s.chain||[]).map(LBL).join(' › ')}</span></div>`;
    grid.appendChild(card);
  }
  m.appendChild(grid);
  const prov = document.createElement('p'); prov.className = 'note';
  prov.innerHTML = `<b>How these were captured.</b> Desktop build <code>${DATA.jar||'?'}</code>
    driven through its test-automation server against a live CIRIS agent running the
    <b>mock LLM</b>, in <code>${DATA.mode}</code> mode — the mode that shows the Agent,
    Node, Safety and Manage groups. Captured ${DATA.captured_at||'—'}.
    Screens are photographed only when the app confirms it actually navigated, so a
    gap below is a real gap, not a mislabelled duplicate.`;
  m.appendChild(prov);
  if (DATA.flow_only && DATA.flow_only.length){
    const n = document.createElement('p'); n.className = 'note';
    n.innerHTML = '<b>Not in the nav tree</b> (reached only inside a flow, so not captured here): '
      + DATA.flow_only.map(x => `<code>${x}</code>`).join(' ');
    m.appendChild(n);
  }
  renderTree(document.getElementById('q').value.toLowerCase());
}

const shotFor = name => (DATA.screens.find(x => x.screen === name) || {});

function selectTab(v){
  document.querySelectorAll('.tab').forEach(x => x.classList.toggle('on', x.dataset.view === v));
}

function addRow(box, name, S, kid){
  const r = S[name] || {}; const shot = shotFor(name);
  const row = document.createElement('div');
  row.className = 'row' + (kid ? ' kid' : '') + (shot.ok ? '' : ' noshot');
  let tags = '';
  if (r.cross_framed) tags += `<span class="tag cross">filed under ${r.group}, child of ${r.parent}</span>`;
  else if (kid && r.parent) tags += `<span class="tag">child of ${r.parent}</span>`;
  if (!r.group && !r.parent) tags += '<span class="tag orphan">no route</span>';
  if (r.children && r.children.length) tags += `<span class="tag">${r.children.length} child${r.children.length>1?'ren':''}</span>`;
  row.innerHTML = `<span class="nm">${name}</span>${tags}`;
  if (shot.ok) row.onclick = () => { selectTab('screens'); show(shot); };
  box.appendChild(row);
}

function renderIA(){
  const st = DATA.structure; const m = document.getElementById('main');
  if (!st){ m.innerHTML = '<p class="note">No structure in this manifest.</p>'; return; }
  const S = st.surfaces;
  m.innerHTML = `<h2 style="font-size:15px;margin:0 0 6px">How the routes relate</h2>
    <p class="note" style="margin:0 0 14px">Two axes, not one. A surface has a
    <b>group</b> — the rail section it is filed under — and a <b>parent</b>, the chevron
    that reveals it. They are independent, and where they disagree the surface is marked
    <span class="tag cross">cross-framed</span>.</p>`;
  const box = document.createElement('div'); box.className = 'ia';
  const byGroup = {};
  for (const [name, r] of Object.entries(S)) if (r.group) (byGroup[r.group] = byGroup[r.group] || []).push(name);
  for (const g of st.groups){
    const names = (byGroup[g] || []).sort();
    if (!names.length) continue;
    const h = document.createElement('div'); h.className = 'grp';
    h.textContent = `${g.replace(/-/g,' ')} · ${names.length}`; box.appendChild(h);
    const roots = names.filter(n => !S[n].parent || !names.includes(S[n].parent));
    for (const n of roots){
      addRow(box, n, S, false);
      for (const kid of (S[n].children || [])) addRow(box, kid, S, true);
    }
  }
  const orph = document.createElement('div'); orph.className = 'grp';
  orph.textContent = `no group · ${st.ungrouped.length}`; box.appendChild(orph);
  for (const n of st.ungrouped) addRow(box, n, S, !!S[n].parent);
  m.appendChild(box);
  const p = document.createElement('p'); p.className = 'note';
  p.innerHTML = 'A row in grey has no screenshot: a surface the atlas could not photograph, or one with no route at all.';
  m.appendChild(p);
}

function renderWhy(){
  const m = document.getElementById('main'); const ns = DATA.namespaces || {};
  m.innerHTML = `<h2 style="font-size:15px;margin:0 0 6px">Why it is shaped this way</h2>
    <p class="note" style="margin:0 0 6px">The arrangement is the visible end of a
    specification chain. These are the documents that decided it, in the order they decided it.</p>`;
  if (ns.families){
    const f = document.createElement('div'); f.className = 'figs';
    f.innerHTML = `<div class="fig"><b>${ns.families}</b><span>namespace families</span></div>
      <div class="fig"><b>${ns.components}</b><span>owning components (${ns.components_normative} normative)</span></div>
      <div class="fig"><b>${ns.cc_version}</b><span>Constitution version</span></div>`;
    m.appendChild(f);
    const note = document.createElement('p'); note.className = 'note';
    note.innerHTML = `Read from <code>manifests/namespace_registry.json</code>, generated from `
      + `<code>${ns.source || ''}</code> — the Constitution says to take the count from there, `
      + `never from a number restated in prose.`;
    m.appendChild(note);
  }
  const wrap = document.createElement('div'); wrap.className = 'why'; wrap.style.marginTop = '16px';
  for (const d of (DATA.lineage || [])){
    const el = document.createElement('div'); el.className = 'doc';
    el.innerHTML = `<h3>${d.title}</h3><div class="where">${d.where}</div>
      <p>${d.says}</p><p class="mu"><b>For the UI:</b> ${d.means_for_ui}</p>`;
    wrap.appendChild(el);
  }
  m.appendChild(wrap);
  if (DATA.observations && DATA.observations.length){
    const h = document.createElement('h2');
    h.style.cssText = 'font-size:15px;margin:22px 0 6px'; h.textContent = 'What the atlas found';
    m.appendChild(h);
    const w2 = document.createElement('div'); w2.className = 'why';
    for (const pair of DATA.observations){
      const el = document.createElement('div'); el.className = 'doc';
      el.innerHTML = `<h3>${pair[0]}</h3><p>${pair[1]}</p>`;
      w2.appendChild(el);
    }
    m.appendChild(w2);
  }
}

function show(s){
  CURRENT = s.screen;
  const m = document.getElementById('main');
  m.innerHTML = '';
  const back = document.createElement('button');
  back.className = 'back'; back.textContent = '← All screens';
  back.onclick = () => renderGrid(DATA.screens, 'All screens');
  m.appendChild(back);
  const d = document.createElement('div'); d.className = 'detail';
  const chain = (s.chain||[]).map((h,i,a) =>
    `<span class="hop${i===a.length-1?' last':''}">${LBL(h)}</span>`).join('');
  d.innerHTML = `<h2 style="margin:0;font-size:17px">${s.screen}</h2>
    <div class="chain">${chain || '<span class="hop">no nav route</span>'}</div>`
    + (s.ok ? `<img src="shots/${s.shot}" alt="${s.screen}">`
            : `<div class="gap" style="border-radius:8px;margin:12px 0">not captured — ${s.detail||''}</div>`)
    + (s.resolved && s.ok && s.resolved.toLowerCase() !== s.screen.toLowerCase()
        ? `<p class="note" style="color:var(--miss)"><b>Mismatch:</b> the app reported
           <code>${s.resolved}</code> here, not <code>${s.screen}</code>.</p>` : '')
    + `<p class="meta">${s.ok ? `${s.tags} test tags on screen` : ''}
       ${s.detail && s.ok ? ' · resolved as <code>'+s.detail+'</code>' : ''}</p>`;
  m.appendChild(d);
  renderTree(document.getElementById('q').value.toLowerCase());
}
</script></body></html>
"""


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--atlas", required=True, type=Path, help="screen_atlas output dir")
    ap.add_argument("--out", required=True, type=Path, help="site directory to write")
    args = ap.parse_args()

    manifest = json.loads((args.atlas / "atlas.json").read_text())
    # The SHAPE and the REASONS ride along with the pictures, so the page can
    # explain an arrangement instead of merely listing it.
    from testing.gate import atlas_context, nav_map
    manifest["structure"] = nav_map.structure()
    manifest["namespaces"] = atlas_context.namespaces()
    manifest["lineage"] = atlas_context.LINEAGE
    manifest["observations"] = atlas_context.OBSERVATIONS
    args.out.mkdir(parents=True, exist_ok=True)
    shots_out = args.out / "shots"
    if shots_out.exists():
        shutil.rmtree(shots_out)
    shutil.copytree(args.atlas / "shots", shots_out)
    (args.out / "atlas.json").write_text(json.dumps(manifest, indent=2))
    (args.out / "index.html").write_text(PAGE)

    kept = len(list(shots_out.glob("*.png")))
    print(f"site -> {args.out}  ({kept} shots, {manifest['captured']}/{manifest['total']} captured)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
