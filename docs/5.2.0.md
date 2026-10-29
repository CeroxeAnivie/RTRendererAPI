# RTRendererAPI 5.2.0

5.2.0 为 retained-scene renderer 增加与 host 无关的封闭体参与介质。持久 scene instances、frame-local primitives 和 global environment medium 使用相同定义。表面材质与体积系数相互独立；传输过程不依赖应用 identity、游戏 block type 或兼容适配器。

此版本通过 GitHub source 和本地 Maven artifacts 交付，**未上传 Maven Central**。三个 modules 均使用 5.2.0，必须配套使用。major-5 public Java ABI 保持不变；新版本有自己的增量 ABI baseline。

## 公共契约

`EnvironmentState.Medium` 定义均匀 RGB absorption/extinction、scattering、density、index of refraction、volumetric emission 和 Henyey-Greenstein phase anisotropy。系数以每单位世界距离计；density 会乘到 extinction、scattering 和 emission 上，不影响 IOR。`absorption(...)` 会在构建时根据最终 scattering 值计算 total extinction。`extinction(...)` 则显式指定 total extinction。在 absorption mode 中，setter 的调用顺序不影响结果。系数必须有限且非负，IOR 至少为 1，anisotropy 严格位于 -1 与 1 之间。Boundary scattering 不得超过 total extinction。

`MediumBoundaryState` 持有 medium、有符号整数 priority、winding orientation 和 surface interaction policy。可将它附加到 `SceneInstance` 或 `PrimitiveInstance`。Volume-only boundary 不可见，但仍保留 extinction、scattering 和 emission。Surface-interacting boundary 使用其普通材质的 transmission、roughness 和 color，并依据边界两侧的 media 确定 IOR 转换。

```java
var water = EnvironmentState.Medium.builder()
        .absorption(0.04f, 0.015f, 0.008f)
        .scattering(0.015f, 0.015f, 0.015f)
        .density(1)
        .indexOfRefraction(1.333f)
        .phaseAnisotropy(0.2f)
        .emission(0, 0, 0)
        .build();
var boundary = MediumBoundaryState.builder(water).priority(1).build();
var volume = SceneInstance.builder(100, closedMesh.id())
        .mediumBoundary(boundary).build();
// Register closedMesh and its material before or in the same scene transaction.
// Frame-scoped replacement uses the same boundary semantics:
var transientVolume = PrimitiveInstance.from(volume);
var quality = MediumTransportOptions.builder().maxInterfaceEvents(12)
        .opticalSamples(1).scatteringSteps(8).maximumDistance(512).build();
var frame = RenderFrameRequest.builder(1, width, height, camera)
        .mediumTransport(quality)
        .primitiveBatch(FramePrimitiveBatch.builder().add(transientVolume).build())
        .build();
```

同一个物理 boundary 应只用于 persistent 或 transient instance 其中一条 lane，不要重复添加到两条 lane。`clearMediumBoundary()` 会移除其角色。不可变副本、相等性判断和 `PrimitiveInstance.from(...)` 都保留完整 medium 定义。Batch 会预先计算 `mediumBoundaryIndices()`，使 provider 只需访问 volume instances。

## 包含关系与边界穿越

Camera 的初始 medium path 会依据已接纳的 mesh generations 和 instance transforms 自动分类，包括镜像/非均匀 transforms，以及 camera 从 volume 内部开始的情况。对于被保留 boundary 使用的 mesh generation，在 instance admission 时以及 mesh 被替换时都会验证封闭流形拓扑。被拒绝的 transactions 不会推进已接纳的 scene revision。

Path 最多跟踪 32 个不同的 boundary instances。进入时加入对应的精确 instance；离开时移除它并恢复外层 medium。重叠区域由最高 priority 决定；priority 相同时采用最近进入顺序。Camera 从多个同 priority persistent volumes 内开始时，以稳定的 instance identity 决定初始顺序；transient list 按列表顺序处理。没有活动的本地 boundary 时使用 global environment medium。

