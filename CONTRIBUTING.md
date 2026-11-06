# 贡献指南

## 提交 Pull Request 前

使用 JDK 25、仓库内 Gradle Wrapper 构建 Java 25 公共 ABI。一次改动应保持在一个明确的
ownership boundary 内；不得通过更新 ABI baseline 掩盖尚未解释的二进制差异。

```powershell
.\gradlew.bat clean check assemble --dependency-verification=strict --no-daemon --console=plain
```

native RTX 验收需要文档规定的 SDK 根目录和可信 Windows RTX runner。来自 fork 的 Pull Request 不得在
self-hosted GPU runner 上执行；该门禁仅由 merge queue 或维护者拥有的分支触发。

发布前的当前平台全量回归入口为 `fullRegression`，包含 `verifyApiClosure` 和全部维护中的 Java self-test。配置 SDK 后用 `tools/Invoke-BoundedBuild.ps1` 限制进程树内存，并传入 `--no-parallel --max-workers=1` 串行执行。原始日志与一次性输出不提交，正式结果写入版本说明。

## 公共 API 变更

- 遵守 `docs/COMPATIBILITY.md` 中的兼容级别与版本策略。
- 在当前 major 内需要二进制兼容时，优先通过 builder、新类型或 interface default method 增加公共能力。
- 为改动覆盖的空输入、非法输入、并发、取消和清理路径补充 contract test。
- 只有审查过当前 major 的公共二进制差分后，才能更新版本化 ABI baseline。
- 没有类型化 execution evidence 时，不得把实现描述为 active。

## 性能优化

源码构建所需的 PowerShell 用户工具集中在根目录 [`tools/`](tools/README.md)。一次性诊断与性能实验脚本已清理；当前验证入口使用 Gradle 任务，历史报告中的旧命令不再作为现行工具接口。

介质渲染的优化遵循[介质性能优化规范](docs/quality/medium-performance-optimization.md)。先冻结目标场景和画质，再为完整帧链路建立低扰动、分层的时间观测；报告必须把 CPU 墙钟、Vulkan GPU timestamp、CUDA kernel 时间、外部同步等待和端到端完成时间分开，不能把重叠区间相加。

优化前先看架构数据，按端到端关键路径上的独占耗时排序，只处理最大的已证实瓶颈。每轮保留正确性与无降噪图像对照、未插桩性能对照和原始测量数据；收益小但稳定的改动可以保留，确认退化的改动立即撤回。介质性能目标、固定场景、报告字段和迭代闭环以该规范为准。

## Pull Request 内容

说明根因、行为契约、失败/回滚行为与已执行验证。不得提交生成文件、本地 SDK 路径、凭据、下载工具或构建输出。
安全敏感问题遵循 `SECURITY.md`，不进入公开 review 流程。

## 发布流程

1. 选择 SemVer 版本并同步全部版本事实。
2. 通过确定性检查、当前 major ABI 验证、published-consumer 验证和有界 RTX 验收。
3. 构建并验证已签名的 Central Portal bundle。
4. 通过 Maven Central 发布，并确认四个模块均可解析。
5. 创建对应的 annotated `vMAJOR.MINOR.PATCH` 源码 tag。GitHub Releases 不是本项目的二进制分发渠道。
