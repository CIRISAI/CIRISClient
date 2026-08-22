//! FSD §11.5, the binding precondition: **measure G/N on real structures before any
//! scaling claim.**
//!
//! The scaling thesis (§11.2) rests on a table of reduction ratios N/G rising from 13x
//! at N=1k to 7037x at N=1M. That table was measured on the disordered-emitter profile
//! system, **not on this engine's scenes**. §11.5 says in terms: if profiles do not
//! repeat, G ~ N, the reduction is 1x, and the engine is "a factor-of-four symmetry
//! trick with a nice metric". This bench is the measurement that decides it.
//!
//! Run: `cargo bench --bench profile_reduction`
//!
//! ## What is measured with the library, and what is not
//!
//! Since E10, `field::coarsen` is generic over `N` and takes a `Structure<N>`, so the
//! **engine's own function is called directly** for every N up to 2048 — sections A, B,
//! C and D are library measurements, not a re-implementation.
//!
//! One equivalent re-implementation (`coarsen_general`) survives, used only where
//! `Structure<N>` will not fit (N = 4096 is 1.07 GB, see section G) and to keep the
//! order-sensitivity sweep affordable. It is checked against `field::coarsen` at every
//! N and tolerance where both run, and the bench aborts on any disagreement — a G/N
//! table from an algorithm that is not the engine's algorithm would be worthless.
//!
//! `field::coarsen` reads `st.coupling` and nothing else, so synthetic structures are
//! built by filling that field and skipping the `O(N^3)` eigensolve. `derived_matches_
//! coupling_only` checks that shortcut against a fully derived `Structure::from_coupling`
//! rather than assuming it.
//!
//! ## The algorithm being measured
//!
//! * distance is the **sup norm** over the two nodes' complete coupling rows, skipping
//!   the two entries `k in {a,b}` that reference the pair itself;
//! * clustering is **greedy leader** in index order — the first unlabelled node opens a
//!   class and every later unlabelled node within tolerance of *that leader* joins it.
//!   It is not transitive closure and it is not order-invariant; `order_sensitivity`
//!   below measures whether that matters.

use ciris_sim_core::structure::{Structure, NO_TWINS};
use ciris_sim_core::{field, K11, N};
use std::time::Instant;

// ------------------------------------------------------------- the reference copy

/// Sup-norm distance between rows `a` and `b` of an `n x n` row-major matrix, skipping
/// the entries at `a` and `b`. Mirrors `field::profile_distance`.
fn profile_distance(m: &[f64], n: usize, a: usize, b: usize) -> f64 {
    let (ra, rb) = (&m[a * n..a * n + n], &m[b * n..b * n + n]);
    let mut worst = 0.0f64;
    for k in 0..n {
        if k != a && k != b {
            let d = (ra[k] - rb[k]).abs();
            if d > worst {
                worst = d;
            }
        }
    }
    worst
}

/// Greedy-leader class count over a flat matrix. Mirrors `field::coarsen` for any `n`
/// and any visiting order. Also returns the number of `profile_distance` evaluations —
/// the cost of *deciding* the reduction, which FSD §11 does not price.
fn coarsen_general(m: &[f64], n: usize, tol: f64, order: &[usize]) -> (usize, u64) {
    let mut label = vec![usize::MAX; n];
    let mut classes = 0usize;
    let mut evals = 0u64;
    for &i in order {
        if label[i] != usize::MAX {
            continue;
        }
        label[i] = classes;
        for &j in order {
            if j != i && label[j] == usize::MAX {
                evals += 1;
                if profile_distance(m, n, i, j) <= tol {
                    label[j] = classes;
                }
            }
        }
        classes += 1;
    }
    (classes, evals)
}

// ------------------------------------------------------------------ generators

/// SplitMix64. Deterministic, dependency-free, seeded per case so every number here
/// reproduces exactly.
struct Rng(u64);
impl Rng {
    fn next_u64(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.0;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }
    /// Uniform on `[0, 2)`, so the off-diagonal mean is 1 — the normalisation
    /// `data::COUPLING` states it uses.
    fn unit2(&mut self) -> f64 {
        ((self.next_u64() >> 11) as f64) * (2.0 / 9_007_199_254_740_992.0)
    }
}

