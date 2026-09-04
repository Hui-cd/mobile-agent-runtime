# Pi mobile runtime validation — 2026-08-11

## 已验证

| 层级 | 环境 | 结果 | 证据 |
|---|---|---|---|
| TypeScript / Pi core | Node 22 | 22/22 tests | lifecycle、native model/tool loop、恢复、取消、step limit、非法工具参数拦截、benchmark 聚合、provider/cohort 分组与登录态覆盖指标 |
| browser bundle | esbuild browser target | pass | 原版 `pi-agent-core` 产生 `user → assistant → toolResult → assistant`，最终 `agent_end` |
| Android embed | Pixel 9 / Android 37 emulator | pass | WebView 经 origin-restricted AndroidX bridge 完成两次 model stub 与一次 native tool；UI 显示“Pi core 已验证” |
| iOS embed | iPhone 17 Pro / iOS 26.5 simulator | pass | WKWebView 经 `WKScriptMessageHandlerWithReply` 完成同一闭环；fixture 的只读工具调用真实进入 `IOSDeviceRuntime.observe` 并校验 iOS capability matrix |
| Android online L1 smoke | Pixel 9 / Android 37 emulator + Kimi | pass | 见下方在线记录；真实 Pi 路径打开 Clock、观察页面并完成 final |
| Android transcript recovery | force-stop 后重启 | conditional pass | Pi messages 恢复成功；无障碍授权被系统清除，重新授权后才能发任务 |
| Android system reboot | Android 37 emulator | pass after fix | Key、Accessibility enabled/bound、benchmark JSONL 与 Pi fixture 均恢复 |
| Android renderer recovery | Android 37 emulator / Debug injection | pass | idle renderer 自动重建；active attempts 18/21 以 `runtime:webview_renderer` 落盘，attempt 19 证明下一任务可用；底层模型连接在 1,060 ms 内主动取消 |
| Android screen-off boundary | Android 37 emulator + Kimi | expected failure after fix | attempt 23 在首个 model request 后熄屏，以 `SCREEN_NOT_INTERACTIVE` / `tool:device_invoke`、1 tool call 结束；attempt 24 解锁后成功 |
| Android QuickJS carrier | Android 37 16KB ARM64 emulator | pass | `quickjs-kt@1.0.12` 加载 QuickJS 2026-06-04；Activity 未启动时完成真实 Pi fixture 的 16 个 lifecycle events |
| Android 5-minute background progress | Android 37 emulator + Kimi | pass after carrier migration | WebView attempt 27 需 Activity 回前台才继续；QuickJS attempt 30 在 Launcher 前台等待 300s 后自行完成 model → tool → model，309.534s 成功 |
| iOS idle WebContent recovery | iPhone 17 Pro / iOS 26.5 simulator | pass | 当前构建中 MobileAgent PID 50123 保持，WebContent PID 50136 被终止后重建为 50189，Pi fixture 再次显示“Pi core 已验证” |
| iOS active deterministic WebContent recovery | iOS 26.5 simulator / 30s native delay | pass after fix | App PID 78400 保持；WebContent 78402 被终止，bridge Task 立即 cancel，重建为 78455，下一 fixture 通过且无 stale completion |
| iOS finite background carrier | iOS 26.5 simulator / Debug deterministic delay | bounded pass | 普通后台 60s 会挂起；`beginBackgroundTask` 下 20s Pi 全闭环在 background 完成，60s 样本约 44s 收到 expiration 并明确失败 |
| iOS Release runtime host | iOS 26.5 simulator Release | pass, online pending | hidden WKWebView host 不再被 `#if DEBUG` 移除；Release 安装启动记录 `MobileAgentPi runtime ready`，不运行 fixture，Debug hooks 在二进制中不存在 |
| Provider-neutral Release build | Android 37 toolchain / iPhoneOS 26.5 SDK | pass, unsigned | Android Release APK 与 iOS arm64 Release app 均包含手机端可配置 HTTPS endpoint/model transport；Android APK 16KB zipalign 通过，两端 Release 均无 Debug 注入标记 |
| iOS physical Release runtime | iOS 26.6 physical device | pass, online 10/10 | 本地开发 Team + wildcard profile 签名当前 Release；Kimi + public URL L1 基线 10/10，product gate true |
| Android/iOS L0 repeatability | Android 37 16KB + iOS 26.5 simulators | 10/10 each | Android 每次独立 QuickJS；iOS 10 个独立 App PID 且每次进入真实 `IOSDeviceRuntime.observe`；见结果报告 |
| iOS deterministic public L1 | iOS 26.5 simulator / `open_settings` | 10/10 dev-only | Pi → real `IOSDeviceRuntime.invoke` → system accepted → App background → Pi final；手机端 JSONL 与聚合器通过，product gate false |
| iOS deterministic process recovery | iOS 26.5 simulator / R1 | pass | PID 78919 model wait 中 SIGKILL；PID 78991 补写 `RUN_INTERRUPTED` 后完成新 run，唯一终态且 pending 清除 |
| On-device benchmark recorder | Android online L1 | pass | 统一 JSONL、隔离 session、本地 adjudication、对象 evidence、App 内导出均验证 |
| Android L1 repeated baseline | Pixel 9 / Android 37 emulator + Kimi | 10/10 dev-only | 100% success，p50 12.483s / p95 18.232s；产品证据 0，详见结果报告 |
| native builds | Android Debug / iOS Debug | pass | Gradle `assembleDebug` 与 Xcode simulator build 成功 |
| dependencies | production npm tree | pass | `npm audit --omit=dev` 为 0 vulnerabilities |

