# Java API 参考

> 公共包根：`top.ceroxe.rt.renderer`
>
> 本文聚焦消费方直接使用的稳定 API。`renderer-core` 中的 Vulkan backend、GPUScene、BLAS/TLAS、pipeline 和调度类型属于实现细节，不是公共入口。

## 目录

- [RendererBootstrap](#rendererbootstrap)
- [Renderer](#renderer)
- [RendererConfig](#rendererconfig)
- [场景与帧](#场景与帧)
- [资产与实例](#资产与实例)
- [设备、诊断与异常](#设备诊断与异常)
- [运行期功能控制](#运行期功能控制)
- [官方 Vulkan presenter](#官方-vulkan-presenter)
- [Vulkan 专家扩展](#vulkan-专家扩展)
- [通用渲染语义](#通用渲染语义)

## RendererBootstrap

```java
public final class RendererBootstrap
```

后端发现与打开的唯一公共入口。通过 `ServiceLoader` 发现 `RendererBackendProvider`，按 descriptor priority 和兼容性结果选择后端。

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `open(RendererPreset)` | `Renderer` | 简单模式；按明确的 CPU readback 或 managed GPU presentation preset 打开最佳可用后端 |
| `open(RendererConfig)` | `Renderer` | 专家模式；使用完整显式配置打开最佳可用后端 |
| `open(String, RendererConfig)` | `Renderer` | 专家模式；按 provider id 打开，用于确定性部署或诊断 |
| `availableGpuDevices()` | `List<RendererGpuDevice>` | 返回通过 provider 探测得到的不可变设备列表 |

## Renderer

```java
public interface Renderer extends AutoCloseable
```

一个 `Renderer` 同时承载 retained-scene 与通用 command path。`RenderWorkload.Mode` 是工作负载
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

`BindingType.COMBINED_IMAGE_SAMPLER` 以一个 `BindingSet.CombinedImageSamplerValue(TextureView, SamplerState)`
表达一个 binding 元素。它对应 Vulkan 的 `VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER`，不是
`SAMPLED_TEXTURE` 与 `SAMPLER` 的便利组合；shader reflection、binding layout 和 binding set 都必须使用
同一精确类别。

### 通用渲染语义

`3.0.0` 的 command path 语义、Vulkan 后端支持边界和证据规则见
[Generic-Rendering-Semantics.md](Generic-Rendering-Semantics.md)。这条路径真实消费 graphics
pipeline、attachment、binding 和 draw 命令；未被后端支持的 shader、格式或同步要求会在 admission
阶段拒绝，不会降级成固定 PBR 场景。

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
| `pollLatestCpuFrame()` | `Optional<CpuFrame>` | 非阻塞读取最新托管帧 |
| `awaitLatestCpuFrame(Duration)` | `Optional<CpuFrame>` | 有界等待托管帧 |
| `diagnostics()` | `RendererDiagnostics` | 当前诊断快照 |
| `health()` | `RendererHealth` | 结构化失败与资源欠账 |
| `extension(Class<T>)` | `Optional<T>` | 显式发现可选专家扩展 |
| `close()` | `void` | 关闭 renderer；未归还资源会按契约报告 |
| `closeAsync()` | `CompletionStage<Void>` | 请求关闭，仅在 native 资源实际释放后完成 |
| `awaitClosed(Duration)` | `boolean` | 请求关闭并有界等待真实资源释放；超时返回 `false` |

`FrameSubmissionDeferred.deferralReason()` 和 `SubmissionRejectedException.deferralReason()` 返回稳定的
`SubmissionDeferralReason`，用于重试策略和遥测聚合；`detail()` 仅用于人类诊断。1.0 provider
必须提供类型化分类，API 不从自然语言猜测类别。

## RendererPreset

普通调用方直接把 preset 传给 `RendererBootstrap.open(...)`，不需要接触完整配置：

| 值 | 说明 |
| --- | --- |
| `CPU_READBACK` | 托管 CPU frame 路径；优选重建、降噪、SER 与 AS memory optimization，不请求 presentation-time FG/MFG |
| `MANAGED_GPU_PRESENTATION` | 官方 GPU presenter 路径；关闭 CPU readback，并在支持时额外优选普通 FG 2x 与低延迟 pacing；不自动请求 MFG |

`configuration()` 只供专家检查或复制 preset。派生配置使用 `copyBuilder()`，并通过
`RendererBootstrap.open(...)` 打开。

## RendererConfig

```java
public final class RendererConfig
```

只提供显式 expert Builder 与 `copyBuilder()`，不提供有序兼容构造器或隐式默认入口。

| 静态入口 | 说明 |
| --- | --- |
| `expertBuilder()` | 创建专家显式 Builder；可选 vendor 技术均禁用 |

已有配置通过 `copyBuilder()` 派生。简单模式配置由 `RendererPreset` 独立表达，避免把 preset 与
专家配置入口混为一谈。

`MIN_MAX_FRAMES_IN_FLIGHT`、`DEFAULT_MAX_FRAMES_IN_FLIGHT` 和
`MAX_MAX_FRAMES_IN_FLIGHT` 分别定义合法下界、默认值和上界。

| Builder 方法 | 说明 |
| --- | --- |
| `maxFramesInFlight(int)` | 帧环深度 |
| `validationEnabled(boolean)` | Vulkan validation 策略 |
| `gpuTimingsEnabled(boolean)` | GPU timing 策略 |
| `cpuFrameReadbackEnabled(boolean)` | 是否分配并复制异步托管 CPU 帧；默认开启 |
| `gpuDevice(RendererGpuDevice)` | 绑定枚举所得稳定设备对象 |
| `automaticGpuSelection()` | 恢复 provider 的自动设备选择 |
| `frameOutputFormat(FrameOutputFormat)` | SDR RGBA8 或 linear HDR RGBA16F |
| `temporalRendering(TemporalRenderingOptions)` | temporal reconstruction 策略 |
| `frameReconstruction(FrameReconstructionOptions)` | DLSS SR、DLAA、NIS 或禁用策略 |
| `frameGeneration(FrameGenerationOptions)` | 互斥 FG/MFG 模式、倍率与 fallback 策略 |
| `lowLatency(LowLatencyOptions)` | Reflex/PCL 请求策略 |
| `denoising(DenoisingOptions)` | NRD 请求与 fallback 策略 |
| `rayTracingOptimizations(RayTracingOptimizationOptions)` | SER 与 RTXMU 独立策略 |
| `build()` | 验证全部不变量并创建配置 |

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

### CpuFrame

托管、display-ready RGBA8 帧。普通调用方使用它完成截图、编码、UI 或 CPU 后处理，不需要管理 native handle。

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
