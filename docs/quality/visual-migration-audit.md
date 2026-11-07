# 7.0.0 之后的视觉修复与外部桥接迁移总账

日期：2026-11-07。只把 7.0.0 发布基线之后的视觉修复计为新增；基线原有能力单列，不能倒算成后续修复。历史逐提交审查已完成，本次只核对当前外部桥接及恢复示例 HUD。未构建、未跑 GPU、未启动客户端、未使用 computer use。

最新恢复口径：用户要求人眼旧观感、正确且高性能，不要求逐像素或全部渲染数值相同；下文“等价／差异”不能自动升级为raw逐值一致门槛。当前已完成三种balls模式各360帧及optics六预设720帧，初始GPU31%–45%，只作忙GPU观测。唯一progress guard、独立direct masked receiver／shadow专项PASS；原版emission量化按正常实现记录，不据此宣称全部视觉通过。具体三组表与原始证据在 visual-recovery-status.md，本文保留历史调查。

## 1. 来源和判断边界

| 对象 | 固定身份／范围 |
| --- | --- |
| API 仓库 | D:/Engineering/code/APIs/RTRendererAPI |
| 排除基线 | eb38882ce6341f5cc0327ceff92124dfd71982a9；Prepare 7.0.0 local release and clean diagnostic artifacts；gradle.properties 为 7.0.0 |
| 历史终点 | fe0e0af0bb84db571c6f95b9ba02a5bc9185dafb；BASE..END 共42个提交，包含候选，不都等于正式发布 |
| 消费方对照快照 | D:/Engineering/code/MCVulkanRT；93222d26fd0e99b03897605929b33aa0f4564c5e |
| NVIDIA RTXPT | NVIDIA-RTX/RTXPT v1.9.0；303b408ab81790a5051abd7573830b7a4112eb8e |
| Donut | e50042f4bbd762971cc90431261e967404855b81 |
| NVRHI | 6b96fb03e07539f08327aea76c56d55f1de9d906 |
| 官方导出 | build/upstream-rtxpt/source；身份来自 source-manifest.json。导出没有独立.git，git rev-parse会落到父API仓库，不能冒充NVIDIA身份 |

历史文件行号均指 fe0e0af 快照，可用 git show 提交:仓库相对路径复核。当前工作树正在迁移，下面按文件／符号描述当前状态，不把历史行号当当前行号。

全部自有视觉、诊断、兼容及资源增强必须独立在 API 桥接层；禁止修改 NVIDIA／Donut／NVRHI 原文件、核心 shader、默认算法，禁止构建时补丁，禁止复制自有文件进上游源树再修改其编译清单。独立可选 API DIY 光照、亮度映射和诊断允许保留，不能仅因在 API 就判错层。上游有等价实现必须使用；“不污染上游”和“没有重复实现”是两个检查。

最新唯一例外：用户随后明确允许构建工作副本的 SampleCommon::ProgressBarStart 加入 HelpersIsNonInteractive() 时 return -1 的 guard，interactive 保持原样。官方 source 导出继续只读；这是进度 UI 例外，不授权任何渲染算法／shader／默认策略修改。完整调查及新增授权状态见第10节，不能继续把最终构建统称零 patch。

| 分类 | 事实 | 现行处理 |
| --- | --- | --- |
| A：API自有外部实现 | 独立shader／pass／适配／合成 | 可选DIY允许，仍须检查上游等价能力 |
| B：向上游树注入自有文件 | 复制新增文件并增加原编译项 | 必须外置；不是改写原BSDF／NEE，但不能称构建输入未变 |
| C：修改官方原文件 | 改原PathTracer、NEE、BxDF、DenoisingLayers、Donut等 | 精确撤销定制，使用已证实支持的外部入口；默认不开不抹去修改事实 |
| D：配置公开参数 | 用原生公开字段表达材质、灯、历史和显示设置 | 优先使用；请求／默认字段不是运行生效证明 |

已外置只证明代码位置与接线；保留逻辑不等于视觉等价。编译、纯度、几何一致和纯上游出图都不是整链交付终点。

## 2. 当前结论

