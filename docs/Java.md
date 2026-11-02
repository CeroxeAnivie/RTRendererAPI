# Java 开发指南

RTRendererAPI 适合嵌入 Java 25 或更高版本的桌面或引擎进程。普通调用方只需要理解场景 revision、帧 sequence 和资源生命周期；Vulkan external memory、semaphore、queue-family ownership 等细节被隔离在显式专家扩展中。

公共模型是厂商中立、宿主无关的渲染契约：场景、相机、exact clip-space projection、资源所有权和能力状态均不包含游戏或引擎专用字段。当前实现包含 Windows NVIDIA Vulkan 和可选 RTXPT D3D12；它们各自支持的输入与输出不同。

普通模式提交场景、材质、实例和帧请求。`vulkan-rt` 保留普通表面、可编程场景和通用命令；`rtxpt` 可托管完整 PBR／透明介质场景及原生呈现。Vulkan 专家模式通过显式资源／命令事务或 `SceneShaderProgram` 阶段替换表达。两者共用 `Renderer` 和 `RendererConfig`，配置对象本身不切换提交语义；先根据下表选择后端，再学习对应的输出循环。

Maven 坐标是 `top.ceroxe.rt:renderer-api:7.0.1`，默认传递解析 Vulkan 后端、NVIDIA provider 和 native runtime。RTXPT 场景额外添加 `top.ceroxe.rt:renderer-rtxpt:7.0.1`；使用下面的 `RtxptAccess`／组合类时需要编译依赖。本轮从源码构建到本地 Maven 仓库使用，不上传 Central。消费方不需要安装 SDK 或手工复制 DLL。

## 学习顺序与后端选择

> **默认推荐 RTXPT 单后端**完成完整 3D 与最终呈现，显式使用 `Renderer.open("rtxpt", RendererConfig.pathTracedPresentation())`。
> **RTXPT＋Vulkan 混合模式不推荐作为默认方案**：它增加整帧复制、跨 API 同步和显存成本，仅供确实需要 Vulkan 独立命令／颜色叠层的专家使用。当前以功能正确、稳定出帧为维护目标，不做混合模式性能优化。

