package com.dsharnessmobile.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * root 通道判据的**真值表**（纯函数，无设备、无反射）。
 *
 * 2026-10-02 复审后的口径：判据只有一条，用**真绑定结果**（`bindSucceeded` + `boundServiceUid`）当
 * 「本应用派发得出去」的实证 —— 本机 Shizuku 服务端 v13.6 上 `getUid()` 撤权后仍返回 0、
 * `checkSelfPermission()` 恒报 denied，两个廉价信号都不能作为授权依据。
 */
class RootChannelDecisionTest {

  // ── rootChannelAvailable：已有真实取值时的可用判定 ─────────────────────────────

  @Test fun suGrantAloneIsEnoughRegardlessOfShizuku() {
    assertTrue(ShizukuTransport.rootChannelAvailable(true, -1, false))
    assertTrue(ShizukuTransport.rootChannelAvailable(true, 2000, false))
    assertTrue(ShizukuTransport.rootChannelAvailable(true, 0, false))
  }

  @Test fun shizukuNeedsBOTHRootServiceAndConfirmedAvailability() {
    assertTrue(ShizukuTransport.rootChannelAvailable(false, 0, true))
    // 服务端是 root ≠ 本应用能派发
    assertFalse(ShizukuTransport.rootChannelAvailable(false, 0, false))
    // shell(2000) 服务端即便可用也不是 root 通道
    assertFalse(ShizukuTransport.rootChannelAvailable(false, 2000, true))
    assertFalse(ShizukuTransport.rootChannelAvailable(false, 2000, false))
    // 读不到 uid(-1) 不得当成可用
    assertFalse(ShizukuTransport.rootChannelAvailable(false, -1, true))
    assertFalse(ShizukuTransport.rootChannelAvailable(false, -1, false))
  }

  // ── decideNoRootChannel：唯一判据（绑定实证 + 三态）────────────────────────────

  @Test fun boundRootServiceMeansAvailableBoundShellDoesNot() {
    assertEquals(ShizukuTransport.RootChannel.AVAILABLE,
      ShizukuTransport.decideNoRootChannel(true, true, true, 0, "", null))
    assertEquals(ShizukuTransport.RootChannel.ABSENT,
      ShizukuTransport.decideNoRootChannel(true, true, true, 2000, "", null))
    assertEquals(ShizukuTransport.RootChannel.ABSENT,
      ShizukuTransport.decideNoRootChannel(true, true, true, -1, "", null))
  }

  @Test fun revokedPermissionIsAbsentOnlyWhenTwoSignalsAgree() {
    // 绑定失败 + 权限被拒 + 自检也明确未授权（两源一致）⇒ 肯定派发不了 ⇒ 允许清算
    assertEquals(ShizukuTransport.RootChannel.ABSENT,
      ShizukuTransport.decideNoRootChannel(true, true, false, -1, "shizuku-denied", false))
    assertEquals(ShizukuTransport.RootChannel.ABSENT,
      ShizukuTransport.decideNoRootChannel(true, true, false, -1, "shizuku-permission-requested", false))
    // 权限被拒但自检读不到（矛盾/不可信）⇒ 保守保留隔离
    assertEquals(ShizukuTransport.RootChannel.UNKNOWN,
      ShizukuTransport.decideNoRootChannel(true, true, false, -1, "shizuku-denied", null))
    assertEquals(ShizukuTransport.RootChannel.UNKNOWN,
      ShizukuTransport.decideNoRootChannel(true, true, false, -1, "shizuku-denied", true))
  }

  @Test fun definitelyAbsentCodesAllowClearing() {
    // 未安装 / 服务端没跑 / 版本过低 / UserService 协议过旧 ⇒ 肯定没有这条通道
    assertEquals(ShizukuTransport.RootChannel.ABSENT,
      ShizukuTransport.decideNoRootChannel(false, null, false, -1, "", null))
    assertEquals(ShizukuTransport.RootChannel.ABSENT,
      ShizukuTransport.decideNoRootChannel(true, false, false, -1, "", null))
    assertEquals(ShizukuTransport.RootChannel.ABSENT,
      ShizukuTransport.decideNoRootChannel(true, true, false, -1, "shizuku-prev11", null))
    assertEquals(ShizukuTransport.RootChannel.ABSENT,
      ShizukuTransport.decideNoRootChannel(true, true, false, -1, "shizuku-user-service-too-old", null))
  }

  @Test fun probeFailuresStayUnknownAndNeverTurnIntoAbsent() {
    // binder 抛异常（冷启动未就绪）⇒ 未知
    assertEquals(ShizukuTransport.RootChannel.UNKNOWN,
      ShizukuTransport.decideNoRootChannel(true, null, false, -1, "", null))
    // 绑定失败但原因不明（未绑定 / 配置未确认）⇒ 未知，保留隔离
    assertEquals(ShizukuTransport.RootChannel.UNKNOWN,
      ShizukuTransport.decideNoRootChannel(true, true, false, -1, "shizuku-user-service-not-bound", false))
    assertEquals(ShizukuTransport.RootChannel.UNKNOWN,
      ShizukuTransport.decideNoRootChannel(true, true, false, -1, "shizuku-configuration-required", false))
  }

  @Test fun unknownIsDistinctFromAbsentByConstruction() {
    // 防回归：只要「读不到/说不清」被归成 ABSENT，`clearLeaseWhenNoRootChannel` 就会做不可逆清算。
    val unknownPaths = listOf(
      ShizukuTransport.decideNoRootChannel(true, null, false, -1, "", null),
      ShizukuTransport.decideNoRootChannel(true, true, false, -1, "shizuku-denied", null),
      ShizukuTransport.decideNoRootChannel(true, true, false, -1, "shizuku-user-service-not-bound", false),
    )
    for (state in unknownPaths) {
      assertEquals(ShizukuTransport.RootChannel.UNKNOWN, state)
      assertFalse(state == ShizukuTransport.RootChannel.ABSENT)
    }
  }
}
