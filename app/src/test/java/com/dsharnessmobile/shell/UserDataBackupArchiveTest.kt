package com.dsharnessmobile.shell

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.*
import org.junit.Test

class UserDataBackupArchiveTest {
  private val pass = "correct horse".toCharArray()

  @Test fun exportsAndRestoresWithFactoryAndUnknownConflictsPreserved() {
    val base = Files.createTempDirectory("dsh-user-backup").toFile()
    try {
      val source = File(base, "old/home/.dsh").apply { mkdirs() }
      put(source, "profiles/legacy/cordis.patch.yml", "user model: true")
      put(source, "profiles/web/package.json", """{"dependencies":{"@user/pinned":"1.0","@factory/shared":"user-pin"},"dsh":{"profile":{"bundles":["@user/custom"]}},"futureField":{"keep":true}}""")
      put(source, "profiles/web/node_modules/@dsh/hard-plugin/index.js", "same-name user data must survive")
      put(source, "sessions/a.jsonl", "session")
      put(source, "workspaces/w/notes.txt", "workspace")
      put(source, "future-data/opaque.bin", "unknown")
      put(source, ".credentials.yaml", "token: secret")
      put(source, "profiles/soft-target.js", "soft target")
      put(source, "profiles/web/node_modules/soft-plugin/index.js", "soft plugin")
      val outside = File(base, "usr/hard.js").apply { parentFile.mkdirs(); writeText("hard") }
      val profileLink = File(source, "profiles/web/node_modules/hard-link")
      profileLink.parentFile.mkdirs()
      try { Files.createSymbolicLink(profileLink.toPath(), outside.toPath()) } catch (e: Exception) { org.junit.Assume.assumeNoException(e) }
      Files.createSymbolicLink(File(source, "profiles/web/node_modules/soft-link").toPath(), java.nio.file.Paths.get("../../soft-target.js"))
      val archive = ByteArrayOutputStream()
      UserDataBackupArchive.export(source, archive, pass, UserDataBackupArchive.ExportOptions(
        activeConfigPath = "profiles/legacy/cordis.patch.yml", hardPluginNames = emptySet(),
        preferencesJson = "{\"format\":1,\"values\":{}}", deepseekKey = "legacy-deepseek", dashscopeKey = "legacy-dashscope"))

      val live = File(base, "new/home/.dsh").apply { mkdirs() }
      put(live, "profiles/web/package.json", """{"dependencies":{"@factory/shared":"factory","@factory/new":"2.0"},"dsh":{"profile":{"bundles":["@deepseek-ai/dsh-base"]}}}""")
      put(live, "profiles/web/cordis.patch.yml", "- id: current-factory\n  disabled: false\n")
      put(live, "future-data/opaque.bin", "existing unknown wins")
      val report = UserDataBackupArchive.import(ByteArrayInputStream(archive.toByteArray()), pass, live,
        "profiles/web/cordis.patch.yml", live.parentFile)
      val restoredPatch = File(live, "profiles/web/cordis.patch.yml").readText()
      assertTrue(restoredPatch.contains("user model: true"))
      assertTrue(restoredPatch.contains("current-factory"))
      val restoredPackage = org.json.JSONObject(File(live, "profiles/web/package.json").readText())
      assertEquals("user-pin", restoredPackage.getJSONObject("dependencies").getString("@factory/shared"))
      assertEquals("1.0", restoredPackage.getJSONObject("dependencies").getString("@user/pinned"))
      assertEquals("2.0", restoredPackage.getJSONObject("dependencies").getString("@factory/new"))
      assertTrue(restoredPackage.getJSONObject("futureField").getBoolean("keep"))
      assertEquals("existing unknown wins", File(live, "future-data/opaque.bin").readText())
      assertEquals("session", File(live, "sessions/a.jsonl").readText())
      assertEquals("workspace", File(live, "workspaces/w/notes.txt").readText())
      assertEquals("soft plugin", File(live, "profiles/web/node_modules/soft-plugin/index.js").readText())
      assertEquals("token: secret", File(live, ".credentials.yaml").readText())
      assertEquals("same-name user data must survive", File(live, "profiles/web/node_modules/@dsh/hard-plugin/index.js").readText())
      assertFalse(File(live, "profiles/web/node_modules/hard-link").exists())
      assertEquals(java.nio.file.Paths.get("../../soft-target.js"), Files.readSymbolicLink(File(live, "profiles/web/node_modules/soft-link").toPath()))
      assertEquals("{\"format\":1,\"values\":{}}", report.preferencesJson)
      assertEquals("legacy-deepseek", report.legacyApiKeys["deepseek-key.txt"])
      assertEquals("legacy-dashscope", report.legacyApiKeys["dashscope-key.txt"])
      assertFalse(report.preservedConflicts.contains("profiles/web/package.json"))
      assertTrue(report.preservedConflicts.contains("future-data/opaque.bin"))
    } finally { delete(base) }
  }

