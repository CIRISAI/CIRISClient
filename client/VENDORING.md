# VENDORING — `client/`

This tree is the **tree of record** for the CIRIS Kotlin Multiplatform client.
It began as a vendored copy; since the three-way merge recorded below it is
authored here, and the copies in CIRISServer and CIRISAgent are the ones with
a retirement date: see [`../README.md`](../README.md) § *The consumption
contract* and [`../FSD/ONE_CLIENT_N_NODES.md`](../FSD/ONE_CLIENT_N_NODES.md)
§3.

---

## 1. Provenance

The tree is the result of a true three-way merge (FSD §3), not a copy of any
one upstream state:

| | |
|---|---|
| **Base** | [`CIRISAI/CIRISAgent`](https://github.com/CIRISAI/CIRISAgent) `client/` @ `6083bdff497d774540fd749c647567ec8984e66b` (2.9.28, main, 2026-08-20) — as vendored byte-identically in commit `dc17f56`, digest `f7fc7eba43ef57a5a16d87af3799a95f4692f975d85ec8cc17a91c9790b55dbe` |
| **Ours** | the eight extraction deltas of PR #1 (flavor generation, `VERSION`-derived `CLIENT_VERSION`, destructive-`Sync` removal, vendored localization checker — §4) |
| **Theirs** | `CIRISAI/CIRISServer` `client/` @ `a2433ba46c5d6f1f6e48cb3ddfd287e1f7c3f082` (0.5.185) with §2's exclusions applied, extended: `.ciris_keys/`, `__pycache__/`, `*.pyc`, `local.properties` |
| **Merged on** | 2026-08-20 |

For the artifact manifest (`packaging/stage_artifacts.py`), the newest content
source is the pair a bisect wants:

| | |
|---|---|
| **Source repo** | [`CIRISAI/CIRISServer`](https://github.com/CIRISAI/CIRISServer) |
| **Commit** | `a2433ba46c5d6f1f6e48cb3ddfd287e1f7c3f082` |

### The state digest

The tree's current recorded state — sha256-of-sha256s over every git-tracked
file under `client/` except this one:

**state digest:** `a78fe4fae57c38b73c0865607b81a00fbecda8589cf4f5a884158b844fdb0ec6`

`packaging/check_vendoring.py` asserts it on every push, and refuses any
tracked file matching a §2 never-vendor class. **Any commit that touches
`client/` re-records this line in the same commit**
(`python3 packaging/check_vendoring.py --print`) — one mechanical line, and it
is what forces this file's diff, and therefore the provenance question, into
every review that moves the tree. Git history is the changelog; this digest is
the seal.

---

## 2. What was deliberately NOT vendored

2537 files / ~235 MB of the upstream `client/` tree are **prebuilt binaries
produced by other repositories' releases**, staged into the client tree so an
Xcode or Gradle build could find them without a network fetch.

| Excluded path | Files | Size | What it actually is |
|---|---:|---:|---|
| `client/androidApp/wheels/` | 6 | 86.1 MB | `ciris_server` abi3 wheels (0.5.176), one per Android ABI |
| `client/iosApp/app_packages_native/` | 9 | 61.4 MB | `ciris_server` `_native.abi3.so` + friends, iOS |
| `client/iosApp/Resources/` | 2512 | 39.1 MB | `ciris_engine` + `ciris_adapters` Python tree, rsynced in by the Xcode build phase |
| `client/iosApp/Resources.zip` | 1 | 27.2 MB | the zip of the above, rebuilt on every Xcode build |
| `client/androidApp/src/main/jniLibs/` | 4 | 28.1 MB | `libciris_verify_ffi.so`, `libllama_server.so` |
| `client/iosApp/Frameworks/` | 5 | 9.2 MB | `CIRISVerify.xcframework` |
| `client/androidApp/src/main/assets/bin/` | 1 | 11.2 MB | `llama-server-arm64` |

**Why they are out.** This repo exists so that one client is built once and
consumed as a dependency, instead of each consumer keeping its own copy. A
vendored copy of *CIRISVerify's* and *CIRISServer's* release binaries inside
the client is the same defect one level down — and the copy here would be a
third one, drifting against the two that already exist. `androidApp/wheels/`
was already a release behind the tree that carried it (`ciris_server` 0.5.176
against a `CLIENT_VERSION` of 0.5.181) at the moment of vendoring, which is the
argument stated as a fact.

Two of them (`iosApp/Resources/`, `iosApp/Frameworks/`) are listed in the
client's **own** `client/iosApp/.gitignore` and exist upstream only because
they were force-added.

**How to re-hydrate them** for a device build:

- **Android** — `pip download ciris-server==<VERSION> --platform android_24_arm64_v8a …`
  into `client/androidApp/wheels/`. The ABI list is in
  `client/androidApp/build.gradle`.
- **iOS** — CIRISAgent's `.github/workflows/refresh-ios-substrate.yml` produces
  the whole set (`app_packages_native/`, `Frameworks/`, `Resources/`,
  `Resources.zip`, `substrate.lock.json`) on a macOS runner and uploads it as
  the `ios-substrate-refresh` artifact.
- **Python runtime tree** (Android Chaquopy, iOS Resources) — staged from a
  CIRISAgent checkout by its `tools.dev.stage_runtime`; point Gradle at one
  with `-PcirisAgentRoot=/path/to/CIRISAgent`. The `syncPythonSources` task
  refuses with this remedy when none is present, instead of a
  `ModuleNotFoundError` three tasks deep in Chaquopy.
- `client/iosApp/substrate.lock.json` records which `ciris-server` the excluded
  iOS binaries were built from. The agent tree vendored it; the server tree does
  not, and the merge followed the server: it arrives with rehydration (the
  workflow above writes it) and is git-ignored here alongside what it pins.

Nothing here can be rebuilt from this repo alone, which is the honest statement
of the boundary: **this repo owns the client, not the substrate it drives.**

---

## 3. Local deltas — RETIRED at the merge

The delta table that stood here enumerated the eight changes PR #1 made on top
of the byte-identical vendor commit. It existed because the tree was a copy and
every departure from the copy needed a declaration. Since the three-way merge
(§1) the tree is authored here: git history is the declaration, the §1 state
digest is the seal, and a change worth coordinating with the consumers is a
change worth a cherry-pick or an upstream issue, not a table row.

The eight deltas themselves are folded into the merge and documented where they
live: the flavor generation in §4, the `Sync` removal in §5, the localization
checker in §6. What replaced the table's job:

- **Undeclared drift** — the §1 state digest, re-recorded by every commit that
  touches `client/` (per-file hashes, so no file can move silently).
- **Never-vendor classes** — `packaging/check_vendoring.py` refuses substrate
  binaries, key material, compiled Python, and `local.properties` outright (§2).
- **Upstream coordination** — pulls are merges (§8), so a change made here can
  no longer be silently reverted by a re-vendor. That quiet middle was the
  reason the table had to exist.

---

## 4. The drift-to-flavor migration

Three things differed between the CIRISServer copy and the CIRISAgent copy of
this tree. None of them were disagreements about the code; all three were the
same source configured two ways with no way to say so. Each is now a build
input.

| # | Drift | Was | Is now | Selected by |
|---|---|---|---|---|
| 1 | `CIRISBuild.HAS_AGENT` | `const val` hand-edited per repo — `false` in CIRISServer, `true` in CIRISAgent | generated into `CIRISBuild.kt` per flavor; still a `const val`, so dead-code elimination is unchanged | `-PhasAgent=true\|false` (default `false`) |
| 2 | `CLIENT_VERSION` | `const val` in `models/ClientMode.kt`, hand-edited, kept in step by a script in one repo and by nothing in the other | generated from the repo-root `VERSION` file, the same file the Python package version comes from | `VERSION` (override: `-PclientVersion=`) |
| 3 | localization bundles | 29 languages in 6 mirrored copies, kept identical by a checker that lived outside `client/` | 4 in-tree mirrors, checker vendored to `client/tools/` and run in CI | — (see §5, §6) |
| 4 | `generated-api/` | 1107 committed files, generator not in the build graph | unchanged, provenance documented | — (see §7) |

`-PhasAgent` is the spelling `MISSION.md` §5.2 already names. It is read in
`shared/build.gradle.kts` and defaults from `gradle.properties`
(`ciris.hasAgent=false`), because the node client is the base product and the
agent build is the superset.

**Why generate rather than patch.** `CLIENT_VERSION`'s own KDoc upstream warns
that mutating it at build time "recompiled the whole Compose client and
defeated the desktop-JAR gradle cache every leg (CIRISServer#272)". Generation
does not reintroduce that: the generated file's content is a pure function of
(flavor, version), so it is byte-stable across builds of the same flavor and
the cache holds. It is invalidated exactly when the version changes — which is
when a rebuild is correct. What #272 actually cost was a value that differed
*per CI leg*; a value that differs *per flavor* is what separate flavor build
directories are for.

---

## 5. The localization mirrors, and a landmine the extraction disarms

Upstream, `tools/dev/check_localization_sync.py` guards **six** `en.json`
mirrors. Two of them are not in this repo:

| Mirror | Here? | |
|---|---|---|
| `client/androidApp/src/main/assets/localization` | ✅ | primary bundle; carries `manifest.json` |
| `client/desktopApp/src/main/resources/localization` | ✅ | |
| `client/iosApp/iosApp/localization` | ✅ | |
| `client/shared/src/desktopMain/resources/localization` | ✅ | |
| `client/iosApp/Resources/app/localization` | ❌ | inside the excluded iOS substrate (§2); regenerated by the Xcode build phase |
| `ciris_engine/data/localized` | ❌ | CIRISAgent's **server-side prompt** bundle — never was in `client/` |

The four that remain are exactly the four `readiness`'s `locale-parity` gate
already checks, so the extracted tree's mirror set and the gate's list now
agree. The two that left become **cross-repo obligations**, recorded as rows in
[`../evidence/blocked_upstream.tsv`](../evidence/blocked_upstream.tsv) rather
than as an assumption that someone remembers.

### The landmine

`androidApp/build.gradle` and `desktopApp/build.gradle.kts` each registered a
Gradle **`Sync`** task copying `../../localization/*.json` into a committed
bundle directory, wired to `preBuild` / `processResources`.

At the vendored commit, upstream `localization/` contains **zero** `*.json`
files (4 tracked files: one `CLAUDE.md`, three `.txt`). A `Sync` with an empty
source does not copy nothing — it makes the destination match the source, so it
**deletes all 30 committed files in its destination**: the 29 locales *and*
`manifest.json`. The Android one's destination is `src/main/assets/localization`
— the primary bundle, and `manifest.json` is the supported-language list that
`CLAUDE.md` names as the source of truth and that every parity check reads.

The `exclude "manifest.json"` on the task's *source* filter does not protect the
destination; it only means the file would not be copied back in. A `Sync`'s
delete pass considers everything in the destination, so the one file the task
takes visible care of is a file it removes.

Upstream this is latent (the path resolves to a real, if empty, directory).
Extracted, `../../localization` resolves outside this repo entirely. Either
way the answer is the same: **the committed bundles are the source of truth
here.** Both tasks are removed. The file immediately below the Android one
carries a comment explaining this exact Gradle hazard for a *different* task —
the lesson was learned once and not applied to its neighbours.

This is reported upstream, not just fixed here: the same two tasks are live in
CIRISAgent and in CIRISServer's copy.

---

## 6. `check_localization_sync.py`

Vendored from CIRISAgent `tools/dev/check_localization_sync.py` @ the same
commit, to `client/tools/check_localization_sync.py`.

`REPO_ROOT = Path(__file__).resolve().parents[2]` resolves to the repo root
from either location, so the mirror paths keep their `client/` prefix and the
file needed **one** change: `UI_MIRRORS` drops the two out-of-tree entries
(§5). Its three checks, two severities and every message are unchanged.

```bash
python3 client/tools/check_localization_sync.py            # errors block
python3 client/tools/check_localization_sync.py --strict   # warnings block too
```

Run from the repo root. CI runs the `--strict` form.

---

## 7. `generated-api/`

1107 committed files under `client/generated-api/`, produced by
[openapi-generator](https://openapi-generator.tech) from
`client/openapi.json` using `client/openapi-generator-config.yaml`:

```yaml
generatorName: kotlin
library: multiplatform
inputSpec: ./openapi.json
packageName: ai.ciris.api
```

Regeneration, from `client/`:

```bash
npx @openapitools/openapi-generator-cli generate -c openapi-generator-config.yaml
```

**Nothing runs this.** The generator is not a Gradle task, so drift between
`openapi.json` and the 1107 files is silent, and `openapi.json` is itself a
*committed copy* of a spec the node does not serve. Both facts are already
rows in `../evidence/blocked_upstream.tsv` (CIRISConformance#86) and are why
`readiness`'s `generated-api-drift` gate reports `unimplemented` rather than
`pass`. The extraction does not change this; it does put the generator config
and the generated output in the same repo as the build that consumes them,
which is where a generator task would have to live.

---

## 8. Pulling from upstream — merges, not wipes

Until CIRISServer and CIRISAgent consume the wheel, their trees keep moving.
The wipe-and-reapply procedure that stood here is retired: it is exactly how a
re-vendor silently reverts local work. A pull is now a three-way merge, the
same shape as §1:

```bash
# in a CIRISClient checkout, on a branch
git checkout -b merge/<upstream>-<short-sha> <the commit recorded in §1 as that upstream's last merged state>
rm -rf client && git -C <upstream-checkout> archive <sha> client | tar x
# apply §2's exclusion set (see packaging/check_vendoring.py FORBIDDEN)
rm -rf client/iosApp/Resources client/iosApp/Frameworks \
       client/iosApp/app_packages_native client/iosApp/Resources.zip \
       client/androidApp/wheels client/androidApp/src/main/jniLibs \
       client/androidApp/src/main/assets/bin
find client \( -name .ciris_keys -o -name __pycache__ \) -type d -prune -exec rm -rf {} +
find client -name '*.pyc' -delete; rm -f client/local.properties
git checkout <current-branch> -- client/VENDORING.md   # not upstream's to write
git add -A client && git commit
git checkout <working-branch> && git merge merge/<upstream>-<short-sha>
# resolve, re-record the §1 state digest, update §1's merge inputs — same commit
```

Conflicts are the point: they are the places where this tree and upstream both
moved, surfaced for a decision instead of settled by whichever copy ran last.

Direction of travel: once the consumers depend on the published wheel, their
copies are deleted, pulls stop, and §1 records a history rather than a sync
obligation.
