# The NL bridge — engine selection **[decided 2026-08-23]**

The H3ERE2-G architecture puts an SLM at perception and expression only: it decomposes
input into typed tuples and renders a chosen action. The reasoning between is symbolic
and lives in `ciris-sim-core`, which is `no_std` with no allocator. This document picks
the inference engine and states what it costs.

Scouted 2026-08-20/22 against live registries; three load-bearing claims re-verified
independently before adoption.

## THE DECISION — it splits, and that is the finding

| target | engine | version | why |
|---|---|---|---|
| native CPU (priority 1) | **llama-cpp-2** | `0.1.154` | the ONLY candidate that runs current Gemma today, with the only battle-tested quantised CPU kernel story |
| wasm (priority 2) | **rten** + `rten-generate` | `0.25` | 1.69 MB wasm, both targets build clean, WASM SIMD works, leanest pure-Rust dependency graph (47) |
| both | **llguidance** | `1.8` | engine-agnostic constrained decoding — survives an engine swap |

**No single engine covers both targets.** Pretending otherwise would be the easy error.

## VERIFIED INDEPENDENTLY (not taken on report)
- `google/gemma-4-E2B-it-qat-q4_0-gguf`: **`license: apache-2.0`, `gated: False`**. Gemma 4
  (2026-03-31) dropped the bespoke Gemma ToU. **We may ship Gemma 4 GGUF in our own
  installer** under Apache §4(a)-(d). Gemma 3/3n remain gated under the old ToU.
- Versions confirmed on crates.io: llama-cpp-2 0.1.154, rten 0.25.0, llguidance 1.8.0,
  candle-core 0.11.0.

## THE WALL — and it is a product decision, not a library one
**Gemma 4 is not browser-deployable at any quantisation.** Its ONNX export is ~3.6 GB
(1864 MB decoder + 1763 MB embeddings); even the mobile QAT build is ~2.3 GB. No engine
in the field has a memory64 path, so all are capped at **4 GB linear memory**, and
mmap is unavailable on wasm, so weights must land in that same memory.

E2B is also a size trap: its Per-Layer Embeddings table is ~2.35B of 5.1B params, so the
"2B" model ships a **3.35 GB** q4_0 GGUF.

**Consequence, stated plainly: two models forever.** Gemma 4 native, and something
sub-1 GB in the browser — two prompt formats, two quality bars, two eval sets. Viable
browser candidates: SmolLM2-360M-Instruct q4 (**388 MB, Apache-2.0**) or Qwen3-0.6B
(618 MB). Gemma-3-270m is 273 MB but carries the OLD ToU — gated, NOTICE file, and
downstream use restrictions we would have to impose on our own users. **Prefer
SmolLM2 and pay the 115 MB.**

## RULED OUT, with the specific blocker
- **candle** (the popular choice): wasm build **fails on the released crate** with
  `cannot find type CurrentCpuF16` under `+simd128` — the exact flag its own
  `.cargo/config.toml` sets. Open issue #3835, two unmerged fixes. Works only in scalar
  mode, discarding most CPU throughput. Root cause is structural: `grep -rn wasm
  .github/` in huggingface/candle returns **nothing** — zero wasm CI behind 11 in-tree
  browser demos. Also: `gemma4` exists but uses `VarBuilder`/`Linear`, **not `QMatMul`** —
  there is no `quantized_gemma4`, so no GGUF path at all.
- **mistral.rs**: crates.io 0.8.1 is 4 months behind git v0.9.2; zero wasm; architecturally
  a server (unconditional tokio/reqwest/hf-hub).
- **llama-cpp-2 for wasm**: `build.rs` `panic!`s on any triple outside
  Windows/Apple/Linux/Android. Emscripten↔wasm-bindgen ABI mismatch means a naive patch
  would not suffice.

## WE DO NOT NEED vLLM's FEATURES — and this is the argument for rejecting the featureful option
Continuous batching amortises across *concurrent independent* requests; our calls are
sequential within a turn. Paged attention fixes KV fragmentation under long variable
contexts; ours are short prompts and short outputs. What actually matters is low
per-call overhead and **prefix caching** for the fixed system prompt — both backends give
that cheaply. Adopting a server-shaped engine would buy a tokio dependency tree for
nothing measurable.

## COEXISTENCE WITH THE no_std CORE
`resolver = "2"` must be set **explicitly** — a virtual workspace manifest does not
inherit `edition`, so resolver 2 is not implied. But resolver 2 does NOT stop feature
unification between two members built together, so the real protection is procedural:
**never let `cargo build --workspace` be the proof of no_std compliance.** CI must build
the core alone:
```
cargo build -p ciris-sim-core --no-default-features --target wasm32-unknown-unknown
cargo build -p ciris-sim-core --no-default-features --target wasm32-wasip1
```
Feature-gate the engines (`native` → llama-cpp-2, `web` → rten) behind one `NlBridge`
trait, so the symbolic layer never sees an engine and a machine without a C++ toolchain
can still build the physics core.

## THE HOLE — close this before committing
**There are no CPU latency or cold-start numbers for any engine on small models.** Every
published benchmark is GPU, Apple Silicon, or Xeon-AMX. Per-call overhead on generic x86
— precisely our axis — is unmeasured everywhere. That needs a spike, not more desk
research, and it should happen before we write code against either engine.

Second risk worth pricing: `rten-generate` has ~1,211 recent downloads. It is the
least-exercised code in this recommendation and it runs our decode loop. rten has the
best CI discipline of the seven, but expect to read its source.
