//! Deployment probe for `ciris-sim-core`. Not engine code — see `Cargo.toml` for why it
//! exists at all.
//!
//! Every exported function is pure and deterministic: given a scenario id and an index
//! it recomputes the whole scenario and returns one `f64`. That costs redundant work and
//! buys the thing that matters here — no shared mutable state, so no `unsafe`, and the
//! host can read the engine's output without knowing anything about its memory layout.
//!
//! The same functions are callable natively (this crate is also an `rlib`), which is how
//! `tools/portability_check.mjs` compares native and wasm results **bit for bit** rather
//! than to a tolerance.

#![no_std]
// NOT `forbid(unsafe_code)`, unlike the engine, and the reason is narrow: since Rust
// 1.82 `#[no_mangle]` is itself classified as an unsafe attribute, so a crate that
// exports a C ABI cannot forbid unsafe. There are still zero `unsafe` blocks here —
// `grep -c "unsafe" src/lib.rs` should find only this comment and the export attributes.
// The engine keeps its `#![forbid(unsafe_code)]` untouched.

use ciris_sim_core::dynamics::{run, Params, State};
use ciris_sim_core::{field, gaps, sectors, tables, twin_probe, K11, N};

#[cfg(feature = "verify")]
use ciris_sim_core::linalg::{jacobi_eigen, laplacian};
#[cfg(feature = "verify")]
use ciris_sim_core::structure::{Structure, NO_TWINS};
#[cfg(feature = "verify")]
use ciris_sim_core::{COUPLING, TWINS};

#[cfg(feature = "verify")]
/// The generic path is exercised at this size. `Structure<64>` is
/// `8*(8*64^2 + 3*64) + 72 = 263,752` bytes — a quarter of the 1 MiB wasm stack, which
/// the probe raises anyway (see `.cargo/config.toml` in this crate).
pub const BIG: usize = 64;

#[cfg(feature = "verify")]
/// A second, larger size for the eigensolver ALONE. A full `Structure<128>` is 1.05 MB
/// and would not fit the 1 MiB wasm stack, but `jacobi_eigen` needs only `2N^2` doubles
/// plus its `Eigen<N>` return — about 525 KB at N=128, which does fit. Included because
/// the sweep count is the quantity most at risk of diverging by target, and the risk
/// grows with N: more terms accumulate into `off_sq`, so more chances for the
/// `off_sq <= tol_sq` comparison to land differently.
pub const HUGE: usize = 128;

#[cfg(feature = "verify")]
/// SplitMix64. Integer-only, so the synthetic coupling below is bit-identical on every
/// target by construction and any divergence found downstream is the eigensolver's,
/// not the generator's.
struct Rng(u64);
#[cfg(feature = "verify")]
impl Rng {
    fn next_u64(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.0;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }
    fn unit2(&mut self) -> f64 {
        ((self.next_u64() >> 11) as f64) * (2.0 / 9_007_199_254_740_992.0)
    }
}

#[cfg(feature = "verify")]
/// A deterministic symmetric non-negative coupling with zero diagonal, at size `M`.
/// Dense and irregular on purpose: a structured matrix could converge in a sweep or two
/// and hide a divergence the general case would expose.
fn synth_coupling<const M: usize>() -> [[f64; M]; M] {
    let mut r = Rng(0x5EED_1234_ABCD_0001);
    let mut m = [[0.0f64; M]; M];
    let mut i = 0;
    while i < M {
        let mut j = i + 1;
        while j < M {
            let v = r.unit2();
            m[i][j] = v;
            m[j][i] = v;
            j += 1;
        }
        i += 1;
    }
    m
}

#[cfg(feature = "verify")]
/// FNV-1a over the RAW BIT PATTERNS of a slice of `f64`.
///
/// Used only where dumping every value would mean recomputing an `O(N^3)` eigensolve
/// per value. It is a bit-exact test, not a tolerance: it hashes `to_bits`, so any
/// single-ULP difference anywhere in the field changes the digest. It localises a
/// divergence to a field rather than to a cell — the N=11 scenario dumps every cell
/// individually and is where a cell-level answer comes from.
fn fnv_bits(acc: u64, xs: &[f64]) -> u64 {
    let mut h = acc;
    let mut i = 0;
    while i < xs.len() {
        let b = xs[i].to_bits();
        let mut k = 0;
        while k < 8 {
            h ^= (b >> (k * 8)) & 0xFF;
            h = h.wrapping_mul(0x0000_0100_0000_01B3);
            k += 1;
        }
        i += 1;
    }
    h
}

