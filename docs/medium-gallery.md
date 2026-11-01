# RTXPT 介质演示

7.0.0 使用 RTXPT。旧 CUDA 密度、散射、光子图展厅已撤下，历史报告保留在 `quality/`，不代表当前功能。

```powershell
./gradlew.bat :demos:hex-ball:rtxptMediumDemo
./gradlew.bat :demos:hex-ball:rtxptMediumDemo '-PrtxptArgs=--scene=nested'
./gradlew.bat :demos:hex-ball:rtxptMediumBenchmark
```

提供 `mixed`、`glass`、`nested` 场景。窗口和退出由宿主控制；编译/加载只写日志，不弹独立进度窗。参数以 `RtxptMediumDemo` 和 `RtxptMediumBenchmark` 为准。

混合场景用同一原生渲染器处理不透明表面、金属、表面发光、吸收玻璃和嵌套介电材质，不含撤下的体积散射。见 [版本合同](7.0.0.md)。
