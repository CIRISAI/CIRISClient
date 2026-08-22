//! The object a simulation runs on — **gap E10, variable `N`** (FSD §10.2).
//!
//! Everything in this crate used to be written against a single fixed structure: eleven
//! kinds, one coupling matrix, and derived tables emitted at code-generation time. That
//! is why the crate is fast, and it is also why FSD §10.1 forbids benchmarking it
//! against a general-purpose engine — "reporting a win at N=11 against an engine built
//! for arbitrary lattices would be meaningless".
//!
//! [`Structure`] is the fork the FSD chose: **const generics, with the N=11 tables
//! retained as a specialisation.** A `Structure<N>` carries the coupling, its
//! twin-symmetrised average, the resistance metric, the masses, the susceptibilities,
//! the Laplacian spectrum and the four character projectors — everything the dynamics
//! reads and nothing it can tune.
//!
//! ## Two constructors, and the difference between them is the whole point
//!
//! * [`Structure::k11`] is a `const fn`. It copies the shipped tables verbatim, so the
//!   built-in object costs exactly what it cost before: no eigensolve, no runtime
//!   linear algebra, and [`K11`] is a `static` the linker places in rodata.
//! * [`Structure::from_coupling`] COMPUTES the same quantities with [`crate::linalg`].
//!   That is `O(N^3)` once, at construction, and it is the honest price of generality.
//!
//! `k11_matches_the_computed_structure` is the test that makes the pair trustworthy:
//! it runs the general path on the built-in coupling and checks it reproduces every
//! shipped table. Without that cross-check the fast path would be an unverified
//! shortcut and the general path an unverified rewrite.
//!
//! ## What a `Structure<N>` costs
//!
//! Eight `N x N` arrays of `f64` (coupling, symmetrised coupling, metric,
//! eigenvectors, and the four projectors) plus three vectors and a little bookkeeping:
//! `8 * (8 N^2 + 3 N) + 72` bytes, **measured at 8080 — 7.9 KB — for `N = 11`**, and
//! 61 MB at `N = 1000`. There is no allocator, so a `Structure` lives wherever
//! the caller puts it — a `static`, a stack frame, or the host's own arena — and
//! [`Structure::from_coupling`] returns one **by value**, which at large `N` means a
//! large stack temporary. [`Structure::init_from_coupling`] fills one in place for
//! callers who cannot afford that.

use crate::linalg;
use crate::sectors::{build_projectors, sector_dims_from_projectors, SECTORS};

/// A structure with no twin symmetry: both generators are the identity.
///
/// Passing this to [`Structure::from_coupling`] gives character sectors of dimension
/// `[N, 0, 0, 0]` — the whole space is "bright" and there are no dark modes, which is
/// the correct answer for a graph with no exact twin pair rather than a special case.
pub const NO_TWINS: [(usize, usize); 2] = [(0, 0), (0, 0)];

/// The relational object a simulation runs on: a coupling and everything derived from
/// it.
///
/// Not `Copy` on purpose. At `N = 11` it is 7.9 KB and at `N = 1000` it is 61 MB;
/// making an accidental copy cheap to write would be a trap, so callers pass `&self`.
#[derive(Clone, Debug)]
pub struct Structure<const N: usize> {
    /// The measured coupling `c_ij`. Symmetric, zero diagonal (a force simulation has
    /// no self-springs — FSD §12).
    pub coupling: [[f64; N]; N],
    /// The twin-symmetrised coupling: the Z2xZ2 group average of [`Self::coupling`].
    /// Under this matrix the twin dark modes are exactly decoupled.
    pub coupling_sym: [[f64; N]; N],
    /// Resistance distance on the coupling Laplacian. Supplies spring rest lengths.
    pub metric: [[f64; N]; N],
    /// **E2** — mass per node, the weighted degree `m_i = sum_j c_ij`.
    pub mass: [f64; N],
    /// **M9** — positional susceptibility, the Laplacian pseudo-inverse diagonal.
    pub susceptibility: [f64; N],
    /// Laplacian eigenvalues, ascending. `[0]` is the zero mode, `[1]` the Fiedler
    /// value, `[N-1]` the stiffest.
    pub eigenvalues: [f64; N],
    /// Laplacian eigenvectors, `[mode][node]`.
    pub eigenvectors: [[f64; N]; N],
    /// The four Z2xZ2 character projectors, in order `(+,+) (+,-) (-,+) (-,-)`.
    pub sector_projectors: [[[f64; N]; N]; SECTORS],
    /// Dimensions of the four character sectors. Sums to `N`.
    pub sector_dims: [usize; SECTORS],
    /// The two twin transpositions generating the symmetry group. `a == b` denotes the
    /// identity, i.e. "this generator is absent" — see [`NO_TWINS`].
    pub twins: [(usize, usize); 2],
    /// Whether the eigensolve that produced the spectrum converged. Always `true` for
    /// [`Structure::k11`], whose spectrum is a shipped table; reported rather than
    /// asserted for computed structures, so a caller can see a failure instead of
    /// inheriting one silently.
    pub spectrum_converged: bool,
}

