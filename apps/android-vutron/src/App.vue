<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { App as CapacitorApp } from '@capacitor/app'
import { Capacitor, type PluginListenerHandle } from '@capacitor/core'
import CoverImage from './components/CoverImage.vue'
import OriginalIcon from './components/OriginalIcon.vue'
import originalBrandLogo from '../../../src/renderer/assets/images/vutronmusic-icon.png?url'
import AppearanceSettings from './components/AppearanceSettings.vue'
import CollapsibleSettingsCard from './components/CollapsibleSettingsCard.vue'
import LyricColorPicker from './components/LyricColorPicker.vue'
import LyricOffsetControl from './components/LyricOffsetControl.vue'
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
const { trackAction, openTrackMenu, closeTrackMenu, vTrackMenu } = useTrackActionMenu()
const cookie = ref('')
const sidebarOpen = ref(false)
const libraryMode = ref<'playlists' | 'local' | 'offline'>('playlists')
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
const searchCategories = [
  { key: 'tracks', label: '歌曲' },
  { key: 'albums', label: '专辑' },
  { key: 'artists', label: '歌手' },
  { key: 'playlists', label: '歌单' },
  { key: 'mvs', label: 'MV' }
] as const
const currentTrack = computed<VutronTrack>(() => {
  const id = store.playback.trackId
  const match = store.playback.track ?? [...store.tracks, ...store.localTracks, ...store.cachedTracks].find(track => track.id === id)
  return match ?? {
    id,
    name: store.playback.title || '未在播放',
    artists: store.playback.artist ? [store.playback.artist] : [],
    duration: store.playback.duration
  }
})
const currentCover = computed(() => currentTrack.value.picLocalUri || currentTrack.value.picUrl || '')
const filteredPlaylists = computed(() => store.playlists.filter(item => item.name.toLowerCase().includes(query.value.trim().toLowerCase())))
const visibleLocalTracks = computed(() => store.localTracks.filter(item => `${item.name} ${item.artists?.join(' ')}`.toLowerCase().includes(query.value.trim().toLowerCase())))
const visibleCachedTracks = computed(() => store.cachedTracks.filter(item => `${item.name} ${item.artists?.join(' ')}`.toLowerCase().includes(query.value.trim().toLowerCase())))
const cacheUsedPercent = computed(() => store.cacheStatus.capacityMb > 0
  ? Math.min(100, (store.cacheStatus.usedBytes / (store.cacheStatus.capacityMb * 1024 * 1024)) * 100)
  : 0)

