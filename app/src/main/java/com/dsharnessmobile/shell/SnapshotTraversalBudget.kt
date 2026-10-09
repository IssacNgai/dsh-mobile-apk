package com.dsharnessmobile.shell

/** Completeness of the selected accessibility root, not a claim about other windows. */
internal class SnapshotTraversalBudget(
  private val maxNodes: Int,
  private val maxDepth: Int,
  private val deadline: Long,
) {
  var truncated: Boolean = false
    private set
  var visited: Int = 0
    private set

  /** Every node counts, including hidden/zero-area identity evidence. */
  fun enter(nodePresent: Boolean, depth: Int, now: Long): Boolean {
    if (truncated) return false
    if (!nodePresent || depth > maxDepth || visited >= maxNodes || now > deadline) {
      incomplete()
      return false
    }
    visited++
    return true
  }

  /** Null advertised children, traversal exceptions and expired budgets are incomplete. */
  fun incomplete() { truncated = true }

  fun finish(now: Long) {
    if (now > deadline) incomplete()
  }
}
