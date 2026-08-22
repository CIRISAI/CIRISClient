# Portability and benchmark findings — `ciris-sim-core`

**Measured 2026-08-22.** Every number below names the command that produced it. Nothing
here is inherited from a spec or an earlier campaign; where a claim could not be
verified it is listed in §6 rather than softened.

**Source state.** All numbers are pinned to CIRISClient `d6d15e1`, taken from a clean
`git archive d6d15e1 sim_engine` extraction because the working tree was mid-edit
(`src/structure.rs` and `src/linalg.rs` were being added by the E10 work and the crate
did not compile at that moment). Re-running in the working tree once E10 lands is
expected to move the timings; the portability and G/N conclusions are structural and
should not move.

**Host.** `rustc 1.95.0 (59807616e 2026-04-14)`, `x86_64-unknown-linux-gnu`,
`node v20.20.2`. No `wasmtime`/`wasmer` on this machine — wasm execution is via Node's
`node:wasi` and the bare `WebAssembly` API. See §6.

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
| `x86_64-unknown-linux-gnu` | builds, **32/32 tests pass** | `libciris_sim_core.rlib` | — |
| `wasm32-unknown-unknown` | builds clean, no warnings | `target/wasm32-unknown-unknown/release/libciris_sim_core.rlib` | 176,430 |
| `wasm32-wasip1` | builds clean, no warnings | `target/wasm32-wasip1/release/libciris_sim_core.rlib` | 176,420 |

**An rlib byte count is not a wasm byte count** and must not be quoted as one: an rlib is
an `ar` archive of object code plus crate metadata, and `ciris-sim-core` is a library, so
`cargo build --target wasm32-*` never emits a `.wasm` at all. Real module sizes are §1.3.

### 1.2 The tests actually run under wasm, they are not merely compiled

`crates/ciris-sim-core/.cargo/config.toml` registers a `wasm32-wasip1` runner
(`tools/wasi-run.mjs`, ~20 lines of `node:wasi`), so:

```
cargo test --release --target wasm32-wasip1
# test result: ok. 32 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out
```

The same 32 tests that pass natively pass inside a wasm sandbox — including the twin
dark-state null at 1e-12, the sector dimensions 9/1/1/0, and the E8 ledger over 3000
steps. This is the substantive half of the portability claim; the build succeeding is
the easy half.

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

| target | `.wasm` bytes | build profile |
|---|---:|---|
| `wasm32-unknown-unknown` | **16,922** | `opt-level="s"`, LTO, `panic=abort`, stripped |
| `wasm32-wasip1` | **16,879** | same |
| `wasm32-unknown-unknown` | 25,207 | `opt-level=3` (`CARGO_PROFILE_RELEASE_OPT_LEVEL=3`) |
| `wasm32-unknown-unknown` | 24,685 | `opt-level=3`, `-C target-feature=+simd128` |

**~17 KB is the whole engine plus its sealed tables plus `libm`.** The crate documents
~6.9 KB of static data, so roughly 10 KB is code. No `wasm-opt` was run (Binaryen is not
installed here), so these are unpostprocessed linker output — a floor to improve on, not
a best case.

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
into visible divergence, so this is a real determinism check and it passes. FSD §10.4
item 2 (bit-identical trajectories across the three targets) is **satisfied for our own
engine**; whether the incumbent can say the same is a separate question and is not
scored here.

The `opt-level=3` and `+simd128` variants are also bit-identical to native, so the
determinism does not depend on the optimisation settings.

---

## 2. Speed is NOT portable, even though results are

This is the one place the "runs identically" claim needs qualifying, and it is worth
stating plainly because it is invisible to every correctness test.

```
node tools/wasm_step_cost.mjs <module.wasm>          # wasm, best of 5 x 500k steps
cd crates/ciris-sim-core && cargo bench --bench step_cost   # native
```

| path | native | wasm (`opt-level=3`) | ratio |
|---|---:|---:|---:|
| `step`, harmonic (`F = −Lx`) | 187.8 ns | 373.2 ns | 2.0x |
| `step`, default params, measured coupling | 366.4 ns | 2829.3 ns | **7.7x** |

The harmonic path costs the ordinary ~2x that wasm costs everything. The nonlinear path
costs **7.7x**, and the cause is `libm::sqrt`:

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
| `step`, harmonic (no sqrt on this path) | 187.8 ns | 218.3 ns |
| `step`, default params | 366.4 ns | **1210.8 ns** |
| `forces()`, default params | 176.8 ns | 593.5 ns |

