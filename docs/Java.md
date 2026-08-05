# Java 开发指南

RTRendererAPI 适合嵌入 Java 21 或更高版本的桌面或引擎进程。普通调用方只需要理解场景 revision、帧 sequence 和资源生命周期；Vulkan external memory、semaphore、queue-family ownership 等细节被隔离在显式专家扩展中。

Maven 坐标是 `top.ceroxe.rt:renderer-api:0.5.1`。只声明这一个依赖即可；Windows Vulkan 后端、NVIDIA provider 与经过完整性校验的 native runtime 会传递解析。消费方不需要安装 SDK、配置 SDK root 或手工复制 DLL。

## 最小调用

`RendererBootstrap.open()` 会枚举已安装 provider、执行兼容性探测并打开优先级最高的可用后端。兼容目标是 Windows 10 x64 或更高版本、NVIDIA RTX 20 系或更新 GPU、Vulkan 1.2+ 与 Java 21 或更高版本；`0.5.1` 的仓库实机验收只证明 README 支持表所列 Windows 11 x64 与 RTX 5080 Laptop。其他系统、GPU、显存和驱动组合不是已验证的生产稳定性声明。

```java
try (RayTracingRenderer renderer = RendererBootstrap.open()) {
    long revision = renderer.apply(SceneTransaction.empty(0L))
            .acceptedSceneRevision();

    CameraState camera = CameraState.lookAt(
            0.0, 1.0, 5.0,
            0.0, 1.0, 0.0
    ).aspectRatio(16.0 / 9.0).build();

    renderer.submit(RenderFrameRequest.builder(0L, 1280, 720, camera)
            .minimumSceneRevision(revision)
            .build());

    CpuFrame frame = renderer.awaitLatestCpuFrame(Duration.ofSeconds(5))
            .orElseThrow(() -> new IllegalStateException("frame timed out"));
}
```

关键语义：

- `RayTracingRenderer` 是 `AutoCloseable`，必须使用 `try-with-resources` 或等价的确定性关闭。
- 场景 revision 必须严格递增；帧的 `minimumSceneRevision` 防止旧场景被误当成新结果。
- 帧 sequence 必须严格递增，不可重用。
- `pollLatestCpuFrame()` 非阻塞；`awaitLatestCpuFrame(Duration)` 是有界等待，超时返回 `Optional.empty()`。
- `close()` 发起确定性关闭；存在外部 GPU lease 时用 `closeAsync()` 或 `awaitClosed(Duration)` 等待 native 资源真实释放。
- API 不使用 `null` 表示“暂时无帧”，也不会无限等待。
- 普通路径的 readback 使用 frame-slot 常驻异步 ring，不会逐帧创建 staging buffer 或执行 queue-idle；但把图像送到 CPU 本身仍有带宽成本。

## 发布一个最小场景

公共模型使用语义工厂与 Builder，避免长有序构造器。调用方提供的数组会在安全工厂中复制；高吞吐路径可以显式使用只读 direct buffer 工厂。

```java
TextureAsset texture = TextureAsset.color(1L, 1, 1,
        new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255});

MaterialAsset material = MaterialAsset.builder(2L)
        .baseColorTexture(texture)
        .roughness(0.7F)
        .build();

MeshAsset mesh = MeshAsset.triangles(
        3L,
        new float[]{
                -1.0F, 0.0F, 0.0F,
                 1.0F, 0.0F, 0.0F,
                 0.0F, 1.0F, 0.0F
        },
        new int[]{0, 1, 2},
        material.id()
);

SceneInstance instance = SceneInstance.builder(4L, mesh.id())
        .lightmapCoordinates(240, 240)
        .build();
SceneLight light = SceneLight.directional(5L, -0.4F, -1.0F, -0.2F)
        .intensity(3.0F)
        .build();

long acceptedRevision = renderer.apply(SceneTransaction.builder(1L)
        .upsert(texture)
        .upsert(material)
        .upsert(mesh)
        .upsert(instance)
        .upsert(light)
        .build()).acceptedSceneRevision();
```

