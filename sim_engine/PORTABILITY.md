# Portability and benchmark findings — `ciris-sim-core`

**Measured 2026-08-22.** Every number below names the command that produced it. Nothing
is inherited from a spec or an earlier campaign; where a claim could not be verified it
is listed in §7 rather than softened.

**Source state.** CIRISClient `0285e43`; `sim_engine/crates/ciris-sim-core/src/` is
**clean** (E10 landed at `247f260`, so the shipping code is committed). SHA-256 prefixes
of the exact sources measured:

```
b7557625d556 data.rs   f2a6a1dfb362 dynamics.rs  fcb3f8964085 entropy.rs
294ed2a89e97 field.rs  fbd2ff02ca05 gaps.rs      067022886a20 lib.rs
0fb72e059216 linalg.rs 6e26c0d346c3 sectors.rs   46638a0d3ea7 structure.rs
d04d550df984 tables.rs da8eb4f0ebae twin_probe.rs
```

An earlier pass measured the pre-E10 crate at `d6d15e1`; where E10 moved a number
materially the old value is given for comparison and labelled. **§8 is the re-run that
covers E10's runtime linear algebra**, which the first pass did not reach.

**Host.** `rustc 1.95.0 (59807616e 2026-04-14)`, `x86_64-unknown-linux-gnu`,
`node v20.20.2`. No `wasmtime`/`wasmer` on this machine — wasm execution is via Node's
`node:wasi` and the bare `WebAssembly` API. See §7.

---

## 1. The deployment claim, verified

The crate claims it runs identically on `wasm32-unknown-unknown`, `wasm32-wasip1` and
native. **It does.** All three targets build, all three *execute*, and their results are
bit-identical — not close, identical, checked as raw IEEE-754 bit patterns.

Both wasm targets were already installed; the command if they are not is:

```
rustup target add wasm32-unknown-unknown wasm32-wasip1
```

### 1.1 Builds

```
cd sim_engine/crates/ciris-sim-core
cargo build --release --target wasm32-unknown-unknown
cargo build --release --target wasm32-wasip1
cargo test  --release                                    # native
```

| target | result | artifact | bytes |
|---|---|---|---:|
| `x86_64-unknown-linux-gnu` | builds, **54/54 tests pass** | `libciris_sim_core.rlib` | — |
| `wasm32-unknown-unknown` | builds clean, no warnings | `target/wasm32-unknown-unknown/release/libciris_sim_core.rlib` | 351,728 |
| `wasm32-wasip1` | builds clean, no warnings | `target/wasm32-wasip1/release/libciris_sim_core.rlib` | 351,718 |

(Reproducible: two clean rebuilds give the same bytes. Pre-E10 at `d6d15e1` these
rlibs were 176,430 / 176,420 with 32 tests. E10's
`structure.rs` + `linalg.rs` roughly doubled the rlib; §1.3 has the effect on the
artifact that actually ships.)

**An rlib byte count is not a wasm byte count** and must not be quoted as one: an rlib is
an `ar` archive of object code plus crate metadata, and `ciris-sim-core` is a library, so
`cargo build --target wasm32-*` never emits a `.wasm` at all. Real module sizes are §1.3.

### 1.2 The tests actually run under wasm, they are not merely compiled

`crates/ciris-sim-core/.cargo/config.toml` registers a `wasm32-wasip1` runner
(`tools/wasi-run.mjs`, ~20 lines of `node:wasi`), so:

```
cargo test --release --target wasm32-wasip1
# test result: ok. 54 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out
```

The same 54 tests that pass natively pass inside a wasm sandbox — including the twin
dark-state null at 1e-12, the sector dimensions 9/1/1/0, the E8 ledger over 3000 steps,
and E10's runtime eigensolve. This is the substantive half of the portability claim; the
build succeeding is the easy half.

*Caveat on the runner.* `wasm32-wasip1` std aborts on panic, so a failing test kills the
whole process and libtest cannot print a failure summary — you get a `RuntimeError:
unreachable` and the name of the **first** failing test, not a list. This was observed
during E10's landing and is a property of the target, not of the runner. Diagnose
failures natively; use the wasm run to confirm the target agrees.

### 1.3 Real `.wasm` sizes

Getting an actual module requires a `cdylib`, and a `no_std` cdylib requires a
`#[panic_handler]`, which the engine (correctly) does not supply. `crates/wasm-probe/`
is the smallest honest way to produce one: it links the engine, supplies the panic
handler, and exports enough surface that the linker cannot discard the engine as dead
code. It adds no `std`, no `alloc`, and no `unsafe` blocks. Its `[lib] crate-type` is
`rlib` only, because a host `cdylib` would demand a panic handler a std build must not
have; the wasm `cdylib` is requested per-target on the command line instead:

```
cd sim_engine/crates/wasm-probe
cargo rustc --release --target wasm32-unknown-unknown --crate-type cdylib
cargo rustc --release --target wasm32-wasip1          --crate-type cdylib
```

