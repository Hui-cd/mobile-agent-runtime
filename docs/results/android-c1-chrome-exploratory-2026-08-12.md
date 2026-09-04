# Android Chrome C1 exploratory runs — 2026-08-12

## Outcome

这是实现演进期间的探索集，不是固定版本的连续基线。Android 37 / 16KB ARM64 emulator 上共记录 C1 20 次；聚合器按 adjudicator v2 重判后为 9 success、11 failed，另撤销 1 条旧假阳性，探索集成功率 45%。全部为 `dev_only=true`，产品证据仍为 0。

任务已真实覆盖 Chrome `145.0.7632.218` 中的搜索、前 5 条结果读取、进入博客园详情、滚动与结构化返回。成功样本要求目标包、成功搜索路径、结果点击、成功滚动、滚动后至少 8 个可见文本节点及无阻断网络错误同时成立。

## What the runs established

- attempt 1 暴露弱裁决假阳性：结果承认正文未读且滚动失败，却曾写 success；v2 聚合已将其撤销。
- attempts 2/7 的真实设备证据完整，但 final 在 JSON 前添加说明文字；因此 benchmark final 改为严格 JSON contract。
- JSON Mode 消除了语法漂移，但 attempt 9 把 `detail` 改成 `opened`；C1 改用 `json_schema + strict=true`。
- attempt 18 从首轮强制 schema 后出现 0 tool call 的完整幻觉结果；因此 schema 只能在成功搜索、点击和滚动都进入 transcript 后启用。
- attempt 20 是分阶段实现后的首次请求，日志确认 `responseFormat=text`，但 Kimi 立即返回 HTTP 429（余额不足），以 `KIMI_API_ERROR`、545ms、0 tool call 落盘。它不是有效 smoke，也不验证最终 `json_schema` 切换。
- provider-neutral transport 与 cohort recorder 安装后，attempt 22 在 `api.moonshot.cn / kimi-k3 / c1-staged-smoke-v1` 独立组中再次得到 HTTP 429；记录含实际 endpoint host、model、cohort，首轮仍为 `responseFormat=text`。账户阻塞得到当前复核，但该 run 仍不验证工具链后的 schema 切换。

## Current gate

本探索集之后，固定版本的 `c1-staged-baseline-v2` 已完成 10/10 success；正式结果见 [android-c1-chrome-baseline-2026-08-12.md](android-c1-chrome-baseline-2026-08-12.md)。本页仍保留为实现演进和失败分类证据，不与正式 cohort 合并。

恢复 Kimi 额度后，先以独立 `[COHORT:c1-staged-smoke-v1]` 执行 1 次在线 smoke：前段请求必须为 `text`，搜索 + 点击 + 滚动成功后的 final 请求必须为 `json_schema`，且手机端 adjudicator 通过。随后改用 `[COHORT:c1-staged-baseline-v1]`，从同一构建、同一 prompt、同一网络条件重新开始连续 10 次；本文件的 20 次探索记录、smoke 与正式基线三者不得拼接。

当前离线回归包括 Android 阶段策略 JVM 单测（搜索路径、点击、滚动缺一不可，且同时接受 UI input 与查询 URL 两条搜索路径）、QuickJS 原版 Pi fixture，以及全量 JSONL 历史兼容聚合。
