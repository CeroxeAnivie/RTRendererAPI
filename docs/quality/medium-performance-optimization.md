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
- 同一候选至少采集 instrumentation-off 基线和 instrumentation-on profile。若插桩令完成吞吐或帧时分位数变化超过 2%，减少采样频率或改为轮换采样，并记录误差。
- 先完成一轮覆盖全链路的分阶段计时，再按证据深入。不得在看到热点后才发现首轮漏掉主要阶段或同步边界。

## 单瓶颈优化闭环

1. 冻结工作负载，保存源码提交、制品哈希、GPU 型号/功耗档、驱动、Java、温度/频率状态、输出设置和原始样本。
2. 先按架构画清数据流、队列、内存所有权、同步点、tile/批次和每帧临时数据，再根据全阶段表选端到端关键路径上最大的独占延迟。
3. 每轮只改一个已证实的瓶颈。若热点是 inclusive 阶段，先用首轮已准备的计时层次拆分其组成；不得先改旁支或同时做多个猜测性微优化。
   遇到方案、正确性或架构疑点，先查成熟开源实现，再决定改法；不能反复在本项目里猜到用户提醒。记录参考项目、固定 commit、文件、许可证、采用的逻辑和本项目必须保留的语义。优先检查架构和数据流能否消除热点工作，避免只修零散单点。
4. 同一候选跑正确性、场景 HDR 图像对照、帧时间与吞吐。确定性小收益可保留；噪声范围内的“提升”不算收益；确认退化则立即撤回该轮改动。
5. 更新延迟排名和架构说明。显著收益应对应最大热点下降；如果总帧时间没按预期变化，检查重叠、资源竞争和新的关键路径再继续。
6. 每个闭环保留可复现命令、原始输出和结论；已验证的改动按当前仓库发布/分支流程及时推送 GitHub，不把未完成验收的候选当作优化成果。

每次优化成功，立即给出前后对照表：原热点延迟、占比和排名、互斥阶段求和、关闭诊断后的原生完成吞吐/帧时、正确性结果、测前 GPU 占用和提交状态。收益用明确的百分比表示，并给出最新延迟排序；不能只口头说“有明显提升”。

发现根因、重大进展或实测显著变化，立即报告。每次 GPU 测量开始前记录 GPU 0 占用；超过 5% 时绝对延迟/FPS 只作参考，继续按相对排名定位和优化，不因用户其他 GPU 任务干扰而停工。当前约 7% 的诊断扰动不再阻塞热点优化，最终性能验收必须关闭诊断。

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
