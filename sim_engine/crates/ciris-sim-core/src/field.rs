//! Closing E6 (locality), E7 (continuum limit / level of detail) and E8 (dissipation
//! accounting) — **build-mode analogical fills**, same discipline as [`crate::gaps`].
//!
//! | gap | filled with | the analogy |
//! |---|---|---|
//! | **E6** locality | locality is **metric, not topological** | K11 is complete, so adjacency says nothing; resistance distance says everything |
//! | **E7** continuum | profile-class coarsening | level of detail IS coarse-graining; two kinds merge when their COMPLETE profiles agree |
//! | **E8** dissipation | the **ledger** | energy is not lost at the boundary, it is *recorded* — and the books balance |
//!
//! E7 is the one that only becomes interesting at other sizes. At `N = 11` with a
//! measured coupling there is almost nothing to merge; the FSD's scaling thesis
//! (§11.2) is that `N/G` grows without bound as `N` does, and the point of E10 is that
//! [`reduction_ratio`] can now be asked that question at a size where the answer
//! matters.

use crate::dynamics::{kinetic_energy, potential_energy, step, Params, State};
use crate::gaps::RecordBoundary;
use crate::structure::Structure;

// -------------------------------------------------------------- E6: locality

/// Time at which a disturbance injected at `src` first moves `dst` by more than
/// `threshold`, in steps. `None` if it never does within `max_steps`.
///
/// **Why this is the right question.** K11 is a COMPLETE graph: every kind is adjacent
/// to every other, so there is no hop distance and no topological light cone. That
/// looked like a fatal gap for a physics engine — nothing to watch travel. The
/// resolution is that **locality here is metric rather than topological**: the
/// resistance distance ([`Structure::metric`]) already orders the kinds by how strongly
/// they are connected through the whole field, and a disturbance reaches near kinds
/// before far ones even though all are adjacent. This function measures that, and
/// [`tests::arrival_order_follows_the_metric`] checks the ordering is real.
///
/// This is consistent with M7 (the object's laws are of a connected field, not of kinds
/// severally): there is no strict locality, but there is an *effective* one.
pub fn arrival_step<const N: usize>(
    st: &Structure<N>,
    src: usize,
    dst: usize,
    amplitude: f64,
    threshold: f64,
    params: &Params,
    max_steps: usize,
) -> Option<usize> {
    let mut pos = [[0.0f64; 3]; N];
    pos[src][0] = amplitude;
    let mut s = State::at_rest(pos);
    let mut t = 0;
    while t < max_steps {
        step(&mut s, st, params, true);
        let p = s.pos[dst];
        let d = libm::sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        if d > threshold {
            return Some(t);
        }
        t += 1;
    }
    None
}

/// The effective neighbourhood of `i` at radius `r`: every kind within resistance
/// distance `r`. This is what "local" means on a complete graph.
pub fn neighbourhood<const N: usize>(st: &Structure<N>, i: usize, r: f64) -> [bool; N] {
    let mut out = [false; N];
    let mut j = 0;
    while j < N {
        out[j] = j != i && st.metric[i][j] <= r;
        j += 1;
    }
    out
}

// ------------------------------------------------- E7: continuum / level of detail

/// Coarsen the object by merging kinds whose COMPLETE relational profiles agree within
/// `tolerance`, returning a class label per kind and the number of classes.
///
/// **The analogy, and the theorem behind it.** `GrayAlgebra.Kmat_det_ne_zero` and its
/// converse prove that a profile with pairwise DISTINCT values closes to the whole
/// space, while confinement happens exactly when values REPEAT. So coarse-graining is
/// legal precisely when complete profiles repeat — not when the state space is small
/// and not when the rank is low. Level of detail and the continuum limit are therefore
/// the SAME operation, and this is it.
///
/// At `N = 11` with a measured coupling there is little to merge; the point is that the
/// criterion is computable per frame, and it is what makes the reduction scale (FSD
/// §11): as `N` grows with profiles repeating, the class count grows far slower.
pub fn coarsen<const N: usize>(st: &Structure<N>, tolerance: f64) -> ([usize; N], usize) {
    let mut label = [usize::MAX; N];
    let mut classes = 0;
    let mut i = 0;
    while i < N {
        if label[i] == usize::MAX {
            label[i] = classes;
            let mut j = i + 1;
            while j < N {
                if label[j] == usize::MAX && profile_distance(st, i, j) <= tolerance {
                    label[j] = classes;
                }
                j += 1;
            }
            classes += 1;
        }
        i += 1;
    }
    (label, classes)
}

/// Sup-norm distance between two kinds' complete coupling profiles, ignoring the two
/// entries that reference the pair itself (which differ trivially).
pub fn profile_distance<const N: usize>(st: &Structure<N>, a: usize, b: usize) -> f64 {
    let mut worst = 0.0f64;
    let mut k = 0;
    while k < N {
        if k != a && k != b {
            let d = libm::fabs(st.coupling[a][k] - st.coupling[b][k]);
            if d > worst {
                worst = d;
            }
        }
        k += 1;
    }
    worst
}

