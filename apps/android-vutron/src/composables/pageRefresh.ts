import { computed, type Ref } from 'vue'
import type { useAndroidShellStore } from '../stores/androidShell'
import { translate as t } from '../platform/i18n'

type RefreshStore = Pick<ReturnType<typeof useAndroidShellStore>,
  'onlineAvailable' | 'loginVisible' | 'activeTab' | 'selectedPlaylist' | 'homeLoading' | 'playlistsLoading' |
  'error' | 'queueNotice' | 'loadHomeData' | 'loadPlaylists'>

export function usePageRefresh(store: RefreshStore, libraryMode: Ref<'playlists' | 'local' | 'offline'>) {
  const headerRefreshLabel = computed(() => {
    if (!store.onlineAvailable || store.loginVisible) return ''
    if (store.activeTab === 'home') return t('mobile.home.refresh')
    if (store.activeTab === 'library' && !store.selectedPlaylist && libraryMode.value === 'playlists') return t('mobile.home.refreshPlaylists')
    return ''
  })
  const headerRefreshing = computed(() => store.activeTab === 'home' ? store.homeLoading : store.playlistsLoading)

  async function refreshCurrentView() {
    const label = headerRefreshLabel.value
    if (!label || headerRefreshing.value) return
    store.error = ''
    store.queueNotice = ''
    const isHome = store.activeTab === 'home'
    const result = await (isHome ? store.loadHomeData() : store.loadPlaylists())
    if (!result) return
    store.queueNotice = result === 'cached'
      ? t('mobile.home.cached')
      : t(isHome ? 'mobile.home.updated' : 'mobile.home.playlistsUpdated')
  }

  return { headerRefreshLabel, headerRefreshing, refreshCurrentView }
}
