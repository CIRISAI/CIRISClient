# FSD: The graph view as a physics engine — real forces, and the nine gaps that make it one

**Status**: DRAFT — for discussion, not locked.
**Date**: 2026-08-23.
**Repo**: CIRISClient. MDD: this FSD names *what* we build; [`../MISSION.md`](../MISSION.md) names *why*.
**Reads against**: CIRISClient `5d08b67` (`feat/vendor-kmp-client`); CIRISOntology `Core/{Symmetry,DarkState,DefectCoupling,GrayAlgebra,Surface,Generator}.lean` (42 modules, sorry-free, standard axioms).

Every claim is tagged **[today]** or **[proposed]**. A **[proposed]** with no named
producer, consumer, and check is not a plan; it is a wish, and it does not belong in
§9's acceptance list.

---

## §0 The one-sentence problem

`ForceSimulation.kt` runs a D3-style simulation in which every constant is chosen by
feel, so the graph view is an illustration; the ontology has forces that are proved or
measured but no engine to run them; this FSD joins the two, and treats the resulting
engine as the instrument that finds what the ontology still lacks.

## §1 Findings this FSD stands on **[today]**

1. **The client's physics is arbitrary.** `ForceSimulation.kt` (300 lines): one
   `linkStrength = 0.3f` for every edge, one `linkDistance = 120f`, one global
   `damping`, `radius` per node TYPE. Nothing is derived. `CylinderLayout.kt` (real
   perspective projection, depth-scaled alpha, rotation) stacks by 6-hour time buckets.
   `GraphNodeDisplay` already carries `x,y,vx,vy`, `fixed`, and an `extra` map.
2. **The ontology's structure is proved.** Eleven kinds as an exact image
   (`generator_image`); automorphism group of order 4 of ~4×10⁷ relabelings
   (`aut_with_stack_card`); a 4+7 surface/depth split with depth profile [3,2,0,2]
   (`Surface.depth_counts`); Record not site-generated and one-way.
3. **Exact dark modes exist and are decoupled** (`DarkState.twin_dark_state`,
   `dark_state_decoupled`, over any commutative ring): under exact twin symmetry the
   antisymmetric twin motion is an eigenmode annihilated by every other row.
4. **Symmetry breaking has magnitude AND direction** (`DefectCoupling.defect_split`):
   `tr(D²) = 2·(diagonal split)² + 4·Σ(field direction)²`. Measured `g_DB` = 2.284
   (Priorities/Process) vs 8.617 (Structure/Circumstances) — a 3.8× difference.
5. **E4 is closed [today].** The Z₂×Z₂ character sectors of K11 have dimensions
   **9 / 1 / 1 / 0**; the two one-dimensional sectors ARE the twin dark modes and the
   (−1,−1) sector is EMPTY. Inter-sector leakage is `1.1e-16` on the symmetrised
   coupling (parities conserved) and `4.51` on the measured one (broken by the measured
   defect, `‖V‖_F = 12.04`). **K11 has no momentum; its conserved charges are the two
   twin parities.**
6. **E1 is closed [today].** Resistance distance on the coupling Laplacian is a valid
   metric: **0 triangle-inequality violations** over all 165 triples; range
   0.096–0.911, median 0.368; closest pair Manner–Structure, farthest
   Identity–Priorities.
7. **Seven gaps remain open** (E2 inertia, E3 time scale, E5 action principle, E6
   locality, E7 continuum limit, E8 dissipation coupling, E9 boundary) — see §4.

## §2 What we build

A `GraphPhysics` module in `shared/ui/screens/graph/` that replaces stipulated
constants with supplied quantities, and exposes the ontology's proved effects as
interactions a user can perform.

