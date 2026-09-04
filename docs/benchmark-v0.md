# Mobile Agent Benchmark v0

## 目的

验证完整链路，而不是单个点击：

```text
user prompt
→ on-device agent/session
→ remote model inference (allowed)
→ on-device tool execution
→ target app observation/action
→ evidence-backed structured result
→ user response
```

任何依赖 Mac、PC、ADB host 或云手机完成任务的 run 都标为 `dev_only`，不计入产品成功率。

## 测试层级

### L0 Runtime fixture

- fake model 固定返回一次 tool call 和 final。
- fake native tool 返回可断言结果。
- 验证 session、事件顺序、取消、step limit、tool error 和恢复。

当前证据：Android QuickJS 与 iOS WKWebView 均已完成 10/10 simulator/dev-only；详见 [Mobile L0 结果](results/mobile-l0-fixture-2026-08-12.md)。该结果满足 L0 门槛，不计入 L1/L2 产品成功率。

### L1 系统 App smoke

- Android：打开时钟，观察页面并返回当前可见 tab/标题。
- iOS：调用系统地图或设置 URL，只验证系统接受请求，不伪造目标 App 内结果。

当前证据：Android emulator/dev-only Clock 已连续 10/10；iOS `open_settings` deterministic public adapter/recorder 也已 10/10，但在线 smoke 尚未运行。两者都不计产品门槛。详见 [runtime validation](runtime-validation-2026-08-11.md)、[Android L1 结果](results/android-l1-clock-2026-08-11.md) 与 [iOS deterministic L1 结果](results/ios-l1-open-settings-deterministic-2026-08-12.md)。

### L2 真实 App 只读任务

| ID | App | 任务 | 结构化结果 |
|---|---|---|---|
| M1 | 美团 | 搜索测试地点附近的“日料”，浏览并读取前 10 个可见结果 | 店名、评分、人均、距离；缺失字段显式为 null |
| X1 | 小红书 | 搜索“澳门餐厅”，读取前 10 篇结果并进入 3 篇详情 | 标题、作者、可见互动量、摘要 |
| D1 | 抖音 | 搜索“iPhone 17 Pro”，读取前 10 条可见结果 | 文案、作者、可见互动量 |
| W1 | 微信 | 使用专用测试账号搜索专用测试联系人，读取最近 5 条测试消息 | 方向、可见文本、可见时间 |
| C1 | Chrome | 在地址栏输入固定查询并搜索，读取前 5 条自然结果；进入一个详情页，滚动至少一次并读取标题与摘要 | 查询、5 条标题/摘要、详情标题/摘要、滚动标记 |

`C1` 是在现有设备具备 Chrome、但 M1/X1/D1/W1 目标包尚未安装时增加的公开网页主流 App 候选；它验证完整搜索/浏览/滚动/详情读取链路，不替代四个原始目标 App。Android 手机端判定除结构化字段外，还要求目标包为 `com.android.chrome`，搜索证据为成功的 `input + click`，或成功的 `open_url`（只保存 host、URL hash 与查询参数存在性）再点击结果；之后还必须成功 `scroll/swipe`，且滚动后的页面至少有 8 个非空可见文本节点、没有阻断性 WebView 网络错误。Chrome 在 partial-connectivity 环境中可能显示全局 `No internet connection` 横幅；只有该横幅与文本不足同时出现时才判网络失败，不能据横幅否定已加载正文。聚合器会把旧 adjudicator 或不满足这些证据条件却记录为 success 的 C1 run 重新计为 failed，并单列 `invalidated_successes`。

`W1` 额外要求登录持久性证据：只在 `com.tencent.mm` UI tree 同时出现“微信 / 通讯录 / 发现 / 我”时判为高置信 `signed_in`，同时出现“登录 / 注册”时判为 `signed_out`，其余一律 `unknown`。成功 run 必须至少两次观察到 `signed_in` 且全程未出现 `signed_out`；中途观察到 `signed_out` 后即使最终恢复 `signed_in`，仍记为 `LOGIN_LOST`。缺失证据则分别以 `LOGIN_STATE_UNVERIFIED` 或 `LOGIN_PERSISTENCE_UNVERIFIED` 失败。不能把“没看到登录按钮”当作已登录。美团、小红书、抖音目前没有足够稳定的高置信已登录 UI 标志，保持 `unknown`，不伪造登录证据。

不发送消息、不下单、不支付、不关注、不点赞。测试地点、账号和内容必须属于测试环境或得到明确授权。

## 运行矩阵

每个 App/平台/后端组合运行 10 次。后端必须单独统计，不能混合：

- `android_accessibility_foreground`
- `android_computer_control_background`
- `ios_app_intent_public`
- `ios_livecontainer_research`

无法支持的组合记为 `unsupported`，不以人工完成替代自动化成功。

