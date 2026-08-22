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
use ciris_sim_core::{field, gaps, sectors, tables, twin_probe, N};

/// Golden-angle spiral on the unit sphere. Deterministic, no RNG, no `std`.
fn spiral() -> State {
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
        _ => 0,
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
            run(&mut st, &params, sym, 1000);
            let i = i as usize;
            let node = (i / 3) % N;
            let comp = i % 3;
            if i < 3 * N {
                st.pos[node][comp]
            } else {
                st.vel[node][comp]
            }
        }
        2 => match i {
            0..=10 => tables::LAPLACIAN_EIGENVALUES[i as usize],
            11..=21 => tables::MASS[(i - 11) as usize],
            22 => tables::METRIC[0][7],
            23 => tables::TIME_UNIT,
            24 => gaps::stiffness_ratio(),
            25 => gaps::suggested_dt(0.1),
            26 => twin_probe::g_db(&ciris_sim_core::COUPLING, 0),
            27 => twin_probe::g_db(&ciris_sim_core::COUPLING, 1),
            28 => twin_probe::probe(0, 1.0, true).max_other_displacement,
            29 => twin_probe::probe(0, 1.0, false).leakage,
            30 => sectors::inter_sector_leakage(&tables::COUPLING_SYM),
            31 => field::reduction_ratio(0.5),
            _ => 0.0,
        },
        _ => 0.0,
    }
}

/// Class count from `field::coarsen` at `tolerance` — exported so the host can confirm
/// E7's coarsening reads the same on every target, integers included.
pub fn coarsen_classes(tolerance: f64) -> u32 {
    let (_, c) = field::coarsen(tolerance);
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
    run(&mut st, &p, symmetrised == 1, n as usize);
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
