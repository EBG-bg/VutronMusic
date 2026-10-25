import { registerPlugin, type Plugin } from '@capacitor/core'

export interface VutronProfile { userId: number; nickname: string; avatarUrl?: string; isVip?: boolean }
export interface VutronPlaylist { id: number; name: string; coverImgUrl?: string; coverLocalUri?: string; trackCount?: number; copywriter?: string; creatorUserId?: number; subscribed?: boolean; privacy?: number; description?: string; tags?: string[]; specialType?: number }
export interface VutronOnlineLibrary { userId: number; playlists?: VutronPlaylist[]; likedIds?: number[]; playlist?: VutronPlaylist; tracks?: VutronTrack[]; committed?: boolean; refreshFailed?: boolean; deletedPlaylistId?: number; createdPlaylistId?: number; message?: string }
export interface VutronNeteaseMutation { action: 'like' | 'create' | 'edit' | 'delete' | 'add' | 'remove'; playlistId?: number; trackId?: number; liked?: boolean; name?: string; description?: string; tags?: string[]; privacy?: number; refreshPlaylistId?: number }
export interface VutronLocalCollection { id: string; name: string; trackCount: number; uri?: string; isLocalBundle?: boolean; permissionGranted?: boolean }
export interface VutronLocalLibrary { folders: VutronLocalCollection[]; playlists: VutronLocalCollection[]; excludedTrackCount?: number }
export interface VutronLibraryTask { kind: string; running: boolean; cancelled?: boolean; done: number; total: number }
export interface VutronLocalImportResult { cancelled?: boolean; playlist?: VutronLocalCollection; needsMode?: boolean; importToken?: string; audioCount?: number; canMatchOnline?: boolean; matched?: number; total?: number; failed?: number; matchingCancelled?: boolean; matchingPaused?: boolean; matchingFailed?: boolean }
export interface VutronTrack {
  cached?: boolean
  missingLocal?: boolean
  onlineOnly?: boolean
  localEntryKey?: string
  localKey?: string
  localFolderId?: string
  localPlaylistId?: string
  localFileName?: string
  id: number
  name: string
  duration?: number
  picUrl?: string
  picLocalUri?: string
  artists?: string[]
  album?: string
  localUri?: string
  localPath?: string
  neteaseId?: number
  trackVolumeKey?: string
  playlistVolumeKey?: string
  playlistVolumeName?: string
}
export interface VutronBanner { id: number; picUrl?: string; picLocalUri?: string; type?: string; typeTitle?: string; url?: string; sourceId?: number }
export interface VutronArtist { id: number; name: string; picUrl?: string }
export interface VutronAlbum { id: number; name: string; picUrl?: string; artists?: VutronArtist[] }
export interface VutronHomeData {
  cached?: boolean
  banners: VutronBanner[]
  playlists: VutronPlaylist[]
  artists: VutronArtist[]
  albums: VutronAlbum[]
  charts: VutronPlaylist[]
  dailyTracks: VutronTrack[]
}
export type VutronSearchCategory = 'tracks' | 'albums' | 'artists' | 'playlists' | 'mvs'
export interface VutronSearchResult { items: unknown[]; count: number; page: number; nextPage: number }
export interface VutronLocalTrack extends VutronTrack {
  localUri: string
  source?: 'local' | 'cache'
  cached?: boolean
  bytes?: number
}
export interface VutronCacheStatus {
  capacityMb: number
  usedBytes: number
  trackCount: number
  libraryBytes?: number
  artworkCapacityMb?: number
  enabled?: boolean
  queued?: number
  downloading?: boolean
  downloadedBytes?: number
  downloadTotalBytes?: number
  error?: string
}
export interface VutronLyricPayload {
  lrc?: string
  tlyric?: string
  yrc?: string
  ytlrc?: string
}
export interface VutronLyricOverlaySettings {
  enabled: boolean
  locked: boolean
  translation: boolean
  nextLine: boolean
  wifiValidation: boolean
  estimatedWordTiming: boolean
  fontSize: number
  playedColor: number
  unplayedColor: number
  backgroundColor: number
  shadowColor: number
  shadowEnabled: boolean
  shadowRadius: number
  touchPassthroughPriority: boolean
  permissionGranted?: boolean
  currentLyricOffsetMs?: number
  temporaryHidden?: boolean
  temporaryHiddenRemainingMs?: number
}
export interface VutronPlaybackState {
  playing: boolean
  buffering?: boolean
  trackId: number
  title: string
  artist: string
  duration: number
  position: number
  error: string
  track?: VutronTrack
  quality?: string
  trackVolumePercent?: number
  playlistVolumePercent?: number
  queueIndex?: number
  queueToken?: string
  queueCount?: number
  mode?: number
  upNextCount?: number
}
export interface VutronQueue { tracks: VutronTrack[]; index: number; upNext: VutronTrack[]; mode: number }

