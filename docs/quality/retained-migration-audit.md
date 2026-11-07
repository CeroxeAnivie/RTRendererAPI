# 7.x → 8.x 完整迁移对照与本批修复

本批在 main 原工作区完成，不切分支、不建 worktree。NVIDIA/Donut/NVRHI 的官方源码、shader 和默认算法均不修改。唯一历史进度窗例外没有扩大。

## 已核对范围

固定基线 eb38882ce6341f5cc0327ceff92124dfd71982a9；7.x 保全点 fe0e0af0bb84db571c6f95b9ba02a5bc9185dafb。42 个提交，其中 24 个触及生产代码/补丁；21 份旧内部补丁全部列入索引。当前 API/RTXPT/原生示例旧路径中，101 个文件逐字保留、32 个改变、258 个删除；删除项包含旧 Vulkan/interop 路线，不能按文件数当功能回归。

完整逐提交/逐文件/旧补丁 hunk 位置见 data/retained-migration-audit.json。原行为账仍以 historical-fix-migration.md 的 A01–A37 和 visual-migration-audit.md 的 V01–V15 为索引。文件相同证明源码保留，不代表最终运行已通过；被保留但未调用的旧诊断代码尤其不能算迁移完成。本轮 53 个恢复候选的逐文件采用/替代/缺口、全部 21 个旧内部补丁的处置，以及原 42 提交索引集中见 [完整适配决策](data/bridge-adaptation-decisions.json)。

## 代码级结论和集中修改

| 类别 | 旧正确行为 | 8.x 真实缺口 | 本批外部实现 |
| --- | --- | --- | --- |
| 增量几何 | 只构建变化网格 | 每个拓扑包先重导入全部材质，再触发 Sample 全场 ReleaseBlases/CreateAccelStructs | Resources 保存真实 delta；材质集合不变跳过 ReimportMaterials；RefreshRetainedDirectStructures 仅创建没有 AS 的网格，调用原 bvh::GetMeshBlasDesc、NVRHI BuildBottomLevelAccelStruct 和原 CreateTlas |
| 上传等待 | 不为每个局部更新等待全 GPU idle | 初始候选仍在每次结构变化 waitForIdle；原 load-time BlasCompactor 每批另有两个 GPU 往返 | 流式局部构建使用现成的普通 NVRHI 建结构路径；原版 load-time 路径保留。退休旧 MeshInfo/BufferGroup 以 graphics/compute 两个完成事件保护；64 批压力保护才等待。不自写 AS 池或分配器 |
| 属性更新 | normal/UV-only 不更新 AS | 任何 mesh upsert 新建网格和 BLAS | 精确比较存活位置、索引、几何分组与属性布局。仅属性变化保留同一 MeshInfo/BLAS；外部 ManagedScene 子类调用原 CreateMeshBuffers/UpdateGeometry/WriteGeometryBuffer；不向永久 read 状态 VB 非法 CopyDest，不自写上传器 |
| 材质无变化 | 不重复使缓存失效 | 等价材质对象重建也按变化处理 | Decode/引用校验后按完整输入值精确判等；不使用近似容差或仅 hash 判等 |
| 仿射合同 | 正确转置、非均匀缩放、前后帧姿态 | 原 TRS 分解近半圈数值失败；通用 AffineTransform 的剪切被拒绝 | AffinePose 优先原 Donut 分解，SDK 稳定四元数转换；单 TRS 不够时有界 3×3 分解为两个原 SceneGraphNode 的 TRS 组合。严格重建校验仍为 1e-5；不改原节点字段、不烘焙每帧网格、不丢剪切 |
| 错误关闭 | 原失败不重复阻断资源关闭 | owner 失败后 retirement 查询重抛原异常，抑制异常再污染 close | Session::Stopped 在已停 owner 上检查真实 lease/fence；正常 owner 仍按原 owner 查询，关闭不绕过外部读者 |
| 诊断 | 独立游标、忙帧可读、有界数值传输、OFF 停采 | 8.x 只留 owner 上 Java 快照；native ring 虽保留却未接线；PacketPool JFR 未接总开关 | Java primitive ring 带发布戳与并发 reader；独立 Monitor；nativeDiagnosticMonitor 复用原 v7 数值环，只记录桥接 Resources/Import/SceneRefresh/BLAS/TLAS/Materials/Retirement 和 RTXPT 调用边界。OFF 同时停 Java、native scope 和 packet JFR；不注入 RTXPT 内部 |

