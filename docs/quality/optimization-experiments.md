# 性能候选记录

2026-11-05：`early-blas-submit-scene-preparation-overlap` 正确性通过，四窗完整GPU P95 6.489632→6.751903 ms（+0.262271／+4.04%），吞吐190.136585→190.316058 FPS（+0.179473／+0.09%），未证明整体收益，已撤回源码、默认DLL/PDB并移走候选OBJ。115份生产源码和完整runtime与ce9快照一致，禁止原样重试。见[失败实测及回退](resource-pipeline-overlap.md)。当前没有活动候选；用户继续宏观优化的授权仍有效。

历史候选（已测后撤回） `early-blas-submit-scene-preparation-overlap`：内存 native-lighting 的结构批次先在原 graphics queue 提交独立 BLAS，再完整执行原 CPU 场景准备，最后提交场景表／TLAS 更新。只重排依赖，不跳过 SDK 准备、不改 shader 或 RR。当前 ce9 两份16事务后缀的 PREPARATION 中位约2.7～2.9 ms，仅为可重叠上限线索，不预报收益。两个命令对象必须随同一代退休，完整请求计时不变；错误或无真实收益就仅撤回此候选，保留ce9。

2026-11-05 两次重启后：`phase-batched-independent-blas` 已保留，完整锁依赖原始样本、所有退化及重启配对见[独立BLAS实测](independent-blas-batching.md)。当前DLL为`ce9c1c65…`，不是原发布矩阵身份。用户要求同标准继续下一处宏观架构优化；先估算关键路径大头。

2026-11-05 重启后继续授权：用户明确要求继续宏观架构优化，允许部分常态小指标退化换取真实整体收益；正确性、完整请求计时和失败撤回要求继续有效。当前 `e2f45b06…` 完整 runtime、匹配 PDB、115 份生产源码和已完成 26 窗身份已保存到 `build/reports/release-716/macro-continuation/base-e2f/`。原 36 窗有 10 窗未完成，不补标通过。

新候选 `bounded-transaction-buffer-pages`：将同一内存事务的小 mesh 顶点／索引资源及上传合并为有界页（最多 32 mesh、合计有效载荷不超过 1 MiB），每 mesh 保留独立属性范围、索引偏移和 BLAS；大 mesh／单 mesh 沿用原路径。页的 lease 由全部使用者共享，部分删除不释放尚有 reader 的页，最后使用者按原双队列退休。目标是减少资源对象、描述符和上传提交，不修改 SDK 的场景全量／增量准备判定，因此不重试失败的 `selective-topology-preparation`，也不恢复 GPU 分拆或双队列。直接以 e2f 完整包锁定依赖作交错对照；性能尚未验证，不预报收益。

用户在本候选对照期间进一步明确：确认是真优化就保留，不要求每项都大幅改善；找问题时仍须优先寻找理论收益大的架构瓶颈。容量、实际工作量与时延分开报告，不能把大幅节省容量写成同幅度加速。

事务页已按真实容量收益保留，延迟基本持平、退化照录，见[完整实测](transaction-buffer-pages.md)。下一候选 `phase-batched-independent-blas`：同一 graphics queue 先统一准备独立输入／目标状态，再批量 clone，统一 clone→refit 依赖，最后批量 build/refit；末尾 BLAS→TLAS 依赖保留，不关闭自动屏障，不复用未退休存储。目的为去掉逐个 AS 状态切换产生的阶段串行，保留全部独立 BLAS 与严格 refit 判定。与已失败的光照 shader 分拆、跨队列 direct/GI 重叠、选择性 SDK 准备均不同。旧 977/DEEP/stream 的 24,662 次构建 scope 共 5,174.4758 ms 仅是诊断瓶颈线索，不作为生产收益。直接基线为已保留 5b518c7a，源码／完整 runtime 保存至 `build/reports/release-716/macro-blas-batch/base-5b518c7a/`。尚未验证，失败完整撤回该候选，事务页保留。

2026-11-05 7.1.6 新授权：按 P01～P09 代码根因交接继续架构修复，允许全量回归，代码先于测试落地。新候选为同 graphics queue 的本帧 receiver/guides → shading，不做旧双队列重叠、不复用旧帧，保留真实 GI RR；正确性和性能均待验。旧失败候选仍禁止原样重试，以下停止记录保留原时点。见[实施记录](7.1.6-implementation.md)。

