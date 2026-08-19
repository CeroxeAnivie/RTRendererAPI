# 通用命令与硬件光线追踪指南

本页面向已经拥有自己的资源、着色器和提交顺序的渲染器或引擎集成者。它讲解 `3.1.3` 的
专家 command path：如何在不转换为 `MeshAsset` 或 PBR 材质的前提下，提交通用 Vulkan 资源、图形命令和
硬件光线追踪命令。

普通应用不需要阅读本页。它们应使用 [Java 开发指南](Java.md) 中的 retained-scene 路径，由
`Renderer` 管理场景、帧节奏与输出。

## 先选择正确的路径

| 你的输入 | 应使用的入口 | 不应做的事 |
| --- | --- | --- |
| 网格、纹理、PBR 材质、实例、灯光 | `SceneTransaction` + `RenderFrameRequest` | 为了“更底层”而手写 Vulkan 同步 |
| 已经确定的 buffer、texture、shader、binding、draw/pass 顺序 | `submitResources(...)` + `submitCommands(...)` | 把既有 shader 猜测性映射到固定 PBR 字段 |
| 明确的 RT SPIR-V、三角形输入、BLAS/TLAS、SBT group | 通用 command path 的 RT commands | 期待 API 自动把任意 raster shader 翻译为 hit/miss shader |
| 外部 Vulkan image/queue/semaphore | `VulkanFrameInterop` | 把 native handle 当作普通 Java 对象缓存 |

专家路径保留你已经确定的图形语义，但不承诺把任意 OpenGL/Vulkan raster shader 自动变为语义等价的
ray-tracing shader。雾、屏幕空间读取、任意 blend 副作用与宿主私有 shader 约定不能可靠推断；调用方
必须提交明确的 RT SPIR-V 与 shader group。不能被 backend 精确执行的输入会 fail-closed。

## 三个不变量

1. **能力先于构造。** 类存在不表示当前设备可执行。每项必需语义都必须查询
   `RenderingSemanticCapabilities` 的 `executable()`。
2. **发布、使用、回收分离。** `ResourceVersion` 描述 buffer/texture storage shape/usage generation；
   内容写入以 `ResourceMutationKey` 和 fence-backed evidence 表示。buffer/texture 回收必须指定精确的
   `ResourceGenerationKey`；AS 则必须以精确的 `AccelerationStructureResource` 提交销毁命令，二者都不能按
   稳定 identity 一次性销毁所有版本。
3. **录制不等于完成，更不等于可见。** `RECORDED` 仅表示命令已被接纳；等待
   `GPU_COMPLETED` 或 `OUTPUT_PRODUCED`。GPU fence completion 也不能证明外部 consumer 已接受或
   显示器已扫描输出。

## 0. 打开 renderer 并协商能力

专家配置不会默认请求 NVIDIA 图像或帧生成能力。请求的 RT 语义也必须由当前 backend 明确标记为可执行：

```java
try (Renderer renderer = RendererBootstrap.open(RendererConfig.expertBuilder().build())) {
    RenderingSemanticCapabilities capabilities = renderer.renderingSemanticCapabilities();
    require(capabilities.feature(
            RenderingSemanticCapabilities.Feature.ACCELERATION_STRUCTURE_BUILDS
    ).executable(), "current backend cannot build acceleration structures");
    require(capabilities.feature(
            RenderingSemanticCapabilities.Feature.RAY_TRACING_PIPELINES
    ).executable(), "current backend cannot create RT pipelines");
    require(capabilities.feature(
            RenderingSemanticCapabilities.Feature.RAY_TRACING_DISPATCH
    ).executable(), "current backend cannot dispatch trace rays");

    // Publish resources, build AS, and trace as shown below.
}
```

`require` 是应用自己的异常辅助方法。不要把 `SUPPORTED`、构造成功或 `RECORDED` 当成替代证据。

## 1. 发布精确资源 generation

每个 command 引用的 buffer/texture 都必须已通过 `submitResources` 发布为精确 resource generation。
BLAS/TLAS 本身由后续的 build command 声明和创建。用于 BLAS 的 position/index buffer 必须有
`ACCELERATION_STRUCTURE_BUILD_INPUT` usage；trace 输出 texture 必须有 `STORAGE_READ_WRITE` usage。

