interface LiveSettingWriterOptions<T> {
  write(value: T): Promise<void>
  merge?(previous: T, next: T): T
  onError?(error: unknown): void
  intervalMs?: number
}

/** One native request at a time; intermediate drag values collapse into latest. */
export function createLiveSettingWriter<T>(options: LiveSettingWriterOptions<T>) {
  const interval = options.intervalMs ?? 64
  const merge = options.merge ?? ((_previous: T, next: T) => next)
  let queued: { value: T; revision: number } | undefined
  let running: Promise<void> | undefined
  let timer: ReturnType<typeof setTimeout> | undefined
  let revision = 0
  let lastStarted = Date.now()
  let attempted = 0
  let retryBlocked = false
  const waiters: { revision: number; resolve(): void }[] = []

  function settle(through: number) {
    for (let index = waiters.length - 1; index >= 0; index--) {
      if (waiters[index].revision <= through) waiters.splice(index, 1)[0].resolve()
    }
  }
  function schedule() {
    if (timer !== undefined || running || !queued || retryBlocked) return
    timer = setTimeout(() => { timer = undefined; void commit() }, Math.max(0, interval - (Date.now() - lastStarted)))
  }
  function commit(): Promise<void> {
    if (running) return running
    if (!queued) return Promise.resolve()
    if (timer !== undefined) clearTimeout(timer)
    timer = undefined
    const next = queued
    queued = undefined
    retryBlocked = false
    attempted = next.revision
    lastStarted = Date.now()
    running = Promise.resolve().then(async () => {
      try { await options.write(next.value) }
      catch (error) {
        // Keep the failed draft, but retry only after another edit or flush.
        const newer = queued as { value: T; revision: number } | undefined
        queued = newer ? { value: merge(next.value, newer.value), revision: newer.revision } : next
        retryBlocked = !newer
        options.onError?.(error)
      } finally {
        settle(next.revision)
        running = undefined
        schedule()
      }
    })
    return running
  }
  function queue(value: T): Promise<void> {
    const current = ++revision
    queued = { value: queued ? merge(queued.value, value) : value, revision: current }
    retryBlocked = false
    const promise = new Promise<void>(resolve => waiters.push({ revision: current, resolve }))
    schedule()
    return promise
  }
  async function flush(): Promise<void> {
    if (timer !== undefined) clearTimeout(timer)
    timer = undefined
    retryBlocked = false
    if (running) await running
    if (queued) {
      const target = queued.revision
      await commit()
      // An edit arriving during this flush must also reach its final value.
      while (queued && queued.revision > Math.max(target, attempted) && !retryBlocked) await commit()
    }
  }
  return { queue, flush, pending: () => Boolean(queued || running), revision: () => revision }
}
