import { computed, ref, watch, type ComputedRef, type Ref } from 'vue'
import type { VutronTrack } from '../platform/vutronPlugin'

export type CollectionOrder = 'original' | 'title' | 'artist' | 'album' | 'custom'
export interface SavedCollectionOrder { mode: CollectionOrder; keys: string[] }
export const ORDER_STORAGE_KEY = 'vutron:collection-orders:v1'
const collator = new Intl.Collator('zh-CN', { numeric: true, sensitivity: 'base' })
export const trackKey = (track: VutronTrack) => track.localKey || (track.localEntryKey ? track.localPlaylistId + ':' + track.localEntryKey : track.id + ':' + (track.localUri || ''))

export function applyCollectionOrder<T extends VutronTrack>(source: T[], saved: SavedCollectionOrder): T[] {
  if (saved.mode === 'original') return source
  if (saved.mode === 'custom') {
    const ranks = new Map(saved.keys.map((key, index) => [key, index]))
    return [...source].sort((a, b) => (ranks.get(trackKey(a)) ?? Infinity) - (ranks.get(trackKey(b)) ?? Infinity))
  }
  const field = (track: T) => saved.mode === 'artist' ? track.artists?.join(' / ') || '' : saved.mode === 'album' ? track.album || '' : track.name
  return [...source].sort((a, b) => collator.compare(field(a), field(b)) || collator.compare(a.name, b.name))
}

export function moveTrackKey(keys: string[], moving: string, anchor: string, after = false): string[] {
  if (moving === anchor || !keys.includes(moving) || !keys.includes(anchor)) return keys
  const next = keys.filter(key => key !== moving)
  next.splice(next.indexOf(anchor) + (after ? 1 : 0), 0, moving)
  return next
}

function readOrders(): Record<string, SavedCollectionOrder> {
  try {
    const raw = JSON.parse(localStorage.getItem(ORDER_STORAGE_KEY) || '{}')
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {}
    return Object.fromEntries(Object.entries(raw).filter(([, value]) => {
      const item = value as SavedCollectionOrder
      return item && ['original', 'title', 'artist', 'album', 'custom'].includes(item.mode) && Array.isArray(item.keys) && item.keys.every(key => typeof key === 'string')
    })) as Record<string, SavedCollectionOrder>
  } catch { return {} }
}

export function useCollectionTrackOrder(source: ComputedRef<VutronTrack[]>, collection: Ref<string>) {
  const records = ref(readOrders())
  const saved = computed(() => records.value[collection.value] || { mode: 'original' as CollectionOrder, keys: [] })
  function save(value: SavedCollectionOrder) {
    const next = { ...readOrders(), [collection.value]: value }
    localStorage.setItem(ORDER_STORAGE_KEY, JSON.stringify(next))
    records.value = next
  }
  const mode = computed<CollectionOrder>({ get: () => saved.value.mode, set: value => save({ mode: value, keys: saved.value.keys }) })
  const ordered = computed(() => applyCollectionOrder(source.value, saved.value))
  function commit(keys: string[]) { save({ mode: 'custom', keys }) }
  function restore() { save({ mode: 'original', keys: [] }) }
  watch(collection, () => { records.value = readOrders() })
  return { mode, ordered, commit, restore }
}