初次接触先照[三个 Demo 的命令](../demos/hex-ball/README.md)打开窗口，再学习[最小 CPU 帧](#最小调用)或[RTXPT 原生窗口](#rtxpt-透明介质与原生窗口)。之后学习资产、帧级图元、[配置与诊断](#常用配置)、[生命周期](#生命周期与资源所有权)，最后按需阅读通用命令与专家扩展。

| 需要完成的工作 | 入口 | 输出及限制 |
| --- | --- | --- |
| 普通 CPU 帧、HDR／整数数据 | `Renderer.open()` | Vulkan `FrameView`／`FrameSnapshot` |
| 普通 Vulkan GPU 窗口 | `Renderer.open(RendererConfig.presentation())` | `VulkanFramePresenter`，优选 2× FG |
| RTXPT 全场景或透明介质 | `Renderer.open("rtxpt", RendererConfig.pathTracedPresentation())` | `RtxptAccess` 原生 SDR 窗口，库默认 FG 关闭 |
| 按场景是否含介质选择后端 | `Renderer.openForScene(policy, scene, firstFrame)` | 为整场景选一个固定后端；首帧已提交 |
| RTXPT 3D＋Vulkan 独立命令／UI | `RtxptComposedRenderer.open()` | RTXPT 最终呈现；Vulkan 设备按需创建 |
| 自定义 shader、精确投影、通用资源／命令 | 显式选择 `vulkan-rt` | 先查询逐项可执行能力 |

`RTXPT_FOR_MEDIA` 是整场景路由：没有介质选 Vulkan，存在介质或透射工作选 RTXPT。不是把一帧中的介质单独交给另一后端。固定会话的场景类别变化需关闭重开；同窗切换完整 3D 后端需要[组合指南](d3d12-vulkan-composition.md)中的显式 Vulkan 最终呈现入口。两个后端的材质、光照与采样不保证逐像素相同。

## 最小调用

`Renderer.open()` 会枚举已安装 provider、执行能力探测并打开优先级最高的可用后端。兼容目标是 Windows 10 x64 或更高版本、NVIDIA RTX 20 系或更新 GPU、Vulkan 1.2+ 与 Java 25 或更高版本。当前行为、验收证据和已知限制见 [7.0.1 版本与验证范围](7.0.1.md)，历史 procedural/triangle RT 证据见 [4.1.0 验证记录](quality/verification-report.md#4-1-0-procedural-validation)。这些结果不代表全部兼容硬件或特定宿主集成都已验收。

```java
try (Renderer renderer = Renderer.open()) {
    long revision = renderer.apply(SceneTransaction.empty(0L))
            .acceptedSceneRevision();

    CameraState camera = CameraState.lookAt(
            0.0, 1.0, 5.0,
            0.0, 1.0, 0.0
    ).aspectRatio(16.0 / 9.0).build();

    renderer.submit(RenderFrameRequest.builder(0L, 1280, 720, camera)
            .minimumSceneRevision(revision)
            .build());

    FrameSnapshot frame = renderer.awaitFrame(Duration.ofSeconds(5))
            .orElseThrow(() -> new IllegalStateException("frame timed out"));
}
```

关键语义：

- `Renderer` 是 `AutoCloseable`，必须使用 `try-with-resources` 或等价的确定性关闭。
- 场景 revision 必须严格递增；帧的 `minimumSceneRevision` 防止旧场景被误当成新结果。
- 帧 sequence 必须严格递增，不可重用。
- `pollFrame()` 非阻塞；`awaitFrame(Duration)` 是有界等待，超时返回 `Optional.empty()`。
- `close()` 发起确定性关闭；存在外部 GPU lease 时用 `closeAsync()` 或 `awaitClosed(Duration)` 等待 native 资源真实释放。
- API 不使用 `null` 表示“暂时无帧”，也不会无限等待。
- 普通路径的 readback 使用 frame-slot 常驻异步 ring，不会逐帧创建 staging buffer 或执行 queue-idle；但把图像送到 CPU 本身仍有带宽成本。

## RTXPT 透明介质与原生窗口

下面是可放入 `Main.java` 的完整示例，依赖 `renderer-api` 和 `renderer-rtxpt`。它创建一个朝外绕序的封闭玻璃立方体，首帧提交后复用驻留场景出帧，直到关闭窗口。吸收系数以世界单位的倒数计；材质透射控制表面，`MediumBoundary` 控制封闭内部的吸收和折射率。

```java
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.RtxptAccess;

public final class Main {
    public static void main(String[] args) {
        var glass = MaterialAsset.builder(1L)
                .transmission(1.0F).roughness(0.05F)
                .indexOfRefraction(1.5F).build();
        var cube = MeshAsset.triangles(2L, new float[]{
                -.5F,-.5F,-.5F,  .5F,-.5F,-.5F,
                 .5F, .5F,-.5F, -.5F, .5F,-.5F,
                -.5F,-.5F, .5F,  .5F,-.5F, .5F,
                 .5F, .5F, .5F, -.5F, .5F, .5F
        }, new int[]{
                0,2,1, 0,3,2, 4,5,6, 4,6,7,
                0,4,7, 0,7,3, 1,2,6, 1,6,5,
                0,1,5, 0,5,4, 3,7,6, 3,6,2
        }, glass.id());
        var boundary = MediumBoundary.surface(
                new Medium(new Rgb(.02F, .12F, .20F), 1.5F));
        var scene = SceneTransaction.builder(0L).resetScene()
                .upsert(glass).upsert(cube)
                .upsert(SceneInstance.builder(3L, cube.id())
                        .mediumBoundary(boundary).build())
                .upsert(SceneLight.directional(4L, -.4F, -1F, -.2F)
                        .intensity(3F).build())
                .build();
        var camera = CameraState.lookAt(0, .5, 3, 0, 0, 0)
                .aspectRatio(16.0 / 9.0).build();
        var frame = RenderFrameRequest.builder(0L, 1920, 1080, camera)
                .minimumSceneRevision(0L).build();
        try (var renderer = Renderer.open("rtxpt",
                RendererConfig.pathTracedPresentation())) {
            renderer.apply(scene);
            renderer.submit(frame);
            var host = renderer.extension(RtxptAccess.class).orElseThrow();
            while (host.renderFrame()) {
                // 常驻场景不变；有动画时按递增 sequence 提交新帧。
            }
        }
    }
}
```

消费者以 Java 25 启动，并为类路径运行启用 `--enable-native-access=ALL-UNNAMED`。这里默认 1 SPP、优选重建／降噪和辐射缓存，FG 关闭。请求 2× FG 可派生配置：

```java
var config = RendererConfig.pathTracedPresentation().copyBuilder()
        .frameGeneration(FrameGenerationOptions.recommended()).build();
```

实际 SR／RR、SHaRC、ReSTIR、SER 和 FG 状态读取 `host.runtimeStatus()`、`RenderingFeatureCapabilities` 与 `renderer.diagnostics().frameGenerationEvidence()`。请求不等于执行；DLSS-RR 生效时不再叠加独立 NRD。SPP 和路径深度通过 `RendererConfig.Builder.pathTracing(PathTracingOptions)` 设置，不使用每帧的多采样抗锯齿入口。

三个配置工厂都支持 `RendererPerformanceTier.LOW / MEDIUM / HIGH`，无参默认 `MEDIUM`。例如弱 GPU 可以从下面配置开始，再按实际画面与预算调整：

```java
var base = RendererConfig.pathTracedPresentation(RendererPerformanceTier.LOW);
var path = base.pathTracing().withExactOpticalInterfaces(2);
var config = base.copyBuilder()
        .pathTracing(path.withQuality(path.quality().withDenoisingLayers(2, 5)))
        .resources(base.resources().withThreads(2, 1)).build();
```

精确界面预算只在 `SCENE_ANALYTIC` 光照输入下激活；默认介质 RR 路径使用上游原有传输。`host.pathQualityStatus()` 可回读真实缓存容量、线程池、帧环及 sampler，`runtimeStatus()` 区分已请求和已激活。修改路径／质量／资源预算需要重建 renderer，不把它伪装成逐帧热开关。原始资产限额不等于进程／显存总量。完整范围、六档 shader 发行与弱设备建议见[性能档位与资源预算](performance-budgets.md)。

画面过曝时可用 `host.setExposureCompensation(-1.5F)` 调整上游 tone mapper。该入口也可在首帧提交前调用，场景重载保留，实际值通过 `presentationStatus()` 回读。它只控制显示亮度；SR 本身不替代路径追踪降噪，小灯策略也不覆盖环境光、面积光和间接反弹的全部随机噪声。

`Medium` 只含 `absorption` 和 `[1,4]` 的 IOR；`MediumBoundary` 只含介质和 `[0,14]` 优先级，最多 4 层嵌套内部。需要闭合、朝外且绕序一致的网格。旧体积散射、密度网格、HG、体积发光、无限／相机初始介质和不可见边界已删除，详见[7.0.1 合同](7.0.1.md)。

RTXPT 当前不提供通用 CPU 帧、线性 HDR 导出、任意场景 shader、精确 Vulkan 投影、屏幕雾、旧光子图预算及逐帧纹理／lightmap 覆盖；无效输入在调用边界拒绝。`capture(Path)` 是显式单次诊断捕获，`awaitIdle()` 是显式 GPU 排空，两者都不应放进正常出帧循环。

## RTXPT 与 Vulkan 独立工作组合

组合属于不推荐作为默认方案的专家选项。确实需要 RTXPT 3D 加上 Vulkan 独立计算、图形或颜色叠层时，使用同样实现 `Renderer` 的组合入口：

```java
import top.ceroxe.rt.renderer.rtxpt.RtxptComposedRenderer;

try (var renderer = RtxptComposedRenderer.open()) {
    renderer.apply(scene);       // 复用上面的完整场景
    renderer.submit(frame);
    var host = renderer.extension(RtxptAccess.class).orElseThrow();
    while (host.renderFrame()) { }
}
```

Vulkan 独立命令通过借用的 `vulkanCommands()` 提交；已完成 GPU 纹理通过 `overlays(layers)` 回交给 RTXPT 最终呈现。无旧工作时不创建Vulkan 设备。两边不能分别追踪同一场景的光路，颜色叠层也不补全任意自定义 BSDF。需要叠层资源格式、同步、热切换或空闲内存回收时，继续阅读[组合教程](d3d12-vulkan-composition.md)。

## 生命周期与资源所有权

| 对象／操作 | 调用方责任 |
| --- | --- |
| `Renderer`／`RendererSceneSession` | 一次打开对应一次关闭；场景与帧提交保持单写者 |
| RTXPT／组合会话 | 全部操作在创建线程；一个进程同时只允许一个 RTXPT 会话 |
| `Renderer.openForScene(...)` | 已发布初始完整 reset 场景和首帧，后续 sequence 必须推进；`selection()` 查看实际选择 |
| `RtxptAccess`／`vulkanCommands()` | 借用接口，不单独关闭拥有者；宿主拥有原生窗口 |
| Vulkan presenter | 在创建线程泵事件、呈现和关闭；先关闭 presenter，再关闭 renderer |
| `FrameView` | 只在回调作用域内使用，不保留其字节视图；需要保存显式复制 |
| 外部 GPU lease／资源 generation | 按完成证据归还、退休，再等待拥有者关闭完成 |

窗口关闭后结束循环并让 try-with-resources 释放。不要跨线程再次驱动已关窗口，或把等待 GPU 空闲当成每帧步骤。初始化接受输入、GPU 完成、真正 Present 是三个独立事实；异常、能力回退与未归还资源通过 typed exception、`health()` 和 diagnostics 检查。

## 发布一个最小场景

场景聚合使用语义工厂与 Builder；7.0.1 的介质值使用直接 record。调用方提供的数组会在安全工厂中复制；高吞吐路径可以显式使用只读 direct buffer 工厂。

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
List<RendererGpuDevice> devices = Renderer.devices();
RendererGpuDevice selected = devices.stream()
        .filter(device -> device.type() == RendererGpuDevice.Type.DISCRETE)
        .max(Comparator.comparingLong(RendererGpuDevice::deviceLocalMemoryBytes))
        .orElseThrow(() -> new IllegalStateException("No supported RTX GPU"));

RendererConfig config = RendererConfig.builder()
        .gpuDevice(selected)
        .build();

try (Renderer renderer = Renderer.open(config)) {
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

普通 CPU-readable 路径直接调用 `Renderer.open()`：该 preset 以 `PREFERRED`
自动协商 SR、NRD、SER 与 RTXMU，不支持的实现保留各自的 renderer fallback。该路径没有
swapchain ownership，因此不会请求 FG/MFG 或 display pacing，避免产生无法 retire 的
presentation-time 输入。

只通过官方 GPU presenter 消费帧时，调用
`Renderer.open(RendererConfig.presentation())`；该 preset 关闭 CPU readback，并在普通自动质量
策略之上请求 FG 2x 与 Reflex/PCL。MFG 永远不由普通 preset 自动开启，只能通过专家 builder
显式选择 3x/4x。provider 始终检查 Vulkan feature/extension、SDK、驱动、真实 adapter 和
资源合同，不能由 GPU 型号字符串猜测能力；显式 `REQUIRED` 也不允许被 fallback 吞掉。
只使用专家 `VulkanFrameInterop`、但没有官方 managed presenter 的应用，应从
`RendererConfig.recommended().copyBuilder().cpuFrameReadbackEnabled(false)` 开始，
再交给 `Renderer.open(...)`；不能错误借用会请求 swapchain frame generation 的
GPU-presenter preset。

`RendererConfig.builder()` 是专家显式基线，所有 vendor 技术默认禁用；
`RendererConfig.recommended().copyBuilder()` 和
`RendererConfig.presentation().copyBuilder()` 用于专家派生相应 preset。
派生后的配置必须通过 `Renderer.open(...)` 打开。两种入口最终进入同一
capability negotiation 和执行状态机，不存在绕过生命周期约束的隐藏模式开关。下面是普通
CPU-readable preset 的等价专家配置：

```java
RendererConfig explicitProduction = RendererConfig.builder()
        .temporalRendering(TemporalRenderingOptions.balanced())
        .frameReconstruction(FrameReconstructionOptions.recommended())
        .denoising(DenoisingOptions.recommended())
        .frameGeneration(FrameGenerationOptions.disabled())
        .lowLatency(LowLatencyOptions.disabled())
        .rayTracingOptimizations(RayTracingOptimizationOptions.recommended())
        .build();
```

### 完整专家配置

本节配置用于 Vulkan 后端；RTXPT 的固定倍率、路径预算和能力边界见 [7.0.1](7.0.1.md)。专家模式只表达应用意图，不接管 Vulkan、Streamline、NRD 或 RTXMU 的资源 owner。下面的配置
显式覆盖 7.0.1 中保留的全部 NVIDIA 能力，同时保持生产环境可降级：DLSS SR 不可用时允许 NIS，
NRD 不可用时保留内建时域路径，FG/MFG 不可用时继续发布原生帧。SER 和 RTXMU 独立
协商，某一项不支持不会阻止其他项启用。

```java
RendererConfig expert = RendererConfig.builder()
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

以下片段都只需要 `top.ceroxe.rt:renderer-api:7.0.1`。把对应 options 传给
`RendererConfig.builder()` 即可；没有任何片段要求额外模块或手工 DLL。

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

NRD 的 GPUScene 集成遵循官方 REBLUR 输入契约：未命中（天空）像素使用高于
`denoisingRange` 的 `viewZ` sentinel，并在 compose 阶段保留原始 trace；不得将天空编码为
`viewZ = 0`，否则会被 NRD 当作有效表面而复用历史。NRD 的 queued-frame 数与
`maxFramesInFlight` 一致，保证描述符和常量缓冲不会在 GPU 完成前复用。多 SPP 会先以首个有效
表面的 material factor 对每个有效样本做 demodulation，再平均后送入 NRD。

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
| `EnvironmentState` | 天空、太阳与环境光；不包含相机初始介质 |
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
try (Renderer renderer = Renderer.open(RendererConfig.presentation());
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

## 预编译场景 shader

生产应用可以在构建阶段编译场景 shader，并通过 `SceneShaderProgram` 携带优化 SPIR-V，避免首次启动
重复执行 shaderc 优化。所有模块仍须通过 stage、entry point 和 descriptor ABI 校验；错误二进制不会
悄悄换成内置 shader。raygen 分别注册 SDR 与 HDR，miss/hit 与输出编码无关：

```java
var stages = SceneShaderProgram.builder()
    .spirv(ShaderStage.RAY_GENERATION, FrameOutputFormat.SDR_RGBA8,
        Files.readAllBytes(Path.of("scene.rgen.sdr.spv")))
    .spirv(ShaderStage.RAY_GENERATION, FrameOutputFormat.LINEAR_HDR_RGBA16F,
        Files.readAllBytes(Path.of("scene.rgen.hdr.spv")))
    .spirv(ShaderStage.RAY_MISS, Files.readAllBytes(Path.of("scene.rmiss.spv")))
    .dataBindingCount(1)
    .build();
```

未替换的阶段继续使用内置实现。已替换阶段缺少所需二进制变体时，只有显式提供的 GLSL 可参与编译；
没有源码则明确拒绝。可同时调用 `shader(stage, glsl)` 保留编辑和源码变体能力。预编译需使用相同版本
core 中的 `gpuscene_common.glsl`/ABI 头文件。自定义 raygen 的既有 SER 协商策略保持一致。

## 通用命令语义：专家入口

`Renderer` 是统一公共契约；本节使用 Vulkan 后端。retained-scene 快速路径与版本化资源、严格有序的
`RenderCommandTransaction` 可共存于同一 Vulkan 实例；`RenderWorkload.Mode` 或被直接调用的提交方法明确
选择语义，两种输入不会相互猜测或转换。普通调用方使用 `Renderer.open()` 与
`SceneTransaction`。只有需要保留既有图形提交语义的宿主才应使用 command path。完整学习顺序、
通用 RT 最小事务、SPIR-V/SBT 规则、错误处理和 retirement 边界见
[通用命令与硬件光线追踪指南](Generic-Commands-and-Ray-Tracing.md)。

```java
try (Renderer renderer = Renderer.open(
        RendererConfig.builder().build())) {
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

能力必须逐项查询。`7.0.1` 的 Vulkan 后端真实执行版本化 buffer/texture、view/sampler、staging upload、buffer/texture copy、buffer-image copy、typed clear/barrier、SPIR-V compute/graphics pipeline、render pass、direct/multi/indirect draw，以及在设备支持时执行通用 BLAS/TLAS build、显式 RT shader group/SBT、AS descriptor 和 trace dispatch，并通过 fence evidence 公开完成状态。逻辑操作和 geometry shader 现在分别受设备 feature capability 约束；RT pipeline/SBT 可通过 `RetireRayTracingPipelineCommand` 按完成序列退休。`BindingType.COMBINED_IMAGE_SAMPLER` 必须使用一个 `BindingSet.CombinedImageSamplerValue`，Vulkan 会将同一 view/sampler 对写入一个 `VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER`。接口布局会在 pipeline 创建前与 SPIR-V 逐项核对；错误的 entry point、stage、set/binding、数组长度或拆分 descriptor 声明均 fail-closed。RT 的 `RECORDED` 或 `OUTPUT_PRODUCED` 都不是显示可见证据；能力未标为 executable 的功能不能根据静态类型推断可用。

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