1. 历史既有修改原文件的C，也有独立自有pass的A/B，不能笼统说成全都重写RTXPT核心。A可保留；B/C必须按边界外置。
2. 当前 rtxpt_upstream_host.h::SampleRenderCode 在beauty路线委托原AdvancedPathTracer；direct路线从独立AdapterShaders加载自有pass，复用原生scene/TLAS，输出自己的信号。它仍是API DIY，不是NVIDIA原生beauty。
3. tools/Build-AdapterShaders.ps1使用API自己的shader源、清单及runtime/AdapterShaders产物，官方include只读。旧逻辑名称含app/ProcessingPasses，不代表文件被复制进官方树。
4. 当前direct源组合、gain、中心支撑、HostResponse公式保留；sampler策略从占用原材质flags改到自有geometry元数据／sampler。这是接线迁移，未证明全部视觉等价。
5. 合理的旧视觉修复尚未全部恢复，尤其finite fade、透明coverage、host-primary、CONTINUOUS beauty历史和beauty精确投影。删空契约、关闭能力或改名都不能解决这些差异。原版emission量化不是新发现的渲染错误，不要求恢复raw逐值一致，但其实际人眼观感仍不能未经判断写PASS。
6. 旧balls为SCENE_ANALYTIC且关去噪；新balls为stochastic beauty且关去噪。复制denoise-off不等价。该代码差异可以解释噪声来源的一部分，但没有逐因素像素测量，不能称已证明唯一根因，也不擅自增加SPP/RR掩盖。

## 3. 十五项后续视觉修复矩阵

旧shader／native路径前缀为renderer-rtxpt/src/main/cpp，patch路径前缀为tools/patches。当前状态均为源码审查，不是新画面PASS。

