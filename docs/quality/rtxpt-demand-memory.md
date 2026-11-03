# 7.0.1 原生 RTXPT 内存修复验收

本轮确认并修复了默认解析光照路线中约 **818 MiB 的闲置资源分配**。相同玻璃球静止阶段，私有提交少 **815.934 MiB**，独立显存少 **817.633 MiB**。物理 RAM 工作集没有同步下降，本次反而高了 28.287 MiB；不能把提交内存或显存节省写成物理 RAM 节省。

以下是内存修复完成时的独立验收。后续通用灯光增量更新另行验收，不用这批固定场景数据代替动态灯光验证。统计摘要见 [JSON 数据](data/rtxpt-demand-memory.json)。原始本机日志、VMMap XML、JFR、截图和构建输出保留在 `build/reports/hexball-memory-20261102/`，不作为发行运行时文件。

## 对照方法和口径

原始 7.0.0 Demo 与 7.0.1 候选使用同一个确定性 fixture：1920×1080 输出、960×540 内部输入、SR Performance、1 SPP、四层精确光学界面、解析光照、请求 2× FG；SHaRC、ReSTIR 和独立降噪关闭。固定时钟和物理步进，三种材质各 32 帧静止、16 帧运动，最后 16 帧切换至 1280×720，总计 160 个真实原生帧。

内存对照只取**第一种材质 GLASS 的 1080p 静止观察段**，不用 720p 数据或不同阶段峰值拼接。进程私有提交和工作集来自外部 PSUtil；显存来自 Windows PDH 的进程 GPU Adapter Memory，保留适配器身份，选择独立显存非零的 NVIDIA 适配器。各列使用观察段中位数，旧版 10 个采样点、正式包候选 11 个。另用原生 DXGI 查询与资源创建/释放清单核对。

| 指标 | 修复前 | 7.0.1 正式包候选 | 变化 |
| --- | ---: | ---: | ---: |
| 进程私有提交 | 2712.367 MiB | 1896.434 MiB | −815.934 MiB |
| RAM 工作集 | 826.068 MiB | 854.355 MiB | +28.287 MiB |
| NVIDIA 独立显存 | 1903.723 MiB | 1086.090 MiB | −817.633 MiB |
| NVIDIA 共享 GPU 内存 | 174.980 MiB | 174.273 MiB | −0.707 MiB |
| VMMap 时刻追踪到的资源分配容量 | 1640.3125 MiB | 822.2500 MiB | −818.0625 MiB |

这些指标有重叠，不能相加。资源容量是 D3D12 分配粒度对齐后的账本，并不等于物理显存驻留；工作集包含共享页面，也不等于进程独占物理 RAM。

私有提交和 RAM 有关系，但没有一一对应关系：提交表示系统已承诺提供内存后备，涉及物理 RAM 和分页文件；工作集表示采样时实际驻留 RAM 的页面。已提交页面可以尚未驻留或已被换出，工作集又包含另一统计范围的共享页面。因此提交下降不保证工作集同比下降，也不能从提交差额反推出物理 RAM 节省。本轮只能确认显存和提交额度明显减少。

VMMap 只统计顶层区域；检查 write-combined 属性时只统计叶子区域，避免父子重复。相同静止阶段，write-combined 映射容量从 1964.492 MiB 变为 1148.238 MiB；其中 PrivateWS 从 52.211 MiB 变为 51.508 MiB，ShareableWS 均为 47.461 MiB。这说明此前接近 2 GiB 的映射不能解释成接近 2 GiB 的物理 RAM。VMMap 长期保留在本机 `D:\VMMap`。

## 根因、调用链与修复

| 根因 | 旧分配 | 修复后 | 处理 |
| --- | ---: | ---: | --- |
| SHaRC 关闭时构造函数仍创建 hash、accumulation、resolved 三个缓存 | 80 MiB | 0 | 保留配置元数据，首次实际启用再分配；关闭后按两条队列 fence 退休 |
| 常规显示也创建完整调试几何缓冲及输出副本 | 100.625 MiB | 0.3125 MiB | 保留 header/printf，按需恢复完整几何容量与副本 |
| 解析光照仍初始化环境烘焙器 | 384.3125 MiB | 0 | 按 shader 实际路线跳过 baker；保留正确类型的微小绑定资源 |
| 解析光照仍初始化通用灯光采样与反馈资源 | 253.375 MiB | 0 | 通用路线仍创建完整 baker，解析路线跳过它的分配与更新 |

上表旧环境分配包括四张主 cube（368.1875 MiB）、环境辐射/重要性图和源天空纹理；灯光分配包括十个大容量采样缓冲（228.375 MiB）及反馈、lookup、控制和读回资源。最早列出的 777.1875 MiB 是四个主要容量组的合计，不是最终实测净节省。完成逐资源清点后，上表原有容量共 818.3125 MiB，保留 compact debug 后移除 818 MiB；新增十个小型 fallback 绑定共 0.625 MiB，观察时 upload pool 少 0.6875 MiB，账本净差为 818.0625 MiB。

