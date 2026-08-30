import { measureTextWrap } from '@evenrealities/pretext'

const LINE_HEIGHT = 27

export interface PaginateBox {
  width: number
  height: number
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