/// The reduction ratio `N / classes` at a given tolerance — the quantity FSD §11 says
/// must be measured on real scenes before any scaling claim.
pub fn reduction_ratio<const N: usize>(st: &Structure<N>, tolerance: f64) -> f64 {
    let (_, c) = coarsen(st, tolerance);
    N as f64 / c as f64
}

// ---------------------------------------------------------- E8: dissipation

/// A ledger of where the energy went. **This is what closes E8.**
///
/// The Record boundary absorbs, which naively destroys energy and breaks any
/// conservation check. The object's own answer is that nothing is destroyed: what
/// leaves the field is *recorded*. So the engine keeps the books — kinetic plus
/// potential plus recorded is the conserved total, and a leak is then a bug rather
/// than a feature of the model.
///
/// Carries no `N`: it is one number, whatever the size of the field it accounts for.
#[derive(Copy, Clone, Debug, Default)]
pub struct Ledger {
    /// Energy carried out of the live field by absorbed nodes. Monotone non-decreasing.
    pub recorded: f64,
}

impl Ledger {
    /// Step the system, apply the boundary, and record **every joule the boundary
    /// removes** — not only on the step where a node is newly absorbed.
    ///
    /// The first version of this method recorded only when `apply` returned a new
    /// absorption, and the ledger drifted 14.6%. The cause: the boundary zeroes the
    /// velocity of absorbed nodes on EVERY subsequent step, so it goes on removing
    /// kinetic energy long after the absorption event. That is exactly the E8 failure
    /// the FSD predicted ("probability leaks or freezes on Record edges"). The fix is
    /// to measure the energy the boundary itself removes, each step, and pay it into
    /// the record.
    pub fn step_and_account<const N: usize>(
        &mut self,
        state: &mut State<N>,
        st: &Structure<N>,
        boundary: &mut RecordBoundary<N>,
        params: &Params,
        symmetrised: bool,
    ) -> usize {
        step(state, st, params, symmetrised);
        let before = kinetic_energy(state) + potential_energy(state, st, params, symmetrised);
        let n = boundary.apply(state);
        let after = kinetic_energy(state) + potential_energy(state, st, params, symmetrised);
        let paid = before - after;
        if paid > 0.0 {
            self.recorded += paid;
        }
        n
    }

