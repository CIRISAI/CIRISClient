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

---

## Measured CPU latency, and a loader correction — 2026-08-22

`inference-scout` measured the runtime hole on real hardware (i9-13900HX, AVX2/FMA, **no
AVX-512** — a Xeon/EPYC or Zen4+ box would read better). Nothing below is estimated.

### llama-cpp-2 native — settled, act on all three
| finding | number |
|---|---|
| cold start (process → first token) | **0.5–0.9 s**, and **prefill-dominated**, not load-dominated (GGUF is mmap'd; model_load is only 64ms at 360M) |
| per-call, 200-in / 30-out, 8 threads | SmolLM2-360M **602ms p50 / 690ms p95**; Qwen3-0.6B 742/842 |
| decode throughput | 7.8 ms/tok (SmolLM2 Q4) · 13.3 ms/tok (Qwen3 Q4) |
| **prefix reuse** | **1.56× (Qwen3) to 2.17× (SmolLM2) on p50 — and 2.35× on p95** |

Three binding consequences:
1. **Keep the inference process resident.** Cold start amortises to zero if it lives;
   fork-per-call pays 0.5–0.9s every time. Highest-priority integration constraint.
2. **Prefix reuse belongs in the first implementation, not a later optimisation.** ~10
   lines against `clear_kv_cache_seq`. It beats everything else on the list, it cuts the
   *tail* harder than the median, and the saving grows linearly with system-prompt length
   while its cost stays flat — measured with only a 150-token prefix, ours will be longer.
3. **Set threads to P-core count (8 here), not `num_cpus`.** Prefill and decode scale
   *oppositely* — prefill improves to 32 threads, decode peaks at 8 and degrades as it
   spills onto E-cores. `n_threads` and `n_threads_batch` should be set separately.

### rten — the benchmark measured the WRONG ARTIFACT; numbers are provisional
The scout hit a real failure — `rten-convert` rejected the prebuilt HF ONNX with **161
unconvertible operators**, naming exactly the `com.microsoft` contrib ops — and worked
around it with a self-exported fp32 (1638MB) and a dynamic-QUInt8 (553MB) build. **The
diagnosis was right; the workaround was unnecessary.** rten 0.25.0 has **two loaders with
different op coverage**:

| loader | format | `com.microsoft` registrations |
|---|---|---:|
| `onnx_loader` / `onnx_registry.rs` | `.onnx` **direct** | **7** (incl. GQA, RotaryEmbedding, SimplifiedLayerNorm) |
| `rten_loader` / `rten_registry.rs` | `.rten` (what `rten-convert` emits) | **0** |

`Model::load_file` auto-detects file type and the docs state models load "from either
`.onnx` or `.rten`". So the prebuilt **387.94MB `model_q4.onnx` loads directly**, contrib
ops included, with no conversion step to own. Consequences:

- The 1.6GB fp32 intermediate is not a cost we have to pay.
- **"int8 made rten slower" does NOT transfer to q4.** Dynamic QUInt8 wraps every MatMul
  in quantise/dequantise, which is why it lost at batch-1 GEMV. `MatMulNBits` is a *fused*
  int4 kernel with an `accuracy_level` attribute — a different code path, not the same trade.
- **rten's speed on the shipping artifact is therefore UNMEASURED.** The reported 2.1×
  (fp32) and 4.5× (int8) gaps behind llama.cpp are provisional.
- Re-measure at **our true output length**. The scout's sharpest observation: rten's
  *prefill is faster* than llama.cpp's (250ms vs 364ms) and only *decode* loses (33.7 vs
  7.8 ms/tok). A terse typed-tuple output is prefill-dominated, where rten may win outright.

### Open blocker, independent of the above — ranks above every performance number
`rten-text`'s `Tokenizer::from_file` fails with `BpeError(MissingVocabEntry("Ą"))` on both
the original and a freshly-exported `tokenizer.json` — an rten-text BPE gap, not a corrupt
file. Bypassed for timing with synthetic token IDs. **This is a genuine shipping blocker
for rten with this model family** until the `tokenizers` crate is swapped in.

### Not measured, stated plainly
No Gemma 4 number (3.35GB, impractical here — these are the *shape* of the overhead, not
Gemma-specific; do not extrapolate linearly). No llguidance mask overhead. **No wasm
runtime performance at all** — everything is native x86; browser will be worse
(single-threaded, no mmap). One machine, no AVX-512.

### Direct-ONNX load: VERIFIED BY EXECUTION, not inference — 2026-08-22
The loader correction above was made from source reading. I then ran it, because a
correction issued to another agent on unexecuted evidence is exactly the failure mode we
police elsewhere. `rten 0.25` + `Model::load_file` on the **unmodified prebuilt**
`HuggingFaceTB/SmolLM2-360M-Instruct/onnx/model_q4.onnx` (387,943,246 bytes, size-exact):

