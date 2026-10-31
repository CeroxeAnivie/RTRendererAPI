# 新增介质 API 的计时覆盖

本次只补观测，不改场景、渲染算法、预算或同步顺序。以下是执行路径覆盖表；功能是否真的启用，由性能场景矩阵、请求预算及原生提交计数共同检查，不能仅凭出现一个计时名就声称功能已执行。

| 功能 | 实际执行路径 | 原缺口 | 补齐后的计时 |
| --- | --- | --- | --- |
| 相机吸收/自发光，含密度场 | `CudaCameraMediumManager` 的四参数 primary emission CUDA launch | 只有共享交接总时间，没有此 kernel 的 GPU event；native 线程内 wall 未挂回调用者 | `cuda.entry.primary_medium_emission_resolve`；`PRIMARY_MEDIUM_NATIVE/NATIVE_TASK_EXECUTION`，含上传、下载、提交、同步子项 |
| 显式边界、优先级及密度吸收 | `CudaBoundaryMediumManager` reset/step/packet resolve | 匿名 kernel 没有 GPU event；native 线程事件不在墙钟树内 | `cuda.entry.native_boundary_reset`、`cuda.entry.native_boundary_step` 或 `cuda.entry.native_boundary_packet_resolve`；`BOUNDARY_MEDIUM_NATIVE` |
| 密度直接散射，含 PBR 组合 | `CudaDensityScatteringManager` prepare/resolve 与分块 Vulkan 连接 | CUDA 两个阶段未独立计时；native 线程缺 wall 归因 | `cuda.entry.density_scattering_prepare`、`cuda.entry.density_scattering_resolve`；`DENSITY_SCATTERING_NATIVE` |
| 均匀多次散射 | `CudaMultipleScatteringManager` init/reset/step/reduce | 匿名 kernel 无 GPU event，native wall 缺失 | `cuda.entry.camera_multiple_*`；`MULTIPLE_SCATTERING_NATIVE` |
| 密度多次散射 | 同 manager 的 heterogeneous prepare/connections/start/reset/step/reduce | 各计算阶段没有 GPU event | `cuda.entry.heterogeneous_multiple_*`，同一帧所有 bounce/连接次数累加 |
| 显式边界散射，含密度与多次散射 | 同 manager 的 boundary scatter prepare/connections/continue/reset/step/reduce | 各阶段没有 GPU event | `cuda.entry.boundary_scatter_*`；`MULTIPLE_SCATTERING_NATIVE` |
| 平滑/粗糙玻璃、部分透射、金属反射 | `CudaOpticalMediumManager` 的 native optical step 变体，Vulkan optical query | 普通 timings 只有含等待的 CUDA wall；缺实际 step GPU event；单轮 query 不独立 | `cuda.entry.native_optical_reset`、`cuda.entry.native_optical_step_<layout>`；`vulkan.optical.cameraInitialTrace/end`、`vulkan.optical.cameraContinuationTrace/end`；`OPTICAL_MEDIUM_NATIVE` |
| alpha、LIGHTMAP_MODULATED、叠层 mask/depth、混合/乘法覆盖 | 同 optical shader 与 native optical step 内的组合表面连接逻辑 | 与前行相同；既有详细 shader 阶段只在显式 detailed 模式才输出 | 同前行的真实 kernel/query 计时；详细阶段保留。每种表面由独立场景实例化，不制造独立虚构 kernel |
| 均匀普通 Vulkan 光子焦散与体积焦散 | PBR/optical producer 内 `CausticPhotonPlan.record` | 光子建图只包含在整个 producer 内 | `vulkan.pbrMedium.photonBuild` 或 `vulkan.opticalPhoton.vulkanBuild/end` |
| 密度/边界/嵌套介质光子焦散 | `CudaOpticalPhotonPass`，光子 query、CUDA clear/reset/step、最终 map copy | 缺独立 GPU event，初始化/query/复制混在共享 producer/consumer | `cuda.entry.native_optical_photon_*`；`vulkan.opticalPhoton.mapInitialize/end`、`vulkan.opticalPhoton.trace/end`、`vulkan.opticalPhoton.mapCopy/end` |
| 内置 shader data 声明与实际绑定 | 路由/布局校验、描述符数据绑定，再进入对应原生路径 | 原 CPU 阶段已有墙钟边界；原生路径新增计时由上述各行补齐 | 保留 `ROUTE_AND_PIPELINES`、`TEMPORAL_UNIFORMS_DESCRIPTORS`，以及实际所选路径的 kernel/query；不声称单独声明会产生 GPU 工作 |
| PBR、均匀直接散射及所有共享原生交接 | 已有显式 kernel/primary/light query 与共享 Vulkan producer/continuation | 旧固定数组可读，通用全功能场景报告缺统一命名导出 | 保留旧计时数组及全部下标；新增 `vulkan.shared.producer`、`vulkan.shared.continuation` 和已有显式阶段的统一导出 |

`OpticalFrameTimings.stageSnapshot()` 返回 `Map<String,long[]>`，数组为累计 GPU 纳秒与调用次数。`stageDeltaJson(before, frames)` 输出 `totalMs`、`msPerFrame`、`calls`。它们是 GPU event/timestamp 证据，可能具有父子包含关系，不能与墙钟等待相加；互斥求和继续使用 `OpticalWallTimeline`。

关闭 `opticalFrameProfile` 时不记录 CUDA event、不获取新增 Vulkan timestamp capture、不更新命名计数器；关闭 wall timeline 时不创建 awaited wrapper。kernel 名在编译 entry point 时保存，运行时不反复反射或读回输入。开启观测后，CUDA event 在原有 stream/fence 完成点统一解析，Vulkan query 在原有完成 fence 之后读取，不新增 GPU 同步或每路径日志。已保留默认直接散射/PBR 的原计时名，避免旧表断裂。光学 queued pair 现也在已有最终同步后解析该批的 event，避免事件跨批悬挂。

本表描述已实现的观测机制，不冒充实测开销验收。每个性能场景仍须与关闭诊断的同参窗口比较开销，并检查墙钟 droppedEvents 为零、命名事件确有调用。新增观测不应借用默认场景的既有开销百分比。
