import assert from 'node:assert/strict'
import test from 'node:test'
import { paginateHarborSummary } from '../src/harbor.ts'

test('puts the work summary before the user action', () => {
  const pages = paginateHarborSummary(
    '修正とテストを完了しました。',
    '次に何をしますか？\n1. コミット\n2. 追加修正',
    { width: 568, height: 108 },
  )

  assert.match(pages[0], /処理のまとめ/)
  assert.match(pages.join('\n'), /次の指示/)
  assert.match(pages.join('\n'), /1\. コミット/)
})

test('caps a long summary at three pages', () => {
  const pages = paginateHarborSummary(
    '長い処理結果。'.repeat(200),
    '続行しますか？\n1. はい\n2. いいえ',
    { width: 568, height: 108 },
  )

  assert.equal(pages.length, 3)
  assert.match(pages[0], /処理のまとめ/)
  assert.match(pages[0], /長い処理結果/)
  assert.match(pages.join('\n'), /次の指示/)
  assert.match(pages.at(-1) ?? '', /2\. いいえ/)
})

test('omits a blank summary page when only the question remains', () => {
  const pages = paginateHarborSummary(
    '',
    '続行しますか？\n1. はい\n2. いいえ',
    { width: 568, height: 108 },
  )

  assert.equal(pages.length, 1)
  assert.doesNotMatch(pages.join('\n'), /処理のまとめ/)
  assert.match(pages[0], /次の指示/)
  assert.match(pages[0], /2\. いいえ/)
})
