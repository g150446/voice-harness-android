import assert from 'node:assert/strict'
import test from 'node:test'
import { measureTextWrap } from '@evenrealities/pretext'
import { IncrementalReadingPaginator } from '../src/paginate.ts'

const oneLine = { width: 568, height: 27 }
const twoLines = { width: 568, height: 54 }

test('withholds a short Kindle tail until more source text arrives', () => {
  const paginator = new IncrementalReadingPaginator(twoLines)
  paginator.reset('短い本文。')

  assert.equal(paginator.takeNextPage(), null)
  assert.equal(paginator.remainingText, '短い本文。')
})

test('withholds a boundary-free sentence even when it reaches the last line', () => {
  const paginator = new IncrementalReadingPaginator(oneLine)
  paginator.reset('句読点のない文章')

  assert.equal(paginator.takeNextPage(), null)
  assert.equal(paginator.remainingText, '句読点のない文章')
})

test('joins a sentence split across Kindle pages before displaying it', () => {
  const paginator = new IncrementalReadingPaginator({ width: 80, height: 54 })
  paginator.reset('吾輩は')
  assert.equal(paginator.takeNextPage(), null)

  paginator.append('猫である。')

  assert.equal(paginator.takeNextPage(), '吾輩は猫である。')
  assert.equal(paginator.remainingText, '')
})

test('inserts a space when an English word continues across source chunks', () => {
  const paginator = new IncrementalReadingPaginator(oneLine)
  paginator.reset('Hello')
  paginator.append('world.')

  assert.equal(paginator.takeNextPage(), 'Hello world.')
})

test('prefers a sentence ending over a later clause ending', () => {
  const paginator = new IncrementalReadingPaginator(twoLines)
  paginator.reset('第一文。\n長い文、\nまだ続く')

  assert.equal(paginator.takeNextPage(), '第一文。')
  assert.equal(paginator.remainingText, '長い文、\nまだ続く')
})

test('uses a clause ending when a sentence is longer than the screen', () => {
  const paginator = new IncrementalReadingPaginator(twoLines)
  paginator.reset('長い文、\n続きます\nまだ続く')

  assert.equal(paginator.takeNextPage(), '長い文、')
})

test('keeps closing quotation marks with sentence punctuation', () => {
  const paginator = new IncrementalReadingPaginator(oneLine)
  paginator.reset('「第一文。」\n次の文')

  assert.equal(paginator.takeNextPage(), '「第一文。」')
})

test('hard splitting keeps Unicode code points intact and within the display', () => {
  const box = { width: 24, height: 54 }
  const paginator = new IncrementalReadingPaginator(box)
  paginator.reset('😀😀😀')

  const page = paginator.takeNextPage()
  assert.equal(page, '😀😀')
  assert.ok(page != null)
  assert.equal(Array.from(page).filter((character) => character === '😀').length, 2)
  assert.ok(measureTextWrap(page, box.width).lineCount <= 2)
})

test('flushes an underfilled remainder after Kindle advance fails', () => {
  const paginator = new IncrementalReadingPaginator(twoLines)
  paginator.reset('最終ページ。')

  assert.equal(paginator.takeNextPage(), null)
  assert.equal(paginator.flushRemainder(), '最終ページ。')
  assert.equal(paginator.remainingText, '')
})

test('materializes only one screen and retains later text for the next request', () => {
  const paginator = new IncrementalReadingPaginator(oneLine)
  paginator.reset('一ページ目。\n二ページ目。\n三ページ目。')

  assert.equal(paginator.takeNextPage(), '一ページ目。')
  assert.equal(paginator.remainingText, '二ページ目。\n三ページ目。')
})