The probe carries a `verify` feature holding §8's E10 verification surface. The
deployment figure is the build **without** it, so it measures the engine as a consumer
would link it:

```
cargo rustc --release --no-default-features --target <T> --crate-type cdylib   # lean
cargo rustc --release                       --target <T> --crate-type cdylib   # + §8 surface
```

| target | lean `.wasm` | with §8 surface | pre-E10 lean |
|---|---:|---:|---:|
| `wasm32-unknown-unknown` | **18,259** | 35,321 | 16,922 |
| `wasm32-wasip1` | **18,216** | 35,278 | 16,879 |

(`opt-level="s"`, LTO, `panic=abort`, stripped. At `opt-level=3` the lean module is
27,048 bytes.) The 17,062-byte gap is worth naming rather than hiding: it is what
**const-generic monomorphisation costs** — `Structure::from_coupling` and `jacobi_eigen`
instantiated at three sizes instead of one. E10 chose const generics (FSD §10.2) and
this is the per-size price in shipped bytes.

**~18 KB is the whole engine plus its sealed tables plus `libm`.** E10 cost 1,337 bytes
(+7.9%) in the lean module — the runtime eigensolve and general `Structure<N>` path are
nearly free in code size at one instantiation, even though they doubled the rlib. No `wasm-opt` was run
(Binaryen is not installed here), so these are unpostprocessed linker output — a floor
to improve on, not a best case.

**Host imports: none.**

```
node -e "const m=new WebAssembly.Module(require('fs').readFileSync(process.argv[1]));
         console.log(JSON.stringify(WebAssembly.Module.imports(m)))" <module.wasm>
# []   (both targets)
```

Zero imports on both targets — including `wasm32-wasip1`, which imports no
`wasi_snapshot_preview1` function at all. The module needs nothing from the host: no
allocator, no clock, no RNG, no syscall. That is the "runs in a WASM sandbox with no
allocator" claim in `lib.rs`, confirmed at the module boundary rather than argued.

### 1.4 Bit-identical results across all three targets

`tools/portability_check.mjs` (wasm) and `crates/wasm-probe/examples/native_probe.rs`
(native) print the same 169 values as raw `f64` bit patterns:

```
cd sim_engine/crates/wasm-probe
cargo run --release --example native_probe > native.txt
node ../../tools/portability_check.mjs target/wasm32-unknown-unknown/release/ciris_sim_wasm_probe.wasm > uu.txt
node ../../tools/portability_check.mjs target/wasm32-wasip1/release/ciris_sim_wasm_probe.wasm          > wasi.txt
diff native.txt uu.txt && diff native.txt wasi.txt
```

**Result: 0 differing lines over 169 values, for both wasm targets.** The 169 cover

* 66 values — position and velocity of all eleven kinds after **1000 harmonic steps**
  under the symmetrised coupling (the regime the twin theorem lives in);
* 66 values — the same after **1000 steps under `Params::default` and the measured
  coupling**: nonlinear springs with rest lengths, softened repulsion, damping, the
  arithmetic-heavy path where a divergence would show first;
* 32 scalars — Laplacian eigenvalues, masses, `TIME_UNIT`, `stiffness_ratio`,
  `suggested_dt`, both `g_db` readings, twin-probe displacement and leakage, sector
  leakage, `reduction_ratio`;
* 5 integers — `field::coarsen` class counts at five tolerances.

A thousand steps is enough for any last-bit disagreement in the force law to amplify
into visible divergence, so this is a real determinism check and it passes. **It covers
the integrator and the sealed tables only** — E10's runtime linear algebra is checked
separately in §8, which extends the same comparison to 1152 values. FSD §10.4 item 2
(bit-identical trajectories across the three targets) is **satisfied for our own
engine**; whether the incumbent can say the same is a separate question and is not
scored here. The `opt-level=3` and `-C target-feature=+simd128` variants are also
bit-identical, so determinism does not depend on the optimisation settings.

---

## 2. Speed is NOT portable, even though results are

This is the one place the "runs identically" claim needs qualifying, and it is worth
stating plainly because it is invisible to every correctness test.

```
node tools/wasm_step_cost.mjs <module.wasm>                 # wasm, best of 5 x 500k steps
cd crates/ciris-sim-core && cargo bench --bench step_cost   # native
```

| path | native | wasm (`opt-level=3`, V8) | ratio |
|---|---:|---:|---:|
| `step`, harmonic (`F = −Lx`) | 175.5 ns | 365.8 ns | 2.1x |
| `step`, default params, measured coupling | 349.7 ns | 2750.8 ns | **7.9x** |

The harmonic path costs the ordinary ~2x that wasm costs everything. The nonlinear path
costs **7.9x**, and the cause is `libm::sqrt`:

* on `x86_64`, libm's default `arch` feature routes `sqrt` to hardware `sqrtsd` — that
  branch is gated on `target_feature = "sse2"` alone, which x86_64 always has;
* on `wasm32`, the branch that would emit the `f64.sqrt` instruction additionally
  requires libm's **non-default, nightly-only `unstable-intrinsics` feature**
  (`libm-0.2.16/src/math/arch/mod.rs:12`, `configure.rs:81`). Without it, wasm gets the
  software Newton routine.

