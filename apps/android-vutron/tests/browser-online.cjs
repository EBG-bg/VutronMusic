// Run explicitly: node tests/browser-online.cjs. Uses installed Edge, a temporary profile and Vite.
const fs = require('node:fs'); const path = require('node:path'); const { spawn, execFileSync } = require('node:child_process'); const assert = require('node:assert/strict');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function main() {
  const app = path.resolve(__dirname, '..'), repo = path.resolve(app, '../..'), tempRoot = path.join(repo, '_analysis');
  const profile = fs.mkdtempSync(path.join(tempRoot, 'ui-online-'));
  fs.writeFileSync(path.join(profile, '.vutron-online-test'), 'online-playlist-interactions');
  const edge = process.env.EDGE_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
  const { createServer } = await import('vite');
  const server = await createServer({ root: app, optimizeDeps: { entries: ['tests/fixtures/online-playlists.html'] }, plugins: [{name:'native-online-fixture', enforce:'pre', transform(code,id){ if(id.replaceAll('\\','/').endsWith('/src/platform/vutronPlugin.ts')) return "export { vutronPlugin } from '/tests/fixtures/online-native.mock.js'"; }}], server: { host: '127.0.0.1', port: 0 }, logLevel: 'error' });
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
    await page.send('Page.navigate', { url: server.resolvedUrls.local[0] + 'tests/fixtures/online-playlists.html' });
    for (let i = 0; !(await evaluate('Boolean(window.onlineReady)')) && i < 100; i++) await delay(100);
    assert.equal(await evaluate('Boolean(window.onlineReady)'), true);
    const click = (selector, label) => evaluate("(()=>{const button=Array.from(document.querySelectorAll("+JSON.stringify(selector)+")).find(el=>el.textContent.includes("+JSON.stringify(label)+"));if(!button)throw new Error('Missing button: '+"+JSON.stringify(label)+");button.click()})()");
    const checkFit = async () => assert.equal(await evaluate("Array.from(document.querySelectorAll('[role=dialog]')).every(el=>{const r=el.getBoundingClientRect();return r.left>=0&&r.right<=innerWidth&&r.top>=0&&r.bottom<=innerHeight})"),true);
    await evaluate('window.onlineHarness.openMenu()'); await delay(150);
    assert.equal(await evaluate("Array.from(document.querySelectorAll('.track-action-options button')).some(el=>el.textContent.includes('取消喜欢'))"),true);
    await checkFit();
    await click('.track-action-options button','取消喜欢');
    assert.deepEqual(await evaluate('Array.from(window.onlineHarness.store.likedTrackIds)'),[2]);
    await delay(260); assert.deepEqual(await evaluate('Array.from(window.onlineHarness.store.likedTrackIds)'),[]);
    await click('.track-action-options button','添加到歌单'); await delay(150);
    assert.deepEqual(await evaluate("Array.from(document.querySelectorAll('.online-playlist-choice b')).map(el=>el.textContent)"),['Own']);
    await checkFit(); await click('.online-playlist-choice','Own'); await delay(260);
    assert.equal(await evaluate('window.onlineHarness.backend.writes.at(-1).action'),'add');
    await evaluate("document.querySelector('#new-playlist').click()"); await delay(150);
    await evaluate("(()=>{const field=document.querySelector('.online-playlist-dialog input');field.value='New playlist';field.dispatchEvent(new Event('input',{bubbles:true}));const visibility=document.querySelector('.online-playlist-dialog select');visibility.value='10';visibility.dispatchEvent(new Event('change',{bubbles:true}))})()");
    await checkFit(); await evaluate("document.querySelector('.online-playlist-dialog form').requestSubmit();document.querySelector('.online-playlist-dialog form').requestSubmit()");
    await delay(260);
    assert.equal(await evaluate("window.onlineHarness.backend.writes.filter(row=>row.action==='create').length"),1);
    assert.equal(await evaluate('window.onlineHarness.store.playlists.find(row=>row.id===50).privacy'),10);
    await evaluate("document.querySelector('#more-playlist').click()"); await delay(120); await click('.online-dialog-actions button','编辑'); await delay(120);
    await evaluate("(()=>{const fields=document.querySelectorAll('.online-playlist-dialog input');fields[0].value='Edited';fields[0].dispatchEvent(new Event('input',{bubbles:true}));fields[1].value='流行;摇滚';fields[1].dispatchEvent(new Event('input',{bubbles:true}));const desc=document.querySelector('.online-playlist-dialog textarea');desc.value='Updated description';desc.dispatchEvent(new Event('input',{bubbles:true}))})()");
    await evaluate("document.querySelector('.online-playlist-dialog form').requestSubmit()"); await delay(260);
    assert.equal(await evaluate('window.onlineHarness.store.selectedPlaylist.name'),'Edited');
    assert.deepEqual(await evaluate('Array.from(window.onlineHarness.store.selectedPlaylist.tags)'),['流行','摇滚']);
    await evaluate('window.onlineHarness.openMenu()'); await delay(120); await click('.track-action-options button','从当前歌单移除'); await delay(120);
    await checkFit(); await evaluate("document.querySelector('.action-dialog [data-default-action]').click()"); await delay(260);
    assert.equal(await evaluate('window.onlineHarness.store.tracks.length'),0);
    await evaluate("document.querySelector('#more-playlist').click()"); await delay(120); await click('.online-dialog-actions button','删除'); await delay(120);
    await evaluate("document.querySelector('.action-dialog [data-default-action]').click()"); await delay(260);
    assert.equal(await evaluate('window.onlineHarness.store.selectedPlaylist'),undefined);
    assert.equal(await evaluate('window.onlineHarness.store.playlists.some(row=>row.id===42)'),false);
    await evaluate("window.onlineHarness.store.localOnly=true;document.querySelector('#new-playlist').click();window.onlineHarness.openMenu()"); await delay(150);
    assert.equal(await evaluate("document.querySelectorAll('.online-dialog-layer').length"),0);
    assert.equal(await evaluate("Array.from(document.querySelectorAll('.track-action-options button')).some(el=>/喜欢|添加到歌单|当前歌单移除/.test(el.textContent))"),false);
    await evaluate("document.querySelector('.track-action-menu header button').click();window.onlineHarness.store.activeTab='settings'");
    await evaluate("document.querySelector('[data-settings-section=app-update] > summary').click()"); await delay(150);
    await evaluate("document.querySelector('.about-entry').click()"); await delay(150);
    assert.equal(await evaluate('window.onlineHarness.store.activeTab'),'about');
    assert.match(await evaluate("document.querySelector('.about-identity').textContent"),/\d+\.\d+\.\d+/);
    await evaluate("document.querySelector('[data-settings-section=about-privacy] > summary').click()"); await delay(250);
    assert.ok(await evaluate("/privacy/i.test(document.querySelector('.about-document').textContent)"));
    await page.send('Emulation.setDeviceMetricsOverride', { width: 320, height: 640, deviceScaleFactor: 1, mobile: true });
    assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true);
    await evaluate('window.onlineHarness.store.navigateBack()'); await delay(120);
    assert.equal(await evaluate('window.onlineHarness.store.activeTab'),'settings');
    assert.ok(await evaluate("document.querySelector('.about-entry')"));
    console.log('PASS: real Vue/Pinia playlist management, own-only picker, duplicate-submit protection, confirmations, local-mode hiding, 390/320px layout, About entry, offline privacy and Back to settings');
  } catch (error) { failure = error; console.error('Interaction check:', error.message); }
  finally {
    if (browser) { try { await browser.send('Browser.close'); } catch {} browser.ws.close(); }
    if (page) page.ws.close();
    await server.close();
    for (let i = 0; child.exitCode === null && i < 30; i++) await delay(100);
    if (child.exitCode === null) child.kill();
    await delay(500);
    const relative = path.relative(tempRoot, path.resolve(profile));
    if (!relative.startsWith('..') && !path.isAbsolute(relative) && relative.startsWith('ui-online-')) {
      try {
        if (process.platform === 'win32') {
          const script = [
            'import sys,shutil,os,stat',
            'from pathlib import Path',
            'root=Path(sys.argv[1]).resolve();target=Path(sys.argv[2]).resolve()',
            "assert target.parent==root and target.name.startswith('ui-online-') and (target/'.vutron-online-test').read_text()=='online-playlist-interactions'",
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