| ID | 版本／提交 | 当时问题与固定旧证据 | 历史层级 | 当前去向、缺口与最小正确路径 |
| --- | --- | --- | --- | --- |
| V01 | 7.0.1 de976bb | range、castsShadow、spot、方向丢失／编码不一致；黑天空把烘焙太阳乘黑。incremental-lighting.patch:75–143,183–199,279–287 | A+C；改EnvMapBaker/RTXDI/PackingUtils/PolymorphicLight/NEE | 部分原生字段映射与外部direct保留。beauty当前noShadow拒绝，finite fade不等价。先正确转交原生type/direction/flux/spot；不能以单常数恢复所有距离。待各灯型／阴影／黑环境复验。 |
| V02 | 7.0.3 5c65388 | 连续1ULP灯变化反复清RR/NRD，照明闪烁。旧JNI:277–291,435；retained patch:178,232–248 | A+C；CONTINUOUS与历史接线 | 外部direct仍是当前帧输入输出；beauty CONTINUOUS尚未等价。先正确稳定ID、真实delta、变化分类与reset，不绕过原生合法history失效。 |
| V03 | 7.0.3 5c65388 | primary背景改变误伤内部照明；RGB编辑误失效masked coverage。旧JNI:437–440,543 | A+C | 材质失效分类由适配层表达；独立primary背景未完整接线。最终外部背景可覆盖简单primary-only显示，不自动等价玻璃路径内部照明与背景分工。 |
| V04 | 7.0.4 b9dbba3 | RR guide强制alpha1、标记变化未重建、alpha0残RGB、cutout/背景/UI不一致。transparent-background.patch:8–30,92–129,140+,183–202 | A/B合成；C改guides/PathTracer/PostProcess/ToneMapping | direct receiver alpha可外置；完整beauty透明合同不等价。先用原生coverage/材质，最终RGB无法反推玻璃路径覆盖率，不补假alpha冒充恢复。 |
| V05 | 7.0.5 b806c65 | 精确外部输出/resize；MASKED/TRANSLUCENT动态切换。frame-preparation.patch:303–320,393–411,485–534 | A+C；MaterialsBaker/PTPipelineBaker/Sample/Streamline | 原生材质重导入与direct精确输出已迁。beauty glfw窗口请求不保证texture extent，须观测实际window/framebuffer/render/display。动态domain和resize往返待测。 |
| V06 | 7.1.0 ad6d265 | 清晰宿主底图只加direct gain/shadow及可选一跳GI；旧增亮和源组合。native_lighting:230+,580+；Style | A自有pass；B复制ProcessingPasses/改Shaders.cfg；sparse beauty另有C | 独立AdapterShaders pass已外部接线；复用原scene/TLAS，旧公式保留，待新画面验收。显式可选DIY允许，不能称原生beauty，也不因在API判违规。 |
| V07 | 7.1.0 ad6d265 | host-primary、lobe/source所有权、emission/environment只算一次、AOV。host-surface.patch:43–58,115+,247+；RtrHostAov:23–53 | A/B AOV；C改Hit/Sample/PathTracer/NEE/DenoisingLayers/BxDF | 旧完整合同未恢复。原生等价AOV优先；未找到已证实公开逐hit/lobe/source过滤入口。最终RGB减底图不等价，不能为保留它再改原核心。 |
| V08 | 7.1.0 ad6d265 | emission压缩误差；矩阵/TRS布局、旋转叠非均匀缩放错误 | C把ShadingData emission改fullfloat；A矩阵/TRS | TRS独立适配已迁；beauty保留原packed emission。原raw断言失败已由原版RGB10 log及fp16端点解释，CPU25点核对通过；不要求raw逐值一致，不改原字段/分支，人眼观感尚未整体通过。 |
| V09 | 7.1.1 0a78724；7.1.5 729f0e9 | 顶点色/alpha/barycentric/纹理相乘、角色及删后恢复；masked scalar丢失。NativeLighting:111–142,184+；masked-alpha.patch:14 | A属性；C覆盖Donut masked opacity | 独立direct已外置保留scalar，receiver与shadow定向PASS，每状态25点；未改renderer的shadow复验纠正了错误夹具。原生beauty masked anyhit仍缺scalar；其它纹理/角色/重建覆盖不能由此代签。 |
| V10 | 7.1.1 0a78724 | 独立GI RR、guide/motion/depth/coverage、薄receiver移动残留。旧native_rr:166+；Reconstruct:32–53 | A/B GI；C新增private token getter/绑定 | 已改公开StreamlineInterface、viewport1，未运行证明。官方tag为eValidUntilPresent，资源必须活到Present；normal/roughness、specHitDist/specMotion互斥不可漏。direct关闭RR不代签。 |
| V11 | 7.1.1 0a78724 | 半分辨率边界不一致、薄边漏色/跨边插值。GiUpsample:16–23 | A/B深度/法线/identity/coverage引导重建 | 自有shader外置保留，待薄物体/运动/跨边复验。原生等价优先，普通双线性不是这些语义的等价替换；可选API DIY允许。 |
| V12 | 7.2.0-rc.10 3a780ba | BOX最近点切向不同旧中心；持灯/静态遮挡终点不符旧画面。NativeLighting:256+,427+ | A/B CENTER_BOX/SPHERE/EvaluateSource/SupportShadow | 外部自有pass保留。中心支撑/半径/容差不是原生emitter geometry等价物，也非已证实上游bug；slab/球内外/动态半径/真实遮挡待测。 |
| V13 | 7.2.0-rc.10 3a780ba | hurt/bob/asymmetric/affine合法投影误拒。ExactProjectionState:130–147 | A逆投影/共享原点 | direct适配保留；beauty仅FOV不等价全部精确投影。需矩阵/射线与resize一致，不能用FOV样例代签。 |
| V14 | 7.2.1 e14289c | 手持/掉落/放置统一RGB lightmap；staticCovered/fallback/sun/moon/fog/compact255。旧shader:630–648 | A/B RGB表/HostResponseGain | 外部自有公式保留，当前RtrHostResponseGain。是可选API DIY，非NVIDIA原生亮度；不因在API违规。同源同色同几何的三状态一致仍须测。 |
| V15 | 7.2.2 f5d670b | response尾部随机值导致JNI随机拒绝。host_response_lookup:8–13 | A初始化修复 | 640对完整初始化保留，不改变V14公式；更新/删除/重建需回归，不能只测首帧。 |

7.0.2的532940d大灯树importance sampling改变单帧噪声：63次抽样、5% uniform。它主要为性能，不算新增视觉修复。35 lights/48-frame平均HDR证据只覆盖该夹具，不代替单帧动态与当前场景。

