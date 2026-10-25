import { computed, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { createLiveSettingWriter } from '../platform/liveSettingWrites'
import { createAuthSession } from './domains/authSession'
import { createLocalLibrary } from './domains/localLibrary'
import { createNeteaseLibrary } from './domains/neteaseLibrary'
import { libraryMutationBusy } from '../platform/libraryActivity'
import { selectLyricFamily, type LyricFamily } from '@vutronmusic/core/lyrics'
import {
  vutronPlugin,
  type VutronCacheStatus,
  type VutronHomeData,
  type VutronLocalTrack,
  type VutronLocalCollection,
  type VutronLyricOverlaySettings,
  type VutronPlaybackState,
  type VutronPlaylist,
  type VutronOnlineLibrary,
  type VutronSearchCategory,
  type VutronSearchResult,
  type VutronTrack,
  type VutronQueue
} from '../platform/vutronPlugin'

const emptyPlaybackState: VutronPlaybackState = {
  playing: false, trackId: 0, title: '', artist: '', duration: 0, position: 0, error: ''
}

type ShellTab = 'home' | 'library' | 'search' | 'entity' | 'localCollection' | 'player' | 'settings' | 'about'
type ShellEntity = { category: 'albums' | 'artists'; id: number; name: string; picUrl?: string }
interface NavigationEntry {
  tab: ShellTab
  playlist?: VutronPlaylist
  entity?: ShellEntity
  localCollection?: VutronLocalCollection
  tracks: VutronTrack[]
  scrollY: number
}

function errorMessage(error: unknown, fallback: string): string {
  if (typeof error === 'string' && error.trim()) return error
  if (error && typeof error === 'object' && 'message' in error) {
    const message = (error as { message?: unknown }).message
    if (typeof message === 'string' && message.trim()) return message
  }
  return fallback
}

function colorInputValue(value: number): string {
  return `#${((value >>> 0) & 0xffffff).toString(16).padStart(6, '0')}`
}

export const useAndroidShellStore = defineStore('androidShell', () => {
  const loading = ref(false)
  const error = ref('')
  const authSession = createAuthSession({
    loading, error, formatError: errorMessage, restoreLocalState, resetToLocalLibrary, startPlaybackPolling,
    loadOnlineState: async () => { await Promise.all([loadPlaylists(), loadHomeData(), onlineLibrary.loadLikedTracks()]) },
    resetToOnlineHome: () => { navigationHistory.value = []; activeTab.value = 'home' },
    beforeLogout: async () => {
      await flushLiveSettings()
      ++trackListRequestId; ++searchRequestId; ++lyricsRequestId
      ++cacheSettingsRevision; playbackPlaylistCache.clear()
    },
    clearOnlineState: () => { playlistReadVersion++; playlistsLoading.value = false; playlists.value = [] }
  })
  const {
    profile, localOnly, loginRequested, localModeLoading, loggedIn, hasAccess, loginVisible, onlineAvailable,
    qrLoginKey, qrLoginImage, qrLoginStatus, qrLoginLoading,
    restore, enterLocalOnly, requestLogin, login, createQrLogin, checkQrLogin, cancelQrLogin, logout
  } = authSession
  const playlists = ref<VutronPlaylist[]>([])
  const playlistsLoading = ref(false)
  let playlistReadVersion = 0
  const tracks = ref<VutronTrack[]>([])
  const selectedPlaylist = ref<VutronPlaylist>()
  const selectedLocalCollection = ref<VutronLocalCollection>()
  const { localLibrary, libraryLoading, localScanLoading, localTracks, folderName,
    pickMusicFolder, loadLocalMusic, loadLocalLibrary, importLocalPlaylist
  } = createLocalLibrary({ error, onlineAvailable, formatError: errorMessage, openCollection: openLocalCollection, notice: message => { queueNotice.value = message } })
  const cachedTracks = ref<VutronLocalTrack[]>([])
  const cacheStatus = ref<VutronCacheStatus>({ capacityMb: 2048, usedBytes: 0, trackCount: 0, enabled: true })
  const cacheCapacityMb = ref(2048)
  const artworkCacheCapacityMb = ref(128)
  const overlaySettings = ref<VutronLyricOverlaySettings>({
    enabled: false, locked: false, translation: true, nextLine: true, wifiValidation: true, estimatedWordTiming: false,
    fontSize: 22, playedColor: 0xff37cf88, unplayedColor: 0xffd2d2d2,
    backgroundColor: 0xff181920, shadowColor: 0x99000000, shadowEnabled: true,
    shadowRadius: 2, touchPassthroughPriority: true, permissionGranted: false
  })
  const overlayEnabled = computed(() => overlaySettings.value.enabled)
  const overlayPlayedColor = computed(() => colorInputValue(overlaySettings.value.playedColor))
  const overlayUnplayedColor = computed(() => colorInputValue(overlaySettings.value.unplayedColor))
  const overlayShadowColor = computed(() => colorInputValue(overlaySettings.value.shadowColor))
  const overlayBackgroundOpacity = computed(() => Math.round(((overlaySettings.value.backgroundColor >>> 24) & 0xff) * 100 / 255))
  const lyrics = ref<LyricFamily>({ original: [], translation: [], pairedTranslations: [], source: 'empty' })
  const lyricsLoading = ref(false)
  const activeTab = ref<ShellTab>('home')
  const navigationHistory = shallowRef<NavigationEntry[]>([])
  const navigationDirection = ref<'forward' | 'back'>('forward')
  const navigationScrollY = ref(0)
  const rootTab = computed<ShellTab>(() => localOnly.value ? 'library' : 'home')
  const canNavigateBack = computed(() => loginRequested.value && localOnly.value || navigationHistory.value.length > 0 || activeTab.value !== rootTab.value)
  const playback = ref<VutronPlaybackState>({ ...emptyPlaybackState })
  const playbackQueue = ref<VutronQueue>({ tracks: [], index: -1, upNext: [], mode: 0 })
  const queueOpen = ref(false)
  let queueReadKey = ''
  const queueNotice = ref('')
  const selectedEntity = ref<ShellEntity>()
  const cachedPlaylists = ref<VutronPlaylist[]>([])
  const homeData = ref<VutronHomeData>({ banners: [], playlists: [], artists: [], albums: [], charts: [], dailyTracks: [] })
  const searchCategory = ref<VutronSearchCategory>('tracks')
  const searchKeywords = ref('')
  const searchResults = ref<unknown[]>([])
  const searchCount = ref(0)
  const searchNextPage = ref(0)
  const searchLoading = ref(false)
  let searchRequestId = 0
  const homeLoading = ref(false)
  const sleepTimerRemainingMs = ref(0)
  const sleepTimerMinutes = ref(30)
  const cacheAllPlaylistsLoading = ref(false)
  const cacheAllPlaylistsResult = ref('')
  const autoMatchSources = ref(true)
  let playbackTimer: ReturnType<typeof setInterval> | undefined
  let playbackRequestPending = false
  let cacheStatusPending = false
  let cacheSettingsRevision = 0
  let cachePollAt = 0
  let lyricTrackId = 0
  let trackListRequestId = 0
  let lyricsRequestId = 0
  let overlayRevision = 0
  let overlayReadRequest = 0
  let overlayTrackKey = ''
  type OverlayValue = boolean | number
  const overlayDrafts = new Map<keyof VutronLyricOverlaySettings, { value: OverlayValue; revision: number }>()
  const offsetDrafts = new Map<string, { value: number; revision: number }>()
  const trackVolumeDrafts = new Map<string, { value: number; revision: number }>()
  const playlistVolumeDrafts = new Map<string, { value: number; revision: number }>()
  let offsetRevision = 0
  let volumeRevision = 0
  type NumberWriter = ReturnType<typeof createLiveSettingWriter<number>>
  const offsetWriters = new Map<string, NumberWriter>()
  const trackVolumeWriters = new Map<string, NumberWriter>()
  const playlistVolumeWriters = new Map<string, NumberWriter>()
  const overlayWriter = createLiveSettingWriter<Partial<VutronLyricOverlaySettings>>({
    merge: (previous, next) => ({ ...previous, ...next }),
    write: async patch => {
      const savedRevisions = new Map([...overlayDrafts].map(([key, draft]) => [key, draft.revision]))
      const next = vutronPlugin.updateLyricOverlaySettings
        ? await vutronPlugin.updateLyricOverlaySettings({ settings: patch })
        : patch.enabled !== undefined && vutronPlugin.setLyricOverlayEnabled
          ? await vutronPlugin.setLyricOverlayEnabled({ enabled: patch.enabled }) : undefined
      for (const key of Object.keys(patch) as (keyof VutronLyricOverlaySettings)[]) {
        const draft = overlayDrafts.get(key)
        if (draft?.revision === savedRevisions.get(key) && draft?.value === patch[key]) {
          if (key === 'enabled' && next?.enabled !== undefined) overlaySettings.value.enabled = next.enabled
          overlayDrafts.delete(key)
        }
      }
      // Full native snapshots can contain unrelated older slider values.
      if (next?.permissionGranted !== undefined) overlaySettings.value.permissionGranted = next.permissionGranted
      if (next?.temporaryHidden !== undefined) overlaySettings.value.temporaryHidden = next.temporaryHidden
      if (next?.temporaryHiddenRemainingMs !== undefined) overlaySettings.value.temporaryHiddenRemainingMs = next.temporaryHiddenRemainingMs
    },
    onError: cause => { error.value = errorMessage(cause, '桌面歌词设置保存失败') }
  })
  const playbackPlaylistCache = new Set<number>()

  function playbackTrackKey(state = playback.value): string {
    const track = state.track
    return [state.trackId, track?.localUri ?? '', track?.localPath ?? '', track?.neteaseId ?? ''].join('|')
  }

  function queueStateKey(state = playback.value): string {
    return JSON.stringify([state.queueToken, state.queueIndex, state.queueCount, state.upNextCount, state.mode, playbackTrackKey(state)])
  }

  function rememberPage() {
    navigationHistory.value = [...navigationHistory.value, {
      tab: activeTab.value, playlist: selectedPlaylist.value, entity: selectedEntity.value,
      tracks: tracks.value, localCollection: selectedLocalCollection.value, scrollY: window.scrollY
    }].slice(-40)
    navigationDirection.value = 'forward'
    navigationScrollY.value = 0
  }

  function navigateTo(tab: ShellTab) {
    if (!onlineAvailable.value && ['home', 'search', 'entity'].includes(tab)) return
    if (tab === activeTab.value && !(tab === 'library' && selectedPlaylist.value)) return
    rememberPage()
    ++trackListRequestId
    loading.value = false
    activeTab.value = tab
    if (tab === 'library') { selectedPlaylist.value = undefined; selectedEntity.value = undefined; selectedLocalCollection.value = undefined; tracks.value = [] }
    if (tab === rootTab.value) navigationHistory.value = []
  }

  function navigateBack(): boolean {
    const previous = navigationHistory.value.at(-1)
    if (!previous && activeTab.value === rootTab.value) return false
    ++trackListRequestId
    loading.value = false
    navigationDirection.value = 'back'
    navigationScrollY.value = previous?.scrollY ?? 0
    navigationHistory.value = navigationHistory.value.slice(0, -1)
    selectedPlaylist.value = previous?.playlist
    selectedEntity.value = previous?.entity
    selectedLocalCollection.value = previous?.localCollection
    tracks.value = previous?.tracks ?? []
    activeTab.value = previous?.tab ?? rootTab.value
    return true
  }

  async function restoreLocalState() {
    try { await vutronPlugin.restorePlayback() } catch { /* No saved playback cursor. */ }
    await Promise.all([loadOfflinePlaylists(), loadPlaybackQueue(), refreshPlaybackState(), loadCacheStatus(), loadLocalMusic(), loadOverlaySettings(), loadSourceSettings()])
  }

  function resetToLocalLibrary() {
    ++trackListRequestId; ++searchRequestId
    navigationHistory.value = []
    selectedPlaylist.value = undefined
    selectedEntity.value = undefined
    selectedLocalCollection.value = undefined
    activeTab.value = 'library'
    tracks.value = []
    loading.value = false
    searchLoading.value = false
  }

  const onlineLibrary = createNeteaseLibrary({ profile, onlineAvailable, playlists, tracks, selectedPlaylist, error,
    invalidateReads: () => { playlistReadVersion++; playlistsLoading.value = false },
    applySnapshot: applyOnlineSnapshot, notice: message => { queueNotice.value = message }, formatError: errorMessage })

  function applyOnlineSnapshot(snapshot: VutronOnlineLibrary) {
    playlistReadVersion++; playlistsLoading.value = false
    playlists.value = snapshot.playlists!
    const deleted = snapshot.deletedPlaylistId || 0
    navigationHistory.value = navigationHistory.value.filter(entry => !deleted || entry.playlist?.id !== deleted).map(entry => {
      return snapshot.playlist && entry.playlist?.id === snapshot.playlist.id
        ? { ...entry, playlist: snapshot.playlist, tracks: snapshot.tracks ?? entry.tracks } : entry
    })
    if (deleted && selectedPlaylist.value?.id === deleted) {
      selectedPlaylist.value = undefined; tracks.value = []; trackListRequestId++; loading.value = false
    } else if (snapshot.playlist && selectedPlaylist.value?.id === snapshot.playlist.id) {
      selectedPlaylist.value = snapshot.playlist
      if (snapshot.tracks) { tracks.value = snapshot.tracks; trackListRequestId++; loading.value = false }
    }
    void loadOfflinePlaylists()
  }

  async function loadPlaylists() {
    if (!onlineAvailable.value || !profile.value || playlistsLoading.value) return
    const request = ++playlistReadVersion, uid = profile.value.userId
    playlistsLoading.value = true
    try {
      const result = await vutronPlugin.getPlaylists({ userId: uid })
      if (request === playlistReadVersion && onlineAvailable.value && profile.value?.userId === uid) {
        playlists.value = result.playlists
        return result.cached ? 'cached' : 'updated'
      }
    }
    catch (cause) { if (request === playlistReadVersion && onlineAvailable.value && profile.value?.userId === uid) error.value = errorMessage(cause, '歌单读取失败') }
    finally { if (request === playlistReadVersion) playlistsLoading.value = false }
  }

  async function loadOfflinePlaylists() {
    if (!vutronPlugin.getCachedPlaylists) return
    try { cachedPlaylists.value = (await vutronPlugin.getCachedPlaylists()).playlists ?? [] }
    catch { /* Offline snapshots are optional on older builds. */ }
  }

  async function loadHomeData() {
    if (!onlineAvailable.value || !vutronPlugin.getHomeData || homeLoading.value) return
    homeLoading.value = true
    try {
      const result = await vutronPlugin.getHomeData()
      if (onlineAvailable.value) {
        const hasContent = Object.values(homeData.value).some(value => Array.isArray(value) && value.length > 0)
        if (!result.cached || !hasContent) homeData.value = result
        return result.cached ? 'cached' : 'updated'
      }
    }
    catch (cause) {
      error.value = errorMessage(cause, '首页内容暂时无法加载')
      if (!homeData.value.playlists.length) homeData.value.playlists = cachedPlaylists.value.slice(0, 10)
    }
    finally { homeLoading.value = false }
  }

  async function openPlaylist(playlist: VutronPlaylist) {
    if (!onlineAvailable.value) { await openCachedPlaylist(playlist); return }
    rememberPage()
    const requestId = ++trackListRequestId
    const accountId = profile.value?.userId
    selectedPlaylist.value = playlist; selectedEntity.value = undefined; tracks.value = []
    activeTab.value = 'library'; loading.value = true; error.value = ''
    try {
      const result = await vutronPlugin.getPlaylistTracks({ playlistId: playlist.id, playlist })
      playbackPlaylistCache.add(playlist.id)
      if (requestId === trackListRequestId && onlineAvailable.value && profile.value?.userId === accountId) {
        tracks.value = result.tracks
        if (result.playlist) selectedPlaylist.value = result.playlist
      }
      void loadOfflinePlaylists()
    } catch (cause) { if (requestId === trackListRequestId) error.value = errorMessage(cause, '歌曲列表读取失败') }
    finally { if (requestId === trackListRequestId) loading.value = false }
  }

  async function reloadManagedLibrary(removed: { track?: VutronTrack; collectionId?: string } = {}) {
    const matches = (track: VutronTrack) => removed.collectionId
      ? track.localFolderId === removed.collectionId || track.localPlaylistId === removed.collectionId || track.playlistVolumeKey === removed.collectionId
      : removed.track && (track.localKey && removed.track.localKey ? track.localKey === removed.track.localKey : track.localUri && track.localUri === removed.track.localUri)
    navigationHistory.value = navigationHistory.value.filter(entry => !removed.collectionId || entry.localCollection?.id !== removed.collectionId)
      .map(entry => ({ ...entry, tracks: entry.tracks.filter(track => !matches(track)) }))
    await loadLocalMusic(false, false)
    const collection = selectedLocalCollection.value
    if (collection) {
      const exists = [...localLibrary.value.folders, ...localLibrary.value.playlists].find(item => item.id === collection.id)
      if (!exists) { selectedLocalCollection.value = undefined; tracks.value = []; activeTab.value = 'library'; ++trackListRequestId }
      else {
        const requestId = ++trackListRequestId
        const result = exists.isLocalBundle ? await vutronPlugin.getLocalPlaylistTracks?.({ playlistId: exists.id }) : await vutronPlugin.getLocalFolderTracks?.({ folderId: exists.id })
        if (requestId === trackListRequestId) { selectedLocalCollection.value = exists; tracks.value = result?.tracks ?? [] }
      }
    } else tracks.value = tracks.value.filter(track => !matches(track))
    await loadPlaybackQueue(); await refreshPlaybackState(); await loadCacheStatus(false)
  }

  async function cachePlaylist(playlist: VutronPlaylist) {
    if (!onlineAvailable.value || !vutronPlugin.cachePlaylist) return
    loading.value = true
    try {
      await vutronPlugin.cachePlaylist({ playlistId: playlist.id })
      const cached = await vutronPlugin.getCachedPlaylistTracks?.({ playlistId: playlist.id })
      if (cached && activeTab.value === 'library' && selectedPlaylist.value?.id === playlist.id) tracks.value = cached.tracks
      await loadOfflinePlaylists()
    } catch (cause) { error.value = errorMessage(cause, '歌单缓存失败') }
    finally { loading.value = false }
  }

  async function cacheAllPlaylists() {
    if (!onlineAvailable.value || !vutronPlugin.cacheAllPlaylists || cacheAllPlaylistsLoading.value) return
    cacheAllPlaylistsLoading.value = true
    cacheAllPlaylistsResult.value = ''
    try {
      const result = await vutronPlugin.cacheAllPlaylists()
      cacheAllPlaylistsResult.value = `已缓存 ${result.cachedPlaylists} 个歌单、${result.cachedTracks} 首曲目资料${result.failedPlaylists ? `，${result.failedPlaylists} 个暂未完成` : ''}${result.pendingCovers ? `；${result.pendingCovers} 张封面仍在后台下载` : ''}`
      await Promise.all([loadOfflinePlaylists(), loadCacheStatus()])
    } catch (cause) { error.value = errorMessage(cause, '批量缓存歌单失败') }
    finally { cacheAllPlaylistsLoading.value = false }
  }

  async function openCachedPlaylist(playlist: VutronPlaylist) {
    rememberPage()
    const requestId = ++trackListRequestId
    selectedPlaylist.value = playlist
    selectedEntity.value = undefined
    tracks.value = []
    activeTab.value = 'library'
    loading.value = true
    try {
      const result = await vutronPlugin.getCachedPlaylistTracks?.({ playlistId: playlist.id })
      if (requestId === trackListRequestId) tracks.value = result?.tracks ?? []
    } catch (cause) { if (requestId === trackListRequestId) error.value = errorMessage(cause, '读取离线歌单失败') }
    finally { if (requestId === trackListRequestId) loading.value = false }
  }

  async function openEntityTracks(category: 'albums' | 'artists', id: number, name: string, picUrl?: string) {
    if (!onlineAvailable.value || !vutronPlugin.getEntityTracks) return
    rememberPage()
    const requestId = ++trackListRequestId
    loading.value = true
    tracks.value = []
    activeTab.value = 'entity'
    selectedEntity.value = { category, id, name, picUrl }
    selectedPlaylist.value = undefined
    try {
      const result = await vutronPlugin.getEntityTracks({ category, id })
      if (requestId === trackListRequestId) tracks.value = result.tracks
    } catch (cause) { if (requestId === trackListRequestId) error.value = errorMessage(cause, '读取搜索结果歌曲失败') }
    finally { if (requestId === trackListRequestId) loading.value = false }
  }

  async function openMv(id: number) {
    if (!onlineAvailable.value || !vutronPlugin.openMv) return
    try { await vutronPlugin.openMv({ id }) }
    catch (cause) { error.value = errorMessage(cause, '无法打开 MV 页面') }
  }

  async function runSearch(category = searchCategory.value, keywords = searchKeywords.value, reset = true) {
    if (!onlineAvailable.value) return
    const requestId = ++searchRequestId
    const normalizedKeywords = keywords.trim()
    if (!vutronPlugin.search || !normalizedKeywords) {
      searchResults.value = []
      searchCount.value = 0
      searchNextPage.value = 0
      searchLoading.value = false
      return
    }
    searchCategory.value = category
    searchKeywords.value = keywords
    if (reset) {
      searchResults.value = []
      searchCount.value = 0
      searchNextPage.value = 0
    }
    searchLoading.value = true
    try {
      const page = reset ? 1 : searchNextPage.value
      const result = await vutronPlugin.search({ category, keywords: normalizedKeywords, page })
      if (requestId !== searchRequestId || searchCategory.value !== category || searchKeywords.value.trim() !== normalizedKeywords) return
      searchResults.value = reset ? result.items : [...searchResults.value, ...result.items]
      searchCount.value = result.count
      searchNextPage.value = result.nextPage
    } catch (cause) {
      if (requestId === searchRequestId && searchCategory.value === category && searchKeywords.value.trim() === normalizedKeywords) {
        error.value = errorMessage(cause, '搜索失败')
      }
    } finally {
      if (requestId === searchRequestId) searchLoading.value = false
    }
  }

  async function loadMoreSearch() {
    if (searchNextPage.value > 0 && !searchLoading.value) await runSearch(searchCategory.value, searchKeywords.value, false)
  }

  async function play(track: VutronTrack, playlistKey?: string, suppliedQueue?: VutronTrack[]) {
    if (libraryMutationBusy.value) return
    const fromPlaylist = activeTab.value === 'library' && selectedPlaylist.value
      && tracks.value.some(item => item.id === track.id)
    const fromLocalCollection = activeTab.value === 'localCollection' ? selectedLocalCollection.value : undefined
    const context = {
      playlistVolumeKey: playlistKey ?? (fromPlaylist ? `netease:${selectedPlaylist.value!.id}` : fromLocalCollection?.id ?? ''),
      playlistVolumeName: fromPlaylist ? selectedPlaylist.value!.name : fromLocalCollection?.name ?? (playlistKey === 'local:folder' ? '本地音乐' : playlistKey === 'cache:all' ? '离线歌曲' : '')
    }
    const sourceQueue = suppliedQueue ?? (fromPlaylist || activeTab.value === 'entity' || fromLocalCollection ? tracks.value
      : playlistKey === 'local:folder' ? localTracks.value : playlistKey === 'cache:all' ? cachedTracks.value
      : activeTab.value === 'search' && searchCategory.value === 'tracks' ? searchResults.value as VutronTrack[]
      : homeData.value.dailyTracks.some(item => item.id === track.id) ? homeData.value.dailyTracks : [track])
    const queue = sourceQueue.map(item => ({ ...item, ...context }))
    const selectedIndex = queue.findIndex(item => item.id === track.id && item.localUri === track.localUri)
    const index = Math.max(0, selectedIndex)
    if (selectedIndex < 0) queue.splice(0, queue.length, { ...track, ...context })
    const contextual = queue[index]
    // Browsing already persists metadata; playing also ensures the current
    // playlist is saved when invoked from a saved queue or another page.
    const playlistId = Number(context.playlistVolumeKey.replace(/^netease:/, ''))
    if (onlineAvailable.value && context.playlistVolumeKey.startsWith('netease:') && playlistId > 0 && !playbackPlaylistCache.has(playlistId)) {
      playbackPlaylistCache.add(playlistId)
      void vutronPlugin.getPlaylistTracks({ playlistId, playlist: {
        id: playlistId, name: context.playlistVolumeName || `歌单 ${playlistId}`,
        coverImgUrl: contextual.picUrl, trackCount: queue.length
      } }).then(() => loadOfflinePlaylists()).catch(() => { playbackPlaylistCache.delete(playlistId) })
    }
    error.value = ''; navigateTo('player')
    playback.value = { ...emptyPlaybackState, trackId: track.id, title: track.name, artist: track.artists?.join(' / ') ?? '', track: contextual }
    overlayTrackKey = ''
    overlaySettings.value.currentLyricOffsetMs = offsetDrafts.get(playbackTrackKey())?.value ?? 0
    try { queueReadKey = ''; await vutronPlugin.play({ track: contextual, queue, index }); await refreshPlaybackState() }
    catch (cause) { error.value = errorMessage(cause, '播放失败') }
  }
  async function reorderLocalPlayback(collectionId: string, keys: string[]) {
    if (!vutronPlugin.reorderLocalPlaybackQueue) return
    try { playbackQueue.value = await vutronPlugin.reorderLocalPlaybackQueue({ collectionId, keys }) }
    catch (cause) { error.value = errorMessage(cause, '播放队列排序失败') }
  }
  async function playAdjacent(direction: -1 | 1) {
    try {
      if (direction > 0) await vutronPlugin.next()
      else await vutronPlugin.previous()
      await refreshPlaybackState()
    } catch (cause) { error.value = errorMessage(cause, '切换歌曲失败') }
  }
  async function pause() { try { await vutronPlugin.pause(); await refreshPlaybackState() } catch (cause) { error.value = errorMessage(cause, '暂停失败') } }
  async function resume() { try { await vutronPlugin.resume(); await refreshPlaybackState() } catch (cause) { error.value = errorMessage(cause, '继续播放失败') } }
  async function stop() { try { await vutronPlugin.stop(); await refreshPlaybackState() } catch (cause) { error.value = errorMessage(cause, '停止播放失败') } }
  async function cyclePlaybackMode() {
    if (!vutronPlugin.setPlaybackMode) return
    try { playback.value.mode = (await vutronPlugin.setPlaybackMode({ mode: ((playback.value.mode ?? 0) + 1) % 3 })).mode }
    catch (cause) { error.value = errorMessage(cause, '播放模式保存失败') }
  }
  async function seek(position: number) {
    if (!vutronPlugin.seek) return
    try { await vutronPlugin.seek({ position }); await refreshPlaybackState() }
    catch (cause) { error.value = errorMessage(cause, '跳转失败') }
  }

  async function refreshPlaybackState() {
    if (playbackRequestPending) return
    playbackRequestPending = true
    const volumeReadRevision = volumeRevision
    const pendingTrackVolumes = new Set([...trackVolumeWriters].filter(([, writer]) => writer.pending()).map(([key]) => key))
    const pendingPlaylistVolumes = new Set([...playlistVolumeWriters].filter(([, writer]) => writer.pending()).map(([key]) => key))
    try {
      const next = { ...emptyPlaybackState, ...(await vutronPlugin.getPlaybackState()) }
      const previousTrackKey = playbackTrackKey()
      const nextKey = playbackTrackKey(next)
      const trackDraft = trackVolumeDrafts.get(nextKey)
      const playlistKey = next.track?.playlistVolumeKey ?? ''
      const playlistDraft = playlistVolumeDrafts.get(playlistKey)
      if (trackDraft) {
        if (pendingTrackVolumes.has(nextKey) || trackVolumeWriters.get(nextKey)?.pending() || trackDraft.revision > volumeReadRevision || next.trackVolumePercent !== trackDraft.value) next.trackVolumePercent = trackDraft.value
        else trackVolumeDrafts.delete(nextKey)
      }
      if (playlistDraft) {
        if (pendingPlaylistVolumes.has(playlistKey) || playlistVolumeWriters.get(playlistKey)?.pending() || playlistDraft.revision > volumeReadRevision || next.playlistVolumePercent !== playlistDraft.value) next.playlistVolumePercent = playlistDraft.value
        else playlistVolumeDrafts.delete(playlistKey)
      }
      playback.value = next
      const nextTrackKey = playbackTrackKey()
      if (nextTrackKey !== previousTrackKey) overlaySettings.value.currentLyricOffsetMs = offsetDrafts.get(nextTrackKey)?.value ?? 0
      if (next.trackId && nextTrackKey !== overlayTrackKey) await loadOverlaySettings()
      if (queueOpen.value && queueStateKey(next) !== queueReadKey) await loadPlaybackQueue()
      if (vutronPlugin.getSleepTimer) {
        try { sleepTimerRemainingMs.value = (await vutronPlugin.getSleepTimer()).remainingMs }
        catch { /* Timer support was added after the earliest WebShell build. */ }
      }
      if (next.trackId && next.trackId !== lyricTrackId) await loadLyrics(next.trackId)
    } catch (cause) { error.value = errorMessage(cause, '读取播放状态失败') }
    finally { playbackRequestPending = false }
  }

  async function loadLyrics(trackId: number) {
    if (!hasAccess.value || !vutronPlugin.getLyrics || !trackId) return
    const requestId = ++lyricsRequestId
    lyricsLoading.value = true
    try {
      const payload = await vutronPlugin.getLyrics({ trackId })
      if (requestId !== lyricsRequestId || playback.value.trackId !== trackId) return
      lyrics.value = selectLyricFamily(payload); lyricTrackId = trackId
    } catch { if (requestId === lyricsRequestId) lyrics.value = { original: [], translation: [], pairedTranslations: [], source: 'empty' } }
    finally { if (requestId === lyricsRequestId) lyricsLoading.value = false }
  }

  async function loadSourceSettings() {
    try { autoMatchSources.value = (await vutronPlugin.getSourceSettings()).autoMatch }
    catch (cause) { error.value = errorMessage(cause, '音源设置读取失败') }
  }

  async function loadPlaybackQueue() {
    if (!vutronPlugin.getPlaybackQueue) return
    try { playbackQueue.value = await vutronPlugin.getPlaybackQueue(); queueReadKey = queueStateKey() }
    catch (cause) { error.value = errorMessage(cause, '播放队列读取失败') }
  }
  async function showPlaybackQueue() { queueOpen.value = true; await loadPlaybackQueue() }
  function trackWithContext(track: VutronTrack, playlistKey?: string): VutronTrack {
    const fromPlaylist = activeTab.value === 'library' && selectedPlaylist.value
      && tracks.value.some(item => item.id === track.id && item.localUri === track.localUri) ? selectedPlaylist.value : undefined
    const fromLocal = activeTab.value === 'localCollection' ? selectedLocalCollection.value : undefined
    const key = playlistKey ?? track.playlistVolumeKey ?? (fromPlaylist ? `netease:${fromPlaylist.id}` : fromLocal?.id ?? '')
    const name = track.playlistVolumeName || (fromPlaylist && key === `netease:${fromPlaylist.id}` ? fromPlaylist.name
      : fromLocal && key === fromLocal.id ? fromLocal.name : key === 'local:folder' ? '本地音乐' : key === 'cache:all' ? '离线歌曲' : '')
    return { ...track, artists: track.artists?.slice(), playlistVolumeKey: key, playlistVolumeName: name }
  }
  async function queueTrackNext(track: VutronTrack) {
    if (!vutronPlugin.addToPlayNext) { error.value = '当前原生版本尚未支持插队播放'; return false }
    const contextual = trackWithContext(track)
    try {
      playbackQueue.value = await vutronPlugin.addToPlayNext({ track: contextual })
      queueNotice.value = `下一首播放：${track.name}`
      return true
    } catch (cause) { error.value = errorMessage(cause, '加入插队播放失败'); return false }
  }
  async function appendTrackToQueue(track: VutronTrack) {
    if (!vutronPlugin.appendToPlaybackQueue) { error.value = '当前原生版本尚未支持添加到播放列表'; return false }
    try {
      playbackQueue.value = await vutronPlugin.appendToPlaybackQueue({ track: trackWithContext(track) })
      queueNotice.value = `已添加到播放列表末尾：${track.name}`
      return true
    } catch (cause) { error.value = errorMessage(cause, '添加到播放列表失败'); return false }
  }
  async function playQueueIndex(index: number, upNext = false) {
    if (!vutronPlugin.playQueueIndex) return
    try { playbackQueue.value = await vutronPlugin.playQueueIndex({ index, upNext }); await refreshPlaybackState() }
    catch (cause) { error.value = errorMessage(cause, '队列歌曲播放失败') }
  }
  async function removeQueueTrack(index: number, upNext = false) {
    if (!vutronPlugin.removeQueueTrack) return
    try { playbackQueue.value = await vutronPlugin.removeQueueTrack({ index, upNext }); await refreshPlaybackState() }
    catch (cause) { error.value = errorMessage(cause, '移除队列歌曲失败') }
  }
  async function clearPlayNext() {
    if (!vutronPlugin.clearPlayNext) return
    try { playbackQueue.value = await vutronPlugin.clearPlayNext() }
    catch (cause) { error.value = errorMessage(cause, '清除插队列表失败') }
  }

  async function setAutoMatchSources(enabled: boolean) {
    try { autoMatchSources.value = (await vutronPlugin.updateSourceSettings({ autoMatch: enabled })).autoMatch }
    catch (cause) { error.value = errorMessage(cause, '音源设置保存失败') }
  }

  async function setCurrentTrackVolume(percent: number) {
    if (!Number.isInteger(percent) || percent < 0 || percent > 100 || !playback.value.trackId) return
    const key = playbackTrackKey()
    const track = playback.value.track ? { ...playback.value.track } : { id: playback.value.trackId, name: playback.value.title }
    trackVolumeDrafts.set(key, { value: percent, revision: ++volumeRevision })
    playback.value.trackVolumePercent = percent
    let writer = trackVolumeWriters.get(key)
    if (!writer) {
      writer = createLiveSettingWriter<number>({
        write: async value => { await vutronPlugin.setTrackVolume({ track, percent: value }) },
        onError: cause => { error.value = errorMessage(cause, '单曲音量保存失败') }
      })
      trackVolumeWriters.set(key, writer)
    }
    await writer.queue(percent)
  }

  async function setCurrentPlaylistVolume(percent: number) {
    const playlistKey = playback.value.track?.playlistVolumeKey
    if (!playlistKey || !Number.isInteger(percent) || percent < 0 || percent > 100) return
    playlistVolumeDrafts.set(playlistKey, { value: percent, revision: ++volumeRevision })
    playback.value.playlistVolumePercent = percent
    let writer = playlistVolumeWriters.get(playlistKey)
    if (!writer) {
      writer = createLiveSettingWriter<number>({
        write: async value => { await vutronPlugin.setPlaylistVolume({ playlistKey, percent: value }) },
        onError: cause => { error.value = errorMessage(cause, '歌单音量保存失败') }
      })
      playlistVolumeWriters.set(playlistKey, writer)
    }
    await writer.queue(percent)
  }

  async function loadOverlaySettings() {
    if (!vutronPlugin.getLyricOverlaySettings) return
    const requestId = ++overlayReadRequest
    const revision = overlayRevision
    const readOffsetRevision = offsetRevision
    const trackKey = playbackTrackKey()
    const readingDirtyKeys = [...overlayDrafts.keys()]
    const readingOffsetWrite = offsetWriters.get(trackKey)?.pending()
    try {
      const next = await vutronPlugin.getLyricOverlaySettings()
      if (requestId !== overlayReadRequest) return
      const { currentLyricOffsetMs, ...globalSettings } = next
      if (revision === overlayRevision) {
        const safeSettings: Partial<VutronLyricOverlaySettings> = { ...globalSettings }
        for (const key of readingDirtyKeys) delete safeSettings[key]
        for (const key of overlayDrafts.keys()) delete safeSettings[key]
        overlaySettings.value = { ...overlaySettings.value, ...safeSettings }
      }
      const draft = offsetDrafts.get(trackKey)
      if (!readingOffsetWrite && trackKey === playbackTrackKey() && !offsetWriters.get(trackKey)?.pending() && (!draft || draft.revision <= readOffsetRevision)) {
        if (!draft || currentLyricOffsetMs === draft.value) {
          overlaySettings.value.currentLyricOffsetMs = currentLyricOffsetMs ?? 0
          offsetDrafts.delete(trackKey)
        }
        overlayTrackKey = trackKey
      }
    } catch (cause) {
      error.value = errorMessage(cause, '桌面歌词设置读取失败')
    }
  }

  async function toggleLyricOverlay() {
    const enabled = !overlaySettings.value.enabled
    await updateOverlayField('enabled', enabled)
  }

  async function updateOverlayField(key: keyof VutronLyricOverlaySettings, value: boolean | number) {
    if (key === 'currentLyricOffsetMs') { await updateCurrentLyricOffset(Number(value)); return }
    const patch = { [key]: value } as Partial<VutronLyricOverlaySettings>
    overlayDrafts.set(key, { value, revision: ++overlayRevision })
    overlaySettings.value = { ...overlaySettings.value, ...patch }
    await overlayWriter.queue(patch)
  }

  async function temporarilyHideOverlay() {
    if (!vutronPlugin.temporaryHideLyricOverlay) return
    try {
      const next = await vutronPlugin.temporaryHideLyricOverlay({ durationMs: 30_000 })
      overlaySettings.value.temporaryHidden = next.temporaryHidden
      overlaySettings.value.temporaryHiddenRemainingMs = next.temporaryHiddenRemainingMs
    }
    catch (cause) { error.value = errorMessage(cause, '歌词临时隐藏失败') }
  }
  async function restoreOverlay() {
    if (!vutronPlugin.restoreLyricOverlay) return
    try {
      const next = await vutronPlugin.restoreLyricOverlay()
      overlaySettings.value.temporaryHidden = next.temporaryHidden
      overlaySettings.value.temporaryHiddenRemainingMs = next.temporaryHiddenRemainingMs
    }
    catch (cause) { error.value = errorMessage(cause, '歌词恢复失败') }
  }

  async function updateOverlayColor(key: 'playedColor' | 'unplayedColor' | 'shadowColor', value: string) {
    const parsed = Number.parseInt(value.replace('#', ''), 16)
    if (!Number.isFinite(parsed)) return
    const alpha = key === 'shadowColor' ? overlaySettings.value.shadowColor & 0xff000000 : 0xff000000
    await updateOverlayField(key, (alpha | parsed) >>> 0)
  }

  async function updateOverlayBackgroundOpacity(percent: number) {
    const alpha = Math.round(Math.max(0, Math.min(100, percent)) * 255 / 100)
    const rgb = overlaySettings.value.backgroundColor & 0x00ffffff
    await updateOverlayField('backgroundColor', ((alpha << 24) | rgb) >>> 0)
  }

  async function updateCurrentLyricOffset(offsetMs: number) {
    if (!vutronPlugin.setLyricOffset || !playback.value.trackId) return
    if (!Number.isFinite(offsetMs) || offsetMs < -30_000 || offsetMs > 30_000) { error.value = '歌词延迟范围为 −30 至 30 秒'; return }
    const trackId = playback.value.trackId
    const trackKey = playbackTrackKey()
    const track = playback.value.track ? { ...playback.value.track } : { id: trackId, name: playback.value.title }
    offsetDrafts.set(trackKey, { value: offsetMs, revision: ++offsetRevision })
    overlaySettings.value.currentLyricOffsetMs = offsetMs
    overlayTrackKey = trackKey
    let writer = offsetWriters.get(trackKey)
    if (!writer) {
      writer = createLiveSettingWriter<number>({
        write: async value => { await vutronPlugin.setLyricOffset!({ trackId, track, offsetMs: value }) },
        onError: cause => { error.value = errorMessage(cause, '歌词延迟保存失败') }
      })
      offsetWriters.set(trackKey, writer)
    }
    await writer.queue(offsetMs)
  }

  async function flushLiveSettings() {
    await Promise.all([overlayWriter.flush(), ...[...offsetWriters.values(), ...trackVolumeWriters.values(), ...playlistVolumeWriters.values()].map(writer => writer.flush())])
  }

  async function openLocalCollection(collection: VutronLocalCollection) {
    rememberPage()
    const requestId = ++trackListRequestId
    selectedPlaylist.value = undefined; selectedEntity.value = undefined; selectedLocalCollection.value = collection
    tracks.value = []; activeTab.value = 'localCollection'; loading.value = true
    try {
      const result = collection.isLocalBundle ? await vutronPlugin.getLocalPlaylistTracks?.({ playlistId: collection.id })
        : await vutronPlugin.getLocalFolderTracks?.({ folderId: collection.id })
      if (requestId === trackListRequestId) tracks.value = result?.tracks ?? []
    } catch (cause) { if (requestId === trackListRequestId) error.value = errorMessage(cause, '本地歌单读取失败') }
    finally { if (requestId === trackListRequestId) loading.value = false }
  }
  async function setAutomaticCache(enabled: boolean) {
    if (!vutronPlugin.setCacheEnabled) return
    ++cacheSettingsRevision
    try { cacheStatus.value = await vutronPlugin.setCacheEnabled({ enabled }) }
    catch (cause) { error.value = errorMessage(cause, '自动缓存设置失败') }
  }
  async function loadCacheStatus(updateInputs = true) {
    if (!vutronPlugin.getCacheStatus || cacheStatusPending) return
    cacheStatusPending = true
    const revision = cacheSettingsRevision
    try {
      const status = await vutronPlugin.getCacheStatus()
      if (revision !== cacheSettingsRevision) return
      cacheStatus.value = status
      if (updateInputs) {
        cacheCapacityMb.value = status.capacityMb
        artworkCacheCapacityMb.value = status.artworkCapacityMb ?? artworkCacheCapacityMb.value
      }
      if (vutronPlugin.getCachedTracks) {
        const cached = await vutronPlugin.getCachedTracks()
        if (revision === cacheSettingsRevision) cachedTracks.value = cached.tracks ?? []
      }
    } catch (cause) { error.value = errorMessage(cause, '缓存状态读取失败') }
    finally { cacheStatusPending = false }
  }
  async function setCacheCapacity() {
    if (!vutronPlugin.setCacheCapacity) return
    ++cacheSettingsRevision
    try { cacheStatus.value = await vutronPlugin.setCacheCapacity({ capacityMb: cacheCapacityMb.value }) }
    catch (cause) { error.value = errorMessage(cause, '缓存容量设置失败') }
  }
  async function setArtworkCacheCapacity() {
    if (!vutronPlugin.setArtworkCacheCapacity) return
    ++cacheSettingsRevision
    try { cacheStatus.value = await vutronPlugin.setArtworkCacheCapacity({ capacityMb: artworkCacheCapacityMb.value }) }
    catch (cause) { error.value = errorMessage(cause, '封面缓存容量设置失败') }
  }
  async function cacheCurrent() {
    if (!onlineAvailable.value || !vutronPlugin.cacheTrack || !playback.value.trackId) return
    const track = playback.value.track ?? [...tracks.value, ...localTracks.value].find(item => item.id === playback.value.trackId)
    if (!track) return
    try { await vutronPlugin.cacheTrack({ track }); await loadCacheStatus() }
    catch (cause) { error.value = errorMessage(cause, '歌曲缓存失败') }
  }
  async function clearCache() {
    if (!vutronPlugin.clearCache) return
    try { await vutronPlugin.clearCache(); await Promise.all([loadCacheStatus(), loadOfflinePlaylists()]) }
    catch (cause) { error.value = errorMessage(cause, '缓存清理失败') }
  }

  async function setSleepTimer(minutes: number) {
    if (!vutronPlugin.setSleepTimer || !Number.isFinite(minutes) || minutes < 1 || minutes > 1440) return
    try { sleepTimerRemainingMs.value = (await vutronPlugin.setSleepTimer({ durationMs: Math.round(minutes * 60_000) })).remainingMs }
    catch (cause) { error.value = errorMessage(cause, '睡眠定时设置失败') }
  }

  async function clearSleepTimer() {
    if (!vutronPlugin.clearSleepTimer) return
    try { sleepTimerRemainingMs.value = (await vutronPlugin.clearSleepTimer()).remainingMs }
    catch (cause) { error.value = errorMessage(cause, '睡眠定时取消失败') }
  }

  const sleepTimerLabel = computed(() => {
    const seconds = Math.max(0, Math.ceil(sleepTimerRemainingMs.value / 1000))
    return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
  })

  function startPlaybackPolling(intervalMs = 2000) {
    stopPlaybackPolling(); void refreshPlaybackState()
    playbackTimer = setInterval(() => {
      if (document.visibilityState === 'hidden') return
      void refreshPlaybackState()
      if (!['settings', 'library'].includes(activeTab.value)) return
      const now = Date.now()
      if (now - cachePollAt >= 5000) { cachePollAt = now; void loadCacheStatus(false) }
    }, intervalMs)
  }
  function stopPlaybackPolling() { if (playbackTimer !== undefined) clearInterval(playbackTimer); playbackTimer = undefined }
  return {
    ...onlineLibrary,
    profile, playlists, playlistsLoading, cachedPlaylists, tracks, selectedPlaylist, selectedEntity, localTracks, cachedTracks, folderName, cacheStatus, cacheCapacityMb,
    localLibrary, selectedLocalCollection, libraryLoading, localScanLoading, loadLocalLibrary, importLocalPlaylist, openLocalCollection, reloadManagedLibrary,
    homeData, homeLoading, searchCategory, searchKeywords, searchResults, searchCount, searchNextPage, searchLoading,
    sleepTimerRemainingMs, sleepTimerMinutes, sleepTimerLabel, cacheAllPlaylistsLoading, cacheAllPlaylistsResult,
    lyrics, lyricsLoading, activeTab, loading, error, playback, loggedIn, localOnly, hasAccess, onlineAvailable, loginVisible, localModeLoading, enterLocalOnly, requestLogin,
    playbackQueue, queueOpen, queueNotice, showPlaybackQueue, loadPlaybackQueue, trackWithContext, queueTrackNext, appendTrackToQueue, playQueueIndex, removeQueueTrack, clearPlayNext,
    canNavigateBack, navigationDirection, navigationScrollY, navigateTo, navigateBack,
    qrLoginKey, qrLoginImage, qrLoginStatus, qrLoginLoading,
    restore, login, createQrLogin, checkQrLogin, cancelQrLogin, loadPlaylists, loadOfflinePlaylists, loadHomeData, openPlaylist, cachePlaylist, cacheAllPlaylists, openCachedPlaylist, openEntityTracks,
    runSearch, loadMoreSearch, openMv, play, reorderLocalPlayback, playAdjacent, pause, resume, stop, seek, cyclePlaybackMode, refreshPlaybackState, loadLyrics,
    pickMusicFolder, loadLocalMusic, loadCacheStatus, setCacheCapacity, setArtworkCacheCapacity, artworkCacheCapacityMb, cacheCurrent, clearCache,
    setSleepTimer, clearSleepTimer, setAutomaticCache, temporarilyHideOverlay, restoreOverlay,
    autoMatchSources, loadSourceSettings, setAutoMatchSources, setCurrentTrackVolume, setCurrentPlaylistVolume,
    overlaySettings, overlayEnabled, overlayPlayedColor, overlayUnplayedColor, overlayShadowColor, overlayBackgroundOpacity,
    loadOverlaySettings, toggleLyricOverlay, updateOverlayField, updateOverlayColor,
    updateOverlayBackgroundOpacity, updateCurrentLyricOffset, flushLiveSettings,
    startPlaybackPolling, stopPlaybackPolling, logout
  }
})
