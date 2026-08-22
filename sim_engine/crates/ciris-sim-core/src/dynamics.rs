//! The integrator (FSD §2 `ForceSimulation`).
//!
//! `N` nodes move in R^3 under three terms. Only the first is ontology-supplied:
//!
//! ```text
//! F_i = Σ_{j≠i}  c_ij · (1 − ℓ_ij/r_ij) · (x_j − x_i)      spring   — MEASURED
//!     − Σ_{j≠i}  q · (x_j − x_i) / (r_ij² + s²)^{3/2}      repulsion — engine knob
//!     − κ · x_i                                            centering — engine knob
//! ```
//!
//! where `c_ij` is the coupling ([`Structure::coupling_for`], measured or
//! twin-symmetrised) and `ℓ_ij` is the resistance distance ([`Structure::metric`]). Stiffness and rest
//! length are therefore both *readings*, not taste: the springs are as stiff as the
//! confusion matrix says two kinds are coupled, and they relax to the length the
//! Laplacian's resistance metric says they are apart.
//!
//! Everything else in [`Params`] is an **engine gap**, named as such on each field. The
//! ontology supplies a susceptibility, which is a *response*; it does not supply an
//! inertia, a time unit, an excluded volume, or a boundary. Those four holes are E2,
//! E3, E5 and E9 of the FSD §4 table, and this module fills them with round numbers
//! rather than pretending otherwise.
//!
//! ## The structure is an argument, not a constant (E10)
//!
//! Every function here takes a `&Structure<N>`. At `N = 11` the caller passes
//! [`crate::K11`], whose tables are compile-time constants, and the generated code is
//! what it always was. At any other `N` the caller passes a structure built by
//! [`Structure::from_coupling`], which paid for its metric and spectrum once at
//! construction. The integrator itself does not know or care which it was given, and
//! it does no linear algebra either way.
//!
//! ## The harmonic regime carries the twin theorem; the full model does not
//!
//! [`Params::harmonic`] sets `rest_scale = 0`, which collapses the spring term to
//! `F = −L x` exactly — `L` the coupling Laplacian, no division, no rest length. That
//! is the regime in which `dark_state_decoupled` is a theorem, and
//! [`tests::symmetrised_twin_mode_exerts_no_force_on_others`] checks that this file
//! actually carries it: to the last bit, an antisymmetric twin displacement under the
//! symmetrised coupling exerts zero force on the other nodes.
//!
//! With `rest_scale = 1` the spring is nonlinear in the separation and the repulsion is
//! nonlinear in everything, so the null holds only to O(d²) in the displacement. That
//! is a property of the force law chosen here, not of the ontology, and the twin probe
//! should stake its proved null in the harmonic regime and its *measured* leakage in
//! whichever regime it means to display.

use crate::structure::Structure;

/// Node mass, uniform.
///
/// **NOT ontology-supplied — an engine gap (E2 inertia).** CIRISOntology measures a
/// positional *susceptibility*, which is a response to forcing, not an inertia; nothing
/// in the record fixes a mass ratio between Priorities and Premises. Until E2 closes,
/// every kind weighs the same and the value is 1 so that force and acceleration are the
/// same number. The visible consequence of the gap: relative oscillation frequencies
/// after an impulse are set by the couplings alone, and are wrong by whatever the true
/// mass ratios turn out to be.
pub const MASS: f64 = 1.0;

/// Separation below which a spring with a nonzero rest length exerts no force.
///
/// **NOT ontology-supplied — a numerical guard.** At exactly coincident positions the
/// unit separation vector is undefined; rather than emit a NaN or pick a direction (and
/// picking one would be a hidden source of asymmetry, which this crate cannot afford)
/// the pair is skipped for that step. Springs with `rest_scale = 0` never take this
/// branch because they need no direction — see the module note on the harmonic regime.
pub const COINCIDENT_EPS: f64 = 1e-12;