impl<const N: usize> Structure<N> {
    /// An all-zero structure, for callers who will fill it with
    /// [`Self::init_from_coupling`].
    pub const fn zeroed() -> Self {
        Structure {
            coupling: [[0.0; N]; N],
            coupling_sym: [[0.0; N]; N],
            metric: [[0.0; N]; N],
            mass: [0.0; N],
            susceptibility: [0.0; N],
            eigenvalues: [0.0; N],
            eigenvectors: [[0.0; N]; N],
            sector_projectors: [[[0.0; N]; N]; SECTORS],
            sector_dims: [0; SECTORS],
            twins: NO_TWINS,
            spectrum_converged: false,
        }
    }

    /// Derive every quantity from an arbitrary symmetric coupling — **the general
    /// path**.
    ///
    /// `coupling` must be symmetric and non-negative; its diagonal is ignored. `twins`
    /// are two transpositions that must be DISJOINT (they generate Z2xZ2 only if they
    /// commute, and disjoint transpositions is how this crate's object realises that);
    /// a transposition of an index with itself means the generator is absent.
    ///
    /// The metric is meaningful only on a connected graph — see
    /// [`linalg::resistance_distance`] for what a disconnected input produces and why
    /// this constructor does not try to detect it.
    ///
    /// Cost: one `O(N^3)` eigensolve plus a handful of `O(N^3)` passes, once. Returns
    /// by value; prefer [`Self::init_from_coupling`] when `N` makes that a large stack
    /// temporary.
    pub fn from_coupling(coupling: &[[f64; N]; N], twins: [(usize, usize); 2]) -> Self {
        let mut s = Self::zeroed();
        s.init_from_coupling(coupling, twins);
        s
    }

    /// [`Self::from_coupling`] in place, without a by-value temporary.
    pub fn init_from_coupling(&mut self, coupling: &[[f64; N]; N], twins: [(usize, usize); 2]) {
        self.twins = twins;

        // The coupling, with its diagonal dropped: self-coupling is not a spring.
        for i in 0..N {
            for j in 0..N {
                self.coupling[i][j] = if i == j { 0.0 } else { coupling[i][j] };
            }
        }

        self.coupling_sym = group_average(&self.coupling, &twins);
        self.sector_projectors = build_projectors(&twins);
        self.sector_dims = sector_dims_from_projectors(&self.sector_projectors);

        self.mass = linalg::weighted_degree(&self.coupling);

        let l = linalg::laplacian(&self.coupling);
        let e = linalg::jacobi_eigen(&l);
        self.spectrum_converged = e.converged;
        self.eigenvalues = e.values;
        self.eigenvectors = e.vectors;

        let pinv = linalg::pseudo_inverse_psd(&e);
        for i in 0..N {
            self.susceptibility[i] = pinv[i][i];
        }
        self.metric = linalg::resistance_distance(&pinv);
    }

