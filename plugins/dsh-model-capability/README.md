# dsh-model-capability — 自定义提供商能力发现（0.14.5，Issue #56）

> 状态：**已挂载到 profile，支持模型级能力回填和 Issue #56 的 MiMo 二态思考开关**。
> 本轮以本地协议夹具和两份插件回归验证；未使用真实凭据或真实 API。

## 解决的问题

用户自填的提供商路由（llm-pi-ai `providers.<route>`）没有能力元数据：设置页的自定义
提供商编辑器只写 `id/name/contextWindow/maxTokens`，所以输入框旁**没有可选的推理等级**，
模态（图片/音频）也只能手改 settings.yaml。本插件按「端点说了什么就记什么」的原则补上
这层元数据。

## 发现管线（严格顺序，任一阶段未声明即保持未知）

1. **被动 GET**：只读取配置端点**实际返回**的字段。`openai-completions`/`anthropic-messages`/
   `openrouter` 走 `GET <baseURL>/models`；`google-generative` 走 `GET <baseURL>/models?key=`；
   `ollama` 走 `GET <baseURL>/api/tags` + 只读元数据 `POST /api/show`（不发补全请求、不耗额度）。
2. **厂商描述符解析**：按**响应形状**（不是 URL 或模型名）识别显式能力 schema：
   - OpenAI 兼容：`data[].id`，若带 `context_length`/`max_output_tokens` 则读入；
   - OpenRouter 形态：`architecture.input_modalities` → 模态；`supported_parameters` 含
     `reasoning` 只说明「支持推理」，**等级词表仍未声明 → 保持未知并记注**；
     `reasoning_efforts` 显式给出等级词才写入（词表外单词丢弃并记注）；
   - Google：`models[].name`（剥 `models/` 前缀）、`inputTokenLimit`/`outputTokenLimit`；
   - Ollama：`/api/show.capabilities`（`vision` → 图片模态；`thinking` → 支持推理但等级未知）。
3. **主动 reasoning 探测受显式授权和协议门控**：只有 `active=true` 且 `confirm=true`、
   在线端点模式才可能发请求。`openai-responses` 使用 `/responses` 与
   `reasoning.effort`；`openai-completions` 仅在用户明确配置
   `compat.thinkingFormat=openai` 且 `supportsReasoningEffort=true` 时使用标准
   `/chat/completions` 与 `reasoning_effort`。两者都先发无效 effort 负控；只有服务端以明确
   提及 reasoning/effort 的参数错误拒绝负控，候选成功才标记 `active-probe`。MiMo 的
   `thinking.type` 二态开关永不进入 effort 探测。

**协议与写回**：有效 wire API 按 DSH 规则解析，模型 compat 高于 route compat，高于同协议 catalog。
`openai-completions` 的自动档位要求有效 compat 同时明确 `thinkingFormat` 与
`supportsReasoningEffort: true`；pi-ai 会按该组合生成对应 request field。`openai-responses`
由 serializer 将 `thinkingLevelMap` 映射到 `reasoning.effort`，不要求 Completions compat。
仅 models.dev 或端点自述的多个 Responses 档位不足以证明语义不同，因此保持未知；目录内相同
wire spelling 的档位会合并。
route/model 已显式设置的 compat、reasoningEfforts（包括 `false`）仅参与判断，不会被重新写回。

**MiMo 思考开关**：插件只对精确、版本化的官方 profile（`mimo-v2.5`、`mimo-v2.5-pro`）或用户
明确写入 `compat.thinkingFormat=mimo` 的 `openai-completions` 模型生成二态运行时视图。即使已有
用户档位（例如 `high`），设置中的原值也不改写；DSH 运行时仅把旧的非 `off` 默认值归一为
canonical `low`，selector 固定显示“关闭思考/开启思考”。PiAi serializer 只发送
`thinking.type=disabled|enabled`，不发送 `reasoning_effort`；多轮 assistant 历史回传实际
`reasoning_content`，没有内容时按协议要求补空字段。官方默认思考为开启。
未知 API、模型别名、URL 和 route 名均不用于自动识别，用户已声明的值保持原样。上游 `dsh/`
保持只读，适配由本仓 0.14.5 补丁完成。

**不变量**：不做 URL/模型名启发式；未声明的能力保持缺席；每条能力都带 `source`
（`endpoint-descriptor` / `vendor-descriptor` / `models-dev` / `engine-catalog` / `user-fallback`）；
`reasoningEfforts` 永远是模型级值。MiMo 借用 `off/low` 代表二态 UI，但不会将其序列化成强度。

## 工具

`model_capability_probe`（参数：`provider` 必填；`active`/`confirm`/`levels` 可选）。主动探测会
产生模型请求与费用，必须同时传 `active=true`、`confirm=true`；默认档位为 `low/medium/high`。
返回摘要文本 + 结构化 `report`（`fetched` 审计抓取过的端点、`models[].sources` 标来源、
`unknown` 列未获元数据的模型、`notes` 记端点自述与丢弃的未知等级词）。

## 自动补给的事件循环边界

自动离线补给在初次描述符投影之后、每条 route 发现之前，以及最终 fresh 签名读取之前
通过 `setImmediate` 让出事件循环。上游 `describe({ namespaces })` 实际仍做完整 profile/schema
投影；这些同步工作与目录 JSON 解析叠在同一 turn 曾令 C4 超预算。各阶段保留原描述符
缓存、写后失效、最新签名重读与 CAS 重试；checkpoint 期间插件销毁则终止后续发现和读写。
自动路径仍使用 `offline=true`、`allowModelsDevNetwork=false`，主动请求授权条件保持原样。

## 验收边界

- 插件和补丁的本地回归覆盖配置校验、selector 二态映射、Completions 请求字段、用户值保护、
  多轮 `reasoning_content` 回传，以及无确认/离线/不支持 API 时的零主动请求。
- 真实服务端行为和设备 UI 尚未在本轮验收；这不影响协议夹具和离线回归结论，也不应表述为真实 API 已验证。

## 开发

```powershell
cd plugins\dsh-model-capability
npm run build     # tsc
npm test          # build + 插件本地回归测试
```
