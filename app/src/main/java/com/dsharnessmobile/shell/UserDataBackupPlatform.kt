package com.dsharnessmobile.shell

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.json.JSONObject

/** Platform writes participate in the archive's commit; their rollback records live in its owned tx. */
internal class UserDataBackupPlatform(
  private val filesDir: File,
  private val preferences: Preferences,
  private val invalidateAuth: () -> Unit = {},
) : UserDataBackupArchive.PlatformParticipant {
  companion object {
    fun forContext(context: android.content.Context): UserDataBackupPlatform {
      val app = context.applicationContext
      val store = object : Preferences {
        override fun snapshot(): Map<String, Map<String, Any?>> = UserDataBackupPreferences.fields.keys.associateWith { name ->
          app.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).all.toMap()
        }

        override fun replace(values: Map<String, Map<String, Any>>) {
          for ((name, schema) in UserDataBackupPreferences.fields) {
            val editor = app.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).edit()
            schema.keys.forEach(editor::remove)
            for ((key, value) in values[name].orEmpty()) when (value) {
              is Boolean -> editor.putBoolean(key, value)
              is Int -> editor.putInt(key, value)
              is Float -> editor.putFloat(key, value)
              is String -> editor.putString(key, value)
              else -> throw java.io.IOException("Invalid portable preference type")
            }
            if (!editor.commit()) throw java.io.IOException("Cannot persist restored preferences")
          }
        }
      }
      return UserDataBackupPlatform(app.filesDir, store) { EngineAuth.invalidate(app) }
    }
  }

  interface Preferences {
    fun snapshot(): Map<String, Map<String, Any?>>
    /** Replace only UserDataBackupPreferences.fields, leaving other keys in every group intact. */
    fun replace(values: Map<String, Map<String, Any>>)
  }

  private val keyNames = setOf("deepseek-key.txt", "dashscope-key.txt")
  private val maxKeyBytes = 64 * 1024

  fun preferencesForExport(): String = UserDataBackupPreferences.encode(preferences.snapshot())

  fun legacyKeysForExport(): Map<String, String> = keyNames.mapNotNull { name ->
    val file = File(filesDir, name)
    if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
      val bytes = regularBytes(file, maxKeyBytes)
      val text = bytes.toString(Charsets.UTF_8)
      require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Legacy key is not UTF-8" }
      name to text
    } else null
  }.toMap()

  override fun prepare(tx: File, data: UserDataBackupArchive.PlatformData) {
    val dir = platformDir(tx)
    if (Files.exists(File(dir, "ready.json").toPath(), NOFOLLOW_LINKS)) {
      val metadata = readMetadata(dir)
      require(metadata.getBoolean("preferences") == (data.preferencesJson != null)) { "Platform preparation changed" }
      if (data.preferencesJson != null) require(readText(dir, "new-preferences.json") == data.preferencesJson) {
        "Platform preferences changed"
      }
      require(metadata.getJSONObject("newKeys").keys().asSequence().toSet() == data.legacyApiKeys.keys) {
        "Platform keys changed"
      }
      for ((name, value) in data.legacyApiKeys) require(readBytes(dir, "new-$name").contentEquals(value.toByteArray(Charsets.UTF_8))) {
        "Platform key preparation changed"
      }
      return
    }
    require(data.legacyApiKeys.keys.all { it in keyNames }) { "Unsupported legacy key" }
    data.preferencesJson?.let { UserDataBackupPreferences.decode(it) }
    val oldPreferences = UserDataBackupPreferences.encode(preferences.snapshot())
    write(dir, "old-preferences.json", oldPreferences.toByteArray(Charsets.UTF_8))
    data.preferencesJson?.let { write(dir, "new-preferences.json", it.toByteArray(Charsets.UTF_8)) }
    val oldKeys = JSONObject()
    val newKeys = JSONObject()
    for (name in keyNames) {
      val live = File(filesDir, name)
      val exists = Files.exists(live.toPath(), NOFOLLOW_LINKS)
      oldKeys.put(name, exists)
      if (exists) write(dir, "old-$name", regularBytes(live, maxKeyBytes))
      data.legacyApiKeys[name]?.let { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxKeyBytes) { "Legacy key exceeds limit" }
        write(dir, "new-$name", bytes)
        newKeys.put(name, true)
      }
    }
    val metadata = JSONObject().put("version", 1).put("preferences", data.preferencesJson != null)
      .put("oldKeys", oldKeys).put("newKeys", newKeys)
    // Last durable file: without it prepare has not completed and no platform write is permitted.
    write(dir, "ready.json", metadata.toString().toByteArray(Charsets.UTF_8))
  }

  override fun apply(tx: File) {
    val dir = platformDir(tx, false)
    val metadata = readMetadata(dir)
    if (metadata.getBoolean("preferences")) preferences.replace(UserDataBackupPreferences.decode(readText(dir, "new-preferences.json")))
    for (name in metadata.getJSONObject("newKeys").keys().asSequence()) {
      installKey(name, readBytes(dir, "new-$name"))
    }
  }

  override fun rollback(tx: File) {
    val dir = platformDir(tx, false)
    if (!Files.exists(dir.toPath(), NOFOLLOW_LINKS)) return
    if (!Files.exists(File(dir, "ready.json").toPath(), NOFOLLOW_LINKS)) return // prepare never applied
    val metadata = readMetadata(dir)
    // Validate every rollback payload before changing any platform value.
    val oldPrefs = UserDataBackupPreferences.decode(readText(dir, "old-preferences.json"))
    val oldKeys = metadata.getJSONObject("oldKeys")
    val restoredKeys = keyNames.associateWith { name ->
      if (oldKeys.getBoolean(name)) readBytes(dir, "old-$name") else null
    }
    preferences.replace(oldPrefs)
    for ((name, bytes) in restoredKeys) {
      if (bytes != null) installKey(name, bytes)
      else {
        val live = File(filesDir, name)
        require(!Files.isSymbolicLink(live.toPath()) &&
          (!Files.exists(live.toPath(), NOFOLLOW_LINKS) || Files.isRegularFile(live.toPath(), NOFOLLOW_LINKS))) {
          "Unsafe legacy key destination"
        }
        Files.deleteIfExists(live.toPath())
        syncDir(filesDir)
      }
    }
  }

  override fun committed(tx: File) {
    // Auth is derived from restored .credentials.yaml; no old-device cookie is installed.
    invalidateAuth()
    // The archive owns tx cleanup. Keeping all participant records until then makes replay safe.
  }

  private fun platformDir(tx: File, create: Boolean = true): File {
    require(Files.isDirectory(tx.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(tx.toPath())) { "Unsafe backup transaction" }
    val dir = File(tx, "platform")
    if (Files.exists(dir.toPath(), NOFOLLOW_LINKS)) require(Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS)) {
      "Unsafe platform journal"
    } else if (create) require(dir.mkdir()) { "Cannot create platform journal" }
    require(!Files.isSymbolicLink(dir.toPath())) { "Unsafe platform journal" }
    if (create) syncDir(tx)
    return dir
  }

  private fun readMetadata(dir: File): JSONObject {
    val value = JSONObject(readText(dir, "ready.json"))
    require(value.keys().asSequence().toSet() == setOf("version", "preferences", "oldKeys", "newKeys") && value.get("version") == 1) {
      "Invalid platform journal schema"
    }
    require(value.get("preferences") is Boolean)
    val old = value.getJSONObject("oldKeys")
    val new = value.getJSONObject("newKeys")
    require(old.keys().asSequence().toSet() == keyNames && new.keys().asSequence().all { it in keyNames })
    for (name in keyNames) require(old.get(name) is Boolean)
    for (name in new.keys().asSequence()) require(new.get(name) == true)
    return value
  }

  private fun readText(dir: File, name: String): String = regularBytes(File(dir, name), 512 * 1024).toString(Charsets.UTF_8)
  private fun readBytes(dir: File, name: String): ByteArray = regularBytes(File(dir, name), maxKeyBytes)

  private fun regularBytes(file: File, limit: Int): ByteArray {
    require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath())) { "Unsafe platform journal entry" }
    require(file.length() <= limit) { "Platform journal entry exceeds limit" }
    return file.inputStream().use { input ->
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(4096)
      while (true) {
        val count = input.read(buffer)
        if (count == -1) break
        if (count == 0) continue
        require(output.size().toLong() + count <= limit) { "Platform journal entry exceeds limit" }
        output.write(buffer, 0, count)
      }
      output.toByteArray()
    }
  }

  private fun installKey(name: String, bytes: ByteArray) {
    require(name in keyNames && bytes.size <= maxKeyBytes)
    val live = File(filesDir, name)
    require(!Files.isSymbolicLink(live.toPath()) &&
      (!Files.exists(live.toPath(), NOFOLLOW_LINKS) || Files.isRegularFile(live.toPath(), NOFOLLOW_LINKS))) {
      "Unsafe legacy key destination"
    }
    write(filesDir, name, bytes)
  }

  private fun write(dir: File, name: String, bytes: ByteArray) {
    val target = File(dir, name)
    require(!Files.isSymbolicLink(target.toPath()) &&
      (!Files.exists(target.toPath(), NOFOLLOW_LINKS) || Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS))) {
      "Unsafe platform write destination"
    }
    val temp = File.createTempFile(".platform-", ".tmp", dir)
    try {
      FileOutputStream(temp).use { stream -> stream.write(bytes); stream.fd.sync() }
      Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
      syncDir(dir)
    } finally { Files.deleteIfExists(temp.toPath()) }
  }

  private fun syncDir(dir: File) {
    try {
      java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
    } catch (_: java.io.IOException) {
      // Matches the archive core's directory-fsync fallback on Windows/unsupported filesystems.
    } catch (_: UnsupportedOperationException) {
    }
  }
}