## 提交帧级图元

`FramePrimitiveBatch` 面向数量或变换高频变化、但复用常驻网格的实例。场景事务继续拥有
`MeshAsset`、材质与几何 residency；每个 `PrimitiveInstance` 只引用已经被目标 scene revision
接受的 mesh，并随 `RenderFrameRequest` 原子替换当前帧的紧凑实例集合。批次不可变且最多包含
`FramePrimitiveBatch.MAX_PRIMITIVES` 个图元；下一次成功提交省略某个图元即表示移除它，空批次
会清空上一帧的全部帧级图元。

```java
InstanceRenderState markerState = InstanceRenderState.builder()
        .uvTransform(UvTransform.scaleAndOffset(0.5F, 0.5F, 0.25F, 0.0F))
        .surfaceMask(0x04)
        .objectMask(42)
        .outline(OutlineStyle.of(0xff40_20ff, 1.5F))
        .build();

PrimitiveInstance marker = PrimitiveInstance.builder(mesh.id())
        .transform(currentTransform)
        .previousTransform(previousSubmittedTransform)
        .renderState(markerState)
        .build();

FramePrimitiveBatch primitives = FramePrimitiveBatch.of(List.of(marker));
RenderFrameRequest frame = RenderFrameRequest.builder(sequence, width, height, camera)
        .minimumSceneRevision(acceptedRevision)
        .primitiveBatch(primitives)
        .build();
```

帧级图元没有跨批次的持久 ID，列表位置只定义当前批次的 TLAS custom-index 顺序。移动图元的
`previousTransform` 必须来自上一次**成功提交**的对应图元；新图元或静态图元可以不设置，默认
等于当前 `transform` 并产生零对象运动。使用 `trySubmit(...)` 时，只有收到 `FrameSubmitted`
后才能推进 previous transform、物理状态与 sequence；`FrameSubmissionDeferred` 必须原样重试，
否则 NRD、DLSS SR/DLAA 与帧生成会收到错误的对象运动历史。

provider 为批次使用 frame-slot-local 实例记录与 TLAS，并复用常驻 mesh BLAS；UV、mask、outline
和实例 transform 的变化不会重建 mesh BLAS。帧 TLAS 在完成前借用目标 scene revision 的 BLAS
地址，因此 renderer 会推迟可能回收这些 BLAS 的场景变更，而不是执行 device-wide wait。
需要跨帧稳定场景 identity、独立场景增删或长期持有的实例仍应使用 `SceneInstance`。

`SceneInstance` 与 `PrimitiveInstance` 共享 `InstanceRenderState`：`surfaceMask` 声明普通表面
receiver，`overlayReceiverMask` 选择 overlay 可合成的 receiver，`objectMask` 提供 outline
比较所需的对象 identity。启用 `OutlineStyle` 时 `objectMask` 必须非零。

## 选择 GPU

不要根据设备名称猜测能力。先枚举设备，再按稳定 identity 或显式 capability 选择：

```java
List<RayTracingGpuDevice> devices = RendererBootstrap.availableGpuDevices();
RayTracingGpuDevice selected = devices.stream()
        .filter(device -> device.type() == RayTracingGpuDevice.Type.DISCRETE)
        .max(Comparator.comparingLong(RayTracingGpuDevice::deviceLocalMemoryBytes))
        .orElseThrow(() -> new IllegalStateException("No supported RTX GPU"));

RayTracingRendererConfig config = RayTracingRendererConfig.builder()
        .gpuDevice(selected)
        .build();

try (RayTracingRenderer renderer = RendererBootstrap.open(config)) {
    // publish scene and submit frames
}
```

## 常用配置

