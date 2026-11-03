# 普通用法与专家控制

普通用法只提交场景事实、选择输出、处理帧和关闭资源。专家用法在同一套对象上显式控制质量预算、功能转换、程序、命令和 GPU 交接。没有需要猜测的隐藏模式开关，也不需要把普通场景改写成另一套数据模型。

## 先按输出选入口

|需要|普通入口|需要承担的工作|
|---|---|---|
|CPU 可读图像|`Renderer.open()`|`apply` 场景、`submit` 帧、`awaitFrame` 或 `pollFrame`|
|Vulkan GPU 窗口|`Renderer.open(RendererConfig.presentation())`|官方 `VulkanFramePresenter` 处理窗口和显示|
|RTXPT 完整场景窗口|`Renderer.open("rtxpt", RendererConfig.pathTracedPresentation())`|提交场景，通过 `RtxptAccess` 驱动窗口|
|独立有限光照|`RtxptRenderers.openNativeLighting(plan)`|提交同一套常驻场景，按 `NativeLightingControl` 获取原子光照包|
|宿主主表面传输|`RtxptRenderers.openHostSurface(plan)`|宿主提供主表面输入；分拆传输默认优选真实 RR|
|已照亮底图增补|`RtxptRenderers.openHostSurface(plan, augmentation)`|宿主提供明确的 B/S/V/F 和实际底图；确定性 gain/GI 不请求全图 RR|

最后三项会为所选算法准备兼容配置，调用方不用手工关闭不相容的缓存、重建、bloom 或插帧。它们的跨 API GPU 输入／输出仍是显式宿主集成：lease、格式、LUID、fence 和退休责任不会因使用快捷工厂而消失。只需要窗口或 CPU 图像时使用前三项，不必接触这些资源协议。

## 最小普通调用

```java
try (var renderer = Renderer.open()) {
    long revision = renderer.apply(SceneTransaction.empty(0)).acceptedSceneRevision();
    var camera = CameraState.lookAt(0, 0, 3, 0, 0, 0).aspectRatio(16.0 / 9.0).build();
    renderer.submit(RenderFrameRequest.builder(0, 1280, 720, camera)
        .minimumSceneRevision(revision).build());
    var frame = renderer.awaitFrame(java.time.Duration.ofSeconds(5)).orElseThrow();
}
```

场景 revision 和帧 sequence 严格递增。`awaitFrame` 有界等待；持续生产使用 `trySubmit` 的类型化背压结果重试。推荐配置不会自动请求 MFG；CPU 路径不启动呈现插帧，Vulkan GPU 窗口优选普通 2× FG。Vulkan 普通默认不启用 NRD，RTXPT 窗口预设优选 RR。`LOW/MEDIUM/HIGH` 是工作量起点，默认 `MEDIUM`，并非按显卡型号保证帧率。

## 从普通配置进入专家控制

保留预设中的其他选择时，从完整配置派生：

```java
var plan = NativeLightingPlan.defaults(640, 360);
var policy = RtxptRenderers.nativeLightingConfiguration().copyBuilder()
    .gpuTimingsEnabled(false)
    .build();
try (var renderer = RtxptRenderers.openNativeLighting(policy, plan)) {
    // 明确配置入口使用 policy；不会为了适配算法偷偷改写请求。
}
```

分拆传输的起点是 `RtxptRenderers.hostSurfaceConfiguration(plan.composition())`。完整 Vulkan 或 RTXPT 窗口从各自 `RendererConfig` 预设派生。需要从零表达策略时使用 `RendererConfig.builder()`：可选厂商功能默认禁用；应用分别决定重建、降噪、FG、低延迟、SER、路径与资源预算。

专家控制包括：

- `RendererFeatureController`：先规划 generation 绑定的变更，再应用并检查类型化结果与实际执行状态。
- Vulkan `SceneShaderProgram` 及资源／命令事务：显式 shader、绑定、pipeline、pass、draw、barrier、AS、SBT 与 trace。
- Vulkan external-memory 或 RTXPT 输入／输出 lease：显式协商、GPU 同步与资源退休。
- 原生有限光照：`NativeLightingStyle`、实例角色与 baseline publication、应用数据局部更新，以及独立原生模式的离线 DXIL 扩展。

专家入口提供所选后端实际支持的控制。Vulkan 程序不会因选择 RTXPT 而自动转译；底图增补不执行独立原生扩展；没有合法 RR guides 的有限信号不会声称执行 RR。不支持的组合必须明确拒绝，`PREFERRED` 功能仍按公开能力协议协商。始终区分请求、协商结果和实际执行结果。

更详细的调用见 [Java 指南](Java.md)、[API 参考](Java-API-Reference.md)、[主表面输入](rtxpt-710-host-surface-input.md)、[底图增补](rtxpt-710-host-augmentation.md)、[离线扩展](rtxpt-710-native-lighting-extension.md)与 [Vulkan 互操作](Vulkan-Interop.md)。模式工厂和拒绝边界的无 GPU 检查为 `RtxptModeUsabilitySelfTest`；它不代替最终 GPU 与发布包验收。