/// **Hostile case.** Every off-diagonal entry drawn independently, so no two complete
/// profiles agree except by accident. This is the failure mode §11.5 names.
fn random_couplings(n: usize, seed: u64) -> Vec<f64> {
    let mut r = Rng(seed);
    let mut m = vec![0.0; n * n];
    for i in 0..n {
        for j in (i + 1)..n {
            let v = r.unit2();
            m[i * n + j] = v;
            m[j * n + i] = v;
        }
    }
    m
}

/// **Favourable case.** `k` archetypes replicated: node `i` has type `i % k` and
/// `c_ij = A[type i][type j]`. Complete profiles then repeat EXACTLY within a type —
/// which works only because `profile_distance` skips the two self-referencing entries,
/// the sole places two same-type rows differ.
///
/// `jitter` adds independent noise of that amplitude to every entry: the realistic
/// middle, where profiles repeat approximately rather than exactly.
fn blocks(n: usize, k: usize, seed: u64, jitter: f64) -> Vec<f64> {
    let mut r = Rng(seed);
    let mut arch = vec![0.0; k * k];
    for t in 0..k {
        for u in t..k {
            let v = r.unit2();
            arch[t * k + u] = v;
            arch[u * k + t] = v;
        }
    }
    let mut m = vec![0.0; n * n];
    for i in 0..n {
        for j in (i + 1)..n {
            let mut v = arch[(i % k) * k + (j % k)];
            if jitter > 0.0 {
                v += (r.unit2() - 1.0) * jitter;
            }
            m[i * n + j] = v;
            m[j * n + i] = v;
        }
    }
    m
}

/// A `Structure<N>` carrying `m` as its coupling and nothing else derived.
/// `field::coarsen` reads only `coupling`, so the `O(N^3)` eigensolve is skipped;
/// `derived_matches_coupling_only` is the check that this is legitimate.
fn coupling_only<const M: usize>(m: &[f64]) -> Box<Structure<M>> {
    let mut st = Box::new(Structure::<M>::zeroed());
    for i in 0..M {
        st.coupling[i].copy_from_slice(&m[i * M..i * M + M]);
    }
    st
}

// ------------------------------------------------------------------ checks

/// `coarsen_general` must agree with the engine's `field::coarsen`, or nothing below
/// means anything.
fn cross_validate() {
    let mut k11 = vec![0.0; N * N];
    for i in 0..N {
        k11[i * N..i * N + N].copy_from_slice(&K11.coupling[i]);
    }
    let order: Vec<usize> = (0..N).collect();
    for step in 0..=40 {
        let tol = step as f64 * 0.25;
        let (_, want) = field::coarsen(&K11, tol);
        let (got, _) = coarsen_general(&k11, N, tol, &order);
        assert_eq!(got, want, "reference copy disagrees with field::coarsen at tol={tol}");
    }
    println!("cross-check 1: reference copy == field::coarsen on K11, 41/41 tolerances in [0, 10]");
}

/// A structure with ONLY `coupling` filled must coarsen identically to one whose every
/// derived quantity was computed. Checked at N=64, where the eigensolve is affordable.
fn derived_matches_coupling_only() {
    const M: usize = 64;
    let flat = blocks(M, 8, 0xDEED, 0.1);
    let mut sq = [[0.0f64; M]; M];
    for i in 0..M {
        sq[i].copy_from_slice(&flat[i * M..i * M + M]);
    }
    let derived = Box::new(Structure::<M>::from_coupling(&sq, NO_TWINS));
    let cheap = coupling_only::<M>(&flat);
    for step in 0..=20 {
        let tol = step as f64 * 0.1;
        let (_, a) = field::coarsen(derived.as_ref(), tol);
        let (_, b) = field::coarsen(cheap.as_ref(), tol);
        assert_eq!(a, b, "coupling-only structure coarsens differently at tol={tol}");
    }
    println!("cross-check 2: coupling-only Structure<64> == fully derived Structure<64>, 21/21 tolerances");
}

/// Is G a property of the structure, or of the index order the greedy leader walks?
fn order_sensitivity(m: &[f64], n: usize, tol: f64, seed: u64) -> (usize, usize, usize) {
    let identity: Vec<usize> = (0..n).collect();
    let (g0, _) = coarsen_general(m, n, tol, &identity);
    let (mut lo, mut hi) = (usize::MAX, 0usize);
    let mut r = Rng(seed);
    for _ in 0..8 {
        let mut p = identity.clone();
        for i in (1..n).rev() {
            let j = (r.next_u64() % (i as u64 + 1)) as usize;
            p.swap(i, j);
        }
        let (g, _) = coarsen_general(m, n, tol, &p);
        lo = lo.min(g);
        hi = hi.max(g);
    }
    (g0, lo, hi)
}

