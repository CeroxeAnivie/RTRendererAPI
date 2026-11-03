# RTRendererAPI 5.0.0

5.0.0 是一个不兼容的通用渲染 API 版本，发布分支为 `main`。交付渠道为 GitHub 源码和已签名的 Maven Central 制品。

API 保持主机与领域中立。公共模型不包含应用、游戏、引擎、方块或特定领域的光源类型。Java 25 是已发布源码和字节码的基线。

## 变更

- 将 renderer 创建统一到 `Renderer.open(...)`，配置统一到 `RendererConfig.builder()`，并提供明确的 `recommended()` 与 `presentation()` 策略。移除了冗余的 bootstrap/preset 路径和未引用的 `VulkanRendererRuntime` 执行路径。
- 为 ray-generation、miss、closest-hit 和 any-hit 阶段增加公开的 retained-scene GLSL 替换接口。应用可以声明普通只读 std430 storage buffer，并为每帧提供不可变的 `ResourceData`。通用 graphics、compute、triangle RT 和 procedural RT 命令 API 仍可用于 retained-scene 拓扑之外的工作负载。
- 增加原生格式 CPU frame view、显式不可变快照、origin/stride/provenance 元数据和受作用域限制的映射读取。HDR 与整数像素不会被隐式转换。为 programmable scene data 增加直接 `ByteBuffer` buffer upload 和可复用 descriptor scratch。
- 更新 Demo、迁移与参考指南、独立 Maven consumer、ABI baseline、依赖验证、发布校验和 Java 25 CI 配置。

## 验收证据

| Gate | 结果 |
| --- | --- |
| API、core、NVIDIA contracts、打包、JavaDoc、ABI、仓库中立性和 published-consumer graph | 通过，记录于 `build/v5-acceptance.log`（168 个 Gradle tasks） |
| 最终 contracts、assembly、release checksums、独立 published Maven consumer、Demo 检查和 programmable-scene 示例 | 通过，记录于 `build/v5-delivery-final.log`（160 个 Gradle tasks） |
| 公共 programmable-scene 示例 | 通过；原生 RGBA16F 首个 radiance 为 4.0，保留的显式 snapshot 为 32,768 bytes |
| Programmable retained stages、application data mutation、作用域生命周期、HDR 和全部公开 color formats | 在 NVIDIA GeForce RTX 5080 Laptop GPU 上通过；`VulkanModernFrameNativeSelfTest` |
| 通用 raster/RT commands、procedural geometry、resource barriers、integer textures、composition 和 retained complex scene | 通过，记录于 `build/v5-regression.log` 与 `build/v5-acceptance.log` |
| NVIDIA DLSS SR、DLAA、NIS、NRD、RTXMU 以及 NRD/SR 组合路径 | 在 NVIDIA GeForce RTX 5080 Laptop GPU 上通过 |
| DLSS FG 2x，前台窗口 | 通过；实际生成 302 帧，2x 下连续 302 个样本；request misses 和 state-query failures 均为零 |
| 固定 MFG 3x，前台窗口 | 通过；连续 292 个 3x 样本实际生成 584 帧；request misses 和 state-query failures 均为零 |
| NRD + DLSS SR + FG 2x | 通过；连续 302 个 2x 样本生成 302 帧 |
| Capability-adaptive FG/MFG | 通过；生成 795 帧，观测到的最大单批生成帧数为 3，request misses 和 state-query failures 均为零 |
| 历史 Streamline frame-token retirement | 原生回归通过：retire 较旧 tag 时复用其原 token，且不推进 SDK ring；回收后的 token 会在触及其他 frame 前被拒绝 |
| Central bundle、release checksums 和 detached signatures | `verifyCentralPortalBundle` 与 `verifyReleaseChecksums` 通过；本地验证了全部 15 个 detached signatures |

GPU frame-generation 证据记录在 `build/v5-fg-foreground.log` 和 `build/v5-fg-composed-foreground.log`。设备证据采集环境为 Windows 10.0.26300.9711、NVIDIA GeForce RTX 5080 Laptop GPU、driver 616.92、Java 25.0.5 和 Streamline 2.12。这只是一个硬件/driver 配置，不构成跨厂商或跨平台认证。

## Frame-generation 验收

请求的倍率或健康的 SDK 状态不能证明实际生成了帧。验收必须同时满足以下条件：

1. renderer 窗口是实际的 Windows 前台窗口。仅依据 GLFW 缓存的 focus bit 不够。
2. 携带有效 scene motion、depth、motion vectors、constants 和 frame markers 的真实 application frames 到达 proxy present path。
3. `slDLSSGGetState` 报告所请求的 configured multiplier 和健康状态；state-query failures 与 generation-request misses 均为零。
4. 权威的 `numFramesActuallyPresented` 证据证明至少连续 12 次 present 达到所请求的 generated-frame count。首个生成帧之后还要继续观测至少五秒。
5. Renderer 和 presenter 正常关闭，进程退出代码为零。

原生验收 gate 最长运行 60 秒，并报告 elapsed time、实际 base FPS、foreground/focus observations、generated output、multiplier、native status 以及 query/miss counters。如果累计五秒都未观测到窗口处于前台，则报告 `ENVIRONMENT_NOT_FOREGROUND`：这是不确定的环境证据，既非通过，也不代表硬件/API 不支持。如果前台时间充足但没有生成输出或输出未能持续，严格输出 gate 会依据已观测诊断失败。

前台状态的区分依据是本机直接观测。此前一次 60 秒运行交付了 3,594 个 base frames、没有生成帧，但只报告 GLFW focus。配对的前台运行中，Windows 报告前 10 秒没有 foreground presents，生成帧数也一直为零。窗口被切到前台后，生成开始并持续达到 2x，覆盖连续 302 个前台样本。之后 MFG 3x 在 292 个前台样本中生成了 584 帧。用户确认较早的窗口当时处于后台。因此，后台窗口下零输出应归类为 presentation-environment failure，不能作为 renderer 故障证据。

Streamline 当前的 Vulkan 路径在此设备上支持固定 MFG 倍率。SDK programming guide 说明 Dynamic MFG 仅适用于 D3D12；不能把 adaptive API policy 解释为 Vulkan 支持 Dynamic MFG 的证据。上述 adaptive gate 记录的是 Streamline 实际选择的固定 cadence。

## 性能与限制

GPU scene throughput gate 先前测得三次 1080p 运行分别为 410.79、333.61 和 297.94 FPS（GPU average 2.870 ms）；三次 windowed 运行分别为 338.66、228.21 和 218.20 FPS。这些是有边界的本地测量，不保证应用吞吐量。此 driver 上冷启动 pipeline creation 约需 166.5 秒；warm throughput 不含该启动成本。

已实现 backend 的目标平台是 Windows、NVIDIA RTX 硬件和 Vulkan。本次验收未认证 AMD、Intel、Linux、macOS、D3D12、Metal、软件渲染、跨 driver 稳定性或任意 host-engine 集成。GPU frame-generation 验收要求交互式前台显示器；不能根据 headless 或后台窗口运行推断功能失败。

## 交付

将审核后的 5.0.0 源码和文档推送到 GitHub `main`，然后提交已验证的签名 bundle，以触发 Maven Central 自动发布。Upload acceptance 和返回的 deployment ID 只证明提交成功；异步验证、发布以及仓库传播是独立状态。此次交付在 upload acceptance 后结束，不继续轮询这些状态。