```
LOAD OK in 192.012719ms          <- no rten-convert, no self-export, contrib ops included
inputs  = 67   input_ids · attention_mask · position_ids · past_key_values.{0..31}.{key,value}
outputs = 65   logits [batch, seq, 49152] · present.{0..31}.{key,value}
```

Confirmed: the `com.microsoft` contrib ops load through the direct-ONNX path, the KV-cache
plumbing is intact (5 KV heads, head_dim 64), and **load is 192ms — comparable to the
self-exported int8 (187–194ms) and 2.6x faster than the fp32 (487–506ms)**, at a third of
fp32's size. The 1.6GB intermediate and the permanent conversion step are not costs we owe.

---

## The pin flip's browser cost, MEASURED — 2026-08-22

The 4-way surface eval convicted SmolLM2-360M (0.250 = uniform chance, below the 0.370
majority class) and gave Qwen3-0.6B real grip (0.467, p=0.035; McNemar vs SmolLM2
p=0.00119). So the pin flips on capability. The open question was the browser cost, which
had been quoted at 919MB. I proposed a rescue and then tested it. **The rescue fails.**

| candidate | file rten can load | size | `Model::load_file` |
|---|---|---:|---|
| SmolLM2-360M | `onnx/model_q4.onnx` | **387.9 MB** | **OK, 192 ms** |
| Qwen3-0.6B | `onnx/model_q4.onnx` | **919.1 MB** | **OK, 831 ms** |
| Qwen3-0.6B | `onnxruntime/cpu_and_mobile/cpu-int4-kld-block-128/model.onnx` | 524.6 MB | **FAILS** |

```
LOAD FAILED: in node "/model/embed_tokens/GatherBlockQuantized":
  com.microsoft/GatherBlockQuantized operator not supported or not enabled
```

**The hypothesis was right and the conclusion is still negative.** I predicted the 919MB
was inflated by an unquantised embedding table. It is — confirmed by the loaded graph:
Qwen3-0.6B's vocab is **151,936** against SmolLM2's **49,152**, a 3.09x larger embedding
table, and `MatMulNBits` quantises MatMul weights only, never the Gather-based embedding
lookup. ORT's `cpu_and_mobile` export gets to 524.6MB precisely *by* quantising that table
— which requires `GatherBlockQuantized`, an op rten does not implement. So the 1.75x saving
is real and **unavailable to us**.

### What this actually forces
**"One model, every platform" and "a model that works" are now in tension**, and that is
the decision, not a detail:

- **Native is settled.** Qwen3-0.6B GGUF Q4_K_M is 484MB through llama-cpp-2 — fine
  everywhere, and it is the only sub-gig candidate with measured signal.
- **Browser is not.** 919MB is not viable on mobile Safari. SmolLM2 fits at 388MB but
  reads *floor at both granularities*, so shipping it in the browser ships a model that
  cannot do the task — worse than shipping nothing.

Three honest routes, none free: accept desktop-browser-only at 919MB; implement
`GatherBlockQuantized` in rten (an upstream contribution, and the only route that gets
Qwen3 to 524.6MB); or find a model with a small vocabulary and real 4-way grip. **Vocabulary
size — not parameter count — is the binding constraint on browser payload**, which is the
generalisable lesson and should govern the next model search.

Unchanged: `onnx-community` declares no licence on any of these files, so the export must
be reproduced from the Apache-2.0 original regardless of which variant wins.

---

## CORRECTION: q4f16 DOES run in rten — my earlier note was wrong — 2026-08-22

Above I recorded "Open item #2 CLOSES NEGATIVE: rten cannot run `q4f16`", reasoning from
`impl Operator for MatMulNBits` declaring
`OutputType::Fixed(ValueType::Tensor(DataType::Float))`. **That reading was wrong.** That
declaration governs the operator's *output* dtype, not what the model file may *store*.
`inference-scout` tested it rather than accepting my source argument, and q4f16 loads,
runs, and produced byte-identical generated text against q4 on a matched prompt.

I have re-tested the consequence for both candidates. Every figure below is
`Model::load_file` on the unmodified published file:

| model | variant | size | load |
|---|---|---:|---|
| SmolLM2-360M | `model_q4.onnx` | 387.9 MB | OK 192 ms |
| **SmolLM2-360M** | **`model_q4f16.onnx`** | **272.7 MB** | OK (scout) |
| Qwen3-0.6B | `model_q4.onnx` | 919.1 MB | OK 831 ms |
| **Qwen3-0.6B** | **`model_q4f16.onnx`** | **569.8 MB** | **OK 594 ms** |
| Qwen3-0.6B | ORT `cpu-int4` (quantised embeddings) | 524.6 MB | **FAILS** — `GatherBlockQuantized` |

