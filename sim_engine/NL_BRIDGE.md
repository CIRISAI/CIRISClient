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

---

## Model decision VERIFIED against the artifacts — 2026-08-22

`model-scout` recommended **SmolLM2-360M-Instruct**, overturning the earlier
"two models forever" conclusion (which had anchored on Gemma 4 at 3.35GB). I checked its
own flagged open items against the real files rather than accepting the inference. **The
recommendation survives**, and three integration facts came out of the check that the
brief did not have.

### Confirmed by inspecting the actual ONNX graph
Range-fetched the first 6MB of `HuggingFaceTB/SmolLM2-360M-Instruct/onnx/model_q4.onnx`:

| op | count | domain |
|---|---:|---|
| `RotaryEmbedding` | 256 | com.microsoft |
| `MatMulNBits` | 224 | com.microsoft |
| `GroupQueryAttention` | 128 | com.microsoft |
| `SimplifiedLayerNormalization` | 65 | com.microsoft |

**Open item #3 CLOSES CONFIRMED** — the file really is on rten's documented `MatMulNBits`
int4 path. But note what else is in there: this export is **built from ORT fused contrib
ops**, not plain ONNX. rten's `llama.rs` example proves the *architecture*; it does not by
itself prove *this export*. So I checked the op registry directly (rten 0.25.0 source):
all four are implemented and registered, `RotaryEmbeddingMicrosoft` exists as a distinct
contrib variant, and rten's own test fixtures are named `LlamaMSFT`. rten supports this
export deliberately, not incidentally.

### NEW — three facts for whoever wires this up

1. **The contrib ops are behind a Cargo feature.**
   `register_op!("com.microsoft", GroupQueryAttention, feature = "contrib")`.
   It IS in `default`, so a plain dependency works — **but the wasm build must not set
   `default-features = false`**, which is exactly the reflex for trimming browser binary
   size. Doing so drops `contrib` and the model fails to load *at all*. If features are
   trimmed, `contrib` and `onnx_format` must be re-added explicitly.
2. **`wasm_api` is a non-default feature** — it must be enabled for the browser target.
3. **Open item #2 CLOSES NEGATIVE: rten cannot run `q4f16`.**
   `impl Operator for MatMulNBits` requires `TensorView<f32>` for both activations and
   scales, and declares `OutputType::Fixed(DataType::Float)`. There is no f16 path.
   The scout's conservative use of the `q4` files was **correct**, and the hoped-for
   halving of every browser payload **is not available**. The real browser payload is
   **387.94MB**, not ~200MB. This makes the SmolLM2-vs-Qwen3 gap *more* decisive, not
   less: Qwen3-0.6B's browser cost stays at 919MB, which is not viable on mobile Safari.

### Verdict
**Pin SmolLM2-360M-Instruct.** One model, all three platforms, first-party GGUF *and*
first-party ONNX, both apache-2.0 with no re-publisher in the licence chain — the exact
place every other candidate breaks. Native `Q4_K_M` 270.6MB, browser `model_q4.onnx`
387.94MB, tokenizer 2.10MB.

Unchanged and still owed: **no model at any size has been evaluated on an 11-category
typed-tuple taxonomy.** Published IFEval numbers cannot settle SmolLM2-vs-Qwen3 for our
task; only our own eval set can, and we are fine-tuning regardless.