export interface VutronAppUpdateState {
  currentVersion: string
  comparisonVersion?: string
  installAllowed?: boolean
  autoCheckPaused?: boolean
  currentVersionCode: number
  autoCheck: boolean
  status: 'idle' | 'checking' | 'available' | 'upToDate' | 'downloading' | 'ready' | 'error' | 'permissionRequired'
  latestVersion?: string
  releaseUrl: string
  notes?: string
  progress: number
  downloadedBytes: number
  totalBytes: number
  error: string
  checkedAt: number
  canInstall: boolean
}

export interface VutronMusicPlugin extends Plugin {
  getAppUpdateState(): Promise<VutronAppUpdateState>
  setAutoCheckUpdates(options: { enabled: boolean }): Promise<VutronAppUpdateState>
  checkAppUpdate(options: { manual: boolean }): Promise<VutronAppUpdateState>
  downloadAppUpdate(): Promise<VutronAppUpdateState>
  installAppUpdate(): Promise<VutronAppUpdateState>
  getLocalOnly(): Promise<{ enabled: boolean }>
  setLocalOnly(options: { enabled: boolean }): Promise<{ enabled: boolean }>
  getSession(): Promise<{ loggedIn: boolean; profile?: VutronProfile }>
  loginWithCookie(options: { cookie: string }): Promise<{ profile: VutronProfile }>
  createQrLogin?(): Promise<{ key: string; url: string; imageData: string }>
  checkQrLogin?(options: { key: string }): Promise<{ code: number; message?: string; loggedIn?: boolean; profile?: VutronProfile }>
  cancelQrLogin?(options: { key: string }): Promise<void>
  logout(): Promise<void>
  getPlaylists(options: { userId: number }): Promise<{ playlists: VutronPlaylist[]; cached?: boolean }>
  getPlaylistTracks(options: { playlistId: number; playlist?: VutronPlaylist }): Promise<{ tracks: VutronTrack[]; playlist?: VutronPlaylist; cached?: boolean }>
  getLikedTrackIds?(): Promise<{ userId: number; likedIds: number[] }>
  getOnlineLibrary?(options: { playlistId?: number }): Promise<VutronOnlineLibrary>
  manageNeteaseLibrary?(options: VutronNeteaseMutation): Promise<VutronOnlineLibrary>
  getCachedPlaylists?(): Promise<{ playlists: VutronPlaylist[] }>
  getCachedPlaylistTracks?(options: { playlistId: number }): Promise<{ tracks: VutronTrack[] }>
  getLocalLibrary?(): Promise<VutronLocalLibrary>
  importLocalPlaylist?(): Promise<VutronLocalImportResult>
  completeLocalPlaylistImport?(options: { importToken: string; matchOnline: boolean }): Promise<VutronLocalImportResult>
  discardLocalPlaylistImport?(options: { importToken: string }): Promise<void>
  getLocalPlaylistTracks?(options: { playlistId: string }): Promise<{ tracks: VutronTrack[] }>
  getLocalFolderTracks?(options: { folderId: string }): Promise<{ tracks: VutronTrack[] }>
  cachePlaylist?(options: { playlistId: number }): Promise<{ count: number }>
  cacheAllPlaylists?(): Promise<{ cachedPlaylists: number; cachedTracks: number; failedPlaylists: number; pendingCovers?: number }>
  getHomeData?(): Promise<VutronHomeData>
  search?(options: { category: VutronSearchCategory; keywords: string; page: number }): Promise<VutronSearchResult>
  getEntityTracks?(options: { category: 'albums' | 'artists'; id: number }): Promise<{ tracks: VutronTrack[] }>
  openMv?(options: { id: number }): Promise<void>
  play(options: { track: VutronTrack; queue?: VutronTrack[]; index?: number }): Promise<void>
  next(): Promise<void>
  previous(): Promise<void>
  getPlaybackQueue?(): Promise<VutronQueue>
  addToPlayNext?(options: { track: VutronTrack }): Promise<VutronQueue>
  appendToPlaybackQueue?(options: { track: VutronTrack }): Promise<VutronQueue>
  playQueueIndex?(options: { index: number; upNext?: boolean }): Promise<VutronQueue>
  removeQueueTrack?(options: { index: number; upNext?: boolean }): Promise<VutronQueue>
  clearPlayNext?(): Promise<VutronQueue>
  exportUserBackup?(options: { frontend: Record<string, string>; orders: Record<string, unknown> }): Promise<{ cancelled?: boolean; collectionCount?: number }>
  importUserBackup?(): Promise<{ cancelled?: boolean; frontend?: Record<string, string>; orders?: Record<string, unknown>; collectionCount?: number }>
  reorderLocalPlaybackQueue?(options: { collectionId: string; keys: string[] }): Promise<VutronQueue>
  pause(): Promise<void>
  resume(): Promise<void>
  stop(): Promise<void>
  restorePlayback(): Promise<{ restored: boolean }>
  getPlaybackState(): Promise<VutronPlaybackState>
  seek?(options: { position: number }): Promise<void>
  getLyrics?(options: { trackId: number }): Promise<VutronLyricPayload>
  pickMusicFolder?(): Promise<{ uri?: string; name?: string }>
  scanLocalMusic?(options?: { folderId?: string }): Promise<{ tracks: VutronLocalTrack[]; cancelled?: boolean }>
  getLocalMusicIndex?(): Promise<{ tracks: VutronLocalTrack[]; indexed: boolean; updatedAt: number }>
  setPlaybackMode?(options: { mode: number }): Promise<{ mode: number }>
  getCacheStatus?(): Promise<VutronCacheStatus>
  getArtwork?(options: { url: string }): Promise<{ uri: string }>
  setCacheCapacity?(options: { capacityMb: number }): Promise<VutronCacheStatus>
  setCacheEnabled?(options: { enabled: boolean }): Promise<VutronCacheStatus>
  setArtworkCacheCapacity?(options: { capacityMb: number }): Promise<VutronCacheStatus>
  cacheTrack?(options: { track: VutronTrack }): Promise<{ cached: boolean }>
  getCachedTracks?(): Promise<{ tracks: VutronLocalTrack[] }>
  clearCache?(): Promise<void>
  deleteCachedTrack?(options: { trackId: number }): Promise<{ removed: boolean; inUse?: boolean }>
  manageLocalLibrary?(options: { action: 'removeTrack' | 'deletePlaylist' | 'forgetFolder' | 'restoreRemoved'; track?: VutronTrack; collectionId?: string; stopCurrent?: boolean }): Promise<{ changed: boolean; requiresStop?: boolean }>
  cancelLibraryTask?(): Promise<VutronLibraryTask>
  getLibraryTaskState?(): Promise<VutronLibraryTask>
  getLyricOverlaySettings?(): Promise<VutronLyricOverlaySettings>
  updateLyricOverlaySettings?(options: { settings: Partial<VutronLyricOverlaySettings> }): Promise<VutronLyricOverlaySettings>
  setLyricOverlayEnabled?(options: { enabled: boolean }): Promise<VutronLyricOverlaySettings>
  showLyricOverlay?(): Promise<VutronLyricOverlaySettings>
  hideLyricOverlay?(): Promise<VutronLyricOverlaySettings>
  temporaryHideLyricOverlay?(options: { durationMs: number }): Promise<VutronLyricOverlaySettings>
  restoreLyricOverlay?(): Promise<VutronLyricOverlaySettings>
  setLyricOffset?(options: { trackId: number; offsetMs: number; track?: VutronTrack }): Promise<void>
  getSleepTimer?(): Promise<{ remainingMs: number }>
  setSleepTimer?(options: { durationMs: number }): Promise<{ remainingMs: number }>
  clearSleepTimer?(): Promise<{ remainingMs: number }>
  getSourceSettings(): Promise<{ autoMatch: boolean }>
  updateSourceSettings(options: { autoMatch: boolean }): Promise<{ autoMatch: boolean }>
  getVolumeSettings(options?: { track?: VutronTrack; playlistKey?: string }): Promise<{ trackPercent: number; playlistPercent: number }>
  setTrackVolume(options: { percent: number; track?: VutronTrack }): Promise<{ trackPercent: number; playlistPercent: number }>
  setPlaylistVolume(options: { playlistKey: string; percent: number }): Promise<{ trackPercent: number; playlistPercent: number }>
}

export const vutronPlugin = registerPlugin<VutronMusicPlugin>('VutronMusic')
