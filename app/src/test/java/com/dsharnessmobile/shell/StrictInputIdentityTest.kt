package com.dsharnessmobile.shell

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Runs production resolver with fake node getters; not an Android/Binder/device test. */
class StrictInputIdentityTest {
  private val expected = StrictInputIdentity.Fact("com.example", "7", "com.example:id/input", "android.widget.EditText", false, true, true, true, 100, 40)
  private class Node(var fact: StrictInputIdentity.Fact, val children: MutableList<Node?> = mutableListOf())
  private inner class Source : StrictInputIdentity.Source<Node> {
    val epoch = SnapshotFreshness().apply { publish(capture()) }
    var tree: Node? = Node(expected)
    var windows: List<SnapshotWindowScope.WindowFact>? = listOf(SnapshotWindowScope.WindowFact(7, 1))
    var clock = 0L
    var reads = 0
    var rootReads = 0
    var onFact: (() -> Unit)? = null
    var onInventory: (() -> Unit)? = null
    override fun root(): Node? { rootReads++; return tree }
    override fun inventory(): List<SnapshotWindowScope.WindowFact>? { onInventory?.invoke(); return windows }
    override fun fact(node: Node): StrictInputIdentity.Fact { reads++; onFact?.invoke(); return node.fact }
    override fun childCount(node: Node): Int = node.children.size
    override fun child(node: Node, index: Int): Node? = node.children[index]
    override fun now(): Long = clock
    override fun fresh(): Boolean = !epoch.isInvalidated()
    fun resolve(maxNodes: Int = 4000, maxDepth: Int = 40): Node? = StrictInputIdentity.resolve(expected, this, maxNodes, maxDepth, 3000)
  }

  @Test fun requestMatchesOriginalIdentityAndPasswordFalseOnly() {
    fun request() = JSONObject().put("v", 1).put("packageName", expected.pkg).put("windowId", expected.windowId)
      .put("resourceId", expected.rid).put("className", "EditText").put("password", false)
    assertTrue(StrictInputIdentity.matchesRequest(request(), expected))
    for ((key, value) in listOf("v" to "1", "packageName" to "other", "windowId" to "8", "resourceId" to "input", "className" to "TextView", "password" to true)) {
      assertFalse(StrictInputIdentity.matchesRequest(request().put(key, value), expected))
    }
    assertFalse(StrictInputIdentity.matchesRequest(request().put("extra", true), expected))
    assertFalse(StrictInputIdentity.matchesRequest(request().apply { remove("password") }, expected))
    assertFalse(StrictInputIdentity.matchesRequest(request(), expected.copy(password = null)))
  }

  @Test fun fullLiveIdentityRejectsStalePathLookalikeAndUntrustedGetters() {
    val source = Source()
    val original = source.tree
    assertSame(original, source.resolve())
    for (changed in listOf(expected.copy(pkg = "other"), expected.copy(windowId = "8"), expected.copy(rid = "other"),
      expected.copy(cls = "other.EditText"), expected.copy(password = true), expected.copy(password = null),
      expected.copy(editable = false), expected.copy(enabled = false), expected.copy(visible = false), expected.copy(width = 0))) {
      source.tree = Node(changed)
      assertNull(changed.toString(), source.resolve())
    }
    source.tree = Node(expected)
    source.onFact = { throw IllegalStateException("getter failure") }
    assertNull(source.resolve())
  }

  @Test fun hiddenZeroAreaAndWrongPackageDuplicateRidAllDeny() {
    for (duplicate in listOf(expected, expected.copy(visible = false, width = 0, editable = false), expected.copy(pkg = "other"))) {
      val source = Source()
      source.tree!!.children.add(Node(duplicate))
      assertNull(source.resolve())
      assertEquals(2, source.reads)
    }
  }

  @Test fun nullChildDepthNodeDeadlineAndForeignWindowDeny() {
    val source = Source()
    source.tree!!.children.add(null)
    assertNull(source.resolve())
    source.tree = Node(expected, mutableListOf(Node(expected.copy(rid = "other"))))
    assertNull(source.resolve(maxNodes = 1))
    assertNull(source.resolve(maxDepth = 0))
    source.onFact = { source.clock = 3001 }
    assertNull(source.resolve())
    source.onFact = null
    source.tree = Node(expected, mutableListOf(Node(expected.copy(rid = "other", windowId = "8"))))
    assertNull(source.resolve())
  }

  @Test fun inventoriesAndDeliveredEventsDuringGettersRemainFailClosed() {
    for (windows in listOf(null, emptyList(), listOf(SnapshotWindowScope.WindowFact(7, 2)),
      listOf(SnapshotWindowScope.WindowFact(7, 1), SnapshotWindowScope.WindowFact(8, 1)))) {
      val source = Source(); source.windows = windows
      assertNull(source.resolve())
    }
    val changed = Source()
    var inventories = 0
    changed.onInventory = { if (++inventories == 2) changed.windows = listOf(SnapshotWindowScope.WindowFact(8, 1)) }
    assertNull(changed.resolve())
    val duringGetter = Source()
    duringGetter.onFact = { duringGetter.epoch.invalidate() }
    assertNull(duringGetter.resolve())
  }

  @Test fun focusThenReacquireNeverReusesOriginalHandleOrClearsEpoch() {
    val source = Source()
    val first = source.resolve()
    source.tree = Node(expected)
    val second = source.resolve()
    assertNotSame(first, second)
    assertEquals(2, source.rootReads)
    source.epoch.invalidate() // delivered focus/content event; not a new accepted generation
    assertNull(source.resolve())
    assertTrue(source.epoch.isInvalidated())
    val switched = Source()
    assertNotNull(switched.resolve())
    switched.tree = Node(expected.copy(password = true)) // focus switched input without event delivery
    assertNull(switched.resolve())
  }
}
