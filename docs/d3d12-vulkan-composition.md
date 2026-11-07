# RTXPT 与Vulkan 的 GPU 组合

> 历史记录：本文保留 7.x 路线与验收记录，不是 8.0.0 当前用法。当前原生优先的两模块接入见[文档首页](Home.md)和[项目 README](../README.md)。

普通完整场景优先使用单后端预设；本页是需要 Vulkan 独立命令／叠层时的专家组合入口。它不同于 7.1 复用宿主主表面的传输／底图增补，入口区别见[普通用法与专家控制](ordinary-and-expert.md)。

> **默认推荐 RTXPT 单后端**完成完整 3D 与最终呈现，显式使用 `Renderer.open("rtxpt", RendererConfig.pathTracedPresentation())`。
> **RTXPT＋Vulkan 混合模式不推荐作为默认方案**：它增加整帧复制、跨 API 同步和显存成本，仅供确实需要 Vulkan 独立命令／颜色叠层的专家使用。当前以功能正确、稳定出帧为维护目标，不做混合模式性能优化。

需要组合时的执行链：**RTXPT 完整 3D → Vulkan GPU 叠层 → RTXPT 最终呈现**。RTXPT 是唯一 Streamline owner，保留其原生 SR／RR、FG／MFG、Reflex、SHaRC、SER、采样和降噪规则；Vulkan 执行独立图形／计算工作，不另启一套 Streamline。普通帧没有 CPU 像素读回。

命名按执行职责区分：`vulkanCommands()`、`vulkanExtension(...)` 和 `WorkSnapshot.vulkanFrames()` 指 Vulkan 的独立工作与帧计数；RTXPT 的场景光照输入使用 `SceneLighting`，预 SR 历史使用 `PreSrHistory`。场景解析光照通过公开的 `pathTracing().quality().lighting().withInput(SCENE_ANALYTIC)` 选择，须关闭 ReSTIR／SHaRC；精确光学界面通过 `withExactOpticalInterfaces(1..6)` 控制。`RTR_RTXPT_DIAGNOSTIC_PRE_SR_HISTORY=1` 仅是诊断候选，不是普通配置。原始测量日志保留当时记录；当前配置见[预算指南](performance-budgets.md)。

## 普通与专家入口

叠层纹理在消费方 GPU fence 完成前仍由组合器借用。复用同一纹理写入时，若 `submitCommands(...)` 返回 `BLOCKED / BOUNDED_BACKPRESSURE`，先调用 `awaitCompositionConsumers()` 等待已提交的消费者释放占用，再以同一个命令序号重试；等待不会销毁导入图像或管线缓存。不要用“已经轮换了几帧”推断借用结束，也不要绕过占用检查。真正非法的命令仍返回拒绝。该等待只用于实际背压，不能每帧调用 `trimIdleCompositionResources()` 代替。

```java
try (var renderer = RtxptComposedRenderer.open()) {
    renderer.apply(initialScene);
    renderer.overlays(uiLayers); // 可省略；精确引用Vulkan 已完成的 GPU 纹理
    renderer.submit(firstFrame); // 窗口尺寸来自 frame
    var host = renderer.extension(RtxptAccess.class).orElseThrow();
    while (host.renderFrame()) { /* 常驻场景，回交叠层与最终出帧仍完整执行 */ }
}
```

普通配置沿用 `pathTracedPresentation()`，FG 默认关闭。专家显式请求高级能力：

```java
var vulkan = RendererConfig.builder().cpuFrameReadbackEnabled(false).build();
var rtxpt = RendererConfig.pathTracedPresentation().copyBuilder()
    .frameGeneration(FrameGenerationOptions.recommended()).build();
try (var renderer = RtxptComposedRenderer.openRtxptPresentation(vulkan, rtxpt, "My game")) {
    // vulkanCommands() / vulkanExtension(type)：Vulkan 独立工作。
    // overlays(layers)：Vulkan GPU 纹理；RTXPT 唯一可见窗口通过 RtxptAccess 管理。
}
```

最终 FPS／FG 交付计数来自 RTXPT 的真实 DXGI／SDK；隐藏的 Vulkan compositor 不 acquire 或 present WSI，也不计作已显示帧。它的 `PerformanceSnapshot.presentSamples` 必须为零。关闭叠层会直接解除回调，不分配共享帧、不复制颜色、不运行合成 shader。

原生最终呈现入口延迟创建Vulkan 设备：只渲染 RTXPT 和上游 HUD 时，不创建Vulkan 设备、分配器或隐藏窗口。首次调用 `vulkanCommands()`、`vulkanExtension(...)`、Vulkan 资源／命令接口、Vulkan 语义能力查询或启用叠层时才创建，失败时清理本次半成品并保留 RTXPT 会话。已创建的Vulkan 资源继续按原合同存活到关闭；停用叠层保留有界交接缓存，避免 UI 开关时反复等待和重建。

## 3D 后端热切换的专家选项

