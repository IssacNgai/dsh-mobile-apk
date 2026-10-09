package com.dsharnessmobile.shell

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Pure production helpers plus source wiring; not an Android getter/device simulation. */
class SnapshotEvidenceTest {
  @Test fun epochCannotClearAnEventDuringWalkOrPublication() {
    val f = SnapshotFreshness()
    assertTrue(f.isInvalidated())
    val start = f.capture()
    f.invalidate()
    assertFalse(f.publish(start))
    assertTrue(f.isInvalidated())
    assertTrue(f.publish(f.capture()))
    assertFalse(f.isInvalidated())
    // A window-only event after publication and another dump cannot be debounced away.
    f.invalidate()
    assertTrue(f.isInvalidated())
    assertTrue(f.publish(f.capture()))
    f.invalidate()
    assertTrue(f.isInvalidated())
    assertFalse(f.publish(start))
  }

  @Test fun deliveredEventsDuringGetterOrFocusDenyTheNextMutation() {
    // Deterministic helper schedule, not an Android getter or Binder simulation.
    for (phase in listOf("resolve-getter", "focus", "text-getter")) {
      val f = SnapshotFreshness()
      assertTrue(f.publish(f.capture()))
      var focusCount = 0
      var mutationCount = 0
      fun requireFresh(): Boolean = !f.isInvalidated()
      assertTrue(requireFresh()) // handler entry
      if (phase == "resolve-getter") f.invalidate()
      if (requireFresh()) {
        focusCount++
        if (phase == "focus" || phase == "text-getter") f.invalidate()
        if (requireFresh()) mutationCount++
      }
      assertEquals(phase, 0, mutationCount)
      assertEquals(phase, if (phase == "resolve-getter") 0 else 1, focusCount)
    }
  }

  @Test fun budgetCountsEveryVisitAndMarksAllIncompleteExits() {
    val exact = SnapshotTraversalBudget(2, 1, 100)
    assertTrue(exact.enter(true, 0, 0))
    assertTrue(exact.enter(true, 1, 100))
    exact.finish(100)
    assertEquals(2, exact.visited)
    assertFalse(exact.truncated)
    assertFalse(exact.enter(true, 1, 100))
    assertTrue(exact.truncated)
    val absent = SnapshotTraversalBudget(10, 1, 100)
    assertFalse(absent.enter(false, 0, 0))
    assertTrue(absent.truncated)
    val deep = SnapshotTraversalBudget(10, 1, 100)
    assertFalse(deep.enter(true, 2, 0))
    assertTrue(deep.truncated)
    val expired = SnapshotTraversalBudget(10, 1, 100)
    assertFalse(expired.enter(true, 0, 101))
    assertTrue(expired.truncated)
    val late = SnapshotTraversalBudget(10, 1, 100)
    assertTrue(late.enter(true, 0, 100))
    late.finish(101)
    assertTrue(late.truncated)
    val exception = SnapshotTraversalBudget(10, 1, 100)
    exception.incomplete()
    assertFalse(exception.enter(true, 0, 0))
    assertTrue(exception.truncated)
  }

