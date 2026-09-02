import {
  CreateStartUpPageContainer,
  OsEventTypeList,
  TextContainerProperty,
  TextContainerUpgrade,
  waitForEvenAppBridge,
} from '@evenrealities/even_hub_sdk'
import { paginate } from './paginate'

const BODY_WIDTH = 576
const BODY_HEIGHT = 288
const BODY_PADDING = 4
const INNER_WIDTH = BODY_WIDTH - BODY_PADDING * 2
const INNER_HEIGHT = BODY_HEIGHT - BODY_PADDING * 2
const CONTAINER_ID = 1
const CONTAINER_NAME = 'main'
const READING_URL = 'http://127.0.0.1:8787/api/v1/reading'
const ADVANCE_URL = 'http://127.0.0.1:8787/api/v1/reading/advance'

type DisplayMode = 'idle' | 'response' | 'reading'

interface ReadingState {
  enabled: boolean
  active: boolean
  mode?: DisplayMode
  revision: number
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
let rendering: Promise<unknown> = Promise.resolve()

function resolveMode(state: ReadingState): DisplayMode {
  if (state.mode === 'response' || state.mode === 'reading' || state.mode === 'idle') {
    return state.mode
  }
  if (state.active && state.bodyText) return 'reading'
  return 'idle'
}

function idleMessage(state: ReadingState): string {
  if (state.error) return `Voice Harness\n\n${state.error}`
  if (state.enabled) return 'Voice Harness\n\nEven G2\nWaiting for reading gesture…'
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
  if (awaitingAdvanceRevision === currentRevision || currentRevision < 0) return
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
    awaitingAdvanceRevision = null
  }
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
      await requestNextKindlePage()
      break
    } else {
      break
    }
  }
}

async function renderState(state: ReadingState): Promise<void> {
  const mode = resolveMode(state)
  const tapCount = singleTapCountOf(state)
  if (state.revision !== currentRevision) {
    currentRevision = state.revision
    currentMode = mode
    currentPage = 0
    awaitingAdvanceRevision = null
    pages = state.active && state.bodyText ? paginate(state.bodyText, {
      width: INNER_WIDTH,
      height: INNER_HEIGHT,
    }) : []
    lastSingleTapCount = tapCount
    if (pages.length > 0) {
      await textUpgrade(pages[0])
    } else {
      await textUpgrade(idleMessage(state))
    }
  } else {
    currentMode = mode
  }
  await handleSingleTapCount(tapCount)
  if (state.active && state.loading) return
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
    else void requestNextKindlePage()
    return
  }
  if (sysType === OsEventTypeList.SYSTEM_EXIT_EVENT || sysType === OsEventTypeList.ABNORMAL_EXIT_EVENT) {
    unsubscribe()
  }
})

void poll()
