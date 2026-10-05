import { useEffect, type Dispatch, type RefObject } from 'react'
import { sendToHost, type PR } from '../../bridge/types'
import {
  CHAT_HEIGHT_KEY,
  clampChatHeight,
  effectiveChatAvailableHeight,
} from './chatHeight'
import {
  isSelectionInFormField,
  SELECTION_CAPTURE_DEBOUNCE_MS,
} from './reviewControllerState'

type Ref<T> = { current: T }

interface UseReviewChatEffectsOptions {
  pr: PR | null
  showChat: boolean
  chatVisible: boolean
  chatHeight: number
  stateKind: string
  paneRef: RefObject<HTMLDivElement | null>
  reviewBodyRef: RefObject<HTMLDivElement | null>
  chatHeightRef: Ref<number>
  setChatAvailableHeight: Dispatch<React.SetStateAction<number>>
  setChatHeightState: Dispatch<React.SetStateAction<number>>
  setSelectedContext: Dispatch<React.SetStateAction<string>>
}

export function useReviewChatEffects({
  pr,
  showChat,
  chatVisible,
  chatHeight,
  stateKind,
  paneRef,
  reviewBodyRef,
  chatHeightRef,
  setChatAvailableHeight,
  setChatHeightState,
  setSelectedContext,
}: UseReviewChatEffectsOptions) {
  useEffect(() => {
    if (showChat && chatVisible) {
      sendToHost({ type: 'webviewLayoutChanged', reason: 'chat-panel' })
    }
  }, [showChat, chatVisible, chatHeight])

  useEffect(() => {
    const pane = paneRef.current
    if (!pane) return
    const updateBounds = () => {
      const containerHeight = pane.getBoundingClientRect().height || window.innerHeight
      const reviewBodyHeight = reviewBodyRef.current?.getBoundingClientRect().height ?? containerHeight
      const availableHeight = effectiveChatAvailableHeight(
        containerHeight,
        chatHeightRef.current,
        reviewBodyHeight,
      )
      setChatAvailableHeight(availableHeight)
      const clamped = clampChatHeight(chatHeightRef.current, availableHeight)
      chatHeightRef.current = clamped
      setChatHeightState(clamped)
      localStorage.setItem(CHAT_HEIGHT_KEY, String(clamped))
    }
    updateBounds()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(updateBounds)
    observer.observe(pane)
    if (reviewBodyRef.current) observer.observe(reviewBodyRef.current)
    return () => observer.disconnect()
  }, [chatVisible, showChat, stateKind])

  useEffect(() => {
    if (!pr) {
      setSelectedContext('')
      return
    }

    function handleMouseUp(event: MouseEvent) {
      if ((event.target as HTMLElement).closest?.('.chat-pane__input')) return
      const text = window.getSelection()?.toString().trim() ?? ''
      if (text) setSelectedContext(text)
    }

    // Keyboard selections fire no mouseup; capture them once the selection settles.
    let selectionTimer: ReturnType<typeof setTimeout> | null = null
    function captureKeyboardSelection() {
      selectionTimer = null
      const selection = window.getSelection()
      if (isSelectionInFormField(selection)) return
      const text = selection?.toString().trim() ?? ''
      if (text) setSelectedContext(text)
    }
    function handleSelectionChange() {
      if (selectionTimer !== null) clearTimeout(selectionTimer)
      selectionTimer = setTimeout(captureKeyboardSelection, SELECTION_CAPTURE_DEBOUNCE_MS)
    }

    document.addEventListener('mouseup', handleMouseUp)
    document.addEventListener('selectionchange', handleSelectionChange)
    return () => {
      document.removeEventListener('mouseup', handleMouseUp)
      document.removeEventListener('selectionchange', handleSelectionChange)
      if (selectionTimer !== null) clearTimeout(selectionTimer)
    }
  }, [pr])
}