Verified rather than inferred — forcing the software path on the native target
reproduces the gap on native hardware:

```
# libm = { version = "0.2", features = ["force-soft-floats"] }
cargo bench --bench step_cost
```

| path | native, hardware sqrt | native, forced software sqrt |
|---|---:|---:|
| `step`, harmonic (no sqrt on this path) | 175.5 ns | 199.4 ns |
| `step`, default params | 349.7 ns | **1143.1 ns** |
| `forces()`, default params | 171.9 ns | 559.0 ns |

Software sqrt costs native 3.3x on the nonlinear path and costs the harmonic path
almost nothing — exactly the shape of the wasm gap. The residual wasm-vs-native factor
after accounting for it (2750.8 / 1143.1 = 2.4x) matches the harmonic path's 2.1x, so
`sqrt` accounts for essentially all of the excess.

`-C target-feature=+simd128` changes nothing, which rules out vectorisation as the
explanation and is itself worth knowing: **this force loop does not vectorise**, on
either target.

**Why determinism survived a different sqrt implementation:** IEEE-754 `sqrt` is
correctly rounded, so the software routine and `sqrtsd` return the same bits. The
engine's determinism does not depend on both targets computing it the same *way*, only
on the operation being exact — a genuine robustness property, not luck.

**Actionable, and not yet done:** enabling libm's `unstable-intrinsics` on the wasm
build should recover most of the 7.9x. It could not be tested here — see §7.

---

## 3. FSD §11.5 — the binding precondition, measured

> *"The N/G table was measured on the disordered-emitter profile system, not on this
> engine's scenes. If scene profiles do not repeat … G ~ N, the reduction is 1x, and the
> engine is a factor-of-four symmetry trick with a nice metric. That is the honest
> failure mode and it must be measured on real scenes before any scaling claim is
> made."*

```
cd sim_engine/crates/ciris-sim-core
cargo bench --bench profile_reduction
```

### 3.0 What is the library, and what is a reference copy

Since E10, `field::coarsen` is generic over `N` and takes a `Structure<N>`, so **the
engine's own function produces every class count in sections A–D up to N=1024**. One
equivalent reference implementation survives in the bench, used only where
`Structure<N>` will not fit (N=4096 is 1.07 GB — see §3.4) and to keep the
order-sensitivity sweep affordable. Two cross-checks run first and abort the bench on
any disagreement:

```
cross-check 1: reference copy == field::coarsen on K11, 41/41 tolerances in [0, 10]
cross-check 2: coupling-only Structure<64> == fully derived Structure<64>, 21/21 tolerances
```

Cross-check 2 exists because synthetic structures are built by filling `st.coupling` and
skipping the `O(N^3)` eigensolve; it confirms against a fully derived
`Structure::from_coupling` that coarsening really does read nothing else. Every row
produced by the library is additionally asserted equal to the reference copy at run time.

The algorithm being measured: distance is the **sup norm** over the two nodes' complete
coupling rows, skipping the two entries `k ∈ {a,b}` that reference the pair itself;
clustering is **greedy leader** in index order — not transitive closure, and not
order-invariant in general (§3.2 E measures whether that matters here).

### 3.1 The table

Tolerances are absolute, on matrices whose off-diagonal mean is 1 — the normalisation
`data::COUPLING` states it uses. `dist evals` is the number of profile-distance
computations the coarsening itself cost; §11 does not price this and §3.3 argues it
should.

**A. The built-in K11 object** (`field::coarsen(&K11, tol)`)

| N | G@0.1 | N/G | G@0.5 | N/G | G@1.0 | N/G |
|---:|---:|---:|---:|---:|---:|---:|
| 11 | 11 | **1.00x** | 11 | **1.00x** | 11 | **1.00x** |

The full curve, swept at 0.05 resolution:

| tolerance | 0.00 | 1.20 | 1.50 | 2.65 | 3.25 | 3.45 | 5.00 | 7.05 | 7.60 | 9.05 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| G | 11 | 10 | 9 | 8 | 6 | 5 | 4 | 3 | 2 | 1 |

The first merge needs a tolerance of 1.20 — larger than the mean off-diagonal coupling
itself, which is 1 by construction. **There is no reduction to be had on the engine's
only real scene** at any tolerance that preserves the scene.

**B. HOSTILE — independent random couplings** (the §11.5 failure mode, by construction)

| N | G@0.1 | N/G | G@0.5 | N/G | G@1.0 | N/G | dist evals @0.5 | time @0.5 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 64 | 64 | 1.00x | 64 | 1.00x | 64 | 1.00x | 2,016 | 0.2 ms |
| 128 | 128 | 1.00x | 128 | 1.00x | 128 | 1.00x | 8,128 | 0.9 ms |
| 256 | 256 | 1.00x | 256 | 1.00x | 256 | 1.00x | 32,640 | 6.3 ms |
| 512 | 512 | 1.00x | 512 | 1.00x | 512 | 1.00x | 130,816 | 45.0 ms |
| 1024 | 1024 | 1.00x | 1024 | 1.00x | 1024 | 1.00x | 523,776 | 331.0 ms |
| 2048 | 2048 | 1.00x | 2048 | 1.00x | 2048 | 1.00x | 2,096,128 | 2617.0 ms |