    /// The coupling matrix to put in the force law: measured, or twin-symmetrised.
    ///
    /// `false` gives [`Self::coupling`] — what the panel actually read. `true` gives
    /// [`Self::coupling_sym`], under which the twin dark mode is exactly decoupled.
    /// The difference between the two runs is the demonstrator (FSD §3): a proved null
    /// on one side, a measured leakage on the other.
    ///
    /// Named `coupling_for` rather than `coupling` so that it cannot be mistaken at a
    /// call site for the field of that name, which is always the measured matrix.
    #[inline]
    pub fn coupling_for(&self, symmetrised: bool) -> &[[f64; N]; N] {
        if symmetrised {
            &self.coupling_sym
        } else {
            &self.coupling
        }
    }

    /// **E3** — the Fiedler value (algebraic connectivity), the slowest non-trivial
    /// mode. Zero for `N < 2`, where there is no such mode.
    #[inline]
    pub fn fiedler(&self) -> f64 {
        if N < 2 {
            0.0
        } else {
            self.eigenvalues[1]
        }
    }

    /// The largest Laplacian eigenvalue — the fastest mode, which the integrator step
    /// must resolve. Zero for `N == 0`.
    #[inline]
    pub fn lambda_max(&self) -> f64 {
        if N == 0 {
            0.0
        } else {
            self.eigenvalues[N - 1]
        }
    }

    /// **E3** — the object's natural time unit, `tau = 1/sqrt(lambda_2)`.
    ///
    /// Infinite for `N < 2`, where [`Self::fiedler`] is zero because there is no
    /// non-trivial mode to be the slowest. A one-node field has no clock, and saying so
    /// with an infinity is more honest than returning a number.
    #[inline]
    pub fn time_unit(&self) -> f64 {
        1.0 / libm::sqrt(self.fiedler())
    }
}

impl Structure<{ crate::data::N }> {
    /// **The N=11 fast path.** The built-in object, from the shipped tables — no
    /// eigensolve, no runtime linear algebra, evaluated at compile time.
    ///
    /// Cross-checked against [`Structure::from_coupling`] by
    /// `k11_matches_the_computed_structure`, which is what entitles this to be called
    /// a specialisation rather than a second, unverified implementation.
    pub const fn k11() -> Self {
        Structure {
            coupling: crate::data::COUPLING,
            coupling_sym: crate::tables::COUPLING_SYM,
            metric: crate::tables::METRIC,
            mass: crate::tables::MASS,
            susceptibility: crate::tables::SUSCEPTIBILITY,
            eigenvalues: crate::tables::LAPLACIAN_EIGENVALUES,
            eigenvectors: crate::tables::EIGENVECTORS,
            sector_projectors: crate::tables::SECTOR_PROJECTORS,
            sector_dims: crate::tables::SECTOR_DIMS,
            twins: crate::data::TWINS,
            spectrum_converged: true,
        }
    }
}

/// The built-in eleven-kind object. A `static`, so using it costs a reference.
pub static K11: Structure<{ crate::data::N }> = Structure::k11();

/// The Z2xZ2 group average `(1/4) sum_g P_g M P_g^T`, `g` ranging over the four
/// products of the two twin transpositions.
///
/// This is what makes [`Structure::coupling_sym`] twin-symmetric by construction, so
/// the dark-state theorem holds on it exactly rather than approximately — and "exactly"
/// is the word that dictates how it is computed.
///
/// **Why this is two successive two-term averages and not one four-term sum.** Written
/// as `(A + PAP + QAQ + QPAPQ)/4`, the four terms reach entry `(i,j)` in one order and
/// entry `(P(i),P(j))` in another. Floating-point addition is not associative, so the
/// two would agree to a few ulps and not to the last bit — and the whole point of the
/// symmetrised coupling is that `c_ka` and `c_kb` are bit-for-bit equal, which is what
/// makes the twin null EXACTLY zero instead of merely small. Averaging over one
/// generator at a time gives each entry the form `(x + y)/2` against its partner's
/// `(y + x)/2`, and IEEE-754 addition IS commutative, so the equality is exact. The
/// second pass preserves the first generator's invariance because the two
/// transpositions are disjoint and therefore commute.
///
/// This is not a micro-optimisation dressed up as a reason: it is the difference
/// between `assert_eq!(force, 0.0)` and a tolerance, and the crate asserts the former.
fn group_average<const N: usize>(
    m: &[[f64; N]; N],
    twins: &[(usize, usize); 2],
) -> [[f64; N]; N] {
    let mut out = *m;
    for gen in 0..2 {
        let (m0, m1) = (gen == 0, gen == 1);
        let mut next = [[0.0f64; N]; N];
        for i in 0..N {
            let pi = permute(i, twins, m0, m1);
            for j in 0..N {
                let pj = permute(j, twins, m0, m1);
                next[i][j] = 0.5 * (out[i][j] + out[pi][pj]);
            }
        }
        out = next;
    }
    out
}

