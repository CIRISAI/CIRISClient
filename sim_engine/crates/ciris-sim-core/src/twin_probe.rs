//! The twin dark-state probe — the crate's one *proved* experiment.
//!
//! Displace the two members of a twin pair in opposite directions,
//! `x = amplitude * (e_a - e_b)/sqrt(2)`, and ask what the other nine nodes feel.
//!
//! Under a coupling that is symmetric in the pair the answer is **exactly zero**, and
//! it is zero for a reason rather than by cancellation of a fitted parameter:
//!
//! * `DarkState.dark_state_decoupled` — every row `k` outside `{a, b}` annihilates the
//!   mode, because twin symmetry forces `c_ka = c_kb` and the mode's two entries are
//!   equal and opposite. The rest of the graph cannot see the displacement at all.
//! * `DarkState.twin_dark_state` — so the mode is an exact eigenvector with eigenvalue
//!   `-c_ab`, over any commutative ring, sorry-free.
//!
//! That makes criterion (a) below a prediction with no fitted content: the null is a
//! theorem, and a nonzero reading on `tables::COUPLING_SYM` would mean the
//! symmetrisation is wrong, not that the physics is interesting.
//!
//! ## How far the measured object is from its own symmetry
//!
//! Under the MEASURED `data::COUPLING` the twins are only approximately twins, so the
//! mode leaks. `Core/DefectCoupling.lean` gives the leak a closed form. Writing
//! `w = e_a - e_b` (so `w . w = 2`) and `D = H - P H P` for the swap reflection `P`,
//! `defect_split` proves
//!
//! ```text
//!   tr(D^2) = 2 (H_aa - H_bb)^2  +  4 * sum_{c not in {a,b}} (H_ca - H_cb)^2
//!                 diagonal split                off-twin defect
//! ```
//!
//! and `trace_defect_sq` proves the dark-to-bright coupling is
//! `g_DB = ||(1 - d dT)(H d)|| = sqrt(tr(D^2)) / (2 sqrt 2)`. Symmetry breaking enters
//! through ONE vector and splits into exactly two parts — a diagonal part and an
//! off-twin part. [`g_db`] is that formula; [`ProbeResult::leakage`] is the same number
//! obtained the physical way, by measuring the residual, and a test checks they agree.
//!
//! ## FINDING: criterion (b) is NOT met by the tables in this crate, and why
//!
//! The FSD asks for a twin-1 / twin-0 leakage ratio in `[3.0, 4.6]`, from
//! CIRISOntology's measured `g_DB = 2.284` and `8.617` (ratio 3.77,
//! `DefectCoupling.lean` closing note). The tables here give **6.3166** — out of band.
//! Nothing is tuned to hide that; the cause is identified and it is in the data, not
//! in this module:
//!
//! `data::COUPLING` is documented as "symmetrised, **diagonal zeroed**". Zeroing the
//! diagonal deletes the `2 (H_aa - H_bb)^2` term above. The campaign's two twins carry
//! near-identical diagonal splits (3.710 and 3.685) but wildly different off-twin
//! defects (3.552 and 141.729), so that deleted term is **66% of twin 0's** `tr(D^2)`
//! and only **4.6% of twin 1's**. Removing it therefore lifts the ratio from 3.77 to
//! 6.32 — the asymmetry that survives is real, it is just being read without its
//! larger, nearly-equal component.
//!
//! Back-solving confirms the diagnosis rather than assuming it: the diagonal splits
//! implied by the published 2.284 and 8.617, given this crate's off-twin defects, are
//! **3.7098** and **3.6815**, which are the campaign's quoted 3.710 and 3.685 to within
//! 0.1%. Restoring them reproduces `2.28410` and `8.61737`, ratio **3.7728**, inside
//! the band. `campaign_ratio_recovered_when_diagonal_split_is_restored` asserts exactly
//! that, so the shortfall is pinned to a single missing input.
//!
//! **To close it properly**, `data::COUPLING` must ship the diagonal it currently
//! zeroes (or `data.rs` must carry the two splits alongside). That is another agent's
//! file. Until then [`CAMPAIGN_DIAGONAL_SPLIT`] carries the two numbers explicitly,
//! marked as imported constants rather than as anything this crate can derive.

use crate::sectors::dark_vector;
use crate::{Mat, Vec11, N};

/// `1/(2 sqrt 2)`, the normalisation in `g_DB = ||D||_F / (2 sqrt 2)`.
const INV_TWO_ROOT_TWO: f64 = 0.353_553_390_593_273_76;

