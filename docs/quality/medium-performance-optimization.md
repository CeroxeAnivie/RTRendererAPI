# 介质渲染性能优化规范

本规范用于持续优化内置介质渲染路径，并作为后续性能工作的交接记录。优化由单一执行线程顺序推进，任何性能结论都绑定到固定场景、画质、GPU、驱动和制品。

最新恢复记录见文末“重启后正确性恢复与 micro-all 新基线”。此前默认均匀场景的高帧率不适用于新的全路径 micro-all 场景；当前主目标仍是后者的原生 180 FPS。

每次上传必须包含当前全部源码、测试、文档及生成的着色器改动，统一提交并推送到 `main`，上传后确认 `git status --porcelain` 为空。构建产物和 Python 字节码缓存按仓库忽略规则处理，不把源码改动留在工作区等待后续上传。

## 目标场景与优化终点

2026-10-31 用户将后续主性能基准和稳定 180 FPS 目标转到 `micro-all`：1920×1080、1 SPP，一个同时展示介质、材质、叠层和焦散的可见组合场景，按原生完成帧计数（预算 5.56 ms/帧）。原 54 个场景全部保留，追加这个场景后全矩阵为 55 个。SPP2/8、随机首界面与直接/多次散射等互斥请求策略用 `micro-checks` 小分辨率快速回归；这些检查不计入主场景 FPS。下表中原默认场景的历史目标不再作为后续主性能判断。

目标硬件为本机 NVIDIA RTX 5080 Laptop GPU。测量分辨率为 1920×1080，1 SPP，原生渲染；关闭帧生成、帧重建、时域累积和降噪。FPS 指 GPU 完成的原生帧，不计生成帧，不用提交次数或屏幕刷新率替代。

| 场景等级 | 固定工作负载 | 优秀目标 | 单帧预算 |
| --- | --- | ---: | ---: |
| 默认 | PBR 均匀直接散射，默认灯光和场景 | 稳定 180 FPS（原 120 FPS 已验收） | 5.56 ms |
| 扩展介质 | 密度网格或多次散射；分别记录，不混成一个结果 | 稳定 60 FPS | 16.67 ms |
| 焦散组合 | 表面/体积光子焦散与介质的具体预设 | 独立确定 | 按冻结后的场景单独设定 |

这些数值是优化目标，不是当前性能声明。2026-10-31 用户将默认场景的下一目标提高到 180 FPS；历史表格中的 120 FPS 仍是各轮当时目标。所有场景必须使用相同输出格式、相机、采样和积分预算做前后对照；任何工作负载参数变化都要另开结果行。

## 对外汇报口径

降幅列不得省略：没有同条件可信对照时填“待空闲复测”，不能用调用次数降幅代替耗时降幅。确定的工作量变化另表列出。

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
  **2026-10-31 最新指令：完成这次稀疏观测调整后，不再继续优化观测开销，立即落地已测出的大头修复；最终验收关闭诊断。当前稀疏采样开销 6.63%，已经满足接受线。**
- 先完成一轮覆盖全链路的分阶段计时，再按证据深入。不得在看到热点后才发现首轮漏掉主要阶段或同步边界。

## 分支与 worktree

2026-10-31 用户要求所有成果统一进入 `main`，后续直接在 `main` 顺序推进，不新建多余分支。负优化用精确恢复或反向提交撤回，保留冻结证据。子任务完成后先核对源码和结果已经保存，再归档并删除对应 worktree；不得丢弃未合入或未保存的工作。

## 单瓶颈优化闭环

1. 冻结工作负载，保存源码提交、制品哈希、GPU 型号/功耗档、驱动、Java、温度/频率状态、输出设置和原始样本。
   benchmark 在预热及计时窗口之前从实际 `RenderFrameRequest` 导出 `*-workload.json`，包含 maxEvents、opticalSamples、scatteringSteps、lightSamples、距离/cutoff、bounce 与接口采样方式。对外参数以此为准，不按构造器位置手工猜测。运行工具只收集当前 JVM 新写出的元数据，旧冻结 benchmark 没有该文件时不借用上次结果。
2. 先按架构画清数据流、队列、内存所有权、同步点、tile/批次和每帧临时数据，再根据全阶段表选端到端关键路径上最大的独占延迟。
3. 每轮只改一个已证实的瓶颈。若热点是 inclusive 阶段，先用首轮已准备的计时层次拆分其组成；不得先改旁支或同时做多个猜测性微优化。
   **2026-10-31 最新指令：持续循环修复 Vulkan 完成 fence 等待内部实测前三项，直到它在新的实测表中不再第一；覆盖上一轮内部前五项的范围。以其包住的实际工作减少为准，不能通过搬移等待或改名改变排名。正确性失败的窗口不作为最终基线。**
   **2026-10-31 用户限定特例：本次允许处理 PBR 完成 fence 等待内实测排名前 3 的事件，不扩展到其他阶段或后续轮次。是否追加拆解由已有数据能否指导修改决定；已有测量足够时直接修改、验证，不为拆而拆。每项改动仍独立保存对照、归因收益，负优化立即撤回。**
   **同日最新范围：同时处理当前总榜第一、第二内部实测前三项，直到这两项退出总榜前二，或关闭诊断达到 120 FPS。各候选仍单独冻结、验收、保留或撤回；不能仅移动等待位置或改事件名称来满足排名条件。**
   **用户随后扩大范围：当前总榜前三项均纳入，各自处理内部实测前三名。当前第二、第三项共同等待同一条散射链，必须映射回实际 GPU 工作归因，不能把同一项 GPU 工作或收益算两次；各候选仍单独验收。**
   **2026-10-31 最新授权收紧主线：集中处理当前总榜第一项，允许同时处理其内部实测前三名。当前对应不透明阴影查询、局部灯几何与衰减、采样与位置计算。必须落到第一名中的实际热点，不能仅因代码位于第一大阶段就认定修到了大头；每项候选仍分别记录收益、正确性和撤回决定。**
   **用户追加里程碑：关闭诊断达到 100+ FPS 后，先插入修复未开启降噪却过度模糊的问题；清晰度与正确性验收通过后，再继续 120 FPS 性能目标。能力闭合由一个独立子代理推进，GPU 验收及重型编译与性能测量串行安排。**
   遇到方案、正确性或架构疑点，先查成熟开源实现，再决定改法；不能反复在本项目里猜到用户提醒。记录参考项目、固定 commit、文件、许可证、采用的逻辑和本项目必须保留的语义。优先检查架构和数据流能否消除热点工作，避免只修零散单点。
   **2026-10-31 用户再次强调勤参考成熟项目：每个渲染逻辑或架构候选进入验证前，都对照相关成熟实现，记录采用和不适用的部分；不能只在失败后才查。边界数值回归须使用运行时输入，并覆盖三角形顶点排列和镜像，不能让常量折叠或单一顶点顺序掩盖生产路径差异。已知失败帧用固定 sequence 快速复现，通过后仍须跑完整窗口。**
4. 同一候选跑正确性、场景 HDR 图像对照、帧时间与吞吐。确定性小收益可保留；噪声范围内的“提升”不算收益；确认退化则立即撤回该轮改动。
   **2026-10-31 用户追加：大头候选失败后，必须先回到全局架构与数据流观察，再选择下一项改动；不能连续换细节盲试。对照成熟项目的阶段职责、生产/消费关系、中间数据存活期与跨 API 同步方式，列出本项目必要工作与可消除工作，并用已有实测核对预计影响范围。架构问题优先排查；仍以正确性和实际热点收益决定保留，不能只移动等待或名称。**
5. 更新延迟排名和架构说明。显著收益应对应最大热点下降；如果总帧时间没按预期变化，检查重叠、资源竞争和新的关键路径再继续。
6. 每个闭环保留可复现命令、原始输出和结论；已验证的改动按当前仓库发布/分支流程及时推送 GitHub，不把未完成验收的候选当作优化成果。

每次优化成功，立即给出前后对照表：原热点延迟、占比和排名、互斥阶段求和、关闭诊断后的原生完成吞吐/帧时、正确性结果、测前 GPU 占用和提交状态。收益用明确的百分比表示，并给出最新延迟排序；不能只口头说“有明显提升”。

发现根因、重大进展或实测显著变化，立即报告。每次 GPU 测量开始前记录 GPU 0 占用；超过 5% 时绝对延迟/FPS 只作参考，继续按相对排名定位和优化，不因用户其他 GPU 任务干扰而停工。观测开销以 10% 为接受线，最终性能验收必须关闭诊断。允许一个子代理仅补齐未归因差额的观测、验证与实测，完成这一件事后停止，不参与主线渲染优化。

**2026-10-31 用户最新规则：测前 GPU 0 占用低于 10%，可以比较绝对延迟、FPS和前后收益；达到或超过10%时，只相信本窗排名和占比，不用于绝对收益判断。覆盖此前5%与必须0%的门槛。受干扰时继续按当前热点和确定的工作量推进，不因此停工。180 FPS仍按固定负载、关闭诊断的原生完成帧验收。JFR有助于定位Java线程、分配、锁或驱动调用停顿时应使用，并与已有GPU事件计时对照；JFR诊断窗口不直接作为最终吞吐收益。**

**严禁只看代码不改代码。** 源码阅读只能用来确定下一项可验证动作；每轮分析都必须落到新增/调整测量、正确性检查或一个有证据的代码改动，并在同一轮拿到结果。若当前缺少关键数据，先补齐最小且低扰动的观测并运行它，不得以继续阅读、口头推测或整理计划代替推进；只有外部条件确实阻断时才说明阻断点。

不能为了 FPS 改低 SPP、散射步数、路径事件预算、介质容量或图像质量。每项降级若成为产品选项，必须显式命名、单独验收并保留原质量档位。

## 允许有成熟实现依据的微小误差优化

2026-10-31 用户明确允许为性能采用误差极小、非数学一致的优化，包括算法/逻辑层面的近似、低贡献工作省略、近似可见性或积分，不限于浮点运算设置；但必须有成熟项目采用对应逻辑的证据，不能自行猜近似。近似只是新增候选方向，不替代常规优化和架构优化；继续按实测热点、可消除的实际工作和收益排序选择，不因用户提出一种方向就只搜这一种。每个候选记录固定 commit、文件、许可证和采用边界，事先固定误差标准，再同时测 GPU 成本和关闭诊断吞吐。默认先用严格的线性 HDR 相对 RMS ≤0.1%、亮度能量偏差 ≤0.1%、最大通道绝对误差 ≤对照绝对值 P99 的 0.5%+1e-5；若候选不适用这套标准，应在测量前另定且说明依据，不能事后放宽来保留失败候选。原有独立解析、几何、失效输入和资源生命周期测试照常执行。无依据、误差超线或负优化立即撤回；不得暗中降低采样、步数或渲染分辨率；算法近似省去的查询/贡献必须明确说明适用条件、误差界和实测影响。

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

### 撤回：零自发光均匀 PBR 静态专用化

参考固定 PBRT-v4 `media.h` 的 `HomogeneousMedium::IsEmissive` 能力判断，为无边界、无密度且实际打包发射系数全零的 PBR 增加静态零发射类型。host 按实际 packed header 选择，kernel 再验证系数；保留同一 LuxCore 段函数、相同浮点累计与统计。1080p 8 帧 HDR 全字节一致，清单与 PBR ABI 检查通过。

正序关闭诊断 112.953455→114.118927 FPS（+1.03%），反序 113.205625→113.752116 FPS（+0.48%）；但 PBR kernel 分别 0.797154→0.827609 ms、0.803215→0.827964 ms，均未改善目标热点。离线 Driver API 资源查询确认原 kernel 76 个寄存器/线程、专用化 54 个，两者 local memory 均为 0；不能把寄存器减少当作时延下降。这轮没有通过热点验收，源码、选择器和清单已恢复；不宣称端到端的微小变化属于确定优化。[测量、资源与恢复证据](data/pbr-nonemissive-uniform-rejected-20261031.json)。新工具 `tools/CudaKernelResources.java` 可对冻结 core JAR 输出寄存器、local/shared bytes、Driver 建议 block size 及多种 block size 的占用，不渲染帧、不修改生产 kernel 配置。

### 撤回：只遍历有效灯掩码位

参考 Godot 4.4 `4c311cbee68c0b66ff8ebb8b0defdd9979dd2a41`（MIT）`servers/rendering/renderer_rd/shaders/forward_clustered/scene_forward_clustered.glsl` 的有效位遍历。独立把本项目穷举分支从逐槽测试改为 `findLSB` 消费掩码，保持原升序浮点求和；没有搬入 subgroup 行为或增加 feature。1080p 8 帧 HDR 全字节一致。正序关闭诊断 114.094673→113.116613 FPS，散射 producer 3.450299→3.493522 ms；反序 113.881277→112.411592 FPS、3.432177→3.519958 ms。两种顺序均退化，GLSL 和 16 个 SPIR-V 全部恢复，不保留。[两种顺序及制品证据](data/scattering-mask-walk-rejected-20261031.json)。

### 撤回：独立采样点调度与平面 source 布局

在已拒绝串行拆分之外，单独验收完整的 prepare→独立采样点 query 数据流：每个散射点独立 invocation，source 按 step 分平面，Vulkan 和 CUDA 消费者同时改变布局；保持相同位置、完整 PDF、阴影射线、采样数量和累计顺序。新增阶段 timestamp，并为并行诊断准备独占计数槽，避免原子争用。参考相同固定 PBRT-v4 wavefront 独立工作原则，没有复制外部代码。

1080p 8 帧 HDR 仍全字节一致。关闭诊断 114.130945→88.485387 FPS（−22.47%），散射 producer 3.396093→5.703312 ms；采样准备 0.637356 ms，CUDA 积分 0.661662→0.634317 ms 的下降不足以弥补生产退化。明确拒绝；源码、清单、全部 SPIR-V 和诊断结构均恢复，不保留中间架构，也未为其继续跑全回归。冻结制品与 patch 在 `build/scattering-point-planes`，[测量和恢复证据](data/scattering-point-planes-rejected-20261031.json)。后续不要重复这种每散射点扩展 dispatch；回到当前按像素复用状态的生产路径，以实测继续。

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

### 均匀缓存 PBR 复用颜色中的终点距离

对照固定 PBRT-v4 `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f`（Apache-2.0）`src/pbrt/media.h` 的 `HomogeneousMedium::SampleRay`：均匀介质使用区间与系数，不读取起点或方向。独立适配本项目已有严格 cached-primary 证明，仅当 endpoint packet、无边界、无初始体积、无环境密度网格时，以已有 camera diffuse.w 承载主命中距离，以 connection specular.w 承载原终点/无阴影标记；两个距离权重共用原 diffuse.w 中相同的真实灯距。省掉重复 camera packet dispatch、连接 packet 清零/写入及 CUDA packet 读取。保留原 tile 计划、驻留分配、LuxCore segment、积分裁剪、光线和预算。shader 继续验证原本由 packet walk 验证的起点；CUDA 校验紧凑格式适用条件，通用边界 walk 安全拒绝空 packet。

| 顺序 | 关闭诊断对照 FPS | 候选 FPS | FPS 提升 | PBR 整段降幅 |
| --- | ---: | ---: | ---: | ---: |
| 正序 | 114.913199 | 117.787680 | 2.50% | 3.69% |
| 反序 | 113.416226 | 117.600207 | 3.69% | 5.39% |

每窗预热 360 帧、3×120 完成帧，所有测前 GPU 0 占用为 0%。反序 PBR kernel 0.806190→0.625649 ms/帧，重复主命中 packet 0.126409→0；灯光包 0.966413→1.147625 ms/帧，增加 18.75%，如实计入 PBR 总成本。两种顺序的 PBR 整段及实际吞吐均改善，因此保留。灯光包回退仍需后续独立处理，不能把 CUDA 降幅冒充全段降幅。尚未达到稳定 120 FPS。

最新反序观测 117.687565 FPS，49,489 事件，零丢样，严格包含及互斥求和通过。正序观测开销 0.57%；反序 off/timings 差 −0.07%，处于窗口波动内，不称为负开销。GPU 子事件不加入墙钟求和。

| 排名 | 阶段 | ms/帧 | 每秒累计 ms | 占比 | 同窗降幅 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | 散射完成 CUDA 同步 | 2.439083 | 287.050 | 28.705% | 5.26% |
| 2 | PBR 完成 fence | 2.415891 | 284.320 | 28.432% | 5.67% |
| 3 | 散射读回前同步 | 1.689781 | 198.866 | 19.887% | 3.27% |
| 4 | benchmark park | 0.439884 | 51.769 | 5.177% | 3.29% |
| 5 | 散射 Vulkan fence | 0.363362 | 42.763 | 4.276% | 增加 0.60% |
| 6 | 最终帧录制提交 | 0.103020 | 12.124 | 1.212% | 增加 6.67% |
| 7 | PBR release 录制 | 0.095212 | 11.205 | 1.121% | 5.85% |
| 8 | 散射 release 录制 | 0.071111 | 8.369 | 0.837% | 5.88% |
| | 其余 74 项 | 0.879731 | 103.533 | 10.353% | — |
| | **互斥墙钟求和** | **8.497074** | **1000.000** | **100.000%** | **4.09%** |