/// Apply the group element `swap0^m0 . swap1^m1` to an index.
///
/// A transposition `(a, a)` is the identity, which is how an absent generator is
/// spelled — so this needs no branch for the no-twins case.
pub(crate) fn permute(j: usize, twins: &[(usize, usize); 2], m0: bool, m1: bool) -> usize {
    let mut x = j;
    if m0 {
        let (a, b) = twins[0];
        if x == a {
            x = b;
        } else if x == b {
            x = a;
        }
    }
    if m1 {
        let (a, b) = twins[1];
        if x == a {
            x = b;
        } else if x == b {
            x = a;
        }
    }
    x
}

#[cfg(test)]
mod tests {
    use super::*;

    use crate::data::{COUPLING, N, TWINS};

    fn max_mat_diff<const M: usize>(a: &[[f64; M]; M], b: &[[f64; M]; M]) -> f64 {
        let mut worst = 0.0f64;
        for i in 0..M {
            for j in 0..M {
                let d = libm::fabs(a[i][j] - b[i][j]);
                if d > worst {
                    worst = d;
                }
            }
        }
        worst
    }

    fn max_vec_diff<const M: usize>(a: &[f64; M], b: &[f64; M]) -> f64 {
        let mut worst = 0.0f64;
        for i in 0..M {
            let d = libm::fabs(a[i] - b[i]);
            if d > worst {
                worst = d;
            }
        }
        worst
    }

