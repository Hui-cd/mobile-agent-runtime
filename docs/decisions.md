# Decisions and findings

## 2026-08-06 — 工具面固定为三个原语

结论：Agent 只看到 `observe / act / invoke`，能力发现合并进 `observe` 响应，等待由 `act / invoke` 的 Runtime 后处理承担。

依据：截图、UI Tree 和前台 App 都是同一种“观察”；点击、输入、滑动是同一种“界面动作”。减少工具数量可降低模型选错工具的概率。

限制：工具少不等于参数可以模糊。每个原语仍使用严格结构化 Schema，并返回可纠正的错误码。

后续：保持模型工具面稳定，新增平台能力优先扩展参数和 capability matrix。

## 2026-08-06 — 不向模型暴露任意 ADB Shell

结论：ADB 是 Android Adapter 的内部实现；模型使用结构化 `device.invoke` 白名单。

依据：任意 Shell 会导致命令注入、不可审计行为和 Android 绑定；结构化 Intent/系统能力仍能覆盖大多数高效率路径。

限制：开发调试仍可能需要 Shell，但它不属于 Agent 公共协议。

后续：如果增加开发者调试口，使用独立进程/权限边界，不注册为模型工具。

## 2026-08-06 — Android 37 前台 Activity 数据源

结论：优先解析 `dumpsys activity activities` 的 `topResumedActivity`，同时兼容 `mResumedActivity / mCurrentFocus / mFocusedApp`。

依据：本机 Pixel 9、Android 37 模拟器实测，`dumpsys window windows` 没有前台焦点字段，而 `topResumedActivity` 可稳定返回 `package/activity`。

限制：`dumpsys` 文本格式不是稳定公共 API，厂商 ROM 仍可能不同。

后续：加入不同 Android 版本和厂商样本；companion app 可提供更稳定的前台包名来源。

## 2026-08-06 — Runtime 与 Agent Gateway 解耦

结论：Runtime 不依赖模型；Codex 走 MCP，BYOK 模型走 Provider Adapter。

依据：OpenAI Responses 与 OpenAI-compatible Chat 的工具循环格式不同，设备协议不应承担这些差异。官方工具调用流程也要求应用侧执行工具并把结果送回模型。

限制：BYOK Gateway 首版只传 UI Tree，不传截图；兼容 API 的工具调用实现质量由具体 Provider 决定。

后续：加入多模态结果标准化和更多 Provider Adapter，不修改三个设备工具。

## 2026-08-06 — API Key 和设备动作安全边界

结论：Key 仅通过 Gateway 环境变量/部署密钥注入；HTTP 任务默认只读，写设备必须设置 `allow_device_actions=true`。

依据：移动设备可能包含消息、文件、账号和支付能力，模型调用不能默认继承广泛写权限。

限制：显式布尔授权不是最终策略系统，尚未区分普通导航与发送、购买等高风险动作。

后续：加入分级策略、用户确认、调用审计和可撤销操作设计。

## 2026-08-06 — ADB 设备端 Shell 参数必须二次引用

结论：Android Adapter 把 `adb shell` 后的每一个逻辑参数进行 POSIX 单引号引用，再组装成唯一的设备端命令字符串。

依据：主机侧使用 `spawn(adb, argv)` 只能避免主机 Shell 注入；ADB 仍会把 `shell` 后的内容交给设备端 Shell 解析。Intent extras、URL 和输入文本都可能包含设备端元字符。

限制：这层保护针对当前只需要字面参数的命令；未来如果确实需要管道或重定向，不能绕过它拼接字符串。

后续：所有新 Android 能力继续传逻辑参数数组，并加入含引号、美元符号和命令替换字符的回归测试。

## 2026-08-07 — 产品主入口改为手机内 Agent App

结论：Android App 承担用户交互、模型工具循环和设备 Runtime；桌面 MCP/ADB Runtime 保留为开发、调试和 Codex 接入路径。

依据：目标交互是用户直接在手机里发任务，并允许网络推理在后台继续。只提供电脑命令行无法满足这一产品形态。

限制：GUI 自动化必然把目标 App 切到前台；只有推理、数据整理和等待确认能够不占用当前屏幕。

后续：增加任务历史、结构化比较结果、语音入口和更细粒度的后台/前台调度。

## 2026-08-07 — Kimi 中国区 BYOK 与本地 Key 存储

结论：内测 App 默认直连 `https://api.moonshot.cn/v1` 的 `kimi-k3`，使用 OpenAI-compatible tool calling；API Key 用 Android Keystore AES/GCM 加密保存在设备本地，不编入源码或 APK。

依据：同一测试 Key 在国际区 `.ai` 返回 401，在中国区 `.cn` 验证成功；真实请求能够生成符合 Schema 的 `device_invoke` 工具调用。

限制：当前 Provider 和模型写在 App 配置中，还没有可视化 Provider 切换；设备被 root 后不承诺密钥绝对安全。

后续：把 Provider、Base URL 和模型做成高级设置，并增加 Key 删除/轮换入口。