2026-08-12 provider-neutral transport 变更后再次复验：Android `assembleRelease` 与 lint vital 通过，`app-release-unsigned.apk` 的 16KB page zipalign 通过，合并 Release manifest 不含 Debug receiver/action。iOS `iphoneos` Release、`CODE_SIGNING_ALLOWED=NO` 生成 arm64 Mach-O，二进制不含 Debug fixture/delay 标记。最初把证书显示名中的括号值误当 Team ID，导致显式定向构建出现 `No Accounts` / `No profiles`；随后由 Xcode Accounts 与 profile 内容确认真实 Team，该阻塞已解除，见下文真机结果。

2026-08-12 登录态记录变更后复验：Android JVM 12/12（endpoint 3、benchmark response 4、login probe/state machine 5），Debug/Release 均构建通过；最新 Debug APK 安装后 QuickJS fixture 为 `quickJs=2026-06-04 events=16`。iOS simulator Debug 与 unsigned iphoneos Release 均构建通过；指定 iPhone 17 Pro simulator 安装后真实进入 `IOSDeviceRuntime.observe`，并记录 `fixture passed; state=0`。Android 私有目录的 56 条历史 JSONL 可由新聚合器完整读取；旧记录均计入 `login_state_unknown_runs`，`login_persistence_verified_runs=0`，没有把“未测量”误报为“未丢失”。目标包与授权测试账号仍缺失，尚无 W1 真机登录迁移证据。

2026-08-12 Kimi 额度恢复后，C1 attempt 25 首次在线验证分阶段 `text → json_schema`，随后 `c1-staged-baseline-v2` attempts 26–35 连续 10/10 success；p50/p95 43.903s/51.657s，0 crash、0 tool failure、0 manual takeover。全部为 emulator/dev-only，产品门槛仍 false，详见 [C1 结果](results/android-c1-chrome-baseline-2026-08-12.md)。

2026-08-12 物理 iPhone 签名复核发现，证书显示名中的括号值不是 Team ID；Xcode 账号及已下载 wildcard profile 中的真实 Team ID 才能用于签名，且 profile 必须覆盖目标设备。改用正确 Team 后当前 Release 成功签名并安装到 iOS 26.6 真机，启动记录 `MobileAgentPi runtime ready`。随后完成在线 BYOK public L1；锁屏与长时后台预算仍待验证。

