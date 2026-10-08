/**
 * Narrow-screen content gestures for the frame's independent sidebars.
 *
 * Only a deliberate, horizontal drag that starts away from the system gesture
 * edges is claimed. A right drag opens the left navigation drawer; a left drag
 * closes it or returns a selected main panel to Conversation. A left drag opens
 * the right panel and a right drag closes it.
 */

export type SidebarGestureSide = 'left' | 'right'

export const LEFT_SWIPE_KEY = 'dsh.android.sidebarSwipe.left'
export const RIGHT_SWIPE_KEY = 'dsh.android.sidebarSwipe.right'
const SWIPE_PX = 64
const HORIZONTAL_RATIO = 1.5
/** Keep the narrow outer strip available to Android's immersive back gestures. */
const SYSTEM_EDGE_EXCLUSION_PX = 32
const INTERACTIVE = 'input, textarea, select, button, label, a, summary, [contenteditable="true"], [role="button"], [role="tab"], [role="menu"], [role="menuitem"], [aria-haspopup], [data-trigger-menu]'

export interface SidebarGestureActions {
  mobileForm(): boolean
  leftOpen(): boolean
  rightOpen(): boolean
  mainPanelSelected(): boolean
  openLeft(): void
  returnToConversation(): void
  openRight(): void
  closeRight(): void
}

type GestureAction = 'open-left' | 'return' | 'open-right' | 'close-right'
interface StartPoint { id: number; x: number; y: number; action: GestureAction | null }

function findTouch(touches: TouchList, id: number): Touch | undefined {
  for (let index = 0; index < touches.length; index += 1) {
    const touch = touches[index]
    if (touch?.identifier === id) return touch
  }
  return undefined
}

/** Whether a given side's swipe setting is enabled (missing storage defaults on). */
export function sidebarSwipeEnabled(side: SidebarGestureSide): boolean {
  try {
    return localStorage.getItem(side === 'left' ? LEFT_SWIPE_KEY : RIGHT_SWIPE_KEY) !== '0'
  } catch {
    return true
  }
}

/** Persist one side independently; false means browser storage rejected the write. */
export function setSidebarSwipeEnabled(side: SidebarGestureSide, enabled: boolean): boolean {
  try {
    localStorage.setItem(side === 'left' ? LEFT_SWIPE_KEY : RIGHT_SWIPE_KEY, enabled ? '1' : '0')
    return true
  } catch {
    return false
  }
}

function editableOrInteractive(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return true
  if (target.closest(INTERACTIVE) !== null) return true
  const selection = window.getSelection()
  return selection !== null && !selection.isCollapsed
}

function horizontalScroller(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false
  for (let node: Element | null = target; node !== null; node = node.parentElement) {
    if (node === document.body || node === document.documentElement) continue
    const overflowX = getComputedStyle(node).overflowX
    if ((overflowX === 'auto' || overflowX === 'scroll' || overflowX === 'overlay')
      && node.scrollWidth > node.clientWidth + 2) return true
  }
  return false
}

/** Attach one shared touch arbiter; callbacks always read current upstream state. */
export class MobileSidebarGestures {
  private readonly actions: SidebarGestureActions
  private start: StartPoint | null = null
  private suppressClickTimer: number | null = null
  private attached = false

  constructor(actions: SidebarGestureActions) { this.actions = actions }

  attach(): void {
    if (this.attached) return
    this.attached = true
    document.addEventListener('touchstart', this.onTouchStart, true)
    // Chrome marks document-level touchmove passive by default. Explicitly
    // opt out so a recognized horizontal gesture can stop native panning;
    // vertical moves are never prevented.
    document.addEventListener('touchmove', this.onTouchMove, { capture: true, passive: false })
    document.addEventListener('touchend', this.onTouchEnd, true)
    document.addEventListener('touchcancel', this.onTouchCancel, true)
    document.addEventListener('click', this.onClick, true)
  }

  detach(): void {
    if (!this.attached) return
    this.attached = false
    document.removeEventListener('touchstart', this.onTouchStart, true)
    document.removeEventListener('touchmove', this.onTouchMove, true)
    document.removeEventListener('touchend', this.onTouchEnd, true)
    document.removeEventListener('touchcancel', this.onTouchCancel, true)
    document.removeEventListener('click', this.onClick, true)
    if (this.suppressClickTimer !== null) window.clearTimeout(this.suppressClickTimer)
    this.suppressClickTimer = null
    this.suppressClick = false
    this.start = null
  }

