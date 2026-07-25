# Vulkan 专家互操作

隔离 Maven consumer 中的 [`PublishedVulkanInteropConsumer`](../gradle/published-consumer-smoke/src/main/java/consumer/PublishedVulkanInteropConsumer.java)
是随发布门禁编译的完整控制流样例：它演示 extension discovery、有界等待、非空结果、lease 的
`ACTIVE -> RELEASED -> CLOSED` 顺序，以及只按 `RendererDeviceException.RecoveryAction` 决策的恢复路径。应用只需在
`NativeFrameConsumer` 内实现自身 Vulkan device/queue 的 import、ownership transfer 与 submission；不得把这些 native
职责隐藏回普通 `RayTracingRenderer` 路径。

`VulkanFrameInterop` 是显式 opt-in 的零拷贝外部图像接口。它只适合已经能正确实现 Vulkan external memory、external
semaphore、queue-family ownership transfer 和 Win32 handle 所有权的调用方。普通应用应使用
`RayTracingRenderer.pollLatestCpuFrame()` 或有界等待方法。

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
  flags/layout、mip/layer/sample、sharing mode、producer queue family、allocation size/offset 和 dedicated allocation。
- `memoryHandle()`：导出内存 Win32 handle 的有状态 owner，包含强类型 memory handle type、import disposition 和 handle
  state。
- `acquireSignal()`：可选 producer completion semaphore；为空表示 producer completion 已由 CPU 观察。
- `consumerCompletionCapabilities()`：该 concrete lease 实际接受的 completion 机制。
- `state()`：权威 `ACTIVE`、`RELEASED`、`CLOSED` 状态。

所有 Vulkan 数值都使用不同的强类型包装。不要把 memory handle type、semaphore handle type、format、layout、queue family 或普通
`int` 相互替代。

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
capability，使用 `RayTracingGpuDevice.capabilities()` 和实际 open 结果。

## 关闭检查表

- 所有成功 import 都已 `markImported()`。
- 所有失败 import 的 exporter-owned handle 都已关闭。
- consumer GPU 工作已等待 acquire signal。
- 必要的 queue-family ownership 已释放回 external。
- completion 类型已通过当前 lease capability 协商。
- lease 已成功从 `ACTIVE` 到 `RELEASED` 再到 `CLOSED`。
- renderer 关闭前 `outstandingGpuFrameLeases == 0`。
