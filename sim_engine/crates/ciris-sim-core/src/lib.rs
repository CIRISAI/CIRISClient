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
//!
//! ## Sizes, and where the fast path went (E10)
//!
//! The crate was originally written against one structure: eleven kinds, one coupling,
//! and every derived quantity emitted as a compile-time table. That is what made it
//! fast, and — per FSD §10.1 — what made it unbenchmarkable against a general engine.
//!
//! It is now generic in `const N: usize`, with the eleven-kind case kept as a
//! specialisation:
//!
//! * [`structure::K11`] is a `static` built by a `const fn` from the shipped
//!   [`tables`]. Using the built-in object costs a reference; no linear algebra runs,
//!   exactly as before.
//! * [`Structure::from_coupling`] takes an arbitrary symmetric coupling at any `N` and
//!   COMPUTES the metric, the spectrum, the susceptibility and the character
//!   projectors with [`linalg`] — one `O(N^3)` eigensolve at construction, then the
//!   same `O(N^2)` per step as before.
//!
//! The two are held together by `structure::tests::k11_matches_the_computed_structure`,
//! which runs the general path on the built-in coupling and checks it reproduces every
//! table. That cross-check is the only thing entitling the fast path to be called a
//! specialisation rather than a second, unverified implementation.
//!
//! ## Why there is no allocator
//!
//! Every array in this crate is a compile-time-sized `[[f64; N]; N]` and nothing is
//! heap-allocated, so the crate is `no_std` WITHOUT `alloc` — it runs on bare metal, in
//! a WASM sandbox with no allocator, or inside another engine's frame loop. Generality
//! did not change that: [`linalg`] does its work in stack scratch arrays, and a
//! [`Structure`] lives wherever the caller puts it. The trade is that a `Structure<N>`
//! is `8 * (7 N^2 + 3 N)` bytes of *somewhere* — 7.2 KB at `N = 11`, 56 MB at
//! `N = 1000` — and at large `N` the caller must place it deliberately rather than let
//! it land on a stack frame ([`Structure::init_from_coupling`] exists for that).
//!
//! Per-step cost is `O(N^2)` multiply-adds for forces, unchanged.

#![no_std]
#![forbid(unsafe_code)]

pub mod data;
pub mod entropy;
pub mod linalg;
pub mod structure;
pub mod tables;
pub mod dynamics;
pub mod gaps;
pub mod field;
pub mod twin_probe;
pub mod sectors;

pub use data::{COUPLING, DEPTH, KINDS, N, TWINS};
pub use structure::{Structure, K11};

/// A square `N x N` matrix of `f64`, row-major, at an arbitrary size.
pub type MatN<const N: usize> = [[f64; N]; N];
/// A vector of length `N`, at an arbitrary size.
pub type VecN<const N: usize> = [f64; N];

/// A square matrix over the eleven built-in kinds.
pub type Mat = MatN<N>;
/// A vector over the eleven built-in kinds.
pub type Vec11 = VecN<N>;
