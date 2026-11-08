# 贡献指南

## 构建与检查

使用 JDK 25 与仓库 Gradle Wrapper。源码构建和 SDK 配置见[工具说明](tools/README.md)。

```powershell
./gradlew.bat check assemble --dependency-verification=strict --no-daemon --console=plain --no-parallel --max-workers=1
```

当前平台完整回归入口为 `fullRegression`。原生 RTX 验收需要受支持的 NVIDIA GPU 和对应 SDK；来自 fork 的 PR 不在可信 self-hosted GPU runner 上直接执行。重构建和图形验证串行运行，使用 `tools/Invoke-BoundedBuild.ps1` 限制进程树内存。

## API 与实现

- 遵守[兼容性](docs/COMPATIBILITY.md)，为输入、并发、取消、失败和清理路径添加相应测试。
- 先审查公共二进制差异，再更新对应 major 的 ABI 基线。
- RTXPT／Donut／NVRHI／RTXMU 已有等价实现时直接调用，独立扩展保持在外部桥接层。
- 官方导出只读。构建副本仅允许已审计的非交互进度窗与网络策略差异；使用官方 `TRACY_ENABLE=OFF` 关闭 Tracy。差异由 `tools/Assert-RtxptAuthorizedPatches.ps1` 审计。
- 发布包不得依赖运行时 shader/native 源码编译。

## 验证与提交

修改应有明确根因、行为契约与影响范围。使用同入口、同场景和同配置验证性能，区分 CPU 墙钟、GPU timestamp 和端到端完成时间，不相加重叠区间。浮点/视觉差异按契约与实际质量判断，不用额外容差掩盖回归。

PR 中说明行为变化和已执行的检查。源码仓库保留代码、测试、必要设计说明和面向用户的文档；不提交任务交接、实验流水账、原始测量、截图取证、本机发布回执或生成二进制。本地质量记录可放在被忽略的 `docs/quality/`。

安全问题按 [SECURITY.md](SECURITY.md)私下报告。

## 发布

同步两模块版本，完成相关 ABI、消费者、原生和打包检查后，先提交并上传源码、正式工具与文档，再向选定的 Maven 渠道发布。当前交付使用 Maven Local；不自动向 Central 发布。发布元数据与制品校验随分发流程管理，生成二进制不提交到源码仓库。
