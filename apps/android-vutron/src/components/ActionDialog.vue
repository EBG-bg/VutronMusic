<script setup lang="ts">
import { nextTick, onUnmounted, ref, watch } from 'vue'
import { actionDialog, settleAction } from '../platform/actionDialog'
const dialog = ref<HTMLElement>()
let previous: HTMLElement | null = null
function key(event: KeyboardEvent) {
  if (!actionDialog.value) return
  if (event.key === 'Escape') { event.preventDefault(); event.stopImmediatePropagation(); settleAction('cancel') }
  if (event.key !== 'Tab') return
  const buttons = [...dialog.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? []]
  const first = buttons[0], last = buttons.at(-1)
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
watch(actionDialog, async value => {
  if (value) {
    previous = document.activeElement as HTMLElement | null
    document.addEventListener('keydown', key, true)
    await nextTick(); dialog.value?.querySelector<HTMLButtonElement>('[data-default-action]')?.focus()
  } else { document.removeEventListener('keydown', key, true); previous?.focus({ preventScroll: true }) }
})
onUnmounted(() => document.removeEventListener('keydown', key, true))
</script>
<template>
  <Transition name="notice-fade"><div v-if="actionDialog" class="action-dialog-layer" @click.self="settleAction('cancel')"><section ref="dialog" role="dialog" aria-modal="true" aria-labelledby="action-dialog-title" aria-describedby="action-dialog-message" class="action-dialog"><h2 id="action-dialog-title">{{ actionDialog.title }}</h2><p id="action-dialog-message">{{ actionDialog.message }}</p><div class="action-dialog-buttons"><button class="text-button" type="button" @click="settleAction('cancel')">{{ actionDialog.cancelLabel }}</button><button v-if="actionDialog.secondaryLabel" class="secondary-button" type="button" :disabled="actionDialog.secondaryDisabled" @click="settleAction('secondary')">{{ actionDialog.secondaryLabel }}</button><button data-default-action class="primary-button" type="button" @click="settleAction('confirm')">{{ actionDialog.confirmLabel }}</button></div></section></div></Transition>
</template>
<style scoped>
.action-dialog-layer { position: fixed; inset: 0; z-index: 500; display: grid; align-items: center; padding: 20px; background: rgb(0 0 0 / 42%); }
.action-dialog { width: min(100%, 460px); margin: auto; padding: 20px; border-radius: 18px; color: var(--ink); background: var(--color-body-bg); box-shadow: 0 16px 60px #0005; }
h2 { margin: 0 0 10px; font-size: 17px; } p { margin: 0; font-size: 13px; white-space: pre-line; line-height: 1.7; overflow-wrap: anywhere; }
.action-dialog-buttons { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; margin-top: 18px; }
</style>