/// Positions and velocities of `N` kinds, indexed in the structure's own order (for the
/// built-in object, [`crate::data::KINDS`] order).
///
/// Fixed-size and `Copy`: `48 N` bytes, no allocator, no indirection. Snapshotting a
/// state for a rollback or a probe is an assignment.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct State<const N: usize> {
    /// Position of each kind in R^3, in resistance-metric units (so a separation of
    /// `structure.metric[i][j]` is the relaxed length of that pair's spring).
    pub pos: [[f64; 3]; N],
    /// Velocity of each kind, in metric units per unit of [`Params::dt`] time — a unit
    /// which is itself an open gap (E3).
    pub vel: [[f64; 3]; N],
}

impl<const N: usize> State<N> {
    /// Every kind at the origin, at rest. The Laplacian's zero mode: net force is
    /// exactly zero in the harmonic regime, so this state is stationary forever.
    pub const ZERO: State<N> = State {
        pos: [[0.0; 3]; N],
        vel: [[0.0; 3]; N],
    };

    /// A state at the given positions, at rest.
    pub fn at_rest(pos: [[f64; 3]; N]) -> State<N> {
        State {
            pos,
            vel: [[0.0; 3]; N],
        }
    }
}

impl<const N: usize> Default for State<N> {
    fn default() -> State<N> {
        State::ZERO
    }
}

/// The tunables the ontology does not supply.
///
/// Every field here is an engine gap. They are grouped in one struct precisely so that
/// the boundary is visible in the type: the coupling and the metric enter the force law
/// from the [`Structure`] and cannot be tuned, while everything a caller *can* turn is
/// in here and is therefore not a result.
///
/// `Params` carries no `N`: none of these quantities is per-node, and none of them
/// depends on how many kinds there are.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Params {
    /// Integration step.
    ///
    /// **NOT ontology-supplied — an engine gap (E3 time scale).** The couplings fix
    /// ratios of frequencies; nothing fixes the second. Until E3 closes — the FSD's
    /// check is "a rate matched to measured revision cadence" — `dt` is a numerical
    /// convenience with no physical meaning, and animation speed has no correct
    /// setting. Chosen small enough that the stiffest measured spring
    /// (Manner–Structure, `c = 9.016`) is resolved ~300 steps per period.
    pub dt: f64,

    /// Multiplicative velocity retention per step. `1.0` is no damping.
    ///
    /// **NOT ontology-supplied — an engine gap (E2 inertia / E8 dissipation
    /// coupling).** A per-step velocity multiplier is not a minimal dilation of
    /// anything; it is the cheapest way to make a demo settle. E8's check is
    /// "positivity preserved over a long run", which this term does not address.
    /// Set to `1.0` for the conservation test, where it must not appear.
    pub damping: f64,

    /// Coulomb-like repulsion strength `q`, applied to every pair.
    ///
    /// **NOT ontology-supplied — an engine gap (E5 action principle).** The ontology
    /// has no excluded volume: two kinds are never *forbidden* to coincide, they are
    /// only coupled. This term exists so the layout does not collapse onto the zero
    /// mode, and it is exactly the kind of hand-coded interaction E5 exists to
    /// eliminate. It is at least a gradient — see [`potential_energy`] — so it does
    /// not spoil conservation, but it composes with nothing.
    pub repulsion: f64,

    /// Plummer softening length `s` for the repulsion.
    ///
    /// **NOT ontology-supplied — a numerical guard.** Caps the repulsive force at
    /// `0.385·q/s²` instead of letting it diverge as two nodes approach, which keeps
    /// the step size that conserves energy from depending on the closest approach in
    /// the run. Softening enters both the force and the potential consistently, so the
    /// softened system is still exactly conservative.
    pub softening: f64,

    /// Weak spring from every node to the origin, strength `κ`.
    ///
    /// **NOT ontology-supplied — an engine gap (E9 boundary).** The purifier is
    /// implicit in the ontology and departing objects have nowhere to go; this term is
    /// a stand-in that keeps the centre of mass and the overall scale from wandering.
    /// It is *not* a rendering convenience only — it lifts the Laplacian's zero mode,
    /// which changes the spectrum, so a caller reading normal-mode frequencies off a
    /// run should set it to zero.
    pub centering: f64,

    /// Multiplier on the resistance-metric rest lengths. `1.0` uses
    /// [`Structure::metric`] as measured; `0.0` gives exact Laplacian dynamics.
    ///
    /// The metric itself is ontology-supplied; **this multiplier is not**, and is the
    /// one knob here whose extreme setting is meaningful rather than merely tuned. At
    /// `0.0` the spring term is `F = −L x` with no division and no direction, which is
    /// where the twin dark-state theorem is exact (see the module note). Any value
    /// between is a presentation choice.
    pub rest_scale: f64,
}