## 逐类保留与不恢复项

- V01/V06/V09/V11–V15：原生灯字段适配、独立 direct gain/可选 GI、scalar/texture/vertex alpha、深度法线 identity 重建、中心 BOX/SPHERE、阴影终点、精确投影、RGB host-response 及 lookup 尾部初始化代码保留；本批不改光照公式/采样质量/输出编码。RtrNativeLighting 相对 7.x 的变化仍是外部 sampler metadata 和 masked scalar 适配。
- 7.3.0–7.3.2：增量 PacketPool/Delta/PersistentMap、application patch 限额与重试、稀疏灯树与区域事务、脏队列与局部 frontier 原代码保留。7.3.3 连续灯首次发布的引导逻辑保留。未把旧已撤回 frontier/并行分拆/提前 BLAS 提交候选重新加入。
- 21 个旧内部补丁：retained/dynamic/local/high-framerate/frame-preparation 的通用资源适配改在外部，资源执行沿原 Scene/NVRHI；metadata-delta 使用 API 自有 journal。旧 tlas-capacity、scene-tables 的原生容量/范围写入，以及 light-registry/topology-journal 的原图登记加速尚不等价，具体见下表，不写成已全部迁移。rtxmu-prepared/native-managed 内部 allocator 补丁不恢复。incremental/sparse 的 API 灯状态与外部 pass 保留，原版 beauty 不改积分器。growth/staging/upload-statistics 内部探针退役，外部边界诊断只替代可观测边界，不冒充原内部细分。masked-alpha 由外部 direct metadata 提供。host-surface 和 transparent-background 只有已证实的外部能力保留。
- 旧自写 AS 分配器、clone/refit/周期重建策略、上传 arena 不直接复活；本批从公共原生入口消除错误全局工作，不把旧微基准数值转签新实现。
- 已人工通过的弹球/介质默认配置、HUD 和解析视觉模式保持；没有用提升 SPP、降分辨率或增加 FG 倍率冒充恢复。

## 仍须明确的边界

不能把“全部审计”写成“全部行为等价”：原 beauty 逐 hit/lobe/source 过滤、独立 primary 背景/透明 coverage、旧 pre-SR history 等仍没有已证实的等价接入口；旧 packed emission 使用原版精度。它们在原总账中保持未等价，不偷偷补进 NVIDIA 本体。本批统一验证前不能宣称 MC 视觉、180 FPS 或全部旧功能已经恢复。

## 恢复候选的实际接通与清理

2026-11-07 本轮源级核对：53 个候选中，24 个保留在实际外部执行链，29 个仅恢复而不可达的旧 glue 已移除，采用决策无待定项。删除前逐项限定为本轮恢复清单中的 untracked 文件，并从 rtxpt_upstream_jni.cpp、宿主 EXE 及 AdapterShaders.cfg 的本地 include 可达图确认不是活跃输入；没有删除已纳入 Git 的生产文件，也没有删除新接通的 rtxpt_scene_delta.h。每个删除项均记录旧 blob、现行替代或真实缺口，不把删除数算迁移收益。

- 保留的主要执行链：独立 direct/GI/reconstruction/encoding shader，receiver 属性与范围管理、API 自有 scene journal、retained light tree、sparse regions 和局部上传。sampler/scalar 放在外部 geometry metadata；RR 使用公开 StreamlineInterface。
- 旧 HostPathTracer retained_scene/lighting/memory glue 由现行 Resources、Lights、ManagedScene、原生 AS 调用和 signal ring 分担；不把旧内部字段、私有 frame/token 调用、重复池或分配器重新接入。
- 旧 HostSurface/AOV/PreSR/核心光路探针、SDR/Vulkan 共享路线、未对外暴露的旧扩展 loader 随其旧执行路线移除。对应未等价能力仍列缺口，不以现行不同能力冒充恢复。

