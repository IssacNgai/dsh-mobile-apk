package com.dsharnessmobile.shell

import org.json.JSONObject

/** Opt-in input binding. Generic user-directed setText keeps its existing semantics. */
internal object StrictInputIdentity {
  data class Fact(
    val pkg: String, val windowId: String, val rid: String, val cls: String,
    val password: Boolean?, val editable: Boolean, val enabled: Boolean,
    val visible: Boolean, val width: Int, val height: Int,
  )

  /** Wire class is V2's short class; the live comparison below uses the full native class. */
  fun matchesRequest(request: JSONObject, expected: Fact): Boolean {
    val keys = setOf("v", "packageName", "windowId", "resourceId", "className", "password")
    return request.keys().asSequence().toSet() == keys && request.opt("v") == 1 &&
      request.opt("packageName") == expected.pkg && request.opt("windowId") == expected.windowId &&
      request.opt("resourceId") == expected.rid && request.opt("className") == expected.cls.substringAfterLast('.') &&
      request.opt("password") == false && safe(expected)
  }

  fun safe(f: Fact): Boolean = f.pkg.isNotEmpty() && f.windowId.toIntOrNull()?.let { it >= 0 && it.toString() == f.windowId } == true &&
    f.rid.isNotEmpty() && f.cls.isNotEmpty() && f.password == false && f.editable && f.enabled && f.visible && f.width > 0 && f.height > 0

  interface Source<N : Any> {
    fun root(): N?
    fun inventory(): List<SnapshotWindowScope.WindowFact>?
    fun fact(node: N): Fact
    fun childCount(node: N): Int
    fun child(node: N, index: Int): N?
    fun now(): Long
    fun fresh(): Boolean
  }

  /** New root and full bounded traversal on every invocation; no stored/path/fingerprint fallback. */
  fun <N : Any> resolve(expected: Fact, source: Source<N>, maxNodes: Int, maxDepth: Int, budgetMs: Long): N? {
    if (!safe(expected) || !source.fresh()) return null
    val budget = SnapshotTraversalBudget(maxNodes, maxDepth, source.now() + budgetMs)
    return try {
      val before = source.inventory()
      val root = source.root() ?: return null
      var matches = 0
      var target: N? = null
      fun walk(node: N?, depth: Int) {
        if (!budget.enter(node != null, depth, source.now()) || node == null) return
        val fact = source.fact(node)
        if (fact.windowId != expected.windowId || (depth == 0 && fact.pkg != expected.pkg)) budget.incomplete()
        // Count RID before visibility, editability, package or geometry filtering.
        if (fact.rid == expected.rid) {
          matches++
          if (safe(fact) && fact.pkg == expected.pkg && fact.cls == expected.cls && fact.windowId == expected.windowId) target = node
        }
        val count = source.childCount(node)
        if (count < 0) budget.incomplete()
        for (i in 0 until count) {
          if (budget.truncated) break
          walk(source.child(node, i), depth + 1)
        }
      }
      walk(root, 0)
      val after = source.inventory()
      budget.finish(source.now())
      val proof = SnapshotWindowScope.evidence(0, expected.windowId.toIntOrNull(), before, after, source.fresh())
      if (!budget.truncated && matches == 1 && proof.optBoolean("inventoryComplete", false) && source.fresh()) target else null
    } catch (_: Exception) { null }
  }
}
