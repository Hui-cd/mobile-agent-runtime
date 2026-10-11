# Android 真机 L1 smoke(时钟任务验收)

对应 `PROJECT_STATUS.md` 第一阶段的最小闭环验收：真机"打开时钟 → 观察页面 → 返回证据结果"。

## 运行

```bash
npm run android:smoke                 # 一台已授权真机,预检全部通过
npm run android:smoke -- --serial <id>   # 多台设备时明确选择
npm run android:smoke -- --install       # 先安装现有 debug APK 再执行
npm run android:smoke -- --timeout 300   # 默认轮询 180s
```

前置条件(不满足时命令会给出可操作的错误提示):

- 仅一台 `authorized` 真机(`adb devices -l` 为 `device`),拒绝 offline / unauthorized / emulator;
- `ai.mobileagent` debug APK 已安装(`--install` 会从 `apps/android/app/build/outputs/apk/debug/app-debug.apk` 安装,缺失时先 `npm run android:build`);
- 已在 Mobile Agent 中保存可用的模型 endpoint、模型名和 API Key；脚本不会读取这些密钥；
- 屏幕点亮且未锁、Mobile Agent Accessibility 服务已启用、设备装有 Google/AOSP 时钟。
- ADB 按 `MOBILE_AGENT_ADB`、`ANDROID_HOME` / `ANDROID_SDK_ROOT`、macOS 默认 SDK 路径、PATH 的顺序查找。

## 流程与判定

1. 通过 debug `AgentTaskTestReceiver`(`ai.mobileagent.DEBUG_RUN_AGENT_TASK`,`prompt_base64` extra)触发固定 prompt:`[BENCH:L1] [COHORT:physical-clock-smoke-v1]`(唯一 Agent 路径,不新增第二条);
2. 用 `run-as ai.mobileagent cat files/benchmarks/runs.jsonl` 轮询本次运行之后的新终态记录,超时即失败;
3. 仅当记录同时满足 `environment=physical_device`、`dev_only=false`、`task_id=L1`、`status=success`、`target_package` 为 Google/AOSP 时钟包,且 `completion_evidence.claim_follows_tool_evidence=true`、`completion_evidence.last_observation.current_app` 为时钟包时退出 0;否则打印简洁原因并非零退出。

命令不会读取或输出 API Key，也不会输出 benchmark 记录中的 prompt。

这条命令用 ADB 触发 debug receiver，只用于证明「Agent 确实在真机上调用 App 并形成设备证据」；它不证明最终产品已经摆脱电脑和 ADB。产品入口仍需单独验收。

## 测试

```bash
npx tsx --test test/android-device-smoke.test.ts   # 解析与判定的纯函数单测
```
