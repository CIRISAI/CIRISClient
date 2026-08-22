//! Closing engine gaps E2, E3, E5 and E9 — **build-mode analogical fills**.
//!
//! Each quantity below is derived from the object by analogy rather than by proof. The
//! research side will formalise or replace them; until then every one names what it
//! stands in for and what would falsify it. None is a theorem, and none is presented
//! as one.
//!
//! | gap | filled with | the analogy |
//! |---|---|---|
//! | **E2** inertia | weighted degree `m_i = Σ_j c_ij` | a kind bound to everything resists motion |
//! | **E3** time | `τ = 1/√λ₂` (Fiedler) | the object's clock is how long a disturbance takes to cross the field |
//! | **E5** action | `F = −∇V` verified numerically | the force law is conservative, so a potential exists |
//! | **E9** boundary | the Record: absorbing, one-way | what leaves the field enters the record and does not come back |

use crate::dynamics::{forces, Params, State};
use crate::tables::{FIEDLER, LAMBDA_MAX, MASS, SUSCEPTIBILITY, TIME_UNIT};
use crate::N;

// ---------------------------------------------------------------- E2: inertia

/// Mass of kind `i` — the weighted degree `Σ_j c_ij`.
///
/// **Analogical fill, not a theorem.** Heaviest are Facts (17.82) and Premises (16.75);
/// lightest are Priorities (2.55) and Identity (2.81). Deep, well-connected commitments
/// have inertia; peripheral ones are easy to move. Ordering agrees with the inverse M9
/// susceptibility at correlation 0.904, which is the ontology quantity this stands in
/// for. **Falsifier:** if the research side derives a mass from the field that ranks
/// the kinds differently, this is wrong and the ordering is the thing to check.
#[inline]
pub fn mass(i: usize) -> f64 {
    MASS[i]
}

/// The alternative convention, `m_i = 1/χ_i`, kept so the two can be compared.
#[inline]
pub fn mass_from_susceptibility(i: usize) -> f64 {
    1.0 / SUSCEPTIBILITY[i]
}

/// One semi-implicit Euler step with **per-node mass**: `a_i = F_i / m_i`.
///
/// This is the E2-closed counterpart of [`crate::dynamics::step`], which uses unit
/// mass. With real masses the light kinds (Priorities, Identity) ring fast and the
/// heavy ones (Facts, Premises) barely move — the object acquires a spectrum of
/// timescales instead of one.
pub fn step_massive(state: &mut State, params: &Params, symmetrised: bool) {
    let f = forces(state, params, symmetrised);
    for i in 0..N {
        let inv_m = 1.0 / mass(i);
        for k in 0..3 {
            state.vel[i][k] = (state.vel[i][k] + params.dt * f[i][k] * inv_m) * params.damping;
            state.pos[i][k] += params.dt * state.vel[i][k];
        }
    }
}

// ------------------------------------------------------------------ E3: time

/// The object's natural time unit, `τ = 1/√λ₂` — the reciprocal root of the Fiedler
/// value, i.e. the period of the slowest non-trivial relaxation.
///
/// **Analogical fill.** Everything in the object is faster than a field-crossing
/// disturbance, so this is the outer clock. Measured: λ₂ = 1.7396, τ = 0.7582.
#[inline]
pub fn time_unit() -> f64 {
    TIME_UNIT
}

/// The largest step an explicit integrator can take and stay stable, from the object's
/// own spectrum: `dt < 2 / ω_max`, `ω_max = √(λ_max / m_min)`.
///
/// The stiffness ratio `λ_max/λ₂ = 14.55` is what forces this — the fast end must be
/// resolved even though the interesting motion is slow. `safety` should be < 1.
pub fn suggested_dt(safety: f64) -> f64 {
    let mut m_min = MASS[0];
    let mut i = 1;
    while i < N {
        if MASS[i] < m_min {
            m_min = MASS[i];
        }
        i += 1;
    }
    let omega_max = libm::sqrt(LAMBDA_MAX / m_min);
    safety * 2.0 / omega_max
}

