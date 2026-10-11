# 安卓通用工具契约

更新：2026-10-11。目标是让模型根据任务与真实页面组合工具，不在 Runtime 中编写每个 App 的点击流程。

普通用户入口的会话、配置和权限问题及本轮修复见[使用体验审查](android-usability-review.md)。

## 目标架构

```text
用户目标（本地或通讯入口）
    → 手机内 Pi Agent
    → 发现能力 / 观察 → 选择目标 → 原生审批 → 操作 → 新观察
    → 核对用户目标，继续或报告阻塞
```

- REUSE：三个工具名称、手机内 Pi loop、Intent/Accessibility、原生风险确认与停止能力。
- EXTEND：页面状态、应用发现、目标参数约束、观察绑定、截图传递和错误信息。
- REPLACE：App 别名表、同名节点取第一个、索引忽略其他选择条件、手势被拒绝/取消仍当作成功。
- ADD：两个小型纯模块 `AppCatalog`、`UiTargetResolver`；按目标运行的工具验收脚本。

通讯接入只传递目标，不决定点击顺序。开发验收可以使用 ADB 触发任务；产品工具不暴露 ADB 或 Shell。

## 三个工具

| 工具 | 通用职责 | 本次变化 |
|---|---|---|
| `device_observe` | 获取当前 App、界面节点、设备状态和可选截图 | `include_apps` 发现实际可启动应用；返回 `observation_id` 和 visible/selected/checked/focused 等状态 |
| `device_invoke` | 优先调用系统提供的直接入口 | 按系统应用目录解析名称和包名；同名应用必须消歧，不维护 App 别名表 |
| `device_act` | 一次点击、长按、输入、滚动、滑动、返回或 Home | 目标字段严格定义并同时匹配；拒绝歧义、不可见、禁用和类型不合的控件；操作后返回新观察 |

输入替换文本字段的全部内容。滚动方向指手指移动方向，`up` 通常对应向页面后方滚动。无指定目标的输入/滚动只在唯一可用控件时选择默认目标。

语义选择条件支持 text、content_description、resource_id、role、index。index 必须带对应 observation_id；附带 text 等其他条件时也必须同时满足，不能用索引绕过文字匹配。

传入 observation_id 时，原生层核对上次签发的观察、前台应用和当前有界节点快照。页面改变时返回 `STALE_OBSERVATION`，要求重新观察。语义选择器不带 observation_id 的旧调用仍兼容；不能据此宣称所有操作都具有强制页面版本保护。

模型每次只选择一个依赖页面的动作。Runtime 不预定义“时钟流程”“微信流程”，也不提前猜测后续页面。

## 失败与完成

- 空目标或未知选择条件：`TARGET_REQUIRED` / `TARGET_INVALID`。
- 无匹配目标：`TARGET_NOT_FOUND`；重新观察。
- 同名目标：`TARGET_AMBIGUOUS`；用更多条件或带观察 ID 的索引消歧。
- 页面已变：`STALE_OBSERVATION`；不执行旧目标。
- 手势拒绝、取消、超时：分别返回 `GESTURE_REJECTED`、`GESTURE_CANCELLED`、`GESTURE_TIMEOUT`，不冒充成功。
- 返回/Home 被系统拒绝时明确失败。

审批判断使用原生层实际解析的点击接收控件及其可见子节点标签，执行仍保留原始选择条件。未知或有风险的目标继续要求确认，模型提供的名称不能替代实际控件标签。这改善普通容器节点的误拦截，不意味着现有关键词审批已经覆盖所有 App 的外部影响。

工具请求成功不等于任务完成。完成声明必须紧跟带观察的成功工具结果；后续工具仅返回 `ok` 时，不能借之前的旧页面绑定完成声明。只将 visible=true 的节点当作当前可见内容；需要更多内容时继续滚动/观察。

状态对比与错误处理使用 Android 提供的[节点状态](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo)和[手势完成/取消回调](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.GestureResultCallback)。

## 截图

最新工具截图现在被传入下一轮模型请求。OpenAI-compatible 请求保留完整的 assistant/tool 对应顺序，再以带“不可信设备数据”说明的图像消息传入截图；历史截图不重复传输。截图获取失败会返回 screen_error，语义观察仍可使用。

图像请求转换已通过单元测试；尚未建立不同视觉模型的实测基线。当前没有坐标点击工具，也未验证 Canvas/游戏的通用视觉操作。

## 验收

```sh
npm run android:tools:smoke -- --task clock
npm run android:tools:smoke -- --task settings
```

先安装最新 debug APK、配置模型、授权无障碍和通知，保持模拟器亮屏解锁。脚本只接受模拟器。

任务描述只指定目标。模型自己决定工具与选择条件；脚本核对真实工具结果，包括中间/最终选中页签、搜索输入及结果是否出现在最后观察中。它不执行预定点击序列，也不替模型补做动作。

每次使用独立会话，保留用户正常会话历史。开始前仅终止测试目标应用的旧任务，不清除应用数据，以免旧搜索页面满足本轮目标而未实际验证输入。调试 transcript 必须与该次任务 prompt 一致，取消或失败不能借用旧任务 transcript。结果可用 `--out path.json` 保存，已有文件不覆盖。

通用任务在原生记录中仍标记 `completed_unverified`；脚本的外部 verdict 是独立验收结果，不修改产品的完成状态。一次通过也不等于通用能力已经稳定，仍需第三方 App、重复运行、动态页面、视觉与真机验证。

本次实测见[时钟与设置结果](results/android-general-tools-2026-10-11.md)。
