# Java API 参考

> 公共包根：`top.ceroxe.rt.renderer`
>
> 本文聚焦消费方直接使用的稳定 API。`renderer-core` 中的 Vulkan backend、GPUScene、BLAS/TLAS、pipeline 和调度类型属于实现细节，不是公共入口。

## 目录

- [通用 command path](#通用-command-path)
- [External frame composition](#external-frame-composition)
- [后端与会话](#后端与会话)
- [Renderer](#renderer)
- [RendererConfig](#rendererconfig)
- [RTXPT 与组合入口](#rtxpt-与组合入口)
- [场景与帧](#场景与帧)
- [资产与实例](#资产与实例)
- [设备、诊断与异常](#设备诊断与异常)
- [运行期功能控制](#运行期功能控制)
- [官方 Vulkan presenter](#官方-vulkan-presenter)
- [Vulkan 专家扩展](#vulkan-专家扩展)
- [通用命令与硬件光追](#通用命令与硬件光追)

## 后端与会话

```java
public interface Renderer extends AutoCloseable
```

后端发现与打开的唯一公共入口。通过 `ServiceLoader` 发现 `RendererBackendProvider`，按 descriptor priority 和兼容性结果选择后端。

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `open()` | `Renderer` | 简单模式；使用推荐 CPU-readable 配置打开最佳可用后端 |
| `open(RendererConfig)` | `Renderer` | 使用显式配置打开最佳可用后端；保留普通场景和专家入口 |
| `open(String, RendererConfig)` | `Renderer` | 按 provider id 与显式配置打开，用于确定性部署或诊断 |
| `openForScene(RendererBackendPolicy, SceneTransaction, RenderFrameRequest)` | `RendererSceneSession` | 为完整 reset 场景选择固定后端，并发布场景／提交首帧 |
| `openForScene(policy, scene, firstFrame, vulkanConfig, rtxptConfig)` | `RendererSceneSession` | 同上，分别显式指定两个后端配置，只打开选中的一个 |
| `devices()` | `List<RendererGpuDevice>` | 返回通过 provider 探测得到的不可变设备列表 |

`RendererBackendPolicy` 包含 `VULKAN_ONLY`、`RTXPT_ONLY` 和 `RTXPT_FOR_MEDIA`；后者为整个场景选择一个后端。`RendererSceneSession.selection()` 返回固定选择，`initialSubmission()` 返回已提交首帧的接受证据。后续帧必须推进 sequence。类别发生变化时显式关闭重开，不偷偷替换窗口、设备或扩展。完整依赖与分类规则见[后端路由](backend-routing.md)。

## 通用 command path

```java
public interface Renderer extends AutoCloseable
```

Vulkan `Renderer` 同时承载 retained-scene 与通用 command path；RTXPT 原生后端不实现任意 Vulkan 命令。`RenderWorkload.Mode` 是工作负载
discriminator；直接使用 `apply/trySubmit` 或 `submitCommands` 时，调用的方法本身就是明确模式，
不会隐式修改或改写另一条路径。

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `renderingSemanticCapabilities()` | `RenderingSemanticCapabilities` | 每项通用语义的真实可执行状态；不存在“设备支持 Vulkan 即全部可用”的推断 |
| `submitResources(RenderResourceTransaction)` | `ResourceTransactionEvidence` | 原子发布/回收精确资源 generation；接受不等于 GPU ready |
| `resourceResidencyEvidence(ResourceGenerationKey)` | `Optional<ResourceResidencyEvidence>` | 查询 exact generation 的 accepted/recorded/ready/retirement 证据 |
| `submitCommands(RenderCommandTransaction)` | `CommandExecutionEvidence` | 有序 command admission；返回时最高只可能是 recorded |
| `commandExecutionEvidence(long)` | `Optional<CommandExecutionEvidence>` | 观察与 transaction sequence 关联的后续 fence completion |

普通场景调用方不需要创建这些对象。专家调用方必须先发布精确 resource generation，再提交仅引用这些 generation 的 command transaction，并始终以 capability 与 typed evidence 作为可执行性依据。

## External frame composition

`renderer.extension(FrameCompositionProvider.class)` 是从 generic command output 到 provider-owned
external frame ring 的唯一公共桥接。它不是 `FrameCompositionPlan.target()` 的别名：targetless
`FrameCompositionRequest` 让 provider 选择真正可写的 frame slot，返回的 `FrameCompositionEvidence` 使用
独立的 frame sequence/scene revision，不伪装成普通 `ResourceMutationKey`。

```java
FrameCompositionProvider composition = renderer.extension(FrameCompositionProvider.class)
        .orElseThrow(() -> new IllegalStateException("backend has no executable frame composition"));
FrameCompositionRequest request = new FrameCompositionRequest(
        List.of(new FrameCompositionPlan.Layer(
                new ResourceMutationKey(outputGeneration, commandSequence),
                FrameCompositionPlan.Operation.REPLACE)),
        2560, 1440, FrameOutputFormat.SDR_RGBA8, nextFrameSequence, sceneRevision,
        FrameCompositionRequest.AlphaEncoding.PREMULTIPLIED);
FrameCompositionEvidence evidence = composition.compose(request);
if (evidence.outcome() == FrameCompositionEvidence.Outcome.REJECTED) {
    throw new IllegalStateException(evidence.detail());
}
FrameCompositionEvidence latest = composition.compositionEvidence(evidence.frameSequence())
        .orElseThrow();
```

每个 source 必须仍 resident，且对应 command evidence 已达到 `OUTPUT_PRODUCED`；generation、command
sequence、format、extent 与 storage-read usage 都会精确验证。第一层必须是 `REPLACE`，后续层按顺序执行
`REPLACE`、`ALPHA_OVER` 或 `ADDITIVE`；`ALPHA_OVER` 的输入约定是 premultiplied alpha。请求形状不固化
层数上限，调用方应先读取 `FrameCompositionProvider.maxLayers()`，provider 会对超过自身能力的请求 fail-closed。
`SUBMITTED` 只证明 composition command 已进入 provider-owned frame ring；GPU completion、
consumer acceptance 和 `VISIBLE` 是独立事实，不能由该值推导。未能完整执行 source validation、GPU barrier、
frame-slot ownership 和 external lease 的 backend 必须返回空 extension。
`compositionEvidence(frameSequence)` 是后续状态的权威只读查询；Vulkan 仅在 exact external lease 发布 consumer
completion 后推进到 `CONSUMER_ACCEPTED`。该 provider 不拥有显示系统，因此不会伪造 `VISIBLE`。

`BindingType.COMBINED_IMAGE_SAMPLER` 以一个 `BindingSet.CombinedImageSamplerValue(TextureView, SamplerState)`
表达一个 binding 元素。它对应 Vulkan 的 `VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER`，不是
`SAMPLED_TEXTURE` 与 `SAMPLER` 的便利组合；shader reflection、binding layout 和 binding set 都必须使用
同一精确类别。

### 通用命令与硬件光追

4.2.0 新增 18 种整数纹理格式、`TextureFormat.numericClass()`、`bytesPerTexel()`，以及
`ClearValue.SignedColor` / `UnsignedColor`。整数采样与清除必须匹配精确的有符号／无符号类型；
整数附件关闭 blending。CPU 回读保留整数原始格式，详见[整数纹理契约](Integer-Textures.md)。

专家入口先使用 `renderingSemanticCapabilities()` 确认必需语义为 `EXECUTABLE`，再依次调用
`submitResources(...)` 与 `submitCommands(...)`。资源的 accepted、写入 recorded、GPU ready、
retirement 与 command output 都是不同的类型化 evidence，不能由构造成功或日志推断。

[通用命令与硬件光线追踪指南](Generic-Commands-and-Ray-Tracing.md) 提供完整顺序：资源发布、
同 transaction 的上传与 BLAS/TLAS build、SPIR-V reflection、RT pipeline/SBT、typed AS binding、
`TraceRaysCommand`、完成轮询和精确 retirement。后端不把 graphics shader 猜测性转换成 PBR 或
RT hit shader；不能精确执行的 shader、格式或同步要求会在 admission 阶段拒绝。

4.1.0 新增以下公共类型；原三角形构造与访问方法保持不变。

| 类型 | 契约 |
| --- | --- |
| `AccelerationStructureAabbGeometry(BufferSlice, long, int, boolean)` | 精确 bounds slice、字节 stride、primitive 数量和 opaque flag；offset/stride 八字节对齐，每条 bounds 为六个 float32 |
| `BuildProceduralBottomLevelAccelerationStructureCommand(AccelerationStructureResource, AccelerationStructureBuildMode, List<AccelerationStructureAabbGeometry>)` | render pass 外执行，非空 AABB geometry 集合；UPDATE 要求已有可更新 BLAS，且类型、数量、primitive 数量和 flags 不变 |

使用既有 `RayTracingShaderGroup.procedural(closestHit, anyHit, intersection)` 关联命中组；intersection
shader 必须存在。详见 [Procedural geometry](Procedural-Geometry.md) 与
[版本兼容策略](COMPATIBILITY.md)。

## Renderer

```java
public interface Renderer extends AutoCloseable
```

线程安全边界、状态转换和资源所有权由具体方法契约定义。调用方必须确定性关闭实例。

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `status()` | `Status` | 当前生命周期状态 |
| `apply(SceneTransaction)` | `SceneUpdateResult` | 原子应用场景事务并返回 accepted revision |
| `submit(RenderFrameRequest)` | `FrameSubmissionResult` | 提交一帧；sequence 必须严格递增 |
| `trySubmit(RenderFrameRequest)` | `FrameSubmissionAttempt` | 容量拒绝返回 deferred，其他契约错误保持 typed exception |
| `readFrame(FrameConsumer)` | `boolean` | 直接消费作用域原生像素，回调返回后视图失效 |
| `pollFrame()` | `Optional<FrameSnapshot>` | 非阻塞读取最新托管帧 |
| `awaitFrame(Duration)` | `Optional<FrameSnapshot>` | 有界等待托管帧 |
| `diagnostics()` | `RendererDiagnostics` | 当前诊断快照 |
| `health()` | `RendererHealth` | 结构化失败与资源欠账 |
| `extension(Class<T>)` | `Optional<T>` | 显式发现可选专家扩展 |
| `close()` | `void` | 关闭 renderer；未归还资源会按契约报告 |
| `closeAsync()` | `CompletionStage<Void>` | 请求关闭，仅在 native 资源实际释放后完成 |
| `awaitClosed(Duration)` | `boolean` | 请求关闭并有界等待真实资源释放；超时返回 `false` |

`FrameSubmissionDeferred.deferralReason()` 和 `SubmissionRejectedException.deferralReason()` 返回稳定的
`SubmissionDeferralReason`，用于重试策略和遥测聚合；`detail()` 仅用于人类诊断。所有 provider
必须提供类型化分类，API 不从自然语言猜测类别。

普通模式向所选后端提交完整场景和帧。透明介质与透射场景可显式选择 RTXPT，或通过 `RTXPT_FOR_MEDIA` 路由。Vulkan 专家模式由显式资源／命令事务或 `SceneShaderProgram` 阶段替换表达；自定义阶段不会被普通模式优化覆盖。配置入口统一使用 `RendererConfig`，不存在独立的 `expertBuilder`。

CPU 帧方法仅对提供该输出的后端有效：Vulkan 支持原生格式读取；RTXPT 要求关闭 CPU readback，使用原生窗口与 `RtxptAccess`。不能把 GPU 已呈现推导为有 `FrameSnapshot` 可取。

## RendererConfig

普通调用方使用 `Renderer.open()`，或者把完整推荐配置传给 `Renderer.open(config)`：

| 值 | 说明 |
| --- | --- |
| `RendererConfig.recommended()` | CPU frame 路径；优选重建、SER 与 AS memory optimization，不请求 FG/MFG；NRD 显式启用 |
| `RendererConfig.presentation()` | 官方 GPU presenter 路径；关闭 CPU readback，并在支持时额外优选普通 FG 2x 与低延迟 pacing；不自动请求 MFG |
| `RendererConfig.pathTracedPresentation()` | RTXPT GPU 路径；关闭 CPU readback，优选重建、降噪、低延迟与辐射缓存；FG 默认关闭 |

派生配置使用 `copyBuilder()`，并通过
`Renderer.open(...)` 打开。

```java
public final class RendererConfig
```

配置使用统一 Builder 与 `copyBuilder()`，不提供旧接口别名或有序兼容构造器。

| 静态入口 | 说明 |
| --- | --- |
| `builder()` | 创建统一 Builder；可选 vendor 技术均禁用 |

已有配置通过 `copyBuilder()` 派生，简单默认值和专家配置使用同一个不可变类型。

`MIN_MAX_FRAMES_IN_FLIGHT`、`DEFAULT_MAX_FRAMES_IN_FLIGHT` 和
`MAX_MAX_FRAMES_IN_FLIGHT` 分别定义合法下界、默认值和上界。

| Builder 方法 | 说明 |
| --- | --- |
| `maxFramesInFlight(int)` | 帧环深度 |
| `validationEnabled(boolean)` | Vulkan validation 策略 |
| `gpuTimingsEnabled(boolean)` | GPU timing 策略 |
| `cpuFrameReadbackEnabled(boolean)` | 是否准备异步 CPU 帧；默认开启；`readFrame` 无像素所有权复制，快照方法显式复制 |
| `sceneShaders(SceneShaderProgram)` | 替换四个 retained-scene GLSL 阶段并声明应用缓冲区绑定数 |
| `gpuDevice(RendererGpuDevice)` | 绑定枚举所得稳定设备对象 |
| `automaticGpuSelection()` | 恢复 provider 的自动设备选择 |
| `frameOutputFormat(FrameOutputFormat)` | SDR RGBA8 或 linear HDR RGBA16F |
| `temporalRendering(TemporalRenderingOptions)` | temporal reconstruction 策略 |
| `frameReconstruction(FrameReconstructionOptions)` | DLSS SR、DLAA、NIS 或禁用策略 |
| `frameGeneration(FrameGenerationOptions)` | 互斥 FG/MFG 模式、倍率与 fallback 策略 |
| `lowLatency(LowLatencyOptions)` | Reflex/PCL 请求策略 |
| `denoising(DenoisingOptions)` | NRD 请求与 fallback 策略 |
| `rayTracingOptimizations(RayTracingOptimizationOptions)` | SER 与 RTXMU 独立策略 |
| `pathTracing(PathTracingOptions)` | 每像素样本、路径深度、辐射缓存、直接／间接光重采样意图，由支持该配置的后端执行 |
| `build()` | 验证全部不变量并创建配置 |

`PathTracingOptions` 是 record：`(int samplesPerPixel, int maxBounces, int diffuseBounces, RendererFeaturePreference radianceCache, RendererFeaturePreference directLightResampling, RendererFeaturePreference indirectLightResampling)`。样本与最大深度各为 1–64，漫反射深度为 0–maxBounces；请求光重采样时必须为 1 SPP。`defaults()` 为 `(1,20,2,DISABLED,DISABLED,DISABLED)`，`recommended()` 仅将辐射缓存改为 `PREFERRED`。每帧 `AntiAliasingState` 的多采样不能替代 RTXPT 的这个入口。

## RTXPT 与组合入口

以下公开类型来自可选 `renderer-rtxpt` 模块，包为 `top.ceroxe.rt.renderer.rtxpt`。先提交首帧，再驱动原生窗口；所有操作在创建线程执行，一个进程同时只允许一个 RTXPT 会话。

| 类型／方法 | 说明 |
| --- | --- |
| `renderer.extension(RtxptAccess.class)` | 借用原生宿主控制接口，不转移 renderer／窗口所有权 |
| `RtxptAccess.renderFrame()` | 渲染并呈现驻留场景，窗口关闭返回 false |
| `runtimeStatus()`／`presentationStatus()` | 实际内部／输出尺寸、重建、降噪、复用技术及高级呈现设置 |
| `setExposureCompensation(float)` | 设置上游 tone mapper 的有限 EV 偏移；-1 表示曝光减半，可在首帧前设置并跨场景重载保留；不增加渲染阶段，也不负责降噪 |
| `setWindowTitle(String)`／`windowHandle()` | 首帧后管理标题／借用原生 HWND，关闭后失效 |
| `setOverlayText(String)` | 上游 GPU HUD；空串关闭该 HUD 及其计时查询 |
| `setOverlayChoices(List<String>, int)`／`overlaySelection()` | 同一原生 HUD 内的选项；空列表隐藏，未配置返回 -1 |
| `loadNativeScene(Path)` | 加载原生上游场景，绕过 Java retained 适配；与 Java 场景路径二选一 |
| `capture(Path)`／`awaitIdle()` | 显式单次捕获／GPU 排空；正常帧循环不调用 |
| `bridgeTimings()` | 驻留渲染、场景导出与变换批次的计数／耗时；不是 FG 交付证据 |
| `RtxptComposedRenderer.open()` | 默认 RTXPT 完整 3D／最终呈现，旧 Vulkan 独立工作与 UI 按需创建 |
| `openRtxptPresentation(RendererConfig, RendererConfig, String)` | 专家分别配置旧侧与 RTXPT；RTXPT 唯一拥有 Streamline |
| `openVulkanPresentation(RendererBackendPolicy, RendererConfig, RendererConfig, VulkanFramePresenterConfig)` | 专家同窗切换完整 3D 后端；RTXPT FG/MFG/Reflex 和旧侧 Streamline 均须关闭 |
| `legacyCommands()`／`legacyExtension(Class<T>)` | 借用旧侧命令／扩展；不得自行关闭拥有者 |
| `overlays(List<FrameCompositionPlan.Layer>)` | 最多 7 层已完成、全尺寸 RGBA8_UNORM GPU 纹理 |
| `trimIdleCompositionResources()` | 原生最终呈现且叠层为空时，在空闲维护窗口排空／回收交接缓存 |
| `backendPolicy(RendererBackendPolicy)` | 仅 Vulkan 最终呈现入口允许改变完整 3D 选择；原生最终呈现固定 RTXPT |
| `captureRtxptSource(Path)` | 显式捕获旧侧叠层之前的 RTXPT 源 |

原生 RTXPT 当前只接受 GPU 直显 SDR，不接受 CPU readback、线性 HDR 导出、显式 Vulkan GPU 对象、validation-layer 开关或 `SceneShaderProgram` 替换。帧输入也不接受 exact projection、shader data、屏幕雾、旧焦散预算、逐帧纹理／lightmap 覆盖与历史 reset。任意命令、自定义光传输和两张独立图不能自动合为一个正确的 3D 场景。当前限制、叠层同步和可执行验证见[组合指南](d3d12-vulkan-composition.md)与[版本合同](7.0.0.md)。

## 场景与帧

### SceneTransaction

```java
public final class SceneTransaction
```

| 静态入口 | 说明 |
| --- | --- |
| `empty(long revision)` | 创建不含资源变化的 revision 屏障 |
| `builder(long revision)` | 创建 upsert/removal 事务 Builder |

Builder 支持 `upsert(TextureAsset)`、`upsert(MaterialAsset)`、`upsert(MeshAsset)`、`upsert(SceneInstance)`、`upsert(SceneLight)` 与对应 removal。单个事务内不能同时 upsert 和 remove 同一 identity。

### RenderFrameRequest

```java
public final class RenderFrameRequest
```

```java
RenderFrameRequest.builder(sequence, width, height, camera)
        .minimumSceneRevision(revision)
        .environment(environment)
        .lightmap(lightmap)
        .fog(fog)
        .textureSampling(sampling)
        .antiAliasing(antiAliasing)
        .resetTemporalHistory(reason)
        .build();
```

`toBuilder()` 用于从已有请求安全派生下一帧。不要复用旧 sequence。

### 介质

7.0.0 当前托管合同使用下面三个 record，不保留旧散射／密度 API：

| 类型 | 契约与入口 |
| --- | --- |
| `Rgb(float red, float green, float blue)` | 有限非负线性 RGB；`ZERO`、`WHITE`、`add`、`multiply`、`maxComponent`、`isZero` |
| `Medium(Rgb absorption, float indexOfRefraction)` | 每世界单位非负有限 RGB 吸收系数；IOR 为 `[1,4]`；`vacuum()`、`isVacuum()` |
| `MediumBoundary(Medium medium, int priority)` | 封闭、朝外绕序且有表面交互的内部；优先级 `[0,14]`；`surface(medium)` 默认优先级 0，`withMedium`、`withPriority` 派生新值；最多 4 层嵌套内部 |

通过 `SceneInstance.Builder.mediumBoundary` 或 `PrimitiveInstance.Builder.mediumBoundary` 定义内部，通过材质 `transmission`／`roughness` 定义表面透射。吸收经 Beer 关系交给 RTXPT attenuation；Fresnel、折射、全反射、粗糙透射及嵌套介电由上游执行。

体积单／多次散射、密度网格、HG、体积发光、无限／相机初始介质及不可见边界已删除；不再提供 `MediumDensityField`、`MediumTransport` 或 `cameraMedium`。当前范围见[7.0.0](7.0.0.md)，完整运行示例见[Java 教程](Java.md#rtxpt-透明介质与原生窗口)。

### FrameSubmissionAttempt

`trySubmit(...)` 返回穷尽的 `FrameSubmitted` 或 `FrameSubmissionDeferred`。deferred 不发布逻辑/native 状态，因此 sequence 只能在 submitted 后推进。它消除正常背压的异常构造与 stack capture；顺序、revision、生命周期和设备失败仍抛 typed exception。

### CameraState

使用 `CameraState.lookAt(eyeX, eyeY, eyeZ, targetX, targetY, targetZ)` 创建 Builder，再设置 aspect ratio、FOV、near/far plane 等参数。

### ExactProjectionState

`ExactProjectionState` is the generic exact projection contract. It requires an explicit matrix
layout, right-handed `-Z` view convention, viewport, depth convention, and jitter convention.
`CameraState.exactProjection(mapping)` selects the `EXACT_CLIP` discriminator; the existing
`lookAt` and `explicitBasis` factories select `BASIS_FOV` and retain their old ABI and behavior.
The Vulkan provider consumes the inverse clip matrix and camera-to-world rotation in primary
raygen. Singular, non-finite, non-rigid, missing-layout, or viewport-mismatched input fails closed.

`MeshAsset.vertexColorsRgba8()` remains raw numeric RGBA8 channel data. It is not sRGB-decoded;
that gamma meaning is intentionally unchanged.

### FrameSnapshot

原生格式的不可变 CPU 帧副本。`format()` 决定组件编码，`pixels()` 返回只读 little-endian 字节，HDR 和整数不会隐式转成 SDR。高频处理用 `readFrame(FrameConsumer)` 的作用域视图；需要保存时显式 `FrameSnapshot.copyOf(view)`。

### TemporalRenderingOptions

| 工厂 | 语义 |
| --- | --- |
| `disabled()` | 禁用跨帧历史 |
| `balanced()` | 生产推荐的平衡历史策略 |
| `accumulating(int)` | 显式设置 2 到 64 帧历史上限 |

## 资产与实例

| 类型 | 推荐入口 | 作用 |
| --- | --- | --- |
| `TextureAsset` | `color(...)`、`colorMipChain(...)`、`builder(...)` | RGBA8 texture 与 mip chain |
| `MaterialAsset` | `builder(id)` | PBR/材质状态、receiver-aware overlay 与 texture 引用 |
| `MeshAsset` | `triangles(...)`、`builder(...)` | 顶点属性、索引和逐三角形材质 |
| `SceneInstance` | `builder(id, meshAssetId)` | transform、visibility、lightmap 与实例级 lighting |
| `SceneLight` | `point(...)`、`directional(...)`、`spot(...)` | typed light 与物理参数 |

安全工厂复制调用方数组。需要零额外复制时只能使用标明 `wrapImmutableDirect` 的入口，并保证 direct buffer 在完整资源生命周期内不再被修改。

`SceneInstance` 默认使用 `FULL_BRIGHT_PACKED_LIGHT`。没有逐顶点 lightmap 坐标的 mesh 可调用
`lightmapCoordinates(first, second)` 设置两个位于 `[0, 240]` 的坐标；已有 packed host 数据可调用
`packedLight(value)`，其低、高 unsigned 16-bit 半字分别表示 first、second coordinate，并执行相同范围校验。
光照属于实例 shading state；移动实例只需 upsert 新实例 generation，不应修改 mesh 或重建 BLAS。

### SurfaceOverlayState

`depthEqual(tolerance)` 与 `depthBias(maximumBias)` 保持 `ALPHA_OVER` 默认语义。需要裂纹或破坏
纹理时，使用接收 `CompositionMode` 的重载并选择 `MULTIPLY`。其精确定义为
`receiver * mix(1, overlayRgb, alpha)`，不是 alpha blending 的近似；overlay 仍复用普通 mesh
BLAS，并由 `InstanceRenderState.overlayReceiverMask()` 与 receiver 的 `surfaceMask()` 做匹配。

### CardinalLightingState

`CardinalLightingState.objectSpace(...)` 和 `worldSpace(...)` 分别按 object/world 几何法线的
dominant axis 选择 -X/+X/-Y/+Y/-Z/+Z multiplier。六个值均为 `[0, 1]` 内的有限数，默认
`disabled()` 等价于六个 `1.0`。分类只读取未扰动几何法线，normal map 不改变 face 归属；结果
在材质 base-color 采样后进行 RGB 调制。状态属于 `InstanceRenderState`，因此不同实例可以共享
同一 mesh/BLAS 而采用不同方向光照。

### DirectionalDiffuseState

`DirectionalDiffuseState.builder()` 定义最多两条 surface-to-light 方向、各自的 `[0, 1]` 强度、
`[0, 1]` ambient、object/world 坐标空间与显式背面策略。active state 的非零强度必须配套显式
方向；方向必须为非零有限向量并在构建时归一化。精确调制公式为
`clamp(ambient + I0 * max(dot(N, L0), 0) + I1 * max(dot(N, L1), 0), 0, 1)`。

`N` 是 normal map 之前的重心插值顶点法线；没有 normal stream 时回退到几何法线。
`KEEP_AUTHORED` 保留 mesh 朝向，`FLIP_ON_BACK_FACE` 在背面命中时翻转。unit ambient 会规范化为
共享 `disabled()`，因为非负直射项不可能改变最终的 unit multiplier。该状态与
`CardinalLightingState` 互斥，冲突配置由 `InstanceRenderState.Builder.build()` 拒绝；两者都只进入
实例 SSBO，不改变 mesh 或 BLAS。

## 设备、诊断与异常

### RendererGpuDevice

设备对象提供 provider id、stable id、名称、类型、API version 和完整 `HardwareCapabilities`。
设备选择应依据 stable identity 与能力，不依据展示名称。`hardwareCapabilities()` 是物理设备事实；
`RenderingFeatureCapabilities` 是已打开 session 的协商/运行状态，两者不得互相推导。

### HardwareCapabilities

`probeState()` 必须为 `COMPLETE`，`supports(feature)` 才可能返回 true。每项 `Support` 都是
`SUPPORTED`、`UNSUPPORTED` 或 `UNKNOWN`，失败/缺失查询不能乐观升级为支持。
`frameInterop(format, handleType)` 按输出格式与 native handle 精确区分 memory export/import、
semaphore export/import 和 dedicated-allocation 要求；扩展名存在本身不构成互操作证据。

### RendererDiagnostics

不可变诊断快照，包含状态、场景/帧进度、GPU timing 和 device recovery 信息。使用 Builder 构造内部/测试快照，消费方通常只读取。

### RendererHealth

结构化健康快照：

- `Failure`：稳定 failure kind、recovery action、operation 和可选 native result。
- `ResourceObligations`：outstanding GPU lease、native cleanup 与 device recovery 欠账。

### 异常层次

```text
RendererException
├── RendererUnavailableException
├── RendererInitializationException
├── RendererDeviceException
├── RendererStateException
├── SceneValidationException
├── SceneRevisionException
├── SubmissionOrderException
└── SubmissionRejectedException
```

## 运行期功能控制

`RendererFeatureController` 是显式专家扩展：

```java
RendererFeatureController controller = renderer
        .extension(RendererFeatureController.class)
        .orElseThrow();
RendererFeaturePlan plan = controller.plan(targetProfile);
```

`RendererFeatureProfile` 是完整、厂商中立的目标策略，不允许用省略字段表达隐式意图。plan 绑定
controller generation 且只能消费一次。`Disposition.APPLICABLE` 只允许 `NEXT_FRAME` 或
`FRAME_DRAIN`；其他 disposition 明确要求 swapchain、pipeline、scene 或 renderer rebuild。
库不会静默执行这些 rebuild。

调用 `apply(plan)` 后，只有 `RendererFeatureApplyResult.Outcome.APPLIED` 证明 profile 已提交；
`UNCHANGED`、`STALE_PLAN`、`RETRY_AFTER_FRAME_DRAIN`、各类 `REQUIRES_*_REBUILD` 和 `REJECTED`
都没有相同含义。`featureControlDiagnostics()` 提供 generation、计划/应用/拒绝计数和最近一次
plan/result，不重新探测 vendor runtime。当前 backend 是否支持某一 in-session 转换必须以该次
plan/result 为准，不能从 controller 扩展存在本身推断。

## 官方 Vulkan presenter

### VulkanFramePresenter

```java
VulkanFramePresenter presenter = VulkanFramePresenter.open(renderer, configuration);
```

renderer-bound 官方窗口/swapchain consumer。`presentLatestFrame()` 是主推简单入口，返回 empty 表示尚无已提交帧；provider-owned 实现可用内部 GPU timeline 提前排入同 logical-device fast path。`presentAndRelease(...)` 保留给显式 lease 调用方。两种路径都以 `PRESENTED`、`SKIPPED_MINIMIZED` 或 `RETIRED_FOR_RECREATE` 表示穷尽结果并关闭被消费 lease。全部 presenter 方法绑定创建线程；`setOverlayText("")` 关闭 transfer-only HUD，`performanceSnapshot()` 对未知 timing 返回零样本而非估算值。

### VulkanFramePresenterConfig

| Builder 方法 | 说明 |
| --- | --- |
| `title(String)` | 非空原生窗口标题 |
| `initialExtent(int, int)` | 正初始 framebuffer extent |
| `resizable(boolean)` | 窗口 resize 策略 |
| `windowMode(WindowMode)` | 普通窗口或主显示器全屏；可选 full-screen-exclusive hint 失败时安全回退 |
| `presentMode(PresentMode)` | VSYNC、LOW_LATENCY 或 UNCAPPED 偏好 |
| `maximumFramesQueuedAhead(int)` | 1–16 的 producer lead 上限；默认 2，不是 FPS 锁 |

`activePresentMode()` 返回最终平台模式，而不是配置偏好。实际显示统计只累计 `Outcome.PRESENTED`。

### VulkanFramePresenterFactory

renderer 通过 `extension(VulkanFramePresenterFactory.class)` 发布 provider-bound 工厂；普通调用方优先使用 `VulkanFramePresenter.open(...)`，无需手动发现工厂。

## Vulkan 专家扩展

包路径：`top.ceroxe.rt.renderer.api.interop.vulkan`

### VulkanFrameInterop

```java
VulkanFrameInterop interop = renderer.extension(VulkanFrameInterop.class)
        .orElseThrow();
```

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `pollLatestFrame()` | `FramePollResult` | 非阻塞；返回 `FrameAvailable` 或 `FrameNotReady` |
| `awaitLatestFrame(Duration)` | `FramePollResult` | 有界等待 |
| `awaitLatestFrameAsync(Duration, Executor)` | `CompletableFuture<FramePollResult>` | 使用调用方 executor 的异步等待 |

### GpuFrameLease

lease 的权威状态机是：

```text
ACTIVE -> RELEASED -> CLOSED
```

`descriptor()` 描述共享 image；其中 `resourceId` 是可缓存 import 的稳定底层 image identity，`memoryTypeIndex` 是 producer allocation 的显式 memory type。`memoryHandle()` 和可选 `acquireSignal()` 描述导入所有权；`consumerCompletionCapabilities()` 约束可提交 completion；`release(...)` 发布 consumer 完成；`close()` 关闭剩余所有权。

任何专家接入都必须阅读 [Vulkan 专家互操作](Vulkan-Interop.md)，不得只根据本页的方法列表实现 native 同步。
