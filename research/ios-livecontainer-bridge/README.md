# Mobile Agent LiveContainer bridge (research)

这是 iOS research backend，不属于 TestFlight/App Store 公共产品。它以 LiveContainer 3.8.0 的 guest tweak framework 运行在目标 App 同一进程，提供统一工具所需的 UIKit 语义观察和动作 C ABI：

- `MABridgeObserveJSON()`：当前 guest bundle、可见 UIKit 节点、稳定 view path、文本/identifier/frame。
- `MABridgeActJSON()`：按 path 执行 `activate`、`input`、`scroll`。
- framework constructor：在 guest `Documents/mobile-agent-bridge-loaded.json` 写入版本、guest bundle、进程名和加载时间，作为真机注入证据。

构建：

```bash
scripts/build-ios-livecontainer-bridge.sh
```

产物为 `research/build/MobileAgentBridge.framework`。`scripts/deploy-ios-livecontainer-bridge.sh` 会用同一开发证书签名后放入 LiveContainer 的全局 `Documents/Tweaks`；因此无需为每个 guest 选择 tweak folder。LiveContainer 必须保持 TweakLoader 注入和加载开启。

研究 host 由 `scripts/prepare-ios-livecontainer-host.sh` 从官方 3.8.0 release IPA 生成。脚本固定并校验上游 SHA-256，只在被 git 忽略的 `research/build` 中产生重签名结果；不会把 AGPL 上游二进制提交进公共产品。现有 wildcard profile 没有 App Groups，所以研究 host 会移除依赖 App Groups 的 extensions，保留单进程 guest launcher / TweakLoader 链路。

host 使用独立 bundle ID `ai.mobileagent.livecontainer`，但必须保留上游 `livecontainer` URL scheme：3.8.0 把其他 scheme 识别为 secondary container，并拒绝 guest 导入。设备上不能同时安装另一个占用该 scheme 的主 LiveContainer。

iOS 26+ 必须进入 LiveContainer 的 JIT-less 分支。研究 host 的 guest 与 Bridge 在 Mac 上由同一个 Apple Development identity 外部签名；Bridge 首次以 host 自身 tweak 加载时只持久化一个 `external-development-signature` 模式标记，不向 LiveContainer 导入或保存 P12/私钥。该模式不适用于任意第三方 IPA，也不能替代正式证书导入流程。

当前阶段只建立 guest 内 native bridge。Pi carrier、模型配置 UI/安全存储和 benchmark recorder 仍需合并到 framework，才能形成完全手机内自主的 research agent。不要把“framework 已加载”当作主流 App 任务成功。

安全边界：LiveContainer 本身声明 guest containers 不是互相安全隔离的；插件与 host 能接触 guest UI 和数据。本路线只允许授权测试 IPA/账号，必须与公开 App 独立分发、独立 bundle、独立结果 cohort。

上游固定：LiveContainer 3.8.0，AGPL-3.0，release IPA SHA-256 `b6fea95e30083382e29ffef88fa1aaa40b5069e1112e5307d490dab04648bba6`。
