# Goal v0

## 目标

让 Pi Agent 的核心 agent/session/tool loop 直接运行在用户自己的 Android 与 iPhone 上，通过统一的 `device_observe / device_act / device_invoke` 在本机执行真实 App 任务，完成搜索、浏览、滚动、详情读取和结构化信息采集，并用可重复 benchmark 验证结果。

模型推理可以远程；产品运行不能依赖 Mac、PC 或云手机。

## 不可偷换的验收条件

1. **Pi 真实性**：不是“类似 Pi 的手写循环”。应优先运行 `@mariozechner/pi-agent-core`；若移动 JS Runtime 无法承载，必须记录证据并把替代实现标成 Pi-compatible port。
2. **本机闭环**：用户 prompt、session、tool 调度、设备观察、设备动作和结果证据都在手机端闭环。
3. **能力不伪造**：后台、沙箱、跨 App UI 控制和登录持久化必须以实际平台能力与实测结果为准。
4. **三原语稳定**：平台差异放在 capability discovery 和 adapter 内，不扩散到模型工具面。
5. **结果可复现**：完成由观察证据和 benchmark 判定，不采用模型自报成功。

## v0 范围

- 只做只读或低风险任务：搜索、浏览、进入详情、返回、提取。
- Android 与 iOS 使用同一任务表达和结果格式，但允许能力不对称。
- App 首批覆盖美团、小红书、抖音、微信；微信只使用测试账号读取测试会话，不自动发送。
- 高风险动作继续要求用户确认，不纳入 v0 成功率。

## 明确不做

- 不先建设 Task DAG、复杂业务状态机、长期 Memory 或通用 multi-agent scheduler。
- 不把模型推理必须本地化作为 v0 条件。
- 不把开发期 ADB、Xcode、模拟器或桌面 MCP 算作产品运行链路。
- 不承诺 Android 与 iOS 具有相同的跨 App 权限。

## 交付门槛

### G0：基线真实

- 当前仓库所有构建与测试通过。
- README、能力矩阵和实现一致。
- benchmark schema、任务定义和证据要求固定。

### G1：Pi 移动嵌入

- 在 Android 与 iOS 模拟器分别运行一个 `pi-agent-core` fixture。
- fixture 至少完成：prompt → 模型 stub → tool call → native tool bridge → tool result → final。
- session 可持久化、取消，App 切后台时行为有实测记录。

当前 Android 用户任务采用 Service-owned QuickJS，iOS 使用 WKWebView，均不重写现有原生 UI；模型凭据与设备权限不进入 JavaScript。Android 的迁移由 WebView 5 分钟后台不推进的实测触发，QuickJS 已在 16KB emulator 完成同条件闭环。

### G2：平台执行后端

- Android 通用路径：Accessibility + Intent，明确标记目标 App 会占用前台。
- Android 原生后台路径：在具备 OEM Computer Control 能力的设备上验证；无专用权限时不得声称支持。
- iOS 公共路径：App Intent、Shortcut、Universal Link、URL Scheme。
- iOS 研究路径：独立评估 LiveContainer 类侧载容器；与公开分发产品隔离。

### G3：真实 App benchmark

- 每个已支持的 App/平台组合连续运行 10 次。
- 输出成功率、步骤数、耗时、模型调用数、人工接管、崩溃、登录丢失、观察失败和前台干扰。
- 只有达到 [benchmark-v0.md](benchmark-v0.md) 的证据标准才计为成功。

## 当前状态（2026-08-12）

| 项目 | 状态 |
|---|---|
| Android/iOS 手机端 Agent UI 与远程模型调用 | 已实现；默认 Kimi，也可在手机配置 HTTPS Chat Completions endpoint/model，Key 仍只存本机安全存储 |
| 三原语协议 | 已实现 |
| Android Accessibility 前台控制 | 已实现；Clock emulator/dev-only 连续 10/10，尚无真机产品证据 |
| iOS 公开 invoke 能力 | 已实现 |
| 手机 App 内真正的 `pi-agent-core` | 已集成；双模拟器完成模型 stub → 原生工具 → final，用户任务入口已切换到 Pi |
| 双平台 L0 Runtime fixture | Android QuickJS 10/10；iOS WKWebView 10/10，均为 simulator/dev-only |
| Pi session 持久化与取消 | 双端确定性测试通过；Android QuickJS 已测 5 分钟后台、active cancel 与进程恢复；iOS Release 真机已测公开网页覆盖后的在线 session 恢复，仍缺在线 active cancel/transcript 进程恢复、锁屏与长时后台证据 |
| 手机端 benchmark recorder | 双端已实现；Android 在线 L1 已验证；iOS deterministic public L1 与 physical Release public L1 各写入/聚合 10 条有效记录；新增 endpoint/model/cohort 隔离及保守登录态覆盖指标，微信探针待测试账号真机验证 |
| Android 不打扰前台的真实 App 执行 | 未实现 |
| iOS 任意第三方 App GUI 自动化 | 公共 SDK 不支持；研究路径未实现 |
| 真实 App 可重复 benchmark | Android Clock、Chrome C1 真实搜索/阅读/详情/滚动与 iOS open_settings 开发基线各 10/10；iOS public URL physical Release 产品基线 10/10；L2 四个原始目标 App 与 Android 真机产品基线未实现 |

当前验证环境：Android 只连接 16KB emulator，L2 四个目标包未安装；一台 iOS 26.6 真机已使用本地开发证书签名、安装并完成在线 public L1 10/10。个人 Team、证书和设备标识不属于可复现配置，不进入仓库；四个 L2 目标 App/授权测试账号仍未准备。

G1 进度：原版 `pi-agent-core` 已在 Android QuickJS 与 iOS WKWebView 完成 `model_complete → tool_execute → model_complete → final`，双平台 L0 fixture 各 10/10；原生层负责 Key、模型 HTTP、审批和 Mobile Tools。22 项 Node 测试及 12 项 Android JVM 策略测试覆盖 lifecycle、恢复、取消、step limit、参数校验、endpoint 安全校验、benchmark 聚合与保守登录态状态机。Android WebView 的 5 分钟样本只能存活、不能后台推进，用户任务因此迁到 Service-owned QuickJS；16KB emulator 的 attempt 30 已在 Launcher 前台等待 300 秒后自行完成两次在线模型和一次工具调用，active cancel、PID kill 恢复和 forced deep-idle 也通过。iOS deterministic 对照已证明普通后台会挂起，公开 background task 下 20 秒闭环通过、60 秒由 expiration 明确终止；Release 真机 public URL L1 10/10 并验证网页覆盖后的在线 session 恢复。Android 仍缺真机自然 Doze/OEM 省电；iOS 仍缺在线 active cancel/transcript 进程恢复、锁屏与长时后台证据，因此 G1 尚未封板。证据见 [runtime-validation-2026-08-11.md](runtime-validation-2026-08-11.md)、[iOS physical L1](results/ios-l1-physical-release-baseline-2026-08-12.md) 与 [L0 结果](results/mobile-l0-fixture-2026-08-12.md)。
