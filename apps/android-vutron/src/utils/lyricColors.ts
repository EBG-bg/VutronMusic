export type LyricColorKey = 'playedColor' | 'unplayedColor' | 'backgroundColor' | 'shadowColor'

export interface LyricColorSettings {
  playedColor: number
  unplayedColor: number
  backgroundColor: number
  shadowColor: number
  shadowEnabled?: boolean
  shadowRadius?: number
}

export interface HsvColor { hue: number; saturation: number; value: number; alpha: number }

export const lyricColorTargets: { key: LyricColorKey; label: string }[] = [
  { key: 'playedColor', label: '已唱' },
  { key: 'unplayedColor', label: '未唱' },
  { key: 'backgroundColor', label: '背景' },
  { key: 'shadowColor', label: '阴影' }
]

// Values from the original personal edition's MainActivity.lyricColorSettings.
export const lyricColorPresets: { name: string; values: Partial<Record<LyricColorKey, number>> }[] = [
  { name: '原版绿', values: { playedColor: 0xff37cf88, unplayedColor: 0xffd2d2d2, backgroundColor: 0xb6181920 } },
  { name: '暖金', values: { playedColor: 0xffffdb7a, unplayedColor: 0xffe6e6e9 } },
  { name: '透明背景', values: { backgroundColor: 0x00181920 } }
]

export function clampUnit(value: number): number { return Math.max(0, Math.min(1, value)) }

export function argbHex(value: number): string {
  return '#' + (value >>> 0).toString(16).padStart(8, '0').toUpperCase()
}

/** Android colors use AARRGGBB rather than the CSS RRGGBBAA order. */
export function parseArgbHex(value: string): number | undefined {
  const clean = value.trim()
  if (!/^#(?:[0-9a-f]{6}|[0-9a-f]{8})$/i.test(clean)) return undefined
  return Number.parseInt(clean.length === 7 ? 'FF' + clean.slice(1) : clean.slice(1), 16) >>> 0
}

export function argbCss(value: number): string {
  const unsigned = value >>> 0
  return 'rgba(' + ((unsigned >>> 16) & 255) + ', ' + ((unsigned >>> 8) & 255) + ', ' + (unsigned & 255) + ', ' + ((unsigned >>> 24) / 255) + ')'
}

export function argbToHsv(color: number, previousHue = 0): HsvColor {
  const unsigned = color >>> 0
  const red = ((unsigned >>> 16) & 255) / 255
  const green = ((unsigned >>> 8) & 255) / 255
  const blue = (unsigned & 255) / 255
  const maximum = Math.max(red, green, blue)
  const minimum = Math.min(red, green, blue)
  const difference = maximum - minimum
  let hue = previousHue
  if (difference > 0) {
    hue = maximum === red ? 60 * (((green - blue) / difference) % 6)
      : maximum === green ? 60 * ((blue - red) / difference + 2)
      : 60 * ((red - green) / difference + 4)
    if (hue < 0) hue += 360
  }
  return { hue, saturation: maximum === 0 ? 0 : difference / maximum, value: maximum, alpha: unsigned >>> 24 }
}

export function hsvToArgb(color: HsvColor): number {
  const hue = ((color.hue % 360) + 360) % 360
  const saturation = clampUnit(color.saturation)
  const value = clampUnit(color.value)
  const chroma = value * saturation
  const intermediate = chroma * (1 - Math.abs(((hue / 60) % 2) - 1))
  const minimum = value - chroma
  const channels = hue < 60 ? [chroma, intermediate, 0] : hue < 120 ? [intermediate, chroma, 0]
    : hue < 180 ? [0, chroma, intermediate] : hue < 240 ? [0, intermediate, chroma]
    : hue < 300 ? [intermediate, 0, chroma] : [chroma, 0, intermediate]
  const [red, green, blue] = channels.map(channel => Math.round((channel + minimum) * 255))
  const alpha = Math.max(0, Math.min(255, Math.round(color.alpha)))
  return ((alpha << 24) | (red << 16) | (green << 8) | blue) >>> 0
}