2026-08-12 iOS 真机在线复验：镜像键盘会破坏 `[` 等 benchmark 标记，因此补上 `mobileagent://compose?prompt=` 只预填、不自动执行的输入入口。`UIApplication.open` 在该 iOS 26.6 设备上对 Settings 与 HTTPS 均返回 false；HTTP/HTTPS 改用公开 `SFSafariViewController` 后，`ios-physical-release-baseline-v2` 连续 10/10 success。每次 2 model / 1 tool，p50/p95 12.393s/16.923s，0 action/observation failure、crash、manual takeover；全部 `physical_device`、`dev_only=false`，机器聚合器 `meets_v0_product_gate=true`。网页覆盖期间 Pi session 保持，关闭网页后恢复并写终态。详见 [iOS physical L1 结果](results/ios-l1-physical-release-baseline-2026-08-12.md)。

核心命令：

```bash
npm run check
npm test
npm run build
npm run pi:test:browser
npm run android:build
npm run ios:typecheck
npm run ios:build
npm audit --omit=dev
```

模拟器运行证据不是产品 benchmark：开发机只负责构建、安装和取证；Pi core、session 与 bridge 在 App 进程内运行，运行期没有 ADB/Xcode 参与 agent loop。

双平台 L0 重复性结果见 [results/mobile-l0-fixture-2026-08-12.md](results/mobile-l0-fixture-2026-08-12.md)。两端各 10/10，满足 fixture 门槛；仍不代表在线模型、真机或主流 App benchmark。

iOS public adapter 另有 [10/10 deterministic L1 基线](results/ios-l1-open-settings-deterministic-2026-08-12.md)：10 个独立 PID 均由 Pi 调用真实 `open_settings`，确认系统 accepted、Mobile Agent 进入 background 后继续完成 final，并在手机端写出有效 JSONL。聚合器报告 success rate 100%、p95 2.251s、前台干扰 p95 1.025s、0 crash/failure；因 simulator + deterministic model，`product_eligible=0` 且 `meets_v0_product_gate=false`。

同报告内的独立 R1 进程故障样本在 PID `78919` 的首个 model wait 中注入 `SIGKILL`。新 PID `78991` 将 pending run `84F9D8F6-ABC2-4678-AF87-FC1A8228CBD9` 补写为 `failed/crash/RUN_INTERRUPTED/process`，随后以新 run `07F39B56-7CD2-4588-A5E2-1FECA450EBE5` 完成 success；最终无 pending，旧动作没有自动重放。

## 用户任务路径

两端 UI 提交任务后均调用嵌入式 Pi runtime；Android 用户任务由进程内、Activity 无关的 QuickJS 承载，iOS 仍由 WKWebView 承载：

```text
prompt + persisted Pi messages
→ pi-agent-core
→ native model_complete (Key 不进入 JS)
→ Pi tool validation and scheduling
→ native tool_execute + native approval
→ persisted Pi messages + final response
```

Android 把 Pi messages 存入私有 SharedPreferences；iOS 存入 UserDefaults。API Key 仍分别位于 Android Keystore 与 iOS Keychain。

iOS deterministic fixture 不再用工具结果 stub：`tool_execute(device_observe)` 直接调用 `IOSDeviceRuntime.observe`，并在 native reply 前校验 `platform=ios`、application state，以及 `global_ui_observe/global_ui_control=false`。iOS 26.5 模拟器前台样本记录 `fixture used IOSDeviceRuntime.observe; state=active` 后完成 final。该项证明真实只读 adapter 接入，不替代需要 BYOK 的在线模型证据。

## Android 在线 L1 记录

Prompt：`Open the Clock app and report the visible page title.`

- 第一次 `model_complete`：HTTP 200，4143 ms，2 messages / 3 tools。
- Pi 调用 `device_invoke(open_app)`，`topResumedActivity` 变为 `com.google.android.deskclock/com.android.deskclock.DeskClock`。
- Clock 占前台、Mobile Agent Activity 在后台时，Pi WebView 继续执行。
- 第二次 `model_complete`：HTTP 200，4043 ms，4 messages / 3 tools。
- `agent_complete` 保存 4 条 Pi messages；App 最终报告可见标题 `Clock` 及底部 tab。

随后对 Mobile Agent 执行 Android `force-stop` 并重启。Pi transcript 仍在，但 Android 37 模拟器把侧载 App 的 AccessibilityService 从 enabled list 移除，必须再次经过系统同意页授权。重新授权后的追问 `What app did you just open? Reply only with its name.` 显示 `restored=true`，请求携带 6 messages，HTTP 200 / 4260 ms，回答 `Clock`。

