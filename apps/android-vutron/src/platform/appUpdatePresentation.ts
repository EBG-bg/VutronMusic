import type { VutronAppUpdateState } from './vutronPlugin'
import { translate as t } from './i18n'

export function isNewerAppVersion(candidate: string | undefined, current: string): boolean {
  const parse = (value: string) => /^[0-9]+[.][0-9]+[.][0-9]+$/.test(value) ? value.split('.').map(Number) : undefined
  const next = candidate ? parse(candidate) : undefined
  const installed = parse(current)
  if (!next || !installed || ![...next, ...installed].every(Number.isSafeInteger)) return false
  for (let i = 0; i < 3; i++) {
    if (next[i] !== installed[i]) return next[i] > installed[i]
  }
  return false
}

export function appUpdateSummary(state: VutronAppUpdateState): string {
  switch (state.status) {
    case 'checking': return t('mobile.updateStatus.checking')
    case 'available': return t('mobile.updates.found', { version: state.latestVersion ?? '' })
    case 'upToDate': return t('mobile.updateStatus.upToDate')
    case 'downloading': return t('mobile.updateStatus.downloading', { progress: Math.round(state.progress) })
    case 'ready': return t('mobile.updateStatus.ready')
    case 'permissionRequired': return t('mobile.updateStatus.permission')
    case 'error': return state.error || t('mobile.updateStatus.error')
    default: return t('mobile.updateStatus.idle')
  }
}

export function appUpdateActionLabel(state: VutronAppUpdateState): string {
  if (state.status === 'downloading') return t('mobile.updates.downloading')
  if (state.status === 'permissionRequired') return t('mobile.updates.continueInstall')
  if (state.status === 'ready') return t('mobile.updates.install')
  return t('mobile.updates.download')
}
