import { measureTextWrap } from '@evenrealities/pretext'

const LINE_HEIGHT = 27

const SENTENCE_ENDINGS = new Set(Array.from('。！？.!?'))
const CLAUSE_ENDINGS = new Set(Array.from('、，,;:；：'))
const CLOSING_MARKS = new Set(Array.from('"\'\u2019\u201d」』】〕）)]〉》'))

export interface PaginateBox {
  width: number
  height: number
}

/**
 * Incremental paginator for Kindle reader mode.
 *
 * A short tail is intentionally withheld until another Kindle source page is
 * appended. This lets the caller fill the G2 screen without exposing Kindle's
 * physical page boundary to the reader.
 */
export class IncrementalReadingPaginator {
  private readonly maxLines: number
  private buffer = ''

  constructor(private readonly box: PaginateBox) {
    this.maxLines = Math.max(1, Math.floor(box.height / LINE_HEIGHT))
  }

  reset(source: string): void {
    this.buffer = normalizeSource(source)
  }

  append(source: string): void {
    const incoming = normalizeSource(source)
    if (!incoming) return
    if (!this.buffer) {
      this.buffer = incoming
      return
    }
    this.buffer = `${this.buffer}${sourceJoiner(this.buffer, incoming)}${incoming}`
  }

  /** Returns null when another Kindle page is needed to fill the next screen. */
  takeNextPage(): string | null {
    if (!this.buffer) return null
    const characters = Array.from(this.buffer)
    const fittingLength = largestFittingPrefix(characters, this.box.width, this.maxLines)
    const allTextFits = fittingLength === characters.length
    const lineCount = measureTextWrap(this.buffer, this.box.width).lineCount
    if (allTextFits && lineCount < this.maxLines) return null

    const preferred = preferredBoundary(characters, fittingLength)
    // If Kindle ended in the middle of a boundary-free sentence, fetch its
    // continuation before falling back to a hard display-capacity split.
    if (allTextFits && preferred === 0) return null
    const boundary = preferred || fittingLength
    return this.consume(boundary)
  }

  /** Emits a short tail after Kindle advance fails or reaches the end of the book. */
  flushRemainder(): string | null {
    if (!this.buffer) return null
    const page = this.takeNextPage()
    if (page != null) return page
    return this.consume(Array.from(this.buffer).length)
  }

  get remainingText(): string {
    return this.buffer
  }

  private consume(characterCount: number): string | null {
    const characters = Array.from(this.buffer)
    const page = characters.slice(0, characterCount).join('').trim()
    this.buffer = characters.slice(characterCount).join('').trimStart()
    return page || null
  }
}

export function paginate(source: string, box: PaginateBox): string[] {
  const maxLines = Math.max(1, Math.floor(box.height / LINE_HEIGHT))
  const paragraphs = source.split(/\n{2,}/).map((part) => part.trim()).filter(Boolean)
  const pages: string[] = []
  let buffer: string[] = []
  let bufferLines = 0

  const flush = () => {
    if (buffer.length > 0) pages.push(buffer.join('\n\n'))
    buffer = []
    bufferLines = 0
  }

  for (const paragraph of paragraphs) {
    const lines = measureTextWrap(paragraph, box.width).lineCount
    if (lines > maxLines) {
      flush()
      pages.push(...splitParagraph(paragraph, box.width, maxLines))
      continue
    }
    const cost = lines + (buffer.length > 0 ? 1 : 0)
    if (bufferLines + cost > maxLines) flush()
    buffer.push(paragraph)
    bufferLines += lines + (buffer.length > 1 ? 1 : 0)
  }
  flush()
  return pages.length > 0 ? pages : ['']
}

function splitParagraph(text: string, width: number, maxLines: number): string[] {
  const characters = Array.from(text)
  const chunks: string[] = []
  let offset = 0
  while (offset < characters.length) {
    let low = 1
    let high = characters.length - offset
    let best = 1
    while (low <= high) {
      const middle = Math.floor((low + high) / 2)
      const candidate = characters.slice(offset, offset + middle).join('')
      if (measureTextWrap(candidate, width).lineCount <= maxLines) {
        best = middle
        low = middle + 1
      } else {
        high = middle - 1
      }
    }
    chunks.push(characters.slice(offset, offset + best).join(''))
    offset += best
  }
  return chunks.filter((chunk) => chunk.length > 0)
}

function normalizeSource(source: string): string {
  return source.replace(/\r\n?/g, '\n').trim()
}

function sourceJoiner(current: string, incoming: string): string {
  const previous = Array.from(current).at(-1) ?? ''
  const next = Array.from(incoming)[0] ?? ''
  return /[A-Za-z0-9]/.test(previous) && /[A-Za-z0-9]/.test(next) ? ' ' : ''
}

function largestFittingPrefix(characters: string[], width: number, maxLines: number): number {
  let low = 1
  let high = characters.length
  let best = 0
  while (low <= high) {
    const middle = Math.floor((low + high) / 2)
    const candidate = characters.slice(0, middle).join('')
    if (measureTextWrap(candidate, width).lineCount <= maxLines) {
      best = middle
      low = middle + 1
    } else {
      high = middle - 1
    }
  }
  return Math.max(1, best)
}

function preferredBoundary(characters: string[], limit: number): number {
  let sentenceBoundary = 0
  let clauseBoundary = 0
  let whitespaceBoundary = 0
  for (let index = 0; index < limit; index += 1) {
    const character = characters[index]
    if (SENTENCE_ENDINGS.has(character)) {
      sentenceBoundary = includeClosingMarks(characters, index + 1, limit)
    } else if (character === '\n' && characters[index + 1] === '\n') {
      sentenceBoundary = index
    } else if (CLAUSE_ENDINGS.has(character)) {
      clauseBoundary = includeClosingMarks(characters, index + 1, limit)
    } else if (/\s/u.test(character)) {
      whitespaceBoundary = index
    }
  }
  return sentenceBoundary || clauseBoundary || whitespaceBoundary
}

function includeClosingMarks(characters: string[], start: number, limit: number): number {
  let end = start
  while (end < limit && CLOSING_MARKS.has(characters[end])) end += 1
  return end
}
