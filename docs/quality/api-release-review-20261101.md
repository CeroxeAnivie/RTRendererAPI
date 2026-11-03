# 7.0.0 发布前 API 检查

后续 7.1 普通／专家入口审查见[当前用法选择](../ordinary-and-expert.md)。本页保留原日期的审查证据，不将后续策略工厂的 CPU 检查当作本页历史 GPU 验收。

> 清理说明（2026-11-02）：历史 JSON／CSV／JSONL 等实验原始数据及一次性诊断脚本已清理；本文结论、参数和截图保留。原始文件见[清理前版本](https://github.com/CeroxeAnivie/RTRendererAPI/tree/eb3bc21c8b7559dd847c575ca3f392a2f896d86f/docs/quality)，当前工具与验证入口见[工具说明](../../tools/README.md)。

普通和专家模式共用不可变场景、帧和配置模型。普通调用方用 preset、场景事务和帧请求；专家按需发现扩展、规划功能变更或提交通用命令，不需要另一套互不兼容的对象模型。

## 入口怎么选

| 目标 | 入口 | 生命周期和观察 |
|---|---|---|
| 普通表面、离屏 CPU 帧 | `Renderer.open()` | `apply` → `submit` → 有界 `awaitFrame`；用 `FrameView.format()` 解释原始格式 |
| 普通 Vulkan GPU 窗口 | `Renderer.open(RendererConfig.presentation())` + `VulkanFramePresenter` | 主动 poll/present；presenter 先于 renderer 关闭 |
| 吸收玻璃、嵌套折射、混合 PBR | `Renderer.open("rtxpt", RendererConfig.pathTracedPresentation())` | 首次 submit 后用 `RtxptAccess` 控制窗口；全部在创建线程；一个进程一个 session |
| Vulkan 自定义 shader/图形/计算/显式 RT | `SceneShaderProgram` 或资源/命令事务 | 先检查 `renderingSemanticCapabilities()`；显式提交与证据、资源所有权和同步 |
| 高级功能开关 | `RendererConfig`；运行期 `RendererFeatureController` | plan → 检查 boundary → apply；需要 rebuild 时显式重建，不当成已生效 |

普通 preset 是政策请求，不是实际执行证明。功能状态读 `RenderingFeatureCapabilities`，FG/MFG 产出读 `FrameGenerationEvidence`；RTXPT 具体组合读 `RtxptAccess.runtimeStatus()`。原生循环 `renderFrame()` 的 true 表示窗口继续运行，可能因为最小化而没有完成新帧；实际完成帧看 bridge 的 `frames()` 差值或 SDK 证据。

`pathTracedPresentation()` 默认 FG 关闭，用于可比性能测量。应用可通过 `copyBuilder().frameGeneration(FrameGenerationOptions.recommended()).build()` 请求普通 2× FG。Demo 自己的默认政策与 API preset 分开管理。

## 为什么不能删除 Vulkan 后端

| 能力 | Vulkan | 当前 RTXPT 桥接 |
|---|---|---|
| 吸收/IOR/嵌套介电/粗糙透射 | 不代替 RTXPT 托管介质 | 支持收缩合同，算法交给上游 |
| 通用 graphics/compute、render pass、draw、barrier、显式 BLAS/TLAS/SBT | 资源/命令事务及语义能力表 | `submitResources`、`submitCommands` 明确 REJECTED，语义能力表 unsupported |
| 四阶段 GLSL/SPIR-V 替换、应用 shaderData | `SceneShaderProgram` | 拒绝；采用上游 shader |
| 应用精确投影、自定义 screen fog、光子图预算 | 普通帧/专家模型可表达 | 拒绝，不做假映射 |
| 原生格式 CPU 帧、linear HDR RGBA16F、外部 GPU consumer | `FrameView`/`FrameSnapshot`、Vulkan interop | GPU SDR 窗口呈现；显式 PNG 捕获仅供诊断，不是 readback 流 |
| 每帧确定性 AA、lightmap、纹理 footprint 策略 | 普通帧模型 | 拒绝非中性请求；样本数改用 `PathTracingOptions`，灯光/采样交上游 |
| 实例 mask、无阴影实例、surface overlay、自定义渲染状态、剪切变换 | 公共场景/专家模型 | `RtxptScene` 明确拒绝不能表达的组合 |

具体实现依据：`RtxptRenderer.submitResources/submitCommands/renderingSemanticCapabilities`、`RtxptInputContract`、`RtxptScene.validate/validateInstance`。某个弹球场景 FPS 达标不能推出上述通用图形能力已被 RTXPT 覆盖。若只发布 RTXPT 专用游戏光追 API，可以另作产品收缩；当前通用图形定位下不能移除 Vulkan。

## 本次确定修复

1. RTXPT 曾忽略配置中的自定义 scene shader、HDR 输出、GPU 选择和 validation 控制，现在在初始化前给出具体拒绝原因。
2. RTXPT 曾忽略帧中的确定性多采样、非中性 lightmap、纹理 footprint 覆盖和显式 history reset，现在在修改原生状态前拒绝。不把“不支持”标为“已执行”。
3. 场景 revision 曾允许重复，现在按公共合同严格递增，首次 revision 0 仍可发布。首次帧之后也不能重新提交旧 revision 0。
4. 通用命令/证据入口补齐 creating-thread、关闭状态和空参数检查，不再在关闭后伪装正常拒绝结果。
5. `FrameOutputFormat` 的过期 Javadoc 已纠正：HDR 快照保留半浮点格式，不会自动转成 RGBA8。Java 指南去掉旧 CUDA 介质程序选择描述，并标明 Vulkan 专家配置适用后端。
6. RTXPT 专家控制器分开保存“已提交的请求”和“实际执行快照”。临时 FG 暂停/回退后恢复不会丢失请求；观察到新执行状态时旧计划失效、旧结果不会与新快照混装。修复了 `effectiveProfile()` 后再取 `featureControlDiagnostics()` 可能抛出 profile 不一致异常的问题；模拟回归覆盖启用→回退→恢复、计划后状态变化、回退期间主动关闭。
7. 发布供应链任务仍强制从 core JAR 读取已撤下的 NVRTC manifest，会阻塞干净构建或把旧缓存当成依赖。已删除旧下载/打包助手和虚构的 CUDA 清单条目，只从当前 runtime graph 生成 SBOM/许可证清单，并校验不再出现旧 NVRTC 模块。RTXPT 适配模块纳入许可证策略；原生运行时继续携带各上游原始许可。

CPU 证据由 `RtxptAdapterSelfTest`、`RendererApiContractSelfTest` 和 API 边界检查提供；GPU 画面、功能组合和吞吐证据仍分别以已有原生验收与本次 Demo 验收为准。上述修复不表示所有硬件、所有组合与所有公开方法都经过穷尽验证；“收缩后的介质合同闭合”和“RTXPT 可替代整个通用图形 API”是两件事。

## 2026-11-02 干净构建与发布检查

发布前已执行各模块及 Demo 的 `clean`，删除旧 JAR，再关闭构建缓存重新编译。RTXPT SDK 与已校验的六档 shader 构建输入保留，不把它们当作旧制品删除。

本轮修复的是验证工具与构建配置：

1. 完整 gate 纳入 RTXPT 原生、打包、预算、高级组合、Vulkan 表面 Demo 与跨后端组合测试；GPU 验证串行执行，禁止用子集开关代替完整验收。
2. 独立 Maven 消费模板同步当前 `Medium`、`MediumBoundary` 和 `PathTracingOptions`，不再依赖已删除的 CUDA manifest。API 单依赖的传递依赖、provider 加载和原生文件哈希检查保留。
3. 预编译 shader 测试同步当前 16 个 raygen 入口，移除对退役介质 shader 的存在要求。移除源码中引用已删除文件的不可达 include 分支后，158 份固定 SPIR-V 与重新编译结果逐字节一致。
4. Primary queue 测试正确处理场景收敛期间的背压，核对实际 scene revision，并只在测试内启用队列计数诊断；原始像素一致性断言保留。
5. RTXPT 高级验收改为实测 DI＋GI、DI＋SHaRC、GI＋SHaRC 三组支持组合；不再请求 API 已拒绝的 DI＋GI＋SHaRC。八组渲染配置、FG/MFG 倍数切换和同 JVM 顺序关闭重开已通过聚焦验收。
6. 用户 PowerShell 工具集中到 `tools/` 并提供路径检查；本机 SDK 与 CMake 路径改由参数、环境变量或 PATH 提供。一次性脚本和历史原始数据清理后，报告保留到清理前提交的恢复链接。

生成 JAR 通过 Maven Local 交付；GitHub 只提交源码、文档及必要构建输入。`.gitignore` 排除生成 JAR，既有 Gradle Wrapper 保留。

GraalVM 25 在一次吞吐验收中发生 JVM 崩溃；按相同用例重试，测试进程临时使用 `-XX:-UseJVMCICompiler -XX:TieredStopAtLevel=1` 后正常退出，800 个 GPU 计时样本无丢失或失败。这些选项仅用于本轮验证进程，不修改库或 Demo 默认启动配置；本轮 gate 用于正确性与打包验收，不作为新的 FPS 性能基线。
