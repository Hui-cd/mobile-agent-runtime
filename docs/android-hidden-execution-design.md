# Android 后台执行：Shizuku + 独立显示屏

状态：已接入 Android 应用，并在 Android 17 / API 37 arm64 模拟器上通过手机内 Agent 的秒表操作、系统设置搜索/返回、微信下载/安装。没有物理真机或登录/验证码接管证据。测试环境用电脑 ADB 安装并启动 Shizuku；运行任务不依赖 scrcpy 或电脑控制进程。手机自身无线调试激活仍需实测。

## 目标架构

保留（REUSE）QuickJS / Pi 推理、三个工具名、现有模型配置、审批策略和任务通知。扩展（EXTEND）Accessibility 的页面、目标匹配、截图、手势，使每次调用绑定明确的 displayId。新增（ADD）Shizuku UserService、显示屏生命周期和应用下载工具。替换（REPLACE）未接入的 Shizuku 骨架；删除（DELETE）始终返回不可用的旧实现及其骨架测试。

```text
用户目标 → 手机内 QuickJS Agent → AndroidDeviceRuntime
                              ├─ 主屏模式：现有 Accessibility / Intent
                              └─ 后台模式：HiddenExecutionSession
                                  ├─ Shizuku ExecutionBroker：创建显示屏 / 启动 Intent / 返回键 / 安装
                                  └─ Accessibility：displayId 对应的树 / 截图 / 语义操作 / 手势
```

后台指目标应用在独立显示屏上渲染和运行。主屏可继续使用；通知保留进度和停止入口。当前要求 Android 14+、屏幕点亮且设备解锁、无障碍已启用、Shizuku 已运行并授权。只验证 API 37 模拟器，不保证所有 Android 14+ 厂商设备兼容。未使用 ALWAYS_UNLOCKED。

## 工具与生命周期

- `device_observe`：从指定屏幕的应用窗口取树，`takeScreenshot(displayId)` 取图，返回 display_id / execution_mode。
- `device_act`：语义节点来自指定屏幕；手势绑定 displayId；尺寸取该屏；后台 back/home 交给 broker。目标检查拒绝已改变的操作目标，但无关的动态读数变化不再阻止暂停秒表。
- `device_invoke`：普通应用和深链通过显式显示屏选项启动；不由模型传 shell 命令。broker 校验调用者 UID，清理 Binder 身份后执行白名单操作。
- 每任务绑定非 daemon UserService，同主屏尺寸/dpi 的 ImageReader + trusted VirtualDisplay；图像持续消费，防止缓冲区堵塞。终态和取消释放显示屏并解绑服务；Shizuku 生命周期销毁允许服务自身 shell 身份，业务操作仍只允许调用应用 UID。
- 后台模式缺授权/服务不可用时明确拒绝，不静默切主屏。当前任务服务死亡后不创建新屏继续旧动作。

## 通用应用下载与安装

三个能力属于现有 `device_invoke`，不增加模型工具数量：

| capability | params | 返回 |
|---|---|---|
| download_app | url：发行方 HTTPS APK | download_id、状态、进度 |
| download_status | download_id | pending / downloading / paused / failed / downloaded、字节数 |
| install_app | download_id、package | 包名、版本、installed=true；否则明确错误 |

DownloadManager 下载到应用自己的外部文件目录，持久保存本应用拥有的下载 ID。安装只接受该目录已完成的 APK，先解析并匹配预期包名，再通过文件描述符传给 Shizuku broker。broker 固定调用系统包管理器的流式安装入口，大小限制 500 MB；没有任意命令接口。`installed=true` 以安装结果及 PackageManager 再读取为依据。下载进度记录的 installed=false 不代表设备上没有旧版本，只表示此下载尚未完成安装确认。

HTTPS 和包名校验不等于发行方签名鉴定；下载 URL 需来自用户指定来源或已核实的发行方。后续应增加签名/哈希来源与更新策略。Google Play 需要登录时停下；独立 APK 下载不需要 Google 账号。微信实测来源为 [发行方官网](https://weixin.qq.com/) 中的 Android arm64 下载链接，安装包 285284760 字节，包名 com.tencent.mm，版本 8.0.79。

## 已验证与剩余边界

[实测证据](results/shizuku-ondevice-2026-10-12/README.md)包含完整工具文字转录、主屏采样和安装系统信息。早期失败与修复保留在说明中。ad_hoc 的 recorder 状态 `completed_unverified` 仅表示未经过预设 benchmark 自动裁决；不能仅凭模型最终文本宣称验收通过，本轮另外检查实际工具返回与系统状态。

尚未实现：登录/验证码人工接管、同 task 主屏迁移后恢复、密码/验证码输入流程、FLAG_SECURE 自动分类、任意 App 跨屏跳转阻止、物理真机验证、锁屏/熄屏持续执行。后台密码节点输入已明确拒绝；普通验证码框尚无可靠分类，当前系统提示要求遇到登录/验证码停止，不能当作完整的原生接管策略。

同一 App 已在主屏使用时，系统可能把已有 singleTask 移到独立屏；当前不要同时让用户与 Agent 使用同一个目标 App。OEM/系统弹窗也可能自行跳主屏，尚无全应用覆盖保证。旋转、相机、媒体选择器、支付和长任务需逐项验收。

## 使用

1. 安装并启动 [Shizuku](https://shizuku.rikka.app/guide/setup/)，授权 Mobile Agent。
2. Mobile Agent 菜单选择“开启后台执行（Shizuku）”。首次弹授权后再次选择；菜单显示当前模式，底部说明主屏可继续使用。
3. 发出任务，如“打开时钟，启动秒表再暂停”。可从通知或应用停止。

模型密钥和无障碍设置沿用已有配置。Shizuku 重启/权限失效后需要重新启动与授权；当前不自动重放中断任务。

机制调研与前次外部 scrcpy 实验见 [研究记录](../research/android-hidden-execution/README.md)。实现参考 [Shizuku UserService](https://github.com/RikkaApps/Shizuku-API#userservice) 和 [scrcpy 独立显示屏机制](https://github.com/Genymobile/scrcpy/blob/v5.0.1/doc/virtual-display.md)，产品运行不包含 scrcpy。
