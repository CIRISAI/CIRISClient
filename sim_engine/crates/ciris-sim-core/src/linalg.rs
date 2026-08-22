//! Runtime linear algebra for the general-`N` path (FSD §10.2, gap E10).
//!
//! At `N = 11` the object is constant, so every derived quantity is a compile-time
//! table ([`crate::tables`]) and this module is never called. At any other `N` the
//! tables do not exist and the same quantities must be COMPUTED — the metric, the
//! Laplacian spectrum, the susceptibility. That is the honest cost of generality and
//! the FSD names it: "derived tables can no longer be precomputed".
//!
//! ## The constraints this module is written under
//!
//! `no_std`, no allocator, no `unsafe`, and bit-identical replay across
//! `wasm32-unknown-unknown`, `wasm32-wasip1` and native. Together those rule out every
//! library eigensolver and every workspace-allocating algorithm, and they rule out
//! anything whose control flow depends on a pivot search over magnitudes. What is left
//! is **cyclic Jacobi**:
//!
//! * it needs exactly two `N x N` scratch arrays, both on the stack;
//! * its sweep order is fixed (`p` ascending, then `q > p` ascending) rather than
//!   chosen by looking at the data, so two runs take the same rotations in the same
//!   order and accumulate the same rounding;
//! * it is backward stable and, unlike QR, accurate in the *relative* sense on the
//!   small eigenvalues — which matters here because a Laplacian's zero mode is the
//!   thing the pseudo-inverse must discard, and discarding it correctly requires
//!   knowing it is small.
//!
//! Every numerical constant below is arbitrary in the sense that it was chosen rather
//! than derived, and each is named as such with the reasoning that fixed it.

/// Maximum number of cyclic Jacobi sweeps before [`jacobi_eigen`] gives up.
///
/// **A chosen limit, not a derived one.** Cyclic Jacobi converges quadratically once
/// the off-diagonal is small; the textbook figure is 6-10 sweeps for well-scaled
/// matrices of this size and the count grows slowly with `N`. **Measured: the built-in
/// object's Laplacian converges in 6 sweeps at `N = 11`.** Sixty is ten times that, so
/// hitting the limit means the input was not what this routine assumes — not that the
/// tolerance was a little tight. A run that hits the limit reports `converged = false`
/// rather than silently returning a half-diagonalised matrix.
pub const JACOBI_MAX_SWEEPS: usize = 60;

/// Convergence tolerance for [`jacobi_eigen`], **relative** to the Frobenius norm of
/// the input.
///
/// **A chosen threshold.** The sweep stops when the off-diagonal Frobenius norm has
/// fallen to `JACOBI_TOL * ||A||_F`. At `1e-15` this is a few units in the last place
/// of a double, i.e. the point past which further sweeps only move rounding error
/// around. It is relative so that the same tolerance is meaningful whether the
/// couplings are of order 1 or of order 10^6.
pub const JACOBI_TOL: f64 = 1e-15;

/// Above this `|theta|` the tangent is computed as `1/(2*theta)` instead of
/// `sign/(|theta| + sqrt(theta^2 + 1))`.
///
/// **A chosen overflow guard.** `theta*theta` overflows a double at `|theta| ~ 1.3e154`;
/// the two expressions agree to full precision long before that, so the switch is made
/// at `1e150` where the asymptotic form is exact to the last bit and the square is
/// still finite.
pub const JACOBI_LARGE_THETA: f64 = 1e150;

/// Components smaller than this in absolute value are skipped when fixing an
/// eigenvector's overall sign.
///
/// **A chosen threshold.** Jacobi returns each eigenvector up to a sign; to make the
/// output canonical, the first component larger than this is forced positive. The
/// threshold exists only so that the sign is not decided by a component that is pure
/// rounding noise, which would make the convention unstable under a perturbation far
/// below the accuracy of the result.
pub const SIGN_PIVOT_EPS: f64 = 1e-12;

