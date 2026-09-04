# Contributing

感谢参与 Mobile Agent Runtime。

## 开始之前

- 先阅读 [Goal v0](docs/goal-v0.md) 和相关 [ADR](docs/adr/)；
- 新能力应继续使用 `device_observe / device_act / device_invoke`，不要为单个 App 增加新的模型工具；
- 区分源码实现、模拟器验证、真机验证和产品完成度；
- 不提交 API Key、签名材料、真实账号数据、设备标识或 benchmark 原始敏感数据。

## 本地检查

```bash
npm ci
npm test
npm run build
npm run android:test
npm run android:build
```

只修改文档或单个平台时，可以运行与改动相称的检查，并在 Pull Request 中说明未运行的项目和原因。

## Pull Request

请简要说明：

1. 解决的问题；
2. 行为或接口是否改变；
3. 实际运行的验证及结果；
4. 仍未验证的边界。

影响长期架构、工具契约、安全策略或兼容性的决定，应新增或 supersede 一份 ADR。
