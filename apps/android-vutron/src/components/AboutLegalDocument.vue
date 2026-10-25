<script setup lang="ts">
import { onMounted, ref } from 'vue'
const props = defineProps<{ documentId: 'privacy' | 'usage' | 'license' | 'notice' | 'thirdParty' }>()
const text = ref(''), failed = ref(false)
const loaders = {
  privacy: () => import('../../../../legal/PRIVACY.md?url'),
  usage: () => import('../../../../legal/USE_POLICY.md?url'),
  license: () => import('../../../../LICENSE?url'),
  notice: () => import('../../../../THIRD_PARTY_NOTICES.md?url'),
  thirdParty: () => import('../../../../legal/THIRD_PARTY_LICENSES.txt?url')
}
onMounted(async () => {
  try {
    const response = await fetch((await loaders[props.documentId]()).default)
    if (!response.ok) throw new Error('Local legal document unavailable')
    text.value = await response.text()
  } catch { failed.value = true }
})
</script>
<template><pre v-if="text" class="about-document" tabindex="0">{{ text }}</pre><p v-else class="setting-note" role="status">{{ $t(failed ? 'mobile.about.documentFailed' : 'mobile.about.loading') }}</p></template>
<style scoped>
.about-document { max-height:55dvh; overflow:auto; margin:0; padding:2px; white-space:pre-wrap; overflow-wrap:anywhere; user-select:text; -webkit-user-select:text; font:11px/1.7 system-ui,sans-serif; color:var(--ink); }
</style>
