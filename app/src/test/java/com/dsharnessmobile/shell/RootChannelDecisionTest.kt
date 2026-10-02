package com.dsharnessmobile.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * root 通道判据的**真值表**（纯函数，无设备、无反射）。
 *
 * 为什么要有这一层（review 2026-10-02）：判据此前只有「源码 contains 字符串」的启发式契约测试，
 * 改一个条件都不一定判红；而这条判据决定「要不要不可逆地清掉一条 UNKNOWN 隔离租约」，
 * 必须把每种取值组合钉死。`null` 参数表示对应探测**抛异常/读不到**，不是 false。
 */
class RootChannelDecisionTest {

  // ── rootChannelAvailable：已有真实取值时的可用判定 ─────────────────────────────

  @Test fun suGrantAloneIsEnoughRegardlessOfShizuku() {
    assertTrue(ShizukuTransport.rootChannelAvailable(true, -1, false))
    assertTrue(ShizukuTransport.rootChannelAvailable(true, 2000, false))
    assertTrue(ShizukuTransport.rootChannelAvailable(true, 0, false))
  }

  @Test fun shizukuNeedsBOTHServerRootAndThisAppAuthorization() {
    assertTrue(ShizukuTransport.rootChannelAvailable(false, 0, true))
    // review 指出的缺口：服务端是 root ≠ 本应用能派发 —— 授权被撤时必须判为不可用
    assertFalse(ShizukuTransport.rootChannelAvailable(false, 0, false))
    // shell(2000) 服务端即便「已授权」也不是 root 通道
    assertFalse(ShizukuTransport.rootChannelAvailable(false, 2000, true))
    assertFalse(ShizukuTransport.rootChannelAvailable(false, 2000, false))
    // 读不到 uid(-1) 不得当成可用
    assertFalse(ShizukuTransport.rootChannelAvailable(false, -1, true))
    assertFalse(ShizukuTransport.rootChannelAvailable(false, -1, false))
  }

  // ── classifyRootChannel：三态（未知绝不等于「确定不存在」）─────────────────────

  @Test fun suGrantShortCircuitsToAvailable() {
    assertEquals(ShizukuTransport.RootChannel.AVAILABLE,
      ShizukuTransport.classifyRootChannel(true, false, null, null, null))
    assertEquals(ShizukuTransport.RootChannel.AVAILABLE,
      ShizukuTransport.classifyRootChannel(true, true, true, false, 2000))
  }

  @Test fun missingShizukuOrStoppedServerIsDefinitelyAbsent() {
    // 未安装 Shizuku ⇒ 肯定没有这条路
    assertEquals(ShizukuTransport.RootChannel.ABSENT,
      ShizukuTransport.classifyRootChannel(false, false, null, null, null))
    // 已安装但服务端不在跑（ping 明确 false）⇒ 肯定没有
    assertEquals(ShizukuTransport.RootChannel.ABSENT,
      ShizukuTransport.classifyRootChannel(false, true, false, null, null))
  }

  @Test fun revokedAppPermissionIsDefinitelyAbsentEvenWhenServerIsRoot() {
    // review 场景：Shizuku 服务端仍以 root 运行，但本应用授权被撤 ⇒ 派发不了任何特权工作。
    // 判序必须让「未授权」先于 uid 生效——否则 getUid() 抛权限异常会被误归成 UNKNOWN 而不清租约。
    assertEquals(ShizukuTransport.RootChannel.ABSENT,
      ShizukuTransport.classifyRootChannel(false, true, true, false, null))
    assertEquals(ShizukuTransport.RootChannel.ABSENT,
      ShizukuTransport.classifyRootChannel(false, true, true, false, 0))
    assertEquals(ShizukuTransport.RootChannel.ABSENT,
      ShizukuTransport.classifyRootChannel(false, true, true, false, 2000))
  }

  @Test fun authorizedButNonRootServerIsAbsent() {
    assertEquals(ShizukuTransport.RootChannel.ABSENT,
      ShizukuTransport.classifyRootChannel(false, true, true, true, 2000))
    assertEquals(ShizukuTransport.RootChannel.ABSENT,
      ShizukuTransport.classifyRootChannel(false, true, true, true, -1))
  }

  @Test fun authorizedRootServerIsAvailable() {
    assertEquals(ShizukuTransport.RootChannel.AVAILABLE,
      ShizukuTransport.classifyRootChannel(false, true, true, true, 0))
  }

  @Test fun probeFailuresStayUnknownAndNeverTurnIntoAbsent() {
    // 冷启动：binder 尚未就绪（pingBinder 抛异常）⇒ 未知，不得据此清租约
    assertEquals(ShizukuTransport.RootChannel.UNKNOWN,
      ShizukuTransport.classifyRootChannel(false, true, null, null, null))
    // 能 ping 但授权状态读不到（checkSelfPermission 抛异常）⇒ 未知
    assertEquals(ShizukuTransport.RootChannel.UNKNOWN,
      ShizukuTransport.classifyRootChannel(false, true, true, null, null))
    // 已授权、但读不到服务端 uid ⇒ 未知
    assertEquals(ShizukuTransport.RootChannel.UNKNOWN,
      ShizukuTransport.classifyRootChannel(false, true, true, true, null))
  }

  @Test fun unknownIsDistinctFromAbsentByConstruction() {
    // 防回归：只要「读不到」被归成 ABSENT，`clearLeaseWhenNoRootChannel` 就会在冷启动窗口里
    // 做不可逆清算。三条未知路径逐一钉住。
    val unknownPaths = listOf(
      ShizukuTransport.classifyRootChannel(false, true, null, null, null),
      ShizukuTransport.classifyRootChannel(false, true, true, null, null),
      ShizukuTransport.classifyRootChannel(false, true, true, true, null),
    )
    for (state in unknownPaths) {
      assertEquals(ShizukuTransport.RootChannel.UNKNOWN, state)
      assertFalse(state == ShizukuTransport.RootChannel.ABSENT)
    }
  }
}