Software sqrt costs native 3.3x on the nonlinear path and costs the harmonic path
almost nothing — exactly the shape of the wasm gap. The residual wasm-vs-native factor
after accounting for it (2829 / 1210.8 = 2.3x) matches the harmonic path's 2.0x, so
`sqrt` accounts for essentially all of the excess.

`-C target-feature=+simd128` changes nothing (2823.2 ns), which rules out vectorisation
as the explanation and is itself worth knowing: **this force loop does not vectorise**,
on either target.

**Why determinism survived a different sqrt implementation:** IEEE-754 `sqrt` is
correctly rounded, so the software routine and `sqrtsd` return the same bits. The
engine's determinism does not depend on both targets computing it the same *way*, only
on the operation being exact — which is a genuine robustness property, not luck.

**Actionable, and not yet done:** enabling libm's `unstable-intrinsics` on the wasm
build should recover most of the 7.7x. It could not be tested here — see §6.

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

### 3.0 What had to be re-implemented, and why that is disclosed

`field::coarsen` takes **no matrix and no N** — it is hardwired to the sealed N=11
`COUPLING`, because E10 (variable N) is the gap still open. It therefore cannot answer a
question about how G behaves as N grows. `benches/profile_reduction.rs` reproduces its
semantics for general N (sup norm over complete coupling rows, skipping the two entries
`k ∈ {a,b}`; greedy leader in index order, not transitive closure), and **cross-validates
the reproduction against the real `field::coarsen` on K11 before reporting any synthetic
number**:

```
cross-validation: coarsen_general == field::coarsen on K11 at 41/41 tolerances in [0, 10]
```

If that check ever fails the bench aborts. A G/N table produced by an algorithm that is
not the engine's algorithm would be worthless.

### 3.1 The table

Tolerances are absolute, on matrices whose off-diagonal mean is 1 — the normalisation
`data::COUPLING` states it uses. `dist evals` is the number of profile-distance
computations the coarsening itself cost; §11 does not price this and §3.3 argues it
should.