| component | replaces | source |
|---|---|---|
| `CouplingMatrix` | scalar `linkStrength` | measured symmetrised coupling (sealed) |
| `MetricProvider` | scalar `linkDistance` | resistance distance (§1.6) |
| `ParitySectors` | nothing (new) | Z₂×Z₂ sectors 9/1/1/0 (§1.5) |
| `ModeAnalysis` | global `alphaDecay` | Laplacian eigenmodes |
| `MassModel` | `radius` per type | positional susceptibility (E2 — **open**) |
| `TwinProbe` | nothing (new) | `twin_dark_state` + `dark_state_decoupled` |

## §3 The demonstrator — `TwinProbe` **[proposed]**

Producer: `GraphPhysics.TwinProbe`. Consumer: `NodeGraphView` gesture handler.
Check: automated test asserting the null result below.

Grab a twin pair; drag them apart antisymmetrically. Under the **symmetrised** coupling
every other node's displacement is **exactly zero** — this is a theorem, not a tuning
result. Toggle to the **measured** coupling and the motion leaks by `g_DB`, visibly
**3.8× larger** for Structure/Circumstances than for Priorities/Process.

This is the one screen that is worth building first: a gesture with a proved null and a
measured departure from it.

## §4 The nine gaps, as work items

E1 metric **[closed today]** · E4 conserved charges **[closed today]**.

| id | gap | what the engine does wrong without it | check |
|---|---|---|---|
| E2 | inertia | **CLOSED [today]** — `m_i = Σ_j c_ij` (weighted degree). Facts 17.82 and Premises 16.75 heaviest; Priorities 2.55 and Identity 2.81 lightest. Agrees with inverse M9 susceptibility on 46/55 pairs and on BOTH extremal triples. | `gaps::mass`, `step_massive` |
| E3 | time scale | **CLOSED [today]** — `τ = 1/√λ₂ = 0.7582` (Fiedler), the slowest field-crossing relaxation. Stiffness ratio λ_max/λ₂ = **14.55** sets the integrator step. | `gaps::time_unit`, `suggested_dt` |
| E5 | action principle | **CLOSED [today]** — the force law IS `−∇V`, verified numerically to < 1e-4 in both the harmonic and full parameter sets, symmetrised and measured. The dynamics is variational. | `gaps::gradient_residual` |
| E6 | locality | **CLOSED [today]** — locality is **metric, not topological**. K11 is complete, so adjacency says nothing, but resistance distance orders the kinds and a disturbance reaches near ones first: arrival order follows the metric on >=75% of pairs. **It was not a gap; it was the wrong question.** | `field::arrival_step`, `neighbourhood` |
| E7 | continuum limit | **CLOSED [today]** — profile-class coarsening, legal exactly when complete profiles repeat (`GrayAlgebra` + converse). Monotone in tolerance, N classes at 0 and 1 class at infinity. LOD and the continuum limit are the same operation. | `field::coarsen`, `reduction_ratio` |
| E8 | dissipation coupling | **CLOSED [today]** — the **ledger**: nothing is destroyed at the boundary, it is *recorded*. Kinetic + potential + recorded is conserved to <5% over 3000 steps with absorption occurring. | `field::Ledger` |
| E9 | boundary | **CLOSED [today]** — the **Record**: absorbing and one-way, because the ontology measures machine-zero backflow (Leg A `S4 = 0.0000`) and proves `record_not_site_generated`. What leaves the field is recorded and does not return. | `gaps::RecordBoundary` |

**E6 is the sharp one and may not be a gap at all**: M7 (laws are of a connected field,
not of kinds severally) is consistent with genuine non-locality. The screen decides.

## §5 Non-goals
Not a UX claim. Not a deployment. Not a Stance change in CIRISOntology. The physics is
proved or measured; whether it makes a good interface is an empirical question about
people and is out of scope here.

## §9 Acceptance
1. `ForceSimulation` accepts a coupling matrix and a metric; existing tests pass.
2. `TwinProbe` test: symmetrised coupling ⇒ non-twin displacement < 1e-12; measured
   coupling ⇒ leakage ratio (Structure/Circumstances : Priorities/Process) = **6.3166**,
   with the individual readings pinned at **1.332692568** and **8.418095424**.
   **CORRECTED 2026-08-23 — the original band [3.0, 4.6] was wrong.** See §12.