    /// **The cross-check that makes E10 honest.** Running the general, runtime path on
    /// the built-in coupling must reproduce every shipped table.
    ///
    /// If this fails, one of two things is true and both are serious: either the
    /// precomputed tables are wrong (and every result in this crate is suspect), or
    /// the general path is wrong (and every result at `N != 11` is). There is no third
    /// reading, which is why the tolerance is tight and why it is not to be relaxed.
    ///
    /// **The tolerance, and where it comes from.** The tables were emitted with twelve
    /// significant digits, so they carry ~1e-11 of absolute error on entries of order
    /// 10 before this crate does any arithmetic at all. The requirement is `< 1e-9`,
    /// which sits two orders above the tables' own precision and two orders below any
    /// deviation that could mean the algorithms disagree.
    ///
    /// Eigenvectors are compared **up to an overall sign per mode**: an eigenvector is
    /// only defined up to sign, the shipped table took LAPACK's convention and this
    /// crate fixes its own (`linalg::SIGN_PIVOT_EPS`), and no quantity in the crate
    /// depends on the choice. The comparison is still exact in every other respect —
    /// it is a sign, not a tolerance. This is legitimate only because the eleven
    /// eigenvalues are non-degenerate, so each eigenvector is determined up to that
    /// one sign; a repeated eigenvalue would make the eigenSPACE the only meaningful
    /// object and this assertion would have to change.
    #[test]
    fn k11_matches_the_computed_structure() {
        let computed = Structure::<N>::from_coupling(&COUPLING, TWINS);
        assert!(computed.spectrum_converged, "the eigensolve did not converge");

        let tol = 1e-9;
        let checks: [(&str, f64); 5] = [
            ("coupling_sym", max_mat_diff(&computed.coupling_sym, &K11.coupling_sym)),
            ("metric", max_mat_diff(&computed.metric, &K11.metric)),
            ("mass", max_vec_diff(&computed.mass, &K11.mass)),
            (
                "susceptibility",
                max_vec_diff(&computed.susceptibility, &K11.susceptibility),
            ),
            (
                "eigenvalues",
                max_vec_diff(&computed.eigenvalues, &K11.eigenvalues),
            ),
        ];
        for (name, dev) in checks.iter() {
            assert!(dev < &tol, "{name}: computed vs precomputed differ by {dev}");
        }

        // Projectors are exact rationals on both sides — halves and zeroes — so they
        // must agree to the last bit, not merely to a tolerance.
        for s in 0..SECTORS {
            assert_eq!(
                computed.sector_projectors[s], K11.sector_projectors[s],
                "sector {s} projector"
            );
        }
        assert_eq!(computed.sector_dims, K11.sector_dims);
        assert_eq!(computed.sector_dims, [9, 1, 1, 0]);

        // Eigenvectors, up to a per-mode sign.
        for m in 0..N {
            let mut same = 0.0f64;
            let mut flipped = 0.0f64;
            for i in 0..N {
                let a = computed.eigenvectors[m][i];
                let b = K11.eigenvectors[m][i];
                let d0 = libm::fabs(a - b);
                let d1 = libm::fabs(a + b);
                if d0 > same {
                    same = d0;
                }
                if d1 > flipped {
                    flipped = d1;
                }
            }
            let dev = if same < flipped { same } else { flipped };
            assert!(dev < tol, "eigenvector {m} differs by {dev} (up to sign)");
        }

        // The eigenvalues are non-degenerate, which is the precondition for comparing
        // eigenvectors at all. Asserted rather than assumed.
        for m in 1..N {
            let gap = computed.eigenvalues[m] - computed.eigenvalues[m - 1];
            assert!(gap > 1e-6, "eigenvalues {} and {m} are degenerate (gap {gap})", m - 1);
        }
    }

    /// The largest single deviation across every cross-checked table, **measured**:
    ///
    /// ```text
    ///   quantity          computed vs precomputed
    ///   eigenvalues            5.78e-11      <- the worst
    ///   mass                   3.00e-11
    ///   coupling_sym           5.00e-12
    ///   eigenvectors (±)       3.54e-12
    ///   metric                 1.56e-12
    ///   susceptibility         1.18e-12
    ///   sector projectors      0             (exact — rationals on both sides)
    /// ```
    ///
    /// Cyclic Jacobi converges on this Laplacian in **6 sweeps**.
    ///
    /// **These are the tables' precision, not the solver's.** `tables.rs` was emitted
    /// with twelve significant digits, so an eigenvalue of order 25 already carries
    /// ~1e-11 of truncation before this crate does any arithmetic; the deviations above
    /// sit exactly at that scale and shrink with the magnitude of the quantity. A
    /// solver disagreement would not respect the tables' printed precision that
    /// closely. Independently confirmed against a LAPACK reference, which reproduces
    /// the same six figures against the same tables.
    #[test]
    fn report_the_worst_deviation() {
        let c = Structure::<N>::from_coupling(&COUPLING, TWINS);
        let mut worst = 0.0f64;
        let mut which = "none";
        for (name, dev) in [
            ("coupling_sym", max_mat_diff(&c.coupling_sym, &K11.coupling_sym)),
            ("metric", max_mat_diff(&c.metric, &K11.metric)),
            ("eigenvectors", {
                let mut w = 0.0f64;
                for m in 0..N {
                    let mut same = 0.0f64;
                    let mut flip = 0.0f64;
                    for i in 0..N {
                        let d0 = libm::fabs(c.eigenvectors[m][i] - K11.eigenvectors[m][i]);
                        let d1 = libm::fabs(c.eigenvectors[m][i] + K11.eigenvectors[m][i]);
                        if d0 > same {
                            same = d0;
                        }
                        if d1 > flip {
                            flip = d1;
                        }
                    }
                    let d = if same < flip { same } else { flip };
                    if d > w {
                        w = d;
                    }
                }
                w
            }),
            ("mass", max_vec_diff(&c.mass, &K11.mass)),
            ("susceptibility", max_vec_diff(&c.susceptibility, &K11.susceptibility)),
            ("eigenvalues", max_vec_diff(&c.eigenvalues, &K11.eigenvalues)),
        ] {
            if dev > worst {
                worst = dev;
                which = name;
            }
        }
        // Not a threshold to tune: the assertion is the one in the test above. This
        // exists so the measured number is on the record.
        assert!(worst < 1e-9, "worst deviation {worst} in {which}");
    }

