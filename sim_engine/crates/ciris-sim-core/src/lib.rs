//! # ciris-sim-core
//!
//! The deterministic physics core for the CIRIS relational object. No rendering,
//! no `std`, no non-deterministic iteration — everything must replay bit-identically
//! across `wasm32-unknown-unknown`, `wasm32-wasip1`, and native CI, following the
//! `ciris-game-engine-core` pattern.
//!
//! ## What makes this physics rather than a force layout
//!
//! Every constant is supplied by a theorem or a measurement from CIRISOntology
//! (42 sorry-free Lean modules), not chosen by feel:
//!
//! * springs are the **measured** couplings ([`data::COUPLING`]);
//! * lengths are **resistance distance** on the coupling Laplacian, a proper metric;
//! * the conserved charges are the **twin parities** — the Z2xZ2 character sectors
//!   have dimensions 9/1/1/0, and the two one-dimensional sectors ARE the dark modes;
//! * the twin dark mode is an **exact** eigenvector, annihilated by every other row
//!   (`DarkState.twin_dark_state`, `dark_state_decoupled`) — so the twin probe has a
//!   *proved* null result, and the measured coupling departs from it by a known amount.
//!
//! ## Known gaps (an unlisted gap is a defect — see the FSD §9.5)
//!
//! E2 inertia · E3 time scale · E5 action principle · E6 locality (K11 is complete,
//! so nothing propagates) · E7 continuum limit · E8 dissipation coupling · E9 boundary.

//! ## Why there is no allocator
//!
//! Eleven nodes, fixed forever. Every array in this crate is a compile-time-sized
//! `[[f64; N]; N]`, every derived table ([`tables`]) is precomputed at code-generation
//! time, and nothing is heap-allocated. The crate is therefore `no_std` WITHOUT
//! `alloc` — it runs on bare metal, in a WASM sandbox with no allocator, or inside
//! another engine's frame loop with zero setup cost. Total static data: ~6.9 KB.
//! Per-step cost: O(N^2) = 121 multiply-adds for forces. There is no runtime linear
//! algebra because there is nothing left to compute.

#![no_std]
#![forbid(unsafe_code)]

pub mod data;
pub mod entropy;
pub mod tables;
pub mod dynamics;
pub mod gaps;
pub mod field;
pub mod twin_probe;
pub mod sectors;

pub use data::{COUPLING, DEPTH, KINDS, N, TWINS};

/// A square `N x N` matrix of `f64`, row-major. The only matrix type in this crate.
pub type Mat = [[f64; N]; N];
/// A vector of length `N`.
pub type Vec11 = [f64; N];
