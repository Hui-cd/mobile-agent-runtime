# Mobile Agent Runtime：当前进展与目标

更新：2026-10-12。当前阶段：Technical Alpha。

当前只推进 Android。先稳定通用工具闭环，再按实际失败逐步增量，之后接入飞书、微信。仓库已有 iOS 实现保留，新的 iOS 研究改动不属于本次发布。

## 目标与架构

用户描述目标 → 手机内 Pi Agent → device_observe / device_invoke / device_act → Android Intent / Accessibility → 操作后观察 → 返回结果或说明阻塞。

产品运行不依赖电脑、ADB 或云手机；模型推理可使用远程 API。开发期模拟器与 ADB 不算独立手机产品验收。v0 以只读和低风险任务为主，风险动作要求用户确认。

## 已有实现和验证

- 手机内 Agent loop、应用发现、语义目标消歧、观察 ID 绑定、页面状态、原生审批、停止与完成证据。
- 模拟器使用在线模型，时钟启动固定基线 10/10；通用任务在时钟和设置通过，模型自己选择调用和目标，未预编排点击序列。
- 实际日常页面入口完成“新任务 → 示例 → 发送 → 切换时钟页签 → 返回中文结果”；修复冷启动隐藏会话及旧 benchmark 格式污染普通任务的问题。
- 简化配置与日常页面；空密钥保留原配置，所有任务入口检查权限，失败提示中文处理办法。
- 本地 Node 测试 56/56、Android JVM 测试 52/52及 debug APK 构建通过。这些检查不等于真机产品验收。
- 飞书开发入口支持授权私聊的查看时钟、状态、停止，通过电脑 ADB 触发手机内 Agent；未配置账号，尚无线上收发证据。
- 已接入手机内 Shizuku UserService + 独立显示屏，模拟器验证秒表启动/暂停和截图、系统设置搜索/返回；主屏可继续使用。下载和安装工具实际安装官方微信 com.tencent.mm 8.0.79。
- Shizuku 本次通过电脑 ADB 激活；任务执行不依赖 scrcpy 或电脑控制进程。手机自身无线启动、物理真机和登录/验证码接管尚未验收。

## 尚未完成与增量顺序

1. 用一个第三方 App 的只读搜索任务验证迁移，围绕实际缺口补键盘提交、条件等待与恢复；不铺开专用 App 流程。
2. 验证滚动、动态重排、视觉与长流程能力；截图请求转换单测不能证明视觉控制。
3. 真机验证权限持久性、锁屏、省电、后台与网络恢复。
4. 完成新用户安装和首次配置验收，交付可安装预览版。
5. 接手机独立通讯传输，再验证飞书，随后评估微信入口。

## 证据与说明

- [安卓通用工具契约](docs/android-general-tools.md)
- [通用工具实测](docs/results/android-general-tools-2026-10-11.md)
- [使用体验审查与实际页面验证](docs/android-usability-review.md)
- [模拟器与飞书开发入口](docs/android-channel-development.md)
- [Android 真机 smoke](docs/android-device-smoke.md)
- [Android 后台执行设计与使用](docs/android-hidden-execution-design.md)
- [Shizuku 手机内执行实测](docs/results/shizuku-ondevice-2026-10-12/README.md)

每次增量保留已有基线，明确区分实现、模拟器证据、真机证据和未验证设计。
