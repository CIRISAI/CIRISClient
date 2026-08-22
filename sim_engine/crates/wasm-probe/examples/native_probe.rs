//! Native half of the portability check. Prints exactly what
//! `tools/portability_check.mjs` prints from the `.wasm`, as raw IEEE-754 bit patterns
//! so the comparison is bit-for-bit rather than to a tolerance.
//!
//!   cargo run --release --example native_probe > native.txt
//!   node ../../tools/portability_check.mjs <module.wasm> > wasm.txt
//!   diff native.txt wasm.txt
//!
//! Scenarios 0–2 exercise the integrator and the sealed tables. Scenarios 3–4 and the
//! `chk`/`sweeps`/`conv` lines exercise the E10 RUNTIME path — `Structure::from_coupling`
//! and the cyclic Jacobi eigensolver — which the sealed tables do not cover.

use ciris_sim_wasm_probe::{
    coarsen_classes, eigensolve_digest, field_digest, jacobi_converged, jacobi_sweeps, scenario_len,
    scenario_value, sweep_boundary_bits, sweeps_at,
};

fn main() {
    for s in 0..5u32 {
        for i in 0..scenario_len(s) {
            println!("{s} {i} {:016x}", scenario_value(s, i).to_bits());
        }
    }
    for (k, tol) in [(0u32, 0.0f64), (1, 0.5), (2, 1.0), (3, 2.0), (4, 100.0)] {
        println!("coarsen {k} {}", coarsen_classes(tol));
    }
    for n_sel in 0..2u32 {
        for which in 0..9u32 {
            println!("chk {n_sel} {which} {:016x}", field_digest(n_sel, which));
        }
    }
    for n_sel in 0..3u32 {
        println!("eig {n_sel} 0 {:016x}", eigensolve_digest(n_sel, 0));
        println!("eig {n_sel} 1 {:016x}", eigensolve_digest(n_sel, 1));
        println!("sweeps {n_sel} {}", jacobi_sweeps(n_sel));
        println!("conv {n_sel} {}", jacobi_converged(n_sel));
    }
    // The knife edge: the exact double at which the iteration count changes, and the
    // counts on either side of it.
    let b = sweep_boundary_bits();
    println!("edge bits {b:016x}");
    for (tag, bits) in [("below", b - 1), ("at", b), ("above", b + 1)] {
        println!("edge {tag} {}", sweeps_at(f64::from_bits(bits)));
    }
}