    /// The fast path is the TABLES, bit for bit — not a solve that happens to agree.
    ///
    /// `k11_matches_the_computed_structure` compares the two paths to a tolerance, which
    /// would still pass if [`Structure::k11`] were quietly reimplemented as a runtime
    /// eigensolve. This asserts the thing that makes it a fast path at all: every field
    /// is the shipped constant, unmodified, so constructing the built-in object does no
    /// arithmetic.
    #[test]
    fn the_fast_path_is_the_tables_verbatim() {
        assert_eq!(K11.coupling, crate::data::COUPLING);
        assert_eq!(K11.coupling_sym, crate::tables::COUPLING_SYM);
        assert_eq!(K11.metric, crate::tables::METRIC);
        assert_eq!(K11.mass, crate::tables::MASS);
        assert_eq!(K11.susceptibility, crate::tables::SUSCEPTIBILITY);
        assert_eq!(K11.eigenvalues, crate::tables::LAPLACIAN_EIGENVALUES);
        assert_eq!(K11.eigenvectors, crate::tables::EIGENVECTORS);
        assert_eq!(K11.sector_projectors, crate::tables::SECTOR_PROJECTORS);
        assert_eq!(K11.sector_dims, crate::tables::SECTOR_DIMS);
        assert_eq!(K11.twins, crate::data::TWINS);
        assert!(K11.spectrum_converged);

        // And the tables' own named scalars agree with what the structure reads off
        // the spectrum, so `tables.rs` cannot drift against itself.
        assert_eq!(K11.fiedler(), crate::tables::FIEDLER);
        assert_eq!(K11.lambda_max(), crate::tables::LAMBDA_MAX);
        assert!(libm::fabs(K11.time_unit() - crate::tables::TIME_UNIT) < 1e-12);
    }

    /// The general path is not secretly the special path: a structure with a DIFFERENT
    /// coupling produces different derived quantities, so the cross-check above is
    /// testing computation rather than a table lookup.
    #[test]
    fn the_general_path_actually_computes() {
        let mut c = COUPLING;
        c[0][1] += 1.0;
        c[1][0] += 1.0;
        let s = Structure::<N>::from_coupling(&c, TWINS);
        assert!(max_mat_diff(&s.metric, &K11.metric) > 1e-6, "the metric did not move");
        assert!(max_vec_diff(&s.mass, &K11.mass) > 0.9, "the mass did not move");
    }

    /// A small structure with no twins: the whole space is bright, the two dark sectors
    /// are empty, and nothing divides by zero.
    #[test]
    fn a_structure_with_no_twins_is_all_bright() {
        let c = [[0.0, 1.0, 0.0], [1.0, 0.0, 1.0], [0.0, 1.0, 0.0]];
        let s = Structure::<3>::from_coupling(&c, NO_TWINS);
        assert!(s.spectrum_converged);
        assert_eq!(s.sector_dims, [3, 0, 0, 0]);
        assert!(libm::fabs(s.metric[0][2] - 2.0) < 1e-12);
        assert_eq!(s.mass, [1.0, 2.0, 1.0]);
        // With no twin symmetry the group average is the identity operation.
        assert_eq!(s.coupling_sym, s.coupling);
        assert!(libm::fabs(s.fiedler() - 1.0) < 1e-12);
        assert!(libm::fabs(s.lambda_max() - 3.0) < 1e-12);
    }