**G = N exactly, at every N and every tolerance. The predicted failure mode is real and
it is total.** Not "reduction degrades" — no two profiles merge, ever.

The mechanism is forced rather than accidental: profile distance is a **sup norm over
N−2 independent coordinates**, so two profiles merge only if *all* N−2 differences fall
under tolerance, and that probability decays exponentially in N. Raising the tolerance
does not rescue it — the tolerance would have to grow with N, and a tolerance that
admits everything coarsens everything to one class and discards the scene.

**C. FAVOURABLE — k archetypes replicated, profiles repeat EXACTLY**

| case | N | G@0.1 | N/G | G@0.5 | N/G |
|---|---:|---:|---:|---:|---:|
| blocks k=4 | 256 | 4 | 64.00x | 4 | 64.00x |
| blocks k=4 | 1024 | 4 | 256.00x | 3 | 341.33x |
| blocks k=4 | 4096 | 4 | **1024.00x** | 3 | **1365.33x** |
| blocks k=16 | 256 | 16 | 16.00x | 16 | 16.00x |
| blocks k=16 | 1024 | 16 | 64.00x | 16 | 64.00x |
| blocks k=16 | 4096 | 16 | **256.00x** | 16 | **256.00x** |
| blocks k=64 | 256 | 64 | 4.00x | 64 | 4.00x |
| blocks k=64 | 1024 | 64 | 16.00x | 64 | 16.00x |
| blocks k=64 | 4096 | 64 | **64.00x** | 64 | **64.00x** |

**G = k exactly, independent of N**, so N/G grows linearly in N and without bound — a
stronger scaling than §11.2's table claims. (Where G dips below k, two archetypes fell
within tolerance of each other by chance; that is a smaller G, not a different story.)
But read what G = k is: the number of archetypes *the generator was handed*. The
reduction is a property of the generating process, not something the coarsening
discovers.

**D. REALISTIC MIDDLE — k=16 archetypes plus independent jitter**

| jitter | N | G@0.1 | N/G | G@0.5 | N/G | G@1.0 | N/G |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 0.05 | 256 | 16 | 16.00x | 16 | 16.00x | 15 | 17.07x |
| 0.05 | 1024 | 16 | 64.00x | 16 | 64.00x | 16 | 64.00x |
| 0.20 | 256 | **256** | **1.00x** | 16 | 16.00x | 16 | 16.00x |
| 0.20 | 1024 | **1024** | **1.00x** | 16 | 64.00x | 16 | 64.00x |
| 0.60 | 256 | 256 | 1.00x | **256** | **1.00x** | 254 | 1.01x |
| 0.60 | 1024 | 1024 | 1.00x | **1024** | **1.00x** | 1024 | 1.00x |

This is the informative panel. The transition between "1024x reduction" and "no
reduction at all" is a **step, not a slope**, and it sits exactly where jitter crosses
tolerance. At jitter 0.2 the same scene reads 1.00x at tolerance 0.1 and 64x at
tolerance 0.5. At jitter 0.6 nothing survives at any tolerance tested.

**E. Order sensitivity** (greedy leader in index order vs 8 random relabellings, tol 0.5)

| case | N | G(index order) | G(min over 8) | G(max over 8) |
|---|---:|---:|---:|---:|
| K11 measured | 11 | 11 | 11 | 11 |
| random couplings | 512 | 512 | 512 | 512 |
| blocks k=16 exact | 512 | 16 | 16 | 16 |
| blocks k=16 jitter=0.2 | 512 | 16 | 16 | 16 |

G is stable under relabelling in all four cases, so the greedy leader is not silently
inventing or destroying classes here. Worth checking — greedy leader clustering is
neither transitive nor order-invariant in general, and a G that moved under relabelling
would be an algorithm artefact rather than a structural quantity. It does not move on
any structure tested. It is not proved that it never can.

**F. How G moves with N at fixed tolerance 0.5**

| case | N | G | N/G | growth |
|---|---:|---:|---:|---|
| random couplings | 128 → 1024 | 128 → 1024 | 1.00x throughout | N x2 ⇒ **G x2.00** each doubling |
| blocks k=16 exact | 128 → 1024 | 16 → 16 | 8x → 64x | N x2 ⇒ **G x1.00** each doubling |
| blocks k=16 jitter=0.2 | 128 → 1024 | 16 → 16 | 8x → 64x | N x2 ⇒ **G x1.00** each doubling |

### 3.2 The reading

**Does G stay small as N grows? Only when the scene is built from a small number of
repeated profiles — and then it stays small because it was small, not because
coarsening found anything.**

1. **The FSD's honest failure mode is confirmed, at full strength.** On unstructured
   scenes G = N exactly, every N, every tolerance.