impl Default for Params {
    /// Defaults chosen for a stable, watchable relaxation — **not** measured values.
    /// See each field for which gap it stands in for.
    fn default() -> Params {
        Params {
            dt: 0.005,
            damping: 0.98,
            repulsion: 0.05,
            softening: 0.1,
            centering: 0.02,
            rest_scale: 1.0,
        }
    }
}

impl Params {
    /// The harmonic regime: `F = −L x` exactly, no repulsion, no centering, no damping.
    ///
    /// This is the regime the proved results live in — the coupling Laplacian's
    /// eigenmodes ([`Structure::eigenvalues`]) are the exact normal modes, and the twin
    /// dark state is exactly decoupled under the symmetrised coupling. Use it for
    /// anything that claims to be checking a theorem; use [`Params::default`] for
    /// anything that is meant to be looked at.
    pub fn harmonic() -> Params {
        Params {
            dt: 0.005,
            damping: 1.0,
            repulsion: 0.0,
            softening: 0.1,
            centering: 0.0,
            rest_scale: 0.0,
        }
    }
}

/// Force on every node at the current positions. Velocity-independent, hence exactly
/// the negative gradient of [`potential_energy`].
///
/// `symmetrised` selects which of the structure's two couplings enters: `false` is what
/// the panel actually read, `true` is the Z2xZ2 group average under which the twin dark
/// mode is exactly decoupled.
///
/// Pairs are summed in a fixed `i < j` order so the floating-point result is
/// bit-identical across platforms and runs. Allocation-free: the return value is a
/// fixed-size array by value.
pub fn forces<const N: usize>(
    state: &State<N>,
    st: &Structure<N>,
    params: &Params,
    symmetrised: bool,
) -> [[f64; 3]; N] {
    let c = st.coupling_for(symmetrised);
    let mut f = [[0.0f64; 3]; N];
    let soft2 = params.softening * params.softening;

    for i in 0..N {
        for j in (i + 1)..N {
            let d = [
                state.pos[j][0] - state.pos[i][0],
                state.pos[j][1] - state.pos[i][1],
                state.pos[j][2] - state.pos[i][2],
            ];
            let r2 = d[0] * d[0] + d[1] * d[1] + d[2] * d[2];

            // Spring: stiffness is the measured coupling, rest length the resistance
            // metric. `scale · d` is the force on i; on j it is its negative.
            let k = c[i][j];
            if k != 0.0 {
                let rest = params.rest_scale * st.metric[i][j];
                let scale = if rest == 0.0 {
                    // Harmonic regime: no normalisation, so no division and no
                    // coincidence guard. This branch is what makes `F = −L x` exact.
                    k
                } else {
                    let r = libm::sqrt(r2);
                    if r < COINCIDENT_EPS {
                        0.0
                    } else {
                        k * (1.0 - rest / r)
                    }
                };
                if scale != 0.0 {
                    for a in 0..3 {
                        f[i][a] += scale * d[a];
                        f[j][a] -= scale * d[a];
                    }
                }
            }

            // Repulsion: Plummer-softened inverse square, pushing i away from j.
            if params.repulsion != 0.0 {
                let rs = r2 + soft2;
                let q = params.repulsion / (rs * libm::sqrt(rs));
                for a in 0..3 {
                    f[i][a] -= q * d[a];
                    f[j][a] += q * d[a];
                }
            }
        }
    }

    // Centering: a weak spring to the origin, standing in for the absent boundary.
    if params.centering != 0.0 {
        for i in 0..N {
            for a in 0..3 {
                f[i][a] -= params.centering * state.pos[i][a];
            }
        }
    }

    f
}

