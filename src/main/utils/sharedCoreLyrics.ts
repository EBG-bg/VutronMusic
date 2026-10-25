import { selectLyricFamily, type LyricLine as CoreLyricLine } from '../../../packages/core/src/lyrics'
import type { LyricLine, Word } from '@/types/plugin'

function toWord(start: number, end: number, text: string): Word {
  return { start, end, word: text }
}

function toPluginLine(line: CoreLyricLine, translation: string): LyricLine {
  const result: LyricLine = {
    start: line.start / 1000,
    end: line.end / 1000,
    lyric: {
      text: line.text,
      ...(line.words.length > 0
        ? { info: line.words.map((word) => toWord(word.start, word.end, word.text)) }
        : {})
    }
  }
  if (translation) result.tlyric = { text: translation }
  return result
}

/** Shared desktop/Android lyric selection and timestamp pairing adapter. */
export function parseSharedLyricPayload(payload: {
  lrc?: { lyric?: string }
  tlyric?: { lyric?: string }
  yrc?: { lyric?: string }
  ytlrc?: { lyric?: string }
}): LyricLine[] {
  const family = selectLyricFamily({
    lrc: payload.lrc?.lyric,
    tlyric: payload.tlyric?.lyric,
    yrc: payload.yrc?.lyric,
    ytlrc: payload.ytlrc?.lyric
  })
  return family.original.map((line, index) => toPluginLine(line, family.pairedTranslations[index] ?? ''))
}

export function parseSharedLyricText(value: string): LyricLine[] {
  return parseSharedLyricPayload({ lrc: { lyric: value } })
}
