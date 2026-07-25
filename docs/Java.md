# Java 开发指南

RTRendererAPI 适合嵌入 Java 25 桌面或引擎进程。普通调用方只需要理解场景 revision、帧 sequence 和资源生命周期；Vulkan external memory、semaphore、queue-family ownership 等细节被隔离在显式专家扩展中。

Maven 坐标是 `top.ceroxe.rt:renderer-api:0.2.0`。只声明这一个依赖即可，Windows Vulkan 后端与 natives 会传递解析。

## 最小调用

`RendererBootstrap.open()` 会枚举已安装 provider、执行兼容性探测并打开优先级最高的可用后端。当前发布只接受 Windows 10+、NVIDIA RTX 20 系或更新 GPU、Vulkan 1.2+ 与 Java 25。

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

SceneInstance instance = SceneInstance.builder(4L, mesh.id()).build();
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
| `maxFramesInFlight(int)` | `3` | 调整 CPU/GPU 并行深度；合法范围由 API 常量约束 |
| `validationEnabled(boolean)` | 生产默认值 | 调试 Vulkan validation 时开启 |
| `gpuTimingsEnabled(boolean)` | 生产默认值 | 需要 GPU stage timing 时开启 |
| `cpuFrameReadbackEnabled(boolean)` | `true` | 只走 GPU presenter/interop 时关闭 CPU buffer 与 image-to-buffer copy |
| `frameOutputFormat(FrameOutputFormat)` | `SDR_RGBA8` | 专家链路需要 linear HDR RGBA16F 时修改 |
| `temporalRendering(TemporalRenderingOptions)` | `balanced()` | 禁用或调整 temporal history 长度 |
| `gpuDevice(RayTracingGpuDevice)` | 自动选择 | 必须绑定确定 GPU 时修改 |

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
    // 保留相同 request/sequence，稍后重试；不要 busy-spin。
    LockSupport.parkNanos(25_000L);
}
```

`FrameSubmissionDeferred` 只代表本次没有发布任何逻辑或 native submission 状态。顺序、revision、生命周期和 device failure 仍抛出对应 typed exception。`submit(...)` 保留给“拒绝即异常”的控制流。

## 结果与健康状态

`submit(...)` 返回 typed result，不要通过日志文本判断是否被接收。运行期治理读取：

```java
RendererHealth health = renderer.health();
RendererDiagnostics diagnostics = renderer.diagnostics();
```

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
| `SubmissionRejectedException` | 有界队列或运行期策略拒绝提交 |

不要解析异常 message 做控制流；使用结构化字段、reason 和 recovery action。

## 官方 GPU presenter

需要窗口显示但不想自行实现 Vulkan interop 的应用，使用官方 renderer-bound presenter：

```java
RayTracingRendererConfig config = RayTracingRendererConfig.builder()
        .cpuFrameReadbackEnabled(false)
        .build();

try (RayTracingRenderer renderer = RendererBootstrap.open(config);
     VulkanFramePresenter presenter = VulkanFramePresenter.open(
             renderer,
             VulkanFramePresenterConfig.builder()
                     .initialExtent(2560, 1600)
                     .presentMode(VulkanFramePresenterConfig.PresentMode.UNCAPPED)
                     .maximumFramesQueuedAhead(2)
                     .build())) {
    VulkanFrameInterop interop = renderer.extension(VulkanFrameInterop.class).orElseThrow();
    presenter.pollEvents();
    if (interop.pollLatestFrame() instanceof VulkanFrameInterop.FrameAvailable available) {
        VulkanFramePresenter.PresentationResult result =
                presenter.presentAndRelease(available.lease());
    }
}
```

presenter 的 open、event pump、present、title 和 close 都绑定创建线程。`presentAndRelease(...)` 在成功、minimized skip、swapchain recreate 或失败时都负责消费并关闭传入 lease。`maximumFramesQueuedAhead(2)` 允许 trace 与 present 重叠，同时阻止生产队列饿死 swapchain；它不锁 FPS。显示统计只应在 `Outcome.PRESENTED` 时累计，`activePresentMode()` 才是平台实际模式。

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

这些命令不会把制品发布到远程 Maven 仓库。
