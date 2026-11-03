# 原生双信号与旧 Vulkan 后端实测

2026-11-04 最新干净环境对照：每端 1,800 帧，测前 GPU 0%、P8。旧 Vulkan P50/P95=3.561312/4.247680 ms，最终 native=3.470080/4.142176 ms，本轮核心耗时分别减少 2.56%/2.48%，图像通过原定误差阈值。详见 [性能报告](native-lighting-performance.md) 与 [完整数据](data/native-lighting-traversal.json)。首次 native 的测前 P4 与旧端 P8 不一致，该轮未用于结论；只补测 native，保留已通过的旧端。以下为早期受干扰的历史记录。

下述表格保留修复前的原始跨后端记录。随后 native 已完成通用性能修复，600 帧同算法 A/B 的 P50 为 11.351488→8.303168 ms、两输出逐位一致；见 [最新 native 记录](native-lighting-performance.md)。这轮同样受外部占用影响，不与下述旧 Vulkan 时间直接计算倍率。

本批增加了可运行的旧后端适配器。它实际加载已发布的 Vulkan core JAR，通过正式 `VulkanSceneRuntime` 上传同一 retained scene、构建四个 BLAS 和一个 TLAS，再对该 TLAS 执行 GLSL compute ray query。输出为 direct gain、indirect contribution 两个 RGBA16F 图像。没有用 CPU 求交、beauty pass、旧游戏 FPS 或既有 native 剪枝数据充当旧后端结果。

两端共享 `RtxptNativeLightingCostBenchmark` 的场景、相机、80 个 float 参数和 fixture JSON。Vulkan 适配器移植了同样的 source AVL 插入与支撑域遍历、32 个独立组、all/dynamic 两套统计、三种 composition、稳定 64 位 source ID 平局选择、贡献上界剪枝及两条确定性 GI 射线。材质、角色和基线的适配范围是 fixture 声明的四个不透明四边形；这不是新增的第二套生产光照后端，也不代表旧后端任意材质具备与 native 相同的功能。

固定工作量：1280×800 lighting grid、2560×1600 逻辑 viewport、64 个 source、2 GI samples、24 次 warmup、每端 120 个新执行帧。双方关闭 RR、NRD、FG/MFG、垂直同步和帧率限制。逐射线计数与图像 readback 在计时区间之外；计时只覆盖真正的 dispatch。旧端直接保存 Vulkan timestamp query 的起止整数 tick，native 保存 command-list query tick。比较工具先用整数相减，再转换单位，避免绝对 GPU 时钟超过 double 精确整数范围时误判。

源 fixture 的 SHA-256：

`87f664fb54367f43426778920a3f2e9127b7f87c3fd1e59e6018d04631502697`

已发布旧 core JAR 的 SHA-256：

`89f2779fc16fe317044d875f8f7063c23ffd245e686f83bb9c072be1d79b84fa`

两端 PFM 使用一致的 bottom-up 行顺序、little-endian RGB float 和单位缩放。`-1` 与 `-1.0` 都是合法的同一单位缩放，比较工具不再错误拒绝 native 生成的后一种写法。预先固定最大绝对误差 0.01、RGB RMSE 0.002；不允许观察结果后放宽。

完整语义的最终旧端适配器按实际非加法 source group mask 选择存储容量：零个或一个有效组选择 capacity 1，多个组保留 capacity 32。单组可以是任意组号，两种非加法 composition 各自保留结果；all/dynamic 统计互相独立，加法 source 不占用组存储。该选择通过空组、纯加法、group 31、混合 composition、静动态混合、两个组及完整 32 组的 CPU 核验。

最终旧端原始证据位于 `build/reports/lighting-backends/vulkan-specialized/`，新 native 容量选择版位于 `build/reports/lighting-backends/native-specialized/`，首次通用 native 记录保留在 `build/reports/lighting-backends/native/`。每端均保存全部 120 条新执行帧的 dispatch timestamp，旧端 Vulkan validation 完整关闭，0 warning、0 error、0 dropped message。新 native 与其原通用版的两个完整 PFM 均 bit-exact，GPU query 0 dropped、0 pending。逐 RGB 旧后端对照结果如下；两个输出合计比较 6,144,000 个 RGB 数值。

| 信号 | 最大绝对误差 | RGB RMSE | 固定阈值 |
| --- | ---: | ---: | --- |
| Direct gain | 0.00390625 | 0.0000148698185 | 0.01 / 0.002，PASS |
| Indirect contribution | 0.0001220703125 | 0.0000003689303 | 0.01 / 0.002，PASS |

下表保存首次完整通用 native、新 native 容量选择版与最终旧端适配器的原始时间分布。新 native 测前 GPU 为 38/35/35%，旧端为 30/29/29%，原 native 为 18/17/17%，均超过预定 10% 上限。比较工具据此将速度比置空，这些原始时间不能当作已确认的后端速度差或全游戏 FPS；此次数据也不能证明 native 容量选择已经显著加速或超过旧端。

