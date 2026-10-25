// Browser-only deterministic replacement, loaded solely by browser-online.cjs.
export const onlineTestBackend = {
  playlists:[{id:42,name:'Own',creatorUserId:7,tags:['流行'],description:'Before',privacy:0,trackCount:1},{id:99,name:'Other',creatorUserId:8,subscribed:true,privacy:0,trackCount:0},{id:5,name:'Liked',creatorUserId:7,specialType:5,trackCount:1}],
  tracks:[{id:2,name:'Song',artists:['Singer'],playlistVolumeKey:'netease:42'}],likedIds:[2],writes:[]
}
const backend=onlineTestBackend
const copy=value=>JSON.parse(JSON.stringify(value))
const snapshot=pid=>({userId:7,playlists:copy(backend.playlists),likedIds:copy(backend.likedIds),
  ...(pid&&backend.playlists.some(row=>row.id===pid)?{playlist:copy(backend.playlists.find(row=>row.id===pid)),tracks:pid===42?copy(backend.tracks):[]}: {})})
export const vutronPlugin={
  getLikedTrackIds:async()=>({userId:7,likedIds:copy(backend.likedIds)}),
  getCachedPlaylists:async()=>({playlists:[]}),
  getOnlineLibrary:async options=>snapshot(options.playlistId),
  getVolumeSettings:async()=>({trackPercent:100,playlistPercent:100}),
  async manageNeteaseLibrary(request){
    backend.writes.push(copy(request)); await new Promise(resolve=>setTimeout(resolve,180))
    if(request.action==='like')backend.likedIds=request.liked?[request.trackId]:[]
    if(request.action==='create')backend.playlists.push({id:50,name:request.name,creatorUserId:7,privacy:request.privacy,trackCount:0})
    if(request.action==='edit')Object.assign(backend.playlists.find(row=>row.id===request.playlistId),{name:request.name,description:request.description,tags:request.tags})
    if(request.action==='remove'){backend.tracks=backend.tracks.filter(row=>row.id!==request.trackId);backend.playlists[0].trackCount=backend.tracks.length}
    if(request.action==='delete')backend.playlists=backend.playlists.filter(row=>row.id!==request.playlistId)
    return {...snapshot(request.action==='create'?50:request.refreshPlaylistId),committed:true,deletedPlaylistId:request.action==='delete'?request.playlistId:0}
  }
}
