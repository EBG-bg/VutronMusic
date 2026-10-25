import test from 'node:test'
import assert from 'node:assert/strict'
import { parseLrc, selectLyricFamily } from '../src/lyrics.ts'

test('enhanced LRC keeps exact word starts, terminal ends and whitespace', () => {
  const [line] = parseLrc('[00:01.00]<00:01.00>Hello <00:01.50>world<00:02.25>')
  assert.equal(line.start, 1000)
  assert.equal(line.text, 'Hello world')
  assert.deepEqual(line.words, [{ start: 1000, end: 1500, text: 'Hello ' }, { start: 1500, end: 2250, text: 'world' }])
})
test('WRC timestamps precede each word and preserve a final unclosed word', () => {
  const lines = parseLrc('[00:01.0]你[00:01.25]好[00:02.000]\n[00:03.0]世[00:03.5]界\n[00:05]next')
  assert.deepEqual(lines.map(row => row.text), ['你好', '世界', 'next'])
  assert.deepEqual(lines[0].words.map(row => [row.start, row.end]), [[1000, 1250], [1250, 2000]])
  assert.equal(lines[1].words[1].end, 5000)
})
test('ordinary repeated timestamps and untimed text remain ordinary LRC', () => {
  const lines = parseLrc('[ar:artist]\n[00:01][00:03]same lyric\n[00:05]plain <tag>')
  assert.deepEqual(lines.map(row => [row.start, row.text, row.words.length]), [[1000, 'same lyric', 0], [3000, 'same lyric', 0], [5000, 'plain <tag>', 0]])
})
test('LRC offset shifts both line and word clocks without changing translation pairing', () => {
  const family = selectLyricFamily({ lrc: '[offset:100]\n[00:01]<00:01>a<00:02>b<00:03>', tlyric: '[offset:100]\n[00:01]translation' })
  assert.equal(family.source, 'word-timed')
  assert.equal(family.original[0].start, 900)
  assert.equal(family.original[0].words[0].start, 900)
  assert.equal(family.original[0].words[1].end, 2900)
  assert.deepEqual(family.pairedTranslations, ['translation'])
})
test('decreasing timestamps keep readable text without inventing word times', () => {
  const [line] = parseLrc('[00:02]<00:02>one<00:01>two<00:03>')
  assert.equal(line.text, 'onetwo')
  assert.deepEqual(line.words, [])
})
test('mixed enhanced and plain lines survive incomplete YRC fallback', () => {
  const family = selectLyricFamily({ lrc: '[00:00]Intro\n[00:02]<00:02>你<00:03>好<00:04>\n[00:06]Outro', yrc: '[2000,500](2000,500,0)你' })
  assert.deepEqual(family.original.map(row => row.text), ['Intro', '你好', 'Outro'])
  assert.equal(family.original[1].words.length, 2)
  assert.equal(family.source, 'word-timed')
})
