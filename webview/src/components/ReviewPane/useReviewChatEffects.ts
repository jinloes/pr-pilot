import { useEffect, type Dispatch, type RefObject } from 'react'
import { sendToHost, type PR } from '../../bridge/types'
import {
  clampChatHeight,
  effectiveChatAvailableHeight,
  saveChatHeight,
} from './chatHeight'
import {
  isSelectionInFormField,
  SELECTION_CAPTURE_DEBOUNCE_MS,
  type ChatSelection,
} from './reviewControllerState'
import { selectionLocation } from '@/lib/selectionLocation'

type Ref<T> = { current: T }

interface UseReviewChatEffectsOptions {
  pr: PR | null
  showChat: boolean
  chatVisible: boolean
  chatHeight: number
  renderedChatHeight?: number
  stateKind: string
  paneRef: RefObject<HTMLDivElement | null>
  reviewBodyRef: RefObject<HTMLDivElement | null>
  chatHeightRef: Ref<number>
  setChatAvailableHeight: Dispatch<React.SetStateAction<number>>
  setChatHeightState: Dispatch<React.SetStateAction<number>>
  setSelection: Dispatch<React.SetStateAction<ChatSelection | null>>
}

export function useReviewChatEffects({
  pr,
  showChat,
  chatVisible,
  chatHeight,
  renderedChatHeight,
  stateKind,
  paneRef,
  reviewBodyRef,
  chatHeightRef,
  setChatAvailableHeight,
  setChatHeightState,
  setSelection,
}: UseReviewChatEffectsOptions) {
  const activeHeight = renderedChatHeight ?? chatHeight

  useEffect(() => {
    if (showChat && chatVisible) {
      sendToHost({ type: 'webviewLayoutChanged', reason: 'chat-panel' })
    }
  }, [showChat, chatVisible, activeHeight])

  useEffect(() => {
    const pane = paneRef.current
    if (!pane) return
    const updateBounds = () => {
      const containerHeight = pane.getBoundingClientRect().height || window.innerHeight
      const reviewBodyHeight = reviewBodyRef.current?.getBoundingClientRect().height || containerHeight
      const availableHeight = effectiveChatAvailableHeight(
        containerHeight,
        renderedChatHeight ?? chatHeightRef.current,
        reviewBodyHeight,
      )
      setChatAvailableHeight(availableHeight)
      const clamped = clampChatHeight(chatHeightRef.current, availableHeight)
      chatHeightRef.current = clamped
      setChatHeightState(clamped)
      saveChatHeight(clamped)
    }
    updateBounds()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(updateBounds)
    observer.observe(pane)
    if (reviewBodyRef.current) observer.observe(reviewBodyRef.current)
    return () => observer.disconnect()
  }, [chatVisible, renderedChatHeight, showChat, stateKind])

  useEffect(() => {
    if (!pr) {
      setSelection(null)
      return
    }

    function handleMouseUp(event: MouseEvent) {
      if ((event.target as HTMLElement).closest?.('.chat-pane__input')) return
      const selection = window.getSelection()
      const text = selection?.toString().trim() ?? ''
      if (text) setSelection({ text, location: selectionLocation(selection) })
    }

    // Keyboard selections fire no mouseup; capture them once the selection settles.
    let selectionTimer: ReturnType<typeof setTimeout> | null = null
    function captureKeyboardSelection() {
      selectionTimer = null
      const selection = window.getSelection()
      if (isSelectionInFormField(selection)) return
      const text = selection?.toString().trim() ?? ''
      if (text) setSelection({ text, location: selectionLocation(selection) })
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
