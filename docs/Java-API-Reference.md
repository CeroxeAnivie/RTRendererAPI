# Java API 参考入口

本文索引 **8.0.0** 的主要公开类型；完整方法签名以链接源码和对应版本 API 为准。

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

调用顺序见[Java 指南](Java.md)，宿主启动见[运行时](runtime-distribution.md)，事件与计时说明见[诊断](rtxpt-diagnostics.md)。