2026-11-05 发布收尾：最终重新链接 DLL 为 `190f107a…`，本轮 13 项正确性通过。用户随后禁止重跑已通过测试，三规模和 36 窗保留原身份复用，最终性能未重测。当前状态见[发布验收](resource-latency.md)；以下性能候选和 808 等身份均保留为历史记录，不重启候选。

2026-11-05。**最后一次性能候选已失败并完整撤回，性能优化现已停止；完整 GPU P95 ≤4 ms 尚未达到。** 当前数据、N01～N09 状态与真实剩余项见[收尾与前后对照](optimization-closeout.md)。本表和[完整机器记录](data/optimization-experiments.json)保留失败证据，防止重复尝试；不再作为继续优化的授权。

## 定向长尾审查已归档

用户曾单独重新授权调查两个极值，现再次明确停止。该次调查没有新增生产修复；锁定全部依赖的交错对照数据、快帧分布和尾部频率详见[收尾审查](optimization-closeout.md)与 [机器记录](data/tail-latency-review.json)。实际执行顺序为 A1/B1/B2/A2，各 30 秒。默认 DLL/PDB、NRD 和生产源码已核对，均与调查前一致；临时诊断夹具已归档并恢复。4 ms 和罕见长尾未解决，不再启动候选。

## 最后一次候选：失败回退并停止优化

`gi-first-rr-direct-overlap-final` 先在 graphics 完成 GI/guides，再让 compute 的 RR 与 graphics 的独立 direct 重叠；场景／AS 读取仍在 graphics，常量和未使用的 UAV 隔离，输出等待两阶段。它与此前同时启动 direct/GI 的 `concurrent-states` 不同，但真实 RR 主测试首帧即返回 `0x887A0001 / DXGI_ERROR_INVALID_CALL`。构建成功，正确性未通过，未测性能；具体 native 子因未知，不归因驱动，也不是 JVM 崩溃。

失败 DLL `5f299f45d09c1376a7164b7ac46f225252d8bdcf1149666069d856705aa2008e` 已归档。三个源码、默认 DLL/PDB 均恢复到尝试前并核对 hash；失败 OBJ 已归档且从构建输出删除。默认 DLL 保留 `80803215…`，匹配 PDB age 226。回退后主 RR 和修正背压提交方式的 lifecycle 复验通过；原失败日志保留，没有放宽输出断言。证据在 `build/reports/resource-critical-path/gi-first-final-{experiment,rollback}.json`、`gi-first-final-rejected/`、`rr-handoff-gi-first-final-check/`、`final-rollback-check/`、`final-rollback-lifecycle-check/`。

这是用户最后授权的一次性能尝试；宏观失败回退计数为 1，但不得转向局部优化或启动下一候选。后续只收尾，4 ms 未达及当前制品未全矩阵／未发布状态均如实保留。

## 保留：RR 资源按本次评估交接

`evaluate-local-rr-handoff` 是资源所有权修复：将 8 张 RR 纹理随 `slEvaluateFeature` 作为局部标签传入，生命周期为 `eValidUntilEvaluate`，不再使用全局 `eOnlyValidNow` 标签。NVRHI 的 `setTextureState` 持有纹理直到 GPU 完成，覆盖尺寸变化和关闭 RR；外部呈现模式不再对这些纹理承诺不适用的 Present 生命周期。光照 shader、采样、分辨率、DLAA/default RR preset 与完整计时边界均未修改。

审计了 Streamline 2.14.16 发布源码（commit `625db07d...`）的标签复制条件与局部标签优先级。运行中的 SDK 二进制版本同为 2.14.16，但文件标识为 `b742680bf / rc2`，不将发布源码误称为该二进制的精确构建源码。运行回调补充了实际证据：5 秒诊断窗，SDK 可见分配 **67 → 60 次**，累计分配字节 **284950528 → 234487808**，减少 **50462720 字节 / 17.71%**；恰少 7 张、尺寸与输入副本吻合。该数字是回调可见分配统计，不是整个 SDK 的显存峰值，也不是每帧新分配量。

