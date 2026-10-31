const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),ts=require('typescript');
test('lyric interpolation follows speed, seek, pause and the end of a CUE segment',()=>{
 const module={exports:{}};
 vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname,'../src/utils/lyricPlayback.ts'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,
 {module,exports:module.exports,Math});
 const position=module.exports.interpolatedPosition;
 assert.equal(position({position:1000,monotonicTime:2000,playing:true,rate:2},2500,10000),2000);
 assert.equal(position({position:2000,monotonicTime:2500,playing:true,rate:.5},3000,10000),2250);
 assert.equal(position({position:5000,monotonicTime:3000,playing:false,rate:.5},9000,10000),5000);
 assert.equal(position({position:9500,monotonicTime:3000,playing:true,rate:2},5000,10000),10000);
});
