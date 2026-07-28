# GPUScene 增量更新

0.3.1 保持 0.3.0 的公开 API、revision、completion、lease 和 deferred-retirement 契约，修复 Provider scene apply 的全场景放大。

## 更新边界

- CPU 引用验证按 transaction dirty IDs 执行，并由 texture→material、material→mesh、mesh→instance 的稳定反向引用索引验证 removal 影响面。失败 transaction 不发布任何索引或 accepted revision。
- texture、material、mesh、instance 和 light 保持稳定 slot。variable-size arena 只准备 dirty placement/removal delta，不复制完整 active allocation map。
- GPUScene upload 只包含 dirty stream/range。相邻 range 以分段所有权合并，最终 staging 只线性写入一次；native staging buffer 在 completion 后保留一个有界可复用 allocation。
- mesh generation 区分 positions、indices、normal、tangent、UV、color、lightmap 和 triangle-material streams。只有 positions/indices 变化请求 BLAS；triangle material、vertex shading attribute、material、light、tint 和 packed-light 更新不会制造 BLAS rebuild。
- TLAS 只响应 instance membership、transform、visibility mask 或 BLAS identity/address 变化；appearance-only instance generation 复用当前 TLAS。

## 0.3.1 验证数据

`coreVulkanGpuSceneHeavySceneBenchmark` 使用 4096 resident regions、128 次单 region geometry 更新、128 次 shading/light 更新、removal、256-region burst load/unload 和最终 reset。2026-07-28 的 Java 21 gate 结果：

| 指标 | 结果 |
| --- | ---: |
| apply P50 / P95 | 0.0727 ms / 0.4435 ms |
| apply second-max / max | 5.3714 ms / 10.129 ms |
| 每 transaction allocation median / max | 48,776 B / 1,885,224 B |
| 单增量 upload ranges max | 3 |
| burst upload ranges max | 520 |
| shading-only BLAS builds | 0 |
| 最终 geometry retirement debt | 0 B |

同一 workload 的 JFR `jdk.ObjectAllocationSample` 中，`VulkanGpuSceneUploadPlanner$Builder.build` sampled allocation 约 0.35 MB；修复前现场记录约 1.57 GiB。JFR profile sampling 不是精确 allocation accounting，因此该数据只用于定位热点；每 transaction allocation 使用 `ThreadMXBean` 直接计数。

完整 contract gate 覆盖 rollback、stale generation、backpressure retry、completion、lease、retirement 和 shutdown 的确定性路径。原生 RTX 门禁用于验证 Vulkan fence、BLAS/TLAS 和 device resource 生命周期；其他 GPU 厂商仍不在 0.3.1 支持范围。
