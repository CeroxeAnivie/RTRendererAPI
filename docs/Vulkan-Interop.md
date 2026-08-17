# Vulkan 专家互操作

隔离 Maven consumer 中的 [`PublishedVulkanInteropConsumer`](../gradle/published-consumer-smoke/src/main/java/consumer/PublishedVulkanInteropConsumer.java)
是随发布门禁编译的完整控制流样例：它演示 extension discovery、有界等待、非空结果、lease 的
`ACTIVE -> RELEASED -> CLOSED` 顺序，以及只按 `RendererDeviceException.RecoveryAction` 决策的恢复路径。应用只需在
`NativeFrameConsumer` 内实现自身 Vulkan device/queue 的 import、ownership transfer 与 submission；不得把这些 native
职责隐藏回普通 `Renderer` 路径。

`VulkanFrameInterop` 是显式 opt-in 的零拷贝外部图像接口。它只适合已经能正确实现 Vulkan external memory、external
semaphore、queue-family ownership transfer 和 Win32 handle 所有权的调用方。普通应用应使用
`Renderer.pollLatestCpuFrame()`/有界等待，或使用官方 `VulkanFramePresenter` 完成 GPU 窗口显示。

## 官方托管 presenter 与自定义专家 consumer

两条 GPU 路径使用同一 lease 契约，但所有权层级不同：

| 路径 | 调用方负责 | API/provider 负责 |
| --- | --- | --- |
| `VulkanFramePresenter` | 创建配置、pump event、提交帧、调用 `presentLatestFrame` | 同设备 managed timeline fast path、swapchain copy/present、HUD、completion、lease close；必要时 external fallback |
| `VulkanFrameInterop` | 全部 consumer Vulkan import、queue ownership、同步、submission、completion 与 lease close | producer image/handle export 与契约验证 |

官方 presenter 通过 `VulkanFramePresenter.open(renderer, config)` 打开。其全部方法绑定创建线程；同一 renderer 同时只允许一个官方 presenter。简单模式调用 `presentLatestFrame()`，由 provider 获取可用 internal managed lease；该路径复用 renderer 的 `VkInstance`、`VkDevice` 与受控 queue，并用 GPU timeline 表达 producer-ready 依赖，不导出 Win32 memory handle。`VulkanFrameInterop.pollLatestFrame()` 是独立专家入口，继续只返回 CPU 已观察完成的 external lease。两种消费模式在同一 renderer session 内互斥：存在 expert lease 时不能打开 presenter，presenter 打开期间也不能 poll expert lease。`maximumFramesQueuedAhead` 限制 producer lead，但不按时间锁 FPS。

`PresentMode.UNCAPPED` 依次偏好 immediate、mailbox、FIFO；`LOW_LATENCY` 偏好 mailbox；`VSYNC` 使用 FIFO。平台可以 fallback，唯一权威结果是 `activePresentMode()`。`PresentationResult` 的 `PRESENTED` 才能计入实际 present FPS；minimized 和 swapchain recreate 结果会安全退休 lease，但不是可见帧。同一 `VkSwapchainKHR` 的 acquire/present host access 必须串行；不得用跨线程竞争伪造吞吐。`performanceSnapshot()` 用于区分 acquire、GPU copy、queue lock 与 native present；`setOverlayText(...)` 可把诊断值直接画入 swapchain。

### Streamline proxy swapchain 与帧生成所有权

FG/MFG 启用时，官方 presenter 通过 Streamline 官方 Vulkan WSI proxy 函数创建、枚举、acquire、present 和销毁 swapchain；
没有第二套可由应用直接调用的 swapchain owner。proxy 调用仍受 Vulkan host synchronization 约束，全部 acquire/present
操作保持在 presenter owner 线程串行执行。`VK_SUBOPTIMAL_KHR` 和 `VK_ERROR_OUT_OF_DATE_KHR` 进入有界的
retire/recreate 路径，不把正常 WSI 重建永久解释为功能熔断。

每个 native frame 使用同一个严格递增 sequence 获取 Streamline frame token。HUD-less color、depth 和 motion vectors 以
`VK_IMAGE_LAYOUT_GENERAL` 及真实 extent/format/usage 提交 `eValidUntilPresent` tag；只有 exact token、backbuffer extent 和 tagged
资源合同全部匹配时才请求生成，否则该帧确定性回退为 native present 并增加 request-miss 诊断。presenter 的 copy 目标在
调用 native 或 proxy `vkQueuePresentKHR` 前必须从 `TRANSFER_DST_OPTIMAL` 回到 `VK_IMAGE_LAYOUT_PRESENT_SRC_KHR`。

同步和资源回收遵循以下边界：

