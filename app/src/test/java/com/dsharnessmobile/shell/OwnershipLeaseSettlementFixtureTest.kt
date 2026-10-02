package com.dsharnessmobile.shell

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 属主维护「结算语义 + 清算纪律」源码契约（2026-10-01 无 root/已撤 root 设备卡「等待属主维护」事故换来）：
 * ①拿到完整信封的回执必须 finish 租约（部分修复回 repair-incomplete，而不是 UNKNOWN 隔离到整机重启）；
 * ②su 从未派发出去（spawn 即失败/明确拒绝）同样 finish 租约；
 * ③granted 缓存不得比 su 本体活得久（拉不起进程 ⇒ 立刻降级，别让启动自愈按旧缓存反复误判）；
 * ④残留租约只在**入口**（咨询租约之前、同一临界区内）清算，且**只在 root 通道被肯定判定为不存在时**清；
 * ⑤worker 内不再清算，且 worker 与入口共用同一三态判据（真值表见 [RootChannelDecisionTest]）。
 *
 * 切片纪律（review 2026-10-02 两次收紧）：成员边界取「下一个成员声明」而不是下一个文档注释；
 * 注释用**扫描器**剔除（不是按行首匹配）。否则断言会命中隔壁函数或行尾注释里的同名片段而假绿——
 * 本文件末尾的 `memberBoundaryDoesNotCrossIntoTheNextMember` 就是这条纪律的反证用例。
 */
class OwnershipLeaseSettlementFixtureTest {
  private fun source(name: String): String {
    val suffix = "app/src/main/java/com/dsharnessmobile/shell/$name.kt"
    val file = listOf(File(suffix), File("dsh-mobile-apk/$suffix"),
      File("src/main/java/com/dsharnessmobile/shell/$name.kt")).firstOrNull { it.isFile }
      ?: error("owned source unavailable: $name")
    return stripComments(file.readText())
  }