    /// Live energy plus recorded energy — the quantity that must not drift.
    pub fn total<const N: usize>(
        &self,
        state: &State<N>,
        st: &Structure<N>,
        params: &Params,
        symmetrised: bool,
    ) -> f64 {
        kinetic_energy(state) + potential_energy(state, st, params, symmetrised) + self.recorded
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::structure::{Structure, K11, NO_TWINS};
    use crate::N;

    /// E6: on a COMPLETE graph, arrival order still follows the metric. Near kinds
    /// (small resistance distance) are moved before far ones.
    #[test]
    fn arrival_order_follows_the_metric() {
        let p = Params::harmonic();
        let src = 5; // Facts — the heaviest, most connected kind
        let mut pairs: [(f64, usize); N] = [(0.0, 0); N];
        let mut n = 0;
        for dst in 0..N {
            if dst == src {
                continue;
            }
            if let Some(t) = arrival_step(&K11, src, dst, 1.0, 1e-3, &p, 20_000) {
                pairs[n] = (K11.metric[src][dst], t);
                n += 1;
            }
        }
        assert!(n >= 8, "only {n} kinds were reached — propagation is not happening");
        // Rank agreement between resistance distance and arrival time.
        let mut concordant = 0;
        let mut total = 0;
        for a in 0..n {
            for b in (a + 1)..n {
                total += 1;
                if (pairs[a].0 < pairs[b].0) == (pairs[a].1 < pairs[b].1) {
                    concordant += 1;
                }
            }
        }
        assert!(
            concordant * 4 >= total * 3,
            "arrival order barely follows the metric: {concordant}/{total}"
        );
    }

    /// E6: the effective neighbourhood is a real restriction, not everything.
    #[test]
    fn neighbourhood_is_a_proper_subset() {
        let nb = neighbourhood(&K11, 5, 0.30);
        let c = nb.iter().filter(|&&x| x).count();
        assert!(c > 0 && c < N - 1, "neighbourhood at r=0.30 has {c} members");
    }

    /// E7: coarsening is monotone in tolerance and bounded by the extremes.
    #[test]
    fn coarsening_is_monotone() {
        let (_, c_fine) = coarsen(&K11, 0.0);
        let (_, c_mid) = coarsen(&K11, 0.5);
        let (_, c_coarse) = coarsen(&K11, 100.0);
        assert_eq!(c_fine, N, "zero tolerance must keep every kind distinct");
        assert_eq!(c_coarse, 1, "huge tolerance must merge everything");
        assert!(c_mid <= c_fine && c_mid >= c_coarse);
        assert!(reduction_ratio(&K11, 0.0) == 1.0);
    }

    /// E7: profile distance is a genuine metric on the kinds (symmetric, zero on self).
    #[test]
    fn profile_distance_is_symmetric() {
        for a in 0..N {
            for b in 0..N {
                let d = profile_distance(&K11, a, b);
                assert!((d - profile_distance(&K11, b, a)).abs() < 1e-15);
                if a == b {
                    assert!(d < 1e-15);
                }
            }
        }
    }

    /// E8: the books balance. Live energy plus recorded energy does not drift, even as
    /// the boundary absorbs.
    #[test]
    fn the_ledger_balances_across_absorption() {
        let mut pos = [[0.0f64; 3]; N];
        for i in 0..N {
            let a = i as f64 * 0.9;
            pos[i] = [libm::cos(a) * 0.8, libm::sin(a) * 0.8, 0.1 * i as f64 - 0.5];
        }
        let mut s = State::at_rest(pos);
        s.vel[0] = [3.0, 0.0, 0.0]; // kick one kind hard enough to leave
        let p = Params {
            damping: 1.0,
            ..Params::harmonic()
        };
        let mut b = RecordBoundary::<N>::new(2.0);
        let mut ledger = Ledger::default();
        let e0 = ledger.total(&s, &K11, &p, true);
        let mut absorbed = 0;
        for _ in 0..3000 {
            absorbed += ledger.step_and_account(&mut s, &K11, &mut b, &p, true);
        }
        let e1 = ledger.total(&s, &K11, &p, true);
        assert!(absorbed > 0, "nothing was absorbed — the test proves nothing");
        assert!(ledger.recorded > 0.0, "absorption recorded no energy");
        let drift = libm::fabs(e1 - e0) / libm::fabs(e0).max(1e-12);
        assert!(drift < 0.05, "ledger drifted {drift} (e0={e0}, e1={e1})");
    }

    /// **E7 at a size where the answer is not trivially 1.** Six nodes in two exact
    /// profile classes: three mutually interchangeable "hub" nodes and three
    /// interchangeable "leaf" nodes. The reduction ratio must read 3x — one class per
    /// distinct complete profile — which is the measurement FSD §11.5 says has to be
    /// made on real scenes before any scaling claim.
    ///
    /// This is the E10 payoff for E7: at `N = 11` the ratio is 1 and says nothing.
    #[test]
    fn coarsening_finds_repeated_profiles_at_other_sizes() {
        // Nodes 0,1,2 are hubs; 3,4,5 are leaves. Every hub-leaf pair couples at 1.0,
        // every hub-hub pair at 2.0, and leaves do not couple to each other. Then all
        // three hubs have identical complete profiles, and so do all three leaves.
        let mut c = [[0.0f64; 6]; 6];
        for i in 0..3 {
            for j in 0..3 {
                if i != j {
                    c[i][j] = 2.0;
                }
            }
            for j in 3..6 {
                c[i][j] = 1.0;
                c[j][i] = 1.0;
            }
        }
        let st = Structure::<6>::from_coupling(&c, NO_TWINS);

        let (labels, classes) = coarsen(&st, 0.0);
        assert_eq!(classes, 2, "expected two profile classes, got {classes}");
        assert_eq!(labels[0], labels[1]);
        assert_eq!(labels[1], labels[2]);
        assert_eq!(labels[3], labels[4]);
        assert_eq!(labels[4], labels[5]);
        assert_ne!(labels[0], labels[3]);
        assert!(
            libm::fabs(reduction_ratio(&st, 0.0) - 3.0) < 1e-15,
            "reduction ratio {}",
            reduction_ratio(&st, 0.0)
        );

        // And the ledger, the neighbourhood and the arrival probe all run at N = 6.
        assert!(neighbourhood(&st, 0, 0.5).iter().any(|&x| x));
        let p = Params::harmonic();
        assert!(arrival_step(&st, 0, 4, 1.0, 1e-3, &p, 20_000).is_some());

        let mut s = State::<6>::at_rest([[0.4, 0.0, 0.0]; 6]);
        s.pos[0] = [0.0, 0.0, 0.0];
        s.vel[0] = [3.0, 0.0, 0.0];
        let pn = Params { damping: 1.0, ..Params::harmonic() };
        let mut b = RecordBoundary::<6>::new(2.0);
        let mut ledger = Ledger::default();
        let e0 = ledger.total(&s, &st, &pn, false);
        for _ in 0..3000 {
            ledger.step_and_account(&mut s, &st, &mut b, &pn, false);
        }
        let e1 = ledger.total(&s, &st, &pn, false);
        let drift = libm::fabs(e1 - e0) / libm::fabs(e0).max(1e-12);
        assert!(drift < 0.05, "ledger drifted {drift} at N = 6");
    }
}