| Builder 方法 | 默认值 | 什么时候改 |
| --- | --- | --- |
| `maxFramesInFlight(int)` | `RayTracingRendererConfig.DEFAULT_MAX_FRAMES_IN_FLIGHT` | 调整 CPU/GPU 并行深度；合法范围为 `MIN_MAX_FRAMES_IN_FLIGHT..MAX_MAX_FRAMES_IN_FLIGHT` |
| `validationEnabled(boolean)` | 生产默认值 | 调试 Vulkan validation 时开启 |
| `gpuTimingsEnabled(boolean)` | 生产默认值 | 需要 GPU stage timing 时开启 |
| `cpuFrameReadbackEnabled(boolean)` | `true` | 只走 GPU presenter/interop 时关闭 CPU buffer 与 image-to-buffer copy |
| `frameOutputFormat(FrameOutputFormat)` | `SDR_RGBA8` | 专家链路需要 linear HDR RGBA16F 时修改 |
| `temporalRendering(TemporalRenderingOptions)` | `balanced()` | 禁用或调整 temporal history 长度 |
| `frameReconstruction(FrameReconstructionOptions)` | `disabled()` | 显式请求 DLSS SR、DLAA 或 NIS |
| `denoising(DenoisingOptions)` | `disabled()` | 显式请求 NRD 类时域降噪及其 fallback |
| `frameGeneration(FrameGenerationOptions)` | `disabled()` | 明确 opt-in adaptive 或固定 2x/3x/4x；该能力接管 swapchain pacing |
| `lowLatency(LowLatencyOptions)` | `disabled()` | 独立请求低延迟 pacing 与 frame markers |
| `rayTracingOptimizations(RayTracingOptimizationOptions)` | SER、RTXMU 均禁用 | 显式请求某项优化 |
| `gpuDevice(RayTracingGpuDevice)` | 自动选择 | 必须绑定确定 GPU 时修改 |

普通 CPU-readable 路径直接使用 `RayTracingRendererConfig.defaults()`：它以 `PREFERRED`
自动协商 SR、NRD、SER 与 RTXMU，不支持的实现保留各自的 renderer fallback。该路径没有
swapchain ownership，因此不会请求 FG/MFG 或 display pacing，避免产生无法 retire 的
presentation-time 输入。

只通过官方 GPU presenter 消费帧时，使用
`RayTracingRendererConfig.gpuPresentationDefaults()`；它关闭 CPU readback，并在普通自动质量
策略之上请求 FG 2x 与 Reflex/PCL。MFG 永远不由普通 preset 自动开启，只能通过专家 builder
显式选择 3x/4x。provider 始终检查 Vulkan feature/extension、SDK、驱动、真实 adapter 和
资源合同，不能由 GPU 型号字符串猜测能力；显式 `REQUIRED` 也不允许被 fallback 吞掉。
只使用专家 `VulkanFrameInterop`、但没有官方 managed presenter 的应用，应从
`defaults().toBuilder().cpuFrameReadbackEnabled(false)` 开始，不能错误借用会请求 swapchain
frame generation 的 GPU-presenter preset。

`RayTracingRendererConfig.builder()` 是专家显式基线，所有 vendor 技术默认禁用；
`RayTracingRendererConfig.defaults().toBuilder()` 和
`gpuPresentationDefaults().toBuilder()` 用于调整相应普通 preset。两种入口最终进入同一
capability negotiation 和执行状态机，不存在绕过生命周期约束的隐藏模式开关。下面是普通
CPU-readable preset 的等价专家配置：

```java
RayTracingRendererConfig explicitProduction = RayTracingRendererConfig.builder()
        .temporalRendering(TemporalRenderingOptions.balanced())
        .frameReconstruction(FrameReconstructionOptions.productionDefault())
        .denoising(DenoisingOptions.productionDefault())
        .frameGeneration(FrameGenerationOptions.disabled())
        .lowLatency(LowLatencyOptions.disabled())
        .rayTracingOptimizations(RayTracingOptimizationOptions.productionDefault())
        .build();
```

### 完整专家配置

