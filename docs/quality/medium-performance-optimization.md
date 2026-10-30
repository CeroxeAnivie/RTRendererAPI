# 介质渲染性能优化规范

本规范用于持续优化内置介质渲染路径，并作为后续性能工作的交接记录。优化由单一执行线程顺序推进，任何性能结论都绑定到固定场景、画质、GPU、驱动和制品。

## 目标场景与优化终点

目标硬件为本机 NVIDIA RTX 5080 Laptop GPU。测量分辨率为 1920×1080，1 SPP，原生渲染；关闭帧生成、帧重建、时域累积和降噪。FPS 指 GPU 完成的原生帧，不计生成帧，不用提交次数或屏幕刷新率替代。

| 场景等级 | 固定工作负载 | 优秀目标 | 单帧预算 |
| --- | --- | ---: | ---: |
| 默认 | PBR 均匀直接散射，默认灯光和场景 | 稳定 120 FPS | 8.33 ms |
| 扩展介质 | 密度网格或多次散射；分别记录，不混成一个结果 | 稳定 60 FPS | 16.67 ms |
| 焦散组合 | 表面/体积光子焦散与介质的具体预设 | 独立确定 | 按冻结后的场景单独设定 |

这些数值是优化目标，不是当前性能声明。所有场景必须使用相同输出格式、相机、采样和积分预算做前后对照；任何工作负载参数变化都要另开结果行。

## 对外汇报口径

对外主表固定输出：阶段排名、`ms/帧`、每 1 秒墙钟内的阶段累计 `ms`、占该秒的百分比，以及同场景前后降幅。所有展示延迟统一用 ms。ms/帧用于判断每帧工作是否真正减少；每秒累计 ms 与占比用于展示时间去向；关闭诊断后的原生完成 FPS 用于最终验收。

以同一个完成帧观测窗口为准，设该窗口完成 N 帧、墙钟 T ms、某阶段实测累计 S ms：

- `ms/帧 = S / N`。
- `每秒累计 ms = S / T × 1000`，`占一秒百分比 = S / T × 100`。
- 表示长窗口归一到 1 秒时明确说明，不能声称采集了一个实际的一秒时间片。不得把某轮 GPU 阶段时间乘以另一轮关闭诊断的 FPS。
- 只对互不重叠的叶子工作区间求和。父阶段、CPU/GPU 同步等待、跨 API handoff 墙钟单独列出，不能与其包住的 GPU 工作重复相加。并行执行路径须对时间区间取并集后才能解释为独占墙钟占比。
- 如果做“墙钟减已记录 GPU 工作”的差额，只能标成“未归因差额”，不能把它说成独立测出的阶段、CPU 计算或 GPU 空闲。各阶段每秒 ms 也不是硬件 GPU 利用率读数。
- 阶段每秒 ms 会随 FPS 改变，因此前后收益首先比较 ms/帧与原生 FPS，不能只凭每秒占比的涨跌判断退化或提升。

抽样 shader 路径计时、路径内部占比和互斥阶段求和全部保留，作为内部热点拆解证据；不能将它作为整帧主排名，也不能用路径内部占比直接分摊一秒墙钟。下文历史抽样表属于这一内部口径。

## 首轮全阶段观测

首次 GPU 基线前，先按真实执行路径列出所有同步边界和生产阶段。至少记录下表中的阶段；字段不存在的路径写 `not-applicable`，不能省略后让总时间看起来更短。时间采集开启时只写入预分配数值槽或 GPU timestamp/query，不在逐帧热路径打印日志、不分配逐事件对象；结束后批量输出 JSON 与 CSV。

| 阶段组 | 必须拆出的阶段 | 主要计时口径 | 排名时如何使用 |
| --- | --- | --- | --- |
| 请求与 admission | 请求构造、trySubmit、容量拒绝/重试、extent/feature 协商 | CPU 单调时钟；记录重试数 | 作为端到端 CPU 成本，不和下游 inclusive 计时相加 |
| 帧准备 | 场景路由、介质计划/资源预算、帧图元与 TLAS、uniform/descriptor、temporal 输入准备 | 分阶段 CPU elapsed；独立记录分配/增长字节 | 区分固定每帧开销与仅首次/扩容成本 |
| 散射贡献生产 | Vulkan 几何/主射线查询、Vulkan 灯光/可见性查询、CUDA 介质积分 kernel、tile 数与每 tile 工作量 | Vulkan timestamp capture、CUDA event；按 tile 和整帧汇总 | GPU 阶段可并行或相邻；分别报告，不把 GPU 阶段简单累加到墙钟 |
| PBR 介质解析 | Vulkan packet trace、scene/header upload、CUDA resolve kernel、统计/错误校验 | Vulkan timestamps、CUDA event、上传字节/路径数 | 区分传输、trace 与 kernel；场景数据未变化时记录实际上传量 |
| 跨 API 交接 | Vulkan ownership release/submit、CUDA semaphore wait/launch/signal、Vulkan acquire/submit/fence wait | 每次边界的 CPU 墙钟；CUDA event 与 Vulkan timestamp 分列 | 等待时间是关键路径候选；标清其包住的 GPU 工作，避免重复计入 |
| 最终着色与输出 | BLAS/TLAS 更新、surface trace/shading、medium 合成、输出变换/resolve/blit、present | Vulkan GPU timestamps；无对应操作时标记不适用 | 记录完整输出，不以 trace-only 代替完整帧 |
| 完成与消费 | fence/query 等待、帧轮询、CPU readback/copy、帧 lease/presenter 消费 | CPU elapsed、GPU timestamp、字节数；分别记录 readback 开关 | 原生完成吞吐与显示呈现分开，CPU readback 基线不可混入无回读运行 |
| 首帧/非稳态 | renderer open、shader/module 编译、首次分配、首次 scene convergence、首帧完成 | 独立 wall-clock；不得塞进稳态均值 | 单独报告启动和首次使用延迟 |

每个阶段至少输出 `count`、总耗时、均值、中位数、P95、最大值和失败/丢样数；按每帧归一化，GPU capture 还须输出 query 可用率。记录帧时间样本或足以重算分位数的直方图/原始批次数据。阶段表需给出排序、占整帧关键路径比例、计时范围、是否重叠和证据来源。

### 计时可信度

- CPU 墙钟用于 host 工作和阻塞等待；函数调用耗时若包含 GPU fence wait，必须标成 inclusive，不能说成 CPU 计算。
- Vulkan GPU 时间来自同一 queue 上的 timestamp query；CUDA kernel 时间来自 CUDA event。host enqueue 到返回的时间不是 GPU kernel 时间。
- Vulkan/CUDA 之间会有交叉等待。GPU interval、host wait 和外层 exchange wall 可能重叠；报告保留时间线关系，不把三者相加成一帧。
- 采样器应预分配 query 和 CPU 统计结构，满载时丢诊断样本而不等待。诊断开关默认关闭，稳定 benchmark 再跑关闭插桩的对照。
- 同一候选至少采集同制品 instrumentation-off 基线和 instrumentation-on profile，记录开销和帧时分布。用户允许观测开销在 **10% 以内**；已达标时不为继续压低观测开销反复返工。定位时开启观测，最终验收关闭全部诊断；超过 10% 再减少采样频率或改为轮换采样。
- 先完成一轮覆盖全链路的分阶段计时，再按证据深入。不得在看到热点后才发现首轮漏掉主要阶段或同步边界。

## 单瓶颈优化闭环

1. 冻结工作负载，保存源码提交、制品哈希、GPU 型号/功耗档、驱动、Java、温度/频率状态、输出设置和原始样本。
   benchmark 在预热及计时窗口之前从实际 `RenderFrameRequest` 导出 `*-workload.json`，包含 maxEvents、opticalSamples、scatteringSteps、lightSamples、距离/cutoff、bounce 与接口采样方式。对外参数以此为准，不按构造器位置手工猜测。运行工具只收集当前 JVM 新写出的元数据，旧冻结 benchmark 没有该文件时不借用上次结果。
2. 先按架构画清数据流、队列、内存所有权、同步点、tile/批次和每帧临时数据，再根据全阶段表选端到端关键路径上最大的独占延迟。
3. 每轮只改一个已证实的瓶颈。若热点是 inclusive 阶段，先用首轮已准备的计时层次拆分其组成；不得先改旁支或同时做多个猜测性微优化。
   **2026-10-31 用户限定特例：本次允许处理 PBR 完成 fence 等待内实测排名前 3 的事件，不扩展到其他阶段或后续轮次。是否追加拆解由已有数据能否指导修改决定；已有测量足够时直接修改、验证，不为拆而拆。每项改动仍独立保存对照、归因收益，负优化立即撤回。**
   **同日最新范围：同时处理当前总榜第一、第二内部实测前三项，直到这两项退出总榜前二，或关闭诊断达到 120 FPS。各候选仍单独冻结、验收、保留或撤回；不能仅移动等待位置或改事件名称来满足排名条件。**
   **用户随后扩大范围：当前总榜前三项均纳入，各自处理内部实测前三名。当前第二、第三项共同等待同一条散射链，必须映射回实际 GPU 工作归因，不能把同一项 GPU 工作或收益算两次；各候选仍单独验收。**
   **用户追加里程碑：关闭诊断达到 100+ FPS 后，先插入修复未开启降噪却过度模糊的问题；清晰度与正确性验收通过后，再继续 120 FPS 性能目标。能力闭合由一个独立子代理推进，GPU 验收及重型编译与性能测量串行安排。**
   遇到方案、正确性或架构疑点，先查成熟开源实现，再决定改法；不能反复在本项目里猜到用户提醒。记录参考项目、固定 commit、文件、许可证、采用的逻辑和本项目必须保留的语义。优先检查架构和数据流能否消除热点工作，避免只修零散单点。
4. 同一候选跑正确性、场景 HDR 图像对照、帧时间与吞吐。确定性小收益可保留；噪声范围内的“提升”不算收益；确认退化则立即撤回该轮改动。
5. 更新延迟排名和架构说明。显著收益应对应最大热点下降；如果总帧时间没按预期变化，检查重叠、资源竞争和新的关键路径再继续。
6. 每个闭环保留可复现命令、原始输出和结论；已验证的改动按当前仓库发布/分支流程及时推送 GitHub，不把未完成验收的候选当作优化成果。

每次优化成功，立即给出前后对照表：原热点延迟、占比和排名、互斥阶段求和、关闭诊断后的原生完成吞吐/帧时、正确性结果、测前 GPU 占用和提交状态。收益用明确的百分比表示，并给出最新延迟排序；不能只口头说“有明显提升”。

发现根因、重大进展或实测显著变化，立即报告。每次 GPU 测量开始前记录 GPU 0 占用；超过 5% 时绝对延迟/FPS 只作参考，继续按相对排名定位和优化，不因用户其他 GPU 任务干扰而停工。观测开销以 10% 为接受线，最终性能验收必须关闭诊断。允许一个子代理仅补齐未归因差额的观测、验证与实测，完成这一件事后停止，不参与主线渲染优化。

**严禁只看代码不改代码。** 源码阅读只能用来确定下一项可验证动作；每轮分析都必须落到新增/调整测量、正确性检查或一个有证据的代码改动，并在同一轮拿到结果。若当前缺少关键数据，先补齐最小且低扰动的观测并运行它，不得以继续阅读、口头推测或整理计划代替推进；只有外部条件确实阻断时才说明阻断点。

不能为了 FPS 改低 SPP、散射步数、路径事件预算、介质容量或图像质量。每项降级若成为产品选项，必须显式命名、单独验收并保留原质量档位。

## 清晰度与正确性

关闭降噪时的原生介质图像必须单独验收。锁定随机种子和场景，保存线性 HDR 原图；检查渲染分辨率、像素覆盖、光线方向/焦点映射、shader 输出写入、累积/归一化、曝光与显示变换。以解析或高样本参考比较边缘、细节和亮度误差；不允许用显示端锐化或降噪掩盖采样/坐标/合成错误。

涉及输运数学、采样或滤波时，先调查成熟开源 renderer 的对应路径，记录项目、版本/commit、源文件、算法边界和许可证。移植要适配本 API 的 RGB、密度、相函数、资源生命周期和 Vulkan/CUDA ownership 语义，并以相同 HDR 参考验证；不能按类名或结果截图照搬，也不能把不同积分器/画质设置的数据直接比较。

## 本轮记录

| 项目 | 状态 |
| --- | --- |
| 目标场景 | 默认 PBR 均匀直接散射；必须使用展厅实际场景和相机，不以简化合成场景代替 |
| 目标 | RTX 5080 Laptop，1080p，1 SPP，原生 120 FPS；FG、重建、时域累积、降噪关闭 |
| 测量源码 | `fffaf55`；报告工具与第一轮全路径分阶段计时已推送到 GitHub |
| 硬件/软件 | NVIDIA GeForce RTX 5080 Laptop GPU，驱动 616.92，Oracle GraalVM Java 25.0.5；实测时间 2026-10-30 |
| 固定场景 | `pbr-direct-uniform`，1920×1080，1 SPP，caustics/animation/FG/reconstruction/temporal/denoising/CPU readback 关闭；非窗口 presenter 跑分 |
| 测量长度 | 每组 90 帧预热，3 轮各 120 个 GPU 完成帧；每轮单独记录，不混合首帧 |
| 关闭插桩基线 | 33.68 / 35.90 / 35.54 FPS；合计 360 帧 / 10.281 s = **35.02 FPS，28.56 ms/帧** |
| 细分 profile 扰动 | 33.53 / 30.29 / 32.38 FPS；合计 360 帧 / 11.248 s = **32.01 FPS，31.24 ms/帧**，比关闭插桩慢约 9.4%；细分值用于排序，不作为交付 FPS |
| 当前第一名 | 相机散射灯光/可见性 raygen，平均 **15.70 ms/帧**；该行仍是复合阶段，下一步必须拆 shader 内部子阶段后再改代码 |
| 产物位置 | 原始诊断数据写入忽略的 `build/reports/medium-performance/`；仓库只保留稳定规范与最终摘要 |

### 首轮实测排序

固定场景、画质和计时方式见上表。GPU 子阶段按三轮中各自每帧均值排序；范围为三轮均值的最小值到最大值，不是逐帧 P95。主阶段与子阶段包含关系单独列出，不重复纳入叶子合计。

| 排名 | 可相加的 GPU 工作片段 | 均值 ms/帧 | 三轮均值范围 ms/帧 | 每帧样本数 |
| ---: | --- | ---: | ---: | ---: |
| 1 | 相机散射灯光/可见性 raygen | **15.696** | 13.794–17.400 | 3 |
| 2 | PBR 灯光 packet 查询 | **2.523** | 2.392–2.602 | 3 |
| 3 | CUDA PBR resolve | **2.063** | 1.875–2.233 | 3 |
| 4 | PBR primary packet 查询 | **1.731** | 1.634–1.780 | 3 |
| 5 | 最终帧 GPU 输出 | **0.978** | 0.890–1.102 | 1 |
| 6 | CUDA 相机散射 resolve | **0.720** | 0.675–0.746 | 3 |
| 7 | PBR radiance 恢复拷贝 | **0.135** | 0.118–0.158 | 3 |
|  | **GPU 工作片段算术和** | **23.846** | 各行均值之和 |  |

