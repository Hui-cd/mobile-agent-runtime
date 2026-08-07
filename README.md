# Mobile Agent Runtime

一个同时运行在 Android 和 iOS 手机上的 Agent App，并保留桌面 MCP/ADB 开发 Runtime。两端模型都只看到三个高杠杆原语：

- `device_observe`：读取前台 App、语义 UI Tree，必要时附截图。
- `device_act`：点击、输入、滚动、滑动、返回和回到桌面。
- `device_invoke`：直接打开 App、URL、设置、导航、拨号或分享。

## Android App 已实现

- 手机内聊天界面：输入任务、查看步骤和最终结果。
- BYOK：当前直连 Kimi 中国区 `kimi-k3`；Key 通过 Android Keystore AES/GCM 加密，只保存在设备本地。
- 手机内 Agent 工具循环：最近对话 + 当前设备上下文 + 切入 Agent 前最后一个外部 App 上下文 → Kimi 请求 → Runtime 执行 → 新观察继续回传。
- AccessibilityService：语义 UI 观察、点击、长按、Unicode 输入、滚动、滑动、返回、Home 和截图。
- Android 系统调用：优先使用 Intent/IntentSender 打开 App 和系统能力，避免无意义的逐步点击。
- 后台任务：模型请求和工具执行通过前台服务继续运行，并显示可停止通知。
- 风险确认：拨号、分享，以及支付、购买、发送、删除等动作会先回到 App 请求用户确认。

已在 Pixel 9 / Android 37 模拟器真实验证：从 Mobile Agent 输入 `Open the Clock app`，Kimi 返回 `device_invoke(open_app)`，由 `ai.mobileagent` 启动系统时钟；Agent 随后读取时钟页面并在手机聊天中报告结果。

## iOS App 已实现

- 原生 SwiftUI 手机内聊天界面，与 Android 使用相同的 context + tool-call 循环。
- Kimi `kimi-k3` BYOK；Key 存入 iOS Keychain，并限制为本机首次解锁后可用。
- `device_observe / device_act / device_invoke` 三原语保持稳定，`observe` 返回明确的 iOS 能力矩阵。
- Universal Link、URL Scheme、Apple Maps、拨号、当前 App 设置和系统分享面板。
- App Intent + App Shortcut，可从 Shortcuts、Siri 和 Spotlight 把任务发送给 Mobile Agent。
- `run_shortcut` 能调用用户创建的跨 App 工作流；拨号、分享和 Shortcut 执行前要求确认。
- 用户发起的任务进入后台后使用有限 background task 完成收尾，并通过本地通知报告结果。

已在 iPhone 17 Pro / iOS 26.5 模拟器真实验证：Xcode Debug 构建、安装和启动成功；测试 Key 可写入 Keychain；App 把设备 Context 发给 Kimi，真实请求返回 `ios`；通知权限延迟到用户首次真正发起任务时申请。

iOS 不提供 Android AccessibilityService 等价的跨 App UI 读取和点击 API。iOS 版不会伪造这项能力，而是使用目标 App 公开的 App Intent、Shortcut、Universal Link 或 URL Scheme。详细差异见 [能力矩阵](docs/capability-matrix.md)。

## 架构

```text
Android / iOS App
  聊天 UI
     │
     ├── Kimi Chat Completions（用户自己的 Key）
     │       ↕ tool_calls
     └── On-device Runtime
             ├── Android ── AccessibilityService + Intent/IntentSender
             └── iOS ────── App Intents + Shortcuts + URL/Share APIs

Codex / 其他 MCP Host ── stdio MCP ── Desktop Runtime ── ADB / UIAutomator
其他模型 API ─────────── BYOK HTTP Gateway ┘
```

## 构建和安装 Android App

要求 JDK 17+、Android SDK 37。可以直接在 Android Studio 打开 `apps/android`，也可以运行：

```bash
cd /Users/naitang/WorkSpace/mobile-agent-runtime
npm run android:build
$HOME/Library/Android/sdk/platform-tools/adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
```

首次使用：

1. 打开 `Mobile Agent`，输入 Kimi API Key 并保存。
2. 点击“去设置”，在 Android 无障碍设置中启用 `Mobile Agent device control`。
3. 回到 App，看到“Kimi 已连接”和“设备控制已启用”后即可发任务。

中国区 Kimi Key 使用 `https://api.moonshot.cn/v1`；当前默认模型是 `kimi-k3`。源码和 APK 中不包含测试 Key。

## 构建 iOS App

要求 Xcode 26+。工程位于 `apps/ios/MobileAgentIOS.xcodeproj`：

```bash
cd /Users/naitang/WorkSpace/mobile-agent-runtime
npm run ios:typecheck
npm run ios:build
```

首次使用 Xcode 的机器需要先由用户本人阅读并接受 Apple/Xcode License。真机侧载还需要在 Xcode Signing & Capabilities 中选择自己的 Development Team。

如果刚下载 Simulator Runtime 后 `simctl list runtimes` 仍为空，可运行：

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcrun simctl runtime scan-and-mount
```

## 桌面 MCP Runtime

要求 Node.js 22+、Android SDK Platform Tools，以及已授权的 Android 真机或模拟器。

```bash
cd /Users/naitang/WorkSpace/mobile-agent-runtime
npm install
npm test
npm run build
codex mcp add mobile-device -- node /Users/naitang/WorkSpace/mobile-agent-runtime/dist/server.js
```

Codex 重新加载配置后只需要看到 `device.observe`、`device.act`、`device.invoke` 三个工具。

## 桌面 BYOK Gateway

桌面 Gateway 仍支持 OpenAI Responses 和 OpenAI-compatible Chat API：

```bash
export MOBILE_AGENT_PROVIDER=openai-compatible-chat
export MOBILE_AGENT_API_KEY='...'
export MOBILE_AGENT_MODEL='provider-model-id'
export MOBILE_AGENT_BASE_URL='https://provider.example/v1'
npm run gateway
```

默认只监听 `127.0.0.1`；设备写操作需要请求显式设置 `allow_device_actions=true`。监听非本机地址时必须同时配置 `MOBILE_AGENT_GATEWAY_TOKEN`。

## 能力边界

- Agent 操作其他 App 的 GUI 时，目标 App 必须成为前台，因此这部分无法做到完全“不打扰当前屏幕”；网络推理、比较和等待确认可以在后台继续。
- AccessibilityService 只能读取 App 暴露的无障碍节点；Canvas、游戏和部分 WebView 需要截图视觉兜底，仍可能受登录、验证码、风控和 App 版本影响。
- Android 沙箱仍然生效，Agent 不能读取其他 App 的私有数据库。
- 目前是内测侧载版本。若进入应用商店或大规模分发，需要单独评估无障碍权限、隐私披露、审计和各商店政策。
- iOS 的后台时间由系统决定，不能像 Android Foreground Service 一样无限常驻；长任务需要拆分、后台 URLSession 或系统调度。

关键判断、版本差异和已踩过的坑见 [docs/decisions.md](docs/decisions.md)。
