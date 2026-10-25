<script setup lang="ts">
import AppSettingsView from './components/AppSettingsView.vue'
import { formatBytes } from './utils/formatting'
import { buildInfo } from './platform/buildInfo'
import { computed, defineAsyncComponent, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { App as CapacitorApp } from '@capacitor/app'
import { Capacitor, type PluginListenerHandle } from '@capacitor/core'
import CoverImage from './components/CoverImage.vue'
import OriginalIcon from './components/OriginalIcon.vue'
import originalBrandLogo from '../../../src/renderer/assets/images/vutronmusic-icon.png?url'
import TrackListPager from './components/TrackListPager.vue'
import AppUpdateNotice from './components/AppUpdateNotice.vue'
import ActionDialog from './components/ActionDialog.vue'
import OnlinePlaylistDialog from './components/OnlinePlaylistDialog.vue'
import LibraryTaskProgress from './components/LibraryTaskProgress.vue'
import { initializeLibraryTasks, disposeLibraryTasks } from './platform/libraryTasks'
import { useLibraryManagement } from './composables/libraryManagement'
import { actionDialog, settleAction } from './platform/actionDialog'
import { initializeAppUpdates, disposeAppUpdates } from './platform/appUpdates'
import { useDebouncedLibraryQuery, useTrackSearch, useProgressiveTracks, useSortedTracks, useLocalTrackOrder } from './composables/libraryPresentation'
import LocalLibrarySort from './components/LocalLibrarySort.vue'
import LocalTrackList from './components/LocalTrackList.vue'
import { usePageRefresh } from './composables/pageRefresh'
import CollapsibleSettingsCard from './components/CollapsibleSettingsCard.vue'
import SideNavItem from '../../../packages/ui/src/SideNavItem.vue'
import OriginalLyricsView from './components/OriginalLyricsView.vue'
import LibraryLayoutControls from './components/LibraryLayoutControls.vue'
import { playlistView, playlistColumns } from './platform/libraryLayout'
import PlaybackQueuePanel from './components/PlaybackQueuePanel.vue'
import TrackActionMenu from './components/TrackActionMenu.vue'
import TrackMenuButton from './components/TrackMenuButton.vue'
import { useTrackActionMenu } from './composables/trackActionMenu'
import { useAndroidShellStore } from './stores/androidShell'
import { vutronPlugin, type VutronTrack } from './platform/vutronPlugin'

const store = useAndroidShellStore()
const AppAboutView = defineAsyncComponent(() => import('./components/AppAboutView.vue'))
const libraryManagement = useLibraryManagement()
const { t } = useI18n()
const { trackAction, openTrackMenu, closeTrackMenu, vTrackMenu } = useTrackActionMenu()
const cookie = ref('')
const sidebarOpen = ref(false)
const libraryMode = ref<'playlists' | 'local' | 'offline'>('playlists')
const { headerRefreshLabel, headerRefreshing, refreshCurrentView } = usePageRefresh(store, libraryMode)
const query = ref('')
const seeking = ref(false)
const seekValue = ref(0)
const heroIndex = ref(0)
let heroTimer: ReturnType<typeof setInterval> | undefined
let qrPollTimer: ReturnType<typeof setTimeout> | undefined
let qrPollPending = false
let backButtonListener: PluginListenerHandle | undefined
let noticeTimer: ReturnType<typeof setTimeout> | undefined
const pageKey = computed(() => store.activeTab === 'library'
  ? `library:${store.selectedPlaylist?.id ?? 'root'}`
  : store.activeTab === 'localCollection' ? `local:${store.selectedLocalCollection?.id}`
  : store.activeTab === 'entity' ? `entity:${store.selectedEntity?.category}:${store.selectedEntity?.id}` : store.activeTab)
const transitionName = computed(() => store.activeTab === 'player' ? 'player-open' : store.navigationDirection === 'back' ? 'page-back' : 'page-forward')
const searchCategories = computed(() => [
  { key: 'tracks', label: t('nav.track') },
  { key: 'albums', label: t('nav.album') },
  { key: 'artists', label: t('nav.artist') },
  { key: 'playlists', label: t('nav.playlist') },
  { key: 'mvs', label: 'MV' }
] as const)
const currentTrack = computed<VutronTrack>(() => {
  const id = store.playback.trackId
  const match = store.playback.track ?? [...store.tracks, ...store.localTracks, ...store.cachedTracks].find(track => track.id === id)
  return match ?? {
    id,
    name: store.playback.title || t('mobile.common.notPlaying'),
    artists: store.playback.artist ? [store.playback.artist] : [],
    duration: store.playback.duration
  }
})
const currentCover = computed(() => currentTrack.value.picLocalUri || currentTrack.value.picUrl || '')
const libraryQuery = useDebouncedLibraryQuery(query)
const filteredPlaylists = computed(() => store.playlists.filter(item => item.name.toLowerCase().includes(libraryQuery.value)))
const { visible: visiblePlaylists, hasMore: morePlaylists, showMore: showMorePlaylists } = useProgressiveTracks(filteredPlaylists, [libraryQuery, () => store.playlists])
const { visible: visibleOfflinePlaylists, hasMore: moreOfflinePlaylists, showMore: showMoreOfflinePlaylists } = useProgressiveTracks(computed(() => store.cachedPlaylists), () => store.cachedPlaylists)
const cachedMatches = useTrackSearch(computed(() => store.cachedTracks), libraryQuery)
const libraryOrder = useLocalTrackOrder()
const orderedCachedTracks = useSortedTracks(cachedMatches, libraryOrder)
const { visible: visibleCachedTracks, hasMore: moreCachedTracks, showMore: showMoreCachedTracks } = useProgressiveTracks(orderedCachedTracks, [libraryQuery, libraryOrder, () => store.cachedTracks])
const { visible: visibleCollectionTracks, hasMore: moreCollectionTracks, showMore: showMoreCollectionTracks } = useProgressiveTracks(computed(() => store.tracks), pageKey)
const { visible: visibleSearchResults, hasMore: moreSearchResults, showMore: showMoreSearchResults } = useProgressiveTracks(computed(() => store.searchResults), [() => store.searchCategory, () => store.searchKeywords])
async function loadMoreSearchResults() {
  if (moreSearchResults.value) { showMoreSearchResults(); return }
  await store.loadMoreSearch()
  if (moreSearchResults.value) showMoreSearchResults()
}


onMounted(async () => {
  await initializeLibraryTasks()
  void initializeAppUpdates()
  document.addEventListener('visibilitychange', onVisibilityChange)
  if (Capacitor.isNativePlatform()) {
    backButtonListener = await CapacitorApp.addListener('backButton', () => {
      if (!handleBack()) void CapacitorApp.minimizeApp()
    })
  }
  await store.restore()
  if (store.hasAccess) {
    if (store.localOnly) libraryMode.value = !store.localTracks.length && store.cachedTracks.length ? 'offline' : 'local'
    store.startPlaybackPolling()
    startHeroRotation()
  }

})
onUnmounted(() => {
  disposeLibraryTasks()
  disposeAppUpdates()
  void store.flushLiveSettings()
  document.removeEventListener('visibilitychange', onVisibilityChange)
  if (noticeTimer !== undefined) clearTimeout(noticeTimer)
  store.stopPlaybackPolling()
  stopHeroRotation()
  stopQrPolling()
  void store.cancelQrLogin()
  void backButtonListener?.remove()
  backButtonListener = undefined
})

watch(() => store.loginVisible, visible => {
  if (!visible) stopQrPolling()
})
watch(() => store.localOnly, enabled => {
  if (enabled) { libraryMode.value = 'local'; stopHeroRotation() }
  else libraryMode.value = 'playlists'
})
watch(() => store.queueNotice, text => {
  if (noticeTimer !== undefined) clearTimeout(noticeTimer)
  if (text) noticeTimer = setTimeout(() => { store.queueNotice = '' }, 2200)
})

watch(() => store.activeTab, tab => {
  if (tab === 'home' && store.onlineAvailable) {
    if (!store.homeData.banners.length && !store.homeData.playlists.length) void store.loadHomeData()
    startHeroRotation()
  } else stopHeroRotation()
})

watch(() => store.homeData.banners, () => { heroIndex.value = 0; startHeroRotation() })

function restorePageScroll() {
  window.scrollTo({ top: store.navigationScrollY, behavior: 'instant' as ScrollBehavior })
}

function onVisibilityChange() {
  if (document.visibilityState === 'hidden') { void store.flushLiveSettings(); store.stopPlaybackPolling() }
  refreshOverlaySettings()
  if (document.visibilityState === 'visible' && store.hasAccess) {
    void store.loadLocalMusic()
    store.startPlaybackPolling()
  }
  if (document.visibilityState === 'visible' && store.activeTab === 'home') startHeroRotation()
  else stopHeroRotation()
}

function startHeroRotation() {
  stopHeroRotation()
  if (!store.onlineAvailable) return
  if (document.visibilityState !== 'visible' || store.activeTab !== 'home' || store.homeData.banners.length < 2) return
  heroTimer = setInterval(() => moveHero(1), 8000)
}

function stopHeroRotation() {
  if (heroTimer !== undefined) clearInterval(heroTimer)
  heroTimer = undefined
}

function stopQrPolling() {
  if (qrPollTimer !== undefined) clearTimeout(qrPollTimer)
  qrPollTimer = undefined
}

function scheduleQrPoll(delay = 2200) {
  stopQrPolling()
  if (!store.loginVisible || !store.qrLoginKey) return
  qrPollTimer = setTimeout(() => { void pollQrLogin() }, delay)
}

async function pollQrLogin() {
  if (qrPollPending || !store.loginVisible || !store.qrLoginKey) return
  qrPollPending = true
  try { await store.checkQrLogin() }
  finally {
    qrPollPending = false
    scheduleQrPoll()
  }
}

async function refreshQrLogin() {
  stopQrPolling()
  await store.createQrLogin()
  if (store.qrLoginKey) scheduleQrPoll(500)
}

async function enterLocalMode() {
  stopQrPolling()
  await store.enterLocalOnly()
  if (store.localOnly) libraryMode.value = !store.localTracks.length && store.cachedTracks.length ? 'offline' : 'local'
}

function requestOnlineLogin() {
  sidebarOpen.value = false
  store.requestLogin()
  if (Capacitor.isNativePlatform()) void refreshQrLogin()
}

function moveHero(direction: -1 | 1) {
  const length = store.homeData.banners.length
  if (length < 2) return
  heroIndex.value = (heroIndex.value + direction + length) % length
}

function heroAt(offset: -1 | 0 | 1) {
  const banners = store.homeData.banners
  if (!banners.length) return undefined
  return banners[(heroIndex.value + offset + banners.length) % banners.length]
}

function heroPosition(index: number) {
  const length = store.homeData.banners.length
  const delta = (index - heroIndex.value + length) % length
  return delta === 0 ? 'hero-center' : delta === 1 ? 'hero-right' : delta === length - 1 ? 'hero-left' : 'hero-hidden'
}
let heroTouchStart = 0
function endHeroSwipe(event: TouchEvent) {
  const distance = (event.changedTouches[0]?.clientX ?? heroTouchStart) - heroTouchStart
  if (Math.abs(distance) >= 35) { moveHero(distance < 0 ? 1 : -1); startHeroRotation() }
}
function clickHero(index: number) {
  if (index === heroIndex.value) activateHero(0)
  else { heroIndex.value = index; startHeroRotation() }
}

function activateHero(offset: -1 | 0 | 1) {
  if (offset !== 0) { moveHero(offset); return }
  const banner = heroAt(0)
  if (!banner) return
  if (banner.type === 'track') void store.play({ id: banner.id, name: banner.typeTitle || '推荐歌曲', picUrl: banner.picUrl })
  else if (banner.type === 'playlist') void store.openPlaylist({ id: banner.id, name: banner.typeTitle || '推荐歌单', coverImgUrl: banner.picUrl })
  else if (banner.type === 'album') void store.openEntityTracks('albums', banner.id, banner.typeTitle || '推荐专辑', banner.picUrl)
  else if (banner.type === 'mv') void store.openMv(banner.id)
  else if (banner.url) window.open(banner.url, '_blank', 'noopener,noreferrer')
}

function handleBack(): boolean {
  if (actionDialog.value) { settleAction('cancel'); return true }
  if (store.onlinePlaylistDialog) { store.closeOnlinePlaylistDialog(); return true }
  if (store.loginVisible && store.hasAccess) { void enterLocalMode(); return true }
  if (trackAction.value) { closeTrackMenu(); return true }
  if (store.queueOpen) { store.queueOpen = false; return true }
  if (sidebarOpen.value) { sidebarOpen.value = false; return true }
  const openDetails = Array.from(document.querySelectorAll<HTMLDetailsElement>('details[open]:not([data-closing])')).at(-1)
  if (openDetails) {
    if (openDetails.classList.contains('settings-collapse')) openDetails.dispatchEvent(new Event('vutron-settings-close'))
    else openDetails.open = false
    return true
  }
  if (!store.localOnly && store.activeTab === 'library' && !store.selectedPlaylist && libraryMode.value !== 'playlists') {
    libraryMode.value = 'playlists'; query.value = ''; return true
  }
  return store.navigateBack()
}
function openLibrarySection(section: 'playlists' | 'local' | 'offline') {
  if (store.localOnly && section === 'playlists') return
  libraryMode.value = section
  store.navigateTo('library')
  sidebarOpen.value = false
}
function openSidebarCollection(collection: typeof store.localLibrary.folders[number]) {
  sidebarOpen.value = false
  if (collection.permissionGranted === false) {
    store.queueNotice = t('mobile.library.reauthorizeHint')
    void store.pickMusicFolder()
    return
  }
  void store.openLocalCollection(collection)
}
async function pickFolderFromSidebar() { sidebarOpen.value = false; await store.pickMusicFolder(); openLibrarySection('local') }
async function importFromSidebar() { sidebarOpen.value = false; await store.importLocalPlaylist() }

function submitSearch() {
  const keywords = store.searchKeywords.trim()
  if (!keywords) return
  store.navigateTo('search')
  void store.runSearch(store.searchCategory, keywords, true)
}

function changeSearchCategory(category: typeof searchCategories[number]['key']) {
  store.searchCategory = category
  if (store.searchKeywords.trim()) void store.runSearch(category, store.searchKeywords.trim(), true)
}

function openSearchEntity(item: { id?: number; name?: string; picUrl?: string }) {
  if (!item.id || !['albums', 'artists'].includes(store.searchCategory)) return
  void store.openEntityTracks(store.searchCategory as 'albums' | 'artists', item.id, item.name || '', item.picUrl)
}

function coverOf(item: { coverLocalUri?: string; coverImgUrl?: string; picLocalUri?: string; picUrl?: string }): string {
  return item.coverLocalUri || item.picLocalUri || item.coverImgUrl || item.picUrl || ''
}

function refreshOverlaySettings() {
  if (document.visibilityState === 'visible' && store.hasAccess) void store.loadOverlaySettings()
}

function formatTime(milliseconds: number): string {
  const totalSeconds = Math.floor(Math.max(0, milliseconds) / 1000)
  return `${Math.floor(totalSeconds / 60)}:${String(totalSeconds % 60).padStart(2, '0')}`
}

function playAdjacent(direction: -1 | 1) {
  void store.playAdjacent(direction)
}

function seekFromInput(event: Event) {
  const input = event.target as HTMLInputElement
  seeking.value = false
  void store.seek(Number(input.value))
}
function previewSeek(event: Event) {
  seeking.value = true
  seekValue.value = Number((event.target as HTMLInputElement).value)
}



</script>

<template>
  <main class="app-shell" :class="`playlist-layout-${playlistView}`" :style="{ '--playlist-columns': playlistColumns }">
    <header class="app-header">
      <button v-if="store.hasAccess && !store.loginVisible" class="icon-button sidebar-toggle" type="button" :aria-label="$t('mobile.library.openSidebar')" :aria-expanded="sidebarOpen" @click="sidebarOpen = !sidebarOpen"><OriginalIcon name="list" /></button>
      <button v-if="store.canNavigateBack" class="icon-button header-back" type="button" :aria-label="$t('mobile.common.back')" @click="handleBack"><OriginalIcon name="back" /></button>
      <div v-if="!store.hasAccess" class="brand-mark original-brand"><img :src="originalBrandLogo" :alt="buildInfo.displayName" /></div>
      <div class="brand-copy">
        <h1>{{ buildInfo.displayName }}</h1>
        <p>{{ store.localOnly ? $t('mobile.common.localOnly') : store.profile?.nickname || $t('mobile.common.mobilePlayer') }}</p>
      </div>
      <button v-if="headerRefreshLabel" class="icon-button header-refresh" :class="{ 'is-refreshing': headerRefreshing }" type="button" :aria-label="headerRefreshing ? $t('mobile.home.refreshing') : headerRefreshLabel" :title="headerRefreshLabel" :aria-busy="headerRefreshing" :disabled="headerRefreshing" @click="refreshCurrentView"><OriginalIcon name="refresh" /></button>
    </header>

    <section v-if="store.loginVisible" class="login-view">
      <div class="login-art original-login-art"><img :src="originalBrandLogo" :alt="buildInfo.displayName" /></div>
      <div class="eyebrow">YOUR MUSIC, YOUR WAY</div>
      <h2>{{ $t('mobile.login.connect') }}</h2>
      <p class="muted">{{ $t('mobile.login.description') }}</p>
      <div v-if="Capacitor.isNativePlatform()" class="qr-login-card">
        <div class="qr-image-frame">
          <img v-if="store.qrLoginImage" :src="store.qrLoginImage" :alt="$t('mobile.login.qrAlt')" />
          <div v-else class="qr-image-placeholder">{{ store.qrLoginLoading ? '正在生成二维码…' : '二维码暂不可用' }}</div>
        </div>
        <p class="qr-login-status" role="status">{{ store.qrLoginStatus || '点击获取二维码，或选择仅本地模式' }}</p>
        <button class="primary-button full-button" type="button" :disabled="store.qrLoginLoading" @click="refreshQrLogin">
          {{ store.qrLoginImage ? $t('mobile.login.qrRefresh') : '获取二维码' }}
        </button>
        <p class="qr-login-hint">{{ $t('mobile.login.qrTip') }}</p>
      </div>
      <button class="secondary-button full-button" type="button" :disabled="store.localModeLoading" @click="enterLocalMode()">{{ store.localModeLoading ? '正在读取本地音乐…' : '仅本地模式（无需登录）' }}</button>
      <p class="setting-note">{{ $t('mobile.login.localHint') }}</p>
      <CollapsibleSettingsCard class="cookie-login-details" section-id="cookie-login" :title="$t('mobile.login.cookieFallback')" :remember="false">
        <form class="login-form" @submit.prevent="store.login(cookie)">
          <label for="cookie-input">{{ $t('mobile.login.cookieLabel') }}</label>
          <textarea id="cookie-input" v-model="cookie" autocomplete="off" spellcheck="false" :placeholder="$t('mobile.login.cookiePlaceholder')" rows="4" />
          <button class="primary-button full-button" type="submit" :disabled="store.loading">
            {{ store.loading ? $t('mobile.login.connecting') : $t('mobile.login.loginLoad') }}
          </button>
        </form>
      </CollapsibleSettingsCard>
      <p v-if="store.error" class="inline-error login-error" role="alert">{{ store.error }}</p>
    </section>

    <template v-else>
      <PlaybackQueuePanel />
      <Teleport to="body"><Transition name="track-menu"><TrackActionMenu v-if="trackAction" :track="trackAction" @close="closeTrackMenu" /></Transition></Teleport>
      <Transition name="sidebar">
        <div v-if="sidebarOpen" class="sidebar-layer" @click.self="sidebarOpen = false">
          <aside class="library-sidebar" role="dialog" aria-modal="true" :aria-label="$t('mobile.library.sidebar')">
            <div class="sidebar-heading"><img :src="originalBrandLogo" alt="" /><b>{{ buildInfo.displayName }}</b><button type="button" class="icon-button" :aria-label="$t('mobile.library.closeSidebar')" @click="sidebarOpen = false"><OriginalIcon name="close" /></button></div>
            <button type="button" v-if="store.onlineAvailable" @click="openLibrarySection('playlists')"><OriginalIcon name="library" />{{ $t('mobile.library.netease') }}</button>
            <button v-if="store.onlineAvailable" type="button" :disabled="store.onlineMutationBusy" @click="sidebarOpen = false; store.openCreateOnlinePlaylist()"><OriginalIcon name="plus" />{{ $t('mobile.netease.create') }}</button>
            <button type="button" @click="pickFolderFromSidebar"><OriginalIcon name="folder" />{{ $t('mobile.library.chooseLocalFolder') }}</button>
            <button type="button" :disabled="store.libraryLoading" @click="importFromSidebar"><OriginalIcon name="down" />{{ $t('mobile.library.importDesktop') }}</button>
            <button type="button" @click="openLibrarySection('local')"><OriginalIcon name="local" />{{ $t('mobile.library.allLocal') }}</button>
            <button type="button" @click="openLibrarySection('offline')"><OriginalIcon name="down" />{{ $t('mobile.library.cache') }}</button>
            <button type="button" :disabled="store.localScanLoading" @click="store.loadLocalMusic(true)"><OriginalIcon name="refresh" />{{ $t('mobile.library.scan') }}</button>
            <h3 v-if="store.localLibrary.folders.length">{{ $t('mobile.library.myLocal') }}</h3>
            <div v-for="folder in store.localLibrary.folders" :key="folder.id" class="sidebar-managed-row"><button type="button" @click="openSidebarCollection(folder)"><OriginalIcon name="folder" /><span>{{ folder.name }}<small>{{ folder.permissionGranted === false ? $t('mobile.library.reauthorize') : $t('mobile.common.tracks', { count: folder.trackCount }) }}</small></span></button><button class="icon-button collection-delete" type="button" :aria-label="$t('mobile.management.forgetFolder') + ' ' + folder.name" @click="libraryManagement.removeCollection(folder, 'forgetFolder')"><OriginalIcon name="close" /></button></div>
            <h3 v-if="store.localLibrary.playlists.length">{{ $t('mobile.library.imported') }}</h3>
            <div v-for="playlist in store.localLibrary.playlists" :key="playlist.id" class="sidebar-managed-row"><button type="button" @click="openSidebarCollection(playlist)"><OriginalIcon name="library" /><span>{{ playlist.name }}<small>{{ $t('mobile.common.tracks', { count: playlist.trackCount }) }}</small></span></button><button class="icon-button collection-delete" type="button" :aria-label="$t('mobile.management.deletePlaylist') + ' ' + playlist.name" @click="libraryManagement.removeCollection(playlist, 'deletePlaylist')"><OriginalIcon name="close" /></button></div>
          </aside>
        </div>
      </Transition>
      <Transition :name="transitionName" mode="out-in" @after-enter="restorePageScroll">
      <div :key="pageKey" class="page-stage">
      <section v-if="store.activeTab === 'home'" class="page home-page">
        <div class="home-greeting"><div><div class="eyebrow">VUTRON MUSIC</div><h2>{{ $t('mobile.home.forYou') }}</h2></div><button class="home-search-shortcut" type="button" aria-label="搜索音乐" @click="store.navigateTo('search')"><OriginalIcon name="search" /></button></div>
        <div v-if="store.homeData.banners.length" class="hero-carousel" aria-label="推荐轮播" @touchstart.passive="heroTouchStart = $event.touches[0]?.clientX ?? 0" @touchend.passive="endHeroSwipe">
          <button v-for="(banner, index) in store.homeData.banners" :key="`${banner.id}-${index}`" class="hero-card" :class="heroPosition(index)" :tabindex="heroPosition(index) === 'hero-hidden' ? -1 : 0" :aria-hidden="heroPosition(index) === 'hero-hidden'" type="button" @click="clickHero(index)">
            <CoverImage :src="banner.picLocalUri || banner.picUrl" :remote-src="banner.picUrl" loading="eager" alt="" /><span class="hero-shade"></span><span class="hero-label">{{ banner.typeTitle || $t('mobile.home.daily') }}</span>
          </button>
          <div class="hero-dots"><button v-for="(banner, index) in store.homeData.banners" :key="`${banner.id}-${index}`" type="button" :class="{ active: index === heroIndex }" :aria-label="`第 ${index + 1} 张推荐`" @click="heroIndex = index; startHeroRotation()"></button></div>
        </div>
        <div v-else class="hero-placeholder"><span class="eyebrow">YOUR MUSIC, YOUR WAY</span><b>{{ $t('mobile.home.discover') }}</b><button class="text-button" type="button" :disabled="store.homeLoading" @click="refreshCurrentView">{{ store.homeLoading ? '正在载入…' : '刷新推荐' }}</button></div>

        <section v-if="store.homeData.playlists.length" class="home-section">
          <div class="home-section-title"><div><span class="eyebrow">MADE FOR YOU</span><h3>{{ $t('home.recommendPlaylist') }}</h3></div><button class="text-button" type="button" @click="store.navigateTo('library')">{{ $t('mobile.common.more') }}</button></div>
          <div class="horizontal-cards"><button v-for="item in store.homeData.playlists" :key="item.id" class="home-cover-card" type="button" @click="store.openPlaylist(item)"><span class="home-cover"><CoverImage v-if="coverOf(item)" :src="coverOf(item)" :remote-src="item.coverImgUrl || item.picUrl" alt="" loading="lazy" /><span v-else><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span></span><b>{{ item.name }}</b><small>{{ item.copywriter || `${item.trackCount ?? 0} 首歌曲` }}</small></button></div>
        </section>
        <section v-if="store.homeData.dailyTracks.length" class="home-section">
          <div class="home-section-title"><div><span class="eyebrow">DAILY MIX</span><h3>{{ $t('mobile.home.daily') }}</h3></div></div>
          <div class="home-track-strip"><div v-for="track in store.homeData.dailyTracks.slice(0, 8)" :key="track.id" class="home-track-card" role="button" tabindex="0" v-track-menu="() => openTrackMenu(track)" @click="store.play(track)" @keydown.enter.self.prevent="store.play(track)" @keydown.space.self.prevent="store.play(track)"><CoverImage v-if="coverOf(track)" :src="coverOf(track)" :remote-src="track.picUrl" alt="" loading="lazy" /><span v-else class="home-track-fallback"><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span><span><b>{{ track.name }}</b><small>{{ track.artists?.join(' / ') || track.album || '网易云音乐' }}</small></span><TrackMenuButton :name="track.name" @open="openTrackMenu(track)" /></div></div>
        </section>
        <section v-if="store.homeData.artists.length" class="home-section"><div class="home-section-title"><div><span class="eyebrow">ARTISTS</span><h3>推荐歌手</h3></div></div><div class="horizontal-cards artist-cards"><button v-for="item in store.homeData.artists.slice(0, 12)" :key="item.id" class="home-cover-card artist-card" type="button" @click="store.openEntityTracks('artists', item.id, item.name, item.picUrl)"><span class="home-cover artist-cover"><CoverImage v-if="coverOf(item)" :src="coverOf(item)" :remote-src="item.coverImgUrl || item.picUrl" alt="" loading="lazy" /><span v-else><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span></span><b>{{ item.name }}</b></button></div></section>
        <section v-if="store.homeData.albums.length" class="home-section"><div class="home-section-title"><div><span class="eyebrow">NEW RELEASES</span><h3>{{ $t('nav.newAlbum') }}</h3></div></div><div class="horizontal-cards"><button v-for="item in store.homeData.albums" :key="item.id" class="home-cover-card" type="button" @click="store.openEntityTracks('albums', item.id, item.name, item.picUrl)"><span class="home-cover"><CoverImage v-if="coverOf(item)" :src="coverOf(item)" :remote-src="item.coverImgUrl || item.picUrl" alt="" loading="lazy" /><span v-else><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span></span><b>{{ item.name }}</b><small>{{ item.artists?.map(artist => artist.name).join(' / ') || '新发行' }}</small></button></div></section>
        <section v-if="store.homeData.charts.length" class="home-section home-bottom-section"><div class="home-section-title"><div><span class="eyebrow">TOP CHARTS</span><h3>{{ $t('nav.chart') }}</h3></div></div><div class="horizontal-cards"><button v-for="item in store.homeData.charts" :key="item.id" class="home-cover-card" type="button" @click="store.openPlaylist(item)"><span class="home-cover"><CoverImage v-if="coverOf(item)" :src="coverOf(item)" :remote-src="item.coverImgUrl || item.picUrl" alt="" loading="lazy" /><span v-else><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span></span><b>{{ item.name }}</b><small>{{ item.copywriter || '网易云音乐榜单' }}</small></button></div></section>
        <div v-if="store.homeLoading" class="home-loading"><i></i><i></i><i></i><span>{{ $t('mobile.home.updating') }}</span></div>
      </section>

      <section v-else-if="store.activeTab === 'search'" class="page search-page">
        <div class="section-heading"><div><div class="eyebrow">DISCOVER</div><h2>搜索音乐</h2></div></div>
        <form class="remote-search-box" @submit.prevent="submitSearch"><span><OriginalIcon name="search" /></span><input v-model="store.searchKeywords" type="search" autocomplete="off" placeholder="歌曲、歌手、专辑或歌单" /><button type="submit" :disabled="store.searchLoading || !store.searchKeywords.trim()">{{ $t('nav.search') }}</button></form>
        <div class="search-categories" role="tablist" aria-label="搜索分类"><button v-for="category in searchCategories" :key="category.key" type="button" role="tab" :aria-selected="store.searchCategory === category.key" :class="{ selected: store.searchCategory === category.key }" @click="changeSearchCategory(category.key)">{{ category.label }}</button></div>
        <div class="search-result-heading" v-if="store.searchKeywords"><b>“{{ store.searchKeywords }}”</b><small>{{ store.searchCount ? `${store.searchCount} 条结果` : (store.searchLoading ? '正在搜索…' : '搜索结果') }}</small></div>
        <Transition name="content-switch" mode="out-in">
        <div :key="store.searchCategory" class="content-switch-stage">
        <LibraryLayoutControls v-if="store.searchCategory === 'playlists'" />
        <div v-if="store.searchCategory === 'tracks'" class="track-list search-track-list"><div v-for="(track, index) in visibleSearchResults" :key="track.id" class="track-row" role="button" tabindex="0" v-track-menu="() => openTrackMenu(track)" @click="store.play(track)" @keydown.enter.self.prevent="store.play(track)" @keydown.space.self.prevent="store.play(track)"><span class="track-index">{{ String(index + 1).padStart(2, '0') }}</span><span class="track-copy"><b>{{ track.name }}</b><small>{{ track.artists?.join(' / ') || track.album || '网易云音乐' }}</small></span><TrackMenuButton :name="track.name" @open="openTrackMenu(track)" /></div></div>
        <div v-else-if="store.searchCategory === 'playlists'" class="playlist-grid search-playlist-grid"><button v-for="item in visibleSearchResults" :key="item.id" class="playlist-card" type="button" @click="store.openPlaylist(item)"><span class="cover-wrap"><CoverImage v-if="coverOf(item)" :src="coverOf(item)" :remote-src="item.coverImgUrl || item.picUrl" alt="" loading="lazy" /><span v-else class="cover-fallback"><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span><span class="cover-play"><OriginalIcon name="play" /></span></span><span class="playlist-copy"><b>{{ item.name }}</b><small>{{ item.trackCount ?? 0 }} 首歌曲</small></span></button></div>
        <div v-else class="search-entity-list"><button v-for="item in visibleSearchResults" :key="item.id" class="search-entity-row" type="button" @click="store.searchCategory === 'mvs' ? store.openMv(item.id) : openSearchEntity(item)"><CoverImage v-if="coverOf(item)" :src="coverOf(item)" :remote-src="item.coverImgUrl || item.picUrl" alt="" loading="lazy" /><span v-else class="entity-fallback"><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span><span class="entity-copy"><b>{{ item.name }}</b><small>{{ item.artists?.map(artist => artist.name).join(' / ') || (store.searchCategory === 'mvs' ? '音乐视频' : '网易云音乐') }}</small></span><span class="entity-type">{{ searchCategories.find(category => category.key === store.searchCategory)?.label }}</span></button></div>
        <div v-if="store.searchLoading && !store.searchResults.length" class="empty-state">正在搜索…</div><div v-else-if="store.searchKeywords && !store.searchLoading && !store.searchResults.length" class="empty-state">没有找到相关内容</div>
        <button v-if="store.searchNextPage || moreSearchResults" class="load-more-button" type="button" :disabled="store.searchLoading" @click="loadMoreSearchResults()">{{ store.searchLoading ? '正在载入…' : '加载更多' }}</button><div v-else-if="store.searchResults.length" class="search-end">已经到底了</div>
        </div>
        </Transition>
      </section>

      <section v-else-if="store.activeTab === 'localCollection'" class="page library-page">
        <div class="section-heading"><div><div class="eyebrow">LOCAL MUSIC</div><h2>{{ store.selectedLocalCollection?.name }}</h2></div><button class="text-button" type="button" @click="handleBack">{{ $t('mobile.common.back') }}</button></div>
        <LocalTrackList :tracks="store.tracks" :collection-key="store.selectedLocalCollection?.id || 'local:folder'" :loading="store.loading" :local-only="store.localOnly" @play="(track, queue) => store.play(track, store.selectedLocalCollection?.id, queue)" @menu="track => openTrackMenu(track, store.selectedLocalCollection?.id)" @order-change="keys => store.reorderLocalPlayback(store.selectedLocalCollection?.id || 'local:folder', keys)" />
      </section>
      <section v-else-if="store.activeTab === 'entity'" class="page library-page">
        <div class="section-heading"><div><div class="eyebrow">{{ store.selectedEntity?.category === 'artists' ? 'ARTIST' : 'ALBUM' }}</div><h2>{{ store.selectedEntity?.name }}</h2></div><button class="text-button" type="button" @click="handleBack">{{ $t('mobile.common.back') }}</button></div>
        <div class="playlist-hero"><CoverImage v-if="store.selectedEntity?.picUrl" :src="store.selectedEntity.picUrl" alt="" /><span v-else class="cover-fallback"><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span><div><div class="eyebrow">{{ store.selectedEntity?.category === 'artists' ? $t('nav.artist') : $t('nav.album') }}</div><h3>{{ store.selectedEntity?.name }}</h3><p>{{ store.tracks.length }} 首歌曲</p></div></div>
        <div class="track-list playlist-tracks"><div v-for="(track, index) in visibleCollectionTracks" :key="track.id" class="track-row" role="button" tabindex="0" v-track-menu="() => openTrackMenu(track)" @click="store.play(track)" @keydown.enter.self.prevent="store.play(track)" @keydown.space.self.prevent="store.play(track)"><span class="track-index">{{ String(index + 1).padStart(2, '0') }}</span><span class="track-copy"><b>{{ track.name }}</b><small>{{ track.artists?.join(' / ') || track.album }}</small></span><TrackMenuButton :name="track.name" @open="openTrackMenu(track)" /></div><div v-if="store.loading" class="empty-state">正在读取歌曲…</div>
<TrackListPager :more="moreCollectionTracks" @more="showMoreCollectionTracks" />
</div>
      </section>

      <section v-else-if="store.activeTab === 'library'" class="page library-page">
        <div class="section-heading">
          <div><div class="eyebrow">YOUR LIBRARY</div><h2>{{ store.selectedPlaylist?.name ?? $t('nav.library') }}</h2></div>
          <button v-if="store.selectedPlaylist" type="button" class="text-button" @click="handleBack">{{ $t('mobile.common.back') }}</button>
        </div>

        <template v-if="!store.selectedPlaylist">
          <div class="library-shortcuts">
            <button v-if="store.onlineAvailable" class="shortcut-card favorite-shortcut" type="button" @click="libraryMode = 'playlists'">
              <span class="shortcut-icon"><OriginalIcon name="library" /></span><span><b>{{ $t('mobile.library.myPlaylists') }}</b><small>{{ store.playlists.length }} 个歌单</small></span>
            </button>
            <button class="shortcut-card local-shortcut" type="button" @click="libraryMode = 'local'">
              <span class="shortcut-icon"><OriginalIcon name="local" /></span><span><b>{{ $t('nav.localMusic') }}</b><small>{{ store.localTracks.length }} 首</small></span>
            </button>
          </div>
          <div class="segmented-control" role="tablist">
            <button v-if="store.onlineAvailable" type="button" :class="{ selected: libraryMode === 'playlists' }" @click="libraryMode = 'playlists'">{{ $t('nav.playlist') }}</button>
            <button type="button" :class="{ selected: libraryMode === 'local' }" @click="libraryMode = 'local'">{{ $t('mobile.common.local') }}</button>
            <button type="button" :class="{ selected: libraryMode === 'offline' }" @click="libraryMode = 'offline'">{{ $t('mobile.common.offline') }}</button>
          </div>
          <label class="search-box"><span><OriginalIcon name="search" /></span><input v-model="query" type="search" :placeholder="libraryMode === 'playlists' ? '搜索歌单' : '搜索歌曲'" /></label>
          <p v-if="store.localScanLoading" class="setting-note" role="status">正在后台扫描音乐并更新索引…</p>
          <Transition name="content-switch" mode="out-in">
          <div :key="libraryMode" class="content-switch-stage">
          <LibraryLayoutControls v-if="libraryMode !== 'local'" />

          <template v-if="libraryMode === 'playlists'">
          <div v-if="store.onlineAvailable" class="online-library-tools"><button class="secondary-button" type="button" :disabled="store.onlineMutationBusy" @click="store.openCreateOnlinePlaylist"><OriginalIcon name="plus" />{{ $t('mobile.netease.create') }}</button><button v-if="store.onlineNeedsRefresh" class="text-button" type="button" :disabled="store.onlineMutationBusy" @click="store.refreshOnlineLibrary"><OriginalIcon name="refresh" />{{ $t('mobile.netease.refresh') }}</button></div>
          <div class="playlist-grid">
            <button v-for="playlist in visiblePlaylists" :key="playlist.id" class="playlist-card" type="button" @click="store.openPlaylist(playlist)">
              <span class="cover-wrap">
                <CoverImage v-if="coverOf(playlist)" :src="coverOf(playlist)" :remote-src="playlist.coverImgUrl" alt="" loading="lazy" />
                <span v-else class="cover-fallback"><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span>
                <span class="cover-play"><OriginalIcon name="play" /></span>
              </span>
              <span class="playlist-copy"><b>{{ playlist.name }}</b><small>{{ playlist.trackCount ?? 0 }} 首歌曲</small></span>
            </button>
            <div v-if="!filteredPlaylists.length" class="empty-state">{{ store.playlistsLoading ? '正在载入歌单…' : '这里还没有歌单' }}</div>
          </div>

          <TrackListPager :more="morePlaylists" :label="$t('mobile.library.morePlaylists')" @more="showMorePlaylists" />
          </template>
          <div v-else-if="libraryMode === 'offline'" class="offline-content">

            <div class="playlist-grid">
            <button v-for="playlist in visibleOfflinePlaylists" :key="playlist.id" class="playlist-card" type="button" @click="store.openCachedPlaylist(playlist)">
              <span class="cover-wrap"><CoverImage v-if="coverOf(playlist)" :src="coverOf(playlist)" :remote-src="playlist.coverImgUrl" alt="" loading="lazy" /><span v-else class="cover-fallback"><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span><span class="cover-play"><OriginalIcon name="play" /></span></span>
              <span class="playlist-copy"><b>{{ playlist.name }}</b><small>{{ playlist.trackCount ?? 0 }} 首 · 离线歌单</small></span>
            </button>
            <div v-if="!store.cachedPlaylists.length" class="empty-state">打开过的歌单会出现在这里</div>
            </div>
            <TrackListPager :more="moreOfflinePlaylists" :label="$t('mobile.library.morePlaylists')" @more="showMoreOfflinePlaylists" />
            <h3 class="offline-track-heading">已缓存歌曲 · {{ store.cachedTracks.length }} 首</h3>
            <LocalLibrarySort v-model="libraryOrder" />
            <div class="track-list">
              <div v-for="(track, index) in visibleCachedTracks" :key="`${track.id}-${index}`" class="track-row" role="button" tabindex="0" v-track-menu="() => openTrackMenu(track, 'cache:all')" @click="store.play(track, 'cache:all', orderedCachedTracks)" @keydown.enter.self.prevent="store.play(track, 'cache:all', orderedCachedTracks)" @keydown.space.self.prevent="store.play(track, 'cache:all', orderedCachedTracks)">
                <span class="track-index">{{ String(index + 1).padStart(2, '0') }}</span>
                <span class="track-copy"><b>{{ track.name }}</b><small>{{ track.artists?.join(' / ') || track.album }}</small></span>
                <span class="offline-badge">{{ $t('mobile.common.offline') }}</span><TrackMenuButton :name="track.name" @open="openTrackMenu(track, 'cache:all')" />
              </div>
              <div v-if="!visibleCachedTracks.length" class="empty-state">还没有离线缓存歌曲</div>

<TrackListPager :more="moreCachedTracks" @more="showMoreCachedTracks" />
</div>
          </div>

          <div v-else class="track-list">
            <div class="inline-info"><span>{{ store.folderName }}</span><button type="button" class="text-button" @click="store.pickMusicFolder">{{ $t('mobile.library.chooseFolder') }}</button></div>
            <LocalTrackList :tracks="store.localTracks" collection-key="local:folder" :query="libraryQuery" :loading="store.localScanLoading" :local-only="store.localOnly" @play="(track, queue) => store.play(track, 'local:folder', queue)" @menu="track => openTrackMenu(track, 'local:folder')" @order-change="keys => store.reorderLocalPlayback('local:folder', keys)" />
          </div>
          </div>
          </Transition>
        </template>
        <template v-else>
          <div class="playlist-hero">
            <CoverImage v-if="coverOf(store.selectedPlaylist)" :src="coverOf(store.selectedPlaylist)" :remote-src="store.selectedPlaylist.coverImgUrl" alt="" />
            <span v-else class="cover-fallback"><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span>
            <div><div class="eyebrow">PLAYLIST</div><h3>{{ store.selectedPlaylist.name }}</h3><p>{{ store.tracks.length }} 首歌曲</p></div>
            <button v-if="store.canManageOnlinePlaylist(store.selectedPlaylist)" class="icon-button" type="button" :disabled="store.onlineMutationBusy" :aria-label="$t('mobile.netease.more')" @click="store.openOnlinePlaylistMore(store.selectedPlaylist)"><OriginalIcon name="more" /></button>
          </div>
          <button class="play-all-button" type="button" :disabled="!store.tracks.length" @click="store.tracks[0] && store.play(store.tracks[0])"><span><OriginalIcon name="play" /></span> 播放全部</button>
          <div class="track-list playlist-tracks">
            <div v-for="(track, index) in visibleCollectionTracks" :key="track.id" class="track-row" role="button" tabindex="0" v-track-menu="() => openTrackMenu(track)" @click="store.play(track)" @keydown.enter.self.prevent="store.play(track)" @keydown.space.self.prevent="store.play(track)">
              <span class="track-index">{{ String(index + 1).padStart(2, '0') }}</span>
              <span class="track-copy"><b>{{ track.name }}</b><small>{{ track.artists?.join(' / ') || track.album }}</small></span>
              <TrackMenuButton :name="track.name" @open="openTrackMenu(track)" />
            </div>
            <div v-if="store.loading" class="empty-state">正在读取歌曲…</div>

<TrackListPager :more="moreCollectionTracks" @more="showMoreCollectionTracks" />
</div>
        </template>
      </section>

      <section v-else-if="store.activeTab === 'player'" class="page player-page">
        <div class="player-overline"><span>NOW PLAYING</span><span class="quality-chip">{{ store.playback.quality || '待播放' }}</span></div>
        <div class="player-artwork">
          <CoverImage v-if="currentCover" :src="currentCover" :remote-src="currentTrack.picUrl" loading="eager" alt="歌曲封面" />
          <div v-else class="cover-fallback large-cover"><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></div>
          <span class="artwork-glow"></span>
        </div>
        <div class="player-title-row">
          <div class="player-title"><h2>{{ currentTrack.name }}</h2><p>{{ currentTrack.artists?.join(' / ') || store.playback.artist || buildInfo.displayName }}</p></div>
        </div>
        <div class="progress-control">
          <input type="range" min="0" :max="Math.max(1, store.playback.duration)" :value="seeking ? seekValue : store.playback.position" :disabled="!store.playback.duration" @input="previewSeek" @change="seekFromInput" />
          <div><span>{{ formatTime(seeking ? seekValue : store.playback.position) }}</span><span>{{ formatTime(store.playback.duration) }}</span></div>
        </div>
        <div class="transport-controls">
          <button class="icon-button transport-small" type="button" :aria-label="`播放模式：${[$t('mobile.player.listLoop'), $t('mobile.player.singleLoop'), $t('mobile.player.shuffle')][store.playback.mode ?? 0]}`" @click="store.cyclePlaybackMode()"><OriginalIcon :name="store.playback.mode === 1 ? 'repeatOne' : store.playback.mode === 2 ? 'shuffle' : 'repeat'" /></button>
          <button class="icon-button transport-small" type="button" :aria-label="$t('player.previous')" @click="playAdjacent(-1)"><OriginalIcon name="previous" /></button>
          <button class="main-play-button" type="button" :aria-label="store.playback.playing ? $t('player.pause') : $t('contextMenu.play')" @click="store.playback.playing ? store.pause() : store.resume()">
            <OriginalIcon :name="store.playback.playing ? 'pause' : 'play'" />
          </button>
          <button class="icon-button transport-small" type="button" :aria-label="$t('player.next')" @click="playAdjacent(1)"><OriginalIcon name="next" /></button>
          <button class="icon-button cache-current" type="button" aria-label="缓存当前歌曲" @click="store.cacheCurrent"><OriginalIcon name="down" /></button>
        </div>
        <p v-if="store.playback.error" class="inline-error">{{ store.playback.error }}</p>
        <button class="wide-outline-button playback-queue-button" type="button" @click="store.showPlaybackQueue()"><OriginalIcon name="list" />播放队列 · {{ store.playback.queueCount ?? 0 }} 首{{ store.playback.upNextCount ? ` · 插队 ${store.playback.upNextCount}` : '' }}</button>
        <CollapsibleSettingsCard class="volume-settings" section-id="playback-volume" title="单曲与歌单音量">
          <template #heading><div><h3>单曲与歌单音量</h3><p>实际比例 {{ Math.round((store.playback.trackVolumePercent ?? 100) * (store.playback.playlistVolumePercent ?? 100) / 100) }}%</p></div></template>
          <div class="volume-control">
            <label for="track-volume">当前歌曲</label>
            <input id="track-volume" type="range" min="0" max="100" :value="store.playback.trackVolumePercent ?? 100" :disabled="!store.playback.trackId" @input="store.setCurrentTrackVolume(Number(($event.target as HTMLInputElement).value))" @change="store.flushLiveSettings()" />
            <label class="percent-input"><input type="number" min="0" max="100" inputmode="numeric" aria-label="单曲音量百分比" :value="store.playback.trackVolumePercent ?? 100" :disabled="!store.playback.trackId" @change="store.setCurrentTrackVolume(Number(($event.target as HTMLInputElement).value))" /><span>%</span></label>
          </div>
          <div class="volume-control">
            <label for="playlist-volume">{{ store.playback.track?.playlistVolumeName || '当前歌单' }}</label>
            <input id="playlist-volume" type="range" min="0" max="100" :value="store.playback.playlistVolumePercent ?? 100" :disabled="!store.playback.track?.playlistVolumeKey" @input="store.setCurrentPlaylistVolume(Number(($event.target as HTMLInputElement).value))" @change="store.flushLiveSettings()" />
            <label class="percent-input"><input type="number" min="0" max="100" inputmode="numeric" aria-label="歌单音量百分比" :value="store.playback.playlistVolumePercent ?? 100" :disabled="!store.playback.track?.playlistVolumeKey" @change="store.setCurrentPlaylistVolume(Number(($event.target as HTMLInputElement).value))" /><span>%</span></label>
          </div>
          <p class="setting-note">自动保存。实际比例为单曲音量 × 歌单音量，系统媒体音量另外生效。</p>
        </CollapsibleSettingsCard>
        <section class="lyrics-panel">
          <div v-if="store.lyricsLoading || !store.lyrics.original.length" class="lyrics-heading"><div><div class="eyebrow">LYRICS</div><h3>歌词</h3></div></div>
          <div v-if="store.lyricsLoading" class="lyrics-empty">正在读取歌词…</div>
          <div v-else-if="!store.lyrics.original.length" class="lyrics-empty">暂无歌词</div>
          <OriginalLyricsView v-else :family="store.lyrics" :position="store.playback.position" :playing="store.playback.playing && !store.playback.buffering" :offset-ms="store.overlaySettings.currentLyricOffsetMs ?? 0" :duration="store.playback.duration" :display-settings="store.overlaySettings" @seek="store.seek" />
        </section>
      </section>

      <AppAboutView v-else-if="store.activeTab === 'about'" />
      <AppSettingsView v-else @request-login="requestOnlineLogin" @local-mode="enterLocalMode" @open-offline="openLibrarySection('offline')" />

      </div>
      </Transition>
      <Transition name="notice-fade"><div v-if="store.error" class="toast-error" role="alert"><span>{{ store.error }}</span><button type="button" :aria-label="$t('mobile.common.close')" @click="store.error = ''"><OriginalIcon name="close" /></button></div></Transition>
      <Transition name="notice-fade"><div v-if="store.queueNotice" class="queue-notice" role="status">{{ store.queueNotice }}</div></Transition>
      <Transition name="mini-player">
      <div v-if="store.playback.trackId && store.activeTab !== 'player'" class="mini-player" role="button" tabindex="0" aria-label="打开正在播放" @click="store.navigateTo('player')" @keydown.enter.prevent="store.navigateTo('player')" @keydown.space.prevent="store.navigateTo('player')">
        <CoverImage v-if="currentCover" :src="currentCover" :remote-src="currentTrack.picUrl" loading="eager" alt="" /><span v-else class="mini-cover-fallback"><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span>
        <span class="mini-copy"><b>{{ currentTrack.name }}</b><small>{{ currentTrack.artists?.join(' / ') || store.playback.artist }}</small></span>
        <button class="mini-action" type="button" :aria-label="store.playback.playing ? $t('player.pause') : $t('contextMenu.play')" @click.stop="store.playback.playing ? store.pause() : store.resume()" @keydown.enter.stop @keydown.space.stop><OriginalIcon :name="store.playback.playing ? 'pause' : 'play'" /></button>
      </div>
      </Transition>
      <nav class="bottom-nav" :style="{ gridTemplateColumns: store.onlineAvailable ? 'repeat(5, minmax(0, 1fr))' : 'repeat(3, minmax(0, 1fr))' }" aria-label="主导航">
        <SideNavItem v-if="store.onlineAvailable" variant="mobile" type="button" :active="store.activeTab === 'home'" @click="store.navigateTo('home')"><span><OriginalIcon name="logo" /></span><small>{{ $t('nav.home') }}</small></SideNavItem>
        <SideNavItem variant="mobile" type="button" :active="store.activeTab === 'library'" @click="store.navigateTo('library')"><span><OriginalIcon name="library" /></span><small>{{ $t('nav.library') }}</small></SideNavItem>
        <SideNavItem v-if="store.onlineAvailable" variant="mobile" type="button" :active="store.activeTab === 'search'" @click="store.navigateTo('search')"><span><OriginalIcon name="search" /></span><small>{{ $t('nav.search') }}</small></SideNavItem>
        <SideNavItem variant="mobile" type="button" :active="store.activeTab === 'player'" @click="store.navigateTo('player')"><span><OriginalIcon name="play" /></span><small>{{ $t('mobile.common.playing') }}</small></SideNavItem>
        <SideNavItem variant="mobile" type="button" :active="store.activeTab === 'settings' || store.activeTab === 'about'" @click="store.navigateTo('settings')"><span><OriginalIcon name="settings" /></span><small>{{ $t('nav.settings') }}</small></SideNavItem>
      </nav>
    </template>
    <AppUpdateNotice />
    <ActionDialog />
    <OnlinePlaylistDialog />
    <LibraryTaskProgress />
  </main>
</template>