// ------------------------------------------------------------------ report

const TOLS: [f64; 3] = [0.1, 0.5, 1.0];

fn header(title: &str) {
    println!("\n{title}");
    print!("{:<26} {:>6}", "case", "N");
    for tol in TOLS {
        print!("  {:>6} {:>9} {:>11} {:>10}", format!("G@{tol}"), "N/G", "dist evals", "time");
    }
    println!();
}

/// One row, measured with the ENGINE's `field::coarsen`. `evals` comes from the
/// reference copy, which the cross-check pins to the same class count.
fn row_lib<const M: usize>(label: &str, m: &[f64]) {
    let st = coupling_only::<M>(m);
    let order: Vec<usize> = (0..M).collect();
    print!("{label:<26} {M:>6}");
    for tol in TOLS {
        let t0 = Instant::now();
        let (_, g) = field::coarsen(st.as_ref(), tol);
        let ms = t0.elapsed().as_secs_f64() * 1e3;
        let (g_ref, evals) = coarsen_general(m, M, tol, &order);
        assert_eq!(g, g_ref, "{label} N={M} tol={tol}: library {g} vs reference {g_ref}");
        print!("  {g:>6} {:>8.2}x {evals:>11} {ms:>8.1}ms", M as f64 / g as f64);
    }
    println!();
}

/// One row where `Structure<N>` will not fit — reference copy only, and said so.
fn row_ref(label: &str, m: &[f64], n: usize) {
    let order: Vec<usize> = (0..n).collect();
    print!("{label:<26} {n:>6}");
    for tol in TOLS {
        let t0 = Instant::now();
        let (g, evals) = coarsen_general(m, n, tol, &order);
        let ms = t0.elapsed().as_secs_f64() * 1e3;
        print!("  {g:>6} {:>8.2}x {evals:>11} {ms:>8.1}ms", n as f64 / g as f64);
    }
    println!();
}