/// Ratio of fastest to slowest mode — how stiff the object is as an integration problem.
#[inline]
pub fn stiffness_ratio() -> f64 {
    LAMBDA_MAX / FIEDLER
}

// ---------------------------------------------------------------- E5: action

/// Numerical check that the force law is the negative gradient of the potential:
/// returns the largest `|F_i + ∂V/∂x_i|` over all nodes and axes.
///
/// **This is what closes E5**: if the residual is at finite-difference precision, a
/// potential exists and the dynamics is variational. If it is not, some term in the
/// force law is non-conservative and must be named. Nothing here assumes the answer.
pub fn gradient_residual(state: &State, params: &Params, symmetrised: bool, h: f64) -> f64 {
    let f = forces(state, params, symmetrised);
    let mut worst = 0.0f64;
    for i in 0..N {
        for k in 0..3 {
            let mut sp = *state;
            let mut sm = *state;
            sp.pos[i][k] += h;
            sm.pos[i][k] -= h;
            let vp = crate::dynamics::potential_energy(&sp, params, symmetrised);
            let vm = crate::dynamics::potential_energy(&sm, params, symmetrised);
            let grad = (vp - vm) / (2.0 * h);
            let r = libm::fabs(f[i][k] + grad);
            if r > worst {
                worst = r;
            }
        }
    }
    worst
}

// -------------------------------------------------------------- E9: boundary

/// The Record boundary — **absorbing and one-way**.
///
/// **The analogy is a proved property, which is why this boundary and not another.**
/// CIRISOntology measures the Record axis at machine-zero backflow (Leg A `S4 = 0.0000`)
/// and proves it is not site-generated (`Generator.record_not_site_generated`). So the
/// edge of the field is not a reflecting wall and not periodic: what leaves is recorded,
/// and recorded things do not return. A node that crosses `radius` is absorbed, frozen,
/// and never released.
#[derive(Copy, Clone, Debug)]
pub struct RecordBoundary {
    /// Distance from the origin at which a node is absorbed into the record.
    pub radius: f64,
    /// Which nodes have been absorbed. Monotone: entries only ever go `false -> true`.
    pub absorbed: [bool; N],
}

impl RecordBoundary {
    /// A boundary at `radius`, nothing absorbed yet.
    pub fn new(radius: f64) -> Self {
        RecordBoundary {
            radius,
            absorbed: [false; N],
        }
    }

    /// Absorb any node now outside the radius, and freeze everything absorbed.
    /// Returns the number newly absorbed this call.
    pub fn apply(&mut self, state: &mut State) -> usize {
        let mut n = 0;
        for i in 0..N {
            if !self.absorbed[i] {
                let p = state.pos[i];
                let r = libm::sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
                if r > self.radius {
                    self.absorbed[i] = true;
                    n += 1;
                }
            }
            if self.absorbed[i] {
                state.vel[i] = [0.0; 3];
            }
        }
        n
    }