## 4. 基线继承要求：不算后续新增，但不能丢

| 旧能力 | 原生优先路径 | 尚须明确 |
| --- | --- | --- |
| SCENE_ANALYTIC稳定ambient、小灯exhaustive direct、sky/metal | 正确原生light/environment和模式；若独立稳定direct则显式选择 | stochastic beauty不等价analytic direct，不能复制denoise-off称行为一致 |
| optical六场景、rough/delta/shared-prefix | 原生IoR/ThinSurface/NestedPriority/VolumeAttenuation、delta lobes/denoising layers | 旧精确1..6并非原生layer数；先正确材质和分支，不重写已有volume/optical核心 |
| point/environment分流、BSDF/MIS | 原生输运 | 旧采样策略差异须披露，不能以接口同名恢复原shader patch |
| fixed SR exposure、SR preset、pre-SR history | 公开tone-map/exposure/reconstruction | 未证实原版相同pre-SR中段hook，请求与实际状态分别记 |
| 旧HUD/Scene Combo/title/非阻塞日志 | 外部ConsumerUI与Java示例，500ms刷新 | 实际状态、键盘/console/selector同步；未知不报ACTIVE或0 |

RtrSceneLightingInputs、TransportTrace、ReplayCache、PreSrHistory在eb38882..fe0e0af间内容零差异，不能算后续新增。RtrSceneDirect增加54行删除2行，主要为7.0.2采样和7.1 host/sparse，不把整文件全算新写。

原生已有optical branch queue：PathTracerDenoisingLayersHandleHit.hlsli:123–125 evalDeltaLobes；138–225 prune/capacity；236–242 SplitDeltaPath/StoreExplorationStart；266–267复用best branch；DenoisingLayers.hlsli:186–205为payload queue。先用原生，不因旧项目也有queue再写一份。

MaterialsBaker.h:147 IoR、170 ThinSurface、183–188 NestedPriority/VolumeAttenuation、195 GPUDataDirty。当前rtxpt_upstream_resources把IOR、exp(-absorption)、distance=1、priority转原生，不需改上游volume核心。

## 5. 已证实公开入口和缺口

| 入口／官方依据 | 可以做什么 | 限制 |
| --- | --- | --- |
| SampleRenderCode，实际被原Sample/AdvancedPathTracer调用 | scene/TLAS/material/light更新后调外部pass或委托beauty | direct跳beauty仍可能经过原后处理尾部，纯度不自动消除成本 |
| UpdateCustomResources | descriptor snapshot前更新自有资源 | 不把原私有状态当外部合同 |
| UpdateCustomConstants | 原CB上传前写允许的自定义常量 | 不是任意改原核心算法许可 |
| OnRenderTargetsRecreated | 按实际尺寸重建外部资源 | 请求窗口不等于render/display extent |
| CreateUI virtual/SampleUI::buildUI virtual | 外部HUD/selector | 当前ConsumerUI不改原UI文件 |
| Donut ShaderFactory.h:98–113；.cpp:62–110 | 自有IFileSystem/basePath加载独立shader | 不需复制源进官方树；逻辑路径和物理路径分清 |
| StreamlineInterface.h:325–360,385–398 | SetConstants/SetDLSSRROptions/TagResourcesGeneral/TagResourcesDLSSRR/EvaluateDLSSRR | StreamlineIntegration.cpp:1311–1313,1460–1467为eValidUntilPresent；不加private token getter，不读私有字段 |

UpdateCustomLighting只有声明、没有调用，不能当有效入口。AddCustomBindings不能任意扩原shader root layout；自有pass用自己的layout。

未找到同等公开入口：beauty逐hit/lobe/source的host-primary过滤、玻璃路径primary coverage、原FilterReconstructionInput中段pre-SR history。必须保留缺口，不以最终RGB减法、关闭功能或删合同冒充等价。

## 6. 代码根因、当前修复和HUD