判定：Pi session 的进程恢复通过；Android `force-stop` 下设备控制授权持久性失败。`force-stop` 是强制停止语义，不能直接外推为普通低内存进程回收，但也不能把它隐藏成 session 成功。后续应分别测 swipe-away、低内存 kill、系统重启和 App 更新。

### Recorder 与整机重启补充

第一条 recorder smoke 生成 12.539s / 2 turns / 2 model calls / 1 tool call，识别 Clock 7.5 和 2.814s 前台干扰估算。测试发现并修复 evidence 被编码成 JSON 字符串而非对象的问题。

随后运行隔离 `[BENCH:L1]`：11.241s / 3 turns / 3 model calls / 2 tool calls。模型第一次 `device_invoke` 失败后自行纠正，最终返回 `{"visible_title":"Clock","current_app":"com.google.android.deskclock"}`；手机端 adjudicator 判为 success，同时保留 `action_failures=1`，没有因最终成功抹掉中间失败。记录为 emulator/dev_only。

第一次整机重启后，Key、Accessibility 与 JSONL 均保留，但 Pi fixture 卡在首个 `runtime_event`。根因是原生 bridge 在 hidden WebView 尚未 `isAttachedToWindow` 时丢弃 reply；reply proxy 本身不需要该条件。移除检查后第二次整机重启通过：Accessibility 仍 enabled/bound，Pi fixture 完成，三项状态均就绪。

Android 导出第一次验证还发现 FileProvider 把 path 配到具体文件会崩溃；修正为只映射私有 `benchmarks/` 目录，并用 ClipData/临时 read grant 后，系统 Chooser 正常打开且无权限警告。

### 连续 10 次与生命周期补充

连续 L1 attempts 2–11 为 10/10 success，0 crash、0 permission loss、0 observation failure、0 manual takeover；duration p50/p95 为 12.483s/18.232s，前台干扰 p50/p95 为 1.923s/2.051s。共有 33 次 model call、23 次 tool call、9 次已恢复的 action failure。全部为 emulator/dev-only，`product_eligible=0`；机器聚合器因此把重复次数/成功率门槛与产品门槛分开，产品门槛明确为 false。逐 run 证据见 [results/android-l1-clock-2026-08-11.md](results/android-l1-clock-2026-08-11.md)。

9 次中间失败主要是模型猜测 `com.android.deskclock`，设备实际安装 `com.google.android.deskclock`。原生 adapter 增加精确包名、常见别名与 Launcher label 解析；随后强制错误包名的定向 run 以 1 tool call、0 action failure 成功打开 Google Clock。该结果只验证修复命中，不冒充修复后的新 10 次基线。

以 App UID 对明确进程 PID 发送 `SIGKILL` 后，Accessibility 绑定启动新进程；从最近任务真正上滑移除后亦然。两种情况下重开 UI 均显示 Kimi 已连接、设备控制已启用、Pi core 已验证，12 行 JSONL 保留。它们与会清掉该模拟器无障碍启用态的 `force-stop` 是不同生命周期语义。

App 更新后又复现一项 owner 竞态：新 `MainActivity` 可见，但单例 runner 仍复用旧 Activity 的 hidden WebView，在线 run 停在第一个 `runtime_event`。主入口改为 `singleTask`，runner 按 Activity owner 销毁/重建旧 runtime；修复后的在线 run 能越过首事件并以 9.387s、1 tool call、0 action failure 完成。

该卡死还暴露“只在结束写 JSONL”会漏 crash。双端 recorder 现于 run 开始原子写 `pending-run.json`，正常终态追加 JSONL 后清除；新进程启动时将未完成 pending 保守补写为 `failed/crash/RUN_INTERRUPTED`，并注明 counters 可能不完整。Android 注入进程死亡实测从 14 行变 15 行且 pending 清除；下一次正常终态失败（Kimi engine overloaded）只追加一行并清除 pending，没有重复或误判 crash。

### Renderer 回收与模型请求取消