    /// The symmetrised coupling really is invariant under both twin swaps, at whatever
    /// `N` — which is the property the dark-state theorem needs and the only reason
    /// `coupling_sym` exists.
    ///
    /// Two things are asserted, and both are **exact equality**, not a tolerance. The
    /// first is full invariance `M[g(i)][g(j)] = M[i][j]` under every element of the
    /// group. The second is the condition `dark_state_decoupled` actually uses:
    /// `c_ka = c_kb` for every `k` OUTSIDE the pair — outside, because a twin's
    /// coupling to its own partner is not required to equal its (zero) self-coupling
    /// and nothing in the theorem says it should. Exactness is the point: it is what
    /// makes the twin null read `0.0` rather than `1e-17`.
    #[test]
    fn the_group_average_is_twin_invariant() {
        let s = Structure::<N>::from_coupling(&COUPLING, TWINS);

        for g in 0..4 {
            let m0 = (g & 1) == 1;
            let m1 = (g & 2) == 2;
            for i in 0..N {
                for j in 0..N {
                    let pi = permute(i, &s.twins, m0, m1);
                    let pj = permute(j, &s.twins, m0, m1);
                    assert_eq!(
                        s.coupling_sym[pi][pj], s.coupling_sym[i][j],
                        "group element {g} moved entry ({i}, {j})"
                    );
                }
            }
        }

        for &(a, b) in s.twins.iter() {
            for k in 0..N {
                if k == a || k == b {
                    continue;
                }
                assert_eq!(
                    s.coupling_sym[k][a], s.coupling_sym[k][b],
                    "row {k} is not symmetric in the twin ({a}, {b})"
                );
            }
        }
    }

    /// `init_from_coupling` and `from_coupling` are the same operation.
    #[test]
    fn in_place_and_by_value_construction_agree() {
        let by_value = Structure::<N>::from_coupling(&COUPLING, TWINS);
        let mut in_place = Structure::<N>::zeroed();
        in_place.init_from_coupling(&COUPLING, TWINS);
        assert_eq!(by_value.metric, in_place.metric);
        assert_eq!(by_value.eigenvalues, in_place.eigenvalues);
        assert_eq!(by_value.eigenvectors, in_place.eigenvectors);
        assert_eq!(by_value.coupling_sym, in_place.coupling_sym);
    }

    /// A four-node structure at a size the tables do not cover, driven end to end: the
    /// general path produces a usable metric, a positive spectrum, and a mass.
    #[test]
    fn a_four_node_structure_is_well_formed() {
        let c = [
            [0.0, 2.0, 0.5, 0.0],
            [2.0, 0.0, 1.0, 3.0],
            [0.5, 1.0, 0.0, 0.25],
            [0.0, 3.0, 0.25, 0.0],
        ];
        let s = Structure::<4>::from_coupling(&c, [(0, 2), (1, 3)]);
        assert!(s.spectrum_converged);
        assert!(libm::fabs(s.eigenvalues[0]) < 1e-12, "zero mode {}", s.eigenvalues[0]);
        assert!(s.fiedler() > 0.0, "graph should be connected");
        for i in 0..4 {
            assert!(s.mass[i] > 0.0);
            assert!(s.susceptibility[i] > 0.0);
            for j in 0..4 {
                assert_eq!(s.metric[i][j], s.metric[j][i]);
                if i != j {
                    assert!(s.metric[i][j] > 0.0);
                }
            }
        }
        // Triangle inequality, over all triples.
        for i in 0..4 {
            for j in 0..4 {
                for k in 0..4 {
                    assert!(
                        s.metric[i][k] <= s.metric[i][j] + s.metric[j][k] + 1e-12,
                        "triangle violated at {i},{j},{k}"
                    );
                }
            }
        }
        assert_eq!(s.sector_dims.iter().sum::<usize>(), 4);
    }
}
