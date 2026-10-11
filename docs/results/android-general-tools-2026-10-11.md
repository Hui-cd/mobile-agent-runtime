# 通用工具使用：模拟器实测

日期：2026-10-11。开发环境为 Pixel_9 Android 37 ARM64 模拟器，在线模型 `kimi-k3` / `api.moonshot.cn`；不是模型 stub。

最终验证 APK SHA256：`dda35d61636ce14ca7909dd33e8e79015532756970033cfc21c3753996631d3e`。

| 目标 | 实际工具使用 | 独立验收 | 耗时 |
|---|---|---|---:|
| 发现应用，打开时钟，切到秒表再切回闹钟 | observe 1、invoke 1、click 2 | 中间选中 Stopwatch，最终选中 Alarm；回答与最终观察一致 | 29.450 秒 |
| 打开设置，输入 bluetooth，读取三个可见搜索结果 | invoke 1、click 1、input 1 | 观察到实际输入框内容与 Bluetooth / Bluetooth tethering / USB tethering 三项 | 19.073 秒 |

原始证据：[时钟](android-general-clock-final-2026-10-11.json)、[设置](android-general-settings-final-2026-10-11.json)。包含任务、工具参数、设备观察、最后回答和外部 verdict，不包含模型凭据。设置搜索由系统委托至 `com.google.android.settings.intelligence`，不是主 Settings 包；验收同时支持该系统搜索组件。

模型自己选择调用和节点。Host 只终止目标应用的旧任务以重置测试起始状态，再触发手机内 Agent、收集结果和判定；没有预编写点击序列，不清除应用数据。正常用户会话历史不参与测试，也不被覆盖。

这些通用任务在设备记录里仍为 `completed_unverified`，独立工具验收的 verdict 为 true。最终回答可能是包着 Markdown 围栏的 JSON；判定器只解码整段单一 JSON 围栏，原始回答原样保留，不从混杂文本中猜测结果。

## 发现并处理的问题

1. 搜索容器只有资源 ID，文字在子节点：旧审批将它当作未知目标而暂停。改为原生层解析实际点击接收控件与可见子节点标签后判断风险；该次暂停记录保留为取消，不算成功。
2. 搜索结果含当前不可见条目：独立验收拒绝。观察工具说明强调 visible 状态，测试目标明确只读取可见三项。
3. Settings 重开时可能复用旧搜索页：未实际输入的运行被拒绝。现在重置目标应用任务，并明确本轮要验证真实输入。
4. 取消运行没有本轮完成 transcript：不读取上次任务的 transcript，要求 prompt 绑定。

保留的失败记录见 `android-general-settings-2026-10-11.json`、`android-general-settings-fixed-2026-10-11.json`、`android-general-settings-v2-2026-10-11.json`。这些失败不会混入最终通过次数。

## 代码检查与边界

- TypeScript check、Node build、Android debug APK 构建通过。
- Node 测试 55/55；Android JVM 测试 46/46。
- 覆盖同名目标、多个条件同时匹配、不可见/禁用节点、旧观察拒绝、应用名称歧义、截图请求转换、旧页面不能绑定新声明，以及验收假阳性。
- 截图传递仅有转换单测，尚未验证视觉模型任务；手势取消/拒绝逻辑已修复并编译，但本次实际任务没有注入系统手势取消。
- 本次证明两个系统应用内的发现、语义点击、输入与读取。尚无第三方 App 通用能力、滚动长流程、跨窗口动态重排、坐标/Canvas 操作或真机后台稳定性的证据。

工具契约和后续边界见[通用工具设计](../android-general-tools.md)。
