package com.dsharnessmobile.shell

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 属主维护「结算语义」源码契约（2026-10-01 无 root/已撤 root 设备卡「等待属主维护」事故换来）：
 * ①拿到完整信封的回执必须 finish 租约（部分修复回 repair-incomplete，而不是 UNKNOWN 隔离到整机重启）；
 * ②su 从未派发出去（spawn 即失败/明确拒绝）同样 finish 租约；
 * ③granted 缓存不得比 su 本体活得久（拉不起进程 ⇒ 立刻降级，别让启动自愈按旧缓存反复误判）；
 * ④root 路已不存在时，残留租约必须在**入口**（咨询租约之前）被清掉，worker 里清够不到；
 * ⑤「root 路存在」= su 已授权，或（Shizuku 服务端 root 且**本应用**已授权）；在飞维护不参与清算。
 *
 * 成员边界取「下一个顶层成员声明」（与 RootGrantTest.kt:128 同口径）。取下一个文档注释会跨进
 * 隔壁函数，让断言命中别的函数里的同名片段——防线被删掉仍然绿（review 2026-10-02 指出）。
 */
class OwnershipLeaseSettlementFixtureTest {
  private fun source(name: String): String {
    val suffix = "app/src/main/java/com/dsharnessmobile/shell/$name.kt"
    val file = listOf(File(suffix), File("dsh-mobile-apk/$suffix"),
      File("src/main/java/com/dsharnessmobile/shell/$name.kt")).firstOrNull { it.isFile }
      ?: error("owned source unavailable: $name")
    // 剔除注释行，断言只钉代码（与 RootGrantTest 同口径），别让文档措辞把契约测试洗绿。
    return file.readText().lineSequence().filterNot {
      val line = it.trimStart()
      line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")
    }.joinToString("\n")
  }

  private fun body(name: String, signature: String): String {
    val text = source(name)
    val start = text.indexOf(signature)
    if (start < 0) error("missing member $name: $signature")
    val next = Regex("(?m)^  (?:(?:private|internal|override) )?fun ")
      .find(text, start + signature.length)?.range?.first ?: text.length
    return text.substring(start, next)
  }

  @Test fun acknowledgedEnvelopeSettlesLeaseEvenWhenRepairIsIncomplete() {
    val repair = body("ShizukuTransport", "fun repairOwnership(")
    val envelope = repair.indexOf("val envelopeComplete =")
    val incomplete = repair.indexOf("lease.complete(result, definitive = false)")
    val settled = repair.indexOf("lease.complete(settled, definitive = true)")
    assertTrue(envelope >= 0 && incomplete > envelope && settled > incomplete)
    // 部分修复的落点：ok=false + repair-incomplete（复用 CALL_REASON 已登记文案），remaining 如实 -1。
    assertTrue(repair.contains("put(\"reason\", \"repair-incomplete\")"))
    assertTrue(repair.contains("put(\"remaining\", -1)"))
    assertFalse(repair.contains("definitive = verified"))
  }

  @Test fun suSpawnFailureIsNeverDispatchedAndSettlesLeaseInsteadOfQuarantine() {
    // 边界收紧后，这条 contains 钉的确实是 execRoot **自己**新增的拒绝集项：删掉它即红。
    val entry = body("RootAccess", "fun execRoot(")
    assertTrue(entry.contains("\"su-exec-failed\""))
    assertFalse(entry.contains("writeState("))
    val repair = body("RootAccess", "private fun repairOwned(")
    assertTrue(repair.contains("\"su-exec-failed\""))
    // 两条路径的「未派发」分支都必须 finish 而不是 markUnknown。
    val finishInExec = entry.indexOf("RootMaintenanceLease.finish(app)")
    val markInExec = entry.indexOf("RootMaintenanceLease.markUnknown")
    assertTrue(finishInExec >= 0 && (markInExec < 0 || finishInExec < markInExec))
  }