2. **The favourable case is real but tautological** — G = k because the generator was
   handed k archetypes.
3. **K11, the engine's only real scene, reads 1.00x at every usable tolerance.** The
   scaling thesis has zero support from the object the engine runs.
4. **The behaviour is a step function of (jitter / tolerance), not a curve**, so the
   win cannot be estimated from a small sample. A scene is on one side or the other.
5. **What the thesis requires is a claim about scene generation, not about coarsening.**
   N/G = 7037x at N=1M needs G ≈ 142 complete profiles among a million nodes. The right
   next question is "do CIRIS scenes come from a low-cardinality generator, and how much
   jitter does the pipeline add?" — a measurement on captured scenes, which do not exist.

FSD §16 records the consequence: §11 is retracted as a claim and restated conditionally.

### 3.3 A cost the FSD does not price

The `dist evals` column is the reduction check's own bill. In the hostile case it is
**N(N−1)/2 evaluations, each O(N)** — 2.1 million distance computations and 2.62 s at
N=2048 — because when nothing merges, every node becomes a leader and gets compared to
every remaining node. So the coarsening is **Θ(N³) exactly when it fails**, against the
Θ(N²) force evaluation it is meant to accelerate. In the favourable case it is
Θ(k·N²) — 34,800 evals at N=4096, cheap.

§11.4 calls the runtime check "a covering number of observed profiles at the tolerance
the frame needs, and it is computable per frame". At these constants it is not
per-frame computable on an unstructured scene: 2.62 s at N=2048 is ~157,000 frames'
worth of budget at 60 Hz. Either the check is amortised across frames with an
incremental update, or it needs an early-out that abandons coarsening once G exceeds a
threshold. Neither exists today. **This is an unlisted open gap, and by §9.5's rule
("an unlisted open gap is a defect") it should be listed.**

### 3.4 The other scaling wall, and it is not G

E10 chose const generics (FSD §10.2), which keeps `no_std` and zero heap. The cost the
fork table does not name is that `Structure<N>` is **8 dense N×N `f64` matrices** —
`coupling`, `coupling_sym`, `metric`, `eigenvectors` and 4 `sector_projectors` — held by
value, so it is `64·N²` bytes with no allocator to put them in.

Measured with `size_of`:

| N | `size_of::<Structure<N>>()` | derivation |
|---:|---:|---|
| 11 | 0.01 MB | shipped table |
| 64 | 0.25 MB | `O(N³)` eigensolve |
| 256 | 4.01 MB | `O(N³)` eigensolve — **0.856 s measured** |
| 1024 | 64.02 MB | `O(N³)` eigensolve |
| 2048 | 256.05 MB | `O(N³)` eigensolve |

`Structure::<256>::from_coupling` takes **0.856 s** (`spectrum_converged = true`).
Extrapolating the `O(N³)` Jacobi solve, N=1024 is roughly a minute and N=4096 is 1.07 GB
of stack-resident struct. **The regime §11 wanted — N = 10⁶ — would need 64 TB for the
`Structure` alone**, before any question about G arises. The bench runs on a 1 GiB thread
stack for exactly this reason (`std::thread::Builder::stack_size`); the default 8 MB main
stack cannot hold a `Structure<1024>`.

The architectural consequence is worth stating: **`field::coarsen` takes a
`Structure<N>`, so today you must build the Θ(N²) object in order to discover that you
only needed G classes.** The level-of-detail path is inverted. If coarsening is to buy
anything it must run on the raw scene and only then build a `Structure<G>` — which means
it should take a coupling (or a scene), not a derived `Structure`. This is a design
observation from the measurement, not a defect report; the current signature is fine for
K11.

---

## 4. Step cost — self-measurement only

```
cd sim_engine/crates/ciris-sim-core
cargo bench --bench step_cost
```

**No comparison against any other engine appears here and none should until E10 lands
and the comparison is at matched N with matched generality.** FSD §10.1 and the §10.4
anti-hype clause are binding. What follows is a baseline to regress against, not a score.

Native, `x86_64`, best of 5 repetitions:

| measurement | ns/unit | allocations |
|---|---:|---:|
| `step`, harmonic (`F = −Lx`, symmetrised) | **175.5** | 0 |
| `step`, `Params::default`, measured coupling | **349.7** | 0 |
| `step_massive` (E2 fill, per-kind mass) | 180.7 | 0 |
| `run(1000)`, amortised per step | 358.7 | 0 |
| `forces()` alone, harmonic | 89.4 | 0 |
| `forces()` alone, default params | 171.9 | 0 |
| `Ledger::step_and_account` (E8 bookkeeping) | 630.5 | 0 |
| 1,000,000 consecutive steps, harmonic | 0.180 s total = **180.5 ns/step** | 0 |

The headline for the K11 object is **176 ns/step harmonic, 350 ns/step under the full
nonlinear default parameters** — about 5.7 M and 2.9 M steps/second.

Reading notes, so the numbers are not misread:

