import { shallowRef, type ObjectDirective } from 'vue'
import type { VutronTrack } from '../platform/vutronPlugin'
import { useAndroidShellStore } from '../stores/androidShell'

const trackAction = shallowRef<VutronTrack>()
let returnFocus: HTMLElement | undefined

function closeTrackMenu() {
  trackAction.value = undefined
  if (returnFocus?.isConnected) returnFocus.focus({ preventScroll: true })
  returnFocus = undefined
}

type MenuTrigger = () => void
type TrackElement = HTMLElement & { __trackMenuCleanup?: () => void; __trackMenuOpen?: MenuTrigger }

/** Capture only menu gestures. A scroll cancels the timer and its eventual row click. */
export const vTrackMenu: ObjectDirective<TrackElement, MenuTrigger> = {
  mounted(element, binding) {
    element.__trackMenuOpen = binding.value
    const previousStyle = { select: element.style.userSelect, callout: element.style.getPropertyValue('-webkit-touch-callout') }
    element.style.userSelect = 'none'
    element.style.setProperty('-webkit-touch-callout', 'none')
    let timer: ReturnType<typeof setTimeout> | undefined
    let pointer: number | undefined
    let x = 0, y = 0, suppressUntil = 0
    const cancel = () => { if (timer !== undefined) clearTimeout(timer); timer = undefined }
    const ignore = (event: Event) => {
      const control = (event.target as Element)?.closest('[data-track-menu-button],.row-play,button')
      return Boolean(control && control !== element)
    }
    const open = () => { cancel(); suppressUntil = performance.now() + 1200; element.__trackMenuOpen?.() }
    const down = (event: PointerEvent) => {
      if (ignore(event) || event.button !== 0 || !event.isPrimary) return
      pointer = event.pointerId; x = event.clientX; y = event.clientY; cancel()
      timer = setTimeout(open, 450)
    }
    const move = (event: PointerEvent) => {
      if (event.pointerId !== pointer) return
      if (Math.hypot(event.clientX - x, event.clientY - y) > 10) { cancel(); suppressUntil = performance.now() + 700 }
    }
    const end = (event: PointerEvent) => { if (event.pointerId === pointer) { cancel(); pointer = undefined } }
    const click = (event: MouseEvent) => {
      if (performance.now() < suppressUntil && !ignore(event)) { event.preventDefault(); event.stopImmediatePropagation() }
    }
    const context = (event: MouseEvent) => { if (ignore(event)) return; event.preventDefault(); event.stopPropagation(); open() }
    const key = (event: KeyboardEvent) => {
      if (ignore(event) || !(event.key === 'ContextMenu' || event.key === 'F10' && event.shiftKey)) return
      event.preventDefault(); event.stopPropagation(); open()
    }
    const scroll = () => { if (pointer !== undefined) { cancel(); suppressUntil = performance.now() + 700 } }
    const preventSelection = (event: Event) => { if (!(event.target as Element)?.closest('input,textarea,[contenteditable=true]')) event.preventDefault() }
    element.addEventListener('pointerdown', down)
    element.addEventListener('pointermove', move)
    element.addEventListener('pointerup', end)
    element.addEventListener('pointercancel', end)
    element.addEventListener('click', click, true)
    element.addEventListener('contextmenu', context)
    element.addEventListener('keydown', key)
    element.addEventListener('selectstart', preventSelection)
    element.addEventListener('dragstart', preventSelection)
    window.addEventListener('scroll', scroll, true)
    element.__trackMenuCleanup = () => {
      cancel(); element.removeEventListener('pointerdown', down); element.removeEventListener('pointermove', move)
      element.removeEventListener('pointerup', end); element.removeEventListener('pointercancel', end)
      element.removeEventListener('click', click, true); element.removeEventListener('contextmenu', context)
      element.removeEventListener('keydown', key); window.removeEventListener('scroll', scroll, true)
      element.removeEventListener('selectstart', preventSelection); element.removeEventListener('dragstart', preventSelection)
      element.style.userSelect = previousStyle.select
      if (previousStyle.callout) element.style.setProperty('-webkit-touch-callout', previousStyle.callout)
      else element.style.removeProperty('-webkit-touch-callout')
    }
  },
  updated(element, binding) { element.__trackMenuOpen = binding.value },
  beforeUnmount(element) { element.__trackMenuCleanup?.(); delete element.__trackMenuCleanup; delete element.__trackMenuOpen }
}

export function useTrackActionMenu() {
  const store = useAndroidShellStore()
  function openTrackMenu(track: VutronTrack, playlistKey?: string) {
    returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : undefined
    trackAction.value = store.trackWithContext(track, playlistKey)
  }
  return { trackAction, openTrackMenu, closeTrackMenu, vTrackMenu }
}
