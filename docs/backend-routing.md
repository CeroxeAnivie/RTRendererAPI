# provider 与接入边界

8.0.0 正在迁移，尚未发布。工程只构建 renderer-api 和 renderer-rtxpt，旧自研 Vulkan、NVIDIA 独立后端和双后端场景路由已删除。

renderer-api 是纯契约，不隐式加载 provider。需要 RTXPT 的应用依赖 top.ceroxe.rt:renderer-rtxpt:8.0.0，由其正向带入同版本 renderer-api；不要继续列四个模块或引用 RtxptComposedRenderer。

## 谁负责什么

- 消费方提供已经得到的场景、纹理、相机、时钟和变更，并按 GPU 输出租约完成导入／返回。
- API 外部桥只做必要格式适配、稳定身份映射、输入输出与同步契约、错误传播，并保留已实现的通用 NativeLighting／Sparse 扩展；它们须与原版算法区分。宿主专属曲线、画风和最终合成归消费方，现有宿主策略迁出尚未完成。
- RTXPT／Donut／NVRHI 承担原有场景资源、上传、加速结构、原生渲染与呈现生命周期。存在上游等价实现就直接调用，不维护第二套池或调度器。

官方源码和 shader 不接受本项目定制，构建时也不 patch、不拷入自有文件。没有官方等价能力或公开入口时，说明限制与成本，不能静默丢语义，也不能改原版默认行为来绕过。

Renderer 只聚合生命周期和场景／帧提交。旧 Vulkan、CPU frame、通用 GPU command／resource、假 health、GPU 枚举及无数据指标公开面已退役，本轮删除 188 个孤立类型；不保留固定拒绝的兼容壳。Config 只留六类已接线原生 feature 与实际资产字节准入预算。

生产 29 帧和原版 beauty 24 帧 proof 已通过；新控制定向验证、完整负载、人工验收和正式包仍待完成。启动必须遵守[原版运行时布局和 JVM 宿主契约](runtime-distribution.md)。诊断按[真实边界](rtxpt-diagnostics.md)采集。
