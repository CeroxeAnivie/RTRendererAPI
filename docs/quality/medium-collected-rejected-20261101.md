# collected 多交点候选实测与结构问题

同一个冻结 core（源码 `6c6b08a`，完整哈希见 JSON），只切换 `top.ceroxe.rt.medium.cuda.opticalCollectedConnections`。场景固定 micro-all、1920×1080、1 SPP、光学样本 2、原事件/散射预算；FG、降噪、时间累积、重建及诊断关闭，进程树上限 3 GB。两边测前 GPU 0%，均完成 4 帧预热及 16×16 个测量帧，exit=0。

| 关闭诊断实测 | 关闭候选 | 开启候选 | 开启后的变化 |
| --- | ---: | ---: | ---: |
| 原生完成 FPS | 6.196455 | 3.041974 | 下降 50.9078% |
| 整帧 ms/帧 | 161.382595 | 328.733924 | 增加 103.6985% |
| 进程树峰值 GB | 2.886 | 2.429 | 两边均未超上限 |

随后关闭候选复测：4 帧预热、32 帧测量，6.106292 FPS、163.765513 ms/帧，进程树峰值 1.268 GB，exit=0。两帧 1080p RGBA16F 输出在开关前后逐字节一致；使用完整字节比较，不放宽任何数值阈值。

## 实际大头的前后对照

以下来自相同序列的两组 32 帧诊断，测前 GPU 均 0%。GPU 工作包在 host 同步内，不能和 host 等待再次相加。

| 实际工作 | 关闭 ms/帧 | 开启 ms/帧 | 关闭次数/帧 | 开启次数/帧 |
| --- | ---: | ---: | ---: | ---: |
| Vulkan 光学续射线 | 84.049709 | 250.204550 | 110.125 | 110.125 |
| CUDA 光学推进 | 31.101868 | 33.262537 | 114.125 | 114.125 |
| Vulkan 初始射线 | 7.183875 | 7.678897 | 4 | 4 |
| host CUDA stream 同步 | 144.346222 | 313.087275 | 单独见完整排序 | 单独见完整排序 |

续射线增加 **166.154841 ms/帧**，不是单纯把等待换了位置。跨 API 的推进次数没有减少。完整开关排序、计数和原始区间校验见[精简证据](data/medium-collected-rejected-20261101.json)；开启观测 101409 个区间零丢样、独占求和 1000 ms/秒。

## 为什么批量收集没有变快

1. 当前默认 `opticalVertexDirectLighting=true` 已把完整顶点直射光及光源连接处理合进 Vulkan。collected 主要替换这段 Vulkan 内部的交点查找，不能再将此前已经消掉的跨 API 交接算成自身收益；本轮 dispatch/推进计数也证实未减少。
2. `gpuscene_collected_gather.rcall` 第 41–42 行启动固定完整距离的 NoOpaque ray query，遍历候选时没有确认更近距离，也没有在找到不透明遮挡时终止。数组容量 8 只限制保存的交点数量，并不限制遍历范围；每个候选仍有材质检查、去重、排序/移动，边界另有精确求交。
3. `gpuscene_collected_connections.glsl` 第 60–65 行遇到非 volume-only 事件时回到 `gsOpticalConnectionHitRepeated`，保留原生产着色器的数值语义。这意味着先做收集的射线仍可能再跑原 traceRay；这一轮 HDR 一致不能掩盖这项重复工作。

固定对照 [Embree 82c67a5318c535be94f017b9ec745498b5eb5606](https://github.com/RenderKit/embree/blob/82c67a5318c535be94f017b9ec745498b5eb5606/tutorials/next_hit/next_hit_device.cpp)（Apache-2.0）：`gather_next_hits` 第 197 行注明“first N hits up to the first opaque surface”；第 237–240 行在收够 N 个或末尾是不透明表面时，将 `ray->tfar` 收到最后保留交点。这里缺失的正是减少后续遍历的剪枝逻辑。Embree 的可改 tfar filter 与 Vulkan triangle ray-query 的能力不同，不能只翻译交点列表和排序，就假设具有同样收益。

已经证明的成本增长范围是整个 Vulkan 续射线阶段；收集、排序、callable 和回退各自的数值占比尚未单独测出，不把寄存器溢出或占用率当作已知根因。

结论：本版候选拒绝启用，生产默认保持 false，保留源码和证据供重新设计。新的候选必须先指出能删掉哪些实际求交、如何裁掉遮挡之后的工作、如何避免收集后重查，并在当前已合流架构上计算收益，不能复用旧架构的预期。