4096 / SUMMARY / stable / 1280×800 / 双 HDR / GI2/6/0.35 / 真实 RR / 禁用复用，A1/B1/B2/A2 各 30 秒。窗口前 GPU 占用 A1/B1/A2 为 0%、B2 为 1%。按版本合并原始样本，旧 11580 帧、新 11716 帧：

| 指标 | 旧 | 新 | 新减旧 | 变化 |
| --- | ---: | ---: | ---: | ---: |
| 完整请求 GPU P50 ms | 5.158400 | 5.099072 | -0.059328 | -1.15% |
| 完整请求 GPU P95 ms | 5.466272 | 5.422623 | -0.043649 | -0.80% |
| 完整请求 GPU P99 ms | 6.751903 | 6.767807 | +0.015904 | +0.24% |
| 吞吐 FPS | 192.890763 | 195.141819 | +2.251056 | +1.17% |
| 采样 RR scope P50 ms | 3.296000 | 3.217248 | -0.078752 | -2.39% |
| 采样 RR scope P95 ms | 3.467008 | 3.358016 | -0.108992 | -3.14% |

完整请求改善很小，不据此承诺稳定的大幅加速；P99 退化照录，不把空闲 GPU 等同于锁定频率。收尾审计发现 A1 的 NRD.dll 为 `3b20ab61…`，B1/B2/A2 为 `62eca296…`；因此这些是版本观测差值，不能将全部时延变化隔离归因于 RR 交接修改。保留依据包括消除多余资源副本、正确性对照通过及 P50/P95／吞吐的观测改善。没有达到 4 ms，不进入历史最大收益表，也不算宏观失败回退。

旧新版 19 份 HDR / guide 捕获逐位一致；新版真实 RR 主测试、HostAugmentation、51 帧诊断等价检查、25 帧四种尺寸切换与 RR 开关、32 帧移动遮挡检查均通过。尺寸切换时持有上一代输出 lease，未通过每帧 awaitIdle 掩盖生命周期问题；旧版也通过新增测试。没有放宽断言。新 DLL `80803215305918e7113e043cd365cc5714f2ee96564bbf42beef823f7470c749`。

证据均在 `build/reports/resource-critical-path/`：`rr-handoff-comparison.json`、`rr-handoff-output-equivalence.json`、`rr-handoff-allocation-comparison.json`、`rr-handoff-{control,candidate}-check/`、`rr-handoff-{A1,B1,B2,A2}/`。历史 PDB 快照与默认 PDB 存在硬链接，构建时符号文件被更新；已断开默认 PDB 硬链接，旧 DLL 哈希及性能样本未受影响。该旧 PDB 不能再作为基线符号使用，详情保留在 `rr-handoff-symbol-snapshot-note.json`。


## 最佳收益候选（精简表）

当前锁依赖对照中已保留的主要架构收益为独立BLAS批量调度。仅列该候选；完整退化、场景差异与重启配对见[实测](independent-blas-batching.md)。历史增量拓扑反向索引记录仍在JSON中，不与不同依赖、不同窗口作严格排名，也不累计百分比。

|候选／场景指标|旧|新|差值（新−旧）|变化|
|---|---:|---:|---:|---:|
|独立BLAS批量调度／stream完整GPU P95 ms|17.242783|8.921664|−8.321119|−48.26%|
|同一候选／stream更新GPU P95 ms|22.088287|16.506496|−5.581791|−25.27%|
|同一候选／stream吞吐FPS|186.101315|197.323823|+11.222507|+6.03%|

条件：4096 mesh、SUMMARY、1280×800双HDR、真实GI/RR、禁用复用，各版本两窗、每窗30秒，完整请求边界；第一次重启前后分别成对。不是全场景加速，4ms未达。此前历史失败如下，保留证据防止原样重试。

本轮基线 DLL 为 `58c8b181ed8c8f01317a9b2cd2da2723aab250eea5785055f703ebcf8169dc6e`。均为 4096 mesh、1280×800、双 HDR、真实 GI RR、GI2/6/0.35、禁用信号复用，每窗 10 秒。旧候选在两个新候选之间回测；没有交错重复的正式 A/B，因此表内百分比只表示观测变化。