散射算法本轮未修改，其同窗变化不归功于本候选。正确性：1080p 连续 8 帧 HDR 全字节一致；query/no-query 各 33 帧灯光矩阵、各 12 帧 cached-primary 矩阵（PBR/UNLIT、outline、SPP 1/2/8）均与冻结对照全字节一致。PBR 31 项/43 帧、组合 15 项（含低驻留跨 tile）、camera 17 项独立参考、多次散射解析回归通过；13 份正式 validation 零 warning/error/drop，所有会话关闭。298 份 SPIR-V 精确匹配，冻结 JAR 与本轮源码、class、SPIR-V 内容一致。API 完整门禁仍绑定 `79a8f20`，没有将这些专项回归改称全量重跑。[完整证据](data/pbr-color-endpoints-20261031.json)、[新完整排名](data/pbr-color-endpoints-ranked-20261031.json)。

### 撤回：只把起点有限值检查前移

在 `3286768` 上仅将 connection origin 的有限值检查移到 shadow query 前，尝试缩短起点跨查询的存活范围。首个同参数窗口灯光包 1.153850→1.164648 ms/帧，未降低目标成本；关闭诊断 117.115351→116.135697 FPS。源文件及 24 份 SPIR-V 立即恢复，不为无目标收益的候选追加全量回归，不宣称通过图像或原生验收。冻结 JAR 与 binary patch 留于 `build/pbr-origin-validation`。[原始窗口](data/pbr-origin-validation-rejected-20261031.json)。

### opaque 专用 PBR 静态选择颜色终点格式

在 `3286768` 上继续处理 PBR 内部第一名灯光包。`NATIVE_PBR_MEDIUM_LIGHTS_OPAQUE` 只由严格 reusable-primary、DIRECT_UNIFORM、无焦散路径选择，且均匀空密度计划必为 endpoint packet。opaque query shader 因此可以静态选择颜色终点和 primary field planes，在入口校验实际格式 flags=3、endpoint、零 capacity 后，编译掉循环内可选 packet 存取和 primary 布局判断。保留全部起点及终点有效性检查。无 query 的程序仍加载通用原 stage，使用运行时格式判断；其它场景不变。参考固定 Falcor `759aad033ff610fb0d82c74f7e0a508d0096d5f2`（BSD-3-Clause）`RaytracingInline.slang` 的 `traceSceneVisibilityRay<UseAlphaTest>` 静态选择原则，独立实现。

| 窗口 | 关闭诊断对照 FPS | 候选 FPS | 整帧 FPS 变化 | PBR 整段降幅 | 灯光包 GPU 降幅 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 正序，预热 360 | 117.406717 | 116.571379 | −0.71% | 3.50% | 12.46% |
| 反序，预热 360 | 115.838219 | 118.241384 | +2.07% | 5.99% | 13.07% |
| 延长预热 1200 | 116.753971 | 116.674062 | −0.07% | 4.93% | 10.03% |

每窗 3×120 完成帧，测前 GPU 0 均为 0%。保留依据是三组目标 GPU 阶段与 PBR 整段均缩短，符合确定性局部收益保留规则；整帧结果尚不稳定，不能给本轮记稳定 FPS 提升，更没有达到 120 FPS。延长预热灯光包 1.175866→1.057922 ms/帧；CUDA 未修改，同窗 kernel 0.643796→0.653529 ms 的变化仍完整计入总帧。只改变 8 份 opaque-query SPIR-V，每份 33544→32308 bytes。

最新延长预热观测 114.149489 FPS，49,503 事件、零丢样，包含与互斥求和通过。观测开销 2.21%，低于 10%；不将观测 FPS 冒充关闭诊断 FPS。散射没有修改，其同窗上升不归因于本候选，也未从表中隐藏。

| 排名 | 阶段 | ms/帧 | 每秒累计 ms | 占比 | 同窗降幅 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | 散射完成 CUDA 同步 | 2.683628 | 306.335 | 30.633% | 增加 5.27% |
| 2 | PBR 完成 fence | 2.395067 | 273.396 | 27.340% | 5.83% |
| 3 | 散射读回前同步 | 1.791532 | 204.502 | 20.450% | 增加 3.52% |
| 4 | benchmark park | 0.502223 | 57.328 | 5.733% | 增加 5.64% |
| 5 | 散射 Vulkan fence | 0.367115 | 41.906 | 4.191% | 增加 2.72% |
| 6 | PBR release 录制 | 0.084475 | 9.643 | 0.964% | 0.98% |
| 7 | 散射 release 录制 | 0.082449 | 9.411 | 0.941% | 增加 35.16% |
| 8 | 最终帧录制提交 | 0.080416 | 9.179 | 0.918% | 增加 3.27% |
| | 其余 74 项 | 0.773537 | 88.299 | 8.830% | — |
| | **互斥墙钟求和** | **8.760442** | **1000.000** | **100.000%** | **增加 1.12%** |

1080p 8 帧 HDR、query/no-query 各 33 帧灯光及各 12 帧 cached-primary 矩阵全部与本轮冻结对照全字节一致。四套原生生产回归通过，13 份正式 validation 零 warning/error/drop、会话均正常关闭；全 298 份 shader 与源码精确一致。没有放宽图像或解析误差阈值。[全部窗口和验收证据](data/pbr-static-color-endpoints-20261031.json)、[新完整排名](data/pbr-static-color-endpoints-ranked-20261031.json)。

### 撤回：终点携带起点无效标记

在 `1f85c7f` 上用正无穷终点承载非有限起点，交给原 CUDA finite 检查拒绝发布，尝试替代 shader 逐连接原子失败计数。首窗灯光包 1.017657→1.012266 ms/帧，仅变化 0.53%，关闭诊断 117.709364→117.000231 FPS，未证明明确目标收益。GLSL 和 24 份 SPIR-V 全部恢复，没有追加全量回归或宣称正确性通过。冻结制品和 patch 留于 `build/pbr-endpoint-validity`。[原始证据](data/pbr-endpoint-validity-rejected-20261031.json)。

冻结 benchmark 工具增加可选 `-Frames`、`-Rounds`，保留原 120 帧×3 轮默认值。长窗口验收可指定 `-Warmup 1200 -Frames 600 -Rounds 5`；仍记录相同 JAR SHA、初始 GPU 状态、真实请求预算和 CSV，禁止覆盖已有标签证据。

### 默认均匀直接散射达到 120 FPS：紧凑相机颜色与独立分块

原严格 cached uniform 路径已不读 packet，驻留计划仍按 packet 加 camera colors 保留临时区，且强制散射/PBR 使用相同 tile。现在在相同证明下，以每条路径的最终 diffuse lane 暂存 camera RGB/distance；CUDA 在本线程读完后才原位写最终 diffuse，camera specular 原值为零。connection colors 单独存储，省掉 packet 和 camera colors 临时区。两份完整帧输出均保留，Vulkan primary payload 不变。内部格式 bit 2 明确选择原位 camera lane，CUDA 校验它必须与无边界/无密度的颜色终点格式同时成立。

新的规划器在原 512 MiB 联合预算内选择不同 tile，优先最少交接，再优先减少散射块数。通常仅测试两三种交换数；极低预算在最多 64 次交换的有界搜索后保留精确的 balanced 回退。其它非 cached、密度、多散射和边界计划仍用原布局及约束。参考固定 PBRT-v4 `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f`（Apache-2.0）`workqueue.h` 中 `WorkQueue::Reset` 按阶段复用存储的原则；相机输出别名和独立 tile 算法为本项目实现，不声称上游采用相同布局。

默认 1080p、1 SPP、steps=8、maxEvents=16、lightSamples=4、2 个局部灯：散射 2,073,600 像素一块，PBR 1,036,800 像素两块，每帧交接 4→3。PBR 165,888,288 bytes，散射 364,953,728 bytes，加两份 65,536 bytes allowance 共 530,973,088 bytes，低于原 536,870,912 bytes 上限；预算和画质没有降低。

| 窗口 | 关闭诊断对照 FPS | 候选 FPS | FPS 提升 | PBR 整段降幅 | 散射整段降幅 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 正序，预热 360，360 完成帧 | 118.858534 | 123.377619 | 3.80% | 1.16% | 4.87% |
| 反序，预热 1200，360 完成帧 | 117.646063 | 132.357491 | 12.50% | 7.67% | 11.87% |

两组初始 GPU 0 占用均为 0%。反序散射准备 5.121578→4.513822 ms/帧、PBR 准备 2.710913→2.503096 ms/帧。主线程墙钟及目标整段都改善，保留。统计读回前同步在新结构中包含原先分散在两次交接中的大部分等待，其单项增长不能解释为散射总成本增长。

反序同参计时为 127.886787 FPS，40,146 事件、零丢样，观测相对关闭诊断开销 3.50%；包含、互斥和求和通过。

| 排名 | 阶段 | ms/帧 | 每秒累计 ms | 占比 | 同参降幅 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | 散射读回前 CUDA 同步 | 4.052186 | 518.221 | 51.822% | 增加 132.04% |
| 2 | PBR 完成 fence | 2.181315 | 278.961 | 27.896% | 6.90% |
| 3 | benchmark park | 0.550184 | 70.361 | 7.036% | 增加 9.46% |
| 4 | 散射 Vulkan fence | 0.186106 | 23.800 | 2.380% | 50.64% |
| 5 | 散射读回自身 | 0.076101 | 9.732 | 0.973% | 增加 18.53% |
| 6 | 最终帧录制提交 | 0.075113 | 9.606 | 0.961% | 10.08% |
| 7 | PBR release 录制 | 0.072132 | 9.225 | 0.922% | 23.99% |
| 8 | PBR CUDA kernel 入队 | 0.041917 | 5.361 | 0.536% | 4.57% |
| | 其余 74 项 | 0.584363 | 74.732 | 7.473% | — |
| | **互斥墙钟求和** | **7.819416** | **1000.000** | **100.000%** | **9.18%** |

最终验收延长到预热 1200 帧、5 轮×600 个完成帧。关闭全部诊断连续 3000 帧平均 **130.064872 FPS**，五轮分别 **130.18、129.57、130.18、130.25、130.14 FPS**，最低轮精确值 129.569156。每轮吞吐均超过 120 FPS；没有将轮均吞吐称为逐帧最低 FPS。保持默认场景、1080p、1 SPP 与全部质量预算；FG、降噪、重建、时域、动画、焦散和 CPU 回读关闭。

另一次相同长度计时验收为 128.559342 FPS，观测开销 1.17%，334,505 事件零丢样，严格包含和互斥求和通过。以下为最终长窗口排序；它没有同长度对照，不能借用短窗口的降幅列。

| 排名 | 阶段 | ms/帧 | 每秒累计 ms | 占比 |
| ---: | --- | ---: | ---: | ---: |
| 1 | 散射读回前 CUDA 同步 | 4.094610 | 526.400 | 52.640% |
| 2 | PBR 完成 fence | 2.211793 | 284.347 | 28.435% |
| 3 | benchmark park | 0.477189 | 61.347 | 6.135% |
| 4 | 散射 Vulkan fence | 0.182340 | 23.442 | 2.344% |
| 5 | PBR release 录制 | 0.078832 | 10.135 | 1.013% |
| 6 | 散射读回自身 | 0.076116 | 9.785 | 0.979% |
| 7 | 最终帧录制提交 | 0.073052 | 9.392 | 0.939% |
| 8 | PBR release 提交 | 0.040325 | 5.184 | 0.518% |
| | 其余 74 项 | 0.544252 | 69.969 | 6.997% |
| | **互斥墙钟求和** | **7.778509** | **1000.000** | **100.000%** |

正确性：1080p 连续 8 帧原始 HDR，query/no-query 各 33 帧灯光矩阵和各 12 帧 cached-primary 矩阵（PBR/UNLIT、outline、SPP 1/2/8）均与冻结对照全字节一致。PBR 31 项/43 帧、散射组合 15 项（含低驻留多块）、camera 17 项独立参考、多散射解析回归通过。新增规划器测试默认 1+2 分块、4 MiB 下 81 组 SPP/灯数/步数、所有输出范围和跨块全局身份；原 ABI 与来源合同通过。13 份正式 validation 日志零 warning/error/drop、会话全关闭；298 份 shader 源码/SPIR-V 一致。冻结 JAR 中改动的源码、SPIR-V 及三个 Java class 与被测构建一致。原 API 全门禁仍绑定既有源码，最终性能版本的全门禁另跑另记。

[完整性能与正确性证据](data/pbr-compact-residency-20261031.json)、[同参降幅表](data/pbr-compact-residency-ranked-20261031.json)、[最终长窗口完整排序](data/pbr-compact-residency-acceptance-ranked-20261031.json)。达到稳定 120 FPS 目标后停止本轮性能改动，继续最终 API 制品门禁。

### 最终性能版本完整 API 门禁完成

最终全量 `verifyApiClosure --offline --no-daemon --console=plain --continue` 于本地时间 2026-10-31 06:39:38 通过，耗时 7 分 40 秒。验收提交 `87fa6d11b9f641d66f034009cfd9f65eadc2f5f5`，tree `0329e4861978b8d0412b57a746c2ac15edbfc7c6`，开始与结束 tracked 源树干净。生产源码与性能验收提交 `05760a9` 相同；只修正了测试中旧的 primary 布局布尔断言，使其检查实际 bit 0，完整 receiver 字段检查和所有图像容差保留。

281 个任务、14 个必需组、76 个实际执行的 Java 原生任务全部通过，未使用子集选择器或排除任务。298 个 shader/SPIR-V、完整介质与统一 API、NVIDIA 功能组合、13 场景/104 帧展厅、独立 Maven 消费者和 2158 文件 ZIP 均通过。35 份本轮 Vulkan validation 日志共 66 次会话全部关闭，warning/error/drop 均为零。遇到的单次 GraalVM 编译器崩溃使用进程级 `-XX:-UseJVMCICompiler` 重试，未修改项目配置，最终全量运行无崩溃。首轮失败另存，不冒充成功。

本轮完成状态：默认均匀直接散射的 3000 帧性能验收为 130.064872 FPS，最低轮 129.569156 FPS；最新生产源码的完整 API 和发布制品门禁也已通过。本轮性能修改停止，后续新改动仍按本规范重新归因和验收。[最终门禁证据与制品哈希](data/api-closure-performance-final-20261031.json)、[完整原始任务报告](data/api-closure-performance-final-full-result-20261031.json)、[首轮失败记录](data/api-closure-performance-blockers-20261031.json)。

### 继续到 180 FPS：观测与首个撤回候选

用户将默认场景下一目标提高到 180 FPS；其余画质、预算、图像与单瓶颈闭环要求不变。当前全尺寸单块让 shader 诊断 pipeline 的覆盖过高，显式诊断现按每 32 帧中的连续 8 帧轮换，保留所有 bucket 相位，其余使用生产 shader，且不清零/读回诊断缓冲。相邻冻结窗口预热 1200 帧、3×240 完成帧：off 132.153392 FPS，细粒度 profile 124.464683 FPS，开销 6.177422%，695 条记录零漏样；三轮内部前三均为 opaque shadow、局部灯几何与衰减、采样位置。此项只是诊断工具调整，不计为渲染提速。

严格 opaque 路径的遮挡连接提前返回零候选：目标散射灯光 GPU 3.144229→3.265265 ms/帧，增加 3.85%；off 129.354390→129.984943 FPS 的 0.49% 波动不算收益。源码及 16 个 SPIR-V 已恢复，没有为已拒绝候选继续跑图像或全量回归。[冻结制品和原始窗口](data/scattering-occluded-return-rejected-20261031.json)。

### 撤回：compute ray-query 调度

参考 NVIDIA nvpro-samples/vk_mini_samples 固定 `af5c0a6b63095bb7b84b59dd4f88b718c28206a8`（Apache-2.0）的 ray-query compute 调度，将相同严格 opaque cached-uniform 散射估计器独立适配为 compute；64 线程没有目标收益，256 线程同样负优化。256 线程反序关闭诊断 130.653296→126.499080 FPS，散射 lights 3.195643→3.374146 ms/帧。全部生产 Java/GLSL/SPIR-V 已恢复；冻结 patch、新文件、参考和原始日志留在 build 试验目录。未对失败候选追加完整回归，也未声称其通过图像一致性。详见 [原始窗口](data/scattering-compute-query-rejected-20261031.json)。

### 缓存均匀路径共用串行驻留区

两个阶段使用同一 CUDA owner 和导出 allocation，按存活区间复用 hit、最终输出和临时区。默认 1080p 下散射/PBR 都是一块，交接 3→2，辐射恢复拷贝归零；同一线程先读后覆盖，所有跨 API ownership barrier 仍覆盖整个 allocation。未修改 GLSL、CUDA 积分、随机数或预算。参考 PBRT-v4 固定 `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f`（Apache-2.0）`workqueue.h` 的阶段存储复用原则，arena 布局为本项目独立实现。

正序 off 127.514190→139.849798 FPS（+9.67%），PBR 整段 2.580339→1.944339 ms（−24.65%）；反序 off 132.066318→137.502640 FPS（+4.12%），PBR 整段 2.492217→1.992112 ms（−20.07%）。各窗预热 1200 帧、3×240 帧；反序计时开销 1.25%。8 帧 1080p HDR、8 帧小图及 query/no-query 两组矩阵共 106 帧逐字节一致；PBR、组合、camera、多次散射专项通过，16 份 validation 零 warning/error。