| 旧正确策略 | 现行 8.x 位置/状态 | 仍有的差异与处理门槛 |
| --- | --- | --- |
| 纹理 no-op、generation/rectangle、引用材质增量 | Resources::Apply 精确判等；公开 PTTexture 重绑定；旧纹理双队列 fence 退休 | 本轮修改待新混合负载验证，不能沿用旧无动态纹理负载的收益 |
| receiver metadata delta | rtxpt_scene_delta.h + NativeLightingPass::CollectSceneDelta/UploadChangedAttributes | journal 在 API；原图 dense reindex 仍需比较，不能称零扫描 |
| 原 scene 表范围写入 | 原 Scene 仍拥有并维护 scene tables | 旧 scene-tables.patch 修改原 Scene 上传逻辑；外部 receiver delta 不等于原 scene 表增量。原成本尚在 |
| 原图 O(1) registry/重排 journal | 原 SceneGraph + API 稳定身份/反向依赖 | 旧 light-registry/topology-journal 内部改动不恢复；原图扫描/登记成本不能宣称已消失 |
| TLAS/instance/table 几何扩容 | 原 Sample::CreateTlas 与 EnsureTLASInstanceBuffer | 保持纯上游 exact-size，不宣称旧容量策略等价；禁止假实例 padding。无调用的旧 rtxpt_capacity.h 已清理 |
| 真材质 definition 的局部更新 | 原 MaterialsBaker/完整 reimport；纹理单独重绑定 | 纹理优化不自动覆盖新 shader-feature/domain 变化；原局部 HostSyncMaterials hook 不恢复 |
| 双队列退休和共享 lease | 原 NVRHI event + 外部 resource retirement；RtrSignalFrameRing | 新旧 mesh/buffer/texture 及 command owner 必须真实 fence 保护，不每包 waitForIdle 掩盖问题 |
| SR/RR 模块关闭保活 | Session::Run 中采用 rtxpt_shutdown.h 的公开 feature callback/Win32 模块引用 | optional guard 在 Application 前声明、成功 Init 后创建，跨原 Application 析构保活到原 DeviceManager::Shutdown/slShutdown；不改原关闭顺序。运行验证单独记 |
| 原 allocator budget/growth/staging/光路细分诊断 | 外部边界事件/公共诊断，原内部探针退役 | 不伪造 native allocation census、内层 staging 时延或光路记录；上游无入口就明说 |

上述是源级决策账，不是新构建或运行验收结果。集中验证必须基于当前候选 identity；上游纯度、编译和 fresh lease 只能分别证明各自检查，不能代签视觉或移动 FPS。

### 仍交给上游的具体入口

| 工作 | 原生所有者/入口 | 桥接侧保留的最小职责 |
| --- | --- | --- |
| mesh/scene buffer 创建与上传 | Donut Scene::CreateMeshBuffers、UpdateGeometry、WriteGeometryBuffer、RefreshBuffers | 稳定外部身份、变更分类及调用；不复制上传器 |
| texture 与材质 GPU 行 | TextureCache::LoadTextureFromMemory/UnloadTexture、PTTexture::InitFromLoadedTexture、原 MaterialsBaker/GPUDataDirty | generation/rectangle 合同、真实差异判断、引用重绑和旧资源安全退休 |
| BLAS/TLAS | bvh::GetMeshBlasDesc、NVRHI BuildBottomLevelAccelStruct、Sample::CreateTlas/UpdateTLAS/EnsureTLASInstanceBuffer | 仅提交新增/变化 mesh、补真实 graphics→compute 依赖；不维护第二套 AS 池 |
| dense ID 与 scene tables | 原 SceneGraph::Refresh/registry、Scene::RefreshBuffers/WriteInstanceBuffer | 外部名字与 receiver metadata journal；不注入原图字段 |
| GPU 排队与回收 | NVRHI executeCommandList、queueWaitForCommandList、event query | 外部事务/输出 lease 所需双队列 owner 保留；不自建原生 scratch/uploader |
| 材质/输运/重建 | 原 RTXPT scene/TLAS/surface/BSDF helper、公开 StreamlineInterface | API 显式外部 direct/analytic pass 和公开 RR 参数/标签，不改原积分器 |
| 窗口/逐帧/关闭 | 原 Application::Init/End、DeviceManager::Shutdown/slShutdown | owner 请求、公开 beforeFrame 回调、插件模块引用保活 |

