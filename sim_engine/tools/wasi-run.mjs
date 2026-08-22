// Minimal WASI runner so `cargo test --target wasm32-wasip1` can actually EXECUTE.
// Wired in via sim_engine/crates/ciris-sim-core/.cargo/config.toml as the target runner.
// Usage: node tools/wasi-run.mjs <module.wasm> [args...]
import { WASI } from 'node:wasi';
import { readFile } from 'node:fs/promises';
import process from 'node:process';

const [wasmPath, ...args] = process.argv.slice(2);
const wasi = new WASI({
  version: 'preview1',
  args: [wasmPath, ...args],
  env: process.env,
  preopens: { '/': '/' },
  returnOnExit: true,
});
const bytes = await readFile(wasmPath);
const mod = await WebAssembly.compile(bytes);
const instance = await WebAssembly.instantiate(mod, wasi.getImportObject());
process.exitCode = wasi.start(instance);