**The browser floor is 30–38% lower than recorded.** The `GatherBlockQuantized` failure
and the vocabulary analysis both still stand — 151,936 vs 49,152 is still why Qwen3 is
larger — but the flip's browser cost is **569.8 MB vs 272.7 MB (2.09x)**, not 919 vs 388.

### This materially strengthens the pin flip, on three independent counts
1. **569.8 MB is a shippable browser payload** where 919 MB was not.
2. **`rten-text` works on Qwen3 and FAILS on the SmolLM2 family** (`MissingVocabEntry("Ą")`
   at *load*, both 360M and 135M — family-wide, not one bad file). The tokenizer blocker
   is a SmolLM2 problem, not a Qwen3 one, so the flip removes it rather than inheriting it.
3. Qwen3-0.6B is the only sub-gig candidate with measured 4-way grip (0.467 vs SmolLM2's
   0.250 floor).

**Recommendation: flip the pin to Qwen3-0.6B on both targets** — native GGUF Q4_K_M 484 MB
via llama-cpp-2, browser `model_q4f16.onnx` 569.8 MB via rten direct-ONNX. The licence
obligation is unchanged and real: `onnx-community` declares no licence, so that export must
be reproduced from the Apache-2.0 original.

**Owed before shipping q4f16:** its numerical equivalence is verified on ONE short greedy
prompt. Identical output there does not establish equivalence across the distribution —
run the 4-way eval on q4f16 specifically before it ships.

---

# DECISION: the pin is Qwen3-0.6B — 2026-08-22 (Eric)

SmolLM2-360M-Instruct is **unpinned**. It reads floor at both granularities (12-way 0.060 —
statistically indistinguishable from chance and below the majority class; 4-way 0.250 —
exactly uniform chance), its collapse target moves with the prompt, and `rten-text` cannot
load its tokenizer at all. It is smaller and cleaner and it does not work.

## Pinned artifacts

| target | repo → file | size | licence |
|---|---|---:|---|
| **native** (llama-cpp-2) | `unsloth/Qwen3-0.6B-GGUF` → `Qwen3-0.6B-Q4_K_M.gguf` | **396.7 MB** | **apache-2.0**, ungated |
| **browser** (rten direct-ONNX) | `model_q4f16.onnx` — **must be self-produced**, see below | **569.8 MB** | — |
| **tokenizer** | `Qwen/Qwen3-0.6B` → `tokenizer.json` | 11.4 MB | apache-2.0 |

**Use unsloth's GGUF, not bartowski's.** The two disagreed in the scouting reports and both
were right about different files: `bartowski/Qwen_Qwen3-0.6B-GGUF` is **484.2 MB and declares
no licence**; `unsloth/Qwen3-0.6B-GGUF` is **396.7 MB and declares apache-2.0**. Smaller *and*
licensed — strictly better on both axes, so there is no trade to weigh.

**The browser ONNX must be reproduced by us.** `onnx-community/Qwen3-0.6B-ONNX` declares no
licence on any file, including the `q4f16` we measured. The upstream `Qwen/Qwen3-0.6B` LICENSE
is unmodified stock Apache-2.0 with no acceptable-use addendum, so the grant is real and the
gap is purely the re-publisher's undeclared artifact. Export from the Apache-2.0 original.

## Why this model
- **The only sub-gig candidate with measured grip.** 4-way surface: 0.467 vs SmolLM2's 0.250
  floor, McNemar p = 0.00119. Reaches 86% of a 14B control on the same task, unfine-tuned.
- **`rten-text` loads its tokenizer**, and fails family-wide on SmolLM2 (360M *and* 135M).
  The flip removes the tokenizer blocker rather than inheriting it.
- Loads in rten with **zero conversion** — `Model::load_file`, 594 ms, contrib ops included.

## Costs accepted, stated plainly
- **2.09x the browser payload** of SmolLM2 (569.8 vs 272.7 MB). Root cause measured and
  unavoidable: vocab 151,936 vs 49,152. `MatMulNBits` never touches the Gather-based
  embedding lookup, and ORT's 524.6 MB embedding-quantised export **fails to load**
  (`GatherBlockQuantized` unimplemented in rten). 569.8 MB is heavy for mobile Safari.
- **We own the ONNX export** in perpetuity.
- **q4f16 numerical equivalence rests on ONE short greedy prompt.** Run the 4-way eval on
  q4f16 specifically before it ships.

## Engine split, unchanged but for a new reason
llama-cpp-2 native / rten wasm still holds — but performance no longer decides it. With
prefix reuse the gap is **94 ms vs 111 ms (~15%)**, not 2x. Portability and tokenizer
support decide it now.
