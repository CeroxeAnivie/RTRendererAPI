# 7.1.5 修复收尾与发布记录

2026-11-05。最终 DLL SHA-256：`190f107aec92100823589730b323372d02fe812dafecb3a262651e753eb89409`。当前身份 **13 项正确性通过**，另复用三档规模组合；源码及 API/core/RTXPT 契约构建通过。

**用户最终明确禁止重跑已经通过的测试。** 因此本次不再启动已通过的图形/CPU/性能测试：历史 36 窗保留原 DLL、模式和旧渲染边界；最终 DLL 新性能窗口为 **0**。这是一份按该约束完成的修复与制品交付记录，不是最终 DLL 的完整性能验收通过证明。

GitHub 仅推送代码 `main` 与 `v7.1.5` 标签，不上传二进制包或测试附件；四模块二进制发布到 Maven Local。最终 commit 由 Git tag 固定；JAR/sources/Javadoc/POM/module/runtime/symbols 身份见仓库的 `docs/quality/data/resource-latency-release.json`。该自哈希记录不嵌入 runtime，避免循环。新发布物只做文件、哈希、元数据和封装核对，不运行已通过的 GPU 或消费者测试。

[当前机器记录](data/resource-latency.json)明确区分当前通过与复用；[历史矩阵](resource-latency-historical.md)和[停止优化时的完整前后对照](optimization-closeout.md)保留原值、退化、原始身份与失败候选。不得把历史窗口改标当前 DLL。

## 当前性能结论

最后可比的完整请求参考来自 DLL `80803215…`，4096 mesh、SUMMARY/stable、1280×800 双 HDR、GI2/6/0.35、真实 GI RR、禁用复用：GPU P95 **5.400128 ms**，4 ms 未达。该数据保留在 [tail-latency-review.json](data/tail-latency-review.json)，不是最终重新链接 DLL `190f107a…` 的新跑分。最终 DLL 未重测，不能宣称其 4 ms、稳态 CPU 1/2 ms 或普通局部 CPU 2/4/8 ms 已达标。

历史锁定依赖 ABBA 的旧→新完整 GPU P95 为 5.498399→5.400128 ms，减少 0.098271 ms（1.79%）；GPU P99 为 6.545376→6.602784 ms，增加 0.057408 ms（0.88%）；GPU max 为 8.147551→8.391167 ms，增加 0.243616 ms（2.99%）。此处仅复述已存在的同条件对照，没有新增性能候选或测量。更多分位数、CPU、快帧分布、极值频率和 NRD 差异见历史前后表。

## N01～N09 结果

| 项目 | 保留的修复/源码 | 证据与未闭环项 |
| --- | --- | --- |
| N01 更新链 | `rtxpt_memory_resources.inl`、`rtxpt_retained_scene.inl`：唯一变化计划、资源原因/预算、AS/上传子调用、有界池 | 当前 sparse/组合/删除回归通过；三规模局部/流入结果按原身份复用。原 MC 最慢 AS/上传的同 sequence native 栈和调度根因仍未知 |
| N02 准入/退休 | `rtxpt_resource_reclaimer.h`、`rtxpt_jni.cpp`：32 固定槽后台释放，准入移出 descriptor reset、AS 查询/析构，真实 defer，双 reader 完成 | 当前故障/退休和删除寿命通过；旧慢消费/队列压力复用。原 MC 34.8667 ms retirement 根因未证实；完整 CPU 预算不能冒签通过 |
| N03 生产 GPU | `rtxpt_native_lighting.h`、`rtxpt_native_rr.h`：完整请求计时、八资源 evaluate-local RR 标签、GPU 完成前保活 | 当前真实 RR 与光照正确性通过；最近完整参考 P95 5.400128 ms 未达 4 ms，最终 relink 未重测；失败双队列候选保持撤回 |
| N04 增量索引/payload | 稳定稠密场景表、变化行上传、反向依赖增量、MASKED opacity 修复 | 当前属性/拓扑 8 批×3 信号逐位一致；旧固定局部与三规模数据复用。SDK 仍有必要全局协调，不声称全部严格 O(变更量) |
| N05 在途观察 | `rtxpt_diagnostics.h`、`rtxpt_protocol_trace.h`、`RtxptDiagnostics.java`：format 5、token/ordinal/资源/线程/attempt/mode | 当前 native 在途自检、覆盖/晚注册、Java 新旧 ABI 与高位 ID 解码通过。snapshot 不等主提交锁、不调驱动；原 MC 最慢帧具体因果仍未知 |
| N06 模式开销 | OFF 停新诊断、SUMMARY 无射线原子、独立 benchmark 时间戳 | 当前 instrumentation 正确性通过；旧 36 窗模式对照复用，不声称当前 DLL 已重跑全矩阵 |
| N07 生命周期 | 初始化前 monitor、runtime/device/scene/lighting scope、严格预编译 | 当前冷启动/暖重开/关闭与未导入输出通过；大尺寸/奇数、RR 生命周期等旧结果保留原身份。冷启动仍有秒级成本 |
| N08 协议/异常 | 双 HDR 原子输出、lease/generation、graphics/compute readers、设备首故障 | 当前真实 GL/D3D12、RR/raw、光源组合、共享/最后实例删除和受控 fault 通过；其他已过项目复用。未人为触发系统 TDR |
| N09 矩阵/身份 | 四模块同版、native/shader/PSO/PDB、源码与 metadata 封装核对 | 最终包与当前源/二进制逐字节核对；因用户禁止重跑，最终身份 36 窗未执行。MC 收包后自行同 seed/视距/路径复验，API 不代签 180 FPS |