```java
BufferResource positions = new BufferResource(
        new RenderResourceId(100), ResourceVersion.initial(), 36,
        Set.of(BufferUsage.COPY_DESTINATION, BufferUsage.ACCELERATION_STRUCTURE_BUILD_INPUT)
);
TextureResource output = new TextureResource(
        new RenderResourceId(101), ResourceVersion.initial(), TextureDimension.TEXTURE_2D,
        1280, 720, 1, 1, 1, 1, TextureFormat.RGBA8_UNORM,
        Set.of(TextureUsage.COPY_DESTINATION, TextureUsage.STORAGE_READ_WRITE)
);
ResourceTransactionEvidence resources = renderer.submitResources(
        RenderResourceTransaction.builder(0).upsert(positions).upsert(output).build()
);
if (resources.outcome() != ResourceTransactionEvidence.Outcome.ACCEPTED) {
    throw new IllegalStateException(resources.detail());
}
```

一次内容写入不需要创建新的 `BufferResource` Java 对象或新的 storage generation。后续
`WriteBufferCommand` 会产生新的 mutation evidence；同一提交内的后续 vertex/index/AS-build 读取
自动获得必要的 submission-local visibility dependency。跨 transaction 读取仍须等待前一写入到达
`GPU_READY`，或由调用方提交明确的 barrier。

## 2. 上传、构建 BLAS/TLAS

以下示例使用一个 non-indexed float3 三角形。`positionBytes` 必须是 native-order direct buffer，
包含三个连续的 `(x, y, z)` float；应用可以使用更大的 stride，但 position 必须从 offset zero 开始。

```java
ResourceSlice.BufferSlice positionSlice = new ResourceSlice.BufferSlice(
        positions, new ByteRange(0, 36)
);
AccelerationStructureResource blas = new AccelerationStructureResource(
        new RenderResourceId(102), ResourceVersion.initial(),
        AccelerationStructureKind.BOTTOM_LEVEL, false
);
AccelerationStructureResource tlas = new AccelerationStructureResource(
        new RenderResourceId(103), ResourceVersion.initial(),
        AccelerationStructureKind.TOP_LEVEL, false
);
AccelerationStructureTriangleGeometry triangle =
        new AccelerationStructureTriangleGeometry(positionSlice, 12, 3, null, null, 0);

CommandExecutionEvidence build = renderer.submitCommands(
        RenderCommandTransaction.builder(1)
                .add(new WriteBufferCommand(positionSlice, new ResourceData(positionBytes)))
                .add(new BuildBottomLevelAccelerationStructureCommand(
                        blas, AccelerationStructureBuildMode.BUILD, List.of(triangle)))
                .add(new BuildTopLevelAccelerationStructureCommand(
                        tlas, AccelerationStructureBuildMode.BUILD,
                        List.of(new AccelerationStructureInstance(
                                blas, AffineTransform3x4.identity(), 0, 0xff, 0, true, false))))
                .build()
);
```

同一 transaction 中 BLAS build、引用该 BLAS 的 TLAS build 与之后的 trace 可以按书写顺序执行；backend
会加入 `ACCELERATION_STRUCTURE_BUILD -> RAY_TRACING_SHADER` 依赖。`UPDATE` 只允许更新同一个、
已 fence-idle 的 AS generation。

## 3. 描述 SPIR-V 接口并构建 RT pipeline

`ShaderModule` 接收**完整、native byte order 的 SPIR-V**，而非 GLSL 文本。下面的 `readSpirv` 是应用
的资源加载函数，必须返回 direct/native-order、以 SPIR-V magic word 开头的完整字节序列。reflection
必须与 SPIR-V 的实际 set/binding、数组长度与 stage 完全一致；backend 会重新验证，不能靠声明绕过。

