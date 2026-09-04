# Mobile runtime feasibility — 2026-08-11

## 结论

目标需要拆成三条彼此独立的验证线：Pi runtime、Android 执行后端、iOS 执行后端。Android Pi runtime 已证明 5 分钟 Activity-background 持续推进，iOS 仍只有前台/有限后台基础；不打扰用户前台的独立 App 执行和真实主流 App 兼容性仍未证明。

## Pi runtime

审计开始时，Android `AgentEngine.kt` 与 iOS `AgentEngine.swift` 各自实现 OpenAI-compatible tool-call 循环，仓库没有 Pi package。现在两端用户任务入口均已切换到原版 `pi-agent-core`；Android 由 Service-owned QuickJS 承载，iOS 由 WKWebView 承载，Kotlin/Swift 只实现 native-backed model transport、审批和设备工具。

`@mariozechner/pi-agent-core` 0.73.1 的 package metadata 要求 Node 20+，但 core bundle 本身没有直接 Node import，并支持自定义 `streamFn`。直接打包完整 `pi-ai` 会遍历不需要的 provider SDK，因此移动 bundle 保留原版 agent-core、替换 provider transport。Android 37 与 iOS 26.5 模拟器已经分别证明 native model/tool bridge 的完整双轮闭环。

最初选择系统 WebView/WKWebView，是因为无需引入另一套 UI/runtime 框架并有可信 origin bridge。Android 5 分钟 attempt 27 证明 WebView 能存活且原生网络能完成，但后台 JS 不推进，必须恢复 Activity；因此 Android 迁到 `quickjs-kt@1.0.12`。它直接运行任意 JS、提供 suspend binding/interrupt/memory limit，16KB ARM64 emulator 已完成 Pi fixture 和 309.534 秒在线闭环。Zipline 更成熟但主要面向 Kotlin/JS module 与 typed services；Hermes 更贴近 React Native/JSI。此次迁移只解决 Agent runtime carrier，不改变 Accessibility 工具仍占前台的事实。

依据：

- [Pi agent-core](https://github.com/badlogic/pi-mono/tree/main/packages/agent)
- [Pi SDK embedding documentation](https://github.com/badlogic/pi-mono/blob/main/packages/coding-agent/docs/sdk.md)
- [QuickJS](https://bellard.org/quickjs/)
- [quickjs-kt](https://github.com/dokar3/quickjs-kt)
- [Zipline](https://github.com/cashapp/zipline)
- [Hermes](https://github.com/facebook/hermes)

## Android

### 1. Android Computer Control：最贴近目标，但不是普通 App 能力

Android 37 的 Computer Control 能在安全的后台虚拟显示中启动目标 App、截图并注入点击、滑动和文字输入。这正是“不持续占用用户前台”的原生路线。

限制同样是产品级的：

- 仅面向 OEM 预装 AI assistant。
- 要求 privileged `ACCESS_COMPUTER_CONTROL` 权限和当前 ASSISTANT 角色。
- 设备必须预装 Computer Control extension 与 VirtualDeviceManager 支持。
- 目标 App 受 allowlist/denylist 和用户逐 App 同意约束。
- 单次 session 最多请求 6 个目标 App，系统同时只允许一个 active session。

本机 Android 37 SDK 的普通 `android.jar` 不包含 Computer Control class 或该权限 stub，只在平台配置里出现 super-agent 配置。这进一步说明当前项目不能仅添加普通 Gradle 依赖就获得该能力。

依据：[Android Computer Control](https://developer.android.com/ai/computer-control)

### 2. Accessibility + Intent：可分发基线，但会占用前台

这是当前已实现路线。它适合先建立真实 App benchmark 和 agent/tool 可靠性基线，但不能满足“独立、不干扰前台”。Accessibility 还受无障碍节点质量、截图权限、Canvas/WebView、验证码和商店政策影响。

### 3. App virtualization：研究备选，不作为默认产品依赖

VirtualApp/VMOS 类方案可能提供独立 App 数据与运行空间，但通常涉及 API hooking、兼容性、商业授权、目标 App 风控和更大的安全面。只有在 Computer Control OEM 路线不可获得且产品明确接受这些成本时再做 spike。

## iOS

### 1. 公共 SDK 路线

App Intents 可以让**目标 App 自己公开的动作**在后台或 extension 中运行；Shortcut 可以组合多个已公开动作。它不能让 Mobile Agent 任意读取、截图或点击其他 App 的现有 UI。

Apple 的 Virtualization framework 面向 Mac 上的 macOS/Linux VM，不是 iPhone 上运行 iOS App 的公共容器能力。

依据：

- [Apple App Intents](https://developer.apple.com/documentation/appintents)
- [Apple Virtualization](https://developer.apple.com/documentation/virtualization)

### 2. LiveContainer 研究路线

LiveContainer 是“在宿主进程内加载 iOS App”的 launcher，不是 Apple 公共 hypervisor。它支持侧载 App、独立数据 container 与多窗口，因而值得作为 iOS 研究原型的现成基础，而不是从零写 VX。

必须先接受并验证：

- AGPL-3.0 对集成和分发的影响。
- 依赖 AltStore/SideStore 或其他侧载链路，不能等同 App Store 产品。
- 宿主可访问 container 内登录凭据与数据，安全责任显著增加。
- 部分 App、entitlement、JIT、推送、Keychain 和反篡改能力不兼容。
- 美团、小红书、抖音、微信的具体版本必须逐个实测，不能从“多数 App 可运行”外推。

依据：[LiveContainer repository](https://github.com/LiveContainer/LiveContainer)

## 推荐顺序

1. Android 补 QuickJS active cancel、进程回收、Doze 与真机；iOS 用可用 BYOK 完成在线 smoke、前后台、取消和 WebContent 回收。
2. 用 Android Accessibility 跑真实只读任务，建立 benchmark 基线。
3. 同步准备 Android OEM Computer Control 合作/设备准入验证。
4. iOS 公共产品继续使用 App Intents；LiveContainer 单独建 research target，不与公开产品代码和安全边界混合。
