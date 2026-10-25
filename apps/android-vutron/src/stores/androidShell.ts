import { computed, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { createLiveSettingWriter } from '../platform/liveSettingWrites'
import { selectLyricFamily, type LyricFamily } from '@vutronmusic/core/lyrics'
import {
  vutronPlugin,
  type VutronCacheStatus,
  type VutronHomeData,
  type VutronLocalTrack,
  type VutronLocalLibrary,
  type VutronLocalCollection,
  type VutronLyricOverlaySettings,
  type VutronPlaybackState,
  type VutronPlaylist,
  type VutronProfile,
  type VutronSearchCategory,
  type VutronSearchResult,
  type VutronTrack,
  type VutronQueue
} from '../platform/vutronPlugin'

const emptyPlaybackState: VutronPlaybackState = {
  playing: false, trackId: 0, title: '', artist: '', duration: 0, position: 0, error: ''
}

type ShellTab = 'home' | 'library' | 'search' | 'entity' | 'localCollection' | 'player' | 'settings'
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
  const profile = ref<VutronProfile>()
  const playlists = ref<VutronPlaylist[]>([])
  const tracks = ref<VutronTrack[]>([])
  const selectedPlaylist = ref<VutronPlaylist>()
  const localLibrary = ref<VutronLocalLibrary>({ folders: [], playlists: [] })
  const selectedLocalCollection = ref<VutronLocalCollection>()
  const libraryLoading = ref(false)
  const localTracks = ref<VutronLocalTrack[]>([])
  const cachedTracks = ref<VutronLocalTrack[]>([])
  const folderName = ref('未选择文件夹')
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
  const canNavigateBack = computed(() => navigationHistory.value.length > 0 || activeTab.value !== 'home')
  const loading = ref(false)
  const error = ref('')
  const playback = ref<VutronPlaybackState>({ ...emptyPlaybackState })
  const playbackQueue = ref<VutronQueue>({ tracks: [], index: -1, upNext: [], mode: 0 })
  const queueOpen = ref(false)
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
  const qrLoginKey = ref('')
  const qrLoginImage = ref('')
  const qrLoginStatus = ref('')
  const qrLoginLoading = ref(false)
  const loggedIn = computed(() => Boolean(profile.value))
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

  function rememberPage() {
    navigationHistory.value = [...navigationHistory.value, {
      tab: activeTab.value, playlist: selectedPlaylist.value, entity: selectedEntity.value,
      tracks: tracks.value, localCollection: selectedLocalCollection.value, scrollY: window.scrollY
    }].slice(-40)
    navigationDirection.value = 'forward'
    navigationScrollY.value = 0
  }

  function navigateTo(tab: ShellTab) {
    if (tab === activeTab.value && !(tab === 'library' && selectedPlaylist.value)) return
    rememberPage()
    ++trackListRequestId
    loading.value = false
    activeTab.value = tab
    if (tab === 'library') { selectedPlaylist.value = undefined; selectedEntity.value = undefined; selectedLocalCollection.value = undefined; tracks.value = [] }
    if (tab === 'home') navigationHistory.value = []
  }

  function navigateBack(): boolean {
    const previous = navigationHistory.value.at(-1)
    if (!previous && activeTab.value === 'home') return false
    ++trackListRequestId
    loading.value = false
    navigationDirection.value = 'back'
    navigationScrollY.value = previous?.scrollY ?? 0
    navigationHistory.value = navigationHistory.value.slice(0, -1)
    selectedPlaylist.value = previous?.playlist
    selectedEntity.value = previous?.entity
    selectedLocalCollection.value = previous?.localCollection
    tracks.value = previous?.tracks ?? []
    activeTab.value = previous?.tab ?? 'home'
    return true
  }

  async function restore() {
    try {
      const session = await vutronPlugin.getSession()
      profile.value = session.profile
      if (profile.value) {
        try { await vutronPlugin.restorePlayback() } catch { /* First run or expired session. */ }
        await Promise.all([loadPlaylists(), loadHomeData(), loadOfflinePlaylists(), refreshPlaybackState(), loadCacheStatus(), loadLocalMusic(), loadOverlaySettings(), loadSourceSettings()])
        if (playback.value.trackId) await loadLyrics(playback.value.trackId)
      }
    } catch (cause) { error.value = errorMessage(cause, '无法连接 Android 原生适配器') }
  }

  async function login(cookie: string) {
    if (!cookie.trim()) { error.value = '请输入网易云 Cookie'; return }
    loading.value = true; error.value = ''
    try {
      await completeLogin((await vutronPlugin.loginWithCookie({ cookie: cookie.trim() })).profile)
    } catch (cause) { error.value = errorMessage(cause, '网易云登录失败') }
    finally { loading.value = false }
  }

  async function completeLogin(account: VutronProfile) {
    profile.value = account
    qrLoginKey.value = ''
    qrLoginImage.value = ''
    qrLoginStatus.value = '登录成功，正在载入音乐库…'
    try { await vutronPlugin.restorePlayback() } catch { /* No saved track is valid. */ }
    await Promise.all([loadPlaylists(), loadHomeData(), loadOfflinePlaylists(), refreshPlaybackState(), loadCacheStatus(), loadLocalMusic(), loadOverlaySettings(), loadSourceSettings()])
    startPlaybackPolling()
  }

  async function createQrLogin() {
    if (!vutronPlugin.createQrLogin) {
      qrLoginStatus.value = '当前版本没有二维码登录支持'
      return
    }
    qrLoginLoading.value = true
    qrLoginStatus.value = '正在获取二维码…'
    const previousKey = qrLoginKey.value
    qrLoginKey.value = ''
    qrLoginImage.value = ''
    if (previousKey) {
      try { await vutronPlugin.cancelQrLogin?.({ key: previousKey }) } catch { /* Old QR session may already be expired. */ }
    }
    try {
      const result = await vutronPlugin.createQrLogin()
      qrLoginKey.value = result.key
      qrLoginImage.value = result.imageData
      qrLoginStatus.value = '请使用网易云音乐扫一扫并确认登录'
    } catch (cause) {
      qrLoginStatus.value = errorMessage(cause, '二维码获取失败，请检查网络后重试')
    } finally {
      qrLoginLoading.value = false
    }
  }

  async function checkQrLogin() {
    if (!qrLoginKey.value || !vutronPlugin.checkQrLogin || qrLoginLoading.value) return
    qrLoginLoading.value = true
    try {
      const result = await vutronPlugin.checkQrLogin({ key: qrLoginKey.value })
      if (result.loggedIn && result.profile) {
        await completeLogin(result.profile)
      } else if (result.code === 800) {
        qrLoginKey.value = ''
        qrLoginImage.value = ''
        qrLoginStatus.value = '二维码已过期，请刷新二维码'
      } else if (result.code === 802) {
        qrLoginStatus.value = '已扫描，请在网易云音乐中确认登录'
      } else if (result.code === 801) {
        qrLoginStatus.value = '等待扫码…'
      } else {
        qrLoginStatus.value = result.message || '正在检查登录状态…'
      }
    } catch (cause) {
      qrLoginStatus.value = `${errorMessage(cause, '网络暂时不可用')}，将继续检查`
    } finally {
      qrLoginLoading.value = false
    }
  }

  async function cancelQrLogin() {
    const key = qrLoginKey.value
    qrLoginKey.value = ''
    qrLoginImage.value = ''
    if (!key) return
    try { await vutronPlugin.cancelQrLogin?.({ key }) } catch { /* Session can expire independently. */ }
  }

  async function loadPlaylists() {
    if (!profile.value) return
    loading.value = true
    try { playlists.value = (await vutronPlugin.getPlaylists({ userId: profile.value.userId })).playlists }
    catch (cause) { error.value = errorMessage(cause, '歌单读取失败') }
    finally { loading.value = false }
  }

  async function loadOfflinePlaylists() {
    if (!vutronPlugin.getCachedPlaylists) return
    try { cachedPlaylists.value = (await vutronPlugin.getCachedPlaylists()).playlists ?? [] }
    catch { /* Offline snapshots are optional on older builds. */ }
  }

  async function loadHomeData() {
    if (!vutronPlugin.getHomeData) return
    homeLoading.value = true
    try { homeData.value = await vutronPlugin.getHomeData() }
    catch (cause) {
      error.value = errorMessage(cause, '首页内容暂时无法加载')
      if (!homeData.value.playlists.length) homeData.value.playlists = cachedPlaylists.value.slice(0, 10)
    }
    finally { homeLoading.value = false }
  }

  async function openPlaylist(playlist: VutronPlaylist) {
    rememberPage()
    const requestId = ++trackListRequestId
    selectedPlaylist.value = playlist; selectedEntity.value = undefined; tracks.value = []
    activeTab.value = 'library'; loading.value = true; error.value = ''
    try {
      const result = await vutronPlugin.getPlaylistTracks({ playlistId: playlist.id, playlist })
      playbackPlaylistCache.add(playlist.id)
      if (requestId === trackListRequestId) tracks.value = result.tracks
      void loadOfflinePlaylists()
    } catch (cause) { if (requestId === trackListRequestId) error.value = errorMessage(cause, '歌曲列表读取失败') }
    finally { if (requestId === trackListRequestId) loading.value = false }
  }

  async function cachePlaylist(playlist: VutronPlaylist) {
    if (!vutronPlugin.cachePlaylist) return
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
    if (!vutronPlugin.cacheAllPlaylists || cacheAllPlaylistsLoading.value) return
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
    if (!vutronPlugin.getEntityTracks) return
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
    if (!vutronPlugin.openMv) return
    try { await vutronPlugin.openMv({ id }) }
    catch (cause) { error.value = errorMessage(cause, '无法打开 MV 页面') }
  }

  async function runSearch(category = searchCategory.value, keywords = searchKeywords.value, reset = true) {
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
    if (context.playlistVolumeKey.startsWith('netease:') && playlistId > 0 && !playbackPlaylistCache.has(playlistId)) {
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
    try { await vutronPlugin.play({ track: contextual, queue, index }); await refreshPlaybackState() }
    catch (cause) { error.value = errorMessage(cause, '播放失败') }
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
      if (queueOpen.value) await loadPlaybackQueue()
      if (vutronPlugin.getSleepTimer) {
        try { sleepTimerRemainingMs.value = (await vutronPlugin.getSleepTimer()).remainingMs }
        catch { /* Timer support was added after the earliest WebShell build. */ }
      }
      if (next.trackId && next.trackId !== lyricTrackId) await loadLyrics(next.trackId)
    } catch (cause) { error.value = errorMessage(cause, '读取播放状态失败') }
    finally { playbackRequestPending = false }
  }

  async function loadLyrics(trackId: number) {
    if (!vutronPlugin.getLyrics || !trackId) return
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
    try { playbackQueue.value = await vutronPlugin.getPlaybackQueue() }
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

  async function pickMusicFolder() {
    if (!vutronPlugin.pickMusicFolder) { error.value = '当前 APK 尚未接入文件夹选择器'; return }
    try { const result = await vutronPlugin.pickMusicFolder(); if (result.name) folderName.value = result.name; await loadLocalMusic() }
    catch (cause) { error.value = errorMessage(cause, '文件夹授权失败') }
  }
  async function loadLocalMusic() {
    if (!vutronPlugin.scanLocalMusic) return
    try {
      localTracks.value = (await vutronPlugin.scanLocalMusic()).tracks ?? []
      await loadLocalLibrary()
    }
    catch (cause) { error.value = errorMessage(cause, '本地音乐扫描失败') }
  }
  async function loadLocalLibrary() {
    if (!vutronPlugin.getLocalLibrary) return
    try {
      localLibrary.value = await vutronPlugin.getLocalLibrary()
      folderName.value = localLibrary.value.folders.map(item => item.name).join('、') || '未选择文件夹'
    } catch (cause) { error.value = errorMessage(cause, '本地音乐资料读取失败') }
  }
  async function importLocalPlaylist() {
    if (!vutronPlugin.importLocalPlaylist || libraryLoading.value) return
    libraryLoading.value = true
    try {
      const result = await vutronPlugin.importLocalPlaylist()
      if (result.cancelled) return
      await loadLocalLibrary()
      if (result.playlist) await openLocalCollection(result.playlist)
    } catch (cause) { error.value = errorMessage(cause, '电脑本地歌单导入失败') }
    finally { libraryLoading.value = false }
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
    if (!vutronPlugin.cacheTrack || !playback.value.trackId) return
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

  function startPlaybackPolling(intervalMs = 1000) {
    stopPlaybackPolling(); void refreshPlaybackState()
    playbackTimer = setInterval(() => {
      void refreshPlaybackState()
      if (document.visibilityState === 'hidden' || !['settings', 'library'].includes(activeTab.value)) return
      const now = Date.now()
      if (now - cachePollAt >= 2000) { cachePollAt = now; void loadCacheStatus(false) }
    }, intervalMs)
  }
  function stopPlaybackPolling() { if (playbackTimer !== undefined) clearInterval(playbackTimer); playbackTimer = undefined }
  async function logout() {
    await flushLiveSettings()
    ++trackListRequestId; ++searchRequestId; ++lyricsRequestId
    ++cacheSettingsRevision; playbackPlaylistCache.clear()
    navigationHistory.value = []; selectedEntity.value = undefined; activeTab.value = 'home'
    stopPlaybackPolling(); await cancelQrLogin(); try { await vutronPlugin.logout() } catch { /* Browser preview. */ }
    profile.value = undefined; playlists.value = []; tracks.value = []; localTracks.value = []; cachedTracks.value = []; selectedPlaylist.value = undefined
  }

  return {
    profile, playlists, cachedPlaylists, tracks, selectedPlaylist, selectedEntity, localTracks, cachedTracks, folderName, cacheStatus, cacheCapacityMb,
    localLibrary, selectedLocalCollection, libraryLoading, loadLocalLibrary, importLocalPlaylist, openLocalCollection,
    homeData, homeLoading, searchCategory, searchKeywords, searchResults, searchCount, searchNextPage, searchLoading,
    sleepTimerRemainingMs, sleepTimerMinutes, sleepTimerLabel, cacheAllPlaylistsLoading, cacheAllPlaylistsResult,
    lyrics, lyricsLoading, activeTab, loading, error, playback, loggedIn,
    playbackQueue, queueOpen, queueNotice, showPlaybackQueue, loadPlaybackQueue, trackWithContext, queueTrackNext, appendTrackToQueue, playQueueIndex, removeQueueTrack, clearPlayNext,
    canNavigateBack, navigationDirection, navigationScrollY, navigateTo, navigateBack,
    qrLoginKey, qrLoginImage, qrLoginStatus, qrLoginLoading,
    restore, login, createQrLogin, checkQrLogin, cancelQrLogin, loadPlaylists, loadOfflinePlaylists, loadHomeData, openPlaylist, cachePlaylist, cacheAllPlaylists, openCachedPlaylist, openEntityTracks,
    runSearch, loadMoreSearch, openMv, play, playAdjacent, pause, resume, stop, seek, cyclePlaybackMode, refreshPlaybackState, loadLyrics,
    pickMusicFolder, loadLocalMusic, loadCacheStatus, setCacheCapacity, setArtworkCacheCapacity, artworkCacheCapacityMb, cacheCurrent, clearCache,
    setSleepTimer, clearSleepTimer, setAutomaticCache, temporarilyHideOverlay, restoreOverlay,
    autoMatchSources, loadSourceSettings, setAutoMatchSources, setCurrentTrackVolume, setCurrentPlaylistVolume,
    overlaySettings, overlayEnabled, overlayPlayedColor, overlayUnplayedColor, overlayShadowColor, overlayBackgroundOpacity,
    loadOverlaySettings, toggleLyricOverlay, updateOverlayField, updateOverlayColor,
    updateOverlayBackgroundOpacity, updateCurrentLyricOffset, flushLiveSettings,
    startPlaybackPolling, stopPlaybackPolling, logout
  }
})
