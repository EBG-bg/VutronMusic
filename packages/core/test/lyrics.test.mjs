import test from 'node:test'
import assert from 'node:assert/strict'
import { pairTranslations, selectLyricFamily } from '../src/lyrics.ts'

const fullLrc = '[00:00.00]你好\n[00:02.00]世界'
const fullYrc = '[0,2000](0,1000,0)你(1000,1000,0)好\n[2000,3000](2000,1500,0)世(3500,1500,0)界'

test('chooses full word-timed lyrics over matching LRC', () => {
  const family = selectLyricFamily({ lrc: fullLrc, yrc: fullYrc })
  assert.equal(family.source, 'word-timed')
  assert.deepEqual(family.original.map((line) => line.text), ['你好', '世界'])
  assert.equal(family.original[0].words.length, 2)
})

test('falls back to full LRC when YRC covers only a fragment', () => {
  const family = selectLyricFamily({ lrc: fullLrc, yrc: '[0,500](0,500,0)你' })
  assert.equal(family.source, 'plain')
  assert.deepEqual(family.original.map((line) => line.text), ['你好', '世界'])
})

test('pairs YTLRC with YRC by exact timestamp', () => {
  const family = selectLyricFamily({
    lrc: fullLrc,
    yrc: fullYrc,
    ytlrc: '[00:00.00]Hello\n[00:02.00]World'
  })
  assert.deepEqual(family.pairedTranslations, ['Hello', 'World'])
})

test('does not reuse a translation for a neighboring lyric', () => {
  const original = [{ start: 0, end: 1000, text: 'first', words: [] }, { start: 2000, end: 3000, text: 'second', words: [] }]
  const translation = [{ start: 0, end: 1000, text: 'translation', words: [] }]
  assert.deepEqual(pairTranslations(original, translation), ['translation', ''])
})

test('leaves duplicate timestamp groups unpaired', () => {
  const original = [{ start: 0, end: 500, text: 'a', words: [] }, { start: 0, end: 1000, text: 'b', words: [] }]
  const translation = [{ start: 0, end: 500, text: 'A', words: [] }, { start: 0, end: 1000, text: 'B', words: [] }]
  assert.deepEqual(pairTranslations(original, translation), ['', ''])
})
