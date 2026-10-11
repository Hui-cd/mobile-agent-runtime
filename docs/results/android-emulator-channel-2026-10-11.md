# 安卓模拟器执行与通讯开发入口验证

日期：2026-10-11。证据：[任务记录](android-emulator-channel-2026-10-11.jsonl)。

## 实际设备运行

- Pixel_9 AVD，`emulator-5554`；Android 37 / ARM64 / 16 KB page size。
- 使用模拟器原有的加密模型配置，在线 `kimi-k3` / `api.moonshot.cn`；非模型 stub。
- 同一 debug APK SHA256：`9b6926876fa16d39780c7494641c6ebe4da5e91d7e6c2ca09664df1e50b0d3ad`。
- 任务：每次回到安卓桌面，手机内 Agent 打开 Google Clock，返回页面标题。
- `emulator-clock-smoke-v1` 连续 **10/10** 通过，耗时中位数 **8.694 秒**，范围 **7.415–10.241 秒**。
- 每次均有 `completion_evidence`，最后观察应用为 `com.google.android.deskclock`，`claim_follows_tool_evidence=true`。
- 延迟模型测试中发出 debug 停止指令，记录终态为 `cancelled` / `USER_CANCELLED`，耗时 2.057 秒，无设备工具调用。

最初旧安装版本返回结果但缺少完成证据，被验收脚本正确拒绝；更新 APK 后开始上述十次固定版本基线。

## 代码验证

- TypeScript check 与 Node build 通过。
- Node 测试 **50/50**；包括模拟器/真机证据隔离，以及通讯授权、去重、忙碌拒绝、停止、失败不重放。
- Android debug APK 构建通过；Android JVM 测试 **36/36**。
- 飞书入口脚本语法及 SDK 导入通过；缺少 `FEISHU_APP_ID` 时拒绝启动。

## 验证边界

时钟运行证明 App 启动与操作后观察；未验证任意第三方 App、点击输入或跨 App 长任务。停止实验只覆盖模型等待期间的取消。

通讯控制测试使用合成事件，没有飞书账号或应用凭据，因此无飞书线上接收/回传证据。当前开发入口通过电脑 ADB 触发手机内 Agent；正式手机独立传输、真实渠道取消、真机后台与厂商省电均未验证。

后续流程见[开发说明](../android-channel-development.md)。