  private readonly onTouchStart = (event: TouchEvent): void => {
    this.start = null
    if (!this.actions.mobileForm() || event.touches.length !== 1
      || editableOrInteractive(event.target) || horizontalScroller(event.target)
      || document.querySelector('[role="dialog"][aria-modal="true"]:not([data-dsh-settings-dialog])') !== null) return

    const width = document.documentElement.clientWidth || window.innerWidth
    const touch = event.touches[0]
    if (touch === null || width <= 0 || touch.clientX <= SYSTEM_EDGE_EXCLUSION_PX
      || touch.clientX >= width - SYSTEM_EDGE_EXCLUSION_PX) return

    // Start anywhere in the phone content. The action is resolved only after a
    // deliberate horizontal movement, using the current open-panel state.
    this.start = { id: touch.identifier, x: touch.clientX, y: touch.clientY, action: null }
  }

  private readonly onTouchMove = (event: TouchEvent): void => {
    const start = this.start
    if (start === null) return
    if (event.touches.length !== 1) {
      this.start = null
      return
    }
    const touch = findTouch(event.touches, start.id)
    if (touch === undefined) return
    const dx = touch.clientX - start.x
    const dy = touch.clientY - start.y
    if (window.getSelection() !== null && !window.getSelection()!.isCollapsed) {
      this.start = null
      return
    }
    if (Math.abs(dx) < 12 || Math.abs(dx) <= Math.abs(dy) * HORIZONTAL_RATIO) return
    const action = this.resolveAction(dx)
    if (action === null) return
    if (Math.abs(dx) >= SWIPE_PX) start.action = action
    event.preventDefault()
  }

  private readonly onTouchEnd = (event: TouchEvent): void => {
    const start = this.start
    this.start = null
    // Do not interpret one finger lifting as a completed swipe while another
    // finger is still down (for example, after a pinch/zoom sequence).
    if (start === null || event.touches.length !== 0) return
    const touch = findTouch(event.changedTouches, start.id)
    if (touch === undefined) return
    const dx = touch.clientX - start.x
    const dy = touch.clientY - start.y
    if (Math.abs(dx) < SWIPE_PX || Math.abs(dx) <= Math.abs(dy) * HORIZONTAL_RATIO) return
    const action = start.action ?? this.resolveAction(dx)
    if (action === null || (action === 'open-left' || action === 'close-right' ? dx < 0 : dx > 0)) return
    event.preventDefault()
    this.suppressClick = true
    if (this.suppressClickTimer !== null) window.clearTimeout(this.suppressClickTimer)
    this.suppressClickTimer = window.setTimeout(() => {
      this.suppressClick = false
      this.suppressClickTimer = null
    }, 450)
    if (action === 'open-left' && !this.actions.leftOpen() && !this.actions.rightOpen()) this.actions.openLeft()
    else if (action === 'return' && (this.actions.leftOpen() || this.actions.mainPanelSelected())) {
      this.actions.returnToConversation()
    } else if (action === 'open-right' && !this.actions.rightOpen() && !this.actions.leftOpen()) this.actions.openRight()
    else if (action === 'close-right' && this.actions.rightOpen()) this.actions.closeRight()
  }

  private readonly onTouchCancel = (): void => { this.start = null }

  /** Resolve direction after the swipe threshold; open sidebars consume only their closing direction. */
  private resolveAction(dx: number): GestureAction | null {
    const leftEnabled = sidebarSwipeEnabled('left')
    const rightEnabled = sidebarSwipeEnabled('right')
    if (this.actions.rightOpen()) return dx > 0 && rightEnabled ? 'close-right' : null
    if (this.actions.leftOpen()) return dx < 0 && leftEnabled ? 'return' : null
    if (dx < 0 && this.actions.mainPanelSelected() && leftEnabled) return 'return'
    if (dx > 0 && leftEnabled) return 'open-left'
    if (dx < 0 && rightEnabled) return 'open-right'
    return null
  }

  private suppressClick = false
  private readonly onClick = (event: MouseEvent): void => {
    // Suppress only the compatibility click generated by the just-completed
    // touch sequence. detail=0 remains available to keyboard/screen-reader users.
    if (!this.suppressClick || event.detail === 0) return
    this.suppressClick = false
    if (this.suppressClickTimer !== null) window.clearTimeout(this.suppressClickTimer)
    this.suppressClickTimer = null
    event.preventDefault()
    event.stopImmediatePropagation()
  }
}
