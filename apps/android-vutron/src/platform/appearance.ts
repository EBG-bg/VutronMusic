import { computed, ref, watch } from 'vue'
import originalStyles from '../../../../src/renderer/assets/css/global.scss?raw'

// Reuse desktop tokens, excluding Electron layout rules such as min-width:768px.
const originalThemeBlocks = originalStyles.match(/:root,\s*\[data-theme='light'\]\s*\{[^}]*\}|\[data-theme='dark'\]\s*\{[^}]*\}/g)
if (!originalThemeBlocks || originalThemeBlocks.length !== 2) throw new Error('原版主题变量未找到，请检查 global.scss')
const themeStyle = document.createElement('style')
themeStyle.dataset.vutronOriginalTheme = 'true'
themeStyle.textContent = originalThemeBlocks.join('\n')
document.head.append(themeStyle)

// Exact presets from src/renderer/store/settings.ts; cyan matches the user's desktop.
export const accentPresets = [
  { name: 'blue', label: '蓝色', color: '#335eea' },
  { name: 'purple', label: '紫色', color: '#8854d0' },
  { name: 'orange', label: '橙色', color: '#ea8833' },
  { name: 'cyan', label: '青色', color: '#1db9b5' }
] as const
export type Appearance = 'auto' | 'light' | 'dark'
const storedAppearance = localStorage.getItem('vutron:appearance')
const storedAccent = localStorage.getItem('vutron:accent')
export const appearance = ref<Appearance>(storedAppearance === 'light' || storedAppearance === 'dark' ? storedAppearance : 'auto')
export const accent = ref(storedAccent && /^#[0-9a-f]{6}$/i.test(storedAccent) ? storedAccent : '#1db9b5')
const media = window.matchMedia('(prefers-color-scheme: dark)')
const systemDark = ref(media.matches)
media.addEventListener('change', event => { systemDark.value = event.matches })
export const resolvedAppearance = computed(() => appearance.value === 'auto' ? systemDark.value ? 'dark' : 'light' : appearance.value)
watch([appearance, accent, resolvedAppearance], () => {
  const root = document.documentElement
  root.dataset.theme = resolvedAppearance.value
  root.style.colorScheme = resolvedAppearance.value
  root.style.setProperty('--color-primary', accent.value)
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', resolvedAppearance.value === 'dark' ? '#222222' : '#ffffff')
  localStorage.setItem('vutron:appearance', appearance.value)
  localStorage.setItem('vutron:accent', accent.value)
}, { immediate: true })