Android 增加只存在于 Debug source set、受 `android.permission.DUMP` 保护的 renderer 终止 receiver；`processReleaseManifest` 的合并清单确认 Release 不含该 receiver 或 action。idle 注入后 Pi fixture 自动重建并再次通过。

active attempt 18 在首个在线模型请求期间注入 renderer 死亡，JSONL 以 `failed + crash=true + WEBVIEW_RENDERER_GONE + runtime:webview_renderer` 结束；随后 attempt 19 以 38.915s、2 model calls、1 tool call、0 action failure 成功，证明恢复后的下一任务可用且没有自动重放已中断任务。

第一次取消回归 attempt 20 虽正确结束 run，但阻塞式 `HttpURLConnection` 仍在后台完成 HTTP 200，说明只取消 coroutine 不足以停止底层 I/O。`OpenAICompatibleClient` 现显式跟踪连接，并在 stop、owner 迁移和 renderer gone 时调用 `disconnect()`；attempt 21 的日志从 `model request started` 到 `model request cancelled` 为 1,060 ms，之后没有完成态 HTTP 200，同时 JSONL 正确记录 renderer 失败。

iOS 当前构建也在 active run 跟踪原生 model `Task`，并在 stop 或 `webViewWebContentProcessDidTerminate` 时取消。空闲 WebContent 回收已用 iOS 26.5 模拟器实测：MobileAgent PID 50123 保持，WebContent 从 PID 50136 重建为 50189，界面再次显示“Pi core 已验证”。由于该模拟器没有 API Key，iOS active 在线请求取消仍未运行验证。

iOS deterministic active renderer 注入另用 native model delay 验证。第一次终止 WebContent 后 runtime 能失败并重建，但旧 bridge request Task 仍到点输出，说明 renderer 已死而 native await 未取消。bridge request 现用 Swift UUID token 跟踪（不能复用会随新 JavaScript context 重置的 `native-1` request id），并在 `webViewWebContentProcessDidTerminate` 时统一 cancel。最终复验中 MobileAgent PID `80098` 保持，WebContent `80106` 被终止后立即记录 `WKWEBVIEW_CONTENT_PROCESS_TERMINATED` 与 10 秒 delay cancelled；新 WebContent `80135` 完成真实 `IOSDeviceRuntime.observe` fixture。等待越过原截止点后没有 stale finish。该项证明 deterministic renderer lifecycle，不替代在线 URLSession cancellation。

### iOS WKWebView Promise 与有限后台边界

首次 60 秒 fixture 启动时，Pi 已进入原生 `model_complete`，UI 却立即显示失败。日志定位为 `evaluateJavaScript` 尝试把异步 `run()` 返回的 Promise 桥接给 Swift，报 `JavaScript execution returned a result of an unsupported type`。fixture 与真实用户任务入口均改为 `void window.PiMobileRuntime.run(...)`；最终结果继续只经 `agent_complete` bridge 回传。

修复后运行三个 iOS 26.5 模拟器 Debug 对照，均通过 Simulator 的实际 Home 操作切后台：

- 无 background task、60,000 ms delay：到期后仍无日志；恢复 App 时 delay 才结束并完成 fixture，证明普通后台是 suspension + resume，不是持续推进或进程崩溃。
- 使用 `beginBackgroundTask`、20,000 ms delay：delay、tool bridge、第二次 model 与 `agent_complete` 全部在 `UIApplicationState=background` 完成，fixture pass 时仍停留 Home。
- 使用 `beginBackgroundTask`、60,000 ms delay：从申请到约 44 秒触发 expiration，后台释放 token，并以 `DEBUG_BACKGROUND_TASK_EXPIRED` 明确失败。

该 harness 复用真实 `AgentSession` 的公开后台机制；delay/background 开关均在 `#if DEBUG` 内。结果只建立“预算内可收尾、超预算停止”的 carrier 边界，不是在线模型、真机、锁屏或稳定 44 秒 SLA。

### 锁屏能力边界与恢复

