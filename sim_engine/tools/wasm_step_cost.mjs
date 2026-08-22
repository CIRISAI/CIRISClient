// Two things static inspection cannot settle, measured on the built module:
//   1. does the engine's linear memory ever GROW during a long run (i.e. does anything
//      allocate)?  It is exported, so its byte length is readable directly.
//   2. what does a step cost on the wasm target, in the same units as the native bench?
//
//   node tools/wasm_step_cost.mjs <module.wasm>
import { readFile } from 'node:fs/promises';

const bytes = await readFile(process.argv[2]);
const { exports: e } = await WebAssembly.instantiate(await WebAssembly.compile(bytes), {});

const pages = () => e.memory.buffer.byteLength / 65536;
const p0 = pages();
e.probe_run(1_000_000, 1);
const p1 = pages();
console.log(`linear memory: ${p0} pages (${p0 * 64} KiB) before, ${p1} after 1e6 steps` +
            `  -> ${p0 === p1 ? 'NO GROWTH' : '!! GREW'}`);

for (const [name, sym] of [['harmonic', 1], ['default params', 0]]) {
  e.probe_run(50_000, sym);                       // warm up the JIT
  let best = Infinity;
  for (let rep = 0; rep < 5; rep++) {
    const n = 500_000;
    const t0 = process.hrtime.bigint();
    e.probe_run(n, sym);
    const ns = Number(process.hrtime.bigint() - t0) / n;
    best = Math.min(best, ns);
  }
  console.log(`step, ${name}: ${best.toFixed(1)} ns/step (best of 5 x 500k, node ${process.version})`);
}
console.log(`linear memory after timing: ${pages()} pages -> ${pages() === p0 ? 'NO GROWTH' : '!! GREW'}`);