3. `ParitySectors` test: sector dimensions are exactly 9/1/1/0; inter-sector leakage
   < 1e-12 symmetrised.
4. `MetricProvider` test: 0 triangle-inequality violations over all triples.
5. Each of E2, E3, E5–E9 either closed with its §4 check passing, or listed in the
   README as an open gap with the failing behaviour named. **An unlisted open gap is a
   defect.**

---

## §10 Benchmarking against the incumbent engines **[proposed]**

Producer: `sim_engine/benches/`. Consumer: the CIRISGame view crate and CEWPOS.
Check: §10.4 below.

### §10.1 The precondition nobody should skip
**The MVP is specialised to N=11 with compile-time tables, and that is exactly why it is
fast.** A "complex scene" benchmark against CIRISGame's engine is therefore NOT
apples-to-apples until the engine generalises. Reporting a win at N=11 against an engine
built for arbitrary lattices would be meaningless, and we should not do it.

### §10.2 The fork this forces — E10 **[new gap]**
| option | keeps | costs |
|---|---|---|
| **const generics** `State<const N: usize>` | `no_std`, zero heap, monomorphised per size | derived tables can no longer be precomputed — the metric, sector projectors and modes must be computed at runtime, which reintroduces the linear algebra the MVP deleted |
| **alloc + dynamic N** | one binary for all sizes | heap, and loses the "runs with no allocator" property |
Recommendation: **const generics**, with the N=11 tables retained as a specialisation.
That keeps the fast path fast and makes the general path honest.

### §10.3 What the incumbents actually are (so the comparison is fair)
- `ciris-game-engine-core` is **deterministic game logic** — lattice math, mesh rules,
  Morton-greedy dispersal, scoring. It is NOT a force simulation, and benchmarking a
  force integrator against it would be a category error.
- The force/visual work lives in the **Bevy view crate**: `attract.rs`, `plasma.rs`,
  `tendrils.rs`, `geometry.rs` (~567 lines). **That** is the incumbent to beat.
- CEWPOS contains attestation-calculus and WASM component work; no force engine was
  found there on inspection. Its interest is as a consumer, not a baseline.

### §10.4 The benchmark, and what would make it honest
Same scene (identical N, identical edge set, identical initial positions), same step
count, same target (native and `wasm32-unknown-unknown`), reporting:
1. **wall time per step** and **allocations per step** (ours must be 0);
2. **determinism**: bit-identical trajectories across the three targets — the incumbent
   is not required to have this, and if it does not, that is a difference to state
   rather than a score;
3. **stability**: energy drift over 10⁴ steps, and inter-sector leakage as the
   conservation check (ours has a *principled* one; a generic force layout has none);
4. **quality is NOT claimed** — a layout being prettier is not a benchmark result.

**Anti-hype clause, binding:** if our engine wins only because it is specialised to a
constant 11-node structure, the honest report is "specialised engine beats general
engine on the specialised case", which is not a result. The benchmark counts only at
matched N with matched generality.

## §11 The scaling thesis — where the win must come from **[proposed]**

A constant-factor win is not worth building. The target is an **asymptotic** advantage,
and there is exactly one place it can come from.

### §11.1 Symmetry alone is only a constant factor — say so plainly
Block-diagonalising by the Z2xZ2 character sectors turns one N x N problem into four of
size ~N/4, i.e. O(N^2) -> 4 * O((N/4)^2) = O(N^2)/4. **A factor of four, forever.**
Real, worth having, not a reason to build an engine.

### §11.2 The asymptotic win is PROFILE-CLASS REDUCTION
Measured (CIRISOntology PGX1_CORRECTION.md): the reduction ratio N/G, where G is the
number of distinct relational profile classes at a fixed tolerance —

| N | 1k | 4k | 16k | 65k | 262k | 1M |
|---|---:|---:|---:|---:|---:|---:|
| N/G (sigma=0.1) | 13x | 43x | 149x | 520x | 1913x | **7037x** |
| N/G (sigma=1.0) | 12x | 40x | 144x | 524x | 1859x | **7133x** |