/// Eigenvalues below `PSEUDOINVERSE_EPS * max|lambda|` are treated as exactly zero by
/// [`pseudo_inverse_psd`].
///
/// **A chosen threshold, and the one that most deserves the label.** A graph
/// Laplacian's kernel is spanned by one vector per connected component; those
/// eigenvalues are zero in exact arithmetic and come back at `~1e-16 * lambda_max`
/// from any floating-point solver. The cutoff must sit above that noise and below the
/// smallest genuine eigenvalue — the algebraic connectivity. `1e-9` relative leaves
/// seven orders of headroom on the noise side, and it is a *relative* cutoff so that
/// rescaling every coupling by a constant does not change which modes are discarded.
///
/// **The failure mode it cannot detect:** a graph that is connected but only just
/// (algebraic connectivity below `1e-9 * lambda_max`) has its Fiedler mode discarded
/// as if the graph were disconnected, and the resistance distance across that
/// near-cut comes back finite instead of enormous. That is a real limit of the general
/// path, not of this constant, and the fix is to check connectivity rather than to
/// lower the epsilon.
pub const PSEUDOINVERSE_EPS: f64 = 1e-9;

/// The result of a symmetric eigendecomposition.
///
/// `values` are ascending. `vectors[m]` is the unit eigenvector of `values[m]` — i.e.
/// the array is indexed `[mode][node]`, matching [`crate::tables::EIGENVECTORS`].
///
/// `converged` is reported rather than asserted: a caller that needs the guarantee
/// must look, and a caller that is exploring gets its numbers plus the warning.
#[derive(Clone, Debug)]
pub struct Eigen<const N: usize> {
    /// Eigenvalues, ascending.
    pub values: [f64; N],
    /// Eigenvectors, `vectors[mode][node]`, each of unit norm.
    pub vectors: [[f64; N]; N],
    /// Sweeps actually performed.
    pub sweeps: usize,
    /// Whether the off-diagonal fell below [`JACOBI_TOL`] within [`JACOBI_MAX_SWEEPS`].
    pub converged: bool,
}

/// Cyclic Jacobi eigendecomposition of a **symmetric** matrix.
///
/// The input is assumed symmetric and both triangles are read; an asymmetric argument
/// produces meaningless output rather than an error, because checking would cost an
/// `N^2` pass on every call for a condition the callers in this crate establish by
/// construction (a Laplacian of a symmetric coupling).
///
/// Determinism: the rotation order is row-cyclic and fixed — `p` ascending, `q > p`
/// ascending — with no pivot search, so the sequence of rotations depends only on
/// which entries are exactly zero and not on any comparison of magnitudes. The only
/// data-dependent branch is `a[p][q] == 0.0`, which is exact.
///
/// Cost: `O(N^3)` per sweep, `2 N^2` doubles of stack, no allocation.
pub fn jacobi_eigen<const N: usize>(m: &[[f64; N]; N]) -> Eigen<N> {
    let mut a = *m;
    let mut v = [[0.0f64; N]; N];
    for i in 0..N {
        v[i][i] = 1.0;
    }

    // Convergence target, fixed from the input so it cannot drift with the iteration.
    let mut frob_sq = 0.0f64;
    for i in 0..N {
        for j in 0..N {
            frob_sq += a[i][j] * a[i][j];
        }
    }
    let tol_sq = JACOBI_TOL * JACOBI_TOL * frob_sq;

    let mut sweeps = 0usize;
    let mut converged = false;
    while sweeps < JACOBI_MAX_SWEEPS {
        let mut off_sq = 0.0f64;
        for p in 0..N {
            for q in (p + 1)..N {
                off_sq += a[p][q] * a[p][q];
            }
        }
        if off_sq <= tol_sq {
            converged = true;
            break;
        }

        for p in 0..N {
            for q in (p + 1)..N {
                let apq = a[p][q];
                if apq == 0.0 {
                    continue;
                }
                let app = a[p][p];
                let aqq = a[q][q];

                // Annihilate a[p][q] with the rotation that keeps |t| <= 1 (the
                // smaller root), which is what makes the sweep numerically stable.
                let theta = 0.5 * (aqq - app) / apq;
                let t = if libm::fabs(theta) > JACOBI_LARGE_THETA {
                    0.5 / theta
                } else {
                    let root = libm::sqrt(theta * theta + 1.0);
                    if theta >= 0.0 {
                        1.0 / (theta + root)
                    } else {
                        -1.0 / (-theta + root)
                    }
                };
                let c = 1.0 / libm::sqrt(t * t + 1.0);
                let s = t * c;

                a[p][p] = app - t * apq;
                a[q][q] = aqq + t * apq;
                a[p][q] = 0.0;
                a[q][p] = 0.0;
                for k in 0..N {
                    if k == p || k == q {
                        continue;
                    }
                    let akp = a[k][p];
                    let akq = a[k][q];
                    let np = c * akp - s * akq;
                    let nq = s * akp + c * akq;
                    a[k][p] = np;
                    a[p][k] = np;
                    a[k][q] = nq;
                    a[q][k] = nq;
                }
                for k in 0..N {
                    let vkp = v[k][p];
                    let vkq = v[k][q];
                    v[k][p] = c * vkp - s * vkq;
                    v[k][q] = s * vkp + c * vkq;
                }
            }
        }
        sweeps += 1;
    }

    // Transpose into [mode][node] order and sort ascending. Insertion sort: no
    // allocation, and stable, so equal eigenvalues keep their rotation order.
    let mut values = [0.0f64; N];
    let mut vectors = [[0.0f64; N]; N];
    for mode in 0..N {
        values[mode] = a[mode][mode];
        for node in 0..N {
            vectors[mode][node] = v[node][mode];
        }
    }
    for i in 1..N {
        let key_val = values[i];
        let key_vec = vectors[i];
        let mut j = i;
        while j > 0 && values[j - 1] > key_val {
            values[j] = values[j - 1];
            vectors[j] = vectors[j - 1];
            j -= 1;
        }
        values[j] = key_val;
        vectors[j] = key_vec;
    }

    // Canonical sign: first component above SIGN_PIVOT_EPS is positive. Both signs
    // span the same eigenspace, so this is cosmetic — but fixing it means two runs
    // that agree on the subspace also agree on the array, which is what replay needs.
    for mode in 0..N {
        for node in 0..N {
            let x = vectors[mode][node];
            if libm::fabs(x) > SIGN_PIVOT_EPS {
                if x < 0.0 {
                    for k in 0..N {
                        vectors[mode][k] = -vectors[mode][k];
                    }
                }
                break;
            }
        }
    }

    Eigen {
        values,
        vectors,
        sweeps,
        converged,
    }
}

