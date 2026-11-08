# 普通接入与专家控制

普通消费者依赖 `renderer-rtxpt`，从[标准 JVM 宿主](runtime-distribution.md)启动，用场景事务和帧请求提交实际输入。资源、上传、加速结构与原生渲染生命周期交给 provider 和上游实现。

## 选择执行入口

- 完整原生场景使用 `Renderer` 与 RTXPT 的原生呈现路径。
- 独立光照使用 `RtxptRenderers` 的 NativeLighting 入口，并按共享输出租约完成 GPU 消费和合成。
- NativeLighting／Sparse 是独立外桥能力，其语义以公开计划、输入和输出契约为准。

## 专家配置

通过 `RendererConfig` 设置原生 feature、公开质量字段及资产字节准入预算。特殊纹理采样、仿射变换、法线贴图和同步行为必须满足接口支持范围。

共享输出使用 ready/completion fence 与 lease；不得在 GPU 消费完成前回收。诊断按需选择 OFF、SUMMARY 或 DEEP，读取范围与线程要求见[诊断](rtxpt-diagnostics.md)。

曝光使用 `RtxptAccess.exposureState()` 和 `setExposureOffset()`；呈现状态使用 `presentationState()`。FG 请求、激活与最终呈现帧数分别解释。

8.0.0 不提供旧 Vulkan 组合器、自动场景路由、私有 shader ABI 或内部资源池探针。详见[Java 指南](Java.md)与[兼容性](COMPATIBILITY.md)。