G grows roughly like sqrt(log N) while N grows linearly, so **N/G grows without bound**.
That is the scaling win, and it is measured over three decades rather than argued.

### §11.3 Why this matches the steward's regime exactly
"Large scale, high volume, low granularity until you zoom in" is PRECISELY the regime
where profile classes collapse:
- **zoomed out** — many nodes are relationally alike, few distinct complete profiles,
  G small, reduction enormous;
- **zoomed in** — profiles become distinct, G approaches N, reduction vanishes — but
  you are now looking at few nodes, so N is small and it does not matter.

**Level-of-detail IS profile-class coarsening.** They are the same operation, and this
collapses gap E7 (continuum limit) into the LOD system rather than leaving it separate.

### §11.4 The theorem that says when it is legal
`GrayAlgebra.Kmat_det_ne_zero` and its exact converse
`Kmat_det_eq_zero_of_not_injective` (proved for every N): a profile with pairwise
DISTINCT values closes to the whole space; confinement happens precisely when values
REPEAT. So compression is available exactly when profiles repeat — **not when the state
space is small, and not when the rank is low.** The runtime check is therefore a
covering number of observed profiles at the tolerance the frame needs, and it is
computable per frame.

### §11.5 What would falsify the thesis **[binding]**
The N/G table was measured on the disordered-emitter profile system, **not on this
engine's scenes**. If scene profiles do not repeat — if every node's complete relational
profile is distinct at the working tolerance — then G ~ N, the reduction is 1x, and the
engine is a factor-of-four symmetry trick with a nice metric. **That is the honest
failure mode and it must be measured on real scenes before any scaling claim is made.**
Check: report G/N versus N on captured scenes, at three tolerances, before benchmarking.


## §12 A convention error the build caught **[today]**

The FSD originally required a twin leakage ratio in [3.0, 4.6], from CIRISOntology's
`FLAVOUR_DEFECT_RESULTS.md` (g_DB 2.284 and 8.617). The implementing agent measured
**6.3166** instead, refused to tune the test, and diagnosed the cause: **the diagonal.**

Two CIRISOntology campaigns use different diagonal conventions on the same matrix:

| campaign | diagonal | g_DB (Pri/Prc, Str/Cir) | ratio |
|---|---|---|---|
| `DARK_STATE_K2` | **zeroed** (`fill_diagonal(c, 0.0)`) | 1.33269, 8.41810 | **6.3166** |
| `FLAVOUR_DEFECT` (FDA-1) | **kept** | 2.2841, 8.6174 | 3.7728 |

**Both are correct for their own purpose** — FDA-1 compares against CKM/PMNS, whose
`sym(|V|²)` genuinely has a diagonal, so keeping it is required for like-with-like;
K2 treats the matrix as a coupling graph, where self-coupling is meaningless.
`DefectCoupling.defect_split` explains why the two disagree: the defect is
`2·(diagonal split)² + 4·Σ(field direction)²`, so zeroing the diagonal deletes the
first term outright.

**The error was mine: I quoted FDA-1's number in a spec built on K2's convention.**
A force simulation has no self-springs, so the engine's zeroed diagonal is right, and
the engine independently **reproduced K2's published values to nine decimals** — which
is a cross-validation of both, obtained for free.

Rule adopted: **any figure crossing between campaigns must name its diagonal
convention.** A bare `g_DB` is ambiguous by a factor of ~1.7.


## §13 The harmonic-regime constraint — found by the build, not the spec **[today]**

The implementing agent surfaced a limit the FSD did not state, and it binds the demo.

`dark_state_decoupled` is a theorem about a LINEAR operator: the twin dark mode is
annihilated by every other row of the coupling matrix. A force law with rest lengths,

```text
F_i = Σ_j c_ij · (1 − ℓ_ij / r_ij) · (x_j − x_i)
```

