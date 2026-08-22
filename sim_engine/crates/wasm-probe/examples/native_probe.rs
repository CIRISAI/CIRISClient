//! Native half of the portability check. Prints exactly what
//! `tools/portability_check.mjs` prints from the `.wasm`, as raw IEEE-754 bit patterns
//! so the comparison is bit-for-bit rather than to a tolerance.
//!
//!   cargo run --release --example native_probe > native.txt
//!   node ../../tools/portability_check.mjs <module.wasm> > wasm.txt
//!   diff native.txt wasm.txt

use ciris_sim_wasm_probe::{coarsen_classes, scenario_len, scenario_value};

fn main() {
    for s in 0..3u32 {
        for i in 0..scenario_len(s) {
            println!("{s} {i} {:016x}", scenario_value(s, i).to_bits());
        }
    }
    for (k, tol) in [(0u32, 0.0f64), (1, 0.5), (2, 1.0), (3, 2.0), (4, 100.0)] {
        println!("coarsen {k} {}", coarsen_classes(tol));
    }
}
