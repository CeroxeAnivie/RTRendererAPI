# 当前文档与历史证据

当前指南对应正在迁移、尚未发布的 **8.0.0**，不是完成报告。生产支持、视觉与整体性能必须以新路径实际验证为准。

## 当前入口

| 文档 | 内容 |
| --- | --- |
| [README](../README.md) | 两模块、原生优先、当前状态 |
| [Java 接入](Java.md)／[API 参考入口](Java-API-Reference.md) | 实际公共契约与源码 |
| [原生场景示例](../examples/native-scenes/README.md) | balls／mixed 等单进程人工场景、真实光学范围与状态测量 |
| [接入边界](backend-routing.md)／[普通与专家控制](ordinary-and-expert.md) | 上游与外部适配的分工 |
| [运行时](runtime-distribution.md) | 官方布局、JVM 宿主、提取和发布 |
| [外部 shader／SDK](rtxpt-release-sdk.md) | 独立 shader 与上游纯净边界 |
| [诊断](rtxpt-diagnostics.md) | 有界事件、游标及内部未展开 |
| [兼容性](COMPATIBILITY.md)／[消费方接入](consumer-agent-handoff.md) | 破坏性升级及完整验证要求 |
| [文件命名](file-naming.md) | 功能命名，不创建版本副本 |

## 历史材料

release-*.md、release-index.json、quality/ 及其中数据、截图身份和失败记录，继续描述各自受测版本；未重新测量的数据不转签 8.0.0。

旧 Vulkan 命令／可编程场景／procedural／CPU 帧／组合器指南，以及旧 host-surface、六档 shader ABI、内部诊断和验收矩阵，保留为历史。其模块、类、任务或 manifest 即使仍出现在文中，也不表示在新运行图中可用。相关页首标明历史入口，不用于新版本部署。

旧代码已经由 Git 历史保全；本次不创建额外版本备份、不恢复已删旧模块。文档变更不触发无关构建、重复测试或自动发布。
