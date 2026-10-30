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
4. 同一候选跑正确性、场景 HDR 图像对照、帧时间与吞吐。确定性小收益可保留；噪声范围内的“提升”不算收益；确认退化则立即撤回该轮改动。
5. 更新延迟排名和架构说明。显著收益应对应最大热点下降；如果总帧时间没按预期变化，检查重叠、资源竞争和新的关键路径再继续。
6. 每个闭环保留可复现命令、原始输出和结论；已验证的改动按当前仓库发布/分支流程及时推送 GitHub，不把未完成验收的候选当作优化成果。

不能为了 FPS 改低 SPP、散射步数、路径事件预算、介质容量或图像质量。每项降级若成为产品选项，必须显式命名、单独验收并保留原质量档位。

## 清晰度与正确性

关闭降噪时的原生介质图像必须单独验收。锁定随机种子和场景，保存线性 HDR 原图；检查渲染分辨率、像素覆盖、光线方向/焦点映射、shader 输出写入、累积/归一化、曝光与显示变换。以解析或高样本参考比较边缘、细节和亮度误差；不允许用显示端锐化或降噪掩盖采样/坐标/合成错误。

涉及输运数学、采样或滤波时，先调查成熟开源 renderer 的对应路径，记录项目、版本/commit、源文件、算法边界和许可证。移植要适配本 API 的 RGB、密度、相函数、资源生命周期和 Vulkan/CUDA ownership 语义，并以相同 HDR 参考验证；不能按类名或结果截图照搬，也不能把不同积分器/画质设置的数据直接比较。

## 本轮记录

| 项目 | 状态 |
| --- | --- |
| 目标场景 | 默认 PBR 均匀直接散射；必须使用展厅实际场景和相机，不以简化合成场景代替 |
| 目标 | RTX 5080 Laptop，1080p，1 SPP，原生 120 FPS；FG、重建、时域累积、降噪关闭 |
| 当前观测 | 尚未建立本轮完整 PBR 路径阶段表；已有 optical 路径计时不覆盖默认 PBR 直接散射 |
| 当前性能结论 | 无；在首轮全阶段 instrumentation-off/on 基线完成前不排名、不改算法 |
| 产物位置 | 原始诊断数据写入忽略的 `build/reports/medium-performance/`；仓库只保留稳定规范与最终摘要 |
