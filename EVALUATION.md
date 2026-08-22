# EVALUATION — for the CIRISServer team

You are being asked one question:

> **Should CIRISServer stop carrying `client/` and depend on `ciris-client` instead?**

This document is the runnable case for "yes" and the honest list of what you
would be accepting. It is written to be *falsified*: every claim below is a
command you can run, and if one of them fails, that is the answer.

Tracking issue: [CIRISServer#471](https://github.com/CIRISAI/CIRISServer/issues/471).
The agent half is [CIRISAgent#1089](https://github.com/CIRISAI/CIRISAgent/issues/1089).
Rejecting is a coherent outcome — see §7.

---

## 1. What this tree is

It is **your client tree, plus theirs, merged** — not a fork and not a copy of
either. Provenance is recorded per-upstream in
[`client/VENDORING.md`](client/VENDORING.md) §1:

| Upstream | Last merged state |
|---|---|
| `CIRISAI/CIRISServer` | `v0.5.186` (`1ea2c8b`) |
| `CIRISAI/CIRISAgent` | `v2.9.32-stable` (`2504960`) |

Everything you shipped through v0.5.186 is here: contacts, user chat,
`NodeRefusal`, the Codex sweeps, the iOS LogTail work, and all 22 numbered
`NODE VENDOR DRIFT` restorations. Everything the agent shipped through
v2.9.32 is here too: the LLM status surface with its `_observer` variants, the
#1062 BYOK setup fixes, the stale-click-closure fix.

The merges were real three-way merges, not "take theirs". Where both trees had
independently fixed the same thing — the click-closure bug is the clearest case,
which your Codex race sweep and their #1099 both landed — the code was already
identical and only the better *explanation* survived.

## 2. The fifteen-minute evaluation

It is on PyPI — this is a real `pip install`, not a CI artifact:

```bash
python3 -m venv /tmp/eval
/tmp/eval/bin/pip install ciris-client==0.5.186

/tmp/eval/bin/python - <<'PY'
import ciris_client as c
print(c.__version__)                             # 0.5.186
print(c.manifest()['vendored_from'])             # repo + commit this was built from
print(c.artifact_path('desktop-uber-jar'))       # a real path to a real jar
PY

java -jar "$(/tmp/eval/bin/python -c 'import ciris_client;print(ciris_client.artifact_path("desktop-uber-jar"))')"
```

That last line launches the client your users would get. **Point it at a bare
node** and check the four things that matter: the sidebar shows **no agent
surfaces** (no Interact, Tools, Memory or agent settings — they are present in
the binary and withheld because your node cannot serve them), **Contacts is
home**, the version banner reads **0.5.186**, and the three reserved mesh
surfaces (Video, Voting, Private Groups) render the SOON placeholder with their
tracking issue rather than not existing.

Then **point the same install at an agent** — the surfaces appear. That is the
whole design: one artifact, narrowed at runtime by what the node can actually
do, so a node upgraded with a brain needs no client reinstall. It is also the
one behaviour most worth trying to break, because it is what replaced the
compile-time flag your build used to flip.

## 3. Does it match what you shipped?

The claim "this is your tree plus theirs" is checkable directly:

```bash
# in a CIRISClient checkout. merge/server-v0.5.186 is your v0.5.186 client tree
# verbatim (with VENDORING.md §2's exclusions applied) — it is the exact input
# the merge consumed, pushed so you can diff against it rather than trust us.
git fetch origin merge/server-v0.5.186
git diff --stat origin/merge/server-v0.5.186..HEAD -- client/shared/src/commonMain
```

More useful, because it is the question that actually matters — **does anything
of yours go missing?** Pick the things you would most hate to lose:

```bash
# the drift markers, the refusal channel, chat, contacts
grep -rc "NODE VENDOR DRIFT" client/ | grep -v ':0' | wc -l
ls client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/api/NodeRefusal.kt
ls client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/ui/screens/{ChatScreen,ContactsScreen}.kt
```

And the localization corpus, which is the part with the most surface area to
lose quietly:

```bash
python3 client/tools/check_localization_sync.py --self-test        # the gate proves it can fail
python3 client/tools/check_localization_sync.py --no-server-src --strict
```

That last command is the guard *you wrote* (CIRISServer#366), adopted here
verbatim plus one flag. It currently passes over ~108,000 value comparisons
across 29 languages, and it has already earned its place in this repo twice: it
caught two placeholder defects riding in from agent v2.9.30 that are still live
upstream ([CIRISAgent#1096](https://github.com/CIRISAI/CIRISAgent/issues/1096)).

## 4. What you would stop doing

This is the actual return on the change:

| You stop | Because |
|---|---|
| Re-vendoring the agent's client tree by hand | Pulls are merges here, and you consume a wheel |
| Maintaining 22+ numbered `NODE VENDOR DRIFT` markers | There is one tree; a difference is a commit, not a marker to re-apply |
| `scripts/sync-client-version.sh` + its pre-commit hook + its CI `--check` | `CLIENT_VERSION` is generated from one `VERSION` file |
| Hand-flipping `CIRISBuild.HAS_AGENT` | `-PhasAgent`, selected per flavor at build time |
| Guarding four byte-identical locale bundles in your CI | Guarded here, on every PR, with the mutation self-test |
| "Re-vendor drift recovery: 19 restorations" commits | The condition that produced them is gone |

What you would gain that does not exist today: a **published compatibility
matrix** ([`compat/matrix.json`](compat/matrix.json)) that answers "which client
works with which node, and what does it do when they mismatch" as a validated
artifact rather than folklore; and a **localization pipeline** that translates
new keys into 29 languages in CI for cents
([`client/tools/localize.py`](client/tools/localize.py) — the six mesh strings
cost $0.14, the two upstream repairs cost $0.035).

## 5. What you would owe

Small, but real, and worth stating before you agree rather than after:

1. **Client changes land here.** A client fix in a CIRISServer PR would now be a
   PR to CIRISClient, cherry-picked or released, then consumed. That is a
   round-trip you do not pay today.
2. **Release coupling.** `ciris-client==X` pairs with `ciris-server==X`. Cutting
   0.5.187 means cutting a client 0.5.187 — currently one `VERSION` bump plus a
   compat-matrix row, both gated in CI.
3. **The substrate stays yours.** This repo deliberately does not vendor
   `androidApp/wheels/`, jniLibs, the iOS Resources tree or the xcframeworks
   (`VENDORING.md` §2). A device build re-hydrates them from *your* releases,
   and the Android Python runtime still stages from a CIRISAgent checkout via
   `-PcirisAgentRoot`. Nothing here removes that dependency; it names it.
4. **Two gates you do not run today** would gate your client changes:
   the compat matrix and the flavor-pin check. Both are seconds, both are
   `python3` and stdlib.

## 6. What is missing, stated plainly

Do not decide without these:

- **This is the FIRST published release.** `ciris-client 0.5.186` went to PyPI
  on 2026-08-22 — cut from the `v0.5.186` tag by
  `.github/workflows/publish.yml` via Trusted Publishing (OIDC, no tokens),
  gated on the same checks plus one more: a placeholder payload can never be
  published, because a wheel that installs and then refuses every lookup is
  worse than no wheel. Treat it as a first release accordingly: it has been
  installed from the public index and exercised, and it has never been used by
  a consumer in anger. That is what your feedback is for.
- **Desktop uber-jar only.** No Android AAR, no iOS framework in the wheels yet.
  Your APK and installer pipelines still build from source — which is why
  adoption can be *staged* (§7) rather than all-or-nothing.
- **The payload is Linux-built.** `compose.desktop.currentOs` means the staged
  jar carries one OS's desktop runtime; `artifact_path` now refuses on the wrong
  platform with the remedy rather than handing you a broken jar, but per-OS
  payload builds are not wired yet.
- **`generated-api` regeneration is still not in the build graph**, so spec
  drift stays silent — which is what
  [CIRISServer#470](https://github.com/CIRISAI/CIRISServer/issues/470) (serve and
  publish your OpenAPI per release) exists to unblock. That issue is worth doing
  whether or not you adopt this.
- **The readiness gate board has never run for real** here, because CIRISGrace
  is unpublished and absent from the dev machine. The gates are written; their
  framework is not installable.

## 7. The decision, and how to say no

**Staged adoption is available and is what we would recommend.** Take the
resolver + node payload for the *desktop* artifact only, keep building your APK
and installer from source, and delete `client/` only when the AAR ships. That
converts the big irreversible step into two small ones and lets the drift
machinery retire early — the drift markers are about the *shared source*, not
about which artifact you build.

**Rejecting is coherent.** If the answer is no, say so on
[CIRISServer#471](https://github.com/CIRISAI/CIRISServer/issues/471) and this
repo goes back to being what it started as: the readiness gates, plus a
localization guard and a compatibility matrix that anyone can run against a
vendored tree. Nothing here is load-bearing for you until you choose it. What
does *not* survive a "no" is the current arrangement — three trees, aligned by
hand, is the thing that is not working, and if this is not the fix, the drift
markers and the re-vendor recovery commits are the cost you keep paying.

The one thing we would ask either way: **serve your OpenAPI spec per release**
([#470](https://github.com/CIRISAI/CIRISServer/issues/470)). Everything
downstream of "the contract comes from the node" is blocked on it, and it is
independent of this decision.

---

*Facts current as of the merge of CIRISServer `v0.5.186` and CIRISAgent
`v2.9.32-stable`; run the commands, do not trust the prose.*
