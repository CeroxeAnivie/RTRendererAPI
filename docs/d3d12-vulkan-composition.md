# RTXPT 场景与旧 Vulkan UI 的 GPU 组合

场景后端选择和图像组合是两件事。[后端选择](backend-routing.md)决定完整 3D 场景由谁渲染；这里的组合链允许 RTXPT 完成 3D 后，把已经完成色调映射的图像交给旧 Vulkan，再叠加普通／专家命令产生的 UI 纹理和 HUD，由 Vulkan 窗口最终呈现。普通表面、灯光、透射、吸收和遮挡仍由 RTXPT 在同一个场景中计算。

旧 Vulkan 的场景渲染入口继续保留。组合时不需要先提交旧 3D 帧，可以直接打开其 `VulkanFramePresenter`，让它负责 GPU 纹理合成和呈现。切回旧 3D 时，使用 `presentLatestFrame(uiLayers)` 保留相同 UI；没有 UI 时继续调用原来的 `presentLatestFrame()`／`presentAndRelease()`。旧线为 RGBA16F 时，先走原有 SDR 显示转换，再参与同一个 UI 合成管线。

## 普通模式与专家模式

依赖 `renderer-rtxpt` 和旧 Vulkan 模块，普通模式只需一个窗口配置：

```java
try (var renderer = RtxptComposedRenderer.open(VulkanFramePresenterConfig.builder()
        .initialExtent(1920, 1080).title("My game").build())) {
    renderer.apply(initialScene);
    renderer.overlays(uiLayers); // 可省略；引用旧命令入口实际完成的 GPU 纹理
    renderer.presenter().pollEvents();
    renderer.submit(frame);
}
```

默认 `RTXPT_FOR_MEDIA`：无介质的完整场景使用旧 3D，有介质的完整场景使用 RTXPT；最终都是同一个 Vulkan 窗口。`backendPolicy(RTXPT_ONLY)`／`backendPolicy(VULKAN_ONLY)` 可在下一帧切换完整 3D 后端，不重建可见窗口。含光学介质的场景不能强行切成旧 3D 并丢弃其光学语义。

专家入口 `open(policy, legacyConfiguration, rtxptConfiguration, window)` 分别配置两条路径。RTXPT 配置应基于 `pathTracedPresentation()`，关闭 `lowLatency` 和 FG；旧配置基于 `builder().cpuFrameReadbackEnabled(false)`，关闭旧侧 Streamline 功能。`legacyCommands()` 和 `legacyExtension(type)` 提供独立旧 GPU 工作入口；`overlays(layers)` 在两种 3D 后端下均保留。运行期功能控制器对应当前选中的 3D 后端，切换后重新获取；不要缓存另一后端的控制器继续调用。`RtxptAccess` 不暴露隐藏窗口的独立推进入口，避免绕过消费者造成共享槽未释放。

场景资源只沿用已有 RTXPT 薄适配器的保留表，不新增渲染算法。Vulkan 专属材质参数原样保留，选中旧 3D 时交给旧后端；选择 RTXPT 时仍明确验证其能力合同。场景分类缓存复用公共 `RendererSceneRouting`，普通帧不重新扫描整个场景；旧侧活动场景的纯变换更新保留增量路径。

这不是“所有能力在任意同一场景内自动取并集”。独立计算／绘制、GPU UI、叠层和 HUD 可以组合；RTXPT 内部的自定义 BSDF、任意命中着色、Vulkan 精确投影、光传输中的自定义采样不能靠最后一张颜色图补回来。当前导出的是上游 SDR 颜色，不包含可供任意深度后期使用的深度／法线／运动向量；不宣称任意后期已接通。组合窗口的 FG/MFG、Reflex 暂不支持，独占 RTXPT 的对应能力继续保留。

## 图像格式与颜色

`D3D12SharedFrame` 是专家互操作描述，不拥有句柄。固定接收专用 committed D3D12 `RGBA8_UNORM` 纹理：1 mip、1 layer、1 sample，已经由生产侧完成 SDR 色调映射。Vulkan 不再做第二次色调映射，也不把它当作线性 HDR。生产侧提供 `GetResourceAllocationInfo` 的专用分配大小，以及 `D3D12_RESOURCE`／`D3D12_FENCE` 两个 Win32 NT handle。

实际 D3D12 adapter LUID 必须与 Vulkan physical device LUID 相同。跨 GPU、格式或同步机制不符会明确报错，不经过 CPU 图片兜底。`resourceId` 是正的分配代次，不能拿同一个 ID 代指另一张纹理或另一个 fence；一次提交的 `frameSequence` 必须严格递增。

## GPU 所有权交接

每张 ring slot 纹理有自己独立的 shared fence，不能让不同 slot 共用会提前越过其他 slot 信号的计数器。

1. D3D12 写入，转为 `COMMON`，queue signal 正奇数 `readyValue = 2n + 1`。
2. Vulkan GPU wait ready；从 `EXTERNAL/GENERAL` 获取纹理所有权，复制或合成。
3. Vulkan 释放回 `GENERAL/EXTERNAL`，GPU signal 紧接的偶数 `completionValue = 2n + 2`。
4. D3D12 queue wait completion 后才能再次写入／销毁这个 slot。