* `step` is velocity-Verlet and evaluates `forces()` **twice** per step; `step_massive`
  is semi-implicit Euler and evaluates it **once**. That, and not a cheaper mass model,
  is the whole of why `step_massive` reads faster. They are different integrators.
* `2 x 89.4 = 179` matches `step` harmonic at 175.5, so the step is force evaluation and
  nothing else — there is no hidden per-step overhead to remove.
* `Ledger::step_and_account` costs 1.8x a bare step because it evaluates the full
  potential energy twice per step (before and after the boundary). That is the price of
  E8's books balancing, and only paid when a caller asks for the ledger.
* Per step: `N(N−1)/2 = 55` pairs x 2 force evaluations = 110 pair-terms. `State<11>` is
  528 bytes, `Copy`, stack-resident.

**One trap this bench walked into and now guards.** The Ledger measurement first read
355 ns — indistinguishable from a bare step. The cause was not a speedup: `l.recorded`
was never read after the loop, so the optimiser deleted the accumulation and with it
*both* potential-energy evaluations. The fix is `black_box(l.recorded)` after the loop,
and the comment in `benches/step_cost.rs` says so, because the wrong number was
plausible and would have been reported.

### 4.1 Zero allocations — how it was verified, not asserted

"The crate has no allocator, so it must be true" is an argument, not evidence, and the
failure it would miss is an allocation introduced by something the library *calls*.
Three independent checks, all passing:

1. **Counted at runtime.** `benches/step_cost.rs` installs a counting
   `#[global_allocator]` wrapping `System` and reads the counter immediately before and
   after every timed region, including a single uninterrupted 1,000,000-step run.
   **Every region reports exactly 0 allocator calls** (`alloc`, `alloc_zeroed`,
   `realloc` and `dealloc` are all counted). The 1e6-step run asserts on it, so a
   regression fails the bench rather than printing a footnote.
2. **Structural, in the dependency graph.**
   `cargo tree --target wasm32-unknown-unknown` → `ciris-sim-core v0.1.0 └── libm
   v0.2.16`. One dependency, itself `no_std`. And
   `grep -rn 'extern crate alloc\|alloc::\|Vec<\|Box<\|String' src/*.rs` returns
   **nothing** — no allocator in the graph to call, no allocating type in the source.
3. **On the wasm target, from outside.** `node tools/wasm_step_cost.mjs <module.wasm>`
   reads the exported linear memory's byte length before and after 1,000,000 steps:
   **17 pages (1088 KiB) before, 17 pages after — no growth**, and still 17 after the
   timing loop. With §1.3's empty import list, the module cannot be obtaining memory
   from the host either.

Only (1) is measured by the bench itself; (2) and (3) are the commands above, recorded
so the claim is reproducible rather than taken on trust.

---

## 5. FSD §10.4 coverage

| §10.4 item | status |
|---|---|
| 1. wall time per step | §4. Measured. |
| 1. allocations per step, ours must be 0 | §4.1. Measured 0, three ways. |
| 2. bit-identical trajectories across the three targets | §1.4 (integrator + sealed tables) and §8 (E10 runtime linear algebra). **Verified**, 1152 values, 0 differences, harness mutation-tested to one ULP. |
| 3. energy drift over 10⁴ steps | **Not benchmarked.** Crate tests cover conservation (`energy_does_not_grow_without_damping`, ledger <5% over 3000 steps); a dedicated drift bench is not written. |
| 3. inter-sector leakage as conservation check | Covered by the crate's `sectors` tests, not by a bench. |
| 4. quality is NOT claimed | Nothing here claims it. |
| §10.1 no cross-engine comparison before E10 | Honoured. No incumbent was benchmarked. |

---

## 6. What was added, and where

Nothing under `crates/ciris-sim-core/src/` was touched.

| path | what it is |
|---|---|
| `crates/ciris-sim-core/benches/profile_reduction.rs` | §3. The FSD §11.5 precondition measurement. `harness = false`. |
| `crates/ciris-sim-core/benches/step_cost.rs` | §4. Step cost + the counting allocator. `harness = false`. |
| `crates/ciris-sim-core/Cargo.toml` | two `[[bench]]` entries only. |
| `crates/ciris-sim-core/.cargo/config.toml` | `wasm32-wasip1` test runner. Does not affect native or `wasm32-unknown-unknown`. |
| `crates/wasm-probe/` | §1.3. Deployment probe: real `.wasm`, and the native half of the bit-identity check. Not engine code. |
| `tools/wasi-run.mjs` | ~20-line `node:wasi` shim used as the cargo runner. |
| `tools/portability_check.mjs` | wasm half of the bit-identity check. |
| `tools/wasm_step_cost.mjs` | wasm step timing + linear-memory growth check. |
| `crates/wasm-probe/examples/sweep_sensitivity.rs` | §8.3. Calibrates how sensitive the `sweeps` count is, so its agreement is not oversold. |
| `crates/wasm-probe/Cargo.toml` | a `verify` feature, so §1.3's deployment size measures the lean build. |

