export interface LyricWord {
  start: number
  end: number
  text: string
}

export interface LyricLine {
  start: number
  end: number
  text: string
  words: LyricWord[]
}

export interface LyricFamily {
  original: LyricLine[]
  translation: LyricLine[]
  pairedTranslations: string[]
  source: 'word-timed' | 'plain' | 'empty'
}

const lrcLine = /^(?<stamps>(?:\[.+?\])+)(?!\[)(?<text>.*)$/gm
const lrcStamp = /\[(?<minutes>\d+):(?<seconds>\d+)(?:[.:](?<milliseconds>\d{1,3}))?\]/g
const yrcLine = /^\[(?<start>\d+),(?<duration>\d+)\](?<content>.*)$/
const yrcWord = /\((?<start>\d+),(?<duration>\d+),\d+\)(?<text>.*?)(?=\(\d+,\d+,\d+\)|$)/g

function stampToMs(match: RegExpExecArray): number {
  const milliseconds = match.groups?.milliseconds ?? ''
  const fraction = milliseconds ? Number(milliseconds.padEnd(3, '0')) : 0
  return Number(match.groups?.minutes ?? 0) * 60_000
    + Number(match.groups?.seconds ?? 0) * 1_000
    + fraction
}

export function parseLrc(value: string | undefined | null): LyricLine[] {
  if (!value) return []
  const lines: LyricLine[] = []
  for (const match of value.trim().matchAll(lrcLine)) {
    const stamps = [...match.groups!.stamps.matchAll(lrcStamp)]
    const text = match.groups!.text.trim()
    for (const stamp of stamps) {
      lines.push({ start: stampToMs(stamp), end: 0, text, words: [] })
    }
  }
  lines.sort((a, b) => a.start - b.start)
  lines.forEach((line, index) => {
    line.end = lines[index + 1]?.start ?? line.start + 3_000
  })
  return lines
}

export function parseYrc(value: string | undefined | null): LyricLine[] {
  if (!value) return []
  const lines: LyricLine[] = []
  for (const raw of value.trim().split(/\r?\n/)) {
    const line = yrcLine.exec(raw)
    if (!line) continue
    const words: LyricWord[] = []
    let text = ''
    for (const word of line.groups!.content.matchAll(yrcWord)) {
      const wordText = word.groups?.text ?? ''
      const start = Number(word.groups?.start ?? 0)
      const end = start + Number(word.groups?.duration ?? 0)
      text += wordText
      words.push({ start, end, text: wordText })
    }
    if (!text.trim()) continue
    const start = Number(line.groups!.start)
    lines.push({ start, end: start + Number(line.groups!.duration), text, words })
  }
  return lines.sort((a, b) => a.start - b.start)
}

function coverage(lines: LyricLine[]): { characters: number; span: number } {
  let characters = 0
  let first = -1
  let last = 0
  for (const line of lines) {
    const count = [...line.text].filter((char) => /[\p{L}\p{N}]/u.test(char)).length
    if (!count) continue
    characters += count
    if (first < 0) first = line.start
    last = Math.max(last, line.end)
  }
  return { characters, span: first < 0 ? 0 : Math.max(0, last - first) }
}

export function hasComparableCoverage(candidate: LyricLine[], reference: LyricLine[]): boolean {
  const current = coverage(candidate)
  const full = coverage(reference)
  if (!current.characters) return false
  if (!full.characters) return true
  return current.characters * 5 >= full.characters * 4 && current.span * 5 >= full.span * 4
}

export function pairTranslations(original: LyricLine[], translation: LyricLine[]): string[] {
  const result = original.map(() => '')
  let source = 0
  let translated = 0
  while (source < original.length && translated < translation.length) {
    const left = original[source]
    const right = translation[translated]
    if (left.start < right.start) { source++; continue }
    if (right.start < left.start) { translated++; continue }
    const sourceEnd = source + 1
    const translationEnd = translated + 1
    const duplicateSource = original[sourceEnd]?.start === left.start
    const duplicateTranslation = translation[translationEnd]?.start === right.start
    if (!duplicateSource && !duplicateTranslation && left.text && right.text !== left.text) {
      result[source] = right.text
    }
    while (source < original.length && original[source].start === left.start) source++
    while (translated < translation.length && translation[translated].start === right.start) translated++
  }
  return result
}

export function selectLyricFamily(payload: {
  lrc?: string
  tlyric?: string
  yrc?: string
  ytlrc?: string
}): LyricFamily {
  const plain = parseLrc(payload.lrc)
  const translatedPlain = parseLrc(payload.tlyric)
  const words = parseYrc(payload.yrc)
  const translatedWords = parseLrc(payload.ytlrc)
  const useWords = hasComparableCoverage(words, plain)
    && (translatedWords.length > 0 || plain.length === 0 || translatedPlain.length === 0)
  const original = useWords ? words : plain
  const translation = useWords ? translatedWords : translatedPlain
  return {
    original,
    translation,
    pairedTranslations: pairTranslations(original, translation),
    source: original.length === 0 ? 'empty' : useWords ? 'word-timed' : 'plain'
  }
}
