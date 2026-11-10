# Java 接入指南

本文介绍 **8.0.0** 的 Java 接入、场景提交、配置和输出生命周期。

## 先选运行方式

依赖 renderer-rtxpt，它会带入同版本 renderer-api；纯契约模块不再反向拉 provider。旧四模块依赖、openForScene 双后端路由、旧 Vulkan presenter 和 RtxptComposedRenderer 已移除。

原版 RTXPT 通过 EXE 目录找 shader 与资产，因此先按[运行时与 JVM 宿主](runtime-distribution.md)从 rtrenderer_jvm_host.exe 启动 Java 应用。不要把宿主 EXE 当作普通 java.exe 追加 -cp/main 参数；Java 配置经 RTR_JVM_* 环境字段传入，真实 argv 留给上游。

## 提供输入，交给上游执行

Renderer 只聚合生命周期与场景／帧提交，不再聚合 CPU FrameAccess 或 CommandAccess。Renderer.open()／RendererConfig.recommended() 的目标是原生 GPU 呈现，不再是 CPU 可读 renderer。旧 GPU 枚举、CPU readFrame／pollFrame、通用 submitResources／submitCommands、假 health 和无数据的指标接口已退役，不用长期抛 Unsupported 或固定 REJECTED 保留空壳。

- 用 TextureAsset、MaterialAsset、MeshAsset、SceneInstance 及 SceneLight 表达宿主已经得到的数据；API 只做必要转换，不再解释一套宿主场景。
- 用 SceneTransaction 发布变更；revision 必须前进，删除与替换保持合法资源身份，不能引用已经移除的资产。
- 用 RenderFrameRequest 提交本帧实际相机、尺寸、clock、最低 scene revision 和显式 history reset。sequence 必须前进。
- 原生场景资源、上传、加速结构和渲染循环由官方 RTXPT／Donut／NVRHI 管理。API 增量索引只把必要变更交到原接口，不再复制原资源池与调度器。
- 不支持的语义清楚报错，不静默丢材质采样、变换或同步约束。

场景与帧的精确签名以当前公开源码为准：[SceneTransaction](../renderer-api/src/main/java/top/ceroxe/rt/renderer/api/SceneTransaction.java)、[RenderFrameRequest](../renderer-api/src/main/java/top/ceroxe/rt/renderer/api/RenderFrameRequest.java)。

## 输出与生命周期

完整原生场景由 RTXPT 的原循环执行；RtxptAccess 只保留实际场景、帧、窗口和 capture 访问，以及外部边界诊断。旧内部设置／资源池探针不是 8.0.0 兼容目标。

独立光照通过 RtxptRenderers 的 native-lighting 入口提供计划和必要属性。自有直接光 shader 是外部扩展，不称为原版 RTXPT 光照。

在这个有限信号模式下，MeshAsset 的原始三角形顺序保持稳定，逐三角形材质索引独立上传。材质分组、排序、纹理采样和透明类型变化不改变几何索引；位置或索引真正变化时，仍由原生上传器与 AS 构建器处理。身份替换经过精确内容比较后可以沿用已完成的几何和属性范围；新的公开身份、材质及帧版本照常发布。此路径适用于所有使用该模式的消费者，不依赖应用名称或场景名称。

表面常量仍由原版 Donut Material.FillConstantBuffer 生成，纹理描述符和 GPU 资源由原生接口管理。桥接层只保留表面映射及其生命周期；透明覆盖在本模式的主命中和候选命中入口统一读取该映射。完整原生 beauty 模式继续使用原版材质几何组和渲染循环。

资源事务可以复用原生已驻留、且经精确比较相同的几何输入。桥接层在变化或退休的候选中按不可变缓冲存储、原身份及姿态查找，不扫描所有驻留几何；继承的是原始位置、法线、切线、纹理坐标与规范索引的字节所有权，材质和着色属性仍按当前事务发布。原始租约直接保活，不串联历史替换对象，也不持有 JVM 借用地址。真实通道变化或没有安全基准时仍传完整新输入。完整原生 beauty 路径仍根据当前材质映射建立原生几何组。

共享光照输出通过 LightingFrameLease 交接。消费方核对 sequence、scene revision、attribute generation、clock、格式与尺寸；导入后按契约排入 GPU completion 再返回租约。没有导入的包可以直接归还；已导入的包不能只调用 close 就释放仍在消费的资源。禁止重复旧帧冒充 fresh 输出。