On feature gating: benches did **not** need the `std` feature. A bench target is a
separate crate that links the library, so it may use `std` freely while the library
stays `no_std` — no gymnastics were required and the `std` feature remains unused.
`harness = false` because libtest's `#[bench]` is nightly-only; these are plain
`fn main()` reports meant to be read.

`crates/wasm-probe` does not carry `#![forbid(unsafe_code)]`, unlike the engine, for one
narrow reason: since Rust 1.82 `#[no_mangle]` is itself an unsafe attribute, so a crate
exporting a C ABI cannot forbid unsafe. It contains **zero `unsafe` blocks**; its panic
handler spins rather than calling the unsafe `core::arch::wasm32::unreachable`. The
engine's own `#![forbid(unsafe_code)]` is untouched.

---

## 7. What could NOT be verified

1. **No standalone wasm runtime on this host.** No `wasmtime`, `wasmer` or `wasm3`.
   All wasm execution went through Node 20's V8 (`node:wasi` for wasip1, bare
   `WebAssembly` for unknown-unknown). Bit-identity is a property of the module and
   would not change under a different runtime; **the wasm timings in §2 are V8 numbers
   and should not be quoted as "wasm" numbers** without re-measuring under wasmtime.
2. **The `libm` intrinsics fix is diagnosed but untested.** Building with libm's
   `unstable-intrinsics` feature requires nightly, and the installed nightly toolchain
   has no wasm targets (`rustup +nightly target list --installed` →
   `x86_64-unknown-linux-gnu` only). Adding them needs network access. The diagnosis in
   §2 is nonetheless established independently, by reproducing the slowdown on native
   with `force-soft-floats`.
3. **G/N was measured on synthetic structures, not on captured scenes.** No captured
   CIRIS scenes exist. §3 measures the two bracketing cases and the transition between
   them; it does **not** answer where real scenes fall. **The scaling claim remains
   unmade, and this document does not make it.**
4. **`wasm-opt` was not run** (Binaryen not installed), so §1.3's sizes are
   unpostprocessed linker output.
5. **Energy drift over 10⁴ steps was not benchmarked** — see §5.
6. **`Structure<N>` derivation was timed only at N=256** (0.856 s). N≥1024 was not
   timed; the O(N³) extrapolation in §3.4 is arithmetic, not a measurement.
7. **Timings are single-host, unpinned CPU, no frequency control.** Best-of-5 with a
   warmup, worst repetition reported alongside; spread was under 4% on every
   measurement. Treat as ±10%.
8. **N > 128 was not verified across targets.** `Structure<128>` (1.05 MB) does not fit
   the 1 MiB wasm stack, and raising it with `-C link-arg=-zstack-size=…` was not
   attempted. §8's eigensolver check stops at N=128.
9. **Only one host architecture.** Everything native is `x86_64`. An `aarch64` leg is
   the obvious next target — `libm` takes a different arch branch there — but it is
   blocked on this machine: no `qemu-aarch64` user-mode emulator and no
   `aarch64-linux-gnu` cross-linker, though `rustup` has the target installed.
   One structural reason to expect it to pass: **the engine calls no `fma` anywhere**
   (`grep -rn fma src/` is empty). Fused multiply-add is the classic source of
   architecture-dependent float results, because `a*b+c` fused rounds once and unfused
   rounds twice; a codebase that never uses it has removed the main way two IEEE-754
   targets legitimately disagree. That is an argument, not a measurement, and it is
   listed here rather than in §8 for that reason.
10. **`jacobi_eigen().sweeps` agreement at a fixed input is weak on its own** — a ULP
    does not move it (§8.3). The strong evidence is the 885 cell-level comparisons, the
    digests (which do move on one ULP), and the knife-edge test.

---

## 8. E10's runtime linear algebra — the re-run that §1.4 did not cover

The first pass compared the **integrator** and the **sealed `K11` tables**. E10 added
`linalg.rs` and `structure.rs`: a const-generic `Structure<N>` derived at runtime by a
hand-rolled cyclic Jacobi eigensolver. That is a different and much sharper risk. The
sweep loop exits on `off_sq <= tol_sq`, a floating-point comparison, so a single-ULP
difference in the accumulated off-diagonal norm would change the iteration count and
cascade into every derived table. Nothing in §1.4 would have caught it.

```
cd sim_engine/crates/wasm-probe
cargo rustc --release --target wasm32-unknown-unknown --crate-type cdylib
cargo rustc --release --target wasm32-wasip1          --crate-type cdylib
cargo run --release --example native_probe > native.txt
node ../../tools/portability_check.mjs target/wasm32-unknown-unknown/release/ciris_sim_wasm_probe.wasm > uu.txt
node ../../tools/portability_check.mjs target/wasm32-wasip1/release/ciris_sim_wasm_probe.wasm          > wasi.txt
diff native.txt uu.txt && diff native.txt wasi.txt
```

### 8.1 Result

**0 differing lines over 1152, on both wasm targets.** Coverage added beyond §1.4:

