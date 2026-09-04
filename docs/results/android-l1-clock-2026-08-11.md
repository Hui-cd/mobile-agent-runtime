# Android L1 Clock baseline — 2026-08-11

## 结论

Android 37 Pixel 9 emulator 上，手机端 Pi runtime 经 `android_accessibility_foreground` 连续完成 10/10 次 L1 Clock；无 crash、权限丢失、人工接管或 observation failure。全部记录均为 `dev_only=true`，因此产品证据为 0，不能替代 Android 真机门槛。

原始 JSONL 在 App 私有目录生成并经 ADB 只读导出取证；纳入本报告的 10 行子集 SHA-256 为 `79e40a2ad34c740d480073f4c4fe5d243d4bca929245690008884932009bc905`，时间范围为 `2026-08-11T01:37:08.905253Z` 至 `2026-08-11T01:46:43.428451Z`。

## 聚合

| 指标 | 结果 |
|---|---:|
| success | 10/10 (100%) |
| product eligible | 0/10 |
| duration | min 6,037 ms / p50 12,483 ms / p95 18,232 ms |
| foreground interrupt | min 1,690 ms / p50 1,923 ms / p95 2,051 ms |
| agent turns | 33 total / min 2 / p50 3 / max 4 |
| model calls | 33 total / min 2 / p50 3 / max 4 |
| tool calls | 23 total / min 1 / p50 2 / max 3 |
| action failures | 9 total，分布于 8 个 run，均由 agent 后续纠正 |
| observation failures / crashes / permission losses | 0 / 0 / 0 |

目标 App 均为 `com.google.android.deskclock`，版本 `7.5 (563137996)`。

## 单次记录

| attempt | run_id | duration ms | turns/model/tools | foreground ms | action failures |
|---:|---|---:|---:|---:|---:|
| 2 | `badc76d7-8630-47bd-9a67-d9d3baf845aa` | 11,241 | 3/3/2 | 1,978 | 1 |
| 3 | `6dee2cd6-fea1-41a4-9f50-5d6ca67629e4` | 9,565 | 3/3/2 | 2,051 | 1 |
| 4 | `bf351c7f-2a71-4cac-b2ca-d8d924cdfbb6` | 18,232 | 4/4/3 | 1,838 | 1 |
| 5 | `a64d4951-0c37-4bda-bdda-292e621dcb83` | 6,037 | 2/2/1 | 1,937 | 0 |
| 6 | `e84fb064-8e42-4ff1-8cb1-a4cc03c08397` | 12,483 | 4/4/3 | 1,800 | 1 |
| 7 | `5d09c3dd-fefb-42d2-bbde-461ed5261672` | 12,869 | 3/3/2 | 1,855 | 1 |
| 8 | `aaf44cc7-bc93-40e2-af46-d80db014339f` | 13,170 | 4/4/3 | 1,947 | 2 |
| 9 | `f598ed99-b36d-4906-9b18-c1bf91040609` | 13,070 | 4/4/3 | 1,923 | 1 |
| 10 | `88d8ecef-de24-4aa5-b7cd-5d488a2f62e0` | 6,299 | 2/2/1 | 1,690 | 0 |
| 11 | `a07daff6-46c6-42c6-8850-d46b880a3235` | 12,889 | 4/4/3 | 2,020 | 1 |

## 失败分析与修复验证

基线中间失败主要来自模型猜测 AOSP 包名 `com.android.deskclock`，而设备安装的是 `com.google.android.deskclock`。原生 `open_app` adapter 随后增加“精确包名 → 已知别名 → Launcher label”的解析顺序。

定向回归 run `931421d6-55a6-4d59-9099-e9c10453f085` 明确要求打开错误包名 `com.android.deskclock`，原生层解析到 Google Clock，并以 7,442 ms、2 turns、2 model calls、1 tool call、0 action failures 完成。该单次结果证明修复命中，但不是修复后的新 10 次基线。

工具 Schema 与 Activity owner 修复后的 run `d5d205f3-c6b4-4d31-abc1-d1de5363ec31` 再次以错误包名启动，9,387 ms、2 turns、2 model calls、1 tool call、0 action failures 完成；结果为当前可见 `Bedtime` tab 与正确目标包。该回归仍是单次开发证据。

## 生命周期补充

