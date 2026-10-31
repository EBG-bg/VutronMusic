// Isolated native contract for the real App.vue browser check; never contacts music services.
const account={userId:7,nickname:'Saved account'}
const listeners=new Map(), counts={}
const key=name=>'vutron-offline-ui:'+name
const read=(name,fallback)=>JSON.parse(localStorage.getItem(key(name))??JSON.stringify(fallback))
const write=(name,value)=>localStorage.setItem(key(name),JSON.stringify(value))
const local={id:51,name:'Phone song',artists:['Device'],localUri:'content://test/phone',localKey:'phone',localFolderId:'folder:test'}
const cached={id:61,name:'Cached song',artists:['Cache'],localUri:'file:///cache/61.audio',cached:true,source:'cache'}
const playlists=[{id:71,name:'Cached list',trackCount:1,cacheOwnerId:7,offlineSnapshot:true},{id:72,name:'Other cached list',trackCount:1,cacheOwnerId:7,offlineSnapshot:true}]
function status(){return {enabled:read('manual',false)||!read('connected',true),manual:read('manual',false),connected:read('connected',true)}}
function counted(name,result){return async(...args)=>{counts[name]=(counts[name]||0)+1;return typeof result==='function'?result(...args):structuredClone(result)}}
export const offlineTestBackend={counts,async setConnected(connected){write('connected',connected);await listeners.get('musicNetworkChanged')?.(status())}}
export const vutronPlugin={
  async addListener(name,callback){listeners.set(name,callback);return {async remove(){listeners.delete(name)}}},
  getLocalOnly:counted('getLocalOnly',status),
  setLocalOnly:counted('setLocalOnly',value=>{write('manual',value.manual??value.enabled);return status()}),
  getSession:counted('getSession',{loggedIn:true,profile:account}),
  createQrLogin:counted('createQrLogin',()=>{throw new Error('Unexpected QR login')}),
  loginWithCookie:counted('loginWithCookie',()=>{throw new Error('Unexpected login')}),
  restorePlayback:counted('restorePlayback',{restored:false}),
  getPlaybackState:counted('getPlaybackState',{playing:false,trackId:0,position:0,duration:0,title:'',artist:'',error:''}),
  getPlaybackQueue:counted('getPlaybackQueue',{tracks:[cached],index:0,upNext:[],mode:0}),
  getCachedPlaylists:counted('getCachedPlaylists',()=>({playlists:playlists.filter(row=>!read('hidden',[]).includes(row.id))})),
  getCachedPlaylistTracks:counted('getCachedPlaylistTracks',{tracks:[cached]}),
  deleteCachedPlaylist:counted('deleteCachedPlaylist',({playlistId,userId})=>{if(userId!==7)throw new Error('Wrong cache owner');write('hidden',[...new Set([...read('hidden',[]),playlistId])]);return {removed:true}}),
  getCachedTracks:counted('getCachedTracks',{tracks:[cached]}),
  getLocalMusicIndex:counted('getLocalMusicIndex',()=>({tracks:[local],indexed:true,updatedAt:Date.now()})),
  getLocalLibrary:counted('getLocalLibrary',{folders:[{id:'folder:test',name:'Phone folder',trackCount:1}],playlists:[{id:'bundle:test',name:'Imported songs',trackCount:1,isLocalBundle:true}]}),
  getLocalPlaylistTracks:counted('getLocalPlaylistTracks',{tracks:[{...local,id:52,name:'Imported song',localPlaylistId:'bundle:test'}]}),
  getCacheStatus:counted('getCacheStatus',()=>({capacityMb:512,usedBytes:1024,trackCount:1,enabled:true,artworkCapacityMb:96,libraryBytes:2048-read('hidden',[]).length*256})),
  getSourceSettings:counted('getSourceSettings',{autoMatch:false}),
  getLyricOverlaySettings:counted('getLyricOverlaySettings',{enabled:false,locked:true,translation:true,nextLine:true,fontSize:22,playedColor:0xff37cf88,unplayedColor:0xffd2d2d2,backgroundColor:0xff181920,shadowColor:0x99000000,shadowEnabled:true,shadowRadius:2,touchPassthroughPriority:true,permissionGranted:false,estimatedWordTiming:false}),
  getLikedTrackIds:counted('getLikedTrackIds',{userId:7,likedIds:[]}),
  getPlaylists:counted('getPlaylists',{playlists:[{id:71,name:'Online original',trackCount:1,creatorUserId:7}]}),
  getHomeData:counted('getHomeData',{banners:[],playlists:[],artists:[],albums:[],charts:[],dailyTracks:[]}),
  getLibraryTaskState:counted('getLibraryTaskState',{kind:'',running:false,done:0,total:0})
}
