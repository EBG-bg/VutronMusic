import { ref } from 'vue'

const storageKey = 'vutron.mobile.local-only'
function readPreference(): boolean {
  try { return localStorage.getItem(storageKey) === 'true' }
  catch { return false }
}

export const localOnlyMode = ref(readPreference())

export function setLocalOnlyPreference(enabled: boolean, persist = true, manual = enabled) {
  localOnlyMode.value = enabled
  try { if (persist) localStorage.setItem(storageKey, String(manual)) }
  catch { /* Native preferences still retain the music network policy. */ }
}

export function isRemoteMusicUri(uri: string): boolean {
  return uri.toLowerCase().startsWith('http://') || uri.toLowerCase().startsWith('https://')
}