#[cfg(feature = "verify")]
fn fnv_mat<const M: usize>(acc: u64, m: &[[f64; M]; M]) -> u64 {
    let mut h = acc;
    let mut i = 0;
    while i < M {
        h = fnv_bits(h, &m[i]);
        i += 1;
    }
    h
}

#[cfg(feature = "verify")]
const FNV_OFFSET: u64 = 0xcbf2_9ce4_8422_2325;

/// Golden-angle spiral on the unit sphere. Deterministic, no RNG, no `std`.
fn spiral() -> State<N> {
    let mut pos = [[0.0f64; 3]; N];
    let ga = 2.399963229728653_f64; // pi*(3 - sqrt 5)
    let mut i = 0;
    while i < N {
        let z = 1.0 - 2.0 * (i as f64 + 0.5) / (N as f64);
        let r = libm::sqrt(1.0 - z * z);
        let th = ga * (i as f64);
        pos[i] = [r * libm::cos(th), r * libm::sin(th), z];
        i += 1;
    }
    State::at_rest(pos)
}

/// Number of `f64`s scenario `s` produces.
pub fn scenario_len(s: u32) -> u32 {
    match s {
        0 | 1 => 6 * N as u32, // 11 positions + 11 velocities, 3 components each
        2 => 32,               // sealed tables and derived scalars
        #[cfg(feature = "verify")]
        3 => 885, // Structure::<11>::from_coupling, every cell
        #[cfg(feature = "verify")]
        4 => BIG as u32, // Structure::<64>::from_coupling, the spectrum
        _ => 0,
    }
}

#[cfg(feature = "verify")]
/// `Structure::<11>::from_coupling` — the RUNTIME path, not the sealed `K11` table.
/// Index layout, all raw `f64`:
/// `0..121` coupling_sym · `121..242` metric · `242..363` eigenvectors ·
/// `363..847` sector_projectors (4 x 121) · `847..858` eigenvalues ·
/// `858..869` mass · `869..880` susceptibility · `880..884` sector_dims ·
/// `884` spectrum_converged.
fn structure11_value(i: u32) -> f64 {
    let st = Structure::<N>::from_coupling(&COUPLING, TWINS);
    let i = i as usize;
    let cell = |m: &[[f64; N]; N], o: usize| m[o / N][o % N];
    match i {
        0..=120 => cell(&st.coupling_sym, i),
        121..=241 => cell(&st.metric, i - 121),
        242..=362 => cell(&st.eigenvectors, i - 242),
        363..=846 => {
            let o = i - 363;
            st.sector_projectors[o / (N * N)][(o % (N * N)) / N][o % N]
        }
        847..=857 => st.eigenvalues[i - 847],
        858..=868 => st.mass[i - 858],
        869..=879 => st.susceptibility[i - 869],
        880..=883 => st.sector_dims[i - 880] as f64,
        884 => {
            if st.spectrum_converged {
                1.0
            } else {
                0.0
            }
        }
        _ => 0.0,
    }
}

