# 后端选择：全 RTXPT、仅介质 RTXPT、保留 Vulkan

> **默认推荐 RTXPT 单后端**完成完整 3D 与最终呈现，显式使用 `Renderer.open("rtxpt", RendererConfig.pathTracedPresentation())`。
> **RTXPT＋Vulkan 混合模式不推荐作为默认方案**：它增加整帧复制、跨 API 同步和显存成本，仅供确实需要 Vulkan 独立命令／颜色叠层的专家使用。当前以功能正确、稳定出帧为维护目标，不做混合模式性能优化。

`Renderer.open()` 和 `Renderer.open(config)` 保持原有 provider 优先级行为。它们不会因为第一次提交了玻璃就自动重建另一个后端。明确指定时也仍可调用 `Renderer.open("vulkan-rt", config)` 或 `Renderer.open("rtxpt", config)`，不会静默回退。

新的普通入口 `Renderer.openForScene(policy, initialScene, firstFrame)` 在打开设备前分析完整场景，并返回实现 `Renderer` 的 `RendererSceneSession`。高级功能、专家扩展、资源和命令仍由原后端执行，没有新增渲染算法或逐帧图像搬运层。

需要 RTXPT 完整 3D 与旧 Vulkan UI 一起工作时，使用另外的 [D3D12↔Vulkan GPU 组合接口](d3d12-vulkan-composition.md)。推荐由 RTXPT 最终呈现以保留 RR＋FG/MFG，旧 Vulkan 负责独立工作与叠层；专家也可明确选择 Vulkan 最终窗口与完整 3D 后端热切换。这里的固定场景策略只负责选后端，不代替图像组合。

| 策略 | 普通表面场景 | 吸收／玻璃／混合场景 |
|---|---|---|
| `VULKAN_ONLY` | Vulkan | 仍指定 Vulkan；不支持的介质请求由该后端明确拒绝 |
| `RTXPT_ONLY` | RTXPT | RTXPT |
| `RTXPT_FOR_MEDIA` | Vulkan | 整个场景交给 RTXPT |

“仅介质用 RTXPT”以**完整场景／会话**为粒度，不是把介质物体单独画成一张图，再与 Vulkan 场景硬拼。混合场景中的普通表面、金属、灯光、玻璃和遮挡一并交给同一个 RTXPT 场景，才能保持其光传输关系。

## 普通调用

已有完整 `initialScene` 和 `firstFrame` 时：

```java
var policy = RendererBackendPolicy.RTXPT_FOR_MEDIA;
var decision = policy.select(initialScene, firstFrame); // 纯 CPU，不创建设备或窗口
System.out.println(decision.backend().providerId() + ": " + decision.reason());

try (var renderer = Renderer.openForScene(policy, initialScene, firstFrame)) {
    System.out.println(renderer.selection()); // 本次真正指定并成功打开的 provider
    System.out.println(renderer.initialSubmission());
    // initialScene 已发布，firstFrame 已提交；下一帧必须递增 sequence。
    renderer.submit(nextFrame);
}
```

初始事务必须调用 `resetScene()`，包含被引用的网格和材质。增量事务无法单独代表完整场景，因此不能拿来首次选后端。识别范围包括持久实例、首帧 `primitiveBatch` 的显式 `MediumBoundary`，以及它们实际引用网格的 `transmission > 0` 材质。没有边界字段的透射材质也会选 RTXPT；未被实例引用的玻璃资产不会误触发。缺少网格或材质依赖在打开后端前报错。

普通默认配置分开处理：Vulkan 使用 `RendererConfig.recommended()`，提供 CPU 可读帧；RTXPT 使用 `RendererConfig.pathTracedPresentation()`，使用上游 GPU 窗口呈现。两者的输出接口不伪装成相同：先通过 `select` 或会话的 `selection` 选择对应的显示集成。RTXPT preset 的 FG 默认关闭；Demo 的默认 2× FG 属于 Demo 配置。

配置工厂无参均使用 `RendererPerformanceTier.MEDIUM`，也提供 LOW／HIGH 重载。路由两侧可分别设置 `.resources(...)`、Vulkan `.surfaceTransport(...)` 与 RTXPT `.pathTracing(...)`；每项只由支持它的后端执行，不从 GPU 型号推断能力或偷偷升档。资源限额是 retained-scene 原始资产准入，不是总显存上限。详见[性能档位与资源预算](performance-budgets.md)。

## 专家配置与已有调用链

