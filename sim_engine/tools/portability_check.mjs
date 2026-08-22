// Wasm half of the portability check: instantiate the probe module and print the same
// lines `examples/native_probe.rs` prints, as raw f64 bit patterns.
//
//   node tools/portability_check.mjs <module.wasm>
//
// wasm32-unknown-unknown modules here import nothing, so no import object is needed.
// A wasip1 cdylib may import wasi_snapshot_preview1; those imports are stubbed rather
// than serviced, because the probe never calls them.
import { readFile } from 'node:fs/promises';

const wasmPath = process.argv[2];
const bytes = await readFile(wasmPath);
const mod = await WebAssembly.compile(bytes);

const imports = {};
for (const imp of WebAssembly.Module.imports(mod)) {
  imports[imp.module] ??= {};
  imports[imp.module][imp.name] =
    imp.kind === 'function'
      ? () => { throw new Error(`probe called host import ${imp.module}.${imp.name}`); }
      : imp.kind === 'memory'
      ? new WebAssembly.Memory({ initial: 17 })
      : 0;
}
const { exports: e } = await WebAssembly.instantiate(mod, imports);

for (let s = 0; s < 3; s++) {
  const n = e.probe_scenario_len(s);
  for (let i = 0; i < n; i++) {
    const buf = new DataView(new ArrayBuffer(8));
    buf.setFloat64(0, e.probe_scenario_value(s, i));
    const hi = buf.getUint32(0).toString(16).padStart(8, '0');
    const lo = buf.getUint32(4).toString(16).padStart(8, '0');
    console.log(`${s} ${i} ${hi}${lo}`);
  }
}
const tols = [0.0, 0.5, 1.0, 2.0, 100.0];
tols.forEach((t, k) => console.log(`coarsen ${k} ${e.probe_coarsen_classes(t)}`));