| 实际 dispatch | 样本 | P50 ms | P95 ms | P99 ms | 最大 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| 初始 native 通用分组 | 120 | 15.047744 | 21.392480 | 23.113600 | 23.520960 |
| 新 native 实际组容量选择 | 120 | 14.888096 | 18.834752 | 19.657312 | 19.983200 |
| 旧 Vulkan 最佳通用适配器 | 120 | 4.920352 | 5.526432 | 5.754880 | 5.818656 |

开发中较早的单组专用参考、GLSL 数组结构按值复制版和固定 32 槽 private 数组版均保留原始证据，但不作为最终速度 baseline。完整组处理与合理的实际组容量选择都属于最终适配器；不会拿低效移植的高耗时制造加速比。

| 阶段 | native 记录 | 旧 Vulkan 记录 | 可比范围 |
| --- | --- | --- | --- |
| 初始化与场景准备 | `initialPreparationMs`，从 apply 到首次完成，排除 renderer 打开 | `initialPreparationMs`，包含 device/scene/pipeline 准备 | 边界不同，保留原值，不计算准备阶段加速比 |
| 光照核心 | 120 条 `NativeLightingDispatch` GPU interval | 120 条 `lighting_dispatch` GPU interval | 同输入、同输出、同算法；仍受空闲条件限制 |
| GPU 属性上传 | 单独 `NativeLightingUpload` scope | 静态输入在准备阶段一次上传，没有逐帧匹配 scope | 不把未测项填成零，不作比值 |
| CPU admission/提交 | `cpu.csv` 的 submit recording/admission wait | `cpu.csv` 的 submit recording/completion wait | 名称和等待边界不同，不能直接相减组成总耗时 |
| 原生 packet 交接 | CPU acquire/未导入 packet release 有单独样本 | 适配器没有外部消费者 packet | 没有匹配 GPU handoff interval，未完成端到端交接成本对照 |
| GL import/消费者读取 | 未运行 | 未运行 | 未测 |
| 全分辨率引导重建/合成 | 未运行 | 未运行 | 未测 |

图像 readback 发生在全部计时帧结束后。上述未测的交接、GL 和重建工作没有被包含到 dispatch，也没有按估算值补入总时间。

单独 native producer 历史 `run.json` 中的 `baselineStatus=NOT_RUN` 表示该单端进程不执行旧端，不能用来覆盖已成对运行的对照结果。是否存在真实旧端、图像是否通过以及环境能否支撑速度比，应读两目录和 `native-specialized/comparison.json`。

目前可以从代码确认的工作量差别如下；它们提供排查方向，不是已经用独立 GPU interval 证实的耗时归因。

- [原生接收面读取](../../renderer-rtxpt/src/main/cpp/RtrNativeLighting.hlsl) 的 `RtrLoad` 支持实例变换、索引/顶点读取、插值法线与 UV、material/texture/vertex color 相乘、粗糙度/金属度/透射、角色、基线坐标及 lookup。编译器可能裁掉部分未使用字段，但不能把源码中的所有通用分支都视为零成本。
- [旧端适配器](../../tools/shaders/native-lighting-reference.comp) 的 `loadReceiver` 使用 fixture 已上传的四个 identity 四边形位置和材质色，直接读取固定 0.25/0.75 基线。它复现此 fixture 的正确数学结果，却不承担任意纹理材质和任意实例属性的完整读取成本。因此这轮能对照固定算法，不能把差距全部归结为 D3D 与 Vulkan 本身。
- native 另有独立 `TLAS Update` interval，当前 P50 为 0.046464 ms；旧端静态 fixture 在准备完成后复用 TLAS。这个额外工作确实存在，但量级不足以解释 dispatch 的主要差距，不能混入或拿来解释十毫秒级差值。
- 两端均按实际 group mask 选择容量。新 native 与原 native 的双信号 bit-exact，但受干扰的 P50 只从 15.047744 变为 14.888096 ms；目前不能断言 native 的主要问题就是分组数组，也不能据此宣称已显著优化。
- 测前 GPU 占用和 graphics clock 不同，timestamp interval 会包含调度和抢占影响。没有分离 receiver/material/source/shadow 的生产 GPU interval，也没有可比的空闲复测，就无法可靠把剩余差距分摊到某一个函数、驱动或 API。

重新生成对照时，提供明确的旧 core JAR 路径和包含测试依赖的 classpath：

```powershell
./tools/Invoke-LightingBackendComparison.ps1 `
  -LegacyCoreJar $legacyCoreJar `
  -ClassPath $validationClassPath
```

`-CompileOnly` 不占用 GPU。`-LegacyOnly` 用于只运行尚未验证或受改动影响的旧端；native 已通过的独立证据可以直接保留。`-CompareOnly` 只重新分析现有文件。所有 Java/Javac 调用使用 10 GiB bounded job。最终分布、图像误差、环境和证据文件哈希见 [数据记录](data/lighting-backend-comparison.json)。这些 dispatch 数据不包含 GL import、消费者重建、应用其他绘制或整个游戏 FPS。

`-GeneralGroups` 显式强制 capacity 32，用于分组存储的诊断对照；普通运行按实际 source 声明选择容量。