| 问题 | 代码证据 | 当前状态 |
| --- | --- | --- |
| balls关去噪不再等价 | 旧profile:34–45为SCENE_ANALYTIC；新NativeSceneDemo::rendererConfig为PathTracing defaults/denoising disabled | 已定位差异，HUD修复不改质量。正确native模式/材质优先，不加SPP/RR掩盖 |
| RELAX曾只计算未传native | RtxptRenderer已有RtxptNative.denoiser(handle,options.relaxDenoiser()) | 接线已修，运行状态待验；selection不等于activation |
| masked scalar被默认packing覆盖 | 历史问题为Resources写opacity、Donut Material.cpp:97–101 masked打包1，direct再次读packed值 | 当前独立direct外置修复已定向PASS：0.49999不命中、0.50001命中，shadow阻挡gain0.039978接近期望0.04；原生beauty缺口仍在 |
| finite range无法常数等价 | 旧SceneDirect:113–114为(1-d/range)^2/max(d²,1)；原PolymorphicLight:287为flux/d² | 模型差异明确，单距离缩放不覆盖所有距离；保留外部可选模型或明确原生差异 |
| HUD/selector/title丢失 | 原示例依赖原CreateUI，迁移后需ConsumerUI；周期标题曾覆写requested尺寸 | native owner已恢复外部布局；Java首submit前设title/choices/text，500ms刷新，25ms selector轮询，键盘/console保留；未人工验 |

本次Java只修改NativeSceneDemo.java、新增NativeSceneHud.java。geometry/physics/SPP/reconstruction/denoising/cache不动。状态在owner线程、关闭前缓存，关闭后报告只读缓存。

HUD及JSON已接真实RtxptAccess.RenderingState：realtime、reconstruction、dlssQuality、SPP、standaloneDenoiser、NRD method/integration数量、cache mode、ReSTIR DI/GI、DX/NVAPI hit-object/reorder、subsampling、externalLighting。它们是原生模式、shader-path flags与初始化对象，不是GPU执行计数。DLAA按锁定Donut的DLSSMode::eDLAA=6识别，不从输入输出同尺寸推断。

同时读取真实WindowState与ExposureState，分别显示window/framebuffer/render/display、最大化/全屏、曝光。九项技术行保留DLSS SR、DLAA、NIS、DLSS FG、DLSS MFG、REFLEX/PCL、NRD、SER、RTXMU；未有观测的NIS/RTXMU及GPU执行/时间为NOT MEASURED，JSON为null。enum selection、启用策略、对象初始化和本帧执行分开，不能前者冒充后者。

FG请求、active native mode、configured generated/native、SDK查询区间计数与实际交付FPS分开；不把区间count当倍率，也不由它生成output FPS。native submit FPS独立标名。

提交后的状态查询/HUD更新、capture读回不在submit-call/consumer-frame-through-submit局部延迟样本内；提交前selector轮询计入consumer-frame-through-submit，不计入submit-call。全部开销包含在wall FPS区间，报告分项说明。第一段HUD FPS未知，不写0。

## 7. 验收账本

| 范围 | 必须覆盖 | 状态 |
| --- | --- | --- |
| 灯光V01 | native type/direction/spot、range内外、shadow、黑环境太阳 | 待运行 |
| 历史V02/V03 | 1ULP/连续与不连续变化、背景/RGB独立编辑、reset/残留 | 待运行，beauty CONTINUOUS无等价证明 |
| V04/V05 | alpha0无残RGB、cutoff、domain切换、玻璃、resize往返/实际texture extent | 待运行，完整coverage有缺口 |
| V06/V12/V14/V15 | 三状态同亮、BOX/slab/sphere内外/遮挡端点、源组合/tie、RGB表/staticCovered/fallback/sun/moon/fog/零分母/640对 | 待运行 |
| V07/V08 | host-primary、emission/environment只算一次、lobe/AOV、fullfloat/packed范围 | 待运行，多项入口/精度不等价 |
| V09 | 顶点/texture/scalar alpha/cutoff、角色、删重建、sampler | direct scalar receiver/shadow专项PASS；其余属性/beauty仍待 |
| V10/V11 | GI RR/guide/motion/depth/coverage、Present生命周期、薄物体运动/跨边 | 待运行，关闭RR不代签 |
| V13 | hurt/bob/asymmetric/affine/非均匀缩放、resize/共享原点 | 待运行，FOV样例不代签 |
| native optical/material | 六场景/三材质、rough/delta/nested/absorption/IOR，正确native NRD/layers | 待运行 |
| HUD | 首帧title、原布局、九技术行、Scene Combo/键盘/console双向同步、500ms/真实状态/未知不造假 | 首title曾抛Submit first，root缓存后创建应用并javac1.110s通过；四场启动与循环已复验，交互操作及人眼仍待 |
| 整链稳定/性能 | 通用流式真实数量级，apply到fresh、冷/暖、阶段mean/P95/P99、GPU/分配/预算；正式Local后消费方同路线FPS | 未通过；局部、纯度和出图不代签180FPS |