超过 32 层的 camera nesting 和无效的 transient boundary topology 会产生类型化 `FrameValidationException` reasons。Geometry validation 会检查 welded edges、coherent winding、vertex links、非退化 faces 和非零体积，但不能证明不存在任意 triangle self-intersections。调用方必须提供无自交、方向一致的物理边界。应避免重合 interfaces，并避免将 camera origin 放在 renderer 约 1e-3 world-unit ray-offset 范围内；恰好位于表面上的包含关系存在数值歧义。

Boundary 移动、替换、角色移除和 retirement 会使受影响的 medium state 失效。Persistent 与 frame-local medium 的变更也会使 temporal shading history 失效，包括 volume 可见像素之外的间接光照历史。

## 传输与质量

- Beer-Lambert attenuation 和均匀 emission 的解析积分按 segment 进行。
- Dielectric interfaces 使用 Snell refraction、精确非偏振 Fresnel 和 total internal reflection。反射路径保留入射 medium；透射路径跨越 boundary。
- 第一个 optical interface 按 Fresnel 权重拆分反射/折射。后续事件使用确定性的 per-pixel、有界 Monte Carlo branching。粗糙 interfaces 使用 GGX sampling。
- Single scattering 将 HG phase function 与 ambient environment、sun、point、spot 和 directional lights 做积分。按顺序执行的 shadow rays 会计入 media 和可透射表面。
- Optical/volume pixels 避开普通 surface temporal history。NRD signals 会保留 primary attenuation/source contributions，并维持既有 diffuse/specular signal contract。

`MediumTransportOptions` 可逐帧编辑，不必重建 renderer：

| Setting | 默认值 | 支持范围 |
| --- | --- | --- |
| continuation/shadow interface events | 12 | 1..32 |
| first-interface optical samples | 1 | 1..8 |
| 每段 scattering integration intervals | 8 | 1..64 |
| medium/shadow segment distance | 512 | finite (0, 1,000,000] world units |
| 同时存在的 boundary instances | 固定容量 32 | 0..32 |

SPP 独立设置；Demo 推荐 **1 SPP**。Event budget 耗尽后会终止后续 transport contributions。未解决的 shadow traversal 和 path overflow 会 fail closed，不会凭空生成可见背景。这些是明确且有限的质量预算，不是无限路径追踪。

Scattering 为均匀 RGB **single scattering**。本版本不宣称支持 heterogeneous density fields、spectral dispersion、完整 multiple-scattering convergence 或聚焦式 refractive caustics。Shadow transmittance 沿到 light 的直线路径计算，不会让该连接路径在 dielectric interfaces 处弯折。粗糙界面传输是有界的实时近似。

## 性能与 shader ABI

Medium geometry 会为每个已接纳的不可变 mesh generation 构建一个可复用的 triangle BVH。常规 queries 使用 BVH ray crossings；对于有歧义的 shared edges，会改用 alternate directions 和 generalized-winding fallback。Mesh instances 共享该 index。静态 camera/batch queries 会复用结果；普通 surface-only batches 和 scenes 不执行 medium containment 工作。

所有次级 optical branches 共用一个 continuation call site。本地 path 会缓存当前 medium slot，并在 crossing 时重算。Surface lighting、均匀介质积分和有序 medium shadows 共用单独编译的 miss service；一次 PBR surface dispatch 会评估其全部 lights。Mask-zero service dispatch 无法与 scene geometry 相交。不参与介质的 opaque shadow queries 在该 service 内仍保留 direct first-hit fast path。这样可避免 compiler 将 transport/light loops 复制到每个 shading branch。

Descriptor binding numbers 不变。Instance records 从 57 words 增至 72，frame uniforms 从 404 增至 447；已有前缀保持不变。Custom scene GLSL 必须包含匹配版本的 `gpuscene_abi.glsl` / `gpuscene_common.glsl`，不要复制旧 strides 或 payloads。Retained pipeline 有一个 public miss 和一个位于 index 1 的内部 medium-service miss，内部 ray tracing depth 为 2。Public raygen/miss/hit SBT-region indices 不变。显式 resource/command API 仍使用用户声明的 groups 和 recursion contracts。

## Demo 与验证