```java
var vulkan = RendererConfig.presentation();
var rtxpt = RendererConfig.pathTracedPresentation().copyBuilder()
    .frameGeneration(FrameGenerationOptions.recommended())
    .build();

try (var renderer = Renderer.openForScene(RendererBackendPolicy.RTXPT_FOR_MEDIA,
        initialScene, firstFrame, vulkan, rtxpt)) {
    if (renderer.selection().backend() == RendererBackendPolicy.Backend.RTXPT) {
        var host = renderer.extension(RtxptAccess.class).orElseThrow();
        while (host.renderFrame()) { /* 已常驻的静态场景 */ }
    } else {
        // 复用原有 VulkanFramePresenter / GPU consumer 集成；先关闭 presenter 再关闭 renderer。
    }
}
```

只有选中的配置会用于打开 provider。RTXPT 的 HDR、自定义 GLSL/SPIR-V、通用资源／命令等未支持能力不会被悄悄删除以强行打开，仍按[能力边界](quality/api-release-review-20261101.md)明确拒绝。运行期能力控制器、真实技术状态、FG 交付证据、错误和关闭语义均复用原后端；会话也转发已有 `submitWorkload` 的组合能力，不降低 Vulkan 专家路径。

## 固定生命周期与场景变化

会话只打开一个 provider。`apply`／`submit` 在创建线程执行；状态观察和关闭遵循所选后端合同。实例变换、灯光和吸收系数的普通变化不会改换后端。光学分类在场景依赖变化时缓存，同一个不可变 primitive batch 可复用结果；不会每帧重新扫描三角形材质。

`RTXPT_FOR_MEDIA` 下，若更新后应使用另一个 provider，会在把该帧交给错误后端前明确抛错，要求关闭会话并用完整场景重新打开。不会在窗口、GPU consumer、功能控制器或专家扩展背后偷偷换设备。Vulkan 会话新增持久介质在 `apply` 前就拒绝；RTXPT 会话去掉介质后，在提交下一个无介质帧时要求重开。`RTXPT_ONLY` 始终保持 RTXPT，不受场景类别变化影响。

需要自己管理组合会话／切换时，复用公共 `RendererSceneRouting`，不要每帧把完整场景再传给一次性 `policy.select(...)`：

```java
var routing = new RendererSceneRouting(initialScene); // 一次扫描持久场景的材质依赖
var decision = routing.select(policy, firstFrame);    // 后续帧复用分类

var candidate = routing.updated(delta);              // 只准备，不改变原 routing
renderer.apply(delta);                              // 失败时继续保留原 routing
routing = candidate;                                // 后端成功发布才提交分类
```

分类器只保存路由所需的不可变资产依赖；变换、灯光与不改变边界有无的实例系数更新可直接复用缓存。相同不可变 `FramePrimitiveBatch` 的依赖校验和光学分类也会缓存。候选状态与旧状态相互独立，固定 `RendererSceneSession` 也使用同一个分类实现。它不代替后端的场景 revision／GPU 发布验证。

打开过程中如果首次 `apply`／`submit` 失败，已打开的 provider 会关闭；失败不会再尝试另一后端。已有单 provider 入口完全保留，熟悉原调用方式的应用不必改用会话入口。

## 只部署 RTXPT

`RTXPT_ONLY` 保证执行只选择 RTXPT，不代表默认发行包已经去掉 Vulkan。现有 `renderer-api` 的发布运行依赖仍带 `renderer-core` 和 `renderer-nvidia`，以兼容旧调用者。只需 RTXPT 的消费者可以明确排除这两个默认模块：

```kotlin
dependencies {
    implementation("top.ceroxe.rt:renderer-api:7.0.5") {
        exclude(group = "top.ceroxe.rt", module = "renderer-core")
        exclude(group = "top.ceroxe.rt", module = "renderer-nvidia")
    }
    implementation("top.ceroxe.rt:renderer-rtxpt:7.0.5")
}
```

`renderer-rtxpt` 以 compile-only 方式依赖 API，不会重新引入上述两个模块。API 本身的场景路由只依赖公共模型和 SPI，不加载 Vulkan 实现。纯 API 调用者可将 RTXPT 依赖写成 `runtimeOnly`；使用 `RtxptAccess` 专家接口时需要编译期依赖。Maven 同样在 `renderer-api` dependency 下排除 `renderer-core`／`renderer-nvidia`，并显式添加 `renderer-rtxpt`。双后端部署则保留默认依赖并添加 RTXPT 即可。

本次刷新 7.0.2 ABI 基线同时记录新场景会话 API，以及此前已实施但基线尚未同步的旧散射、密度、相机初始介质等签名收缩；没有恢复被撤下能力。
