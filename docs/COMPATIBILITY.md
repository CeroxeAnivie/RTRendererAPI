# 兼容性与版本

本文适用于 **8.0.0**。当前运行平台为 Java 25、Windows x86_64、D3D12/DXR 与受支持的 NVIDIA GPU。

## 依赖与升级

- 依赖 `renderer-rtxpt`，由它带入同版本 `renderer-api`。
- 8.0.0 是破坏性升级；不要混用 7.x provider、原生 DLL、私有 shader ABI 或源码 overlay。
- 旧 `renderer-core`、`renderer-nvidia`、Vulkan 后端、双后端路由和组合器不属于 8.0.0 运行图。
- 按[运行时契约](runtime-distribution.md)从专用 JVM 宿主启动。显式选择的运行时不完整时启动失败，不回退到旧开发目录。

## 公开接口

`Renderer` 保留生命周期、场景事务、帧提交和真实扩展查询。旧 CPU 帧读取、通用 GPU command/resource、GPU 枚举及私有资源池探针不再提供。

`RendererConfig` 包含 frameReconstruction、frameGeneration、lowLatency、denoising、rayTracingOptimizations、pathTracing 和 resources。geometryBytes、textureBytes、totalAssetBytes 是资产准入限制，不是显存驻留统计。

质量参数以 `PathTracingQualityOptions` 当前公开字段为准。旧 exactOpticalInterfaces、SCENE_ANALYTIC、pointLimit、directionalStrata 和自定义 SHaRC 容量/训练参数不再提供；原生 SHaRC 启停保留。

NativeLighting、Sparse 和共享同步作为外部桥接能力提供，不等同于 RTXPT 原生光照算法。共享输出须核对 sequence、scene revision、attribute generation、clock、格式与尺寸，并按 GPU completion 归还租约。

网格定义与实例成员资格分别管理。移除最后一个实例不会删除仍声明的网格；再次使用相同定义可复用已导入输入。调用方应在资源真正淘汰时发送 `removeMesh`，或通过场景 reset 释放定义。原生层保留全部活跃语义变体，每个已声明网格最多额外保留一个休眠变体；休眠实例不进入追踪图。休眠期间的几何修改在再次激活时上传并构建，不能继续使用旧几何。此生命周期不等同于显存预算或 FPS 保证。

## 能力边界

5.0.0 的输入尚未全量对齐。有限光照已接通实例角色、帧雾、packedLight 回退、临时几何显式上一姿态，以及宿主覆盖率重建信号；这些能力不代表普通 beauty 路径具备同样语义。

当前仍拒绝自定义 shaderData、旧帧 lightmap、非单样本 AntiAliasingState、非默认纹理 footprint，以及非默认 InstanceRenderState、overlay 和非闭合介质。普通 beauty 还不支持精确投影、帧雾、自定义实例角色与最近邻／clamp 纹理策略。beauty 的顶点颜色、网格 UV2、packedLight、临时几何显式上一姿态和共有 DepthProjectionState 尚未接通，调用方不能依赖这些输入产生效果。

FG 请求、原生激活状态和实际呈现帧数是不同信息。资产预算、CPU 墙钟和 GPU 查询耗时也不能相互替代。

光学场景支持折射、透射、吸收与嵌套介质；没有公开接通的体散射参数不属于本版接口保证。

实际签名见[API 参考入口](Java-API-Reference.md)，调用方式见[Java 指南](Java.md)。
