# Mobile Agent Runtime 项目进展与内部实现

> 快照日期：2026-08-22（Asia/Shanghai）
>
> 依据：当前工作树源码、本机构建与测试、仓库内截至 2026-08-12 的运行记录。
>
> 结论边界：本次没有连接 Android 设备，也没有启动 iOS Simulator，因此没有新增真机/模拟器任务样本；运行成功率沿用仓库中已保存的证据，不从“编译通过”外推。

## 1. 结论

项目当前处于**功能型 Alpha / 产品验收未完成**阶段：

- 手机端真正的 `@mariozechner/pi-agent-core@0.73.1` 已在 Android 和 iOS 用户任务路径中运行，不再使用两端各自手写的 Agent 循环。
- prompt、Pi session、模型/工具调度、设备工具执行、结果与 benchmark 记录已能在手机端闭环；模型推理仍通过用户配置的远程 OpenAI-compatible Chat Completions 服务。
- Android 已实现 Accessibility + Intent 的前台跨 App 控制，并用 Service-owned QuickJS 支撑 Agent 在 Activity 退到后台后继续推进。
- iOS 已实现公共 SDK 允许的 URL、App Intent、Shortcut、分享等能力，但**不能**读取或点击任意第三方 App UI。
- 双平台 L0、Android Clock L1、Android Chrome C1、iOS public L1 已形成 10/10 开发或产品基线；原定美团、小红书、抖音、微信四个 L2 任务尚未完成。
- 当前源码和三类构建均通过本机验证，但核心改造仍主要存在于未提交工作树中，尚不是可从 `origin/main` 直接重建的已发布状态。

一句话判断：**核心架构路线已经跑通，下一阶段重点不应继续扩展 Agent 框架，而应完成真机、目标 App、登录态和平台后台边界的产品级验收。**

## 2. 目标与验收进度

| Gate | 当前状态 | 已完成 | 主要缺口 |
|---|---|---|---|
| G0 基线真实 | 基本完成，文档尚需收口 | Node/Android/iOS 构建通过；协议、benchmark schema 和结果证据已存在 | 关键实现未提交；部分旧文档语句已落后于后续证据 |
| G1 Pi 移动嵌入 | 已实现，未封板 | Android QuickJS、iOS WKWebView 均运行原版 Pi core；session、取消、故障 journal 已实现 | Android 真机自然 Doze/OEM 省电；iOS 在线取消、在线进程恢复、锁屏和长时后台证据不足 |
| G2 平台执行后端 | 部分完成 | Android Accessibility + Intent；iOS 公共 invoke 路径；桌面 ADB adapter | Android 不占用用户前台的通用执行未实现；iOS 任意第三方 App GUI 自动化不受公共 SDK 支持 |
| G3 真实 App benchmark | 部分完成 | L0 双端；Android Clock L1、Chrome C1；iOS public L1 均有 10/10 记录 | Android 真机产品基线；M1/X1/D1/W1 四个原始 L2 目标 App 与授权测试账号 |

### 已保存的运行证据

| 组合 | 结果 | 证据等级 | 不能证明什么 |
|---|---:|---|---|
| Android QuickJS L0 fixture | 10/10 | emulator / dev-only | 不证明在线模型和真实 App |
| iOS WKWebView L0 fixture | 10/10 | simulator / dev-only | 不证明真机、长时后台和第三方 App |
| Android Clock L1 | 10/10 | emulator / dev-only | 不计产品门槛 |
| Android Chrome C1 | 10/10；p50/p95 43.903s/51.657s | emulator / dev-only | 不替代四个原始 L2 App，也不证明真机稳定性 |
| iOS `open_settings` deterministic L1 | 10/10；p95 2.251s | simulator / deterministic | 不证明在线模型或外部页面结果 |
| iOS public URL Release L1 | 10/10；p50/p95 12.393s/16.923s | physical device / `dev_only=false` | 只证明公开 URL 路径和 session 恢复，不证明任意第三方 App UI 控制 |

上述结果来源见 `docs/results/`。`docs/benchmark-v0.md` 中“iOS 在线 smoke 尚未运行”一句早于后续 physical Release 结果；判断当前状态时，以后者和 `docs/goal-v0.md` 的更新状态为准。

## 3. 总体架构

