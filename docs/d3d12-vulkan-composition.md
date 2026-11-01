# RTXPT 与旧 Vulkan 的 GPU 组合

推荐链：**RTXPT 完整 3D → 旧 Vulkan GPU 叠层 → RTXPT 最终呈现**。RTXPT 是唯一 Streamline owner，保留其原生 SR／RR、FG／MFG、Reflex、SHaRC、SER、采样和降噪规则；旧 Vulkan 执行独立图形／计算工作，不另启一套 Streamline。普通帧没有 CPU 像素读回。

## 普通与专家入口

```java
try (var renderer = RtxptComposedRenderer.open()) {
    renderer.apply(initialScene);
    renderer.overlays(uiLayers); // 可省略；精确引用旧 Vulkan 已完成的 GPU 纹理
    renderer.submit(firstFrame); // 窗口尺寸来自 frame
    var host = renderer.extension(RtxptAccess.class).orElseThrow();
    while (host.renderFrame()) { /* 常驻场景，回交叠层与最终出帧仍完整执行 */ }
}
```

普通配置沿用 `pathTracedPresentation()`，FG 默认关闭。专家显式请求高级能力：

```java
var legacy = RendererConfig.builder().cpuFrameReadbackEnabled(false).build();
var rtxpt = RendererConfig.pathTracedPresentation().copyBuilder()
    .frameGeneration(FrameGenerationOptions.recommended()).build();
try (var renderer = RtxptComposedRenderer.openRtxptPresentation(legacy, rtxpt, "My game")) {
    // legacyCommands() / legacyExtension(type)：旧路线独立工作。
    // overlays(layers)：旧 GPU 纹理；RTXPT 唯一可见窗口通过 RtxptAccess 管理。
}
```

最终 FPS／FG 交付计数来自 RTXPT 的真实 DXGI／SDK；隐藏的 Vulkan compositor 不 acquire 或 present WSI，也不计作已显示帧。它的 `PerformanceSnapshot.presentSamples` 必须为零。关闭叠层会直接解除回调，不分配共享帧、不复制颜色、不运行合成 shader。

原生最终呈现入口延迟创建旧 Vulkan 设备：只渲染 RTXPT 和上游 HUD 时，不创建旧设备、分配器或隐藏窗口。首次调用 `legacyCommands()`、`legacyExtension(...)`、旧资源／命令接口、旧语义能力查询或启用叠层时才创建，失败时清理本次半成品并保留 RTXPT 会话。已创建的旧资源继续按原合同存活到关闭；停用叠层仅回收交接和合成临时资源，不破坏应用持有的纹理。

## 3D 后端热切换的专家选项

`openVulkanPresentation(policy, legacyConfig, rtxptConfig, window)` 保留另一条已验证的链：完整 RTXPT 或完整旧 Vulkan 3D → Vulkan 叠层／HUD → 同一 Vulkan 窗口。`backendPolicy(...)` 在共同支持的场景中切换完整 3D，保留场景、相机和叠层；旧侧 `trySubmit` 保留有界背压合同。介质场景不能切旧路线并丢掉光学语义。

