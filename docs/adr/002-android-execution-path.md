# ADR-002：Android 优先 Intent，必要时使用 Accessibility

> 状态：Accepted
> 日期：2026-09-04

## 背景

Android 任务既可能有系统提供的直接入口，也可能只能通过目标应用的前台界面完成。全部使用逐步点击会更慢、更脆弱；只使用 Intent 又无法覆盖应用内部流程。

## 决定

Android Runtime 按以下顺序选择执行路径：

```text
公开系统能力可完成 → device_invoke → Intent / IntentSender
需要读取当前状态   → device_observe → Accessibility Observation
只能操作前台界面   → device_act → Accessibility semantic action
```

`device_invoke` 和 `device_act` 执行后都重新观察设备。只有观察结果支持预期状态，Agent 才能继续或报告完成。

Android 33 及以上打开应用使用 `getLaunchIntentSenderForPackage`；Android 30–32 使用受 package visibility 约束的 launcher Intent 兼容路径。

## 结果

- 可用系统能力时减少脆弱的界面步骤；
- 必须操作界面时优先文本、content description、resource ID 等语义目标，坐标只作受控降级；
- 普通安装的 Accessibility 路径会占用用户前台，不宣称通用后台控制；
- 目标应用、系统版本和 OEM 差异仍可能导致失败，必须通过能力发现和真实设备验证处理。

## 约束

- 屏幕关闭、设备锁定或 Accessibility 未连接时快速失败；
- Intent 被系统接受只证明请求已发出，不证明目标应用内部业务完成；
- 不把 ADB 或调试能力暴露为产品 Tool。