父代理本轮人工运行回执：进程exit0，初始GPU0%，nativeSubmit147.9008 FPS，P95 9.4505ms，max31981.93ms，treepeak8.350GB。用户明确拒绝，不能记PASS；treepeak不冒充任务管理器内存，口径和原始报告由主代理记录。旧数据条件不同，不能计算旧新绝对差或百分比。

## 8. 证明盲区和尚未获得的结论

1. 若只校验官方已列文件hash，会漏掉新增注入文件和运行时旧patched shader cache；必须连来源、构建输入、实际加载产物。
2. 编译/纯度/几何一致不证明visual equivalent；单球/单帧也不覆盖整链。纯上游出图不是交付终点。
3. request/default不证明native生效；native策略/对象存在不证明GPU执行；字段未知不能补0。
4. requested尺寸不证明window/framebuffer/render/display；FG query count不等于倍率/output FPS。
5. 诊断只到RTXPT真实调用边界，延伸只用已有公开诊断，不注入探针或访问私有状态。CPU wall不是GPU busy。
6. mean FPS不代替P95/P99；局部微基准、静止FPS与GPU忙时观测不能代签同条件移动FPS。历史分辨率/场景/preset不同的均值不能硬比。
7. 本次无新运行数据，未证明画面、去噪、三状态亮度、闪退或180/200FPS恢复；HUD只是可审查代码。未经受影响功能与整链验证的候选不能发布。

## 9. 固定证据入口

- D:/Engineering/code/MCVulkanRT/validation/visual-upstream-changes-review.md：42提交及V01–V15完整历史证据。
- D:/Engineering/code/MCVulkanRT/validation/visual-bridge-reintegration-review.md：外部接法、公开hook、实际未调用hook、Streamline生命周期。
- D:/Engineering/code/MCVulkanRT/validation/diagnostic-upstream-changes-review.md：诊断历史及计时语义；不展开为本视觉新增项。
- 当前外部源码：rtxpt_upstream_host.h、rtxpt_upstream_resources.h、rtxpt_native_lighting.h、rtxpt_native_rr.h、RtrNativeLighting*.hlsl/.hlsli、tools/Build-AdapterShaders.ps1。
- 当前示例：examples/native-scenes/src/main/java/top/ceroxe/rt/renderer/rtxpt/examples下NativeSceneDemo.java、NativeSceneHud.java、OpticalMediumScene.java、BouncingBallScene.java。

后续owner应更新当前状态并附真实验收证据，保留历史固定身份，不能把待测直接改成通过。

## 10. 原版进度弹窗调查与唯一补丁授权

本节历史调查只读锁定官方导出源码，没有改native/demo、没有构建或运行GPU。原版调查结论仍成立：正常窗口化与当前Sample生命周期下，没有公开配置能保证首次初始化及真实管线重建零进度弹窗；原版 nonInteractive 不关闭进度，磁盘缓存也不覆盖全部窗口。调查后用户新增唯一内部补丁授权，不再以等待上游入口作为当前执行阻塞。

唯一授权内容是构建工作副本的 SampleCommon::ProgressBarStart 在 HelpersIsNonInteractive() 为真时直接 return -1；interactive保持原样。不重写progress函数，不改baker调度、编译、缓存、错误处理或任何渲染算法。native owner已实施并完成定向progress专项，summary.progress.pass=true；这项PASS不扩展到全部视觉、全部窗口或整体FPS。

### 10.1 精确触发链