/// The twin diagonal splits `|H_aa - H_bb|` measured by CIRISOntology's dark-state
/// campaign, indexed like [`crate::TWINS`].
///
/// **Imported, not derived.** `data::COUPLING` has its diagonal zeroed, so this crate
/// cannot compute these; they come from the campaign record quoted in
/// `Core/DefectCoupling.lean` ("3.710 vs 3.685, 0.7% apart"). They exist here only so
/// the module doc's diagnosis is executable — see
/// `campaign_ratio_recovered_when_diagonal_split_is_restored`.
pub const CAMPAIGN_DIAGONAL_SPLIT: [f64; 2] = [3.710, 3.685];

/// What the other nine nodes feel when a twin pair is driven antisymmetrically.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ProbeResult {
    /// `max |f_k|` over the nine nodes `k` outside the twin pair, where `f = M x`.
    ///
    /// Proved zero for a twin-symmetric `M` (`DarkState.dark_state_decoupled`). The
    /// reading does not depend on whether forces are taken from the coupling matrix or
    /// from its Laplacian: outside the pair `x_k = 0`, so the diagonal degree term
    /// drops out and the two differ only in overall sign.
    pub max_other_displacement: f64,
    /// `||(1 - d dT)(M x)||` — the whole leak, not just its largest single entry, and
    /// the quantity `Core/DefectCoupling.lean` calls `g_DB`. Equal to
    /// `|amplitude| * g_db(M, twin)`.
    pub leakage: f64,
}

/// `sum_{c not in {a,b}} (M_ca - M_cb)^2` — the off-twin half of `defect_split`.
///
/// This is the part of the symmetry defect that a zero-diagonal matrix can still
/// report, and the only part this crate's tables carry.
///
/// # Panics
/// If `twin >= 2`.
pub fn off_twin_defect_sq(m: &Mat, twin: usize) -> f64 {
    assert!(twin < crate::TWINS.len(), "twin index out of range");
    let (a, b) = crate::TWINS[twin];
    let mut acc = 0.0f64;
    for c in 0..N {
        if c == a || c == b {
            continue;
        }
        let diff = m[c][a] - m[c][b];
        acc += diff * diff;
    }
    acc
}

/// `g_DB = sqrt(2 * split^2 + 4 * off_defect_sq) / (2 sqrt 2)` — `defect_split` and
/// `trace_defect_sq` composed, with the diagonal split supplied separately.
///
/// Split out from [`g_db`] because `data::COUPLING` cannot supply the split itself
/// (its diagonal is zeroed); see the module doc.
pub fn g_db_of(off_defect_sq: f64, diagonal_split: f64) -> f64 {
    libm::sqrt(2.0 * diagonal_split * diagonal_split + 4.0 * off_defect_sq) * INV_TWO_ROOT_TWO
}

/// The dark-to-bright coupling of a twin pair, read off `m` alone.
///
/// Uses `m`'s own diagonal for the split, so it is correct for any matrix that carries
/// one. For this crate's zero-diagonal tables the split term vanishes and the result is
/// the off-twin part only — which is precisely the gap documented in the module doc.
///
/// # Panics
/// If `twin >= 2`.
pub fn g_db(m: &Mat, twin: usize) -> f64 {
    let (a, b) = crate::TWINS[twin];
    g_db_of(off_twin_defect_sq(m, twin), m[a][a] - m[b][b])
}