  @Test fun wrongPasswordTruncationAndGcmFailureLeaveLiveUntouched() {
    val base = Files.createTempDirectory("dsh-user-backup-auth").toFile()
    try {
      val source = File(base, "source/.dsh").apply { mkdirs() }
      put(source, "sessions/a", "archive")
      val bytes = archive(source)
      val live = File(base, "live/.dsh").apply { mkdirs() }
      put(live, "sessions/a", "live")
      put(live, "unknown", "keep")
      fun reject(payload: ByteArray, password: CharArray = pass) {
        try { UserDataBackupArchive.import(ByteArrayInputStream(payload), password, live, null, live.parentFile); fail("expected rejection") }
        catch (_: Exception) { }
        assertEquals("live", File(live, "sessions/a").readText())
        assertEquals("keep", File(live, "unknown").readText())
      }
      reject(bytes, "wrong".toCharArray())
      reject(bytes.copyOf(bytes.size - 9))
      // Last byte is the final-frame GCM tag; ZIP plaintext and manifest hash remain otherwise valid.
      val corrupt = bytes.copyOf(); corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 1).toByte(); reject(corrupt)
    } finally { delete(base) }
  }

  @Test fun hardPluginNameAloneCannotSuppressPossiblyUserModifiedFiles() {
    val base = Files.createTempDirectory("dsh-user-backup-hard-name").toFile()
    try {
      val source = File(base, "source/.dsh").apply { mkdirs() }
      put(source, "profiles/web/node_modules/@factory/hard/index.js", "user-modified bytes")
      try {
        UserDataBackupArchive.export(source, ByteArrayOutputStream(), pass,
          UserDataBackupArchive.ExportOptions(null, setOf("@factory/hard")))
        fail("package name is not proof that current bytes are regenerateable")
      } catch (_: IllegalArgumentException) { }
    } finally { delete(base) }
  }

  @Test fun chunkedAeadRejectsReorderDuplicateMissingFinalLengthAndHeaderTampering() {
    val base = Files.createTempDirectory("dsh-user-backup-frames").toFile()
    try {
      val source = File(base, "source/.dsh").apply { mkdirs() }
      val randomPayload = ByteArray(210_000).also(SecureRandom()::nextBytes)
      File(source, "large.bin").writeBytes(randomPayload)
      val archive = archive(source)
      val frames = frames(archive)
      assertTrue("random payload should span multiple AEAD frames", frames.size >= 4)
      val live = File(base, "live/.dsh").apply { mkdirs() }; put(live, "keep", "live")
      val reordered = archive.copyOfRange(0, 32) + frames[1] + frames[0] + frames.drop(2).fold(ByteArray(0)) { acc, b -> acc + b }
      val duplicated = archive.copyOfRange(0, 32) + frames[0] + frames[0] + frames.drop(1).fold(ByteArray(0)) { acc, b -> acc + b }
      val noFinal = archive.copyOfRange(0, archive.size - frames.last().size)
      val wrongLength = archive.copyOf().also { it[37] = 0; it[38] = 0; it[39] = 0xff.toByte(); it[40] = 0xff.toByte() }
      val wrongHeader = archive.copyOf().also { it[8] = (it[8].toInt() xor 1).toByte() }
      for (bad in listOf(reordered, duplicated, noFinal, wrongLength, wrongHeader)) {
        try { UserDataBackupArchive.import(ByteArrayInputStream(bad), pass, live, null, live.parentFile); fail("invalid frame sequence must be rejected") }
        catch (_: Exception) { }
        assertEquals("live", File(live, "keep").readText())
        assertFalse(File(live, "large.bin").exists())
      }
      UserDataBackupArchive.import(ByteArrayInputStream(archive), pass, live, null, live.parentFile)
      assertArrayEquals(randomPayload, File(live, "large.bin").readBytes())
    } finally { delete(base) }
  }

  @Test fun existingSymlinkParentCannotRedirectMergeOutsideDsh() {
    val base = Files.createTempDirectory("dsh-user-backup-link-parent").toFile()
    try {
      val source = File(base, "source/.dsh").apply { mkdirs() }
      put(source, "profiles/web/user.txt", "archive")
      val bytes = archive(source)
      val live = File(base, "live/.dsh").apply { mkdirs() }
      val outside = File(base, "outside").apply { mkdirs() }
      put(outside, "web/user.txt", "outside stays")
      try { Files.createSymbolicLink(File(live, "profiles").toPath(), outside.toPath()) }
      catch (e: Exception) { org.junit.Assume.assumeNoException(e) }
      val report = UserDataBackupArchive.import(ByteArrayInputStream(bytes), pass, live, null, live.parentFile)
      assertEquals("outside stays", File(outside, "web/user.txt").readText())
      assertTrue(report.preservedConflicts.contains("profiles/web/user.txt"))
      assertTrue(Files.isSymbolicLink(File(live, "profiles").toPath()))
    } finally { delete(base) }
  }

  @Test fun legacySettingsYamlIsRejectedWhenTargetUsesProfilePatchFormat() {
    val base = Files.createTempDirectory("dsh-user-backup-config-format").toFile()
    try {
      val source = File(base, "source/.dsh").apply { mkdirs() }
      put(source, "settings.yaml", "legacy: true\n")
      val output = ByteArrayOutputStream()
      UserDataBackupArchive.export(source, output, pass, UserDataBackupArchive.ExportOptions("settings.yaml", emptySet()))
      val live = File(base, "live/.dsh").apply { mkdirs() }
      put(live, "profiles/web/cordis.patch.yml", "- id: current\n  disabled: false\n")
      try {
        UserDataBackupArchive.import(ByteArrayInputStream(output.toByteArray()), pass, live,
          "profiles/web/cordis.patch.yml", live.parentFile)
        fail("legacy settings YAML must not be path-renamed into a profile patch")
      } catch (_: IllegalArgumentException) { }
      assertEquals("- id: current\n  disabled: false\n", File(live, "profiles/web/cordis.patch.yml").readText())
      assertFalse(File(live, "settings.yaml").exists())
    } finally { delete(base) }
  }

  @Test fun rejectsTraversalDuplicateManifestPathBadHashAndUnsupportedFormatBeforeCommit() {
    val base = Files.createTempDirectory("dsh-user-backup-malformed").toFile()
    try {
      val live = File(base, "live/.dsh").apply { mkdirs() }; put(live, "keep", "live")
      val bad = listOf(
        crafted("../escape" to "x"),
        crafted("safe" to "x", duplicate = true),
        craftedDuplicateZipEntry(),
        crafted("safe" to "x", wrongHash = true),
        crafted("safe" to "x", truncatedHash = true),
        crafted("safe" to "x", version = "77"),
        crafted("safe" to "x", schema = "77"),
        crafted("safe" to "x", declaredSize = "2147483649"),
      )
      for (bytes in bad) {
        try { UserDataBackupArchive.import(ByteArrayInputStream(bytes), pass, live, null, live.parentFile); fail("expected rejection") }
        catch (_: Exception) { }
        assertEquals("live", File(live, "keep").readText())
        assertFalse(File(base, "escape").exists())
      }
    } finally { delete(base) }
  }

  @Test fun commitExceptionRollsBackAndNextImportRecoversInterruptedOldTreeMove() {
    val base = Files.createTempDirectory("dsh-user-backup-rollback").toFile()
    try {
      val source = File(base, "source/.dsh").apply { mkdirs() }; put(source, "sessions/new", "new")
      val bytes = archive(source)
      val live = File(base, "live/.dsh").apply { mkdirs() }; put(live, "sessions/a", "old")
      try {
        UserDataBackupArchive.import(ByteArrayInputStream(bytes), pass, live, null, live.parentFile) { phase ->
          if (phase == "NEW_MOVED") error("injected post-swap failure")
        }
        fail("expected injected failure")
      } catch (_: IllegalStateException) { }
      assertEquals("old", File(live, "sessions/a").readText())

      // Simulate a process stop after live -> transaction/old, before stage -> live.
      val tx = File(live.parentFile, UserDataBackupArchive.TX_PREFIX + java.util.UUID.randomUUID()).apply { mkdirs() }
      File(tx, "owner").writeText(UserDataBackupArchive.TX_OWNER)
      File(tx, "state").writeText("OLD_MOVED\n")
      Files.move(live.toPath(), File(tx, "old").toPath(), StandardCopyOption.ATOMIC_MOVE)
      UserDataBackupArchive.import(ByteArrayInputStream(bytes), pass, live, null, live.parentFile)
      assertEquals("old", File(live, "sessions/a").readText())
      assertEquals("new", File(live, "sessions/new").readText())
      assertEquals("DONE", File(tx, "state").readText().trim())
    } finally { delete(base) }
  }

  @Test fun platformParticipantSharesCommitAndRollbackWithDshTree() {
    val base = Files.createTempDirectory("dsh-user-backup-platform-tx").toFile()
    try {
      val source = File(base, "source/.dsh").apply { mkdirs() }
      put(source, "sessions/new", "restored")
      val out = ByteArrayOutputStream()
      UserDataBackupArchive.export(source, out, pass, UserDataBackupArchive.ExportOptions(
        null, emptySet(), preferencesJson = "{\"format\":1,\"values\":{}}", deepseekKey = "new-key"))
      val live = File(base, "live/.dsh").apply { mkdirs() }; put(live, "sessions/old", "preserve")
      val callbacks = mutableListOf<String>()
      val participant = object : UserDataBackupArchive.PlatformParticipant {
        var platformValue = "old"
        override fun prepare(tx: File, data: UserDataBackupArchive.PlatformData) {
          callbacks += "prepare"
          assertEquals("new-key", data.legacyApiKeys["deepseek-key.txt"])
          assertEquals("{\"format\":1,\"values\":{}}", data.preferencesJson)
          File(tx, "platform").mkdirs()
          File(tx, "platform/ready").writeText("prepared")
        }
        override fun apply(tx: File) { callbacks += "apply"; platformValue = "new"; error("injected platform failure") }
        override fun rollback(tx: File) { callbacks += "rollback"; platformValue = "old" }
        override fun committed(tx: File) { callbacks += "committed" }
      }
      try {
        UserDataBackupArchive.import(ByteArrayInputStream(out.toByteArray()), pass, live, null, live.parentFile,
          participant = participant)
        fail("expected platform apply failure")
      } catch (_: IllegalStateException) { }
      assertEquals("old", participant.platformValue)
      assertEquals("preserve", File(live, "sessions/old").readText())
      assertFalse(File(live, "sessions/new").exists())
      assertEquals(listOf("prepare", "apply", "rollback"), callbacks)

      val successCallbacks = mutableListOf<String>()
      val successful = object : UserDataBackupArchive.PlatformParticipant {
        override fun prepare(tx: File, data: UserDataBackupArchive.PlatformData) { successCallbacks += "prepare"; File(tx, "platform").mkdirs(); File(tx, "platform/ready").writeText("ready") }
        override fun apply(tx: File) { successCallbacks += "apply" }
        override fun rollback(tx: File) { successCallbacks += "rollback" }
        override fun committed(tx: File) { successCallbacks += "committed" }
      }
      UserDataBackupArchive.import(ByteArrayInputStream(out.toByteArray()), pass, live, null, live.parentFile,
        participant = successful)
      assertEquals(listOf("prepare", "apply", "committed"), successCallbacks)
      assertEquals("restored", File(live, "sessions/new").readText())
    } finally { delete(base) }
  }

  @Test fun committedPlatformCleanupFailureNeverRollsBackAndIsRetriedAtStartup() {
    val base = Files.createTempDirectory("dsh-user-backup-platform-commit").toFile()
    try {
      val source = File(base, "source/.dsh").apply { mkdirs() }; put(source, "sessions/new", "committed")
      val bytes = archive(source)
      val live = File(base, "live/.dsh").apply { mkdirs() }; put(live, "sessions/old", "old")
      val events = mutableListOf<String>()
      val participant = object : UserDataBackupArchive.PlatformParticipant {
        var failCleanup = true
        override fun prepare(tx: File, data: UserDataBackupArchive.PlatformData) { File(tx, "platform").mkdirs(); File(tx, "platform/ready").writeText("ready") }
        override fun apply(tx: File) { events += "apply" }
        override fun rollback(tx: File) { events += "rollback" }
        override fun committed(tx: File) { events += "committed"; if (failCleanup) error("injected journal cleanup fault") }
      }
      UserDataBackupArchive.import(ByteArrayInputStream(bytes), pass, live, null, live.parentFile, participant = participant)
      assertEquals("committed", File(live, "sessions/new").readText())
      assertTrue(live.parentFile.listFiles()!!.any { it.name.startsWith(UserDataBackupArchive.TX_PREFIX) })
      assertEquals(listOf("apply", "committed"), events)

      participant.failCleanup = false
      UserDataBackupArchive.recoverPending(live.parentFile, live, participant)
      assertEquals("committed", File(live, "sessions/new").readText())
      assertFalse(live.parentFile.listFiles()!!.any { it.name.startsWith(UserDataBackupArchive.TX_PREFIX) &&
        File(it, "state").readText().trim() != "DONE" })
      assertEquals(listOf("apply", "committed", "committed"), events)
    } finally { delete(base) }
  }

  @Test fun unknownScratchAndUnownedTransactionSiblingsAreNeverDeleted() {
    val base = Files.createTempDirectory("dsh-user-backup-unknown-siblings").toFile()
    try {
      val source = File(base, "source/.dsh").apply { mkdirs() }; put(source, "sessions/new", "new")
      val bytes = archive(source)
      val live = File(base, "live/.dsh").apply { mkdirs() }; put(live, "keep", "live")
      val fixedStage = File(live.parentFile, ".dsh-backup-stage").apply { mkdirs() }; put(fixedStage, "sentinel", "stage-owned-by-someone")
      val fixedMerged = File(live.parentFile, ".dsh-backup-merged").apply { mkdirs() }; put(fixedMerged, "sentinel", "keep-merged")
      val legacyTx = File(live.parentFile, ".dsh-backup-transaction/old").apply { mkdirs() }; put(legacyTx, "sentinel", "keep-tx")
      val unowned = File(live.parentFile, UserDataBackupArchive.TX_PREFIX + java.util.UUID.randomUUID()).apply { mkdirs() }
      put(unowned, "sentinel", "unowned")
      try { UserDataBackupArchive.import(ByteArrayInputStream(bytes), pass, live, null, live.parentFile); fail("unowned transaction must fail closed") }
      catch (_: Exception) { }
      assertEquals("live", File(live, "keep").readText())
      assertEquals("stage-owned-by-someone", File(fixedStage, "sentinel").readText())
      assertEquals("keep-merged", File(fixedMerged, "sentinel").readText())
      assertEquals("keep-tx", File(legacyTx, "sentinel").readText())
      assertEquals("unowned", File(unowned, "sentinel").readText())
    } finally { delete(base) }
  }

  @Test fun malformedOwnedPhaseIsPreservedAndBlocksImport() {
    val base = Files.createTempDirectory("dsh-user-backup-bad-phase").toFile()
    try {
      val source = File(base, "source/.dsh").apply { mkdirs() }; put(source, "new", "archive")
      val bytes = archive(source)
      val live = File(base, "live/.dsh").apply { mkdirs() }; put(live, "keep", "live")
      val tx = File(live.parentFile, UserDataBackupArchive.TX_PREFIX + java.util.UUID.randomUUID()).apply { mkdirs() }
      File(tx, "owner").writeText(UserDataBackupArchive.TX_OWNER)
      File(tx, "state").writeText("UNKNOWN_PHASE\n")
      put(tx, "old/sentinel", "do not remove")
      try { UserDataBackupArchive.import(ByteArrayInputStream(bytes), pass, live, null, live.parentFile); fail("bad phase must fail closed") }
      catch (_: Exception) { }
      assertEquals("live", File(live, "keep").readText())
      assertEquals("do not remove", File(tx, "old/sentinel").readText())
    } finally { delete(base) }
  }

  @Test fun nullDirectoryEnumerationAbortsCloneAndRepeatedRollbackFailureLeavesRecoverySource() {
    val base = Files.createTempDirectory("dsh-user-backup-list-failure").toFile()
    try {
      val source = File(base, "source/.dsh").apply { mkdirs() }; put(source, "sessions/new", "new")
      val bytes = archive(source)
      val live = File(base, "live/.dsh").apply { mkdirs() }; put(live, "sessions/old", "old")
      val rejectingLister: (File) -> Array<File>? = { dir -> if (dir == live) null else dir.listFiles() }
      try {
        UserDataBackupArchive.import(ByteArrayInputStream(bytes), pass, live, null, live.parentFile, listDirectory = rejectingLister)
        fail("null directory listing must abort")
      } catch (_: java.io.IOException) { }
      assertEquals("old", File(live, "sessions/old").readText())

      // Owned OLD_MOVED residue with both old rollback data and the uncommitted live tree.
      val tx = File(live.parentFile, UserDataBackupArchive.TX_PREFIX + java.util.UUID.randomUUID()).apply { mkdirs() }
      File(tx, "owner").writeText(UserDataBackupArchive.TX_OWNER)
      File(tx, "state").writeText("OLD_MOVED\n")
      val old = File(tx, "old").apply { mkdirs() }; put(old, "sessions/old", "rollback source")
      put(live, "sessions/newer-uncommitted", "pending")
      val rollbackLister: (File) -> Array<File>? = { dir -> if (dir == live) null else dir.listFiles() }
      try { UserDataBackupArchive.import(ByteArrayInputStream(bytes), pass, live, null, live.parentFile, listDirectory = rollbackLister); fail("rollback enumeration fault") }
      catch (_: java.io.IOException) { }
      assertEquals("rollback source", File(tx, "old/sessions/old").readText())
      assertEquals("pending", File(live, "sessions/newer-uncommitted").readText())
      assertTrue(tx.exists())

      UserDataBackupArchive.import(ByteArrayInputStream(bytes), pass, live, null, live.parentFile)
      assertEquals("rollback source", File(live, "sessions/old").readText())
      assertEquals("new", File(live, "sessions/new").readText())
      assertEquals("DONE", File(tx, "state").readText().trim())
    } finally { delete(base) }
  }

  private fun archive(root: File): ByteArray {
    val out = ByteArrayOutputStream()
    UserDataBackupArchive.export(root, out, pass, UserDataBackupArchive.ExportOptions(null, emptySet()))
    return out.toByteArray()
  }

  private fun put(root: File, rel: String, body: String) { val f = File(root, rel); f.parentFile.mkdirs(); f.writeText(body) }
  private fun delete(f: File) { if (!Files.exists(f.toPath(), NOFOLLOW_LINKS)) return; if (Files.isDirectory(f.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(f.toPath())) f.listFiles()?.forEach(::delete); Files.deleteIfExists(f.toPath()) }

  private fun crafted(path: String, body: String, duplicate: Boolean = false, wrongHash: Boolean = false, truncatedHash: Boolean = false, version: String = "1", schema: String = "1", declaredSize: String? = null): ByteArray {
    val bytes = body.toByteArray()
    val digest = when { wrongHash -> "0".repeat(64); truncatedHash -> MessageDigestCompat.sha(bytes).dropLast(1); else -> MessageDigestCompat.sha(bytes) }
    val path64 = b64(path); val body64 = b64("")
    val row = "I\tF\t${declaredSize ?: bytes.size.toString()}\t$digest\t$path64\t$body64\n"
    val manifest = "DSH-USER-BACKUP\t$version\t$schema\t\n" + row + if (duplicate) row else ""
    val zipBytes = ByteArrayOutputStream()
    ZipOutputStream(zipBytes).use { z ->
      z.putNextEntry(ZipEntry("META-INF/dsh-backup.tsv")); z.write(manifest.toByteArray()); z.closeEntry()
      if (path.startsWith("/") || !path.contains("..")) { z.putNextEntry(ZipEntry(path)); z.write(bytes); z.closeEntry() }
    }
    return seal(zipBytes.toByteArray())
  }

  private fun craftedDuplicateZipEntry(): ByteArray {
    val path = "safe"; val body = "x".toByteArray()
    val digest = MessageDigestCompat.sha(body)
    val manifest = "DSH-USER-BACKUP\t1\t1\t\nI\tF\t1\t$digest\t${b64(path)}\t\n".toByteArray()
    val entries = listOf("META-INF/dsh-backup.tsv" to manifest, path to body, path to body)
    val local = ByteArrayOutputStream(); val central = ByteArrayOutputStream(); val offsets = mutableListOf<Int>()
    for ((name, data) in entries) {
      offsets += local.size()
      val nameBytes = name.toByteArray(); val crc = CRC32().apply { update(data) }.value
      le32(local, 0x04034b50); le16(local, 20); le16(local, 0); le16(local, 0); le16(local, 0); le16(local, 0)
      le32(local, crc); le32(local, data.size.toLong()); le32(local, data.size.toLong()); le16(local, nameBytes.size); le16(local, 0)
      local.write(nameBytes); local.write(data)
    }
    val centralOffset = local.size()
    entries.forEachIndexed { index, (name, data) ->
      val nameBytes = name.toByteArray(); val crc = CRC32().apply { update(data) }.value
      le32(central, 0x02014b50); le16(central, 20); le16(central, 20); le16(central, 0); le16(central, 0)
      le16(central, 0); le16(central, 0); le32(central, crc); le32(central, data.size.toLong()); le32(central, data.size.toLong())
      le16(central, nameBytes.size); le16(central, 0); le16(central, 0); le16(central, 0); le16(central, 0); le32(central, 0); le32(central, offsets[index].toLong())
      central.write(nameBytes)
    }
    val zip = ByteArrayOutputStream(); zip.write(local.toByteArray()); zip.write(central.toByteArray())
    le32(zip, 0x06054b50); le16(zip, 0); le16(zip, 0); le16(zip, entries.size); le16(zip, entries.size)
    le32(zip, central.size().toLong()); le32(zip, centralOffset.toLong()); le16(zip, 0)
    return seal(zip.toByteArray())
  }

  private fun seal(zipBytes: ByteArray): ByteArray {
    val magic = byteArrayOf(68, 83, 72, 66, 65, 75, 50, 10)
    val salt = ByteArray(16).also(SecureRandom()::nextBytes); val prefix = ByteArray(8).also(SecureRandom()::nextBytes)
    val spec = PBEKeySpec(pass, salt, 210_000, 256)
    val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    val encrypted = ByteArrayOutputStream()
    encrypted.write(magic); encrypted.write(salt); encrypted.write(prefix)
    var sequence = 0; var offset = 0
    while (offset < zipBytes.size) {
      val n = minOf(64 * 1024, zipBytes.size - offset)
      val chunk = zipBytes.copyOfRange(offset, offset + n)
      writeFrame(encrypted, key, magic, salt, prefix, 1, sequence, chunk)
      sequence++; offset += n
    }
    writeFrame(encrypted, key, magic, salt, prefix, 127, sequence, ByteArray(0))
    key.fill(0)
    return encrypted.toByteArray()
  }

  private fun writeFrame(out: ByteArrayOutputStream, key: ByteArray, magic: ByteArray, salt: ByteArray, prefix: ByteArray,
    type: Int, sequence: Int, payload: ByteArray) {
    val header = byteArrayOf(type.toByte()) + intBytes(sequence) + intBytes(payload.size)
    val nonce = prefix + intBytes(sequence)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
      init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
      updateAAD("DSH-BACKUP-AEAD-FRAME-V2".toByteArray(Charsets.US_ASCII)); updateAAD(magic); updateAAD(salt); updateAAD(prefix); updateAAD(header)
    }
    out.write(header); out.write(cipher.doFinal(payload))
  }

  private fun frames(archive: ByteArray): List<ByteArray> {
    val out = mutableListOf<ByteArray>(); var at = 32
    while (at < archive.size) {
      val start = at; val type = archive[at++].toInt() and 0xff
      val length = readInt(archive, at + 4); at += 8 + length + 16
      out += archive.copyOfRange(start, at)
      if (type == 127) break
    }
    return out
  }

  private fun intBytes(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
  private fun readInt(bytes: ByteArray, offset: Int): Int = ((bytes[offset].toInt() and 255) shl 24) or ((bytes[offset + 1].toInt() and 255) shl 16) or ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)

  private fun le16(out: ByteArrayOutputStream, value: Int) { out.write(value and 0xff); out.write(value ushr 8 and 0xff) }
  private fun le32(out: ByteArrayOutputStream, value: Long) { repeat(4) { out.write((value ushr (8 * it) and 0xff).toInt()) } }

  private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))
  private object MessageDigestCompat { fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) } }
}