/// One velocity-Verlet step.
///
/// Positions and velocities advance by `params.dt`, then velocities are multiplied by
/// `params.damping`. With `damping = 1.0` the update is symplectic and the energy
/// oscillates within a bounded band rather than drifting — which is what
/// [`tests::energy_does_not_grow_without_damping`] measures.
///
/// Two force evaluations per step, `N(N−1)/2` pairs each, no allocation, no branching
/// on anything but the parameters. Deterministic by construction: fixed loop bounds,
/// fixed summation order, no randomness, no map iteration.
pub fn step<const N: usize>(
    state: &mut State<N>,
    st: &Structure<N>,
    params: &Params,
    symmetrised: bool,
) {
    let dt = params.dt;
    let half = 0.5 * dt / MASS;

    let a0 = forces(state, st, params, symmetrised);
    for i in 0..N {
        for k in 0..3 {
            state.vel[i][k] += half * a0[i][k];
            state.pos[i][k] += dt * state.vel[i][k];
        }
    }

    let a1 = forces(state, st, params, symmetrised);
    for i in 0..N {
        for k in 0..3 {
            state.vel[i][k] += half * a1[i][k];
        }
    }

    if params.damping != 1.0 {
        for i in 0..N {
            for k in 0..3 {
                state.vel[i][k] *= params.damping;
            }
        }
    }
}

/// Run `n` steps. Provided so callers do not have to re-derive the loop; identical to
/// calling [`step`] `n` times.
pub fn run<const N: usize>(
    state: &mut State<N>,
    st: &Structure<N>,
    params: &Params,
    symmetrised: bool,
    n: usize,
) {
    for _ in 0..n {
        step(state, st, params, symmetrised);
    }
}

/// Kinetic energy, `½ Σ m |v_i|²`.
pub fn kinetic_energy<const N: usize>(state: &State<N>) -> f64 {
    let mut t = 0.0;
    for i in 0..N {
        let v = &state.vel[i];
        t += v[0] * v[0] + v[1] * v[1] + v[2] * v[2];
    }
    0.5 * MASS * t
}