| 已撤回方案 | 指标 | 旧 ms → 新 ms | 变化 |
| --- | --- | --- | --- |
| 六个线程局部计数、入口结束后 wave 汇总；前两次 GI 查询保存 winner 候选 | SUMMARY stable 旧渲染区间 GPU P95 | 5.597920 → 6.372543 | +0.774623，+13.84% |
| 同上 | DEEP stable 光照 dispatch P50 | 2.766048 → 3.645424 | +0.879376，+31.79% |
| 每次查询局部累计两个遍历计数；最大值组合按分组优先建树 | SUMMARY stable 旧渲染区间 GPU P95 | 5.597920 → 5.999519 | +0.401599，+7.17% |
| 同上 | DEEP stable 光照 dispatch P50 | 2.766048 → 3.519616 | +0.753568，+27.24% |

第一批 stable 窗口开始前 GPU 占用为 SUMMARY 19%、DEEP 11%，不具备绝对目标验收资格。第二批相应窗口及基线开始前为 0%。不能据此断言所有变化都由代码造成，也不能忽略已出现的退化。

流入窗口同样保留在 JSON 中：旧候选 SUMMARY / DEEP 旧渲染区间 GPU P95 分别为 13.606144 / 17.048832 ms；第一批为 7.674464 / 8.331071 ms；第二批为 8.061279 / 7.884159 ms。旧候选流入波动明显，不能单凭这些降幅把两批代码判为收益。两批均未通过整体性能接受标准，全部撤回；不能把批内单项写成已单独证明有效或无效。

两批正确性对照通过，但不抵消性能失败。第一批 DLL 为 `c57b424f70cba6007b1da3ef62691873270c07f6784a33c5b62855efc1950135`；第二批为 `536d7e405731bb0365af0361d832b407aa186c17ae50cd247dc059a15065b84e`。原始 CSV、分析、receipts、源码和 DLL/shader 归档都在 `build/reports/resource-search/`，具体路径、样本数、哈希及所有场景的旧→新差值见 JSON。

撤回检查已确认：三个 light-tree/sparse 头文件与本轮前备份逐字节相同；六计数、查询局部 wave 汇总及 GI winner 层代码标记已消失；默认运行 DLL 和 shader 已恢复本轮前候选。之后构建的新候选须另作验证，不能沿用失败候选的通过结论。

此前也已撤回：每次 `RtrCount` 做 wave 汇总（没有实测收益）、`packed-order`（完整 GPU P95 5.606112 → 5.936096 ms，+0.329984 ms / +5.89%）、`concurrent-states` 双队列（5.606112 → 6.283040 ms，+0.676928 ms / +12.07%）。原记录见 [high-framerate-resources.md](high-framerate-resources.md) 与 [resource-optimization.md](resource-optimization.md)。

第三批 `instrumented-max` 给最大值组合专用 shader 增加实际计数，让 DEEP 选择该专用路径，仍用立即原子计数。原始光照、source oracle 和精确计数通过；RR motion guide 的 variant 2 逐位比较失败。该候选已撤回，没有测性能，也没有收益结论，源码和运行文件归档在 `build/reports/resource-search/instrumented-max-*`。

未改动的基线 DLL 在相同 RR 对照中也失败：122880 个 motion 值中 7912 个不同，最大差为 `1.7881393432617188e-7`。基线对照证据在 `build/reports/resource-search/control/rr-instrumentation.log`。只预热一帧的修复仍失败；投影变量加 `precise` 也未解决，已撤回源码并重建默认 shader，不能原样重试。

实际输入快照给出直接证据：`build/reports/resource-critical-path/input-trace/native-gi-reconstruction/instrumentation-0-{0,1}-inputs.json` 中，除了计数/搜索历史位和默认评估器不使用的时钟，唯一语义差异为 word 75 的 previous-geometry 位。OFF 标志为 30，含位 16；DEEP 为 7，不含位 16。两份投影矩阵逐位相同。上一帧变换的刷新还会延续一帧，因此预热一次不足。随后使用三帧预热、对照前强制核对语义输入，并对相同相机/物体运动轨迹下 OFF/SUMMARY/DEEP 的全部八组 raw signal/guide 做零容差对照，结果见下段。

