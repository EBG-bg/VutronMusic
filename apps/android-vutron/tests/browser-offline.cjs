// Run explicitly: node tests/browser-offline.cjs. Uses installed Edge, a temporary profile and Vite.
const fs = require('node:fs'); const path = require('node:path'); const { spawn, execFileSync } = require('node:child_process'); const assert = require('node:assert/strict');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function main() {
  const app = path.resolve(__dirname, '..'), repo = path.resolve(app, '../..'), tempRoot = path.join(repo, '_analysis');
  const profile = fs.mkdtempSync(path.join(tempRoot, 'ui-offline-'));
  fs.writeFileSync(path.join(profile, '.vutron-offline-test'), 'offline-mode-interactions');
  const edge = process.env.EDGE_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
  const { createServer } = await import('vite');
  const server = await createServer({ root: app, optimizeDeps: { entries: ['tests/fixtures/offline-library.html'] }, plugins: [{name:'native-online-fixture', enforce:'pre', transform(code,id){ if(id.replaceAll('\\','/').endsWith('/src/platform/vutronPlugin.ts')) return "export { vutronPlugin } from '/tests/fixtures/offline-native.mock.js'"; }}], server: { host: '127.0.0.1', port: 0 }, logLevel: 'error' });
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
    await page.send('Page.navigate', { url: server.resolvedUrls.local[0] + 'tests/fixtures/offline-library.html' });
    for (let i = 0; !(await evaluate('Boolean(window.offlineReady)')) && i < 100; i++) await delay(100);
    assert.equal(await evaluate('Boolean(window.offlineReady)'), true);
    const click = (selector,label)=>evaluate("(()=>{const el=Array.from(document.querySelectorAll("+JSON.stringify(selector)+")).find(el=>el.textContent.includes("+JSON.stringify(label)+"));if(!el)throw new Error('Missing '+"+JSON.stringify(label)+");el.click()})()");
    const ready = async()=>{for(let i=0;!(await evaluate('Boolean(window.offlineReady)'))&&i<100;i++)await delay(100);assert.equal(await evaluate('Boolean(window.offlineReady)'),true);await delay(180)};
    await ready(); assert.equal(await evaluate('window.offlineHarness.store.loggedIn'),true);
    assert.equal(await evaluate('window.offlineHarness.store.profile.nickname'),'Saved account');
    assert.equal(await evaluate('window.offlineHarness.store.error'),'');
    await evaluate("window.offlineHarness.store.navigateTo('library')"); await delay(180);
    await click('.segmented-control button','本机音乐');await delay(180);
    assert.ok(await evaluate("document.querySelector('.library-mode-description').textContent.includes('手机文件夹')"));
    assert.ok(await evaluate("document.body.textContent.includes('Phone song')&&document.body.textContent.includes('Imported songs')"));
    await click('.local-collection-links button','Imported songs'); await delay(180);
    assert.ok(await evaluate("document.body.textContent.includes('Imported song')"));
    await evaluate('window.offlineHarness.store.navigateBack()'); await delay(180);
    await click('.segmented-control button','缓存与离线');await delay(180);
    await evaluate("document.querySelector('[aria-label=\"删除离线歌单 Cached list\"]').click()");await delay(150);
    assert.equal(await evaluate('window.offlineHarness.store.selectedPlaylist'),undefined);
    assert.ok(await evaluate("document.querySelector('.action-dialog').textContent.includes('保留已缓存音频')"));
    await evaluate("document.querySelector('.action-dialog [data-default-action]').click()");await delay(180);
    assert.equal(await evaluate('window.offlineHarness.store.cachedPlaylists.some(row=>row.id===71)'),false);
    assert.equal(await evaluate('window.offlineHarness.store.cachedTracks.length'),1);
    assert.equal(await evaluate('window.offlineHarness.store.playbackQueue.tracks.length'),1);
    assert.equal(await evaluate('window.offlineHarness.backend.counts.deleteCachedPlaylist'),1);
    await page.send('Page.reload');await ready();
    assert.equal(await evaluate('window.offlineHarness.store.profile.userId'),7);
    assert.equal(await evaluate('window.offlineHarness.store.cachedPlaylists.some(row=>row.id===71)'),false);
    assert.equal(await evaluate('window.offlineHarness.store.cachedTracks.length'),1);
    await evaluate('window.offlineHarness.backend.setConnected(false)');await delay(180);
    assert.equal(await evaluate('window.offlineHarness.store.localOnly'),true);
    assert.equal(await evaluate('window.offlineHarness.store.manualLocalOnly'),false);
    assert.equal(await evaluate('window.offlineHarness.store.loggedIn'),true);
    assert.ok(await evaluate("document.body.textContent.includes('网络暂不可用')"));
    assert.equal(await evaluate("document.querySelector('.segmented-control button.selected').textContent"),'缓存与离线');
    await evaluate('window.offlineHarness.backend.setConnected(true)');await delay(180);
    assert.equal(await evaluate('window.offlineHarness.store.onlineAvailable'),true);
    assert.equal(await evaluate('window.offlineHarness.backend.counts.createQrLogin||0'),0);
    assert.equal(await evaluate('window.offlineHarness.backend.counts.loginWithCookie||0'),0);
    await evaluate("window.offlineHarness.store.navigateTo('settings')");await delay(180);
    await evaluate("document.querySelector('[role=switch][aria-label=\"手动保持离线\"]').click()");await delay(180);
    await evaluate('window.offlineHarness.backend.setConnected(false)');await evaluate('window.offlineHarness.backend.setConnected(true)');await delay(180);
    assert.equal(await evaluate('window.offlineHarness.store.manualLocalOnly'),true);
    assert.equal(await evaluate('window.offlineHarness.store.onlineAvailable'),false);
    assert.ok(await evaluate("document.querySelector('.account-settings').textContent.includes('Saved account')"));
    await page.send('Page.reload');await ready();
    assert.equal(await evaluate('window.offlineHarness.store.manualLocalOnly'),true);
    assert.equal(await evaluate('window.offlineHarness.store.loggedIn'),true);
    assert.equal(await evaluate('window.offlineHarness.backend.counts.getPlaylists||0'),0);
    await evaluate("window.offlineHarness.store.navigateTo('settings')");await delay(180);
    await evaluate("document.querySelector('[role=switch][aria-label=\"手动保持离线\"]').click()");await delay(180);
    assert.equal(await evaluate('window.offlineHarness.store.onlineAvailable'),true);
    await page.send('Emulation.setDeviceMetricsOverride',{width:320,height:740,deviceScaleFactor:1,mobile:true});
    await evaluate('window.offlineHarness.backend.setConnected(false)');await evaluate("window.offlineHarness.store.navigateTo('library')");await delay(180);
    assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true);
    assert.equal(await evaluate('window.offlineHarness.store.error'),'');
    await delay(1200);
    assert.equal(await evaluate("Number(getComputedStyle(document.querySelector('.page-stage')).opacity)>=.99"),true);
    const picture=await page.send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});fs.writeFileSync(path.join(tempRoot,'personal-0.3.17-library.png'),Buffer.from(picture.data,'base64'));
    console.log('PASS: real App cache-only deletion/restart, saved account, automatic reconnect, sticky manual mode, imported music labels and 320px layout');
  } catch (error) { failure = error; console.error('Interaction check:', error.message); }
  finally {
    if (browser) { try { await browser.send('Browser.close'); } catch {} browser.ws.close(); }
    if (page) page.ws.close();
    await server.close();
    for (let i = 0; child.exitCode === null && i < 30; i++) await delay(100);
    if (child.exitCode === null) child.kill();
    await delay(500);
    const relative = path.relative(tempRoot, path.resolve(profile));
    if (!relative.startsWith('..') && !path.isAbsolute(relative) && relative.startsWith('ui-offline-')) {
      try {
        if (process.platform === 'win32') {
          const script = [
            'import sys,shutil,os,stat',
            'from pathlib import Path',
            'root=Path(sys.argv[1]).resolve();target=Path(sys.argv[2]).resolve()',
            "assert target.parent==root and target.name.startswith('ui-offline-') and (target/'.vutron-offline-test').read_text()=='offline-mode-interactions'",
            "for item in [target,*target.rglob('*')]:",
            " try: assert not getattr(item.lstat(),'st_file_attributes',0)&0x400",
            " except FileNotFoundError: pass",
            'def retry(function,path,exception):',
            ' os.chmod(path,stat.S_IWRITE);function(path)',
            "shutil.rmtree(chr(92)*2+'?'+chr(92)+str(target),onerror=retry)",
            'assert not target.exists()'
          ].join(String.fromCharCode(10));
          execFileSync('py', ['-3', '-c', script, tempRoot, profile], { windowsHide: true, stdio: 'pipe' });
        } else fs.rmSync(profile, { recursive: true, force: true });
      }
      catch (error) { console.warn('Browser profile cleanup pending:', profile, error.stderr?.toString() || error.message); }
    }
  }
  if (failure) throw failure;
}
main().catch(error => { console.error(error); process.exitCode = 1; });