```text
用户任务
  │
  ▼
Android Compose / iOS SwiftUI
  │
  ▼
AgentSession ─────────────── 用户确认、取消、前后台生命周期
  │
  ▼
原版 pi-agent-core
  ├─ Android: QuickJS (quickjs-kt) + Foreground Service
  └─ iOS: WKWebView + beginBackgroundTask（有限时间）
  │
  ▼  JSON bridge: model_complete / tool_execute / runtime_event / agent_complete
原生能力层
  ├─ 模型：HTTPS OpenAI-compatible Chat Completions
  ├─ device_observe
  ├─ device_act
  └─ device_invoke
  │
  ├─ Android: AccessibilityService + Intent/IntentSender
  └─ iOS: URL / SFSafariViewController / App Intent / Shortcut / Share Sheet
  │
  ├─ transcript 持久化
  └─ benchmark pending journal + JSONL evidence
```

设计核心是“**共享 Agent 内核，原生持有敏感能力**”：Pi core 决定模型—工具—模型循环；API Key、HTTP 请求、用户审批、系统权限和设备执行都留在 Kotlin/Swift 原生层。这样既保证双平台运行同一 Agent 语义，也不把凭据和高权限对象注入 JavaScript。

## 4. 一次任务如何执行

1. `AgentSession` 校验 prompt 与本机 API Key，建立 metrics 和 benchmark run；benchmark prompt 使用干净 session。
2. 宿主启动 Pi runtime，并恢复普通会话的 transcript。
3. Pi core 将 system prompt、历史消息和三个工具定义交给原生 `model_complete`。
4. 原生 `OpenAICompatibleClient` 调用配置的 HTTPS Chat Completions endpoint，并把响应转换回 Pi assistant message。
5. 如果模型返回 tool call，Pi core 校验参数后调用 `tool_execute`。
6. 原生 runtime 执行 `device_observe / device_act / device_invoke`；拨号、分享、支付、发送、删除等动作先进入用户确认。
7. 工具结果和可选截图回到 Pi transcript，继续下一轮模型调用；默认最多 20 个模型 turn。Android host 另设 15 分钟总任务超时；iOS 依赖任务取消和系统给出的有限后台预算。
8. Pi 返回 final 和完整 messages；宿主持久化普通 transcript，并将成功、失败或取消写入 benchmark JSONL。

三原语的职责稳定不变：

- `device_observe`：获取设备状态和能力。Android 可返回前台 App、语义 UI Tree、截图；iOS 只返回本 App 状态和公共能力矩阵。
- `device_act`：执行点击、输入、滚动、滑动、返回、Home 等 GUI 动作。Android 由 Accessibility 执行；iOS 公共路径明确返回不支持。
- `device_invoke`：优先用系统能力打开 App/URL/设置、导航、拨号、分享或 Shortcut，减少脆弱的逐步点击。

## 5. Android 内部实现

### Runtime carrier

`QuickJsPiAgentRunner` 从 APK asset 加载打包后的 Pi bundle，通过 `quickjs-kt@1.0.12` 运行：

- 128 MiB JS memory limit、1 MiB stack limit；bundle 初始化限时 60 秒，单任务限时 15 分钟。
- 向 QuickJS 暴露唯一异步桥 `mobileNativeCall`，只接受结构化 JSON request/response。
- `cancel()` 同时取消模型请求、原生 bridge coroutine，并中断 QuickJS evaluation。
- 使用 `AgentForegroundService` 保持长任务进程优先级，并提供可停止的常驻通知。

Android 最初使用 WebView，但 5 分钟后台样本显示原生网络完成后 JavaScript 不继续推进，Activity 恢复前台后才继续。因此用户任务 carrier 已迁移到 Service-owned QuickJS；WebView 只保留 fixture/对照用途。

### 设备执行

`AndroidDeviceRuntime` 连接 `MobileAgentAccessibilityService`：

- observe 读取前台窗口、可访问节点、交互/锁屏状态，并按需截图。
- act 优先按 text、content description、resource id、role 等语义目标定位；动作后重新观察验证。
- invoke 使用受约束的 Android Intent/IntentSender，而不是向模型暴露任意 shell。
- 屏幕熄灭或锁定时返回 `SCREEN_NOT_INTERACTIVE` / `DEVICE_LOCKED`，要求 Agent 停止重试并提示用户。

限制：Accessibility 控制目标 App 时，目标 App 必须占用当前前台；它不能读取其他 App 私有数据库，也不能获得 Android 37 Computer Control 的 OEM privileged 能力。

### 状态与安全

- API Key 使用 Android Keystore AES/GCM 密钥加密后存入 app-private SharedPreferences。
- Base URL 必须是无嵌入凭据、query、fragment 的 HTTPS URL；model id 经过长度和非空校验。
- 普通 Pi transcript 存在 app-private SharedPreferences；benchmark 不复用普通 transcript。
- 进程启动时会补写上次未终结的 pending run，避免 crash 从样本中消失。