正常帧不需要 CPU 等 GPU 完成。最小化或 swapchain 需要重建时，Vulkan 仍提交 wait ready 和 signal completion；不会因为跳过窗口显示让生产侧卡在 QueueWait。

内存导入使用 dedicated allocation，fence 导入使用 Vulkan timeline semaphore 和 `VkTimelineSemaphoreSubmitInfo`。这是 [Khronos 推荐的 D3D12 fence 互操作方式](https://docs.vulkan.org/refpages/latest/refpages/source/VkD3D12FenceSubmitInfoKHR.html)。Vulkan 只释放自己创建的 image、memory 和 semaphore，**不关闭生产侧借出的 NT handle**。

`D3D12_RESOURCE` 导入的 `allocationSize` 由驱动从 OS 查询，[Vulkan 规范明确忽略此输入值](https://docs.vulkan.org/refpages/latest/refpages/source/VkMemoryAllocateInfo.html)。不能用 Vulkan 普通 image 的 memory requirement 大小去否决 D3D12 专用纹理；两边的分配元数据可能不同。共享颜色纹理保留 NVRHI 默认 shader-resource 标志，不设置仅允许与深度模板资源配合的 [`DENY_SHADER_RESOURCE`](https://learn.microsoft.com/en-us/windows/win32/api/d3d12/ne-d3d12-d3d12_resource_flags)。这不会创建额外 SRV 或逐帧 shader 工作。

## 旧 Vulkan UI 纹理

```java
// sharedFrame 由 D3D12 生产侧提供；uiLayers 引用本 Vulkan renderer 的已完成纹理 mutation。
presenter.presentD3D12SharedFrame(sharedFrame, uiLayers);

// 无 UI：直接 GPU copy，不初始化额外合成管线。
presenter.presentD3D12SharedFrame(sharedFrame);

// 热切回旧 Vulkan 3D，同一层列表继续参与最终显示，不会丢失 UI。
presenter.presentLatestFrame(uiLayers);
```

外部场景是第 0 层，最多再叠加 7 个 `FrameCompositionPlan.Layer`。它们复用原来的 `ResourceMutationKey` 和 `REPLACE`、`ALPHA_OVER`、`ADDITIVE` 语义，不新增一套 UI shader。

UI 必须是本 Vulkan renderer 已完成的、精确 mutation 对应的全尺寸单层单采样 `RGBA8_UNORM` storage-readable 纹理。`ALPHA_OVER` 使用预乘 alpha；纹理内容在最终 SDR 显示数值域中参与合成。尺寸按共享场景纹理匹配；窗口缩放由最后的 presenter copy／blit 处理。资源在取得后被 pin，GPU 完成读取后才允许释放或改写；layout 更新沿用旧 generic registry 的账本。

有 UI 时，外部纹理先 GPU copy 到私有 storage 图，再经过原 `VulkanFrameCompositionPipeline`；不把借用的外部 image 硬塞进拥有 VMA 生命周期的资源表。没有 UI 时保留直接复制快路径。`setOverlayText()` 的文字 HUD 在上述组合后绘制。

## 关闭、重建和切换

在生产侧 resize、销毁资源、关闭 shared handles 或切换 3D 后端前，先调用：

```java
presenter.retireD3D12SharedFrames();
```

此操作等待已提交消费完成，释放缓存导入，保留原 Vulkan 窗口。随后才让生产侧关闭对应的 D3D12 资源／句柄。每帧不能这样调用；它只用于生命周期边界。关闭整个 presenter 也会退休导入。

组合模式第一版明确不支持 FG，RTXPT 独占窗口路径仍保留其 FG／MFG 能力。D3D12 原窗口生成的帧不等于 Vulkan 最终窗口交付的帧，不能把前者计作组合输出。旧 Vulkan 用于组合时应从 `RendererConfig.builder()` 创建，关闭其 reconstruction、generation 和 low-latency 请求，避免与 RTXPT 在同进程重复初始化 Streamline。`PREFERRED` 也会尝试初始化，不应当作关闭；不能直接套用 `recommended()`／`presentation()`。

## 显式最终帧截图

```java
presenter.captureNextPresentedFrame(outputPng);
presenter.presentD3D12SharedFrame(sharedFrame, uiLayers);
```

截图只在显式请求时创建一次性 readback。它在 UI、HUD 完成后、swapchain 图像交给 WSI 前复制，保留 RGBA／BGRA 通道转换；该可见帧调用返回前完成 PNG。最小化时请求保持待处理。普通帧没有 CPU 图片分配或 readback。

诊断截图需要 renderer-bound presenter 和 surface 的 `TRANSFER_SRC` 支持。不支持时明确拒绝。它同样可验收旧 Vulkan 的最终呈现，但不回读已交给 WSI 的上一帧图像。
