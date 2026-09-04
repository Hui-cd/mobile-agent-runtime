# Decisions and findings

## 2026-08-12 — C1 分阶段 Structured Output 基线通过 10/10

结论：Kimi 额度恢复后，先用独立 `c1-staged-smoke-v2` 证明前段 `text`、搜索+点击+滚动后 `json_schema` 的真实切换；通过后才开始 `c1-staged-baseline-v2`。固定 APK、endpoint/model、Chrome 版本、Bing 查询、prompt 和网络条件的连续 10 次为 10/10 success，无 crash、tool failure 或人工接管。

依据：attempt 25 smoke 日志明确从 `responseFormat=text` 转为 `responseFormat=json_schema`，43.504s / 5 model / 4 tool 完成，attempts 26–35 同 cohort 连续成功。聚合器报告 p50/p95 43.903s/51.657s，前台干扰 p50/p95 38.421s/44.842s，47 model calls、37 tool calls、0 action/observation failure、0 invalidated success；每条均有 5 items、detail 和 `scrolled=true`。详见 [C1 结果](results/android-c1-chrome-baseline-2026-08-12.md)。

测试控制面：Compose/Gboard 的 ADB 文本注入会回滚旧草稿，`adb shell am --es` 又会在设备 shell 二次拆分空格。因此增加 Debug-only、`android.permission.DUMP` 保护的 Base64 prompt receiver，它仅调用现有 `AgentSession.start`，不建立第二条 loop；Release merged manifest 确认不含 receiver/action。

限制：这是 Android 37 16KB emulator / Debug / Accessibility 前台 backend，所有 run 为 `dev_only=true`、产品门槛 false。Google 在该环境不可达，因此基线显式固定 Bing + cnblogs，不将首次 Google 失败 smoke 删除或混入 baseline。

后续：物理 Android 使用新 product cohort 重跑；M1/X1/D1/W1 安装目标包与授权账号后各自 smoke/10-run，不继承 C1 成功率。

## 2026-08-12 — iOS Release 真机 public L1 基线通过 10/10

结论：当前签名 Release 已在 iPhone 17 Pro Max / iOS 26.6 完成在线 Kimi public URL L1 连续 10/10，机器聚合器 product gate true。HTTP/HTTPS 使用公开 `SFSafariViewController`；`mobileagent://compose?prompt=` 只预填任务，保留用户点击发送的控制权。

依据：固定 cohort `ios-physical-release-baseline-v2` 每次 2 model / 1 tool，p50/p95 12.393s/16.923s，0 action/observation failure、crash、manual takeover；10 条均为 `physical_device`、`dev_only=false`、target `https://example.com`。探索中保留了键盘损坏标记、系统拒绝 Settings/HTTPS、模型跳过工具等失败，没有混入固定 cohort。详见 [iOS physical L1 结果](results/ios-l1-physical-release-baseline-2026-08-12.md)。

限制：证明的是公开 URL、在线 Pi loop、App 内 Safari 覆盖后的恢复与 recorder，不是任意第三方 UI 控制，也未覆盖锁屏、长时后台、在线 cancel/进程恢复。当前 foreground 指标不会统计 App 内 sheet 的遮挡时间，不能据此声称零干扰。

后续：补在线 active cancel/transcript 进程恢复、锁屏/后台预算；为 App 内 sheet 建立可观测的遮挡时间指标；L2 使用目标 App 明确提供的 App Intent/URL/Shortcut，否则公开路线应报告 unsupported。

## 2026-08-12 — iOS 真机签名配置已纠正并解除阻塞

结论：证书显示名中的括号值不是 Apple Development Team ID。使用 Xcode 账号和 provisioning profile 中的真实 Team ID 后，签名、安装和运行阻塞解除。

依据：本机 Xcode Accounts 与 provisioning profile 的 `application-identifier` 使用同一 Team ID，且 profile 覆盖目标设备。将该值传给 `DEVELOPMENT_TEAM` 后，Release 经开发证书签名并由 CoreDevice 正常安装、启动。

限制：命令行 Team 只用于本次验证，未写入工程默认值；使用的是开发签名与 wildcard development profile，不等于 App Store/TestFlight 分发签名。

后续：如需分发，单独配置明确 bundle id 的 distribution profile；本地验证参数只通过环境变量或 Xcode Signing 设置提供，不写入仓库。

## 2026-08-12 — 登录态 0 丢失必须有可见状态覆盖

结论：benchmark 不再仅输出默认 `login_lost=null` 或把 loss 数为 0。Android evidence 从每次工具后的 UI tree 生成 `login_state`，run 保存 before/after；聚合器同时报告 applicable、observed、unknown、not-applicable、persistence-verified 和 losses。W1 只有至少两次高置信 `signed_in` 且未观察到 `signed_out` 才能成功；中途出现过 `signed_out` 后即使最终恢复，loss 也保持为 true，并由逐工具 evidence 可审计。

依据：目标明确要求验证登录态持久性，但此前双端 recorder 始终写 `login_lost=null`，聚合报告的 `login_losses=0` 实际等于“没有测量”，容易被误读。当前微信探针只接受主导航四项“微信 / 通讯录 / 发现 / 我”或登录页“登录 / 注册”的完整组合；单个“我”、缺少登录按钮或其他 App 的相似文字都不推断已登录。

限制：这是可见 UI 的保守状态机，不读取微信私有数据库、token 或账号标识；启动页、弹窗、灰度导航和无障碍节点缺失会得到 `unknown` 而不是猜测。美团、小红书、抖音尚无已验证的稳定正向标志，当前只记录 unknown。由于目标包和测试账号仍不可用，本轮只有 5 项 Android 纯策略测试与 1 项 Node 汇总回归，尚无 W1 真机状态迁移证据。

后续：在授权微信测试账号上先运行独立 login probe cohort，确认两个正向快照与登出注入；再开始 W1 连续 10 次，报告必须同时给出 persistence verified count 与 loss count。

## 2026-08-12 — 手机端模型 transport 不绑定单一供应商

