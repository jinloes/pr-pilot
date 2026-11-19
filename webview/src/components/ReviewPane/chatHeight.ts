export const CHAT_HEIGHT_KEY = 'claude-reviews:chat-height'
export const EMPTY_CHAT_HEIGHT = 104
export const MIN_CHAT_HEIGHT = 200
export const MAX_CHAT_HEIGHT = 600
export const DEFAULT_CHAT_HEIGHT = 240
export const MIN_REVIEW_REMAINDER = 240
export const MIN_REVIEW_BODY_HEIGHT = 160

export function chatHeightBounds(availableHeight: number): { min: number; max: number } {
  const max = Math.max(0, Math.min(MAX_CHAT_HEIGHT, Math.floor(availableHeight) - MIN_REVIEW_REMAINDER))
  return { min: Math.min(MIN_CHAT_HEIGHT, max), max }
}

export function clampChatHeight(height: number, availableHeight: number): number {
  const { min, max } = chatHeightBounds(availableHeight)
  return Math.max(min, Math.min(max, height))
}

export function effectiveChatAvailableHeight(
  containerHeight: number,
  currentChatHeight: number,
  reviewBodyHeight: number,
): number {
  const heightPreservingReviewBody = currentChatHeight
    + reviewBodyHeight
    - MIN_REVIEW_BODY_HEIGHT
    + MIN_REVIEW_REMAINDER
  return Math.max(0, Math.min(containerHeight, heightPreservingReviewBody))
}

function safeLocalStorage(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.localStorage : null
  } catch {
    return null
  }
}

export function loadChatHeight(
  storage?: Pick<Storage, 'getItem'> | null,
  availableHeight = Number.POSITIVE_INFINITY,
): number {
  let saved = Number.NaN
  try {
    const target = storage === undefined ? safeLocalStorage() : storage
    const raw = target?.getItem(CHAT_HEIGHT_KEY) ?? null
    saved = raw === null ? Number.NaN : Number(raw)
  } catch {
    saved = Number.NaN
  }
  const preferred = saved >= MIN_CHAT_HEIGHT && saved <= MAX_CHAT_HEIGHT ? saved : DEFAULT_CHAT_HEIGHT
  return Number.isFinite(availableHeight) ? clampChatHeight(preferred, availableHeight) : preferred
}

export function saveChatHeight(
  height: number,
  storage?: Pick<Storage, 'setItem'> | null,
): void {
  try {
    const target = storage === undefined ? safeLocalStorage() : storage
    target?.setItem(CHAT_HEIGHT_KEY, String(height))
  } catch {
    // Ignore storage quota or sandbox restrictions in embedded webviews.
  }
}