发现旧采集工具通配符混入此前长窗口 round 4/5；已修工具并按 CSV 的三轮重算墙钟表，原 FPS/GPU 数据未受影响。详见 [完整证据](data/scattering-serial-arena-20261031.json) 和 [修正后的最新排名](data/scattering-serial-arena-ranked-20261031.json)。180 FPS 尚未达到。

### 撤回：PBRT CUDA fast-math 编译策略

参考固定 PBRT-v4 `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f`（Apache-2.0）`CMakeLists.txt:249` 的 `--use_fast_math`，只为严格缓存均匀路径的两份 CUDA kernel 启用，并隔离不同算术模式的 PTX/module 缓存。8 帧 1080p 的最大相对 RMS 为 0.000587%，亮度能量偏差 0.00000412%，通过事先固定的误差线；运行时 5376 通道及缓存隔离回归通过。但目标 PBR CUDA 0.586008→0.624507 ms，三轮均慢约 6.6%；散射 CUDA 0.639015→0.632566 ms 仅约 1%，off 139.115187→139.716659 FPS 仅 +0.43%，没有确定性目标收益。已立即恢复全部生产代码，保留误差比较工具和冻结证据，不继续为失败候选跑全量回归。[原始证据](data/scattering-fast-arithmetic-rejected-20261031.json)。

### 撤回：有界低贡献连接省略

对照 OSPRay `1fc364776c5cbbf8b1100dbf321cc3d40da41311`（Apache-2.0）`Renderer.cpp:43` 默认 minContribution=0.001 和 `scivis/volumes.ispc:225/276/343` 的低贡献终止。独立适配为严格 opaque cached exhaustive 路径的局部灯连接上界：考虑相机 T、散射、inverse PDF、无表面遮挡时的光照和 Beer/phase；将每路径每通道 0.001 分摊给固定步数与连接数。8 帧 1080p 相对 RMS 最大 0.003576%，亮度能量偏差 0.000339%，通过事先误差线。但排除主射线后的 lights 2.474456→2.634219 ms，三轮均退化；散射整段 4.609648→4.715325 ms，off 138.143426→137.802385 FPS。已恢复全部 GLSL/SPIR-V，不继续为失败候选追加全量回归。成熟逻辑和微小误差本身不足以保留负优化。[冻结证据](data/scattering-contribution-cull-rejected-20261031.json)。

补充计时范围：原 cameraScatteringLights capture 在 split primary 前开始，包住 primary 和 lights；同窗细分时必须减去单独的 primary capture，不能将两个 inclusive 项相加。主表互斥墙钟及原生 FPS 不受影响。


### 共享散射 source 改为步数平面：保留小幅确定收益

仅在严格共享均匀 arena 中，source 从每路径连续步数改为每步连续路径；Vulkan 写入与 CUDA 读取同步调整，以实际 tile 路径数处理末块。source 值、随机数、光线、积分顺序和预算不变。参考固定 PBRT-v4 `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f`（Apache-2.0）`src/pbrt/wavefront/workqueue.h` 的 SOA 连续工作字段原则，独立适配跨 API ABI。

正序 off 139.539054→144.007431 FPS（+3.20%），反序 137.960905→138.909042（+0.69%），灯光 GPU 两窗分别下降 2.48%、2.11%。回归发现通用 shader 把 multiple ABI 的 bounces=2 误读为 planar bit；已补 direct ABI magic 判断，保留首轮失败日志。修复后最终对照 off 135.729694→140.608704 FPS（+3.59%），纯灯光 2.498717→2.394619 ms（−4.17%），散射 CUDA 0.644610→0.625895 ms，散射整段 4.620773→4.493228 ms。观测开销 1.59%，58,686 个墙钟事件零丢样。

修复后 PBR 31 项/43 帧、组合 15 项、camera 17 项独立参考及多次散射解析通过；query/no-query 灯光与消费者矩阵 90 帧，加 1080p/小图 16 帧，共 106 帧全字节一致。14 份正式 validation 无 warning/error。最终生产提交为 `39fb8cc`；候选制品不与后来未验收的阴影排序改动混用。最近细粒度观测 695 条路径零漏样，阴影、局部灯几何、采样位置三项合计 72.13%。

证据：[性能与正确性](data/scattering-source-planes-20261031.json)、[互斥墙钟排名](data/scattering-source-planes-ranked-20261031.json)、[第一名内部排序](data/scattering-current-internal-ranked-20261031.json)。后续按用户最新授权集中处理总榜第一内部前三，不继续围绕尾部 source 地址与写回做微优化。


### 撤回：阴影查询的屏幕 Morton 排序

针对总榜第一内部第一名，参考固定 Falcor `759aad033ff610fb0d82c74f7e0a508d0096d5f2`（BSD-3-Clause）`Source/RenderPasses/PathTracer/GeneratePaths.cs.slang` 的 16×16 屏幕 Morton 调度。只重排严格穷举 cached query 的像素执行顺序，保留全部像素身份、源索引、随机数与射线；完整 16 行重排，末尾 8 行及非整行 tile 保留原映射。1080p 8 帧 HDR 全字节一致，但灯光 GPU 三轮均增加，均值 2.438277→2.456899 ms（+0.76%）；off 136.693355→137.565750 的 +0.64% 不算目标收益。全部 GLSL/SPIR-V 已恢复，不追加失败候选的完整回归。[冻结证据](data/scattering-shadow-morton-candidate-20261031.json)。


### 撤回：缓存单个无阴影方向灯贡献；返回架构审查

针对散射内部第二名，仅将一个有效且不投影阴影的方向灯贡献每路径计算一次，在每个积分点仍按原槽位累加。参考固定 Falcor `759aad033ff610fb0d82c74f7e0a508d0096d5f2`（BSD-3-Clause）`LightHelpers.slang::sampleDirectionalLight` 的位置无关方向与强度。仅保持一个 RGB 和槽号跨阴影查询，区别于已拒绝的整套小灯参数数组缓存。1080p 8 帧 HDR 全字节一致，但散射灯光三轮均慢，均值 2.416707→2.588441 ms（+7.11%）；off 138.053365→137.869682 FPS。全部生产 GLSL/SPIR-V 恢复。[原始证据](data/scattering-directional-invariant-candidate-20261031.json)。

用户要求大头失败后先跳出局部，当前暂停继续试细节，检查完整中间 source 数据、Vulkan/CUDA 串行生产消费、shader 跨查询状态与已有真实 GPU 工作成本。不能据“少算几次”或成熟实现采用该逻辑就声称提速；寄存器压力目前只是待查假设，不作已证实根因。

### 当前优先级：宏观架构先行

用户要求现阶段暂时宏观优先。暂停连续尝试 shader 算式细节，优先审查完整帧依赖、生产消费时序、跨 API 提交/ownership 交接、CPU/GPU 等待和中间缓冲生命周期。大头候选失败后必须回到这张全局依赖图，对照成熟项目，再决定下一轮；不能仅凭主观判断断言架构已被证实占主导。

当前待验收候选把 cached uniform 散射与 PBR 的 Vulkan 生产合成一次 ownership release，在同一 CUDA stream 按原顺序执行两个 kernel，再一次 signal/fence 完成。PBR 灯光生产只依赖 Vulkan 主命中，不依赖散射 CUDA 结果；为提前生产，两个 header 和两份 scratch 同时保留，camera RGB/distance 与散射 radiance 各有明确所有者。默认完整批次占用 530,907,552 字节（含原 65,536 字节 allowance），仍在原 512 MiB 预算内；不足则回退已验收的串行 arena。输出偏移仍对 allocation 计数，第二个 header 的 wire pointer 转成相对自身的偏移。

成熟参考：NVIDIA/cuda-samples 固定 `9c688d7ff78455ed42e345124d1495aad6bf66de`，BSD-3-Clause，`Samples/5_Domain_Specific/vulkanImageCUDA/vulkanImageCUDA.cu::cudaUpdateVkImage`。其两次 kernel launch 在同一 stream 的 external wait/signal 之间顺序执行；本项目独立适配缓冲和原有 ownership barrier，不引入数学近似。必须保留两个 kernel 的独立 event 计时、两份失败计数、完整 HDR 比较及低预算回退验证。两段工作会重新归入一个等待边界，排名转移不计收益；判定依据是完整链路、交接次数及关闭诊断 FPS。此处仅记录候选，不表示已通过或已提速。

### 保留：uniform 散射与 PBR 一次交接

生产提交 `db2aca4`，测试 fixture 修正 `4898381`。每帧 ownership exchange 从 2 次降为 1 次，两个 kernel 的 event 计数仍分别为 1。正序关闭诊断 143.658934→148.231853 FPS（+3.18%），反序 139.936412→144.347279 FPS（+3.15%）；各窗口预热 1200 帧，3×240 完成帧，初始 GPU0 占用均为 0%。反序完整散射+PBR prepare 6.496115→6.084459 ms/帧（−6.34%）。这是可重复的交接收益，未达到 180 FPS。

反序计时窗口互斥墙钟表如下。原散射 stream 同步和独立 fence 被合并进唯一 PBR fence，不能把原排名消失当成 GPU 工作消失，也不能把该 fence 的同比增幅解释为负优化。

| 排名 | 阶段 | ms/帧 | 每秒累计 ms | 占比 |
| ---: | --- | ---: | ---: | ---: |
| 1 | 合批完成 fence 等待 | 5.579255 | 794.537 | 79.454% |
| 2 | benchmark park | 0.494302 | 70.393 | 7.039% |
| 3 | 最终帧录制提交 | 0.172586 | 24.578 | 2.458% |
| 4 | 合批 release 录制 | 0.093929 | 13.376 | 1.338% |
| 5 | 两个 CUDA kernel 入队 | 0.076401 | 10.880 | 1.088% |
| | 其余 47 项 | 0.605543 | 86.235 | 8.623% |
| | **互斥墙钟求和** | **7.022016** | **1000.000** | **100.000%** |

该等待所覆盖的最大实际 GPU 工作仍是纯散射灯光查询 2.432998 ms/帧（inclusive lights 3.221553 减 primary 0.788555），随后是 PBR 灯光 0.955043、主命中 0.788555、散射 CUDA 0.604974、PBR CUDA 0.573909 ms/帧。它们属于 GPU 证据，不加到上面的墙钟表中。细粒度 profile 695 条记录零漏样，开销 2.18%；内部前三仍是 shadow 31.19%、局部灯几何/衰减 27.61%、采样位置 13.41%，合计 72.21%。后续宏观优化优先处理这一散射查询执行路径。

正确性：CPU ABI 精确预算/低预算回退/相对偏移和非法布局通过；CUDA component 包含 SPP2、1/8/64 步、514 条非整 block 路径、camera lane 不可变和非法 flag 拒绝。1080p 8 帧、query/no-query 灯光及 cached-primary 矩阵 90 帧，共 98 帧 HDR 全字节一致。PBR 31 项/43 帧、组合 15 项、camera 独立参考 17 项、多次散射解析回归通过；13 份 validation 日志会话均关闭，零 warning/error/drop。完整 API 门禁尚未重新执行，不能把此前全门禁套用到本提交。

[冻结制品、正反序窗口与正确性证据](data/scattering-joint-batch-20261031.json)、[完整互斥墙钟排名](data/scattering-joint-batch-ranked-20261031.json)。


### 重启后正确性恢复与 micro-all 新基线

生产修复 `741ac22` 保留边界行列式求和与除法中的低位残差，避免三角形顶点排列改变入/出界事件次序；`71e4e47` 在几何误差带内沿入射线段保守构造散射起点，避免精确内点舍入到体积外后重复入栈。严格栈检查、散射预算、PDF 和贡献权重均保留。依据固定 PBRT-v4 `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f`（Apache-2.0）`shapes.cpp` 的稳健交点与 `ray.h` 的误差界/舍入原则独立适配；不能声称 PBRT 原样采用本项目散射起点实现。

重启后重建有效制品，并重新通过六种顶点排列边界回归、CUDA 散射起点独立回归、固定帧 57、72 帧 micro-all 完整窗口、边界原生 HDR 27 组、光学原生 HDR 46 组/152 帧，以及 micro-checks 七场景/46 功能检查。这里仅声明本轮相关路径通过，不代表完整 API 门禁已重新运行。

`9a9c753` 制品，1920×1080、1 SPP、maxEvents=24、opticalSamples=2、scatteringSteps=8、scatteringBounces=2、photons=65536；关闭 FG、降噪、重建、时域和 CPU 读回。两窗均 8 帧预热、4×16 测量。稳定诊断 JVM 为 `ReservedCodeCacheSize=256m -UseJVMCICompiler ActiveProcessorCount=4 TieredStopAtLevel=1`，因此以下不能充当正常生产 JIT 最终验收。初始 GPU0 占用 2%。关闭观测 591.390227 ms/帧、1.690931 FPS；开启观测 611.670631 ms/帧、1.634867 FPS，开销 3.4293%。1,643,763 条区间零丢样。

| 排名 | 互斥 host 阶段 | ms/帧 | 每秒累计 ms | 占比 |
| ---: | --- | ---: | ---: | ---: |
| 1 | Vulkan 完成 fence 等待 | 304.600523 | 497.981279 | 49.7981% |
| 2 | CUDA stream 同步 | 194.902239 | 318.639197 | 31.8639% |
| 3 | CUDA kernel 入队 | 25.064188 | 40.976608 | 4.0977% |
| 4 | Vulkan release 录制 | 16.248348 | 26.563885 | 2.6564% |
| 5 | CUDA semaphore 等待入队 | 11.666298 | 19.072844 | 1.9073% |
| 6 | Vulkan consumer 提交 | 11.470927 | 18.753437 | 1.8753% |
| | 其余 64 项 | 47.718108 | 78.012750 | 7.8013% |
| | **互斥求和** | **611.670631** | **1000.000000** | **100.0000%** |

同窗嵌套 GPU 证据：continuation trace 174.093372 ms/帧、923.656 次/帧；CUDA optical step 149.104245 ms/帧、939.656 次/帧。它们不能再加进上表。第一名内部前五项的互斥 shader 排名尚待采集，不以这些嵌套项凑数。

恢复 opt-in shader 观测时保留两轮 queued 调度，将两份采样数据异步下载到预分配 pinned host 内存，在原有同步之后汇总；记录偏移取当前 `plan.bytes()`。主机仅遍历所选组并按 stage 汇总，避免逐样本字符串和锁。默认关闭，无新增生产观测工作。工具支持独立 `normal/stable` JVM 模式并记录实参；冻结 benchmark 必须同时包含 demo main 与 test 类。后续先测观测开销，再对照 PBRT wavefront 活跃队列检查全尺寸 inactive 调度，不能提前宣布性能收益。

[本轮正确性、制品哈希和观测开销](data/medium-micro-recovered-correctness-20261031.json)、[完整互斥排名](data/medium-micro-recovered-ranked-20261031.json)。


### 保留：压缩 optical continuation 活跃路径

对照 PBRT-v4 固定 `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f`（Apache-2.0）`src/pbrt/wavefront/workqueue.h:40–135` 的 `Push`、`Reset`、按队列 Size 限定工作，以及 `integrator.cpp` 的 current/next 队列生命周期，独立适配 Vulkan/CUDA 共享缓冲。两个队列只保存原始 path ID，不搬动光学状态；CUDA 每 warp 一次原子保留输出区间，下一轮 Vulkan/CUDA 使用同一队列。保留完整容量 launch、原始像素/SPP/随机身份、所有交接屏障及严格错误检查。内存规划计入双队列与对齐，reducedCounters 关闭或 kernel clock profile 使用原路径。属性 `top.ceroxe.rt.medium.cuda.opticalActiveQueues=false` 可作同制品精确对照。

该改动针对总榜第一内部的 inactive 状态读取，以及活跃连接射线的调度稀疏性。首次全路径启用被已有显存门禁拦住，已限定到显式边界或连接输运，保留无边界紧凑状态的单 tile 容量。生产 helper 的 8 种容量、部分 warp、尾 block、空队列、唯一 ID、ping-pong 与容量保护全部通过；固定失败帧 57、72 帧完整窗口、46 组/152 帧光学原生、27 组边界原生、7 场景/46 功能检查通过，4 份 validation 均关闭且零 warning/error/drop。

生产 `610698e`：正序关闭诊断 1.723912→2.031356 FPS（+17.834%），反序 1.683693→2.012713（+19.542%）。同窗 continuation GPU 173.660205→124.555536 ms/帧（−28.276%），CUDA step 151.476351→112.549426 ms/帧（−25.698%）；调用数稍增，未削减工作预算。互斥墙钟 601.451784→503.953444 ms/帧，fence 第一项 257.824683 ms/帧，下降 16.099%；仍居第一，180 FPS 尚未达到。观测开销 2.371%。

两帧 1080p HDR 中，一帧逐字节相同，另一帧仅一个 RGB 分量相差一个 half ULP（0.0009765625）；相对 RMS 0.00009648%，亮度能量偏差 0.0000002173%，通过既定微误差线。原版重复与候选关闭队列均逐字节一致；不把结果描述为全字节一致，也不把这点差异归因于尚未证实的具体浮点指令。参考依据是上文 PBRT 的队列重排，误差阈值沿用此前规范，未临时放宽。

