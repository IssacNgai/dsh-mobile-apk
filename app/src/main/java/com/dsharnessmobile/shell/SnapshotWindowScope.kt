package com.dsharnessmobile.shell

import org.json.JSONArray
import org.json.JSONObject

/** Conservative inventory evidence: no IME, system or overlay exclusions. */
internal object SnapshotWindowScope {
  data class WindowFact(val id: Int, val type: Int)

  fun valid(ids: List<Int>?): Boolean = ids != null && ids.isNotEmpty() &&
    ids.all { it >= 0 } && ids.distinct().size == ids.size

  fun evidence(displayId: Int, selected: Int?, before: List<WindowFact>?, after: List<WindowFact>?, stable: Boolean): JSONObject {
    val beforeIds = before?.map { it.id }
    val afterIds = after?.map { it.id }
    val complete = displayId == 0 && selected != null && selected >= 0 && stable &&
      valid(beforeIds) && valid(afterIds) && before == after &&
      after!!.size == 1 && after[0].id == selected && after[0].type == 1 // TYPE_APPLICATION
    return JSONObject().put("v", 1).put("kind", "selected-root").put("displayId", displayId)
      .put("selectedWindowId", selected?.toString() ?: "")
      .put("windowIds", JSONArray((afterIds ?: emptyList()).map { it.toString() }))
      .put("inventoryComplete", complete)
  }
}
