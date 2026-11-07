# 历史修复与 8.0.0 实现对照

2026-11-07。范围是 API 仓库，不包含宿主工程改动。历史索引覆盖截至 405ed3e 的全部 661 个可达提交，其中 146 个触及 renderer-rtxpt。提交中也有试验、撤回、发布及文档，不能把提交数当成功修复数。完整标题、路径、身份见 [全部提交索引](data/historical-fix-index.json)，下方逐提交导航覆盖全部 146 项。

本表区分代码去向与实际验证。外置、原生替代、退役、未证明等价分别记录；旧版跑分不能转签当前 DLL。旧实现可由索引中的提交恢复查看，合并保全不表示旧实现仍在执行。

## 功能、视觉、性能及诊断

当前源码路径统一以 renderer-rtxpt/src/main 为前缀，示例以 examples/native-scenes/src/main/java 为前缀。

| 组 | 旧修复／新增能力 | 8.0.0 实现及位置 | 当前证据／限制 |
| --- | --- | --- | --- |
| A01 | Java 宿主、JNI、D3D12 Agility 跨盘路径、依赖加载顺序、Streamline 预载 | cpp/rtxpt_jvm_host.cpp、rtxpt_upstream_jni.cpp，真实 EXE 宿主与官方 DeviceManager | 当前构建及完整弹球启动通过；不保留旧伪造宿主或自有设备创建链 |
| A02 | SDK 关闭顺序、插件卸载、设备／窗口生命周期 | cpp/rtxpt_upstream_session.h，原 Application.End、单 owner 线程、准入及关闭取消 | 正常关闭及关闭 future 修复保留；原生致命错误仍可能终止进程 |
| A03 | ZIP 内原生运行时、签名插件、资源路径、校验和、并发提取 | java/.../RtxptRuntime.java、RtxptRuntimeBundle.java；build.gradle.kts | 标准分发保留；本轮另核包内 DLL/host/shader 与 Local 身份 |
| A04 | UTF-8 编译、PDB 隔离、构建内存限制、原生源身份 | tools/Build-RtxptBridge.ps1、Build-RtxptUpstream.ps1、Invoke-BoundedBuild.ps1 | 最终构建 20 仓／4426 原文件校验，10 GiB Job；唯一进度窗补丁单列 |
| A05 | 普通／高级 API 配置、能力反馈、输入合法性、显式 capture | RtxptRenderer、RtxptAccess、配置翻译、upstream_host.h | 不支持的请求明确拒绝；request、native mode、GPU 实测分开 |
| A06 | 增量 scene、稳定身份、局部更新、删除重建 | RtxptScene、RtxptDelta、RtxptPersistentMap；cpp/rtxpt_upstream_resources.h::Apply | 桥接保存 API 事实，资源执行交原 SceneGraph/SceneBuffer；不是原资源算法搬家 |
| A07 | 全场景 packet 生命周期、借用、arena 放大、解码与事务内存 | RtxptMemoryResources.PacketPool；upstream_resources.h::Decode | 同步 JNI 借用、明确释放、借用期间禁止重用、小块桶／大块对齐保留；无本轮移动 FPS 结论 |
| A08 | material/texture/mesh 引用校验、反向依赖、材质失效与变换更新 | upstream_resources.h::ValidateReferences、ConfigureMaterials、ApplyPendingTransforms | 通用适配保留；原版材质打包差异及动态 domain 未全量证明等价 |
| A09 | 大场景 BLAS/TLAS、资源池、批量上传、退休／压缩、容量管理 | 原 Donut/NVRHI/RTXMU 场景维护；桥接只提交场景变化 | 旧自有 AS 分配／批调度不再维护；7.x 微基准收益不能继承，流式负载整体仍待验证 |
| A10 | 增量灯、稳定 light journal、区域维护、稀疏上传 | cpp/rtxpt_upstream_lights.h::Apply/Upload/Update；RtxptLightMap | retained records/tree 与变化上传在 API；输运调用原生或显式外部模式 |
| A11 | point/spot/directional 类型、方向旋转、强度与阴影开关 | upstream_lights.h 与 upstream_host.h::ApplyLights | 原生字段映射；解析模式支持 shadow off，公共消费者 off→on→off 定向通过；Native 不支持请求仍拒绝 |
| A12 | 有限半径衰减、小灯稳定求和、彩色透射阴影 | cpp/RtrAnalyticBeauty.hlsl，显式 ANALYTIC_DIRECT | API 外部 pass；原生相交、材质、Fresnel；不是原版 bool visibility 等价替换。玻璃弹球及公共入口已运行 |
| A13 | 原生 NEE/NEE-AT、点／环境 residual、采样 PDF/MIS、灯族比例 | NATIVE 模式直接使用官方积分器及公开参数 | 旧改原 shader 的 exact/residual/低差异策略不重新注入；未声称逐项等价。失败候选不恢复 |
| A14 | 玻璃反射／折射、粗糙度、嵌套介质、吸收、IOR | 原 BSDF/delta lobes/GenerateScatterRay/nested dielectric；外部解析 pass 编排 | 前四界面确定性双分支、后续固定序列；主表面只采原镜面 lobe，修正误引漫反射颗粒。完整六介质场未在本轮签收 |
| A15 | 光路共同前缀、bounded replay、分支 payload 丢失修复 | 解析 pass 共用着色前缀，调用原生表面／散射函数；Native 用原生 branch queue | 不保留旧 patched replay cache 或相同 payload 实现；边缘仍见固定采样纹理，人眼待验 |
| A16 | 连续灯微变不反复清 RR/NRD、连续／不连续历史分类 | API 稳定 ID、真实 delta 与原生合法 history reset；direct 为当前帧 | 完整 Native beauty CONTINUOUS／1ULP 等价仍未证明，不能写已全部迁移 |
| A17 | SR preset、固定曝光、输入／输出尺寸及画质默认 | 原生 SR／exposure／Bloom／tone map；示例 rendererConfig | 弹球 1 SPP、Performance、2×FG 请求、玻璃 −1.5 EV、去噪／SHaRC 关；未用加 SPP 冒充恢复 |
| A18 | pre-SR bounded history、抖动重投影、自有稳定化 | 未找到等价公开中段 hook | 旧自定义 pre-SR history 没有原样迁移；不得说被原生 SR 自动等价替代 |
| A19 | RELAX/REBLUR、RR、分层降噪、原生光学层 | 原生配置／Streamline/NRD；RtxptNative.denoiser | RELAX JNI 接线保留；解析模式明确禁用 NRD/RR，弹球通过不能代签 RR |
| A20 | 背景与内部照明分离、alpha0 残 RGB、透明 coverage、RR guides | direct receiver alpha 外置；Native coverage 沿官方实现 | 完整 beauty 背景／透明合同仍有缺口，最终 RGB 不能反推玻璃 coverage |
| A21 | host-primary、lobe/source 所有权、避免 emission/environment 重算、AOV | 原生可用 AOV 优先；外部 host/direct 输入独立 | 完整旧逐 hit/lobe/source 过滤没有等价公开入口，未恢复；不做 RGB 减法伪实现 |
| A22 | emission 精度、矩阵/TRS、非均匀缩放、默认 flat normals | resources 适配 TRS/法线；emission 保留官方 packed 格式 | 原版量化差异已记录，不改原 shading data；全部人物与删重建场景未代签 |
| A23 | 顶点色、纹理／scalar alpha、cutout、sampler、masked shadow | 外部 direct 属性／geometry 元数据／sampler | direct masked receiver/shadow 专项通过；原生 beauty masked scalar 仍有缺口 |
| A24 | 清晰宿主底图＋direct gain/shadow、源组合与亮度映射 | cpp/RtrNativeLighting*、RtrHostResponseGain、rtxpt_native_lighting.h | 独立 API DIY；复用原 scene/TLAS。旧公式保留，非 NVIDIA 原生亮度；完整宿主三状态仍待复验 |
| A25 | 独立一跳 GI RR、motion/depth/normal/coverage、Present 资源生命期 | rtxpt_native_rr.h，公开 StreamlineInterface 与 viewport1 | 不增加私有 getter；公开接线不等于当前 GI/RR 已实际验收 |
| A26 | 半分辨率深度／法线／identity 引导重建与薄边防串色 | 外部 GiUpsample 等 adapter shader | 保留可选 API pass；运动薄边及跨边完整复验尚未完成 |
| A27 | CENTER_BOX/SPHERE、静态中心支撑、动态半径、遮挡终点 | 外部 source/support shader | 旧自有几何语义保留；不是原生 emitter geometry；slab/球内外及遮挡终点待复验 |
| A28 | 精确投影、hurt/bob/asymmetric/affine、共享原点 | direct 精确矩阵适配；Native beauty 公开相机 | direct 代码保留；beauty 仅 FOV 不等价所有矩阵，不能代签 |
| A29 | 手持／掉落／放置 RGB 响应、staticCovered/fallback、sun/moon/fog | 外部 HostResponseGain 与完整初始化的 640 对 lookup | 尾部初始化修复保留；同源同色同几何亮度一致须由实际消费方复验 |
| A30 | resize、外部目标尺寸、缓存绑定生命周期 | upstream_host.h::RenderAnalyticBeauty 重建时清 binding set/light identity | 本轮 640×480→960×540→640×480 三阶段 36 帧通过；不代签全部材质 domain 切换 |
| A31 | 深度诊断、事件关联、有界异步输出、OFF 停采 | RtxptDiagnostics、API 边界事件／阶段与 session statistics | 不再向 RTXPT 核心注入旧 probe；内部只用原公开 Tracy；CPU wall 不是 GPU busy |
| A32 | GPU scope／光路追踪、采样记录、SR 输入因果证据 | 已退役的 patched 内部 optical/NEE probes 不进入新版 | 历史证据留档；没有公开同等诊断入口就收缩，不能伪称仍有逐光路内部诊断 |
| A33 | FG 交付与节奏、标题/HUD FPS、真实技术状态 | RtxptAccess.frameStatistics 读取原 GetFPSInfo；NativeSceneHud 分列 submit FPS | 原标题 DLSS-G 的 * 是倍率估计，不是实测 presents；GPU 毫秒没有 getter，不填假数 |
| A34 | 旧 HUD／Scene combo、键盘／console、默认曝光、窗口化 | ConsumerUI、NativeSceneHud、NativeSceneDemo、两场景类 | 控件与真实 native 状态保留；500ms HUD／25ms selector。人眼外观仍由用户验收 |
| A35 | 原编译进度弹窗与非交互错误处理 | 唯一授权 ProgressBarStart nonInteractive guard | 定向专项通过；原版编译失败仍可能 ExitProcess，不把 JVM 终止描述成可恢复异常 |
| A36 | Vulkan 渲染／RTXPT-Vulkan 共享 surface、融合调度、FG interop、预算 | 按用户授权整条退役 | 对应泄漏／回收／resize／overlay 修复随旧路线删除，不算迁移成功；无幽灵后端 |
| A37 | 旧后端／光学实现、测试和文档清理 | 8.0.0 重建接口与测试，旧记录由 Git 保全 | 非 RTXPT 的早期实现不留第二套执行链；撤回实验不可按标题恢复 |

