# RTRendererAPI 文档

本目录当前入口对应 **8.0.0 迁移候选**，尚未发布。API 仅保留纯契约 renderer-api 和外部 provider renderer-rtxpt；资源、上传、加速结构及原生渲染循环尽量直接交给锁定的官方 RTXPT／Donut／NVRHI。

| 需要了解的内容 | 当前入口 |
| --- | --- |
| 模块、依赖与状态 | [项目 README](../README.md) |
| Java 调用与真实配置 | [Java 指南](Java.md)／[API 参考入口](Java-API-Reference.md) |
| 弹球与光学人工场景 | [原生场景示例](../examples/native-scenes/README.md) |
| provider 与职责边界 | [后端与接入边界](backend-routing.md) |
| 官方资源、JVM 宿主、打包及发布 | [运行时分发](runtime-distribution.md) |
| 外部 shader 与上游纯净边界 | [外部 shader／SDK](rtxpt-release-sdk.md) |
| 实际采集范围、OFF 与事件游标 | [诊断](rtxpt-diagnostics.md) |
| 升级与不再保留的旧接口 | [兼容性](COMPATIBILITY.md) |
| 当前与历史文档分类 | [文档索引](documentation-inventory.md) |

8.0.0 不保留旧自研 Vulkan 后端、RTXPT＋旧 Vulkan 组合器、六档私有 shader ABI 或旧内部诊断探针。历史发布、质量报告与验收数字保留原版本和制品身份，不能作为本候选通过证明。当前未完成的生产接线、视觉及完整负载验证，以实际执行结果更新，不用文档替代验证。

Renderer 现只保留生命周期与场景／帧提交；本轮收缩删除 188 个孤立类型，CPU 帧、通用 command／resource、假健康、GPU 枚举与无数据指标不再保留。生产 29 帧和原版 beauty 24 帧 proof 已通过，但新控制定向验证、完整负载与人工验收仍未完成。8.0.0 尚未提交本次 GitHub 交付或发布 Maven Local；MC 仍为 7.3.3，无本版 MC FPS。
