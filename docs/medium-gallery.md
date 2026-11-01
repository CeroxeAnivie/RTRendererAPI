# RTXPT 介质演示

7.0.0 使用 RTXPT。旧 CUDA 密度、散射、光子图展厅已撤下，历史报告保留在 `quality/`，不代表当前功能。

```powershell
./gradlew.bat :demos:hex-ball:rtxptMediumDemo
./gradlew.bat :demos:hex-ball:rtxptMediumDemo '-PrtxptArgs=--scene=nested'
./gradlew.bat :demos:hex-ball:rtxptMediumBenchmark
```

默认以窗口模式输出 1920×1080、1 SPP，采用上游 DLSS-RR＋SHaRC，开启 2× FG。左上角完整 HUD 显示实际原生／生成／总 FPS、GPU 帧耗时、内外分辨率及各项技术的运行状态；生成帧来自 SDK 实际交付计数。HUD 每 500 ms 更新，普通帧不做 CPU 图像读回。

| 窗口按键 | 场景 | 内容 |
|---|---|---|
| `1` | `mixed`（默认） | 吸收、嵌套介电、粗糙透射、金属和表面发光 |
| `2` | `glass` | 单层有色吸收玻璃、反射及折射 |
| `3` | `nested` | 外层 IOR 1.50／优先级 2，内层 IOR 1.33／优先级 6 |
| `4` | `absorption` | 相同吸收系数，比较 0.3／0.7／1.2 厚度 |
| `5` | `rough` | 粗糙透射，比较 0.02／0.15／0.35 粗糙度 |
| `6` | `ior` | 比较 IOR 1.1／1.5／2.4 的反射与折射 |
| `Esc` | 退出 | 释放原生窗口和 GPU 资源 |

左上角 HUD 下方提供可点击的 `Scene` 下拉切换器，与数字键共用同一组真实场景。它由上游 ImGui 直接在 GPU 画面内绘制，不启动额外控制窗口。按键仅在本窗口获得焦点时处理，控制台也支持上述场景名与 `quit`。窗口持续运行，除非主动关闭或传入 `--seconds=N`。编译／加载只写日志，不弹独立进度窗。

可执行 JAR：`java -jar demos/hex-ball/build/libs/RTRendererAPI-HexBallDemo-7.0.0.jar --media`。`--capture=文件.png` 在画面稳定后显式捕获一次含 HUD 的最终画面；`--native=true` 改为原生内部分辨率并关闭重建。`-Ddemo.disable-fg=true` 显式关闭 FG，`-Ddemo.fg-multiplier=3`／`4` 请求 MFG，普通／专家 API 控制仍可覆盖对应选项。独立 `rtxptMediumBenchmark` 保持 FG 关闭，不能和 Demo 总输出 FPS 混为一谈。

六个场景都只展示真实支持的光学能力。画面上方白色长板是实际表面发光几何，用作面光源，不是体积自发光。旧密度、体积单／多次散射、HG、体积自发光、初始相机介质及无限介质没有菜单或占位场景；未知场景直接拒绝。见 [版本合同](7.0.0.md)。