[性能、HDR 和原生回归证据](data/medium-optical-active-queue-20261031.json)、[完整新排名](data/medium-optical-active-queue-ranked-20261031.json)。下一候选仍处理第一名：利用一个输入最多一个后继的队列不增性，以前一完成批次的活跃数限制下一对 Vulkan/CUDA 实际 launch 范围；保留完整状态/命中 stride 和诊断记录范围，不新增同步。待独立验证，不预报收益。

### 保留：按存活路径数缩小实际 launch

`0c14504` 保留完整状态/命中 stride 和诊断范围，只收紧下一对 Vulkan/CUDA 发射数量。对照 PBRT 上述按队列 Size 工作的逻辑，一个输入最多产生一个后继，因此上轮完成计数是安全上界，无新增同步。关闭诊断正序 2.031356→2.069786 FPS（+1.892%），反序 1.996878→2.017533（+1.034%），确定性小收益保留。HDR 通过既定微误差线，固定帧 57、72 帧完整窗口、光学 46 组/152 帧及 micro-checks 7 场景/46 功能通过。

开启计时 497.302294 ms/帧；fence 等待仍第一，251.240531 ms/帧、505.206862 ms/秒；CUDA 同步第二，160.674503 ms/帧。未达到 180 FPS，继续处理第一名。见[本轮独立证据](data/medium-optical-bounded-launch-20261031.json)、[完整排名](data/medium-optical-bounded-launch-ranked-20261031.json)。

### 保留：把空 acquire 合入真正的生产提交

对照 NVIDIA cuda-samples `9c688d7ff78455ed42e345124d1495aad6bf66de`（BSD-3-Clause），`Samples/5_Domain_Specific/vulkanImageCUDA/vulkanImageCUDA.cu` 的 `cudaUpdateVkImage` 和 `submitVulkanCuda`：CUDA 外部信号由真正的 Vulkan 消费提交等待。本项目独立适配双轮 timeline 交接，不声称上游原有相同 API。

目前每对两轮输运有第三次仅 acquire 所有权的空 Vulkan 提交。候选在完成第二轮 CUDA 后暂留 external ownership，将 acquire 与上一轮 timeline wait 合入下一次真正 producer；整帧发布前仍显式 acquire。保留两处已经通过 RT epoch 回归的 CUDA stream 同步、所有必要 ownership barrier、采样和输运预算。属性 `top.ceroxe.rt.medium.cuda.opticalRetainedOwnership=false` 可回到原行为。成功标准同时包括正确性、真实提交次数减少和关闭诊断整帧收益；只改变等待归属不算成功。扩展原生回归覆盖连续 retained pair、显式 acquire、retained/legacy 切换及两种模式下四阶段回调故障。

首版数据/HDR 一致，但 validation 报命令缓冲仍 pending，未接受该版性能结果。修正为最后 CUDA 同步后，host 等待第二轮 Vulkan 已经发出的 timeline 值，正式确认命令缓冲可重用；它不新增 GPU 提交，独立记录 `VULKAN_COMMAND_REUSE_WAIT`，不能隐藏进录制成本。仍须零 validation 错误后才验收收益。

最终生产制品 `8cd988e`：扩展交接原生回归通过；4 个 validation 生命周期全部关闭，零 warning/error/drop；光学原生 46 组/152 帧、micro-checks 7 场景/46 功能、固定 sequence 57、完整 micro-all 8 预热+64 测量窗口通过。两帧 1080p HDR 与前一生产制品逐字节一致。独立工具 `tools/CheckVulkanValidation.py` 会拦住 warning/error、日志未关闭和丢日志，避免只依据自测退出码认定成功。

正序关闭诊断 2.068698→2.181611 FPS（+5.458%），反序 2.018011→2.167707（+7.418%）。两组均 8 帧预热、4×16 帧测量，测前 GPU0 为 0%。每帧 release 提交维持 478.21875 次，consumer 提交 948.4375→479.21875 次，实际减少 469.21875 次空提交；fence 等待次数 478.21875→9 次，新增 470.21875 次已完成 timeline 的命令重用确认。所有采样与输运预算不变。

以下将相同阶段名的互斥 host 区间合并，长窗口归一到每秒；不是 GPU 利用率。此前 497.302294→468.605123 ms/帧（−5.771%）。CUDA stream 同步接住了此前在 Vulkan fence 中发生的等待，不能把 fence 的 99.549% 降幅说成 GPU 工作下降。实际 continuation trace 为 123.849519→121.718995 ms/帧，CUDA optical step 为 113.017442→111.942490 ms/帧，调用数均相同；这轮主要收益是交接调度减少。当前同名合并榜 fence 已从第 1 降至第 12，CUDA stream 同步为新第一；180 FPS 尚未达到。观测开销 2.231%，不再调整观测开销。

| 排名 | 互斥阶段 | ms/帧 | 每秒累计 ms | 占比 | 相对上一轮 |
| ---: | --- | ---: | ---: | ---: | --- |
| 1 | CUDA stream 同步 | 389.591122 | 831.384683 | 83.1385% | 等待归属改变，增加 140.769% |
| 2 | CUDA kernel 入队 | 24.004256 | 51.224912 | 5.1225% | 增加 12.376% |
| 3 | Vulkan release 录制 | 9.435692 | 20.135700 | 2.0136% | 下降 28.094% |
| 4 | Vulkan release 提交 | 8.043913 | 17.165652 | 1.7166% | 增加 10.635% |
| 5 | CUDA semaphore 等待入队 | 7.976064 | 17.020864 | 1.7021% | 增加 0.783% |
| 6 | Vulkan consumer 提交 | 5.582927 | 11.913926 | 1.1914% | 下降 48.581% |
| 7 | CUDA semaphore 信号入队 | 5.433277 | 11.594574 | 1.1595% | 下降 4.319% |
| 10 | Vulkan 命令重用确认 | 2.245903 | 4.792741 | 0.4793% | 新增完成确认 |
| 12 | Vulkan 完成 fence 等待 | 1.138475 | 2.429498 | 0.2429% | 下降 99.549%，含等待归属变化 |
| | 其余 35 项合计 | 15.153495 | 32.337451 | 3.2337% | — |
| | **互斥求和** | **468.605123** | **1000.000000** | **100.0000%** | **下降 5.771%** |

[完整证据、制品哈希、正反序原始样本与 validation](data/medium-optical-retained-ownership-20261031.json)、[73 条区间路径及 44 个合并阶段的完整排序](data/medium-optical-retained-ownership-ranked-20261031.json)。该结果仅表示本轮 fence 退出第一且整帧收益通过验收，不表示 shader 内部三个热点已经分别完成全部优化，也不表示原生 180 FPS 达标。

### CUDA 同步主线：删除零贡献连接轮次（候选）

用户要求继续以 micro-all 原生 180 FPS 为终点，必须减少实际延迟，不能搬移等待。当前生产制品重采样：连接路径 stage16 占 shader 互斥抽样时钟 61.075%，普通 continuation trace stage18 为 17.130%，inactive 读取 stage14 已降至 3.453%；这些是内部定位数据，不是整帧墙钟占比。现有观测直接复用，不再为观测开销返工。

参考 PBRT-v4 `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f`（Apache-2.0）`src/pbrt/wavefront/surfscatter.cpp:280–285` 在零灯光或零 BSDF 时不排阴影工作，`media.cpp:285` 同样只为非零光源排队。本项目每个零贡献连接原先仍占一次 Vulkan/CUDA 轮次。候选在 shader 中仅跳过 RGB 三通道严格等于零的中间连接；逐个保留 ordinal 和 epoch 推进，最后一个连接仍交 CUDA 完成照明、相函数、状态与随机数推进。体积焦散首连接 gather 保留。没有近似阈值、采样削减或同步迁移；属性 `top.ceroxe.rt.medium.cuda.opticalSkipEmptyConnections=false` 可同制品对照。

验收同时要求：HDR 微误差线、完整窗口和原生功能回归、validation、连接/step 发射次数减少，以及关闭诊断正反序整帧收益。尚未验收，不预报收益。

`5f3bde8` 正确性通过：两帧 HDR 逐字节一致、固定帧 57、72 帧完整窗口、46 组/152 帧光学原生、7 场景/46 功能、3 个 validation 生命周期零 warning/error/drop。同一前 32 帧工作计数：CUDA step 941→871.0625 次/帧，RT continuation 925→855.0625 次/帧，实际删去 69.9375 轮/帧。测前 GPU 占用非零，**不声明净性能收益或负优化**，停止无效绝对 FPS 对照；保留独立开关的候选，继续真实热点主线，待干净 GPU 验收。[工作计数与正确性](data/medium-empty-connections-20261031.json)、[受占用窗口排名（仅占比有效）](data/medium-empty-connections-loaded-ranked-20261031.json)。

### CUDA 同步架构候选：独立二进制信号量串联两轮

回到 NVIDIA cuda-samples 固定 `9c688d7ff78455ed42e345124d1495aad6bf66de`（BSD-3-Clause）的 `cudaUpdateVkImage`/`submitVulkanCuda`：同一 stream 上外部 wait→kernels→signal，再由真正 Vulkan 提交等待，不在中途调用主机完成同步。此前本项目 timeline 双轮删除中间同步曾失败，不能在旧协议上盲删。新候选为两轮分别使用独立 binary semaphore 对，保留 release/acquire barrier、末尾 CUDA 同步、pinned 统计读取和发布验证；retained 模式把 fence 放在真正的中间 Vulkan 提交上，在最后 CUDA 同步后确认命令可重用，不新增空提交。跨模式及通用交换先显式恢复所有权，故障路径必须能 drain。

属性 `top.ceroxe.rt.medium.cuda.opticalBinaryPairs=false` 回到已验证 timeline 协议。真实 RT epoch、连续 retained、classic/retained/binary/timeline 交替、四阶段故障清理与零 validation 问题是先决门禁。只有同步次数/真实 GPU 空隙及关闭诊断整帧收益能支持性能结论，等待归属变化不能算收益。当前为待验证候选。


`fbf8ca8` 正确性门禁通过：独立 binary 两轮的真实 RT epoch、协议/所有权切换、四阶段故障清理通过；interop 1 个及 optical 3 个 validation 生命周期零 warning/error/drop；两帧 1080p HDR 逐字节一致、固定 sequence 57、8 预热+32 测量 timings 窗口、46 组/152 帧光学原生、micro-checks 7 场景/46 功能通过。尚未完成本候选独立的 8+64 off 窗口及干净 GPU 正反序性能验收。

确定减少的是主光学路径 CUDA stream 同步次数：871.0625→435.53125 次/帧，下降 **50.00%**；上一零贡献候选 CUDA step 941→871.0625 次/帧（−7.43%），RT continuation 925→855.0625 次/帧（−7.56%）。这些不是耗时降幅。测前 GPU42%，下表只用于本窗排名和占比，耗时/FPS不作为绝对性能结论，所有耗时降幅待空闲复测。

| 排名 | 阶段 | ms/帧（本窗记录） | 每秒累计 ms | 占比 | 耗时降幅 |
| ---: | --- | ---: | ---: | ---: | --- |
| 1 | CUDA stream 同步 | 659.996738 | 812.367282 | 81.2367% | 待空闲复测 |
| 2 | CUDA kernel 入队 | 31.987484 | 39.372294 | 3.9372% | 待空闲复测 |
| 3 | Vulkan release 录制 | 29.570559 | 36.397384 | 3.6397% | 待空闲复测 |
| 4 | Vulkan release 提交 | 18.718309 | 23.039723 | 2.3040% | 待空闲复测 |
| 5 | CUDA semaphore 等待入队 | 14.121172 | 17.381265 | 1.7381% | 待空闲复测 |
| 6 | CUDA semaphore 信号入队 | 10.277747 | 12.650525 | 1.2651% | 待空闲复测 |
| 7 | CUDA 异步读回入队 | 9.253472 | 11.389780 | 1.1390% | 待空闲复测 |
| 14 | Vulkan 命令重用确认 | 2.243009 | 2.760843 | 0.2761% | 待空闲复测 |
| 16 | Vulkan 完成 fence 等待 | 1.662272 | 2.046033 | 0.2046% | 待空闲复测 |
| | 其余 35 项合计 | 34.605622 | 42.594870 | 4.2595% | 待空闲复测 |
| | **互斥求和** | **812.436384** | **1000.000000** | **100.0000%** | **待空闲复测** |

[正确性与同步次数证据](data/medium-binary-pairs-20261031.json)、[完整本窗排序](data/medium-binary-pairs-loaded-ranked-20261031.json)。CUDA 同步仍为第一，180 FPS 尚未达到。

### 已撤回的 CUDA 同步架构候选：长尾偶数批次

后续干净 GPU 的正反序关闭诊断对照均确认四轮为负优化，已撤回代码、恢复双轮。以下保留当时的正确性和工作计数记录，不能作为性能收益；最终对照见后续验收表。

首两轮仍读取存活上界，随后将长尾交接按 `top.ceroxe.rt.medium.cuda.opticalBatchRounds=2|4|8` 排队（候选默认 4，2 为原双轮对照）。剩余预算不足整批时使用更短偶数尾批，最后单轮沿用原路径；不超过 `maximumRounds`。只有 retained binary 模式使用该批次，其他协议仍走原双轮。

成熟依据仍为 NVIDIA cuda-samples `9c688d7ff78455ed42e345124d1495aad6bf66de`（BSD-3-Clause）`cudaUpdateVkImage` 的 stream wait→work→signal 与真正 Vulkan 提交依赖；同时参考 PBRT-v4 `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f`（Apache-2.0）`src/pbrt/wavefront/integrator.cpp` 的逐深度 GPU 队列排队、`workqueue.h::Size` 的 GPU 队列边界。采用其依赖和队列 guard 思路，不照搬输运算法或改变采样。

每轮独立 Vulkan command buffer，最后一个真实提交携带 fence；四个 binary semaphore 按奇偶复用，消费依赖先于重新 signal。每轮 pinned 统计和 shader 样本独立，批末汇总全部错误/完成/界面计数，最后 active 数控制下一批上界。单路径最多一个后继，故上一完成批次的 active 数可安全约束整个批次；已空队列仍由原 GPU guard 返回。CUDA event 槽扩至 16，容纳八轮 reset+step，不插入隐式完成同步。任何回调失败都不能提交等待未排队 signal 的 Vulkan 工作。

必须验证 2/4/6/8 轮真实 RT epoch、空队列尾部、协议/所有权切换、每轮记录及 CUDA 回调故障清理、HDR、固定失败帧、完整窗口、原生功能与 validation。同步次数变化只算工作计数证据；是否保留最终性能方案以干净 GPU 下关闭诊断整帧收益为准，不将等待转移算优化。


`a06d720` 的 4 轮候选通过真实 RT 逐轮快照、2/4/6/8 尾批次与空队列、每轮记录/CUDA 回调故障 drain、interop 1 个及 optical 3 个 validation 生命周期零 warning/error/drop、两帧 1080p HDR 逐字节一致、固定 sequence57、8+64 off 完整窗口、46 组/152 帧原生、micro-checks 7 场景/46 功能。8 轮严格 HDR 门禁未通过（第一帧单个 RGB 分量差 0.0009765625，另一帧一致）；原 2 轮复跑一致。8 轮不作为采用方案，不事后放宽阈值认定成功，后续单独查因。

| 4 轮候选指标 | 双轮对照 | 四轮候选 | 已确认变化 |
| --- | ---: | ---: | --- |
| 主光学 CUDA 同步次数/帧 | 435.53125 | 229.875 | 减少 47.2196% |
| CUDA step 次数/帧（含空尾轮） | 871.0625 | 887.5 | **增加 1.8871%** |
| continuation trace 次数/帧（含空尾轮） | 855.0625 | 871.5 | **增加 1.9224%** |
| CUDA 同步/整帧耗时降幅及 FPS 提升 | — | — | **待空闲复测** |

测前 GPU40%，32 帧排名中 CUDA 同步仍第一（69.1179%），kernel 入队第二（7.6809%），release 录制第三（4.8129%）。互斥墙钟记录合计 795.613441 ms/帧，归一每秒 1000ms；该绝对数不用于与旧窗比较。同步减少和空尾轮增加均须计入验收，不将前者单独包装成净收益。[完整正确性与成本](data/medium-optical-batch4-20261031.json)、[带待复测降幅字段的完整排名](data/medium-optical-batch4-loaded-ranked-20261031.json)。


### CUDA 同步候选的干净 GPU 正反序验收（2026-10-31）

这十个历史窗口测前 GPU0 均为 0%，关闭诊断，每窗 8 预热 + 32 测量帧；正反序分别验证，合并行用 64 完成帧 / 累计墙钟计算，不能平均已舍入的 FPS。当前 GPU 已有其他任务，本表不是当前负载下的新测量。

