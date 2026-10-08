// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  LEFT_SWIPE_KEY, RIGHT_SWIPE_KEY, MobileSidebarGestures, sidebarSwipeEnabled,
} from '../src/client/mobile/sidebar-gestures.ts'

function touch(type: string, x: number, y: number, target: EventTarget, id = 1, activeIds?: number[]): Event {
  const event = new Event(type, { bubbles: true, cancelable: true })
  const point = { identifier: id, clientX: x, clientY: y }
  const active = (activeIds ?? (type === 'touchend' ? [] : [id])).map(identifier => ({ ...point, identifier }))
  Object.defineProperty(event, 'touches', { value: active })
  Object.defineProperty(event, 'changedTouches', { value: [point] })
  target.dispatchEvent(event)
  return event
}

function setRect(element: HTMLElement, left: number, width: number): void {
  element.getBoundingClientRect = () => ({
    x: left, y: 0, left, right: left + width, top: 0, bottom: 700, width, height: 700,
    toJSON: () => ({}),
  })
}

function setup({ panel = false, drawerOpen = false, rightOpen = false } = {}) {
  document.documentElement.setAttribute('data-dsh-mobile-form', '')
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: 400 })
  const frame = document.createElement('div')
  frame.setAttribute('data-dsh-frame', '')
  if (!drawerOpen) frame.setAttribute('data-sidebar-collapsed', '')
  const drawer = document.createElement('aside')
  drawer.className = 'sidebarCol_test'
  frame.appendChild(drawer)
  document.body.appendChild(frame)
  setRect(drawer, 0, 200)
  if (rightOpen) {
    const rightPanel = document.createElement('aside')
    rightPanel.setAttribute('data-sidebar-right-panel', 'fullscreen')
    rightPanel.setAttribute('data-sidebar-right-open', '')
    setRect(rightPanel, 0, 400)
    document.body.appendChild(rightPanel)
  }
  const state = { panel, rightOpen }
  const calls = {
    openLeft: vi.fn(() => frame.removeAttribute('data-sidebar-collapsed')),
    returnToConversation: vi.fn(() => { state.panel = false; frame.setAttribute('data-sidebar-collapsed', '') }),
    openRight: vi.fn(() => { state.rightOpen = true }),
    closeRight: vi.fn(() => { state.rightOpen = false }),
  }
  const gestures = new MobileSidebarGestures({
    mobileForm: () => document.documentElement.hasAttribute('data-dsh-mobile-form'),
    leftOpen: () => !frame.hasAttribute('data-sidebar-collapsed'),
    rightOpen: () => state.rightOpen,
    mainPanelSelected: () => state.panel,
    ...calls,
  })
  gestures.attach()
  return { frame, drawer, gestures, calls, state }
}

function swipe(target: EventTarget, x1: number, y1: number, x2: number, y2: number): void {
  touch('touchstart', x1, y1, target)
  touch('touchmove', x2, y2, target)
  touch('touchend', x2, y2, target)
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  document.documentElement.removeAttribute('data-dsh-mobile-form')
})

afterEach(() => {
  document.body.innerHTML = ''
  document.documentElement.removeAttribute('data-dsh-mobile-form')
})