后续输入对齐验证已通过：24 组静态及相机/物体运动捕获，八组 raw signal/guide 全部逐位一致，51 次真实 RR 执行，27 帧输入预热。对照前同时验证了语义输入逐位相同；相机/物体移动必须产生可观察的非零 motion。原有 RR 主测试和 HostAugmentation 测试通过。证据在 `build/reports/resource-critical-path/rr-input-aligned/`。这说明旧对照不能作为候选画质回归的证据，不代表重新接受已撤回的性能方案。逐位一致是此处的内部等价检查，整个 API 的标准见[正确性说明](correctness-criteria.md)。

计时边界更正：以上历史字段曾被称为“完整 GPU”，实际只覆盖 `NativeLightingFrameFull` 的渲染到输出，遗漏进入渲染前已经提交的资源构建。历史数值保留，但不能用来验收完整请求 4 ms，也不能与扩大后的边界计算优化收益。新测量从准入前开始，覆盖 native 资源提交直到输出，另行保留旧渲染区间；本次边界修正不是性能优化。

## 已保留的核心资源优化

`stable-scene-table-rows`：稳定稠密资源顺序、实例 swap-remove、只上传变化行。4096 / SUMMARY / 每窗 10 秒 ABBA，stream 完整请求 GPU P95 **24.504704 → 21.611040 ms，-2.893664 ms / -11.81%**；local 整体 **12.185920 → 12.636319 ms，+0.450399 ms / +3.70%**，更新帧 **14.460256 → 14.423968 ms，-0.036288 ms / -0.25%**。用户决定保留；小幅退化可能含外部波动，原因未证实。证据：`build/reports/resource-critical-path/scene-tables-comparison.json`。

`incremental-topology-index`：按增删维护 mesh/instance 反向引用，保留材质/纹理清单协调。4096 / SUMMARY / stream / 旧新旧各 10 秒，完整请求 GPU P95 **23.671424 → 14.814208 ms，-8.857216 ms / -37.42%**；更新帧 **28.225919 → 23.421600 ms，-4.804319 ms / -17.02%**。五项正确性通过，含 11 帧真实像素覆盖。证据：同上目录的 `scene-index-comparison.json`、`scene-index/receipts.json`。两个批次基线不同，收益不能相加；窗口前 GPU 有外部负载，4 ms 尚未验收通过。

## 已撤回候选：selective-topology-preparation

`selective-topology-preparation`：内存资源事务将已知变化网格传入 SDK；只重新打包变化、迁移或材质编号变化的几何行，跳过未变化材质的重复常量生成。仍完整刷新图、实例和迁移检查；初始化、失效或骨骼路径回退完整准备，扩容时完整打包表。与已撤回的 shader/双队列方案不同，不改采样、光照算法、GI/RR 或计时边界。改前源码及 DLL：`build/reports/resource-critical-path/topology-preparation-before/`。当时最终版本通过七项正确性，并按下述 10 秒观测暂时保留；现由干净 GPU 30 秒重测撤销性能接受结论。

### 历史暂留结果（已被后续重测推翻）

SDK 只为已知变化网格准备缺失缓冲，并只重新打包变化、编号迁移或材质编号变化的几何；未变材质跳过重复常量生成。缓存使用弱身份，避免保活退休资源和地址复用误判；事务失败 epoch 触发完整回退。场景图、实例、编号迁移检查及若干清单仍全量处理，不宣称全部增量化。

新增像素回归发现旧版和初版候选都忽略 MASKED 材质自身的 alpha 因子：Donut 打包器固定写 opacity=1。现已在 Scene::UpdateMaterial 为两个 masked domain 保留实际 opacity；原失败日志分别保留在 `topology-preparation-alpha-control/`、`topology-preparation-alpha/`。没有放宽断言。最终七项正确性通过，含透明/不透明多材质区域、槽位复用、混合几何编号迁移、跨 1024 行扩容/收缩、共享/最后实例删除、11 帧覆盖检查、真实 RR、4096 矩阵和 HostAugmentation。证据：`build/reports/resource-critical-path/topology-preparation-corrected/receipts.json`。

最终 DLL `31e1fe62fff4e055da996acb3e9e2bf3781c18a3197e68e49a06b49377f03966`。4096 / SUMMARY / 1280×800 / GI2/6/0.35 / 真实 GI RR / 无信号复用，旧新新旧各场景 10 秒：

