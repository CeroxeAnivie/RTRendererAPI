# 7.1.0 宿主主表面 GPU 输入 ABI

普通配置入口为 `RtxptRenderers.openHostSurface(plan)`：分拆传输默认优选 RR，底图增补保持有限信号 NONE；完整配置重载用于专家明确控制。GPU 输入／输出 lease 责任不变，见[普通用法与专家控制](ordinary-and-expert.md)。

`HostSurfaceControl` 管理输入方向的共享槽；`LightingFrameLease` 管理相反方向的光照输出，两者不能互换。输入描述由 API 分配，借用的 Windows handle 不由宿主关闭。实现限同一适配器上的 Windows D3D12／支持 EXT Win32 互操作的 OpenGL；导入前核对完整八字节 LUID。

## 布局

分拆光照每槽含前八张单 mip、单 sample 的二维纹理；底图增强在其后追加两张，共十张。行 0 是图像顶端，世界坐标与常驻场景一致。分拆光照使用未曝光的 scene-linear 颜色；底图增强由 `HostSurfaceAugmentation.ColorDomain` 显式选择共同颜色域，底图、自发光、源 RGB 与新增间接光必须处于该域，API 不隐式曝光、tone-map 或转换。`position.w` 使用可精确表示的数值整数标志，不能使用 `asfloat`／浮点 bitcast。

|序号|平面|精确格式|通道含义|
|---:|---|---|---|
|0|`WORLD_POSITION_FLAGS`|RGBA32F|世界位置 xyz；w 为标志|
|1|`GEOMETRIC_NORMAL_COVERAGE`|RGBA16F|世界几何法线 xyz；coverage|
|2|`SHADING_NORMAL_ALPHA_CUTOFF`|RGBA16F|世界着色法线 xyz；cutout 阈值|
|3|`BASE_COLOR_OPACITY`|RGBA16F|线性基础颜色 rgb；opacity|
|4|`MATERIAL`|RGBA16F|roughness、metalness、IOR、transmission|
|5|`EMISSION`|RGBA16F|线性自发光 rgb；保留 w=0|
|6|`MOTION`|RG32F|当前像素到上一帧位置的位移，单位为输入像素，+y 向下|
|7|`IDENTITY`|RGBA32UI|公开 instance64 的低／高 32 位；原始 triangle64 的低／高 32 位|
|8|`HOST_LIGHTING_BASELINE`|RGBA16F|仅底图增强：已解析的局部／天空基准 B、S；visibility V；fog F，均在 [0,1]|
|9|`HOST_LIT_COLOR`|RGBA16F|仅底图增强：已经照亮的 diffuse 底图 RGB；straight alpha|

逻辑容量分拆为 80、增强为 96 字节／像素／槽；分拆模式不分配或复制增强平面。实际分配量含 D3D12 对齐，以各平面 `allocationBytes` 为准。三槽、最多两个驻留 allocation generation，共最多六槽。默认且最大纹理预算为 2 GiB，覆盖当前与退休 generation 的真实分配量。尺寸范围 1…16384，同时必须满足所选布局的三槽预算。新 generation 资源全部准备成功后才替换配置；分配、格式或预算失败保留原配置。

输入 wire ABI 版本为 1：固定 18 个 int64 header 加每平面的 `kind,format,allocationBytes,handle` 四项，分拆／增强分别是 50／58 个 int64。header 第 17 项（从 0 计数）必须为实际 plane 数 8／10，所有平面按表中顺序完整出现；未知数量、乱序、缺失、重复、格式不符和分配不足均拒绝。`HostSurfaceInputLease.Frame.composition()` 由该完整布局确定，不能仅凭 `Plane.values().length` 推断本帧数量。

标志：`VALID=1`、`OPAQUE=2`、`PRIMARY_FALLBACK=4`、`BACKGROUND=8`。合法不透明／cutout 表面提供完整位置、法线、已采样材质和身份。无效输入若允许受控主射线则置 `PRIMARY_FALLBACK`；明确背景置 `BACKGROUND`。法线朝向保留几何正面信息，后端根据实际视线决定前后面。宿主负责 alpha、覆盖及分层策略与常驻 RT 几何一致。

instance64 是公开 `SceneInstance.id`，原始 triangle64 是该实例 `MeshAsset` 索引流中的三角形序号。它们不是后端压缩后的实例／geometry 索引。Java mesh 的现行三角形数量范围使 triangle 高 32 位为 0；越界或无法映射的身份按无效表面处理，不能随机借用其他表面的材料。次级命中仍使用常驻场景、原始材质与纹理。

`HostSurfacePlan` 明确选择 `HOST_LIT_AUGMENTATION` 或 `SPLIT_SURFACE_LIGHTING`，并声明保留在宿主底图内的 direct／ambient／AO／emission／fog 位。两者共用前八张主表面及常驻 RT 场景。增强的 `HOST_LIT_COLOR` 是最终 fog、HUD 和其他无关叠加之前的 diffuse 光照层，不能把 `BASE_COLOR_OPACITY` 材质 albedo 当作已照亮底图。HOST_LIT_COLOR 用于请求 COMBINED 的合成；仅请求调制／GI 时仍按十平面布局分配。visibility／fog 只衰减修正及新增 GI，宿主在最终合成后施加一次 fog。motion 是否已包含 jitter 由 plan 声明；调用者应保留申请时的完整 `RenderFrameRequest` 并原样重试。

