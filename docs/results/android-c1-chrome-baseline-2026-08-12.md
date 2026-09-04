# Android Chrome C1 staged baseline — 2026-08-12

## Outcome

Android 37 / 16KB ARM64 emulator 上，固定 Debug APK、`quickjs-kt@1.0.12`、`api.moonshot.cn / kimi-k3`、Chrome `145.0.7632.218`、Bing 查询和同一 prompt，`c1-staged-baseline-v2` 连续 10 次全部 success。每条 run 等待手机端 JSONL 终态后才启动下一条，没有并发、重试或删除失败样本。

Smoke 与 baseline 分开：`c1-staged-smoke-v2` 先暴露 Google 在当前 emulator 网络不可达，随后固定到已证实可达的 Bing + `site:cnblogs.com Android AccessibilityService`。有效 smoke attempt 25 为 43.504s、5 model calls、4 tool calls、0 failure；日志证明前段请求为 `text`，完成搜索、点击和滚动后切换为 `json_schema`，最终通过手机端 adjudicator v2。

## Aggregate

| Metric | Result |
|---|---:|
| success | 10 / 10 (100%) |
| duration | min 34.466s / p50 43.903s / p95 51.657s / max 51.657s |
| foreground interruption | min 28.377s / p50 38.421s / p95 44.842s / max 44.842s |
| agent turns | 47 total; p50/p95 5/5 |
| model calls | 47 total; p50/p95 5/5 |
| tool calls | 37 total; p50/p95 4/4 |
| manual takeovers | 0 |
| crashes / observation failures / action failures | 0 / 0 / 0 |
| permission losses / invalidated successes | 0 / 0 |
| structured items | 5 / run |
| detail scroll | true / run |
| pending journal after run 10 | absent |
| product eligible | 0 / 10 |

## Runs

| Run | Attempt | Status | Duration ms | Model | Tools | Failures | Foreground ms |
|---:|---:|---|---:|---:|---:|---:|---:|
| 1 | 26 | success | 45,763 | 4 | 3 | 0 | 39,657 |
| 2 | 27 | success | 38,912 | 5 | 4 | 0 | 33,360 |
| 3 | 28 | success | 43,903 | 5 | 4 | 0 | 38,421 |
| 4 | 29 | success | 51,657 | 5 | 4 | 0 | 44,842 |
| 5 | 30 | success | 48,393 | 5 | 4 | 0 | 42,666 |
| 6 | 31 | success | 34,466 | 4 | 3 | 0 | 28,377 |
| 7 | 32 | success | 41,999 | 5 | 4 | 0 | 34,746 |
| 8 | 33 | success | 48,107 | 5 | 4 | 0 | 42,978 |
| 9 | 34 | success | 49,106 | 5 | 4 | 0 | 41,177 |
| 10 | 35 | success | 40,054 | 4 | 3 | 0 | 33,507 |

## Evidence and limits

每条 run 都有 `com.android.chrome`、成功搜索 URL、结果点击、详情滚动、滚动后至少 8 个可见文本节点、无阻断网络错误，并返回恰好 5 个 item、非空 detail 与 `scrolled=true`。evidence 只保存 hash、host、计数和结构化元数据，不复制完整 UI tree 或截图。

这是 emulator / Debug / Accessibility 前台 backend，`dev_only=true`、`product_eligible=0`、`meets_v0_product_gate=false`。它证明真实主流 App 搜索→阅读→详情→滚动→结构化结果在当前 Android 开发环境下可重复，不证明物理 Android、OEM Computer Control、锁屏 GUI 执行、登录 App 或 Play 合规性。