| 指标 P95 | 旧 → 新 ms | 差值 / 百分比 |
| --- | --- | --- |
| stream 完整请求 GPU | 14.541343 → 12.816607 | -1.724736 / -11.86% |
| stream 更新帧 GPU | 17.927743 → 15.150080 | -2.777663 / -15.49% |
| stream 更新帧同步 CPU | 18.118700 → 17.736500 | -0.382200 / -2.11% |
| 场景 PREPARATION self CPU | 4.768400 → 3.133500 | -1.634900 / -34.29% |
| stable 完整请求 GPU（仅观测） | 13.725600 → 6.908928 | -6.816672 / -49.66% |

CPU scope 来自有界历史：旧 69 / 新 62 个事务，非全部样本。GPU 占用和频率有明显波动，稳定场景的大幅下降不能归为这项代码收益；初版 12 窗 stable **10.867647 → 12.444991 ms，+1.577344 / +14.51%** 的退化照留，初版和最终版分开记录，不合并 DLL 身份。初版 stream **17.801791 → 14.782943 ms，-3.018848 / -16.96%**；local **12.124191 → 11.621535 ms，-0.502656 / -4.15%**，仅属初版观测，不能替代最终版实测。

完整证据：`build/reports/resource-critical-path/topology-final-comparison.json`、`topology-final-cpu-comparison.json`、`topology-final-{A1,B1,B2,A2}/`。当时源码和失败候选 DLL 归档在 `topology-final-source/`、`topology-final-runtime/`。以上为历史暂留结论；后续干净 GPU 重测未证明收益，现撤回性能方案。没有降低质量或改变计时边界；**完整 GPU P95 ≤4 ms 仍未达到**。

## 干净 GPU 重测：撤回选择性准备

2026-11-05，B1/A1/A2/B2，4096 mesh、SUMMARY、1280×800、GI2/6/0.35、真实 GI RR、禁用信号复用，每场景每窗 30 秒。A 为 `92f3fe2b...`，B 为 `31e1fe62...`；完整边界仍从准入前资源提交覆盖到输出。12 窗基线 11 窗 GPU 0%，A1 local 为 1%，显存均 109 MiB。

以下按同版本两窗原始样本合并后取 P95，不平均各窗 P95，不挑最快窗口：

| 指标 | 旧 ms | 新 ms | 新减旧 ms | 变化 |
| --- | ---: | ---: | ---: | ---: |
| stream 完整请求 GPU | 9.158239 | 20.706880 | +11.548641 | +126.10% |
| stream 更新帧 GPU | 25.061087 | 29.792480 | +4.731393 | +18.88% |
| stable 完整请求 GPU | 6.390368 | 6.878944 | +0.488576 | +7.65% |
| local 完整请求 GPU | 7.039391 | 8.169055 | +1.129664 | +16.05% |
| PREPARATION self CPU | 7.371100 | 7.581300 | +0.210200 | +2.85% |

CPU scope 仅包含有界历史中的旧 85 / 新 106 个事务。相同旧 DLL 的 stream 两窗 P95 为 19.090976 / 6.821472 ms，重复性仍不足；空闲不等于锁定时钟和 CPU 状态，不能把全部差异武断归因于代码或外部负载。但本轮没有证明候选整体收益，因此撤回性能覆盖层、默认源码及运行 DLL，并单独保留 MASKED alpha 正确性修复，不放宽测试。失败源码与运行文件继续归档，禁止原样重新加入。

证据：`topology-clean-comparison.json`、`topology-clean-cpu-comparison.json`、`topology-clean-{B1,A1,A2,B2}/`；均在 `build/reports/resource-critical-path/`。撤回源码清单 `topology-clean-rollback-source.json`，失败源码 `topology-clean-rejected-source/`，失败 DLL `topology-final-runtime/`。回退版已重建，回退版七项正确性全部通过；SUMMARY 每窗 30 秒，稳定 / stream / local 完整请求 GPU P95 分别为 5.545951 / 6.679072 / 5.909023 ms，均未达到 4 ms。证据目录为 `topology-rollback-corrected/`。

