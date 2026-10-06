package com.dsharnessmobile.shell

import org.json.JSONObject

/** Portable user choices, separate from Android grants, live display IDs and auth caches. */
internal object UserDataBackupPreferences {
  private const val MAX_BYTES = 512 * 1024
  private const val MAX_TEXT = 32 * 1024
  private val categories = listOf("silent", "todo", "report", "question", "approval")
  val fields: Map<String, Map<String, String>> = linkedMapOf(
    "dsh_settings" to mapOf("immersive_mode" to "boolean"),
    "dsh-overlay" to mapOf("enabled" to "boolean"),
    "overlay_display" to mapOf(
      "auto_collapse_on_done" to "boolean", "template_thinking" to "string",
      "template_tool" to "string", "template_completion" to "string",
    ),
    "dsh_screen_scope" to mapOf("scope" to "string"),
    "dsh-vdisplay" to mapOf("resolutionScale" to "float", "floatEnabled" to "boolean"),
    "dsh_prefs" to mapOf("dev_log_enabled" to "boolean"),
    "dsh-notify" to (categories.associate { "cat.$it" to "boolean" } +
      categories.associate { "channel.$it" to "string" } + mapOf(
        "suppressForeground" to "boolean", "suppressForegroundSchema" to "int",
        "suppressForegroundLegacy" to "boolean",
      )),
  )

  /** Unknown/current-device fields are neither exported nor cleared by a restore. */
  fun encode(values: Map<String, Map<String, Any?>>): String {
    val prefs = JSONObject()
    for ((name, schema) in fields) {
      val current = values[name] ?: continue
      val objectValues = JSONObject()
      for ((key, type) in schema) {
        val value = current[key] ?: continue
        validate(name, key, type, value)
        objectValues.put(key, JSONObject().put("type", type).put("value", value))
      }
      if (objectValues.length() > 0) prefs.put(name, objectValues)
    }
    return JSONObject().put("version", 1).put("preferences", prefs).toString().also(::checkSize)
  }

  fun decode(encoded: String): Map<String, Map<String, Any>> {
    checkSize(encoded)
    val root = JSONObject(encoded)
    require(root.keys().asSequence().toSet() == setOf("version", "preferences") && root.get("version") == 1) {
      "Unsupported preference backup version"
    }
    val prefs = root.getJSONObject("preferences")
    return prefs.keys().asSequence().toSet().associateWith { name ->
      val schema = requireNotNull(fields[name]) { "Unknown preference group" }
      val values = prefs.getJSONObject(name)
      values.keys().asSequence().toSet().associateWith { key ->
        val type = requireNotNull(schema[key]) { "Unknown preference field" }
        val entry = values.getJSONObject(key)
        require(entry.keys().asSequence().toSet() == setOf("type", "value") && entry.getString("type") == type) {
          "Invalid preference value type"
        }
        val raw = entry.get("value")
        // JSON represents finite Float values as Double; validate before converting.
        val value = if (type == "float") {
          require(raw is Number && raw.toDouble().isFinite()) { "Invalid preference float" }
          raw.toFloat()
        } else raw
        validate(name, key, type, value)
        value
      }
    }
  }

  private fun checkSize(value: String) {
    require(value.length <= MAX_BYTES && value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
      "Preference backup too large"
    }
  }

  private fun validate(name: String, key: String, type: String, value: Any) {
    require(when (type) {
      "boolean" -> value is Boolean
      "string" -> value is String && value.length <= MAX_TEXT
      "int" -> value is Int
      "float" -> value is Float && value.isFinite()
      else -> false
    }) { "Invalid preference value" }
    if (name == "dsh_screen_scope") require(value in setOf("virtual-only", "real-only", "all")) {
      "Invalid screen scope"
    }
    if (name == "dsh-vdisplay" && key == "resolutionScale") require(value as Float in 0.4f..1f) {
      "Invalid virtual display scale"
    }
    if (name == "dsh-notify" && key == "suppressForegroundSchema") require(value as Int in 0..1) {
      "Unsupported notification preference schema"
    }
  }
}