全部视觉 V01–V15 的旧代码证据及具体缺口另见 [视觉调查](visual-migration-audit.md)。该文件是历史调查，当前解析恢复与本次 resize/HUD 修复以 [恢复记录](analytic-visual-recovery.md) 为准。不能把早期 stochastic balls 状态当当前实现，也不能把当前弹球通过扩大为上述所有行通过。

## 本次人工黑屏、HUD 与发布

用户在 build/reports/manual-acceptance/20261107-144150-balls 人工场看到黑屏；该场正常 exit 0，但视觉失败。错误为 volatile analytic beauty constant buffer 未写入。needNewPasses 清 pipeline 后会重建 constants，而旧 binding set 仅按 light buffer identity 复用，仍引用旧 constants。现于 pipeline 重建分支同时清空 cached bindings/light identity，范围完全在 API 桥接层。

最终 DLL SHA256 为 e7297fea195b9a59ed8821696c9d02b6d4b709a5ccb7b686b86eae10d4c8ecda；解析 shader 未改。桥接构建 25.554s、Job 提交峰值 0.786GB，Java 编译 9.994s、1.239GB。独立公共 resize 消费者 36 帧 PASS，14.938s、4.169GB。两场都没有上述 volatile buffer 错误；原 SymInitialize code87 日志不伪称完全无 ERROR。

