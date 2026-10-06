package com.dsharnessmobile.shell

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class UserDataBackupPlatformTest {
  private class Store(initial: Map<String, Map<String, Any?>>) : UserDataBackupPlatform.Preferences {
    val values = initial.mapValues { it.value.toMutableMap() }.toMutableMap()
    override fun snapshot(): Map<String, Map<String, Any?>> = values.mapValues { it.value.toMap() }
    override fun replace(values: Map<String, Map<String, Any>>) {
      for ((name, schema) in UserDataBackupPreferences.fields) {
        val current = this.values.getOrPut(name) { mutableMapOf() }
        schema.keys.forEach(current::remove)
        values[name]?.let { current.putAll(it) }
      }
    }
  }

  @Test fun participantApplyAndRollbackAreIdempotentAndPreserveUnknownFields() {
    val root = Files.createTempDirectory("backup-platform-").toFile()
    try {
      val tx = File(root, "owned-tx").apply { mkdir() }
      File(root, "deepseek-key.txt").writeText("old-key")
      val store = Store(mapOf("dsh_settings" to mapOf("immersive_mode" to true, "future" to "keep")))
      var invalidated = 0
      val participant = UserDataBackupPlatform(root, store) { invalidated++ }
      val prefs = UserDataBackupPreferences.encode(mapOf("dsh_settings" to mapOf("immersive_mode" to false)))
      val data = UserDataBackupArchive.PlatformData(preferencesJson = prefs, legacyApiKeys = mapOf("deepseek-key.txt" to "new-key", "dashscope-key.txt" to "second-key"))
      participant.prepare(tx, data)
      participant.prepare(tx, data)
      assertEquals("old-key", File(root, "deepseek-key.txt").readText())
      assertEquals(true, store.values["dsh_settings"]?.get("immersive_mode"))
      participant.apply(tx); participant.apply(tx)
      assertEquals("new-key", File(root, "deepseek-key.txt").readText())
      assertEquals(false, store.values["dsh_settings"]?.get("immersive_mode"))
      assertEquals("keep", store.values["dsh_settings"]?.get("future"))
      participant.rollback(tx); participant.rollback(tx)
      assertEquals("old-key", File(root, "deepseek-key.txt").readText())
      assertFalse(File(root, "dashscope-key.txt").exists())
      assertEquals(true, store.values["dsh_settings"]?.get("immersive_mode"))
      assertEquals("keep", store.values["dsh_settings"]?.get("future"))
      participant.committed(tx)
      assertEquals(1, invalidated)
    } finally { root.deleteRecursively() }
  }

  @Test fun incompletePreparationNeverChangesLivePlatformState() {
    val root = Files.createTempDirectory("backup-platform-").toFile()
    try {
      val tx = File(root, "owned-tx").apply { mkdir() }
      val platform = File(tx, "platform").apply { mkdir() }
      File(platform, "old-preferences.json").writeText("partial")
      File(root, "deepseek-key.txt").writeText("untouched")
      val store = Store(mapOf("dsh_settings" to mapOf("immersive_mode" to true)))
      UserDataBackupPlatform(root, store).rollback(tx)
      assertEquals("untouched", File(root, "deepseek-key.txt").readText())
      assertEquals(true, store.values["dsh_settings"]?.get("immersive_mode"))
    } finally { root.deleteRecursively() }
  }

  @Test fun invalidJournalIsRejectedBeforePreferenceChanges() {
    val root = Files.createTempDirectory("backup-platform-").toFile()
    try {
      val tx = File(root, "owned-tx").apply { mkdir() }
      val platform = File(tx, "platform").apply { mkdir() }
      File(platform, "ready.json").writeText("""{"version":1,"preferences":true,"oldKeys":{"deepseek-key.txt":false,"dashscope-key.txt":false},"newKeys":{"../outside":true}}""")
      val store = Store(mapOf("dsh_settings" to mapOf("immersive_mode" to true)))
      assertThrows(IllegalArgumentException::class.java) { UserDataBackupPlatform(root, store).apply(tx) }
      assertEquals(true, store.values["dsh_settings"]?.get("immersive_mode"))
      assertFalse(File(root, "outside").exists())
    } finally { root.deleteRecursively() }
  }

  @Test fun archiveWithoutPortablePreferencesKeepsCurrentPreferences() {
    val root = Files.createTempDirectory("backup-platform-").toFile()
    try {
      val tx = File(root, "owned-tx").apply { mkdir() }
      val store = Store(mapOf("dsh_settings" to mapOf("immersive_mode" to true)))
      val participant = UserDataBackupPlatform(root, store)
      participant.prepare(tx, UserDataBackupArchive.PlatformData(preferencesJson = null, legacyApiKeys = emptyMap()))
      participant.apply(tx)
      assertEquals(true, store.values["dsh_settings"]?.get("immersive_mode"))
    } finally { root.deleteRecursively() }
  }

  @Test fun changedPreparationCannotReplaceRollbackAuthority() {
    val root = Files.createTempDirectory("backup-platform-").toFile()
    try {
      val tx = File(root, "owned-tx").apply { mkdir() }
      val participant = UserDataBackupPlatform(root, Store(emptyMap()))
      participant.prepare(tx, UserDataBackupArchive.PlatformData(preferencesJson = null, legacyApiKeys = mapOf("deepseek-key.txt" to "first")))
      assertThrows(IllegalArgumentException::class.java) {
        participant.prepare(tx, UserDataBackupArchive.PlatformData(preferencesJson = null, legacyApiKeys = mapOf("deepseek-key.txt" to "second")))
      }
      participant.apply(tx)
      assertEquals("first", File(root, "deepseek-key.txt").readText())
    } finally { root.deleteRecursively() }
  }
}
