import { reactive, ref, watch, onScopeDispose, type Ref } from 'vue'
import { vutronPlugin } from '../platform/vutronPlugin'
import type { VutronComment, VutronCommentPage, VutronCommentTarget } from '../platform/commentTypes'

export function createCommentThread(target: Ref<VutronCommentTarget>, account: Ref<string>, enabled: Ref<boolean>) {
  const comments = ref<VutronComment[]>([]), total = ref(0), sort = ref<99 | 2 | 3>(99)
  const loading = ref(false), busy = ref(false), error = ref(''), more = ref(true), expired = ref(false)
  const floors = reactive<Record<string, { rows: VutronComment[]; cursor: string; more: boolean; loading: boolean; error: string }>>({})
  let generation = 0, page = 1, cursor = '0'
  const valid = (token: number) => token === generation && enabled.value && !expired.value
  function failure(value: unknown) {
    const code = String((value as { code?: string })?.code ?? '')
    if (['301', '401'].includes(code)) { expired.value = true; return '登录已失效，请在设置中重新登录网易云' }
    return value instanceof Error ? value.message : typeof (value as { message?: unknown })?.message === 'string' ? (value as { message: string }).message : '评论请求失败，请重试'
  }
  function append(rows: VutronComment[], response: VutronCommentPage) {
    const ids = new Set(rows.map(row => row.id))
    const fresh = response.comments.filter(row => !ids.has(row.id) && Boolean(ids.add(row.id)))
    rows.push(...fresh)
    return fresh.length > 0 && response.hasMore
  }
  function reset(preserveBusy = false) {
    generation++; page = 1; cursor = '0'; comments.value = []; total.value = 0
    loading.value = false; if (!preserveBusy) busy.value = false; error.value = ''; more.value = true; expired.value = false
    for (const id of Object.keys(floors)) delete floors[id]
  }
  async function load(resetPage = false, preserveBusy = false) {
    if (resetPage) reset(preserveBusy)
    if (!enabled.value || loading.value || !more.value || expired.value) return
    const token = generation; loading.value = true; error.value = ''
    try {
      let response = await vutronPlugin.getComments({ ...target.value, sort: sort.value, page, cursor })
      if (!valid(token)) return
      // The desktop implementation switches empty recommended feeds to latest.
      if (page === 1 && sort.value === 99 && response.comments.length === 0) {
        sort.value = 3
        response = await vutronPlugin.getComments({ ...target.value, sort: 3, page: 1, cursor: '0' })
        if (!valid(token)) return
      }
      more.value = append(comments.value, response)
      total.value = response.total
      cursor = response.cursor; page = response.nextPage
    } catch (value) { if (token === generation) error.value = failure(value) }
    finally { if (token === generation) loading.value = false }
  }
  async function loadFloor(parent: VutronComment) {
    if (!enabled.value || expired.value) return
    if (!floors[parent.id]) floors[parent.id] = { rows: [], cursor: '0', more: true, loading: false, error: '' }
    const floor = floors[parent.id]
    if (floor.loading || !floor.more) return
    const token = generation; floor.loading = true; floor.error = ''
    try {
      const response = await vutronPlugin.getCommentReplies({ ...target.value, parentId: parent.id, cursor: floor.cursor })
      if (!valid(token)) return
      floor.more = append(floor.rows, response); floor.cursor = response.cursor
    } catch (value) { if (token === generation) floor.error = failure(value) }
    finally { if (token === generation) floor.loading = false }
  }
  async function like(row: VutronComment) {
    if (!enabled.value || expired.value || busy.value || row.deleted) return
    const token = generation, previous = row.liked, count = row.likedCount
    busy.value = true; error.value = ''; row.liked = !previous; row.likedCount = Math.max(0, count + (previous ? -1 : 1))
    try { await vutronPlugin.manageComment({ ...target.value, action: 'like', commentId: row.id, liked: row.liked }) }
    catch (value) { if (token === generation) { row.liked = previous; row.likedCount = count; error.value = failure(value) } }
    finally { if (token === generation) busy.value = false }
  }
  async function submit(action: 'add' | 'reply' | 'delete', text: string, row?: VutronComment) {
    if (!enabled.value || expired.value || busy.value) return false
    const content = text.trim()
    if (action !== 'delete' && (!content || Array.from(content).length > 140)) { error.value = '评论应为 1–140 个字'; return false }
    if (action === 'delete' && (!row || row.userId !== account.value)) return false
    const token = generation; busy.value = true; error.value = ''
    try {
      await vutronPlugin.manageComment({ ...target.value, action, content, commentId: row?.id })
      if (!valid(token)) return false
      // Writes use authoritative refresh, including independent reply pagination.
      const refreshGeneration = generation + 1
      await load(true, true)
      if (generation === refreshGeneration) busy.value = false
      return true
    } catch (value) { if (token === generation) error.value = failure(value); return false }
    finally { if (token === generation) busy.value = false }
  }
  watch([() => JSON.stringify(target.value), account, enabled], () => { reset(); if (enabled.value) void load() }, { immediate: true, flush: 'sync' })
  onScopeDispose(() => { generation++ })
  return { comments, total, sort, loading, busy, error, more, expired, floors, load, loadFloor, like, submit }
}