/// Drive twin pair `twin` antisymmetrically at `amplitude` and report what leaks.
///
/// `symmetrised` selects the matrix: `true` uses `tables::COUPLING_SYM`, for which the
/// null is a theorem; `false` uses the measured `data::COUPLING`, for which the leak is
/// a measurement.
///
/// Both outputs are homogeneous of degree one in `amplitude`, so a ratio between two
/// twins is amplitude-free.
///
/// # Panics
/// If `twin >= 2`.
pub fn probe(twin: usize, amplitude: f64, symmetrised: bool) -> ProbeResult {
    let m: &Mat = if symmetrised {
        &crate::tables::COUPLING_SYM
    } else {
        &crate::COUPLING
    };
    let (a, b) = crate::TWINS[twin];
    let d = dark_vector(twin);

    // x = amplitude * d, and f = M x. Fixed loop order for bit-identical replay.
    let mut f: Vec11 = [0.0f64; N];
    for i in 0..N {
        let mut acc = 0.0f64;
        for j in 0..N {
            acc += m[i][j] * (amplitude * d[j]);
        }
        f[i] = acc;
    }

    let mut max_other = 0.0f64;
    for (k, &fk) in f.iter().enumerate() {
        if k == a || k == b {
            continue;
        }
        let v = libm::fabs(fk);
        if v > max_other {
            max_other = v;
        }
    }

    // The leak is everything in f that is not along the dark mode itself; the
    // component along d is the eigenvalue and carries no information about the rest
    // of the graph.
    let mut along = 0.0f64;
    for i in 0..N {
        along += d[i] * f[i];
    }
    let mut residual_sq = 0.0f64;
    for i in 0..N {
        let r = f[i] - along * d[i];
        residual_sq += r * r;
    }

    ProbeResult {
        max_other_displacement: max_other,
        leakage: libm::sqrt(residual_sq),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::data::COUPLING;
    use crate::tables::COUPLING_SYM;

    /// **Acceptance criterion (a)** — the proved null.
    ///
    /// `DarkState.dark_state_decoupled` says every row outside the pair annihilates the
    /// mode, so this must hold for both twins, at any amplitude, exactly.
    #[test]
    fn symmetrised_probe_is_silent_to_the_rest_of_the_graph() {
        for twin in 0..2 {
            for &amp in &[1.0f64, 7.5, -3.25, 1e6] {
                let r = probe(twin, amp, true);
                assert!(
                    r.max_other_displacement < 1e-12,
                    "twin {twin} at amplitude {amp}: max other force {} — the symmetrised \
                     coupling must give the proved null",
                    r.max_other_displacement
                );
                // `leakage` subtracts the along-mode component, whose two entries are
                // of order `amplitude`, so its error floor is RELATIVE to amplitude
                // (~1.6e-10 at 1e6) while the null itself is exact.
                // `max_other_displacement` above needs no such allowance: it never
                // touches the pair's own entries, and it reads exactly 0.
                let floor = 1e-12 * libm::fabs(amp).max(1.0);
                assert!(
                    r.leakage < floor,
                    "twin {twin} at amplitude {amp}: leakage {}",
                    r.leakage
                );
            }
        }
    }

    /// The eigenvalue half of `twin_dark_state`: on the symmetrised coupling the pair's
    /// own two entries are `-c_ab` times the mode.
    #[test]
    fn symmetrised_probe_returns_the_predicted_eigenvalue() {
        for twin in 0..2 {
            let (a, b) = crate::TWINS[twin];
            let d = dark_vector(twin);
            let mut f = [0.0f64; N];
            for i in 0..N {
                for j in 0..N {
                    f[i] += COUPLING_SYM[i][j] * d[j];
                }
            }
            let lambda = -COUPLING_SYM[a][b];
            for i in 0..N {
                assert!(
                    libm::fabs(f[i] - lambda * d[i]) < 1e-12,
                    "twin {twin} node {i}: {} != {}",
                    f[i],
                    lambda * d[i]
                );
            }
        }
    }

    /// The physical residual and the closed form agree — a float check of
    /// `DefectCoupling.trace_defect_sq` on the object's own matrices.
    #[test]
    fn leakage_matches_the_closed_form() {
        for twin in 0..2 {
            let measured = probe(twin, 1.0, false).leakage;
            let closed = g_db(&COUPLING, twin);
            assert!(
                libm::fabs(measured - closed) < 1e-12,
                "twin {twin}: residual {measured} != g_DB {closed}"
            );
        }
    }

    /// Both outputs scale linearly with amplitude, so twin ratios are amplitude-free.
    #[test]
    fn probe_is_linear_in_amplitude() {
        let one = probe(1, 1.0, false);
        let ten = probe(1, 10.0, false);
        assert!(libm::fabs(ten.leakage - 10.0 * one.leakage) < 1e-9);
        assert!(
            libm::fabs(ten.max_other_displacement - 10.0 * one.max_other_displacement) < 1e-9
        );
    }

    /// **Acceptance criterion (b), AS MEASURED — the FSD band `[3.0, 4.6]` is NOT met.**
    ///
    /// This test pins the number the shipped tables actually produce (6.3166) instead
    /// of asserting the band, because the band cannot be reached from a zero-diagonal
    /// coupling and tuning it to pass would hide a real gap in `data.rs`. The cause is
    /// diagnosed in the module doc and demonstrated by the next test. When
    /// `data::COUPLING` ships its diagonal, this test should be replaced by the band.
    #[test]
    fn measured_leakage_ratio_is_out_of_the_fsd_band_because_the_diagonal_is_zeroed() {
        let t0 = probe(0, 1.0, false);
        let t1 = probe(1, 1.0, false);

        // The individual readings, pinned.
        assert!(libm::fabs(t0.leakage - 1.332_692_568_223) < 1e-9, "{}", t0.leakage);
        assert!(libm::fabs(t1.leakage - 8.418_095_424_559) < 1e-9, "{}", t1.leakage);
        assert!(
            libm::fabs(t0.max_other_displacement - 1.028_446_623_252) < 1e-9,
            "{}",
            t0.max_other_displacement
        );
        assert!(
            libm::fabs(t1.max_other_displacement - 6.375_497_499_211) < 1e-9,
            "{}",
            t1.max_other_displacement
        );

        let ratio = t1.leakage / t0.leakage;
        assert!(
            libm::fabs(ratio - 6.316_607_164_534) < 1e-6,
            "measured leakage ratio {ratio}"
        );
        // Recorded, not asserted as a success: this is outside [3.0, 4.6].
        assert!(ratio > 4.6, "ratio {ratio} — if this now sits in band, the data changed");
    }

    /// **The diagnosis, executable.** Restoring the campaign's diagonal splits to the
    /// off-twin defects this crate does carry reproduces CIRISOntology's published
    /// `g_DB = 2.284` and `8.617` and puts the ratio at 3.7728, inside `[3.0, 4.6]`.
    ///
    /// So the shortfall in the test above is one missing input — the zeroed diagonal —
    /// and not a defect in the probe.
    #[test]
    fn campaign_ratio_recovered_when_diagonal_split_is_restored() {
        let g0 = g_db_of(off_twin_defect_sq(&COUPLING, 0), CAMPAIGN_DIAGONAL_SPLIT[0]);
        let g1 = g_db_of(off_twin_defect_sq(&COUPLING, 1), CAMPAIGN_DIAGONAL_SPLIT[1]);
        assert!(libm::fabs(g0 - 2.284) < 5e-4, "twin 0 g_DB {g0}, campaign 2.284");
        assert!(libm::fabs(g1 - 8.617) < 5e-4, "twin 1 g_DB {g1}, campaign 8.617");

        let ratio = g1 / g0;
        assert!(
            (3.0..=4.6).contains(&ratio),
            "restored ratio {ratio} should sit in the FSD band [3.0, 4.6]"
        );
        assert!(libm::fabs(ratio - 3.772_772_323_086) < 1e-6, "restored ratio {ratio}");
    }

    /// The off-twin defects are wildly unequal while the diagonal splits are nearly
    /// equal — this is *why* deleting the diagonal moves twin 0 so much more than
    /// twin 1, and it is the two-dimensional symmetry breaking that
    /// `DefectCoupling.defect_three_gen_collapse` shows three-generation flavour
    /// structurally cannot carry.
    #[test]
    fn the_two_twins_break_symmetry_in_different_directions() {
        let off0 = off_twin_defect_sq(&COUPLING, 0);
        let off1 = off_twin_defect_sq(&COUPLING, 1);
        assert!(libm::fabs(off0 - 3.552_138_962_793) < 1e-9, "{off0}");
        assert!(libm::fabs(off1 - 141.728_661_153_960) < 1e-9, "{off1}");

        // Diagonal share of tr(D^2) = 2 s^2 / (2 s^2 + 4 off): 66% vs 4.6%.
        let share = |s: f64, off: f64| 2.0 * s * s / (2.0 * s * s + 4.0 * off);
        let s0 = share(CAMPAIGN_DIAGONAL_SPLIT[0], off0);
        let s1 = share(CAMPAIGN_DIAGONAL_SPLIT[1], off1);
        assert!(s0 > 0.60 && s0 < 0.70, "twin 0 diagonal share {s0}");
        assert!(s1 > 0.03 && s1 < 0.06, "twin 1 diagonal share {s1}");
    }

    /// The symmetrised coupling has no defect at all — the closed form agrees with the
    /// theorem it mechanizes.
    #[test]
    fn symmetrised_coupling_has_zero_defect() {
        for twin in 0..2 {
            assert!(off_twin_defect_sq(&COUPLING_SYM, twin) < 1e-24);
            assert!(g_db(&COUPLING_SYM, twin) < 1e-12);
        }
    }
}
