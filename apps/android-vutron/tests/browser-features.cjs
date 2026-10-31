// Run explicitly. Real App.vue with an isolated native bridge; no account or service writes.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{spawn,execFileSync}=require('node:child_process');
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function main(){
 const app=path.resolve(__dirname,'..'),root=path.resolve(app,'../../_analysis');
 const profile=fs.mkdtempSync(path.join(root,'ui-features-'));
 const {createServer}=await import('vite');
 const bridgeFixture=process.env.VUTRON_TEST_SCENARIO==='sliders'?'sliders-native.mock.js':'features-native.mock.js';
 const server=await createServer({root:app,optimizeDeps:{entries:['tests/fixtures/features.html']},plugins:[{name:'feature-fixture',enforce:'pre',transform(code,id){if(id.replaceAll('\\','/').endsWith('/src/platform/vutronPlugin.ts'))return "export { vutronPlugin } from '/tests/fixtures/"+bridgeFixture+"'"}}],server:{host:'127.0.0.1',port:0},logLevel:'error'});
 await server.listen();
 const child=spawn(process.env.EDGE_PATH||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=0','--user-data-dir='+profile,'about:blank'],{windowsHide:true,stdio:'ignore'});
 let page,browser;
 async function connect(url){const ws=new WebSocket(url),pending=new Map();let sequence=0;await new Promise((yes,no)=>{ws.addEventListener('open',yes,{once:true});ws.addEventListener('error',no,{once:true})});ws.addEventListener('message',e=>{const m=JSON.parse(e.data),p=pending.get(m.id);if(!p)return;pending.delete(m.id);m.error?p.no(new Error(m.error.message)):p.yes(m.result)});return {ws,send:(method,params={})=>new Promise((yes,no)=>{const id=++sequence;pending.set(id,{yes,no});ws.send(JSON.stringify({id,method,params}))})}}
 try{
  const portFile=path.join(profile,'DevToolsActivePort');for(let i=0;!fs.existsSync(portFile)&&i<100;i++)await delay(100);
  const port=Number(fs.readFileSync(portFile,'utf8').split('\n')[0]),info=await(await fetch('http://127.0.0.1:'+port+'/json/version')).json();
  browser=await connect(info.webSocketDebuggerUrl);
  const target=await(await fetch('http://127.0.0.1:'+port+'/json/new?about:blank',{method:'PUT'})).json();page=await connect(target.webSocketDebuggerUrl);
  const evaluate=async expression=>{const r=await page.send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw new Error(r.exceptionDetails.exception?.description||r.exceptionDetails.text);return r.result.value};
  await page.send('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});
  await page.send('Page.navigate',{url:server.resolvedUrls.local[0]+'tests/fixtures/features.html'});
  const wait=async expression=>{for(let i=0;i<100;i++){if(await evaluate(expression))return;await delay(100)}throw new Error('Timed out: '+expression)};
  await wait('window.featureReady===true');
  if(process.env.VUTRON_TEST_SCENARIO==='sliders'){
    await require('./browser-sliders.cjs')({page,evaluate,wait,delay,root});return;
  }
  await evaluate("window.featureHarness.store.navigateTo('player')");await delay(500);
  const click=(selector,label)=>evaluate("(()=>{const e=Array.from(document.querySelectorAll("+JSON.stringify(selector)+")).find(e=>e.textContent.includes("+JSON.stringify(label)+"));if(!e)throw new Error('Missing button '+ "+JSON.stringify(label)+");e.click()})()");
  await evaluate("window.commentTrigger=Array.from(document.querySelectorAll('.player-page button')).find(e=>e.textContent.includes('歌曲评论'));window.commentTrigger.focus();window.commentTrigger.click()");
  await wait("document.querySelector('.comment-row')");
  assert.equal(await evaluate("document.querySelector('.app-shell').inert"),true);
  const key=async(name,modifiers=0)=>{
    await page.send('Input.dispatchKeyEvent',{type:'keyDown',key:name,code:name,windowsVirtualKeyCode:name==='Tab'?9:27,modifiers});
    await page.send('Input.dispatchKeyEvent',{type:'keyUp',key:name,code:name,windowsVirtualKeyCode:name==='Tab'?9:27,modifiers});
  };
  await evaluate("window.focusableComments=()=>Array.from(document.querySelector('.comments-sheet').querySelectorAll('button,input,textarea,select,a[href],summary,[tabindex]')).filter(e=>e.tabIndex>=0&&!e.matches(':disabled')&&e.getClientRects().length>0);window.focusableComments().at(-1).focus()");
  await key('Tab');
  assert.equal(await evaluate("document.activeElement===window.focusableComments()[0]"),true);
  await key('Tab',8);
  assert.equal(await evaluate("document.activeElement===window.focusableComments().at(-1)"),true);
  await evaluate("window.commentTrigger.focus()");
  assert.equal(await evaluate("document.querySelector('.comments-sheet').contains(document.activeElement)"),true);
  assert.equal(await evaluate("document.querySelector('.comment-content').textContent"),'<img src=x onerror=alert(1)> Plain text');
  assert.equal(await evaluate("document.querySelector('.comments-sheet img')"),null);
  await click('.comment-row .comment-actions button','3');await delay(200);
  assert.equal(await evaluate("document.querySelector('.comment-actions [aria-pressed=true]').textContent.trim()"),'4');
  await click('.comment-row .comment-actions button','楼层回复');await wait("document.querySelector('.comment-floor article')");
  assert.ok(await evaluate("document.querySelector('.comment-floor').textContent.includes('该评论已删除')"));
  await evaluate("(()=>{const e=document.querySelector('.comment-composer textarea');e.value='My comment';e.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('.comment-composer').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))})()");
  await wait("window.featureHarness.backend.counts.add===1");await wait("document.querySelector('.comment-content').textContent==='My comment'");
  await click('.comment-row .comment-actions button','删除');await wait("document.querySelector('.action-dialog')");
  assert.equal(await evaluate("document.querySelector('.comments-layer').inert"),true);
  assert.equal(await evaluate("document.querySelector('.action-dialog').closest('[inert]')"),null);
  assert.equal(await evaluate("document.querySelector('.action-dialog').contains(document.activeElement)"),true);
  await key('Escape');await wait("!document.querySelector('.action-dialog')");
  assert.equal(await evaluate("Boolean(document.querySelector('.comments-sheet'))"),true);
  assert.equal(await evaluate("document.querySelector('.comments-sheet').contains(document.activeElement)"),true);
  await click('.comment-row .comment-actions button','删除');await wait("document.querySelector('.action-dialog')");
  await evaluate("document.querySelector('.action-dialog [data-default-action]').click()");await wait("window.featureHarness.backend.counts.delete===1");
  await click('.comments-sheet header button','');await delay(300);
  assert.equal(await evaluate("document.activeElement===window.commentTrigger"),true);
  assert.equal(await evaluate("document.querySelector('.app-shell').inert"),false);
  await evaluate("window.commentMenuTrigger=document.querySelector('.player-title-row [data-track-menu-button]');window.commentMenuTrigger.focus();window.commentMenuTrigger.click()");await wait("document.querySelector('.track-action-menu')");
  await click('.track-action-options button','歌曲评论');await wait("document.querySelector('.comments-sheet')");
  await wait("!document.querySelector('.track-action-menu')");
  await key('Escape');await wait("!document.querySelector('.comments-sheet')");
  assert.equal(await evaluate("document.activeElement===window.commentMenuTrigger"),true);
  await evaluate("window.featureHarness.store.navigateTo('settings')");await delay(300);
  await click('summary','播放 · 高级');await delay(300);
  assert.equal(await evaluate("document.querySelector('[aria-label=\"全局倍速\"]').value"),'1');
  await evaluate("(()=>{const e=document.querySelector('[aria-label=\"全局倍速\"]');e.value='1.5';e.dispatchEvent(new Event('change',{bubbles:true}))})()");
  await wait('window.featureHarness.store.playback.rate===1.5');
  await evaluate("(()=>{const e=Array.from(document.querySelectorAll('label')).find(e=>e.textContent.includes('Wi-Fi 下预取'));e.querySelector('input').click()})()");
  await wait("JSON.parse(localStorage.getItem('feature-tuning')).prefetchWifi===true");
  await page.send('Page.reload');await wait('window.featureReady===true');await evaluate("window.featureHarness.store.navigateTo('settings')");await delay(400);
  assert.equal(await evaluate("document.querySelector('[aria-label=\"全局倍速\"]').value"),'1.5');
  await evaluate("window.featureHarness.backend.setConnected(false)");await delay(300);
  const before=await evaluate('window.featureHarness.backend.counts.comments||0');
  await evaluate("window.featureHarness.store.navigateTo('player')");await delay(400);
  assert.equal(await evaluate("Array.from(document.querySelectorAll('.player-page button')).some(e=>e.textContent.includes('歌曲评论'))"),false);
  assert.equal(await evaluate('window.featureHarness.backend.counts.comments||0'),before);
  await page.send('Emulation.setDeviceMetricsOverride',{width:320,height:740,deviceScaleFactor:1,mobile:true});
  await evaluate("window.featureHarness.backend.setConnected(true)");await delay(250);await click('.player-page button','歌曲评论');await wait("document.querySelector('.comment-row')");await delay(1200);
  assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true);
  const picture=await page.send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});fs.writeFileSync(path.join(root,process.env.VUTRON_TEST_SCREENSHOT||'mobile-features-comments.png'),Buffer.from(picture.data,'base64'));
  await click('.comments-sheet header button','');await wait("!document.querySelector('.comments-sheet')");
  await evaluate('window.featureHarness.backend.setCommentCount(360)');
  await click('.player-page button','歌曲评论');await wait("document.querySelectorAll('.comment-row').length===30");
  const scrollBottom=async()=>{
    await evaluate("document.querySelector('.comments-sheet').scrollTop=0");await delay(100);
    await evaluate("(()=>{const sheet=document.querySelector('.comments-sheet');sheet.scrollTop=sheet.scrollHeight})()");
  };
  for(let loaded=30;loaded<300;loaded+=30){
    await scrollBottom();await wait("document.querySelectorAll('.comment-row').length==="+(loaded+30));
  }
  const requestsAtLimit=await evaluate('window.featureHarness.backend.counts.comments');
  for(let i=0;i<3;i++){await scrollBottom();await delay(180)}
  assert.equal(await evaluate('window.featureHarness.backend.counts.comments'),requestsAtLimit);
  assert.ok(await evaluate("document.querySelector('.comment-count').textContent.includes('300')&&document.querySelector('.comment-count').textContent.includes('360')"));
  assert.equal(await evaluate("getComputedStyle(document.querySelector('.comment-row')).contentVisibility"),'auto');
  await click('.comment-more button','继续加载');await wait("document.querySelectorAll('.comment-row').length===330");
  assert.equal(await evaluate('window.featureHarness.backend.counts.comments'),requestsAtLimit+1);
  await scrollBottom();await delay(300);
  assert.equal(await evaluate('window.featureHarness.backend.counts.comments'),requestsAtLimit+1);
  await click('.comment-more button','继续加载');await wait("document.querySelectorAll('.comment-row').length===360");
  assert.ok(await evaluate("document.querySelector('.comment-more').textContent.includes('没有更多评论')"));
  await click('.comments-sheet header button','');await wait("!document.querySelector('.comments-sheet')");
  await evaluate("(async()=>{window.reviewPlugin=(await import('/src/platform/vutronPlugin.ts')).vutronPlugin;window.originalCacheRead=window.reviewPlugin.getCachedTracks;window.cacheRows=Array.from({length:160},(_,i)=>({id:1000+i,name:'Cached '+i,cached:true,localUri:'file:///cache/'+i+'.audio',artists:['Cache']}));window.reviewPlugin.getCachedTracks=async()=>({tracks:structuredClone(window.cacheRows)});await window.featureHarness.store.loadCacheStatus(false);window.featureHarness.store.navigateTo('library')})()");
  await delay(350);await click('.segmented-control button','缓存与离线');
  await wait("document.querySelectorAll('.offline-content .track-row').length===80");
  await evaluate("document.querySelector('.offline-content .track-list-pager button, .offline-content .track-list .load-more-button, .offline-content .track-list .track-pager button')?.click()");
  await wait("document.querySelectorAll('.offline-content .track-row').length===160");
  await evaluate('window.featureHarness.store.loadCacheStatus(false)');await delay(150);
  assert.equal(await evaluate("document.querySelectorAll('.offline-content .track-row').length"),160);
  await evaluate("window.reviewPlugin.getCachedTracks=window.originalCacheRead;window.searchRequests=[];window.reviewPlugin.search=async query=>{window.searchRequests.push({...query});return {items:[{id:query.page,name:query.keywords+' page '+query.page}],count:4,page:query.page,nextPage:query.page+1}};window.featureHarness.store.navigateTo('search')");
  await evaluate("window.featureHarness.store.runSearch('tracks','Alpha',true)");
  await evaluate("window.featureHarness.store.searchKeywords='Beta';window.featureHarness.store.loadMoreSearch()");
  assert.deepEqual(await evaluate('window.searchRequests.map(q=>[q.keywords,q.page])'),[['Alpha',1],['Alpha',2]]);
  assert.deepEqual(await evaluate('window.featureHarness.store.searchResults.map(row=>row.name)'),['Alpha page 1','Alpha page 2']);
  assert.equal(await evaluate('window.featureHarness.store.searchKeywords'),'Beta');
  await evaluate("window.featureHarness.store.runSearch('tracks','Beta',true)");
  assert.deepEqual(await evaluate('window.featureHarness.store.searchResults.map(row=>row.name)'),['Beta page 1']);
  await evaluate("window.featureHarness.store.navigateTo('player')");await delay(350);
  await evaluate("window.queueReturnFocus=document.querySelector('.playback-queue-button');window.queueReturnFocus.focus();window.queueReturnFocus.click()");
  await wait("document.querySelector('.queue-panel')&&document.querySelector('.queue-panel').contains(document.activeElement)");
  assert.equal(await evaluate("document.querySelector('.app-shell').inert"),true);
  await evaluate("window.queueControls=()=>Array.from(document.querySelector('.queue-panel').querySelectorAll('button,input,select,textarea,a[href],summary,[tabindex]')).filter(e=>e.tabIndex>=0&&!e.matches(':disabled')&&e.getClientRects().length>0);window.queueControls().at(-1).focus()");
  await key('Tab');assert.equal(await evaluate('document.activeElement===window.queueControls()[0]'),true);
  await key('Tab',8);assert.equal(await evaluate('document.activeElement===window.queueControls().at(-1)'),true);
  await key('Escape');await wait("!document.querySelector('.queue-panel')");
  assert.equal(await evaluate('document.activeElement===window.queueReturnFocus'),true);
  assert.equal(await evaluate("document.querySelector('.app-shell').inert"),false);
  console.log('PASS: real App cache refresh keeps 160 rows; search query snapshots prevent mixed pages; queue keyboard/focus/inert; comment 300 cap and existing interactions');
 }catch(error){console.error('Feature interaction:',error.message);throw error}finally{
  if(browser){try{await browser.send('Browser.close')}catch{}browser.ws.close()}if(page)page.ws.close();await server.close();
  for(let i=0;child.exitCode===null&&i<30;i++)await delay(100);if(child.exitCode===null)child.kill();await delay(500);
  const resolved=path.resolve(profile);if(path.dirname(resolved)!==root||!path.basename(resolved).startsWith('ui-features-'))throw new Error('Unexpected temporary path');
  try {
   if(process.platform==='win32')execFileSync('py',['-3','-c',[
    'import sys,shutil,stat,os,time',
    'from pathlib import Path',
    'root=Path(sys.argv[1]).resolve();target=Path(sys.argv[2]).resolve()',
    "assert target.parent==root and target.name.startswith('ui-features-')",
    "for p in [target,*target.rglob('*')]:",
    " try: assert not getattr(p.lstat(),'st_file_attributes',0)&0x400",
    " except FileNotFoundError: pass",
    'def retry(fn,p,e):',
    ' os.chmod(p,stat.S_IWRITE)',
    ' for attempt in range(15):',
    '  try: fn(p);return',
    '  except PermissionError:',
    '   if attempt==14: raise',
    '   time.sleep(.2)',
    "shutil.rmtree(chr(92)*2+'?'+chr(92)+str(target),onerror=retry)"
   ].join('\n'),root,resolved],{windowsHide:true,stdio:'pipe'});
   else fs.rmSync(resolved,{recursive:true,force:true,maxRetries:6,retryDelay:300});
  }catch(error){console.warn('Temporary browser cleanup pending:',resolved,error.message)}
 }
}
main().catch(error=>{console.error(error);process.exitCode=1});
