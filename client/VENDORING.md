# VENDORING — `client/`

This tree is **vendored**, not authored here. It is the CIRIS Kotlin
Multiplatform client, copied from the repository that has been its source of
truth to date.

Until this repo's first release, CIRISServer and CIRISAgent still carry their
own copies. The point of the extraction is that they stop: see
[`../README.md`](../README.md) § *The consumption contract*.

---

## 1. Provenance

| | |
|---|---|
| **Source repo** | [`CIRISAI/CIRISAgent`](https://github.com/CIRISAI/CIRISAgent) |
| **Source path** | `client/` |
| **Commit** | `6083bdff497d774540fd749c647567ec8984e66b` |
| **Commit subject** | `2.9.28 — say which model, which provider, and every language equal` |
| **Commit date** | 2026-08-20 |
| **Branch** | `main` |
| **Vendored on** | 2026-08-20 |
| **Files vendored** | 1761 |
| **`CLIENT_VERSION` at vendor time** | `0.5.181` |

### The vendoring digest

Every vendored file is **byte-identical** to its upstream original. That is a
checkable claim, not a promise:

```
find client -type f -not -name VENDORING.md -print0 \
  | sort -z | xargs -0 sha256sum | sha256sum
→ f7fc7eba43ef57a5a16d87af3799a95f4692f975d85ec8cc17a91c9790b55dbe
```

(This file is the one thing under `client/` that is not vendored, so it excludes
itself.)

Run from the repo root, so the hashed paths carry the `client/` prefix the
upstream tree uses too — the same command over an upstream checkout, with §2's
exclusions applied, produces the same digest. It is recorded here so that "did anything drift?" is
one command, and so the answer survives the memory of whoever asks.

**The digest is for the vendoring commit only.** Local deltas (§3) change it by
design — that is why they are enumerated. Re-verify against §2's excluded-path
list, not against a later HEAD.

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
- `client/iosApp/substrate.lock.json` **is** vendored — it is the pin, and it is
  small. It records which `ciris-server` the excluded binaries were built from.

Nothing here can be rebuilt from this repo alone, which is the honest statement
of the boundary: **this repo owns the client, not the substrate it drives.**

---

## 3. Local deltas

Changes made *after* the vendoring commit, each one a drift-to-configuration
migration or a fix for something that only breaks once the tree is extracted.
This table is the complete list. Adding to the tree without adding a row here
is how the digest in §1 stops meaning anything.

| File | Delta | Why |
|---|---|---|
| `shared/build.gradle.kts` | + `generateBuildFlavor` task, generated srcDir | Makes `HAS_AGENT` / `CLIENT_VERSION` build inputs (§4) |
| `shared/src/commonMain/.../CIRISBuild.kt` | **deleted** | Regenerated per flavor into `build/generated/flavor/` |
| `shared/src/commonMain/.../models/ClientMode.kt` | `const val CLIENT_VERSION` removed | Regenerated from the `VERSION` file |
| `desktopApp/build.gradle.kts` | `syncLocalizationResources` removed | Destructive after extraction — see §5 |
| `androidApp/build.gradle` | `syncLocalizationAssets` removed | Destructive after extraction — see §5 |
| `local.properties` | **deleted**, now git-ignored | Contained one developer's absolute `sdk.dir`; breaks every other machine |
| `tools/check_localization_sync.py` | **added** (adapted) | Was `tools/dev/` in CIRISAgent, outside `client/` — see §6 |

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
**deletes the 30 committed locale files in its destination**. The Android one's
destination is `src/main/assets/localization`: the primary bundle, the one
`manifest.json` and every parity check read.

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

## 8. Re-vendoring

Until CIRISServer and CIRISAgent switch to consuming the wheel, upstream will
keep moving. To pull a newer upstream state:

```bash
git clone --depth=1 https://github.com/CIRISAI/CIRISAgent /tmp/agentsrc
cd /tmp/agentsrc && git rev-parse HEAD          # → the new SHA for §1

cd <CIRISClient>
git checkout -b chore/re-vendor-<short-sha>
rm -rf client
git -C /tmp/agentsrc archive --format=tar HEAD client | tar x

# §2 — the exclusion set
rm -rf client/iosApp/Resources client/iosApp/Frameworks \
       client/iosApp/app_packages_native client/iosApp/Resources.zip \
       client/androidApp/wheels client/androidApp/src/main/jniLibs \
       client/androidApp/src/main/assets/bin

git add -A -f client
find client -type f -not -name VENDORING.md -print0 \
  | sort -z | xargs -0 sha256sum | sha256sum      # → the new §1 digest
```

Then **re-apply §3 by hand and update this file in the same commit.** The
deltas are few and small precisely so that this step stays possible; if the
table ever grows past what a person will re-apply, that is the signal to push
the change upstream instead of carrying it.

Direction of travel: this is temporary. Once consumers depend on the published
wheel, `client/` here becomes the source of truth and the copies in
CIRISServer and CIRISAgent are deleted — at which point §1 records a
provenance, not a sync obligation, and this section is what gets deleted first.
