# 兼容性与版本

## 8.0.0 迁移候选

8.0.0 是允许破坏性调整的新 major，尚未发布。当前工程只保留 renderer-api 与 renderer-rtxpt；后者正向依赖前者。Java 25、Windows x86_64 与可执行 D3D12/DXR 的受支持 NVIDIA GPU 是当前运行基线。

不保留 7.x 兼容壳：旧自研 Vulkan 后端、独立 renderer-nvidia、旧演示、双后端路由和 RTXPT／Vulkan 组合器均不属于新运行图。旧私有 shader ABI、资源池／准入探针和内部诊断结构也不作为迁移兼容目标。保留通用互操作数据类型不表示旧渲染实现仍可使用。

本轮公开面收缩删除 188 个孤立类型。Renderer 只保留生命周期与 SceneTransaction／帧提交；CPU 帧读取、通用 GPU command／resource、假健康、GPU 枚举及固定假指标契约不再保留。RendererConfig 只包含六类真实原生 feature 与资产字节准入预算，不维持 CPU 回读／自定义 shader 等假开关。

原生质量控制只保留原版公开设置支持且已接线的字段。exactOpticalInterfaces、SCENE_ANALYTIC、pointLimit、directionalStrata、自定义 SHaRC 容量／训练参数已删除，SHaRC 启停保留。通用 NativeLighting／Sparse／共享同步仍是已实现的外桥能力；宿主专属亮度曲线与画风迁出尚未完成。

消费者应按[模块边界](backend-routing.md)、[运行时启动契约](runtime-distribution.md)和[新诊断边界](rtxpt-diagnostics.md)重新接入，不拼用 7.x provider、native DLL 或源码 overlay。显式选择的运行时不完整时应失败，不回退到旧开发目录。

官方 RTXPT v1.9.0 的源码、核心 shader、默认算法与生命周期保持原版。本项目自有能力只能位于外部桥；存在上游等价实现时直接调用。独立扩展输出、原版输出和版本间回归分别验证。

## 验证与发布口径

- 公共契约的 ABI baseline 只覆盖受检模块的 Java 二进制表面，不能代替 native／画面／性能验证。
- 生产 29 帧与原版 beauty 24 帧 proof 已通过；新控制定向验证、正式包、完整负载和人工验收仍未完成。此前编译、提取、符号与失败证据保持原身份，不转签后续变更。
- 生产资源与直接光、输出租约、生命周期、诊断 OFF、完整流式负载及消费方性能未全部验证前，不发布候选。
- 本次发布顺序是源码／工具／文档先上传 GitHub，再发布 Maven Local；不发布 Maven Central，不覆盖已发行版本。

历史 release-*.md、docs/quality/ 及其数据文件记录各自版本，不代表 8.0.0 兼容性或性能承诺。
