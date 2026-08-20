# Java 开发指南

RTRendererAPI 适合嵌入 Java 21 或更高版本的桌面或引擎进程。普通调用方只需要理解场景 revision、帧 sequence 和资源生命周期；Vulkan external memory、semaphore、queue-family ownership 等细节被隔离在显式专家扩展中。

公共模型是厂商中立、宿主无关的渲染契约：场景、相机、exact clip-space projection、资源所有权和能力状态均不包含游戏或引擎专用字段。Windows NVIDIA Vulkan 是当前发布实现，不是公共 API 的身份。

Maven 坐标是 `top.ceroxe.rt:renderer-api:3.1.7`。只声明这一个依赖即可；Windows Vulkan 后端、NVIDIA provider 与经过完整性校验的 native runtime 会传递解析。消费方不需要安装 SDK、配置 SDK root 或手工复制 DLL。Maven Central 是这些制品的唯一发布事实源；Git tag 只用于定位构建相同制品的源码。

## 最小调用

`RendererBootstrap.open(RendererPreset.CPU_READBACK)` 会枚举已安装 provider、执行兼容性探测并打开优先级最高的可用后端。兼容目标是 Windows 10 x64 或更高版本、NVIDIA RTX 20 系或更新 GPU、Vulkan 1.2+ 与 Java 21 或更高版本。兼容目标不是实机验收结论；本文不把尚未运行的 3.1.7 GPU smoke、特定宿主集成或跨硬件验证声明为已通过。

