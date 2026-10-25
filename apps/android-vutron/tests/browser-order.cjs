// Run explicitly: node tests/browser-order.cjs. Uses installed Edge, a temporary profile and Vite.
const fs = require('node:fs'); const path = require('node:path'); const { spawn, execFileSync } = require('node:child_process'); const assert = require('node:assert/strict');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function main() {
  const app = path.resolve(__dirname, '..'), repo = path.resolve(app, '../..'), tempRoot = path.join(repo, '_analysis');
  const profile = fs.mkdtempSync(path.join(tempRoot, 'ui-order-'));
  fs.writeFileSync(path.join(profile, '.vutron-order-test'), 'local-order-touch');
  const edge = process.env.EDGE_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
  const { createServer } = await import('vite');
  const server = await createServer({ root: app, server: { host: '127.0.0.1', port: 0 }, logLevel: 'error' });
  await server.listen();
  const child = spawn(edge, ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--remote-debugging-port=0', '--user-data-dir=' + profile, 'about:blank'], { windowsHide: true, stdio: 'ignore' });
  let page, browser, failure;
  async function connect(address) {
    const ws = new WebSocket(address), pending = new Map(); let sequence = 0;
    await new Promise((resolve, reject) => { ws.addEventListener('open', resolve, { once: true }); ws.addEventListener('error', reject, { once: true }); });
    ws.addEventListener('message', message => { const data = JSON.parse(message.data); const item = pending.get(data.id); if (!item) return; pending.delete(data.id); data.error ? item.reject(new Error(JSON.stringify(data.error))) : item.resolve(data.result); });
    return { ws, send: (method, params = {}) => new Promise((resolve, reject) => { const id = ++sequence; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params })); }) };
  }
  try {
    const portFile = path.join(profile, 'DevToolsActivePort');
    for (let i = 0; !fs.existsSync(portFile) && i < 100; i++) await delay(100);
    if (!fs.existsSync(portFile)) throw new Error('Edge remote debugging did not start');
    const port = Number(fs.readFileSync(portFile, 'utf8').split('\n')[0]);
    const info = await (await fetch('http://127.0.0.1:' + port + '/json/version')).json();
    browser = await connect(info.webSocketDebuggerUrl);
    const target = await (await fetch('http://127.0.0.1:' + port + '/json/new?about:blank', { method: 'PUT' })).json();
    page = await connect(target.webSocketDebuggerUrl);
    const evaluate = async expression => { const result = await page.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true }); if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text); return result.result.value; };
    await page.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    await page.send('Emulation.setTouchEmulationEnabled', { enabled: true });
    await page.send('Page.navigate', { url: server.resolvedUrls.local[0] + 'tests/fixtures/local-order.html' });
    for (let i = 0; !(await evaluate('Boolean(window.orderReady)')) && i < 100; i++) await delay(100);
    assert.equal(await evaluate('Boolean(window.orderReady)'), true);
    await evaluate("document.querySelector('.local-order-tools select').value='custom'; document.querySelector('.local-order-tools select').dispatchEvent(new Event('change',{bubbles:true}))"); await delay(100);
    const bounds = selector => evaluate("(()=>{const r=document.querySelector(" + JSON.stringify(selector) + ").getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()");
    const touch = async (type, point) => page.send('Input.dispatchTouchEvent', { type, touchPoints: point ? [{ x: point.x, y: point.y, id: 1 }] : [] });
    let start = await bounds('.sort-handle'), destination = await bounds('[data-local-track-key="2"]'); destination.y += 10;
    await touch('touchStart', start); await touch('touchMove', destination); await delay(80);
    await touch('touchEnd'); await delay(100);
    assert.deepEqual(await evaluate("Array.from(document.querySelectorAll('[data-local-track-key]')).slice(0,4).map(x=>x.dataset.localTrackKey)"), ['1', '2', '0', '3']);
    const beforeCancel = await evaluate("localStorage.getItem('vutron:collection-orders:v1')");
    start = await bounds('.sort-handle'); destination = await bounds('[data-local-track-key="3"]');
    await touch('touchStart', start); await touch('touchMove', destination); await touch('touchCancel'); await delay(80);
    assert.equal(await evaluate("localStorage.getItem('vutron:collection-orders:v1')"), beforeCancel);
    await evaluate("window.orderHarness.state.query='Track 10'"); await delay(100);
    const row = await bounds('[data-local-track-key="10"]'); await touch('touchStart', row); await touch('touchEnd'); await delay(100);
    const playback = await evaluate('window.orderHarness.plays.at(-1)');
    assert.equal(playback.selected, 'Track 10'); assert.equal(playback.queue.length, 120); assert.deepEqual(playback.queue.slice(0,3), ['Track 1','Track 2','Track 0']);
    assert.equal(await evaluate("localStorage.getItem('vutron:collection-orders:v1')"), beforeCancel);
    await evaluate("window.orderHarness.state.query=''"); await delay(100);
    start = await bounds('.sort-handle'); await touch('touchStart', start); await touch('touchMove', { x: start.x, y: 800 }); await delay(450);
    assert.ok(await evaluate('window.scrollY > 0')); await touch('touchCancel');
    await page.send('Page.reload');
    for (let i = 0; !(await evaluate('Boolean(window.orderReady)')) && i < 100; i++) await delay(100);
    await delay(100);
    assert.deepEqual(await evaluate("Array.from(document.querySelectorAll('[data-local-track-key]')).slice(0,3).map(x=>x.dataset.localTrackKey)"), ['1','2','0']);
    console.log('PASS: real touch drag, cancellation, edge scroll, filtered full playback and reload persistence');
  } catch (error) { failure = error; console.error('Interaction check:', error.message); }
  finally {
    if (browser) { try { await browser.send('Browser.close'); } catch {} browser.ws.close(); }
    if (page) page.ws.close();
    await server.close();
    for (let i = 0; child.exitCode === null && i < 30; i++) await delay(100);
    if (child.exitCode === null) child.kill();
    const relative = path.relative(tempRoot, path.resolve(profile));
    if (!relative.startsWith('..') && !path.isAbsolute(relative) && relative.startsWith('ui-order-')) {
      try {
        if (process.platform === 'win32') {
          const script = [
            'import sys,ctypes,ctypes.wintypes as w',
            'from pathlib import Path',
            'root=Path(sys.argv[1]).resolve();target=Path(sys.argv[2]).resolve()',
            "assert target.parent==root and target.name.startswith('ui-order-') and (target/'.vutron-order-test').read_text()=='local-order-touch'",
            "assert not any(getattr(p.lstat(),'st_file_attributes',0)&0x400 for p in [target,*target.rglob('*')])",
            'class Operation(ctypes.Structure):',
            " _fields_=[('hwnd',w.HWND),('wFunc',w.UINT),('pFrom',w.LPCWSTR),('pTo',w.LPCWSTR),('fFlags',w.WORD),('aborted',w.BOOL),('mapping',ctypes.c_void_p),('title',w.LPCWSTR)]",
            "operation=Operation();operation.wFunc=3;operation.pFrom=str(target)+chr(0);operation.fFlags=0x40|0x04|0x10|0x400",
            "api=ctypes.WinDLL('shell32').SHFileOperationW;api.argtypes=[ctypes.POINTER(Operation)]",
            'assert api(ctypes.byref(operation))==0 and not operation.aborted and not target.exists()'
          ].join(String.fromCharCode(10));
          execFileSync('py', ['-3', '-c', script, tempRoot, profile], { windowsHide: true, stdio: 'pipe' });
        } else fs.rmSync(profile, { recursive: true, force: true });
      }
      catch (error) { console.warn('Browser profile cleanup pending:', profile, error.code); }
    }
  }
  if (failure) throw failure;
}
main().catch(error => { console.error(error); process.exitCode = 1; });
