# Mobile L0 fixture repeatability — 2026-08-12

## 结果

| 平台 | 设备 | Runtime | 样本 | 结果 |
|---|---|---|---:|---:|
| Android | `sdk_gphone16k_arm64` / Android 17 (API 37) / ARM64 / 16KB page | `quickjs-kt` / QuickJS 2026-06-04 | 10 | 10/10 |
| iOS | iPhone 17 Pro / iOS 26.5 simulator | WKWebView + `WKScriptMessageHandlerWithReply` | 10 | 10/10 |

两端均运行 App 内打包的原版 `@mariozechner/pi-agent-core` fixture，完成 fake model → tool call → native bridge → tool result → fake model → final。每次结果都校验四条消息角色 `user/assistant/toolResult/assistant` 和最终 `agent_end`。

## Android 证据

Debug APK 通过受 `android.permission.DUMP` 保护的 receiver 依次触发 10 次；每次调用都创建并关闭独立 QuickJS runtime。测试等待上一条 `fixture accepted` 后才开始下一次，没有并发堆叠。

核心命令：

```bash
adb shell am broadcast \
  -a ai.mobileagent.DEBUG_RUN_QUICKJS_FIXTURE \
  -p ai.mobileagent
```

00:52:32–00:52:34 的日志包含 10 条：

```text
MobileAgentQuickJs: fixture passed; quickJs=2026-06-04 events=16
MobileAgentQuickJsTest: fixture accepted; platform=android-quickjs finalText=Pi mobile fixture complete.
```

总计 10 条 `fixture passed`、10 条 `fixture accepted`、0 条 `fixture failed`。

## iOS 证据

安装 Debug simulator App 后，对同一 iPhone 17 Pro simulator 连续执行 10 次 terminate → launch。每次按新 App PID 查询 unified log，必须同时出现真实 adapter 日志与 fixture pass 才计数。

核心命令：

```bash
xcrun simctl terminate <simulator> ai.mobileagent.ios
xcrun simctl launch <simulator> ai.mobileagent.ios
xcrun simctl spawn <simulator> log show \
  --predicate 'processIdentifier == <pid> AND eventMessage CONTAINS "MobileAgentPi"'
```

10 个独立 PID：`74963, 74998, 75036, 75073, 75109, 75144, 75179, 75212, 75247, 75286`。每个 PID 均记录：

```text
MobileAgentPi fixture used IOSDeviceRuntime.observe; state=active
MobileAgentPi fixture passed; state=0
```

`IOSDeviceRuntime.observe` 返回前还校验 `platform=ios`、application state，以及 `global_ui_observe/global_ui_control=false`。总计 10/10，0 failure。

## 判定与限制

该结果满足 [benchmark-v0.md](../benchmark-v0.md) 的双平台 L0 10/10 fixture 门槛，但仍是 simulator/dev-only 证据：

- ADB/simctl 只负责安装、触发与取证；Pi loop 和 native bridge 在手机 App 进程内运行。
- Android 10 次在同一 App 进程内创建独立 QuickJS；iOS 10 次为独立 App 进程冷启动。
- fake model 不证明在线供应商、网络取消、真机后台预算或目标 App 兼容性。
- 不计入 L1/L2 产品成功率，也不替代 Android OEM Computer Control、iOS public App Intent 或 LiveContainer research backend 的真实任务证据。