```java
try (Renderer renderer = RendererBootstrap.open(RendererPreset.CPU_READBACK)) {
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

- `Renderer` 是 `AutoCloseable`，必须使用 `try-with-resources` 或等价的确定性关闭。
- 场景 revision 必须严格递增；帧的 `minimumSceneRevision` 防止旧场景被误当成新结果。
- 帧 sequence 必须严格递增，不可重用。
- `pollLatestCpuFrame()` 非阻塞；`awaitLatestCpuFrame(Duration)` 是有界等待，超时返回 `Optional.empty()`。
- `close()` 发起确定性关闭；存在外部 GPU lease 时用 `closeAsync()` 或 `awaitClosed(Duration)` 等待 native 资源真实释放。
- API 不使用 `null` 表示“暂时无帧”，也不会无限等待。
- 普通路径的 readback 使用 frame-slot 常驻异步 ring，不会逐帧创建 staging buffer 或执行 queue-idle；但把图像送到 CPU 本身仍有带宽成本。

## 通用命令语义：专家入口

`Renderer` 是 3.0 的唯一公共入口。retained-scene 快速路径与版本化资源、严格有序的
`RenderCommandTransaction` 共存于同一实例；`RenderWorkload.Mode` 或被直接调用的提交方法明确
选择语义，两种输入不会相互猜测或转换。普通调用方继续使用 `RendererPreset` 与
`SceneTransaction`。只有需要保留既有图形提交语义的宿主才应使用 command path。完整学习顺序、
通用 RT 最小事务、SPIR-V/SBT 规则、错误处理和 retirement 边界见
[通用命令与硬件光线追踪指南](Generic-Commands-and-Ray-Tracing.md)。

```java
try (Renderer renderer = RendererBootstrap.open(
        RendererConfig.expertBuilder().build())) {
    RenderingSemanticCapabilities capabilities = renderer.renderingSemanticCapabilities();
    if (!capabilities.feature(RenderingSemanticCapabilities.Feature.BUFFER_UPLOAD).executable()) {
        throw new IllegalStateException("当前后端不能执行所需的通用 buffer 路径");
    }

    BufferResource input = new BufferResource(
            new RenderResourceId(1L), ResourceVersion.initial(), 256,
            Set.of(BufferUsage.COPY_DESTINATION, BufferUsage.COPY_SOURCE)
    );
    ResourceTransactionEvidence published = renderer.submitResources(
            RenderResourceTransaction.builder(1L).upsert(input).build()
    );
    if (published.outcome() != ResourceTransactionEvidence.Outcome.ACCEPTED) {
        throw new IllegalStateException(published.detail());
    }

    ResourceData bytes = new ResourceData(ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder()));
    CommandExecutionEvidence submitted = renderer.submitCommands(
            RenderCommandTransaction.builder(1L)
                    .add(new WriteBufferCommand(new ResourceSlice.BufferSlice(input, new ByteRange(0, 256)), bytes))
                    .build()
    );
    // RECORDED 只表示已提交；随后用 commandExecutionEvidence(1L) 观察 GPU_COMPLETED。
}
```

能力必须逐项查询。`3.1.7` 的 Vulkan 后端真实执行版本化 buffer/texture、view/sampler、staging upload、buffer/texture copy、buffer-image copy、typed clear/barrier、SPIR-V compute/graphics pipeline、render pass、direct/multi/indirect draw，以及在设备支持时执行通用 BLAS/TLAS build、显式 RT shader group/SBT、AS descriptor 和 trace dispatch，并通过 fence evidence 公开完成状态。`BindingType.COMBINED_IMAGE_SAMPLER` 必须使用一个 `BindingSet.CombinedImageSamplerValue`，Vulkan 会将同一 view/sampler 对写入一个 `VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER`。接口布局会在 pipeline 创建前与 SPIR-V 逐项核对；错误的 entry point、stage、set/binding、数组长度或拆分 descriptor 声明均 fail-closed。RT 的 `RECORDED` 或 `OUTPUT_PRODUCED` 都不是显示可见证据；能力未标为 executable 的功能不能根据静态类型推断可用。

## 发布一个最小场景

公共模型使用语义工厂与 Builder，避免长有序构造器。调用方提供的数组会在安全工厂中复制；高吞吐路径可以显式使用只读 direct buffer 工厂。

## Exact clip-space projection

Hosts that already own the final projection must use the explicit exact path. It is not inferred
from a matrix and it never silently falls back to the basis/FOV path. Matrices are validated,
copied, inverted once, and stored canonically as row-major values. The renderer currently accepts
the Vulkan forward depth attachment convention (`ZERO_TO_ONE`); other conventions remain
representable in the API but fail closed at the Vulkan provider boundary.

```java
double[] cameraToWorld = {
        1, 0, 0, 3,
        0, 1, 0, 4,
        0, 0, 1, 5,
        0, 0, 0, 1
};
double[] clipFromView = {
        1, 0, 0, 0,
        0, 1, 0, 0,
        0, 0, -100.0 / 99.0, -100.0 / 99.0,
        0, 0, -1, 0
};
ExactProjectionState projection = ExactProjectionState.builder(1920, 1080)
        .matrixLayout(ExactProjectionState.MatrixLayout.ROW_MAJOR)
        .coordinateSystem(ExactProjectionState.CoordinateSystem.RIGHT_HANDED_NEGATIVE_Z_FORWARD)
        .depthConvention(ExactProjectionState.DepthConvention.ZERO_TO_ONE)
        .jitter(ExactProjectionState.JitterConvention.PIXEL_CENTER_OFFSET, 0.25, -0.25)
        .cameraToWorld(cameraToWorld)
        .clipFromView(clipFromView)
        .build();
