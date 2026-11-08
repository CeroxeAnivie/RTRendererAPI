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

共享光照输出通过 LightingFrameLease 交接。消费方核对 sequence、scene revision、attribute generation、clock、格式与尺寸；导入后按契约排入 GPU completion 再返回租约。没有导入的包可以直接归还；已导入的包不能只调用 close 就释放仍在消费的资源。禁止重复旧帧冒充 fresh 输出。

NativeLighting／Sparse 是外部桥接能力。应用按公开输入提供宿主亮度响应，并负责自身画风与最终合成。

## 真实配置与原生控制

RendererConfig 仅包含 frameReconstruction、frameGeneration、lowLatency、denoising、rayTracingOptimizations、pathTracing 六类 feature 与 resources。resources 的 geometryBytes／textureBytes／totalAssetBytes 是保留资产的字节准入限制，不是 VRAM 驻留预算或内存遥测。

PathTracingQualityOptions 只保留原版公开 UI 支持并已 JNI 接线的光源采样、去噪层、嵌套介质质量、subsampling、LOD、精度及后处理等字段。exactOpticalInterfaces、SCENE_ANALYTIC、pointLimit、directionalStrata、自定义 SHaRC 容量／训练参数已删除；原生 SHaRC 启停保留。

RtxptAccess.presentationState() 读取原生请求／激活状态，exposureState() 读取原生曝光字段，setExposureOffset() 对接原生曝光补偿。FG 请求、原生激活和实际呈现帧数分别报告，不能相互代替。

当前[原生场景示例](../examples/native-scenes/README.md)使用主类 top.ceroxe.rt.renderer.rtxpt.examples.NativeSceneDemo。光学示例支持折射／透射／吸收／嵌套；Medium 没有对外散射参数，不能凭上游 sigmaS helper 宣称已接通体散射。

## 诊断与升级

诊断采用[外部调用边界](rtxpt-diagnostics.md)：OFF／SUMMARY／DEEP、固定容量环、owner 线程 snapshot 和明确覆盖计数。没有官方数值 observer 时，RTXPT 内部写“未展开”，不保留旧 private probes。

8.0.0 为破坏性升级。按当前公开接口重新接入，升级范围见[兼容性](COMPATIBILITY.md)。