## 6. iOS 内部实现

### Runtime carrier

`PiCoreFixtureRunner` 实际同时承担 Pi runtime host 和用户任务 runner：

- 在 non-persistent `WKWebView` 中运行同一份 Pi Web bundle。
- 只允许 `https://mobile-agent.local/runtime` 主 frame 使用带 reply 的 script bridge。
- bridge request 以原生 UUID 跟踪，WebContent 进程终止时取消所有 bridge/model Task、将当前 run 记为 renderer failure，并重建 runtime。
- `AgentSession` 用 `beginBackgroundTask` 获取系统给出的有限收尾时间；到期时主动停止，不承诺常驻。

iOS 已证明普通后台会挂起 WKWebView；20 秒 deterministic 闭环可在 background task 预算内完成，60 秒样本会由 expiration 终止。因此 iOS 路线必须按“有限后台”设计，不能复制 Android Foreground Service 语义。

### 设备执行

`IOSDeviceRuntime` 只使用公开 API：

- observe 返回 application state、当前能力和最近一次 invoke，不读取其他 App UI。
- HTTP/HTTPS URL 在 App 内以 `SFSafariViewController` 打开；其他 scheme 交给 `UIApplication.open`。
- 支持 Apple Maps、拨号、分享面板、App Intent/Shortcut 和已注册 URL Scheme。
- invoke 的“系统已接受”不等于目标 App 内结果已验证；记录中保留 `externally_verified=false`。

限制：iOS 公共 SDK 不允许本 App截图、读取或点击任意第三方 App 的现有界面。LiveContainer bridge 仅在 `research/` 中作为侧载研究，不属于当前公共产品链路。

### 状态与安全

- API Key 存入 Keychain，使用 `AfterFirstUnlockThisDeviceOnly`，不进入 iCloud Keychain。
- 普通 Pi transcript 存入 UserDefaults；benchmark 使用独立干净 session。
- 分享、拨号和 Shortcut 在原生层请求用户确认。
- 任务退到后台完成或失败时用本地通知反馈；通知权限在用户真实发起任务后申请。

## 7. Benchmark 与可观测性

双平台 recorder 采用相同的 `schema_version=1` JSONL：

- run 开始时原子写 `pending-run.json`。
- 正常终态追加 `runs.jsonl` 后清除 pending。
- 下次进程启动发现 pending 时，补写 `failed + crash=true + RUN_INTERRUPTED`，不自动重放旧动作。
- 每次工具调用保存结构化 evidence 和结果 SHA-256。Android 的 URL evidence 保存 host 与 URL hash；iOS 当前会把公开 invoke 的原始 URL保存在 `target_reference`，所以导出文件不能视为已脱敏。
- 记录 model/tool/turn 数、耗时、前台干扰、人工确认、观察/动作失败、权限和登录态。
- 聚合器按 platform/backend/task/environment/endpoint/model/cohort 隔离，防止模型、endpoint 或测试批次变化后混算。
- C1 另做 post-adjudication，旧版缺少点击、搜索、滚动或正文证据的“成功”会重新判为失败。

汇总命令：

```bash
npm run benchmark:summary -- runs.jsonl
```

## 8. 桌面开发链路

手机产品链路之外，仓库保留两套桌面能力：

- MCP server：`src/mcp-server.ts` 把同样的 observe/act/invoke 暴露为 `device.observe / device.act / device.invoke`，底层通过 ADB + UIAutomator 驱动 Android。
- BYOK HTTP gateway：支持 OpenAI Responses 和 OpenAI-compatible Chat；默认只监听 loopback，非本机监听必须配置 bearer token，设备写操作还要求请求显式设置 `allow_device_actions=true`。

这两套能力用于开发、调试和外部集成，不算手机产品的本机闭环证据。

## 9. 2026-08-22 本机验证

| 检查 | 结果 | 通过条件 |
|---|---|---|
| `npm test` | 22/22 通过 | 0 failed / cancelled / skipped |
| `npm run check` | 通过 | TypeScript 无类型错误 |
| `npm run build` | 通过 | Node/MCP/Gateway 编译完成 |
| Android `testDebugUnitTest` | 12 项既有结果为 0 failure；当前 Gradle 输入判定 `UP-TO-DATE` | Gradle task 成功 |
| Android `assembleDebug` | `BUILD SUCCESSFUL` | APK 构建 task 成功 |
| `npm run ios:typecheck` | 通过 | Swift 类型检查无错误 |
| `npm run ios:build` | `BUILD SUCCEEDED` | iOS 26.5 Simulator Debug 构建成功 |

