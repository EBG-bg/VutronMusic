import { Capacitor } from '@capacitor/core'
import { artworkRequestUrl } from '@vutronmusic/core/artwork'
import { vutronPlugin } from './vutronPlugin'
import { localOnlyMode, isRemoteMusicUri } from './localOnlyMode'

const pending = new Map<string, Promise<string>>()

export function displayArtworkUri(uri: string): string {
  if (localOnlyMode.value && isRemoteMusicUri(uri)) return ''
  if (uri.startsWith('file://') || uri.startsWith('content://') || uri.startsWith('/')) return Capacitor.convertFileSrc(uri)
  return artworkRequestUrl(uri) || uri
}

export function fetchArtwork(source: string): Promise<string> {
  const url = artworkRequestUrl(source)
  if (!url || !Capacitor.isNativePlatform() || !vutronPlugin.getArtwork) return Promise.resolve(displayArtworkUri(source))
  const existing = pending.get(url)
  if (existing) return existing
  const request = vutronPlugin.getArtwork({ url }).then(result => {
    if (!result.uri) throw new Error('封面缓存返回空路径')
    return displayArtworkUri(result.uri)
  }).finally(() => { pending.delete(url) })
  pending.set(url, request)
  return request
}