| 位置（官方导出行号） | 原版行为 | 为什么现有配置无效 |
| --- | --- | --- |
| SampleCommon/SampleCommon.cpp:354–375 | ProgressBarStart仅检查IsWindowFullscreen，然后取slot并直接std::thread(ShowProgressWindow) | 不读取HelpersIsNonInteractive，没有公开quiet/progress callback/延迟阈值 |
| 同文件:266–299 | 用已注册active window计算位置；CreateWindowEx的parent实参是NULL，创建独立toolwindow，随后SW_SHOW | 隐藏宿主窗口、去掉consumer HUD或不注册active parent均不能关闭该独立窗口；仅影响位置，不是关闭入口 |
| 同文件:325–351 | IsWindowFullscreen比较真实active窗口矩形和monitor矩形 | 真实fullscreen是现有唯一提前返回路径；用户要求窗口化，不能伪造fullscreen或临时铺满屏幕规避 |
| PTPipelineBaker.cpp:717–721 | 仅有实际DXC待编译unique项时Start Compiling shaders | 完整有效DXIL缓存可避开此一个窗口 |
| PTPipelineBaker.cpp:774–779 | 每次进入needsUpdate的编译循环都无条件Start Compiling PSOs，检查updateQueue之前已经调用 | 即使DXIL全命中，甚至queue空，也尝试开窗口；缓存加速或线程尚未调度就完成可能让它偶然不显示，不能保证不弹 |
| ComputePipelineBaker.cpp:187–199 | updateQueue空则退出；仅uniqueCompile数量大于0才Start Compiling compute shaders | 有效缓存可使uniqueCompile=0，避开本编译窗口；仍加载shader并创建PSO |
| Sample.cpp:2804–2806 | needNewPasses时先Start Initializing renderer | 不受DXIL缓存或baker进度开关约束；即使只解决两个baker，初始化窗口仍可能出现 |

ProgressBar包装本身也没有扩展点：SampleCommon.h:129–143只有Start/Set/Stop/Active，Start直接调全局ProgressBarStart；不是virtual，baker构造及Update参数均无progress policy/callback。ShowProgressWindow:269–271只在工作已结束时取消尚未创建的窗口，没有“耗时超过某阈值才显示”的保证。

### 10.2 公开配置与生命周期检查结果

- CommandLine.cpp:27–77的公开选项没有noProgress/quiet。nonInteractive说明为禁需要输入的弹框；SampleBaseApp.cpp:358–361关闭Donut MessageBox并设置HelpersNonInteractive。它只让PTPipelineBaker.cpp:824–827、ComputePipelineBaker.cpp:268–271编译失败时直接记录错误并退出，不经过Retry/Cancel MessageBox。
- noWindow不是headless替代：SampleBaseApp.cpp:364–367明确拒绝。borderless改变窗口装饰与尺寸，不是禁进度窗口策略。
- PTPipelineBaker.h:136–142公开构造/Update/CreateVariant/ReleaseVariant；ComputePipelineBaker.h:34–55公开构造/CreateVariant/ReleaseVariant/Update/verbose。没有progress setter，也没有公开导入已建PSO/序列化baker状态入口。
- Sample.cpp:2852/2856在外部SampleRenderCode之前直接调用两个baker Update，传入needNewPasses。SampleRenderCode外部pass挂钩不能回头阻止这些窗口。CreateRTPipelines/CustomRenderPasses可注册自己能力，但不是“跳过原版初始化/所有baker”的开关；Sample.h:163–168还要求派生类串联原共享postprocess管线。
- 因此，覆盖整个Sample::Render、删除必须的variants、跳过合法needNewPasses/reset或谎报已编译不是薄桥接的等价修复；本专项不建议它们。

### 10.3 缓存能解决什么，不能解决什么

两类baker都使用ShaderDynamic/Bin下对应graphicsAPI的原生缓存。ShaderCompilerUtils.cpp:103–111从exe目录确定绝对输出路径；PTPipelineBaker.cpp:278–288把完整宏、绝对source/include路径等编译命令hash进入文件名，ComputePipelineBaker.cpp:384–395同样使用真实命令hash。ShaderCompilerUtils.cpp:305–310仅当正确文件存在且binary mtime不早于最新source时间才命中。

