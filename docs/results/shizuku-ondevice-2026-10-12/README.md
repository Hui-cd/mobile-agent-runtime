# 手机内 Shizuku 后台执行实测（2026-10-12）

环境：Pixel_9 AVD，Android 17 / API 37，arm64，16 KB 页；无物理手机。Shizuku server 13.6.0，API/provider 13.1.5；debug APK，手机内 QuickJS / Pi / kimi-k3。电脑只安装/激活测试环境、发送 debug 目标和读取证据；任务的创建显示屏、观察、操作、下载、安装由手机内代码执行。没有 scrcpy 客户端/服务控制任务。手机自身无线配对启动尚未验收。

## 结果

| 任务 | 实际工具证据 | 主屏 |
|---|---|---|
| 秒表暂停 | display 6，Clock Pause → Start，最终 5:01.42 | Mobile Agent |
| 秒表启动再暂停 | display 7，Start → Pause → Start，最终 5:09.85 | 20 次采样均 Mobile Agent；有与独立屏同时 resumed 的采样 |
| 系统设置搜索并返回 | display 10，Settings → 搜索 App，input bluetooth，三条可见结果，back 返回 Settings | 主屏通过 input -d 0 HOME 切到桌面，20 次采样均 Launcher，包含与独立屏并行样本 |
| 独立屏截图和页签切换 | display 11，takeScreenshot 两次 screen_attached=true；Alarm → Stopwatch，截图为 5:09.85 暂停状态 | 主屏留在 Agent |
| 官方微信下载安装 | download_id=48，285284760/285284760 字节，installed=true，com.tencent.mm，8.0.79 | 未打开微信或登录；系统 PackageManager 独立确认安装 |
| 已下载 APK 再查询安装 | 已下载 ID 48 仍可查询，安装再次返回同包/版本 | 无登录操作；新任务使用持久下载 ID |

文字工具转录分别见本目录 JSON；图片块用占位文本替代，截图验收任务的原图另存 JPEG。主屏采样依据 dumpsys activity，独立安装检查见 wechat-package.txt。未直接记录登录凭据或模型密钥。

ad_hoc Recorder 的 `completed_unverified` 表示没有走预设 benchmark 自动裁决；本表结论来自工具返回和系统独立检查，不能仅凭模型最终回答。后台 metadata 修复前的早期 recorder backend 字段仍为 foreground，原始文件不改写；工具 display_id / execution_mode 是当时实际执行依据。

## 失败与修复

- 首次测试没有真正开启后台开关，前台 run 手动取消，不计后台通过。
- 首次 broker 用 Context.startActivity，系统拒绝 package=android / uid=2000；改为固定 callingPackage=com.android.shell 的 typed 系统 Binder 调用。
- 秒表动态读数使整树陈旧检查反复拒绝暂停；改为校验实际目标。目标自身变化/跨观察身份仍拒绝。实际任务出现一次目标变化拒绝，Agent 重新观察后完成；没有忽略错误假报成功。
- Shizuku server 的销毁回调是 shell UID，原本 owner-only 校验导致已释放屏幕的旧 UserService 进程残留；生命周期销毁单独允许自身 shell UID，业务操作仍 owner-only。早期五个本项目实验残留进程已清理。修复后设置任务结束，系统只剩主屏，ps 未找到 execution 进程。
- uiautomator dump 会暂时抑制/重连现有无障碍服务；实际运行阶段改用 dumpsys / screencap 只读取证，避免测量工具干扰控制服务。

## 尚未覆盖

Android 14–16/OEM 真机、同 App 主屏并行、旋转/相机/分享/选择器/系统安全页面、锁屏/熄屏、自动接管登录和验证码、真实 YouTube 上传、长期稳定性。Shizuku 正常启动也不表示所有 App 支持独立显示屏。

补验 Shizuku 不可用：停止官方 server 后发起后台 Clock 任务，启动前检查拒绝任务并显示中文服务断开提示，没有前台回退。官方 server 已重新启动；界面已清空测试聊天并留在后台模式。构建通过，Android JVM 52 项 / Node 56 项通过，git diff --check 通过。