attempt 22 在首个在线模型请求开始后熄屏。Pi、原生 HTTP 与前台服务继续运行并在 39.476s 写出唯一终态，但 Accessibility/Intent 无法让 Clock 成为可观察前台；旧 adapter 仍把 6 次工具调用记为成功，模型重试到 7 次 model call，最终只得到 null 字段并由手机端 adjudicator 判失败。这证明“Agent 仍在运行”不等于“锁屏下 GUI backend 可用”。

Android adapter 现通过 `PowerManager.isInteractive` 与 `KeyguardManager.isDeviceLocked` 把状态写入 `observe.device_state/capabilities`，并让 GUI `act/invoke` 返回 `SCREEN_NOT_INTERACTIVE` 或 `DEVICE_LOCKED`。Pi prompt contract 要求收到这两个能力错误后不再重试；benchmark 终态优先保留底层 tool failure，而非覆盖为泛化 adjudication 错误。

修复后的 attempt 23 在相同注入点以 8.142s、2 model calls、1 tool call、`action_failures=1` 结束，`failure_code=SCREEN_NOT_INTERACTIVE`、`failure_stage=tool:device_invoke`。解锁后的 attempt 24 随即以 11.634s、2 model calls、1 tool call、0 action failure 成功，Key、Accessibility、Pi fixture 和 JSONL 均保留。输入时还暴露软键盘覆盖发送区；Compose 根布局增加 IME inset，发送按钮在键盘显示时保持可见，并提供 IME Send action。

attempt 25 尝试用“打开 Clock 后独立观察 12 次”延长后台窗口。Pi/Kimi 在一个 model turn 中批量产生 12 个 `device_observe`，总计 13 tools 全成功，但整条 run 仅 20.902s，Clock 前台干扰区间为 14.195s。它证明 multi-tool burst 可执行，不证明长时后台；长时门槛必须使用确定性持续时间或真实长任务，不能用 tool count 代替 elapsed time。

### 5 分钟后台与 Android runtime carrier

Debug-only model-delay receiver 受 `android.permission.DUMP` 保护、单次最多 300,000 ms；Release 合并清单不包含该 action。它只延迟下一次原生模型请求，不伪造 Pi、网络或工具执行。

WebView attempt 27 在首个 model call 前注入 300,000 ms 延迟并回到 Launcher。等待期间同一 PID `13160`、Foreground Service 与 pending journal 均保持；延迟到点后原生 Kimi 在 4,626 ms 返回 HTTP 200，但 WebView JS 在后台没有产生 `tool_execute`。继续等待约 53 秒仍无进展，手动把 Mobile Agent Activity 拉回前台后才立即调用工具并最终以 362.856s 成功。这个 success 是“恢复前台后完成”，不能记作后台持续推进。

因此 Android 用户任务 carrier 迁到 `quickjs-kt@1.0.12`，WebView 暂留 deterministic fixture/回退对照。QuickJS bundle 使用集中 prelude 补齐 `TextEncoder`、Abort 与 microtask 宿主差异；APK 含四 ABI 的 `libquickjs.so`，`zipalign -P 16` 通过，16KB ARM64 emulator 在 Activity 未启动、Launcher 前台时运行原版 Pi fixture，返回 `android-quickjs`、正确四条 message roles 与最终 `agent_end`（共 16 events）。

迁移后的普通在线 attempt 28 以 7.416s、2 model/1 tool、0 failure 完成。第一次 5 分钟 QuickJS attempt 29 在原生 HTTP 已于延迟到点后返回 200 时，被实例级 `evaluationTimeoutMillis=60s` 中断；这证明该超时会跨 native async await 累积墙钟，不能用于长任务。修复为“bundle 初始化 60s 限时 + 整体 coroutine 15 分钟 watchdog + 显式 cancel interrupt”后重跑 attempt 30：

- 00:06:24.012 开始 300,000 ms 延迟并退到 Launcher；等待期 PID `16015`、Foreground Service、pending journal 均稳定。
- 00:11:24.016 延迟结束，Activity 未恢复；原生 HTTP 5,016 ms 后返回 200。
- 00:11:29.043 直接出现 `tool_execute`，Clock 成为前台；随后第二次 HTTP 3,414 ms。
- 00:11:33.500 Pi 完成 4 messages，journal 清除；JSONL attempt 30 为 success，309.534s、2 turns/2 model/1 tool、0 failure，`runtime_carrier=quickjs-kt@1.0.12`。