CameraState camera = CameraState.exactProjection(projection);
```

`CameraState.lookAt(...)` and `explicitBasis(...)` remain the compatible fast path. The exact
path consumes the inverse clip matrix and camera-to-world rotation in GPU raygen; it is therefore
safe for rotated, non-uniform, non-16:9 projections. A malformed, singular, non-finite, layout-
unspecified, or viewport-mismatched mapping is rejected before submission.

The existing `MeshAsset.vertexColorsRgba8()` field is raw authored RGBA8 data normalized by the
renderer as numeric channels. It is not implicitly decoded as sRGB. Hosts that need authored sRGB
tints require a separate future semantic field; changing this field would be a color-management
regression.

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
        .cardinalLighting(CardinalLightingState.objectSpace(
                0.80F, 0.80F, 0.55F, 1.00F, 0.70F, 0.70F))
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

`CardinalLightingState` 按未受 normal map 扰动的几何法线主轴选择 -X/+X/-Y/+Y/-Z/+Z
倍率，并在 terminal shading 前调制 base-color RGB。`objectSpace(...)` 让明暗模式跟随实例旋转，
`worldSpace(...)` 则按变换后的世界方向分类；六个倍率都必须位于 `[0, 1]`。该状态只更新实例
SSBO，mesh 与 BLAS 继续共享，不会因每实例光照差异复制或重建。

连续表面光照使用独立的 `DirectionalDiffuseState`，不能与 `CardinalLightingState` 同时启用：

```java
DirectionalDiffuseState actorLighting = DirectionalDiffuseState.builder()
        .coordinateSpace(DirectionalDiffuseState.CoordinateSpace.OBJECT)
        .firstDirection(0.2F, 1.0F, -0.7F)
        .firstIntensity(0.6F)
        .secondDirection(-0.2F, 1.0F, 0.7F)
        .secondIntensity(0.6F)
        .ambient(0.4F)
        .backFacePolicy(DirectionalDiffuseState.BackFacePolicy.FLIP_ON_BACK_FACE)
        .build();

InstanceRenderState actorState = InstanceRenderState.builder()
        .directionalDiffuse(actorLighting)
        .build();
```

方向表示从着色点指向光源，构建时会归一化；精确倍率为
`clamp(ambient + firstIntensity * max(dot(normal, firstDirection), 0)
+ secondIntensity * max(dot(normal, secondDirection), 0), 0, 1)`。法线使用 normal map
之前的重心插值顶点法线；mesh 没有 normal stream 时回退到几何法线。`KEEP_AUTHORED` 保留
背面的原始朝向，`FLIP_ON_BACK_FACE` 只在真实背面命中时翻转一次。该状态同样只修改实例 SSBO，
不会复制 mesh 或重建 BLAS；同时配置 cardinal 与 directional diffuse 会在 `build()` 时失败。

裂纹、破坏纹理等 receiver-aware overlay 应显式选择乘法合成，不能用 alpha-over 模拟：

```java
MaterialAsset crackMaterial = MaterialAsset.builder(crackMaterialId)
        .blendMode(MaterialAsset.BlendMode.TRANSLUCENT)
        .shadingModel(MaterialAsset.ShadingModel.UNLIT)
        .baseColorTextureId(crackTextureId)
        .surfaceOverlay(SurfaceOverlayState.depthEqual(
                0.002F, SurfaceOverlayState.CompositionMode.MULTIPLY))
        .build();
```

`MULTIPLY` 的定义是 `receiver * mix(1, overlayRgb, alpha)`：透明 texel 保留 receiver，
不透明深色 texel 执行真正的乘法压暗。未传 composition mode 的 `depthEqual(...)` 和
`depthBias(...)` 保持 `ALPHA_OVER`，以兼容普通 decal。

## 选择 GPU

不要根据设备名称猜测能力。先枚举设备，再按稳定 identity 或显式 capability 选择：

```java
List<RendererGpuDevice> devices = RendererBootstrap.availableGpuDevices();
RendererGpuDevice selected = devices.stream()
        .filter(device -> device.type() == RendererGpuDevice.Type.DISCRETE)
        .max(Comparator.comparingLong(RendererGpuDevice::deviceLocalMemoryBytes))
        .orElseThrow(() -> new IllegalStateException("No supported RTX GPU"));

RendererConfig config = RendererConfig.expertBuilder()
        .gpuDevice(selected)
        .build();