NativeLighting／Sparse 是外部桥接能力。应用按公开输入提供宿主亮度响应，并负责自身画风与最终合成。

有限光照模式支持实例及帧临时几何的 visibilityMask：低三位依次表示接收表面、投影遮挡、间接反弹，255 表示全部参与；castsShadow=false 只去掉遮挡角色。原生角色与显式发布的 NativeLightingPublication.Instance.roles 取交集，显式 roles=0 可以保留驻留资源而暂停参与。普通 beauty 模式仍拒绝自定义角色，避免把这些语义静默忽略。

有限光照的基线坐标优先使用显式 MeshBaseline，其次使用 MeshAsset.lightmapCoordinates；两者均未提供时，实例的 packedLight 两个无符号 16 位坐标分别除以 240。显式 Instance 基线变换优先于自动回退。临时几何遵循同一规则，光照坐标变化只更新属性。PrimitiveInstance.previousTransform 直接参与有限光照 RR 的物体运动计算，默认值等于当前 transform；列表位置复用、新增或重新加入的几何不会自动借用同位置的旧姿态。该自有属性行目前由 32 字节扩展为 80 字节以携带矩阵，容量与变化行上传代价相应增加。

NativeLightingPlan 默认的 DIRECT_GAIN alpha 仍是表面透明度。宿主只有在提供专用覆盖率 mip 链时才选择 RECONSTRUCTION_COVERAGE_V1：所有多 mip 的 masked 纹理各层 R 存对应材质 cutoff 的覆盖率，G 存二值 alpha 有效性，B 存布局有效标志，mip 0 的 A 保留原 alpha。G/B 都为 1 时才允许面积积分，普通颜色 mip 链不能启用该模式；单 mip、复杂轮廓或多层半覆盖会回退真实相关射线。输出 alpha 的字节值 255／64／0／128 分别表示普通、已积分、未解决和不可复用的动态覆盖，宿主按这个契约重建，不能再把它当透明度。GI 不改变直接光覆盖标记。

HOST_RESPONSE_PARTIAL_SKY_V1 使用与 HOST_RESPONSE_RATIO_V1 相同的静态支持范围和动态光源规则，同时按昼夜遮挡系数及剩余局部基线降低天空响应。新模式在宿主响应分母为零时保持增益 1；旧 RATIO_V1 的零到正值仍采用 maximumGain。两种模式都必须发布 16×16 RGB 宿主响应表。有限光照还支持帧内 DistanceFogState：球形距离与水平柱形距离取较大雾权重，再与实例 fog 取最大值；可见度与雾只对修正和 GI 各应用一次，雾颜色由宿主最终合成负责。

## 真实配置与原生控制

RendererConfig 仅包含 frameReconstruction、frameGeneration、lowLatency、denoising、rayTracingOptimizations、pathTracing 六类 feature 与 resources。resources 的 geometryBytes／textureBytes／totalAssetBytes 是保留资产的字节准入限制，不是 VRAM 驻留预算或内存遥测。

PathTracingQualityOptions 只保留原版公开 UI 支持并已 JNI 接线的光源采样、去噪层、嵌套介质质量、subsampling、LOD、精度及后处理等字段。exactOpticalInterfaces、SCENE_ANALYTIC、pointLimit、directionalStrata、自定义 SHaRC 容量／训练参数已删除；原生 SHaRC 启停保留。

RtxptAccess.presentationState() 读取原生请求／激活状态，exposureState() 读取原生曝光字段，setExposureOffset() 对接原生曝光补偿。FG 请求、原生激活和实际呈现帧数分别报告，不能相互代替。

当前[原生场景示例](../examples/native-scenes/README.md)使用主类 top.ceroxe.rt.renderer.rtxpt.examples.NativeSceneDemo。光学示例支持折射／透射／吸收／嵌套；Medium 没有对外散射参数，不能凭上游 sigmaS helper 宣称已接通体散射。

## 诊断与升级

诊断采用[外部调用边界](rtxpt-diagnostics.md)：OFF／SUMMARY／DEEP、固定容量环、owner 线程 snapshot 和明确覆盖计数。没有官方数值 observer 时，RTXPT 内部写“未展开”，不保留旧 private probes。

8.0.0 为破坏性升级。按当前公开接口重新接入，升级范围见[兼容性](COMPATIBILITY.md)。