```java
BindingLayoutEntry outputBinding = new BindingLayoutEntry(
        new BindingKey(0, 0), BindingType.READ_WRITE_STORAGE_TEXTURE, 1,
        Set.of(ShaderStage.RAY_GENERATION), false
);
BindingLayoutEntry sceneBinding = new BindingLayoutEntry(
        new BindingKey(0, 1), BindingType.ACCELERATION_STRUCTURE, 1,
        Set.of(ShaderStage.RAY_GENERATION), false
);
BindingLayout layout = new BindingLayout(List.of(outputBinding, sceneBinding));

ShaderModule raygen = new ShaderModule(new RenderResourceId(110), ResourceVersion.initial(),
        ShaderStage.RAY_GENERATION, "main", readSpirv("raygen.spv"),
        new ShaderReflection(List.of(outputBinding, sceneBinding), 0));
ShaderModule miss = new ShaderModule(new RenderResourceId(111), ResourceVersion.initial(),
        ShaderStage.RAY_MISS, "main", readSpirv("miss.spv"), new ShaderReflection(List.of(), 0));
ShaderModule closestHit = new ShaderModule(new RenderResourceId(112), ResourceVersion.initial(),
        ShaderStage.RAY_CLOSEST_HIT, "main", readSpirv("closest-hit.spv"), new ShaderReflection(List.of(), 0));

RayTracingPipelineState pipeline = new RayTracingPipelineState(
        new ShaderProgram(new RenderResourceId(113), ResourceVersion.initial(),
                ShaderProgram.Kind.RAY_TRACING, List.of(raygen, miss, closestHit), layout, 0),
        List.of(
                RayTracingShaderGroup.general(raygen),
                RayTracingShaderGroup.general(miss),
                RayTracingShaderGroup.triangles(closestHit, null)),
        1
);
```

group 顺序就是 SBT 顺序。必须恰好有一个 ray-generation group；triangle hit group 至少需要
closest-hit 或 any-hit，procedural group 还必须提供 intersection shader。

## 4. Bind and trace

trace 输出是普通 `TextureView`；acceleration structure 是强类型 binding value，公共 API 边界
绝不暴露 raw Vulkan handle。

```java
TextureView outputView = new TextureView(output, TextureViewDimension.TEXTURE_2D,
        new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1));
BindingSet bindings = new BindingSet(layout, Map.of(
        new BindingKey(0, 0), List.of(new BindingSet.TextureValue(
                outputView, BindingType.READ_WRITE_STORAGE_TEXTURE)),
        new BindingKey(0, 1), List.of(new BindingSet.AccelerationStructureValue(tlas))
));

CommandExecutionEvidence trace = renderer.submitCommands(
        RenderCommandTransaction.builder(2)
                .add(new BindRayTracingPipelineCommand(pipeline))
                .add(BindBindingSetCommand.fixed(bindings))
                .add(new TraceRaysCommand(outputView, 1280, 720, 1))
                .build()
);
```

轮询 `renderer.commandExecutionEvidence(2)`，只有达到 `OUTPUT_PRODUCED` 才推进依赖它的操作。
该 evidence 标识输出 resource，但不代表已经 present。要组合或显示输出，必须使用声明相应
composition/presentation capability 的 backend，并遵守其 consumer contract。

## 失败处理与回收

| 情形 | 正确操作 |
| --- | --- |
| resource transaction 被拒绝 | 修正类型化的验证原因；不得提交引用这些资源的 command。 |
| command outcome 仍为 `RECORDED` | 在应用 deadline 内 pump/poll；不得提前复用依赖的 storage。 |
| `DEVICE_LOST` | 按报告的 recovery action 重建 renderer。 |
| 需要释放 buffer/texture generation | 所有已记录 consumer 完成后，以精确 `ResourceGenerationKey` 退役。 |
| 需要释放 AS generation | 所有已记录 build/trace use 完成后，提交 `DestroyAccelerationStructureCommand`，其 target 必须是精确的 `AccelerationStructureResource`。 |
| 需要证明输出可见 | 使用对应 external consumer/presenter evidence；renderer fence 不足以证明显示器已扫描。 |

## What the included examples prove

[`VulkanGenericRayTracingNativeSelfTest`](../renderer-core/src/test/java/top/ceroxe/rt/renderer/backend/vulkan/VulkanGenericRayTracingNativeSelfTest.java)
is the repository's hardware acceptance example. It uploads triangle positions, builds BLAS/TLAS, validates
an AS descriptor, creates an RT pipeline/SBT, dispatches rays, and requires `OUTPUT_PRODUCED`. It is a
test fixture, not a consumer dependency or a substitute for your application's shader compiler and output
consumer.