onMounted(async () => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  if (Capacitor.isNativePlatform()) {
    backButtonListener = await CapacitorApp.addListener('backButton', () => {
      if (!handleBack()) void CapacitorApp.minimizeApp()
    })
  }
  await store.restore()
  if (store.loggedIn) {
    store.startPlaybackPolling()
    startHeroRotation()
  } else if (Capacitor.isNativePlatform()) {
    await refreshQrLogin()
  }

})
onUnmounted(() => {
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

watch(() => store.loggedIn, loggedIn => {
  if (loggedIn) stopQrPolling()
})
watch(() => store.queueNotice, text => {
  if (noticeTimer !== undefined) clearTimeout(noticeTimer)
  if (text) noticeTimer = setTimeout(() => { store.queueNotice = '' }, 2200)
})

watch(() => store.activeTab, tab => {
  if (tab === 'home') {
    if (!store.homeData.banners.length && !store.homeData.playlists.length) void store.loadHomeData()
    startHeroRotation()
  } else stopHeroRotation()
})

watch(() => store.homeData.banners, () => { heroIndex.value = 0; startHeroRotation() })

function restorePageScroll() {
  window.scrollTo({ top: store.navigationScrollY, behavior: 'instant' as ScrollBehavior })
}

function onVisibilityChange() {
  if (document.visibilityState === 'hidden') void store.flushLiveSettings()
  refreshOverlaySettings()
  if (document.visibilityState === 'visible' && store.loggedIn) {
    void store.loadLocalMusic()
    void store.refreshPlaybackState()
  }
  if (document.visibilityState === 'visible' && store.activeTab === 'home') startHeroRotation()
  else stopHeroRotation()
}

function startHeroRotation() {
  stopHeroRotation()
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
  if (store.loggedIn || !store.qrLoginKey) return
  qrPollTimer = setTimeout(() => { void pollQrLogin() }, delay)
}

async function pollQrLogin() {
  if (qrPollPending || store.loggedIn || !store.qrLoginKey) return
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
  if (trackAction.value) { closeTrackMenu(); return true }
  if (store.queueOpen) { store.queueOpen = false; return true }
  if (sidebarOpen.value) { sidebarOpen.value = false; return true }
  const openDetails = Array.from(document.querySelectorAll<HTMLDetailsElement>('details[open]:not([data-closing])')).at(-1)
  if (openDetails) {
    if (openDetails.classList.contains('settings-collapse')) openDetails.dispatchEvent(new Event('vutron-settings-close'))
    else openDetails.open = false
    return true
  }
  if (store.activeTab === 'library' && !store.selectedPlaylist && libraryMode.value !== 'playlists') {
    libraryMode.value = 'playlists'; query.value = ''; return true
  }
  return store.navigateBack()
}
function openLibrarySection(section: 'playlists' | 'local' | 'offline') {
  libraryMode.value = section
  store.navigateTo('library')
  sidebarOpen.value = false
}
function openSidebarCollection(collection: typeof store.localLibrary.folders[number]) {
  sidebarOpen.value = false
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
  if (document.visibilityState === 'visible' && store.loggedIn) void store.loadOverlaySettings()
}

function formatTime(milliseconds: number): string {
  const totalSeconds = Math.floor(Math.max(0, milliseconds) / 1000)
  return `${Math.floor(totalSeconds / 60)}:${String(totalSeconds % 60).padStart(2, '0')}`
}
function formatBytes(bytes: number): string {
  if (bytes < 1024 * 1024) return `${Math.max(0, Math.round(bytes / 1024))} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
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
function updateCacheCapacity(event: Event) {
  store.cacheCapacityMb = Number((event.target as HTMLInputElement).value)
}
function updateArtworkCacheCapacity(event: Event) {
  store.artworkCacheCapacityMb = Number((event.target as HTMLInputElement).value)
}
function setSleepTimerFromInput() {
  void store.setSleepTimer(Number(store.sleepTimerMinutes))
}
</script>

<template>
  <main class="app-shell" :class="`playlist-layout-${playlistView}`" :style="{ '--playlist-columns': playlistColumns }">
    <header class="app-header">
      <button v-if="store.loggedIn" class="icon-button sidebar-toggle" type="button" aria-label="展开本地音乐侧栏" :aria-expanded="sidebarOpen" @click="sidebarOpen = !sidebarOpen"><OriginalIcon name="list" /></button>
      <button v-if="store.canNavigateBack" class="icon-button header-back" type="button" aria-label="返回上一级" @click="handleBack"><OriginalIcon name="back" /></button>
      <div v-if="!store.loggedIn" class="brand-mark original-brand"><img :src="originalBrandLogo" alt="Vutron Music - Mobile" /></div>
      <div class="brand-copy">
        <h1>Vutron Music - Mobile</h1>
        <p>{{ store.loggedIn ? store.profile?.nickname : '移动音乐播放器' }}</p>
      </div>
      <button v-if="store.loggedIn" class="icon-button header-refresh" type="button" aria-label="刷新歌单" @click="store.loadPlaylists()"><OriginalIcon name="refresh" /></button>
    </header>

    <section v-if="!store.loggedIn" class="login-view">
      <div class="login-art original-login-art"><img :src="originalBrandLogo" alt="Vutron Music - Mobile" /></div>
      <div class="eyebrow">YOUR MUSIC, YOUR WAY</div>
      <h2>连接网易云音乐</h2>
      <p class="muted">扫码登录网易云音乐，登录状态安全保存在本机。</p>
      <div v-if="Capacitor.isNativePlatform()" class="qr-login-card">
        <div class="qr-image-frame">
          <img v-if="store.qrLoginImage" :src="store.qrLoginImage" alt="网易云音乐登录二维码" />
          <div v-else class="qr-image-placeholder">{{ store.qrLoginLoading ? '正在生成二维码…' : '二维码暂不可用' }}</div>
        </div>
        <p class="qr-login-status" role="status">{{ store.qrLoginStatus || '等待生成二维码…' }}</p>
        <button class="primary-button full-button" type="button" :disabled="store.qrLoginLoading" @click="refreshQrLogin">
          {{ store.qrLoginImage ? '刷新二维码' : '获取二维码' }}
        </button>
        <p class="qr-login-hint">如需在本机扫码，可截屏后在网易云音乐扫一扫中从相册选择。</p>
      </div>
      <CollapsibleSettingsCard class="cookie-login-details" section-id="cookie-login" title="使用 Cookie 登录（备用方式）" :remember="false">
        <form class="login-form" @submit.prevent="store.login(cookie)">
          <label for="cookie-input">网易云 Cookie</label>
          <textarea id="cookie-input" v-model="cookie" autocomplete="off" spellcheck="false" placeholder="粘贴 Cookie 内容" rows="4" />
          <button class="primary-button full-button" type="submit" :disabled="store.loading">
            {{ store.loading ? '正在连接…' : '登录并载入歌单' }}
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
          <aside class="library-sidebar" role="dialog" aria-modal="true" aria-label="音乐侧栏">
            <div class="sidebar-heading"><img :src="originalBrandLogo" alt="" /><b>Vutron Music - Mobile</b><button type="button" class="icon-button" aria-label="收起侧栏" @click="sidebarOpen = false"><OriginalIcon name="close" /></button></div>
            <button type="button" @click="openLibrarySection('playlists')"><OriginalIcon name="library" />网易云歌单</button>
            <button type="button" @click="pickFolderFromSidebar"><OriginalIcon name="folder" />选择本地文件夹</button>
            <button type="button" :disabled="store.libraryLoading" @click="importFromSidebar"><OriginalIcon name="down" />导入电脑本地歌单</button>
            <button type="button" @click="openLibrarySection('local')"><OriginalIcon name="local" />全部本地音乐</button>
            <button type="button" @click="openLibrarySection('offline')"><OriginalIcon name="down" />离线缓存</button>
            <button type="button" @click="store.loadLocalMusic()"><OriginalIcon name="refresh" />重新扫描文件夹</button>
            <h3 v-if="store.localLibrary.folders.length">我的本地音乐</h3>
            <button v-for="folder in store.localLibrary.folders" :key="folder.id" type="button" @click="openSidebarCollection(folder)"><OriginalIcon name="folder" /><span>{{ folder.name }}<small>{{ folder.trackCount }} 首</small></span></button>
            <h3 v-if="store.localLibrary.playlists.length">导入歌单</h3>
            <button v-for="playlist in store.localLibrary.playlists" :key="playlist.id" type="button" @click="openSidebarCollection(playlist)"><OriginalIcon name="library" /><span>{{ playlist.name }}<small>{{ playlist.trackCount }} 首</small></span></button>
          </aside>
        </div>
      </Transition>
      <Transition :name="transitionName" mode="out-in" @after-enter="restorePageScroll">
      <div :key="pageKey" class="page-stage">
      <section v-if="store.activeTab === 'home'" class="page home-page">
        <div class="home-greeting"><div><div class="eyebrow">VUTRON MUSIC</div><h2>为你而听</h2></div><button class="home-search-shortcut" type="button" aria-label="搜索音乐" @click="store.navigateTo('search')"><OriginalIcon name="search" /></button></div>
        <div v-if="store.homeData.banners.length" class="hero-carousel" aria-label="推荐轮播" @touchstart.passive="heroTouchStart = $event.touches[0]?.clientX ?? 0" @touchend.passive="endHeroSwipe">
          <button v-for="(banner, index) in store.homeData.banners" :key="`${banner.id}-${index}`" class="hero-card" :class="heroPosition(index)" :tabindex="heroPosition(index) === 'hero-hidden' ? -1 : 0" :aria-hidden="heroPosition(index) === 'hero-hidden'" type="button" @click="clickHero(index)">
            <CoverImage :src="banner.picLocalUri || banner.picUrl" :remote-src="banner.picUrl" loading="eager" alt="" /><span class="hero-shade"></span><span class="hero-label">{{ banner.typeTitle || '每日推荐' }}</span>
          </button>
          <div class="hero-dots"><button v-for="(banner, index) in store.homeData.banners" :key="`${banner.id}-${index}`" type="button" :class="{ active: index === heroIndex }" :aria-label="`第 ${index + 1} 张推荐`" @click="heroIndex = index; startHeroRotation()"></button></div>
        </div>
        <div v-else class="hero-placeholder"><span class="eyebrow">YOUR MUSIC, YOUR WAY</span><b>打开音乐，发现下一首喜欢的歌</b><button class="text-button" type="button" @click="store.loadHomeData()">{{ store.homeLoading ? '正在载入…' : '刷新推荐' }}</button></div>

        <section v-if="store.homeData.playlists.length" class="home-section">
          <div class="home-section-title"><div><span class="eyebrow">MADE FOR YOU</span><h3>推荐歌单</h3></div><button class="text-button" type="button" @click="store.navigateTo('library')">更多</button></div>
          <div class="horizontal-cards"><button v-for="item in store.homeData.playlists" :key="item.id" class="home-cover-card" type="button" @click="store.openPlaylist(item)"><span class="home-cover"><CoverImage v-if="coverOf(item)" :src="coverOf(item)" :remote-src="item.coverImgUrl || item.picUrl" alt="" loading="lazy" /><span v-else><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span></span><b>{{ item.name }}</b><small>{{ item.copywriter || `${item.trackCount ?? 0} 首歌曲` }}</small></button></div>
        </section>
        <section v-if="store.homeData.dailyTracks.length" class="home-section">
          <div class="home-section-title"><div><span class="eyebrow">DAILY MIX</span><h3>每日推荐</h3></div></div>
          <div class="home-track-strip"><div v-for="track in store.homeData.dailyTracks.slice(0, 8)" :key="track.id" class="home-track-card" role="button" tabindex="0" v-track-menu="() => openTrackMenu(track)" @click="store.play(track)" @keydown.enter.self.prevent="store.play(track)" @keydown.space.self.prevent="store.play(track)"><CoverImage v-if="coverOf(track)" :src="coverOf(track)" :remote-src="track.picUrl" alt="" loading="lazy" /><span v-else class="home-track-fallback"><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span><span><b>{{ track.name }}</b><small>{{ track.artists?.join(' / ') || track.album || '网易云音乐' }}</small></span><TrackMenuButton :name="track.name" @open="openTrackMenu(track)" /></div></div>
        </section>
        <section v-if="store.homeData.artists.length" class="home-section"><div class="home-section-title"><div><span class="eyebrow">ARTISTS</span><h3>推荐歌手</h3></div></div><div class="horizontal-cards artist-cards"><button v-for="item in store.homeData.artists.slice(0, 12)" :key="item.id" class="home-cover-card artist-card" type="button" @click="store.openEntityTracks('artists', item.id, item.name, item.picUrl)"><span class="home-cover artist-cover"><CoverImage v-if="coverOf(item)" :src="coverOf(item)" :remote-src="item.coverImgUrl || item.picUrl" alt="" loading="lazy" /><span v-else><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span></span><b>{{ item.name }}</b></button></div></section>
        <section v-if="store.homeData.albums.length" class="home-section"><div class="home-section-title"><div><span class="eyebrow">NEW RELEASES</span><h3>新专速递</h3></div></div><div class="horizontal-cards"><button v-for="item in store.homeData.albums" :key="item.id" class="home-cover-card" type="button" @click="store.openEntityTracks('albums', item.id, item.name, item.picUrl)"><span class="home-cover"><CoverImage v-if="coverOf(item)" :src="coverOf(item)" :remote-src="item.coverImgUrl || item.picUrl" alt="" loading="lazy" /><span v-else><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span></span><b>{{ item.name }}</b><small>{{ item.artists?.map(artist => artist.name).join(' / ') || '新发行' }}</small></button></div></section>
        <section v-if="store.homeData.charts.length" class="home-section home-bottom-section"><div class="home-section-title"><div><span class="eyebrow">TOP CHARTS</span><h3>排行榜</h3></div></div><div class="horizontal-cards"><button v-for="item in store.homeData.charts" :key="item.id" class="home-cover-card" type="button" @click="store.openPlaylist(item)"><span class="home-cover"><CoverImage v-if="coverOf(item)" :src="coverOf(item)" :remote-src="item.coverImgUrl || item.picUrl" alt="" loading="lazy" /><span v-else><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span></span><b>{{ item.name }}</b><small>{{ item.copywriter || '网易云音乐榜单' }}</small></button></div></section>
        <div v-if="store.homeLoading" class="home-loading"><i></i><i></i><i></i><span>正在更新推荐</span></div>
      </section>

      <section v-else-if="store.activeTab === 'search'" class="page search-page">
        <div class="section-heading"><div><div class="eyebrow">DISCOVER</div><h2>搜索音乐</h2></div></div>
        <form class="remote-search-box" @submit.prevent="submitSearch"><span><OriginalIcon name="search" /></span><input v-model="store.searchKeywords" type="search" autocomplete="off" placeholder="歌曲、歌手、专辑或歌单" /><button type="submit" :disabled="store.searchLoading || !store.searchKeywords.trim()">搜索</button></form>
        <div class="search-categories" role="tablist" aria-label="搜索分类"><button v-for="category in searchCategories" :key="category.key" type="button" role="tab" :aria-selected="store.searchCategory === category.key" :class="{ selected: store.searchCategory === category.key }" @click="changeSearchCategory(category.key)">{{ category.label }}</button></div>
        <div class="search-result-heading" v-if="store.searchKeywords"><b>“{{ store.searchKeywords }}”</b><small>{{ store.searchCount ? `${store.searchCount} 条结果` : (store.searchLoading ? '正在搜索…' : '搜索结果') }}</small></div>
        <Transition name="content-switch" mode="out-in">
        <div :key="store.searchCategory" class="content-switch-stage">
        <LibraryLayoutControls v-if="store.searchCategory === 'playlists'" />
        <div v-if="store.searchCategory === 'tracks'" class="track-list search-track-list"><div v-for="(track, index) in store.searchResults" :key="track.id" class="track-row" role="button" tabindex="0" v-track-menu="() => openTrackMenu(track)" @click="store.play(track)" @keydown.enter.self.prevent="store.play(track)" @keydown.space.self.prevent="store.play(track)"><span class="track-index">{{ String(index + 1).padStart(2, '0') }}</span><span class="track-copy"><b>{{ track.name }}</b><small>{{ track.artists?.join(' / ') || track.album || '网易云音乐' }}</small></span><TrackMenuButton :name="track.name" @open="openTrackMenu(track)" /></div></div>
        <div v-else-if="store.searchCategory === 'playlists'" class="playlist-grid search-playlist-grid"><button v-for="item in store.searchResults" :key="item.id" class="playlist-card" type="button" @click="store.openPlaylist(item)"><span class="cover-wrap"><CoverImage v-if="coverOf(item)" :src="coverOf(item)" :remote-src="item.coverImgUrl || item.picUrl" alt="" loading="lazy" /><span v-else class="cover-fallback"><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span><span class="cover-play"><OriginalIcon name="play" /></span></span><span class="playlist-copy"><b>{{ item.name }}</b><small>{{ item.trackCount ?? 0 }} 首歌曲</small></span></button></div>
        <div v-else class="search-entity-list"><button v-for="item in store.searchResults" :key="item.id" class="search-entity-row" type="button" @click="store.searchCategory === 'mvs' ? store.openMv(item.id) : openSearchEntity(item)"><CoverImage v-if="coverOf(item)" :src="coverOf(item)" :remote-src="item.coverImgUrl || item.picUrl" alt="" loading="lazy" /><span v-else class="entity-fallback"><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span><span class="entity-copy"><b>{{ item.name }}</b><small>{{ item.artists?.map(artist => artist.name).join(' / ') || (store.searchCategory === 'mvs' ? '音乐视频' : '网易云音乐') }}</small></span><span class="entity-type">{{ searchCategories.find(category => category.key === store.searchCategory)?.label }}</span></button></div>
        <div v-if="store.searchLoading && !store.searchResults.length" class="empty-state">正在搜索…</div><div v-else-if="store.searchKeywords && !store.searchLoading && !store.searchResults.length" class="empty-state">没有找到相关内容</div>
        <button v-if="store.searchNextPage" class="load-more-button" type="button" :disabled="store.searchLoading" @click="store.loadMoreSearch()">{{ store.searchLoading ? '正在载入…' : '加载更多' }}</button><div v-else-if="store.searchResults.length" class="search-end">已经到底了</div>
        </div>
        </Transition>
      </section>

      <section v-else-if="store.activeTab === 'localCollection'" class="page library-page">
        <div class="section-heading"><div><div class="eyebrow">LOCAL MUSIC</div><h2>{{ store.selectedLocalCollection?.name }}</h2></div><button class="text-button" type="button" @click="handleBack">返回上一级</button></div>
        <button class="play-all-button" type="button" :disabled="!store.tracks.length" @click="store.tracks[0] && store.play(store.tracks[0])"><OriginalIcon name="play" />播放全部 · {{ store.tracks.length }} 首</button>
        <div class="track-list"><div v-for="(track, index) in store.tracks" :key="`${track.id}-${index}`" role="button" tabindex="0" class="track-row" v-track-menu="() => openTrackMenu(track)" @click="store.play(track)" @keydown.enter.self.prevent="store.play(track)" @keydown.space.self.prevent="store.play(track)"><span class="track-index">{{ index + 1 }}</span><span class="track-copy"><b>{{ track.name }}</b><small>{{ track.artists?.join(' / ') || track.album }}</small></span><TrackMenuButton :name="track.name" @open="openTrackMenu(track)" /></div><div v-if="store.loading" class="empty-state">正在读取本地歌曲…</div></div>
      </section>
      <section v-else-if="store.activeTab === 'entity'" class="page library-page">
        <div class="section-heading"><div><div class="eyebrow">{{ store.selectedEntity?.category === 'artists' ? 'ARTIST' : 'ALBUM' }}</div><h2>{{ store.selectedEntity?.name }}</h2></div><button class="text-button" type="button" @click="handleBack">返回上一级</button></div>
        <div class="playlist-hero"><CoverImage v-if="store.selectedEntity?.picUrl" :src="store.selectedEntity.picUrl" alt="" /><span v-else class="cover-fallback"><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span><div><div class="eyebrow">{{ store.selectedEntity?.category === 'artists' ? '歌手' : '专辑' }}</div><h3>{{ store.selectedEntity?.name }}</h3><p>{{ store.tracks.length }} 首歌曲</p></div></div>
        <div class="track-list playlist-tracks"><div v-for="(track, index) in store.tracks" :key="track.id" class="track-row" role="button" tabindex="0" v-track-menu="() => openTrackMenu(track)" @click="store.play(track)" @keydown.enter.self.prevent="store.play(track)" @keydown.space.self.prevent="store.play(track)"><span class="track-index">{{ String(index + 1).padStart(2, '0') }}</span><span class="track-copy"><b>{{ track.name }}</b><small>{{ track.artists?.join(' / ') || track.album }}</small></span><TrackMenuButton :name="track.name" @open="openTrackMenu(track)" /></div><div v-if="store.loading" class="empty-state">正在读取歌曲…</div></div>
      </section>

      <section v-else-if="store.activeTab === 'library'" class="page library-page">
        <div class="section-heading">
          <div><div class="eyebrow">YOUR LIBRARY</div><h2>{{ store.selectedPlaylist?.name ?? '音乐库' }}</h2></div>
          <button v-if="store.selectedPlaylist" type="button" class="text-button" @click="handleBack">返回上一级</button>
        </div>

        <template v-if="!store.selectedPlaylist">
          <div class="library-shortcuts">
            <button class="shortcut-card favorite-shortcut" type="button" @click="libraryMode = 'playlists'">
              <span class="shortcut-icon"><OriginalIcon name="library" /></span><span><b>我的歌单</b><small>{{ store.playlists.length }} 个歌单</small></span>
            </button>
            <button class="shortcut-card local-shortcut" type="button" @click="libraryMode = 'local'">
              <span class="shortcut-icon"><OriginalIcon name="local" /></span><span><b>本地音乐</b><small>{{ store.localTracks.length }} 首</small></span>
            </button>
          </div>
          <div class="segmented-control" role="tablist">
            <button type="button" :class="{ selected: libraryMode === 'playlists' }" @click="libraryMode = 'playlists'">歌单</button>
            <button type="button" :class="{ selected: libraryMode === 'local' }" @click="libraryMode = 'local'">本地</button>
            <button type="button" :class="{ selected: libraryMode === 'offline' }" @click="libraryMode = 'offline'">离线</button>
          </div>
          <label class="search-box"><span><OriginalIcon name="search" /></span><input v-model="query" type="search" :placeholder="libraryMode === 'playlists' ? '搜索歌单' : '搜索歌曲'" /></label>
          <Transition name="content-switch" mode="out-in">
          <div :key="libraryMode" class="content-switch-stage">
          <LibraryLayoutControls v-if="libraryMode !== 'local'" />

          <div v-if="libraryMode === 'playlists'" class="playlist-grid">
            <button v-for="playlist in filteredPlaylists" :key="playlist.id" class="playlist-card" type="button" @click="store.openPlaylist(playlist)">
              <span class="cover-wrap">
                <CoverImage v-if="coverOf(playlist)" :src="coverOf(playlist)" :remote-src="playlist.coverImgUrl" alt="" loading="lazy" />
                <span v-else class="cover-fallback"><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span>
                <span class="cover-play"><OriginalIcon name="play" /></span>
              </span>
              <span class="playlist-copy"><b>{{ playlist.name }}</b><small>{{ playlist.trackCount ?? 0 }} 首歌曲</small></span>
            </button>
            <div v-if="!filteredPlaylists.length" class="empty-state">{{ store.loading ? '正在载入歌单…' : '这里还没有歌单' }}</div>
          </div>

          <div v-else-if="libraryMode === 'offline'" class="offline-content">
            <div class="playlist-grid">
            <button v-for="playlist in store.cachedPlaylists" :key="playlist.id" class="playlist-card" type="button" @click="store.openCachedPlaylist(playlist)">
              <span class="cover-wrap"><CoverImage v-if="coverOf(playlist)" :src="coverOf(playlist)" :remote-src="playlist.coverImgUrl" alt="" loading="lazy" /><span v-else class="cover-fallback"><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span><span class="cover-play"><OriginalIcon name="play" /></span></span>
              <span class="playlist-copy"><b>{{ playlist.name }}</b><small>{{ playlist.trackCount ?? 0 }} 首 · 离线歌单</small></span>
            </button>
            <div v-if="!store.cachedPlaylists.length" class="empty-state">打开过的歌单会出现在这里</div>
            </div>
            <h3 class="offline-track-heading">已缓存歌曲 · {{ store.cachedTracks.length }} 首</h3>
            <div class="track-list">
              <div v-for="(track, index) in visibleCachedTracks" :key="`${track.id}-${index}`" class="track-row" role="button" tabindex="0" v-track-menu="() => openTrackMenu(track, 'cache:all')" @click="store.play(track, 'cache:all', visibleCachedTracks)" @keydown.enter.self.prevent="store.play(track, 'cache:all', visibleCachedTracks)" @keydown.space.self.prevent="store.play(track, 'cache:all', visibleCachedTracks)">
                <span class="track-index">{{ String(index + 1).padStart(2, '0') }}</span>
                <span class="track-copy"><b>{{ track.name }}</b><small>{{ track.artists?.join(' / ') || track.album }}</small></span>
                <span class="offline-badge">离线</span><TrackMenuButton :name="track.name" @open="openTrackMenu(track, 'cache:all')" />
              </div>
              <div v-if="!visibleCachedTracks.length" class="empty-state">还没有离线缓存歌曲</div>
            </div>
          </div>

          <div v-else class="track-list">
            <div v-if="libraryMode === 'local'" class="inline-info">
              <span>音乐来源：{{ store.folderName }}</span>
              <button type="button" class="text-button" @click="store.pickMusicFolder">选择文件夹</button>
            </div>
            <div v-for="(track, index) in libraryMode === 'local' ? visibleLocalTracks : visibleCachedTracks" :key="`${track.source}-${track.id}-${index}`" class="track-row" role="button" tabindex="0" v-track-menu="() => openTrackMenu(track, libraryMode === 'local' ? 'local:folder' : 'cache:all')" @click="store.play(track, libraryMode === 'local' ? 'local:folder' : 'cache:all')" @keydown.enter.self.prevent="store.play(track, libraryMode === 'local' ? 'local:folder' : 'cache:all')" @keydown.space.self.prevent="store.play(track, libraryMode === 'local' ? 'local:folder' : 'cache:all')">
              <span class="track-index">{{ String(index + 1).padStart(2, '0') }}</span>
              <span class="track-copy"><b>{{ track.name }}</b><small>{{ track.artists?.join(' / ') || track.album || (track.source === 'cache' ? '离线缓存' : '本地文件') }}</small></span>
              <span v-if="track.source === 'cache'" class="offline-badge">离线</span>
              <TrackMenuButton :name="track.name" @open="openTrackMenu(track, libraryMode === 'local' ? 'local:folder' : 'cache:all')" />
            </div>
            <div v-if="!(libraryMode === 'local' ? visibleLocalTracks : visibleCachedTracks).length" class="empty-state">
              {{ libraryMode === 'local' ? '选择音乐文件夹后扫描本地歌曲' : '还没有离线缓存歌曲' }}
            </div>
          </div>
          </div>
          </Transition>
        </template>
        <template v-else>
          <div class="playlist-hero">
            <CoverImage v-if="coverOf(store.selectedPlaylist)" :src="coverOf(store.selectedPlaylist)" :remote-src="store.selectedPlaylist.coverImgUrl" alt="" />
            <span v-else class="cover-fallback"><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span>
            <div><div class="eyebrow">PLAYLIST</div><h3>{{ store.selectedPlaylist.name }}</h3><p>{{ store.tracks.length }} 首歌曲</p></div>
          </div>
          <button class="play-all-button" type="button" :disabled="!store.tracks.length" @click="store.tracks[0] && store.play(store.tracks[0])"><span><OriginalIcon name="play" /></span> 播放全部</button>
          <div class="track-list playlist-tracks">
            <div v-for="(track, index) in store.tracks" :key="track.id" class="track-row" role="button" tabindex="0" v-track-menu="() => openTrackMenu(track)" @click="store.play(track)" @keydown.enter.self.prevent="store.play(track)" @keydown.space.self.prevent="store.play(track)">
              <span class="track-index">{{ String(index + 1).padStart(2, '0') }}</span>
              <span class="track-copy"><b>{{ track.name }}</b><small>{{ track.artists?.join(' / ') || track.album }}</small></span>
              <TrackMenuButton :name="track.name" @open="openTrackMenu(track)" />
            </div>
            <div v-if="store.loading" class="empty-state">正在读取歌曲…</div>
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
          <div class="player-title"><h2>{{ currentTrack.name }}</h2><p>{{ currentTrack.artists?.join(' / ') || store.playback.artist || 'Vutron Music - Mobile' }}</p></div>
        </div>
        <div class="progress-control">
          <input type="range" min="0" :max="Math.max(1, store.playback.duration)" :value="seeking ? seekValue : store.playback.position" :disabled="!store.playback.duration" @input="previewSeek" @change="seekFromInput" />
          <div><span>{{ formatTime(seeking ? seekValue : store.playback.position) }}</span><span>{{ formatTime(store.playback.duration) }}</span></div>
        </div>
        <div class="transport-controls">
          <button class="icon-button transport-small" type="button" :aria-label="`播放模式：${['列表循环', '单曲循环', '随机播放'][store.playback.mode ?? 0]}`" @click="store.cyclePlaybackMode()"><OriginalIcon :name="store.playback.mode === 1 ? 'repeatOne' : store.playback.mode === 2 ? 'shuffle' : 'repeat'" /></button>
          <button class="icon-button transport-small" type="button" aria-label="上一首" @click="playAdjacent(-1)"><OriginalIcon name="previous" /></button>
          <button class="main-play-button" type="button" :aria-label="store.playback.playing ? '暂停' : '播放'" @click="store.playback.playing ? store.pause() : store.resume()">
            <OriginalIcon :name="store.playback.playing ? 'pause' : 'play'" />
          </button>
          <button class="icon-button transport-small" type="button" aria-label="下一首" @click="playAdjacent(1)"><OriginalIcon name="next" /></button>
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

      <section v-else class="page settings-page">
        <div class="section-heading"><div><div class="eyebrow">PREFERENCES</div><h2>设置</h2></div></div>
        <AppearanceSettings />
        <section class="settings-card account-settings">
          <div class="settings-avatar">{{ store.profile?.nickname?.slice(0, 1) || 'V' }}</div>
          <div class="settings-account-copy"><b>{{ store.profile?.nickname }}</b><small>网易云音乐账号</small></div>
          <button type="button" class="text-button" @click="store.logout">退出</button>
        </section>
        <CollapsibleSettingsCard section-id="sources" title="音源与音质">
          <template #heading><span class="settings-icon violet"><OriginalIcon name="explore" /></span><div><h3>音源与音质</h3><p>自动匹配其他音源，并优先选择最高音质</p></div></template>
          <label class="settings-detail"><span>网易云不可用时自动寻找其它音源</span><input type="checkbox" :checked="store.autoMatchSources" @change="store.setAutoMatchSources(($event.target as HTMLInputElement).checked)" /></label>
          <p class="setting-note">优先播放完整网易云音频；其它来源核对歌名、全部歌手、版本和专辑或时长，默认选择通过核对的最高音质。</p>
        </CollapsibleSettingsCard>
        <CollapsibleSettingsCard section-id="local" title="本地音乐">
          <template #heading><span class="settings-icon violet"><OriginalIcon name="local" /></span><div><h3>本地音乐</h3><p>自动识别选定文件夹中的歌曲</p></div></template>
          <div class="settings-detail"><span>{{ store.folderName }}</span><button class="secondary-button" type="button" @click="store.pickMusicFolder">选择文件夹</button></div>
          <button class="wide-outline-button" type="button" @click="store.loadLocalMusic">重新扫描 · {{ store.localTracks.length }} 首</button>
        </CollapsibleSettingsCard>
        <CollapsibleSettingsCard section-id="cache" title="离线缓存">
          <template #heading><span class="settings-icon blue"><OriginalIcon name="down" /></span><div><h3>离线缓存</h3><p>歌曲 {{ formatBytes(store.cacheStatus.usedBytes) }} / {{ store.cacheStatus.capacityMb }} MB · {{ store.cacheStatus.trackCount }} 首</p><p>歌单与封面 {{ formatBytes(store.cacheStatus.libraryBytes ?? 0) }}</p></div></template>
          <label class="settings-detail"><span>自动保存已播放歌曲</span><input type="checkbox" :checked="store.cacheStatus.enabled !== false" @change="store.setAutomaticCache(($event.target as HTMLInputElement).checked)" /></label>
          <p class="setting-note">打开或播放歌单时会静默保存曲目资料和封面；音频随播放顺序自动缓存，无需逐个点击。</p>
          <CollapsibleSettingsCard class="cache-advanced" section-id="cache-prefetch" title="提前缓存其它歌单" :remember="false"><button class="secondary-button" type="button" :disabled="store.cacheAllPlaylistsLoading" @click="store.cacheAllPlaylists()">{{ store.cacheAllPlaylistsLoading ? '正在保存资料…' : '提前保存全部歌单资料与封面' }}</button><p class="setting-note">{{ store.cacheAllPlaylistsResult }}</p></CollapsibleSettingsCard>
          <p v-if="store.cacheStatus.downloading || store.cacheStatus.queued" class="setting-note">{{ store.cacheStatus.downloading ? `正在缓存 ${formatBytes(store.cacheStatus.downloadedBytes ?? 0)} / ${formatBytes(store.cacheStatus.downloadTotalBytes ?? 0)}` : '' }} · 等待 {{ store.cacheStatus.queued ?? 0 }} 首</p>
          <p v-if="store.cacheStatus.error" class="inline-error">{{ store.cacheStatus.error }}</p>
          <div class="storage-meter"><span :style="{ width: `${cacheUsedPercent}%` }"></span></div>
          <label class="setting-range"><span>容量上限</span><b>{{ store.cacheCapacityMb }} MB</b></label>
          <div class="cache-input-row"><input v-model.number="store.cacheCapacityMb" type="number" min="256" max="10240" inputmode="numeric" aria-label="音频缓存容量 MiB" /><button class="secondary-button" type="button" @click="store.setCacheCapacity()">保存上限</button></div>
          <input class="settings-slider" type="range" min="256" max="10240" step="256" :value="store.cacheCapacityMb" @input="updateCacheCapacity" @change="store.setCacheCapacity" />
          <div class="settings-detail"><span>播放时保存歌曲，供离线收听</span><button class="text-button danger-text" type="button" @click="store.clearCache">清空</button></div>
          <button class="wide-outline-button" type="button" @click="libraryMode = 'offline'; store.navigateTo('library')">查看离线歌曲</button>
          <label class="setting-range"><span>封面缓存上限</span><b>{{ store.artworkCacheCapacityMb }} MB</b></label>
          <input class="settings-slider" type="range" min="32" max="1024" step="32" :value="store.artworkCacheCapacityMb" @input="updateArtworkCacheCapacity" @change="store.setArtworkCacheCapacity" />
        </CollapsibleSettingsCard>
        <CollapsibleSettingsCard section-id="timer" title="睡眠定时">
          <template #heading><span class="settings-icon coral"><OriginalIcon name="timer" /></span><div><h3>睡眠定时</h3><p>{{ store.sleepTimerRemainingMs > 0 ? `将在 ${store.sleepTimerLabel} 后暂停` : '设定时间后自动暂停播放' }}</p></div></template>
          <div class="settings-detail"><label for="sleep-timer-minutes">分钟</label><input id="sleep-timer-minutes" v-model.number="store.sleepTimerMinutes" type="number" min="1" max="1440" inputmode="numeric" /></div>
          <div class="timer-actions"><button class="secondary-button" type="button" @click="setSleepTimerFromInput">开始定时</button><button class="text-button" type="button" :disabled="!store.sleepTimerRemainingMs" @click="store.clearSleepTimer">取消定时</button></div>
        </CollapsibleSettingsCard>
        <CollapsibleSettingsCard section-id="lyrics" title="桌面歌词">
          <template #heading><span class="settings-icon coral"><OriginalIcon name="lyrics" /></span><div><h3>桌面歌词</h3><p>悬浮歌词支持逐字滚动和翻译同步</p></div></template>
          <div class="settings-detail"><span>显示悬浮歌词</span><button class="switch" :class="{ on: store.overlayEnabled }" type="button" role="switch" :aria-checked="store.overlayEnabled" @click="store.toggleLyricOverlay()"><i></i></button></div>
          <p class="setting-note">首次启用需要在系统设置中授予悬浮窗权限。</p>
          <div class="timer-actions"><button class="secondary-button" type="button" :disabled="!store.overlayEnabled" @click="store.temporarilyHideOverlay()">临时隐藏 30 秒</button><button class="secondary-button" type="button" :disabled="!store.overlayEnabled" @click="store.restoreOverlay()">立即恢复</button></div>
          <label class="settings-detail"><span>Wi-Fi 下自动校验歌词缓存</span><input type="checkbox" :checked="store.overlaySettings.wifiValidation" @change="store.updateOverlayField('wifiValidation', ($event.target as HTMLInputElement).checked)" /></label>
          <label class="settings-detail"><span>无原始逐字时估算逐字歌词</span><input type="checkbox" :checked="store.overlaySettings.estimatedWordTiming" @change="store.updateOverlayField('estimatedWordTiming', ($event.target as HTMLInputElement).checked)" /></label>
          <p class="setting-note">默认关闭。开启后，缺少原始逐字时间的歌词会按句长计算逐字进度，可能不准确；有原始逐字数据时始终优先使用原数据。此项与调色同时应用于软件内和桌面歌词。</p>
          <CollapsibleSettingsCard section-id="lyric-colors" title="歌词调色与背景" subtitle="展开后调整颜色与透明度" :remember="false">
            <LyricColorPicker :settings="store.overlaySettings" @change="(key, value) => store.updateOverlayField(key, value)" @commit="store.flushLiveSettings()" />
          </CollapsibleSettingsCard>
          <div class="overlay-options">
            <label>歌词大小 <b>{{ store.overlaySettings.fontSize }} sp</b></label>
            <input class="settings-slider" type="range" min="16" max="34" :value="store.overlaySettings.fontSize" @input="store.updateOverlayField('fontSize', Number(($event.target as HTMLInputElement).value))" @change="store.flushLiveSettings()" />
            <label class="settings-detail compact"><span>同步显示译文</span><input type="checkbox" :checked="store.overlaySettings.translation" @change="store.updateOverlayField('translation', ($event.target as HTMLInputElement).checked)" /></label>
          <label class="settings-detail compact"><span>显示下一句</span><input type="checkbox" :checked="store.overlaySettings.nextLine" @change="store.updateOverlayField('nextLine', ($event.target as HTMLInputElement).checked)" /></label>
          <label class="settings-detail compact"><span>锁定歌词位置</span><input type="checkbox" :checked="store.overlaySettings.locked" @change="store.updateOverlayField('locked', ($event.target as HTMLInputElement).checked)" /></label>
          <label class="settings-detail compact"><span>锁定时保留完全不透明（可能阻止下方点击）</span><input type="checkbox" :checked="!store.overlaySettings.touchPassthroughPriority" :disabled="!store.overlaySettings.locked" @change="store.updateOverlayField('touchPassthroughPriority', !($event.target as HTMLInputElement).checked)" /></label>
          <p class="setting-note">默认锁定后允许点击下方，系统会降低窗口整体不透明度，调色数值保留。此前保存的完整不透明设置会保留，取消此项即可改为穿透优先。个别应用仍不接受点击时，可在歌词通知中临时隐藏 30 秒。</p>
            <label class="settings-detail compact"><span>歌词阴影</span><input type="checkbox" :checked="store.overlaySettings.shadowEnabled" @change="store.updateOverlayField('shadowEnabled', ($event.target as HTMLInputElement).checked)" /></label>
          <label>阴影模糊 <b>{{ store.overlaySettings.shadowRadius }} dp</b></label>
          <input class="settings-slider" type="range" min="0" max="8" :value="store.overlaySettings.shadowRadius" @input="store.updateOverlayField('shadowRadius', Number(($event.target as HTMLInputElement).value))" @change="store.flushLiveSettings()" />
          <LyricOffsetControl :model-value="store.overlaySettings.currentLyricOffsetMs ?? 0" :track-key="[store.playback.trackId, store.playback.track?.localUri ?? '', store.playback.track?.localPath ?? '', store.playback.track?.neteaseId ?? ''].join('|')" :disabled="!store.playback.trackId" @update:model-value="store.updateCurrentLyricOffset" @commit="store.flushLiveSettings()" />
          </div>
        </CollapsibleSettingsCard>
      </section>

      </div>
      </Transition>
      <Transition name="notice-fade"><div v-if="store.error" class="toast-error" role="alert"><span>{{ store.error }}</span><button type="button" aria-label="关闭" @click="store.error = ''"><OriginalIcon name="close" /></button></div></Transition>
      <Transition name="notice-fade"><div v-if="store.queueNotice" class="queue-notice" role="status">{{ store.queueNotice }}</div></Transition>
      <Transition name="mini-player">
      <div v-if="store.playback.trackId && store.activeTab !== 'player'" class="mini-player" role="button" tabindex="0" aria-label="打开正在播放" @click="store.navigateTo('player')" @keydown.enter.prevent="store.navigateTo('player')" @keydown.space.prevent="store.navigateTo('player')">
        <CoverImage v-if="currentCover" :src="currentCover" :remote-src="currentTrack.picUrl" loading="eager" alt="" /><span v-else class="mini-cover-fallback"><img class="original-cover-placeholder" :src="originalBrandLogo" alt="" /></span>
        <span class="mini-copy"><b>{{ currentTrack.name }}</b><small>{{ currentTrack.artists?.join(' / ') || store.playback.artist }}</small></span>
        <button class="mini-action" type="button" :aria-label="store.playback.playing ? '暂停' : '播放'" @click.stop="store.playback.playing ? store.pause() : store.resume()" @keydown.enter.stop @keydown.space.stop><OriginalIcon :name="store.playback.playing ? 'pause' : 'play'" /></button>
      </div>
      </Transition>
      <nav class="bottom-nav" aria-label="主导航">
        <SideNavItem variant="mobile" type="button" :active="store.activeTab === 'home'" @click="store.navigateTo('home')"><span><OriginalIcon name="logo" /></span><small>首页</small></SideNavItem>
        <SideNavItem variant="mobile" type="button" :active="store.activeTab === 'library'" @click="store.navigateTo('library')"><span><OriginalIcon name="library" /></span><small>音乐库</small></SideNavItem>
        <SideNavItem variant="mobile" type="button" :active="store.activeTab === 'search'" @click="store.navigateTo('search')"><span><OriginalIcon name="search" /></span><small>搜索</small></SideNavItem>
        <SideNavItem variant="mobile" type="button" :active="store.activeTab === 'player'" @click="store.navigateTo('player')"><span><OriginalIcon name="play" /></span><small>正在播放</small></SideNavItem>
        <SideNavItem variant="mobile" type="button" :active="store.activeTab === 'settings'" @click="store.navigateTo('settings')"><span><OriginalIcon name="settings" /></span><small>设置</small></SideNavItem>
      </nav>
    </template>
  </main>
</template>