Gradle 9.5 默认 configuration cache 在本机长时间停留于 task graph 计算；加 `--no-configuration-cache --offline --no-daemon` 后测试 3 秒完成。这是本次构建工具观察，不应记录成业务测试失败。

本次审计时 `adb devices -l` 为空，所有 iOS Simulator 为 Shutdown，因此未执行新的端到端设备任务。

## 10. 当前主要风险与缺口

1. **版本可复现性**：`main` 与 `origin/main` 都停在 `8d9c1d0`，计入本文件后，当前工作树有 17 个修改、2 个删除和 29 个未跟踪条目。Pi、benchmark、QuickJS 和大量文档尚未进入提交历史。
2. **Android 产品证据**：现有 L0/L1/C1 主要来自 emulator；缺 Android 真机、自然 Doze、OEM 省电和目标 App 版本矩阵。
3. **前台干扰**：普通 Accessibility 方案无法在不占用用户屏幕的情况下通用操作真实 App；Android Computer Control 需要 OEM/privileged 准入。
4. **iOS 能力天花板**：公共 SDK 路线无法完成四个目标 App 的任意 UI 抽取；必须改为目标 App 已公开的 Intent/URL/Shortcut，或把侧载容器作为独立研究产品评估。
5. **L2 数据准备**：四个目标 App、固定版本、授权测试账号、登录态探针和可重复网络条件尚未准备完毕。
6. **敏感数据**：benchmark 可能含原始 prompt、结构化结果和 iOS invoke 原始 URL；微信等样本必须只使用明确授权的测试账号，导出文件按敏感数据处理。若要外发，应先统一双平台 target-reference 脱敏策略。
7. **文档一致性**：结果报告已更新到 2026-08-12，但个别总览句子仍停留在更早状态；需要把本文件作为统一入口，再逐项修正旧文档。

## 11. 推荐下一步与验收条件

1. **先固化可重建基线**：审查当前 48 个工作树条目，拆分提交 Pi core、平台 host、benchmark 和证据文档；从干净 checkout 重跑第 9 节命令。
2. **完成 Android 真机基线**：固定 APK、设备/OS、endpoint/model/cohort，在真机依次跑 L0、Clock L1、Chrome C1 各 10 次；要求 0 crash、0 permission loss，并报告自然 Doze/OEM 条件。
3. **按平台重新定义 iOS L2**：只选择有公开 App Intent、Universal Link、URL Scheme 或 Shortcut 的任务；无法由公共能力观察结果的任务不得记 success。
4. **准备原始 L2 测试资产**：固定美团/小红书/抖音/微信版本和授权账号；先实现并验证登录态探针，再开始正式 10-run cohort。
5. **封板 G1 生命周期**：Android 补物理设备省电；iOS 补在线 active cancel、在线 transcript 进程恢复、锁屏和实际 background expiration 记录。
6. **清理文档入口**：在 README 链接本文件，并把旧文档中与后续证据冲突的句子改为带日期的历史结论。

## 12. 关键源码与文档索引

| 主题 | 位置 |
|---|---|
| 共享 Pi agent/tool loop | `src/pi/mobile-agent-runtime.ts` |
| Android QuickJS host | `apps/android/app/src/main/java/ai/mobileagent/pi/QuickJsPiAgentRunner.kt` |
| Android session/lifecycle | `apps/android/app/src/main/java/ai/mobileagent/session/AgentSession.kt` |
| Android device adapter | `apps/android/app/src/main/java/ai/mobileagent/runtime/AndroidDeviceRuntime.kt` |
| iOS WKWebView host | `apps/ios/MobileAgent/Runtime/PiCoreFixtureRunner.swift` |
| iOS session/lifecycle | `apps/ios/MobileAgent/Session/AgentSession.swift` |
| iOS public device adapter | `apps/ios/MobileAgent/Runtime/IOSDeviceRuntime.swift` |
| Benchmark schema/规则 | `docs/benchmark-run.schema.json`、`docs/benchmark-v0.md` |
| 运行验证总记录 | `docs/runtime-validation-2026-08-11.md` |
| 目标与 Gate | `docs/goal-v0.md` |
| 平台能力边界 | `docs/capability-matrix.md` |
| 单项结果 | `docs/results/` |

## 13. 文档维护规则

- “源码已实现”“本机构建通过”“模拟器验证”“真机产品基线”“计划中”必须分开表述。
- 新增 runtime 证据时同时记录设备/OS、App 版本、commit 或 artifact hash、model endpoint host/model/cohort、原始 JSONL 和聚合结果。
- 本文件只写当前总览；实验细节写入 dated validation/result 文档，再从这里链接，避免总览成为不可审计的长日志。