结论：Android/iOS 默认继续使用 `https://api.moonshot.cn/v1` / `kimi-k3`，同时允许用户在手机上配置 HTTPS OpenAI-compatible Chat Completions Base URL、Model ID 和对应 BYOK。agent/session/tool loop、HTTP 与配置均留在手机；Android Key 仍进 Keystore，iOS Key 仍进 Keychain。自定义 endpoint 不发送 Kimi 专属 `reasoning_effort`。双端无调用者的旧手写 `AgentEngine` 已删除，用户任务只保留 Pi core 一条 loop，避免形成可漂移的第二实现。

依据：C1 staged-schema attempt 20 在首轮因 Kimi 账户余额不足返回 429；此前 UI、session、client 类型、错误码与 benchmark `model` 又全部写死 Kimi，使任意可用兼容供应商都无法接替，并会产生错误归因。升级安装实测保留旧 Key，并自动显示默认 `kimi-k3 · api.moonshot.cn`；双端 Debug 构建、Pi fixture、Android Release APK 与 iOS unsigned arm64 Release 均通过。

安全与口径：Base URL 必须是 HTTPS，禁止内嵌 userinfo、query 和 fragment；benchmark 只新增 `model_endpoint_host`，不保存完整 URL/path/Key，并按 endpoint host + model 独立聚合，不能把供应商或模型变化拼成同一连续基线。smoke 与正式连续 run 还必须使用不同 `[COHORT:<id>]`，聚合器把 cohort 纳入分组键；否则一次失败 smoke 会污染随后 10 次基线。Structured Output/tool-call 兼容性仍需每个 endpoint 在线 smoke，不能因协议名称兼容就假定行为一致。

限制：当前只有默认 Kimi 的历史在线证据；没有第二个可用 BYOK，尚未证明其他 endpoint 的 tool calling、严格 schema、限流和错误结构兼容。最新 attempt 22 已真实写入 `api.moonshot.cn / kimi-k3 / c1-staged-smoke-v1` 后仍在首轮返回余额不足 429；Kimi 余额恢复或提供另一可用兼容 Key 后才能继续 C1 在线基线。

后续：对所选 endpoint 先做 1 次 `text → tools → json_schema final` smoke；通过后以 endpoint host + model 为固定条件新跑 10 次，探索样本不得合并。

## 2026-08-12 — 历史 benchmark evidence 读取时规范化，不改写原记录

结论：聚合器接受早期 App 错把 evidence object 二次序列化成 JSON string 的 v1 行，读取时解析为对象并在分组输出 `legacy_evidence_encodings`；无法解析或解析后不是对象的字符串仍直接拒绝。原始 JSONL 不迁移、不删除。

依据：当前 Android 私有目录共有 54 行，直接聚合在首条旧 L1 上报 `evidence[0] must be object`；同一文件中后续 C1 已是对象。修复后旧行可按当前校验继续验证 hash，而兼容次数显式可见。

限制：这是对已知双重编码缺陷的窄兼容，不放宽 v1 schema 的新写入格式；`docs/benchmark-run.schema.json` 仍只允许 evidence object。

后续：导出报告时保留 `legacy_evidence_encodings`，新写入出现非零即视为 recorder 回归。

## 2026-08-12 — benchmark final 使用 Kimi 原生 Structured Output

结论：仅对 `[BENCH:*]` 会话设置 Kimi `response_format`，普通对话保持默认 text。C1 使用 `json_schema + strict=true` 固定 query、恰好 5 个 items、detail 与 scrolled；尚未定义专用 schema 的 benchmark 暂用 `json_object`。手机端 adjudicator 继续做非空、目标 App 与工具证据复核。