**A. The built-in K11 object** (`field::coarsen`, the engine's own path)

| N | G@0.1 | N/G | G@0.5 | N/G | G@1.0 | N/G |
|---:|---:|---:|---:|---:|---:|---:|
| 11 | 11 | **1.00x** | 11 | **1.00x** | 11 | **1.00x** |

K11's eleven kinds have eleven distinct profiles at every tolerance a frame would use.
The full curve, swept at 0.05 resolution, is

| tolerance | 0.00 | 1.20 | 1.50 | 2.65 | 3.25 | 3.45 | 5.00 | 7.05 | 7.60 | 9.05 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| G | 11 | 10 | 9 | 8 | 6 | 5 | 4 | 3 | 2 | 1 |

so the first merge needs a tolerance of 1.20 — larger than the mean off-diagonal
coupling itself, which is 1 by construction. **There is no reduction to be had on the
engine's only real scene** at any tolerance that preserves the scene.
That is not surprising at N=11 — the eleven kinds are *designed* to be distinct — but it
does mean the scaling thesis has no support from the object the engine currently runs.

**B. HOSTILE — independent random couplings** (the §11.5 failure mode, by construction)

| N | G@0.1 | N/G | G@0.5 | N/G | G@1.0 | N/G | dist evals @0.5 | time @0.5 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 64 | 64 | 1.00x | 64 | 1.00x | 64 | 1.00x | 2,016 | 0.2 ms |
| 128 | 128 | 1.00x | 128 | 1.00x | 128 | 1.00x | 8,128 | 0.9 ms |
| 256 | 256 | 1.00x | 256 | 1.00x | 256 | 1.00x | 32,640 | 6.1 ms |
| 512 | 512 | 1.00x | 512 | 1.00x | 512 | 1.00x | 130,816 | 44.0 ms |
| 1024 | 1024 | 1.00x | 1024 | 1.00x | 1024 | 1.00x | 523,776 | 331.4 ms |
| 2048 | 2048 | 1.00x | 2048 | 1.00x | 2048 | 1.00x | 2,096,128 | 2780.5 ms |

**G = N exactly, at every N and every tolerance. The predicted failure mode is real and
it is total.** Not "reduction degrades" — no two profiles merge, ever. §11.5's own words
apply: on such a scene the engine is a factor-of-four symmetry trick with a nice metric.

This is also forced rather than accidental, and the mechanism is worth naming: profile
distance is a **sup norm over N−2 independent coordinates**. Two profiles merge only if
*all* N−2 coordinate differences fall under tolerance, and for independent coordinates
that probability decays exponentially in N. Raising the tolerance does not rescue it —
the tolerance would have to grow with N, and a tolerance that admits everything
coarsens everything to one class and discards the scene.

**C. FAVOURABLE — k archetypes replicated, profiles repeat EXACTLY**

| case | N | G@0.1 | N/G | G@0.5 | N/G |
|---|---:|---:|---:|---:|---:|
| blocks k=4 | 256 | 4 | 64.00x | 4 | 64.00x |
| blocks k=4 | 1024 | 4 | 256.00x | 4 | 256.00x |
| blocks k=4 | 4096 | 4 | **1024.00x** | 4 | **1024.00x** |
| blocks k=16 | 256 | 16 | 16.00x | 16 | 16.00x |
| blocks k=16 | 1024 | 16 | 64.00x | 16 | 64.00x |
| blocks k=16 | 4096 | 16 | **256.00x** | 16 | **256.00x** |
| blocks k=64 | 256 | 64 | 4.00x | 64 | 4.00x |
| blocks k=64 | 1024 | 64 | 16.00x | 64 | 16.00x |
| blocks k=64 | 4096 | 64 | **64.00x** | 64 | **64.00x** |

**G = k exactly, independent of N**, so N/G grows linearly in N and without bound — a
stronger scaling than §11.2's table claims. But read what it is: G is the number of
archetypes *the generator was given*. The reduction is a property of the generating
process, not something the coarsening discovers.

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
inventing or destroying classes here. This was worth checking — greedy leader clustering
is neither transitive nor order-invariant in general, and a G that moved under
relabelling would be an algorithm artefact rather than a structural quantity. It does
not move on any structure tested. It is not proved that it never can.

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

Precisely:

1. **The FSD's honest failure mode is confirmed, at full strength.** On unstructured
   scenes G = N exactly, every N, every tolerance. §11.5's condition is met, and its
   stated consequence follows: no reduction, no asymptotic win, a factor-of-four
   symmetry trick with a nice metric.
2. **The favourable case is real and beats the FSD's own table** — G = k, constant in N,
   so N/G is linear in N rather than the table's sub-linear growth. But G = k is
   *tautological*: the generator was handed k archetypes.
3. **K11, the engine's only real scene, reads 1.00x at every usable tolerance.** The
   scaling thesis currently has zero support from the object the engine runs. The claim
   is not refuted — it is untested on the thing it is about, and by §11.5's own terms it
   may not be asserted until it is.
4. **The behaviour is a step function of (jitter / tolerance), not a curve.** So the
   scaling win is not a smooth property of a scene that can be estimated from a small
   sample. It is a threshold, and a scene sits on one side of it or the other.
5. **What the thesis actually requires is a claim about scene generation, not about
   coarsening.** N/G = 7037x at N=1M needs G ≈ 142 complete profiles among a million
   nodes — near-exact repetition, i.e. a scene emitted by a low-cardinality process. The
   right next question is therefore not "how well does coarsening compress?" but "do
   CIRIS scenes come from a low-cardinality generator, and how much jitter does the
   pipeline add?" That is a measurement on captured scenes, which do not exist yet.

### 3.3 A cost the FSD does not price

The `dist evals` column is the reduction check's own bill. In the hostile case it is
**N(N−1)/2 evaluations, each O(N)** — 2.1 million distance computations and 2.78 s at
N=2048 — because when nothing merges, every node becomes a leader and gets compared to
every remaining node. So the coarsening is **Θ(N³) exactly when it fails**, against the
Θ(N²) force evaluation it is meant to accelerate. In the favourable case it is
Θ(k·N·N) = 34,800 evals at N=4096, cheap.

§11.4 calls the runtime check "a covering number of observed profiles at the tolerance
the frame needs, and it is computable per frame". At these constants it is not
per-frame computable on an unstructured scene: 2.78 s at N=2048 is ~170,000 frames'
worth of budget at 60 Hz. Either the check is amortised across many frames with an
incremental update, or it needs an early-out that abandons coarsening once G exceeds a
threshold. Neither exists today. **This is a gap the FSD does not list, and by §9.5's
rule ("an unlisted open gap is a defect") it should be listed.**

---

## 4. Step cost — self-measurement only

```
cd sim_engine/crates/ciris-sim-core
cargo bench --bench step_cost
```

**No comparison against any other engine appears here and none should until E10 lands.**
FSD §10.1 and the §10.4 anti-hype clause are binding: the MVP is specialised to N=11
with compile-time tables, so a win over an engine built for arbitrary lattices would
measure the specialisation and nothing else. What follows is a baseline to regress
against, not a score.

Native, `x86_64`, best of 5 repetitions:

| measurement | ns/unit | allocations |
|---|---:|---:|
| `step`, harmonic (`F = −Lx`, symmetrised) | **187.8** | 0 |
| `step`, `Params::default`, measured coupling | **366.4** | 0 |
| `step_massive` (E2 fill, per-kind mass) | 189.3 | 0 |
| `run(1000)`, amortised per step | 366.8 | 0 |
| `forces()` alone, harmonic | 93.5 | 0 |
| `forces()` alone, default params | 176.8 | 0 |
| `Ledger::step_and_account` (E8 bookkeeping) | 660.1 | 0 |
| 1,000,000 consecutive steps, harmonic | 0.193 s total = **193.0 ns/step** | 0 |

The headline for the K11 object is **188 ns/step harmonic, 366 ns/step under the full
nonlinear default parameters** — about 5.3 M and 2.7 M steps/second respectively.

Reading notes, so the numbers are not misread:

* `step` is velocity-Verlet and evaluates `forces()` **twice** per step; `step_massive`
  is semi-implicit Euler and evaluates it **once**. That, and not a cheaper mass model,
  is the whole of why `step_massive` reads faster. They are different integrators.
* `2 x 93.5 = 187` matches `step` harmonic at 187.8 exactly, so the step is force
  evaluation and nothing else — there is no hidden per-step overhead to remove.
* `Ledger::step_and_account` costs 1.8x a bare step because it evaluates the full
  potential energy twice per step (before and after the boundary). That is the price of
  E8's books balancing, and it is only paid when a caller asks for the ledger.
* Per step: `N(N−1)/2 = 55` pairs x 2 force evaluations = 110 pair-terms. `State` is 528
  bytes, `Copy`, stack-resident.

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
   **nothing** — there is no allocator in the graph to call and no allocating type in
   the source.
3. **On the wasm target, from outside.** `node tools/wasm_step_cost.mjs <module.wasm>`
   reads the exported linear memory's byte length before and after 1,000,000 steps:
   **17 pages (1088 KiB) before, 17 pages after — no growth**, and still 17 after the
   timing loop. Combined with §1.3's empty import list, the module cannot be obtaining
   memory from the host either.

Only (1) is measured by the bench itself; (2) and (3) are the commands above, recorded
so the claim is reproducible rather than taken on trust.

---

## 5. What was added, and where

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

## 6. What could NOT be verified

1. **No standalone wasm runtime on this host.** No `wasmtime`, `wasmer` or `wasm3`.
   All wasm execution went through Node 20's V8 (`node:wasi` for wasip1, bare
   `WebAssembly` for unknown-unknown). Bit-identity is a property of the module and
   would not change under a different runtime; **the wasm timings in §2 are V8 numbers
   and should not be quoted as "wasm" numbers** without re-measuring under wasmtime.