使用 `--media` 启动可执行 Demo（也可在 controls workbench 中选择 **Medium transport**）。界面提供 RGB absorption/scattering/emission、density、IOR、anisotropy、priority、surface roughness 和 transmission、inward winding、nested glass、interior camera、volume-only interfaces、persistent/transient instances 以及四项 transport quality settings。Apply 会在 frame boundary 发布已验证的不可变状态。HDR display conversion 属于 Demo。

自动化证据覆盖 API values/copies、topology rejection 与 atomicity、accelerated containment、signed priority、ABI packing、frame-local retirement、temporal invalidation、shader compilation 和 native HDR transport scenes。Native values 会与 analytic absorption、nested-medium restoration、emission、volume-shadow attenuation 和 TIR 预期比较；point/spot/directional/sun scattering 分别测试。本次交付不声称已完成 interactive Demo/FG/MFG 验收或 Minecraft integration。Performance timings 受机器影响，仅供参考，不代表 display-FPS 承诺。

### 记录的执行结果，2026-10-27

Windows x64、Java 25、NVIDIA GeForce RTX 5080 Laptop GPU：

- Root `check`、Demo `check`、version/ABI checks 和全部 34 个 pinned shader variants 通过。
- Native medium transport 通过 23 个解析/行为场景，包括 mirrored/inward boundaries、frame-local volumes、volume shadows 和旧式仅材质 back-face TIR。
- Native modern frames 和复杂 GPUScene session 通过，包括 SER 和 linear HDR。
- NRD 及有序 NRD + DLSS SR 通过 motion、disocclusion、history/reset、resize 和 recovery checks。
- 本地 Maven publication、topology/runtime closure 和 isolated published-artifact consumer 成功编译，包括新的 medium API。未执行 Central upload task。
- 检查过的 composition/modern-frame/transport/session validation logs 中没有 warnings、errors 或 dropped validation messages。

完整 validation invocation 在 3m 25s 内结束（163 tasks）；此前的 full build 已构建未更改的 native bridge。Logs 保留在本地：`build/release-5.2.0-final.log`、`build/release-5.2.0-shaders.log` 和 `build/release-5.2.0-profile.log`。

可通过以下命令复现可选的有界 profile：

```text
gradlew.bat :renderer-core:mediumTransportNativeSelfTest -PmediumProfile=true
```

该 profile 使用 640x360 linear HDR、1 SPP、balanced medium quality、sun + point lighting；每条 lane 先 warm-up 四帧，再串行测量 24 帧，并开启 Vulkan validation。它测量从 admission 到 CPU readback 的完整耗时，包括 polling/scheduling，而非 GPU-only time 或 presentation throughput：

| Lane | P50 | P95 | Maximum |
| --- | --- | --- | --- |
| opaque PBR | 3.98 ms | 5.84 ms | 7.28 ms |
| bounded absorption | 5.97 ms | 6.48 ms | 7.00 ms |
| bounded HG single scattering | 7.97 ms | 8.96 ms | 9.44 ms |

共享的 3072-triangle containment fixture 在全部 512 次查询中，与 analytic cube classification 和 generalized winding 一致：本次 BVH 约 0.30 ms，完整 winding 约 19.02 ms。Retained-scene transaction benchmark 通过，P50 为 0.0675 ms / P95 为 0.3078 ms；这些是 CPU transaction timings，不是 rendered frame times。

Shader restructuring 将 HDR raygen 从最初不可交付候选版本的 1,539,116 bytes 降至 157,836 bytes，另有独立的 120,332-byte transport service。此 driver 上首次使用最终 HDR pipeline 需要 28.5 秒；复用已持久化 driver cache 的重复 session 在 536 ms 内打开。冷 driver compilation 仍是启动成本，不应误报为稳定帧延迟。最初候选版本的数分钟、多 GB 编译开销未被接受。

本地 Maven JAR SHA-256：

```text
renderer-api-5.2.0.jar     7d3524c49cb539483e0b4ead1647c4a2970630e897fc73593654e076fb1365bb
renderer-core-5.2.0.jar    445a324a00bc3a71aab64887198e03c98913de0cfd95233e8de7531eec5a01b2
renderer-nvidia-5.2.0.jar  b13cb0f849108f03fa7f414612269c97a8f36574da06dff5b8867a2b7438399d
```
