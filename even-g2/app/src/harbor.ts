import { paginate, type PaginateBox } from './paginate'

const MAX_HARBOR_PAGES = 3

function withTruncation(page: string, truncated: boolean): string {
  if (!truncated) return page
  return `${page.replace(/\s+$/u, '')}\n…`
}

function capPages(pages: string[], limit: number): string[] {
  if (limit <= 0 || pages.length === 0) return []
  const selected = pages.slice(0, limit)
  if (pages.length > selected.length) {
    selected[selected.length - 1] = withTruncation(selected[selected.length - 1], true)
  }
  return selected
}

/** Summary pages always precede the verbatim question/choice pages. */
export function paginateHarborSummary(
  summary: string,
  action: string,
  box: PaginateBox,
): string[] {
  const summaryText = summary.trim()
  const actionText = action.trim()
  const summaryPages = summaryText ? paginate(`処理のまとめ\n${summaryText}`, box) : []
  if (!actionText) return capPages(summaryPages, MAX_HARBOR_PAGES)

  const actionPages = paginate(`次の指示\n${actionText}`, box)
  if (summaryPages.length === 0) return capPages(actionPages, MAX_HARBOR_PAGES)

  const actionSlots = Math.min(2, actionPages.length)
  return [
    ...capPages(summaryPages, MAX_HARBOR_PAGES - actionSlots),
    ...capPages(actionPages, actionSlots),
  ]
}