1. presenter 等待 swapchain acquire binary semaphore 和 producer-ready timeline，再提交 copy，并 signal present semaphore。
2. proxy present 消费该 present semaphore；同一 frame context 的 retirement fence 证明 presenter/Streamline 对 tagged 输入的访问已经完成。
3. fence 完成后，以原 frame token 为 HUD-less color、depth 和 motion vectors 提交 null tag；null tag 成功前不得释放或复用其 image/view。
4. tag retirement、managed lease release/close 和 host backlog 通知是一个可重试事务。任一步失败都保留 pending lease，不能提前把 slot 归还 producer。
5. minimized、skipped、fallback、swapchain recreate 和 presenter close 使用同一 retirement 事务；post-present diagnostics 失败不能跳过清理。

swapchain recreate/close 先把 DLSS-G 配置为 off，再释放全部剩余 tag。配置 off 失败时保留原 proxy swapchain 供重试；tag 释放
失败时也禁止销毁仍被 Streamline 引用的图像。完成这些资源引用义务后，关闭路径等待 device idle 覆盖 proxy 的异步工作，随后
销毁 proxy swapchain，最后才销毁 frame-context fence/semaphore、feature session 和 Vulkan device。该顺序是 provider 内部合同，
应用不得通过直接销毁 window、swapchain 或 renderer 绕过 `VulkanFramePresenter.close()`。

## 获取扩展

```java
VulkanFrameInterop interop = renderer.extension(VulkanFrameInterop.class)
        .orElseThrow(() -> new IllegalStateException("Vulkan interop unavailable"));
```

扩展缺失表示当前 provider 不支持该契约。调用方不得通过强制类型转换、反射或假定 backend 实现来绕过能力发现。

## 获取帧

| 方法                                          | 返回                                          | 说明                                 |
|---------------------------------------------|---------------------------------------------|------------------------------------|
| `pollLatestFrame()`                         | `FrameAvailable` 或 `FrameNotReady.INSTANCE` | 非阻塞、非 null、穷尽结果                    |
| `awaitLatestFrame(Duration)`                | 同上                                          | 有界等待，超时返回 `FrameNotReady.INSTANCE` |
| `awaitLatestFrameAsync(Duration, Executor)` | `CompletableFuture<FramePollResult>`        | 使用调用方拥有的 executor                  |

`FrameAvailable.lease()` 转移一个新 lease 的独占 consumer 所有权。每个成功获取的 lease 都必须完成必要同步、发布
completion，并关闭。

## Lease 中的事实

`GpuFrameLease` 提供：

- `descriptor()`：帧 sequence、scene revision、extent、Vulkan format/type/tiling/usage/create
  flags/layout、mip/layer/sample、sharing mode、producer queue family、`memoryTypeIndex`、allocation size/offset 和 dedicated allocation；`resourceId` 在同一底层 image 生命周期内稳定。
- `memoryHandle()`：导出内存 Win32 handle 的有状态 owner，包含强类型 memory handle type、import disposition 和 handle
  state。
- `acquireSignal()`：可选 producer completion semaphore；为空表示 producer completion 已由 CPU 观察。
- `consumerCompletionCapabilities()`：该 concrete lease 实际接受的 completion 机制。
- `state()`：权威 `ACTIVE`、`RELEASED`、`CLOSED` 状态。

所有 Vulkan 数值都使用不同的强类型包装。不要把 memory handle type、semaphore handle type、format、layout、queue family 或普通
`int` 相互替代。

`resourceId` 不是 frame sequence。consumer 可以用它缓存同一个 external image 的 import，但每次 lease 仍拥有独立的 acquire/completion 生命周期。同一 `resourceId` 若对应不同的 extent、format、tiling、usage、layout、memory type 或 allocation metadata，必须拒绝为协议错误。导入前还必须验证 image memory requirements 的 `memoryTypeBits` 包含 descriptor 的 `memoryTypeIndex`。

## 正确顺序

一个完整 lease 必须按以下顺序处理：

1. 读取 `FrameDescriptor`，创建与 descriptor 完全一致的 consumer-side Vulkan image/import 配置。
2. 导入 `memoryHandle().value()`。只有 Vulkan import 成功后才能调用 `memoryHandle().markImported()`。
3. 如果有 `acquireSignal()`，导入其 semaphore handle；只有 import 成功后才能调用该 handle owner 的 `markImported()`。
4. 提交 consumer GPU 工作：等待 acquire signal，并在需要时从 `VK_QUEUE_FAMILY_EXTERNAL` 获取 image ownership。
5. 等待或生成该 lease 明确支持的 consumer completion；若转移过 queue-family ownership，在 completion 前释放回
   `VK_QUEUE_FAMILY_EXTERNAL`。
6. 调用 `lease.release(completion)`。成功后状态从 `ACTIVE` 变为 `RELEASED`。
7. 调用 `lease.close()` 关闭 consumer-side lease ownership 和 exporter 仍拥有的 handle。成功后状态为 `CLOSED`。

这些 mutation 必须由调用方串行：handle import 必须先于 `release(...)`/`close()` 完成，`release(...)` 与 `close()` 也不能并发。

## Handle 所有权