  /**
   * 真正的注释扫描器：字符串字面量整体保留（断言的靶子就是字面量，里面的 `//` 不是注释起点），
   * 块注释跨行带走，行尾 `//` 也剔除（这正是「行尾注释里的 su-exec-failed 也能满足 contains」的修法）。
   */
  private fun stripComments(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
      val c = text[i]
      if (c == '"') {
        out.append(c); i++
        while (i < text.length) {
          val d = text[i]
          out.append(d)
          if (d == '\\' && i + 1 < text.length) { out.append(text[i + 1]); i += 2; continue }
          i++
          if (d == '"') break
        }
        continue
      }
      if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
        while (i < text.length && text[i] != '\n') i++
        continue
      }
      if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
        i += 2
        while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
        i = minOf(text.length, i + 2)
        continue
      }
      out.append(c); i++
    }
    return out.toString()
  }

  /**
   * 成员声明边界：注解 + 修饰符（含 suspend/inline/operator…）+ `fun`/`val`/`var`/`object`/`class` 等。
   * 只认**两空格缩进**的顶层成员，所以函数体内的局部 `val`/局部 `fun` 不会误判为边界。
   */
  private val memberBoundary = Regex(
    "(?m)^  (?:@[A-Za-z_][\\w.]*(?:\\([^)]*\\))?\\s+)*" +
      "(?:(?:public|private|protected|internal|open|final|abstract|override|suspend|inline|operator|" +
      "infix|tailrec|external|actual|expect|const|lateinit|crossinline|noinline)\\s+)*" +
      "(?:fun|val|var|object|class|interface|enum class|companion object)\\b",
  )

  private fun body(name: String, signature: String): String {
    val text = source(name)
    val start = text.indexOf(signature)
    if (start < 0) error("missing member $name: $signature")
    val next = memberBoundary.find(text, start + signature.length)?.range?.first ?: text.length
    return text.substring(start, next)
  }

  @Test fun acknowledgedEnvelopeSettlesLeaseEvenWhenRepairIsIncomplete() {
    val repair = body("ShizukuTransport", "fun repairOwnership(")
    val envelope = repair.indexOf("val envelopeComplete =")
    val incomplete = repair.indexOf("lease.complete(result, definitive = false)")
    val settled = repair.indexOf("lease.complete(settled, definitive = true)")
    assertTrue(envelope >= 0 && incomplete > envelope && settled > incomplete)
    assertTrue(repair.contains("put(\"reason\", \"repair-incomplete\")"))
    assertTrue(repair.contains("put(\"remaining\", -1)"))
    assertFalse(repair.contains("definitive = verified"))
  }

  @Test fun suSpawnFailureIsNeverDispatchedAndSettlesLeaseInsteadOfQuarantine() {
    // 边界收紧后这条 contains 钉的确实是 execRoot **自己**新增的拒绝集项；且执行面在隔壁函数里，
    // 用 writeState 的存在与否反证切片没跨过去。
    val entry = body("RootAccess", "fun execRoot(")
    assertTrue(entry.contains("\"su-exec-failed\""))
    assertFalse(entry.contains("writeState("))
    val repair = body("RootAccess", "private fun repairOwned(")
    assertTrue(repair.contains("\"su-exec-failed\""))
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

  @Test fun workerSharesTheSameTriStateCriterionAndNoLongerClearsTheLeaseItself() {
    val direct = body("ShizukuTransport", "internal fun autoHealOwnershipDirect(")
    // 与入口同一判据（同一三态探测），不再自带一套 uid/viaSu 判断。
    val probe = direct.indexOf("val probe = probeRootChannel(app)")
    val fence = direct.indexOf("RootExecutionFence.maintenance(context)")
    assertTrue(probe >= 0 && fence > probe)
    assertFalse(direct.contains("Shizuku.getUid()"))
    assertTrue(direct.contains("probe.state != RootChannel.AVAILABLE"))
    // 不可用报 no-root-path；探测不完备报 root-channel-unknown（不得混为一谈）。
    assertTrue(direct.contains("\"no-root-path\""))
    assertTrue(direct.contains("\"root-channel-unknown\""))
    assertTrue(direct.contains("return RootExecutionFence.maintenance(context) {"))
    assertFalse(direct.contains("clearWhenNoRootChannel"))
  }

  @Test fun entryClearOnlyFiresOnDefiniteAbsenceAndIsGuardedByTheFence() {
    val entry = body("ShizukuTransport", "internal fun clearLeaseWhenNoRootChannel(")
    // 判据必须是**唯一那个决定性探测**（真绑定），不是 getUid 可读性那个替身
    assertTrue(entry.contains("val probe = probeRootChannel(app)"))
    assertTrue(entry.contains("if (probe.state != RootChannel.ABSENT) return false"))
    assertTrue(entry.contains("LeaseClearProbe.record(app, probe, decisive = true)"))
    assertTrue(entry.contains("!RootExecutionFence.maintenanceActive && !RootAccess.isGranted(app)"))
    assertTrue(entry.contains("RootMaintenanceLease.clearWhenNoRootChannel(app)"))
  }

  @Test fun theSingleJudgeUsesARealBindAndNeverUidReadability() {
    val probe = body("ShizukuTransport", "internal fun probeRootChannel(")
    assertTrue(probe.contains("readyService(app, applyGate = false, requestPermission = false)"))
    assertTrue(probe.contains("remote.uid()"))
    assertTrue(probe.contains("decideNoRootChannel("))
    // review 的核心：撤权后 getUid() 仍返回 0，不能再用它当「已授权」的依据
    assertFalse(probe.contains("getUid()"))
    // worker 与入口消费的是同一个探测（判据同源）
    assertTrue(body("ShizukuTransport", "internal fun autoHealOwnershipDirect(").contains("probeRootChannel(app)"))
  }

  @Test fun forcedClearRefusesWhileMaintenanceIsActive() {
    val forced = body("ShizukuTransport", "internal fun forceClearMaintenanceLease(")
    assertTrue(forced.contains("if (RootExecutionFence.maintenanceActive)"))
    assertTrue(forced.contains("\"maintenance-active\""))
    assertTrue(forced.contains("RootMaintenanceLease.clearWhenNoRootChannel(app)"))
  }

  @Test fun leaseClearCommitIsBoundedRetriedInsteadOfLeavingAPermanentLease() {
    val lease = body("RootMaintenanceLease", "fun clearWhenNoRootChannel(")
    assertTrue(lease.contains("for (attempt in 1..2)"))
    assertTrue(lease.contains("if (cleared) break"))
  }

  @Test fun waitingPhaseHasASlowRecheckAfterTheBoundedBudget() {
    assertTrue(source("EngineStartFlow").contains("ownershipRetry.nextDelayMs() ?: SLOW_OWNERSHIP_RECHECK_MS"))
    assertTrue(source("EngineService").contains("SLOW_OWNERSHIP_RECHECK_MS = 300_000L"))
  }

  @Test fun waitingPhaseOffersAProductExitInsteadOfForcingAReboot() {
    val guide = source("GuidePageRenderer")
    assertTrue(guide.contains("confirmClearMaintenanceLease(pending)"))
    assertTrue(guide.contains("ShizukuTransport.forceClearMaintenanceLease"))
    assertTrue(guide.contains("setPositiveButton"))
    assertTrue(guide.contains("清除隔离并重试"))
  }

  @Test fun leaseClearEvaluatesItsGuardInsideTheSameCriticalSectionAsBegin() {
    // 判断—清除与 begin() 共用同一临界区（review 第 4 点）：guard 必须在锁内、清除之前求值。
    val lease = body("RootMaintenanceLease", "fun clearWhenNoRootChannel(")
    val lock = lease.indexOf("synchronized(lock)")
    val guard = lease.indexOf("if (!guard()) return@synchronized false")
    val commit = lease.indexOf("edit().clear().commit()")
    assertTrue(lock >= 0 && guard > lock && commit > guard)
  }

  @Test fun staleLeaseIsClearedInsideTheJobEntryCriticalSection() {
    // 真机实测（2026-10-02）：清算放进本锁、且只在确实有租约时付探测成本；置 running 在同一临界区内。
    val jobs = body("RootOwnershipJobs", "private fun start(")
    val lock = jobs.indexOf("synchronized(lock)")
    val runningCheck = jobs.indexOf("if (running) return false")
    val firstOutstanding = jobs.indexOf("RootMaintenanceLease.outstanding(app) != null")
    val clear = jobs.indexOf("ShizukuTransport.clearLeaseWhenNoRootChannel(app)")
    val secondOutstanding = jobs.lastIndexOf("RootMaintenanceLease.outstanding(app) != null")
    val setRunning = jobs.indexOf("running = true")
    assertTrue(lock >= 0 && runningCheck > lock && firstOutstanding > runningCheck)
    assertTrue(clear > firstOutstanding && secondOutstanding > clear)
    assertTrue(setRunning > clear)
  }

  @Test fun noRootPathClearIsTheOnlyNonOwnerExitAndKeepsUnknownSemanticsElsewhere() {
    val lease = source("RootMaintenanceLease")
    assertTrue(lease.contains("unknown || owner !== Thread.currentThread()"))
    assertTrue(lease.substringAfter("fun finish(").substringBefore("fun markUnknown")
      .doesNotContain("clearWhenNoRootChannel"))
  }

  @Test fun memberBoundaryDoesNotCrossIntoTheNextMember() {
    // 反证：切片右边界必须是下一个成员声明，且注释被扫描器剔除（否则这些断言会因隔壁代码/注释而假绿）。
    assertFalse(body("RootAccess", "fun execRoot(").contains("private fun execPrivileged"))
    assertFalse(body("RootAccess", "fun execRoot(").contains("private fun repairOwned"))
    assertFalse(body("ShizukuTransport", "fun repairOwnership(").contains("internal fun autoHealOwnershipDirect("))
    assertFalse(body("ShizukuTransport", "internal fun rootChannelAvailable(")
      .contains("internal fun classifyRootChannel("))
    // 行尾/块注释里的字面量与说明文字都不该出现在切片里
    assertFalse(body("RootAccess", "private fun execPrivileged(").contains("异常只进日志"))
    assertFalse(body("ShizukuTransport", "internal fun clearLeaseWhenNoRootChannel(").contains("真机实测"))
  }

  private fun String.doesNotContain(other: String): Boolean = !this.contains(other)
}