try (Renderer renderer = RendererBootstrap.open(config)) {
    // publish scene and submit frames
}
```

设备枚举返回的 `HardwareCapabilities` 是物理 probe 事实，不是技术运行状态：

```java
HardwareCapabilities hardware = selected.hardwareCapabilities();
if (!hardware.supports(HardwareCapabilities.Feature.HARDWARE_RAY_TRACING)) {
    throw new IllegalStateException(hardware.reason());
}
HardwareCapabilities.FrameInteropSupport rgba8Win32 = hardware.frameInterop(
        FrameOutputFormat.SDR_RGBA8,
        HardwareCapabilities.ExternalHandleType.OPAQUE_WIN32
);
```

`SupportState.UNKNOWN` 表示未取得可靠证据，不能当作支持。external interop 必须读取具体
format/handle 的 memory 与 semaphore 方向，不能只检查 Vulkan extension 名称。renderer 打开后，
可选技术是否协商或真正执行仍分别读取 `RenderingFeatureCapabilities` 与 diagnostics。

## 常用配置

| Builder 方法 | 默认值 | 什么时候改 |
| --- | --- | --- |
| `maxFramesInFlight(int)` | `RendererConfig.DEFAULT_MAX_FRAMES_IN_FLIGHT` | 调整 CPU/GPU 并行深度；合法范围为 `MIN_MAX_FRAMES_IN_FLIGHT..MAX_MAX_FRAMES_IN_FLIGHT` |
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
| `gpuDevice(RendererGpuDevice)` | 自动选择 | 必须绑定确定 GPU 时修改 |

普通 CPU-readable 路径直接调用 `RendererBootstrap.open(RendererPreset.CPU_READBACK)`：该 preset 以 `PREFERRED`
自动协商 SR、NRD、SER 与 RTXMU，不支持的实现保留各自的 renderer fallback。该路径没有
swapchain ownership，因此不会请求 FG/MFG 或 display pacing，避免产生无法 retire 的
presentation-time 输入。

只通过官方 GPU presenter 消费帧时，调用
`RendererBootstrap.open(RendererPreset.MANAGED_GPU_PRESENTATION)`；该 preset 关闭 CPU readback，并在普通自动质量
策略之上请求 FG 2x 与 Reflex/PCL。MFG 永远不由普通 preset 自动开启，只能通过专家 builder
显式选择 3x/4x。provider 始终检查 Vulkan feature/extension、SDK、驱动、真实 adapter 和
资源合同，不能由 GPU 型号字符串猜测能力；显式 `REQUIRED` 也不允许被 fallback 吞掉。
只使用专家 `VulkanFrameInterop`、但没有官方 managed presenter 的应用，应从
`RendererPreset.CPU_READBACK.configuration().copyBuilder().cpuFrameReadbackEnabled(false)` 开始，
再交给 `RendererBootstrap.open(...)`；不能错误借用会请求 swapchain frame generation 的
GPU-presenter preset。

`RendererConfig.expertBuilder()` 是专家显式基线，所有 vendor 技术默认禁用；
`RendererPreset.CPU_READBACK.configuration().copyBuilder()` 和
`RendererPreset.MANAGED_GPU_PRESENTATION.configuration().copyBuilder()` 用于专家派生相应 preset。
派生后的配置必须通过 `RendererBootstrap.open(...)` 打开。两种入口最终进入同一
capability negotiation 和执行状态机，不存在绕过生命周期约束的隐藏模式开关。下面是普通
CPU-readable preset 的等价专家配置：

```java
RendererConfig explicitProduction = RendererConfig.expertBuilder()
        .temporalRendering(TemporalRenderingOptions.balanced())
        .frameReconstruction(FrameReconstructionOptions.recommended())
        .denoising(DenoisingOptions.recommended())
        .frameGeneration(FrameGenerationOptions.disabled())
        .lowLatency(LowLatencyOptions.disabled())
        .rayTracingOptimizations(RayTracingOptimizationOptions.recommended())
        .build();