依据：Chrome C1 attempts 2/7 的搜索、详情读取与滚动证据均完整，但 Kimi 在合法 JSON 前加入说明文字；JSON Mode 修复语法后，attempt 9 又把 `detail` 改名为 `opened`。Kimi 官方文档明确区分 JSON Mode（不约束字段）与 Structured Output（token 级约束字段名、类型和嵌套），并说明 `kimi-k3` 稳定支持嵌套对象与数组。参考 [Kimi response_format 指南](https://platform.kimi.com/docs/guide/response_format)。

限制：Structured Output 只保证结构，不保证内容真实性或工具执行成功；因此不能删除本地 C1/M1/X1/D1/W1 adjudication。还需在线验证它与 `tool_choice=auto` 的多轮工具调用兼容。

踩坑：Pi 的 OpenAI adapter 会把 user content 传成 `[{"type":"text","text":"..."}]`，不是裸字符串。transport 若只用 `optString/as? String` 查 `[BENCH:*]`，会静默退回 text；attempt 13 的前置说明文字证明前两次所谓 Structured smoke 实际未启用。双端现同时解析字符串与 text-part 数组，并只记录 task ID / response format 类型到日志，不记录 prompt。

阶段约束：Structured Output 不能从第一个 turn 就强制。attempt 18 在 `responseFormat=json_schema` 的首个请求直接生成 5 条貌似完整的数据，`tool_calls=0`，被本地 `C1_TARGET_MISMATCH` 拒绝。transport 现从 assistant tool calls 与按 `tool_call_id` 对应的成功 tool results 重建已完成动作；C1 只有成功的搜索路径（地址栏 input，或带查询参数的 open_url）、click 和 scroll/swipe 三类证据都进入 messages 后，下一次请求才附加 schema。其他 benchmark 也至少等一个成功 tool result 后才启用 JSON final 约束。

在线状态：分阶段实现后的 C1 attempt 20 首轮日志为 `responseFormat=text`，随后 Kimi 返回 HTTP 429（账户余额不足），以 `KIMI_API_ERROR` 在 545ms 落盘，0 tool call；它验证首轮未误挂 schema，但不能验证工具链完成后的切换，也不属于有效基线成功。Android 已把阶段判定抽成纯策略并覆盖“search + click + scroll 缺一不可”的 JVM 单测。

后续：恢复 Kimi 额度后先重跑 C1 在线 smoke，日志必须依次出现前段 text 与最终 json_schema；通过后再开始新的连续 10 次基线，并观察 turn 数、严格 JSON 失败和 provider error。

## 2026-08-12 — WebView 标准滚动失败时降级为真实手势

结论：Android `device_act(scroll)` 先调用 Accessibility `ACTION_SCROLL_FORWARD/BACKWARD`；WebView 节点存在但拒绝该 action 时，在同一工具调用内降级为 `dispatchGesture` swipe，而不是把可恢复的兼容性差异暴露为终态失败。

依据：Chrome C1 attempts 2–4 都能读取搜索结果与博客园详情，但 WebView 对标准 scroll 返回 false；模型随后单独调用 swipe 才成功，造成每次额外 model turn 与 `action_failures`。attempt 4 虽以 71.470s 成功，仍保留 1 个已恢复动作失败。

限制：手势降级只说明系统接受并完成手势，不能保证页面内容一定变化；C1 仍要求工具返回后的详情页至少 8 个可见文本节点并无阻断性网络错误，不能只凭手势完成回调判成功。

后续：在 Chrome C1 连续基线中核对降级后的 action failure、耗时与结果成功率；其他 Canvas/WebView App 仍需逐包验证。

## 2026-08-12 — iOS 进程中断不自动重放设备动作

结论：active App 进程被终止时，pending journal 在下一进程补为 `RUN_INTERRUPTED`；新进程只允许开始新 run，不自动恢复或重放可能已有副作用的设备动作。

依据：R1 attempt 1 在 PID `78919` 首个 model wait 中被 `SIGKILL`。PID `78991` 启动后将原 run `84F9D8F6-ABC2-4678-AF87-FC1A8228CBD9` 补为 failed/crash/process，并以新 run `07F39B56-7CD2-4588-A5E2-1FECA450EBE5` 完成 open_settings；最终 pending 清除，两个 run 各一条终态。

限制：这是 deterministic Debug/simulator，进程在设备动作前被杀；未证明在线 transcript、URLSession cancellation、动作后去重或 iOS 自然内存回收。

后续：真机在线样本分别在模型前、工具后注入中断；工具后仍坚持失败可见且不自动重放，并由用户决定是否重试。

## 2026-08-12 — iOS bridge request Task 必须跟随 WebContent 生命周期

结论：每个 `WKScriptMessageHandlerWithReply` 请求都用 Swift 侧 UUID token 持有 `Task`；WebContent 终止时先取消并清空这些 Task，再失败 active run 和重建 runtime。不能使用 JavaScript request id 作为 Task key，因为新 renderer 会从 `native-1` 重新计数，旧 Task 的 defer 可能误删新 Task。

依据：30 秒 deterministic model delay 中终止 WebContent，第一次虽成功重建 fixture，旧 delay 仍在新 fixture 之后输出 finished。最终 UUID-token 复验中 App PID `80098` 保持，WebContent `80106` 被杀时立即输出 delay cancelled，替换为 `80135` 并完成下一 fixture；等待越过原 10 秒截止点无 stale completion。

限制：这是 simulator deterministic native wait；active 在线 `URLSession` 仍需 BYOK 实测。取消本地 Task 也不能保证远端供应商停止已经接收的推理或计费。

后续：在线首个 model request 中再次终止 WebContent，核对 URLSession cancellation、唯一 recorder 终态和下一任务恢复。

## 2026-08-12 — iOS public invoke 基线必须记录前台干扰

结论：`open_settings` 属于 iOS 公共能力，但会把 Settings 带到前台；Pi 可在 Mobile Agent 的有限 background task 内完成收尾，不得因此把整个 backend 标成“不打扰前台”。deterministic fixture 的 model 名必须写成 `deterministic-fixture`，不能冒充 Kimi。

依据：iOS 26.5 simulator 连续 10 个独立 PID 均完成 Pi → `IOSDeviceRuntime.invoke(open_settings)` → system accepted → state=background → second model → final。手机端 JSONL 为 10/10 success，前台干扰 median 987ms / p95 1025ms，0 failure/crash；聚合器正确输出 `product_eligible=0` 和 `meets_v0_product_gate=false`。详见 [结果报告](results/ios-l1-open-settings-deterministic-2026-08-12.md)。

限制：fake model、Debug、Simulator 只能证明 public adapter、carrier 与 recorder；不能替代 Release BYOK、真机预算或 App Intent 的目标业务结果。系统 accepted 也不代表已读取 Settings 内部状态。

后续：用真机 Release + BYOK 重跑 L1；对真正可后台完成且不切前台的目标 App Intent 单独建立 `foreground_interrupt_ms=0` 组合。

## 2026-08-12 — L0 重复性必须逐平台实跑 10 次

结论：单次 fixture pass 不能替代 v0 的 L0 10/10 门槛。Android 每次创建并关闭独立 QuickJS；iOS 每次终止并冷启动独立 App 进程，只有 native adapter 与 final 都出现才计数。

依据：Android 16KB/API 37 emulator 在 1.8 秒内完成 10 次，每次 16 events、QuickJS 2026-06-04、0 failure。iOS 26.5 simulator 的 10 个独立 PID 均调用真实 `IOSDeviceRuntime.observe` 并完成 fixture，0 failure。详细 PID、命令和限制见 [results/mobile-l0-fixture-2026-08-12.md](results/mobile-l0-fixture-2026-08-12.md)。

限制：两组都是 Debug/simulator/dev-only，fake model 只验证 carrier、Pi loop 与 native bridge；不证明远程模型、真机、后台预算或主流 App。

后续：Release 在线 smoke、真机 L1/L2 和各 backend 的 10 次基线必须单独执行，不能继承 L0 成功率。

## 2026-08-12 — iOS Release 必须挂载 Pi runtime host

结论：承载 Pi bundle 的隐藏 `PiCoreFixtureHost` 在 Debug 和 Release 都必须挂载；只有 deterministic fixture 启动、测试延迟、诊断日志和状态 pill 保持 Debug-only。

依据：静态 Release 收口发现 host 原本整体位于 `#if DEBUG` 内。Release 虽能成功编译，但没有任何地方创建 WKWebView 或调用 `PiCoreFixtureRunner.start`，用户任务会等待约 5 秒后以 `PI_RUNTIME_NOT_READY` 失败。移除 host 外层条件编译后，Release 会加载 bundle 并把 runtime 标为 ready，但不会运行 fixture 或包含测试开关。

限制：这条结论记录的是当时的 simulator 阶段；随后已使用正确的本地 Team 配置完成 Release 真机签名、安装与在线 public L1，见文首新结论。

后续：配置 BYOK 后把 Release 配置的在线 smoke 加入发布门禁，避免仅靠 Debug fixture 掩盖初始化差异。

## 2026-08-12 — WKWebView 注入异步入口必须丢弃 Promise 返回值

结论：Swift 通过 `evaluateJavaScript` 启动 `window.PiMobileRuntime.run(...)` 时使用 `void`，不让异步函数返回的 JavaScript Promise 进入 Swift completion handler。fixture 与真实用户任务入口均采用同一写法。

依据：Pi 已经发出 `runtime_event` 并进入原生 `model_complete`，但旧调用仍收到 `JavaScript execution returned a result of an unsupported type`，把正常运行的 fixture 误标为失败。`void window.PiMobileRuntime.run(...)` 后错误消失，完整闭环可继续完成。

限制：丢弃 Promise 只修复 WKWebView 对返回值桥接的误判；Agent 的实际成功或失败仍必须通过现有 `agent_complete` native bridge 回传，不能靠 `evaluateJavaScript` completion 判断。

后续：保留 native method/failure 日志与 fixture 回归，避免将 fire-and-report-later 的 JavaScript API 再次当成可直接跨桥返回的 Promise。

## 2026-08-12 — iOS WKWebView 只在系统有限后台预算内续跑

结论：普通后台会暂停 WKWebView carrier；真实用户任务使用的 `beginBackgroundTask` 能让 Pi 在系统给定的有限窗口内继续，但 expiration 时必须取消并明确失败，不能声明长时或常驻后台。

依据：iOS 26.5 模拟器的 Debug deterministic delay 中，无 background task 的 60 秒样本退到 Home 后没有进展，回前台时从原位置完成。启用与 `AgentSession` 同机制的 background task 后，20 秒样本在 `UIApplicationState=background` 完成 `model → tool → model → agent_complete`；60 秒样本从申请后台任务起约 44 秒收到 expiration，并以 `DEBUG_BACKGROUND_TASK_EXPIRED` 失败。两个开关只存在于 Debug 编译。

限制：这是模拟器 deterministic carrier 证据，不是在线 Kimi、真机、锁屏或固定时长 SLA；系统预算会随设备、负载和策略变化。它证明 Pi/WKWebView 可在获批窗口内推进，也证明超出窗口不可依赖。

后续：配置 iOS 测试 BYOK 后复验真实模型请求、active cancel/WebContent 回收与真机后台时限；长网络传输改用 background URLSession，可延期工作按场景评估 BGTaskScheduler。

## 2026-08-12 — Android 用户任务从 WebView 迁到 Service-owned QuickJS

结论：Android 用户任务的原版 `pi-agent-core` bundle 由 `quickjs-kt@1.0.12` 承载，不再依赖 Activity/WebView renderer；Foreground Service 负责进程存活，Kotlin async binding 保留 Key、HTTP、审批和 Mobile Tools。WebView 暂留 fixture/回退对照，iOS 仍使用 WKWebView。

依据：WebView attempt 27 在 Launcher 前台等待 300 秒后，原生 HTTP 已返回 200，但 JS 约 53 秒不产生工具调用，直到 Activity 恢复才继续。QuickJS 在同一 16KB ARM64 emulator、Activity 未启动时完成真实 Pi 16-event fixture；attempt 30 在 Launcher 前台等待 300 秒后，无需恢复 Mobile Agent 即完成 model → tool → model，309.534 秒成功。选型优先匹配现有任意 JavaScript bundle、suspend binding、取消和内存限制；Zipline 更成熟但偏 Kotlin/JS module/typed service，Hermes 会引入 React Native/JSI 形态的额外集成面。

限制：`quickjs-kt` 是较年轻的第三方 binding，并引入约 0.9 MB/ABI 的 native library；当前只有 Android 37 emulator 的 16KB load/alignment、单次 5 分钟、active cancel/process kill 与 forced deep-idle 证据，尚无真机自然 Doze/OEM 省电、内存/电量结果。工具打开 App 仍会占用前台，runtime carrier 迁移不等于 OEM Computer Control。

后续：固定依赖版本，补真机自然 Doze/OEM 省电与内存指标；通过后再移除 Android WebView 回退。参考 [QuickJS](https://bellard.org/quickjs/)、[quickjs-kt](https://github.com/dokar3/quickjs-kt)、[Zipline](https://github.com/cashapp/zipline) 与 [Hermes](https://github.com/facebook/hermes)。

## 2026-08-12 — QuickJS 长任务用 coroutine watchdog，不用实例 evaluation timeout

结论：bundle 初始化保留 60 秒 `evaluationTimeoutMillis`；进入在线 Pi run 前将其关闭，改用 15 分钟 coroutine `withTimeout`。用户取消同时断开原生 HTTP、取消 native job 并调用 QuickJS interrupt。

依据：attempt 29 的 300 秒 native model delay 到点后，HTTP 3.874 秒返回 200，但 QuickJS 随即以 interrupted 失败；实例 60 秒 timeout 实际跨 native async await 累积墙钟。修复后的 attempt 30 跨过同一 300 秒 await 并完整结束。

限制：15 分钟是当前 v0 防失控上限，不是平台后台承诺；native async wait 中的 UI cancel 已通过，busy-loop interrupt 仍需 Android 集成回归。单次 model transport 另有 connect/read timeout，Pi 另有 20-turn 上限。

后续：增加 instrumentation coverage，分别验证 busy JS、HTTP 阻塞和总任务 timeout 的 failure code 与唯一 recorder 终态。

## 2026-08-11 — benchmark run 使用原子 pending journal

结论：Android/iOS 在 run 开始先原子写 `pending-run.json`；正常终态先追加 JSONL 再按 run_id 清除 pending。新进程发现未完成 pending 时，补写 `failed + crash=true + RUN_INTERRUPTED`，已存在同 run_id 终态则只清 pending，避免重复。

依据：一次 Activity owner 卡死随后通过 App 更新终止时，attempt 已递增但 JSONL 没有该 run，证明仅终态写入会系统性美化稳定性。实现后对 attempt 16 注入 `SIGKILL`，新进程自动补行；attempt 17 的 provider overload 正常失败只写一行且 pending 清除。

限制：pending 保存启动快照，因此异常终止前的 model/tool/turn 计数可能为 0；记录 notes 明示不完整。`crash=true` 是对未达 recorder 终态的保守归类，可能包含 OS kill 或开发期更新，不等同已证明的代码崩溃原因。

后续：在每次 model/tool 事件后节流刷新 journal，并增加 `termination_kind` 区分 crash、OS kill、update 与 watchdog。

## 2026-08-11 — Pi WebView 必须绑定当前 Activity owner

结论：Android 主入口使用 `singleTask`，Pi runner 同时记录隐藏 WebView 的 Activity owner。新 Activity 接管时，旧 active run 明确失败、旧 WebView 被移除并销毁，再在当前 Activity 建立并验证新 runtime。

依据：App 更新和多次开发启动后，新 Activity UI 可见，但单例 runner 因全局状态仍是 passed 而复用旧 Activity 的 WebView；在线任务停在第一个 `runtime_event`，既不完成也不落失败记录。owner 检查后不能再把旧 WebView 的 fixture 状态当成当前 UI 可用性。

限制：Activity 迁移中的 active run 不自动续跑，以避免重复设备动作；它会记录失败。普通前后台切换不创建 owner，因此不会触发迁移。

后续：增加 Activity recreate 注入测试，确认失败记录、foreground service 停止和下一任务重建三项同时成立。

## 2026-08-11 — Android open_app 在原生层解析包名别名

结论：`open_app` 不要求模型准确猜包名；Android adapter 按“精确已安装包名 → 受控常见别名 → Launcher label”解析，再使用系统 LaunchIntentSender。Clock、美团、小红书、抖音、微信保留最小别名表。

依据：Clock 10 次开发基线有 9 次已恢复 action failure，主要因为模型使用 AOSP `com.android.deskclock`，设备实际安装 Google `com.google.android.deskclock`。修复后定向要求错误包名的 run 以 1 tool call、0 action failure 打开正确 App。

限制：别名表不是完整 App registry；地区版、分身、多渠道包和同名 Launcher 仍可能歧义。解析只选择已安装候选，不绕过包可见性或设备策略。

后续：真机 L2 先做 capability discovery 并记录候选包/版本；遇到多个候选时让用户选择，不由模型静默决定。

## 2026-08-11 — benchmark 由手机端记录并本地初判

结论：Android/iOS 每次任务都在 App 私有目录追加统一 JSONL；记录 Pi turn、model/tool call、耗时、授权交互、前台干扰、失败计数、目标版本与工具结果/截图 SHA-256。`[BENCH:*]` 使用干净 session，只有本地最低结构/数量判定通过才是 success；App 内可经系统分享面板导出。

依据：Android 在线 L1 实测生成目标包、Clock 版本、3 turns/3 model calls/2 tool calls 与对象 evidence，并正确保留一次已恢复的 action failure。聚合器对 schema、evidence 和计数字段做校验，按组合输出成功率与 p95。

限制：前台干扰是从工具观察到外部包到 agent completion 的保守估算，不是像素级系统 trace；hash 能证明 evidence 未变，但单独不能让评审者看到内容。完整截图/UI tree 仍需受控证据包方案。

后续：真机 benchmark 同时采集系统级前台时间与受控原始 evidence；对敏感 result 加密、保留期和删除策略。

## 2026-08-11 — Android WebView reply 不依赖 View attach 状态

结论：收到 `WebMessageListener` 请求后始终使用独立 reply proxy 回包，不再以 hidden WebView 的 `isAttachedToWindow` 为条件。

依据：Android 37 整机冷启动时原生收到第一个 `runtime_event`，但 View 短暂未 attached，旧逻辑丢弃 reply，JS Promise 永久等待；移除条件后第二次整机重启 fixture 完成。

限制：此次修复只解决已收到请求却丢 reply 的竞态；Activity owner 迁移和 renderer gone 由独立生命周期策略处理。

后续：补 Activity recreate 的可重复注入测试；renderer gone 与普通进程 kill 已分别验证。

## 2026-08-11 — WebView renderer 丢失必须终止 active run 并重建 runtime

结论：Android `onRenderProcessGone` 不再只改变状态；若 task 正在运行，会以 `runtime:webview_renderer` 结束对应 deferred，交给 session 记录失败，然后移除死亡 WebView 并重建干净 Pi runtime。不会自动重放可能已经执行过设备动作的 task。

依据：旧实现只把 fixture 标为 failed，`AgentSession` 仍永久等待永远不会返回的 JS Promise。Debug-only 注入口实测：idle renderer 死亡后 fixture 自动重建；active attempts 18/21 均以 `WEBVIEW_RENDERER_GONE`、`runtime:webview_renderer` 和 `crash=true` 落盘；attempt 19 随后成功，证明下一任务可用。自动重试可能重复点击、发送或其他副作用，因此失败可见而不重放是更安全的恢复语义。

限制：证据来自 Android 37 模拟器与 Debug 故障注入，不是系统自然回收或物理设备证据。注入 receiver 受 `android.permission.DUMP` 保护，且 Release 合并清单已确认不存在该 receiver/action。

后续：在物理设备与长时后台压力下验证系统自然回收；保持“中断任务失败、下一任务重建、不自动重放”的判定标准。

## 2026-08-11 — native model request 生命周期必须跟随 active run

结论：仅取消 agent coroutine/Swift `Task` 不足以假定底层网络 I/O 已停止。Android active run 同时持有 model job 与当前 `HttpURLConnection`；stop、Activity owner 迁移和 renderer gone 会先 `disconnect()` 再取消 job。iOS active run 持有原生 model `Task`，stop 与 WebContent termination 都显式取消。

依据：Android attempt 20 中 run 已因 renderer gone 失败，但阻塞连接仍在 4,684 ms 后记录 HTTP 200。增加显式连接跟踪后，attempt 21 在模型开始后 1,060 ms 记录 cancelled，未再出现完成态 200，同时 JSONL 仍正确记录 renderer crash。

限制：Android 已完成在线故障注入；iOS 只通过 Swift 6 typecheck/build 和 idle WebContent 回收，因测试模拟器无 API Key，active 在线取消尚未运行验证。HTTP disconnect/cancel 也不等于远端供应商一定停止已经接收的推理计费。

后续：配置 iOS 测试 BYOK 后，在首个 model request 中终止 WebContent，核对 run 终态、URLSession cancellation 与下一任务恢复。

## 2026-08-11 — 锁屏是 Android 前台 GUI backend 的能力边界

结论：`android_accessibility_foreground` 不能在熄屏/锁屏时伪装成可用。`observe` 必须返回 `screen_interactive`、`device_locked` 与相应能力错误；`act/invoke` 必须在系统拒绝可见 GUI 前快速返回 `SCREEN_NOT_INTERACTIVE` 或 `DEVICE_LOCKED`。Pi 收到后不重试设备工具，benchmark 保留底层 failure code/stage。模型推理与 recorder 可以继续，但不能把它称为锁屏 GUI 成功。

依据：attempt 22 熄屏后仍运行 39.476s，旧 adapter 的 6 个“成功”工具证据均无 `current_app`，最终失败且浪费 7 次模型调用。修复后的 attempt 23 以 8.142s、2 model/1 tool 明确失败；attempt 24 解锁后以 11.634s、1 tool 成功，证明失败不会污染下一任务。

限制：这是 Android 37 模拟器的通用 Accessibility + Intent 路径。物理设备的 Always-On Display、无安全锁配置和 OEM 行为可能不同；Computer Control 安全虚拟显示必须按独立 backend 重新验证，不能继承此结论或反向外推。

后续：真机分别测试熄屏、已点亮 keyguard、解锁三态；后台产品若要在锁屏下执行真实 App，只能以 OEM Computer Control 等被授权能力取得独立证据。

## 2026-08-11 — 长时后台验证以 elapsed time 为准，不以 tool count 代替

结论：要求模型重复调用工具不能构成长时生命周期测试。Pi 允许一个 assistant turn 产生多个 tool call，模型可能批量发出并在数百毫秒内完成；长时后台必须预先定义最小持续时间，并使用可控延迟/时钟、系统调度或真实长任务取得 wall-clock 证据。

依据：attempt 25 要求打开 Clock 后独立 observe 12 次，最终 13 tools 全成功，但 12 个 observe 来自同一个 model turn；总 run 20.902s、目标 App 前台 14.195s，远低于长时门槛。

限制：该结果证明的是当前模型的 batching 行为，不说明 Pi 每次都会并行或批量发工具，也不否定 20 秒级后台闭环。人为 sleep 若不经过真实生命周期仍只能算 harness 证据。

后续：在 Debug/instrumentation lane 增加可审计的 native model/tool delay gate，并分别验证 5 分钟后台、Doze、锁屏和恢复；Release 不包含触发入口。

## 2026-08-11 — 用系统 WebView 嵌入原版 Pi core，原生层保留安全边界

状态：Android 用户任务 carrier 已由 2026-08-12 的 QuickJS 决策取代；本节仍适用于 iOS WKWebView 与 Android 历史 fixture/故障证据。

结论：Android 使用 WebView，iOS 使用 WKWebView 运行同一份 `@mariozechner/pi-agent-core` bundle。Pi 拥有 transcript、模型/工具循环、事件、参数校验、step limit 和取消；Kotlin/Swift 拥有 API Key、模型 HTTP、用户审批与设备权限。

依据：Android 37 与 iOS 26.5 模拟器均完成 `prompt → native model stub → tool call → native tool → tool result → native model stub → final`，消息角色为 `user/assistant/toolResult/assistant`，最终事件为 `agent_end`。iOS fixture 的 `device_observe` 已从简化 stub 切到真实 `IOSDeviceRuntime.observe` 并校验公共能力矩阵。用户任务入口已从两套手写循环切到此路径，Pi transcript 分别持久化到 SharedPreferences/UserDefaults。

限制：Android 已有在线 Kimi Clock smoke、renderer active failure 与底层连接取消证据；iOS 已补 Release 真机 public URL 10/10 与网页覆盖后的 session 恢复，但 active 在线取消、在线进程恢复、锁屏和长时后台仍未验证；Android 也仍缺真机。

后续：Android 已因 5 分钟失败证据迁到 QuickJS；本路径继续用于 iOS 在线 L1、active WebContent 回收与 iOS 长时后台/真机测试。

## 2026-08-11 — Android Pi 在线 smoke 与 force-stop 边界

结论：Android 新 Pi 路径已用在线 Kimi 完成 Clock L1：两次模型 HTTP 200、一次原生 `open_app`、Clock 占前台时 Pi 在后台完成 final。Pi transcript 在 `force-stop` 后仍可恢复，但 Android 37 模拟器会清除侧载 App 的无障碍 enabled 状态，必须重新经过系统授权。

依据：两次模型耗时 4143/4043 ms，任务结束时 Pi 有 4 条 messages；重启并重新授权后日志为 `restored=true`，6-message 追问在 4260 ms 返回 `Clock`。`dumpsys accessibility` 在 force-stop 后显示 enabled/bound services 为空。

限制：`force-stop` 是 Android 的强制停止语义，不等同普通低内存 kill；短时后台 4 秒也不能外推成长时稳定性。

后续：把 swipe-away、低内存 kill、系统重启、App 更新、renderer 回收分别列为 benchmark 场景；权限丢失与 Pi session 丢失分开统计。

## 2026-08-11 — WebView bridge 只服务可信本地页面

结论：JavaScript bundle 只注入 App 自己创建的空白本地页面；Android `WebViewCompat.addWebMessageListener` 限制到 `https://mobile-agent.local`，iOS 校验 main frame 与 security origin。关闭文件/内容访问和弹窗，不允许导航到外部内容。

依据：原生桥可以调用模型、无障碍与系统能力，权限面高于普通网页；一旦把桥暴露给不可信页面，网页脚本就可能越权调用设备工具。

限制：自定义 origin 是逻辑信任边界，不是网络站点；以后若加载远程内容、允许子 frame 或打开 DevTools，必须重新威胁建模。

后续：加入 bridge origin rejection 与 malformed message 的原生自动化测试；Release 禁止调试接口。

## 2026-08-11 — 区分“手机端 Agent”与“Pi Agent 在手机端”

结论：当前 Kotlin/Swift `AgentEngine` 是独立实现的 OpenAI-compatible tool-call loop，不是 `@mariozechner/pi-agent-core`。在完成移动 JS Runtime fixture 之前，不再把它描述为 Pi 已运行在手机上。

依据：审计时仓库没有 Pi dependency；两端分别维护消息、tool-call、step limit 和 tool result 循环。Pi agent-core 支持 SDK embedding、自定义 stream function 和 tool event loop，但 package metadata 仍以 Node 20+ 为运行环境。

限制：Pi core bundle 没有直接 Node import，`pi-ai` 也包含 browser/Vite 兼容路径，因此 React Native/Hermes 可能可行，尚需真机/模拟器编译运行证明。

后续：先做 prompt → fake model → native tool bridge → final 的双端 fixture，再决定 brownfield 嵌入或明确标注的 Pi-compatible source port。

## 2026-08-11 — 移动构建替换 Pi provider transport，不替换 Pi agent-core

结论：保留原版 `@mariozechner/pi-agent-core` 的 session、event 和 tool loop；移动 bundle 用小型 `pi-ai` shim 提供 EventStream 与 TypeBox 参数校验，并强制注入 native-backed `streamFn`。

依据：直接 browser bundle 会遍历 Pi 的全部 lazy provider module，并因 Mistral SDK 的可选 `@opentelemetry/api` 依赖失败。移动端只需要一个用户选定的远程模型 transport，不需要把所有 Node/provider SDK 打进 App。

限制：shim 是需要随 Pi 升级做兼容测试的边界；它不能被当作完整 `pi-ai`，也不能回退到默认 `streamSimple`。

后续：每次 Pi 升级运行 L0 lifecycle、tool validation、error、cancel 和 browser/Hermes bundle 测试。

## 2026-08-11 — 后台 App 执行按平台与分发路径拆分

结论：Android 优先研究 OEM Computer Control；普通安装继续以 Accessibility 前台控制作为基线。iOS 公共产品只使用 App Intents/Shortcuts/URL；LiveContainer 类能力作为独立侧载研究 target，不与公开产品能力混写。

依据：Android Computer Control 官方框架支持安全后台虚拟显示、截图和输入注入，但仅限 OEM 预装 assistant、privileged permission、allowlist 和用户同意。Apple 公共 SDK 没有 iPhone 上任意第三方 App 的虚拟化或 GUI 控制；LiveContainer 是 AGPL 侧载 launcher，并带来凭据访问、兼容性与分发风险。

限制：没有 OEM 准入就不能交付 Android 通用后台控制；没有侧载/私有能力就不能交付 iOS 任意第三方 App GUI 自动化。

后续：分别产出公开合规能力矩阵和 research capability matrix；所有 benchmark 按 backend 独立统计。

## 2026-08-06 — 工具面固定为三个原语

结论：Agent 只看到 `observe / act / invoke`，能力发现合并进 `observe` 响应，等待由 `act / invoke` 的 Runtime 后处理承担。

依据：截图、UI Tree 和前台 App 都是同一种“观察”；点击、输入、滑动是同一种“界面动作”。减少工具数量可降低模型选错工具的概率。

限制：工具少不等于参数可以模糊。每个原语仍使用严格结构化 Schema，并返回可纠正的错误码。

后续：保持模型工具面稳定，新增平台能力优先扩展参数和 capability matrix。

## 2026-08-06 — 不向模型暴露任意 ADB Shell

结论：ADB 是 Android Adapter 的内部实现；模型使用结构化 `device.invoke` 白名单。

依据：任意 Shell 会导致命令注入、不可审计行为和 Android 绑定；结构化 Intent/系统能力仍能覆盖大多数高效率路径。

限制：开发调试仍可能需要 Shell，但它不属于 Agent 公共协议。

后续：如果增加开发者调试口，使用独立进程/权限边界，不注册为模型工具。

## 2026-08-06 — Android 37 前台 Activity 数据源

结论：优先解析 `dumpsys activity activities` 的 `topResumedActivity`，同时兼容 `mResumedActivity / mCurrentFocus / mFocusedApp`。

依据：本机 Pixel 9、Android 37 模拟器实测，`dumpsys window windows` 没有前台焦点字段，而 `topResumedActivity` 可稳定返回 `package/activity`。

限制：`dumpsys` 文本格式不是稳定公共 API，厂商 ROM 仍可能不同。

后续：加入不同 Android 版本和厂商样本；companion app 可提供更稳定的前台包名来源。

## 2026-08-06 — Runtime 与 Agent Gateway 解耦

结论：Runtime 不依赖模型；Codex 走 MCP，BYOK 模型走 Provider Adapter。

依据：OpenAI Responses 与 OpenAI-compatible Chat 的工具循环格式不同，设备协议不应承担这些差异。官方工具调用流程也要求应用侧执行工具并把结果送回模型。

限制：BYOK Gateway 首版只传 UI Tree，不传截图；兼容 API 的工具调用实现质量由具体 Provider 决定。

后续：加入多模态结果标准化和更多 Provider Adapter，不修改三个设备工具。

## 2026-08-06 — API Key 和设备动作安全边界

结论：Key 仅通过 Gateway 环境变量/部署密钥注入；HTTP 任务默认只读，写设备必须设置 `allow_device_actions=true`。

依据：移动设备可能包含消息、文件、账号和支付能力，模型调用不能默认继承广泛写权限。

限制：显式布尔授权不是最终策略系统，尚未区分普通导航与发送、购买等高风险动作。

后续：加入分级策略、用户确认、调用审计和可撤销操作设计。

## 2026-08-06 — ADB 设备端 Shell 参数必须二次引用

结论：Android Adapter 把 `adb shell` 后的每一个逻辑参数进行 POSIX 单引号引用，再组装成唯一的设备端命令字符串。

依据：主机侧使用 `spawn(adb, argv)` 只能避免主机 Shell 注入；ADB 仍会把 `shell` 后的内容交给设备端 Shell 解析。Intent extras、URL 和输入文本都可能包含设备端元字符。

限制：这层保护针对当前只需要字面参数的命令；未来如果确实需要管道或重定向，不能绕过它拼接字符串。

后续：所有新 Android 能力继续传逻辑参数数组，并加入含引号、美元符号和命令替换字符的回归测试。

## 2026-08-07 — 产品主入口改为手机内 Agent App

结论：Android App 承担用户交互、模型工具循环和设备 Runtime；桌面 MCP/ADB Runtime 保留为开发、调试和 Codex 接入路径。

依据：目标交互是用户直接在手机里发任务，并允许网络推理在后台继续。只提供电脑命令行无法满足这一产品形态。

限制：GUI 自动化必然把目标 App 切到前台；只有推理、数据整理和等待确认能够不占用当前屏幕。

后续：增加任务历史、结构化比较结果、语音入口和更细粒度的后台/前台调度。

## 2026-08-07 — Kimi 中国区 BYOK 与本地 Key 存储

结论：内测 App 默认直连 `https://api.moonshot.cn/v1` 的 `kimi-k3`，使用 OpenAI-compatible tool calling；API Key 用 Android Keystore AES/GCM 加密保存在设备本地，不编入源码或 APK。

依据：同一测试 Key 在国际区 `.ai` 返回 401，在中国区 `.cn` 验证成功；真实请求能够生成符合 Schema 的 `device_invoke` 工具调用。

限制：当前 Provider 和模型写在 App 配置中，还没有可视化 Provider 切换；设备被 root 后不承诺密钥绝对安全。

后续：把 Provider、Base URL 和模型做成高级设置，并增加 Key 删除/轮换入口。

## 2026-08-07 — Android 包可见性与打开 App

结论：Android 33+ 使用 `PackageManager.getLaunchIntentSenderForPackage` 打开目标 App；Android 30–32 使用带 Launcher `queries` 的 `getLaunchIntentForPackage` 兼容路径。

依据：Android 37 实测中，仅使用 `getLaunchIntentForPackage` 被 package visibility 过滤，Kimi 虽正确调用 `open_app`，系统时钟仍未启动；改用 LaunchIntentSender 后由 `ai.mobileagent` 成功启动 `com.google.android.deskclock`。

限制：目标包不存在、没有可启动 Activity 或被设备策略禁用时仍会失败。

后续：增加常见 App 的名称到包名解析和候选选择 UI，避免模型猜错包名。

## 2026-08-07 — 首次模型请求直接携带 Context

结论：不设置独立 Planner 阶段。每次用户请求前先观察设备，把当前 App、Activity、UI Tree、能力状态、切入 Agent 前最后一个外部 App 上下文，以及最近 12 条用户/Agent 对话一起放入模型 messages；模型直接回复或产生 tool call。

依据：模型决策应基于请求时已经存在的上下文，不能先用一个无设备状态的“规划”请求再补观察。工具结果继续按标准 assistant tool_calls → tool message 顺序追加。

限制：状态和错误消息不进入长期对话上下文；初始请求默认只附 UI Tree，截图由模型在语义节点不足时显式请求。

后续：为长会话加入 token-aware 截断/摘要，并把最后一个外部 App 上下文持久化到进程重启之后。

## 2026-08-07 — iOS 保持三原语，但不伪造全局 UI 权限

结论：iOS Agent 与 Android 保持相同的 `observe / act / invoke` 工具面。iOS `observe` 返回设备与能力上下文，`act` 对跨 App GUI 操作返回明确的 unsupported；`invoke` 映射到 Universal Link、URL Scheme、App Intents、Shortcuts、地图、拨号和分享面板。

依据：稳定协议有利于模型和 Gateway 跨平台复用，但 Apple 的公开 SDK 没有第三方 App 全局读取/点击其他 App UI 的等价能力。App Intents 是 Apple 提供给 Siri、Spotlight 和 Shortcuts 的正式动作接口。

限制：目标 App 未公开 Intent、Shortcut Action、Universal Link 或 URL Scheme 时，iOS Runtime 无法自动完成其内部界面操作，也不能验证外部 App 内部结果。

后续：为高频目标 App 建 capability registry；优先发现 App Intents，其次 Universal Link/URL Scheme，最后明确请求用户手动接管。

## 2026-08-07 — iOS 后台只使用系统允许的有限续跑

结论：用户前台发起的模型请求使用 `beginBackgroundTask` 获取有限收尾时间，结束或超时时立即释放，并用本地通知报告结果；不声称常驻后台。

依据：iOS 会暂停后台 App，`beginBackgroundTask` 只适合完成已开始的关键工作，不是常驻服务机制。

限制：模型响应或跨 App 流程超过系统给定时间时会被取消；URL 打开通常也需要前台交互。

后续：对长上传下载使用 background URLSession；可延期任务再评估 BGTaskScheduler。

## 2026-08-07 — iOS 权限按任务时机申请

结论：通知权限不在 App 首次打开时申请，而是在用户第一次真正发起 Agent 任务后申请。

依据：通知只用于任务转入后台后的完成或失败提醒；首页启动时弹权限既缺少上下文，也干扰 API Key 配置和产品理解。

限制：用户拒绝通知后，后台任务仍可运行，但完成结果只能在再次打开 App 后查看。

后续：增加设置页中的通知状态与跳转入口，并在任务预计需要后台续跑时解释申请理由。

## 2026-08-07 — Xcode 26 新装 Simulator Runtime 的注册

结论：`xcodebuild -downloadPlatform iOS` 完成后，如果 `simctl` 仍报告 runtime profile 不存在，运行 `xcrun simctl runtime scan-and-mount` 刷新注册。

依据：本机 iOS 26.5 Runtime 下载完成且磁盘镜像状态为 Ready，但 CoreSimulator 的 Runtimes 列表为空；扫描挂载后立即可创建、启动 iPhone 模拟器。

限制：该处理针对 Xcode 26 的本机组件状态；其他 Xcode/macOS 版本的错误原因可能不同。

后续：将 Simulator Runtime 检查加入本地开发环境诊断脚本。

## 2026-08-07 — iOS Simulator 构建保留临时签名

结论：Simulator 构建不设置 `CODE_SIGNING_ALLOWED=NO`，使用 Xcode 默认的临时签名；真机仍由开发者选择 Development Team。

依据：完全禁用签名的 App 虽能安装启动，但访问 Keychain 时返回 `errSecMissingEntitlement (-34018)`；恢复 Simulator 默认签名后才能验证 BYOK 存储。

限制：Simulator 临时签名不能用于真机分发或 TestFlight。

后续：加入 Keychain 保存/读取的 UI 自动化冒烟测试，避免构建参数回归。
