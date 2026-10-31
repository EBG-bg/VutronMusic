<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useAndroidShellStore } from '../stores/androidShell'
import { onlineTrackId } from '../stores/domains/neteaseLibrary'
import { createCommentThread } from '../composables/commentThread'
import type { VutronTrack } from '../platform/vutronPlugin'
import type { VutronComment, VutronCommentTarget } from '../platform/commentTypes'
import { actionDialog, askAction } from '../platform/actionDialog'
import { useModalFocus } from '../composables/modalFocus'
import OriginalIcon from './OriginalIcon.vue'
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
const props = defineProps<{ track: VutronTrack; returnFocus?: HTMLElement }>(), emit = defineEmits<{ close: [] }>()
const store = useAndroidShellStore()
const target = computed<VutronCommentTarget>(() => ({ targetType: 'track', targetId: String(onlineTrackId(props.track)) }))
const account = computed(() => String(store.profile?.userId ?? ''))
const enabled = computed(() => store.onlineAvailable && Number(target.value.targetId) > 0)
const thread = createCommentThread(target, account, enabled)
const { comments, total, sort, loading, busy, error, more, floors, expired } = thread
const text = ref(''), reply = ref<VutronComment>(), composer = ref<HTMLTextAreaElement>(), sheet = ref<HTMLElement>(), sentinel = ref<HTMLElement>()
const length = computed(() => Array.from(text.value).length)
const MAX_AUTO_COMMENTS = 300
let observer: IntersectionObserver | undefined
function date(time: number) { return time > 0 ? new Date(time).toLocaleDateString() : '' }
function replyTo(row: VutronComment) { reply.value = row; void nextTick(() => composer.value?.focus()) }
async function send() { if (await thread.submit(reply.value ? 'reply' : 'add', text.value, reply.value)) { text.value = ''; reply.value = undefined } }
async function remove(row: VutronComment) {
  if (await askAction({ title: t('mobile.comments.delete'), message: t('mobile.comments.deleteHint'), confirmLabel: t('mobile.comments.delete'), cancelLabel: t('mobile.management.cancel') }) === 'confirm')
    await thread.submit('delete', '', row)
}
useModalFocus(sheet, { close: () => emit('close'), enabled: () => !actionDialog.value, returnFocus: () => props.returnFocus })
onMounted(() => {
  observer = new IntersectionObserver(entries => {
    if (entries[0]?.isIntersecting && comments.value.length > 0 && comments.value.length < MAX_AUTO_COMMENTS && !error.value)
      void thread.load()
  }, { root: sheet.value, rootMargin: '100px' })
  if (sentinel.value) observer.observe(sentinel.value)
})
onUnmounted(() => { observer?.disconnect() })
</script>
<template>
  <div class="comments-layer" :inert="Boolean(actionDialog)" @click.self="emit('close')">
    <section ref="sheet" class="comments-sheet" role="dialog" aria-modal="true" :aria-label="$t('mobile.comments.title')" tabindex="-1">
      <header><div><h2>{{ $t('mobile.comments.title') }} <small>{{ total }}</small></h2><p>{{ track.name }}</p></div><button class="icon-button" :aria-label="$t('mobile.common.close')" @click="emit('close')"><OriginalIcon name="close" /></button></header>
      <div class="comment-sort"><button v-for="item in [{ value: 99, label: 'recommended' }, { value: 2, label: 'hot' }, { value: 3, label: 'latest' }]" :key="item.value" :class="{ selected: sort === item.value }" :disabled="busy" @click="sort = item.value as 99 | 2 | 3; thread.load(true)">{{ $t('mobile.comments.' + item.label) }}</button></div>
      <p v-if="!enabled" class="setting-note">{{ $t('mobile.comments.offline') }}</p>
      <p v-if="error" class="inline-error" role="alert">{{ error }} <button v-if="!expired" class="text-button" :disabled="loading" @click="thread.load()">{{ $t('mobile.comments.retry') }}</button></p>
      <p v-if="!loading && !comments.length && !error && enabled" class="setting-note">{{ $t('mobile.comments.empty') }}</p>
      <article v-for="row in comments" :key="row.id" class="comment-row">
        <div class="comment-author"><b>{{ row.nickname }}</b><small>{{ date(row.time) }}</small></div>
        <p class="comment-content">{{ row.content }}</p>
        <blockquote v-for="quoted in row.replies" :key="quoted.id">{{ quoted.nickname ? quoted.nickname + '：' : '' }}{{ quoted.content }}</blockquote>
        <div class="comment-actions">
          <button :disabled="busy || expired || row.deleted" :aria-pressed="row.liked" @click="thread.like(row)"><OriginalIcon :name="row.liked ? 'heartSolid' : 'heart'" />{{ row.likedCount || $t('mobile.comments.like') }}</button>
          <button :disabled="busy || expired || row.deleted" @click="replyTo(row)">{{ $t('mobile.comments.reply') }}</button>
          <button v-if="row.replyCount || floors[row.id]" :disabled="floors[row.id]?.loading || expired" @click="thread.loadFloor(row)">{{ $t('mobile.comments.floor') }} {{ row.replyCount || '' }}</button>
          <button v-if="row.userId === account && !row.deleted" :disabled="busy || expired" @click="remove(row)">{{ $t('mobile.comments.delete') }}</button>
        </div>
        <section v-if="floors[row.id]" class="comment-floor">
          <article v-for="child in floors[row.id].rows" :key="child.id"><b>{{ child.nickname }}</b><p class="comment-content">{{ child.content }}</p><blockquote v-for="quoted in child.replies" :key="quoted.id">{{ quoted.nickname }}：{{ quoted.content }}</blockquote><div class="comment-actions"><button :disabled="busy || expired || child.deleted" :aria-pressed="child.liked" @click="thread.like(child)"><OriginalIcon :name="child.liked ? 'heartSolid' : 'heart'" />{{ child.likedCount || $t('mobile.comments.like') }}</button><button :disabled="busy || expired || child.deleted" @click="replyTo(child)">{{ $t('mobile.comments.reply') }}</button><button v-if="child.userId === account && !child.deleted" :disabled="busy || expired" @click="remove(child)">{{ $t('mobile.comments.delete') }}</button></div></article>
          <p v-if="floors[row.id].error" class="inline-error">{{ floors[row.id].error }}</p>
          <button v-if="floors[row.id].more || floors[row.id].error" class="text-button" :disabled="floors[row.id].loading || expired" @click="thread.loadFloor(row)">{{ $t(floors[row.id].loading ? 'mobile.comments.loading' : 'mobile.comments.moreReplies') }}</button>
        </section>
      </article>
      <p v-if="comments.length" class="comment-count" role="status">{{ $t('mobile.commentPerformance.shown', { count: comments.length, total }) }}<span v-if="comments.length >= MAX_AUTO_COMMENTS && more"> · {{ $t('mobile.commentPerformance.paused') }}</span></p>
      <div ref="sentinel" class="comment-more"><span v-if="loading">{{ $t('mobile.comments.loading') }}</span><button v-else-if="more && enabled && !expired" class="text-button" @click="thread.load()">{{ $t(comments.length >= MAX_AUTO_COMMENTS ? 'mobile.commentPerformance.continue' : 'mobile.comments.more') }}</button><span v-else-if="comments.length">{{ $t('mobile.comments.noMore') }}</span></div>
      <form v-if="enabled" class="comment-composer" @submit.prevent="send">
        <button v-if="reply" class="text-button reply-target" type="button" @click="reply = undefined">{{ $t('mobile.comments.replyTo', { name: reply.nickname }) }}</button>
        <textarea ref="composer" v-model="text" rows="2" :placeholder="$t('mobile.comments.draft')" :aria-label="$t('mobile.comments.content')" :disabled="busy || expired"></textarea>
        <div><small :class="{ 'inline-error': length > 140 }">{{ length }}/140</small><button class="secondary-button" :disabled="busy || expired || !text.trim() || length > 140">{{ $t(busy ? 'mobile.comments.sending' : reply ? 'mobile.comments.reply' : 'mobile.comments.send') }}</button></div>
      </form>
    </section>
  </div>