  @Test fun scopeRequiresStableSoleApplicationWindowWithoutExclusions() {
    val a = SnapshotWindowScope.WindowFact(7, 1)
    val b = SnapshotWindowScope.WindowFact(8, 1)
    fun proof(before: List<SnapshotWindowScope.WindowFact>?, after: List<SnapshotWindowScope.WindowFact>?,
              selected: Int? = 7, display: Int = 0, stable: Boolean = true) =
      SnapshotWindowScope.evidence(display, selected, before, after, stable)
    val good = proof(listOf(a), listOf(a))
    assertTrue(good.getBoolean("inventoryComplete"))
    assertEquals(1, good.getInt("v"))
    assertEquals("selected-root", good.getString("kind"))
    assertEquals("7", good.getString("selectedWindowId"))
    assertEquals("7", good.getJSONArray("windowIds").getString(0))
    val inventories = listOf<List<SnapshotWindowScope.WindowFact>?>(null, emptyList(), listOf(b),
      listOf(a, b), listOf(a, a), listOf(a.copy(id = -1)), listOf(a.copy(type = 2)),
      listOf(a.copy(type = 3)), listOf(a.copy(type = 4)))
    for (other in inventories) {
      assertFalse(proof(listOf(a), other).getBoolean("inventoryComplete"))
      assertFalse(proof(other, listOf(a)).getBoolean("inventoryComplete"))
    }
    assertFalse(proof(listOf(a), listOf(a), selected = null).getBoolean("inventoryComplete"))
    assertFalse(proof(listOf(a), listOf(a), selected = -1).getBoolean("inventoryComplete"))
    assertFalse(proof(listOf(a), listOf(a), selected = 8).getBoolean("inventoryComplete"))
    assertFalse(proof(listOf(a), listOf(a), display = 1).getBoolean("inventoryComplete"))
    assertFalse(proof(listOf(a), listOf(a), stable = false).getBoolean("inventoryComplete"))
    // Endpoint equality alone does not detect ABA: delivered event epoch is required.
    val epoch = SnapshotFreshness()
    val before = epoch.capture()
    epoch.invalidate()
    assertFalse(proof(listOf(a), listOf(a), stable = epoch.publish(before)).getBoolean("inventoryComplete"))
  }

  private fun source(relative: String): String = sequenceOf(File(relative), File("app", relative))
    .firstOrNull { it.isFile }?.readText() ?: error("missing source: $relative")

  @Test fun nativeWiringKeepsTruthInventoryEpochAndImmediateMutationChecks() {
    val s = source("src/main/java/com/dsharnessmobile/shell/DeviceControlService.kt")
    val xml = source("src/main/res/xml/accessibility_service_config.xml")
    assertTrue(s.contains("password = try { node.isPassword } catch (_: Exception) { null }"))
    assertTrue(s.contains("rid = node.viewIdResourceName ?: \"\""))
    for (event in listOf("TYPE_VIEW_TEXT_CHANGED", "TYPE_WINDOWS_CHANGED", "TYPE_WINDOW_CONTENT_CHANGED", "TYPE_WINDOW_STATE_CHANGED", "TYPE_VIEW_SCROLLED")) assertTrue(s.contains(event))
    assertTrue(xml.contains("typeViewTextChanged"))
    assertTrue(xml.contains("typeWindowsChanged"))
    assertFalse(s.contains("lastInvalidateAt"))
    assertFalse(s.contains("invalidated = false"))
    assertFalse(s.contains("node.refresh()"))
    assertFalse(s.substringAfter("private fun requireFresh(").substringBefore("private sealed class Target").contains("buildSnapshot("))
    val walk = s.substringAfter("private fun buildSnapshot(").substringBefore("private fun coordBasis(")
    assertTrue(walk.indexOf("freshness.capture()") < walk.indexOf("val beforeWindows = inventoryWindows()"))
    assertTrue(walk.indexOf("val beforeWindows = inventoryWindows()") < walk.indexOf("val root = rootFor("))
    assertTrue(walk.indexOf("walk(root,") < walk.indexOf("val afterWindows = inventoryWindows()"))
    assertTrue(walk.indexOf("val afterWindows = inventoryWindows()") < walk.indexOf("freshness.publish(observedEpoch)"))
    assertTrue(walk.contains("if (!stable) budget.incomplete()"))
    assertTrue(s.contains("getWindowsOnAllDisplays()[ScreenTargets.REAL_DISPLAY_ID]"))
    assertTrue(s.contains(".put(\"snapshotScope\", snap.scope"))
    for (call in listOf("node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)",
      "val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)",
      "val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)",
      "val ok = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)")) {
      assertTrue(call, Regex("requireFresh\\(args\\)\\?\\.let \\{ return it }\\s+" + Regex.escape(call)).containsMatchIn(s))
    }
    assertEquals(3, Regex("requireFresh\\(args\\)\\?\\.let \\{ return it }\\s+val dispatched = dispatchGesture").findAll(s).count())
    for (name in listOf("onInterrupt", "teardown")) {
      assertTrue(Regex("fun $name\\([^)]*\\)[^{]*\\{\\s*freshness.invalidate\\(\\)").containsMatchIn(s))
    }
  }
}
