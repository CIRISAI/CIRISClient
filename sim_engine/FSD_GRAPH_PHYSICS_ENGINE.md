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
| E2 | inertia — susceptibility is a response, not a mass | overdamped drift only; **the object cannot be rung** | oscillation observed after an impulse |
| E3 | time scale — θ uncalibrated | animation speed has no correct setting | a rate matched to measured revision cadence |
| E5 | action principle — no potential | every interaction hand-coded; nothing composes | forces reproduced as a gradient |
| E6 | **locality — K11 is COMPLETE** | perturbations appear everywhere at once; nothing to watch travel | decide what propagation MEANS on a complete graph |
| E7 | continuum limit — coarse-graining not covered by the mint theorems | cannot zoom; 11 nodes at every scale | a coarse-graining preserving proved structure |
| E8 | dissipation coupling (minimal dilation) | probability leaks or freezes on Record edges | positivity preserved over a long run |
| E9 | boundary — purifier implicit | departing objects have nowhere to go | purifier rendered as the boundary |

**E6 is the sharp one and may not be a gap at all**: M7 (laws are of a connected field,
not of kinds severally) is consistent with genuine non-locality. The screen decides.

## §5 Non-goals
Not a UX claim. Not a deployment. Not a Stance change in CIRISOntology. The physics is
proved or measured; whether it makes a good interface is an empirical question about
people and is out of scope here.

## §9 Acceptance
1. `ForceSimulation` accepts a coupling matrix and a metric; existing tests pass.
2. `TwinProbe` test: symmetrised coupling ⇒ non-twin displacement < 1e-12; measured
   coupling ⇒ ratio of Structure/Circumstances to Priorities/Process leakage in
   [3.0, 4.6].
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