/// The `i`-th `f64` of scenario `s`. Pure; recomputes from scratch every call.
///
/// * `0` — 1000 harmonic steps under the symmetrised coupling. This is the regime the
///   twin theorem lives in, so it is the one whose reproducibility matters most.
/// * `1` — 1000 steps under `Params::default` and the measured coupling: nonlinear
///   springs, softened repulsion, damping. The arithmetic-heavy path.
/// * `2` — derived scalars and sealed table entries, to catch a constant that survived
///   the build differently rather than a trajectory that diverged.
pub fn scenario_value(s: u32, i: u32) -> f64 {
    match s {
        0 | 1 => {
            let (params, sym) = if s == 0 {
                (Params::harmonic(), true)
            } else {
                (Params::default(), false)
            };
            let mut st = spiral();
            run(&mut st, &K11, &params, sym, 1000);
            let i = i as usize;
            let node = (i / 3) % N;
            let comp = i % 3;
            if i < 3 * N {
                st.pos[node][comp]
            } else {
                st.vel[node][comp]
            }
        }
        #[cfg(feature = "verify")]
        3 => structure11_value(i),
        #[cfg(feature = "verify")]
        4 => {
            let c = synth_coupling::<BIG>();
            let st = Structure::<BIG>::from_coupling(&c, NO_TWINS);
            st.eigenvalues[i as usize % BIG]
        }
        2 => match i {
            0..=10 => tables::LAPLACIAN_EIGENVALUES[i as usize],
            11..=21 => tables::MASS[(i - 11) as usize],
            22 => tables::METRIC[0][7],
            23 => tables::TIME_UNIT,
            24 => gaps::stiffness_ratio(&K11),
            25 => gaps::suggested_dt(&K11, 0.1),
            26 => twin_probe::g_db(&K11, &K11.coupling, 0),
            27 => twin_probe::g_db(&K11, &K11.coupling, 1),
            28 => twin_probe::probe(&K11, 0, 1.0, true).max_other_displacement,
            29 => twin_probe::probe(&K11, 0, 1.0, false).leakage,
            30 => sectors::inter_sector_leakage(&K11, &K11.coupling_sym),
            31 => field::reduction_ratio(&K11, 0.5),
            _ => 0.0,
        },
        _ => 0.0,
    }
}

#[cfg(feature = "verify")]
/// FNV-1a digest of one derived field, over raw bit patterns.
///
/// `which`: 0 coupling · 1 coupling_sym · 2 metric · 3 eigenvectors ·
/// 4 sector_projectors · 5 mass · 6 susceptibility · 7 eigenvalues ·
/// 8 sector_dims+converged. `n_sel`: 0 = the N=11 runtime structure, 1 = N=64.
pub fn field_digest(n_sel: u32, which: u32) -> u64 {
    if n_sel == 0 {
        let st = Structure::<N>::from_coupling(&COUPLING, TWINS);
        digest_of::<N>(&st, which)
    } else {
        let c = synth_coupling::<BIG>();
        let st = Structure::<BIG>::from_coupling(&c, NO_TWINS);
        digest_of::<BIG>(&st, which)
    }
}

#[cfg(feature = "verify")]
fn digest_of<const M: usize>(st: &Structure<M>, which: u32) -> u64 {
    match which {
        0 => fnv_mat(FNV_OFFSET, &st.coupling),
        1 => fnv_mat(FNV_OFFSET, &st.coupling_sym),
        2 => fnv_mat(FNV_OFFSET, &st.metric),
        3 => fnv_mat(FNV_OFFSET, &st.eigenvectors),
        4 => {
            let mut h = FNV_OFFSET;
            let mut k = 0;
            while k < st.sector_projectors.len() {
                h = fnv_mat(h, &st.sector_projectors[k]);
                k += 1;
            }
            h
        }
        5 => fnv_bits(FNV_OFFSET, &st.mass),
        6 => fnv_bits(FNV_OFFSET, &st.susceptibility),
        7 => fnv_bits(FNV_OFFSET, &st.eigenvalues),
        8 => {
            let mut h = FNV_OFFSET;
            let mut k = 0;
            while k < st.sector_dims.len() {
                h = fnv_bits(h, &[st.sector_dims[k] as f64]);
                k += 1;
            }
            fnv_bits(h, &[if st.spectrum_converged { 1.0 } else { 0.0 }])
        }
        _ => 0,
    }
}