默认 FG 弹球隐藏场 build/reports/visual-recovery/20261107-145319-balls-auto：初始 GPU0%，2560×1494／1280×747，1SPP、Performance、2×FG 请求且 active mode=1，1834 帧，230.616526 完成提交 FPS；submit mean3.617360ms、P954.8141ms，failure=null、丢失0、exit0，Job 提交峰值5.086GB。提交量不是任务管理器内存。此场与前次 FG-off 条件不同，不硬算性能改善百分比；用户人眼仍待验。

HUD 原来硬编码 GPU/PRESENT N/A，标题则是原生带 FG 倍率估计的统计。现在 HUD 直接显示同源 GetFPSInfo，独立标出 API completed submit FPS，并说明 * 含义；GPU 时间只指向原生 Tracy，不伪造计数。首次统计为空只显示 WARMING UP。

旧 Local 手工删除未完成，安全审核拒绝；本次按用户后续授权用标准 publishToMavenLocal 覆盖同版本两个模块。源码先上传 GitHub，再覆盖 Local；实际发布结果与包身份以发布后回执为准。

## 逐提交导航

以下是历史索引导航，不是逐项行为 PASS。类别按提交标题／路径关联到上表；一个提交可能跨多类，详细文件列表全部保存在 JSON。无法由标题确定的旧逻辑明确列为逐项未证明，不强行归入“已迁移”。

