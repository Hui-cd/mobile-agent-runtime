# 安卓执行与聊天入口：开发验证

更新：2026-10-11。当前选择：先 Android、先飞书、先一个只读任务。

## 目标架构

用户聊天 → 消息接入与设备绑定 → 手机内 AgentSession → App 操作与观察 → 原聊天返回结果。

- REUSE：现有 Pi loop、Intent/Accessibility、完成证据、通知和本地审批。
- EXTEND：验收脚本增加显式模拟器模式，debug receiver 增加停止动作。
- ADD：飞书开发入口、单用户授权、持久消息去重、单任务串行。
- 后续 ADD：手机主动连接的任务传输与设备绑定，替换开发阶段 ADB 传递。

先使用模拟器验证，不需要购买手机。模拟器可以验证 APK、在线模型调用、系统 App、UI 操作和通讯流程；不能证明厂商省电、真实账号风控、真机权限持久性或长期后台可用。

## 当前可运行链路

```text
飞书私聊 → 官方 WebSocket SDK → 本机开发脚本
    → ADB debug receiver → 模拟器内 AgentSession / 在线模型
    → Intent + Accessibility 观察 → benchmark 判定 → 飞书回复
```

这是依赖电脑与 ADB 的开发链路。Agent 推理循环和设备工具仍在安卓内运行；它尚未实现正式产品的独立手机通讯链路。

当前只接受配置用户的私聊文字消息，支持“查看时钟”“状态”“停止”（或 `/clock`、`/status`、`/stop`）。不接受任意 App 任务，也不支持聊天内批准风险动作。没有配置飞书凭据时，不能算作飞书线上验证。

## 模拟器

本机已有 `Pixel_9`，Android SDK 位于 `~/Library/Android/sdk`。

```sh
~/Library/Android/sdk/emulator/emulator -avd Pixel_9 -no-snapshot-save
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME="$HOME/Library/Android/sdk" npm run android:build
npm run android:emulator:smoke -- --install
```

首次需要在 Agent 内配置模型、启用无障碍和通知，并保持亮屏解锁。已有配置不会因 APK 更新而清除。实际模型调用会使用该模拟器已经配置的模型账户。

模拟器模式使用 `emulator-clock-smoke-v1`，要求 `environment=emulator`、`dev_only=true`。默认 `android:smoke` 仍只接受真机；两种结果不会混为同一种验收。

## 飞书

1. 创建飞书自建应用并启用机器人。
2. 开通读取用户发送给机器人的单聊消息、机器人发送消息权限。
3. 配置“使用长连接接收事件”，订阅 `im.message.receive_v1`，发布到测试范围。
4. 将 `.env.feishu.example` 复制为 `.env.feishu`，只在本机填写 App ID、App Secret 和该应用下授权用户的 `open_id`。
5. 启动模拟器、安装最新 debug APK，然后运行 `npm run channel:feishu:dev`。
6. 在飞书私聊机器人发送“查看时钟”，检查接收回复与含页面标题的终态回复。

凭据文件和 `.channel-state/` 均被 Git 忽略。消息 ID 在执行前写入账本；已接收的消息在进程重启后也不重放。超过两分钟的事件拒绝，任务运行期间不排队另一个任务。失败或超时会请求停止，不自动重试。停止回复只表明请求已发出；实际取消须检查设备任务记录。

参考：[飞书官方 Node SDK](https://github.com/larksuite/node-sdk)。

## 验收与下一步

1. 同一 APK、模拟器、模型，回到桌面后重复打开时钟并验证最后观察。
2. 在任务运行中发送停止，要求记录为 `cancelled`。
3. 飞书实测接收、回传、重复事件和取消；本地合成事件测试只能证明控制逻辑。
4. 选择一个真实第三方 App 的只读任务，验证语义节点、点击、滚动和最终结果。
5. 添加手机独立通讯，再用真机验证后台、省电、锁屏和网络恢复。

当前时钟任务仅证明启动 App 和观察结果，不能据此声称已验证任意第三方 App 或点击输入能力。