这条 Vulkan 最终呈现链必须关闭 RTXPT FG/MFG/Reflex，也关闭旧侧 Streamline 请求。官方 [Streamline ProgrammingGuide](https://github.com/NVIDIA-RTX/Streamline/blob/v2.14.16/docs/ProgrammingGuide.md) 明确 `Applications can create multiple devices but SL only works with one.`，`Preferences.renderAPI` 只有一个 API 值。不能让 D3D12 RR 与 Vulkan FG 各自绑定不同设备，再声称这受官方支持。

推荐 RTXPT 最终呈现入口始终保留完整 RTXPT 3D 及其引导，不允许 `backendPolicy` 假切到旧 3D。需要同窗切换完整 3D 时明确选择 Vulkan 最终呈现入口；需要完整 RTXPT 高级呈现时选择 RTXPT 最终呈现入口。

## GPU 交接与资源生命周期

三槽 ring 使用 NVRHI 共享 `RGBA8_UNORM` 专用颜色纹理，每槽独立 D3D12 fence。`D3D12SharedFrame` 借用 Win32 `D3D12_RESOURCE`／`D3D12_FENCE` NT handles，不接管生产侧句柄。实际 D3D12 adapter LUID 必须与 Vulkan physical device 相同；禁止 CPU 图像兜底。

1. RTXPT afterRender 在既有 RenderEnd／PresentStart 之前发布颜色，转 `COMMON`，GPU signal 奇数 ready。
2. Vulkan GPU wait ready，从 `EXTERNAL/GENERAL` 获取所有权，复用已有 `VulkanFrameCompositionPipeline`，将结果 GPU copy 回同一共享图，释放 `GENERAL/EXTERNAL`，signal 紧随的偶数 completion。
3. D3D12 queue wait completion，GPU copy 到上游 backbuffer，仍由原 DXGI／FG／MFG／Reflex 完成呈现。

独立 Vulkan 最终窗口入口不回写，而是复制／合成到其 swapchain。最小化／重建跳过显示时仍归还共享槽。跨 API 等待用 GPU timeline；有界队列和设备本身的呈现背压沿用现有实现。

每次 resize、重建、关闭或完整 3D 切换，先 `retireD3D12SharedFrames()` 等已有消费者完成，再退休生产槽／句柄。消费者退休失败则保留生产资源供重试，不能直接销毁。拒收的新 descriptor 由生产侧在消费者成功退休后按队列顺序回收，避免孤立 ready 值卡住后续工作。

原生最终呈现把叠层改为空列表时，同样先排空消费者，然后立即释放三槽共享图和 Vulkan 合成临时图／管线；不把已停用的显存留到 renderer 关闭。重新启用叠层按需重建。此等待只发生在显式停用或退休边界，不放进正常帧循环。

内存采用 dedicated import，fence 采用 [Khronos 推荐的 timeline semaphore 方式](https://docs.vulkan.org/refpages/latest/refpages/source/VkD3D12FenceSubmitInfoKHR.html)。[规范明确忽略 D3D12_RESOURCE 的 allocationSize 输入](https://docs.vulkan.org/refpages/latest/refpages/source/VkMemoryAllocateInfo.html)，由驱动查询 OS 分配；不能拿普通 Vulkan image requirement 大小否决 D3D12 纹理。共享颜色纹理保留 NVRHI 的默认 shader-resource 标志，不设置仅供深度模板使用的 [DENY_SHADER_RESOURCE](https://learn.microsoft.com/en-us/windows/win32/api/d3d12/ne-d3d12-d3d12_resource_flags)；不因此创建额外 SRV。

## 颜色、叠层和高级能力边界

颜色已经由上游完成 SDR 色调映射；跨 API 按字节交接，不二次色调映射。叠层最多 7 层，必须是本 Vulkan renderer 的已完成、全尺寸、单层单采样 `RGBA8_UNORM` storage-readable 精确 mutation；沿用 `REPLACE`／`ALPHA_OVER`／`ADDITIVE`，alpha-over 使用预乘 alpha。资源 pin 保留到 GPU 消费完成，layout 记账沿用原 generic registry。无叠层不初始化合成管线。

RTXPT 原 Depth／MV／PreUIColor（HUDless）保留，不通过 Vulkan 重建，也不混用 `slSetTag` 与 `slSetTagForFrame`。普通 FG/MFG 沿用上游 HUDless 路径；此桥接未额外启用需要 UIAlpha／UIColorAndAlpha 标签的 UI recomposition 开关，不能把它报成 ACTIVE。

组合覆盖独立 GPU 工作、已完成纹理的叠层和 HUD，**不是任意着色语义的并集**。RTXPT 内部的自定义 BSDF／命中 shader、精确 Vulkan 投影或光传输算法不能靠最后一张颜色图补回来；任意深度／法线／运动后期也没有被这条颜色桥自动接通。Vulkan 专属场景属性会原样保留，只有选择旧完整 3D 时才交其执行；RTXPT 选择仍严格验证原生合同。

## 验证与诊断

`RtxptAccess.capture(path)` 在回交模式读取最终共享颜色，包含上游 HUD 和旧 GPU 叠层；`captureRtxptSource(path)` 显式读取叠层前 RTXPT 色调映射源。Vulkan 最终窗口用 `captureNextPresentedFrame(path)`，只对指定帧做一次 readback。截图、预热和初始化均不进入稳态性能计时。

可执行验证：`:demos:hex-ball:rtxptCompositionSelfTest`；`top.ceroxe.rt.rtxpt.compositionTestMode` 可选 `acceptance`（Vulkan 最终窗口、颜色与热切换）、`rtxpt-final`（回交叠层＋实际 2x/3x/4x FG）、`native`、`return-no-overlay`、`return-overlay`（相同 FG-off 场景稳态吞吐）。2026-11-01 最新要求：性能测试前 GPU 0 基础占用必须为 **0%**；不为零就暂停，等待用户通知空闲后再测，不比较受干扰的绝对值。内存诊断与正确性运行单独标记，不用其耗时认定吞吐收益。

`rtxpt-hud` 单独检查 FG-off 的最终 HUD 像素、切换器和叠层释放／重启，不测吞吐，也不宣称通过 FG/MFG 验收。高级呈现验收同时记录测试窗口是否拥有前台焦点；后台窗口的 1x 交付不能被开关状态掩盖，前台被其他应用占用时保留失败证据并等待可验收窗口。

内存检查入口为 `:demos:hex-ball:rtxptMemorySelfTest`，连续三轮覆盖六个场景、叠层开关、1080p/540p 尺寸变化和关闭，输出带时间戳的 `phases.csv`，记录进程私有提交、Java 堆、非堆、直接缓冲区和实际帧数。可在单独诊断窗口开启 JFR，`top.ceroxe.rt.NativeMemory` 将阶段与调用栈关联。

直接运行 `demo.RtxptMemorySelfTest output 12 natural 2` 可做 12 轮、72 次场景检查，循环内只使用自然 GC，并在关闭后额外重开两次会话。默认三轮的显式 GC 只用于比较保留量，不能代替这个长窗口检查；最后关闭后的显式 GC 作为 Java 资源退休的检查点。

`python tools/Watch-NativeMemory.py <诊断目录> --lightweight` 读取该目录的 `runtime.json`（`executable`、Java `arguments`），以 1 秒频率记录目标进程私有提交／工作集及 Windows GPU Process Memory 专用／共享提交。后两者是本进程 GPU 记账，不是全局显存或唯一物理驻留量，不能把共享纹理的双 API 记账直接当两份物理内存。完整模式继续支持虚拟分配 census、NMT/JFR 及显式启用的原生栈。构建与诊断进程树上限为 10 GB；JFR、显式 GC 和内存 census 不进入最终 FPS 验收。
