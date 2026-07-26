# Java API 参考

> 公共包根：`top.ceroxe.rt.renderer`
>
> 本文聚焦消费方直接使用的稳定 API。`renderer-core` 中的 Vulkan backend、GPUScene、BLAS/TLAS、pipeline 和调度类型属于实现细节，不是公共入口。

## 目录

- [RendererBootstrap](#rendererbootstrap)
- [RayTracingRenderer](#raytracingrenderer)
- [RayTracingRendererConfig](#raytracingrendererconfig)
- [场景与帧](#场景与帧)
- [资产与实例](#资产与实例)
- [设备、诊断与异常](#设备诊断与异常)
- [官方 Vulkan presenter](#官方-vulkan-presenter)
- [Vulkan 专家扩展](#vulkan-专家扩展)

## RendererBootstrap

```java
public final class RendererBootstrap
```

后端发现与打开的唯一公共入口。通过 `ServiceLoader` 发现 `RayTracingBackendProvider`，按 descriptor priority 和兼容性结果选择后端。

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `open()` | `RayTracingRenderer` | 使用默认现代配置打开最佳可用后端 |
| `open(RayTracingRendererConfig)` | `RayTracingRenderer` | 使用显式配置打开最佳可用后端 |
| `openProvider(String, RayTracingRendererConfig)` | `RayTracingRenderer` | 按 provider id 打开，用于确定性部署或诊断 |
| `availableGpuDevices()` | `List<RayTracingGpuDevice>` | 返回通过 provider 探测得到的不可变设备列表 |

## RayTracingRenderer

```java
public interface RayTracingRenderer extends AutoCloseable
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

## RayTracingRendererConfig

```java
public final class RayTracingRendererConfig
```

只提供 Builder 与 `toBuilder()` 现代路径，不提供有序兼容构造器。

| 静态入口 | 说明 |
| --- | --- |
| `builder()` | 从生产默认值创建 Builder |
| `defaults()` | 返回不可变生产默认配置 |

| Builder 方法 | 说明 |
| --- | --- |
| `maxFramesInFlight(int)` | 帧环深度 |
| `validationEnabled(boolean)` | Vulkan validation 策略 |
| `gpuTimingsEnabled(boolean)` | GPU timing 策略 |
| `cpuFrameReadbackEnabled(boolean)` | 是否分配并复制异步托管 CPU 帧；默认开启 |
| `frameOutputFormat(FrameOutputFormat)` | SDR RGBA8 或 linear HDR RGBA16F |
| `temporalRendering(TemporalRenderingOptions)` | temporal reconstruction 策略 |
| `gpuDevice(RayTracingGpuDevice)` | 绑定枚举所得稳定设备对象 |
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
| `MaterialAsset` | `builder(id)` | PBR/材质状态与 texture 引用 |
| `MeshAsset` | `triangles(...)`、`builder(...)` | 顶点属性、索引和逐三角形材质 |
| `SceneInstance` | `builder(id, meshAssetId)` | transform、mobility、visibility、shadow 与实例级 lightmap 坐标 |
| `SceneLight` | `point(...)`、`directional(...)`、`spot(...)` | typed light 与物理参数 |

安全工厂复制调用方数组。需要零额外复制时只能使用标明 `wrapImmutableDirect` 的入口，并保证 direct buffer 在完整资源生命周期内不再被修改。

`SceneInstance` 默认使用 `FULL_BRIGHT_PACKED_LIGHT`。没有逐顶点 lightmap 坐标的 mesh 可调用
`lightmapCoordinates(first, second)` 设置两个位于 `[0, 240]` 的坐标；已有 packed host 数据可调用
`packedLight(value)`，其低、高 unsigned 16-bit 半字分别表示 first、second coordinate，并执行相同范围校验。
光照属于实例 shading state；移动实例只需 upsert 新实例 generation，不应修改 mesh 或重建 BLAS。

## 设备、诊断与异常

### RayTracingGpuDevice

设备对象提供 provider id、stable id、名称、类型、API version、显存、capabilities 和 RT limits。设备选择应依据 stable identity 与能力，不依据展示名称。

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
