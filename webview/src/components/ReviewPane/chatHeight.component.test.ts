import { render } from '@testing-library/react'
import { createElement } from 'react'
import { describe, expect, it, vi } from 'vitest'
import {
  CHAT_HEIGHT_KEY,
  DEFAULT_CHAT_HEIGHT,
  MAX_CHAT_HEIGHT,
  MIN_CHAT_HEIGHT,
  chatHeightBounds,
  clampChatHeight,
  effectiveChatAvailableHeight,
  loadChatHeight,
  saveChatHeight,
} from './chatHeight'
import { ReviewPane } from './ReviewPane'

describe('loadChatHeight', () => {
  it('falls back when a legacy persisted height cannot fit the chat controls', () => {
    localStorage.setItem(CHAT_HEIGHT_KEY, '100')

    expect(loadChatHeight()).toBe(DEFAULT_CHAT_HEIGHT)
  })

  it('preserves persisted heights within the usable range', () => {
    localStorage.setItem(CHAT_HEIGHT_KEY, String(MIN_CHAT_HEIGHT))

    expect(loadChatHeight()).toBe(MIN_CHAT_HEIGHT)
  })

  it('treats the boundary around the minimum as expected', () => {
    localStorage.setItem(CHAT_HEIGHT_KEY, String(MIN_CHAT_HEIGHT - 1))
    expect(loadChatHeight()).toBe(DEFAULT_CHAT_HEIGHT)

    localStorage.setItem(CHAT_HEIGHT_KEY, String(MIN_CHAT_HEIGHT + 1))
    expect(loadChatHeight()).toBe(MIN_CHAT_HEIGHT + 1)
  })

  it('falls back for missing, invalid, and oversized persisted values', () => {
    for (const value of [null, 'invalid', String(MAX_CHAT_HEIGHT + 1)]) {
      localStorage.clear()
      if (value !== null) localStorage.setItem(CHAT_HEIGHT_KEY, value)

      expect(loadChatHeight()).toBe(DEFAULT_CHAT_HEIGHT)
    }
  })

  it('clamps a valid persisted height to leave room for the review', () => {
    localStorage.setItem(CHAT_HEIGHT_KEY, String(MAX_CHAT_HEIGHT))

    expect(loadChatHeight(localStorage, 500)).toBe(260)
    expect(chatHeightBounds(500)).toEqual({ min: MIN_CHAT_HEIGHT, max: 260 })
  })

  it('uses a compact range when the panel cannot fit the normal minimum', () => {
    expect(chatHeightBounds(400)).toEqual({ min: 160, max: 160 })
    expect(clampChatHeight(MAX_CHAT_HEIGHT, 400)).toBe(160)
    expect(chatHeightBounds(200)).toEqual({ min: 0, max: 0 })
  })

  it('reduces chat capacity when fixed review chrome collapses the scroll body', () => {
    expect(effectiveChatAvailableHeight(500, 260, 34)).toBe(374)
    expect(clampChatHeight(260, effectiveChatAvailableHeight(500, 260, 34))).toBe(134)
    expect(effectiveChatAvailableHeight(500, 260, 200)).toBe(500)
  })

  it('survives restricted contexts where accessing window.localStorage throws SecurityError', () => {
    const originalDescriptor = Object.getOwnPropertyDescriptor(window, 'localStorage')
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      get() {
        throw new DOMException('Blocked by sandbox', 'SecurityError')
      },
    })
    try {
      expect(loadChatHeight(undefined, 900)).toBe(DEFAULT_CHAT_HEIGHT)
      expect(() => saveChatHeight(320)).not.toThrow()
    } finally {
      if (originalDescriptor) {
        Object.defineProperty(window, 'localStorage', originalDescriptor)
      }
    }
  })
})

describe('ReviewPane layout notifications', () => {
  it('does not send a webviewLayoutChanged message when no PR is selected', () => {
    const cefQuery = vi.fn()
    Object.assign(window, { cefQuery })

    render(createElement(ReviewPane, { pr: null }))

    expect(cefQuery).not.toHaveBeenCalled()
  })
})

