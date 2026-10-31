const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),ts=require('typescript'),vue=require('vue');
const row=(id='9007199254740993')=>({id,userId:'7',nickname:'User',content:'<script>text</script>',deleted:false,time:1,liked:false,likedCount:2,replyCount:1,replies:[]});
const page=(comments=[],more=false)=>({comments,total:comments.length,hasMore:more,cursor:'9',nextPage:2,sort:99});
const deferred=()=>{let resolve,reject;let promise=new Promise((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject}};
function setup(overrides={}) {
 const calls=[],plugin={getComments:async q=>{calls.push(q);return page([row()])},getCommentReplies:async()=>page([row('4')]),manageComment:async()=>({committed:true}),...overrides};
 const module={exports:{}},filename=path.join(__dirname,'../src/composables/commentThread.ts');
 vm.runInNewContext(ts.transpileModule(fs.readFileSync(filename,'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,
 {module,exports:module.exports,require:n=>n==='vue'?vue:{vutronPlugin:plugin},Error,Set,Array,Number,Boolean,JSON,String,Object});
 const target=vue.ref({targetType:'track',targetId:'1'}),account=vue.ref('7'),enabled=vue.ref(true),scope=vue.effectScope();
 const api=scope.run(()=>module.exports.createCommentThread(target,account,enabled));
 return {api,scope,target,account,enabled,calls};
}
const tick=async()=>{for(let i=0;i<8;i++)await Promise.resolve()};
test('comment pages deduplicate IDs and stop repeated pages; UGC is unchanged text',async()=>{
 const s=setup({getComments:async()=>page([row(),row()],true)});await tick();
 assert.equal(s.api.comments.value.length,1);assert.equal(s.api.comments.value[0].id,'9007199254740993');
 assert.equal(s.api.comments.value[0].content,'<script>text</script>');
 await s.api.load();assert.equal(s.api.more.value,false);assert.equal(s.api.comments.value.length,1);s.scope.stop();
});
test('likes are optimistic, duplicate operations blocked, failure rolls back',async()=>{
 const wait=deferred(),s=setup({manageComment:()=>wait.promise});await tick();const c=s.api.comments.value[0];
 const pending=s.api.like(c);assert.equal(c.liked,true);assert.equal(c.likedCount,3);
 await s.api.like(c);wait.reject(new Error('network'));await pending;assert.equal(c.liked,false);assert.equal(c.likedCount,2);assert.equal(s.api.error.value,'network');s.scope.stop();
});
test('account, offline and disposal invalidate pending reads and writes',async()=>{
 let pending=deferred(),s=setup({getComments:()=>pending.promise});s.account.value='8';s.enabled.value=false;pending.resolve(page([row()]));await tick();
 assert.equal(s.api.comments.value.length,0);assert.equal(s.api.loading.value,false);s.scope.stop();
 pending=deferred();s=setup({getComments:()=>pending.promise});s.scope.stop();pending.resolve(page([row()]));await tick();assert.equal(s.api.comments.value.length,0);
});
test('reply pagination is independent; empty and overlong drafts never submit',async()=>{
 let writes=0;const s=setup({manageComment:async()=>{writes++;return {committed:true}}});await tick();const c=s.api.comments.value[0];
 await s.api.loadFloor(c);assert.equal(s.api.floors[c.id].rows[0].id,'4');assert.equal(s.api.comments.value.length,1);
 assert.equal(await s.api.submit('add','  '),false);assert.equal(await s.api.submit('add','a'.repeat(141)),false);
 assert.equal(await s.api.submit('delete','',{...c,userId:'8'}),false);assert.equal(writes,0);
 assert.equal(await s.api.submit('reply','hello',c),true);assert.equal(writes,1);s.scope.stop();
});
test('expired login prevents repeated requests until session changes',async()=>{
 let writes=0;const s=setup({manageComment:async()=>{writes++;throw {code:'301',message:'expired'}}});await tick();
 await s.api.like(s.api.comments.value[0]);await s.api.like(s.api.comments.value[0]);assert.equal(writes,1);assert.equal(s.api.expired.value,true);s.scope.stop();
});
test('recommendations with no comments fall back to latest once',async()=>{
 const calls=[],s=setup({getComments:async q=>{calls.push(q.sort);return page(q.sort===99?[]:[row()])}});await tick();
 assert.deepEqual(calls,[99,3]);assert.equal(s.api.sort.value,3);s.scope.stop();
});