以下阶段是上面 GPU 工作的父计时或同步墙钟，不能加进 23.846 ms：CUDA/Vulkan shared exchange 27.18 ms/帧、CUDA 阶段墙钟 24.55 ms/帧、Vulkan producer GPU 合计 20.13 ms/帧、相机散射准备 18.79 ms/帧、PBR 准备 8.73 ms/帧、consumer fence 等待 1.02 ms/帧。它们用于解释关键路径，不代表额外独立工作。

首轮底层 profile 只保存了各阶段累计耗时、调用次数和三轮均值范围，没有逐帧分布，故不能从这批数据声称中位数/P95。相机散射 raygen 的 15.696 ms 目前仍包含主光线命中、灯光候选筛选、散射区间采样、太阳/局部灯可见性查询、相函数和结果写入；**在这些子阶段测完并排序前，不开始针对其中任一项做性能改动。**

### 相机散射 raygen 子阶段观测

子阶段计时只在显式 `shaderProfile=true` 时编译独立诊断 pipeline。每 65,536 条全帧路径选一个候选，按帧轮换 bucket 和 bucket 内采样位置，每 8 个候选测一个；仅包含实际抽样路径的 tile 使用诊断 pipeline，其余 tile 使用生产 pipeline。设备时钟和缓冲写入只发生在选中的路径。GPU 诊断槽为每个候选预留 12 个 16-byte 计数槽，缓冲计入 CUDA/Vulkan 共享 resident 预算。诊断缓冲整帧清零一次，并在末 tile 随 CUDA 结果一次读回；它不启用全局 `OpticalFrameTimings`，不改分辨率、SPP、步数或路径数。对局部灯的内部计时会暂停外层计时，故阶段可排序求和而不重复计算；CUDA 读回时间单独留在端到端帧时中。仍须用同场景的 instrumentation-off 与 shader-only 两组完成帧时间对照，实测扰动，不能把“低频采样”当成零成本证明。

固定阶段名为：主射线与命中/缓存读取、灯光范围与权重、候选蓄水池更新、散射区间采样、太阳相函数、遮挡射线可见性、Beer 介质衰减、局部灯几何与衰减、局部灯相函数与贡献、体积焦散、输出地址计算、源包写回。子阶段 JSON 按每条抽样路径的累计纳秒排序，并列调用数、每次调用均值和阶段占比；尾部给互斥阶段的求和。样本不足或有漏样必须在表里显式呈现。

2026-10-30 补充遮挡射线内部计时，扩为 15 个槽：遍历/调度残差、any-hit、closest-hit、有序透射回退分开计量。三轮第一名均为不透明阴影查询残差，0.0636–0.0662 ms/抽样路径，占子阶段和 39.7–40.4%；any-hit 和透射回退均为 0，closest-hit 仅约 0.000018–0.000019 ms/路径。故本轮仅优化阴影查询的遍历/调度成本。采样延迟不等于整帧耗时，GPU 竞争时只作为相对排序依据。

### 不透明阴影查询参考

