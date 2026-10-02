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
 * ④root 路已不存在时，残留租约必须有自救出口（否则每次启动都挂起直到重启）。
 */
class OwnershipLeaseSettlementFixtureTest {
  private fun source(name: String): String {
    val suffix = "app/src/main/java/com/dsharnessmobile/shell/$name.kt"
    val file = listOf(File(suffix), File("dsh-mobile-apk/$suffix"),
      File("src/main/java/com/dsharnessmobile/shell/$name.kt")).firstOrNull { it.isFile }
      ?: error("owned source unavailable: $name")
    return file.readText()
  }

  private fun body(name: String, signature: String): String =
    source(name).substringAfter(signature).substringBefore("\n  /**")

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
    val entry = body("RootAccess", "fun execRoot(")
    assertTrue(entry.contains("\"su-exec-failed\""))
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

  @Test fun staleLeaseHasSelfRescueExitWhenNoRootPathRemains() {
    val lease = body("RootMaintenanceLease", "fun clearWithoutRootPath(")
    assertTrue(lease.contains("if (pendingEpoch == null) return@synchronized true"))
    assertTrue(lease.contains("edit().clear().commit()"))
    val direct = body("ShizukuTransport", "internal fun autoHealOwnershipDirect(")
    // 探测必须在 fence 之前：fence 入口会被残留租约挡住，放里面永远走不到清算。
    val probe = direct.indexOf("if (uid != RootGrant.ROOT_UID && !viaSu)")
    val clear = direct.indexOf("RootMaintenanceLease.clearWithoutRootPath(app)")
    val fence = direct.indexOf("RootExecutionFence.maintenance(context)")
    assertTrue(probe >= 0 && clear > probe && fence > clear)
    assertTrue(direct.contains("\"skipped\", \"no-root-path\""))
    // RootGrantTest 同款钉子：直连修复面仍必须落在 fence 内（return 体，探测在 fence 前）。
    assertTrue(direct.contains("return RootExecutionFence.maintenance(context) {"))
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
    assertTrue(helper.contains("uid == RootGrant.ROOT_UID || RootAccess.isGranted(app)"))
    assertTrue(helper.contains("RootMaintenanceLease.clearWithoutRootPath(app)"))
    // 有 root 路时不得清（隔离语义保持）。
    assertTrue(helper.contains("return false"))
  }

  private fun String.doesNotContain(other: String): Boolean = !this.contains(other)
}
