import { watch } from 'vue'
import { createI18n } from 'vue-i18n'
import zh from '../../../../src/renderer/locales/zh-hans.json'
import zht from '../../../../src/renderer/locales/zh-hant.json'
import en from '../../../../src/renderer/locales/en.json'
import mobileZh from '../locales/zh-hans.json'
import mobileZht from '../locales/zh-hant.json'
import mobileEn from '../locales/en.json'

export type AppLocale = 'zh' | 'zht' | 'en'
export const supportedLocales: readonly AppLocale[] = ['zh', 'zht', 'en']
const storageKey = 'vutron:language'
let initialLocale: AppLocale = 'zh'
try {
  const saved = localStorage.getItem(storageKey)
  if (supportedLocales.includes(saved as AppLocale)) initialLocale = saved as AppLocale
} catch { /* Keep the existing Chinese default when storage is unavailable. */ }

export const i18n = createI18n({
  legacy: false, globalInjection: true, locale: initialLocale, fallbackLocale: 'zh',
  messages: { zh: { ...zh, mobile: mobileZh }, zht: { ...zht, mobile: mobileZht }, en: { ...en, mobile: mobileEn } }
})
export const appLocale = i18n.global.locale
export const translate = i18n.global.t
export function setAppLocale(value: string): boolean {
  if (!supportedLocales.includes(value as AppLocale)) return false
  appLocale.value = value as AppLocale
  try { localStorage.setItem(storageKey, value) } catch { /* The current session can still switch language. */ }
  return true
}
watch(appLocale, locale => {
  if (typeof document !== 'undefined') document.documentElement?.setAttribute('lang', locale === 'zh' ? 'zh-CN' : locale === 'zht' ? 'zh-TW' : 'en')
}, { immediate: true, flush: 'sync' })
