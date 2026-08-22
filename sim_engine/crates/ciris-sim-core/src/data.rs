//! Sealed structural data for the eleven kinds. Generated from CIRISOntology's
//! PANEL-2 curated confusion matrix (248 items, licensed instrument): symmetrised,
//! diagonal zeroed, off-diagonal mean normalised to 1. Embedded as constants because
//! this crate is `no_std` and cannot read files.

/// Number of kinds.
pub const N: usize = 11;

/// Kind names, in the canonical order used by every array in this crate.
pub const KINDS: [&str; N] = [
    "Priorities", "Rules", "Manner", "Identity", "Confidence", "Facts",
    "Circumstances", "Process", "Model", "Structure", "Premises",
];

/// The measured symmetric coupling matrix `c_ij`. Zero diagonal.
pub const COUPLING: [[f64; N]; N] = [
    [0.0, 1.45444316278, 0.0, 0.0, 0.0, 0.0, 0.0, 1.09083237208, 0.0, 0.0, 0.0],
    [1.45444316278, 0.0, 0.0, 0.0, 7.03421602361, 0.0, 0.0, 0.0, 1.42892661606, 0.727221581388, 3.61264914625],
    [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.690244212843, 3.21508488614, 9.01631503026, 0.328422649659],
    [0.0, 0.0, 0.0, 0.0, 0.0, 1.19777672229, 0.299444180572, 0.0, 0.0, 0.0, 1.31369059864],
    [0.0, 7.03421602361, 0.0, 0.0, 0.0, 2.40644232387, 0.0, 0.0, 0.0, 0.0, 0.0],
    [0.0, 0.0, 0.0, 1.19777672229, 2.40644232387, 0.0, 7.58693430616, 0.0, 1.37534186796, 0.0, 5.25476239455],
    [0.0, 0.0, 0.0, 0.299444180572, 0.0, 7.58693430616, 0.0, 0.0, 0.0, 0.0, 2.62738119727],
    [1.09083237208, 0.0, 0.690244212843, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.727221581388, 0.656845299318],
    [0.0, 1.42892661606, 3.21508488614, 0.0, 0.0, 1.37534186796, 0.0, 0.0, 0.0, 0.0, 1.6421132483],
    [0.0, 0.727221581388, 9.01631503026, 0.0, 0.0, 0.0, 0.0, 0.727221581388, 0.0, 0.0, 1.31369059864],
    [0.0, 3.61264914625, 0.328422649659, 1.31369059864, 0.0, 5.25476239455, 2.62738119727, 0.656845299318, 1.6421132483, 1.31369059864, 0.0]
];

/// The two twin pairs, as indices into [`KINDS`]: (Priorities, Process) and
/// (Structure, Circumstances). These generate the order-4 automorphism group.
pub const TWINS: [(usize, usize); 2] = [(0, 7), (9, 6)];

/// Grounding depth per kind (Surface.lean `depth_counts` = [3,2,0,2]).
pub const DEPTH: [u8; N] = [1, 0, 0, 0, 1, 0, 1, 1, 2, 1, 3];
