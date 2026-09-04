# iOS physical Release L1 baseline — 2026-08-12

## Outcome

`ios-physical-release-baseline-v2` 在 iOS 26.6 真机上连续 10/10 success。每次由 Kimi `kimi-k3` 先调用一次 `device_invoke(open_url, https://example.com)`，真机通过公开 `SFSafariViewController` 显示 Example Domain；关闭网页后同一 Pi session 恢复并返回 `{"request_accepted":true}`。

机器聚合器判定：success rate 100%，product success rate 100%，`meets_v0_repeat_count=true`、`meets_v0_success_rate=true`、`meets_v0_product_gate=true`。

## Fixed cohort

- App：本地开发证书签名 Release `ai.mobileagent.ios` 0.1.0
- 设备：iOS 26.6，`physical_device`，`dev_only=false`
- Carrier：WKWebView；agent：`@mariozechner/pi-agent-core@0.73.1`
- Model endpoint：`api.moonshot.cn`；model：`kimi-k3`
- Task：L1；cohort：`ios-physical-release-baseline-v2`
- Target：`https://example.com`
- Prompt 明确要求先收到唯一一次 `device_invoke` 的成功结果，再输出 JSON，避免模型跳过工具猜测成功。

## Aggregate

- Duration：min 12.080s；p50 12.393s；p95/max 16.923s
- Agent turns / model calls：每次 2；总计 20
- Tool calls：每次 1；总计 10
- Action failures / observation failures / crashes / manual takeovers：均为 0
- 每条 evidence 的 `target_reference` 均为 `https://example.com`
- Pending / interrupted：0

| run | attempt | duration | model | tool | status |
|---:|---:|---:|---:|---:|---|
| 1 | 5 | 12.931s | 2 | 1 | success |
| 2 | 6 | 12.538s | 2 | 1 | success |
| 3 | 7 | 12.154s | 2 | 1 | success |
| 4 | 8 | 12.090s | 2 | 1 | success |
| 5 | 9 | 12.080s | 2 | 1 | success |
| 6 | 10 | 14.324s | 2 | 1 | success |
| 7 | 11 | 12.393s | 2 | 1 | success |
| 8 | 12 | 16.923s | 2 | 1 | success |
| 9 | 13 | 14.208s | 2 | 1 | success |
| 10 | 14 | 12.083s | 2 | 1 | success |

## Evidence and limits

真机 JSONL 通过 CoreDevice `appDataContainer` 从 `Library/Application Support/benchmarks/runs.jsonl` 导出，再由仓库聚合器验证。安装升级后 Keychain 中的 endpoint/model/key 保持有效。

探索结果没有混入固定 cohort：一次镜像键盘破坏 benchmark 标记的 ad-hoc 失败、一次 `UIApplication.open` 拒绝 HTTPS 的 smoke v1、一次修复后的 smoke v2，以及 prompt 较弱导致模型未调用工具的 baseline v1 均原样保留。它们促成两项修复：`mobileagent://compose?prompt=` 只预填不自动执行；HTTP/HTTPS 改用公开 `SFSafariViewController`。

本基线证明 Release 真机上的在线 Pi loop、公开 URL 能力、前台覆盖后的 session 恢复和本地 recorder；不证明任意第三方 App UI 控制，也不等于锁屏、长时后台或 M1/X1/D1/W1 登录态证据。当前 `foreground_interrupt_ms=0` 未覆盖 App 内 sheet 的遮挡时间，因此不能据此声称“零前台干扰”。