  @Test fun grantedCacheIsDemotedWhenSuCannotEvenBeSpawned() {
    val dispatch = body("RootAccess", "private fun execPrivileged(")
    val catchBlock = dispatch.substringAfter("} catch (t: Throwable) {")
    assertTrue(catchBlock.contains("writeState(context.applicationContext, STATE_DENIED, -1)"))
    assertTrue(catchBlock.indexOf("writeState") < catchBlock.indexOf("fail(\"su-exec-failed\""))
  }

  @Test fun workerReportsNoRootPathSkipAndNoLongerClearsTheLeaseItself() {
    val direct = body("ShizukuTransport", "internal fun autoHealOwnershipDirect(")
    // 探测必须在 fence 之前：fence 入口会被残留租约挡住，放里面永远走不到。
    val probe = direct.indexOf("if (uid != RootGrant.ROOT_UID && !viaSu)")
    val fence = direct.indexOf("RootExecutionFence.maintenance(context)")
    assertTrue(probe >= 0 && fence > probe)
    assertTrue(direct.contains("\"skipped\", \"no-root-path\""))
    // RootGrantTest 同款钉子：直连修复面仍必须落在 fence 内（return 体）。
    assertTrue(direct.contains("return RootExecutionFence.maintenance(context) {"))
    // 清算已前移到入口；worker 内不再自己清（那条路径会被 fence 的 outstanding 短路挡住）。
    assertFalse(direct.contains("clearWithoutRootPath"))
  }

  @Test fun noRootPathClearIsTheOnlyNonOwnerExitAndKeepsUnknownSemanticsElsewhere() {
    val lease = source("RootMaintenanceLease")
    // finish 仍要求原 owner + 非 unknown；唯一的新出口是 clearWithoutRootPath。
    assertTrue(lease.contains("unknown || owner !== Thread.currentThread()"))
    assertTrue(lease.substringAfter("fun finish(").substringBefore("fun markUnknown")
      .doesNotContain("clearWithoutRootPath"))
  }

  @Test fun staleLeaseIsClearedAtTheJobEntryBeforeTheOutstandingShortCircuit() {
    // 真机实测（2026-10-02）：只把清算放在 worker 里够不到 start() 的 outstanding 短路，
    // 启动仍会挂死；清算必须发生在咨询租约之前。
    val jobs = body("RootOwnershipJobs", "private fun start(")
    val clear = jobs.indexOf("ShizukuTransport.clearLeaseWhenNoRootChannel(app)")
    val shortCircuit = jobs.indexOf("RootMaintenanceLease.outstanding(app)")
    assertTrue(clear >= 0 && shortCircuit > clear)
    val helper = body("ShizukuTransport", "internal fun clearLeaseWhenNoRootChannel(")
    assertTrue(helper.contains("RootMaintenanceLease.clearWithoutRootPath(app)"))
    // 在飞维护不参与清算（否则会把在飞租约抹掉，让 worker 自己的 finish 落成 lease-clear-failed）。
    assertTrue(helper.contains("if (RootExecutionFence.maintenanceActive) return false"))
    assertTrue(helper.contains("if (rootChannelAvailable(app)) return false"))
    assertTrue(helper.indexOf("maintenanceActive") < helper.indexOf("rootChannelAvailable(app)"))
  }

  @Test fun rootChannelRequiresServerRootAndThisAppAuthorization() {
    // review 2026-10-02：`getUid()==0` 是**服务端** uid，不等于本应用已授权——只认它会把
    // 「服务端 root 但本应用授权被撤」误判成有 root 路，残留租约仍把启动挂死。
    val channel = body("ShizukuTransport", "private fun rootChannelAvailable(")
    assertTrue(channel.contains("if (RootAccess.isGranted(context)) return true"))
    assertTrue(channel.contains("if (serverUid != RootGrant.ROOT_UID) return false"))
    assertTrue(channel.contains("Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED"))
  }

  private fun String.doesNotContain(other: String): Boolean = !this.contains(other)
}
