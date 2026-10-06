package com.dsharnessmobile.shell

import java.io.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.PBEKeySpec

/** Pure filesystem archive core. Android SAF and preference writes are supplied by the UI layer. */
internal object UserDataBackupArchive {
  private val MAGIC = byteArrayOf(68, 83, 72, 66, 65, 75, 50, 10)
  private const val FORMAT = "DSH-USER-BACKUP"
  private const val VERSION = "1"
  private const val DATA_SCHEMA = "1"
  private const val ITERATIONS = 210_000
  private const val FRAME_BYTES = 64 * 1024
  private const val FRAME_DATA = 1
  private const val FRAME_FINAL = 127
  private const val FRAME_TAG_BYTES = 16
  internal const val TX_PREFIX = ".dsh-backup-tx-"
  internal const val TX_OWNER = "DSH-USER-BACKUP-TX-OWNER-1\n"
  private const val MAX_ENTRIES = 100_000
  private const val MAX_ENTRY = 2L * 1024 * 1024 * 1024
  private const val MAX_TOTAL = 8L * 1024 * 1024 * 1024
  private const val MAX_ARCHIVE = MAX_TOTAL + 128L * 1024 * 1024
  private const val MAX_MANIFEST = 16L * 1024 * 1024
  private const val MANIFEST = "META-INF/dsh-backup.tsv"
  private const val PREFS = "@platform/preferences.json"
  private const val DEEPSEEK_KEY = "@platform/deepseek-key.txt"
  private const val DASHSCOPE_KEY = "@platform/dashscope-key.txt"
  private const val BUF = 64 * 1024

  data class ExportOptions(
    val activeConfigPath: String?, val hardPluginNames: Set<String> = emptySet(), val preferencesJson: String? = null,
    val deepseekKey: String? = null, val dashscopeKey: String? = null,
  )
  data class ImportReport(
    val restored: Int, val preservedConflicts: List<String>, val skippedUnsafeLinks: List<String>,
    val legacyApiKeys: Map<String, String>, val preferencesJson: String?,
  )

  /** Idempotent platform-side transaction participant; its journal lives inside the owned tx. */
  interface PlatformParticipant {
    fun prepare(tx: File, data: PlatformData)
    fun apply(tx: File)
    fun rollback(tx: File)
    fun committed(tx: File)
  }
  data class PlatformData(val preferencesJson: String?, val legacyApiKeys: Map<String, String>)
  private data class Item(val path: String, val kind: Char, val size: Long, val sha256: String, val target: String? = null)
  private data class Parsed(val items: List<Item>, val activeConfig: String?, val preferencesJson: String?, val legacyApiKeys: Map<String, String>)

  /** Two-pass source scan and streaming ZIP + AES-GCM output; no user file is read into memory. */
  fun export(dshRoot: File, output: OutputStream, passphrase: CharArray, options: ExportOptions,
    listDirectory: (File) -> Array<File>? = { it.listFiles() }) {
    require(passphrase.isNotEmpty())
    require(options.hardPluginNames.isEmpty()) { "Hard plugin pruning requires byte-verified factory ownership" }
    require(!Files.isSymbolicLink(dshRoot.toPath()) && Files.isDirectory(dshRoot.toPath(), NOFOLLOW_LINKS)) { "DSH root must be a regular directory" }
    val root = dshRoot.canonicalFile
    require(root.isDirectory)
    val entries = scan(root, listDirectory)
    options.preferencesJson?.let { value -> val b = value.toByteArray(Charsets.UTF_8); require(b.size <= 512 * 1024) { "Preferences payload too large" }; entries += Item(PREFS, 'F', b.size.toLong(), sha(b)) }
    options.deepseekKey?.let { val b = it.toByteArray(Charsets.UTF_8); entries += Item(DEEPSEEK_KEY, 'F', b.size.toLong(), sha(b)) }
    options.dashscopeKey?.let { val b = it.toByteArray(Charsets.UTF_8); entries += Item(DASHSCOPE_KEY, 'F', b.size.toLong(), sha(b)) }
    require(entries.size <= MAX_ENTRIES && entries.sumOf { it.size } <= MAX_TOTAL) { "Backup exceeds archive limits" }
    options.activeConfigPath?.let(::safePath)
    val manifestSize = (listOf(FORMAT, VERSION, DATA_SCHEMA, b64(options.activeConfigPath ?: "")).joinToString("\t") + "\n").toByteArray(Charsets.UTF_8).size +
      entries.sumOf { encode(it).toByteArray(Charsets.UTF_8).size }
    require(manifestSize <= MAX_MANIFEST) { "Manifest exceeds size limit" }
    val salt = ByteArray(16).also(SecureRandom()::nextBytes)
    val noncePrefix = ByteArray(8).also(SecureRandom()::nextBytes)
    val out = BufferedOutputStream(output)
    out.write(MAGIC); out.write(salt); out.write(noncePrefix)
    val key = deriveKey(passphrase, salt)
    try {
      ZipOutputStream(FrameEncryptingOutputStream(out, key, MAGIC, salt, noncePrefix), Charsets.UTF_8).use { zip ->
        val header = listOf(FORMAT, VERSION, DATA_SCHEMA, b64(options.activeConfigPath ?: "")).joinToString("\t") + "\n"
        zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(header.toByteArray(Charsets.UTF_8))
        entries.forEach { zip.write(encode(it).toByteArray(Charsets.UTF_8)) }
        zip.closeEntry()
        for (item in entries) {
          zip.putNextEntry(ZipEntry(item.path))
          when (item.kind) {
            'F' -> when (item.path) {
              PREFS -> zip.write(options.preferencesJson!!.toByteArray(Charsets.UTF_8))
              DEEPSEEK_KEY -> zip.write(options.deepseekKey!!.toByteArray(Charsets.UTF_8))
              DASHSCOPE_KEY -> zip.write(options.dashscopeKey!!.toByteArray(Charsets.UTF_8))
              else -> streamFile(File(root, item.path), zip, item)
            }
            'L' -> zip.write(item.target!!.toByteArray(Charsets.UTF_8))
            'D' -> Unit
          }
          zip.closeEntry()
        }
        zip.finish()
      }
    } finally { key.fill(0) }
  }