/// The graph Laplacian `L = D - C` of a symmetric non-negative coupling.
///
/// Any diagonal in `C` is ignored: a self-coupling is a self-spring, which a force
/// simulation does not have (FSD §12), and including it would put a spurious constant
/// on `L`'s diagonal.
pub fn laplacian<const N: usize>(coupling: &[[f64; N]; N]) -> [[f64; N]; N] {
    let deg = weighted_degree(coupling);
    let mut l = [[0.0f64; N]; N];
    for i in 0..N {
        for j in 0..N {
            l[i][j] = if i == j { deg[i] } else { -coupling[i][j] };
        }
    }
    l
}

/// Weighted degree `m_i = sum_{j != i} c_ij` — the E2 mass (FSD §14).
///
/// The diagonal is excluded for the reason given on [`laplacian`]. Summation runs in
/// ascending `j` so the float accumulation is fixed.
pub fn weighted_degree<const N: usize>(coupling: &[[f64; N]; N]) -> [f64; N] {
    let mut d = [0.0f64; N];
    for i in 0..N {
        let mut acc = 0.0f64;
        for j in 0..N {
            if i != j {
                acc += coupling[i][j];
            }
        }
        d[i] = acc;
    }
    d
}

/// Moore-Penrose pseudo-inverse of a symmetric positive-semidefinite matrix, from its
/// eigendecomposition: invert every eigenvalue above the [`PSEUDOINVERSE_EPS`] cutoff
/// and drop the rest.
///
/// For a Laplacian the dropped modes are exactly the kernel — one per connected
/// component — so the result is the Laplacian pseudo-inverse whose diagonal is the
/// positional susceptibility and whose entries give the resistance distance.
pub fn pseudo_inverse_psd<const N: usize>(e: &Eigen<N>) -> [[f64; N]; N] {
    let mut scale = 0.0f64;
    for m in 0..N {
        let a = libm::fabs(e.values[m]);
        if a > scale {
            scale = a;
        }
    }
    let cutoff = PSEUDOINVERSE_EPS * scale;

    // Only the upper triangle is accumulated; the lower is mirrored. The result is a
    // symmetric matrix by construction rather than by luck — accumulating both
    // triangles independently would let `p[i][j]` and `p[j][i]` differ in their last
    // bit, and the metric derived from it would then fail its own symmetry check.
    let mut p = [[0.0f64; N]; N];
    for m in 0..N {
        if e.values[m] <= cutoff {
            continue;
        }
        let inv = 1.0 / e.values[m];
        for i in 0..N {
            let vi = e.vectors[m][i] * inv;
            for j in i..N {
                p[i][j] += vi * e.vectors[m][j];
            }
        }
    }
    for i in 0..N {
        for j in (i + 1)..N {
            p[j][i] = p[i][j];
        }
    }
    p
}

