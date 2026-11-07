# Java API 参考入口

8.0.0 是正在迁移、尚未发布的新 major。当前公开签名以源码和该版本 ABI 检查为准；旧 7.x 的四模块路由、Vulkan 后端和私有 native getter 不构成兼容承诺。

| 公开面 | 职责与源码 |
| --- | --- |
| Renderer | [场景与帧提交、生命周期和扩展查询](../renderer-api/src/main/java/top/ceroxe/rt/renderer/api/Renderer.java) |
| RendererConfig | [六类原生 feature 与真实资产准入配置](../renderer-api/src/main/java/top/ceroxe/rt/renderer/api/RendererConfig.java) |
| RendererResourceOptions | [geometry／texture／total 资产字节限制](../renderer-api/src/main/java/top/ceroxe/rt/renderer/api/RendererResourceOptions.java) |
| PathTracingQualityOptions | [原版公开支持且已接线的质量参数](../renderer-api/src/main/java/top/ceroxe/rt/renderer/api/PathTracingQualityOptions.java) |
| SceneTransaction | [资源与实例的变更事务](../renderer-api/src/main/java/top/ceroxe/rt/renderer/api/SceneTransaction.java) |
| RenderFrameRequest | [本帧相机、尺寸、时间与最低 revision](../renderer-api/src/main/java/top/ceroxe/rt/renderer/api/RenderFrameRequest.java) |
| NativeLightingControl | [独立光照计划、属性与输出租约](../renderer-api/src/main/java/top/ceroxe/rt/renderer/api/NativeLightingControl.java) |
| SparseLightingControl | [已实现的稀疏源发布状态](../renderer-api/src/main/java/top/ceroxe/rt/renderer/api/SparseLightingControl.java) |
| LightingFrameLease | [共享平面、帧身份及消费完成契约](../renderer-api/src/main/java/top/ceroxe/rt/renderer/api/LightingFrameLease.java) |
| RtxptRenderers | [RTXPT 的显式执行入口](../renderer-rtxpt/src/main/java/top/ceroxe/rt/renderer/rtxpt/RtxptRenderers.java) |
| RtxptRuntime | [校验／提取后的 bin 与 JVM 宿主路径](../renderer-rtxpt/src/main/java/top/ceroxe/rt/renderer/rtxpt/RtxptRuntime.java) |
| RtxptAccess | [实际原生帧、场景、窗口与 capture 访问](../renderer-rtxpt/src/main/java/top/ceroxe/rt/renderer/rtxpt/RtxptAccess.java) |
| RtxptDiagnosticAccess | [owner 线程上的外部边界采集](../renderer-rtxpt/src/main/java/top/ceroxe/rt/renderer/rtxpt/RtxptDiagnosticAccess.java) |

renderer-api 是纯契约；renderer-rtxpt 正向依赖它，并消费未修改的 RTXPT。Renderer 不再继承 CPU FrameAccess／CommandAccess；旧 Vulkan、CPU 帧、通用 GPU command／resource、假 health 及固定无数据指标契约已退役，本轮收缩删除 188 个孤立类型，不维持兼容拒绝壳。

RtxptAccess 的 presentationState／exposureState／setExposureOffset 对接原生状态和曝光；FG 激活字段不等于实际呈现帧数。通用 NativeLighting／Sparse／共享同步的真实外桥保留；宿主专属曲线与画风迁到消费方仍在进行。原生质量参数和新控制的实际效果尚待定向验证。

[Java 指南](Java.md)说明调用顺序；[运行时说明](runtime-distribution.md)说明标准 JNI 宿主；[诊断说明](rtxpt-diagnostics.md)说明真实计时边界。完整生产支持和正式发布仍待本批验证，不引用历史类／方法列表冒充当前 API。
