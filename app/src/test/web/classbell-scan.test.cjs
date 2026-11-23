const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { setTimeout: delay } = require('node:timers/promises');

const app = path.resolve(__dirname, '../../..');
const scripts = path.join(app, 'src/main/assets');
const generated = path.join(app, 'build/generated/assets/buildClassBellScanWasm');
const runtime = fs.readFileSync(path.join(generated, 'wasm_exec.js'), 'utf8');
const wasm = fs.readFileSync(path.join(generated, 'classbell-scan.wasm'));

function fakeElement() {
  const listeners = new Map();
  return {
    disabled: true,
    textContent: '',
    addEventListener(name, callback) { listeners.set(name, callback); },
    fire(name) { listeners.get(name)?.(); }
  };
}

function openPage(withWasm, openCamera, openGallery = () => {}) {
  const button = fakeElement();
  const gallery = fakeElement();
  const status = fakeElement();
  const diagnostics = fakeElement();
  const env = {
    TextEncoder, TextDecoder, Uint8Array, WebAssembly, crypto: globalThis.crypto,
    performance: { now: (() => { let ticks = 0; return () => (ticks += 123); })() },
    console, setTimeout, clearTimeout,
    document: {
      createElement: fakeElement,
      getElementById: (id) => ({ camera: button, gallery, status, diagnostics })[id],
      head: { appendChild(script) {
        assert.equal(script.src, '/wasm_exec.js');
        vm.runInContext(runtime, context);
        script.fire('load');
      } }
    },
    fetch: async (url) => {
      assert.equal(url, '/classbell-scan.wasm');
      return { ok: withWasm, arrayBuffer: async () => wasm };
    },
    ClassBellScan: { openCamera, openGallery }
  };
  env.window = env;
  const context = vm.createContext(env);
  vm.runInContext(fs.readFileSync(path.join(scripts, 'classbell-scan.js'), 'utf8'), context);
  return { button, gallery, status, diagnostics, env };
}

async function until(predicate) {
  for (let attempt = 0; attempt < 200 && !predicate(); attempt++) await delay(10);
  assert.ok(predicate(), 'timed out waiting for diagnostics');
}

(async () => {
  let calls = 0;
  let galleryCalls = 0;
  const { button, gallery, status, diagnostics, env } = openPage(true, () => { calls++; }, () => { galleryCalls++; });
  button.fire('click');
  gallery.fire('click');
  button.fire('click');
  assert.equal(calls, 1);
  assert.equal(galleryCalls, 0);
  assert.equal(button.disabled, true);
  assert.equal(gallery.disabled, true);
  env.classbellScanResult('unknown');
  assert.equal(button.disabled, true);
  // The result can return before Go is initialized and must still be counted.
  env.classbellScanResult('recognized');
  assert.match(status.textContent, /未提交签到/);
  assert.equal(button.disabled, false);
  await until(() => diagnostics.textContent.includes('识别 1 次'));
  assert.match(diagnostics.textContent, /最近 123 毫秒/);
  gallery.fire('click');
  assert.equal(galleryCalls, 1);
  env.classbellScanResult('cancelled');
  assert.match(diagnostics.textContent, /识别 1 次 · 取消 1 次/);
  assert.equal(env.classbellRecordScan('unknown', 1), null);

  let fallbackCalls = 0;
  const fallback = openPage(false, () => { fallbackCalls++; });
  await until(() => fallback.diagnostics.textContent.includes('扫码仍可使用'));
  fallback.button.fire('click');
  fallback.env.classbellScanResult('cancelled');
  assert.equal(fallbackCalls, 1);
  assert.equal(fallback.button.disabled, false);
  console.log('Page + real Go WASM, duplicate clicks, early results, and fallback: passed');
})().catch((error) => { console.error(error); process.exitCode = 1; });