判定：Android Pi runtime 已证明 5 分钟 Activity-background survival **与 progress**；工具打开目标 App 后仍会占用用户前台，这不等于已实现“不打扰前台”的 Computer Control/OEM backend。证据仍是 emulator/dev-only，尚不能替代真机。

迁移后的 active cancel 也以 30,000 ms native async delay 注入验证：attempt 31 在 21.702s 由 UI Stop 结束，JSONL 为唯一 `cancelled/USER_CANCELLED`、`failure_stage=model`，pending 清除且没有模型 HTTP 启动。下一条在线任务随即重新创建 QuickJS runtime 并完成 2 model/1 tool/final；因 ADB `input text` 吞掉 prompt 开头的 `[`，该恢复样本是 `completed_unverified`，不计 L1 成功率。

新 carrier 的 active process recovery 同样通过：确认 30,000 ms native wait、Foreground Service 与 pending 都已开始后，仅对解析出的 App PID `16738` 注入 `SIGKILL`。Accessibility 拉起新 PID `16940`，Application 将未完成 run 补写为 12.024s、`failed/crash/RUN_INTERRUPTED/process` 并清除 pending；`permission_lost=false`。随后同一新进程用已恢复 transcript 在 9.169s 内完成在线 2 model/1 tool/final，证明不会自动重放中断任务，但下一任务可用。

强制 deep-idle 注入下，同一 PID `16940` 在 `mForceIdle=true / mState=IDLE` 期间跨过 30,000 ms native delay，随后两次在线 HTTP 分别为 5.943s/3.355s，中间完成 `tool_execute`，总 run 40.497s、2 turns/2 model/1 tool、journal 清除。样本结束后立即执行 `deviceidle unforce` 与 battery reset，状态恢复 `ACTIVE`。这只证明 Android 37 emulator 的 forced-idle 注入没有阻断当前 Foreground Service 路径，不能外推到物理设备自然 Doze、厂商省电策略或 maintenance window。

## 尚未验证

- iOS 模拟器没有配置可用 API Key，因此 iOS 仍没有“新 Pi 路径 + 在线 Kimi”的真实请求证据。
- 没有 Android 真机运行证据；iPhone 当前已有签名 Release、在线 Kimi、公开 URL 动作、覆盖后恢复与 10/10 产品基线证据，但仍没有任意第三方 App UI 控制、锁屏或长时后台证据。
- Android 已证明 QuickJS 用户路径的 5 分钟后台持续推进、native async wait 中主动取消、active 进程中断后恢复与 emulator forced deep-idle；旧 WebView carrier 只能存活、不能持续推进。物理设备自然 Doze/OEM 省电和真机长时后台仍需专项回归。
- iOS 已验证 idle/active deterministic WebContent 回收、deterministic App 进程 journal 恢复、有限后台边界及 Release 真机网页覆盖后的在线 session 恢复；active 在线请求取消、在线 transcript 进程恢复、锁屏与长时后台预算仍缺证据，且公开 API 路线不支持长时常驻。
- 没有美团、小红书、抖音、微信的 10 次连续 benchmark。
- 当前 ADB 只连接 16KB emulator，四个 Android 目标包均未安装；iPhone 对四个已知 bundle id 的只读查询也未命中。安装目标 App、授权测试账号与登录态需要真机侧准备。
- Android Accessibility 仍会占用前台；iOS 公共路径仍不能任意读取/点击第三方 App UI。

## 下一步

1. iOS 补在线 active cancel/transcript 进程恢复、锁屏与后台预算；Android 真机补自然 Doze/OEM 省电与更新后的权限/session 结果。
2. Android 真机启用 Accessibility，按 `benchmark-v0.md` 从只读任务开始跑 10 次基线。
3. 真机复验 App 切后台、锁屏与进程回收；核对 QuickJS native ABI、内存与电量。
4. iOS 只对目标 App 已公开的 Intent/Shortcut/URL 能力计入 public benchmark；任意 GUI 自动化留在独立 research lane。
