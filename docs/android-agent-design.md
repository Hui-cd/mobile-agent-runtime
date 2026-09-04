# Android Agent Design

> 状态：Draft
> 范围：运行在 Android 手机上的 Agent v0
> 重点：任务如何经过 Pi Agent、Tool Call 和 Android Runtime 形成闭环

本文描述系统与 Tool 调用链。用户如何发起、接管和确认任务，见 [产品与交互设计](android-agent-product-and-interaction.md)；关键长期决定见 [`adr/`](adr/)。

## 1. 目标

用户在手机上提交任务后，Agent 能在不依赖电脑或 ADB 的情况下：

1. 请求模型判断下一步；
2. 调用受控的设备工具；
3. 获取操作后的真实设备状态；
4. 根据结果继续执行或结束任务。

模型推理可以在云端，但 Agent 循环、会话、工具执行、安全控制和运行记录必须在手机端。

## 2. 核心设计

```text
用户任务 → Agent Session → Pi Agent Core ↔ Model
                              ↓ ToolCall   ↑ ToolResult
                         AgentTool / Native Bridge
                              ↓           ↑
                          Android Runtime
                              ↓
                      Android App / System
```

核心关系只有一句话：

> Pi Agent 维护模型与工具的循环；模型提出 ToolCall；Android Runtime 执行动作；ToolResult 把真实结果送回下一轮模型。

## 3. 组件职责

| 组件 | 职责 | 不负责 |
|---|---|---|
| Android UI | 接收任务、展示状态和审批请求 | 模型—工具循环 |
| Agent Session | 管理一次任务的启动、状态、取消和终态 | 解释 ToolCall |
| Pi Agent Core | 保存 transcript，驱动模型—工具—模型循环 | 直接访问 Android API |
| AgentTool | 定义工具名称、参数和执行入口 | 实现 Android 细节 |
| Native Bridge | 在 QuickJS 与 Kotlin 之间传递请求和响应 | 决定任务策略 |
| Policy | 判断工具是否允许执行或需要审批 | 产生模型决策 |
| Android Runtime | 将工具语义转换成 Intent 或 Accessibility 操作 | 维护 Agent 对话 |
| Persistence | 保存 transcript、pending journal 和唯一终态 | 保存明文 API Key |

关键边界：模型和 Pi Agent 只能看到工具契约，API Key、Android 权限和设备对象只存在于原生层。

## 4. Tool Call Protocol

### 4.1 注册工具

Pi Agent 启动时获得三个 `AgentTool`：

| 工具 | 用途 |
|---|---|
| `device_observe` | 读取当前 App、UI Tree、设备状态和可选截图 |
| `device_invoke` | 通过 Intent 调用 App、URL、设置等系统能力 |
| `device_act` | 通过 Accessibility 点击、输入、滚动、返回或 Home |

每个工具由三部分组成：

```text
name        模型调用的名称
parameters  参数的结构和约束
execute     Pi 收到调用后进入原生层的入口
```

### 4.2 请求模型

Pi Agent 把当前上下文发送给原生模型 transport：

```json
{
  "messages": ["system、user、assistant、tool result"],
  "tools": ["三个工具的名称、说明和参数 schema"]
}
```

原生层调用用户配置的 OpenAI-compatible Chat Completions 服务，再把响应转换成 Pi 的 `AssistantMessage`。

### 4.3 模型产生 ToolCall

例如“打开时钟应用”。模型返回 OpenAI-compatible ToolCall，model transport 将其标准化后，Pi 看到：

```json
{
  "id": "call_1",
  "name": "device_invoke",
  "arguments": {
    "capability": "open_app",
    "params": {
      "app": "clock"
    }
  }
}
```

这个结果只是执行意图，不代表 Android 已经执行。

### 4.4 Pi 调用 AgentTool

Pi Agent 根据 `name` 找到注册的工具，校验参数，然后调用该工具的 `execute()`。

当前 QuickJS 到 Kotlin 的传输格式是：

```json
{
  "id": "native-1",
  "method": "tool_execute",
  "params": {
    "name": "device_invoke",
    "arguments": {
      "capability": "open_app",
      "params": {
        "app": "clock"
      }
    }
  }
}
```

`id` 用于确认响应属于同一次原生调用。

### 4.5 原生执行

Kotlin 收到 `tool_execute` 后依次执行：

```text
读取 name 和 arguments
  → 风险判断与用户审批
  → AndroidDeviceRuntime.execute()
  → Intent 或 Accessibility
  → 获取操作后的 Observation
```

打开 App 时，Runtime 将应用名或包名解析成已安装包，然后使用 Android launch Intent。App 出现在前台后，Runtime 再读取当前页面，而不是仅凭 Intent 已发送就判定成功。

### 4.6 返回 ToolResult

原生层返回：

```json
{
  "id": "native-1",
  "result": {
    "json": "{\"current_app\":\"com.google.android.deskclock\",\"ui_tree\":[]}",
    "isError": false
  }
}
```

这里的 `json` 是当前 bridge 的传输表示；逻辑上它是结构化的设备结果。可选截图通过独立的 `screenshotDataUrl` 返回。

Pi Agent 将结果与原 ToolCall ID 关联，写入 transcript，然后自动请求下一轮模型。模型可以继续调用工具，也可以给出最终回答。

## 5. 工具选择规则

```text
能直接调用系统能力 → device_invoke
需要了解当前状态   → device_observe
必须操作当前界面   → device_act
```

