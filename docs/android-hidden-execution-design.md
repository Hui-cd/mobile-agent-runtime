# Android 隐藏执行设计：Shizuku trusted VirtualDisplay backend

> 状态：设计 + 编译骨架，**无真机证据**（2026-09-20）。
>
> 规则：本 backend 与前台 Accessibility backend 分 cohort、分证据统计；未实证能力不得写入产品口径。

## 目标

在**不占用用户前台屏幕**的条件下执行目标 App 任务：目标 App 渲染在由 Shizuku（shell 级特权服务）创建的 trusted VirtualDisplay 上，Agent 通过截图与输入注入观察和操作该虚拟显示。

产品任务运行不得依赖 Mac/PC 或云手机。Shizuku 激活只接受**手机自身可完成**的路径（无线调试配对，由用户在手机上输入配对码），不接受需要电脑 ADB 常驻的激活方式；激活失效按"权限与会话丢失"场景处理，不静默降级。

## 执行链路

```text
AgentSession（QuickJS Pi loop，不变）
  → AndroidDeviceRuntime（工具面 device_observe / device_act / device_invoke 不变）
  → BackendSelector（前台 Accessibility / 隐藏 VirtualDisplay，策略选择）
  → ShizukuVirtualDisplayBackend
      · Shizuku binder 连接（rikka.shizuku，shell uid 2000）
      · DisplayManager(Shell).createVirtualDisplay(VIRTUAL_DISPLAY_FLAG_TRUSTED)
      · ActivityTaskManager.startActivityOnDisplay → 目标 App 渲染到虚拟显示
      · SurfaceControl.screenshot(displayId) → 观察截图
      · InputManager.injectInputEvent → 点击/滑动/输入
      · Accessibility 语义树（可选叠加；缺失时按不可读页面降级 fail-closed）
  → 证据（JSONL + SHA-256，cohort = android-virtualdisplay-*）
```

工具名、参数 Schema、错误码语义与前台 backend 完全一致；差异只在 capabilities 报告与 adapter 实现。

## 能力边界（能力只在 adapter/capability 中体现）

| 场景 | 行为 |
|---|---|
| Shizuku 未运行/未授权 | `SHIZUKU_UNAVAILABLE`，提示手机内无线调试激活；任务不启动 |
| 前台被占用（用户正在用手机） | 隐藏 backend 可执行；前台 backend 返回占用说明 |
| 熄屏/锁屏 | 虚拟显示上的任务可继续推理与记账；**不绕过锁屏**：目标 App 若显示 keyguard/凭据页面 → `DEVICE_LOCKED` fail-closed |
| `FLAG_SECURE` 窗口（支付、密码、验证码、DRM） | 截图返回黑色或注入被拒 → `FAIL_CLOSED_SECURE_WINDOW`，请求用户接管；不得把黑图当成功观察 |
| 输入框含密码/验证码语义 | `FAIL_CLOSED_CREDENTIAL`，拒绝注入，请求用户接管 |
| Shizuku 服务中途死亡 | 会话终止写 `RUN_INTERRUPTED` 语义终态；新会话不自动重放设备动作 |
| 会话/权限丢失 | 与 force-stop 语义一致：恢复后重新经过系统授权，benchmark 分开统计 |

## 安全（fail-closed）

- 与前台 backend 共用同一 ApprovalPolicy；隐藏执行不降低任何审批要求；
- `INJECT_EVENTS` 与屏幕截图是高权限能力：broker 层（Shizuku 侧实现）对每个注入/截图请求做参数白名单校验，不接受任意 shell 字符串；
- 隐藏执行全程有常驻通知与停止入口，与前台 backend 相同；
- Shizuku binder 句柄、激活凭证不进入 QuickJS、模型上下文、JSONL 或导出日志。

## 证据 cohort

- 隐藏执行全部 run：`[COHORT:android-virtualdisplay-*]`；
- 不与前台 `android-accessibility-*` 结果合并，不互相继承成功率；
- 真机验收顺序：物理真机最小闭环（启动目标 App → 观察虚拟显示 → 注入点击 → 动作后验证）→ 熄屏/锁屏 → Shizuku 中断恢复 → 目标 App benchmark（M1/X1/D1/W1 各自独立 cohort）。

## 当前骨架范围

- `virtualdisplay/HiddenExecutionBackend.kt`：backend 接口与可用性判定；
- `virtualdisplay/ShizukuVirtualDisplayBackend.kt`：骨架实现，未连接 Shizuku 时返回 `SHIZUKU_UNAVAILABLE`，不伪造成功；
- `virtualdisplay/SecureWindowPolicy.kt`：纯策略（secure 窗口/凭据/锁屏 → fail-closed 判定），JVM 单测覆盖；
- 无 Shizuku 依赖引入；binder 契约留待真机 lane 以不破坏现有构建的方式接入。
