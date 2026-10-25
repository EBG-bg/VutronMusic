<template>
  <button class="button-icon" @click="handleClick"><slot></slot></button>
</template>

<script setup lang="ts">
interface Props {
  preventBlur?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  preventBlur: false
})

const emit = defineEmits<{
  click: [event: MouseEvent]
}>()

const handleClick = (e: MouseEvent) => {
  emit('click', e)

  if (!props.preventBlur) {
    setTimeout(() => {
      if (document.activeElement instanceof HTMLElement) {
        document.activeElement.blur()
      }
      document.body.focus()
    }, 0)
  }
}
</script>

<style scoped>
button {
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 8px;
  background: transparent;
  margin: 4px;
  border-radius: 25%;
  transition: 0.2s;
}
button :deep(.svg-icon) {
  height: 16px;
  width: 16px;
  color: var(--color-text);
  transition: color 0.3s;
}
button:first-child { margin-left: 0; }
button:hover { background: var(--color-secondary-bg-for-transparent); }
button:active { transform: scale(0.92); }
</style>
