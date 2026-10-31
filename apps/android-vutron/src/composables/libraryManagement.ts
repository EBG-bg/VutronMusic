import { useAndroidShellStore } from '../stores/androidShell'
import { vutronPlugin, type VutronTrack, type VutronLocalCollection, type VutronPlaylist } from '../platform/vutronPlugin'
import { translate as t } from '../platform/i18n'
import { askAction } from '../platform/actionDialog'

import { libraryMutationBusy } from '../platform/libraryActivity'
export function useLibraryManagement() {
  const store = useAndroidShellStore()
  async function manage(action: 'removeTrack' | 'deletePlaylist' | 'forgetFolder' | 'restoreRemoved', track?: VutronTrack, collection?: VutronLocalCollection): Promise<boolean> {
    if (!vutronPlugin.manageLocalLibrary || libraryMutationBusy.value) return false
    const title = t('mobile.management.' + action)
    const choice = await askAction({ title, message: t('mobile.management.' + action + 'Hint'), confirmLabel: title, cancelLabel: t('mobile.management.cancel') })
    if (choice !== 'confirm' || libraryMutationBusy.value) return false
    libraryMutationBusy.value = true
    try {
      store.error = ''
      const result = await vutronPlugin.manageLocalLibrary({ action, track, collectionId: collection?.id, stopCurrent: true })
      if (!result.changed) return false
      await store.reloadManagedLibrary({ track, collectionId: action === 'removeTrack' ? undefined : collection?.id })
      if (action === 'restoreRemoved') await store.loadLocalMusic(true)
      store.queueNotice = t('mobile.management.completed')
      return true
    } catch (cause) {
      store.error = cause instanceof Error ? cause.message : t('mobile.management.failed')
      try { await store.reloadManagedLibrary() } catch { /* Preserve the original operation error. */ }
      return false
    } finally { libraryMutationBusy.value = false }
  }
  async function deleteCachedTrack(track: VutronTrack): Promise<boolean> {
    if (!vutronPlugin.deleteCachedTrack || libraryMutationBusy.value) return false
    const trackId = track.neteaseId || (!track.localUri && !track.localPath || track.cached ? track.id : 0)
    if (trackId <= 0) return false
    libraryMutationBusy.value = true
    try {
      const result = await vutronPlugin.deleteCachedTrack({ trackId })
      if (result.inUse) {
        await askAction({ title: t('mobile.management.deleteCache'), message: t('mobile.management.cacheInUse'), confirmLabel: t('mobile.management.understood'), cancelLabel: t('mobile.management.cancel') })
        return false
      }
      await store.loadCacheStatus(false)
      store.queueNotice = t('mobile.management.cacheRemoved')
      return true
    } catch (cause) { store.error = cause instanceof Error ? cause.message : t('mobile.management.failed'); return false }
    finally { libraryMutationBusy.value = false }
  }
  let cacheConfirmation = false
  async function deleteOfflinePlaylist(playlist: VutronPlaylist): Promise<boolean> {
    if (!vutronPlugin.deleteCachedPlaylist || libraryMutationBusy.value || cacheConfirmation) return false
    cacheConfirmation = true
    try {
      const choice = await askAction({ title: t('mobile.offline.deletePlaylist'), message: t('mobile.offline.deleteHint', { name: playlist.name }), confirmLabel: t('mobile.offline.deletePlaylist'), cancelLabel: t('mobile.management.cancel') })
      if (choice !== 'confirm' || libraryMutationBusy.value) return false
      libraryMutationBusy.value = true
      try {
        store.error = ''
        const result = await vutronPlugin.deleteCachedPlaylist({ playlistId: playlist.id, userId: playlist.cacheOwnerId })
        if (!result.removed) return false
        store.removeCachedPlaylistView(playlist.id)
        await Promise.all([store.loadOfflinePlaylists(), store.loadCacheStatus(false)])
        store.queueNotice = t('mobile.offline.deleted')
        return true
      } catch (cause) { store.error = cause instanceof Error ? cause.message : t('mobile.management.failed'); return false }
      finally { libraryMutationBusy.value = false }
    } finally { cacheConfirmation = false }
  }
  return { deleteCachedTrack, deleteOfflinePlaylist, removeLocalTrack: (track: VutronTrack) => manage('removeTrack', track),
    removeCollection: (collection: VutronLocalCollection, action: 'deletePlaylist' | 'forgetFolder') => manage(action, undefined, collection),
    restoreRemoved: () => manage('restoreRemoved') }
}
