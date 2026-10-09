# known-gaps.md — 0.14.5 当前缺口与验收入口

> 更新：2026-10-07。目标 0.14.5；0.14.4 是旧包与反馈基线。源码有改动不等于新包已验收。
> 当前自主开发与模拟器验收已获用户授权。Jev 本轮另有明确的 CI-only 例外：仅可在 IssacNgai fork 创建 draft PR，base/head 均为该 fork，用于 CI；不授权上游推送/PR、合并、发布或设备动作，也不豁免最终产物三层验收。其它远程操作仍需最终授权。历史版本的“外测由别人做”不适用于本轮。

## 当前入口

- 协调仓 `docs/REFACTOR-2026-10-05.md` 是本轮唯一活动计划、Issue Ledger 与事实账；子仓地图从 [AGENTS.md](../../AGENTS.md) 进入。
- 恢复与用户数据：[PERSISTENT-DATA.md](PERSISTENT-DATA.md)；协调仓 `docs/RECOVERY-ARCH-0.14.5.md`。当前实现以源码和冻结后的证据为准。
- 三层验收：[emulator-test-protocol.md](emulator-test-protocol.md)。旧包、热推或仅静态审计不能代替最终双 ABI 包的代码/CDP/ADB 用户层验收。

## 本轮仍需收口

| 项 | 当前事实 | 必须取得的结论 |
|---|---|---|
| Hard / Soft 插件状态 | 可信 factory identity、四态与真实冷启动健康正在实施；live 用户 patch 不能成为 Hard 来源 | 冻结后统一测试；Disabled 官方条目不能阻止健康；隔离不丢源码/config；退出 Safe Mode 恢复用户配置 |
| ONLINE 事务与健康 | 完整 tar 和 usr-only 兼容、只交换 usr、Hard sidecar 与 probation/commit/rollback、真实完成态正在修订 | 最终新包验证不改用户 HOME；仅 HTTP 活着不得提前提交；失败后可恢复 |
| 回撤节流纪元 | `.undo-auto-done` schema 2 仅抑制相同安装指纹 + 回滚前失败 patch digest，成功回滚后原子写入；身份变化重开观察窗，旧数字时间戳在有效期内保守抑制并记诊断；显式用户重启在确认本壳子进程停止后清 marker，自动重试不清。纯逻辑与源码接线测试已补，仍待本批 Gradle fresh 验证 | 同一失败配置防循环，新安装/新配置不继承旧事件；marker 写入只发生在成功整份恢复后 |
| 许可证 | Shizuku api/provider/aidl/shared 13.1.5 官方 POM 均为 MIT；已补固定许可文本、矩阵、notices 生成及版本/文本反例 | 最终 APK assets/licenses 内含 MIT 全文与 SDK notices；当前源码门禁通过不等于实包已确认 |
| MiMo 与配置保留 | 旧基线真实 wire on/off 成功；虚拟屏目标达成但旧任务 13 次工具调用超出事先 10 次限制，整例未通过 | 新正式包自主任务预先约定可行预算并按真实事件计数；保留既有配置/会话/凭据 |
| UI / 范围 / 通知 / 浏览器 | 有旧版和阶段证据；最终当前树尚未完成三层回归 | 两个模拟器横竖屏、透明度、拒绝面、文件选择、后台任务和旧会话均按同一产物复验 |
| 真机 | MuMu x86_64 是开发首验；arm64 真机是发布前补充 | 华为 WebView identity/安装反馈不能以模拟器替代真机证据 |
| Jev strict 输入与新快照证据 | 已保存原生 `pwv/pw` 三态、包含隐藏身份行的完整性/窗口库存证据、事件纪元、能力握手与两次 live 唯一 RID 输入验证；manage strict 分支预派发检查、原始 row/gen、禁止 fallback、验证回执，契约见 [BRIDGE-API.md](BRIDGE-API.md)。源码存在与 host 测试不代表 native 构建/设备可用；工作区 proposed dsh-jev-local 为外部源码，未安装、未纳入 fork APK/runtime | 最终同一 APK 的 native 编译与反例、旧/新生产者握手、隐藏重复/预算/空 child/多窗口/焦点后二次验证、精确 editable text 回读；代码/CDP/ADB 双方向后才能称已验收 |
| Jev freshness 非原子边界 | native `notificationTimeout=200ms` 保留；只验证已送达事件纪元，live walk/最终复查→动作仍有窗口。聚焦引入事件/IME 窗口后拒绝属 fail-closed，不是应忽略的噪声 | 同产物真机/模拟器证明动作与拒绝行为；不得写“gen/无变化截图证明绝对新鲜”，也不得放宽库存或旧目标绑定换取假绿 |
| Jev host 环境与 CI | 本机无可用 Java/Android SDK，不能声称 Gradle/native 编译通过；本轮聚合协议 gate 在本机因 host-relative 子进程路径失败；父任务以绝对路径单独生成器 `--check` 得到四套成功，不能将其改报聚合 gate 通过，也不推断项目永久缺陷。draft PR 只用于取得 CI 构建证据 | CI native 构建/单测和最终设备产物三层验收分别留证；静态/code-map/bridge 绿不替代任何缺层 |
| CI 面两个已死 workflow 条目 | `Snapshot Build`（本地已 `git rm`，未推送）与 `ADB runtime patch`（其分支 `contrib/adb-runtime-patch` 已不存在）仍出现在 Actions 列表，均已 `gh workflow disable`；REST 无删除 workflow 的接口 | push 后 `Snapshot Build` 自动消失；`ADB runtime patch` 需在默认分支上不存在该文件，当前仅能保持 disabled |

## 历史事项不自动关闭

旧登记的浏览器自动落位、看门狗恢复入口、API<30 截图回落、多 ROM 限制、动效、滚动条、低置信度依赖判据等，保留当时证据。应按当前源码逐项分类为已改、仍存在或仅特定设备待验；不得把旧“未修”直接当当前事实，也不得把归档当完成。

完整原文在 [历史缺口登记](../archive/known-gaps-pre-0.14.5-2026-10-07.md)。当时版本结论、授权和停止点均不覆盖当前本轮要求。