- 整机重启：Key、Accessibility enabled/bound、JSONL 与 Pi fixture 均恢复；同时修复了冷启动 bridge 首次 reply 被 attach 状态错误丢弃的问题。
- 模拟低内存进程死亡：以 App UID 对明确 PID 发送 `SIGKILL` 后，Accessibility 绑定触发新进程；重开 UI 后 Key、授权、Pi fixture 与 12 行 JSONL 均存在。
- 最近任务上滑移除：任务卡被移除后进程由 Accessibility 绑定恢复；重开 UI 后上述四项仍存在。
- `force-stop`：本模拟器会清除 Accessibility enabled 状态，仍需用户重新授权；不得与普通进程回收混为一谈。
- run 中进程死亡：attempt 16 在 model 期间被明确 `SIGKILL`；下一进程从原子 pending journal 补写 `failed + crash=true + RUN_INTERRUPTED`，没有静默缺行。随后 attempt 17 遇到 Kimi `engine overloaded`，正常落一条 failed 并清除 pending，证明终态失败不会被误记成 crash。
- WebView renderer 独立死亡：Debug-only、`android.permission.DUMP` 保护的注入口在 idle 时触发自动重建；active attempts 18/21 均记录 `failed + crash=true + WEBVIEW_RENDERER_GONE + runtime:webview_renderer`。attempt 19 随后以 38,915 ms、2 model calls、1 tool call、0 action failure 成功，证明下一任务可用。
- 底层 HTTP 取消：attempt 20 暴露仅取消 coroutine 后阻塞连接仍完成 HTTP 200；显式跟踪并 `disconnect()` 后，attempt 21 在 1,060 ms 记录 `model request cancelled`，没有后续完成态 HTTP 200。Release 合并清单不含测试 receiver/action。
- 锁屏边界：attempt 22 在首个 model request 后熄屏，agent 虽继续 39,476 ms 并落盘，但旧 adapter 误报工具成功，最终 7 model/6 tools 后以 null 结果失败。接入 `PowerManager`/`KeyguardManager` 后，attempt 23 在 8,142 ms、2 model/1 tool 内明确记录 `SCREEN_NOT_INTERACTIVE`、`tool:device_invoke`，不再盲目重试。
- 解锁恢复：attempt 24 随即以 11,634 ms、2 model calls、1 tool call、0 action failure 成功，Key、无障碍、Pi fixture 与 JSONL 均保留。
- 多工具后台样本：attempt 25 在 Clock 占前台时完成 12 次 observe，共 20,902 ms / 3 model / 13 tools / 0 failure；模型把 12 次观察批量放在一个 turn，实际前台区间只有 14,195 ms，因此不是长时后台证据。
- WebView 5 分钟反例：attempt 27 等待 300,000 ms 时进程、服务和 journal 均存活，原生 HTTP 也在到点后返回 200；但约 53 秒内没有 `tool_execute`，直到手动恢复 Mobile Agent Activity 才继续。最终 362,856 ms success 不能算后台 progress。
- QuickJS 迁移 smoke：16KB ARM64 emulator 在 Launcher 前台、Activity 未启动时加载 `libquickjs.so`，以 QuickJS 2026-06-04 完成真实 Pi fixture；普通在线 attempt 28 为 7,416 ms / 2 model / 1 tool / 0 failure。
- QuickJS 超时反例：attempt 29 证明实例级 60 秒 evaluation timeout 会跨 300 秒 native async await，在 HTTP 200 后以 interrupted 失败；改为 coroutine 总任务 watchdog。
- QuickJS 5 分钟通过：attempt 30 全程保持 Launcher 前台等待 300,000 ms，到点后无需恢复 Mobile Agent Activity即完成 HTTP → `tool_execute` → HTTP → final；总计 309,534 ms / 2 turns / 2 model / 1 tool / 0 failure，journal 清除，记录 `runtime_carrier=quickjs-kt@1.0.12`。
- QuickJS 主动取消：attempt 31 在 30,000 ms native async delay 中由 UI Stop 结束，21,702 ms 写出唯一 `cancelled/USER_CANCELLED` 并清除 journal；随后下一条在线任务完成，证明取消不会污染下一实例。恢复样本因 ADB 文本注入缺少 `[` 而是 ad hoc，不计 L1。
- QuickJS active process recovery：确认 native wait/pending 已开始后只杀 PID `16738`，Accessibility 拉起 PID `16940`；新进程把该 ad hoc run 补写为 12,024 ms、`failed/crash/RUN_INTERRUPTED/process`，随后 9,169 ms 完成下一条在线 2 model/1 tool 任务。两条均为生命周期证据，不计 L1。
- forced deep-idle：`mForceIdle=true / mState=IDLE` 期间，同一 PID 跨过 30,000 ms delay 并完成 2 model/1 tool/final；总计 40,497 ms、0 failure，结束后已 unforce/reset。仅为 emulator 注入，不代表物理设备自然 Doze/OEM 省电结果。

限制：这些都是模拟器开发证据。未验证物理设备、Doze，以及美团/小红书/抖音/微信的登录与真实内容流程。attempts 18–30 是生命周期/专项样本，不属于 10 次成功率基线；5 分钟 pass 证明 Agent carrier 后台推进，不证明 GUI 工具不占前台；锁屏结论也不代表 OEM 后台 backend 的能力。
