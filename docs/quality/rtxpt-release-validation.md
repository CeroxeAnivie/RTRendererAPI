# 7.1.0 本次交付验收结果

日期：2026-11-03。本次采用“首轮完整覆盖，修复后仅补验失败项”的方式收尾；按用户要求保留已经通过的结果，不重复运行。

首轮 `verifyApiClosure -PrtxptTestPackaged --continue` 执行了 279 个任务，其中 250 个实际执行、29 个为最新状态，耗时 22 分 56 秒。首轮有 9 项失败，因此不将它记为一次全绿的全量运行。

失败原因是新增初始变换上传后测试仍使用旧绝对计数，以及严格打包运行缺少部分 shader 排列。变换断言改为核对编辑前后的增量；预编译包补齐到 1143 项。以下原失败项使用最终 JAR、禁止运行时编译的配置完成补验，全部通过，Gradle 输出 `BUILD SUCCESSFUL`，耗时 11 分 29 秒：

- `rtxptNativeSelfTest`
- `rtxptAdvancedNativeSelfTest`
- `rtxptBudgetNativeSelfTest`
- `rtxptDiagnosticMonitorNativeSelfTest`
- `rtxptPackagedBudgetNativeSelfTest`
- `rtxptPackagedFramePreparationNativeSelfTest`
- `rtxptPackagedNativeSelfTest`
- `rtxptSparseLightingNativeSelfTest`
- `rtxptStructuralLightingNativeSelfTest`

首轮已通过的 CPU、GPU、宿主输入与传输、原生光照矩阵、Vulkan、NVIDIA、Demo 和离仓消费者结果继续保留。压力验收完成 10 次 session、50 次 resize、60 秒动态场景和 120 秒混合场景，共 38231 帧。`rendererCoreMediumNativeGate` 为空汇总节点，其实际依赖由首轮结果及本次 `rtxptNativeSelfTest` 补验覆盖。

本机证据：

- `build/710-first-full-api-closure.log`：首轮完整日志。
- `build/710-package-repair-preflight.log`：9 项严格打包补验日志。
- `build/reports/rtxpt-710-release/result.json`：本次组合验收结果、日志及四模块最终 JAR 的 SHA-256。
- `build/reports/rtxpt-710-stress/result.json`：持续压力结果。
- `build/reports/rtxpt-710-pruning-comparison.json`：同算法优化前后测量及逐位图像对照。

`build/reports/api-closure/result.json` 仍只表示单次全量入口的执行结果；本次组合验收不改写它为通过。Maven Local 发布使用已完成补验的四个 JAR，发布后逐个核对 SHA-256。

量化收益及比较范围见[性能记录](rtxpt-performance.md)。