可执行做法是保留稳定安装目录、完整原生ShaderDynamic/Bin缓存与真实源码时间戳，不在每次启动时清空/重提取；先设好本次真实模式、宏和材质排列，再由原生完成编译，后续持有同一个renderer/baker，避免无必要forceReload/ShaderReloadRequested/needNewPasses。不得篡改mtime让陈旧shader冒充命中，也不得把普通ShaderMake离线shader产物假称所有动态材质排列缓存。

这会减少DXC编译与重复冷初始化，但不能保证无弹窗：新进程/renderer没有可复用的PSO对象；磁盘DXIL命中后PT仍UpdateFinalize并无条件Start PSO进度。新材质排列、真实宏/NRD/NEE质量变更、source改动均可能合法重建，不能为不弹窗吞掉这些变化。NVRHI/driver创建加速也不会改变更早的ProgressBarStart调用；没有本专项性能数值，不能承诺帧率或毫秒收益。

### 10.4 当前授权方案与错误传播

| 目标 | 当前方案 | 结果与代价 |
| --- | --- | --- |
| 少弹、少重复编译 | 同一renderer/baker生命周期、准确预设模式、稳定真实缓存、不无故forceReload | 保持语义，减少不必要工作；首帧/真实重建仍可能弹，不是彻底修复 |
| 后台自动负载不被错误MessageBox卡住 | 保留nonInteractive，完整保留日志/退出码 | 正确防止等待人工Retry；错误仍是失败，不得记PASS |
| 正常窗口化且非交互模式不创建进度窗口 | 按用户唯一授权，构建工作副本ProgressBarStart在HelpersIsNonInteractive()时return -1 | 专项PASS，state=[0,0,1,0,-1,0,0,1]，wall0.562s；官方导出不改；不据此代签完整画面/FPS |

错误边界必须如实保留：PT/Compute在nonInteractive编译或管线错误分支调HelpersExitProcess(1)；SampleCommon.cpp:169–177用TerminateProcess(GetCurrentProcess(),1)后std::_Exit，跳过DLL detach/析构。这是整个宿主进程退出，不是可由JNI catch转换的普通异常。在当前Java宿主内会终止整个JVM，外层runner必须保留native日志和非零退出码；不能在桥接层伪称“已传播Java异常/可恢复”。若另用独立渲染进程，外层可将该真实进程失败转成调用失败，但那是架构和成本选择，本专项未实施，也不冒称官方推荐。

### 10.5 唯一构建副本例外与验证边界

官方 build/upstream-rtxpt/source 与来源清单继续按零差异只读校验；允许差异只发生在构建工作副本的上述一个函数入口。审计须展示原文件身份与仅此 guard 的预期差异，不允许顺带改原shader、默认值、编译清单、缓存有效性或错误分支，也不复制其它自有实现进上游树。未来上游正式提供等价入口时可再收敛，当前不需要等待它才推进授权修复。

HelpersIsNonInteractive()为真时返回-1沿用ProgressBar已有的inactive语义；ProgressBar::Set/Stop已能处理m_slot=-1。补丁只跳过进度线程与UI创建，不跳过shader/PSO工作、不改interactive或错误退出；现在已有定向专项PASS，完整交互/渲染行为及FPS仍不能从该项推导。唯一差异manifest在build/upstream-rtxpt/native/rtrenderer-patches/progress-noninteractive/manifest.json。

此前两模块 Java 全量编译与 API 边界记录为12.918s／采集峰值1.220GB；是构建记录，不是客户端内存。后续progress/direct masked/emission专项原始摘要为build/reports/upstream-material-progress/20261107-130139/summary.json；各项按自己的受测范围记录，不互相转签。本次文档整理不重跑、不联网。finite fade、coverage、host-primary等视觉缺口保留；full-float/raw逐值一致不是本轮人眼目标的硬门槛，正常原版量化不冒称视觉已验收。


Current exception update (2026-11-07): the later network-only authorization is separate from this historical visual result. There are now two allowed exceptions spanning three hash-locked source variants (one progress, two Donut network files); rendering/shader algorithms remain original. Current scope and runtime limits: native-network-policy.md.