| what | how compared | count |
|---|---|---:|
| `Structure::<11>::from_coupling` — `coupling_sym`, `metric`, `eigenvectors`, all four `sector_projectors`, `eigenvalues`, `mass`, `susceptibility`, `sector_dims`, `spectrum_converged` | every cell, raw IEEE-754 bits | 885 |
| `Structure::<64>::from_coupling` — the spectrum | every eigenvalue, raw bits | 64 |
| nine derived fields at N=11 and at N=64 | FNV-1a over raw bit patterns | 18 |
| `jacobi_eigen` eigenvalues + eigenvectors at N=11, 64, **128** | FNV-1a over raw bits | 6 |
| `jacobi_eigen().sweeps` at N=11, 64, 128 | integer | 3 |
| `jacobi_eigen().converged` at the same sizes | integer | 3 |
| the knife-edge test (§8.3) | boundary bit pattern + 3 counts | 4 |

**The sweep counts are identical on all three targets: 6 at N=11, 8 at N=64, 8 at
N=128, `converged = true` throughout.** N=128 is the largest size that fits: a full
`Structure<128>` is 1.05 MB and would overflow the 1 MiB wasm stack, but `jacobi_eigen`
needs only `2N²` doubles plus its `Eigen<N>` return — about 525 KB — so the eigensolver
is exercised there without the surrounding structure.

Digests are cross-consistent in a way that catches a mis-wired harness: `eig 0 0` equals
`chk 0 7` and `eig 0 1` equals `chk 0 3`, i.e. the eigensolve called directly produces
bit-identical output to the eigensolve reached through `Structure::from_coupling`.

### 8.2 The harness is sensitive to one ULP — demonstrated, not assumed

A comparison that cannot fail proves nothing, so the harness was mutation-tested. One
entry of the measured coupling was perturbed by **a single ULP**
(`c[1][4] = from_bits(to_bits + 1)`, symmetrised) and the probe rebuilt:

* **484 of 1148 lines changed** — 242 individually compared cells plus 7 of the 9 N=11
  field digests.
* The two digests that did *not* move are `sector_projectors` and
  `sector_dims`+`converged`, which is correct rather than a gap: the Z₂×Z₂ character
  projectors are built from the twin permutations, not from coupling magnitudes.
* The mutant build was **still bit-identical between native and wasm** (0 differing
  lines), so the agreement is a property of the engine, not of the one input.

**And nothing is constant-folded.** `COUPLING` is a `const` and `from_coupling` is pure,
so a compiler could in principle bake the answers and reduce this to "two compilers
agree" rather than "two runtimes agree". Measured in wasm, three consecutive runs
agreeing to the digit: a trivial export costs **0.0000 ms/call**,
`Structure::<11>::from_coupling` costs **0.027 ms/call**, and
`Structure::<64>::from_coupling` costs **3.71 ms/call**. The module is 35,321 bytes,
while a baked `Structure<64>` alone would be 263,752. The work happens at run time.

### 8.3 The sweep count on its own is a BLUNT test — so a sharper one was built

Reporting "the sweep counts agree" without qualification would overstate the evidence.
Calibration (`cargo run --release --example sweep_sensitivity`):

* **A one-ULP change to an input does not move the count.** 33 consecutive ULP
  perturbations give 8 sweeps at N=64 every time, and 6 at N=11 every time. Jacobi
  converges quadratically against a fixed `1e-30 · ‖A‖_F²` target, so the margin at exit
  is many orders of magnitude and a ULP cannot bridge it.
* **The count is still input-dependent**: over 200 independent random couplings at
  N=64 it is 7 for 11 of them and 8 for the other 189. The branch is live across
  inputs, just blunt within one.

So agreement on `sweeps` at a fixed input is consistent with determinism but is weak
evidence for it. The sharp test is to find an input where one ULP *does* flip the count.
Interpolating `(1−t)·A + t·B` between a 7-sweep and an 8-sweep coupling and bisecting on
the **bit pattern** of `t` lands on that boundary exactly. All three targets report:

```
edge bits 3f74ae99bdca9244
edge below 7        (t one ULP lower)
edge at    8
edge above 8
```

The three targets independently ran a 64-step bit-level bisection — sixty-odd branch
decisions, each one an eigensolve — and **arrived at the same double**, then agreed on
the counts on either side of it. That is agreement on a branch decision a single ULP
genuinely controls, which is the strongest form this claim can take.

### 8.4 What this does and does not license

It licenses: *the E10 runtime linear algebra is bit-identical across
`x86_64-unknown-linux-gnu`, `wasm32-unknown-unknown` and `wasm32-wasip1`, at N = 11, 64
and 128, including the eigensolver's iteration count at a point where that count is
one-ULP sensitive.*

It does not license a claim about N > 128, about non-x86 hosts, or about wasm runtimes
other than V8 — see §7. The mechanism the E10 author documents (row-cyclic rotation
order, no pivot search, so no rounding-dependent tie to resolve) is consistent with what
was measured, and nothing here contradicts it.

**No tolerance was loosened to obtain this result.** Every comparison is on raw
IEEE-754 bit patterns or on integers.