已核对 NVIDIA `nvpro-samples/vk_raytracing_tutorial_KHR`，commit `7c7e1b379132e67f82628f909278e3bc592ac1fe`，Apache-2.0：[`ray_tracing__simple/shaders/raytrace.rchit`](https://github.com/nvpro-samples/vk_raytracing_tutorial_KHR/blob/7c7e1b379132e67f82628f909278e3bc592ac1fe/ray_tracing__simple/shaders/raytrace.rchit) 与同目录 `raytraceShadow.rmiss`。其阴影查询预设遮挡，miss 解除遮挡，使用 `TerminateOnFirstHit | Opaque | SkipClosestHitShader`，不运行命中着色。本项目的候选将复用现有轻量 visibility payload/miss；必须额外证明实际引用的持久和帧局部实例都完全不透明、双面、投影阴影且没有介质边界。原有材质不透明标志不足以作此证明，通用路径仍保留 any-hit 和透射语义。当前只是待验证候选，不能据此声称已有收益。

该 trace-flags 候选已完成闭环并撤回：8 帧 160×90 原始 RGBA16F 逐字节一致，Vulkan 验证 0 警告/0 错误，但三轮阴影残差/其余阶段延迟比从 0.6395–0.6528 上升为 0.7147–0.7844；关闭诊断也没有稳定吞吐收益。不能因为成熟示例使用该模式，就假定迁入本项目一定更快。

以下为该轮对照组的完整互斥叶子排名；90 帧预热，3×120 帧，单位 ms/实际抽样路径。测前 GPU 占用为 38%，因此只按相对排名使用。原始三轮 JSON 和已撤回候选见 [scattering-shadow-traversal-20261030.json](data/scattering-shadow-traversal-20261030.json)。

| 排名 | 阶段 | 三轮平均 ms/路径 | 占比 |
| ---: | --- | ---: | ---: |
| 1 | 不透明阴影射线遍历与调度残差 | 0.060667 | 39.28% |
| 2 | 局部灯几何与衰减 | 0.025989 | 16.83% |
| 3 | 灯光范围与权重 | 0.020610 | 13.34% |
| 4 | 主射线与命中/缓存读取 | 0.018591 | 12.04% |
| 5 | Beer 介质衰减 | 0.013072 | 8.46% |
| 6 | 散射区间采样与位置 | 0.006172 | 4.00% |
| 7 | 候选蓄水池更新 | 0.003031 | 1.96% |
| 8 | 体积焦散检查 | 0.002500 | 1.62% |
| 9 | 太阳相函数 | 0.001258 | 0.81% |
| 10 | 局部灯相函数与贡献 | 0.001161 | 0.75% |
| 11 | 源包写回 | 0.000780 | 0.50% |
| 12 | 输出地址计算 | 0.000604 | 0.39% |
| 13 | closest-hit 着色 | 0.000019 | 0.01% |
| 14 | 透射阴影回退 | 0 | 0% |
| 15 | any-hit 着色 | 0 | 0% |
| | **互斥阶段延迟合计** | **0.154452** | **100%** |

下一候选对照同一 NVIDIA commit 的 [`ray_tracing_rayquery/shaders/frag_shader.frag`](https://github.com/nvpro-samples/vk_raytracing_tutorial_KHR/blob/7c7e1b379132e67f82628f909278e3bc592ac1fe/ray_tracing_rayquery/shaders/frag_shader.frag)：`rayQueryInitializeEXT` → `rayQueryProceedEXT` → 检查 committed intersection，在当前 shader 内完成阴影遍历，省去 payload 和 hit/miss dispatch。仍须严格证明可以跳过 any-hit；先实验验证，再决定是否作为可选硬件能力整合。

复现命令：

```powershell
.\gradlew.bat :demos:hex-ball:mediumGalleryPerformanceBenchmark --offline --no-daemon --max-workers=1 --console=plain "-Dorg.gradle.jvmargs=-Xmx512m --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -Duser.country=CN -Duser.language=zh -XX:-UseJVMCICompiler" "-PgalleryPerformanceArgs=--warmup=90 --frames=120 --rounds=3 --profile=false"
.\gradlew.bat :demos:hex-ball:mediumGalleryPerformanceBenchmark --offline --no-daemon --max-workers=1 --console=plain "-Dorg.gradle.jvmargs=-Xmx512m --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -Duser.country=CN -Duser.language=zh -XX:-UseJVMCICompiler" "-PgalleryPerformanceArgs=--warmup=90 --frames=120 --rounds=3 --profile=true"
.\gradlew.bat :demos:hex-ball:mediumGalleryPerformanceBenchmark --offline --no-daemon --max-workers=1 --console=plain "-Dorg.gradle.jvmargs=-Xmx512m --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -Duser.country=CN -Duser.language=zh -XX:-UseJVMCICompiler" "-PgalleryPerformanceArgs=--warmup=90 --frames=120 --rounds=3 --profile=false --shaderProfile=true"
```

### 阴影 inline ray query 已验证保留

同一 NVIDIA commit 的 ray query 遍历逻辑已经适配本项目。仅在所有可见持久/帧局部实例都满足完全不透明、双面、投影阴影且没有介质边界时启用；不满足证明或设备不支持 `VK_KHR_ray_query` 时保留通用路径。生产与诊断共用同一实现，预编译资源包含 query 变体，诊断默认关闭。

GPU 空闲后重新测试，两个制品测前 GPU 0 占用均为 0%，每组 90 帧预热、3×120 个完成帧：

| 关闭诊断后的整帧指标 | traceRay 对照 | inline query | 变化 |
| --- | ---: | ---: | ---: |
| 完成吞吐 FPS | 37.386 | 56.145 | +50.2% |
| 完成帧墙钟 ms/帧 | 26.748 | 17.811 | −33.4% |

正确性：三轮阴影查询 parity 共 23,083 次，0 mismatch；8×160×90 原始 HDR 对照的 345,600 个 RGB 通道中 23 个通道变化，最大 1 个 half-float ULP。正式路径 Vulkan validation 0 warning、0 error，生产散射 13 项独立 HDR 验收、shader 编译与 surface index 生命周期检查均通过。

正式 query 三轮完整互斥子阶段表如下，单位 ms/实际抽样路径；这是 shader 采样延迟，不是可相加的整帧时间。阴影残差从第一降至第四，下一轮只修局部灯几何与衰减。旧 traceRay profile 有其他 GPU 任务竞争，其绝对值不用于当前硬件能力判断。

| 排名 | 阶段 | 三轮均值 ms/路径 | 占比 |
| ---: | --- | ---: | ---: |
| 1 | 局部灯几何与衰减 | 0.020267 | 27.21% |
| 2 | 灯光范围与权重 | 0.016664 | 22.38% |
| 3 | 主射线与命中/缓存读取 | 0.013432 | 18.04% |
| 4 | 不透明阴影查询残差 | 0.012631 | 16.96% |
| 5 | 候选蓄水池更新 | 0.002907 | 3.90% |
| 6 | 散射区间采样与位置 | 0.002661 | 3.57% |
| 7 | Beer 介质衰减 | 0.001739 | 2.33% |
| 8 | 体积焦散检查 | 0.001458 | 1.96% |
| 9 | 局部灯相函数与贡献 | 0.000993 | 1.33% |
| 10 | 太阳相函数 | 0.000798 | 1.07% |
| 11 | 源包写回 | 0.000614 | 0.82% |
| 12 | 输出地址计算 | 0.000313 | 0.42% |
| 13–15 | 透射回退、any-hit、closest-hit | 0 | 0% |
| | **互斥延迟求和** | **0.074476** | **100%** |

原始轮次、正确性结果和测前 GPU 状态记录在 [scattering-inline-query-20261030.json](data/scattering-inline-query-20261030.json)。

### 局部灯坐标解码缓存已验证保留

当前第一名是局部灯几何与衰减。本轮只修改它的灯位读取：将原 shader 每个采样点执行的 double-word → float 解码，提前到灯光记录上传时执行。复用 24-word 记录中原本保留的 17–19 槽，保留 64 位原始坐标、记录大小、上传计划、距离/衰减公式和随机选灯语义；灯光范围与权重仍使用原路径。CPU 解码按 `gsFiniteDoubleAsFloat` 的 float 运算顺序执行，不用普通 double cast 替代。

参考 NVIDIA Falcor，commit `759aad033ff610fb0d82c74f7e0a508d0096d5f2`，BSD-3-Clause：[`LightData.slang`](https://github.com/NVIDIAGameWorks/Falcor/blob/759aad033ff610fb0d82c74f7e0a508d0096d5f2/Source/Falcor/Scene/Lights/LightData.slang) 和同目录 [`LightHelpers.slang`](https://github.com/NVIDIAGameWorks/Falcor/blob/759aad033ff610fb0d82c74f7e0a508d0096d5f2/Source/Falcor/Scene/Lights/LightHelpers.slang)。参考其提前准备 float 灯位、局部灯采样直接读取的位置数据流，本项目独立实现缓存并保持自身有限 range、双锥角和阴影语义。

前后各 90 帧预热、3×120 个完成帧；shader profile 测前 GPU 占用均为 0%，均实际记录 1,409 条路径，各有 17 条预期样本未记录。以下按优化后的三轮平均排序，单位 ms/实际抽样路径；降幅负号表示增加，不能将这些采样延迟当成整帧耗时。

| 新排名 | 阶段 | 优化前 ms | 优化后 ms | 降幅 | 新占比 | 原排名 |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 灯光范围与权重 | 0.020412 | 0.020458 | −0.2% | 25.30% | 2 |
| 2 | 不透明阴影查询残差 | 0.015401 | 0.015971 | −3.7% | 19.75% | 4 |
| 3 | 主射线与命中/缓存读取 | 0.015552 | 0.015831 | −1.8% | 19.58% | 3 |
| 4 | 局部灯几何与衰减 | 0.024880 | 0.014411 | **42.1%** | 17.82% | 1 |
| 5 | 候选蓄水池更新 | 0.003576 | 0.003569 | 0.2% | 4.41% | 5 |
| 6 | 散射区间采样与位置 | 0.003253 | 0.003339 | −2.6% | 4.13% | 6 |
| 7 | Beer 介质衰减 | 0.002130 | 0.002134 | −0.2% | 2.64% | 7 |
| 8 | 体积焦散检查 | 0.001759 | 0.001815 | −3.2% | 2.24% | 8 |
| 9 | 局部灯相函数与贡献 | 0.001213 | 0.001207 | 0.5% | 1.49% | 9 |
| 10 | 太阳相函数 | 0.000980 | 0.000984 | −0.4% | 1.22% | 10 |
| 11 | 源包写回 | 0.000757 | 0.000755 | 0.3% | 0.93% | 11 |
| 12 | 输出地址计算 | 0.000380 | 0.000393 | −3.3% | 0.49% | 12 |
| 13 | 透射阴影回退 | 0 | 0 | — | 0% | 13 |
| 14 | any-hit | 0 | 0 | — | 0% | 14 |
| 15 | closest-hit | 0 | 0 | — | 0% | 15 |
| | **互斥延迟求和** | **0.090295** | **0.080867** | **10.4%** | **100%** | |

热点/其他阶段延迟比三轮从 0.3811/0.3819/0.3776 降至 0.2167/0.2188/0.2149。关闭 shader 时钟、仅保留已有 GPU timestamp 的正式相机散射灯光阶段，从 6.453/5.952/4.910 ms 降至 5.806/5.328/4.354 ms，平均 **5.772 → 5.163 ms，下降 10.6%**，三轮均改善。

关闭全部诊断的第一组整帧吞吐为 54.945 → 55.012 FPS，近乎不变；其中对照测前 GPU 占用 7%，绝对数值只作参考。反序复测时两组测前均为 0%，合计吞吐 **52.939 → 55.976 FPS**，帧耗时 **18.890 → 17.865 ms**，该组约 +5.7%。因此保留已证实的热点与 GPU 阶段收益，不把单组 +5.7% 声称为稳定整帧提升。

正确性：8 帧 HDR 全字节一致；14 项生产输运 HDR 检查通过，新增非整数 double 点灯/聚光灯及灯光替换检查；ABI 数值与 shader 契约检查通过；234 组预编译 shader 生成成功；Vulkan validation 0 warning、0 error。原始三轮、制品 SHA-256、测前 GPU 状态和完整排名见 [scattering-light-position-cache-20261030.json](data/scattering-light-position-cache-20261030.json)。下一轮只修新的第一名：灯光范围与权重。

### 当前对外主表：归一到 1 秒墙钟

使用同一次正式 GPU timestamp 计时组的 360 个完成帧：墙钟 6350.4642 ms，完成吞吐 56.688769 FPS，平均 17.640178 ms/帧。shader 路径时钟诊断关闭，阶段 timestamp 开启，测前 GPU 占用 0%。这里从该观测窗口归一到 1 秒，没有混用另一次关闭全部诊断运行的 FPS，也没有重新运行 benchmark。

| 排名 | 已记录的 GPU 叶子阶段 | ms/帧 | 每秒累计 ms | 占这一秒 |
| ---: | --- | ---: | ---: | ---: |
| 1 | 相机散射灯光与可见性 | 5.163 | 292.66 | 29.27% |
| 2 | PBR 灯光 packet 查询 | 2.689 | 152.45 | 15.24% |
| 3 | CUDA PBR 解析 | 2.362 | 133.92 | 13.39% |
| 4 | PBR 主射线 packet 查询 | 1.816 | 102.93 | 10.29% |
| 5 | 最终帧 GPU 输出 | 1.189 | 67.39 | 6.74% |
| 6 | CUDA 相机散射积分 | 0.728 | 41.29 | 4.13% |
| 7 | PBR radiance 恢复拷贝 | 0.171 | 9.72 | 0.97% |
| | **已记录 GPU 工作合计** | **14.119** | **800.36** | **80.04%** |
| — | 未归因差额（计算值，未独立计时） | 3.522 | 199.64 | 19.96% |
| | **墙钟合计** | **17.640** | **1000.00** | **100.00%** |

合计使用未舍入数据。当前默认均匀直接散射路径的生产/CUDA 交接是阻塞串行的，表中只纳入叶子 GPU 工作，不再加上其父 prepare/shared-exchange/host-wait 时间。这是阶段累计工作量相对墙钟的口径，不是硬件利用率或完整关键路径时间线。未归因差额尚未单独拆分，不能直接称为 GPU 空闲，也不能全归给 CPU。对外第一名是 292.66 ms/秒的相机散射灯光阶段；内部再用抽样路径表定位该阶段中的灯光范围与权重。

同轮前后窗口、累计阶段时间、公式与求和检查记录在 [medium-wall-second-budget-20261030.json](data/medium-wall-second-budget-20261030.json)。

### 消除散射区间内重复的选灯权重计算

本轮仍只修第一名相机散射灯光阶段中的第一名“灯光范围与权重”。根因是 proposal 使用相机起点、整段方向和距离，与区间内的积分位置无关，却在每个散射区间重新计算；候选数量不超过采样预算、已经穷举全部候选时还计算了最终不会使用的权重。本轮将每个选中光源的 proposal correction 提到积分循环之前，只准备一次；穷举路径直接使用 1。随机数、候选选择、积分点、光源贡献累计顺序、散射步数与画质预算保持一致，零预算的枚举路径保持原实现。

参考 NVIDIA Falcor，commit `759aad033ff610fb0d82c74f7e0a508d0096d5f2`，BSD-3-Clause，文件 [`Source/Falcor/Utils/Sampling/AliasTable.slang`](https://github.com/NVIDIAGameWorks/Falcor/blob/759aad033ff610fb0d82c74f7e0a508d0096d5f2/Source/Falcor/Utils/Sampling/AliasTable.slang)。采用预先准备 proposal 数据、重复采样时复用的思路；本轮未替换蓄水池采样，也没有移植 alias-table 算法或外部代码。

对照和候选使用冻结的相同 runtime、benchmark classes 和运行参数，仅候选 GLSL 与其 16 个预编译 SPIR-V 不同。每组 90 帧预热、3×120 个完成帧，所有组测前 GPU 0 占用均为 0%。采样路径三轮各 473/463/473 条，预期但未记录各 4/9/4 条；前后样本数一致。范围与权重调用次数由每条实际抽样路径 19 次降至 3 次，内部延迟 **0.016823 → 0.001728 ms/路径，下降 89.7%**。

| 已验证指标 | 对照 | 候选 | 变化 |
| --- | ---: | ---: | ---: |
| 正式散射灯光 GPU，第一轮 ms/帧 | 4.651185 | 4.237992 | −8.88% |
| 正式散射灯光 GPU，第二轮 ms/帧 | 3.926459 | 3.545770 | −9.70% |
| 正式散射灯光 GPU，第三轮 ms/帧 | 3.507514 | 3.170746 | −9.60% |
| **正式散射灯光 GPU 均值 ms/帧** | **4.028386** | **3.651503** | **−9.36%** |
| 全部诊断关闭，完成吞吐 FPS | 51.423270 | 56.232297 | 本组 +9.35% |
| 全部诊断关闭，墙钟 ms/帧 | 19.446449 | 17.783374 | 本组 −8.55% |

本组关闭诊断的 FPS 是独立实测，不用于归一化下面的 GPU 阶段表。timestamp 组整帧为 50.933816 → 50.364975 FPS，其他未修改 GPU 阶段普遍上升，故不能把这组整体变化归因于候选。保留依据是被修改阶段三轮一致下降、重复计算次数确定减少、图像与独立参考通过；不将单组 +9.35% 宣称为跨运行稳定收益。

新的内部排序如下，延迟来自三轮真实 ticks/recorded paths 再求均值，单位 ms/采样路径。它用于拆解大头，不能当作 ms/帧。

| 新排名 | 阶段 | 对照 ms/路径 | 候选 ms/路径 | 原排名 |
| ---: | --- | ---: | ---: | ---: |
| 1 | 不透明阴影查询残差 | 0.013122 | 0.011892 | 2 |
| 2 | 主射线与命中/缓存读取 | 0.013084 | 0.011811 | 3 |
| 3 | 局部灯几何与衰减 | 0.011853 | 0.010873 | 4 |
| 4 | 候选蓄水池更新 | 0.002926 | 0.002954 | 5 |
| 5 | 散射区间采样与位置 | 0.002695 | 0.002346 | 6 |
| 6 | 灯光范围与权重 | 0.016823 | 0.001728 | 1 |
| 7 | Beer 介质衰减 | 0.001761 | 0.001423 | 7 |
| 8 | 体积焦散检查 | 0.001450 | 0.001201 | 8 |
| 9 | 局部灯相函数与贡献 | 0.000996 | 0.000845 | 9 |
| 10 | 太阳相函数 | 0.000805 | 0.000701 | 10 |
| 11 | 源包写回 | 0.000613 | 0.000597 | 11 |
| 12 | 输出地址计算 | 0.000320 | 0.000267 | 12 |
| 13–15 | 透射回退、any-hit、closest-hit | 0 | 0 | 13–15 |
| | **互斥采样延迟求和** | **0.066448** | **0.046638** | |

候选 timestamp 组的独立观测窗口：360 帧、7147.8244 ms，50.364975 FPS、19.855068 ms/帧。以下每秒值仅使用这个窗口：

| 排名 | GPU 叶子阶段 | ms/帧 | 每秒累计 ms | 占这一秒 |
| ---: | --- | ---: | ---: | ---: |
| 1 | 相机散射灯光与可见性 | 3.652 | 183.91 | 18.39% |
| 2 | PBR 灯光 packet 查询 | 2.440 | 122.87 | 12.29% |
| 3 | CUDA PBR 解析 | 2.100 | 105.77 | 10.58% |
| 4 | PBR 主射线 packet 查询 | 1.675 | 84.35 | 8.44% |
| 5 | 最终帧 GPU 输出 | 1.031 | 51.94 | 5.19% |
| 6 | CUDA 相机散射积分 | 0.688 | 34.67 | 3.47% |
| 7 | PBR radiance 恢复拷贝 | 0.144 | 7.27 | 0.73% |
| | **已记录 GPU 工作合计** | **11.730** | **590.78** | **59.08%** |
| — | 未归因差额（算术值） | 8.125 | 409.22 | 40.92% |
| | **墙钟合计** | **19.855** | **1000.00** | **100.00%** |

这一差额仍不能叫 GPU 空闲或 CPU 计算；独立子代理正在用嵌套 host 事件边界补齐整个墙钟，不能将其 host wait 再加到本表 GPU 工作上。下一步须结合完整墙钟表选关键路径，再拆大头；GPU 内部当前第一名是不透明阴影查询残差。

正确性：8×160×90 的 RGBA16F 全帧全字节一致，Vulkan validation 0 warning/0 error，14 项独立生产 HDR 对照全部通过，query/通用 raygen 的 16 个 SPIR-V 变体编译成功。原始轮次、制品 SHA-256、HDR 文件 SHA-256、完整内部排名与同窗口归一化结果见 [scattering-light-proposal-reuse-20261030.json](data/scattering-light-proposal-reuse-20261030.json)。

### 跨线程墙钟观测完成

原来只跟踪 benchmark 调用线程，未接通 camera/PBR manager 的专用线程，导致 prepare 的 self 占比很大。现由 `NATIVE_TASK_AWAIT` 显式携带父节点身份到 executor 包装器，worker 记录 `NATIVE_TASK_EXECUTION` 与 CUDA/Vulkan 内部边界；只适用于调用方在相同父作用域内立即等待 Future 的同步任务。Future 返回前恢复调用线程，原始事件保留实际 Java 线程 ID。未携带身份的其他线程不加入记录，不能按接近的时间戳猜父子关系。

参考 Perfetto，commit `8299e44d8586d4650cab0d7bc9d34c7de536e468`，Apache-2.0，[`include/perfetto/tracing/track_event_args.h`](https://github.com/google/perfetto/blob/8299e44d8586d4650cab0d7bc9d34c7de536e468/include/perfetto/tracing/track_event_args.h) 的 `Flow::ProcessScoped`/`TerminatingFlow`。采用显式身份连接线程 track 的思路，未复制外部代码；本项目额外验证被等待的 worker 区间严格包含在调用方父区间中。

观测默认关闭，使用预分配数值数组，不逐事件打印或分配对象；开启时每次 awaited handoff 有一个包装器。满槽只丢失细项并记录 dropped，不等待空槽。每轮结束后批量输出原始边界、父节点、线程、inclusive/self、count、P50/P95/max。`tools/AnalyzeMediumWallTimeline.py` 可独立重算原始区间、严格校验同父兄弟不重叠与包含关系，并输出互斥排名 JSON/CSV；不能把父 inclusive、子 self 和 GPU work 相加。

同制品三组各 90 帧预热、3×120 个完成帧，测前 GPU 0 均为 0%。制品 SHA-256：`3198a476694f2542eb50e32369413a4e91549aab8aa6536aa75228794ecd0641`。

| 观测模式 | 墙钟 ms/帧 | 完成 FPS | 与关闭观测的帧时变化 |
| --- | ---: | ---: | ---: |
| 全关 | 17.118159 | 58.417496 | — |
| 只开墙钟观测 | 16.356668 | 61.137148 | −4.45% |
| 墙钟与 GPU timestamp | 16.771744 | 59.624090 | −2.02% |

逐轮 wall+GPU 相对关闭观测的帧时变化 −4.140%/−2.930%/+1.734%，本组未测出超过 10% 的观测拖慢；负值属于运行波动，不是渲染优化收益。

以下新主表来自同一个 wall+GPU 窗口：360 帧、6037.828 ms。各行是互斥 host 边界时间，等待包含 GPU 工作。

| 排名 | 实测墙钟边界 | ms/帧 | 每秒累计 ms | 占这一秒 |
| ---: | --- | ---: | ---: | ---: |
| 1 | PBR 统计读回前 CUDA stream 同步 | 7.462 | 444.917 | 44.49% |
| 2 | 散射完成边界 CUDA stream 同步 | 4.893 | 291.740 | 29.17% |
| 3 | 散射统计读回前 CUDA stream 同步 | 0.948 | 56.534 | 5.65% |
| 4 | PBR Vulkan consumer fence 等待 | 0.543 | 32.368 | 3.24% |
| 5 | 散射 Vulkan consumer fence 等待 | 0.499 | 29.771 | 2.98% |
| 6 | benchmark park 等待 | 0.465 | 27.708 | 2.77% |
| 7 | PBR 读回调用自身，扣除子项 | 0.205 | 12.251 | 1.23% |
| 8 | 最终帧命令录制与提交 | 0.139 | 8.314 | 0.83% |
| 9 | PBR release 命令录制 | 0.134 | 8.017 | 0.80% |
| 10 | 散射 release 命令录制 | 0.105 | 6.236 | 0.62% |
| | 其余 76 项合计 | 1.378 | 82.143 | 8.21% |
| | **互斥墙钟求和** | **16.772** | **1000.000** | **100.00%** |

主表合并小项，完整明细保留，不为展示 86 行挤占阅读空间。三线程共 71,832 个事件、零丢样；独立 raw 重算、包含与兄弟不重叠校验通过。WINDOW self 仅 0.003003 ms/帧，是循环条件检查与计时边界之间的代码；camera/PBR prepare self 分别仅 0.007243/0.006568 ms/帧，不再把大块工作藏在“准备”下面。`NATIVE_TASK_AWAIT` self 是提交、排队与返回唤醒间隙，worker execution self 是 manager 在已观测子 API 之间的工作；这些 elapsed 都不冒称纯 CPU 时间。

同窗 GPU 叶子第一名仍是相机散射灯光与可见性 4.368425 ms/帧。所选 GPU 工作合计 13.353433 ms/帧，与墙钟差 3.418312 ms/帧仍是算术值；新的 host 表完整覆盖墙钟调用边界，并不把这一算术差强行唯一分配成 CPU 计算或 GPU idle。

完整运行对照、86 项分布与 GPU 重叠视图见 [medium-wall-attribution-20261031.json](data/medium-wall-attribution-20261031.json)，完整互斥排名见 [medium-wall-attribution-ranked-20261031.csv](data/medium-wall-attribution-ranked-20261031.csv)。构建与覆盖自测通过，子代理已完成并停止。主线完成手头阴影候选后，按用户指定处理 PBR 统计读回前 CUDA 同步；必须比较整个交接和关闭诊断 FPS，不能把等待转移到 fence 就声称收益。

复现工具：benchmark 增加 `--wallProfile=true`；`--profile=true` 同时采集 GPU timestamp，`--profile=false --shaderProfile=false --wallProfile=false` 为全关验收。`coreOpticalWallTimelineSelfTest` 检查严格覆盖、无关线程隔离、满槽丢样、非法嵌套与真实 executor 父节点传播。独立重算示例：

```powershell
python -X utf8 tools/AnalyzeMediumWallTimeline.py <包含三轮wall-timeline JSON的目录> --output <输出JSON路径>
```

### 严格不透明三角形阴影查询收紧

现有阴影第一名中，GPUScene 的持久 BLAS 和帧图元均由 `RtDeviceTriangleBlasBuilder` 生成；严格不透明证明成立时，opaque triangle 在 traversal 内自动提交命中，terminate-on-first-hit 完成遍历，没有需要应用确认的 alpha/procedural candidate。因此仅把这个已证明分支的空 `while(rayQueryProceedEXT(...))` 改为一次 proceed，通用透射/非严格场景路径保持原样。

参考 Falcor 同一 commit `759aad033ff610fb0d82c74f7e0a508d0096d5f2`，BSD-3-Clause，[`Source/Falcor/Scene/RaytracingInline.slang`](https://github.com/NVIDIAGameWorks/Falcor/blob/759aad033ff610fb0d82c74f7e0a508d0096d5f2/Source/Falcor/Scene/RaytracingInline.slang) 根据 alpha/procedural 几何需求特化 visibility query 的方式。本项目的单次 proceed 来自更严格的仅三角形证明，没有复制外部代码，也未引入需要额外 Vulkan feature 的 primitive-culling ray flag。

| 指标 | 对照 | 候选 | 变化 |
| --- | ---: | ---: | ---: |
| 正式散射灯光 GPU ms/帧 | 4.115554 | 3.975656 | −3.40% |
| 正式 GPU 三轮降幅 | — | — | 2.37% / 4.37% / 3.55% |
| 首组关闭诊断 FPS，90 帧预热 | 58.375178 | 56.164248 | 未改善，不以此宣称收益 |
| 反序关闭诊断 FPS，360 帧预热 | 64.692861 | 68.959958 | 本组 +6.60% |
| 反序关闭诊断 ms/帧 | 15.457656 | 14.501169 | 本组 −6.19% |

内部 shadow/remainder 比三轮由 0.34884/0.34900/0.34810 降至 0.31996/0.32164/0.31781。候选内部新第一名是主射线与命中/缓存读取，不再是不透明阴影；完整 ms/路径排名保留为内部证据。8 帧 HDR 全字节一致，23,083 次阴影 query 对原 traceRay parity 零差异，14 项生产 HDR 验收全部通过，validation 0 warning/0 error。仅这个 query 的 8 个 SPIR-V 变体重编译与校验。

保留三轮正式 GPU 阶段收益，关闭诊断的 FPS 仍按独立观测组报告，不把反序 +6.60% 当作保证。详细轮次与新内部排名见 [scattering-shadow-single-proceed-20261031.json](data/scattering-shadow-single-proceed-20261031.json)。下一候选按用户指定只处理 PBR 统计读回前同步。

### PBR 统计读回与完成边界合并

本轮按用户指定修复 PBR 统计读回前 CUDA 同步。原实现每个 tile 先 `cuStreamSynchronize`，同步拷回 16-byte 统计，再发 external signal，再等待 CUDA stream，最后提交 Vulkan acquire 并等 fence。统计不参与中间 GPU 调度，只用于每个 tile 返回后拒绝无效结果，因此这些 host 边界可以合并。

现在复用一个由 CUDA runtime 持有并在关闭时释放的 page-locked host 统计缓冲；将 DtoH async 排在 resolve kernel 之后、external signal 之前。新增严格的 enqueue-only exchange 入口，提前提交等待该 signal 的 Vulkan consumer，只用其 fence 作为成功返回的完成边界。每个 tile 返回后仍校验 failed/rejected/completed counts，再处理下一 tile；全部检查通过才发布最终帧。通用含 host-counter 决策或嵌套 exchange 的回调保留原阻塞路径；既有失败 drain 与资源所有权契约保持。

参考 NVIDIA cuda-samples v12.5，commit `9c688d7ff78455ed42e345124d1495aad6bf66de`，BSD-3-Clause：[`Samples/0_Introduction/simpleStreams/simpleStreams.cu`](https://github.com/NVIDIA/cuda-samples/blob/9c688d7ff78455ed42e345124d1495aad6bf66de/Samples/0_Introduction/simpleStreams/simpleStreams.cu) 的 pinned async copy 和 [`Samples/5_Domain_Specific/vulkanImageCUDA/vulkanImageCUDA.cu`](https://github.com/NVIDIA/cuda-samples/blob/9c688d7ff78455ed42e345124d1495aad6bf66de/Samples/5_Domain_Specific/vulkanImageCUDA/vulkanImageCUDA.cu) 的 stream-ordered external semaphore。通过项目已有的 pinned copy、external signal 和 fence 抽象独立适配，未复制外部代码。

冻结对照/候选 JAR 与 benchmark classes，各组 360 帧预热、3×120 个完成帧，所有测前 GPU 0 占用均为 0%。首组对照的 host 录制、轮询耗时更高，故保留原始数据并增加候选之后的反序对照；采用更小且三轮一致的收益，不以首组较大的差值宣称提升。

| 净收益指标 | 反序对照 | 候选 | 改善 |
| --- | ---: | ---: | ---: |
| 整条 PBR prepare/交接，ms/帧 | 7.932838 | 7.546261 | −4.87% |
| 三轮整条 PBR 耗时降幅 | — | — | 5.21% / 4.49% / 4.91% |
| 关闭全部诊断，原生完成 FPS | 68.426201 | 70.313198 | +2.76% |
| 关闭全部诊断，墙钟 ms/帧 | 14.614285 | 14.222081 | −2.68% |

实际净省约 0.386577 ms/帧。被取消的读回前 stream wait 中大部分本来就在等必要 GPU 工作，现在由 Vulkan fence 承担；不能声称原来的约 7 ms 整块都省掉了。候选开启 wall+GPU 观测为 14.147752 ms/帧，关闭观测为 14.222081 ms/帧，本组未测出超过 10% 的拖慢，负差值不算优化收益。

新的互斥墙钟主表来自候选同一观测窗口，360 帧、5093.1907 ms、70.682608 FPS。没有借用另一次关闭诊断的 FPS。

| 排名 | 实测墙钟边界 | ms/帧 | 每秒累计 ms | 占这一秒 |
| ---: | --- | ---: | ---: | ---: |
| 1 | PBR Vulkan 完成 fence 等待 | 7.012 | 495.607 | 49.56% |
| 2 | 散射完成边界 CUDA stream 同步 | 4.008 | 283.287 | 28.33% |
| 3 | 散射统计读回前 CUDA stream 同步 | 0.794 | 56.104 | 5.61% |
| 4 | 散射 Vulkan consumer fence 等待 | 0.459 | 32.426 | 3.24% |
| 5 | benchmark park 等待 | 0.450 | 31.798 | 3.18% |
| 6 | PBR release 命令录制 | 0.133 | 9.424 | 0.94% |
| 7 | 最终帧命令录制与提交 | 0.102 | 7.185 | 0.72% |
| 8 | 散射 release 命令录制 | 0.091 | 6.435 | 0.64% |
| 9 | 散射读回调用自身，扣除子项 | 0.075 | 5.302 | 0.53% |
| 10 | PBR release 提交 | 0.070 | 4.937 | 0.49% |
| | 其余 72 项合计 | 0.955 | 67.495 | 6.75% |
| | **互斥墙钟求和** | **14.148** | **1000.000** | **100.00%** |

新第一名 fence 包含 PBR GPU 生产与解析，不能再加上 GPU 时间；下一次继续优化应拆解其中必要 GPU 工作，而不是把这个 fence 机械删除。原始边界独立重算通过：3 个线程、67,506 个事件、零丢样、严格包含且兄弟不重叠。完整 82 项排名、分布、三轮与制品 hash 见 [pbr-queued-statistics-20261031.json](data/pbr-queued-statistics-20261031.json) 和 [完整排名 CSV](data/pbr-queued-statistics-ranked-20261031.csv)。

正确性：8 帧 RGBA16F 与保留对照全字节一致；14 项相机散射独立 HDR 检查、31 项非散射 PBR 检查（43 个 native frames）、15 项介质组合检查全部通过。覆盖 uniform/density/multiple、低 resident budget 跨 tile、resize、资源替换与 rejection；所有四份 validation 日志均零 warning/error。PBR ABI 与跨线程 timeline 契约自测通过。观测子代理仍保持停止，本候选由主线程顺序完成。


### PBR 第一名：严格不透明灯光连接改用 inline query

本次限定允许优化 PBR fence 内 GPU 前三项；使用已有 timestamp 已能确认灯光 packet 查询为第一名，因此未追加 shader 插桩。复用现有 `OPAQUE_SHADOW_RAY` 场景证明，仅在所有可能命中都应遮挡、双面、完全不透明、无介质边界时，使用 opaque triangle first-hit inline query，消除通用 any-hit/closest-hit/miss 调度及材质判定。保持 packet 清零、origin/epsilon/tMax、BRDF、光照、连接输出和 CUDA 输运不变；无 ray-query feature 或证明不成立时仍走原 packet shader。

参考 Falcor commit `759aad033ff610fb0d82c74f7e0a508d0096d5f2`，BSD-3-Clause，`Source/Falcor/Scene/RaytracingInline.slang` 的 visibility specialization；通过已有 ray-query 接口独立适配，未复制外部代码。基线 `12e9924`，冻结制品，360 帧预热、三轮各 120 个完成帧，所有测前 GPU 0 占用为 0%。

| 指标 | 对照 | 候选 | 变化 |
| --- | ---: | ---: | ---: |
| 灯光 packet GPU，ms/帧 | 2.363802 | 2.039185 | −13.73% |
| 三轮灯光 packet 降幅 | — | — | 12.52% / 10.63% / 17.80% |
| 整条 PBR，ms/帧 | 7.403944 | 7.317564 | −1.17% |
| 反序对照整条 PBR，ms/帧 | 7.601647 | 7.317564 | −3.74% |
| 第一组关闭诊断 FPS | 70.513432 | 69.425435 | −1.54% |
| 反序组关闭诊断 FPS | 66.468479 | 68.647402 | +3.28% |

确定性局部收益保留；不宣称整机 FPS 已稳定提高。CUDA kernel 同组由 2.025168 增至 2.157557 ms，贡献 restore 由 0.131579 增至 0.143174 ms，保留这些变化，不隐藏其他阶段的耗时。下一项按限定特例处理新 GPU 第一名 CUDA 解析。

候选同一观测窗口：360 帧、5129.2839 ms，14.248011 ms/帧、70.185236 FPS；观测相对关闭诊断帧时变化 −1.08%，未测到超过 10% 的拖慢。新主表 PBR fence 6.735173 ms/帧（472.710 ms/墙钟秒，47.271%），散射完成同步 4.119540 ms（289.131 ms/秒，28.913%），散射读回前同步 0.812244 ms（57.008 ms/秒，5.701%）。完整互斥表和求和见本轮 JSON/CSV，GPU 工作不与 fence 相加。67,502 事件，零丢样，严格包含与互斥求和检查通过。

8 帧 HDR 与保留对照全字节一致；31 项 PBR 检查（43 native frames）、15 项散射组合检查通过，三份 validation 日志零 warning/error。场景证明和 PBR ABI 自测通过；通用 shader 原有 8 个 SPIR-V 与改前完全相同，新 query 8 个变体已编译。证据：[pbr-light-inline-query-20261031.json](data/pbr-light-inline-query-20261031.json)、[完整排名 CSV](data/pbr-light-inline-query-ranked-20261031.csv)。工具：`tools/RunMediumPbrTrial.ps1`、`tools/AnalyzeMediumPbrTrial.py`。


### PBR 新第一名：无边界均匀解析移除通用体积状态

限定前三项中的 CUDA 解析成为新 GPU 第一名。原 kernel 无论场景是否存在界面，都为 receiver 和每个有效光连接携带/清零 272-byte 通用 State、32 层 PathVolumeInfo，并编译密度积分、优先级与事件处理。现在用独立 kernel 静态特化无边界且环境无密度 descriptor 的场景，只保留方向、透射率和发射。host 按 scene plan/descriptor 选择，kernel 再检查 magic/version/maxEvents/零边界/零初始集合/无密度；意外非零 packet 事件直接拒绝。边界、密度场景保留通用 kernel。两个入口共享同一个已编译 CUDA module，没有额外编译/同步或 resident 缓冲。

复用锁定 LuxCore `b872186d2fe995a762f73708c606847eed55843e`（Apache-2.0）的 `HomogeneousVolume_SegmentScatter` 和 PathVolumeInfo 语义；只把项目 segment/coefficient 适配器变为模板，使编译器去掉无用状态和密度分支，上游计算与 API 发射积分代码未改。连接遍历顺序、浮点运算、颜色/距离累计、失败与 completed 统计保持。未降低质量、步数或事件预算。

| 指标 | 对照 | 候选 | 降幅/提升 |
| --- | ---: | ---: | ---: |
| CUDA PBR kernel，ms/帧 | 2.231929 | 1.260957 | −43.50% |
| 三轮 kernel 降幅 | — | — | 43.94% / 45.07% / 41.43% |
| 整条 PBR，ms/帧 | 7.855413 | 6.626251 | −15.65% |
| 关闭诊断原生 FPS | 70.566711 | 76.827299 | +8.87% |

候选新 GPU 排名：灯光 packet 2.114346 ms、主射线 packet 1.726636 ms、CUDA 解析 1.260957 ms、贡献 restore 0.155791 ms。继续只处理本次限定前三项，下一项为原第三名主射线 packet。

三轮各 120 帧，360 帧预热，测前 GPU 0 均为 0%。候选观测窗口 14.019467 ms/帧、71.329390 FPS，观测开销 7.71%，在用户允许的 10% 内，最终 FPS 来自关闭全部诊断的独立窗口。墙钟新第一名 PBR fence 6.010726 ms/帧、428.741 ms/秒、42.874%；散射完成同步 4.543996 ms、324.120 ms/秒、32.412%。67,506 事件、零丢样，严格包含和互斥求和通过。GPU 工作不另加到 fence 主表。

正确性：8 帧 HDR 全字节一致，31 项 PBR、15 项介质组合、14 项相机散射检查全部通过，四份 validation 零 warning/error。源码 manifest、PBR ABI 和跨线程 wall timeline 契约通过。正式打包制品仅相对性能制品更新 manifest 中两个适配源的 byte/hash，运行类与 CUDA 源码完全相同，两个 hash 均留档。完整三轮、观测开销和新表：[pbr-uniform-state-20261031.json](data/pbr-uniform-state-20261031.json)、[排名 CSV](data/pbr-uniform-state-ranked-20261031.csv)。


### 限定第三项：主射线 packet 避免冗余 any-hit

主射线原先无条件 `NoOpaque`，即使 CPU 已严格证明所有可见实例都双面、无 alpha/overlay/visibility 排除、无介质边界。现在复用更强的 shadow-blocker proof 选择 `Opaque`，保留完整 closest-hit 材质/纹理/法线与 payload 着色，未替换为简化材质；证明不成立时仍收集边界并执行原 any-hit。参考同一 Falcor `759aad033ff610fb0d82c74f7e0a508d0096d5f2`（BSD-3-Clause）按场景是否需要 alpha/candidate 处理选择追踪路径的方式，独立适配 Vulkan primary packet。

| 指标 | 对照 | 候选 | 改善 |
| --- | ---: | ---: | ---: |
| 主射线 packet GPU，ms/帧 | 1.730851 | 1.578705 | −8.79% |
| 三轮主射线降幅 | — | — | 11.53% / 6.50% / 8.19% |
| 整条 PBR，ms/帧 | 6.591314 | 6.182057 | −6.21% |
| 关闭诊断原生 FPS | 75.293187 | 76.161664 | +1.15% |

360 帧预热、3×120 完成帧，测前 GPU 0 均为 0%。候选观测窗口 13.135243 ms/帧，76.131062 FPS；观测开销 0.04%，满足 10% 要求。67,502 事件、零丢样，严格包含和互斥求和通过。最新 GPU 排名：灯光 packet 1.959591 ms、主射线 packet 1.578705 ms、CUDA 解析 1.204716 ms、贡献 restore 0.146380 ms。

8 帧 HDR 与上轮对照全字节一致，31 项 PBR、15 项介质组合检查通过，三份 validation 零 warning/error；8 个受影响 SPIR-V 变体重新编译。保留确定收益并上传；最终合并验收另用本次开始前冻结制品比较，不拼接各轮 FPS 来算总收益。证据：[pbr-primary-opaque-20261031.json](data/pbr-primary-opaque-20261031.json)、[完整排名](data/pbr-primary-opaque-ranked-20261031.csv)。


### 本次 PBR fence 内前三项合并验收

仅合并本次限定三项：`15b54d8` 灯光 inline query、`f8d7aa4` 无界面均匀 CUDA 状态、`e1aa322` 主射线不透明追踪。用本轮开始前 `12e9924` 的冻结 JAR 做对照，对照/候选分别重新跑 360 帧预热和 3×120 完成帧；关闭观测另跑同样三轮。GPU 0 每组开始前均为 0%，四个冻结制品的运行记录、hash 与逐轮原始输出保留在本轮证据中。最终候选 hash 与第三项完成正确性验收的制品完全相同。

| 指标 | 本轮开始前 | 三项合并后 | 变化 |
| --- | ---: | ---: | ---: |
| 整条 PBR 准备与交接，ms/帧 | 7.596747 | 6.294826 | −17.14% |
| PBR 完成 fence，ms/帧 | 7.059679 | 5.776179 | −18.18% |
| 关闭观测原生 FPS | 69.735096 | 75.303275 | +7.98% |
| 关闭观测 ms/帧 | 14.339982 | 13.279635 | −7.39% |

三项原始目标的 GPU 时间在本组合并对照三轮均下降，按候选新排名：

| 排名 | GPU 工作 | 对照 ms/帧 | 候选 ms/帧 | 变化 |
| ---: | --- | ---: | ---: | ---: |
| 1 | 灯光 packet 查询 | 2.460717 | 2.002720 | −18.61% |
| 2 | 主射线 packet 查询 | 1.713557 | 1.600743 | −6.58% |
| 3 | CUDA 介质解析 | 2.049416 | 1.205131 | −41.20% |
| 4 | 贡献恢复 | 0.130848 | 0.145804 | +11.43% |

贡献恢复未作本轮优化，其 0.014956 ms/帧 增量完整保留。GPU 工作被下面的 fence 等待包住，不能重复相加。

最新互斥墙钟窗口：360 完成帧、4826.1809 ms、74.593143 FPS。按这同一个长窗口归一到一秒：

| 排名 | 墙钟事件 | ms/帧 | 每秒累计 ms | 占这一秒 |
| ---: | --- | ---: | ---: | ---: |
| 1 | PBR 完成 fence 等待 | 5.776179 | 430.863 | 43.086% |
| 2 | 散射完成 CUDA 同步 | 4.347480 | 324.292 | 32.429% |
| 3 | 散射统计读回前同步 | 0.832950 | 62.132 | 6.213% |
| 4 | 散射 Vulkan consumer fence 等待 | 0.532297 | 39.706 | 3.971% |
| 5 | benchmark park 等待 | 0.472151 | 35.219 | 3.522% |
| 6 | PBR release 命令录制 | 0.130828 | 9.759 | 0.976% |
| 7 | 最终帧录制与提交 | 0.111315 | 8.303 | 0.830% |
| 8 | 散射 release 命令录制 | 0.093328 | 6.962 | 0.696% |
| | 其余 74 项合计 | 1.109529 | 82.763 | 8.276% |
| | **互斥墙钟求和** | **13.406058** | **1000.000** | **100.000%** |

观测开销 0.95%，低于用户 10% 接受线；67,502 个事件，零丢样，严格包含、兄弟互斥与精确求和全部通过。原生 FPS 来自关闭诊断窗口，不与观测窗口拼接。8 帧 HDR 全字节一致，31 项 PBR 与 15 项介质组合检查通过，三份 validation 零 warning/error。120 FPS 目标尚未达到，画面模糊问题本轮未修改。

完整证据：[pbr-top3-acceptance-20261031.json](data/pbr-top3-acceptance-20261031.json)、[完整排名 CSV](data/pbr-top3-acceptance-ranked-20261031.csv)。所有确定性优化已保留；此次前三项特例到此闭环，后续仍按当前第一名及其最大内部工作推进。


### 主命中跨阶段复用：PBR fence 已从第一降为第二

用户本轮要求继续优化 PBR 完成 fence 内当前前三项，直到其总榜排名改变；该停止条件在三轮独立重算中均已满足。紧接着用户扩大任务为同时优化当前第一和第二内部前三项，直到两项退出前二或关闭诊断达到 120 FPS；这个新目标尚未完成，不能把本轮第 2 名结果当成最终任务完成。

发现默认均匀直接散射在 compact shader 中已追踪主射线，却只留下命中距离。PBR 再次追踪相同 sample 并写完整命中。现在让散射 shader 将完整 208-byte payload 写入 PBR 原本就拥有的 Vulkan primary 缓冲；PBR cached producer 只生成摄像机 packet，不再 trace/shade。没有新增一份全帧缓存，没有改变 resident/tile/SPP/光线/积分预算。仅对 direct uniform、严格 opaque-shadow-blocker 场景且关闭 caustics 使用；其他路径仍独立追踪。显式处理旧帧读到新帧写的 Vulkan barrier、PBR sink address/当前 plan 校验、跨 tile 全局像素索引、完整 miss 初始化与元数据；CUDA failure counter 与诊断指针不被覆盖。

参考 Falcor commit `759aad033ff610fb0d82c74f7e0a508d0096d5f2`、BSD-3-Clause，`Source/RenderPasses/PathTracer/GeneratePaths.cs.slang` 第 146 行附近从 V-buffer 载入已有 primary hit 的逻辑，独立适配本项目已有 payload/ownership，没有复制外部代码。参考源码 hash 保留在 JSON。

| 指标 | 对照 | 候选 | 变化 |
| --- | ---: | ---: | ---: |
| 主射线 packet GPU，ms/帧 | 1.533851 | 0.407522 | −73.43% |
| PBR fence，ms/帧 | 5.570807 | 4.455405 | −20.02% |
| 整条 PBR，ms/帧 | 6.109463 | 4.965915 | −18.72% |
| 散射与 PBR 两个完整墙钟阶段合计，ms/帧 | 12.209672 | 11.793466 | −3.41% |
| 关闭诊断原生 FPS | 76.278808 | 79.306374 | +3.97% |

完整 payload 保存让散射墙钟阶段从 6.098965 增至 6.826389 ms，已完整计入上表；不是把 PBR 所有下降都称为净收益。PBR 灯光查询 1.859587→1.893284 ms，CUDA resolve 1.203300→1.241484 ms，restore 0.143981→0.151990 ms，原始变差值完整保留，不宣称这些项更快。

最新墙钟排序，使用同一候选 360 个完成帧窗口归一到一秒：

| 排名 | 事件 | ms/帧 | 每秒累计 ms | 占比 |
| ---: | --- | ---: | ---: | ---: |
| 1 | 散射完成 CUDA 同步 | 4.786646 | 378.048 | 37.805% |
| 2 | PBR 完成 fence 等待 | 4.455405 | 351.886 | 35.189% |
| 3 | 散射统计读回前同步 | 0.978561 | 77.286 | 7.729% |
| 4 | benchmark park | 0.527785 | 41.684 | 4.168% |
| 5 | 散射 Vulkan fence 等待 | 0.485100 | 38.313 | 3.831% |
| 6 | PBR release 命令录制 | 0.135107 | 10.671 | 1.067% |
| 7 | 最终帧录制与提交 | 0.099335 | 7.845 | 0.785% |
| 8 | 散射 release 命令录制 | 0.091594 | 7.234 | 0.723% |
| | 其余 74 项合计 | 1.101952 | 87.032 | 8.703% |
| | **互斥墙钟求和** | **12.661484** | **1000.000** | **100.000%** |

三轮均为散射完成同步第一、PBR fence 第二；每轮独立重算结果见 JSON。68,226 个事件、零丢样，观测开销 0.41%。8 帧 HDR 逐字节一致；31 项 PBR、15 项组合、14 项相机散射验收通过，四份 validation 零 warning/error；PBR ABI、场景 proof 和跨线程 wall 契约通过。完整记录：[pbr-shared-primary-20261031.json](data/pbr-shared-primary-20261031.json)、[排名 CSV](data/pbr-shared-primary-ranked-20261031.csv)。


### 灯光按灯排列候选：确认负优化，已撤回

producer 和 CUDA 同步改用 light-major，连接求和顺序未改；8 帧 HDR 全字节一致，31 项 PBR 与 15 项组合检查通过。但 CUDA resolve 仅降低 7.96%，灯光 packet 三轮增加 37.07% / 41.62% / 38.86%，整条 PBR 从 5.059147 增至 5.739625 ms。关闭诊断 FPS 74.453548→73.510501。源文件和所有相应 SPIR-V 已恢复 `7bb3f53`，未保留该候选。证据：[pbr-light-major-20261031.json](data/pbr-light-major-20261031.json)。

### 共享主命中改用现有平面布局

默认均匀直接散射的共享 primary 沿用 optical 路径已实现的 13 字段平面布局。散射直接连续写各字段，cached camera packet、PBR 灯光和最终着色直接读取原缓冲；没有额外 transpose、copy 或新增全帧分配。非缓存 producer 保留原布局，PBR tile header byte 268 标记布局，最终消费者沿用 bit 8。容量、SPP、灯光/积分预算、着色语义和同步边界未改。

参考 NVIDIA cuda-samples commit `9c688d7ff78455ed42e345124d1495aad6bf66de`（BSD-3-Clause）`Samples/6_Performance/transpose/transpose.cu` 的合并访存原则，独立复用项目已有 optical producer/consumer 逻辑，没有复制外部代码。

| 指标 | 对照 | 候选 | 变化 |
| --- | ---: | ---: | ---: |
| 散射 lights raygen，ms/帧 | 4.264380 | 3.883797 | −8.92% |
| PBR 灯光 packet，ms/帧 | 1.902003 | 1.772379 | −6.82% |
| PBR camera packet，ms/帧 | 0.410685 | 0.351233 | −14.48% |
| 整条散射，ms/帧 | 6.849890 | 6.240920 | −8.89% |
| 整条 PBR，ms/帧 | 5.109008 | 4.839894 | −5.27% |
| 关闭诊断 FPS | 76.725559 | 84.575048 | +10.23% |

同一候选观测窗口 360 帧，83.867664 FPS；观测开销 0.84%。下表降幅均比较本轮同口径对照，不能与前轮不同运行窗口拼接：

| 排名 | 事件 | ms/帧 | 每秒累计 ms | 占比 | 本轮降幅 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | PBR 完成 fence 等待 | 4.320841 | 362.379 | 36.238% | 5.12% |
| 2 | 散射完成 CUDA 同步 | 4.280980 | 359.036 | 35.904% | 10.51% |
| 3 | 散射统计读回前同步 | 0.888640 | 74.528 | 7.453% | 8.77% |
| 4 | 散射 Vulkan fence 等待 | 0.538403 | 45.155 | 4.515% | 4.55% |
| 5 | benchmark park | 0.502002 | 42.102 | 4.210% | -3.54% |
| 6 | PBR release 命令录制 | 0.126700 | 10.626 | 1.063% | 11.06% |
| 7 | 散射 release 命令录制 | 0.093766 | 7.864 | 0.786% | -3.07% |
| 8 | 最终帧录制与提交 | 0.091243 | 7.652 | 0.765% | 12.82% |
| | 其余 74 项合计 | 1.080971 | 90.659 | 9.066% | — |
| | **互斥墙钟求和** | **11.923547** | **1000.000** | **100.000%** | **7.04%** |

两项仍居前二，120 FPS 尚未达到，继续优化。8 帧 HDR 全字节一致；31 项 PBR、15 项组合、14 项相机散射检查通过，四份正式 validation 日志零 warning/error；68,226 事件零丢样，区间包含和互斥求和通过。测前 GPU 0 全部为 0%。首轮打包误用旧构建资源，已废弃并保留记录；正式 shader 生成始终将 `renderer-core/src/main/resources` 放在 classpath 最前，随后重新打包、验图和测量。完整记录：[pbr-primary-planar-20261031.json](data/pbr-primary-planar-20261031.json)、[完整排名](data/pbr-primary-planar-ranked-20261031.csv)。


### 两个未保留候选

灯位缓存复用：8 帧 HDR 全字节一致，但目标 PBR 灯光 packet 只降低 0.08%，三轮符号不一致；未将 FPS 的 +1.71% 当作确定收益，源文件和 SPIR-V 已撤回。按像素批处理灯光：8 帧 HDR 全字节一致，但灯光 packet 三轮分别增加 4.13% / 5.20% / 3.60%，已撤回。两轮 31 项 PBR 与 15 项组合检查均通过。证据：[灯位缓存](data/pbr-light-coordinate-cache-20261031.json)、[按像素批处理](data/pbr-pixel-light-batch-20261031.json)。

### 无介质边界 packet 去掉不可能使用的事件槽

无边界场景原本仍强制至少一个事件槽，每个 camera/light packet 占 48 bytes。现在仅在 `boundaryCount == 0` 时令物理事件容量为 0，packet 缩至 32 bytes；保留方向、零事件计数、终点距离与 origin，公共 maxEvents、SPP、灯光、积分预算不变。有边界场景的容量、事件排序、溢出和通用解析全部保持。同步调整 allocation/minimum/统一 tile 预算，仍满足 resident cap，并验证最小一像素和 PBR/scattering tile 一致。CUDA 允许零事件容量的前提仍为没有边界，非零 packet 事件仍拒绝。

参考 PBRT-v4 commit `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f`（Apache-2.0），`src/pbrt/wavefront/workitems.h` 的按实际工作项保留所需数据的做法；独立适配本项目 packet ABI，没有复制外部代码。

| 指标 | 对照 | 候选 | 变化 |
| --- | ---: | ---: | ---: |
| PBR 灯光 packet，ms/帧 | 1.865786 | 1.511013 | −19.01% |
| PBR CUDA resolve，ms/帧 | 1.268399 | 0.930117 | −26.67% |
| PBR camera packet，ms/帧 | 0.360602 | 0.194310 | −46.12% |
| 整条 PBR，ms/帧 | 5.006779 | 4.112037 | −17.87% |
| 关闭诊断 FPS | 85.017362 | 92.241723 | +8.50% |

最新同一观测窗口 360 帧、91.063137 FPS，观测开销 1.29%。两项仍居前二，任务继续。

| 排名 | 事件 | ms/帧 | 每秒累计 ms | 占比 | 本轮降幅 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | 散射完成 CUDA 同步 | 4.811366 | 438.138 | 43.814% | -5.36% |
| 2 | PBR 完成 fence 等待 | 3.627079 | 330.293 | 33.029% | 18.23% |
| 3 | 散射 Vulkan fence 等待 | 0.518052 | 47.175 | 4.718% | 0.15% |
| 4 | benchmark park | 0.415162 | 37.806 | 3.781% | 6.30% |
| 5 | 散射统计读回前同步 | 0.268196 | 24.423 | 2.442% | 71.53% |
| 6 | PBR release 命令录制 | 0.120789 | 10.999 | 1.100% | 16.60% |
| 7 | 最终帧录制与提交 | 0.086711 | 7.896 | 0.790% | 25.64% |
| 8 | 散射 release 命令录制 | 0.085993 | 7.831 | 0.783% | 17.66% |
| | 其余 74 项合计 | 1.048043 | 95.438 | 9.544% | — |
| | **互斥墙钟求和** | **10.981392** | **1000.000** | **100.000%** | **12.10%** |

散射两个同步边界重新分配了等待：完成同步增加、统计读回前同步减少，不能把单个边界的变化当成散射 GPU 工作增加或减少。原始互斥墙钟和 GPU 时间分别保留，不相加。68,216 个事件、零丢样，严格包含与求和通过。8 帧 HDR 全字节一致，31 项 PBR、15 项组合、ABI 最小分配与 tile 检查、源码 manifest 通过，三份 validation 日志零 warning/error。测前 GPU 0 全部为 0%。一次 native JVM 崩溃后仅调整 code cache 重试成功，失败窗口未参与结果。证据：[pbr-compact-packets-20261031.json](data/pbr-compact-packets-20261031.json)、[完整排名](data/pbr-compact-packets-ranked-20261031.csv)。


### 主命中 producer 与原生 PBR consumer 按实际需求对齐

只在严格缓存场景、1 SPP、关闭 temporal/denoise/reconstruction/caustics 且该像素无 outline/lightmap 时，散射主命中保存 PBR 必需的 7 个 lane（112 bytes），不再写不会被消费者读取的其余 6 个 lane。所有多 SPP、开启几何消费者功能、描边或 lightmap 的像素仍保存完整 208-byte payload。PBR 只在真实 caustic region 构造完整 receiver；最终着色复用已有 resolved-publication 分支，直接相加已经求出的 native diffuse/specular，按所有样本检查描边与 lightmap 后才允许跳过完整几何着色。光线、BRDF、随机数、积分预算与原生输运均未变。

沿用现有 optical resolved-publication 的生产/消费分层，并参考上轮固定 PBRT-v4 work-item 最小数据原则，独立适配本项目 PBR split 与 feature fallback。不是把普通最终着色任意关闭。

首组关闭诊断 82.284670→91.679759 FPS，首次对照有较大逐轮波动，因此另做相反顺序的完整三轮，并以反序窗口作为本轮最新表。没有只选择较大的 +11.42% 汇报。

| 指标 | 反序对照 | 反序候选 | 变化 |
| --- | ---: | ---: | ---: |
| 最终帧 GPU，ms/帧 | 0.832698 | 0.316173 | −62.03% |
| PBR 灯光 packet，ms/帧 | 1.534306 | 1.362500 | −11.20% |
| 整条 PBR，ms/帧 | 4.117708 | 3.823832 | −7.14% |
| 关闭诊断 FPS | 91.155374 | 96.706543 | +6.09% |

最新同一候选观测窗口 360 帧、97.794907 FPS，观测窗口比关闭诊断快约 1.11%，没有测到超过 10% 的观测拖慢。GPU 子事件不与以下等待重复相加：

| 排名 | 事件 | ms/帧 | 每秒累计 ms | 占比 | 本轮降幅 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | 散射完成 CUDA 同步 | 4.239601 | 414.611 | 41.461% | 13.20% |
| 2 | PBR 完成 fence 等待 | 3.337586 | 326.399 | 32.640% | 7.31% |
| 3 | benchmark park | 0.497386 | 48.642 | 4.864% | -21.58% |
| 4 | 散射 Vulkan fence 等待 | 0.471164 | 46.077 | 4.608% | 5.20% |
| 5 | 散射统计读回前同步 | 0.267434 | 26.154 | 2.615% | 0.93% |
| 6 | PBR release 命令录制 | 0.118917 | 11.629 | 1.163% | 10.24% |
| 7 | 散射 release 命令录制 | 0.098003 | 9.584 | 0.958% | -10.99% |
| 8 | 最终帧录制与提交 | 0.093974 | 9.190 | 0.919% | -3.27% |
| | 其余 74 项合计 | 1.101417 | 107.713 | 10.771% | — |
| | **互斥墙钟求和** | **10.225481** | **1000.000** | **100.000%** | **7.33%** |

正确性：展厅 8 帧 HDR 全字节一致；新增 PBR/UNLIT × outline 开关 × SPP 1/2/8 的 12 帧消费者矩阵，冻结对照/候选全字节一致；31 项 PBR、15 项介质组合检查通过，所有正式 validation 零 warning/error。68,223 个墙钟事件零丢样、严格包含与求和通过。测前 GPU 0 全部为 0%。基线 shader 细粒度 profile 单独复测开销约 6.18%，前三稳定为 primary、local geometry、opaque shadow，只用于路径内部排序。没有将抽样 ms 分摊整帧。

100 FPS 里程碑尚未达到，继续性能主线。额外矩阵最初使用 metallic=.2，被冻结对照明确拒绝，已记录为功能闭合子代理的真实缺口；正式兼容矩阵限定当前已支持的非金属 PBR/UNLIT，没有将该拒绝算成验收通过。证据：[pbr-primary-consumer-20261031.json](data/pbr-primary-consumer-20261031.json)、[完整排名](data/pbr-primary-consumer-ranked-20261031.csv)。

### 两个撤回候选与成熟实现对照要求

散射 queued 交接：参考 NVIDIA cuda-samples 的 pinned async copy 与 stream-ordered external semaphore，移除散射中的独立 CUDA 主机等待，保留最终 Vulkan fence 与发布前失败检查。8 帧 HDR 全字节一致，31 项 PBR、15 项组合、14 项相机散射参考检查通过。但正序和反序关闭诊断 FPS 分别为 −2.65% 和 +3.75%；合并 720 帧仅约 +0.49%，没有稳定净收益，Java 修改全部撤回。等待从 CUDA 边界迁到 Vulkan fence 不能算性能收益。证据：[scattering-queued-20261031.json](data/scattering-queued-20261031.json)。

小灯集合缓存：参考 Falcor `LightData.slang` 的准备数据思路，将灯位、方向与光锥参数保留到各积分区间复用。独立输运参考检查通过，但散射 lights GPU 三轮分别增加 40.89%、5.75%、11.13%，确认没有优化到当前实际成本；源码与全部 SPIR-V 撤回。8 帧总计 460,800 个 HDR 通道中 4 个通道相差一个半精度刻度，原始差异保留，没有称为全字节一致。未把关闭诊断 FPS 的小幅波动作为保留依据。证据：[scattering-prepared-lights-20261031.json](data/scattering-prepared-lights-20261031.json)。

用户再次明确：遇到疑惑必须深入对照成熟项目，不能只引用相似模式。后续候选必须写清楚成熟实现实际读取哪些输入、本项目多做了哪些工作、producer/ABI/consumer 如何一起删除冗余；跨 ray query 保存数据还必须审查其生命周期成本。收益必须落到当前大头与整帧，不得靠等待改名、重排排名或单个偶然较快窗口交差。

### 均匀无边界 PBR packet 只保留终点

对照 PBRT-v4 commit `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f`（Apache-2.0）`src/pbrt/media.h`：`HomogeneousMedium::SamplePoint` 不读取位置，`SampleRay` 只返回 `[0,tMax]`，不读取射线；`wavefront/workitems.h` 与 `integrator.cpp` 保留通用 shadow ray 的职责。映射到本项目：Vulkan 已完成几何/阴影查询，CUDA 均匀分支通过固定 LuxCore 段函数积分，只需要距离，不再需要方向和零事件计数。因此 producer、ABI 与 consumer 一起删除首个 lane；保留距离与起点的 16-byte lane、发布前失败校验、相同采样和输运预算。没有复制外部代码，也没有改写 LuxCore 段积分。有密度描述或任何边界时保留方向/事件的原布局，原生 PBR 31 项和组合 15 项覆盖这些回退分支。

32→16 bytes 降低 scratch，使默认场景 PBR 与散射均从每帧 3 批变为 2 批，跨 API 交接从 6 次变为 4 次。不是删除必要同步或重命名等待。正序关闭诊断 88.389399→98.391878 FPS（+11.32%）；完整反序复测 90.676574→99.021391 FPS（+9.20%）。以反序作为最新正式表，尚未达到稳定关闭诊断 100 FPS。

| 指标 | 对照 ms/帧 | 候选 ms/帧 | 降幅 |
| --- | ---: | ---: | ---: |
| 整条 PBR | 3.979303 | 3.425797 | 13.91% |
| PBR 主射线 packet | 0.192159 | 0.133928 | 30.30% |
| PBR CUDA 解析 | 0.917985 | 0.829098 | 9.68% |
| PBR 灯光 packet | 1.382503 | 1.402775 | 增加 1.47% |
| contribution restore | 0.148511 | 0.157059 | 增加 5.76% |

最新同一 360 帧观测窗口 97.985217 FPS，观测开销 1.06%，49,499 个事件零丢样，严格包含和精确互斥求和通过。

| 排名 | 事件 | ms/帧 | 每秒累计 ms | 占比 | 本轮降幅 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | PBR 完成 fence 等待 | 3.002210 | 294.172 | 29.417% | 12.91% |
| 2 | 散射完成 CUDA 同步 | 2.979300 | 291.927 | 29.193% | 30.65% |
| 3 | 散射统计读回前同步 | 2.104827 | 206.242 | 20.624% | 增加 708.63% |
| 4 | benchmark park | 0.439320 | 43.047 | 4.305% | 10.18% |
| 5 | 散射 Vulkan fence 等待 | 0.354739 | 34.759 | 3.476% | 32.82% |
| 6 | 最终帧录制提交 | 0.120780 | 11.835 | 1.183% | 增加 26.81% |
| 7 | PBR release 录制 | 0.101633 | 9.959 | 0.996% | 26.03% |
| 8 | 散射 release 录制 | 0.081839 | 8.019 | 0.802% | 9.52% |
| | 其余 74 项合计 | 1.020972 | 100.040 | 10.004% | — |
| | **互斥墙钟求和** | **10.205621** | **1000.000** | **100.000%** | **2.09%** |

更大的 tile 将散射等待重新分配到完成同步与最后一次统计读回，不能把第二项单独的下降当作收益。散射 lights GPU 在反序窗口增加约 15.06%，已完整计入整帧结果；本轮保留依据是 PBR 必要工作减少和两种顺序均有正吞吐收益。展厅 8 帧 HDR 全字节一致，31 项 PBR、15 项介质组合、14 项相机散射独立参考检查通过，全部正式 validation 零 warning/error；CPU ABI 最小分配、密度回退、组合 tile、manifest 检查通过。证据：[pbr-endpoint-packets-20261031.json](data/pbr-endpoint-packets-20261031.json)、[完整排名](data/pbr-endpoint-packets-ranked-20261031.csv)。


### 到达 100 FPS 窗口后的画质插入项

PBR 提前剔除/延后材质读取候选：8 帧 HDR 字节一致，31 项 PBR 与 15 项组合通过，但正序、反序的目标灯光 packet GPU 时间分别增加 1.78%、2.61%；源文件及 16 个 SPIR-V 均已撤回。反序保留版本关闭诊断对照合计 100.491510 FPS，逐轮约 96.06/99.84/106.08，不称为每轮稳定 100+。依用户约定，先处理未开降噪的画质，再继续总榜前三各自内部前三。证据：[撤回候选](data/pbr-light-cull-rejected-20261031.json)。

已修复 GLFW 显示链的确定性错误：线性 RGBA16F 原来直接 blit 成 UNORM SDR，没有色调映射或 gamma；现在窗口复用既有 publication compute 的 ACES-fit + gamma2.2。转换与截图口径一致，不更改线性 HDR 原图、不作邻域滤波或锐化。共享设备和外部导入均经同一转换；每帧槽资源有界，完成后复用，尺寸变化时替换。公开 lease 只保证 TRANSFER_SRC，所以先拷贝到私有 HDR storage，不擅自为借用图创建 storage view。转换增加了必要的显示工作，未宣称是帧率优化；无窗口 benchmark 不经过 presenter。

参考 Falcor commit `759aad033ff610fb0d82c74f7e0a508d0096d5f2`（BSD-3-Clause）`Source/RenderPasses/ToneMapper/ToneMapping.ps.slang` 的显示变换分层和 ACES 曲线，复用本项目已有公式，没有复制外部代码。真实 GPU 12 项覆盖全部 31,744 个非负有限半精度亮度值、单像素黑白边缘、源图不变、帧槽复用、尺寸变化和共享/独立 allocator；RGB 相对独立 double CPU oracle 允许 1 个 SDR 编码刻度，边缘通道和 alpha 完全一致。转换测试、真实 121 帧窗口测试 validation 均为零 warning/error、零丢样，CPU 全半精度显示 oracle 通过。

随后真实可视化运行 FG/降噪/重建/时域均关闭。用户截图窗口为 1920×1080、1 SPP、101.9 FPS、9.81 ms/帧；完整日志还包括启动、窗口尺寸变化和较慢窗口，全部保留，不能把单个 101.9 FPS 当成全程稳定值。用户指出左墙及光束颗粒逐帧闪动，尚未解决；显示修复不能冒充采样噪声已经消失。后续同样保持场景、SPP 和默认 8 段积分，不通过减雾、冻结随机数或暗开降噪交差。此前本段将 maxEvents=16 误记为散射段数，实际场景未变。展示保持 controls=true，保留全部场景切换，不设自动退出上限；用户要求不使用 computer use。证据：[显示链及真实 demo 数据](data/medium-hdr-presentation-20261031.json)。

### 不开降噪的局部光源闪烁：固定预算下按光源位置采样

原均匀直接散射在每个等长区间内按截断指数分布采一个点；相机射线靠近点光/聚光灯时，少数点采到很大的几何贡献，逐帧更换种子后表现为左墙及蓝光的强颗粒闪烁。参考 Blender Cycles commit `a51f293548adba0dda086f5771401e8d8ab66227` 的 `intern/cycles/kernel/integrator/shade_volume.h` 等角采样/PDF 与距离采样混合原则，独立推导并实现，没有复制 GPL 源码。

只对均匀直接散射、小灯集合可穷举、整段不会被 cutoff 截断、且长射线靠近局部灯的情况启用。保持请求原有的 scatteringSteps 预算（默认展厅为 8），在距离分布和最多两个等角分布的联合 CDF 中分层，贡献除以完整混合 PDF；前景平滑区、方向光、较短射线、reservoir 大灯集合和 cutoff 截断仍走原区间采样。直射 source ABI 升为 2：xyz 已除采样 PDF，w 为绝对相机距离，producer/SPIR-V/CUDA 一起更新；缓冲布局及 16-byte source 不增长。多次散射继续使用原相对距离和消费者默认模式。没有改变密度、几何、SPP、阴影/光线预算、降噪或时域功能。

真实 GPU，1080p、每个版本连续 32 帧、不同逐帧种子。区域在比较前按用户截图固定；全部单帧均以同一个显示曲线观察，离线均值只用于验收，运行时没有帧累积：

| 区域 | HDR 帧间方差降幅 | SDR 帧间方差降幅 | 相邻帧 SDR 差值降幅 | 平均亮度变化 |
| --- | ---: | ---: | ---: | ---: |
| 左墙 | 87.84% | 87.55% | 66.83% | +0.0219% |
| 蓝光 | 60.95% | 47.99% | 15.67% | +0.0131% |
| 整图 | 85.99% | 79.70% | 42.49% | +0.0063% |
| 平滑前景 | 约 0% | 约 0% | 约 0% | +0.0000055% |

320×180 各 128 帧独立窗口确认同样趋势，左墙/蓝光 HDR 方差下降 87.82%/61.33%。这次修复显著减轻闪烁，1 SPP 的蓝光与遮挡区域仍有残余噪声，不能称为完全无噪声。近灯/聚光锥和近灯/不透明遮挡两项真实 GPU 测试，各 128 帧，独立 4096 段 double Simpson 参考的均值相对 RMS 误差分别为 0.624%/0.853%；逐通道同时检查六倍标准误差加半精度量化误差，没有删掉旧比较。组件测试覆盖绝对非顺序距离、不同 PDF 权重、解析常量源、compact 分块、输入/外圈 guard 和六类非法包；15 项原有及零参考消光生产检查、31 项 PBR、15 项散射组合和多次散射解析检查通过。10 份正式 Vulkan validation 日志零 warning/error。

关闭诊断正序对照/候选为 104.527/104.177 FPS，反序为 100.652/106.153 FPS；没有将顺序波动宣称为确定性能收益。最终打包版另测 360 帧为 100.714 FPS，三轮 94.07/107.03/101.90，并非每轮稳定 100+。最终版修正极低光学厚度的均匀 proposal 权重，并通过全部 32 帧 1080p 与上述测量候选逐字节一致校验。最新观测窗口 360 帧、106.213 FPS，49,503 个事件、零丢样，互斥包含与求和检查通过；观测未显示拖慢，不能将负观测开销算作优化。

| 排名 | 事件 | ms/帧 | 每秒累计 ms | 占比 |
| ---: | --- | ---: | ---: | ---: |
| 1 | PBR 完成 fence 等待 | 2.746699 | 291.735 | 29.173% |
| 2 | 散射完成 CUDA 同步 | 2.733318 | 290.313 | 29.031% |
| 3 | 散射统计读回前同步 | 1.891885 | 200.942 | 20.094% |
| 4 | benchmark park | 0.524982 | 55.760 | 5.576% |
| 5 | 散射 Vulkan fence | 0.332080 | 35.271 | 3.527% |
| 6 | PBR release 录制 | 0.095970 | 10.193 | 1.019% |
| 7 | 最终帧录制提交 | 0.091579 | 9.727 | 0.973% |
| 8 | 散射统计读回自身 | 0.073256 | 7.781 | 0.778% |
| | 其余 74 项 | 0.925293 | 98.278 | 9.828% |
| | **互斥墙钟求和** | **9.415063** | **1000.000** | **100.000%** |

内部采样路径前三仍为主命中、局部灯几何和 opaque 阴影查询，另一次 shader 时钟窗口仅用于排序。后续继续总榜前三各自内部前三，不能将第二、第三共同等待散射链重复计收益。新的 [逐帧噪声分析工具](../../tools/AnalyzeMediumTemporalNoise.py) 接收紧密 RGBA16F 原图，流式统计 HDR/SDR 方差及相邻帧差值；使用 NumPy，无运行时依赖。完整 [画质及性能证据](data/medium-equiangular-clarity-20261031.json)、[最新完整排名](data/medium-equiangular-ranked-20261031.json)。

### 撤回：无纹理主命中省略纹理导数

修改当前 shader 子阶段第一名主命中：四个材质纹理槽均无效时，不算 UV 和 footprint；有任意纹理仍保留原采样路径。对照本项目 visibility closest-hit 的按需纹理计算，以及固定 PBRT-v4 `surfscatter.cpp` 的导数用途；PBRT 自身未采用此无纹理快捷分支，不声称照搬了它的优化。8 帧展厅 HDR 字节一致，但三轮散射 producer GPU 合计仅下降 0.22%，关闭诊断 FPS 102.221→98.113（−4.02%），未证明优化到了实际成本，源文件和两个 SPIR-V 立即恢复。额外纹理矩阵在首帧前因 fixture 太阳方向未归一而停止，没有算成正确性通过，也未再为已拒绝候选投入 GPU。证据：[候选原始结果](data/primary-texture-demand-rejected-20261031.json)。

子代理的 optical 无阴影灯光连接修复合入后，主树重新通过 camera 散射 17 项比较、optical 46 项/152 帧及一次拒绝恢复、PBR 焦散 5 帧和源码清单，日志 `build/scattering-guided/merged-closure-regression.log`。API 的全入口闭合验收由独立子代理继续执行，局部通过不冒充完整闭合。

### 主命中与散射灯光分段：保留小幅确定收益

对照 PBRT-v4 commit `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f` 的 `src/pbrt/wavefront/integrator.cpp`：`IntersectClosest`、`EvaluateMaterialsAndBSDFs`、`TraceShadowRays` 分开处理。独立实现相同的阶段划分原则，没有复制外部代码。仅当原有严格的 reusable-primary 场景证明成立时，把现有主命中及平面 payload 写入放进专用 shader，随后灯光 shader 读取原 compact 距离/命中标志，避免与主 trace 共用全部积分状态。两个 dispatch 仍在同一次 Vulkan 提交中，中间只有必要的 shader 写读 barrier；不增加主命中次数，不改变 CUDA 交接数、source ABI、预算或输出。其它场景保留原路径。

新增主命中 GPU timestamp 嵌套在 `cameraScatteringLightsGpu` 中，分析时不能再次相加。关闭诊断不创建或解析这些 query。默认场景参数重新按实际请求核实：1080p、1 SPP、scatteringSteps=8、maxEvents=16、lightSamples=4，其余功能关闭。旧画质记录将 maxEvents 错当成了 scatteringSteps；已修正元数据和描述，原始图像、方差、帧率均未改变。离线噪声工具现在要求显式传入 `--scattering-steps`，不再硬编码错误值。

| 测试顺序 | 关闭诊断对照 FPS | 候选 FPS | FPS 提升 | 整段散射 producer GPU 降幅 |
| --- | ---: | ---: | ---: | ---: |
| 正序，预热 360 帧 | 100.476930 | 101.801502 | 1.32% | 1.79% |
| 反序，预热 360 帧 | 98.307712 | 101.489717 | 3.24% | 1.21% |
| 延长预热 1200 帧 | 100.080690 | 101.495213 | 1.41% | 3.15% |

每个窗口均为 3×120 个完成帧；所有测前 GPU 0 占用为 0%。延长预热后散射 producer 从 4.172020 降至 4.040420 ms/帧，其中独立 primary 为 0.891818 ms/帧。这是小幅收益，不称为显著提升，也没有达到 120 FPS。PBR 同窗 GPU 时间也受频率等变化影响，不能把其变化归功于未修改的 PBR 算法。

最新延长预热观测窗口为 100.924015 FPS，49,503 个事件、零丢样，包含与互斥求和检查通过。观测开销 0.57%。下表降幅只比较本轮同参数对照，不跨旧窗口拼接。

| 排名 | 阶段 | ms/帧 | 每秒累计 ms | 占比 | 本轮降幅 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | 散射完成 CUDA 同步 | 3.028501 | 305.648 | 30.565% | 1.92% |
| 2 | PBR 完成 fence 等待 | 2.983461 | 301.103 | 30.110% | 1.90% |
| 3 | 散射统计读回前同步 | 1.966883 | 198.506 | 19.851% | 4.17% |
| 4 | benchmark park | 0.484194 | 48.867 | 4.887% | 增加 19.01% |
| 5 | 散射 Vulkan fence | 0.353787 | 35.706 | 3.571% | 2.49% |
| 6 | PBR release 录制 | 0.086755 | 8.756 | 0.876% | 增加 3.86% |
| 7 | 最终帧录制提交 | 0.083203 | 8.397 | 0.840% | 增加 0.35% |
| 8 | 散射统计读回自身 | 0.076123 | 7.683 | 0.768% | 增加 7.14% |
| | 其余 74 项 | 0.845539 | 85.335 | 8.534% | — |
| | **互斥墙钟求和** | **9.908444** | **1000.000** | **100.000%** | **0.54%** |

正确性：8 帧展厅 HDR 以及六种纹理组合×SPP 1/2/8×真空/直接介质的 36 帧 HDR，冻结对照与候选均逐字节一致。PBR 31 项/43 帧、PBR 散射组合 15 项、camera 17 项（含两组 128 帧近灯均值）、multiple 解析回归均通过，8 份正式 validation 日志零 warning/error、零丢样且正常关闭；274 个预编译 shader 与源码逐字节匹配。镜像边界的独立 API 闭合修复仍由子代理处理，没有将本轮局部回归冒充完整 API 闭合。

完整 [性能及正确性证据](data/scattering-split-primary-20261031.json)、[最新完整排名](data/scattering-split-primary-ranked-20261031.json)。后续继续总榜前三各自内部前三；当前第一与第三共同等待散射链，不能重复计收益。

### 撤回：先选择 proposal 再求逆 CDF

对照固定 Cycles `shade_volume.h` 的先选采样策略、后计算对应分布原则，删除 guided 分支会覆盖的原区间位置/PDF 计算。1080p、连续 8 帧原始 HDR 共 132,710,400 bytes 与冻结对照完全一致，正式 validation 无 warning/error。实际默认预算由真实请求记录：scatteringSteps=8、maxEvents=16、lightSamples=4。

关闭诊断正序 103.816221→103.408107 FPS（−0.39%），反序 102.273032→103.126660 FPS（+0.83%）；散射 producer GPU 分别增加 1.02% 和下降 0.56%。两种顺序没有稳定收益，GLSL 与 40 份 SPIR-V 已精确恢复，不保留看起来更简洁但没有证明性能改善的改动。没有为已拒绝候选继续消耗 GPU 跑完整回归。[两种顺序、制品和完整尺寸 HDR 证据](data/scattering-proposal-branch-rejected-20261031.json)。

### 缓存散射阴影按已有严格证明编译专用路径

参考 Falcor `759aad033ff610fb0d82c74f7e0a508d0096d5f2`、BSD-3-Clause，`Source/Falcor/Scene/RaytracingInline.slang` 的 `traceSceneVisibilityRay<UseAlphaTest>`：按静态 alpha/procedural 条件选择 query 类型和 flags。独立适配本项目已有 `reusablePbrPrimary -> opaqueShadowRayOnly` 证明，仅在 cached ray-query shader 编译掉通用透明/边界回退和两套 ray payload。普通缓存、非缓存及不支持 ray-query 的路径保留；显式 query audit 保留完整对照路径。未改变 flags、射线、交接、预算、source ABI 或积分公式。274 份源码/SPIR-V 全部匹配，实际仅 8 份二进制发生变化，单份约 61.5 KB→51.6 KB。

| 测试顺序 | 关闭诊断对照 FPS | 候选 FPS | FPS 提升 | 散射 producer GPU 降幅 |
| --- | ---: | ---: | ---: | ---: |
| 正序，预热 360 帧 | 104.789134 | 105.704108 | 0.87% | 2.84% |
| 反序，预热 360 帧 | 101.789145 | 104.178643 | 2.35% | 3.34% |
| 延长预热 1200 帧 | 101.816707 | 103.131706 | 1.29% | 2.83% |

三组每组 3×120 个完成帧，所有测前 GPU 0 占用为 0%。延长预热散射 producer 4.035981→3.921805 ms/帧。PBR 未修改，不能把其同窗变化归功于本轮。收益小但三组方向一致，保留；未达到 120 FPS。

最新同一稳态观测窗口 102.419332 FPS，49,499 个事件、零丢样，包含/互斥求和通过；观测开销 0.70%。GPU 子事件不加入下面墙钟求和。

| 排名 | 阶段 | ms/帧 | 每秒累计 ms | 占比 | 同窗降幅 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | PBR 完成 fence 等待 | 3.009606 | 308.242 | 30.824% | 增加 0.25% |
| 2 | 散射完成 CUDA 同步 | 2.954379 | 302.586 | 30.259% | 3.27% |
| 3 | 散射统计读回前同步 | 1.913296 | 195.959 | 19.596% | 2.49% |
| 4 | benchmark park | 0.469009 | 48.036 | 4.804% | 增加 4.06% |
| 5 | 散射 Vulkan fence | 0.374086 | 38.314 | 3.831% | 增加 3.83% |
| 6 | PBR release 录制 | 0.084074 | 8.611 | 0.861% | 增加 0.33% |
| 7 | 最终帧录制提交 | 0.079651 | 8.158 | 0.816% | 7.20% |
| 8 | 散射读回自身 | 0.065734 | 6.732 | 0.673% | 2.46% |
| | 其余 74 项 | 0.813945 | 83.364 | 8.336% | — |
| | **互斥墙钟求和** | **9.763782** | **1000.000** | **100.000%** | — |

1080p 连续 8 帧共 66,355,200 个原始 RGBA16F 通道中，23 个通道相差 1 个相邻半精度值，其余完全相同，alpha 完全相同；显示映射后 1 个 RGB 通道差 1 个 SDR 编码刻度。没有称为全字节一致，也未放宽独立测试阈值。PBR 31 项/43 帧、散射组合 15 项、camera 17 项独立参考、多次散射解析回归全部通过；两组 128 帧近灯均值相对 RMS 误差为 0.634%/0.841%，六份正式 validation 零 warning/error/drop、会话全部关闭。[完整收益、量化差异和回归证据](data/scattering-opaque-specialization-20261031.json)、[新完整排名](data/scattering-opaque-specialization-ranked-20261031.json)。

### 撤回：PBR 遮挡后再算 BRDF

仅把 BRDF 求值移动到现有 shadow query 之后，保留原材质读取、packet 写入、判断及所有数学公式；与本项目普通 `gsDirectContribution` 的求值顺序对齐。也核对固定 PBRT wavefront 实现：其 BRDF-first 后入 shadow queue 的数据寿命不同，不声称该项目直接采用了本候选。1080p 8 帧总共 592 个通道各差一个相邻半精度值，alpha 相同。

正序 PBR light packet GPU 下降 0.68%，关闭诊断 104.962881→104.291611 FPS（−0.64%）；反序 packet 下降 1.14%，关闭诊断 102.863053→102.630426 FPS（−0.23%）。没有稳定端到端收益，GLSL 及 16 份 SPIR-V 已全部恢复；不把单个子阶段的小幅下降作为保留依据。[原始窗口与制品证据](data/pbr-brdf-after-visibility-rejected-20261031.json)。

### 撤回：散射持久 pinned 异步计数读回

在先前 queued-only 实验之外，这次把每帧失败计数及诊断数据改为持久锁页内存，复制入队后再 signal，完整 fence 返回后才读取/校验，保留原错误发布边界。参考相同 NVIDIA cuda-samples pinned async copy 与 stream-ordered semaphore，并复用已验证的 PBR `HostMemory` 数据流。1080p 8 帧 HDR 全字节一致。

正序关闭诊断 105.143829→104.061151 FPS（−1.03%），反序 105.303441→106.222527 FPS（+0.87%）。仍没有稳定端到端收益，两个 Java 文件已完整恢复。不能把 CUDA 等待迁移到 Vulkan fence 计成收益。[两种顺序、逐帧与制品证据](data/scattering-pinned-readback-rejected-20261031.json)。

### 全灯枚举专用掩码：保留稳定收益

对照 PBRT-v3 commit `13d871faae88233b327d04cda24022b8bb0093ee`（BSD-2-Clause）`src/core/integrator.cpp` 的 `UniformSampleAllLights` 与 `UniformSampleOneLight`，分别处理全灯枚举和离散选灯。独立实现，没有复制外部代码。本项目只在已有严格 reusable-primary 证明成立、`0 < lightSamples <= 8`、灯槽上界不超过预算及 8 时选择专用 shader。证明使用包含空洞的槽上界，不使用活跃灯数量；不满足条件时保留原 reservoir/零预算枚举路径。

专用路径仍执行原灯光范围、颜色、强度过滤，按槽号递增连接灯光，用一个 uint 掩码替代跨每次阴影 query 存活的 `selected[8]`、`firstCandidates[8]`、`selectedFactors[8]`。穷举分支权重始终为 1，guide 仍选前两个满足条件的局部灯；采样位置、完整 PDF、贡献顺序、source ABI 和预算不变。目标是减少几何与阴影大头携带的状态，不把省略无用随机数本身当作主要收益。新增普通 cached / ray-query 两个 stage，覆盖 16 份 SPIR-V；所有 290 份着色器与源码精确匹配，旧 274 份不变。

| 测试顺序 | 关闭诊断对照 FPS | 候选 FPS | FPS 提升 | 散射 producer GPU 降幅 |
| --- | ---: | ---: | ---: | ---: |
| 正序，预热 360 帧 | 106.455790 | 109.736509 | 3.08% | 9.09% |
| 反序，预热 360 帧 | 104.404011 | 109.546636 | 4.93% | 10.40% |
| 延长预热 1200 帧 | 105.117945 | 108.010127 | 2.75% | 9.79% |

每窗为 3×120 个完成帧，所有测前 GPU 0 占用为 0%。稳态散射 producer 3.861443→3.483311 ms/帧。PBR 算法未修改，不把其同窗变化计为本候选收益。三组实际吞吐与目标 GPU 阶段均同向改善，保留；尚未达到 120 FPS。

最新稳态观测为 106.652579 FPS，49,506 个事件、零丢样，严格包含、互斥与求和通过。墙钟观测开销 1.27%；另做相同预热的相邻 off/profile 窗口，细粒度 shader 观测开销 5.41%，3 轮共 1,440 条记录零丢样。shader 内部排序为 opaque shadow、局部灯几何/衰减、采样与位置，采样时钟只作内部排序，不加进墙钟表。

| 排名 | 阶段 | ms/帧 | 每秒累计 ms | 占比 | 同窗降幅 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | PBR 完成 fence 等待 | 3.034751 | 323.664 | 32.366% | 增加 1.20% |
| 2 | 散射完成 CUDA 同步 | 2.655697 | 283.237 | 28.324% | 9.30% |
| 3 | 散射统计读回前同步 | 1.790649 | 190.977 | 19.098% | 5.38% |
| 4 | benchmark park | 0.511885 | 54.594 | 5.459% | 增加 10.29% |
| 5 | 散射 Vulkan fence | 0.359592 | 38.351 | 3.835% | 0.73% |
| 6 | PBR release 录制 | 0.088025 | 9.388 | 0.939% | 增加 16.22% |
| 7 | 最终帧录制提交 | 0.086389 | 9.214 | 0.921% | 增加 15.82% |
| 8 | 散射读回自身 | 0.070421 | 7.511 | 0.751% | 增加 9.94% |
| | 其余 74 项 | 0.778829 | 83.064 | 8.306% | — |
| | **互斥墙钟求和** | **9.376238** | **1000.000** | **100.000%** | **2.44%** |

正确性：1080p 连续 8 帧 HDR 与当前保留基线全字节一致；新增 `CudaPbrMediumProductionNativeSelfTest --light-selection` 33 帧矩阵，覆盖 0～9 灯、预算 0/1/全覆盖、点/聚光/方向光、近灯 guide、投影开关、删除内槽/复用、零强度/黑色/范围外灯。ray-query 与禁用 ray-query 两套冻结对照/候选各 33 帧均全字节一致。原生 PBR 31 项/43 帧、散射组合 15 项、camera 17 项独立参考、多次散射解析回归通过；9 份正式 validation 零 warning/error/drop，所有会话正常关闭。API 完整门禁的既有报告仍绑定 `79a8f20`，不将此次局部回归改称完整门禁重跑。[完整证据](data/scattering-exhaustive-mask-20261031.json)、[最新完整排名](data/scattering-exhaustive-mask-ranked-20261031.json)。

### PBR 灯光 packet 按严格证明去除通用 payload

继续修改总榜第一名内部第一项 PBR 灯光 packet。复用与散射相同的严格 reusable-primary 证明，只在均匀直接散射、无焦散且所有可能阴影命中均为不透明遮挡物的帧选择新 query stage，静态编译掉通用 traceRay 回退和 ray payload。无 ray-query 支持时这个 program 仍加载原 packet shader，保留 native boundary any-hit、正确的 push constant 布局及 pipeline 可见性组选择；其他场景不变。没有重排 BRDF、修改 packet、光线或跨 API 同步，区别于已拒绝的 BRDF 后置候选。

参考固定 Falcor `759aad033ff610fb0d82c74f7e0a508d0096d5f2`、BSD-3-Clause，`Source/Falcor/Scene/RaytracingInline.slang` 的 `traceSceneVisibilityRay<UseAlphaTest>`，独立适配静态专用化，没有复制外部代码。基线 `fdf599f`，新增 8 个 SPIR-V；全部 298 个变体与源码精确匹配。

| 测试顺序 | 关闭诊断对照 FPS | 候选 FPS | FPS 提升 | PBR 灯光 packet GPU 降幅 |
| --- | ---: | ---: | ---: | ---: |
| 正序，预热 360 帧 | 109.983411 | 111.784815 | 1.64% | 22.05% |
| 反序，预热 360 帧 | 108.809745 | 112.745779 | 3.62% | 22.63% |
| 延长预热 1200 帧 | 108.897102 | 111.541437 | 2.43% | 21.56% |

每窗 3×120 完成帧，测前 GPU 0 均为 0%。稳态 PBR light packet 从 1.356388→1.063995 ms/帧；三组局部与端到端同向改善，保留。散射未修改，其同窗变化保留在主表中，不归功于本轮。尚未达到 120 FPS。

最新同一稳态观测窗口 110.542771 FPS，49,506 个事件、零丢样，严格包含/互斥求和通过；观测开销 0.90%。GPU 子事件不加入墙钟求和。

| 排名 | 阶段 | ms/帧 | 每秒累计 ms | 占比 | 同窗降幅 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | PBR 完成 fence | 2.783357 | 307.680 | 30.768% | 8.36% |
| 2 | 散射完成 CUDA 同步 | 2.750385 | 304.035 | 30.404% | 增加 3.08% |
| 3 | 散射读回前同步 | 1.826074 | 201.859 | 20.186% | 增加 1.80% |
| 4 | 散射 Vulkan fence | 0.360046 | 39.800 | 3.980% | 增加 1.85% |
| 5 | benchmark park | 0.334132 | 36.936 | 3.694% | 5.90% |
| 6 | PBR release 录制 | 0.091460 | 10.110 | 1.011% | 增加 2.38% |
| 7 | 最终帧录制提交 | 0.082920 | 9.166 | 0.917% | 增加 2.29% |
| 8 | 散射读回自身 | 0.066243 | 7.323 | 0.732% | 7.11% |
| | 其余 74 项 | 0.751654 | 83.090 | 8.309% | — |
| | **互斥墙钟求和** | **9.046272** | **1000.000** | **100.000%** | **2.12%** |

正确性：1080p 连续 8 帧共 66,355,200 个 RGBA16F 通道中 592 个各差一个相邻半精度值，alpha 相同，未称为全字节一致；ray-query / 无 ray-query 两套 33 帧灯光矩阵均全字节一致。原生 PBR 31 项/43 帧、组合 15 项、camera 17 项独立参考、多次散射解析回归通过，未放宽阈值；7 份 validation 零 warning/error/drop，全部会话关闭。原完整 API 门禁仍绑定其既有源码，不冒称已随每个候选完整重跑。[完整证据](data/pbr-opaque-specialization-20261031.json)、[新完整排名](data/pbr-opaque-specialization-ranked-20261031.json)。

### 撤回：PBR 均匀 kernel 的 block 统计汇总

对照 NVIDIA cuda-samples `5443602d89ed99aede2e4b7bf329daddeadb320e`（BSD-3-Clause）的 `cpp/2_Concepts_and_Techniques/reduction/reduction_kernel.cu` / `reduce7`，以及本仓库 optical block 统计，独立将均匀 PBR 的失败/完成整数计数从每 warp 更新全局改成每 block 汇总；所有尾线程参加 barrier，输运与浮点累计不改。1080p 8 帧 HDR 全字节一致，PBR ABI 与源文件清单通过。

正序 kernel 0.807241→0.797066 ms（下降 1.26%），关闭诊断 111.768264→112.883543 FPS（+1.00%）；反序 kernel 0.805046→0.793753 ms（下降 1.40%），关闭诊断 113.000539→112.040968 FPS（−0.85%）。没有稳定端到端收益，CUDA 源码和 manifest 两个文件均精确恢复；不继续为已拒绝候选追加完整 GPU 回归。[冻结制品与正反序数据](data/pbr-statistics-block-rejected-20261031.json)。

### 撤回：采样准备与阴影查询串行拆分

参考固定 PBRT-v4 `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f`（Apache-2.0）`src/pbrt/wavefront/surfscatter.cpp` 的阶段划分原则，独立将严格穷举缓存路径的采样/PDF 准备和光源阴影查询分开。复用 source 缓冲和 compact-hit 保留字段，不增加 resident 分配或 CUDA 交接，新增独立 GPU timestamp。光源和采样循环、AoS 布局仍保持原状。

1080p 连续 8 帧 HDR 全字节一致，但关闭诊断 113.984849→95.902910 FPS（−15.86%），散射 producer 3.355643→4.666490 ms；其中新增准备阶段 0.803469 ms。拆分本身不足以抵消额外读写及调度，明确拒绝，全部生产源码及 SPIR-V 已恢复。冻结制品、binary patch、测量在 `build/scattering-sample-visibility-split`；未为明确退化候选跑额外原生回归。[原始证据](data/scattering-sample-visibility-split-rejected-20261031.json)。

### 撤回：缓存 PBR 连接包按灯分平面

参考固定 PBRT-v4 `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f`（Apache-2.0）`src/pbrt/wavefront/workqueue.h` 的 `WorkQueue : SOA<WorkItem>` 数据布局原则，独立把严格 cached-primary 路径的连接 packet/color 改为按灯分平面；producer 和 CUDA 使用相同 tile-local 映射，射线调度、积分和灯光求和顺序不变。1080p 连续 8 帧原始 HDR 与冻结对照全字节一致。

正序 CUDA PBR kernel 下降 5.48%，但 Vulkan 连接包增加 7.63%，关闭诊断 114.808490→114.478445 FPS（−0.29%）；反序 kernel 下降 5.39%、连接包增加 8.58%，关闭诊断 113.770162→115.213332 FPS（+1.27%）。生产端退化稳定，整帧收益方向不一致，未保留。3 个源码、清单及 24 份 SPIR-V 全部恢复 `e0b1dfe`；冻结 JAR、binary patch 和原始窗口保留于 `build/pbr-connection-planes`。未为已拒绝候选继续跑全量原生回归。[测量与恢复证据](data/pbr-connection-planes-rejected-20261031.json)。此候选和此前 light-major 调度均已拒绝，后续不要仅换布局名称重复试验。

### 不透明 PBR 连接贡献最终一次写回

继续处理 PBR 灯光 packet，将严格 opaque 专用路径的贡献保留在私有临时量，所有提前退出保留零贡献，最后统一写两份输出。避免先清零、有效照明后再覆盖。packet 清零、ambient 写入、BRDF、阴影、距离与贡献顺序不变；通用路径仍为原实现。对照固定 PBRT-v4 `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f`（Apache-2.0）`src/pbrt/wavefront/surfscatter.cpp` 在发布 `ShadowRayWorkItem` 前构造完整候选贡献的原则，独立适配本项目的 dense packet，没有搬入其队列或采样算法。

| 顺序 | 关闭诊断对照 FPS | 候选 FPS | FPS 提升 | 灯光 packet GPU 降幅 |
| --- | ---: | ---: | ---: | ---: |
| 正序 | 112.266612 | 113.138849 | 0.78% | 5.09% |
| 反序 | 112.254380 | 113.194423 | 0.84% | 6.59% |

每窗 360 帧预热、3×120 个完成帧，测前 GPU 0 均为 0%。两种顺序局部与吞吐均同向改善，按确定性小收益保留；未达到 120 FPS。最新反序观测 112.101528 FPS，49,492 事件、零丢样，严格包含与互斥求和通过，观测开销 0.97%。

| 排名 | 阶段 | ms/帧 | 每秒累计 ms | 占比 | 同窗降幅 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | PBR 完成 fence | 2.594633 | 290.862 | 29.086% | 1.79% |
| 2 | 散射完成 CUDA 同步 | 2.553033 | 286.199 | 28.620% | 2.19% |
| 3 | 散射读回前同步 | 1.743879 | 195.492 | 19.549% | 2.13% |
| 4 | benchmark park | 0.423181 | 47.439 | 4.744% | 10.03% |
| 5 | 散射 Vulkan fence | 0.370636 | 41.549 | 4.155% | 增加 6.36% |
| 6 | 最终帧录制提交 | 0.112431 | 12.604 | 1.260% | 增加 15.22% |
| 7 | PBR release 录制 | 0.098397 | 11.031 | 1.103% | 增加 0.94% |
| 8 | 散射 release 录制 | 0.081962 | 9.188 | 0.919% | 增加 18.27% |
| | 其余 74 项 | 0.942333 | 105.637 | 10.564% | — |
| | **互斥墙钟求和** | **8.920485** | **1000.000** | **100.000%** | **0.84%** |

1080p 8 帧 HDR、ray-query / 无 ray-query 两套各 33 帧矩阵，均与保留基线全字节一致。原生 PBR 31 项/43 帧、组合 15 项、camera 17 项独立参考、多次散射解析通过；7 份 validation 零 warning/error/drop、会话全关闭。全部 298 份 SPIR-V 与源码匹配。[完整证据](data/pbr-connection-single-write-20261031.json)、[新完整排名](data/pbr-connection-single-write-ranked-20261031.json)。