/// Resistance distance from a Laplacian pseudo-inverse:
/// `R_ij = L+_ii + L+_jj - 2 L+_ij`.
///
/// This is the crate's metric — the rest length of every spring. On a connected graph
/// it is a genuine metric (FSD §1.6 measures 0 triangle violations on the object); on
/// a disconnected one, pairs in different components come back as a finite number that
/// means nothing, because the kernel those pairs need was discarded as noise. Callers
/// working with arbitrary couplings must establish connectivity themselves.
///
/// Each pair is evaluated once and mirrored, so `r[i][j] == r[j][i]` to the last bit.
/// Evaluating both would sum the same three terms in two orders and could disagree in
/// the final ulp, which would make a symmetric quantity read as asymmetric.
pub fn resistance_distance<const N: usize>(pinv: &[[f64; N]; N]) -> [[f64; N]; N] {
    let mut r = [[0.0f64; N]; N];
    for i in 0..N {
        for j in (i + 1)..N {
            let d = pinv[i][i] + pinv[j][j] - 2.0 * pinv[i][j];
            r[i][j] = d;
            r[j][i] = d;
        }
    }
    r
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Cyclic Jacobi on a matrix whose spectrum is known in closed form: the path
    /// graph `P3` with unit edges has Laplacian eigenvalues 0, 1, 3.
    #[test]
    fn jacobi_recovers_a_known_spectrum() {
        let c = [[0.0, 1.0, 0.0], [1.0, 0.0, 1.0], [0.0, 1.0, 0.0]];
        let e = jacobi_eigen(&laplacian(&c));
        assert!(e.converged, "did not converge in {} sweeps", e.sweeps);
        let want = [0.0, 1.0, 3.0];
        for m in 0..3 {
            assert!(
                libm::fabs(e.values[m] - want[m]) < 1e-14,
                "mode {m}: {} != {}",
                e.values[m],
                want[m]
            );
        }
        // Eigenvectors are orthonormal.
        for a in 0..3 {
            for b in 0..3 {
                let mut dot = 0.0;
                for i in 0..3 {
                    dot += e.vectors[a][i] * e.vectors[b][i];
                }
                let want = if a == b { 1.0 } else { 0.0 };
                assert!(libm::fabs(dot - want) < 1e-14, "<{a},{b}> = {dot}");
            }
        }
    }

    /// **Required small-N sanity check.** Resistances in series add: on a path of three
    /// nodes with unit couplings the endpoints are exactly 2.0 apart, and each edge is
    /// exactly 1.0.
    #[test]
    fn path_graph_resistances_add_in_series() {
        let c = [[0.0, 1.0, 0.0], [1.0, 0.0, 1.0], [0.0, 1.0, 0.0]];
        let e = jacobi_eigen(&laplacian(&c));
        let r = resistance_distance(&pseudo_inverse_psd(&e));
        assert!(libm::fabs(r[0][2] - 2.0) < 1e-12, "endpoints: {}", r[0][2]);
        assert!(libm::fabs(r[0][1] - 1.0) < 1e-12, "first edge: {}", r[0][1]);
        assert!(libm::fabs(r[1][2] - 1.0) < 1e-12, "second edge: {}", r[1][2]);
        assert_eq!(r[0][0], 0.0);
        // Symmetric.
        for i in 0..3 {
            for j in 0..3 {
                assert_eq!(r[i][j], r[j][i]);
            }
        }
    }

    /// Two unit resistors in parallel read 1/2 — the other half of the series/parallel
    /// pair, so the metric is not merely reproducing hop counts.
    #[test]
    fn parallel_resistances_halve() {
        // A triangle of unit edges: between any two nodes, one direct edge in parallel
        // with two in series, giving 1 || 2 = 2/3.
        let c = [[0.0, 1.0, 1.0], [1.0, 0.0, 1.0], [1.0, 1.0, 0.0]];
        let e = jacobi_eigen(&laplacian(&c));
        let r = resistance_distance(&pseudo_inverse_psd(&e));
        assert!(libm::fabs(r[0][1] - 2.0 / 3.0) < 1e-12, "{}", r[0][1]);

        // A doubled edge: two unit resistors in parallel between the same pair, read
        // as a single edge of weight 2, is 1/2.
        let d = [[0.0, 2.0], [2.0, 0.0]];
        let e2 = jacobi_eigen(&laplacian(&d));
        let r2 = resistance_distance(&pseudo_inverse_psd(&e2));
        assert!(libm::fabs(r2[0][1] - 0.5) < 1e-12, "{}", r2[0][1]);
    }

    /// The pseudo-inverse satisfies the Moore-Penrose conditions that matter here:
    /// `L P L = L` and `P L P = P`, on a graph whose kernel is one-dimensional.
    #[test]
    fn pseudo_inverse_satisfies_moore_penrose() {
        let c = [
            [0.0, 2.0, 0.5, 0.0],
            [2.0, 0.0, 1.0, 3.0],
            [0.5, 1.0, 0.0, 0.25],
            [0.0, 3.0, 0.25, 0.0],
        ];
        let l = laplacian(&c);
        let e = jacobi_eigen(&l);
        let p = pseudo_inverse_psd(&e);

        let mul = |a: &[[f64; 4]; 4], b: &[[f64; 4]; 4]| {
            let mut o = [[0.0f64; 4]; 4];
            for i in 0..4 {
                for j in 0..4 {
                    let mut acc = 0.0;
                    for k in 0..4 {
                        acc += a[i][k] * b[k][j];
                    }
                    o[i][j] = acc;
                }
            }
            o
        };
        let lpl = mul(&mul(&l, &p), &l);
        let plp = mul(&mul(&p, &l), &p);
        for i in 0..4 {
            for j in 0..4 {
                assert!(libm::fabs(lpl[i][j] - l[i][j]) < 1e-12, "LPL at {i},{j}");
                assert!(libm::fabs(plp[i][j] - p[i][j]) < 1e-12, "PLP at {i},{j}");
            }
        }
    }

    /// An already-diagonal matrix takes zero rotations, and a zero matrix does not
    /// divide by anything.
    #[test]
    fn degenerate_inputs_are_handled() {
        let d = [[3.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 2.0]];
        let e = jacobi_eigen(&d);
        assert!(e.converged);
        assert_eq!(e.sweeps, 0, "a diagonal matrix needs no sweeps");
        assert_eq!(e.values, [1.0, 2.0, 3.0]);

        let z = [[0.0f64; 3]; 3];
        let ez = jacobi_eigen(&z);
        assert!(ez.converged);
        for m in 0..3 {
            assert_eq!(ez.values[m], 0.0);
            assert!(ez.values[m].is_finite());
        }
        // Everything is kernel, so the pseudo-inverse is zero rather than infinite.
        let p = pseudo_inverse_psd(&ez);
        for i in 0..3 {
            for j in 0..3 {
                assert_eq!(p[i][j], 0.0);
            }
        }
    }

    /// The decomposition is a decomposition: `sum_m lambda_m v_m v_m^T` is the input.
    #[test]
    fn eigendecomposition_reconstructs_the_matrix() {
        let c = [
            [0.0, 1.5, 0.0, 0.25, 4.0],
            [1.5, 0.0, 2.0, 0.0, 0.0],
            [0.0, 2.0, 0.0, 7.0, 0.5],
            [0.25, 0.0, 7.0, 0.0, 1.0],
            [4.0, 0.0, 0.5, 1.0, 0.0],
        ];
        let l = laplacian(&c);
        let e = jacobi_eigen(&l);
        assert!(e.converged, "sweeps {}", e.sweeps);
        for i in 0..5 {
            for j in 0..5 {
                let mut acc = 0.0;
                for m in 0..5 {
                    acc += e.values[m] * e.vectors[m][i] * e.vectors[m][j];
                }
                assert!(libm::fabs(acc - l[i][j]) < 1e-12, "at {i},{j}: {acc} != {}", l[i][j]);
            }
        }
    }

    /// Repeated calls agree bit for bit — the determinism the crate promises.
    #[test]
    fn jacobi_is_bit_reproducible() {
        let c = [
            [0.0, 1.5, 0.0, 0.25, 4.0],
            [1.5, 0.0, 2.0, 0.0, 0.0],
            [0.0, 2.0, 0.0, 7.0, 0.5],
            [0.25, 0.0, 7.0, 0.0, 1.0],
            [4.0, 0.0, 0.5, 1.0, 0.0],
        ];
        let l = laplacian(&c);
        let a = jacobi_eigen(&l);
        let b = jacobi_eigen(&l);
        assert_eq!(a.values, b.values);
        assert_eq!(a.vectors, b.vectors);
        assert_eq!(a.sweeps, b.sweeps);
    }
}
