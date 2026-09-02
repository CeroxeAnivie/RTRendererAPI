# Hex Ball Demo

该可选模块是仓库自带的 retained-scene 交互示例与 GPU smoke workload。它只解析 Maven Central 已发布的
仓库当前构建的 `top.ceroxe.rt:renderer-api:3.1.14` 及其完整传递运行时。Demo 以 Maven Central 为第一解析源，
与外部消费者使用相同坐标；仅当该版本尚未出现在 Central 或 Central 暂不可访问时，才自动发布当前 checkout
到本地 fallback repository。Central 命中时 clone 仓库后运行 Demo 不需要用于构建
`renderer-nvidia` 的 CMake、Visual Studio C++、Vulkan SDK、NRD、NRI、Streamline 或 RTXMU
源码工具链；只有在 Central 缺少该版本、必须从 checkout 构建 fallback 时才需要这些工具链。
fat JAR 门禁仍会验证打包后的 NVIDIA DLL 清单和 SHA-256。

它演示普通场景与官方 GPU presenter，不演示 `submitResources(...)`、`submitCommands(...)` 或
通用 BLAS/TLAS/RT pipeline。需要保留既有资源、shader 与命令顺序的宿主应阅读
[通用命令与硬件光线追踪指南](../../docs/Generic-Commands-and-Ray-Tracing.md)。

在仓库根目录执行：

```powershell
.\gradlew.bat :demos:hex-ball:run --args="--width=2560 --height=1440 --spp=2"
.\gradlew.bat :demos:hex-ball:shadowJar
java -jar .\demos\hex-ball\build\libs\RTRendererAPI-HexBallDemo-3.1.14.jar `
  --width=2560 --height=1440 --spp=2
```

使用 `--duration-seconds=90` 可执行有界验收；进程会通过 presenter 与 renderer 的正常关闭路径
退出。该参数与 `--frames` 互斥；两者都省略时，交互窗口会持续运行，直到用户主动关闭。

以下命令定义可复现的 smoke workload，不构成预先通过声明。只有保存了对应提交、硬件、驱动、
命令输出与结构化证据的实际运行，才能写入版本验收结论。

Demo 默认请求 2x 帧生成。只有技术状态达到 `ACTIVE`，且类型化的
`RendererDiagnostics.frameGenerationEvidence()` 计数器产生对应证据，才能证明生成帧已经到达
provider presentation path；仅提出配置请求不构成证据。每个进程只运行一种功能模式：

```powershell
## 原生基线
java -Ddemo.disable-fg=true -jar .\demos\hex-ball\build\libs\RTRendererAPI-HexBallDemo-3.1.14.jar `
  --width=2560 --height=1440 --spp=2 --duration-seconds=90

## FG 2x
java -Ddemo.fg-multiplier=2 -jar .\demos\hex-ball\build\libs\RTRendererAPI-HexBallDemo-3.1.14.jar `
  --width=2560 --height=1440 --spp=2 --duration-seconds=90

## MFG 3x
java -Ddemo.fg-multiplier=3 -jar .\demos\hex-ball\build\libs\RTRendererAPI-HexBallDemo-3.1.14.jar `
  --width=2560 --height=1440 --spp=2 --duration-seconds=90
```

验收记录必须包含：启动 framebuffer 与 SPP、制品 SHA-256、请求/配置的 presentation
multiplier、proxy present 次数、实际呈现的生成帧数、request miss、state-query failure、native
status、有效倍率、最终 renderer health/obligations，以及进程退出码。

FG 2x 只有在实际生成输出持续存在、技术状态达到 `ACTIVE`、miss/query failure/native status
保持健康、关闭时 obligations 为空且进程以退出码 `0` 结束时才算通过。当前 MFG 3x 门禁要求：
请求与配置倍率均为 3x，90 秒运行期间无 device loss、崩溃、未释放 obligation 或非零退出码；
有效倍率只作为证据记录，不作为性能阈值。

可执行 JAR 会重新启动自身并启用 native access。`:demos:hex-ball:run` 会直接应用同一 native
access 策略。确定性合同检查分别由 `:demos:hex-ball:physicsSelfTest` 与
`:demos:hex-ball:featureIntegrationSelfTest` 提供。
