import test from 'node:test'
import assert from 'node:assert/strict'
import { artworkRequestUrl, artworkUrl } from '../src/artwork.ts'

test('selects the available cover field', () => {
  assert.equal(artworkUrl({ al: { picUrl: 'https://p1.music.126.net/cover.jpg' } }), 'https://p1.music.126.net/cover.jpg')
})

test('requests 512 square artwork from NetEase image hosts', () => {
  const result = new URL(artworkRequestUrl('http://p1.music.126.net/cover.jpg?param=100y100'))
  assert.equal(result.protocol, 'https:')
  assert.equal(result.searchParams.get('param'), '512y512')
})

test('preserves non-NetEase artwork URLs', () => {
  const source = 'http://images.example.com/cover.jpg?size=small'
  assert.equal(artworkRequestUrl(source), source)
})