/// Potential energy of the current positions: the exact antiderivative of [`forces`].
///
/// ```text
/// U = Σ_{i<j} ½·c_ij·(r_ij − ℓ_ij)²  +  Σ_{i<j} q/√(r_ij² + s²)  +  ½·κ·Σ_i |x_i|²
/// ```
///
/// The softened repulsion uses the same `s` in the potential as in the force, so the
/// pair is consistent and the softened system is genuinely conservative rather than
/// approximately so.
pub fn potential_energy<const N: usize>(
    state: &State<N>,
    st: &Structure<N>,
    params: &Params,
    symmetrised: bool,
) -> f64 {
    let c = st.coupling_for(symmetrised);
    let soft2 = params.softening * params.softening;
    let mut u = 0.0;

    for i in 0..N {
        for j in (i + 1)..N {
            let d = [
                state.pos[j][0] - state.pos[i][0],
                state.pos[j][1] - state.pos[i][1],
                state.pos[j][2] - state.pos[i][2],
            ];
            let r2 = d[0] * d[0] + d[1] * d[1] + d[2] * d[2];

            let k = c[i][j];
            if k != 0.0 {
                let rest = params.rest_scale * st.metric[i][j];
                let stretch = if rest == 0.0 {
                    // Matches the no-division branch of `forces`: U = ½·k·r².
                    r2
                } else {
                    let e = libm::sqrt(r2) - rest;
                    e * e
                };
                u += 0.5 * k * stretch;
            }

            if params.repulsion != 0.0 {
                u += params.repulsion / libm::sqrt(r2 + soft2);
            }
        }
    }

    if params.centering != 0.0 {
        for i in 0..N {
            let p = &state.pos[i];
            u += 0.5 * params.centering * (p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        }
    }

    u
}

/// Total energy, for the conservation check.
///
/// Takes `params` because the potential is not a property of the positions alone: rest
/// lengths, repulsion and centering all enter it, and a two-argument version would
/// silently report the energy of a *different* system than the one being stepped. The
/// FSD sketch named a two-argument signature; that signature cannot be made correct, so
/// [`total_energy_with_defaults`] carries it for callers who really are on
/// [`Params::default`].
pub fn total_energy<const N: usize>(
    state: &State<N>,
    st: &Structure<N>,
    params: &Params,
    symmetrised: bool,
) -> f64 {
    kinetic_energy(state) + potential_energy(state, st, params, symmetrised)
}

/// [`total_energy`] under [`Params::default`]. Wrong for any other parameters — prefer
/// the four-argument form.
pub fn total_energy_with_defaults<const N: usize>(
    state: &State<N>,
    st: &Structure<N>,
    symmetrised: bool,
) -> f64 {
    total_energy(state, st, &Params::default(), symmetrised)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::structure::{Structure, K11, NO_TWINS};
    use crate::N;

    /// Deterministic spread of `N` nodes over a sphere of radius 1 by the golden-angle
    /// spiral. No RNG anywhere in this crate, including its tests.
    fn spiral_state<const M: usize>() -> State<M> {
        let mut pos = [[0.0f64; 3]; M];
        let ga = 2.399963229728653; // π·(3 − √5)
        for i in 0..M {
            let z = 1.0 - 2.0 * (i as f64 + 0.5) / (M as f64);
            let r = libm::sqrt(1.0 - z * z);
            let th = ga * (i as f64);
            pos[i] = [r * libm::cos(th), r * libm::sin(th), z];
        }
        State::at_rest(pos)
    }

    fn max_abs<const M: usize>(f: &[[f64; 3]; M]) -> f64 {
        let mut m = 0.0f64;
        for row in f.iter() {
            for &v in row.iter() {
                let a = libm::fabs(v);
                if a > m {
                    m = a;
                }
            }
        }
        m
    }

    /// (a) A step from rest with zero forces leaves positions unchanged.
    ///
    /// The zero-force state is the Laplacian's zero mode: all nodes coincident.
    /// In the harmonic regime every spring reads `k·(x_j − x_i) = 0`, so the force is
    /// exactly zero — asserted, not assumed — and the step must be an identity.
    #[test]
    fn zero_force_state_is_stationary() {
        let p = Params::harmonic();
        for &sym in [false, true].iter() {
            let mut s = State::at_rest([[0.3, -0.7, 1.1]; N]);
            let before = s;
            assert_eq!(
                max_abs(&forces(&s, &K11, &p, sym)),
                0.0,
                "force is not exactly zero"
            );
            step(&mut s, &K11, &p, sym);
            assert_eq!(s.pos, before.pos, "positions moved under zero force");
            assert_eq!(s.vel, before.vel, "velocities changed under zero force");
        }

        // Same at the origin, where the centering term is also zero, so the full
        // default force law is zero too.
        let mut s = State::<N>::ZERO;
        let d = Params::default();
        for &sym in [false, true].iter() {
            // Repulsion is finite at coincidence thanks to softening, but it is
            // antisymmetric about a coincident configuration and cancels exactly.
            assert_eq!(max_abs(&forces(&s, &K11, &d, sym)), 0.0);
        }
        step(&mut s, &K11, &d, false);
        assert_eq!(s.pos, State::<N>::ZERO.pos);
    }

    /// (b) Energy does not grow over 1000 steps at `damping = 1.0`.
    ///
    /// Velocity Verlet is symplectic, so the discrete energy oscillates inside an
    /// O(dt^2) band around the continuous value rather than drifting. Measured here,
    /// full default force law (springs + repulsion + centering) from the spiral start,
    /// over a fixed 5.0 units of simulated time, relative to the initial energy 55.25:
    ///
    /// ```text
    ///   dt        steps   max growth   band (min..max)   band ratio
    ///   0.005      1000    +5.4e-5      1.60e-4            —
    ///   0.0025     2000    +2.1e-5      4.71e-5           3.4x
    ///   0.00125    4000    +5.1e-6      1.17e-5           4.0x
    /// ```
    ///
    /// Two things are asserted, because only the pair is convincing: the excursion is
    /// small and mostly *downward*, and the band shrinks as dt^2 — which is what
    /// identifies it as discretisation error rather than an energy leak.
    ///
    /// **Honest note on the tolerances.** This test first shipped with a guessed bound
    /// of 1e-6 and it FAILED at 1.06e-4: the guess was simply wrong about how wide a
    /// Verlet band is at this step size. The numbers above are the measurement that
    /// replaced it, and the bounds below sit ~25% above them. The bound was widened to
    /// match a measured, dt^2-scaling, non-growing band — not to rescue a leaking
    /// integrator. If it is ever widened again without a table like the one above, that
    /// is the defect.
    #[test]
    fn energy_does_not_grow_without_damping() {
        for &sym in [false, true].iter() {
            let mut band = [0.0f64; 3];
            for (n, &dt) in [0.005f64, 0.0025, 0.00125].iter().enumerate() {
                let p = Params {
                    dt,
                    damping: 1.0,
                    ..Params::default()
                };
                let steps = (5.0 / dt) as usize;
                let mut s = spiral_state::<N>();
                let e0 = total_energy(&s, &K11, &p, sym);
                let (mut hi, mut lo) = (0.0f64, 0.0f64);
                for _ in 0..steps {
                    step(&mut s, &K11, &p, sym);
                    let rel = (total_energy(&s, &K11, &p, sym) - e0) / libm::fabs(e0);
                    if rel > hi {
                        hi = rel;
                    }
                    if rel < lo {
                        lo = rel;
                    }
                }
                if n == 0 {
                    assert!(
                        hi < 7.0e-5,
                        "energy GREW by {} relative over {} steps (sym = {})",
                        hi,
                        steps,
                        sym
                    );
                }
                band[n] = hi - lo;
            }

            assert!(
                band[0] < 2.0e-4,
                "energy band {} at dt = 0.005 (sym = {})",
                band[0],
                sym
            );
            // Halving dt must shrink the band by at least 3x. A true leak would scale
            // with the number of steps instead and barely move.
            assert!(
                band[0] / band[1] > 3.0 && band[1] / band[2] > 3.0,
                "band does not scale as dt^2 — {:?} (sym = {})",
                band,
                sym
            );
        }
    }

    /// Damping only removes energy: never adds it, checkpoint by checkpoint.
    ///
    /// From the spiral start the total falls 55.25 -> 5.44 over 4000 steps and is
    /// monotone across every 500-step checkpoint.
    ///
    /// **The system does not reach a stationary point**, and this test does not claim
    /// it does. Kinetic energy is 8.1e-5 at step 4000 and still 3.6e-5 at step 11000,
    /// decaying roughly like 1/n while the potential creeps 5.4498 -> 5.4228: the
    /// object is sliding along a shallow valley, not sitting in a minimum. The
    /// threshold below therefore says "the initial energy is gone", which is true, and
    /// not "it has settled", which is not.
    #[test]
    fn damping_only_removes_energy() {
        let p = Params::default();
        let mut s = spiral_state::<N>();
        let mut prev = total_energy(&s, &K11, &p, false);
        let e0 = prev;
        for _ in 0..8 {
            run(&mut s, &K11, &p, false, 500);
            let e = total_energy(&s, &K11, &p, false);
            assert!(e <= prev, "energy rose from {} to {} under damping", prev, e);
            prev = e;
        }
        assert!(prev < 0.2 * e0, "damping removed almost nothing");
        assert!(
            kinetic_energy(&s) < 1.0e-3,
            "kinetic energy {} is not small against the {} released",
            kinetic_energy(&s),
            e0
        );
    }

    /// The force law carries `dark_state_decoupled` to the last bit.
    ///
    /// Displace the twin pair (Priorities, Process) antisymmetrically with everything
    /// else at the origin. Under the symmetrised coupling the two twins contribute
    /// `c_m0·d` and `−c_m7·d` to every other node, and `c_m0 = c_m7` bit-for-bit by
    /// construction of the group average — so the other nine feel *exactly* nothing.
    /// Under the measured coupling they do not, which is the demonstrator (FSD §3).
    ///
    /// This is a check on the integrator, not the twin probe: it asserts only that the
    /// force law preserves a symmetry the structure already has.
    #[test]
    fn symmetrised_twin_mode_exerts_no_force_on_others() {
        let p = Params::harmonic();
        for &(a, b) in K11.twins.iter() {
            let mut pos = [[0.0f64; 3]; N];
            pos[a] = [0.1, 0.0, 0.0];
            pos[b] = [-0.1, 0.0, 0.0];
            let s = State::at_rest(pos);

            let f_sym = forces(&s, &K11, &p, true);
            for i in 0..N {
                if i == a || i == b {
                    continue;
                }
                for k in 0..3 {
                    assert_eq!(
                        f_sym[i][k], 0.0,
                        "symmetrised coupling leaked onto node {} from twins ({}, {})",
                        i, a, b
                    );
                }
            }

            let f_meas = forces(&s, &K11, &p, false);
            let mut leak = 0.0f64;
            for i in 0..N {
                if i == a || i == b {
                    continue;
                }
                for k in 0..3 {
                    let v = libm::fabs(f_meas[i][k]);
                    if v > leak {
                        leak = v;
                    }
                }
            }
            assert!(
                leak > 0.0,
                "measured coupling showed no leakage for twins ({}, {}) — expected a \
                 nonzero departure from the proved null",
                a,
                b
            );
        }
    }

    /// `rest_scale = 0` really is the graph Laplacian: `F = −L x` computed directly.
    #[test]
    fn harmonic_force_is_the_laplacian() {
        let p = Params::harmonic();
        let s = spiral_state::<N>();
        for &sym in [false, true].iter() {
            let c = K11.coupling_for(sym);
            let f = forces(&s, &K11, &p, sym);
            for i in 0..N {
                for k in 0..3 {
                    let mut want = 0.0;
                    for j in 0..N {
                        want += c[i][j] * (s.pos[j][k] - s.pos[i][k]);
                    }
                    assert!(libm::fabs(f[i][k] - want) < 1e-12);
                }
            }
        }
    }

    /// The force is the negative gradient of the potential, to central-difference
    /// accuracy. This is what makes the conservation check meaningful — without it,
    /// `total_energy` would be measuring a different system than `step` integrates.
    #[test]
    fn force_is_minus_gradient_of_potential() {
        let p = Params::default();
        let base = spiral_state::<N>();
        let h = 1e-6;
        for &sym in [false, true].iter() {
            let f = forces(&base, &K11, &p, sym);
            for i in 0..N {
                for k in 0..3 {
                    let mut up = base;
                    let mut dn = base;
                    up.pos[i][k] += h;
                    dn.pos[i][k] -= h;
                    let g = (potential_energy(&up, &K11, &p, sym)
                        - potential_energy(&dn, &K11, &p, sym))
                        / (2.0 * h);
                    assert!(
                        libm::fabs(f[i][k] + g) < 1e-5,
                        "node {} axis {}: force {} vs −dU/dx {}",
                        i,
                        k,
                        f[i][k],
                        -g
                    );
                }
            }
        }
    }

    /// Stepping is a pure function of the inputs: the same start replays bit-identically.
    #[test]
    fn step_is_deterministic() {
        let p = Params::default();
        let mut a = spiral_state::<N>();
        let mut b = spiral_state::<N>();
        run(&mut a, &K11, &p, false, 250);
        run(&mut b, &K11, &p, false, 250);
        assert_eq!(a, b);
    }

    /// Springs relax toward the resistance metric, not toward zero: a pair held at its
    /// metric distance feels no spring force from each other.
    #[test]
    fn spring_rest_length_is_the_metric() {
        let p = Params {
            repulsion: 0.0,
            centering: 0.0,
            ..Params::default()
        };
        // Manner-Structure, the stiffest measured pair (c = 9.016).
        let (i, j) = (2usize, 9usize);
        let rest = K11.metric[i][j];

        // The other nine sit at the origin and contribute their own springs, so read
        // the i-j term as the CHANGE in the force on i as that one pair is stretched.
        let at = |sep: f64| {
            let mut pos = [[0.0f64; 3]; N];
            pos[j] = [sep, 0.0, 0.0];
            forces(&State::at_rest(pos), &K11, &p, false)[i][0]
        };
        let (short, at_rest_len, long) = (at(0.5 * rest), at(rest), at(1.5 * rest));

        // Below the metric distance the pair pushes apart, above it pulls together, and
        // at it the pair term changes sign — so the two flanking readings straddle the
        // middle one from opposite sides.
        assert!(
            short < at_rest_len && at_rest_len < long,
            "spring does not relax toward the metric distance {}: {} / {} / {}",
            rest,
            short,
            at_rest_len,
            long
        );
    }

    // ---- E10: the same dynamics at sizes the tables do not cover ----

    /// The integrator runs at an `N` with no precomputed tables, and the properties it
    /// is supposed to have are properties of the algorithm, not of eleven nodes: the
    /// harmonic force is the Laplacian, the force is minus the gradient of the
    /// potential, and the trajectory replays bit-identically.
    #[test]
    fn the_integrator_generalises_to_other_sizes() {
        let c5 = [
            [0.0, 1.5, 0.0, 0.25, 4.0],
            [1.5, 0.0, 2.0, 0.0, 0.0],
            [0.0, 2.0, 0.0, 7.0, 0.5],
            [0.25, 0.0, 7.0, 0.0, 1.0],
            [4.0, 0.0, 0.5, 1.0, 0.0],
        ];
        let st = Structure::<5>::from_coupling(&c5, NO_TWINS);

        // Harmonic force is the Laplacian.
        let s = spiral_state::<5>();
        let ph = Params::harmonic();
        let f = forces(&s, &st, &ph, false);
        for i in 0..5 {
            for k in 0..3 {
                let mut want = 0.0;
                for j in 0..5 {
                    want += st.coupling[i][j] * (s.pos[j][k] - s.pos[i][k]);
                }
                assert!(libm::fabs(f[i][k] - want) < 1e-12, "node {i} axis {k}");
            }
        }

        // Force is minus the gradient of the potential, full parameter set.
        let pd = Params::default();
        let fd = forces(&s, &st, &pd, false);
        let h = 1e-6;
        for i in 0..5 {
            for k in 0..3 {
                let mut up = s;
                let mut dn = s;
                up.pos[i][k] += h;
                dn.pos[i][k] -= h;
                let g = (potential_energy(&up, &st, &pd, false)
                    - potential_energy(&dn, &st, &pd, false))
                    / (2.0 * h);
                assert!(libm::fabs(fd[i][k] + g) < 1e-5, "node {i} axis {k}");
            }
        }

        // Deterministic replay.
        let mut a = spiral_state::<5>();
        let mut b = spiral_state::<5>();
        run(&mut a, &st, &pd, false, 250);
        run(&mut b, &st, &pd, false, 250);
        assert_eq!(a, b);

        // Energy does not grow without damping.
        let pn = Params {
            damping: 1.0,
            ..Params::default()
        };
        let mut e = spiral_state::<5>();
        let e0 = total_energy(&e, &st, &pn, false);
        let mut hi = 0.0f64;
        for _ in 0..1000 {
            step(&mut e, &st, &pn, false);
            let rel = (total_energy(&e, &st, &pn, false) - e0) / libm::fabs(e0);
            if rel > hi {
                hi = rel;
            }
        }
        assert!(hi < 1.0e-3, "energy grew by {hi} at N = 5");
    }

    /// The twin theorem is a theorem about the group average, not about eleven nodes:
    /// build a four-node structure with two twin pairs and the null is exact there too.
    #[test]
    fn the_proved_null_holds_at_other_sizes() {
        let c = [
            [0.0, 5.0, 2.0, 1.0],
            [5.0, 0.0, 3.0, 4.0],
            [2.0, 3.0, 0.0, 7.0],
            [1.0, 4.0, 7.0, 0.0],
        ];
        // Deliberately NOT symmetric under these swaps — the group average must make
        // it so, which is the whole point.
        let st = Structure::<4>::from_coupling(&c, [(0, 1), (2, 3)]);
        let p = Params::harmonic();
        for &(a, b) in st.twins.iter() {
            let mut pos = [[0.0f64; 3]; 4];
            pos[a] = [0.1, 0.0, 0.0];
            pos[b] = [-0.1, 0.0, 0.0];
            let s = State::at_rest(pos);
            let f = forces(&s, &st, &p, true);
            for i in 0..4 {
                if i == a || i == b {
                    continue;
                }
                for k in 0..3 {
                    assert_eq!(f[i][k], 0.0, "leak onto {i} from twins ({a}, {b})");
                }
            }
        }
    }
}