  /** Full authenticated validation precedes staging; live state changes only at the final directory swap. */
  @Synchronized
  fun import(
    input: InputStream,
    passphrase: CharArray,
    dshRoot: File,
    currentActiveConfigPath: String?,
    transactionParent: File,
    listDirectory: (File) -> Array<File>? = { it.listFiles() },
    failAt: ((String) -> Unit)? = null,
    participant: PlatformParticipant? = null,
  ): ImportReport {
    require(passphrase.isNotEmpty())
    val live = dshRoot.absoluteFile
    val parent = transactionParent.canonicalFile
    require(live.parentFile.canonicalFile == parent) { "Transaction parent must be the DSH parent" }
    if (!parent.mkdirs() && !parent.isDirectory) throw IOException("Cannot create backup transaction parent")
    if (Files.exists(live.toPath(), NOFOLLOW_LINKS)) require(Files.isDirectory(live.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(live.toPath())) {
      "DSH root is not a regular directory"
    }
    recoverOwnedTransactions(parent, live, listDirectory, participant)
    val tx = newOwnedTransaction(parent, listDirectory)
    val encrypted = File(tx, "encrypted.input")
    val plain = File(tx, "decrypted.zip")
    val stage = File(tx, "stage")
    val merged = File(tx, "merged")
    try {
      input.use { src -> FileOutputStream(encrypted).use { dst ->
        val b = ByteArray(BUF); var total = 0L; var n: Int
        while (src.read(b).also { n = it } != -1) if (n > 0) {
          total += n; require(total <= MAX_ARCHIVE) { "Encrypted archive exceeds size limit" }; dst.write(b, 0, n)
        }
        dst.fd.sync()
      } }
      decrypt(encrypted, plain, passphrase) // reads through GCM tag before returning
      val parsed = validateExtract(plain, stage, listDirectory)
      if (parsed.activeConfig != null && currentActiveConfigPath != null) {
        require(parsed.activeConfig.substringAfterLast('/') == currentActiveConfigPath.substringAfterLast('/')) {
          "Active configuration format cannot be migrated by path remapping"
        }
      }
      val active = parsed.activeConfig?.let { safePath(currentActiveConfigPath ?: it) }
      val conflicts = mutableListOf<String>()
      val skippedLinks = mutableListOf<String>()
      val remapped = parsed.items.map { original ->
        original to if (original.path == parsed.activeConfig && active != null) original.copy(path = active) else original
      }
      require(remapped.map { it.second.path }.toSet().size == remapped.size) { "Active config remap collides with another archive entry" }
      if (Files.exists(live.toPath(), NOFOLLOW_LINKS)) cloneTree(live, merged, listDirectory)
      else if (!merged.mkdirs() && !merged.isDirectory) throw IOException("Cannot create staged merged tree")
      var restored = 0
      for ((original, item) in remapped) {
        if (item.path.startsWith("@platform/")) continue
        if (item.kind == 'L' && !safeLink(item.path, item.target!!)) { skippedLinks += item.path; continue }
        val rel = safePath(item.path)
        if (hasBlockingAncestor(merged, rel)) { conflicts += rel; continue }
        val dest = File(merged, rel)
        val source = File(stage, safePath(original.path))
        val packageManifest = item.kind == 'F' && rel.matches(Regex("^profiles/[^/]+/package\\.json$"))
        val replace = item.kind == 'F' && (rel == active || rel == ".credentials.yaml" || rel == "models-store.json" || packageManifest)
        if (Files.exists(dest.toPath(), NOFOLLOW_LINKS) && !replace) {
          if (item.kind == 'D' && Files.isDirectory(dest.toPath(), NOFOLLOW_LINKS)) continue
          conflicts += rel
          continue
        }
        if (replace && Files.exists(dest.toPath(), NOFOLLOW_LINKS) &&
          !Files.isRegularFile(dest.toPath(), NOFOLLOW_LINKS)) {
          conflicts += rel
          continue
        }
        if (packageManifest && Files.exists(dest.toPath(), NOFOLLOW_LINKS)) {
          if (!Files.isRegularFile(dest.toPath(), NOFOLLOW_LINKS) || Files.isSymbolicLink(dest.toPath())) { conflicts += rel; continue }
          val mergedPackage = ProfilePackageManifest.merge(source.readText(Charsets.UTF_8), dest.readText(Charsets.UTF_8))
            ?: throw IOException("Cannot safely merge profile package manifest: $rel")
          source.writeText(mergedPackage, Charsets.UTF_8)
        }
        if (rel == active && item.kind == 'F' && rel.endsWith("/cordis.patch.yml") &&
          Files.isRegularFile(dest.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(dest.toPath())) {
          val mergedPatch = FactoryProfilePatch.merge(source.readText(Charsets.UTF_8), dest.readText(Charsets.UTF_8)).text
          require(mergedPatch.isNotBlank()) { "Active profile merge produced an empty patch" }
          source.writeText(mergedPatch, Charsets.UTF_8)
        }
        dest.parentFile?.mkdirs()
        if (Files.exists(dest.toPath(), NOFOLLOW_LINKS)) deleteTree(dest, listDirectory)
        when (item.kind) {
          'D' -> if (!dest.mkdirs() && !dest.isDirectory) throw IOException("Cannot create imported directory: ${item.path}")
          'F' -> Files.move(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
          'L' -> Files.createSymbolicLink(dest.toPath(), java.nio.file.Paths.get(item.target!!))
        }
        restored++
      }
      // No backup plaintext/staged leftovers should remain before the live root is swapped.
      deleteTree(stage, listDirectory)
      participant?.prepare(tx, PlatformData(parsed.preferencesJson, parsed.legacyApiKeys))
      failAt?.invoke("STAGED")
      val hadLive = Files.exists(live.toPath(), NOFOLLOW_LINKS)
      writeState(tx, if (hadLive) "PREPARED" else "PREPARED_EMPTY")
      // Recovery always rolls back unless COMMITTED is durably written. Both moves are same-filesystem renames.
      if (hadLive) Files.move(live.toPath(), File(tx, "old").toPath(), StandardCopyOption.ATOMIC_MOVE)
      syncDir(parent)
      writeState(tx, if (hadLive) "OLD_MOVED" else "OLD_MOVED_EMPTY")
      failAt?.invoke("OLD_MOVED")
      Files.move(merged.toPath(), live.toPath(), StandardCopyOption.ATOMIC_MOVE)
      syncDir(parent)
      writeState(tx, if (hadLive) "NEW_MOVED" else "NEW_MOVED_EMPTY")
      failAt?.invoke("NEW_MOVED")
      participant?.apply(tx)
      writeState(tx, "COMMITTED")
      // The commit marker must survive until rollback data is fully reclaimed.
      try { recoverOwned(tx, live, listDirectory, participant) } catch (_: Exception) { /* Next import retries committed cleanup. */ }
      return ImportReport(restored, conflicts, skippedLinks, parsed.legacyApiKeys, parsed.preferencesJson)
    } catch (t: Throwable) {
    if (tx.exists()) try { recoverOwned(tx, live, listDirectory, participant) } catch (rollback: Throwable) { t.addSuppressed(rollback) }
      throw t
    } finally {
      // Transaction-owned scratch data is removed only by validated phase recovery/cleanup.
    }
  }

  private fun validateExtract(archive: File, stage: File, listDirectory: (File) -> Array<File>?): Parsed {
    if (stage.exists()) deleteTree(stage, listDirectory)
    if (!stage.mkdirs() && !stage.isDirectory) throw IOException("Cannot create backup staging directory")
    val names = HashSet<String>()
    val actual = linkedMapOf<String, Pair<Long, String>>()
    var manifest: String? = null
    var total = 0L
    ZipInputStream(BufferedInputStream(FileInputStream(archive)), Charsets.UTF_8).use { zip ->
      while (true) {
        val e = zip.nextEntry ?: break
        val name = safePath(e.name)
        require(names.add(name)) { "Duplicate archive entry: $name" }
        if (name == MANIFEST) {
          val b = ByteArrayOutputStream(); val buf = ByteArray(BUF); var n: Int; var count = 0L
          while (zip.read(buf).also { n = it } != -1) if (n > 0) {
            count += n; require(count <= MAX_MANIFEST) { "Manifest too large" }; b.write(buf, 0, n)
          }
          manifest = b.toString(Charsets.UTF_8.name())
        } else {
          val f = File(stage, name)
          f.parentFile?.let { if (!it.mkdirs() && !it.isDirectory) throw IOException("Cannot create staged parent") }
          val d = MessageDigest.getInstance("SHA-256"); var size = 0L
          FileOutputStream(f).use { out ->
            val buf = ByteArray(BUF); var n: Int
            while (zip.read(buf).also { n = it } != -1) if (n > 0) {
              size += n; total += n
              require(size <= MAX_ENTRY && total <= MAX_TOTAL) { "Backup exceeds archive limits" }
              d.update(buf, 0, n); out.write(buf, 0, n)
            }
          }
          actual[name] = size to hex(d.digest())
        }
        require(names.size <= MAX_ENTRIES + 1)
      }
    }
    val lines = requireNotNull(manifest) { "Missing manifest" }.trimEnd().lines()
    val header = lines.firstOrNull()?.split('\t') ?: error("Empty manifest")
    require(header.size == 4 && header[0] == FORMAT && header[1] == VERSION && header[2] == DATA_SCHEMA) { "Unsupported backup format/schema version" }
    val active = unb64(header[3]).ifEmpty { null }?.let(::safePath)
    val items = lines.drop(1).filter(String::isNotEmpty).map(::decode)
    require(items.size <= MAX_ENTRIES && items.map { it.path }.toSet().size == items.size) { "Duplicate manifest path" }
    require(items.none { it.path.startsWith("@platform/") && it.path !in setOf(PREFS, DEEPSEEK_KEY, DASHSCOPE_KEY) }) { "Unknown platform data item" }
    require(actual.keys == items.map { it.path }.toSet()) { "Archive/manifest entries differ" }
    if (active != null) require(items.any { it.path == active && it.kind == 'F' }) { "Active config entry missing or not a regular file" }
    var preferencesJson: String? = null
    val legacyKeys = linkedMapOf<String, String>()
    for (i in items) {
      val (size, digest) = actual[i.path] ?: error("Missing archive entry")
      require(size == i.size && digest == i.sha256) { "Size/hash mismatch: ${i.path}" }
      if (i.kind == 'D') require(size == 0L)
      if (i.kind == 'L') {
        val linkBytes = i.target!!.toByteArray(Charsets.UTF_8)
        require(linkBytes.size.toLong() == size && sha(linkBytes) == digest) { "Symlink payload does not match manifest: ${i.path}" }
      }
      if (i.path in setOf(PREFS, DEEPSEEK_KEY, DASHSCOPE_KEY)) {
        require(i.kind == 'F')
        require(i.size <= if (i.path == PREFS) 512L * 1024 else 4L * 1024 * 1024) { "Platform item exceeds limit" }
        val value = File(stage, i.path).readText(Charsets.UTF_8)
        when (i.path) {
          PREFS -> preferencesJson = value
          DEEPSEEK_KEY -> legacyKeys["deepseek-key.txt"] = value
          DASHSCOPE_KEY -> legacyKeys["dashscope-key.txt"] = value
        }
        File(stage, i.path).delete()
      }
    }
    // Staged symbolic-link entry payload is the link text; replace its staging regular file only at merge time.
    return Parsed(items, active, preferencesJson, legacyKeys)
  }

  private fun scan(root: File, listDirectory: (File) -> Array<File>?): MutableList<Item> {
    val out = mutableListOf<Item>()
    fun visit(dir: File, prefix: String) {
      for (f in childrenOf(dir, listDirectory).sortedBy { it.name }) {
        val rel = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
        val a = Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (a.isSymbolicLink) {
          val target = Files.readSymbolicLink(f.toPath()).toString(); val b = target.toByteArray(Charsets.UTF_8)
          out += Item(rel, 'L', b.size.toLong(), sha(b), target)
        } else if (a.isDirectory) {
          out += Item(rel, 'D', 0, sha(ByteArray(0))); visit(f, rel)
        } else if (a.isRegularFile) {
          val (size, digest) = digest(f); require(size <= MAX_ENTRY); out += Item(rel, 'F', size, digest)
        }
        require(out.size <= MAX_ENTRIES) { "Too many archive entries" }
      }
    }
    visit(root, "")
    return out
  }

  private fun cloneTree(from: File, to: File, listDirectory: (File) -> Array<File>?) {
    val a = Files.readAttributes(from.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    if (a.isSymbolicLink) { to.parentFile?.mkdirs(); Files.createSymbolicLink(to.toPath(), Files.readSymbolicLink(from.toPath())); return }
    if (a.isDirectory) {
      if (!to.mkdirs() && !to.isDirectory) throw IOException("Cannot create staged directory: ${to.name}")
      childrenOf(from, listDirectory).forEach { cloneTree(it, File(to, it.name), listDirectory) }
      return
    }
    to.parentFile?.mkdirs()
    try { Files.createLink(to.toPath(), from.toPath()) } catch (_: Exception) { FileInputStream(from).use { i -> FileOutputStream(to).use { o -> i.copyTo(o, BUF) } } }
  }

  /** Never let a pre-existing symlink or file redirect/block archive writes in the staged DSH tree. */
  private fun hasBlockingAncestor(root: File, rel: String): Boolean {
    var cursor = root
    val parents = rel.split('/').dropLast(1)
    for (part in parents) {
      cursor = File(cursor, part)
      if (Files.exists(cursor.toPath(), NOFOLLOW_LINKS) &&
        (Files.isSymbolicLink(cursor.toPath()) || !Files.isDirectory(cursor.toPath(), NOFOLLOW_LINKS))) return true
    }
    return false
  }

  private fun streamFile(file: File, out: OutputStream, expected: Item) {
    val d = MessageDigest.getInstance("SHA-256"); var size = 0L
    FileInputStream(file).use { input -> val b = ByteArray(BUF); var n: Int; while (input.read(b).also { n = it } != -1) if (n > 0) { d.update(b, 0, n); out.write(b, 0, n); size += n } }
    require(size == expected.size && hex(d.digest()) == expected.sha256) { "Source changed during export: ${expected.path}" }
  }

  private fun digest(file: File): Pair<Long, String> {
    val d = MessageDigest.getInstance("SHA-256"); var size = 0L
    FileInputStream(file).use { i -> val b = ByteArray(BUF); var n: Int; while (i.read(b).also { n = it } != -1) if (n > 0) { d.update(b, 0, n); size += n } }
    return size to hex(d.digest())
  }

  private fun decrypt(encrypted: File, plain: File, password: CharArray) {
    FileInputStream(encrypted).use { src ->
      val magic = ByteArray(MAGIC.size); require(src.read(magic) == magic.size && magic.contentEquals(MAGIC)) { "Invalid backup header" }
      val salt = ByteArray(16); val noncePrefix = ByteArray(8)
      readFully(src, salt); readFully(src, noncePrefix)
      val key = deriveKey(password, salt)
      try {
        FileOutputStream(plain).use { output ->
          var expectedSequence = 0
          var clearTotal = 0L
          while (true) {
            val type = src.read()
            require(type >= 0) { "Missing authenticated final frame" }
            val seqBytes = ByteArray(4); val lenBytes = ByteArray(4)
            readFully(src, seqBytes); readFully(src, lenBytes)
            val sequence = int32(seqBytes); val length = int32(lenBytes)
            require(sequence == expectedSequence) { "Backup frame reordered, duplicated, or missing" }
            require(length in 0..FRAME_BYTES) { "Invalid backup frame length" }
            val final = type == FRAME_FINAL
            require((final && length == 0) || (!final && type == FRAME_DATA && length > 0)) { "Invalid backup frame type/length" }
            val encryptedFrame = ByteArray(length + FRAME_TAG_BYTES)
            readFully(src, encryptedFrame)
            val header = byteArrayOf(type.toByte()) + seqBytes + lenBytes
            val frameCipher = frameCipher(Cipher.DECRYPT_MODE, key, noncePrefix, expectedSequence, magic, salt, noncePrefix, header)
            val clear = frameCipher.doFinal(encryptedFrame) // Authenticate each frame before writing its plaintext.
            if (final) {
              require(clear.isEmpty())
              require(src.read() == -1) { "Trailing bytes after final backup frame" }
              break
            }
            clearTotal += clear.size
            require(clearTotal <= MAX_ARCHIVE) { "Decrypted archive exceeds size limit" }
            output.write(clear)
            expectedSequence++
          }
          output.fd.sync()
        }
      } catch (t: Throwable) {
        plain.delete()
        throw t
      } finally { key.fill(0) }
    }
  }

  private fun deriveKey(pass: CharArray, salt: ByteArray): ByteArray {
    val spec = PBEKeySpec(pass, salt, ITERATIONS, 256)
    return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
  }

  private fun frameCipher(mode: Int, key: ByteArray, noncePrefix: ByteArray, sequence: Int,
    magic: ByteArray, salt: ByteArray, prefix: ByteArray, frameHeader: ByteArray): Cipher {
    val nonce = noncePrefix + byteArrayOf((sequence ushr 24).toByte(), (sequence ushr 16).toByte(), (sequence ushr 8).toByte(), sequence.toByte())
    return Cipher.getInstance("AES/GCM/NoPadding").apply {
      init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
      updateAAD("DSH-BACKUP-AEAD-FRAME-V2".toByteArray(Charsets.US_ASCII))
      updateAAD(magic); updateAAD(salt); updateAAD(prefix); updateAAD(frameHeader)
    }
  }

  private class FrameEncryptingOutputStream(
    private val raw: OutputStream,
    private val key: ByteArray,
    private val magic: ByteArray,
    private val salt: ByteArray,
    private val prefix: ByteArray,
  ) : OutputStream() {
    private val pending = ByteArray(FRAME_BYTES)
    private var pendingSize = 0
    private var sequence = 0
    private var finished = false

    override fun write(value: Int) {
      if (finished) throw IOException("Frame stream is closed")
      pending[pendingSize++] = value.toByte()
      if (pendingSize == pending.size) emit(FRAME_DATA)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
      if (finished) throw IOException("Frame stream is closed")
      require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
      var cursor = offset; var remaining = length
      while (remaining > 0) {
        val copied = minOf(remaining, pending.size - pendingSize)
        System.arraycopy(bytes, cursor, pending, pendingSize, copied)
        cursor += copied; remaining -= copied; pendingSize += copied
        if (pendingSize == pending.size) emit(FRAME_DATA)
      }
    }

    override fun flush() { raw.flush() }
    override fun close() {
      if (finished) return
      try {
        if (pendingSize > 0) emit(FRAME_DATA)
        emit(FRAME_FINAL)
        raw.flush(); raw.close()
      } finally {
        finished = true
        pending.fill(0)
        key.fill(0)
      }
    }

    private fun emit(type: Int) {
      if (sequence == Int.MAX_VALUE) throw IOException("Too many encrypted frames")
      val length = if (type == FRAME_FINAL) 0 else pendingSize
      val header = byteArrayOf(type.toByte()) + byteArrayOf((sequence ushr 24).toByte(), (sequence ushr 16).toByte(), (sequence ushr 8).toByte(), sequence.toByte()) +
        byteArrayOf((length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte())
      val cipher = frameCipher(Cipher.ENCRYPT_MODE, key, prefix, sequence, magic, salt, prefix, header)
      val encrypted = cipher.doFinal(if (length == 0) ByteArray(0) else pending.copyOf(length))
      raw.write(header); raw.write(encrypted)
      sequence++
      pendingSize = 0
    }
  }

  @Synchronized
  internal fun recoverPending(parent: File, live: File, participant: PlatformParticipant,
    listDirectory: (File) -> Array<File>? = { it.listFiles() }) {
    val canonicalParent = parent.canonicalFile
    require(live.absoluteFile.parentFile.canonicalFile == canonicalParent) { "Recovery parent must be the DSH parent" }
    require(!Files.isSymbolicLink(live.toPath())) { "DSH root may not be a symbolic link" }
    if (!Files.exists(canonicalParent.toPath(), NOFOLLOW_LINKS)) return
    require(Files.isDirectory(canonicalParent.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(canonicalParent.toPath())) {
      "Recovery parent must be a regular directory"
    }
    recoverOwnedTransactions(canonicalParent, live.absoluteFile, listDirectory, participant)
  }

  private fun recoverOwnedTransactions(parent: File, live: File, listDirectory: (File) -> Array<File>?, participant: PlatformParticipant?) {
    val candidates = childrenOf(parent, listDirectory).filter { it.name.startsWith(TX_PREFIX) }
    for (candidate in candidates) {
      require(candidate.name.matches(Regex("\\.dsh-backup-tx-[0-9a-fA-F-]{36}")) && candidate.isDirectory) {
        "Unrecognized backup transaction path; preserving it: ${candidate.name}"
      }
      requireOwned(candidate, listDirectory)
    }
    for (candidate in candidates) {
      recoverOwned(candidate, live, listDirectory, participant)
    }
  }

  private fun newOwnedTransaction(parent: File, listDirectory: (File) -> Array<File>?): File {
    val reusable = childrenOf(parent, listDirectory).firstOrNull { candidate ->
      candidate.name.startsWith(TX_PREFIX) && candidate.name.matches(Regex("\\.dsh-backup-tx-[0-9a-fA-F-]{36}")) &&
        File(candidate, "state").let { it.isFile && it.readText(Charsets.US_ASCII).trim() == "DONE" }
    }
    if (reusable != null) {
      requireOwned(reusable, listDirectory)
      writeState(reusable, "PREPARING")
      return reusable
    }
    repeat(8) {
      val id = java.util.UUID.randomUUID().toString()
      val preparing = File(parent, ".dsh-backup-alloc-$id")
      val tx = File(parent, TX_PREFIX + id)
      try { Files.createDirectory(preparing.toPath()) } catch (_: java.nio.file.FileAlreadyExistsException) { return@repeat }
      val owner = File(preparing, "owner")
      FileOutputStream(owner).use { it.write(TX_OWNER.toByteArray(Charsets.US_ASCII)); it.fd.sync() }
      syncDir(preparing)
      writeState(preparing, "PREPARING")
      Files.move(preparing.toPath(), tx.toPath(), StandardCopyOption.ATOMIC_MOVE)
      syncDir(parent)
      return tx
    }
    throw IOException("Cannot allocate unique backup transaction directory")
  }

  private fun requireOwned(tx: File, listDirectory: (File) -> Array<File>?) {
    require(Files.isDirectory(tx.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(tx.toPath())) { "Backup transaction is not a directory" }
    val owner = File(tx, "owner")
    require(Files.isRegularFile(owner.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(owner.toPath()) && owner.length() == TX_OWNER.toByteArray(Charsets.US_ASCII).size.toLong() && owner.readText(Charsets.US_ASCII) == TX_OWNER) {
      "Unowned backup transaction; preserving it: ${tx.name}"
    }
    val allowed = setOf("owner", "state", "state.tmp", "encrypted.input", "decrypted.zip", "stage", "merged", "old", "platform")
    val names = childrenOf(tx, listDirectory).map { it.name }.toSet()
    require(names.all { it in allowed }) { "Unexpected backup transaction contents; preserving it" }
    for (name in names) {
      val child = File(tx, name)
      val dir = name in setOf("stage", "merged", "old", "platform")
      val validType = if (dir) Files.isDirectory(child.toPath(), NOFOLLOW_LINKS) else Files.isRegularFile(child.toPath(), NOFOLLOW_LINKS)
      require(!Files.isSymbolicLink(child.toPath()) && validType) {
        "Invalid owned transaction entry; preserving transaction"
      }
    }
    val state = File(tx, "state")
    require(Files.isRegularFile(state.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(state.toPath())) { "Backup transaction has no valid phase" }
    require(state.readText(Charsets.US_ASCII).trim() in setOf(
      "PREPARING", "PREPARED", "PREPARED_EMPTY", "OLD_MOVED", "OLD_MOVED_EMPTY", "NEW_MOVED", "NEW_MOVED_EMPTY", "ROLLED_BACK", "ROLLED_BACK_EMPTY", "COMMITTED", "DONE",
    )) { "Unknown backup transaction phase; preserving it" }
  }

  private fun recoverOwned(tx: File, live: File, listDirectory: (File) -> Array<File>?, participant: PlatformParticipant? = null) {
    requireOwned(tx, listDirectory)
    val phase = File(tx, "state").readText(Charsets.US_ASCII).trim()
    val old = File(tx, "old")
    val merged = File(tx, "merged")
    val liveExists = Files.exists(live.toPath(), NOFOLLOW_LINKS)
    val oldExists = Files.exists(old.toPath(), NOFOLLOW_LINKS)
    val mergedExists = Files.exists(merged.toPath(), NOFOLLOW_LINKS)
    when (phase) {
      "PREPARING" -> require(!oldExists)
      "PREPARED" -> require(if (oldExists) !liveExists else liveExists)
      "PREPARED_EMPTY" -> require(!oldExists && !liveExists)
      "OLD_MOVED" -> require((oldExists && (!liveExists || !mergedExists)) || (!oldExists && liveExists && !mergedExists))
      "OLD_MOVED_EMPTY" -> require(!oldExists && ((mergedExists && !liveExists) || !mergedExists))
      "NEW_MOVED" -> require(oldExists && !mergedExists)
      "NEW_MOVED_EMPTY" -> require(!oldExists && !mergedExists)
      "ROLLED_BACK" -> require(liveExists)
      "ROLLED_BACK_EMPTY" -> require(!liveExists)
      "COMMITTED" -> require(liveExists)
      "DONE" -> require(childrenOf(tx, listDirectory).map { it.name }.toSet() == setOf("owner", "state"))
      else -> error("Unknown backup transaction phase")
    }
    val hasPlatformJournal = Files.exists(File(tx, "platform").toPath(), NOFOLLOW_LINKS)
    if (hasPlatformJournal && participant == null && phase != "DONE") throw IOException("Platform recovery participant required")
    if (phase != "COMMITTED" && phase != "DONE") participant?.rollback(tx)
    when (phase) {
      "PREPARING" -> require(!oldExists) { "Unexpected displaced tree in PREPARING transaction" }
      "PREPARED" -> {
        if (oldExists) {
          require(!liveExists) { "Ambiguous PREPARED transaction state" }
          Files.move(old.toPath(), live.toPath(), StandardCopyOption.ATOMIC_MOVE); syncDir(live.parentFile)
        } else require(liveExists) { "Missing live and rollback trees" }
      }
      "PREPARED_EMPTY" -> require(!oldExists && !liveExists) { "Ambiguous empty PREPARED transaction state" }
      "OLD_MOVED" -> {
        if (oldExists) {
          if (liveExists) {
            require(!mergedExists) { "Ambiguous OLD_MOVED transaction state" }
            deleteTree(live, listDirectory)
          }
          Files.move(old.toPath(), live.toPath(), StandardCopyOption.ATOMIC_MOVE); syncDir(live.parentFile)
          writeState(tx, "ROLLED_BACK")
        } else {
          require(liveExists && !mergedExists) { "Missing or ambiguous rollback tree" }
          writeState(tx, "ROLLED_BACK")
        }
      }
      "OLD_MOVED_EMPTY" -> {
        require(!oldExists) { "Unexpected rollback tree for empty initial state" }
        if (mergedExists) require(!liveExists) { "Ambiguous empty OLD_MOVED transaction state" }
        else if (liveExists) deleteTree(live, listDirectory)
        writeState(tx, "ROLLED_BACK_EMPTY")
      }
      "NEW_MOVED" -> {
        require(oldExists && !mergedExists) { "Invalid NEW_MOVED transaction state" }
        if (liveExists) deleteTree(live, listDirectory)
        Files.move(old.toPath(), live.toPath(), StandardCopyOption.ATOMIC_MOVE); syncDir(live.parentFile)
        writeState(tx, "ROLLED_BACK")
      }
      "NEW_MOVED_EMPTY" -> {
        require(!oldExists && !mergedExists) { "Invalid empty NEW_MOVED transaction state" }
        if (liveExists) deleteTree(live, listDirectory)
        writeState(tx, "ROLLED_BACK_EMPTY")
      }
      "ROLLED_BACK" -> require(liveExists) { "Invalid ROLLED_BACK transaction state" }
      "ROLLED_BACK_EMPTY" -> require(!liveExists) { "Invalid empty ROLLED_BACK transaction state" }
      "COMMITTED" -> require(liveExists) { "Committed backup tree is missing" }
      "DONE" -> require(childrenOf(tx, listDirectory).map { it.name }.toSet() == setOf("owner", "state")) { "Invalid completed backup transaction state" }
      else -> error("Unknown backup transaction phase")
    }
    if (phase != "DONE") {
      if (phase == "COMMITTED") participant?.committed(tx)
      cleanupOwned(tx, listDirectory)
    }
  }

  private fun cleanupOwned(tx: File, listDirectory: (File) -> Array<File>?) {
    requireOwned(tx, listDirectory)
    // Rollback copy is removed first. If deletion fails, owner and phase remain for retry.
    for (name in listOf("old", "stage", "merged", "encrypted.input", "decrypted.zip", "platform")) {
      val entry = File(tx, name)
      if (Files.exists(entry.toPath(), NOFOLLOW_LINKS)) deleteTree(entry, listDirectory)
    }
    // Keep a valid owner and DONE phase to avoid an unlink crash window that
    // could strand an unowned directory and permanently block future imports.
    writeState(tx, "DONE")
  }

  private fun writeState(tx: File, value: String) {
    val state = File(tx, "state")
    val temp = File(tx, "state.tmp")
    FileOutputStream(temp).use { it.write((value + "\n").toByteArray(Charsets.UTF_8)); it.fd.sync() }
    Files.move(temp.toPath(), state.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    syncDir(tx)
  }

  private fun syncDir(dir: File) {
    try { java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) } }
    catch (_: Exception) { /* Some Android filesystems do not expose directory fsync; atomic sibling renames remain required. */ }
  }

  private fun deleteTree(file: File, listDirectory: (File) -> Array<File>?) {
    if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return
    if (Files.isDirectory(file.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath())) childrenOf(file, listDirectory).forEach { deleteTree(it, listDirectory) }
    Files.deleteIfExists(file.toPath())
  }

  private fun childrenOf(dir: File, listDirectory: (File) -> Array<File>?): Array<File> =
    listDirectory(dir) ?: throw IOException("Cannot enumerate directory: ${dir.absolutePath}")

  private fun safePath(s: String): String {
    require(s.isNotEmpty() && !s.startsWith('/') && !s.startsWith('\\') && !s.contains('\\') && '\u0000' !in s && !s.matches(Regex("^[A-Za-z]:.*"))) { "Unsafe path" }
    require(s.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe path" }
    require(s.toByteArray(Charsets.UTF_8).size <= 4096) { "Archive path too long" }
    return s
  }

  private fun safeLink(path: String, target: String): Boolean {
    if (target.isEmpty() || target.startsWith('/') || target.startsWith('\\') || target.contains('\\') || target.matches(Regex("^[A-Za-z]:.*"))) return false
    val stack = path.substringBeforeLast('/', "").split('/').filter(String::isNotEmpty).toMutableList()
    for (p in target.split('/')) when (p) {
      "", "." -> Unit
      ".." -> if (stack.isEmpty()) return false else stack.removeAt(stack.lastIndex)
      else -> stack += p
    }
    return true
  }

  private fun encode(i: Item) = listOf("I", i.kind.toString(), i.size.toString(), i.sha256, b64(i.path), b64(i.target ?: "")).joinToString("\t") + "\n"
  private fun decode(line: String): Item {
    val p = line.split('\t'); require(p.size == 6 && p[0] == "I")
    val kind = p[1].single(); require(kind in "FDL")
    val size = p[2].toLong(); require(size in 0..MAX_ENTRY)
    require(p[3].matches(Regex("[0-9a-f]{64}")))
    val path = safePath(unb64(p[4])); val target = unb64(p[5]).ifEmpty { null }
    require((kind == 'L') == (target != null))
    require(path != MANIFEST)
    return Item(path, kind, size, p[3], target)
  }

  private fun readFully(i: InputStream, b: ByteArray) { var off = 0; while (off < b.size) { val n = i.read(b, off, b.size - off); require(n > 0) { "Truncated archive header" }; off += n } }
  private fun File.readTextOrNull(): String? = if (exists()) readText(Charsets.UTF_8) else null
  private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))
  private fun unb64(s: String) = String(Base64.getUrlDecoder().decode(s), Charsets.UTF_8)
  private fun sha(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))
  private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
