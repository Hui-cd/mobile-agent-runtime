# iOS L1 open_settings deterministic baseline — 2026-08-12

## 任务与判定

Debug-only deterministic model 通过真正的 `@mariozechner/pi-agent-core` 请求：

```json
{"name":"device_invoke","arguments":{"capability":"open_settings","params":{}}}
```

`IOSDeviceRuntime` 调用 `UIApplication.open(UIApplication.openSettingsURLString)`。只有系统 completion 返回 accepted、`last_invoke` 为 `app-settings:`、App 实际进入 `background`，并且 Pi 在 background 完成第二次 model 与 `agent_complete`，该 run 才写为 success。

## 聚合结果

环境：iPhone 17 Pro / iOS 26.5 simulator，WKWebView，backend=`ios_app_intent_public`，model=`deterministic-fixture`。

| 指标 | 结果 |
|---|---:|
| runs | 10 |
| success | 10/10 |
| duration min / median / p95 / max | 2125 / 2163 / 2251 / 2251 ms |
| foreground interrupt min / median / p95 / max | 963 / 987 / 1025 / 1025 ms |
| agent turns | 2/run |
| model calls | 2/run |
| tool calls | 1/run |
| crash / observation failure / action failure | 0 / 0 / 0 |
| manual takeover / login loss / permission loss | 0 / 0 / 0 |
| pending journal after run 10 | absent |

现有聚合器输出：

```text
success_rate=1
meets_v0_repeat_count=true
meets_v0_success_rate=true
product_eligible=0
meets_v0_product_gate=false
```

## 逐次证据

| Attempt | Run ID | Duration ms | Foreground ms | Evidence SHA-256 |
|---:|---|---:|---:|---|
| 1 | `E9D56369-CBFA-48F7-90F2-B5E63BEF49B2` | 2251 | 987 | `9c9411b9afbc4351a0b25380c803955bef8cb9690aa6380279219ed408c4463f` |
| 2 | `68FAE4CA-F5AA-4CC1-B7E9-4BD2A29BD13E` | 2204 | 985 | `dac96ae1cdbde67596e8902a8af129592fd804a15ace86b5d97603f952a36d77` |
| 3 | `AB3C2323-9BB1-43F2-A844-00D2A369BBC3` | 2146 | 979 | `3472cc6698a17f6a96572c2357bb46be48b27b04e1cbb1be0d41930d96037904` |
| 4 | `10B43E7C-4763-4CCB-936A-9D444F9EEB87` | 2191 | 1025 | `1114ab26a0ef6c1d9dfea11ae265fc5228e2543b9c932700ac04ed0873e4b06c` |
| 5 | `EE57FA16-9232-4C32-B419-787CB853AA6C` | 2134 | 963 | `832f6590afa859c95e36b0826f699b0b9224a8e2fcae9bfe39f91fa3b97a67ae` |
| 6 | `910B4E40-0B5C-42D2-9529-85C06F1714C3` | 2140 | 1000 | `dde09d42109f7ffdba6e4dd60a44bdb6fbb92fb91d6dcc27262e3cd6c3918d89` |
| 7 | `54E86F71-866A-433E-8BE9-7B6A9EA69FA4` | 2205 | 972 | `4b347abc698d5c126231facffd9f0548ee0812f5965dc392bd7e6dc2c9650ac2` |
| 8 | `5BEE7886-3C2D-480B-A087-D16902336163` | 2163 | 1020 | `6223bfb83675f3e944aea75433d75045f5f1f94a351c3bf90372a06b8a2435bb` |
| 9 | `B8F379E9-2205-478E-837A-4E5C02066142` | 2168 | 1001 | `025648a678329479e7978cfb59f5fe4e77a9c07912229030b0f28971dce6b35f` |
| 10 | `0D723E92-5FAA-43A4-ACE1-46213EEE1890` | 2125 | 988 | `1570bb03f74c70cb296664b62e5af538e0431e7782ea0e0012366c7f2817556f` |

每条 JSONL 均包含 `result.request_accepted=true`、`target_reference=app-settings:`、对象形式 evidence、`dev_only=true`，并明确 notes 为 deterministic Debug fixture。

## 限制

- 这是公共 URL capability 的 deterministic adapter/recorder 基线，不是在线 Kimi 证据。
- Simulator 不计产品门槛；仍需真机 + Release + BYOK 复验。
- Settings 会占用用户前台约 1 秒，因此不是 `foreground_interrupt_ms=0` 的后台 backend。Pi 的收尾在 Mobile Agent background 完成，不等于目标动作对用户无前台干扰。
- 只验证系统接受 `open_settings` 请求，不声称读取或控制 Settings 内部 UI。

## 独立进程恢复样本 R1

该样本与上面的 L1 10 次正常基线分组隔离，不参与其成功率：

1. PID `78919` 启动 `[BENCH:R1]`，确认 30,000ms deterministic model wait 与 pending journal 已存在后精确 `SIGKILL`。
2. pending run `84F9D8F6-ABC2-4678-AF87-FC1A8228CBD9` 在新进程启动时补写为 attempt 1、`failed`、`crash=true`、`RUN_INTERRUPTED/process`；notes 明确 counters 可能不完整。
3. 新 PID `78991` 不重放 attempt 1，而是创建 attempt 2 `07F39B56-7CD2-4588-A5E2-1FECA450EBE5`，以 2178ms / 2 model / 1 tool 完成新的 `open_settings` success。
4. 两条 run 都有唯一终态，最终 pending journal 不存在。

这证明 iOS deterministic fixture/recorder 的 App 进程中断恢复语义；在线 Pi transcript、URLSession cancellation 与真机系统回收仍需另测。