这些入口保留了上游自身的维护成本：原图登记/重排、原 scene 表上传粒度、exact-size TLAS 分配和真材质定义完整导入仍可能出现。7.x 修改这些内部行为得到的历史性能不能直接继承；新外部增量路径的整链收益只看当前候选混合负载。不能说“旧热点全部不存在”。

## 本轮集中合同与新根因

本轮 40 帧集中合同已通过。成功原始日志为 build/reports/bridge-migration-contract/native-contract-success.log，逐 case 记录为同目录 cases.csv；此前失败保留在 coverage-failure-raw.log，不删失败样本或放宽原 BLAS/VB 身份断言。

实际抓到的新根因是 coverage 被误当 topology：RtxptDelta.alphaEquivalent 正确把 sampler 变化识别为 coverage 变化，但 Resources::Apply 原先把 coverage 无条件升级成拓扑变化，触发全场 BLAS 重建。失败在 sequence=24 的 texture-only 原 BLAS 身份断言暴露。现在分开记录原始 coverageChanged 与 requiresCoverageRebuild：仅在已启外部逐 hit 纹理采样、且没有 OMM 的 direct 链路，sampler/像素 coverage 不制造几何变化；原 beauty/OMM 仍保留原来的保守重建生命周期。没有改上游或降低 opacity 正确性。

通过项覆盖：完整/no-op texture generation、rectangle 真实变化与 no-op 新 parent、sampler/scalar 实际像素、纹理与 normal/UV/拓扑混合、删除/重插及 metadata dense reindex、工作字节、持有输出 lease、两个独立 native monitor 的等价事件且 lost=0、运行时 OFF 停采。cases.csv 中 sequence=37–40 的 OFF no-op 均为 native_events=0；fresh 身份之外还有原 BLAS/VB 和像素/工作量断言，不能把该结果说成仅“能出帧”。

最终本批 DLL 重建 wall 13.266s，Job 进程树提交峰 0.747GB；集中合同 wall 8.353s，提交峰 3.582GB，exit=0。上游 4426 原文件零差异检查通过。提交峰不是任务管理器内存。合同正确性与吞吐分别验收：新的 fast 重场景首轮已完成，结果见下节；不能沿用旧无纹理负载代签新候选。

### 最终桥接重复工作收口：分窗验证完成、移动目标未达

本轮在自有桥接层再消除三处重复工作：`ValidateReferences` 保留全部材质 texture-ID 校验和新 mesh 校验，resident mesh tangent 遍历仅在 normal 从未启用变为启用时执行；纹理 identity、像素、sampler、scalar 变化不会新增顶点要求。纯纹理事务删除额外 `SceneFullRefresh`，由未改动的原版 `Sample.cpp:2923` 本帧 `Refresh` 消费。Decode 的 exact no-op full/rectangle 不再整图复制与重复比较；Java `RtxptDelta` 对完整相同图像用 `ByteBuffer.equals` 短路 alpha 扫描。`ReferenceWork` 两项与七项 texture-work 合为九项实际工作 counter。