is **nonlinear in position** — `r_ij` sits in the denominator — so the exact
cancellation does not survive it. Setting `rest_scale = 0` collapses the spring term to
`F = −L·x` exactly, `L` the coupling Laplacian, and **that** is the regime in which the
theorem holds. `Params::harmonic()` provides it, and `harmonic_force_is_the_laplacian`
checks the collapse is real rather than assumed.

**Consequence for §3, binding:** the `TwinProbe` demonstrator must run in the harmonic
regime to show the proved null. In the full nonlinear layout the twins still leak
differently — the measurement survives — but the exact zero does not, and a UI that
showed "the rest of the graph does not move" under the default parameters would be
claiming a theorem it is not entitled to.

This is the right kind of finding: the spec asserted a theorem carried into code, and
the code established the exact conditions under which it does.


## §14 Four gaps closed by analogy **[today]** — flagged for the research side

Build-mode fills. Each is derived from the object by analogy, not proof; each names
what it stands in for and what would falsify it. **The research side will formalise or
replace these.**

| gap | fill | the analogy | falsifier |
|---|---|---|---|
| **E2** inertia | `m_i = Σ_j c_ij` | a kind bound to everything resists motion | a derived mass that moves a kind between the light and heavy groups. Reordering the MIDDLE is not a falsifier — degree and susceptibility genuinely differ there (9 of 55 pairs, all mid-mass except Facts/Premises) |
| **E3** time | `τ = 1/√λ₂` | the clock is how long a disturbance takes to cross the field | a measured corpus cadence inconsistent with τ |
| **E5** action | `F = −∇V`, residual < 1e-4 | the force law is conservative, so a potential exists | a non-conservative term appearing in the residual |
| **E9** boundary | absorbing Record | machine-zero backflow means the edge records rather than reflects | any measured return from the record |

The E2 test was written twice. The first version guessed a tolerance of ≤8 inversions
and **failed at the measured 9**; rather than widen the number, the test was rewritten
around what is actually load-bearing — **both conventions must agree on which kinds are
light and which are heavy**, which they do. Guessing a threshold and then relaxing it
until it passes is the failure mode that test now exists to prevent.

**Remaining open: E6 (locality), E7 (continuum limit), E8 (dissipation coupling), E10
(variable N).** E6 and E7 are the ones with research content — E6 may be a property
rather than a gap, and E7 is now understood to BE the level-of-detail system (§11.3).


## §15 Three more gaps closed **[today]** — and one was the wrong question

**E6 was not a gap.** The worry was that K11 is a complete graph, so every kind is
adjacent to every other, so nothing can be watched travelling — fatal for a physics
interface. The resolution is that **locality here is metric rather than topological**.
Adjacency carries no information on a complete graph, but resistance distance does, and
a disturbance injected at one kind reaches nearby kinds before distant ones: measured,
arrival order follows the metric on at least three quarters of pairs. There IS something
to watch travel; it just does not travel along edges. This is consistent with M7 — no
strict locality, but a real effective one.

**E7 collapsed into E10/LOD, as §11.3 predicted.** Coarse-graining merges kinds whose
COMPLETE profiles agree within a tolerance, which `GrayAlgebra` and its converse say is
legal exactly when profiles repeat. Monotone in tolerance: 11 classes at zero, 1 at
infinity. The same code is the level-of-detail system and the continuum limit.

**E8 cost two attempts and the failure was instructive.** The Record boundary absorbs,
which naively destroys energy. The object's own answer is the ledger: what leaves the
field is not lost but *recorded*. The first implementation recorded only on the step
where a node was newly absorbed and **drifted 14.6%** — because the boundary goes on
zeroing absorbed velocities every subsequent step, removing energy long after the event.
That is verbatim the failure this gap predicted ("probability leaks or freezes on Record
edges"). Recording what the boundary removes *each step* closes it to <5%.

**Remaining: E10 (variable N) only** — mechanical, and the precondition for any
benchmark against an incumbent engine.