| 改动 | 原 ms/帧 | 后 ms/帧 | 耗时降幅 | 原 FPS | 后 FPS | FPS 增幅 | 正序降幅 | 反序降幅 | 决定 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 零贡献连接跳过 | 487.802411 | 472.495086 | 3.1380% | 2.050010 | 2.116424 | 3.2397% | 0.9394% | 5.2892% | 保留 |
| 独立 binary 双轮链 | 472.495086 | 454.028659 | 3.9083% | 2.116424 | 2.202504 | 4.0672% | 6.3882% | 1.3703% | 保留 |
| 四轮批次 | 454.028659 | 462.364473 | -1.8360% | 2.202504 | 2.162796 | -1.8029% | -2.6404% | -1.0546% | 撤回 |
| 首段不透明遮挡提前结束 | 462.364473 | 483.279289 | -4.5234% | 2.162796 | 2.069197 | -4.3277% | -0.5080% | -8.4850% | 撤回 |

四轮虽减少 47.2196% 的主光学同步次数，但增加空尾工作且实际更慢；首段遮挡跳过没有缩短长尾。两者均撤回，默认恢复已验收的 binary 双轮链。负降幅表示退化。不同 GPU 负载下的内部排名不能拿来填本表的阶段耗时收益。

[原始窗口、哈希与逐项收益](data/medium-idle-ablation-20261031.json)。


### 有限光源连接范围候选（验证中）

总榜第一 CUDA stream 同步包住的实际 shader 工作中，连接路径是 61.075%，连接内首次 trace/空构造占 64.763%（内部抽样口径，不能与墙钟相加）。当前 producer 固定 trace tMax=1e20，而 CUDA connectionStep 已使用 min(hitDistance, endpoint) 并在 distance>=endpoint 时结束，光源后方的 closest-hit 材质计算和 BVH 遍历属于不会被消费的工作。

