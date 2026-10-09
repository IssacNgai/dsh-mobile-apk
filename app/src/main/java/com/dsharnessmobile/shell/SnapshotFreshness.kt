package com.dsharnessmobile.shell

/** Event epochs prevent an in-flight walker from clearing a newer invalidation. */
internal class SnapshotFreshness {
  private var epoch = 0L
  private var accepted = -1L

  @Synchronized fun capture(): Long = epoch
  @Synchronized fun invalidate() { epoch++ }
  @Synchronized fun isInvalidated(): Boolean = accepted != epoch

  /** Called only after all selected-root traversal reads; false means mixed-time evidence. */
  @Synchronized fun publish(observedEpoch: Long): Boolean {
    if (observedEpoch != epoch) return false
    accepted = observedEpoch
    return true
  }
}