## 当前通过与失败留证

| 检查 | 结果来源 |
| --- | --- |
| native-diagnostics | 当前身份通过 |
| native-fault-and-retirement | 当前身份通过 |
| java-diagnostics | 当前身份通过 |
| RtxptHighFramerateResourcesSelfTest | 当前身份通过 |
| RtxptLightingInstrumentationNativeSelfTest | 当前身份通过 |
| RtxptSparseQueryOrderingNativeSelfTest | 当前身份通过 |
| RtxptHighFramerateCompositionNativeSelfTest | 当前身份通过 |
| RtxptResourceLifecycleNativeSelfTest | 当前身份通过 |
| RtxptNativeLightingGlSelfTest | 当前身份通过 |
| matrix-719 | 复用历史通过，保留原 identity |
| matrix-977 | 复用历史通过，保留原 identity |
| matrix-4096 | 复用历史通过，保留原 identity |
| RtxptResourceRemovalNativeSelfTest | 当前身份通过 |
| RtxptNativeLightingReconstructionNativeSelfTest | 当前身份通过 |
| RtxptHostAugmentationNativeSelfTest | 当前身份通过 |
| RtxptAttributeTopologyNativeSelfTest | 当前身份通过 |

属性拓扑首次在 300 秒上限停止；单次开启 JFR 的诊断复验 63.630 秒结束，8 批迁移、3 组信号逐位一致，原断言未改。没有为此修改生产代码；初次超时底层原因未知，不能写成已修好。失败日志/receipt 与正常复验日志分别保留。已通过后不再运行它。

进程冷启动约 4.1225 秒，暖重开约 3.6382 秒；关闭分别约 0.1595/0.1529 秒。`structuredCorrectnessEvidence.lifecycle.json` 保留各阶段、线程 CPU、heap/direct。构建/测试重任务串行且受 10 GiB 总提交上限约束；初次失败没有内存超限。

## 复用的历史矩阵

以下全是旧渲染区间 beforeRender→输出的数据，未覆盖更早资源提交，不能与完整请求区间计算收益，也不能验收最终 DLL。每窗 30 秒，真实 RR、禁用复用；原二进制和初始 GPU 占用详见历史 JSON。

| mesh/场景 | 历史 OFF FPS/P95 ms | 历史 SUMMARY FPS/P95 ms | 历史 DEEP FPS/P95 ms |
| --- | ---: | ---: | ---: |
| 719/稳定 | 233.17/4.531519 | 232.47/4.586591 | 180.67/5.762752 |
| 719/动画 | 227.60/4.595967 | 224.97/4.652480 | 173.77/5.993791 |
| 719/普通局部 | 210.43/5.229024 | 194.68/5.480512 | 152.28/6.922367 |
| 719/持续流入 | 200.62/5.291616 | 200.32/5.201984 | 155.61/6.647936 |
| 977/稳定 | 186.05/5.690752 | 184.57/5.784831 | 136.57/7.790752 |
| 977/动画 | 179.73/5.990208 | 178.95/5.991584 | 132.31/8.044159 |
| 977/普通局部 | 178.89/6.012800 | 188.09/5.647615 | 139.14/7.563199 |
| 977/持续流入 | 183.27/5.678527 | 182.97/5.717792 | 136.24/7.600287 |
| 4096/稳定 | 185.00/5.795008 | 185.15/5.811232 | 137.15/7.772735 |
| 4096/动画 | 182.68/5.810304 | 182.93/5.785183 | 135.26/7.760799 |
| 4096/普通局部 | 184.02/5.754911 | 184.07/5.811200 | 136.41/7.810208 |
| 4096/持续流入 | 163.01/13.541536 | 160.87/14.025759 | 125.53/12.442207 |

历史完整 CPU 请求含 apply、所有 admission/trySubmit 和 defer 暂停；其预算失败保留，不以同步尝试的较低耗时代替。所有原始请求、延迟分母、压力、初始化和失败记录仍可离线复核。没有修改 MC 源码、玩家配置或存档；旧 49 秒 GL 未复现，原因不归咎于驱动。

发布复核使用收集器的 `--reuse-reviewed-evidence` 和制品校验器的 `--allow-reused-acceptance`，显式记录用户禁止重跑的约束。默认严格模式仍要求最终 DLL 36 窗；复用模式不会通过该项，也不会把缺测数据写成通过。Maven Local 后用相同文件校验加 `--local` 比对，不启动渲染。