#[cfg(feature = "verify")]
/// Sweeps performed by `jacobi_eigen` on the Laplacian of the given case.
///
/// **This integer is the sharp test.** The sweep loop exits on `off_sq <= tol_sq`, a
/// floating-point comparison, so a single-ULP difference in the accumulated
/// off-diagonal norm would change the iteration count and cascade into every derived
/// table. If this number differs by target, the engine is not deterministic and no
/// amount of agreement elsewhere repairs it.
///
/// `n_sel`: 0 = K11's measured coupling (N=11), 1 = synthetic N=64, 2 = synthetic N=128.
pub fn jacobi_sweeps(n_sel: u32) -> u32 {
    match n_sel {
        0 => jacobi_eigen(&laplacian(&COUPLING)).sweeps as u32,
        1 => jacobi_eigen(&laplacian(&synth_coupling::<BIG>())).sweeps as u32,
        _ => jacobi_eigen(&laplacian(&synth_coupling::<HUGE>())).sweeps as u32,
    }
}

#[cfg(feature = "verify")]
/// Whether that eigensolve reported convergence. Same cases as [`jacobi_sweeps`].
pub fn jacobi_converged(n_sel: u32) -> u32 {
    let c = match n_sel {
        0 => jacobi_eigen(&laplacian(&COUPLING)).converged,
        1 => jacobi_eigen(&laplacian(&synth_coupling::<BIG>())).converged,
        _ => jacobi_eigen(&laplacian(&synth_coupling::<HUGE>())).converged,
    };
    c as u32
}

// ------------------------------------------------- the knife-edge determinism test

#[cfg(feature = "verify")]
/// The two endpoints of the interpolation used by [`sweep_boundary_bits`]: seed A
/// converges in 7 sweeps and seed B in 8, at `N = BIG`.
const SEED_A: u64 = 0xABCD_0000 ^ 25;
#[cfg(feature = "verify")]
const SEED_B: u64 = 0xABCD_0000 ^ 26;

#[cfg(feature = "verify")]
fn synth_seeded<const M: usize>(seed: u64) -> [[f64; M]; M] {
    let mut r = Rng(seed);
    let mut m = [[0.0f64; M]; M];
    let mut i = 0;
    while i < M {
        let mut j = i + 1;
        while j < M {
            let v = r.unit2();
            m[i][j] = v;
            m[j][i] = v;
            j += 1;
        }
        i += 1;
    }
    m
}

#[cfg(feature = "verify")]
/// Sweeps for the coupling `(1-t)*A + t*B`, the family that crosses the 7/8 boundary.
pub fn sweeps_at(t: f64) -> u32 {
    let a = synth_seeded::<BIG>(SEED_A);
    let b = synth_seeded::<BIG>(SEED_B);
    let mut m = [[0.0f64; BIG]; BIG];
    let mut i = 0;
    while i < BIG {
        let mut j = 0;
        while j < BIG {
            m[i][j] = (1.0 - t) * a[i][j] + t * b[i][j];
            j += 1;
        }
        i += 1;
    }
    jacobi_eigen(&laplacian(&m)).sweeps as u32
}

#[cfg(feature = "verify")]
/// **The sharpest determinism test this probe can construct.**
///
/// A single-ULP change to an input does NOT move the sweep count — the convergence
/// margin is many orders of magnitude, so `sweeps` is normally a blunt instrument. But
/// the count IS input-dependent (7 for some couplings, 8 for most), so somewhere in a
/// continuous family between a 7-sweep and an 8-sweep coupling there is a boundary
/// where ONE ULP of the interpolation parameter changes the iteration count.
///
/// This bisects to that boundary and returns it as a raw bit pattern. If two targets
/// report the same boundary — and the same counts on either side of it — then they
/// agree on a branch decision that a single ULP does control, which is the divergence
/// this whole check exists to find.
pub fn sweep_boundary_bits() -> u64 {
    let (mut lo, mut hi) = (0.0f64, 1.0f64);
    let s_lo = sweeps_at(lo);
    let mut guard = 0;
    // Bisect on the bit pattern, not the value, so it terminates at adjacent doubles.
    while guard < 64 {
        let mid_bits = (lo.to_bits() + hi.to_bits()) / 2;
        let mid = f64::from_bits(mid_bits);
        if mid_bits == lo.to_bits() || mid_bits == hi.to_bits() {
            break;
        }
        if sweeps_at(mid) == s_lo {
            lo = mid;
        } else {
            hi = mid;
        }
        guard += 1;
    }
    hi.to_bits()
}

