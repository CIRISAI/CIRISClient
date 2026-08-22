//! Closing E6 (locality), E7 (continuum limit / level of detail) and E8 (dissipation
//! accounting) — **build-mode analogical fills**, same discipline as [`crate::gaps`].
//!
//! | gap | filled with | the analogy |
//! |---|---|---|
//! | **E6** locality | locality is **metric, not topological** | K11 is complete, so adjacency says nothing; resistance distance says everything |
//! | **E7** continuum | profile-class coarsening | level of detail IS coarse-graining; two kinds merge when their COMPLETE profiles agree |
//! | **E8** dissipation | the **ledger** | energy is not lost at the boundary, it is *recorded* — and the books balance |

use crate::dynamics::{kinetic_energy, potential_energy, step, Params, State};
use crate::gaps::RecordBoundary;
use crate::tables::METRIC;
use crate::{data::COUPLING, N};

// -------------------------------------------------------------- E6: locality

/// Time at which a disturbance injected at `src` first moves `dst` by more than
/// `threshold`, in steps. `None` if it never does within `max_steps`.
///
/// **Why this is the right question.** K11 is a COMPLETE graph: every kind is adjacent
/// to every other, so there is no hop distance and no topological light cone. That
/// looked like a fatal gap for a physics engine — nothing to watch travel. The
/// resolution is that **locality here is metric rather than topological**: the
/// resistance distance ([`METRIC`]) already orders the kinds by how strongly they are
/// connected through the whole field, and a disturbance reaches near kinds before far
/// ones even though all are adjacent. This function measures that, and
/// [`tests::arrival_order_follows_the_metric`] checks the ordering is real.
///
/// This is consistent with M7 (the object's laws are of a connected field, not of kinds
/// severally): there is no strict locality, but there is an *effective* one.
pub fn arrival_step(
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
        step(&mut s, params, true);
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
pub fn neighbourhood(i: usize, r: f64) -> [bool; N] {
    let mut out = [false; N];
    let mut j = 0;
    while j < N {
        out[j] = j != i && METRIC[i][j] <= r;
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
pub fn coarsen(tolerance: f64) -> ([usize; N], usize) {
    let mut label = [usize::MAX; N];
    let mut classes = 0;
    let mut i = 0;
    while i < N {
        if label[i] == usize::MAX {
            label[i] = classes;
            let mut j = i + 1;
            while j < N {
                if label[j] == usize::MAX && profile_distance(i, j) <= tolerance {
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
pub fn profile_distance(a: usize, b: usize) -> f64 {
    let mut worst = 0.0f64;
    let mut k = 0;
    while k < N {
        if k != a && k != b {
            let d = libm::fabs(COUPLING[a][k] - COUPLING[b][k]);
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
pub fn reduction_ratio(tolerance: f64) -> f64 {
    let (_, c) = coarsen(tolerance);
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
    pub fn step_and_account(
        &mut self,
        state: &mut State,
        boundary: &mut RecordBoundary,
        params: &Params,
        symmetrised: bool,
    ) -> usize {
        step(state, params, symmetrised);
        let before = kinetic_energy(state) + potential_energy(state, params, symmetrised);
        let n = boundary.apply(state);
        let after = kinetic_energy(state) + potential_energy(state, params, symmetrised);
        let paid = before - after;
        if paid > 0.0 {
            self.recorded += paid;
        }
        n
    }

    /// Live energy plus recorded energy — the quantity that must not drift.
    pub fn total(&self, state: &State, params: &Params, symmetrised: bool) -> f64 {
        kinetic_energy(state) + potential_energy(state, params, symmetrised) + self.recorded
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
            if let Some(t) = arrival_step(src, dst, 1.0, 1e-3, &p, 20_000) {
                pairs[n] = (METRIC[src][dst], t);
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
        let nb = neighbourhood(5, 0.30);
        let c = nb.iter().filter(|&&x| x).count();
        assert!(c > 0 && c < N - 1, "neighbourhood at r=0.30 has {c} members");
    }

    /// E7: coarsening is monotone in tolerance and bounded by the extremes.
    #[test]
    fn coarsening_is_monotone() {
        let (_, c_fine) = coarsen(0.0);
        let (_, c_mid) = coarsen(0.5);
        let (_, c_coarse) = coarsen(100.0);
        assert_eq!(c_fine, N, "zero tolerance must keep every kind distinct");
        assert_eq!(c_coarse, 1, "huge tolerance must merge everything");
        assert!(c_mid <= c_fine && c_mid >= c_coarse);
        assert!(reduction_ratio(0.0) == 1.0);
    }

    /// E7: profile distance is a genuine metric on the kinds (symmetric, zero on self).
    #[test]
    fn profile_distance_is_symmetric() {
        for a in 0..N {
            for b in 0..N {
                let d = profile_distance(a, b);
                assert!((d - profile_distance(b, a)).abs() < 1e-15);
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
        let mut b = RecordBoundary::new(2.0);
        let mut ledger = Ledger::default();
        let e0 = ledger.total(&s, &p, true);
        let mut absorbed = 0;
        for _ in 0..3000 {
            absorbed += ledger.step_and_account(&mut s, &mut b, &p, true);
        }
        let e1 = ledger.total(&s, &p, true);
        assert!(absorbed > 0, "nothing was absorbed — the test proves nothing");
        assert!(ledger.recorded > 0.0, "absorption recorded no energy");
        let drift = libm::fabs(e1 - e0) / libm::fabs(e0).max(1e-12);
        assert!(drift < 0.05, "ledger drifted {drift} (e0={e0}, e1={e1})");
    }
}