`ExportedNativeHandle` 状态是：

```text
EXPORTED -> IMPORTED
EXPORTED -> CLOSED
IMPORTED -> CLOSED
```

规则：

- import 失败：不要调用 `markImported()`；关闭 handle owner，让 exporter 关闭仍归它所有的 Win32 handle。
- import 成功：立即调用一次 `markImported()`；它根据 `ImportDisposition` 应用“import 消耗 handle”或“caller 继续拥有
  handle”的规则。
- `markImported()` 只有第一次成功转换返回 `true`；不要用重复调用掩盖控制流错误。
- 不要直接关闭 `value()` 表示的 OS handle 后再让 owner 关闭；所有权必须始终由 stateful owner 记录。

## Acquire signal

`acquireSignal()` 返回 `Optional<AcquireSignal>`：

| 字段                | 语义                            |
|-------------------|-------------------------------|
| `handle()`        | 导出 semaphore handle owner     |
| `kind()`          | `BINARY` 或 `TIMELINE`         |
| `timelineValue()` | binary 必须为 `0`；timeline 必须为正值 |

消费者必须在访问共享 image 前满足该 signal。`Optional.empty()` 只表示 producer completion 已在 CPU 侧观察，不取消 image
layout、memory visibility 或 queue-family 规则。

## Consumer completion

先读取 `consumerCompletionCapabilities()`，再选择 completion：

| completion                         | 使用条件                     | 调用方保证                                                     |
|------------------------------------|--------------------------|-----------------------------------------------------------|
| `new GpuFrameLease.CpuCompleted()` | `cpuCompleted() == true` | CPU 已确认 consumer GPU 使用完成，且必要的 external queue release 已完成 |
| `ExternalSemaphoreSignal`          | `supports(kind) == true` | 提供已由 consumer signal 的可导入 external semaphore，字段和所有权规则完整匹配 |

当前 Windows Vulkan backend 会逐 lease 协商能力；调用方不能因为公共 API 定义了某种 completion 就假定 backend 接受它。不支持的
completion 会抛 `UnsupportedOperationException`，不会静默等待或退化。

`ExternalSemaphoreSignal` 的 handle 仍由调用方按 `importDisposition` 收尾：`release(...)` 失败时调用方继续拥有
handle；成功且为 `CALLER_RETAINS_HANDLE` 时调用方必须关闭 handle；成功且为 `IMPORT_CONSUMES_HANDLE` 时不得再次关闭。当前
Windows backend 只在 capability 协商通过后接受 binary `OPAQUE_WIN32` completion，并要求 `CALLER_RETAINS_HANDLE`。

## 失败与重试

lease 状态机是：

```text
ACTIVE -> RELEASED -> CLOSED
```

- `release(...)` 失败时保持 `ACTIVE`，调用方仍拥有 completion/handle，可在修复瞬时错误后重试原操作。
- native handle cleanup 失败时保持 `RELEASED`，调用方可重试 `close()`。
- imported 且仍为 `ACTIVE` 的 lease 不能直接关闭；renderer 不会猜测 consumer GPU 已完成。
- 关闭 renderer 前必须归还全部 lease。`RendererHealth.obligations().outstandingGpuFrameLeases()` 可观测未归还数量。

## Native 输出格式

renderer 创建时选择 `FrameOutputFormat`：

| 配置                   | Descriptor format 语义          | Resolve 语义                            |
|----------------------|-------------------------------|---------------------------------------|
| `SDR_RGBA8`          | display-ready RGBA8           | 每样本 tone-map/linear-to-sRGB 后 resolve |
| `LINEAR_HDR_RGBA16F` | linear scene-referred RGBA16F | 线性 radiance resolve，不 tone-map        |

后端在 probe/open 阶段验证对应 storage image 与 Win32 external-memory export 能力；不支持时拒绝配置，不会改成另一种格式。不要根据设备名称推断
capability，使用 `RendererGpuDevice.hardwareCapabilities()` 的格式与句柄级证据，并以实际 open 结果为最终准入结论。

## 关闭检查表

- 所有成功 import 都已 `markImported()`。
- 所有失败 import 的 exporter-owned handle 都已关闭。
- consumer GPU 工作已等待 acquire signal。
- 必要的 queue-family ownership 已释放回 external。
- completion 类型已通过当前 lease capability 协商。
- lease 已成功从 `ACTIVE` 到 `RELEASED` 再到 `CLOSED`。
- renderer 关闭前 `outstandingGpuFrameLeases == 0`。

普通 uncapped 提交循环应使用 `renderer.trySubmit(...)`。`FrameSubmissionDeferred` 保证该次没有推进 frame sequence 或 native submission；调用方保留同一 request 稍后重试，并让出 CPU。重试与统计读取 `deferralReason()`，`detail()` 只作诊断，禁止解析自然语言。官方 presenter 的默认 producer lead 是 2，允许 trace/present 重叠但不允许生产端无限堆积不可见工作。