## 所有权与时序

1. `acquireHostSurfaceInput(request, resourceGeneration)` 非阻塞申请；无空槽返回 empty。
2. 在正确 LUID 的 OpenGL 上导入本帧声明的八／十张纹理及 D3D12 fence。资源处于 Common，EXT 布局用 `GL_LAYOUT_GENERAL_EXT`。
3. GL 排队等待 `previousDoneValue`，随后 `markImported()`，再写入所有平面。
4. 排队 signal `readyValue` 并 `glFlush()`，随后 `publish()`。宿主不能在发布后修改该槽。
5. RTXPT 精确核对 sequence、scene revision、资源 generation、视图／投影身份和 clock；D3D12 队列等待 host-ready，再消费输入。
6. RTXPT 提交读命令并还原 Common，随后 signal `doneValue`。完成后此槽才可再次申请。

同槽 fence 单调递增：`ready=previousDone+1`，`done=previousDone+2`。另有 reservation ticket，避免未导入取消后相同 fence 值被旧 lease 再次操作。未导入时关闭 lease 立即取消，不推进未 signal 的 fence。导入后未发布的取消必须先排队／flush host-ready，再调用 `abandonSignalEnqueued()`；后端仅等待并 signal done，不采样输入。

已发布输入可在读取前由 `discardHostSurfaceInput(generation,sequence)` 放弃。提交在读取前遭拒会保留该 publication，可用同一个 request 重试。`AbortRead` 仅供尚未提交任何读命令时回滚；读命令提交之后必须正常完成 GPU 退休。重复发布、重复消费、过期 ticket、混合帧信息均拒绝。

正常帧没有 CPU 像素拷贝、CPU fence wait、`glFinish` 或 `waitIdle`。扩缩容时只有一代退休资源；下一次扩缩容必须先让上一代彻底归还。关闭 renderer 前，宿主须取消未导入 lease、发布或安全放弃已导入 lease，并丢弃尚未消费的 publication。`RequireReleased()` 拒绝 Acquired／Published／Reading，允许 renderer 已排入真实 done signal 的 InFlight；显式 shutdown 等待自身 GPU 命令完成后，`Clear()` 仍以 `RequireRetired()` 严格验证完成。此路径不伪造 host-ready 或 renderer-done。

## 独立 HDR 输出类型

`HostSurfaceControl.acquireHostSurfaceFrame()` 非阻塞取得 `HostSurfaceFrameLease`，不能将它解码成 `LightingFrameLease`。首个信号 `COMBINED_SURFACE` 的 ordinal 为 0，表示已组合的线性表面结果；它替代自身包含的贡献，不能再与相同 diffuse／specular 分量重复相加。Frame 给出本次实际使用的 `NONE`／`SR`／`RR`，而不是请求偏好；同时保留输出 generation、输入 allocation generation、宿主资源 generation、sequence／revision／clock。

输出 wire ABI 为基础 16 个 int64，加每 plane 的 `signal,format,allocationBytes,handle` 四项，最后追加 `inputGeneration,resourceGeneration,actualReconstruction,colorDomain`；总长度为 `20+4×planeCount`。format wire 1=RGBA16F、2=RGBA32UI、3=RGBA32F；重建 0=NONE、1=SR、2=RR；颜色域 0=SCENE_LINEAR、1=DISPLAY_LINEAR。只有 `COMBINED_SURFACE` 可报告实际 RR/SR，独立 BSDF AOV 均报告 NONE，不能把其原始估计当成已降噪结果，或再叠到已包含它们的 COMBINED 上。消费者 GPU 排队等待 ready、`markImported()`、读取所有 plane、signal completion 并 flush、`completionSignalEnqueued()`、关闭 lease。未导入可直接放弃；导入后必须先排队 completion。API 仍观察真实 fence 才复用资源。

独立 `RtxptHostSurfaceFrameContractSelfTest` 已通过 27 项 descriptor／lease 防护检查，使用隔离的合成描述符，不加载 JNI、不分配 GPU 资源。它不替代真实 HDR 回传／RR 集成验收。

## 诊断与验证边界

`hostSurfaceStatus()` 报告当前 generation、驻留代数、宿主保留槽、已发布槽、未完成 GPU 操作、真实纹理字节及累计发布／消费／放弃数量。`pendingGpuOperations` 包含正常读取和安全放弃的 GPU 同步。

`hostSurfaceWorkStatisticsEnabled(true)` 开启独立三槽异步统计；`hostSurfaceWorkStatistics()` 返回最近完成样本及其 sequence／revision。计数覆盖 supplied primary、fallback primary、invalid/background，以及填充路径的 reused／fallback／invalid。关闭后停止新增 GPU 计数和读回 copy；查询不会为了当前帧等待 GPU。

独立协议测试：`tools/Test-HostSurfaceInputs.ps1` 的 C++ 43 项、Java 公共输入契约 33 项通过；输入 decoder 的 `RtxptHostSurfaceInputContractSelfTest` 66 项通过，覆盖 50／58-word 包、所有身份、signed clock、格式／顺序和坏包拒绝。`-CheckGpuHeader` 可额外编译完整 D3D12／NVRHI header。GL writer 及两种布局的 fixture 已独立 javac 通过；GPU shader／共享交接由单独测试验证。真实输入写入示例见 `examples/host-surface-opengl/`；最终结果以集成测试及最终发布包验收记录为准。