#[cfg(feature = "verify")]
/// FNV-1a digest of the eigensolve itself, bypassing `Structure` so the largest case
/// fits the stack. `which`: 0 = eigenvalues, 1 = eigenvectors. Same `n_sel` cases as
/// [`jacobi_sweeps`].
pub fn eigensolve_digest(n_sel: u32, which: u32) -> u64 {
    match n_sel {
        0 => {
            let e = jacobi_eigen(&laplacian(&COUPLING));
            if which == 0 { fnv_bits(FNV_OFFSET, &e.values) } else { fnv_mat(FNV_OFFSET, &e.vectors) }
        }
        1 => {
            let e = jacobi_eigen(&laplacian(&synth_coupling::<BIG>()));
            if which == 0 { fnv_bits(FNV_OFFSET, &e.values) } else { fnv_mat(FNV_OFFSET, &e.vectors) }
        }
        _ => {
            let e = jacobi_eigen(&laplacian(&synth_coupling::<HUGE>()));
            if which == 0 { fnv_bits(FNV_OFFSET, &e.values) } else { fnv_mat(FNV_OFFSET, &e.vectors) }
        }
    }
}

/// Class count from `field::coarsen` at `tolerance` — exported so the host can confirm
/// E7's coarsening reads the same on every target, integers included.
pub fn coarsen_classes(tolerance: f64) -> u32 {
    let (_, c) = field::coarsen(&K11, tolerance);
    c as u32
}

// ---------------------------------------------------------------- C ABI for wasm

#[no_mangle]
pub extern "C" fn probe_scenario_len(s: u32) -> u32 {
    scenario_len(s)
}

#[no_mangle]
pub extern "C" fn probe_scenario_value(s: u32, i: u32) -> f64 {
    scenario_value(s, i)
}

#[no_mangle]
pub extern "C" fn probe_coarsen_classes(tolerance: f64) -> u32 {
    coarsen_classes(tolerance)
}

#[cfg(feature = "verify")]
#[no_mangle]
pub extern "C" fn probe_field_digest(n_sel: u32, which: u32) -> u64 {
    field_digest(n_sel, which)
}

#[cfg(feature = "verify")]
#[no_mangle]
pub extern "C" fn probe_jacobi_sweeps(n_sel: u32) -> u32 {
    jacobi_sweeps(n_sel)
}

#[cfg(feature = "verify")]
#[no_mangle]
pub extern "C" fn probe_jacobi_converged(n_sel: u32) -> u32 {
    jacobi_converged(n_sel)
}

#[cfg(feature = "verify")]
#[no_mangle]
pub extern "C" fn probe_eigensolve_digest(n_sel: u32, which: u32) -> u64 {
    eigensolve_digest(n_sel, which)
}

#[cfg(feature = "verify")]
#[no_mangle]
pub extern "C" fn probe_sweep_boundary_bits() -> u64 {
    sweep_boundary_bits()
}

#[cfg(feature = "verify")]
#[no_mangle]
pub extern "C" fn probe_sweeps_at_bits(t_bits: u64) -> u32 {
    sweeps_at(f64::from_bits(t_bits))
}

/// Wall-clock-free step counter for the host-side timing harness: runs `n` harmonic
/// steps and returns one component of the final state, so the optimiser cannot delete
/// the loop.
#[no_mangle]
pub extern "C" fn probe_run(n: u32, symmetrised: u32) -> f64 {
    let p = if symmetrised == 1 {
        Params::harmonic()
    } else {
        Params::default()
    };
    let mut st = spiral();
    run(&mut st, &K11, &p, symmetrised == 1, n as usize);
    st.pos[0][0] + st.vel[N - 1][2]
}

/// A `no_std` cdylib must supply one. Trapping via `core::arch::wasm32::unreachable`
/// would need an `unsafe` block, so this spins instead — acceptable only because every
/// exported function above is total: each index is bounded by the `match` arm that
/// produced it, and the engine allocates nothing that could fail.
#[cfg(target_family = "wasm")]
#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! {
    loop {}
}
