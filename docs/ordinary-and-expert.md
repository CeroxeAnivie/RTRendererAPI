# 直接接入与必要的专家控制

8.0.0 的方向是尽可能直接调用原生能力。当前仍是未发布的迁移候选，不提供两套后端或兼容绕行方案。

普通消费者依赖 renderer-rtxpt，由它带入 renderer-api；按[宿主启动契约](runtime-distribution.md)启动，再用场景事务、帧请求和明确的 GPU 输出租约接入。核心资源、上传、加速结构和渲染生命周期交给官方实现。

专家控制只暴露真实执行能力。六类原生 feature、原版公开支持的质量字段及真实资产字节准入预算通过当前 RendererConfig 表达；没有 GPU 枚举、CPU 回读、自定义 shader 等假开关。上游已有等价实现就直接调用；禁止将元数据塞入上游私有字段、修改默认算法或复制自有文件进上游树。

通用 NativeLighting／Sparse 和共享同步是已实现的独立外桥；宿主专属亮度曲线、画风与最终合成由消费方承担，现有宿主策略迁出尚未完成。没有真实实现的旧契约已收缩，不能用固定 REJECTED、Unsupported、假健康或全零指标假装存在能力。

- 选择完整原生场景或独立光照的真实执行入口，不继续使用旧 Vulkan 组合器或场景自动路由。
- 输入特殊采样、仿射变换和 normal map 时遵守实际支持边界，不能让适配层悄悄降低语义。
- 共享输出按 ready/completion fence 与 lease 完成 GPU 消费；不要以提前回收或复用旧信号换吞吐。
- 诊断只打开需要的级别，RTXPT 内部无官方数值接口时保持未展开。

[Java 指南](Java.md)、[后端边界](backend-routing.md)及[兼容性](COMPATIBILITY.md)是当前用法。历史六档 private shader、资源池／arena／admission 专家探针不再保留。

新控制实际效果仍待定向验证。原生 presentationState／exposureState 只报告其真实字段，FG 请求、激活与最终呈现帧数分别解释；光学示例不宣称没有对外接通的体散射。