这不是恢复历史失败的选择性 SDK 拓扑准备、frontier、shader 分拆或跨队列方案。未修改上游渲染/采样/降噪算法、默认策略或质量，不省略真实变更及生命周期验证。统一构建的上游纯度为 20 仓库、4426 原文件、Unexpected=0；CPU high-framerate 检查通过。最终集中合同日志 `build/reports/bridge-migration-contract-final/stdout.log` 明确 `BRIDGE_MIGRATION PASS frames=40`，保留 GPU 像素、mixed 属性/拓扑、lease、OFF、BLAS/VB 身份断言，并新增 resident tangent visits=0/skipped>0、texture-only `SceneFullRefresh=0`。本次合同 wall 18.117s、Job 进程树提交峰 3.485GB、exit=0；提交峰不是任务管理器内存。

原八 case 长窗旧新计划已调整：`baseline-medium-OFF` 在 185.0100174s 后因反复全场景材质导入/AS 构建、预计超过 600s 单 case 限制而主动停止，见 `build/reports/bridge-heavy-final/baseline-medium-OFF/cancelled.json`。旧包长窗不是 PASS 或真实崩溃，不从部分日志推算 FPS。完整新包 `build/reports/bridge-heavy-current-final` 的 medium/fast × OFF/DEEP 四 case 均 PASS，各 60 tick、540 移动帧、30 warmup、90 静止帧、661/661 fresh；medium/fast 分别 183/610 mesh 入出，覆盖真正区域流入。同 bytecode 的 fast 2 tick、18 移动帧短窗旧/新均 PASS，但只测 texture/pose/source churn、不跨 tile，不能替代长窗。fast20 前后同输入语义观测也已完成，不宣称相同 class。

三组详细表及原始精度见[通用流式结果](streaming-scene-results.md)与 data/streaming-scene-results.json，各窗口独立计算，缺项不补零。完整 fast 模拟率 OFF/DEEP 为 97.913697/93.845884，pipeline 为 110.362964/106.181967，均未达 180；fast20 移动 pipeline 79.982056→106.263426（+32.86%），静止 188.613646→128.447422（−31.90%），退化保留且不推测原因。OFF→DEEP fast 完整模拟观测 −4.15%，不是严格诊断成本因果。`Frame` 是调用线程等待壁钟，self 也不是 exclusive CPU/GPU；GetThreadTimes 粒度约15.625ms，0不代表精确零成本。当前验证已完成，源码161619d0先推GitHub后覆盖Maven Local，两模块18项身份检查通过；消费方验收仍待进行。

### 修复前 fast 重场景首轮（历史）

原始结果为 build/reports/bridge-heavy-fast/current-fast-DEEP/result.json，外层环境记录为同目录 environment.json。初始 GPU 0%，诊断 DEEP；3662 个独立网格、3669 个实例、3294912 个独立网格三角形、45000 个稀疏源。请求 2560×1600、direct 1280×800，GI=0、reconstruction=NONE。fast 输入为 20 个场景 tick、每 tick 9 帧，逻辑速度 48 单位/秒；180 个移动帧实际位移 47.9999025225，转向 72°。这不是此前 709 单位路线，也不是同负载旧/新对照。

| 当前候选指标 | 静止 | 高速移动 |
| --- | ---: | ---: |
| 样本/验证 fresh 帧 | 30/30 | 180/180 |
| pipeline 帧/秒 | 188.6136 | 79.9821 |
| apply→fresh 平均 ms | 5.3018 | 12.5028 |
| apply→fresh P95 ms | 11.9516 | 16.7386 |
| apply→fresh P99 ms | 12.1997 | 93.6557 |
| submit 平均 ms | 4.9432 | 11.3030 |
| submit P95 ms | 11.5665 | 12.1963 |
| submit P99 ms | 11.7040 | 85.7508 |

全部 219/219 帧验证 fresh；诊断已采样窗口 Java/native lost=0、failed=0，native monitor 在首次 fresh 之后创建。初始上传单列为 13595.1418ms，不并入稳态。高速窗口实际发生 90 次 rectangle 更新、85 次完整 no-op generation、5 次 sampler-only 变化；native 工作计数记录 rectangle 上传 92160B、GPU copy 377487360B，不能把上传字节减少写成整体 GPU 工作已消失。