2. **The `libm` intrinsics fix is diagnosed but untested.** Building with libm's
   `unstable-intrinsics` feature requires nightly, and the installed nightly toolchain
   has no wasm targets (`rustup +nightly target list --installed` → `x86_64-unknown-linux-gnu`
   only). Adding them needs network access. The diagnosis in §2 is nonetheless
   established independently, by reproducing the slowdown on native with
   `force-soft-floats`.
3. **G/N was measured on synthetic structures, not on captured scenes.** No captured
   CIRIS scenes exist. §3 measures the two bracketing cases and the transition between
   them; it does **not** answer where real scenes fall, and §11.5 asks about real
   scenes. **The scaling claim remains unmade, and this document does not make it.**
4. **`wasm-opt` was not run** (Binaryen not installed), so §1.3's sizes are
   unpostprocessed linker output.
5. **Energy drift over 10⁴ steps (FSD §10.4 item 3) was not benchmarked here.** The
   crate's existing tests cover conservation (`energy_does_not_grow_without_damping`,
   `the_ledger_balances_across_absorption` at <5% over 3000 steps); a dedicated drift
   bench is not written.
6. **Timings are single-host, unpinned CPU, no frequency control.** Best-of-5 with a
   warmup, worst repetition reported alongside; spread was under 2% except on the
   harmonic step (10%). Treat as ±10%.
7. **Measured at `d6d15e1` on a clean extraction, not in the live working tree**, which
   was mid-edit and not compiling at the time (E10's `structure.rs` / `linalg.rs`). The
   benches and tools are committed to the working tree and will re-run there once it
   builds.
