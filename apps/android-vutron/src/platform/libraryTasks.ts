import { reactive } from 'vue'
import type { PluginListenerHandle } from '@capacitor/core'
import { vutronPlugin, type VutronLibraryTask } from './vutronPlugin'
export const libraryTask = reactive<VutronLibraryTask>({ kind: '', running: false, done: 0, total: 0 })
let listener: PluginListenerHandle | undefined
let generation = 0
export async function initializeLibraryTasks() {
  if (!vutronPlugin.getLibraryTaskState) return
  const token = ++generation
  try {
    const next = await vutronPlugin.addListener('libraryTaskChanged', state => { if (token === generation) Object.assign(libraryTask, state) })
    if (token !== generation) { await next.remove(); return }
    listener = next
    const state = await vutronPlugin.getLibraryTaskState()
    if (token === generation) Object.assign(libraryTask, state)
  } catch { /* Progress is optional; the operation itself reports errors. */ }
}
export function disposeLibraryTasks() { ++generation; void listener?.remove(); listener = undefined }
export async function cancelLibraryTask() {
  if (!libraryTask.running || !vutronPlugin.cancelLibraryTask) return
  Object.assign(libraryTask, await vutronPlugin.cancelLibraryTask())
}