```

### 完整专家配置

专家模式只表达应用意图，不接管 Vulkan、Streamline、NRD 或 RTXMU 的资源 owner。下面的配置
显式覆盖 3.1.7 中保留的全部 NVIDIA 能力，同时保持生产环境可降级：DLSS SR 不可用时允许 NIS，
NRD 不可用时保留内建时域路径，FG/MFG 不可用时继续发布原生帧。SER 和 RTXMU 独立
协商，某一项不支持不会阻止其他项启用。

```java
RendererConfig expert = RendererConfig.expertBuilder()
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
        .lowLatency(LowLatencyOptions.recommended())
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

### 运行期功能变更

只有需要显式运行期控制的专家调用方才发现 `RendererFeatureController`。先创建完整 target profile，
再规划一次 generation-bound 转换：

```java
RendererFeatureController controller = renderer
        .extension(RendererFeatureController.class)
        .orElseThrow(() -> new IllegalStateException("runtime feature control unavailable"));
RendererFeatureProfile target = controller.effectiveProfile().toBuilder()
        .frameReconstruction(FrameReconstructionOptions.recommended())
        .build();
RendererFeaturePlan plan = controller.plan(target);

if (plan.disposition() == RendererFeaturePlan.Disposition.APPLICABLE
        || plan.disposition() == RendererFeaturePlan.Disposition.UNCHANGED) {
    RendererFeatureApplyResult result = controller.apply(plan);
    if (result.outcome() != RendererFeatureApplyResult.Outcome.APPLIED
            && result.outcome() != RendererFeatureApplyResult.Outcome.UNCHANGED) {
        throw new IllegalStateException(result.reason());
    }
}
```

plan 是 single-use，旧 generation、其他 controller、已消费或被后续 plan 取代的值返回
`STALE_PLAN`。`RETRY_AFTER_FRAME_DRAIN` 要求应用在安全边界重新规划；`REQUIRES_SWAPCHAIN_REBUILD`、
`REQUIRES_PIPELINE_REBUILD`、`REQUIRES_SCENE_REBUILD` 与 `REQUIRES_RENDERER_REBUILD` 把最小重建
责任明确交还应用。库不会为了“热切换”静默重建或丢弃资源。controller 存在不证明某项转换可在
session 内完成，只有 `APPLIED` 是提交证据。

### 各能力的最短配置

以下片段都只需要 `top.ceroxe.rt:renderer-api:3.1.7`。把对应 options 传给
`RendererConfig.expertBuilder()` 即可；没有任何片段要求额外模块或手工 DLL。

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

修改已有配置使用 `copyBuilder()`，不要重新拼长参数列表：

```java
RendererConfig hdr = config.copyBuilder()
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
Renderer.FrameSubmissionAttempt attempt = renderer.trySubmit(request);
if (attempt instanceof Renderer.FrameSubmitted submitted) {
    sequence = Math.addExact(sequence, 1L);
} else if (attempt instanceof Renderer.FrameSubmissionDeferred deferred) {
    // 控制流读取稳定枚举，detail() 只用于诊断；保留相同 request/sequence 稍后重试。
    switch (deferred.deferralReason()) {
        case PRESENTATION_BACKLOG, FRAME_RING_FULL -> LockSupport.parkNanos(250_000L);
        default -> LockSupport.parkNanos(1_000_000L);
    }
}
```

`FrameSubmissionDeferred` 只代表本次没有发布任何逻辑或 native submission 状态。`deferralReason()` 是所有 provider 必须提供的稳定遥测与重试分类；`detail()` 只供人类诊断，禁止解析。顺序、revision、生命周期和 device failure 仍抛出对应 typed exception。`submit(...)` 保留给“拒绝即异常”的控制流。

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
try (Renderer renderer = RendererBootstrap.open(RendererPreset.MANAGED_GPU_PRESENTATION);
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