参考 [PBRT-v4 b0bdf461 的 TraceTransmittance](https://github.com/mmp/pbrt-v4/blob/b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f/src/pbrt/wavefront/intersect.h)（Apache-2.0）使用 sr.tMax、pLight=ray(tMax)、trace(ray,tMax)，以及 CPUAggregate 的有限 shadow ray 查询。采用有限连接区间，保留本项目透明表面、overlay 排除、介质优先级、数值偏移和事件预算；不搬用 PBRT 的随机积分/轮盘策略，不降低任何采样预算。

独立属性 top.ceroxe.rt.medium.cuda.opticalBoundConnectionRays，header188 bit2048，false 可回到原无限连接作同制品对照。仅点光/聚光的连接查询使用 min(origin.w,1e20)，方向光、主射线和光子路径保持原范围。初次及 overlay/non-shadow 后续查询都使用同一端点；nextFloat(tHit)>tMax 时保留端点终止命中供 CUDA 消费，不发送 tMin>tMax。

验收包括：真实 native RT 的镜像及反顶点序、光源前后不透明遮挡、alpha 衰减、穿透和终止在体积内部、端点 overlay/non-shadow/opaque、方向光；同 sequence 开关 HDR 字节对照；1080p 两帧 HDR、固定57及完整窗口、micro-checks、完整生产光学回归和 validation。性能按当前 GPU 负载只报本窗排名及占比，净收益待干净 GPU 的关闭诊断对照后判定；不能把理论删除工作包装成已实现加速。


### 架构候选：按设备预算规划整批光学队列（验证中）

逐灯光连接仍串行挂在相机路径状态机内，这与 PBRT 的独立 shadowRayQueue 有明显差异。当前先落地其中可独立验收的一层：光学规划器和 manager 分配检查硬性 min(512MiB)，CUDA 会话默认也是 512MiB；显存充足时也强制重复多个 tile 的长尾迭代和跨 API 交接，设置更大的 maxResidentBytes 仍被前两层截断。

对照 PBRT-v4 b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f（Apache-2.0）src/pbrt/wavefront/integrator.cpp 的 227–243 行按 maxSamples/scanlinesPerPass/maxQueueSize 规划 GPU 工作队列，353 行按 pass 生成相机队列，575 行集中消费 shadowRayQueue。PBRT 本身以 1024*1024 为固定样本队列容量，并在 TODO 中提出按 GPU memory 配置；不能声称它总是整帧一批。采用其按队列容量组织 pass 的原则，设备预算部分使用本项目现有 VMA snapshot 和 VulkanMemoryBudgetPolicy，不宣称四分之一显存这个数来自 PBRT。

生产光学容量取已配置上限、驱动 device-local 总预算的四分之一和光学 ABI 可寻址上限中的最小值。ABI 使用有符号 int 字节偏移，最高 Integer.MAX_VALUE-65536；缺少预算报告时回到 512MiB，小显卡保留相应分块。使用稳定的总预算而非扣除自身分配后的 free bytes，避免每帧因自身占用而缩块、迁移和抖动。实际 storage、外部 primary、光子图的增长仍由现有完整 requestedGrowthBytes admission 检查，保留 64MiB reserve/95% watermark。

只有光学 CUDA 会话使用该容量策略，其他介质会话的默认限制不变。显式 maxResidentBytes=536870912 提供原分块方式的同制品对照；清除此属性使用设备预算。只减少串行 tile 长尾，不减少路径、光学样本、首界面分支、散射事件、光子或可见灯光。

必须验证：预算缺失/小显卡、显式小容量、整数偏移上限、自身占用不导致重分块；1080p 两帧 HDR 严格字节一致、固定 sequence57、完整 64 帧窗口、micro-checks、全部生产光学回归和 validation。净性能与同步/trace 次数共同验收，负优化撤回，不只报告循环数减少。有限连接范围的小修单独保留其正确性与未完成的性能验收记录，不把其未验收收益算入本架构候选。


有限连接范围候选 cfb4dcf 的真实 RT 20 组/40 帧、镜像顶点序与端点回归、2 个 validation 生命周期和两帧 1080p HDR 均逐字节通过。默认 JVM 正序对旧核心为约 1.07%，但同为 C2 的正序仅约 0.18%，不足以确认稳定收益；默认 JVM 反序遇到 jvmcicompiler.dll 崩溃，C2 反序主动中止以转入架构主线。不把不完整反序窗口当验收，不把 1.07% 报成已确认成果。[完整记录](data/medium-bounded-connections-20261031.json)。

**2026-10-31 用户再次明确核心：消除大头里面的大头工作。** 候选进入实测前必须写清实际删除哪些重复工作、保留哪些必要工作，验收同时列真实工作计数和整帧 ms/FPS。代码位于最大热点、同步数量减少、调度方式改变都不能单独证明已消除大头；不能用等待搬家达成排名变化。当前架构候选删除过小 tile 导致的重复长尾轮次、批次初始化及 Vulkan/CUDA 交接；图像、采样、介质事件和灯光预算必须相同。


### 设备预算光学队列：完整验收，保留（28ef876）

四个 off 正反序窗口和两个 timings 窗口测前 GPU0 全部 0%。所有窗口使用相同 C2 优化编译器设置，8 预热 + 32 测量帧；下面的吞吐合并正反序 64 完成帧/累计墙钟。原采样、散射、灯光、光子、画质和原生完成口径均保持。

| 指标 | 512MiB 分块 | 设备预算规划 | 改善 |
| --- | ---: | ---: | ---: |
| 关闭诊断 ms/帧 | 427.927556 | 282.841458 | 下降 33.9044% |
| 原生 FPS | 2.336844 | 3.535550 | 提升 51.2959% |
| 全部 CUDA stream 同步次数/帧 | 452.53125 | 138.78125 | 下降 69.3322% |
| CUDA 同步总 ms/帧（配对 timings） | 375.291419 | 259.979459 | 下降 30.7260% |

| 排名 | 阶段 | ms/帧 | 每秒累计 ms | 占比 | 耗时降幅 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | CUDA stream 同步 | 259.979459 | 908.563189 | 90.8563% | 30.7260% |
| 2 | CUDA kernel 入队 | 5.531475 | 19.331122 | 1.9331% | 69.3423% |
| 3 | Vulkan release 录制 | 3.839888 | 13.419446 | 1.3419% | 57.6436% |
| 4 | Vulkan release 提交 | 2.837941 | 9.917893 | 0.9918% | 65.8935% |
| 5 | CUDA semaphore 等待入队 | 2.100841 | 7.341913 | 0.7342% | 67.3008% |
| 6 | CUDA semaphore 信号入队 | 1.586488 | 5.544377 | 0.5544% | 68.4253% |
| 7 | Vulkan consumer 提交 | 1.435803 | 5.017773 | 0.5018% | 62.3345% |
| 8 | Vulkan consumer fence 等待 | 1.418156 | 4.956101 | 0.4956% | -28.7843% |
| | 其余 36 项 | 7.413459 | 25.908186 | 2.5908% | 46.7264% |
| | **互斥求和** | **286.143509** | **1000.000000** | **100.0000%** | **35.1147%** |

32 帧 204956 条记录零丢样。consumer fence 等待有约 0.317ms 增加，已计入总帧时；并未以省略它夸大收益。主同步自身下降 115.312ms/帧，整帧也下降，证明净等待没有搬家。同步占比升到 90.86% 是其他重复开销降得更多；它仍是第一，下一轮继续删除其包住的求交/积分重复工作。

全部门禁已通过；独立完整 64 帧窗口 283.178086ms/帧、3.531347FPS。容量回归的四批到一批是明确布局测试，真实生产的工作计数以上述同步/录制次数为准。注意提高并行工作集会增加暂存显存，仍受设备预算、显式上限和现有 growth admission 约束。180FPS 尚未达到。

[完整正确性、净收益、制品与原始证据哈希](data/medium-budgeted-waves-20261031.json)、[全部44阶段及逐项降幅](data/medium-budgeted-waves-ranked-20261031.json)。


### 命中阶段 payload 缓存：负优化，已撤回（f8467e9 → a1f0ab1）

同一次边界结果从 any-hit 传给 closest-hit，20 组/40 native 帧开关逐字节一致，2 个 validation 生命周期零 warning/error/drop，两个1080p HDR 帧与同制品关闭及前版冻结制品均逐字节一致。但真实整帧没有收益，不能以删除一次软件求交就认定成功。

| 有效反序对照（各32帧） | 原版 | 缓存候选 | 改善 |
| --- | ---: | ---: | ---: |
| 关闭诊断 ms/帧 | 279.206878 | 296.363659 | -6.1448% |
| 原生 FPS | 3.581574 | 3.374233 | -5.7891% |

此反序两窗测前 GPU0 均为0%，两轮均变慢，立即完整回滚并推送 main。正序候选测前86%，不得拿其绝对值做收益或退化结论。寄存器/payload 压力只是待证原因，不把猜测记为已定位根因；未继续跑已无采用价值候选的完整验收。保留设备预算队列的已验收成果。

[原始窗口、排除理由、正确性与证据哈希](data/medium-boundary-hit-cache-rejected-20261031.json)。

### 单次边界求交内复用宽精度项（编译检查后放弃）

全局复查连接路径后，边界函数内部仍存在重复计算：gsBoundaryTriangleHit 用三个宽精度边函数判断三角形内外，gsBoundaryDistance 为分母再算同三个函数，且 b×c 为距离分子再次计算。当前候选让内外判断、分母和距离分子消费同一次局部计算，保留全部两浮点展开、括号运算顺序、邻面搜索、距离修正及介质事件，不增加跨 trace payload。

对照 [PBRT-v4 b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f shapes.cpp 206–243行](https://github.com/mmp/pbrt-v4/blob/b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f/src/pbrt/shapes.cpp#L206)：其三角形求交缓存 e0/e1/e2，并将它们用于内外判断、det 及 tScaled。只借用一次生产、多处消费的组织，不替换本项目已修正的宽精度算法。源码少算不等于 GPU 已加速，仍以严格 HDR、边界/端点与光学生产门禁、关闭诊断正反序整帧判断。编译器可能本已合并部分表达式，负优化立即撤回；仍需继续解决独立连接队列、推迟未被消费的着色等架构问题。

编译检查提前停止本候选：base any-hit 前后均17942条 SPIR-V 指令，closest-hit 前后均32420条；各 opcode 的计数完全相同。这个源码改写没有消除目标算术指令，仅改变编排，不能算已删除实际重复工作，已恢复原源码和全部四份生成资源，不继续占用 GPU 验收。此判断不声称两份二进制逐字节相同，也不将未实测的性能记为零或收益。


### 按光连接消费需求裁剪命中着色（共享程序动态分支候选已撤回）

参考 [PBRT-v4 b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f intersect.h::TransmittanceTraceResult/TraceTransmittance](https://github.com/mmp/pbrt-v4/blob/b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f/src/pbrt/wavefront/intersect.h#L158)：其专用透射查询只产生 hit、pHit、material，独立于完整表面着色。采用按消费者需求安排工作的组织，本项目的透明面规则比 PBRT 的 shadow opaque 规则更复杂，必须保留原有 base tint/alpha、纹理过滤、光照调制、真实法线/法线贴图、IOR/透射与所有介质边界处理。

旧连接查询使用完整命中着色，无条件生成消费者不读取的金属粗糙度与发光纹理、相机距离雾坐标、上一帧变换与运动 revision、描边/mask，以及焦散几何法线编码。新 query kind5 保留原 query4 的几何/any-hit/介质求交，只跳过上述无人消费字段；边界仍使用原几何法线，普通透明面仍使用原法线贴图。不会提前结束光路，不减少任何连接、光学样本、事件、散射或光子。

header188 bit4096 / top.ceroxe.rt.medium.cuda.opticalLeanConnectionHits（默认true，false回到完整命中）用于同制品对照，不增加 payload ABI。CUDA connectionStep 的所有读取字段保持；默认完整命中记录布局也保留，只将未读取输出清零。此为删除未消费计算的确定性候选，未通过严格图像和性能门禁前不称为成果。

新增独立 --connection-materials 原生验证：同 sequence 开关对照24组/48帧，含镜像/反顶点序、端点、overlay/non-shadow、体积、带颜色/alpha纹理、MR/发光贴图的透射障碍及会实际改变 Fresnel 的法线贴图。后续1080p HDR、完整生产回归、互斥功能覆盖、固定57、完整窗口、validation及关闭诊断正反序净收益共同验收，负优化完整撤回。


24组/48 native 帧、两个validation生命周期、两个1080p HDR帧的同制品开关与前版逐字节对照均通过；但是四个窗口测前GPU0全为0%的关闭诊断正反序都更慢，候选代码已完整撤回，保留证据。

| 净吞吐（正反序各32帧，合计64帧） | 原版 | 动态分支候选 | 改善 |
| --- | ---: | ---: | ---: |
| ms/帧 | 278.971733 | 300.665345 | -7.7763% |
| 原生 FPS | 3.584593 | 3.325957 | -7.2152% |

正序耗时降幅-7.1960%，反序-8.3639%。不声称已定位寄存器溢出；这里只能确认共享shader的动态分支改法没有带来净收益。转向独立连接命中程序的编译期裁剪与SBT路由，在大头的组织方式上解决问题，不继续给通用shader叠加局部特判。

[全部窗口、逐项正确性与原始证据哈希](data/medium-lean-connection-hits-rejected-20261031.json)。


### 独立连接命中程序与 SBT 路由（已完整撤回）

共享shader动态分支候选负优化后，先回到全局程序组织：当前所有连接仍走通用完整命中程序。新候选将原命中实现提取为一份共享源，分别在编译期生成完整命中与连接专用命中，不复制两套材质算法。连接程序中的 leanConnection 为编译期常量，未消费计算可直接从其模块剔除；完整程序也为常量false，移除前个候选给所有查询叠加的动态分支。

直接核对 [PBRT-v4 b0bdf461 的 gpu/optix/aggregate.cpp](https://github.com/mmp/pbrt-v4/blob/b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f/src/pbrt/gpu/optix/aggregate.cpp#L1247) 1247–1262、1326–1357及1641–1656行：closest/shadow/shadow_Tr采用分别的raygen/miss/SBT组织。注意PBRT的shadowTrSBT实际复用isectHGRB，不能声称PBRT也有本项目这个独立透射closest-hit模块。本项目按消费字段做编译期裁剪属于确定性的死工作消除，独立命中模块是本项目的Vulkan适配；具体收益仍须本项目验证。

内置native optical管线新增第3个hit slot（0完整、1visibility、2连接），只有query kind5走slot2，query4和false属性走完整原路径。沿用同一个any-hit、miss、payload布局、纹理/法线/介质求交、CUDA consumer及所有预算；新增模块与组数进入pipeline cache key、SBT packing与生命周期清理，普通及诊断光学管线一致路由。专家自定义shader仍遵守内置特化拒绝替换的原约束。

仍使用独立24组/48 native帧开关与法线敏感性验证、1080p HDR原版/开关逐字节、关闭诊断GPU空闲正反序作为第一门槛。先证实连接模块实际少了目标指令；若负优化，连同共享源拆分和SBT改动完整撤回，不把架构候选当既成成果。

编译产物检查：完整命中32420条SPIR-V指令，连接命中20849条；算术指令6799→4782，load指令1045→722。这是模块静态计数，不能直接换算GPU执行时间或整帧降幅；仍需净吞吐验证。


独立SBT候选通过24组/48原生帧逐字节、2个validation生命周期、两个1080p原版HDR帧严格对照、shader与自定义阶段隔离检查。但是正序耗时降幅-9.8545%，反序-10.7525%；四窗测前GPU0均0%，不继续采用。

| 净吞吐（正反序合计64完成帧） | 原版 | 独立连接程序 | 改善 |
| --- | ---: | ---: | ---: |
| ms/帧 | 278.222900 | 306.899628 | -10.3071% |
| 原生 FPS | 3.594240 | 3.258394 | -9.3440% |

已连同共享源拆分、SBT路由、生成二进制与关联测试完整恢复到原方案，保留设备预算队列的已验收优化。静态模块缩小与成熟项目的结构依据都不能替代本项目净吞吐验证；仍未证明实际寄存器/栈溢出根因，不编造原因。

[完整证据与哈希](data/medium-specialized-connection-sbt-rejected-20261031.json)。


## 2026-10-31 撤回候选 L：只搬运介质栈有效前缀

对照仓库固定的 LuxCore commit `b872186d2fe995a762f73708c606847eed55843e`（Apache-2.0），`include/slg/utils/pathvolumeinfo_funcs.cl` 的 `PathVolumeInfo_Init`、`AddVolume`、`RemoveVolume`（本地固定摘录 `upstream-pathvolumeinfo-functions.cl` 25–89 行）：初始化只置长度/当前介质/scatteredStart，添加写入 `list[size++]`，删除和优先级重选仅读取 `[0,size)`。这里引用的是有效前缀语义，不能声称 LuxCore 已采用本项目这项复制优化。

本项目的确定性候选在 optical transport 的9处相机/连接/候选状态交接只复制三个头字段和有效槽位，删除无读者的尾部搬运；栈仍为144字节、32槽，保持顺序、优先级、进出栈、拒绝行为及全部预算。坏长度仍保留并由原验证拒绝，复制最多32项。`top.ceroxe.rt.medium.cuda.opticalActiveVolumeCopies=false` 可用同制品回到完整结构复制。没有改着色器、同步流程、采样或任何输运近似。

专用真实CUDA检查两种复制模式各136种输入，覆盖长度0至32、坏长度33、两种槽顺序、scatteredStart、容量溢出、缺失删除、删除重选优先级、删空再填、有效顺序和两侧内存守卫，五个变更检查点由独立Java列表/优先级模型验证。随后核对ABI、生产native与validation、两个1080p HDR帧及关闭诊断正反序整帧速度。少搬运不是已确认收益；动态循环可能抵消节省，负优化完整撤回。


ABI及两种复制模式各136组真实CUDA输入、合计1360个独立模型检查点通过。但1080p两帧严格HDR未通过：第一帧单个RGB分量差一个half ULP（0.0009765625），第二帧完全一致。同制品关闭候选后两帧均恢复与原版逐字节一致，开关对照仍有该差异。尚无证据判定浮点/编译/队列的具体成因，不临时放宽阈值，不宣称画面完全一致；正确性门槛失败后停止性能验收，完整撤回这项源码、开关及专项测试。原设备预算队列等已验收优化保留，本轮没有新增可报告FPS收益。

已通过本机代理重新下载固定提交的 `include/slg/utils/pathvolumeinfo_funcs.cl`，确认本地固定摘录是上游的连续原文；下载文件SHA-256为 `844b3b0a9266cb0b598b8f06b031d92e7093878e55a441a79bf82936ee2dbe25`。[本轮证据与哈希](data/medium-active-volume-copies-rejected-20261031.json)。下一轮停止栈复制细节变体，先核对整条路径和队列职责中的重复工作及其成熟实现依据。


## 2026-10-31 保留架构优化 M：删除没有光学分支选择的整段重复路径

四次候选未产生可保留收益后，停止着色器裁剪和栈复制变体，回查完整数据流。现在每个 optical sample 的确定性反射/折射分支从相机各跑一整遍；首个光学lobe选择之前，CUDA体积采样、相函数/材质随机数和Vulkan连接种子均不含branch，只有 `prepareSurfaceContinuation` 和主光学lobe选择读取branch。完全未到分支选择而已经完成的路径仍重复了全部散射、所有灯光连接和续射线。

已核对固定 PBRT-v4 commit `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f`、Apache-2.0：`src/pbrt/wavefront/integrator.cpp` 356–375行的每样本相机队列，以及 `surfscatter.cpp` 181–193行在实际BSDF处采样并生产后续队列的职责。PBRT并没有本项目这项双分支缓存，不能声称直接照搬；该参考用于核对相机工作和实际分支职责。实现是本项目确定性的公共子路径结果复用，没有更换积分器或采样估计器。

候选只在同一tile/optical sample紧接的branch1，复用branch0成功完成、原path ID匹配、初始化成功且从未读取光学分支选择的状态。不额外分配缓存：状态本来保留在原位置。Vulkan在重置前设置专用重放mode；CUDA验证完整条件后按原浮点表达式再做一次 `output + radiance * terminal.w`，保留原累加顺序与两份权重，不合并权重、不减少声明的样本/事件/散射/光子预算。只删除已证明完全相同的第二条实际输运。随机首界面仍走原路径。

新增独立“曾进行分支相关选择”位，在选择前记录，包括零Fresnel反射、零权重/失败lobe，不能仅凭“成功穿过界面”标记判断，否则branch0零贡献会错误覆盖branch1。碰过任何相关选择的路径全部按原逻辑重算。`top.ceroxe.rt.medium.cuda.opticalReuseDuplicateBranches=false` 可同制品关闭。初始化、重复使用、队列、共享内存所有权及原ABI保持。

门槛：独立原生开关HDR对照覆盖体积散射、边界/嵌套优先级、alpha、等IOR零反射、光滑/粗糙介电及金属，SPP1/2和两种初始化；要求无界面散射用例实际continued查询减少。随后1080p两帧与冻结原版严格HDR、关闭诊断正反序整帧检验；有收益再做fixed57、完整窗口、完整生产native/validation及互斥功能覆盖。引用成熟实现和代码少跑都不是性能结论；失败完整撤回。


验收通过：32组/64帧原生开关逐字节一致（两种初始化、SPP1/2、8类场景），continued查询2299440→1505929，减少34.5089%；两个1080p主场景HDR与原版逐字节一致；固定sequence57、完整64帧窗口、46组/152帧完整生产光学回归、7场景/46项互斥功能覆盖均通过。两个专项及三个完整生产validation生命周期全部关闭、零warning/error/drop。36个源清单文件逐字节哈希验证通过；30个着色阶段及17个HDR raygen编译契约通过。

四窗测前GPU0均0%，双方正反序各合计64帧。关闭诊断帧时274.433042→207.883355 ms/帧，下降24.2499%；原生FPS 3.643876→4.810390，提升32.0130%。没有减少请求采样、光学样本、事件/散射/光子预算；前后导出的workload元数据完全相同。独立64帧长窗口只用于回归，不另算新的收益。

| 排名 | 互斥host阶段 | ms/帧 | 每秒累计ms | 占比 | 同轮耗时降幅 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | CUDA stream 同步 | 184.913088 | 880.659537 | 88.0660% | 29.9118% |
| 2 | CUDA kernel 入队 | 4.954387 | 23.595564 | 2.3596% | 9.7100% |
| 3 | Vulkan release 录制 | 3.787328 | 18.037375 | 1.8037% | 0.8234% |
| 4 | Vulkan release 提交 | 2.914316 | 13.879601 | 1.3880% | 4.9417% |
| 5 | CUDA semaphore 等待入队 | 2.019441 | 9.617706 | 0.9618% | 4.9440% |
| 6 | CUDA semaphore 信号入队 | 1.542522 | 7.346352 | 0.7346% | 4.9890% |
| 7 | Vulkan consumer 提交 | 1.291466 | 6.150682 | 0.6151% | 6.8438% |
| 8 | Vulkan consumer fence 等待 | 1.254866 | 5.976372 | 0.5976% | 7.6214% |
| — | 其余36项合计 | 7.293728 | 34.736812 | 3.4737% | 4.0997% |
| — | **互斥求和** | **209.971141** | **1000.000000** | **100%** | **27.6708%** |

本轮观测开销1.0043%，满足10%线，不继续打磨观测开销。202206条互斥区间零丢样。上表为32帧观测窗口，整帧净收益以上方关闭诊断24.2499%为准。第一名包住的真实GPU工作：CUDA光学推进109.827868→67.497932 ms/帧，Vulkan续射线91.964671→59.735283 ms/帧；不能再与host等待求和，已证实工作减少而非等待转移。CUDA stream同步仍为第一，原生180FPS目标尚未达到。

[完整验收与原始证据哈希](data/medium-reused-duplicate-branches-20261031.json)。后续主线优先追求能显著降低整帧耗时的架构改动，从最大热点的最大实际工作入手，优先消除整段重复计算、路径或交接；确定性小收益仍保留，但不让细节试探占据主线。连续失败后回看全局，不用静态指令/源码行数减少代替净收益。新候选仍分别验证，不承诺每轮都能达到本轮降幅。


## 2026-10-31 架构候选 N：相邻两段连接命中一次消费（待验收）

本轮从最大热点包住的真实GPU工作出发：一条直线光连接每经过一个透明层或介质边界，都再次装载完整状态、推进一次CUDA、压缩队列、跨Vulkan/CUDA交接。固定 PBRT-v4 commit `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f`（Apache-2.0），`src/pbrt/wavefront/intersect.h` 158行起的 `TraceTransmittance` 在GPU内循环处理连接的命中与透射。此处参考的是完整连接在GPU内连续处理的组织方式；PBRT没有本项目的两段命中包，后者是Vulkan/CUDA架构的确定性适配。

Vulkan仍以原来的origin、direction、nextFloat(t)、端点上限查询，最多额外预取一段，写入第二hit plane。CUDA按原先顺序调用原 `connectionStep`，逐段更新原事件计数、介质优先级栈、衰减和epoch；第一段终止就不消费第二段。没有降低任何采样/事件/散射/光子预算，没有替换段积分公式。仅在透明或介质命中且预算允许时预取。TIR/cutoff等场景可能多一次未被消费的几何查询，必须测净收益；不能声称几何射线必然减少。

header188 bit8192守卫新metadata，byte228是第二plane地址，byte232是包容量2；compact/full/transposed布局和partial tile均保留。额外plane占用真实resident cap，1080p compact约增加199MB；不能通过增加原cap掩盖内存代价。CUDA clock phase诊断复用同一header区，因此该专用诊断强制单段，普通host/GPU事件计时继续使用真实候选路径。`top.ceroxe.rt.medium.cuda.opticalBatchConnectionHits=false` 回到原单段实现。

先做CPU布局/容量和原生专项：三种hit格式、镜像顶点序、透明链、嵌套优先级、预算2/3、端点与overlay/nonshadow，共84组开关HDR逐字节比较/168帧；再做1080p两帧与已验收M严格HDR。通过后检查关闭诊断的正反序原生吞吐，正收益再跑完整生产回归与新排名。测试前全量提交推送、工作区干净并冻结JAR；任何正确性失败或负优化均撤回候选，不覆盖M已验收成果。


候选N验收通过并保留：84组/168帧三种命中格式专项HDR逐字节一致；两个1080p主场景HDR与M逐字节一致；完整46组/152帧生产回归、固定sequence57、64帧长窗口、7场景/46功能覆盖全部通过。五个validation生命周期均关闭，零warning/error/drop；36文件源清单、30着色阶段/17 HDR raygen契约通过。完整1080p scratch为1775002192字节，仍为单tile、未增加resident cap。

四窗测前GPU0全0%，原版和候选各64完成帧，关闭诊断：209.430448→191.363877 ms/帧，下降8.6265%；原生4.774855→5.225647 FPS，提升9.4410%。正序耗时下降8.4681%，反序8.7827%。预算元数据前后完全相同。独立长窗口为190.211105 ms/帧，仅用于回归，不重复计算收益。普通观测开销1.2399%，不继续打磨观测；153356条互斥区间零丢样。

| 排名 | 互斥host阶段 | ms/帧 | 每秒累计ms | 占比 | 同轮耗时降幅 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | CUDA_STREAM_SYNCHRONIZE | 171.334825 | 884.369753 | 88.4370% | 7.1939% |
| 2 | CUDA_KERNEL_ENQUEUE | 4.084669 | 21.083615 | 2.1084% | 15.9034% |
| 3 | VULKAN_RELEASE_RECORD | 3.671425 | 18.950597 | 1.8951% | 5.8894% |
| 4 | VULKAN_RELEASE_SUBMIT | 2.017953 | 10.415960 | 1.0416% | 35.3486% |
| 5 | CUDA_SEMAPHORE_WAIT_ENQUEUE | 1.650241 | 8.517958 | 0.8518% | 21.2223% |
| 6 | VULKAN_CONSUMER_SUBMIT | 1.530469 | 7.899738 | 0.7900% | -10.3500% |
| 7 | VULKAN_CONSUMER_FENCE_WAIT | 1.316616 | 6.795904 | 0.6796% | -1.0874% |
| 8 | CUDA_SEMAPHORE_SIGNAL_ENQUEUE | 1.263906 | 6.523837 | 0.6524% | 24.6323% |
| — | 其余36项合计 | 6.866537 | 35.442637 | 3.5443% | 7.1300% |
| — | **互斥求和** | **193.736641** | **1000.000000** | **100%** | **7.8981%** |

第一名内部真实GPU工作：CUDA推进240.125→179.0625次/帧，耗时67.464853→60.639885 ms/帧（下降10.1163%）；Vulkan续射线236.125→175.0625次/帧，但是耗时58.857951→63.520982 ms/帧（增加7.9225%）。这说明包内预取确有代价，不能只用交接次数减少宣称整段等比例加速；保留依据是关闭诊断的整帧净收益。两项GPU不能再与host等待相加。CUDA stream同步仍为第一，Vulkan续射线和CUDA推进成为相近的两大实际GPU任务；180原生FPS目标尚未达到。

[完整验收、排名与原始证据哈希](data/medium-paired-connection-hits-20261031.json)。后续继续从最大实际工作和数据流架构入手，确定性小收益保留，主线优先追求能消除整段工作的大幅改进。


## 2026-10-31 撤回候选 O：完整介质路径的热状态分离

对照固定PBRT-v4 `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f`（Apache-2.0），`src/pbrt/wavefront/workitems.h` 41–95行 `SOA<RaySamples>` 和 `src/pbrt/util/soa.h` 130行起的SoA/代理读写组织，曾将完整介质路径的前112字节热字段分为标量plane，后144字节PathVolumeInfo仍按原样逐path存放。未减少内存容量、未改变栈复制或数学表达式；PBRT不包含本项目这个具体混合布局。

CPU容量布局通过；真实CUDA的1/31/32/33/255/256/257/1069路径、两种状态大小与两种布局，共32组原始字段/完整栈尾/内存守卫检查通过，原光学数值检查通过；两种初始化和SPP1/2的32组/64帧开关HDR、三种命中格式的84组/168帧连接开关HDR均逐字节一致，前后实际查询量相同，四个validation生命周期关闭且零warning/error/drop。但1080p两帧严格HDR失败：首帧单个RGB分量差一个half ULP（0.0009765625），第二帧完全一致；相对RMS为9.64807374149859e-7。没有放宽阈值，没有进入性能验收，不报告候选FPS收益。

已完整撤回混合布局、12入口扩展、GLSL/ABI/管理器及关联测试，保留N已验收成果。同制品关闭候选的HDR对照正在后续核实。尚无证据判定这一个分量差异的具体成因，不编造寄存器、FMA或介质栈错误。后续先观察全局数据流和已有两次栈相关候选的差异位置，不继续堆同类局部布局变体。

[失败证据与固定上游文件哈希](data/medium-hybrid-volume-states-rejected-20261031.json)。


O同制品关闭候选后，两个1080p HDR帧均恢复与N逐字节一致；renderer-core源码及生成二进制已核对完全恢复至N。进一步对照历史L，L与O首帧完整HDR字节及SHA-256完全相同，差异均为像素(245,184)的G：1.43359375→1.4345703125；两份关闭候选结果也逐字节相同。当前只能确认共同输出特征，尚不能确认FMA、介质栈、光子并发或具体数值根因。下一步使用现有单path追踪观察该原始path353525，必须核实追踪制品是否仍重现同一差异，避免把观测改变后的结果当成原问题。


现有单path追踪进一步显示：两边均54条事件，排除未命中时不消费的point字段后，记录的origin/direction/命中距离/边界/介质等全部一致。但追踪开关本身改变同一个half分量：O关闭布局时从基线像素值变成候选值，O启用布局时反而恢复基线值。追踪并未保持未观测结果，不能拿它证明原数值根因，也不以几何轨迹一致为由解禁候选。O仍完全撤回，性能主线保留N；下一步使用已有稀疏shader计时确认当前Vulkan续射线内的实际大头，不继续堆栈相关变体。


## 2026-10-31 撤回架构候选 P：整条直线光连接在 Vulkan 内完成

N 的现有稀疏 shader 分组7实测，第一段几何查询与区间处理占50.4823%，第二段查询占22.9708%，连接初始化7.9079%、焦散gather6.5203%、BRDF与源贡献5.0025%、灯光几何2.6836%、选灯0.7305%。这是分组抽样内部占比，不能折算整帧耗时。查询合计73.4531%是此轮关注的实际大头，不转去优化占比很小的选灯缓存。

对照固定 PBRT-v4 `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f`（Apache-2.0），`src/pbrt/wavefront/intersect.h` 165行起 `TraceTransmittance` 在GPU循环里处理整条连接的求交和介质透射。把本项目直线光连接的每段CUDA消费搬到已有Vulkan连接程序：同一GPU调用连续追踪、密度积分、Beer衰减、透明表面Fresnel与介质栈进出，完成整条连接后才交回CUDA。删除中间状态重载、kernel入队、队列压缩和Vulkan/CUDA交接；CUDA相机输运与散射保持。不是将host等待改名或换位置。没有减少任何样本、灯光、事件、散射、密度积分或光子预算。

数值与栈语义参照仓库固定LuxCore `b872186d2fe995a762f73708c606847eed55843e`（Apache-2.0）的 `FrDiel2/FresnelCauchy_Evaluate`、`PathVolumeInfo_AddVolume/RemoveVolume` 和八角密度插值，保留本项目域裁剪与midpoint积分。保留完整栈布局及原删除顺序、同优先级后入胜出、逐段epoch、预算和拒绝码；不恢复L/O复制或布局候选。极端IOR和掠射在提交当前段状态前回退原CUDA double路径，已完成前段正常保留。

这是跨后端浮点实现，验证前固定使用已有bounded HDR门槛：相对RMS≤0.001，亮度能量相对差≤0.001，最大RGB分量差≤参考绝对P99×0.005+0.00001，alpha完全一致且所有值有限。PBRT同固定提交 `CMakeLists.txt` 249行明确给CUDA启用 `--use_fast_math`，支持成熟实现允许有界浮点近似的原则；不声称PBRT用本项目ABI或完全相同的积分公式，也不改变O的严格失败结论。独立解析/能量/预算断言继续保持。

新开关 `top.ceroxe.rt.medium.cuda.opticalVulkanConnections=false` 完整回到N两段命中包。header188 bit16384，source mode3表示已完成、5至11保留原connection错误1至7，hit epoch仍由CUDA校验。开启时只需一个hit plane，真实预算仍包含全部状态与输出。沿用stage175记录新段积分、stage174记录后续查询，不另做观测优化。

按规则先全量提交推送main、确认工作区干净并冻结JAR，再运行CPU ABI、32组/64帧跨路径与84组/168帧连接专项、validation和1080p两帧bounded HDR。通过后用N/P/P/N四窗关闭诊断比较；正收益再完整生产回归、固定57与长窗口、全部互斥覆盖及新排名。失败或负优化完整撤回P，保留N。


P已完成正确性及净吞吐验证，没有可验收的整帧收益，完整撤回。CPU ABI通过，1080p单tile scratch从1775002192降至1575936592字节；32组/64帧路径与84组/168帧连接bounded对照通过，两个1080p帧最大相对RMS为0.0000022809442054569046、最大亮度能量相对差0.00000002207073913642903、最大分量差0.0009765625，alpha完全一致。四个validation生命周期关闭，零warning/error/drop，36源文件清单通过。未因正确性过关而将候选算作性能成果。

四窗起始GPU0均0%，关闭诊断双方各64完成帧：

| 净吞吐 | N基线 | P候选 | 改善 |
| --- | ---: | ---: | ---: |
| ms/帧 | 196.087716 | 196.365016 | -0.1414% |
| 原生FPS | 5.099759 | 5.092557 | -0.1412% |

正序耗时增加0.3941%，反序减少0.1079%，合计持平；不是确定性正收益，不保留。此前探索窗口也归档，其中85%初始占用窗口仍不作绝对比较。没有继续跑无收益候选的完整长期验收。

同轮已有计时说明主要是工作迁移：CUDA推进61.593873→48.021617 ms/帧，但Vulkan续射线65.146246→81.987196 ms/帧；CUDA调用179.0625→170.6875次/帧、Vulkan175.0625→166.6875次/帧，实际轮数只少约4.7%至4.8%。不能将CUDA少的13.57ms单独报成收益。host同步173.550244→174.515581 ms/帧仍第一，观测整帧197.071434→197.746050 ms/帧。没有证据确认寄存器、占用率等硬件层成因，不编造。撤回完整Vulkan积分、属性/ABI、consumer模式、专项测试和生成SPIR-V，恢复N源码；保留全部测试证据。

下一步先回看整帧全局数据流，以JFR核对主机侧线程、驱动等待与现有GPU计时，不继续叠加同类逐段搬移变体。[本轮完整结果与原始证据哈希](data/medium-gpu-complete-connections-rejected-20261031.json)。


## 2026-10-31 正确性修复 Q：保留舍入后重合的极薄连接区间（待验证）

延长JFR窗口后，N原基线sequence133的path1725252报error38：连接栈为空却收到volume-only出口。关闭JFR固定sequence133、再开启现有单path追踪均复现同一个拒绝。追踪看到边界63正常进出后，边界8388608直接收到出口。原始float输入的独立double slab计算给出入口2.202487864754145、出口2.2024878898115383，两者均舍入为float 2.2024879455566406；邻面选择按这个相同float排序，先枚举的顶面出口胜出。不是JFR引起，不通过放宽栈校验掩盖。

对照固定PBRT-v4 `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f`、Apache-2.0，`src/pbrt/util/float.h` 258–278行的DivRoundUp/DivRoundDown及NextFloatUp/Down的向外取整区间原则，延续原边缘双float求交残差。仅当共享边、相反朝向的真实两次交点落进同一个float格时，将先后交点映射至其包含区间的向下/向上float端点，使两个事件仍有可表示的顺序；共面三角形和原本可表示的间隔不改。PBRT不含本项目这项邻面适配。保留原介质栈拒绝、事件预算、射线方向和距离积分，不粗暴忽略薄层。

新增24组真实GPU谓词检查，覆盖6种顶点排列、两种边界朝向和镜像，使用运行时uniform保持输入不被常量折叠。独立double slab oracle同时检查入口/出口方向、包含端点、事件顺序与恰好两次消费。随后固定133、原57、至少128帧长窗口、生产连接/完整回归和1080p HDR门槛。它是新发现的正确性修复，不拿失败窗口报性能收益。

JFR已实际采集原生/Java执行样本、分配、monitor、park和GC，保留二进制及JSON证据。该录制含预热、完整64帧窗口和之后的拒绝诊断；失败报告栈与分配不能混为稳态开销，样本数不能换算成GPU毫秒。[JFR记录摘要](data/medium-host-jfr-20261031.json)。


Q完成验收：24组新增GPU排列/朝向/镜像谓词、固定57/133、128测量帧长窗、84组连接批处理对照、46组/152帧完整生产回归、两帧1080p HDR逐字节一致、7场景/46功能检查和6个validation生命周期全部通过。GPU初始全0%的正反序：N 178.555845→Q 197.715077 ms/帧，耗时增加10.7301%。这是必须保留的正确性修复，不算性能收益；此前基线不能完成sequence133。最新观测199.551731 ms/帧，CUDA stream同步176.873697 ms/帧、88.6355%，零丢样。[完整记录](data/medium-sub-ulp-intervals-20261031.json)。

## 2026-10-31 从根级输运架构审查并修复

从完整积分器开始比较，禁止把某段搬到另一后端就叫根因修复。固定PBRT-v4 b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f 的wavefront/integrator.cpp 356–425按深度执行closest→medium→materials→shadow→next，intersect.h 165起在GPU内部完成整条透射连接；固定LuxCore b872186d2fe995a762f73708c606847eed55843e 的pathoclbase_funcs.cl DirectLight_Illuminate/DirectLight_BSDFSampling按非零灯光/BSDF生产有效阴影任务。二者均Apache-2.0。它们的组织减少职责混杂、无效工作和状态搬运；没有在本机同场景测过它们的FPS，不能声称定量速度倍数。

| 根级职责 | 本项目问题 | 是否必要/修复边界 |
| --- | --- | --- |
| 估计器与采样 | 光学样本、两种首lobe、多个灯光及密度积分 | 保留全部预算与灯光，不能拿PBRT单灯采样替换当前全灯合同 |
| 路径生成 | M已消除无lobe选择的整条重复分支；分叉前共享前缀仍可另审 | 不混入本轮改变随机数和输出累加次序 |
| 完整顶点直接照明 | 每盏灯均返回CUDA、排队、交接；P仅合并一盏灯内段，轮数只少4.8% | 本轮合并完整顶点所有灯光的连接和贡献，在Vulkan内完成后一次返回CUDA |
| 数据存活 | 256字节主状态、352字节连接、两张96字节hit；1080p scratch1.775GB | 主状态留存是必要，逐灯重复全栈恢复与跨API流量应随完整顶点执行消除 |
| 调度关键路径 | 约179 CUDA和175 Vulkan调用/帧，host等待88%包含真实GPU工作 | 核对实际推进轮数、两个后端GPU时和关闭诊断整帧，不只删host同步 |
| 退化边界求交 | 共享边修复有全mesh邻面搜索，Q多重扫描造成额外工作风险 | 正确性必须保留；结构化邻接/快慢路径是独立后续架构候选，不能无证据删除 |

本轮候选R参考完整连接/队列职责，适配为“完整顶点直接照明”执行边界，不声称PBRT具有本项目全灯光ABI。按原ordinal顺序累计所有灯光，逐灯恢复原主介质栈、保持原epoch增量与随机种子；完成后CUDA只做主路径贡献和续射线，禁止重复累计。极端IOR/掠射及相函数近奇点保留CUDA回退。采用与P事先相同的bounded HDR阈值，不降低采样、散射、事件、光子或积分预算；非数学一致依据仍为固定PBRT Float相函数与CUDA fast-math政策。候选收益未知，必须先正确性再整帧正反序；若失败撤回R，不能用概念上的架构更好代替验收。


R正确性完成：44组/88帧路径（含8灯、选灯采样、非阴影灯及g=.999999回退）、84组/168帧连接、46组/152帧完整生产、两帧1080p bounded HDR、7场景/46功能、固定133与128帧长窗全部通过；7个validation生命周期关闭且零warning/error/drop。HDR相对RMS最大2.3234897674436277e-6、能量相对差2.440868693793929e-8、最大分量差0.0009765625、alpha一致。

R CUDA推进179.0625→114.1875次/帧，Vulkan续射线175.0625→110.1875次/帧；但四个吞吐窗起始GPU占用49/43/32/46%，按用户规则不计算或认定绝对收益。最新受载观测310.329166ms/帧，CUDA同步270.165275ms/帧仍第一，包住的Vulkan续射线147.947389ms/帧、CUDA推进48.680868ms/帧；不能把它们与host相加，不能与空闲旧窗作绝对比较。R仅通过正确性，性能待验收，不标为保留优化。[证据](data/medium-complete-vertex-direct-lighting-20261031.json)。

原N同窗两个子步全量输入合计，第5个pair调度轮之后占4.1947%，却占Vulkan续射线GPU时间29.8732%。早先3.7935%仅含pairStep0，后续用双子步4.1947%口径。调度轮不是物理路径深度；该证据解释尾部小队列为何值得整体处理，不保证删除全部对应GPU时间。

下一候选S消除查询管线重复几何修复：any-hit对边界调用gsBoundaryConnectionHit，closest-hit又以相同ray/候选调用同一函数。参考固定PBRT-v4 gpu/optix/optix.cu 197–235的职责分离：无alpha纹理时any-hit不构造SurfaceInteraction，closest-hit消费硬件交点；alpha纹理路径本身仍可能重复构造，不能说PBRT实现了本缓存。本项目保留额外鲁棒修复，确定性复用同一射线已算出的图元/重心/距离。每次trace清空token，以实例槽+原始图元核对；任何未命中、遮挡候选覆盖、OPAQUE跳过any-hit都回退原修复。不改变精度、薄层策略、介质验证或任何预算。分别冻结Q、R、R+S对照，S先严格HDR，再测真实Vulkan工作及整帧；R整体是否有收益仍须空闲三方验收。


## 2026-10-31 R+S 空闲验收与后续架构范围

S完成84组/168帧连接逐字节对照、两帧1080p逐字节HDR、46组/152帧生产回归、7场景/46功能、固定133及128帧长窗口；validation生命周期关闭且零warning/error/drop。Q、R、R+S正反序各64帧、测前GPU0均0%至1%，关闭诊断分别189.579180、189.197184、176.657964 ms/帧。R单独合计仅0.20%且正反序不一致，不认定稳定收益；组合帧时下降6.8157%、原生5.274841→5.660656 FPS，提升7.3143%。保留已验收组合，不将调用数下降冒充收益。

最新32帧观测182.874338 ms/帧，CUDA同步164.967881 ms/帧、90.2083%；内部Vulkan续射线98.434622、CUDA推进33.429399、Vulkan初始7.437551 ms/帧。101466区间零丢样。观测开销3.52%，不继续优化观测。完整新表与原始文件哈希见[data/medium-reused-boundary-hit-20261031.json](data/medium-reused-boundary-hit-20261031.json)。

用户恢复后扩大改造范围：从积分器根级职责、单后端阶段组织和完整输运生命周期深度对照成熟实现，允许大手术并保持频繁全量提交推送main。借鉴成熟实现的逻辑和思想，适配本项目，不机械逐字翻译。主线程顺序推进，不开新子代理或worktree。实际工作减少、完整帧净收益与正确性仍是保留标准；禁止把改名、移动等待或调用数减少当作整帧收益。


## 2026-10-31 候选T：重组边界求交的数据生命周期

主线仍为CUDA同步中最大的Vulkan续射线98.434622ms/帧。重新审视积分器、GPU阶段与几何生产者后，本轮先重做其边界求交子系统：网格静态位置关系在场景生成时准备；逐射线只遍历共享顶点的有序邻面，保留原位置相等语义、原枚举顺序和全部精确谓词。三角形索引前缀保持硬件BLAS原样，私有trailer与同代index allocation一起上传、退休；position-only更新也重建邻接，高度退化网格达到固定存储上限时退回原全mesh扫描，不牺牲功能。

对照固定PBRT-v4 b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f（Apache-2.0）：util/mesh.cpp 23–40将不随射线变化的几何准备放在构建阶段，shapes.cpp 206–260的边函数只生产一次并复用于分类/重心/距离，util/math.h 558–561的TwoProd用FMA残差而非软件拆分乘数。采用这三种职责思想；PBRT没有本项目薄层邻面修复或trailer，不宣称直接照搬其邻接。保留本项目两float扩展、向外舍入薄层顺序、栈校验、优先级与所有预算。FMA残差属于明确算法替换，事前冻结现有bounded HDR线，先尝试严格HDR；独立几何/能量/错误输入断言不放宽。上游文件SHA256见[data/medium-prepared-boundary-candidate-20261031.json](data/medium-prepared-boundary-candidate-20261031.json)。

本轮组合不声称完整renderer重构。完整输运仍为混合后端；先验收最大实测GPU任务的几何数据流重组，再由新表决定是否推进整个输运后端/队列分工。测试前全量推送main、冻结制品，依次验证拓扑独立oracle、position-only更新、GPU极薄/镜像/顶点排列、84组连接、1080p HDR，再做关闭诊断正反序整帧。失败保留证据并撤回，不以代码变化规模或调用数充当收益。


T正确性回归完成：两个1080p HDR帧及84组/168帧连接开关逐字节一致；拓扑独立oracle、position-only上传、极薄/镜像/排列GPU谓词、46组/152帧光学生产、27组边界解析、7场景/46功能、固定sequence133和128帧长窗全部通过。四份validation日志共生命周期完整关闭，零warning/error/drop；30着色阶段及17 HDR raygen预编译契约通过。确定消除了静态邻接重复搜索和求交边函数重复生产，不以源码变化替代FPS证据。

T正反序四个窗口测前GPU0均超过10%，仅归档实测，不认定绝对净收益。最新受载排名：32完成帧、101449区间零丢样，304.157313ms/帧。CUDA stream同步247.733275ms/帧、每秒814.490610ms、81.4491%；Vulkan release录制9.853797ms、3.2397%；CUDA kernel入队9.722591ms、3.1966%；Vulkan release提交5.300494ms、1.7427%；CUDA semaphore等待4.409941ms、1.4499%；信号3.339775ms、1.0980%；Vulkan consumer提交2.807275ms、0.9230%；park2.719500ms、0.8941%；其余18.270666ms、6.0070%。互斥求和1000ms/秒。空闲净吞吐仍须补验收；180FPS尚未达到。[完整证据](data/medium-prepared-boundary-results-20261031.json)。


## 2026-10-31 候选U：按GPU任务类型生产队列并间接派发

从整个输运生产/消费边界修复：旧active queue混合相机续射线与完整光连接，Vulkan每轮按上个host窗口的数量上界派发，raygen再读真实count并分支。对照固定PBRT-v4 b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f 的wavefront/integrator.cpp 356–425按职责推进，以及intersect.h的EnqueueWorkAfterIntersection/medium/nextRay/材质队列分工，采用“由生产者按下一阶段职责排队”的逻辑，适配本项目双后端。PBRT不包含本项目双端ping-pong队列或Vulkan间接参数，不机械翻译它的ABI。

CUDA以warp为单位将主路径存队列前端、光连接存后端；只增加固定64字节控制区，保持原path ID、完整介质栈、随机数、估计器公式和原逐path累加顺序。CUDA输出两个VkTraceRaysIndirectCommandKHR的精确width，Vulkan按两类工作分别间接派发，无CPU计数读回决策，不新增逐path状态数组；不支持间接RT的设备回原队列。空队列width=0，必须验证初始化标记只消费一次；固定64字节计入resident cap，partial tile按实际path数索引。没有承诺调用数下降：两类工作可能增加RT调用，但目标是消除过量invocation与混合分支执行，最后看完整帧。

此候选不改变数学，门槛是严格HDR逐字节一致，不能用bounded放宽。先独立CUDA整数队列oracle覆盖full/compact/transposed状态、1/31/32/33/255/256/257/1069路径、连续三次交换与空队列；再84组/168帧生产连接开关、1080p HDR、功能/固定133/长窗和生产回归。全量提交推送、冻结制品后测试，负优化撤回。

受载时依然核对实际工作消除并列表：射线/查询数量、无效派发槽、整段重复计算和状态读写。U复用已有pair读回统计推导旧容量、真实排队数量与消除的无效invocation，不新增GPU读回。工作量减少证明消除了哪些工作，不能冒充空闲FPS收益。


## 2026-10-31 T/U 空闲验收完成

六窗 S/T/U/U/T/S 测前 GPU0 全为0%，关闭诊断，各版本64个完成帧。T边界准备的净收益现已确认，替代前文“待空闲验证”的状态；较S帧时下降4.0845%。U独立CUDA队列72次交换、84组/168帧生产连接和两个1080p HDR帧逐字节一致；46组/152帧光学生产、7场景/46功能、固定57/133、128测量帧长窗及30阶段/17 HDR raygen契约全部通过。五个validation生命周期完整关闭，零warning/error/drop。

| 关闭诊断版本 | ms/帧 | 原生FPS | 较前版帧时降幅 | 较前版FPS提升 |
| --- | ---: | ---: | ---: | ---: |
| S | 180.342042 | 5.545019 | — | — |
| T：边界数据准备 | 172.975947 | 5.781151 | 4.0845% | 4.2585% |
| U：按任务生产精确队列 | 162.145478 | 6.167301 | 6.2613% | 6.6795% |

S到U累计帧时下降10.0900%，FPS提升11.2224%；正反序均改善。U每帧旧容量20,998,341.25槽、真实队列14,146,887.25槽，消掉6,851,454个无效invocation，减少32.6285%。它没有减少几何射线查询或采样预算；两类间接命令不能说成RT命令数下降。

| 排名 | 互斥host阶段 | ms/帧 | 每秒累计ms | 占比 | 较T观测降幅 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | CUDA_STREAM_SYNCHRONIZE | 144.917216 | 874.292823 | 87.4293% | 4.1571% |
| 2 | VULKAN_RELEASE_RECORD | 3.723106 | 22.461687 | 2.2462% | 21.6404% |
| 3 | CUDA_KERNEL_ENQUEUE | 3.415419 | 20.605392 | 2.0605% | 32.6273% |
| 4 | VULKAN_RELEASE_SUBMIT | 2.093844 | 12.632264 | 1.2632% | 27.4403% |
| 5 | CUDA_SEMAPHORE_WAIT_ENQUEUE | 1.556041 | 9.387671 | 0.9388% | 30.5319% |
| 6 | VULKAN_CONSUMER_FENCE_WAIT | 1.261097 | 7.608261 | 0.7608% | 10.3445% |
| 7 | CUDA_SEMAPHORE_SIGNAL_ENQUEUE | 1.144450 | 6.904524 | 0.6905% | 37.0923% |
| 8 | VULKAN_CONSUMER_SUBMIT | 0.927919 | 5.598180 | 0.5598% | 40.8196% |
| — | 其余36项合计 | 6.714547 | 40.509198 | 4.0509% | 31.5202% |
| — | **互斥求和** | **165.753637** | **1000.000000** | **100%** | **8.2958%** |

32帧观测，101453区间零丢样，U初始GPU2%、T为0%。GPU续射线90.069280→83.626682、CUDA推进33.864603→31.552737 ms/帧，二者不能再次与host表求和；没有把等待挪去另一后端。观测开销U约2.23%、T约4.49%，不再打磨观测。180原生FPS目标仍未达到。[完整证据与原始文件哈希](data/medium-task-queues-20261031.json)。


## 2026-10-31 撤回V/W：平面证书与专用连接命中组

V把已证明为坐标平面的内部命中改为两float平面求商并消费硬件重心；W把连接查询拆成独立SBT命中组和专用payload，删除不消费的完整表面交互数据。参考固定PBRT-v4 b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f的OptiX硬件重心、Disk直接平面求交与TraceTransmittance精简结果原则。

两项各84组/168帧连接、两个1080p完整HDR帧均逐字节一致；42组独立平面oracle、原24组亚ULP薄层、46组/152帧光学生产、7场景/46功能、固定57/133及128帧长窗全部通过，七个validation生命周期零warning/error/drop。稀疏样本741/749（98.9319%）消除了完整软件三角形重算，但不能以此宣称整帧成功。

短窗口曾测得组合帧时177.783272→164.527992ms、下降7.4559%，随后同轮空闲观测162.862216→164.826506ms未复现收益。将预热扩到64帧并做U/组合/组合/U正反序，每版128个完成帧、四窗初始GPU0均0%：U160.103725ms/帧、6.245951FPS；组合160.335044ms/帧、6.236940FPS，帧时增加0.1445%，合计持平、非确定性收益。撤回此前“稳定净收益”的判断，完整撤回两项实现、ABI开关、专用组和生成SPIR-V；恢复U源码，不把它们算作优化成果。保留冻结代码提交、JAR和全部正确性/性能证据。

用户最新授权：同一最大热点可一次改多项、范围扩大，中间可做正确性验证，组合完成后统一性能验收。用户所说“反复确认”适用于**选择新候选之前**：必须以当前总榜→内部最大实际GPU工作→其中最大子过程逐层核对，并指出候选消掉何种/多少主体工作；不能用反复重测旧验收替代选点判断，不能因方案看起来像架构改造就直接做。

当前实际GPU大头仍为Vulkan续射线。新分组7稀疏观测：后续连接查询占69.9353%，首段查询15.5204%，查询合计85.4557%；段输运/介质栈6.7281%，焦散3.0585%，其余4.7577%。这些是抽样内部占比，不能折算整帧ms。样本内首段1116次、后续2420次连接查询函数调用，函数内还可能因overlay/nonshadow继续trace，不把调用数冒充精确硬件射线数。下一候选先针对反复遍历，停止加码只占残余的算术/状态细节。

已经对照固定Intel Embree 82c67a5318c535be94f017b9ec745498b5eb5606（Apache-2.0）tutorials/next_hit/next_hit_device.cpp的single_pass、gather_next_hits及multi_pass：在一次遍历收集多命中、排序去重，或每次消费最近N个事件。适配时必须保留本项目薄层共享边、同距歧义回退、完整介质栈、预算和溢出回退；不能照搬示例截断超额命中的显示策略。拟把收集、完整连接消费和执行阶段分工放在同一批改造中；尚未实现、尚无收益结论。

[完整撤回依据、最新排名和原始证据哈希](data/medium-plane-and-query-rejected-20261031.json)。


## 2026-10-31 撤回 X：多命中候选的驱动编译内存严重膨胀

X在提交`3c017cc`实现多命中收集，`5ddc68e`补充独立连接执行入口和容量回退。已完成120组/240帧严格开关HDR，以及两个完整1080p HDR帧对照U，均逐字节一致；两次生产validation生命周期完整关闭且零warning/error/drop。完整HDR捕获使用性能工作负载配置，未开启validation，不把不存在的HDR validation文件算作通过。初版因新版执行职责拆分已被主动取消，不作验收结论。

尽管图像正确，这一批存在严重的启动资源回归：PID20404在驱动管线编译中观测到9919MiB工作集；线程栈停在vkCreateRayTracingPipelines对应的deferred pipeline等待，native driver编译线程仍活动，不能把它归因于Java堆或CUDA stream运行期等待。首次1080p输出距启动前GPU记录约234秒。原raygen SPIR-V为231168字节，独立连接候选仍为1598752字节、约6.92倍；职责拆分未解决生成程序体的膨胀。尚未通过稳态净吞吐验收，不报告FPS收益。

用户报告内存膨胀与编译过慢后，该捕获进程已经自行结束、内存已释放。完整撤回X两次源码、管线生命周期、ABI、观测和生成SPIR-V改动，恢复已验收U。保留冻结JAR、源码提交与全部证据，不以正确性通过为理由保留严重启动回归。

后续GPU运行工具默认采用Windows Job Object全进程树硬内存限制：总预算3GB，执行树2.5GB，PowerShell宿主预留0.5GB；覆盖Java堆及驱动/NVRTC等native提交内存，不能仅靠-Xmx。超过上限的候选判失败，不自动调高上限重试。完整基准和PBR专项均使用同一既有有界启动器，冻结制品后运行。新渲染候选先做有界冷启动和首帧资源门槛，再做图像与稳态性能验证；本轮冷启动失败的着色器不能再无界运行。

下一次重做仍针对连接重复遍历，但要消除多处调用内联造成的程序体复制，使遍历/候选分类/输运消费共用一个紧凑循环，不把完整材质程序在每个查询入口重复展开；先用生成IR规模及有界冷启动确认，再跑完整吞吐。此方向尚未实现，不能声称内联是已被硬件分析证实的唯一根因。180FPS目标保持，当前可信净吞吐仍为U验收数据。

## 2026-10-31 X3：修复编译膨胀后继续多命中路线

用户明确要求继续修根因；撤回X是止住资源回归，不是放弃多命中架构。检查实际SPIR-V后发现X连接入口包含92987条指令、585个循环合流、4892个分支合流，只有1个函数定义、0个函数调用。shaderc的performance策略提前完整内联；项目已有保留结构的transport策略，但新入口未纳入。X3恢复X2的完整多命中/溢出/歧义回退及职责拆分，仅为连接入口启用结构化编译，不改采样、材质公式或预算。后续仍须严格逐字节验证，不能因源码公式未改就跳过验证。

生成的HDR入口由1598752降至245532字节（84.6423%），指令92987降至14970（83.9009%），保留120个函数定义和374个调用；循环合流30、分支合流278。8份预编译资源已重生成，4种不同编译配置各0.590–0.629秒；生成整树峰值提交内存2.099GB。它们是离线生成/IR数据，尚不证明Vulkan驱动编译内存或FPS改善。下一步在既定2.5GB执行树上限内捕获完整1080p首帧，之后严格连接/HDR、完整功能与诊断关闭正反序验收。不得把IR缩小直接写成帧率收益。

X3有界冷启动失败：88.021秒后驱动deferred管线返回-3，执行树峰值提交2.501GB，没有首帧。保留函数结构不足以控制驱动编译资源，不提高上限，也不把IR缩小视为问题已解决。测前GPU46%，该次启动不能用于FPS对比。

X4继续从程序职责边界修复：参考[NVIDIA 13_callable_shader](https://github.com/nvpro-samples/vk_raytracing_tutorial_KHR/tree/952a1a8e8b85f25781db7014d4d2bda124435e79/raytrace_tutorial/13_callable_shader)的材质callable分工，通过实际SBT调用共用原材质求值函数，不照搬示例的材质模型。收集入口不再包含材质函数体；首段/后续查询共用一个查询调用点，专用入口仅在完整vertexDirect模式下启用，旧配对路径继续由原入口处理。补齐可选callable模块的缓存身份、生命周期、descriptor可见性、SBT组与直接/间接派发区域；无新全局逐path数组、CPU读回或CUDA同步。预编译生成门槛检查恰有一个callable调用点，材质阶段必须是无trace的callable。HDR收集入口147868字节，材质独立阶段459424字节。尚待有界驱动冷启动与完整正确性/吞吐验证，未认定收益。

X4独立连接管线驱动编译成功返回，wall=13431.675ms；完整启动18.519秒后进入输运拒绝诊断，诊断一次申请530841600字节失败，完整HDR尚未产出。不能把这个启动算作完整成功。单独120组/240帧严格连接对照已逐字节通过，有界进程树峰值提交1.766GB；validation另行核对。原拒绝诊断为全path同时分配state/hit/connection，可能分配超过GB并掩盖原拒绝原因。接续修复为4096条路径的有界扫描，最多读取4条完整记录；成功帧不进入此路径、不新增读回或计时。诊断自身失败时保留原拒绝路径数和frameSequence，将诊断异常作为suppressed，不覆盖原失败。

严格连接validation已核对：2 opened/2 closed、零warning/error/drop。分块诊断保住了原拒绝454117条路径与frameSequence=0；后续读回发现device lost。相同预算U完整1080p两帧成功，峰值提交2.258GB。进一步实测JVM在-Xmx512m时InitialHeapSize=528482304字节；显式-Xms32m、最大堆与Job预算不变，X4完整两帧成功，峰值提交2.215GB。两个试验启动器均固定小初始堆，避免默认预提交挤占native/驱动余量；不提高上限。

X4完整HDR第0帧逐字节一致，第1帧仅(773,338)蓝通道相差一个half-float bin（0.00048828125）。仍未通过严格门槛，不放宽容差。X6保留真正的callable边界及单查询循环，恢复与U一致的performance编译策略；无材质内联后HDR入口反而缩至130232字节，原先结构化编译已不再需要。接下来重新验证资源、严格HDR与全功能，然后才做净吞吐判断。

X6连接管线驱动编译10305.751ms，完整两帧进程树峰值提交2.216GB、总16.767秒；没有重现10GB编译膨胀。但两帧hash与X4完全相同，第1帧单通道差异仍在，不能归因于结构化/优化编译开关。X7仅恢复原连接查询循环组织，保留独立材质callable与小初始堆，以分离“循环重排”和“跨着色阶段求值”的影响。生成守卫保留真实callable边界及无trace材质要求，不再要求循环重排后的单调用点。此为定位试验，尚无正确性或吞吐收益结论。

X7原循环在相同小初始堆下首帧仍device lost，峰值提交2.765GB，未能进行HDR对照；冻结JAR的396份着色资源均与当前来源逐字节核对。立即恢复已能完整运行的单查询循环与单callable调用点门槛。X8进一步将query类型初始化放回调用方、通过callable数据传入材质阶段，恢复与原producer/consumer一致的输入职责；不在材质入口将query编译期常量化。用于定位跨阶段求值差异，仍保留严格门槛。

X8完整两帧成功，峰值提交2.300GB，输出hash仍与X4/X6相同，输入初始化位置不是差异来源。X9只对独立材质callable保留函数结构，入口射线仍为优化编译；材质模块459360→105244字节，离线生成0.553秒。此为隔离材质阶段数值生成的试验，不改数学或验收阈值；尚待实际输出核对。

X9驱动连接管线编译9812.538ms，随后首帧device lost，原始拒绝仍为454117条路径、sequence=0；进程树峰值提交2.724GB，总13.506秒，没有HDR可供比较。撤回这项材质编译策略，不再将IR缩小当成驱动资源问题已解决。

X10恢复已能完成两帧的优化材质编译，改材质阶段的数据生命周期：先在局部GpuScenePayload中完成原gsEvaluateTriangleHit，再一次性发布至callable数据；state、launchInfo和诊断输入照原producer传递。它针对跨阶段直接写入与原局部求值的差别，尚未证明是单通道差异的根因。新材质模块459384字节，生成2.773秒；保持原内存预算、单查询循环及严格逐字节HDR门槛。重新打包并核对生成资源后，全量推送main、冻结制品，再做两帧1080p对照；通过后才恢复生产回归与性能验收。

X10完整两帧成功，连接管线驱动编译11350.191ms、进程树峰值提交2.310GB、总17.361秒；单通道差异与X4/X6/X8完全相同，局部求值没有解决差异。同一JAR关闭多命中后两帧也未与U完全一致：第0帧(245,184)G相差0.0009765625，第1帧一致。不能把所有差异都归因于callable，也不能拿这个变化过的关闭路径作为U对照。

X11先隔离原路径：closest-hit恢复U原始实现；非collected入口直接保留原gsOpticalConnectionHit，不经过新增包装函数。生成后原closest-hit的2份SPIR-V及普通光学入口的8份SPIR-V全部与U逐字节一致；新的collected入口仍为130268字节，材质callable保留。该二进制核对确认普通路径的着色程序已经恢复，图像仍须实际验证。下一步同一制品关闭/开启多命中分别对照U，严格门槛不变。

X11关闭多命中两帧恢复与U逐字节一致；开启时单通道差异仍在。开启的连接管线编译9487.280ms、进程树峰值提交2.246GB、总15.939秒；关闭峰值1.907GB。没有稳态FPS结论。由此将关闭路径的额外差异与collected内部剩余差异分离。

后续用可选主机侧定点捕获定位第一处状态分歧：opticalCaptureDirectory、opticalCapturePath及opticalCaptureSequence只选择一个frame/path，在已有CUDA交接回调前后读原始header/state/hit/connection与输出记录。不改任何GLSL、SPIR-V、CUDA源码、样本预算或device allocation；默认静态关闭，不进入读回。与早前会改变CUDA代码的printf追踪分开，先核对观测开启后的完整HDR仍各自复现未观测输出，观测不稳定时不得拿它宣称原根因已证实。该调试读回不用于性能窗口。