大资源创建时采集模块相对栈，用冻结 DLL 配套 PDB 符号化，得到以下调用链：

- `CreateRTPipelines → EnsureSharcCreated → SharcIntegration 构造函数 → createBuffer`：旧实现把“有这个功能对象”等同于“当前使用缓存”。修复后构造函数不分配缓存，`UpdateResources(enabled)` 才决定是否创建。
- `CreateRenderPasses → ShaderDebug 构造函数`，以及 `CloseShaderDebug → CopyForOutput`：旧实现预留两份约 50 MiB 的几何空间。这里是容量浪费，**不是每帧完整复制 100 MiB**；原有间接 copy 会按有效几何数量执行。
- `PreUpdateLighting → EnvMapBaker::PreUpdate → InitBuffers`：即使 host 的解析 shader 不消费烘焙环境，也创建了大型 cube 与辅助图。
- `CreateRenderPasses → LightsBaker::CreateRenderPasses`：按完整通用光照预算创建采样、scratch、历史与反馈资源；默认解析路线没有使用它们。

实现保存在 [按需资源补丁](../../tools/patches/rtxpt-demand-resources.patch)、[小型绑定资源](../../renderer-rtxpt/src/main/cpp/rtxpt_inactive_lighting.h) 和 [原生宿主](../../renderer-rtxpt/src/main/cpp/rtxpt_jni.cpp)。补丁由构建脚本在固定版本的宿主适配后应用，并用反向校验保证重复配置幂等。

资源选择跟随实际 shader 路线：方向分层、SHaRC、ReSTIR 等需要通用光照时仍保留 baker。切换 baker 或调试几何状态使用已有的 render-pass 重建与 GPU 等待边界，没有在稳定帧额外加入 idle。SHaRC 缓冲和 bindless descriptor 一同保留，直到 graphics 与 compute event 都完成。新分配缓存即使暂停训练也会先清空。PT pipeline 更新在提交本帧前等待编译和 PSO 创建完成，不会用新无效描述符运行旧 SHaRC shader。

调试 shader 使用 UAV 的真实大小限制几何写入；未知几何生产者在 compact 模式下只累计请求，CPU 读回后恢复完整容量。printf、反馈和调试入口继续保留。`allocatedCacheEntries()` 只报告真实已分配容量，关闭时为 0；请求容量仍可从 `quality().cache().entries()` 获取。

## Java 堆和剩余资源

独立 90 秒基线采样使用 JFR、GC 日志、NMT 和三次外部 VMMap，没有注入 Frida。Java 堆提交为 34 MiB，抽样使用量在约 11–27 MiB 内；80 秒时使用 22469 KiB，NMT 总提交 175480 KiB。没有发现 Java 堆持续膨胀能解释数 GiB 提交的证据。NMT 不覆盖全部 D3D12/驱动分配，不能用它否认原生资源成本。

修复后仍保留约 373.06 MiB 的 Streamline 资源、119.56 MiB 的 DenoisingLayersBuffer、63.75 MiB 的 SurfaceData，以及输出/历史图、加速结构等。这些属于实际执行路线；本轮没有通过减少分辨率、光学层数、采样数或关闭功能来压低它们。**本轮证明了约 0.8 GiB 可避免的浪费，不声称剩余每一字节都已达到理论最小。**

## 无损与生命周期检查

- 修复前、修复后原生覆盖版、正式包候选均退出 0。七张图覆盖 GLASS / OPAQUE_REFLECTIVE / ORIGINAL_METAL 的静止和运动，以及 720p resize。两组对照共 14 次图像比较，最大 RGB 通道差与平均差均为 0；PNG SHA-256 也完全一致。
- 正式 JAR 在全新、仓库外目录运行 17 项预算矩阵，覆盖 SHaRC 三档及自定义容量、六档精确界面、采样器、方向分层、点光预算、后处理和资产配额。禁止动态编译，361 个 shader 二进制没有新增或改写。
- 八组真实高级组合通过：RR、SR、原生输入、DLAA、ReBLUR/RELAX、SHaRC、ReSTIR DI/GI，另含 2×/3×/4× FG 运行时状态切换和同 JVM 顺序重开。每组实际捕获非平坦画面，关闭后追踪资源归零。
- 原生生命周期 fixture 验证 `SHaRC off/on/off/on/off`，第二次启用时训练冻结；debug 验证 `compact/full/compact`。检查真实缓冲分配与释放，进程结束后资源清单归零。
- 原生诊断关闭/开启两种 CPU 自检、Streamline 函数缓存生命周期自检通过。公共 `renderer-api` 的 7.0.1 ABI 与 7.0.0 逐字节一致，SHA-256 为 `d511a1192b76ad2c618c8d7591b3dc26770cdc6001704655f2a38371b693cec7`。
- 完整配置本机 SDK 后，API/Core/RTXPT/Demo 的 CPU 与发行包检查通过：151 个任务，128 个实际执行，进程树峰值提交 3.688 GB。两个 Demo JAR 均带完整 NVIDIA 运行时；RTXPT 内嵌 DLL 与画面对照时的 DLL 相同。