本轮运行和合同通过，性能目标未通过：高速 79.9821 < 180 pipeline 帧/秒。当前只有单次新候选结果，无同条件旧控制值，不给收益百分比；pipeline 吞吐不是消费方 FPS，壁钟区间也不是纯 GPU 忙时。原生 Frame inclusive 平均/P95/P99 为 9.0178/10.2491/78.3700ms；ResourcePlan inclusive 为 5.9059/7.1077/58.8785ms，内含 Resources/SceneFullRefresh 等子区间，不能与父区间或 Java RTXPT_FRAME 相加。统计摘要见 data/bridge-heavy-fast-summary.json。

53 个候选仍为 24 个实际活跃、29 个不可达旧 glue 清理；正确性合同和本次采集没有把旧内部优化改判为等价恢复。原图 registry/reindex、原 scene 表上传粒度、exact-size TLAS 与真材质 definition 完整导入等差异继续保留。

### 正式交付的预编译守卫与剩余步骤

以下门禁判断来自源码与既有分发证据审查；本轮候选已经统一构建并完成上述合同与分窗负载，最终制品已通过398项包检查，正式发布与Local身份尚待回执。rtxpt_precompiled_runtime.h 在安装前要求进程禁止创建子进程，逐一核对 source catalogue 与 DXIL blob 的 SHA-256，再按实际安装路径生成原版缓存别名；未知全局宏签名明确拒绝。该安装步骤只复制已构建二进制，不进行 shader/native 编译；这项静态检查本身不能证明所有运行路径都无后编译。未知组合不允许回退编译，公开材质排列约束和具体已收录配置仍以 docs/quality/precompiled-runtime.md 与构建命令清单为准，不能外推为任意配置都受支持。

既有新目录/材质切换/损坏输入证据不等于当前最终制品已交付。最终打包已有398项检查通过，150个预编译条目、236个source条目及受测DLL／独立ZIP／JAR内ZIP身份一致，热缓存未入包，回执见 precompiled-runtime.md。当前已按顺序完成正式发布：保持 8.0.0，全量源码、工具和文档161619d0先推GitHub，确认后覆盖Maven Local；两模块18项身份检查通过，见data/bridge-publication.json。消费方仍须重验受影响画面、同条件整体性能及无运行时后编译。未收录配置须离线构建发布，不能用本机热缓存代替可分发制品。

### 新增 Scope 的 OFF 静态审查

RtrDiagnostics::Scope 构造函数在 diagnostics.h:335 先做 level 的 acquire load；OFF 且 minimum≥1 时直接返回，未构造 DiagnosticLock，也不调用时钟、线程 CPU 计时、事件登记或文件输出。析构先检查 enabled 并返回。新增 bridge scope 参数都是数字、枚举和容器 size，未为诊断生成字符串、做分配或遍历；因此稳态 OFF 是固定局部初始化、一次开关读取与返回的常数路径，不能称绝对零开销。metadata 名字拼接是操作所需的场景映射，不是诊断特有开销。

从开启切到 OFF 的 Configure 会有一次受 mutex 保护的收尾，清理已开启 scope，并推进 epoch；这是已有采集的结束路径，不是之后每帧 scope 的开销。原生 40 帧测试证明 OFF 后没有新事件；这里关于无字符串/分配/锁的结论来自实际调用点与构造/析构源码静态检查，没有另跑系统调用跟踪或诊断开销微基准。

## 既有验证（本轮前）

2026-11-07：原生构建通过；20 个官方仓库、4426 份文件零差异校验通过，唯一历史进度窗例外未扩大。CPU 诊断并发合同通过。开启官方 D3D12 debug 层的 48 帧合同通过：近半圈、有符号非均匀缩放、剪切/恢复 TRS、前后帧姿态、属性更新保留 BLAS 并替换 VB、局部拓扑、native 边界事件、失败 owner 清理。该 48 帧结果对应输出环修复前的构建，后续仅输出环变化由下面 69 帧另行验证，不冒称重复运行。

集中验证抓到并修复：

