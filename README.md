# Mobile Agent Runtime

一个直接运行在手机上的开源 Agent Runtime。Agent 循环、会话、工具执行与安全控制位于设备端；模型推理可以使用用户自己的远程 API。

> 当前状态：**Technical Alpha**。Android 核心链路已在模拟器验证，尚未完成 Android 真机与目标 App 的产品级验证。请勿用于支付、发送、删除等高风险任务。

## 它解决什么问题

用户描述目标后，Agent 通过三个稳定工具完成“判断 → 调用 App → 观察结果 → 继续或结束”的闭环：

- `device_observe`：读取当前 App 与语义 UI；
- `device_invoke`：通过 Intent、URL 或系统能力直接调用 App；
- `device_act`：在必须操作界面时点击、输入、滚动或返回。

Android 优先使用 Intent，再使用 Accessibility；每次动作后重新观察设备，不能仅凭模型自报判定完成。运行中的任务有持续通知和停止入口，风险动作会要求用户确认。

## 当前能力

| 平台 | 当前能力 | 验证边界 |
|---|---|---|
| Android | 手机内 Pi agent loop、Intent、Accessibility、前台服务、BYOK、安全存储 | Clock/Chrome 模拟器基线已通过；尚无 Android 真机产品基线 |
| iOS | 手机内 Pi agent loop、App Intents、Shortcuts、URL/Share API | 公开 URL 真机基线已通过；公共 API 不支持任意第三方 App GUI 控制 |
| Desktop | MCP Runtime、ADB/UIAutomator、可选 BYOK Gateway | 开发与测试工具，不属于手机端产品运行链路 |

## 在 Android 上运行

要求：Node.js 22+、JDK 17+、Android 17 Preview SDK（API 37）、Android 11+ 手机或模拟器。API 37 目前属于 preview channel；可在 Android Studio SDK Manager 中安装。真机还需开启开发者选项和 USB 调试。

```bash
git clone https://github.com/Hui-cd/mobile-agent-runtime.git
cd mobile-agent-runtime
npm ci
npm run android:build
adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
```

首次打开后：

1. 配置一个支持 OpenAI-compatible Chat Completions 与 Tool Calling 的 HTTPS endpoint、模型和 API Key；默认值是 Kimi 中国区 `https://api.moonshot.cn/v1` / `kimi-k3`。
2. 按提示在 Android 无障碍设置中启用 `Mobile Agent device control`。
3. 先用低风险任务验证，例如“打开时钟，进入闹钟页面，告诉我有哪些闹钟”。

API Key 通过 Android Keystore 加密，只保存在设备上。界面控制会让目标 App 出现在前台；网络推理可在后台继续。

## 验证源码

```bash
npm ci
npm test
npm run build
npm run android:test
npm run android:build
```

构建成功只证明源码可编译。真实任务是否完成，仍需设备观察证据和重复 benchmark。

## 其他入口

iOS 工程位于 `apps/ios/MobileAgentIOS.xcodeproj`。使用 Xcode 26+ 打开工程，为自己的开发者账号设置唯一 Bundle Identifier 与 Development Team 后构建。iOS 能力受系统公共 API 限制，详见[能力矩阵](docs/capability-matrix.md)。

桌面 MCP Runtime：

```bash
npm ci
npm run build
codex mcp add mobile-device -- node "$(pwd)/dist/server.js"
```

可选 BYOK Gateway 的环境变量见 [.env.example](.env.example)。Gateway 默认只监听 `127.0.0.1`，设备写操作还需显式设置 `allow_device_actions=true`。

## 设计与证据

- [目标与验收边界](docs/goal-v0.md)
- [Android Agent 设计](docs/android-agent-design.md)
- [产品与交互设计](docs/android-agent-product-and-interaction.md)
- [平台能力矩阵](docs/capability-matrix.md)
- [Benchmark 定义](docs/benchmark-v0.md)
- [关键架构决策](docs/adr/)

Benchmark 可能包含原始任务和结构化结果。只使用授权测试账号，并将导出文件视为敏感数据。

## 参与项目

提交问题或代码前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。安全漏洞不要提交公开 Issue，请按 [SECURITY.md](SECURITY.md) 私下报告。

本项目采用 [Apache License 2.0](LICENSE)。`research/` 中引用或下载的上游项目仍遵循各自许可证，不能因本仓库的许可证而改变。
