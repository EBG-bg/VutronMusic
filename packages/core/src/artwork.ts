export function artworkUrl(value: any): string {
  if (!value) return ''
  const direct = [value.coverImgUrl, value.picUrl, value.cover]
  for (const candidate of direct) if (isHttpUrl(candidate)) return candidate.trim()
  for (const album of [value.al, value.album]) {
    for (const candidate of [album?.picUrl, album?.coverImgUrl, album?.cover]) {
      if (isHttpUrl(candidate)) return candidate.trim()
    }
  }
  return isHttpUrl(value.albumPicUrl) ? value.albumPicUrl.trim() : ''
}

export function isHttpUrl(value: unknown): value is string {
  if (typeof value !== 'string' || !value.trim()) return false
  try {
    const parsed = new URL(value)
    return parsed.protocol === 'http:' || parsed.protocol === 'https:'
  } catch {
    return false
  }
}

export function artworkRequestUrl(source: string): string {
  if (!isHttpUrl(source)) return ''
  const url = new URL(source)
  if (url.hostname !== 'music.126.net' && !url.hostname.endsWith('.music.126.net')) return source
  url.protocol = 'https:'
  url.searchParams.set('param', '512y512')
  return url.toString()
}
