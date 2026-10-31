import { vutronPlugin as base, offlineTestBackend } from './offline-native.mock.js'
const track={id:61,name:'Feature track',artists:['Artist'],duration:180000}
const getTuning=()=>JSON.parse(localStorage.getItem('feature-tuning')||'{"speed":1,"pitch":1,"prefetchWifi":false,"actualSpeed":1,"actualPitch":1,"warning":""}')
let rows=[{id:'9007199254740993',userId:'7',nickname:'Listener',content:'<img src=x onerror=alert(1)> Plain text',time:1,deleted:false,liked:false,likedCount:3,replyCount:2,replies:[]}]
const counts={}
let commentCount=0
export const featureBackend={counts,track,setConnected:offlineTestBackend.setConnected,setCommentCount(count){commentCount=count}}
const count=name=>counts[name]=(counts[name]||0)+1
export const vutronPlugin={
  ...base,
  async getPlaybackState(){const s=getTuning();return {trackId:61,title:track.name,artist:'Artist',duration:180000,position:2000,playing:false,error:'',track,rate:s.speed,pitch:s.pitch}},
  async getPlaybackTuning(){return getTuning()},
  async updatePlaybackTuning(value){count('tuning');const s={...getTuning(),...value};s.actualSpeed=s.speed;s.actualPitch=s.pitch;localStorage.setItem('feature-tuning',JSON.stringify(s));return s},
  async getLyrics(){return {lrc:'[00:01.00]Words'}},
  async getComments(query){
    count('comments')
    if(commentCount){
      const start=(query.page-1)*30,end=Math.min(start+30,commentCount)
      return {comments:Array.from({length:Math.max(0,end-start)},(_,i)=>({...structuredClone(rows[0]),id:String(9007199254742000n+BigInt(start+i)),content:'Long comment '+(start+i+1)})),total:commentCount,hasMore:end<commentCount,cursor:String(end),nextPage:query.page+1,sort:query.sort}
    }
    return {comments:query.page===1?structuredClone(rows):[],total:rows.length,hasMore:false,cursor:'9',nextPage:2,sort:query.sort}
  },
  async getCommentReplies(){count('floor');return {comments:[{...structuredClone(rows[0]),id:'9007199254740994',userId:'8',content:'Reply text',replies:[{id:'11',nickname:'',content:'该评论已删除'}]}],total:1,hasMore:false,cursor:'12',nextPage:2,sort:3}},
  async manageComment(value){
    count(value.action)
    if(value.action==='like'){const row=rows.find(row=>row.id===value.commentId);row.liked=value.liked;row.likedCount+=value.liked?1:-1}
    else if(value.action==='delete')rows=rows.filter(row=>row.id!==value.commentId)
    else rows.unshift({id:String(BigInt('9007199254740996')+BigInt(rows.length)),userId:'7',nickname:'Me',content:value.content,time:1,deleted:false,liked:false,likedCount:0,replyCount:0,replies:[]})
    return {committed:true}
  }
}