专家模式只表达应用意图，不接管 Vulkan、Streamline、NRD 或 RTXMU 的资源 owner。下面的配置
显式覆盖 0.5.1 的全部 NVIDIA 能力，同时保持生产环境可降级：DLSS SR 不可用时允许 NIS，
NRD 不可用时保留内建时域路径，FG/MFG 不可用时继续发布原生帧。SER 和 RTXMU 独立
协商，某一项不支持不会阻止其他项启用。

```java
RayTracingRendererConfig expert = RayTracingRendererConfig.builder()
        .maxFramesInFlight(3)
        .validationEnabled(false)
        .gpuTimingsEnabled(true)
        .cpuFrameReadbackEnabled(false)
        .frameOutputFormat(FrameOutputFormat.SDR_RGBA8)
        .temporalRendering(TemporalRenderingOptions.balanced())
        .frameReconstruction(FrameReconstructionOptions.builder()
                .preference(RendererFeaturePreference.PREFERRED)
                .mode(FrameReconstructionOptions.Mode.SUPER_RESOLUTION)
                .quality(FrameReconstructionOptions.Quality.QUALITY)
                .fallback(FrameReconstructionOptions.Fallback.SPATIAL)
                .build())
        .denoising(DenoisingOptions.builder()
                .preference(RendererFeaturePreference.PREFERRED)
                .strategy(DenoisingOptions.Strategy.BALANCED)
                .builtInTemporalFallback(true)
                .build())
        .frameGeneration(FrameGenerationOptions.builder()
                .preference(RendererFeaturePreference.PREFERRED)
                .mode(FrameGenerationOptions.Mode.ADAPTIVE)
                .multiplier(FrameGenerationOptions.Multiplier.FOUR_X)
                .fallback(FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES)
                .build())
        .lowLatency(LowLatencyOptions.productionDefault())
        .rayTracingOptimizations(RayTracingOptimizationOptions.builder()
                .shaderExecutionReordering(RendererFeaturePreference.PREFERRED)
                .memoryOptimization(RendererFeaturePreference.PREFERRED)
                .build())
        .build();
```

`maxFramesInFlight` 是公开的有界并发/资源预算控制；各 native 功能的临时资源预算由同一
frame ring 和 backend owner 管理，API 不暴露会破坏关闭顺序的 SDK allocator 或裸句柄。
Reflex/PCL 通过 `LowLatencyOptions` 独立于 FG/MFG 协商，因此原生展示和 DLSS SR 也能使用
低延迟 pacing。FG/MFG 仍把 Reflex/PCL 作为强制内部依赖；关闭独立策略不会绕过该依赖。

### 各能力的最短配置

以下片段都只需要 `top.ceroxe.rt:renderer-api:0.5.1`。把对应 options 传给
`RayTracingRendererConfig.builder()` 即可；没有任何片段要求额外模块或手工 DLL。

