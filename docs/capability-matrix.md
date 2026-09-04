# Mobile Agent capability matrix

模型在 Android 与 iOS 上始终只看到 `device_observe / device_act / device_invoke`。Runtime 返回平台能力和明确错误，模型不能把 Android 能力假定到 iOS。

| 原语 / 能力 | Android | iOS |
|---|---|---|
| `observe` 当前外部 App | AccessibilityService 可读取包名、Activity 和语义节点 | 系统不向第三方 App 公开其他 App 的前台身份或 UI |
| `observe` 截图 | 用户授权的 AccessibilityService 截图 | 不能静默截取其他 App；当前未请求 ReplayKit 录屏 |
| `act` 点击/输入/滚动 | AccessibilityService | 不支持跨 App GUI 操作 |
| `invoke` 打开 App | 包名 + Intent/IntentSender | Universal Link 或已注册 URL Scheme；不能按 bundle id 任意启动 |
| `invoke` 地图/拨号/设置 | Android Intent | Apple Maps URL、`tel:`；当前 App Settings URL 需按 OS/设备探测，iOS 26.6 真机已观察到拒绝 |
| `invoke` 分享 | Android Sharesheet | `UIActivityViewController`，必须由用户选择目标 |
| `invoke` 自定义工作流 | Android Intent / App deep link | App Intent 与用户安装的 Shortcut |
| 后台执行 | Service-owned QuickJS + Foreground Service + 常驻通知；已测 5 分钟 Agent progress | `beginBackgroundTask` 仅提供有限收尾时间；模拟器 20s 闭环通过、60s expiration，完成后本地通知 |
| 熄屏/锁屏 | 模型/recorder 可继续；Accessibility 前台 GUI 工具返回 `SCREEN_NOT_INTERACTIVE` / `DEVICE_LOCKED`，解锁后下一任务可恢复 | 公共 GUI 跨 App 本就不支持；App Intent/Shortcut 是否可用由系统和目标能力决定 |
| 风险确认 | 支付、发送、删除、拨号、分享等 | 拨号、分享、运行 Shortcut |
| 登录态验证 | 只基于目标 App 可见 UI；微信支持高置信 signed-in/out 探针，其他 App 未验证时为 unknown | 公共 SDK 不能读取其他 App UI，保持 unknown/not-applicable |
| 模型 BYOK | 手机配置 HTTPS Chat Completions endpoint/model；Key 用 Android Keystore AES/GCM | 手机配置 HTTPS Chat Completions endpoint/model；Key 用 iOS Keychain，`AfterFirstUnlockThisDeviceOnly` |

## iOS 任务的正确组合方式

对于“导航到虹桥机场”，Agent 可直接调用 `device_invoke(navigate)`。

对于“在某个第三方 App 内执行一串操作”，iOS 端需要该 App 提供 App Intent、Shortcut Action、Universal Link 或 URL Scheme。如果目标 App 没有公开任何自动化入口，就必须让用户手动完成界面步骤；Runtime 会返回能力错误，不会伪造成功。

用户可以在 Shortcuts 中组合多个 App 的公开 Action，再让 Agent 调用：

```json
{
  "capability": "run_shortcut",
  "params": {
    "name": "比较附近餐厅优惠",
    "input": "虹桥附近"
  }
}
```

这条路径保留 iOS 的权限、确认和隐私边界，也比坐标脚本稳定。