</template>
<style scoped>
.comments-layer { position:fixed; inset:0; z-index:125; background:rgb(0 0 0 / 38%); display:flex; align-items:flex-end; justify-content:center; }
.comments-sheet { width:min(100%,640px); max-height:90dvh; overflow:auto; overscroll-behavior:contain; background:var(--color-body-bg); color:var(--color-text); border-radius:20px 20px 0 0; padding:16px 16px max(12px,env(safe-area-inset-bottom)); box-shadow:0 -8px 32px rgb(0 0 0 / 18%); }
header { display:flex; align-items:center; gap:12px; } header>div { flex:1;min-width:0; } h2 { margin:0;font-size:18px; } header p { margin:6px 0;color:var(--muted);overflow-wrap:anywhere; } small { color:var(--muted);font-size:12px; }
.comment-sort,.comment-actions { display:flex; gap:6px; flex-wrap:wrap; } .comment-sort { position:sticky;top:-16px;background:var(--color-body-bg);padding:10px 0;z-index:1; }
.comment-sort button,.comment-actions button { border:0;background:transparent;color:inherit;border-radius:9px;padding:8px 10px;transition:background-color 125ms ease,color 125ms ease;display:inline-flex;gap:5px;align-items:center; }
button:is(:focus-visible,[data-original-feedback]),.comment-sort .selected { background:var(--accent-soft); } button[aria-pressed=true] { color:var(--color-primary); } button:disabled { opacity:.45; }
@media(hover:hover) { .comment-actions button:hover,.comment-sort button:hover { background:var(--accent-soft); } }
.comment-row { padding:14px 0;border-bottom:1px solid var(--line); } .comment-author { display:flex;gap:10px;align-items:center;flex-wrap:wrap;font-size:13px; }
.comment-row { content-visibility:auto;contain-intrinsic-size:auto 160px; }
.comment-floor article { content-visibility:auto;contain-intrinsic-size:auto 96px; }
.comment-count { color:var(--muted);font-size:12px;line-height:1.5; }
.comment-content { white-space:pre-wrap;overflow-wrap:anywhere;user-select:text;line-height:1.65;font-size:14px;margin:8px 0; }
blockquote { margin:8px 0;padding:8px 10px;border-radius:9px;background:var(--color-secondary-bg);white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px; }
.comment-actions { font-size:12px;color:var(--muted); } .comment-floor { background:var(--color-secondary-bg);border-radius:12px;padding:10px 12px;margin-top:8px; } .comment-floor article+article { margin-top:12px;border-top:1px solid var(--line);padding-top:10px; }
.comment-more { min-height:44px;display:grid;place-items:center;font-size:12px;color:var(--muted); }
.comment-composer { position:sticky;bottom:0;background:var(--color-body-bg);padding-top:10px;border-top:1px solid var(--line); }
textarea { box-sizing:border-box;width:100%;resize:vertical;max-height:150px;border:1px solid var(--line);border-radius:12px;padding:10px;background:var(--color-secondary-bg);color:inherit;font:inherit;font-size:14px;user-select:text; }
.comment-composer>div { display:flex;align-items:center;justify-content:space-between;gap:10px;margin-top:6px; }.reply-target { margin-bottom:6px;max-width:100%;overflow-wrap:anywhere; }
</style>
