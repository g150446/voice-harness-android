import {
  CreateStartUpPageContainer,
  OsEventTypeList,
  TextContainerProperty,
  TextContainerUpgrade,
  waitForEvenAppBridge,
} from '@evenrealities/even_hub_sdk'
import { IncrementalReadingPaginator, paginate } from './paginate'
import { measureTextWrap } from '@evenrealities/pretext'

const BODY_WIDTH = 576
const BODY_HEIGHT = 288
const BODY_PADDING = 4
const INNER_WIDTH = BODY_WIDTH - BODY_PADDING * 2
const INNER_HEIGHT = BODY_HEIGHT - BODY_PADDING * 2
const CONTAINER_ID = 1
const CONTAINER_NAME = 'main'
const READING_URL = 'http://127.0.0.1:8787/api/v1/reading'
const ADVANCE_URL = 'http://127.0.0.1:8787/api/v1/reading/advance'

type DisplayMode = 'idle' | 'response' | 'reading' | 'harbor'

interface ReadingState {
  enabled: boolean
  active: boolean
  mode?: DisplayMode
  revision: number
  title?: string | null
  bodyText: string | null
  loading: boolean
  error: string | null
  doubleTapCount?: number
  singleTapCount?: number
}

const bridge = await waitForEvenAppBridge()
const mainText = new TextContainerProperty({
  xPosition: 0,
  yPosition: 0,
  width: BODY_WIDTH,
  height: BODY_HEIGHT,
  borderWidth: 0,
  borderColor: 5,
  paddingLength: BODY_PADDING,
  containerID: CONTAINER_ID,
  containerName: CONTAINER_NAME,
  content: 'Voice Harness\n\nEven G2\nWaiting for Android…',
  isEventCapture: 1,
})

const created = await bridge.createStartUpPageContainer(
  new CreateStartUpPageContainer({ containerTotalNum: 1, textObject: [mainText] }),
)
if (created !== 0) console.error('createStartUpPageContainer failed:', created)

let pages: string[] = []
let currentPage = 0
let currentRevision = -1
let currentMode: DisplayMode = 'idle'
let lastSingleTapCount: number | null = null
let awaitingAdvanceRevision: number | null = null
let blockedAdvanceRevision: number | null = null
let pendingReadingPage = false
let lastHarborTitle: string | null = null
let rendering: Promise<unknown> = Promise.resolve()
const readingPaginator = new IncrementalReadingPaginator({
  width: INNER_WIDTH,
  height: INNER_HEIGHT,
})

function resolveMode(state: ReadingState): DisplayMode {
  if (state.mode === 'response' || state.mode === 'reading' || state.mode === 'harbor' || state.mode === 'idle') {
    return state.mode
  }
  if (state.active && state.bodyText) return 'reading'
  return 'idle'
}

function harborTail(source: string): string {
  const normalized = source.replace(/\r\n?/g, '\n').replace(/\s+$/u, '')
  if (!normalized) return 'Terminal Harbor\n\n出力はありません'
  const characters = Array.from(normalized)
  const maxLines = Math.max(1, Math.floor(INNER_HEIGHT / 27))
  let low = 1
  let high = characters.length
  let best = characters.length
  while (low <= high) {
    const length = Math.floor((low + high) / 2)
    const candidate = characters.slice(characters.length - length).join('')
    if (measureTail(candidate) <= maxLines) {
      best = length
      low = length + 1
    } else {
      high = length - 1
    }
  }
  return characters.slice(characters.length - best).join('').replace(/^\n+/u, '')
}

function measureTail(value: string): number {
  return measureTextLines(value)
}

function measureTextLines(value: string): number {
  // Preserve single newlines for terminal rows; Pretext measures wrapping within each row.
  return value.split('\n').reduce((total, row) => {
    return total + Math.max(1, measureTextWrap(row || ' ', INNER_WIDTH).lineCount)
  }, 0)
}

function idleMessage(state: ReadingState): string {
  if (state.error) return `Voice Harness\n\n${state.error}`
  if (state.enabled) return 'Voice Harness\n\nリーダーモード待機中'
  return 'Voice Harness\n\nEven G2\nPlugin connected'
}

function eventTypeOf(envelope?: { eventType?: OsEventTypeList }): OsEventTypeList | null {
  if (!envelope) return null
  return envelope.eventType ?? OsEventTypeList.CLICK_EVENT
}

function textUpgrade(content: string): Promise<void> {
  rendering = rendering.then(async () => {
    await bridge.textContainerUpgrade(new TextContainerUpgrade({
      containerID: CONTAINER_ID,
      containerName: CONTAINER_NAME,
      content,
    }))
  })
  return rendering.then(() => undefined)
}

async function showPage(index: number): Promise<void> {
  if (index < 0 || index >= pages.length || index === currentPage) return
  currentPage = index
  await textUpgrade(pages[currentPage])
}

async function requestNextKindlePage(): Promise<void> {
  if (currentMode !== 'reading') return
  if (
    awaitingAdvanceRevision === currentRevision ||
    blockedAdvanceRevision === currentRevision ||
    currentRevision < 0
  ) return
  awaitingAdvanceRevision = currentRevision
  try {
    const response = await fetch(ADVANCE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ revision: currentRevision }),
      signal: AbortSignal.timeout(2_500),
    })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
  } catch (error) {
    console.warn('Could not request the next Kindle page:', error)
    await finishReadingPageWithRemainder()
  }
}

async function requestReadingPage(): Promise<void> {
  if (currentMode !== 'reading' || pendingReadingPage) return
  pendingReadingPage = true
  await fulfillReadingPage()
}

