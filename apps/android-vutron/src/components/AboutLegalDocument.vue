<script setup lang="ts">
import { onMounted, ref } from 'vue'
const props = defineProps<{ documentId: 'privacy' | 'usage' | 'license' | 'notice' | 'thirdParty' }>()
const text = ref(''), failed = ref(false)
const loaders = {
  privacy: () => import('../../../../legal/PRIVACY.md?raw'),
  usage: () => import('../../../../legal/USE_POLICY.md?raw'),
  license: () => import('../../../../LICENSE?raw'),
  notice: () => import('../../../../THIRD_PARTY_NOTICES.md?raw'),
  thirdParty: () => import('../../../../legal/THIRD_PARTY_LICENSES.txt?raw')
}
onMounted(async () => { try { text.value = (await loaders[props.documentId]()).default } catch { failed.value = true } })
</script>
<template><pre v-if="text" class="about-document" tabindex="0">{{ text }}</pre><p v-else class="setting-note" role="status">{{ $t(failed ? 'mobile.about.documentFailed' : 'mobile.about.loading') }}</p></template>
<style scoped>
.about-document { max-height:55dvh; overflow:auto; margin:0; padding:2px; white-space:pre-wrap; overflow-wrap:anywhere; user-select:text; -webkit-user-select:text; font:11px/1.7 system-ui,sans-serif; color:var(--ink); }
</style>
