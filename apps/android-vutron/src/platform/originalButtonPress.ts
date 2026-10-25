import { isMotionReduced } from './motion'

// Android touch input can omit a sustained CSS :active state. Keep the desktop
// press transition and supply only its pressed state, without blocking gestures.
export function installOriginalButtonPress() {
  let pressed: HTMLElement | undefined
  const feedback = new Map<HTMLElement, ReturnType<typeof setTimeout>>()
  let pointerId: number | undefined
  let startX = 0
  let startY = 0
  let pressedAt = 0
  const removeFeedback = (element: HTMLElement) => {
    const timer = feedback.get(element)
    if (timer !== undefined) clearTimeout(timer)
    feedback.delete(element)
    element.removeAttribute('data-original-feedback')
  }
  const clear = () => {
    if (pressed) removeFeedback(pressed)
    pressed?.removeAttribute('data-original-pressed')
    pressed = undefined
    pointerId = undefined
  }
  const down = (event: PointerEvent) => {
    if (!['touch', 'pen'].includes(event.pointerType) || !event.isPrimary || event.button !== 0) return
    if ((event.target as Element | null)?.closest('input,textarea,select,[contenteditable="true"]')) return
    const button = (event.target as Element | null)?.closest<HTMLElement>('button:not(.hero-card), summary, [role="button"]')
    if (!button || button.matches(':disabled, [aria-disabled="true"]')) return
    clear()
    pressed = button
    pointerId = event.pointerId
    startX = event.clientX
    startY = event.clientY
    pressedAt = performance.now()
    removeFeedback(button)
    button.setAttribute('data-original-pressed', '')
    button.setAttribute('data-original-feedback', '')
  }
  const move = (event: PointerEvent) => {
    if (event.pointerId === pointerId && Math.hypot(event.clientX - startX, event.clientY - startY) > 10) clear()
  }
  const up = (event: PointerEvent) => {
    if (event.pointerId !== pointerId || !pressed) return
    const button = pressed
    button.removeAttribute('data-original-pressed')
    pressed = undefined
    pointerId = undefined
    // A quick tap still gets a visible desktop-like fade; actions never wait.
    const minimum = isMotionReduced() ? 0 : 180
    const timer = feedback.get(button)
    if (timer !== undefined) clearTimeout(timer)
    feedback.set(button, setTimeout(() => removeFeedback(button), Math.max(0, minimum - (performance.now() - pressedAt))))
  }
  const cancel = (event: PointerEvent) => { if (event.pointerId === pointerId) clear() }
  const reset = () => { clear(); for (const element of feedback.keys()) removeFeedback(element) }
  const visibility = () => { if (document.visibilityState === 'hidden') reset() }
  const context = (event: Event) => {
    const element = (event.target as Element | null)?.closest('button, summary, [role="button"]')
    if (element && !(event.target as Element).closest('input,textarea,[contenteditable="true"]')) {
      event.preventDefault()
      reset()
    }
  }
  document.addEventListener('pointerdown', down, { capture: true, passive: true })
  document.addEventListener('pointerup', up, { capture: true, passive: true })
  document.addEventListener('pointercancel', cancel, { capture: true, passive: true })
  document.addEventListener('pointermove', move, { capture: true, passive: true })
  document.addEventListener('scroll', reset, { capture: true, passive: true })
  document.addEventListener('contextmenu', context, true)
  document.addEventListener('visibilitychange', visibility)
  window.addEventListener('blur', reset)
  return () => {
    reset()
    document.removeEventListener('pointerdown', down, true)
    document.removeEventListener('pointerup', up, true)
    document.removeEventListener('pointercancel', cancel, true)
    document.removeEventListener('pointermove', move, true)
    document.removeEventListener('scroll', reset, true)
    document.removeEventListener('contextmenu', context, true)
    document.removeEventListener('visibilitychange', visibility)
    window.removeEventListener('blur', reset)
  }
}
