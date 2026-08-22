//! Per-step cost of the integrator, and the zero-allocation claim — **measured, not
//! asserted**.
//!
//! Run: `cargo bench --bench step_cost`
//!
//! ## Scope, per FSD §10.1
//!
//! There is no comparison against any other engine here, and there must not be until
//! E10 (variable N) lands. The MVP is specialised to N=11 with compile-time tables, so
//! a win over an engine built for arbitrary lattices would measure the specialisation
//! and nothing else. §10's anti-hype clause is binding: *the benchmark counts only at
//! matched N with matched generality.* What follows is a self-measurement — a baseline
//! to regress against, not a score.
//!
//! ## How the zero-allocation claim is actually checked
//!
//! "The crate has no allocator, so it must be true" is an argument, not evidence, and
//! the interesting failure would be an allocation introduced by something the library
//! calls rather than by the library itself. So this bench installs a counting
//! `#[global_allocator]` and reads the counter immediately before and after each timed
//! region. A non-zero delta across a million steps is a failure the argument would not
//! have caught. Three independent lines of evidence are reported together:
//!
//! 1. **counted at runtime** — allocation/reallocation/free calls during the timed
//!    region, which must be exactly 0;
//! 2. **structural** — the crate is `#![no_std]` with no `extern crate alloc` and
//!    `libm` as its only dependency, so there is no allocator in the dependency graph
//!    to call (checked by `cargo tree`, see PORTABILITY.md);
//! 3. **on the wasm target** — the linear memory of the built module never grows across
//!    a long run, checked from the host (see PORTABILITY.md).
//!
//! Only (1) is measured *by this file*; (2) and (3) are commands recorded in
//! PORTABILITY.md so the claim is reproducible rather than taken on trust.

use ciris_sim_core::dynamics::{forces, run, step, Params, State};
use ciris_sim_core::field::Ledger;
use ciris_sim_core::gaps::{step_massive, RecordBoundary};
use ciris_sim_core::{K11, N};
use std::alloc::{GlobalAlloc, Layout, System};
use std::hint::black_box;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Instant;

// ------------------------------------------------------- the counting allocator

static ALLOCS: AtomicU64 = AtomicU64::new(0);

struct Counting;

unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, l: Layout) -> *mut u8 {
        ALLOCS.fetch_add(1, Ordering::Relaxed);
        unsafe { System.alloc(l) }
    }
    unsafe fn dealloc(&self, p: *mut u8, l: Layout) {
        ALLOCS.fetch_add(1, Ordering::Relaxed);
        unsafe { System.dealloc(p, l) }
    }
    unsafe fn realloc(&self, p: *mut u8, l: Layout, s: usize) -> *mut u8 {
        ALLOCS.fetch_add(1, Ordering::Relaxed);
        unsafe { System.realloc(p, l, s) }
    }
    unsafe fn alloc_zeroed(&self, l: Layout) -> *mut u8 {
        ALLOCS.fetch_add(1, Ordering::Relaxed);
        unsafe { System.alloc_zeroed(l) }
    }
}

#[global_allocator]
static A: Counting = Counting;

// ------------------------------------------------------------------ harness

/// Deterministic spread of the eleven kinds over the unit sphere. No RNG.
fn spiral() -> State<N> {
    let mut pos = [[0.0f64; 3]; N];
    let ga = 2.399963229728653_f64;
    for i in 0..N {
        let z = 1.0 - 2.0 * (i as f64 + 0.5) / (N as f64);
        let r = (1.0f64 - z * z).sqrt();
        let th = ga * (i as f64);
        pos[i] = [r * th.cos(), r * th.sin(), z];
    }
    State::at_rest(pos)
}

/// Run `f` for `iters` iterations over `reps` repetitions, reporting the **best**
/// repetition (least contaminated by scheduling), and the allocation count over the
/// whole timed region.
fn measure(label: &str, iters: u64, reps: usize, mut f: impl FnMut(u64)) {
    f(iters / 10); // warm up: caches, branch predictors, frequency
    let before = ALLOCS.load(Ordering::Relaxed);
    let mut best = f64::INFINITY;
    let mut worst: f64 = 0.0;
    for _ in 0..reps {
        let t0 = Instant::now();
        f(iters);
        let ns = t0.elapsed().as_secs_f64() * 1e9 / iters as f64;
        best = best.min(ns);
        worst = worst.max(ns);
    }
    let allocs = ALLOCS.load(Ordering::Relaxed) - before;
    let flag = if allocs == 0 { "0 alloc" } else { "!! ALLOCATED" };
    println!(
        "{label:<44} {best:>9.1} ns  (worst rep {worst:>8.1} ns)  {iters:>9} iters x {reps}  {allocs:>3} {flag}"
    );
}