describe('mobile sidebar content gestures', () => {
  it('opens the left drawer from a rightward middle-content swipe', () => {
    const h = setup()
    swipe(document.body, 200, 300, 300, 305)
    expect(h.calls.openLeft).toHaveBeenCalledOnce()
    expect(h.calls.returnToConversation).not.toHaveBeenCalled()
  })

  it('a leftward middle-content swipe returns a selected main panel to Conversation', () => {
    const h = setup({ panel: true })
    swipe(document.body, 200, 300, 100, 302)
    expect(h.calls.returnToConversation).toHaveBeenCalledOnce()
    expect(h.calls.openLeft).not.toHaveBeenCalled()
  })

  it('closes an open drawer and returns to Conversation from content area', () => {
    const h = setup({ drawerOpen: true, panel: true })
    swipe(h.drawer, 180, 300, 80, 302)
    expect(h.calls.returnToConversation).toHaveBeenCalledOnce()
    expect(h.calls.openRight).not.toHaveBeenCalled()
    expect(h.frame.hasAttribute('data-sidebar-collapsed')).toBe(true)
  })

  it('keeps the settings overlay gesture eligible so its owner can close it before returning', () => {
    const h = setup({ drawerOpen: true })
    const settings = document.createElement('div')
    settings.setAttribute('role', 'dialog')
    settings.setAttribute('aria-modal', 'true')
    settings.setAttribute('data-dsh-settings-dialog', '')
    document.body.appendChild(settings)
    swipe(h.drawer, 180, 300, 80, 302)
    expect(h.calls.returnToConversation).toHaveBeenCalledOnce()
  })

  it('opens the right panel from the middle and closes only that panel with the opposite swipe', () => {
    const h = setup()
    swipe(document.body, 200, 300, 100, 302)
    expect(h.calls.openRight).toHaveBeenCalledOnce()
    h.gestures.detach()
    document.body.innerHTML = ''
    const close = setup({ rightOpen: true })
    swipe(document.body, 200, 300, 300, 302)
    expect(close.calls.closeRight).toHaveBeenCalledOnce()
    expect(close.calls.openLeft).not.toHaveBeenCalled()
    close.gestures.detach()
  })

  it('keeps left and right preferences independent', () => {
    localStorage.setItem(LEFT_SWIPE_KEY, '0')
    expect(sidebarSwipeEnabled('left')).toBe(false)
    expect(sidebarSwipeEnabled('right')).toBe(true)
    const h = setup()
    swipe(document.body, 200, 300, 100, 302)
    expect(h.calls.openRight).toHaveBeenCalledOnce()
    swipe(document.body, 200, 300, 300, 302)
    expect(h.calls.openLeft).not.toHaveBeenCalled()
  })

  it('suppresses the compatibility click after a recognized swipe but preserves keyboard clicks', () => {
    const h = setup()
    const clicked = vi.fn()
    document.body.addEventListener('click', clicked)
    swipe(document.body, 200, 300, 300, 302)
    const touchClick = new MouseEvent('click', { bubbles: true, cancelable: true, detail: 1 })
    document.body.dispatchEvent(touchClick)
    expect(touchClick.defaultPrevented).toBe(true)
    expect(clicked).not.toHaveBeenCalled()
    const keyboardClick = new MouseEvent('click', { bubbles: true, cancelable: true, detail: 0 })
    document.body.dispatchEvent(keyboardClick)
    expect(keyboardClick.defaultPrevented).toBe(false)
    expect(clicked).toHaveBeenCalledOnce()
    h.gestures.detach()
  })

  it('does not claim desktop gestures, vertical drags, form controls, modal surfaces, or horizontal scrollers', () => {
    const h = setup()
    document.documentElement.removeAttribute('data-dsh-mobile-form')
    swipe(document.body, 8, 300, 100, 302)
    document.documentElement.setAttribute('data-dsh-mobile-form', '')
    swipe(document.body, 200, 300, 290, 380)
    const input = document.createElement('input')
    document.body.appendChild(input)
    swipe(input, 200, 300, 300, 302)
    const modal = document.createElement('div')
    modal.setAttribute('role', 'dialog')
    modal.setAttribute('aria-modal', 'true')
    document.body.appendChild(modal)
    swipe(modal, 200, 300, 300, 302)
    modal.remove()
    const scroller = document.createElement('div')
    scroller.style.overflowX = 'auto'
    Object.defineProperties(scroller, { clientWidth: { value: 100 }, scrollWidth: { value: 200 } })
    document.body.appendChild(scroller)
    swipe(scroller, 200, 300, 300, 302)
    expect(h.calls.openLeft).not.toHaveBeenCalled()
  })

  it('respects disabled right-side gesture without disabling left-side gesture', () => {
    localStorage.setItem(RIGHT_SWIPE_KEY, '0')
    const h = setup()
    swipe(document.body, 200, 300, 100, 302)
    swipe(document.body, 200, 300, 300, 302)
    expect(h.calls.openRight).not.toHaveBeenCalled()
    expect(h.calls.openLeft).toHaveBeenCalledOnce()
  })

  it('leaves the narrow Android system gesture strips at both screen edges untouched', () => {
    const h = setup()
    swipe(document.body, 16, 300, 120, 302)
    swipe(document.body, 384, 300, 280, 302)
    expect(h.calls.openLeft).not.toHaveBeenCalled()
    expect(h.calls.openRight).not.toHaveBeenCalled()
  })

  it('does not switch panels when an initially horizontal movement becomes vertical', () => {
    const h = setup()
    touch('touchstart', 200, 300, document.body)
    const horizontal = touch('touchmove', 230, 305, document.body)
    expect(horizontal.defaultPrevented).toBe(true)
    const vertical = touch('touchmove', 235, 400, document.body)
    expect(vertical.defaultPrevented).toBe(false)
    touch('touchend', 235, 400, document.body)
    expect(h.calls.openLeft).not.toHaveBeenCalled()
    expect(h.calls.openRight).not.toHaveBeenCalled()
  })

  it('does not claim a pre-existing or newly created text selection', () => {
    const h = setup()
    const text = document.createElement('p')
    text.textContent = 'Selectable conversation text'
    document.body.appendChild(text)
    const range = document.createRange()
    range.selectNodeContents(text)
    const selection = window.getSelection()!
    selection.removeAllRanges()
    selection.addRange(range)
    swipe(text, 200, 300, 300, 302)
    expect(h.calls.openLeft).not.toHaveBeenCalled()
    selection.removeAllRanges()
    touch('touchstart', 200, 300, text)
    selection.addRange(range)
    const move = touch('touchmove', 300, 302, text)
    expect(move.defaultPrevented).toBe(false)
    touch('touchend', 300, 302, text)
    expect(h.calls.openLeft).not.toHaveBeenCalled()
  })

  it('cancels a pending gesture when a second touch joins, even if one finger remains', () => {
    const h = setup()
    touch('touchstart', 200, 300, document.body, 1, [1])
    touch('touchmove', 230, 302, document.body, 1, [1])
    touch('touchstart', 220, 300, document.body, 2, [1, 2])
    touch('touchmove', 300, 304, document.body, 1, [1])
    touch('touchend', 300, 304, document.body, 1, [])
    expect(h.calls.openLeft).not.toHaveBeenCalled()
    expect(h.calls.openRight).not.toHaveBeenCalled()
    h.gestures.detach()
  })

  it('does not complete a swipe when touchend reports another finger still down', () => {
    const h = setup()
    touch('touchstart', 200, 300, document.body, 1, [1])
    touch('touchmove', 300, 302, document.body, 1, [1])
    // Even if the second touchstart/move was missed, the end of the first
    // finger is not a completed single-finger gesture while a touch remains.
    touch('touchend', 300, 302, document.body, 1, [2])
    expect(h.calls.openLeft).not.toHaveBeenCalled()
    expect(h.calls.openRight).not.toHaveBeenCalled()
    touch('touchend', 300, 302, document.body, 2, [])
    expect(h.calls.openLeft).not.toHaveBeenCalled()
    h.gestures.detach()
  })
})
