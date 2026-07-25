# API 迁移指南

本文记录 0.2.0 公共 API 中需要消费方修改源码或生命周期的迁移。机械边界以同版本 Javadoc、ABI baseline 和隔离 Maven 消费者为准。

## 0.2.0 提交背压

持续渲染循环应从异常型 `submit(...)` 迁移到 `trySubmit(...)`：

```java
RayTracingRenderer.FrameSubmissionAttempt attempt = renderer.trySubmit(request);
if (attempt instanceof RayTracingRenderer.FrameSubmitted) {
    sequence++;
} else {
    // 本次没有发布状态；保留 sequence，稍后重试。
}
```

只有普通容量拒绝返回 `FrameSubmissionDeferred`；顺序、场景 revision、生命周期和设备错误继续抛 typed exception。不要在 deferred 后推进 sequence、物理状态或 scene revision，也不要无休止 busy-spin。

## 0.2.0 官方 Vulkan presenter

只需要 GPU 窗口显示的应用不再需要自行编写 native import/present 代码。使用 `VulkanFramePresenter.open(renderer, config)`；内置 backend 会复用 renderer 的 logical device 并拥有窗口、swapchain、managed copy、completion 和 lease close，只有专家/兼容路径才使用 external import。

- `PresentMode` 是偏好；用 `activePresentMode()` 读取平台实际的 `IMMEDIATE`、`MAILBOX` 或 `FIFO`。
- `maximumFramesQueuedAhead` 是 producer lead 上限，不是 FPS 限制。
- 只在 `PresentationResult.outcome() == PRESENTED` 时统计实际 present FPS。
- `SKIPPED_MINIMIZED` 与 `RETIRED_FOR_RECREATE` 已消费 lease，但不代表可见帧。
- `performanceSnapshot()` 分离 acquire、managed GPU copy、queue lock 与 native present。
- `setOverlayText(...)` 提供无需 CPU readback 的 transfer-only 画面内诊断 HUD。
- `windowMode(...)` 可选择普通窗口或主显示器全屏；可选 full-screen-exclusive hint 不可用时自动回退。

已有自定义 Vulkan consumer 继续使用 `VulkanFrameInterop`。`FrameDescriptor` 新增：

- `resourceId()`：同一底层 external image 生命周期内稳定，用于安全缓存 import；不得用 frame sequence 代替。
- `memoryTypeIndex()`：producer allocation 使用的显式 Vulkan memory type；consumer import image requirements 必须允许该 bit。

descriptor metadata 与同一 `resourceId` 冲突必须视为协议错误，不能静默重建或猜测。

## 0.1.2 托管 readback 策略

普通 `CpuFrame` 消费方无需修改：默认路径现在使用 frame-slot 常驻异步 readback，不再为每帧创建 staging buffer、单独提交复制或等待 queue idle。

只消费 Vulkan external-memory lease 的应用可避免任何 host readback 分配与 image-to-buffer copy：

```java
RayTracingRendererConfig config = RayTracingRendererConfig.builder()
        .cpuFrameReadbackEnabled(false)
        .build();
```

该策略属于 renderer-lifetime 配置。关闭后调用 `pollLatestCpuFrame()` 或托管 await API 会明确抛出 `UnsupportedOperationException`，不会退回同步读回。

## 托管帧与显式 Vulkan 扩展

普通调用方使用托管 RGBA8 帧，不再接触 GPU lease：

```java
Optional<CpuFrame> frame = renderer.pollLatestCpuFrame();
```

零拷贝 Vulkan 调用方必须显式发现扩展：

```java
VulkanFrameInterop interop = renderer.extension(VulkanFrameInterop.class)
        .orElseThrow(() -> new IllegalStateException("Vulkan interop unavailable"));
VulkanFrameInterop.FramePollResult result = interop.pollLatestFrame();
```

| 旧入口                                 | 0.1.1+ 入口                                             |
|-------------------------------------|-------------------------------------------------------|
| `renderer.acquireLatestFrame()`     | 已删除；使用 `interop.pollLatestFrame()` 并穷尽处理 typed result |
| `renderer.pollLatestFrame()`        | `interop.pollLatestFrame()`                           |
| `renderer.awaitLatestFrame(...)`    | `interop.awaitLatestFrame(...)`                       |
| `RayTracingRenderer.FrameAvailable` | `VulkanFrameInterop.FrameAvailable`                   |
| `RayTracingRenderer.FrameNotReady`  | `VulkanFrameInterop.FrameNotReady`                    |
| `renderer.api.GpuFrameLease`        | `renderer.api.interop.vulkan.GpuFrameLease`           |
| raw Vulkan descriptor `int`         | 对应的 `Vulkan*` 强类型                                     |

迁移后，普通代码不应 import `api.interop.vulkan`。专家代码必须重新验证 import failure、duplicate import、release failure
retry、close failure retry 和 imported-active lease 关闭拒绝。

## Lease 状态与健康状态

旧 `released()`/`closed()` 布尔观察方法已删除。统一使用 `lease.state()` 读取互斥生命周期：

```text
ACTIVE -> RELEASED -> CLOSED
```

completion 发布失败保持 `ACTIVE`；native handle cleanup 失败保持 `RELEASED`。调用方可以重试失败的原操作，但不能跳过阶段。

恢复和关闭协调器使用 `renderer.health()`：

- `RendererHealth.Failure` 提供稳定 failure kind、recovery action、operation 和可选 native result。
- `ResourceObligations` 提供 outstanding GPU lease、native cleanup 和 device recovery 欠账。

不要解析异常 message 或只根据 `RECOVERING` 猜测资源状态。

## Native 帧输出格式

默认保持 `FrameOutputFormat.SDR_RGBA8`。需要 linear HDR expert lease 时显式选择：

```java
RayTracingRendererConfig config = RayTracingRendererConfig.builder()
        .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
        .build();
```

HDR native lease 是未 tone-map 的 linear scene-referred RGBA16F。托管 `CpuFrame` 在 SDR/HDR 配置下都返回 display-ready
RGBA8。不支持所选 storage/export format 的设备会在 probe/open 被拒绝，不会自动改成 SDR。

有序 `RayTracingRendererConfig` 构造器和 `withXxx(...)` 别名已移除。新代码使用 `builder()`；修改现有配置时使用
`toBuilder()`，避免布尔值和后续新增策略发生位置错配。

## 确定性空间抗锯齿

有序 `RenderFrameRequest` 构造器和 `withAntiAliasing(...)` 已移除。质量优先路径使用 semantic builder：

```java
RenderFrameRequest request = RenderFrameRequest.builder(sequence, width, height, camera)
        .minimumSceneRevision(sceneRevision)
        .environment(environment)
        .antiAliasing(AntiAliasingState.multisampled(4))
        .build();
```

支持 2、4、8 spp；1 spp 使用默认值或 `AntiAliasingState.disabled()`。固定 subpixel sequence 在单帧内解析，不包含 temporal
history、随机种子或跨帧隐藏状态。

`SceneInstance` 的旧 6 参数快捷构造器也已移除。普通实例使用 `SceneInstance.builder(id, meshAssetId).build()`；只有需要覆盖默认
transform、mobility、visibility、shadow 或 surface visibility 时才调用对应 builder 方法。

## 验证迁移

```cmd
.\gradlew.bat :renderer-api:verifyRendererApiAbi verifyPublishedMavenConsumer --dependency-verification=strict --no-daemon --console=plain
```

该命令验证 ABI 与本地 staging 的独立消费方，不会向远程 Maven 仓库发布制品。