## 2026-08-07 — Android 包可见性与打开 App

结论：Android 33+ 使用 `PackageManager.getLaunchIntentSenderForPackage` 打开目标 App；Android 30–32 使用带 Launcher `queries` 的 `getLaunchIntentForPackage` 兼容路径。

依据：Android 37 实测中，仅使用 `getLaunchIntentForPackage` 被 package visibility 过滤，Kimi 虽正确调用 `open_app`，系统时钟仍未启动；改用 LaunchIntentSender 后由 `ai.mobileagent` 成功启动 `com.google.android.deskclock`。

限制：目标包不存在、没有可启动 Activity 或被设备策略禁用时仍会失败。

后续：增加常见 App 的名称到包名解析和候选选择 UI，避免模型猜错包名。

## 2026-08-07 — 首次模型请求直接携带 Context

结论：不设置独立 Planner 阶段。每次用户请求前先观察设备，把当前 App、Activity、UI Tree、能力状态、切入 Agent 前最后一个外部 App 上下文，以及最近 12 条用户/Agent 对话一起放入模型 messages；模型直接回复或产生 tool call。

依据：模型决策应基于请求时已经存在的上下文，不能先用一个无设备状态的“规划”请求再补观察。工具结果继续按标准 assistant tool_calls → tool message 顺序追加。

限制：状态和错误消息不进入长期对话上下文；初始请求默认只附 UI Tree，截图由模型在语义节点不足时显式请求。

后续：为长会话加入 token-aware 截断/摘要，并把最后一个外部 App 上下文持久化到进程重启之后。

## 2026-08-07 — iOS 保持三原语，但不伪造全局 UI 权限

结论：iOS Agent 与 Android 保持相同的 `observe / act / invoke` 工具面。iOS `observe` 返回设备与能力上下文，`act` 对跨 App GUI 操作返回明确的 unsupported；`invoke` 映射到 Universal Link、URL Scheme、App Intents、Shortcuts、地图、拨号和分享面板。

依据：稳定协议有利于模型和 Gateway 跨平台复用，但 Apple 的公开 SDK 没有第三方 App 全局读取/点击其他 App UI 的等价能力。App Intents 是 Apple 提供给 Siri、Spotlight 和 Shortcuts 的正式动作接口。

限制：目标 App 未公开 Intent、Shortcut Action、Universal Link 或 URL Scheme 时，iOS Runtime 无法自动完成其内部界面操作，也不能验证外部 App 内部结果。

后续：为高频目标 App 建 capability registry；优先发现 App Intents，其次 Universal Link/URL Scheme，最后明确请求用户手动接管。

## 2026-08-07 — iOS 后台只使用系统允许的有限续跑

结论：用户前台发起的模型请求使用 `beginBackgroundTask` 获取有限收尾时间，结束或超时时立即释放，并用本地通知报告结果；不声称常驻后台。

依据：iOS 会暂停后台 App，`beginBackgroundTask` 只适合完成已开始的关键工作，不是常驻服务机制。

限制：模型响应或跨 App 流程超过系统给定时间时会被取消；URL 打开通常也需要前台交互。

后续：对长上传下载使用 background URLSession；可延期任务再评估 BGTaskScheduler。

## 2026-08-07 — iOS 权限按任务时机申请

结论：通知权限不在 App 首次打开时申请，而是在用户第一次真正发起 Agent 任务后申请。

依据：通知只用于任务转入后台后的完成或失败提醒；首页启动时弹权限既缺少上下文，也干扰 API Key 配置和产品理解。

限制：用户拒绝通知后，后台任务仍可运行，但完成结果只能在再次打开 App 后查看。

后续：增加设置页中的通知状态与跳转入口，并在任务预计需要后台续跑时解释申请理由。

## 2026-08-07 — Xcode 26 新装 Simulator Runtime 的注册

结论：`xcodebuild -downloadPlatform iOS` 完成后，如果 `simctl` 仍报告 runtime profile 不存在，运行 `xcrun simctl runtime scan-and-mount` 刷新注册。

依据：本机 iOS 26.5 Runtime 下载完成且磁盘镜像状态为 Ready，但 CoreSimulator 的 Runtimes 列表为空；扫描挂载后立即可创建、启动 iPhone 模拟器。

限制：该处理针对 Xcode 26 的本机组件状态；其他 Xcode/macOS 版本的错误原因可能不同。

后续：将 Simulator Runtime 检查加入本地开发环境诊断脚本。

## 2026-08-07 — iOS Simulator 构建保留临时签名

结论：Simulator 构建不设置 `CODE_SIGNING_ALLOWED=NO`，使用 Xcode 默认的临时签名；真机仍由开发者选择 Development Team。

依据：完全禁用签名的 App 虽能安装启动，但访问 Keychain 时返回 `errSecMissingEntitlement (-34018)`；恢复 Simulator 默认签名后才能验证 BYOK 存储。

限制：Simulator 临时签名不能用于真机分发或 TestFlight。

后续：加入 Keychain 保存/读取的 UI 自动化冒烟测试，避免构建参数回归。
