// Invoked by browser-features.cjs with VUTRON_TEST_SCENARIO=sliders.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
module.exports=async({page,evaluate,wait,delay,root})=>{
  await page.send('Page.bringToFront');
  await page.send('Emulation.setTouchEmulationEnabled',{enabled:true,maxTouchPoints:1});
  const selectorFor=label=>'input[type=range][aria-label='+JSON.stringify(label)+']';
  const mouse=async(type,x,y)=>page.send('Input.dispatchMouseEvent',{type,x,y,button:'left',buttons:type==='mouseReleased'?0:1,clickCount:1,pointerType:'mouse'});
  const gesture=async(kind,from,to)=>{
    if(kind==='touch'){
      await page.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:from.x,y:from.y,radiusX:1,radiusY:1}]});
      for(let i=1;i<=6;i++){await page.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:from.x+(to.x-from.x)*i/6,y:from.y+(to.y-from.y)*i/6,radiusX:1,radiusY:1}]});await delay(18)}
      await page.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
    }else{
      await mouse('mousePressed',from.x,from.y);
      for(let i=1;i<=4;i++)await mouse('mouseMoved',from.x+(to.x-from.x)*i/4,from.y+(to.y-from.y)*i/4);
      await mouse('mouseReleased',to.x,to.y);
    }
    await delay(80);
  };
  const geometry=async selector=>evaluate(`(()=>{
    const input=document.querySelector(${JSON.stringify(selector)}),root=input.closest('.thumb-range');root.scrollIntoView({block:'center'});
    const r=root.getBoundingClientRect(),p=root.querySelector('.thumb-range-path').getBoundingClientRect(),t=root.querySelector('.thumb-range-thumb').getBoundingClientRect();
    return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,x:t.left+t.width/2,y:t.top+t.height/2,start:p.left,end:p.right,value:input.value,min:input.min,max:input.max,thumbWidth:t.width,hit:document.elementFromPoint(t.left+t.width/2,t.top+t.height/2)?.className};
  })()`);
  const value=selector=>evaluate('document.querySelector('+JSON.stringify(selector)+').value');
  const assertEndpoints=async selector=>{
    const result=await evaluate(`(()=>{
      const input=document.querySelector(${JSON.stringify(selector)}),root=input.closest('.thumb-range'),t=root.querySelector('.thumb-range-thumb').getBoundingClientRect(),r=root.getBoundingClientRect(),p=root.closest('.settings-collapse-content,.track-action-menu,.progress-control').getBoundingClientRect();
      return {inside:t.left>=r.left+2&&t.right<=r.right-2&&t.top>=r.top+2&&t.bottom<=r.bottom-2,parent:r.left>=p.left&&r.right<=p.right,margin:getComputedStyle(root).margin};
    })()`);
    assert.ok(result.inside,selector+' thumb extends outside its reserved space: '+JSON.stringify(result));
    assert.ok(result.parent,selector+' extends past its content boundary');
  };
  const checked=[];
  const checkRange=async selector=>{
    for(const kind of ['mouse','touch']){
      let g=await geometry(selector);await delay(60);g=await geometry(selector);
      const blankX=g.x<(g.start+g.end)/2?g.end:g.start;
      await gesture(kind,{x:blankX,y:g.y},{x:blankX,y:g.y});
      assert.equal(await value(selector),g.value,selector+': blank track tap changed value');
      await gesture(kind,{x:blankX,y:g.y},{x:(g.start+g.end)/2,y:g.y});
      assert.equal(await value(selector),g.value,selector+': blank track drag changed value');
      for(const edge of ['min','max']){
        g=await geometry(selector);
        await gesture(kind,{x:g.x,y:g.y},{x:edge==='min'?g.left+1:g.right-1,y:g.y});
        assert.equal(await value(selector),g[edge],selector+': '+kind+' thumb drag failed at '+edge);
        await assertEndpoints(selector);
      }
    }
    checked.push(selector);
  };
  await evaluate("window.featureHarness.store.navigateTo('settings')");await delay(300);
  await evaluate("document.querySelector('[data-settings-section=cache] > summary').click()");await delay(200);
  for(const label of ['音频缓存容量','封面缓存容量'])await checkRange(selectorFor(label));
  assert.equal(await evaluate("JSON.parse(localStorage.getItem('vutron-slider-ui')).artworkCapacityMb"),1024);
  assert.equal(await evaluate("window.featureHarness.store.artworkCacheCapacityMb"),1024);
  // Once grabbed, leaving the visible thumb must not interrupt live input.
  let held=await geometry(selectorFor('音频缓存容量'));
  await page.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:held.x,y:held.y}]});
  await page.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:held.x-30,y:held.y+30}]});
  assert.ok(Number(await value(selectorFor('音频缓存容量')))<Number(held.value),'The captured thumb must follow a finger outside its original bounds');
  const liveCapacity=await value(selectorFor('音频缓存容量'));
  await page.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await delay(100);
  assert.equal(await evaluate("JSON.parse(localStorage.getItem('vutron-slider-ui')).capacityMb"),Number(liveCapacity));
  let g=await geometry(selectorFor('音频缓存容量')),oldScroll=await evaluate('scrollY');
  await gesture('touch',{x:g.start,y:g.y},{x:g.start,y:g.y-110});
  assert.ok(await evaluate('scrollY')>oldScroll,'A swipe on the blank rail should scroll the page');
  await evaluate("document.querySelector('[data-settings-section=lyrics] > summary').click()");await delay(200);
  for(const label of ['歌词大小','阴影模糊'])await checkRange(selectorFor(label));
  await evaluate("document.querySelector('[data-settings-section=lyric-colors] > summary').click()");await delay(200);
  for(const label of ['歌词色相','歌词颜色不透明度'])await checkRange(selectorFor(label));
  const key=async(name,code)=>{
    await page.send('Input.dispatchKeyEvent',{type:'keyDown',key:name,code:name,windowsVirtualKeyCode:code});
    await page.send('Input.dispatchKeyEvent',{type:'keyUp',key:name,code:name,windowsVirtualKeyCode:code});await delay(50);
  };
  const alpha=selectorFor('歌词颜色不透明度');
  await evaluate('document.querySelector('+JSON.stringify(alpha)+').focus()');await key('ArrowLeft',37);
  assert.equal(await value(alpha),'254','Native keyboard step must remain available');
  const offsetGeometry=async()=>evaluate(`(()=>{const e=document.querySelector('.offset-slider');e.scrollIntoView({block:'center'});const r=e.getBoundingClientRect(),t=e.querySelector('.offset-thumb').getBoundingClientRect();return {left:r.left,right:r.right,x:t.left+t.width/2,y:t.top+t.height/2,value:e.getAttribute('aria-valuenow')}})()`);
  for(const kind of ['mouse','touch']){
    g=await offsetGeometry();await delay(60);g=await offsetGeometry();
    await gesture(kind,{x:g.left+4,y:g.y},{x:g.right-4,y:g.y});
    assert.equal((await offsetGeometry()).value,g.value,'Offset rail should not start a drag');
    const from={x:g.x,y:g.y};
    if(kind==='mouse'){
      await mouse('mousePressed',from.x,from.y);await mouse('mouseMoved',from.x+12,from.y+16);
    }else{
      await page.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[from]});
      await page.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:from.x+12,y:from.y+16}]});
    }
    const live=Number((await offsetGeometry()).value);
    assert.ok(live>Number(g.value)&&live-Number(g.value)<500,'Offset must respond live in small increments while held');
    if(kind==='mouse')await mouse('mouseReleased',from.x+12,from.y+16);
    else await page.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
    await delay(100);
  }
  await evaluate("window.featureHarness.store.navigateTo('player')");await delay(250);
  await evaluate("document.querySelector('[data-settings-section=playback-volume] > summary').click()");await delay(200);
  for(const selector of ['.progress-control input[type=range]','#track-volume','#playlist-volume'])await checkRange(selector);
  await page.send('Emulation.setDeviceMetricsOverride',{width:320,height:844,deviceScaleFactor:1,mobile:true});
  for(const selector of ['.progress-control input[type=range]','#track-volume','#playlist-volume']){await geometry(selector);await assertEndpoints(selector)}
  assert.equal(await evaluate("Array.from(document.querySelectorAll('.volume-control')).every(e=>e.querySelector('.thumb-range-thumb').getBoundingClientRect().right<e.querySelector('.percent-input').getBoundingClientRect().left)"),true,'Max-volume thumbs must not overlap the percentage fields');
  // The range itself must remain inert when its native input is disabled.
  await evaluate('window.featureHarness.store.playback.duration=0');await delay(30);
  g=await geometry('.progress-control input[type=range]');
  await gesture('touch',{x:g.x,y:g.y},{x:g.left+1,y:g.y});
  assert.equal(await value('.progress-control input[type=range]'),g.value);
  await evaluate('window.featureHarness.store.playback.duration=180000');
  await evaluate("document.querySelector('.player-title-row [data-track-menu-button]').click()");await wait("document.querySelector('.track-action-menu')");
  await evaluate("document.querySelector('[data-settings-section=track-menu-volume] > summary').click()");await delay(200);
  await checkRange(selectorFor('菜单歌曲音量'));
  await key('Escape',27);await wait("!document.querySelector('.track-action-menu')");
  await evaluate("window.featureHarness.store.navigateTo('settings')");await delay(200);
  await evaluate("document.querySelector('[data-settings-section=lyric-colors] > summary').click()");await delay(150);
  for(const width of [320,360,390]){
    await page.send('Emulation.setDeviceMetricsOverride',{width,height:844,deviceScaleFactor:1,mobile:true});
    for(const selector of [selectorFor('音频缓存容量'),selectorFor('封面缓存容量'),selectorFor('歌词大小'),selectorFor('阴影模糊'),selectorFor('歌词色相'),selectorFor('歌词颜色不透明度')]){
      await geometry(selector);await assertEndpoints(selector);
    }
    assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,'Horizontal overflow at '+width);
    assert.equal(await evaluate("Array.from(document.querySelectorAll('input[type=range]')).every(e=>e.closest('.thumb-range'))"),true,'Every numeric rail must use the thumb-only control');
    for(const seconds of [-30,30]){
      await evaluate(`(()=>{const e=document.querySelector('[aria-label="歌词延迟秒数"]');e.value=${seconds};e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('blur'))})()`);
      await offsetGeometry();
      assert.equal(await evaluate("(()=>{const r=document.querySelector('.offset-slider').getBoundingClientRect(),t=document.querySelector('.offset-thumb').getBoundingClientRect();return t.left>=r.left+2&&t.right<=r.right-2})()"),true,'Offset endpoint extends past the rail');
    }
  }
  await geometry(selectorFor('封面缓存容量'));
  const picture=await page.send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
  fs.writeFileSync(path.join(root,'mobile-slider-endpoints.png'),Buffer.from(picture.data,'base64'));
  await evaluate('window.featureReady=false');await page.send('Page.reload');await wait('window.featureReady===true');
  await evaluate("window.featureHarness.store.navigateTo('settings')");await delay(200);
  assert.equal(await value(selectorFor('封面缓存容量')),'1024','Saved cache size should be restored');
  assert.equal(await value(selectorFor('歌词大小')),'34','Saved lyric font size should be restored');
  console.log('PASS: '+checked.length+' real sliders; mouse/touch thumb-only starts, endpoints, track scrolling, live offset, keyboard, disabled, persistence and 320/360/390px layout');
};
