package com.dsharnessmobile.shell

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UserDataBackupPreferencesTest {
  @Test fun roundTripKeepsPortableTypesAndValues() {
    val input = mapOf(
      "dsh_settings" to mapOf("immersive_mode" to false),
      "dsh-vdisplay" to mapOf("resolutionScale" to 0.75f, "floatEnabled" to true),
      "dsh_screen_scope" to mapOf("scope" to "real-only"),
      "overlay_display" to mapOf("template_tool" to "正在执行 {tool}"),
      "dsh-notify" to mapOf("suppressForegroundSchema" to 1, "cat.todo" to false),
    )
    assertEquals(input, UserDataBackupPreferences.decode(UserDataBackupPreferences.encode(input)))
  }

  @Test fun exportOmitsDeviceAuthorityAndUnknownFields() {
    val input = mapOf(
      "dsh_engine_auth" to mapOf("cookie" to "secret-cookie"),
      "dsh_root_grant" to mapOf("granted" to true),
      "dsh-adb" to mapOf("controlToken" to "secret-control"),
      "dsh-overlay" to mapOf("enabled" to true, "pendingEnable" to true),
      "dsh_settings" to mapOf("immersive_mode" to true, "future-user-field" to "untouched"),
    )
    val decoded = UserDataBackupPreferences.decode(UserDataBackupPreferences.encode(input))
    assertEquals(mapOf("dsh-overlay" to mapOf("enabled" to true), "dsh_settings" to mapOf("immersive_mode" to true)), decoded)
  }

  @Test fun rejectWrongTypesAndOutOfRangeValues() {
    for (input in listOf(
      mapOf("dsh_settings" to mapOf("immersive_mode" to "true")),
      mapOf("dsh-vdisplay" to mapOf("resolutionScale" to Float.NaN)),
      mapOf("dsh-vdisplay" to mapOf("resolutionScale" to 1.1f)),
      mapOf("dsh_screen_scope" to mapOf("scope" to "unknown")),
      mapOf("dsh-notify" to mapOf("suppressForegroundSchema" to 2)),
      mapOf("overlay_display" to mapOf("template_tool" to "x".repeat(32769))),
    )) assertThrows(IllegalArgumentException::class.java) { UserDataBackupPreferences.encode(input) }
  }

  @Test fun importRejectsUnknownSchemaAndDeviceAuthority() {
    val valid = UserDataBackupPreferences.encode(mapOf("dsh_settings" to mapOf("immersive_mode" to true)))
    assertThrows(IllegalArgumentException::class.java) {
      UserDataBackupPreferences.decode(JSONObject(valid).put("version", 2).toString())
    }
    assertThrows(IllegalArgumentException::class.java) {
      val root = JSONObject(valid)
      root.getJSONObject("preferences").put("dsh_engine_auth", JSONObject())
      UserDataBackupPreferences.decode(root.toString())
    }
    assertThrows(IllegalArgumentException::class.java) {
      val root = JSONObject(valid)
      root.getJSONObject("preferences").getJSONObject("dsh_settings").put("future", JSONObject())
      UserDataBackupPreferences.decode(root.toString())
    }
  }

  @Test fun importRejectsTypeTagMismatchBeforeConversion() {
    val valid = JSONObject(UserDataBackupPreferences.encode(mapOf("dsh_settings" to mapOf("immersive_mode" to true))))
    valid.getJSONObject("preferences").getJSONObject("dsh_settings").getJSONObject("immersive_mode").put("type", "string")
    assertThrows(IllegalArgumentException::class.java) { UserDataBackupPreferences.decode(valid.toString()) }
  }
}
