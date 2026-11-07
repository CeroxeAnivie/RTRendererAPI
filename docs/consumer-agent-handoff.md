# 消费方接入与复验

本文件是固定接入入口，文件名不随版本变化。当前目标 **8.0.0** 尚未发布，也尚未提交本次 GitHub 交付。生产 29 帧与原版 beauty 24 帧 proof 已通过；新控制定向验证、完整负载和人工验收仍未完成，不当作整体性能恢复。

## 接入边界

1. 只依赖 renderer-rtxpt，由其正向带入同版本 renderer-api。旧 core/nvidia、Vulkan 渲染路线、双后端路由与组合器均已移除。
2. 通过 RtxptRuntime.jvmHostExecutable() 取得校验／提取后的宿主；按[运行时契约](runtime-distribution.md)用 RTR_JVM_* 传 Java 配置，真实 process argv 留上游。
3. API 外部桥提交必要输入和变更，官方 RTXPT／Donut／NVRHI 管理原资源、上传、AS 与渲染循环。有等价实现必须直接调用，不能再造管理器。
4. 通用、已实现的 NativeLighting／Sparse／共享同步保留为外桥能力，不改上游本体。宿主专属亮度曲线、画风与最终合成归消费方；现有宿主策略迁出尚未完成。没有真实执行路径的契约整套收缩，不保留模拟成功、固定 REJECTED／Unsupported 或假零值。
5. 严格校验输出 sequence/revision/attribute generation/clock；共享资源按 GPU completion 与 lease 归还，不用旧帧复用冒充优化。

## 当前公开面

Renderer 只保留生命周期、SceneTransaction.apply／帧 submit 与真实扩展；本轮退役 188 个孤立类型，包括旧 Vulkan、CPU 帧、通用 GPU command／resource、假健康和固定无数据指标。不要按旧接口补回兼容拒绝壳。

RendererConfig 仅包含 frameReconstruction、frameGeneration、lowLatency、denoising、rayTracingOptimizations、pathTracing 六类原生 feature，加 geometryBytes／textureBytes／totalAssetBytes 资产准入预算。预算不是 VRAM 驻留统计。没有 GPU 枚举、CPU 回读、自定义 shader 等假开关。

质量控制以当前 PathTracingQualityOptions 的原版公开设置为准，已实际 JNI 接线，但新控制效果待定向验证。exactOpticalInterfaces、SCENE_ANALYTIC、pointLimit、directionalStrata 和自定义 SHaRC 容量／训练参数已删除；SHaRC 启停保留。原生曝光与 presentationState 的请求／激活状态分开读取，FG 激活状态不等于实际呈现帧数。

## 既有修复的迁移映射

以下描述当前源码去向，不等于所有修复已完成运行验证。合理语义应保留，但已有上游等价实现的旧做法直接退役，不逐行搬运；还在外桥的代码也不能因此称为原版 RTXPT 算法。