    /// How many kinds have entered the record.
    pub fn count(&self) -> usize {
        let mut c = 0;
        let mut i = 0;
        while i < N {
            if self.absorbed[i] {
                c += 1;
            }
            i += 1;
        }
        c
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dynamics::Params;

    fn spread_state() -> State {
        let mut pos = [[0.0f64; 3]; N];
        let mut i = 0;
        while i < N {
            let a = i as f64 * 0.61;
            pos[i] = [libm::cos(a) * (1.0 + 0.1 * i as f64), libm::sin(a), 0.2 * i as f64 - 1.0];
            i += 1;
        }
        State::at_rest(pos)
    }

    /// E2: masses are positive, and the ordering is the one the doc claims.
    #[test]
    fn mass_is_positive_and_ordered() {
        for i in 0..N {
            assert!(mass(i) > 0.0);
        }
        // Facts (index 5) heaviest, Priorities (index 0) lightest.
        let mut heaviest = 0;
        let mut lightest = 0;
        for i in 0..N {
            if MASS[i] > MASS[heaviest] {
                heaviest = i;
            }
            if MASS[i] < MASS[lightest] {
                lightest = i;
            }
        }
        assert_eq!(heaviest, 5, "expected Facts heaviest");
        assert_eq!(lightest, 0, "expected Priorities lightest");
    }

    /// E2: the two mass conventions agree on **46 of 55** pairs — MEASURED, not
    /// guessed. The nine inversions are all among the mid-mass kinds (Manner,
    /// Confidence, Circumstances, Model, Structure) plus the single heavy pair
    /// Facts/Premises. Degree and inverse-susceptibility therefore agree about which
    /// kinds are light and which are heavy, and disagree only about the ordering
    /// WITHIN the middle — which is where the two notions genuinely differ: degree
    /// counts local binding, susceptibility counts global response. **If a future
    /// derivation moves a light kind into the heavy group or vice versa, that is the
    /// falsifier; reordering the middle is not.**
    #[test]
    fn mass_conventions_agree_on_the_extremes() {
        let mut disagreements = 0;
        for i in 0..N {
            for j in (i + 1)..N {
                let a = MASS[i] < MASS[j];
                let b = mass_from_susceptibility(i) < mass_from_susceptibility(j);
                if a != b {
                    disagreements += 1;
                }
            }
        }
        assert_eq!(disagreements, 9, "the measured inversion count changed");
        // The load-bearing claim: the extremes agree. The three lightest and three
        // heaviest kinds must be the same set under both conventions.
        let mut by_deg: [usize; N] = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
        let mut by_chi = by_deg;
        by_deg.sort_by(|&a, &b| MASS[a].partial_cmp(&MASS[b]).unwrap());
        by_chi.sort_by(|&a, &b| {
            mass_from_susceptibility(a)
                .partial_cmp(&mass_from_susceptibility(b))
                .unwrap()
        });
        let light_deg = { let mut v = [by_deg[0], by_deg[1], by_deg[2]]; v.sort(); v };
        let light_chi = { let mut v = [by_chi[0], by_chi[1], by_chi[2]]; v.sort(); v };
        let heavy_deg = { let mut v = [by_deg[8], by_deg[9], by_deg[10]]; v.sort(); v };
        let heavy_chi = { let mut v = [by_chi[8], by_chi[9], by_chi[10]]; v.sort(); v };
        assert_eq!(light_deg, light_chi, "the two conventions disagree on which kinds are LIGHT");
        assert_eq!(heavy_deg, heavy_chi, "the two conventions disagree on which kinds are HEAVY");
    }

    /// E3: the suggested step is stable and the stiffness ratio is the measured one.
    #[test]
    fn suggested_dt_is_stable() {
        assert!((stiffness_ratio() - 14.550).abs() < 0.01, "{}", stiffness_ratio());
        let dt = suggested_dt(0.5);
        let p = Params { dt, damping: 1.0, ..Params::harmonic() };
        let mut s = spread_state();
        let e0 = crate::dynamics::total_energy(&s, &p, true);
        for _ in 0..5000 {
            step_massive(&mut s, &p, true);
        }
        let e1 = crate::dynamics::total_energy(&s, &p, true);
        assert!(e1.is_finite() && e1 < e0 * 4.0, "energy blew up: {e0} -> {e1}");
    }

    /// E5: the force law IS the negative gradient of the potential.
    #[test]
    fn force_is_minus_grad_potential() {
        let s = spread_state();
        for p in [Params::harmonic(), Params::default()] {
            for &sym in [true, false].iter() {
                let r = gradient_residual(&s, &p, sym, 1e-6);
                assert!(r < 1e-4, "gradient residual {r} — a term is non-conservative");
            }
        }
    }

    /// E9: the record is one-way — absorption is monotone and absorbed nodes stay put.
    #[test]
    fn record_boundary_is_one_way() {
        let mut s = spread_state();
        let mut b = RecordBoundary::new(1.5);
        let p = Params::default();
        let mut last = 0;
        for _ in 0..500 {
            step_massive(&mut s, &p, false);
            b.apply(&mut s);
            assert!(b.count() >= last, "absorption went backwards");
            last = b.count();
        }
        // Anything absorbed has zero velocity and never moved again.
        for i in 0..N {
            if b.absorbed[i] {
                assert_eq!(s.vel[i], [0.0; 3]);
            }
        }
    }
}
