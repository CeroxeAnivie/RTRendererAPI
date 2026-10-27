# Hex Ball Demo

该模块提供 retained-scene 交互示例、可编程场景示例与 GPU smoke workload，直接消费同仓库 API
及其完整运行时。源码构建需要仓库的 native SDK/toolchain；生成的 fat JAR 自带完整运行时，
运行时无需安装 SDK。fat JAR 门禁验证打包后的 NVIDIA DLL 清单和 SHA-256。
独立任务 `verifyPublishedMavenConsumer` 验证本地 Maven 制品，避免与 Demo 自举构建相互依赖。

它演示普通场景与官方 GPU presenter，不演示 `submitResources(...)`、`submitCommands(...)` 或
通用 BLAS/TLAS/RT pipeline。需要保留既有资源、shader 与命令顺序的宿主应阅读
[通用命令与硬件光线追踪指南](../../docs/Generic-Commands-and-Ray-Tracing.md)。

在仓库根目录执行：

```powershell
.\gradlew.bat :demos:hex-ball:run --args="--width=2560 --height=1440 --spp=1"
.\gradlew.bat :demos:hex-ball:shadowJar
.\gradlew.bat :demos:hex-ball:programmableSceneDemo
java -jar .\demos\hex-ball\build\libs\RTRendererAPI-HexBallDemo-5.2.1.jar `
  --width=2560 --height=1440 --spp=1
```

使用 `--duration-seconds=90` 可执行有界验收；进程会通过 presenter 与 renderer 的正常关闭路径
退出。该参数与 `--frames` 互斥；两者都省略时，交互窗口会持续运行，直到用户主动关闭。

以下命令定义可复现的 smoke workload，不构成预先通过声明。只有保存了对应提交、硬件、驱动、
命令输出与结构化证据的实际运行，才能写入版本验收结论。

Demo 默认使用 `RendererConfig.presentation()` 推荐模式：GPU 直接呈现、2x 帧生成、
平衡时域重建、低延迟和 RT 优化均由能力协商决定；NRD 不在默认推荐路径中，必须显式请求。只有技术状态达到 `ACTIVE`，且类型化的
`RendererDiagnostics.frameGenerationEvidence()` 计数器产生对应证据，才能证明生成帧已经到达
provider presentation path；仅提出配置请求不构成证据。每个进程只运行一种功能模式：

如需复现实验基线，可显式设置 `-Ddemo.feature-profile=generation-only`；只追加 SR 可使用
`-Ddemo.feature-profile=generation-and-sr`；推荐配置但禁用 NRD 可使用
`-Ddemo.feature-profile=recommended-no-nrd`；在此基础上追加 NRD 可使用
`-Ddemo.feature-profile=generation-sr-nrd`；`all-except-mfg`
用于只禁用 MFG 的专项验收，不是默认接入路径。

```powershell
## 原生基线
java -Ddemo.disable-fg=true -jar .\demos\hex-ball\build\libs\RTRendererAPI-HexBallDemo-5.2.1.jar `
  --width=2560 --height=1440 --spp=1 --duration-seconds=90

## FG 2x
java -Ddemo.fg-multiplier=2 -jar .\demos\hex-ball\build\libs\RTRendererAPI-HexBallDemo-5.2.1.jar `
  --width=2560 --height=1440 --spp=1 --duration-seconds=90

## MFG 3x
java -Ddemo.fg-multiplier=3 -jar .\demos\hex-ball\build\libs\RTRendererAPI-HexBallDemo-5.2.1.jar `
  --width=2560 --height=1440 --spp=1 --duration-seconds=90
```

验收记录必须包含：启动 framebuffer 与 SPP、制品 SHA-256、请求/配置的 presentation
multiplier、proxy present 次数、实际呈现的生成帧数、request miss、state-query failure、native
status、有效倍率、最终 renderer health/obligations，以及进程退出码。

FG 2x 只有在实际生成输出持续存在、技术状态达到 `ACTIVE`、miss/query failure/native status
保持健康、关闭时 obligations 为空且进程以退出码 `0` 结束时才算通过。MFG 3x 同样必须证明每个基础帧
对应两个实际生成帧，配置倍率和无崩溃不能替代输出证据。原生门禁在首个生成帧后继续观察至少 5 秒，
并要求末尾连续至少 12 次取得请求倍率的真实输出。

Windows 验收需保持测试窗口处于真实前台。GLFW 的 focus 通知不能替代 `GetForegroundWindow`；
本次硬件上观察到后台窗口仅呈现基础帧，回到前台后恢复生成。后台零输出标为
`ENVIRONMENT_NOT_FOREGROUND`，保留非零验收结果，既不算通过，也不判定硬件/API 不支持。
前台观察充分仍无输出或倍率不稳则维持严格失败。详见 [5.0.0 验证记录](../../docs/5.0.0.md)。

可执行 JAR 会重新启动自身并启用 native access。`:demos:hex-ball:run` 会直接应用同一 native
access 策略。确定性合同检查分别由 `:demos:hex-ball:physicsSelfTest` 与
`:demos:hex-ball:featureIntegrationSelfTest` 提供。
# Feature workbench

Run the executable JAR without arguments, or pass `--controls`, to edit independent renderer
and frame policies. Recommended spatial sampling is **1 SPP**; 2/4/8 remain explicit alternatives.
Policy dropdowns expose all public enum values, including required/preferred availability,
reconstruction quality/fallback, FG/MFG mode/multiplier/fallback, denoising, low latency, SER
and memory optimization. Invalid combinations report the API's validation error.

`--api.<policy>.<field>=value` reproduces a renderer override; `--frame.<policy>.<field>=value`
reproduces a frame override. Vector values are comma separated. For example:

```text
--spp=1 --api.frameGeneration.multiplier=THREE_X --api.frameGeneration.mode=MULTI_FRAME_GENERATION
--api.denoising.preference=PREFERRED --api.denoising.builtInTemporalFallback=true
--frame.environment.sunRadiance=1,0.9,0.7
```

`--programmable` opens editable ray-generation/miss/closest-hit/any-hit GLSL, HDR preview,
timeline pause/seek/rate, and byte-offset float updates to immutable application storage data.
The preview tone maps explicitly; renderer readback remains native RGBA16F.
`:demos:hex-ball:programmableSceneDemo` retains the deterministic native HDR acceptance example.

`--media` opens the medium transport laboratory. It exposes absorption/scattering/emission RGB,
density, IOR, HG anisotropy, surface transmission/roughness, signed overlap priority, nested glass,
camera-inside mode, inward winding, invisible volume boundaries and persistent/frame-local lanes.
Interface events, optical samples, scattering intervals and maximum distance apply at the next
frame boundary. The default is 1 SPP with balanced transport quality. See [5.2.1](../../docs/5.2.1.md)
for physical scope, quality limits and shader ABI details.