async function fulfillReadingPage(): Promise<void> {
  if (currentMode !== 'reading' || !pendingReadingPage) return
  const page = readingPaginator.takeNextPage()
  if (page == null) {
    if (blockedAdvanceRevision === currentRevision) {
      pendingReadingPage = false
      return
    }
    if (pages.length === 0 && awaitingAdvanceRevision === null) {
      await textUpgrade('Kindle\n\n次ページを取得中…')
    }
    await requestNextKindlePage()
    return
  }
  pages.push(page)
  pendingReadingPage = false
  await showPage(pages.length - 1)
}

async function finishReadingPageWithRemainder(): Promise<void> {
  blockedAdvanceRevision = currentRevision
  awaitingAdvanceRevision = null
  if (!pendingReadingPage) return
  const remainder = readingPaginator.flushRemainder()
  pendingReadingPage = false
  if (remainder == null) return
  pages.push(remainder)
  await showPage(pages.length - 1)
}

function singleTapCountOf(state: ReadingState): number {
  if (typeof state.singleTapCount === 'number') return state.singleTapCount
  // Older Android builds only published doubleTapCount.
  return typeof state.doubleTapCount === 'number' ? state.doubleTapCount : 0
}

async function handleSingleTapCount(count: number): Promise<void> {
  if (lastSingleTapCount === null || count < lastSingleTapCount) {
    lastSingleTapCount = count
    return
  }
  const delta = count - lastSingleTapCount
  lastSingleTapCount = count
  if (currentMode !== 'reading' && currentMode !== 'response') return
  if (currentRevision < 0 || awaitingAdvanceRevision !== null) return
  for (let index = 0; index < delta; index += 1) {
    if (currentPage < pages.length - 1) await showPage(currentPage + 1)
    else if (currentMode === 'reading') {
      await requestReadingPage()
      break
    } else {
      break
    }
  }
}

async function renderState(state: ReadingState): Promise<void> {
  const mode = resolveMode(state)
  if (mode !== 'harbor') lastHarborTitle = null
  const tapCount = singleTapCountOf(state)
  if (state.revision !== currentRevision) {
    const previousRevision = currentRevision
    const expectedReadingAdvance = mode === 'reading' &&
      currentMode === 'reading' &&
      awaitingAdvanceRevision === previousRevision
    currentRevision = state.revision
    currentMode = mode
    awaitingAdvanceRevision = null
    blockedAdvanceRevision = null
    lastSingleTapCount = tapCount
    if (mode === 'harbor') {
      pages = []
      currentPage = 0
      pendingReadingPage = false
      readingPaginator.reset('')
      const revision = currentRevision
      const titleChanged = Boolean(state.title) && state.title !== lastHarborTitle
      lastHarborTitle = state.title ?? null
      const content = state.error
        ? `Terminal Harbor\n\n${state.error}`
        : harborTail(state.bodyText ?? '')
      if (titleChanged && state.title) {
        await textUpgrade(`Harbor\n\n${state.title}`)
        window.setTimeout(() => {
          if (currentMode === 'harbor' && currentRevision === revision) void textUpgrade(content)
        }, 1_000)
      } else {
        await textUpgrade(content)
      }
    } else if (mode === 'reading' && state.active && state.bodyText) {
      if (expectedReadingAdvance) {
        readingPaginator.append(state.bodyText)
      } else {
        pages = []
        currentPage = -1
        pendingReadingPage = true
        readingPaginator.reset(state.bodyText)
      }
      await fulfillReadingPage()
    } else if (state.active && state.bodyText) {
      pendingReadingPage = false
      readingPaginator.reset('')
      pages = paginate(state.bodyText, {
        width: INNER_WIDTH,
        height: INNER_HEIGHT,
      })
      currentPage = 0
      await textUpgrade(pages[0])
    } else {
      pages = []
      currentPage = 0
      pendingReadingPage = false
      readingPaginator.reset('')
      await textUpgrade(idleMessage(state))
    }
  } else {
    currentMode = mode
  }
  await handleSingleTapCount(tapCount)
  if (state.active && state.loading) return
  if (
    currentMode === 'reading' &&
    state.error &&
    awaitingAdvanceRevision === currentRevision
  ) {
    await finishReadingPageWithRemainder()
  }
  if (state.error && pages.length === 0) await textUpgrade(idleMessage(state))
}

async function poll(): Promise<void> {
  try {
    const response = await fetch(READING_URL, {
      cache: 'no-store',
      signal: AbortSignal.timeout(1_500),
    })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    await renderState((await response.json()) as ReadingState)
  } catch (error) {
    console.warn('Voice Harness bridge unavailable:', error)
    if (currentRevision < 0) await textUpgrade('Voice Harness\n\nWaiting for Android…')
  } finally {
    window.setTimeout(() => void poll(), 500)
  }
}

const unsubscribe = bridge.onEvenHubEvent((event) => {
  const sysType = eventTypeOf(event.sysEvent)
  const textType = eventTypeOf(event.textEvent)
  if (sysType === OsEventTypeList.DOUBLE_CLICK_EVENT || textType === OsEventTypeList.DOUBLE_CLICK_EVENT) {
    void bridge.shutDownPageContainer(1)
    return
  }
  if (textType === OsEventTypeList.SCROLL_TOP_EVENT) {
    void showPage(currentPage - 1)
    return
  }
  if (textType === OsEventTypeList.SCROLL_BOTTOM_EVENT) {
    if (currentPage < pages.length - 1) void showPage(currentPage + 1)
    else if (currentMode === 'reading') void requestReadingPage()
    return
  }
  if (sysType === OsEventTypeList.SYSTEM_EXIT_EVENT || sysType === OsEventTypeList.ABNORMAL_EXIT_EVENT) {
    unsubscribe()
  }
})

void poll()
