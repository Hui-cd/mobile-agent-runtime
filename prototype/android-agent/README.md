# Android Agent Interaction Prototype

独立的高保真交互原型，用于验证控制权流转，不属于生产实现。

```bash
python3 -m http.server 4173 --directory prototype/android-agent
```

打开 <http://localhost:4173>。右侧可切换普通完成、风险确认和异常恢复三条路径。

删除条件：交互方案被 Android Compose 实现并完成真机验证后，本目录可整体删除。