## 已撤回：conservative-emitter-hemisphere

发光 AABB 背面子树裁剪已从源码、SDK 阶段源码及默认 DLL/shader 撤回。恢复 DLL `97a2b681e0ce6b6fb01943f379f5d7719663c5531c31d26316522eb9e18c80cd`，运行目录与改前快照逐文件 hash 相同。五项针对性正确性通过；一次 JVM 外部原生崩溃及调整参数重试保留原日志。新增背面/掠射/跨面 BOX oracle 在旧版和候选都通过，作为测试覆盖保留。

4096 / SUMMARY / stable / 1280×800 / 真实 GI RR / 禁用信号复用，各一个 10 秒 A/B 探索窗：GPU P50 **5.021280 → 5.478975 ms，+0.457695 ms / +9.12%**；GPU P95 **7.347104 → 6.425663 ms，-0.921441 ms / -12.54%**；吞吐 **184.134822 → 180.045706 FPS，-4.089116 FPS / -2.22%**。候选 P95 更低，但中位数和吞吐变差，单对短窗不能证明收益，不作为优化接受结论。当前重场景的主要光源都在接收面上方，这个裁剪没有足够适用工作量；用户要求立即撤回并转向宏观架构。

证据：`hemisphere-comparison.json`、`hemisphere-check/`、`hemisphere-rollback.json`；失败源码 `hemisphere-rejected-source/`，失败运行文件 `hemisphere-rejected-runtime/`，均在 `build/reports/resource-critical-path/`。禁止原样重试。


## 7.1.6 单队列 receiver/shading 分拆：失败并撤回

同制品 Fused/Split ABBA，各 30 秒，4096 mesh、SUMMARY/stable、1280×800 双 HDR、GI2/6/.35、真实 RR，禁用信号复用，完整请求 GPU 边界。合并原始逐帧样本后：

|指标|旧 Fused|新 Split|差值（新−旧）|变化|
|---|---:|---:|---:|---:|
|p50Ms|5.103072|5.835424|+0.732352|+14.35%|
|p95Ms|5.392896|6.173184|+0.780288|+14.47%|
|p99Ms|6.391327|7.179807|+0.788480|+12.34%|
|maxMs|13.087839|8.912320|-4.175519|-31.90%|
|fps|194.799052|170.744750|-24.054302|-12.35%|

P50/P95/P99 在两个 Split 窗均退化，单个最大值下降不能代替常态收益。13 份 HDR/guide 文件与原始 7.1.5 runtime 逐字节一致，但性能不成立；不把未独立 profile 的底层原因写成定论。已撤回生产源码、三个候选 shader 和默认运行目录条目，保留资源架构及双 reader 退休正确性修复。禁止原样重试。证据：`build/reports/release-716/receiver-comparison/comparison.json`；候选源码、DLL/PDB、shader 与撤回记录：`build/reports/release-716/receiver-split-rejected/`。

## 7.1.7：剩余TLAS资源链与精确诊断归并

用户重新授权通用修复并要求交付后停止，不接MC或补跑旧版。保留完整SDK场景准备与所有既有BLAS优化，改变TLAS／三张辅助表的容量与placed预留、双fence后独立回收，以及必须发生的属性行上传归并；不恢复已撤回的选择性准备或跨队列／拆shader候选。DEEP六项计数保留原事件语义，改为线程内累加后波内精确汇总。单个已观察请求33.5242ms相关范围仅是机会量，不是收益。

最终DLL `e19ef2868f6c26b32946fcb8caf8102b52fbeadab455cc7054833a684248d35d` 的10项受影响正确性检查实际exit0；同DLL的独立逐事件原子shader参考实际exit0，15个场景六项整数计数一致、80份HDR逐字节相同。容量回归的两轮各删除146与四轮同容量往返均无新TLAS／三表分配，mesh回收积压时已完成双fence的TLAS独立回池212,992字节，旧租约四平面GPU纹理不变。146只是夹具规模。整场景reset的旧graph索引悬空已修；独立参考初次漏空目录导致的初始化失败及既有清理缺口如实保留。详见 `data/native-resource-chain.json` 与 `../release-7.1.7.md`。未运行性能矩阵，机制改善不冒充实测收益。