```java
// DLSS Super Resolution；不可用时允许 NIS。
var dlssSr = FrameReconstructionOptions.builder()
        .preference(RendererFeaturePreference.PREFERRED)
        .mode(FrameReconstructionOptions.Mode.SUPER_RESOLUTION)
        .quality(FrameReconstructionOptions.Quality.AUTO)
        .fallback(FrameReconstructionOptions.Fallback.SPATIAL)
        .build();

// DLAA；保持原生分辨率，不能配置 spatial upscale fallback。
var dlaa = FrameReconstructionOptions.builder()
        .preference(RendererFeaturePreference.PREFERRED)
        .mode(FrameReconstructionOptions.Mode.NATIVE_ANTI_ALIASING)
        .fallback(FrameReconstructionOptions.Fallback.BUILT_IN_TEMPORAL)
        .build();

// NIS 空间缩放。
var nis = FrameReconstructionOptions.builder()
        .preference(RendererFeaturePreference.PREFERRED)
        .mode(FrameReconstructionOptions.Mode.SPATIAL_UPSCALING)
        .build();

// NRD 类原生时域降噪。
var nrd = DenoisingOptions.builder()
        .preference(RendererFeaturePreference.PREFERRED)
        .strategy(DenoisingOptions.Strategy.BALANCED)
        .builtInTemporalFallback(true)
        .build();

// DLSS Frame Generation 2x。
var fg2x = FrameGenerationOptions.builder()
        .preference(RendererFeaturePreference.PREFERRED)
        .mode(FrameGenerationOptions.Mode.FRAME_GENERATION)
        .multiplier(FrameGenerationOptions.Multiplier.TWO_X)
        .fallback(FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES)
        .build();

// DLSS Multi Frame Generation，最高 4x；实际倍率仍以 SDK capability 为准。
var mfg4x = FrameGenerationOptions.builder()
        .preference(RendererFeaturePreference.PREFERRED)
        .mode(FrameGenerationOptions.Mode.MULTI_FRAME_GENERATION)
        .multiplier(FrameGenerationOptions.Multiplier.FOUR_X)
        .fallback(FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES)
        .build();

// SER、RTXMU 分别独立请求；未列出的项保持禁用。
var ser = RayTracingOptimizationOptions.builder()
        .shaderExecutionReordering(RendererFeaturePreference.PREFERRED)
        .build();
var rtxmu = RayTracingOptimizationOptions.builder()
        .memoryOptimization(RendererFeaturePreference.PREFERRED)
        .build();
```

需要硬失败语义时，把对应 `PREFERRED` 改成 `REQUIRED`，并移除该 options 的 fallback；
不要通过 GPU 名称选择 DLSS 版本或生成倍率。DLSS SR 与 DLAA 是同一重建槽位的互斥模式，
不能同时请求；adaptive FG/MFG 则读取 SDK 返回的真实最大倍率。

时域能力还要求每帧提供准确 camera、输出尺寸和 discontinuity。输出尺寸、投影和 sequence 等
renderer 可观察变化会自动失效历史；camera cut、teleport 或其他只有应用知道的语义突变通过
`resetTemporalHistory(...)` 显式声明。能够提供精确深度投影时同时填写
`depthProjection(...)`。缺失或不一致的时域合同会阻止相关能力进入 `ACTIVE`，
backend 不会猜测矩阵或伪造 NRD/DLSS 输入。

```java
RenderFrameRequest temporalFrame = RenderFrameRequest.builder(sequence, width, height, camera)
        .minimumSceneRevision(acceptedRevision)
        .depthProjection(depthProjection)
        .resetTemporalHistory(HistoryResetReason.CAMERA_CUT)
        .build();
```

创建 renderer 后，以 capability 状态和结构化 diagnostics 作为唯一运行时事实：

```java
RenderingFeatureCapabilities features = renderer
        .extension(RenderingFeatureCapabilities.class)
        .orElseThrow();
var mfg = features.technology(RenderingFeatureCapabilities.Technology.MULTI_FRAME_GENERATION);
RendererDiagnostics diagnostics = renderer.diagnostics();
FrameGenerationEvidence generation = diagnostics.frameGenerationEvidence();
```

`AVAILABLE` 只表示已协商；真实 evaluate、dispatch 或 generated present 成功后才是 `ACTIVE`。
`FALLBACK_PENDING` 表示替代实现已选定但尚未出现执行证据；它可能来自启动协商，也可能来自
运行期失败后的帧边界切换。`FALLBACK` 才表示替代实现已经接管。`NOT_SUPPORTED` 表示能力拒绝，
`BLOCKED` 表示初始化或执行错误。应用不得把配置请求或设备
型号映射成 `ACTIVE`。capability 和 diagnostics 都是不可变时间点快照，状态变化后必须再次
调用 `extension(...)`/`diagnostics()`；`reason()` 只供人阅读，不能作为控制流或统计输入。