| 提交 | 原标题 | 新版定位／状态 |
| --- | --- | --- |
| 648f2c17a1 | Rebuild 8.0.0 as an external RTXPT consumer and restore generic analytic visuals | A04：构建与来源；唯一进度窗例外 |
| fe0e0af0bb | Preserve native resource candidate before clean upstream reintegration | 试验／诊断／撤回：保留历史，当前行为须按 A13/A15/A32 核查，未逐项证明 |
| 0b0b0e4337 | Reduce receiver arena amplification and batch sparse uploads | A06–A10/A24–A31：增量事实／桥接契约保留，原生资源执行替代；细项见原文件 |
| 3c61362522 | Fix continuous lighting eligibility before first publication | 历史项：见完整文件索引；当前是否等价未逐项证明，不标为恢复 |
| e4c755398f | Fix retained resource updates and bounded renderer maintenance | A06–A10/A24–A31：增量事实／桥接契约保留，原生资源执行替代；细项见原文件 |
| 81be83d923 | Fix retained light journals and transactional regional maintenance | A06–A10/A24–A31：增量事实／桥接契约保留，原生资源执行替代；细项见原文件 |
| 65810faff9 | Add incremental diagnostic monitoring in 7.3.0 | A06–A10/A24–A31：增量事实／桥接契约保留，原生资源执行替代；细项见原文件 |
| d9e867d657 | Fix scoped full-scene resource packet memory for 7.2.3 | A06–A10/A24–A31：增量事实／桥接契约保留，原生资源执行替代；细项见原文件 |
| af7ea1880a | Register resource import and range regression contract | A06–A10/A24–A31：增量事实／桥接契约保留，原生资源执行替代；细项见原文件 |
| f5d670b3ad | Fix resource CPU hotspots and legacy host lookup defaults | A06–A10/A24–A31：增量事实／桥接契约保留，原生资源执行替代；细项见原文件 |
| eb29fcc5cc | Consolidate compatible lighting acceptance windows [skip ci] | 历史项：见完整文件索引；当前是否等价未逐项证明，不标为恢复 |
| e14289c161 | Complete 7.2.1 host lighting response and resource repairs | A06–A10/A24–A31：增量事实／桥接契约保留，原生资源执行替代；细项见原文件 |
| 3a780ba800 | Complete rc.10 native lighting resource and visibility changes [skip ci] | A06–A10/A24–A31：增量事实／桥接契约保留，原生资源执行替代；细项见原文件 |
| fc72bb4863 | fix: retain generic TLAS capacity and exact counters (7.1.7) [skip ci] | A06–A10/A24–A31：增量事实／桥接契约保留，原生资源执行替代；细项见原文件 |
| cedd9085aa | Complete final native matrix and lifecycle evidence without performance tuning [skip ci] | A02：原生生命周期与 session 关闭 |
| 38158f88cd | Complete 7.1.6 resource architecture repairs and verified local artifacts [skip ci] | A06–A10/A24–A31：增量事实／桥接契约保留，原生资源执行替代；细项见原文件 |
| 729f0e9a35 | Complete 7.1.5 resource lifecycle repairs and local publication [skip ci] | A06–A10/A24–A31：增量事实／桥接契约保留，原生资源执行替代；细项见原文件 |
| cb4f314c2d | Improve retained high-framerate resources and release 7.1.4 | A06–A10/A24–A31：增量事实／桥接契约保留，原生资源执行替代；细项见原文件 |
| be6a33ea12 | Improve retained dynamic resource updates and publish 7.1.3 | A06–A10/A24–A31：增量事实／桥接契约保留，原生资源执行替代；细项见原文件 |
| 86d0370274 | Fix local scene preparation for 7.1.2 | A06–A10/A24–A31：增量事实／桥接契约保留，原生资源执行替代；细项见原文件 |
| f5aaeac6eb | Optimize native light traversal and record clean GPU comparisons | 历史项：见完整文件索引；当前是否等价未逐项证明，不标为恢复 |
| 0a78724450 | Release 7.1.1: repair native lighting, add GI reconstruction and reduce dispatch cost | A23–A26：direct 属性／GI重建；当前未全量复验 |
| ad6d265bb8 | feat: release 7.1.0 retained lighting and host surface capabilities | A06–A10/A24–A31：增量事实／桥接契约保留，原生资源执行替代；细项见原文件 |
| b806c65cfd | fix(rtxpt): bounded frame preparation and exact external output for 7.0.5 | A30/A08：尺寸及材质；本次仅解析 resize 定向通过 |
| b9dbba3df0 | feat: deliver 7.0.4 transparent primary coverage and GPU background composition | A36：旧融合路线退役，相关修复不再执行 |
| 5c6538829a | Release 7.0.3 incremental lighting and retained scene performance | A06–A10/A24–A31：增量事实／桥接契约保留，原生资源执行替代；细项见原文件 |
| 532940d843 | Release 7.0.2 retained scene updates and scalable lighting | A06–A10/A24–A31：增量事实／桥接契约保留，原生资源执行替代；细项见原文件 |
| de976bb900 | Release 7.0.1 RTXPT memory and incremental lighting updates | A06/A10/A11/A16：原生场景／灯适配，历史等价非全通过 |
| eb38882ce6 | Prepare 7.0.0 local release and clean diagnostic artifacts | A03/A37：标准分发／接口重建；不把打包当行为验证 |
| 3419fee148 | Package the Windows JNI bridge case-insensitively and verify native ZIP entries | A03/A37：标准分发／接口重建；不把打包当行为验证 |
| 3d8014d044 | Expose Vulkan surface budgets and complete portable RTXPT budget contracts | A36：旧融合路线退役，相关修复不再执行 |
| 058f8513cf | Apply path budgets before native allocation and verify runtime quality controls | A36：旧融合路线退役，相关修复不再执行 |
| df5343782a | Expose consumer GPU budgets and fix composition source reuse | A36：旧融合路线退役，相关修复不再执行 |
| 18a0f2373f | Restore native fusion camera control and document single RTXPT recommendation | A36：旧融合路线退役，相关修复不再执行 |
| a34d374f31 | Consume resize-transition frames without mismatched Vulkan overlays | A36：旧融合路线退役，相关修复不再执行 |
| 9982a13aed | Add interactive RTXPT Vulkan fusion demo and stabilize native scheduling | A36：旧融合路线退役，相关修复不再执行 |
| 6cd54c48ed | Fix Vulkan FG surface lifecycle and clarify renderer naming and HUD | A36：旧融合路线退役，相关修复不再执行 |
| 55fa34855d | Retain measured replay cleanup, remove inactive hit-cache candidate, prepare clean FG acceptance | 试验／诊断／撤回：保留历史，当前行为须按 A13/A15/A32 核查，未逐项证明 |
| 23dabac0a7 | Keep restored-pixel trace sparse and reject overflowed work evidence | 试验／诊断／撤回：保留历史，当前行为须按 A13/A15/A32 核查，未逐项证明 |
| 4058972a21 | Reuse committed optical fork hits and honor disabled GPU timing | A14/A15/A32：原生光学＋外部解析编排，旧探针退役；非逐项等价 |
| 1348cc4957 | Add sparse optical-work evidence and per-second FG delivery and pacing diagnostics | A14/A15/A32：原生光学＋外部解析编排，旧探针退役；非逐项等价 |
| 15b51f31ed | Reuse common optical replay prefixes and record the material baseline difference | A14/A15/A32：原生光学＋外部解析编排，旧探针退役；非逐项等价 |
| 81be532485 | Align glass transport roughness with native BSDF and trace reconstruction inputs | A18/A32：旧 history／内部证据无等价公开入口 |
| 0402c3de4b | Generalize weighted glass branch replay across optical continuations | A14/A15/A32：原生光学＋外部解析编排，旧探针退役；非逐项等价 |
| efb37a6556 | Split the first downstream glass junction and trace every termination | A14/A15/A32：原生光学＋外部解析编排，旧探针退役；非逐项等价 |
| 26ba71ac40 | Fix straight-through glass shadow Fresnel angular domain | A14/A15/A32：原生光学＋外部解析编排，旧探针退役；非逐项等价 |
| a95b9760db | Add isolated optical branch seed control for causal noise verification | 试验／诊断／撤回：保留历史，当前行为须按 A13/A15/A32 核查，未逐项证明 |
| de713f45cb | Read optical diagnostics after final merge on a single diagnostic queue | A14/A15/A32：原生光学＋外部解析编排，旧探针退役；非逐项等价 |
| f50eb26586 | Complete two-frame glass transport evidence through SR input | A14/A15/A32：原生光学＋外部解析编排，旧探针退役；非逐项等价 |
| 9ef285266b | Capture transparent occluder data and raygen radiance in the same trace | A31/A32：边界诊断保留，旧内部探针退役 |
| 3baa471a65 | Trace glass receiver light inputs through final radiance commit | A14/A15/A32：原生光学＋外部解析编排，旧探针退役；非逐项等价 |
| dac6551c94 | Match bounded glass continuation and transparent direct-light visibility | A14/A15/A32：原生光学＋外部解析编排，旧探针退役；非逐项等价 |
| 42b9221176 | Preserve dielectric branch selection across hit shader payload boundaries | A14/A15/A32：原生光学＋外部解析编排，旧探针退役；非逐项等价 |
| 9e98754f0c | Use numeric optical records compatible with release DXIL | A14/A15/A32：原生光学＋外部解析编排，旧探针退役；非逐项等价 |
| f649abea6e | Enable sparse optical records in release shader diagnostics | A14/A15/A32：原生光学＋外部解析编排，旧探针退役；非逐项等价 |
| 6be76b36bb | Add sparse optical path evidence to diagnose missing glass transmission | A14/A15/A32：原生光学＋外部解析编排，旧探针退役；非逐项等价 |
| 6aeba95f84 | Complete actual dielectric branch transport matching legacy lighting | A14/A15/A32：原生光学＋外部解析编排，旧探针退役；非逐项等价 |
| 3375e8d657 | Add legacy lighting input candidate and correct history jitter reprojection | 试验／诊断／撤回：保留历史，当前行为须按 A13/A15/A32 核查，未逐项证明 |
| 33674869b7 | Port legacy bounded surface history before RTXPT super resolution | 历史项：见完整文件索引；当前是否等价未逐项证明，不标为恢复 |
| 48a18b46b9 | Port summed continuous BSDF response with native mixture PDF | 历史项：见完整文件索引；当前是否等价未逐项证明，不标为恢复 |
| 19351e8dc9 | Isolate per-directional environment sampling with reciprocal MIS | 试验／诊断／撤回：保留历史，当前行为须按 A13/A15/A32 核查，未逐项证明 |
| 206a40cd13 | Reject exact-environment regression and isolate native low-discrepancy NEE | 试验／诊断／撤回：保留历史，当前行为须按 A13/A15/A32 核查，未逐项证明 |
| 85d5dfe90c | Require completed native frames for quality and light-family evidence | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| a12043f2e5 | Replace existing upstream environment macro without conflicting definitions | 历史项：见完整文件索引；当前是否等价未逐项证明，不标为恢复 |
| b71ab66f95 | Compare renderer signal paths and isolate native exact environment sampling | 试验／诊断／撤回：保留历史，当前行为须按 A13/A15/A32 核查，未逐项证明 |
| 8f64d36c72 | Preserve native diagnostics and add isolated pre-SR history evidence | 试验／诊断／撤回：保留历史，当前行为须按 A13/A15/A32 核查，未逐项证明 |
| 7ea2f3d490 | Align NVIDIA SDK policy and isolate actual SR exposure input difference | 试验／诊断／撤回：保留历史，当前行为须按 A13/A15/A32 核查，未逐项证明 |
| 96bd4b8065 | Audit current NVIDIA models and stage native NIS sharpness evaluation | A17/A18：原生公开配置，旧中段定制不等价 |
| e3903aaf2e | Record accepted frame rate and add isolated upstream SR-model comparison | 试验／诊断／撤回：保留历史，当前行为须按 A13/A15/A32 核查，未逐项证明 |
| 3646885b03 | Retain accepted SR power-sampling policy and restore demo defaults | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| 7afa1be727 | Skip residual light remapping for the unchanged environment prefix | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| 22b3cc9a49 | Checkpoint RTXPT sampling correction and matched-settings evidence | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| 83186f513e | Fix unsafe RTXPT RR quality preset and record image tests | A05/A17/A19：公开契约／原生配置 |
| 8fcef71cbe | Expand RTXPT lighting quality benchmark combinations | A05/A17/A19：公开契约／原生配置 |
| edddc1586e | Sync patched RTXPT shaders into packaged runtime | A03/A37：标准分发／接口重建；不把打包当行为验证 |
| f74ff97c50 | Add RTXPT light-family quality measurements | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| 66b63cfe52 | Use RTXPT light type encoding for spot residuals | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| b435067ad5 | Align exact point lighting gate with RTXPT pools | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| 39d120ab99 | Fix weighted residual local light sampling | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| 28527f9d49 | Condition exact point-light sampling on residual lights | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| a37fc960e5 | Allow exact light sampling with native denoisers | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| 8865d8a866 | Gate exact point lighting on active denoising | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| eb54d3edd7 | Allow exact points with DLSS Ray Reconstruction | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| 51ea9e0f47 | Enable exact points with mixed analytic lighting | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| 9c80bcf164 | Log RTXPT sampling eligibility | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| a2e5cf794e | Enable unbiased residual environment sampling | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| adfad4d0c3 | Preserve native NEE-AT feedback PDF | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| 20f688099a | Exclude exact point lights from NEE-AT feedback | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| 2560fbfc27 | Diagnose RTXPT residual-light noise | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| 82ed07e164 | Cover light ratio extremes and direct versus indirect quality diagnostics | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| ec196d9506 | Isolate residual environment sampling from exactly summed point lights | 试验／诊断／撤回：保留历史，当前行为须按 A13/A15/A32 核查，未逐项证明 |
| 7545ead3af | Preserve SR lighting ratio and expose upstream exposure with noise isolation evidence | 试验／诊断／撤回：保留历史，当前行为须按 A13/A15/A32 核查，未逐项证明 |
| 3e03d5c24f | Preserve frame throughput while trimming idle composition resources and reset unloaded SDK function caches | A36：旧融合路线退役，相关修复不再执行 |
| 6471e05965 | Create the Vulkan companion only when its commands or overlays are used | A36：旧融合路线退役，相关修复不再执行 |
| 82e516cfe3 | Release inactive composition memory after consumer retirement | A36：旧融合路线退役，相关修复不再执行 |
| c5ba34390c | Correct composed work accounting and add isolated lifecycle memory observation | A36：旧融合路线退役，相关修复不再执行 |
| 92bc993000 | Return Vulkan GPU layers before RTXPT presentation and separate Medium Demo artifact | A36：旧融合路线退役，相关修复不再执行 |
| e0f972872e | Preserve recoverable Vulkan admission and retire completed presents during composition | A36：旧融合路线退役，相关修复不再执行 |
| de8d48da17 | Use valid D3D12 color-resource flags for shared frame allocations | A06–A10/A24–A31：增量事实／桥接契约保留，原生资源执行替代；细项见原文件 |
| 9afe68112c | Select Agility before Vulkan device creation and preserve both packaged providers | A36：旧融合路线退役，相关修复不再执行 |
| 2778d5cb98 | Initialize the owning RTXPT SDK before the legacy optional bridge | 历史项：见完整文件索引；当前是否等价未逐项证明，不标为恢复 |
| 1afb354066 | Add GPU-native RTXPT Vulkan stage composition and hot-switching contracts | A36：旧融合路线退役，相关修复不再执行 |
| 9d165cc459 | Add fixed backend scene routing native gallery selector and release contract fixes | A17/A33/A34：外部示例与真实状态；人眼待验 |
| 9bebb7ad89 | Add medium demo HUD default FG and enforce RTXPT API input contract | A17/A33/A34：外部示例与真实状态；人眼待验 |
| 26e79d20c9 | Withdraw mixed-scene regression and retain broad lighting evidence | 试验／诊断／撤回：保留历史，当前行为须按 A13/A15/A32 核查，未逐项证明 |
| f4cb354ef5 | Separate exact delta sums from mixed-scene native sampler policy | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| 0b6971fb8e | Record broad lighting evidence and extend delivery and sampling regression | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| 420c6b550f | Apply scene-driven delta lighting and fix general directional NEE semantics | A12/A13：外部解析或原生采样；旧原文件补丁不恢复 |
| 0a8b3ecc4c | Use non-reserved HLSL identifier in isolated lighting candidate | 试验／诊断／撤回：保留历史，当前行为须按 A13/A15/A32 核查，未逐项证明 |
| 2756fa5c86 | Isolate native small-point lighting candidate without adding denoising | 试验／诊断／撤回：保留历史，当前行为须按 A13/A15/A32 核查，未逐项证明 |
| bfb5ce17d1 | Add paired Vulkan stage evidence and native RELAX SR verification | A36：旧融合路线退役，相关修复不再执行 |
| 7dbd0cb265 | Record RR hotspot and add controlled upstream scheduler and model comparisons | A05/A17/A19：公开契约／原生配置 |
| 57260934bc | Add sampled upstream RTXPT GPU scope evidence for hex-ball gap | A17/A33/A34：外部示例与真实状态；人眼待验 |
| 1df3af248d | Match original hex-ball defaults and report observed FG delivery in windowed demo | A17/A33/A34：外部示例与真实状态；人眼待验 |
| e56c6cdc9c | Restore native demo HUD and render physically reflective glass balls | A14/A15/A32：原生光学＋外部解析编排，旧探针退役；非逐项等价 |
| bbae2bea8a | Export directionless point lights with identity rotation | 历史项：见完整文件索引；当前是否等价未逐项证明，不标为恢复 |
| 91b1cc1045 | Render the original animated hex-ball scene through RTXPT and remove completed TODO | A17/A33/A34：外部示例与真实状态；人眼待验 |
| 31452a386a | Add native versus packaged JAR acceptance gates and advanced lifecycle coverage | A02：原生生命周期与 session 关闭 |
| 861a611839 | Fix Streamline plugin shutdown lifetime and complete native feature feedback | A02：原生生命周期与 session 关闭 |
| ad8839472b | Use pinned SDK shutdown and material snapshot headers in JNI host | A02：原生生命周期与 session 关闭 |
| 6af84b1a87 | Preserve upstream frame completion and order embedded Streamline teardown | A02：原生生命周期与 session 关闭 |
| ae52a94689 | Export explicit flat normals for meshes without authored normals | A08/A22：通用材质／几何适配 |
| b05364c506 | Preserve native self-test assets for standalone RTXPT image comparison | 历史项：见完整文件索引；当前是否等价未逐项证明，不标为恢复 |
| a29af86026 | Route RTXPT advanced switches through ordinary API configuration and expert feature control | A05/A17/A19：公开契约／原生配置 |
| 2b1de4eab8 | Validate bundled RTXPT scenes, transform updates, captures and resize outside the checkout | A30/A08：尺寸及材质；本次仅解析 resize 定向通过 |
| a1919e8045 | Include the optional RTXPT backend in releases and document the reduced contract | A03/A37：标准分发／接口重建；不把打包当行为验证 |
| db17ad6d4b | Keep embedded shader compilation silent and load signed runtime plugins | A03/A37：标准分发／接口重建；不把打包当行为验证 |
| a9481978ba | Bundle and checksum the resident RTXPT runtime in the backend JAR | A03/A37：标准分发／接口重建；不把打包当行为验证 |
| 5cd22fd442 | Match upstream numeric controls and accept exported host scene paths | A05/A17/A19：公开契约／原生配置 |
| a663b04753 | Pass embedded host arguments through upstream initialization | A01/A04/A05：真实宿主／标准初始化／构建 |
| ff3358eded | Select Agility through the retail device factory in Java hosts | A01/A04/A05：真实宿主／标准初始化／构建 |
| 17e5ed008d | Resolve RTXPT Agility path across volumes | A01/A04/A05：真实宿主／标准初始化／构建 |
| 5adaaa16fd | Link D3D12 after declaring JNI host target | A01/A04/A05：真实宿主／标准初始化／构建 |
| 8de8467402 | Resolve Agility SDK path relative to Java executable | A01/A04/A05：真实宿主／标准初始化／构建 |
| 1b9cedd1bd | Preload Streamline interposer in the Java process | A01/A04/A05：真实宿主／标准初始化／构建 |
| 821e2de068 | Load upstream native dependencies before JNI entry | A01/A04/A05：真实宿主／标准初始化／构建 |
| f54f7a0c4f | Include native material definitions in JNI host | A01/A04/A05：真实宿主／标准初始化／构建 |
| 51dd7fa005 | Isolate NRD host debug artifacts | 试验／诊断／撤回：保留历史，当前行为须按 A13/A15/A32 核查，未逐项证明 |
| 55ed82b77e | Retire old transport tests and count actual RTXPT presentations | A14/A15/A32：原生光学＋外部解析编排，旧探针退役；非逐项等价 |
| e431721e69 | Fix host tool symbol target names | A04：构建与来源；唯一进度窗例外 |
| 6fc06f612f | Separate embedded host symbols and fix emissive demo material | A17/A33/A34：外部示例与真实状态；人眼待验 |
| 1f4dc86c9e | Fix RTXPT scene calls and enforce UTF-8 native compilation | A04：构建与来源；唯一进度窗例外 |
| fd1e2d5f55 | build(rtxpt): bound shader compilation and use validated local SDK | A04：构建与来源；唯一进度窗例外 |
| 9e92de4f60 | test(rtxpt): align v7 environment contracts and add explicit capture | A05/A17/A19：公开契约／原生配置 |
| 33e9ee72a5 | fix(rtxpt): complete v7 callers and add explicit SDK selection | A01/A04/A05：真实宿主／标准初始化／构建 |
| 5164b65007 | feat(rtxpt): replace LuxCore with in-process host and shrink v7 medium contract | A01/A04/A05：真实宿主／标准初始化／构建 |