fn main() {
    println!("step cost — ciris-sim-core, N = {N}");
    println!("command: cargo bench --bench step_cost");
    println!("scope: FSD §10.1 — NO cross-engine comparison until E10 (variable N) lands");
    println!("host: {} / rustc via cargo bench --release\n", std::env::consts::ARCH);
    println!(
        "{:<44} {:>12}  {:>24}  {:>11}  {}",
        "measurement", "ns per unit", "", "work", "allocations"
    );

    // --- the headline: one velocity-Verlet step of the K11 object.
    measure("step, harmonic (F = -Lx, symmetrised)", 200_000, 5, |n| {
        let p = Params::harmonic();
        let mut s = spiral();
        for _ in 0..n {
            step(&mut s, &K11, &p, true);
        }
        black_box(&s);
    });
    measure("step, default params, measured coupling", 200_000, 5, |n| {
        let p = Params::default();
        let mut s = spiral();
        for _ in 0..n {
            step(&mut s, &K11, &p, false);
        }
        black_box(&s);
    });
    measure("step_massive (E2 fill) - 1 force eval, see note", 200_000, 5, |n| {
        let p = Params::default();
        let mut s = spiral();
        for _ in 0..n {
            step_massive(&mut s, &K11, &p, false);
        }
        black_box(&s);
    });
    measure("run(1000) amortised per step", 200_000, 5, |n| {
        let p = Params::default();
        let mut s = spiral();
        let mut left = n;
        while left > 0 {
            let chunk = left.min(1000);
            run(&mut s, &K11, &p, false, chunk as usize);
            left -= chunk;
        }
        black_box(&s);
    });

    // --- the parts, so a regression can be located rather than just noticed.
    measure("forces() alone, harmonic", 400_000, 5, |n| {
        let p = Params::harmonic();
        let s = spiral();
        for _ in 0..n {
            black_box(forces(black_box(&s), &K11, &p, true));
        }
    });
    measure("forces() alone, default params", 400_000, 5, |n| {
        let p = Params::default();
        let s = spiral();
        for _ in 0..n {
            black_box(forces(black_box(&s), &K11, &p, false));
        }
    });

    // --- the E8 ledger path, which is a step plus two full energy evaluations.
    measure("Ledger::step_and_account (E8 bookkeeping)", 100_000, 5, |n| {
        let p = Params::default();
        let mut s = spiral();
        let mut b = RecordBoundary::new(50.0);
        let mut l = Ledger::default();
        for _ in 0..n {
            black_box(l.step_and_account(&mut s, &K11, &mut b, &p, false));
        }
    });

    // --- a long run, to catch an allocation that only happens once.
    println!();
    let before = ALLOCS.load(Ordering::Relaxed);
    let p = Params::harmonic();
    let mut s = spiral();
    let t0 = Instant::now();
    run(&mut s, &K11, &p, true, 1_000_000);
    let el = t0.elapsed();
    let allocs = ALLOCS.load(Ordering::Relaxed) - before;
    println!(
        "1,000,000 consecutive steps: {:.3} s total, {:.1} ns/step, {} allocator calls",
        el.as_secs_f64(),
        el.as_secs_f64() * 1e9 / 1e6,
        allocs
    );
    println!("state after 1e6 harmonic steps (finiteness check): pos[0][0] = {:e}", s.pos[0][0]);
    assert_eq!(allocs, 0, "the crate allocated during a 1e6-step run");

    println!("\nnote: `step` is velocity-Verlet and evaluates forces TWICE per step;");
    println!("      `step_massive` is semi-implicit Euler and evaluates them ONCE, which is the");
    println!("      whole of why it reads faster. It is a different integrator, not a cheaper mass.");
    println!("per-step arithmetic: N(N-1)/2 = {} pairs x 2 force evaluations = {} pair-terms/step",
        N * (N - 1) / 2, N * (N - 1));
    println!("State is {} bytes, Copy, stack-resident.", core::mem::size_of::<State<N>>());
}
