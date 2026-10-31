import { vutronPlugin as base, featureBackend } from './features-native.mock.js'
const key='vutron-slider-ui'
const read=()=>JSON.parse(localStorage.getItem(key)||'{}')
function save(patch){const state={...read(),...patch};localStorage.setItem(key,JSON.stringify(state));return state}
export const vutronPlugin={
  ...base,
  async getPlaybackState(){
    const state=read(),playback=await base.getPlaybackState()
    return {...playback,position:state.position??2000,trackVolumePercent:state.trackVolume??100,playlistVolumePercent:state.playlistVolume??100,track:{...featureBackend.track,playlistVolumeKey:'test:playlist',playlistVolumeName:'Test playlist'}}
  },
  async seek({position}){save({position})},
  async getVolumeSettings(){const s=read();return {trackPercent:s.trackVolume??100,playlistPercent:s.playlistVolume??100}},
  async setTrackVolume({percent}){save({trackVolume:percent});return this.getVolumeSettings()},
  async setPlaylistVolume({percent}){save({playlistVolume:percent});return this.getVolumeSettings()},
  async getCacheStatus(){const s=read();return {...await base.getCacheStatus(),capacityMb:s.capacityMb??512,artworkCapacityMb:s.artworkCapacityMb??96}},
  async setCacheCapacity({capacityMb}){save({capacityMb});return this.getCacheStatus()},
  async setArtworkCacheCapacity({capacityMb}){save({artworkCapacityMb:capacityMb});return this.getCacheStatus()},
  async getLyricOverlaySettings(){const s=read();return {...await base.getLyricOverlaySettings(),...s.overlay,currentLyricOffsetMs:s.offset??0}},
  async updateLyricOverlaySettings({settings}){save({overlay:{...read().overlay,...settings}});return this.getLyricOverlaySettings()},
  async setLyricOffset({offsetMs}){save({offset:offsetMs})}
}
