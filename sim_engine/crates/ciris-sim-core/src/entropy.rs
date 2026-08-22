//! Entropy intake — the one seam where the deterministic core meets a nondeterministic
//! world.
//!
//! ## Why the core never generates randomness
//!
//! This crate must replay bit-identically across `wasm32-unknown-unknown`,
//! `wasm32-wasip1` and native CI. A crate that reads an OS RNG, a GPU timer, or a
//! clock cannot make that promise. So the core NEVER sources entropy — it accepts it.
//! The host decides where the bytes come from, and the determinism guarantee is stated
//! per-source rather than globally.
//!
//! ## The two sources, and why the difference is scientific rather than cosmetic
//!
//! * **Seeded PRNG** (host-side, e.g. ChaCha as in `ciris-game-engine-core`) — same
//!   seed, same trajectory, forever. This is what verification and replay require.
//! * **True entropy** — CIRISOssicle derives a TRNG from GPU kernel timing jitter:
//!   the lower 4 LSBs at 4 kHz, measured at **7.99 bits/byte**, 6/6 NIST tests,
//!   ~465 kbps, software-only with no external hardware.
//!
//! The distinction MATTERS for one thing in this engine. CIRISOntology proves
//! (`Core/Valve.lean`: `valve_from_nothing`, `valve_no_downward`, `valve_upward_strict`)
//! that under per-cell stochastic noise, order flows only upward — never from nothing,
//! never downward. Driving that with a seeded PRNG demonstrates a *computation*;
//! driving it with real entropy demonstrates that *actual noise* pumps order upward.
//! Same code path, different claim. The engine must therefore record WHICH source fed
//! a run, and a run that does not name its source may not be cited as either.

/// A source of entropy supplied by the host. The core calls this and nothing else.
///
/// Implementors must document their determinism: a seeded PRNG is reproducible given
/// its seed; a TRNG is not reproducible at all, by design.
pub trait EntropySource {
    /// Next 64 bits. Must not panic; must not block indefinitely.
    fn next_u64(&mut self) -> u64;

    /// A uniform `f64` in `[0, 1)`, derived from [`Self::next_u64`] by the standard
    /// 53-bit construction so that every implementor agrees bit-for-bit.
    fn next_f64(&mut self) -> f64 {
        // 53 significand bits; identical on every IEEE-754 target.
        ((self.next_u64() >> 11) as f64) * (1.0 / 9007199254740992.0)
    }
}

/// How a run was driven. Recorded with every result; a run without one is uncitable.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum EntropyProvenance {
    /// Reproducible given the seed. Demonstrates a computation.
    Seeded { seed: u64 },
    /// Not reproducible. Demonstrates behaviour under real noise.
    /// `bits_per_byte_milli` records the measured entropy density x1000
    /// (CIRISOssicle reports 7.99 bits/byte -> 7990).
    TrueRandom { bits_per_byte_milli: u16 },
}
