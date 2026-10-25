import { ref } from 'vue'
import { vutronPlugin } from '../platform/vutronPlugin'
import { askAction } from '../platform/actionDialog'
import { translate as t, setAppLocale } from '../platform/i18n'
import { appearance, accent, type Appearance } from '../platform/appearance'
import { motionPreference, type MotionPreference } from '../platform/motion'
import { playlistView, playlistColumns } from '../platform/libraryLayout'
import { ORDER_STORAGE_KEY } from './localTrackOrdering'
import { libraryMutationBusy } from '../platform/libraryActivity'
import { useAndroidShellStore } from '../stores/androidShell'

const keys = ['vutron:language', 'vutron:appearance', 'vutron:accent', 'vutron:motion-preference', 'vutron:playlist-view', 'vutron:playlist-columns', 'vutron:local-track-order']
export function useUserBackup() {
  const busy = ref(false), message = ref('')
  const store = useAndroidShellStore()
  async function exportBackup() {
    if (busy.value || libraryMutationBusy.value || !vutronPlugin.exportUserBackup) return
    busy.value = true
    try {
      await store.flushLiveSettings()
      const frontend = Object.fromEntries(keys.flatMap(key => { const value = localStorage.getItem(key); return value === null ? [] : [[key, value]] }))
      const orders = JSON.parse(localStorage.getItem(ORDER_STORAGE_KEY) || '{}')
      const result = await vutronPlugin.exportUserBackup({ frontend, orders })
      if (!result.cancelled) message.value = t('mobile.backup.exported', { count: result.collectionCount || 0 })
    } catch (cause) { message.value = cause instanceof Error ? cause.message : t('mobile.backup.failed') }
    finally { busy.value = false }
  }
  async function importBackup() {
    if (busy.value || libraryMutationBusy.value || !vutronPlugin.importUserBackup) return
    if (await askAction({ title: t('mobile.backup.import'), message: t('mobile.backup.confirm'), confirmLabel: t('mobile.backup.import'), cancelLabel: t('mobile.management.cancel') }) !== 'confirm') return
    if (libraryMutationBusy.value) return
    busy.value = true; libraryMutationBusy.value = true
    try {
      await store.flushLiveSettings()
      const result = await vutronPlugin.importUserBackup()
      if (result.cancelled) return
      for (const key of keys) if (typeof result.frontend?.[key] === 'string') localStorage.setItem(key, result.frontend[key])
      const previous = JSON.parse(localStorage.getItem(ORDER_STORAGE_KEY) || '{}')
      localStorage.setItem(ORDER_STORAGE_KEY, JSON.stringify({ ...previous, ...result.orders }))
      const values = result.frontend || {}
      if (values['vutron:language']) setAppLocale(values['vutron:language'])
      if (values['vutron:appearance']) appearance.value = values['vutron:appearance'] as Appearance
      if (values['vutron:accent']) accent.value = values['vutron:accent']
      if (values['vutron:motion-preference']) motionPreference.value = values['vutron:motion-preference'] as MotionPreference
      if (values['vutron:playlist-view']) playlistView.value = values['vutron:playlist-view'] as 'cards' | 'list'
      if (values['vutron:playlist-columns']) playlistColumns.value = Number(values['vutron:playlist-columns'])
      window.dispatchEvent(new Event('vutron-backup-restored'))
      await store.reloadManagedLibrary(); await store.loadOverlaySettings(); await store.loadSourceSettings(); await store.loadCacheStatus(false)
      message.value = t('mobile.backup.imported', { count: result.collectionCount || 0 })
    } catch (cause) { message.value = cause instanceof Error ? cause.message : t('mobile.backup.failed') }
    finally { busy.value = false; libraryMutationBusy.value = false }
  }
  return { busy, message, exportBackup, importBackup }
}
