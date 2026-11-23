(() => {
  'use strict';
  const actions = [['camera', 'openCamera'], ['gallery', 'openGallery']].map(([id, method]) => [document.getElementById(id), method]);
  const state = document.getElementById('status');
  const diagnostics = document.getElementById('diagnostics');
  const messages = Object.freeze({
    scanning: '正在等待原生扫码结果…',
    recognized: '已识别二维码，未提交签到。',
    cancelled: '已取消，可重新打开扫码器。',
    unavailable: '扫码器暂不可用，请返回重试。'
  });
  const native = window.ClassBellScan;
  let startedAt;
  let goAvailable = false;
  let initializing = true;
  const deferredResults = [];

  function updateDiagnostics(code, elapsed) {
    const stats = window.classbellRecordScan(code, elapsed);
    if (!stats) return;
    diagnostics.textContent = `Go 本地诊断：识别 ${stats.recognized} 次 · 取消 ${stats.cancelled} 次 · 不可用 ${stats.unavailable} 次 · 最近 ${stats.lastMillis} 毫秒`;
  }

  async function startDiagnostics() {
    const runtime = document.createElement('script');
    runtime.src = '/wasm_exec.js';
    const loaded = new Promise((resolve, reject) => {
      runtime.addEventListener('load', resolve, { once: true });
      runtime.addEventListener('error', reject, { once: true });
    });
    try {
      document.head.appendChild(runtime);
      await loaded;
      const go = new globalThis.Go();
      const response = await fetch('/classbell-scan.wasm');
      if (!response.ok) throw new Error('Go module is unavailable');
      const { instance } = await WebAssembly.instantiate(await response.arrayBuffer(), go.importObject);
      void go.run(instance).catch(disableDiagnostics);
      if (typeof window.classbellRecordScan !== 'function') throw new Error('Go module did not initialize');
      goAvailable = true;
      initializing = false;
      diagnostics.textContent = 'Go 本地诊断已就绪，统计仅保留在当前页面。';
      for (const [code, elapsed] of deferredResults.splice(0)) updateDiagnostics(code, elapsed);
    } catch {
      disableDiagnostics();
    }
  }

  function disableDiagnostics() {
    goAvailable = false;
    initializing = false;
    deferredResults.length = 0;
    diagnostics.textContent = 'Go 本地诊断不可用，扫码仍可使用。';
  }

  window.classbellScanResult = (code) => {
    if (!Object.prototype.hasOwnProperty.call(messages, code)) return;
    state.textContent = messages[code];
    for (const [button, method] of actions) button.disabled = code === 'scanning' || typeof native?.[method] !== 'function';
    if (code === 'scanning') return;
    const elapsed = startedAt === undefined ? 0 : Math.max(0, performance.now() - startedAt);
    startedAt = undefined;
    if (goAvailable) {
      try { updateDiagnostics(code, elapsed); } catch { disableDiagnostics(); }
    } else if (initializing && deferredResults.length < 10) {
      deferredResults.push([code, elapsed]);
    }
  };

  for (const [button, method] of actions) {
    if (typeof native?.[method] !== 'function') continue;
    button.disabled = false;
    button.addEventListener('click', () => {
      if (button.disabled) return;
      startedAt = performance.now();
      window.classbellScanResult('scanning');
      try { native[method](); } catch { window.classbellScanResult('unavailable'); }
    });
  }
  state.textContent = actions.some(([button]) => !button.disabled) ? '已就绪。' : '请在 ClassBell 应用内打开此工具。';
  void startDiagnostics();
})();