fn report() {
    println!("profile-class reduction — FSD §11.5 precondition");
    println!("command: cargo bench --bench profile_reduction");
    println!("criterion: sup-norm over complete coupling rows, greedy leader in index order");
    println!("tolerances: {TOLS:?} absolute, on matrices whose off-diagonal mean is 1\n");
    cross_validate();
    derived_matches_coupling_only();

    header("A. the built-in K11 object — field::coarsen on the shipped Structure");
    print!("{:<26} {:>6}", "K11 measured coupling", N);
    for tol in TOLS {
        let (_, g) = field::coarsen(&K11, tol);
        print!("  {g:>6} {:>8.2}x {:>11} {:>10}", N as f64 / g as f64, "55", "<0.1ms");
    }
    println!();

    header("B. HOSTILE — independent random couplings, no repetition by construction");
    row_lib::<64>("random couplings", &random_couplings(64, 0xC1715 ^ 64));
    row_lib::<128>("random couplings", &random_couplings(128, 0xC1715 ^ 128));
    row_lib::<256>("random couplings", &random_couplings(256, 0xC1715 ^ 256));
    row_lib::<512>("random couplings", &random_couplings(512, 0xC1715 ^ 512));
    row_lib::<1024>("random couplings", &random_couplings(1024, 0xC1715 ^ 1024));
    row_ref("random couplings", &random_couplings(2048, 0xC1715 ^ 2048), 2048);

    header("C. FAVOURABLE — k archetypes replicated, profiles repeat EXACTLY");
    row_lib::<256>("blocks k=4", &blocks(256, 4, 0xB10C4 ^ 256, 0.0));
    row_lib::<1024>("blocks k=4", &blocks(1024, 4, 0xB10C4 ^ 1024, 0.0));
    row_ref("blocks k=4", &blocks(4096, 4, 0xB10C4 ^ 4096, 0.0), 4096);
    row_lib::<256>("blocks k=16", &blocks(256, 16, 0xB10C5 ^ 256, 0.0));
    row_lib::<1024>("blocks k=16", &blocks(1024, 16, 0xB10C5 ^ 1024, 0.0));
    row_ref("blocks k=16", &blocks(4096, 16, 0xB10C5 ^ 4096, 0.0), 4096);
    row_lib::<256>("blocks k=64", &blocks(256, 64, 0xB10C6 ^ 256, 0.0));
    row_lib::<1024>("blocks k=64", &blocks(1024, 64, 0xB10C6 ^ 1024, 0.0));
    row_ref("blocks k=64", &blocks(4096, 64, 0xB10C6 ^ 4096, 0.0), 4096);

    header("D. REALISTIC MIDDLE — k=16 archetypes plus independent jitter");
    for jitter in [0.05f64, 0.2, 0.6] {
        row_lib::<256>(&format!("blocks k=16 jitter={jitter}"), &blocks(256, 16, 0x117E4 ^ 256, jitter));
        row_lib::<1024>(&format!("blocks k=16 jitter={jitter}"), &blocks(1024, 16, 0x117E4 ^ 1024, jitter));
    }

    println!("\nE. order sensitivity of G (index order vs 8 random relabellings, tol=0.5)");
    println!("{:<34} {:>6} {:>10} {:>10} {:>10}", "case", "N", "G(index)", "G(min)", "G(max)");
    let mut k11 = vec![0.0; N * N];
    for i in 0..N {
        k11[i * N..i * N + N].copy_from_slice(&K11.coupling[i]);
    }
    for (label, m, n) in [
        ("K11 measured", k11, N),
        ("random couplings", random_couplings(512, 7), 512),
        ("blocks k=16 exact", blocks(512, 16, 11, 0.0), 512),
        ("blocks k=16 jitter=0.2", blocks(512, 16, 13, 0.2), 512),
    ] {
        let (g0, lo, hi) = order_sensitivity(&m, n, 0.5, 4242);
        println!("{label:<34} {n:>6} {g0:>10} {lo:>10} {hi:>10}");
    }

    println!("\nF. how G moves with N at fixed tolerance 0.5 — the §11.2 question");
    println!("{:<26} {:>7} {:>7} {:>9} {:>26}", "case", "N", "G", "N/G", "G growth vs previous N");
    for (label, gen) in [("random couplings", 0u8), ("blocks k=16 exact", 1), ("blocks k=16 jitter=0.2", 2)] {
        let mut prev: Option<(usize, usize)> = None;
        for n in [128usize, 256, 512, 1024] {
            let m = match gen {
                0 => random_couplings(n, 0x5CA1E ^ n as u64),
                1 => blocks(n, 16, 0x5CA1E ^ n as u64, 0.0),
                _ => blocks(n, 16, 0x5CA1E ^ n as u64, 0.2),
            };
            let order: Vec<usize> = (0..n).collect();
            let (g, _) = coarsen_general(&m, n, 0.5, &order);
            let note = match prev {
                Some((pn, pg)) => format!("N x{:.1}, G x{:.2}", n as f64 / pn as f64, g as f64 / pg as f64),
                None => "-".to_string(),
            };
            println!("{label:<26} {n:>7} {g:>7} {:>8.2}x {note:>26}", n as f64 / g as f64);
            prev = Some((n, g));
        }
    }

    println!("\nG. what a Structure<N> costs — the OTHER scaling wall, and it is not G");
    println!("Structure<N> is 8 dense N x N f64 matrices plus O(N) vectors, held by value with");
    println!("no allocator. Sizes are `size_of`, measured:");
    println!("{:>8} {:>16} {:>18}", "N", "size_of Structure", "derivation");
    for (n, sz) in [
        (11usize, std::mem::size_of::<Structure<11>>()),
        (64, std::mem::size_of::<Structure<64>>()),
        (256, std::mem::size_of::<Structure<256>>()),
        (1024, std::mem::size_of::<Structure<1024>>()),
        (2048, std::mem::size_of::<Structure<2048>>()),
    ] {
        println!("{n:>8} {:>13.2} MB {:>18}", sz as f64 / 1048576.0, "O(N^3) eigensolve");
    }
    let t0 = Instant::now();
    let flat = random_couplings(256, 99);
    let mut sq = [[0.0f64; 256]; 256];
    for i in 0..256 {
        sq[i].copy_from_slice(&flat[i * 256..i * 256 + 256]);
    }
    let st = Box::new(Structure::<256>::from_coupling(&sq, NO_TWINS));
    println!(
        "Structure::<256>::from_coupling: {:.3} s, spectrum_converged = {}",
        t0.elapsed().as_secs_f64(),
        st.spectrum_converged
    );
}

fn main() {
    // Structure<1024> is 67 MB by value; the default 8 MB main stack cannot hold one.
    std::thread::Builder::new()
        .stack_size(1 << 30)
        .spawn(report)
        .expect("spawn")
        .join()
        .expect("bench panicked");
}