`FrameGenerationEvidence` 中的 requested/configured 值是“每个 native interval 的额外生成帧
数”，其 `...PresentationMultiplier()` 才是 2x/3x 等总 cadence。`proxyPresentCalls` 是应用帧
进入 proxy 的次数，`generatedFramesActuallyPresented`/`totalFramesActuallyPresented` 是 SDK
确认的累计产出，`generationRequestMisses` 则表示 tag、extent 或 sequence 契约不满足而退回
native present。它们证明生成链实际工作，但不冒充显示器 scanout；物理显示测量仍属于外部
显示遥测工具的职责。

修改已有配置使用 `toBuilder()`，不要重新拼长参数列表：

```java
RayTracingRendererConfig hdr = config.toBuilder()
        .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
        .temporalRendering(TemporalRenderingOptions.accumulating(16))
        .build();
```

## 帧请求

```java
RenderFrameRequest request = RenderFrameRequest.builder(sequence, width, height, camera)
        .minimumSceneRevision(acceptedRevision)
        .environment(environment)
        .antiAliasing(AntiAliasingState.multisampled(4))
        .resetTemporalHistory(HistoryResetReason.CAMERA_CUT)
        .build();
```

常用输入：

| 输入 | 说明 |
| --- | --- |
| `CameraState` | 使用 `lookAt(...).aspectRatio(...).build()` 构建 |
| `EnvironmentState` | 天空、太阳、环境光与介质参数 |
| `LightmapState` | 256 项 packed lightmap；默认 full intensity |
| `DistanceFogState` | 距离雾与高度雾；默认 disabled |
| `TextureSamplingState` | pixel-stable、rotated-grid 或显式 anisotropy |
| `AntiAliasingState` | 1/2/4/8 spp 的确定性空间采样 |
| temporal reset | camera cut、teleport、projection change 等显式历史失效原因 |

## 无异常背压提交

交互式或 uncapped 循环使用 `trySubmit(...)`。队列满属于正常状态，只有成功后才推进 sequence 和相关模拟状态：

```java
RayTracingRenderer.FrameSubmissionAttempt attempt = renderer.trySubmit(request);
if (attempt instanceof RayTracingRenderer.FrameSubmitted submitted) {
    sequence = Math.addExact(sequence, 1L);
} else if (attempt instanceof RayTracingRenderer.FrameSubmissionDeferred deferred) {
    // 控制流读取稳定枚举，detail() 只用于诊断；保留相同 request/sequence 稍后重试。
    switch (deferred.deferralReason()) {
        case PRESENTATION_BACKLOG, FRAME_RING_FULL -> LockSupport.parkNanos(250_000L);
        default -> LockSupport.parkNanos(1_000_000L);
    }
}
```

`FrameSubmissionDeferred` 只代表本次没有发布任何逻辑或 native submission 状态。`deferralReason()` 是稳定遥测与重试分类，旧 provider 返回 `UNSPECIFIED`；不要解析 `reason()` 或 `detail()`。顺序、revision、生命周期和 device failure 仍抛出对应 typed exception。`submit(...)` 保留给“拒绝即异常”的控制流。

## 结果与健康状态

`submit(...)` 返回 typed result，不要通过日志文本判断是否被接收。运行期治理读取：

```java
RendererHealth health = renderer.health();
RendererDiagnostics diagnostics = renderer.diagnostics();
```

具体 NVIDIA 技术的 HUD/治理状态读取 `RenderingFeatureCapabilities.technologies()`。不要从
GPU 名称、配置请求或 implementation 字符串反推状态：`ACTIVE` 需要真实执行证据，
`NOT_SUPPORTED` 是能力拒绝，`BLOCKED` 是初始化/运行错误，`AVAILABLE` 是等待首帧证据。
功能域发生降级时可为 `FALLBACK`，同时失败的具体 technology 保持 `BLOCKED`，因此应用
既能显示当前仍可工作的帧链，也不会隐藏原实现错误。

`RendererHealth.ResourceObligations` 会暴露未归还 GPU lease、native cleanup 和 device recovery 欠账。关闭前必须让调用方拥有的资源归零。