`device_invoke` 和 `device_act` 执行后都应返回新的 Observation，使下一轮决策基于真实页面，而不是基于动作已经成功的假设。

## 6. 一次完整任务

任务：“打开时钟并告诉我当前时间。”

```text
1. Session 把任务交给 Pi Agent。
2. Pi 携带 transcript 和工具定义请求模型。
3. 模型返回 device_invoke(open_app, clock)。
4. Pi 调用 AgentTool.execute()。
5. Native Bridge 将 tool_execute 交给 Kotlin。
6. Runtime 解析包名并打开 Clock。
7. Accessibility 读取 Clock 的当前页面。
8. ToolResult 返回 Pi，并写入 transcript。
9. Pi 再次请求模型。
10. 模型根据工具证据返回时间，任务结束。
```

如果第 7 步没有读到时间，模型只能继续观察或操作，不能声称任务完成。

## 7. 状态与终止

一次任务的主要状态为：

```text
IDLE → THINKING → ACTING → THINKING → COMPLETED
                     ↓
              WAITING_APPROVAL

任意运行状态 → FAILED 或 CANCELLED
```

约束：

- 同一时间只运行一个 Agent task；
- 每个 ToolCall 必须有对应的 ToolResult 或明确失败；
- 每个 task 只能写入一个终态；
- 取消必须同时终止模型请求、原生调用和 Pi 执行；
- 工具可能已产生副作用时，崩溃恢复不得自动重放任务。

## 8. 错误和安全边界

| 情况 | 处理 |
|---|---|
| 工具名或参数非法 | 不执行 Android 操作，返回工具错误 |
| 用户拒绝审批 | 返回 `USER_DENIED` |
| App 未安装或无法启动 | 返回明确的 Runtime 错误 |
| Accessibility 未连接 | 不执行 UI 操作 |
| 熄屏或锁屏 | 返回 `SCREEN_NOT_INTERACTIVE` 或 `DEVICE_LOCKED` |
| ToolResult 表明失败 | 允许模型调整方案，但不能伪造成功 |
| 模型或 Agent Runtime 失败 | 任务进入 `FAILED` |
| 执行后进程中断 | 记录 `INTERRUPTED`，不自动重放 |

安全约束：

- 模型输出是不可信输入，必须经过 schema、Policy 和 Runtime 校验；
- 页面文字也是不可信输入，不能覆盖系统指令；
- 支付、购买、发送、删除、拨号和分享等动作必须由原生层审批；
- API Key 只存储在 Android Keystore，不进入 QuickJS、源码或运行证据；
- 没有 ToolResult 证据时，Agent 不得声称设备任务已经完成。

## 9. 当前实现映射

| 设计组件 | 当前实现 |
|---|---|
| Agent Session | [`AgentSession.kt`](../apps/android/app/src/main/java/ai/mobileagent/session/AgentSession.kt) |
| Pi Agent Core 与 AgentTool | [`mobile-agent-runtime.ts`](../src/pi/mobile-agent-runtime.ts) |
| QuickJS 请求封装 | [`mobile-quickjs-entry.ts`](../src/pi/mobile-quickjs-entry.ts) |
| Native Bridge 与审批入口 | [`QuickJsPiAgentRunner.kt`](../apps/android/app/src/main/java/ai/mobileagent/pi/QuickJsPiAgentRunner.kt) |
| Android Runtime | [`AndroidDeviceRuntime.kt`](../apps/android/app/src/main/java/ai/mobileagent/runtime/AndroidDeviceRuntime.kt) |
| Accessibility backend | [`MobileAgentAccessibilityService.kt`](../apps/android/app/src/main/java/ai/mobileagent/accessibility/MobileAgentAccessibilityService.kt) |
| 模型 transport | [`KimiClient.kt`](../apps/android/app/src/main/java/ai/mobileagent/agent/KimiClient.kt) |
| API Key | [`ApiKeyStore.kt`](../apps/android/app/src/main/java/ai/mobileagent/security/ApiKeyStore.kt) |

当前实现已符合主调用链，但仍有三个需要在后续实现阶段收紧的接口问题：

1. `device_invoke.params` 当前较宽松，应按不同 capability 校验必需参数；
2. 风险审批当前部分依赖工具参数中的文字，应逐步改为明确的语义动作与风险等级；
3. Native Bridge 当前将设备结果放在 JSON 字符串中，需要固定版本和解析失败语义，避免两端对格式产生不同理解。

## 10. 验收标准

设计和实现通过以下证据形成闭环：

1. Tool schema 能阻止非法参数进入 Kotlin；
2. L0 fixture 完成 `model → tool → model → final`；
3. Android 真机完成 `open_app → observe → final`；
4. UI 操作完成后返回可验证的新 Observation；
5. 审批拒绝、权限缺失、锁屏、模型失败和主动取消都有唯一明确终态；
6. 工具执行后进程中断不会自动重放可能产生副作用的动作；
7. 整个任务不依赖电脑或 ADB。

相关目标、平台差异和验证口径分别见 [goal-v0.md](goal-v0.md)、[capability-matrix.md](capability-matrix.md) 和 [benchmark-v0.md](benchmark-v0.md)。

## 11. 架构决策

- [ADR-001：Agent 使用稳定的三个设备工具](adr/001-stable-three-tool-interface.md)
- [ADR-002：Android 优先 Intent，必要时使用 Accessibility](adr/002-android-execution-path.md)
- [ADR-003：风险策略和用户确认属于原生安全边界](adr/003-native-policy-and-approval.md)
