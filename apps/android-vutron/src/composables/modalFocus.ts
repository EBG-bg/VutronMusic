import { nextTick, onMounted, onUnmounted, watch, type Ref } from 'vue'

/** Keyboard/focus behavior shared by bottom sheets. A nested confirmation can suspend it. */
export function useModalFocus(element: Ref<HTMLElement | undefined>, options: {
  close(): void
  enabled?: () => boolean
  open?: () => boolean
  returnFocus?: () => HTMLElement | undefined
  restore?: boolean
}) {
  let previous: HTMLElement | undefined
  let lastInside: HTMLElement | undefined
  let mounted = false
  const active = () => mounted && options.open?.() !== false && options.enabled?.() !== false && !!element.value && !element.value.closest('[inert]')
  function rememberFocus() {
    previous = options.returnFocus?.() ?? (document.activeElement instanceof HTMLElement ? document.activeElement : undefined)
    lastInside = undefined
  }
  function restoreFocus() {
    if (options.restore !== false && previous?.isConnected && !previous.closest('[inert]')) previous.focus({ preventScroll: true })
  }
  function controls() {
    return Array.from(element.value?.querySelectorAll<HTMLElement>('button,input,select,textarea,a[href],summary,[tabindex]') ?? [])
      .filter(item => item.tabIndex >= 0 && !item.matches(':disabled') && !item.closest('[inert]') && item.getClientRects().length > 0)
  }
  function focusInside() {
    if (!active()) return
    const saved = lastInside?.isConnected && element.value?.contains(lastInside) && !lastInside.matches(':disabled') ? lastInside : undefined
    ;(saved ?? controls()[0] ?? element.value)?.focus({ preventScroll: true })
  }
  function key(event: KeyboardEvent) {
    if (!active()) return
    if (event.key === 'Escape') { event.preventDefault(); event.stopImmediatePropagation(); options.close(); return }
    if (event.key !== 'Tab') return
    const items = controls(), first = items[0], last = items.at(-1)
    if (!first) { event.preventDefault(); element.value?.focus({ preventScroll: true }); return }
    const outside = !element.value?.contains(document.activeElement) || document.activeElement === element.value
    if (event.shiftKey && (document.activeElement === first || outside)) { event.preventDefault(); last?.focus() }
    else if (!event.shiftKey && (document.activeElement === last || outside)) { event.preventDefault(); first.focus() }
  }
  function focused(event: FocusEvent) {
    if (!active()) return
    if (event.target instanceof HTMLElement && element.value?.contains(event.target)) lastInside = event.target
    else focusInside()
  }
  watch(() => options.enabled?.() !== false, async enabled => {
    if (enabled && mounted) { await nextTick(); focusInside() }
  }, { flush: 'post' })
  watch(() => options.open?.() !== false, async open => {
    if (!mounted) return
    if (open) { rememberFocus(); await nextTick(); focusInside() }
    else { await nextTick(); restoreFocus() }
  }, { flush: 'post' })
  onMounted(async () => {
    if (options.open?.() !== false) rememberFocus()
    mounted = true
    document.addEventListener('keydown', key, true)
    document.addEventListener('focusin', focused, true)
    await nextTick(); focusInside()
  })
  onUnmounted(() => {
    mounted = false
    document.removeEventListener('keydown', key, true)
    document.removeEventListener('focusin', focused, true)
    restoreFocus()
  })
}