- D3D12 id 921 直接指出 DXR Scratch Buffer 0 在 GPU 执行期间 final-release。新局部构建的命令列表原为栈内临时 owner；现与旧 MeshInfo/BufferGroup 一样由真实 graphics/compute fence 保护退休，不再用每次 waitForIdle 掩盖生命周期错误。
- 桥接上传、局部 BLAS 构建和共享输出拷贝发生在原 Sample 记录的 graphics fence 之外；显式通过 NVRHI queueWaitForCommandList 为原 compute prefix 补齐依赖，不做 CPU busy-wait。
- Java 仍拒绝剪切，导致原生两节点适配无法执行；公共 AffineTransform 原有有限值/可逆性检查保留，删除过时的单 TRS 限制。
- 提速后暴露输出环错误背压：未导入且已归还的 lease，其 completion 已排在同一 graphics queue。下一次写入可按 FIFO 复用，无需 CPU 等 fence；真实 imported reader 仍必须归还并完成其 GPU fence，不绕过外部消费者。
- 系统属性 top.ceroxe.rt.rtxpt.validation=true 只接原版 --debug；D3D12 InfoQueue1 公开回调只在显式验证时注册并在销毁时注销，不注入上游内部。

### 同条件流式负载

512 网格、519 实例、459912 个独立三角形、1024 稀疏光源、9000 次光源 delta、60 个移动样本、8 个静止样本；2560×1600 请求、1280×800 direct gain、GI=0、reconstruction=NONE、DEEP。旧/新初始 GPU 为 2%/0%；69/69 fresh、诊断 lost=0，当前完整测试 PASS。单次前后对照，未做交错 ABBA；这里的 pipeline throughput 不是宿主 FPS，也不是 GPU busy 时间。

| 指标 | 旧值 | 新值 | 绝对差 | 百分比 |
| --- | ---: | ---: | ---: | ---: |
| 静止 pipeline 帧/秒，8 样本 | 558.36 | 519.15 | -39.21 | -7.02% |
| 移动 pipeline 帧/秒，60 样本 | 4.5214 | 30.5002 | +25.9788 | +574.58% |
| 移动 apply→fresh 平均 ms | 221.1720 | 32.7867 | -188.3853 | -85.18% |
| 移动 apply→fresh P95 ms | 302.5894 | 61.4477 | -241.1417 | -79.69% |
| 移动 submit 平均 ms | 220.2370 | 28.6697 | -191.5673 | -86.98% |
| 移动 submit P95 ms | 301.3532 | 54.7070 | -246.6462 | -81.85% |
| RTXPT_FRAME 平均 ms | 218.4997 | 26.9386 | -191.5611 | -87.67% |
| RTXPT_FRAME P95 ms | 299.5090 | 51.9891 | -247.5199 | -82.64% |
| 首场上传 ms | 7304.0227 | 7368.7736 | +64.7509 | +0.89% |
| TRANSLATION 平均 ms | 0.4974 | 0.5124 | +0.0150 | +3.02% |
| RESOURCES P95 ms | 0.7855 | 0.7946 | +0.0091 | +1.16% |

全部阶段平均/P95 及真实失败历史见 data/retained-migration-performance.json。本批联合修复消除错误的全局重导入、重建和等待；没有单独消融测试，不能把总收益分配给其中任意单项。移动仍未达 180 帧/秒。Job 提交峰 4.562 GB 不等于任务管理器内存。

旧控制输出已完成 69 个 fresh，但最终断言错误地把 60 条弦长之和与连续圆弧 709 比较。当前只把最终 oracle 改为 2R·N·sin(θ/2N)=708.987041646，容差仍 .01；轨迹、工作量、计时代码不变，旧原始测量不改写。第一场快速流式测试因输出环报满而失败，只完成 1 个移动样本，未用于收益表。

原始日志与 JSON 位于 build/reports/migration-audit；旧控制位于 build/reports/precompiled-runtime/retained-control。源码发布与正式依赖验证另记实际结果，未完成前不能代签宿主画面和快飞 FPS。