FG 的开启状态与倍率配置检查通过，但**本轮不验收实际生成帧交付**。短检查发现后台窗口只交付原生帧；外部 Win32 与原生 trace 均记录 `foreground=0`。用户确认前台另有任务，这是预期行为，并要求停止 FG 验证、不以此阻塞。保留日志，不把后台结果标为功能 bug，也不写成实际 2×/3×/4× 交付已经通过。维护的 `rtxptGenerationDeliverySelfTest` 是需前台窗口的显式检查，不参与 `check`。

本轮基线 GPU 利用率为 31–48%，均超过用户规定的 10% 门槛，**不引用绝对 FPS，也不把运行耗时或冷编译时间当性能对照**。上述画面、功能和生命周期已验证；吞吐量无损尚未在空闲 GPU 条件下实测。

## 诊断默认关闭

仅显式设置进程环境变量 `RTR_RTXPT_MEMORY_DIAGNOSTICS=1` 才启用资源账本、调用栈、进程与 DXGI 采样；`RTR_RTXPT_MEMORY_LOG` 可选择输出文件。关闭时不打开日志、不登记资源、不采集栈、不查询 GPU。用法见[工具说明](../../tools/README.md)。VMMap、JFR 与观察脚本都不随生产进程自动启动。

制品哈希、逐图 SHA-256 和统计原值记录在同页链接的数据文件中。7.0.0 原始 JAR 与冻结诊断 DLL 保留，未用新版本覆盖旧证据。

## 合入通用灯光功能后的最终包

最终 7.0.1 Demo 已重新打包，内部 bridge SHA-256 为 `0b8e7084f7ad5ddb74ea4fe671b341b056d24eeaf11d81ed11dee23145d5039a`，与本次原生构建一致。原有内存修复证据保持原样；最终包数据另存于 JSON 的 `final_combined_candidate`，原始文件位于 `build/reports/hexball-memory-20261102/parity-combined-packaged/`。

| 同一 GLASS-static 阶段 | 7.0.0 | 最终 7.0.1 | 变化 |
| --- | ---: | ---: | ---: |
| 私有提交 | 2712.367 MiB | 1896.316 MiB | −816.051 MiB |
| RAM 工作集 | 826.068 MiB | 843.973 MiB | +17.904 MiB |
| 独立显存 | 1903.723 MiB | 1086.094 MiB | −817.629 MiB |
| 共享 GPU 内存 | 174.980 MiB | 174.430 MiB | −0.551 MiB |
| 对齐资源账本 | 1640.3125 MiB | 822.2500 MiB | −818.0625 MiB |

最终包取 10 个同阶段样本，仍确认约 0.8 GiB 的提交和显存节省，不能称物理 RAM 节省。160 帧与关闭均正常，退出 0，资源账本在关闭后归零。初始 GPU 占用 36%，不作 FPS 对比；未继续 FG 交付验收。

最终七张对照中六张逐像素一致。`ORIGINAL_METAL-moving.png` 的平均 RGB 通道差为 0.035856/255，最大通道差为 42，180824 个像素至少一个通道不同（8.7203%）；其余六张差值为 0。该差异根因尚未独立定位，**不能将最终合并包写成七张全部逐像素无损**。前文内存修复独立阶段的 14 次完全一致仍成立，但不替代最终合并包的结果。本次不追加 Demo 重跑。

最终原生构建、诊断启用／关闭 CPU 自检和 Streamline 缓存自检通过。361 个 shader 覆盖六档预算并通过源码／二进制校验；最终 JAR 的 17 项预算矩阵在全新目录中通过，禁止动态编译且二进制清单保持 361 个，每次关闭均释放资源。完整 API/Core/RTXPT/Demo 检查及版本／合同门禁通过：149 个任务，130 个执行，进程树峰值提交 3.741 GB；复用已完整构建且源码未变的 NVIDIA 原生依赖，保留全部运行时打包检查。

对应日志为 `build/lighting-native-final-artifact.log`、`build/lighting-shaders-final.log`、`build/reports/hexball-memory-20261102/package-combined-701.log` 和 `combined-packaged-budgets.log`。完整灯光功能的字段、组合和退出结果见[独立验收](rtxpt-incremental-lighting.md)。
