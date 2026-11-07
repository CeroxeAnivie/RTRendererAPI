# 7.x → 8.x 完整迁移对照与本批修复

本批在 main 原工作区完成，不切分支、不建 worktree、不派子代理。NVIDIA/Donut/NVRHI 的官方源码、shader 和默认算法均不修改。唯一历史进度窗例外没有扩大。

## 已核对范围

固定基线 eb38882ce6341f5cc0327ceff92124dfd71982a9；7.x 保全点 fe0e0af0bb84db571c6f95b9ba02a5bc9185dafb。42 个提交，其中 24 个触及生产代码/补丁；21 份旧内部补丁全部列入索引。当前 API/RTXPT/原生示例旧路径中，101 个文件逐字保留、32 个改变、258 个删除；删除项包含旧 Vulkan/interop 路线，不能按文件数当功能回归。

完整逐提交/逐文件/旧补丁 hunk 位置见 data/retained-migration-audit.json。原行为账仍以 historical-fix-migration.md 的 A01–A37 和 visual-migration-audit.md 的 V01–V15 为索引。文件相同证明源码保留，不代表最终运行已通过；被保留但未调用的旧诊断代码尤其不能算迁移完成。

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
- 21 个旧内部补丁：retained/dynamic/local/high-framerate/frame-preparation/tlas-capacity/topology-journal/metadata-delta/scene-tables 的事实输入和必要适配改在外部；资源执行沿原 Scene/NVRHI，不恢复原文件补丁。rtxmu-prepared/native-managed 内部 allocator 补丁不恢复。light-registry/incremental/sparse 的 API 灯状态与外部 pass 保留，原版 beauty 不改积分器。growth/staging/upload-statistics 内部探针退役，外部边界诊断替代。masked-alpha 由外部 direct metadata 提供。host-surface 和 transparent-background 只有已证实的外部能力保留。
- 旧自写 AS 分配器、clone/refit/周期重建策略、上传 arena 不直接复活；本批从公共原生入口消除错误全局工作，不把旧微基准数值转签新实现。
- 已人工通过的弹球/介质默认配置、HUD 和解析视觉模式保持；没有用提升 SPP、降分辨率或增加 FG 倍率冒充恢复。

## 仍须明确的边界

不能把“全部审计”写成“全部行为等价”：原 beauty 逐 hit/lobe/source 过滤、独立 primary 背景/透明 coverage、旧 pre-SR history 等仍没有已证实的等价接入口；旧 packed emission 使用原版精度。它们在原总账中保持未等价，不偷偷补进 NVIDIA 本体。本批统一验证前不能宣称 MC 视觉、180 FPS 或全部旧功能已经恢复。

## 验证状态

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