`openVulkanPresentation(policy, vulkanConfig, rtxptConfig, window)` 保留另一条已验证的链：完整 RTXPT 或完整Vulkan 3D → Vulkan 叠层／HUD → 同一 Vulkan 窗口。`backendPolicy(...)` 在共同支持的场景中切换完整 3D，保留场景、相机和叠层；Vulkan 侧 `trySubmit` 保留有界背压合同。介质场景不能切Vulkan 路线并丢掉光学语义。

这条 Vulkan 最终呈现链必须关闭 RTXPT FG/MFG/Reflex，也关闭Vulkan 侧 Streamline 请求。官方 [Streamline ProgrammingGuide](https://github.com/NVIDIA-RTX/Streamline/blob/v2.14.16/docs/ProgrammingGuide.md) 明确 `Applications can create multiple devices but SL only works with one.`，`Preferences.renderAPI` 只有一个 API 值。不能让 D3D12 RR 与 Vulkan FG 各自绑定不同设备，再声称这受官方支持。

RTXPT 最终呈现入口始终保留完整 RTXPT 3D 及其引导，不允许 `backendPolicy` 假切到Vulkan 3D。需要同窗切换完整 3D 时明确选择 Vulkan 最终呈现入口；需要完整 RTXPT 高级呈现时选择 RTXPT 最终呈现入口。

## GPU 交接与资源生命周期

三槽 ring 使用 NVRHI 共享 `RGBA8_UNORM` 专用颜色纹理，每槽独立 D3D12 fence。`D3D12SharedFrame` 借用 Win32 `D3D12_RESOURCE`／`D3D12_FENCE` NT handles，不接管生产侧句柄。实际 D3D12 adapter LUID 必须与 Vulkan physical device 相同；禁止 CPU 图像兜底。

1. RTXPT afterRender 在既有 RenderEnd／PresentStart 之前发布颜色，转 `COMMON`，GPU signal 奇数 ready。
2. Vulkan GPU wait ready，从 `EXTERNAL/GENERAL` 获取所有权，复用已有 `VulkanFrameCompositionPipeline`，将结果 GPU copy 回同一共享图，释放 `GENERAL/EXTERNAL`，signal 紧随的偶数 completion。
3. D3D12 queue wait completion，GPU copy 到上游 backbuffer，仍由原 DXGI／FG／MFG／Reflex 完成呈现。

独立 Vulkan 最终窗口入口不回写，而是复制／合成到其 swapchain。最小化／重建跳过显示时仍归还共享槽。跨 API 等待用 GPU timeline；有界队列和设备本身的呈现背压沿用现有实现。

原生窗口在 native frame 内发生尺寸变化时，本帧仍正常消费并归还共享槽，仅暂缓合成与提交尺寸不符的旧叠层；下一帧按新尺寸重建叠层。

每次 resize、重建、关闭或完整 3D 切换，先 `retireD3D12SharedFrames()` 等已有消费者完成，再退休生产槽／句柄。消费者退休失败则保留生产资源供重试，不能直接销毁。拒收的新 descriptor 由生产侧在消费者成功退休后按队列顺序回收，避免孤立 ready 值卡住后续工作。

原生最终呈现把叠层改为空列表时只解除回调，不额外等待 GPU，不释放缓存；重新启用复用有界三槽资源。在菜单／暂停等不要求连续出帧的空闲维护窗口，可显式调用 `trimIdleCompositionResources()`，排空消费者并释放三槽共享图和 Vulkan 合成临时图／管线。该入口只接受原生最终呈现且叠层为空的状态，不破坏应用纹理；之后启用叠层才按需重建。不能把此重型回收塞进逐帧逻辑。

内存采用 dedicated import，fence 采用 [Khronos 推荐的 timeline semaphore 方式](https://docs.vulkan.org/refpages/latest/refpages/source/VkD3D12FenceSubmitInfoKHR.html)。[规范明确忽略 D3D12_RESOURCE 的 allocationSize 输入](https://docs.vulkan.org/refpages/latest/refpages/source/VkMemoryAllocateInfo.html)，由驱动查询 OS 分配；不能拿普通 Vulkan image requirement 大小否决 D3D12 纹理。共享颜色纹理保留 NVRHI 的默认 shader-resource 标志，不设置仅供深度模板使用的 [DENY_SHADER_RESOURCE](https://learn.microsoft.com/en-us/windows/win32/api/d3d12/ne-d3d12-d3d12_resource_flags)；不因此创建额外 SRV。

## 颜色、叠层和高级能力边界

颜色已经由上游完成 SDR 色调映射；跨 API 按字节交接，不二次色调映射。叠层最多 7 层，必须是本 Vulkan renderer 的已完成、全尺寸、单层单采样 `RGBA8_UNORM` storage-readable 精确 mutation；沿用 `REPLACE`／`ALPHA_OVER`／`ADDITIVE`，alpha-over 使用预乘 alpha。资源 pin 保留到 GPU 消费完成，layout 记账沿用原 generic registry。无叠层不初始化合成管线。

RTXPT 原 Depth／MV／PreUIColor（HUDless）保留，不通过 Vulkan 重建，也不混用 `slSetTag` 与 `slSetTagForFrame`。普通 FG/MFG 沿用上游 HUDless 路径；此桥接未额外启用需要 UIAlpha／UIColorAndAlpha 标签的 UI recomposition 开关，不能把它报成 ACTIVE。

组合覆盖独立 GPU 工作、已完成纹理的叠层和 HUD，**不是任意着色语义的并集**。RTXPT 内部的自定义 BSDF／命中 shader、精确 Vulkan 投影或光传输算法不能靠最后一张颜色图补回来；任意深度／法线／运动后期也没有被这条颜色桥自动接通。Vulkan 专属场景属性会原样保留，只有选择完整 Vulkan 3D 时才交其执行；RTXPT 选择仍严格验证原生合同。

## 7.0.4 宿主背景

需要保留宿主已绘制的天空等内容时，先查询 `SceneLightingControl.supportsTransparentBackground()`，启用 `transparentBackground()`，再用 `backgroundLayer(completedLayer)` 把同一 Vulkan 命令通道的已完成 GPU 背景放到 RTXPT 下方。背景使用 `REPLACE`，此时最多六层上方 overlay。底层 presenter 也提供带 background 参数的 compose/present 重载。没有宿主深度猜遮罩、重复宿主几何或逐帧 CPU 读回。

透明帧的 `D3D12SharedFrame.alphaMode()` 为 `PREMULTIPLIED`。新背景路线的所有输入先解码 sRGB，在线性 display 空间预乘合成，再编码输出。调用方上传的 UI 也必须遵循这一颜色合同。旧 opaque 叠层保留原字节域行为。纹理须是单层、单 mip、单采样、全尺寸 `RGBA8_UNORM`，拥有 storage-read usage，并以精确已完成的输出 mutation 引用；单纯上传并不等于已发布的渲染输出。

透明模式要求关闭 ReSTIR、SHaRC、FG/MFG 和 bloom，且原生窗口本身不会 OS 透明。玻璃首表面仍覆盖像素，物理反射／折射使用 API 环境，不会自动折射宿主二维天空。RR 已测得部分覆盖 AA 边缘，本机 SR／SR＋NRD 仍为二值 coverage。详见 [7.0.4](release-transparent-background-composition.md) 与[定向验收](quality/rtxpt-transparent.md)。

## 验证与诊断

`RtxptAccess.capture(path)` 在回交模式读取最终共享颜色，包含上游 HUD 和Vulkan GPU 叠层；`captureRtxptSource(path)` 显式读取叠层前 RTXPT 色调映射源。Vulkan 最终窗口用 `captureNextPresentedFrame(path)`，只对指定帧做一次 readback。截图、预热和初始化均不进入稳态性能计时。

可执行验证：`:demos:hex-ball:rtxptCompositionSelfTest`；`top.ceroxe.rt.rtxpt.compositionTestMode` 可选 `acceptance`（Vulkan 最终窗口、颜色与热切换）、`rtxpt-final`（回交叠层＋实际 2x/3x/4x FG）、`native`、`return-no-overlay`、`return-overlay`（历史 FG-off 场景稳态吞吐）。2026-11-02 最新要求：性能测试前 GPU 0 基础占用 **低于 10%** 就视为空闲，可比较绝对性能；达到 10% 则暂停绝对性能测试。之后的渲染运行开启 FG，旧证据仍保留当次实际配置。内存诊断与正确性运行单独标记，不用其耗时认定吞吐收益。

`rtxpt-hud` 单独检查 FG-off 的最终 HUD 像素、切换器和叠层释放／重启，不测吞吐，也不宣称通过 FG/MFG 验收。高级呈现验收同时记录测试窗口是否拥有前台焦点；后台窗口的 1x 交付不能被开关状态掩盖，前台被其他应用占用时保留失败证据并等待可验收窗口。

内存检查入口为 `:demos:hex-ball:rtxptMemorySelfTest`，连续三轮覆盖六个场景、叠层开关、1080p/540p 尺寸变化和关闭，输出带时间戳的 `phases.csv`，记录进程私有提交、Java 堆、非堆、直接缓冲区和实际帧数。可在单独诊断窗口开启 JFR，`top.ceroxe.rt.NativeMemory` 将阶段与调用栈关联。

直接运行 `demo.RtxptMemorySelfTest output 12 natural 2` 可做 12 轮、72 次场景检查，循环内只使用自然 GC，并在关闭后额外重开两次会话。默认三轮的显式 GC 只用于比较保留量，不能代替这个长窗口检查；最后关闭后的显式 GC 作为 Java 资源退休的检查点。

一次性 `Watch-NativeMemory.py` 及其原生栈辅助脚本已于 2026-11-02 清理；当前内存验证使用上面的 Gradle self-test，可配合 JDK 自带的 JFR/NMT。历史报告中的 Windows GPU Process Memory 专用／共享提交是本进程 GPU 记账，不是全局显存或唯一物理驻留量，不能把共享纹理的双 API 记账直接当两份物理内存。构建与诊断进程树上限可由调用方通过[构建工具](../tools/README.md)调整；JFR、显式 GC 和内存 census 不进入最终 FPS 验收。