稳定异常层次：

| 异常 | 含义 |
| --- | --- |
| `RendererUnavailableException` | 没有 provider 能满足请求，包含逐 provider attempt |
| `RendererInitializationException` | provider 选择成功但初始化失败 |
| `RendererDeviceException` | device lost、OOM、timeout 等设备失败，带稳定 recovery action |
| `RendererStateException` | 当前生命周期状态不允许操作 |
| `SceneValidationException` | 场景数据违反契约 |
| `SceneRevisionException` | revision 非法或倒退 |
| `SubmissionOrderException` | 帧 sequence 顺序非法 |
| `SubmissionRejectedException` | 仅表示未保留部分状态的可重试容量拒绝，带稳定 `deferralReason()` |

不要解析异常 message 做控制流；使用结构化字段、reason 和 recovery action。

## 官方 GPU presenter

需要窗口显示但不想自行实现 Vulkan interop 的应用，使用官方 renderer-bound presenter：

```java
RayTracingRendererConfig config = RayTracingRendererConfig.gpuPresentationDefaults();

try (RayTracingRenderer renderer = RendererBootstrap.open(config);
     VulkanFramePresenter presenter = VulkanFramePresenter.open(
             renderer,
             VulkanFramePresenterConfig.builder()
                     .initialExtent(2560, 1600)
                     .presentMode(VulkanFramePresenterConfig.PresentMode.UNCAPPED)
                     .maximumFramesQueuedAhead(2)
                     .build())) {
    presenter.pollEvents();
    var presented = presenter.presentLatestFrame();
    if (presented.isPresent()) {
        VulkanFramePresenter.PresentationResult result = presented.orElseThrow();
    }
    presenter.setOverlayText("PRESENT: 145.2 FPS\nTRACE CAPACITY: 266.3 FPS");
    VulkanFramePresenter.PerformanceSnapshot timings = presenter.performanceSnapshot();
}
```

presenter 的 open、event pump、present、title、overlay 和 close 都绑定创建线程。主推的 `presentLatestFrame()` 直接从 renderer 取得 managed frame，可用内部 timeline 在 CPU fence 观察前建立 GPU 依赖；专家 `VulkanFrameInterop.pollLatestFrame()` 保持 completed external lease 语义。`presentAndRelease(...)` 仍供显式 lease consumer 使用，并在所有结果路径消费/关闭 lease。`maximumFramesQueuedAhead(2)` 允许 renderer 与显示消费重叠，同时阻止生产队列饿死 swapchain；它不锁 FPS。同一 swapchain 的 acquire/present 按 Vulkan 外部同步要求在 presenter 线程串行。显示统计只应在 `Outcome.PRESENTED` 时累计；缺失的 GPU timing 以零样本/`NaN` 表示，绝不估算。

## Vulkan 专家模式

只有调用方已经能正确处理 Vulkan external memory、external semaphore、Win32 handle 与 queue-family ownership 时才发现扩展：

```java
VulkanFrameInterop interop = renderer.extension(VulkanFrameInterop.class)
        .orElseThrow(() -> new IllegalStateException("Vulkan interop unavailable"));
```

普通代码不应 import `top.ceroxe.rt.renderer.api.interop.vulkan`。完整状态机与失败重试见 [Vulkan 专家互操作](Vulkan-Interop.md)。

## 构建与验证

| 目标 | 命令 |
| --- | --- |
| 全量 CPU/发布门禁 | `.\gradlew.bat check --dependency-verification=strict --no-daemon --console=plain` |
| 短 GPU 验收 | `.\gradlew.bat strictAcceptanceTest --dependency-verification=strict --no-daemon --console=plain` |
| 独立 Maven consumer | `.\gradlew.bat verifyPublishedMavenConsumer --dependency-verification=strict --no-daemon --console=plain` |
| ABI 验证 | `.\gradlew.bat :renderer-api:verifyRendererApiAbi --dependency-verification=strict --no-daemon --console=plain` |
