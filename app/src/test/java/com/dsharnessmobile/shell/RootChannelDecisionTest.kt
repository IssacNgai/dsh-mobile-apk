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
 *
 * 2026-10-02 真机二次修正：**能否取到服务端 uid 才是「已授权」的实证**——
 * `checkSelfPermission()` 在本机服务端 v13.6 上恒报 denied，把它当第一判据会导致「服务端 root
 * 且授权有效」被误判为不可用而误清租约（真机 A 例实测踩到）。
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
      ShizukuTransport.classifyRootChannel(true, false, null, null, false))
  }

  @Test fun missingShizukuOrStoppedServerIsDefinitelyAbsent() {
    assertEquals(ShizukuTransport.RootChannel.ABSENT,
      ShizukuTransport.classifyRootChannel(false, false, null, null, false))
    assertEquals(ShizukuTransport.RootChannel.ABSENT,
      ShizukuTransport.classifyRootChannel(false, true, false, null, false))
  }

  @Test fun readableRootUidIsAvailableEvenWhenSelfCheckSaysDenied() {
    // 真机 A 例：服务端 root、本应用确有授权，但 checkSelfPermission() 恒 denied（服务端版本差异）。
    // 判据以「取得到 uid」为实证 ⇒ AVAILABLE，绝不能被自检的假阴性带成 ABSENT（那会误清租约）。
    assertEquals(ShizukuTransport.RootChannel.AVAILABLE,
      ShizukuTransport.classifyRootChannel(false, true, true, 0, false))
    // 非 root 服务端即便 uid 读得到也不可用
    assertEquals(ShizukuTransport.RootChannel.ABSENT,
      ShizukuTransport.classifyRootChannel(false, true, true, 2000, false))
    assertEquals(ShizukuTransport.RootChannel.ABSENT,
      ShizukuTransport.classifyRootChannel(false, true, true, -1, false))
  }

  @Test fun unreadableUidWithExplicitDenialIsAbsent() {
    // review 场景：服务端仍以 root 运行，但本应用授权被撤 ⇒ uid 读不到 + 自检明确未授权 ⇒ ABSENT
    assertEquals(ShizukuTransport.RootChannel.ABSENT,
      ShizukuTransport.classifyRootChannel(false, true, true, null, true))
  }

  @Test fun probeFailuresStayUnknownAndNeverTurnIntoAbsent() {
    // 冷启动：binder 尚未就绪（pingBinder 抛异常）⇒ 未知，不得据此清租约
    assertEquals(ShizukuTransport.RootChannel.UNKNOWN,
      ShizukuTransport.classifyRootChannel(false, true, null, null, false))
    assertEquals(ShizukuTransport.RootChannel.UNKNOWN,
      ShizukuTransport.classifyRootChannel(false, true, null, null, true))
    // uid 读不到、自检也没给出「明确未授权」⇒ 未知（保守不清）
    assertEquals(ShizukuTransport.RootChannel.UNKNOWN,
      ShizukuTransport.classifyRootChannel(false, true, true, null, false))
  }

  @Test fun unknownIsDistinctFromAbsentByConstruction() {
    // 防回归：只要「读不到」被归成 ABSENT，`clearLeaseWhenNoRootChannel` 就会在冷启动窗口里
    // 做不可逆清算。两条未知路径逐一钉住。
    val unknownPaths = listOf(
      ShizukuTransport.classifyRootChannel(false, true, null, null, false),
      ShizukuTransport.classifyRootChannel(false, true, true, null, false),
    )
    for (state in unknownPaths) {
      assertEquals(ShizukuTransport.RootChannel.UNKNOWN, state)
      assertFalse(state == ShizukuTransport.RootChannel.ABSENT)
    }
  }
}
