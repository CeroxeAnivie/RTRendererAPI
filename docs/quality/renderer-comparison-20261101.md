# 2026-11-01 干净 GPU 原生引擎对照

本轮执行时间：北京时间 11:46～11:48。RTX 5080 Laptop GPU，1920×1080，无降噪、
无帧生成、无分辨率缩减，最大路径深度 5。14 项的起测 GPU 占用全部为 0%。
GPU 测试顺序执行；各项之间等待利用率统计窗口清空，避免把自己的上一个任务算成外部负载。

## 混合介质场景

各项目的下列 FPS 表示每秒完成的 1080p、1 SPP 渲染量，不含窗口显示。
LuxCore 按预热后完成样本数的增量计数；Cycles 按预热后实际完成的 64 SPP 渲染批次除以
时间计数；Mitsuba 按连续、独立的 1 SPP 完成调用计数。Cycles 的实际执行样本数另由日志
中的 `Sample 16/16`、`Sample 64/64` 检查，不能用请求值代替实际完成数。

| 项目/模式 | 工作组 | FPS | 每 1 SPP 渲染量耗时 |
|---|---:|---:|---:|
| LuxCore RTPATHOCL | 32 | 10.28 | 97.28 ms |
| LuxCore PATHOCL | 32 | 10.21 | 97.95 ms |
| LuxCore TILEPATHOCL | 32 | 0.34 | 2938.83 ms |
| LuxCore RTPATHOCL | 128 | 9.82 | 101.85 ms |
| LuxCore PATHOCL | 128 | 8.87 | 112.74 ms |
| LuxCore RTPATHOCL | 256 | 9.29 | 107.70 ms |
| LuxCore PATHOCL | 256 | 8.79 | 113.80 ms |
| Blender Cycles 4.5.9 LTS / OptiX（物理语义迁移） | — | 36.70 | 27.25 ms |
| Mitsuba 3.9.1 / CUDA（共同功能子集） | — | 9.53 | 104.93 ms |

Cycles 如果每次单独提交并完成一张 1 SPP 图，包含 Blender 场景交接、依赖图更新和结果
处理，实测 **13.45 FPS / 74.35 ms**。36.70 是持续采样吞吐，不冒充该调用的实际帧率。
Mitsuba 此项本身就是独立 1 SPP 调用。LuxCore 的渐进采样吞吐也不代表无历史的独立提交帧。

三个项目沿用同一套 63 个对象、756 个三角形、变换、30 个单元布局、相机和体积系数。
跨项目尚非完整语义等价，不能把这些数字计算成等质性能提升：

- Cycles 使用自己的 BSDF、采样和灯光单位；重叠体积行为不同于 LuxCore 显式优先级。
  使用全局多次散射设置；原 4³ x 方向密度梯度用原生节点表达，边缘插值有差别。
  表面/体积发光、材质及纹理/顶点透明度、法线、MR 纹理、灯光类型和几何均保留。
- Mitsuba 使用原生 `volpath`、HG 和体积网格。当前适配未包含体积自发光、LuxCore 体积
  优先级、按体积单独开关单次/多次散射和顶点颜色/透明度乘积；保留材质/贴图透明度、
  表面发光和主要介质系数。因此只作为共同功能子集，不能叫完整全覆盖等价结果。

## 最小原生对照

同一立方体、12 三角形、同一相机和缩放、漫反射率 (0.5,0.6,0.7)、环境辐射
(0.12,0.16,0.22)，完全没有介质。下表同样为每秒完成的 1 SPP 渲染量。

| 项目/模式 | FPS | 每 1 SPP 渲染量耗时 |
|---|---:|---:|
| LuxCore RTPATHOCL / WG32 | 18.22 | 54.87 ms |
| LuxCore PATHOCL / WG32 | 25.34 | 39.46 ms |
| LuxCore TILEPATHOCL / WG32 | 0.34 | 2969.09 ms |
| Cycles OptiX（64 SPP 批次） | 655.30 | 1.53 ms |
| Mitsuba CUDA（独立 1 SPP 调用） | 870.23 | 1.15 ms |

Cycles 最小场景独立 1 SPP 调用是 43.38 FPS，不能和 655.30 的持续采样吞吐混用。

## 开启方式核对与结论

- 使用官方 LuxCore 2.11.2 SDK 及匹配版本的公开接口；没有修改积分器。
- 独立 C++ 测量程序仅链接上游 SDK，不加载 Java、JNI、Vulkan 或 RTRenderer 运行时。
- 设备统计确认为 RTX 5080 Laptop 的 `CUDA_GPU`；启动日志确认 OptiX enabled。
- CPU 渲染线程为 0，完整 1080p，AA=1，过滤 NONE，未开降噪、FG、分辨率缩减。
- 启动、首次内核编译和预热不进入三轮采样增量测量。
- 三种 GPU 模式及两种额外工作组大小都没有把当前混合场景带到高帧率；本轮 WG32 最快。
- 这能说明当前 LuxCore 路线表现差，不能证明 LuxCore 全项目都是低质量，也不能证明场景
  在所有实现中的性能上限。Cycles 的吞吐已超过 20，最小场景的两个独立项目也明显更快。
- 此前把该混合场景叫作“本来就应当特别快”缺少依据；该验收仍未通过。

原始 JSON、每项起测 GPU 状态、设备统计、实际样本日志及生成图：
[`data/renderer-comparison-clean-20261101/comparison.json`](data/renderer-comparison-clean-20261101/comparison.json)。

测量入口：`tools/Measure-CleanRendererComparison.ps1`；原生计数器：
`tools/luxcore_native_benchmark.cpp`；官方引擎的积分器与调度器均未改动。

上游依据：

- https://github.com/LuxCoreRender/LuxCore/releases/tag/samples-v2.11.2
- https://github.com/LuxCoreRender/LuxCore/blob/b872186d2fe995a762f73708c606847eed55843e/src/slg/engines/tilepathocl/tilepathoclthread.cpp
- https://github.com/mitsuba-renderer/mitsuba3/blob/master/src/media/heterogeneous.cpp
- https://download.blender.org/release/Blender4.5/blender-4.5.9.sha256