## 单次 run 记录

权威机器格式为 [benchmark-run.schema.json](benchmark-run.schema.json)。Android/iOS App 都在本地追加 JSONL，并可从 App 内系统分享面板导出；模型任务运行不依赖导出或桌面端。

以 `[BENCH:<task_id>]` 开头的任务使用干净 Pi session，不读取或污染普通对话 transcript；smoke 与正式基线还必须在 prompt 中分别带 `[COHORT:<id>]`，例如 `[BENCH:C1] [COHORT:c1-staged-smoke-v1] ...` 和 `[BENCH:C1] [COHORT:c1-staged-baseline-v1] ...`。cohort 只允许 1–64 位字母、数字、点、下划线或连字符。final 必须只输出任务要求的合法 JSON，第一个字符为 `{`、最后一个字符为 `}`，前后不得混入说明文字。`ad_hoc` 只记为 `completed_unverified`。L1/M1/X1/D1/W1/C1 只有通过手机端最低字段、数量和目标能力判定才写 `success`，不能把“模型有回复”当成成功。

run 启动时先原子写 pending journal，终态成功/失败/取消写入 JSONL 后清除。若进程未写终态就死亡，下次进程启动把 pending 补为 `failed`、`crash=true`、`failure_code=RUN_INTERRUPTED`；这类记录的 turn/call 计数只代表启动快照，notes 明确标为可能不完整。这样 crash 会降低成功率，而不是从样本中消失。

生命周期/故障注入样本与连续成功率基线分开。进程/renderer 回收、熄屏/锁屏、Activity recreate、权限丢失等场景必须记录唯一终态和下一任务恢复结果，但不得混入某 App 的 10 次正常条件成功率。通用 Android Accessibility 前台 backend 在锁屏下预期快速失败为 `SCREEN_NOT_INTERACTIVE` 或 `DEVICE_LOCKED`；只有 OEM 后台 backend 才能独立声明锁屏执行成功。

- `run_id`, `started_at`, `platform`, `os_version`, `device_model`
- `runtime_version`, `agent_runtime`, `runtime_carrier`, `model_endpoint_host`, `model`, `app_version`, `backend`
- `task_id`, `benchmark_cohort`, `attempt`, `status`
- `duration_ms`, `agent_turns`, `model_calls`, `tool_calls`
- `manual_takeovers`, `foreground_interrupt_ms`
- `crash`, `login_state_before`, `login_state_after`, `login_lost`, `observation_failures`, `action_failures`
- `permission_lost`：系统权限/无障碍/Shortcut 授权是否在重启、更新或强制停止后丢失
- `result`：任务要求的结构化数据
- `evidence`：关键步骤前后截图 hash、UI tree hash 或目标 App 公开 action 返回值
- `failure_code`, `failure_stage`, `notes`

工具 evidence 默认只保存结果与截图的 SHA-256、时间、工具名和目标引用，不复制完整 UI tree/截图；结构化 result 仍可能含敏感内容，必须使用测试账号并按敏感数据管理导出文件。

导出后的聚合命令：

```bash
npm run benchmark:summary -- /path/to/runs.jsonl
```

聚合器会拒绝畸形记录和非对象 evidence，并按 platform/backend/task/environment/model_endpoint_host/model/benchmark_cohort 分组输出成功率、p50/p95 耗时、前台干扰、turn/model/tool call、人工接管、观察/动作失败、崩溃、登录丢失和权限丢失；登录部分分开统计 applicable、observed、unknown、not-applicable、persistence-verified 与 losses，禁止用 `losses=0` 掩盖 `verified=0`。历史双重编码 evidence 只在能解析回合法对象时兼容并计入 `legacy_evidence_encodings`。缺少新字段的历史 run 进入 `legacy-unknown` cohort；不得与新基线合并。重复次数/开发成功率与 `meets_v0_product_gate` 分开；模拟器记录自动为 `dev_only=true`，不计产品证据。

## 成功判定

一个 run 只有同时满足以下条件才是 `success`：

1. 从手机端 prompt 启动并由手机端 session 完成。
2. 没有未记录的人工操作；允许的确认和接管已计数。
3. 达到任务要求的最小结果数量。
4. 结果字段可由保存的 observation evidence 复核。
5. Agent 没有把“URL 已提交”“按钮已点击”错误报告为最终业务结果。
6. App、runtime 和登录态在结束时没有崩溃或损坏。

## 首批门槛

- L0：Android、iOS 均为 10/10。
- L1：各已声明能力组合为 10/10。
- L2：单组合至少 8/10，且 10 次中无登录丢失、无未确认高风险动作。
- 后台后端额外要求 `foreground_interrupt_ms = 0`；通用前台后端只记录干扰，不宣称后台成功。