| 既有职责／修复 | 当前去向与源码依据 | 尚须证明什么 |
| --- | --- | --- |
| 场景资源、纹理上传、GPU 资源与 BLAS／TLAS 生命周期 | **改由上游。**rtxpt_upstream_resources.h 把 packet 转为 ExtendedSceneTypeFactory／SceneGraph 对象，纹理走原 TextureCache.LoadTextureFromMemory，注册／分配／上传走 Scene.Refresh；原生渲染循环继续完成 AS 与回收。旧自管 GPU arena／上传 worker／BLAS 周转策略不沿用为新运行路线 | 资源增删、纹理父代补丁、关闭重开与完整负载的正确性、长尾和峰值，不能从“调用了原生”推定性能恢复 |
| 稳定身份、增量发布、材质关联与 TRS 数据适配 | **外桥保留最小语义。**Resources.Apply 保留宿主 ID 映射、实际变更及关联反查；材质通过原 dirty 契约更新，TRS 转为原 SceneGraph 约定，不能无损表达的变换明确拒绝 | 高频替换／删除、失败重试及本帧身份；不能以丢更新减少工作量 |
| 通用直接光、中心／半径遮挡、透明／顶点色与必要辅助属性 | **外桥保留真实扩展。**RtrNativeLighting.hlsl 与 rtxpt_native_lighting.h 执行自有 pass，不修改原版光照或材质私有 flags。宿主专属曲线、画风及最终合成归消费方，现有宿主策略迁出尚未完成 | 通用 pass 的实际图像、阴影与透明、法线／采样及输出语义仍须验证；shader 编译不代签视觉，不能写全部迁移完成 |
| 独立 pass 的稀疏源、区域、属性增量与有限输出 | **当前仍在外桥。**rtxpt_upstream_lights.h 明确区分原版 LightsBaker 输入和自有 RetainedLightTree；rtxpt_sparse_lighting.h、rtxpt_native_lighting.h 只服务独立扩展。保留位置不证明已是最薄实现，发现上游等价入口仍须替换 | 完整数量级下的 apply→fresh 输出、CPU／GPU 热点和内存；不能宣称旧热点已全部消失 |
| 纹理过滤与采样元数据 | **原生 sampler＋外桥元数据。**Resources.TextureSamplingPolicies 只给独立 pass 提供策略；RtrNativeLighting.hlsl 使用硬件 sampler，原版 beauty sampler 与 MaterialConstants flags 保持原样 | 最近／线性、寻址、alpha cutout、纹理更新与包内 shader 的实际一致性 |
| D3D12 共享信号、ready／completion fence、本帧 lease 和错误传播 | **外桥保留互操作契约。**rtxpt_signal_frames.h 与 rtxpt_upstream_jni.cpp 发布／释放本帧，rtxpt_upstream_session.h 在原 owner 线程串行请求，不用缓存旧帧完成新请求 | 双 API 所有权、sequence／revision／attribute generation／clock、关闭重开和异常释放 |
| 边界计时、增量诊断与 OFF | **外桥替换旧诊断。**RtxptDiagnostics.java 使用 1024 槽 primitive ring 和游标；旧私有 native 状态／诊断导出退役。无官方数值 observer 的上游内部明确未展开 | OFF、覆盖计数、异步输出及真实开销；自有 pass 的观测不等于上游内部诊断 |
| 上游 shader／默认行为补丁、构建 overlay 与 CRT 参数拦截 | **退役。**官方文件不改、不注入；CMake 仅声明外部目标，AdapterShaders.cfg 独立编译，rtxpt_jvm_host.cpp 走标准 JNI Invocation API | 原文件完整性、真实宿主生命周期、正式包身份及最终运行；不能只凭源码边界写全部有效 |

宿主自己的场景捕获、UI／天空、底图／合成与宿主诊断仍由宿主负责，不因本轮迁移全部搬到 API。旧自研 Vulkan、组合器、双后端路由及依赖其私有 ABI 的测试已退役；保留／替换后的测试必须对应新路径，旧通过记录不转签。

## 诊断迁移

新接口只采集[外部边界事件](rtxpt-diagnostics.md)。afterOrdinal 初始 -1，消费后用 nextOrdinal - 1；固定容量覆盖由 lostEvents 报告。消费方只在 owner 线程取 snapshot，复用自己的有界异步 writer。

RTXPT_FRAME 是墙钟边界，包含等待，不是纯 GPU busy。内部没有官方数值接口时为未展开；旧 protocol/arena/index/admission/GpuMonitor 及旧 schema 不作兼容壳。运行时 OFF 应覆盖消费方 CPU/GPU 计时、事件、CSV、JFR 和快照。

## 交付与验证

当前上游 20 个仓库／4426 个原文件完整性保持，生产 29 帧与原版 beauty 24 帧 proof 已通过。这些短帧检查不代表新控制、完整负载与人工验收全通过。早期宿主／CPU／shader／POM／符号检查及首次 native 访问冲突记录保留其受测身份，不能把后续通过追写成早期运行成功，也不能转签当前最终制品。

新原生控制定向验证待跑，完整 3662 mesh／约 45k source 动态模拟尚无有效结果。8.0.0 GitHub 交付与 Maven Local 尚未完成；MC 仍用正式 7.3.3，没有本版 MC FPS。人工[原生场景](../examples/native-scenes/README.md)按 balls→用户关闭进程→mixed 顺序，不自动连开、不使用 computer use；窗口目标 1600×1000、VSync 关闭、无软件上限、请求 2×FG。光学能力只称折射／透射／吸收／嵌套，不称体散射。

最终仍需本次生产路径的资源增删、材质／采样／变换、视觉属性、输出租约、关闭重开、诊断 OFF、完整通用流式负载及消费方同条件实测。基准从 apply 到 fresh 输出，冷启动与稳态分开，mean/P95/P99/长尾与失败照报；通用模拟不代签宿主帧率目标。

完成验证后，先全量源码／正式工具／文档上传 GitHub，再发布两模块 Maven Local，核对实际 JAR／runtime／DLL／shader 身份，消费者随后接入。生成二进制不上传源码仓库，本次不发布 Central。历史证据保留原身份，不重写数值、不以同版本字符串代替内容核对。
