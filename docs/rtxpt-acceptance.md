# 实现与累计验收矩阵

> 历史记录：本文保留 7.x 路线与验收记录，不是 8.0.0 当前用法。当前原生优先的两模块接入见[文档首页](Home.md)和[项目 README](../README.md)。

正确性按[通用 API 标准](quality/correctness-criteria.md)判断：身份、规则与生命周期必须准确，浮点误差按信号单位、精度和下游影响验证。逐位一致是部分内部等价检查，不是用户要求所有浮点输出统一满足的产品标准。

本表保留三份原需求的编号映射。7.1.5 性能优化已停止，N01～N09 最终修复、当前二进制的完整验收与未达预算见[最终验收](quality/resource-latency.md)；[原批次记录](release-resource-latency.md)说明实现，[历史量化证据](quality/resource-latency-historical.md)保留原身份。以下既有渲染结果按原二进制保留，不冒签本轮未运行的功能。此前 7.1.1 新增 H01–H07、最终制品与逐项影响分析见 [修复验收记录](quality/lighting-repair-validation.md)。历史全量、失败补验、本批新覆盖和最终包复验分开累计；遵守不重跑已通过且未受影响项目、不运行 FG/MFG 的约束。`verifyApiClosure` 仍是从零执行完整验收的入口，本次不启动它，也不把累计结果称为一次全量全绿。

宿主主表面模式复用有效 primary；独立原生模式使用自己的低分辨率 primary，不依赖宿主 G-buffer。底图增补输出 H×gain＋有限一跳 GI；主表面传输继续反射、折射和完整光照。两者共用常驻 RT 场景。原生 raw 报告 NONE，新 `openReconstructedNativeLighting` 默认对合法漫反射 GI 执行实际 RR，尺寸、guide 和归属见 [重建契约](native-lighting-reconstruction.md)。gain 不送入 RR，不声称各任意 AOV 均可执行 RR。

## 已取得的开发 GPU 证据

|范围|实际运行与断言|可核对证据|
|---|---|---|
|解析灯结构增量|160k 常驻、12 批编辑，apply＋submit P50 2.3066 ms、P95/max 8.0168 ms；138 upsert／72 removal、116032 B 增量，无完整提交／树重建／PSO 增长|`710` 结构灯日志；最终入口另跑 0／1／64／1k／10k／100k／160k|
|稀疏源|160k 常驻、200 混合批次、TreeMap 参考；GPU HDR 相对 RMSE 0.0003527573＜0.003|`710-sparse-native.log`|
|完整 submit 诊断|并发快照 30 次；frame 前失败与恢复；OFF 后 6 个新帧、同 generation、GPU pending=0；关闭冻结、旧代拒绝|`710-diagnostics-native.log`|
|普通／专家|三种普通模式的配置、RR／NONE 意图、专家预算保留、冲突拒绝，CPU 实跑通过|`RtxptModeUsabilitySelfTest`|
|宿主 raw／RR|raw suppliedPrimary=1936、fallback=0，fill 复用1936；RR 为484／0／484；2帧、8面、96×64→97×65，actual reconstruction 及 AOV 求和断言|`710-directed-raw.log`、`710-directed-rr.log`|
|宿主 BSDF 传输|9帧、8 AOV、178534 断言；相机后红板镜面、表面后蓝板透射、alpha survivor／孔洞／below-cutoff；self emission .25 与 HOST_EMISSION 后0；HOST_DIRECT／AMBIENT 仅移除归属项|`710-directed-transport.log`|
|底图增补|7帧、10输入、4输出，17679断言；黑H／灰H／B0／visibility／fog／颜色域／revision；primaryRays=0；第7帧几何更新无 pipeline-baker 空指针|`710-directed-augmentation.log`|
|应用数据 patch|4096 B 中改4 B实际上传4 B；相邻／间隙上传12 B、重叠上传8 B；GPU结果检查|`710-directed-application.log`|
|compact|100001样本；gain低段绝对误差.003906、高段相对误差.01088916，GI绝对误差.00391；双编码输出8 B／HDR16 B，转换1次|`710-directed-compact.log`|
|有色 GI|2样本／6／.35；CPU参考.705863962、GPU .705566406；红→绿、receiver albedo减半、visibility／fog、源关停恢复、几何移除恢复|`710-directed-matrix.log`|
|角色／姿态|shadow-only／排除／单面／双面／alpha动画／离屏遮挡／receiver cull；491表面像素与烘焙变换比较；opaque快路与完整candidate逐位一致|同上|
|投影／时序|四depth×两种透视×三jitter，共24组合／3960像素CPU对照；源运动、共同原点迁移、负时间seek、暂停、空世界新帧|同上|
|原生 lease／GL|三槽耗尽／归还、49×33→51×35；GL8帧，4个奇数网格，144×96宿主、3格式，合成maxError=0|`710-native-integration-test.log`|
|离线 GPU SDK|离仓同DXIL、编译失败保留旧manifest、额外t12拒绝、坏DXIL拒绝／恢复；ABI随最终shader刷新|`710-native-extension-offline-final.log`|
|同算法性能|1280×800、64源、2 GI、24预热＋120测量；精确剪枝前后GPU P50 34.787296→12.585856 ms；直接光／GI共6144000分量逐位一致|[性能记录](quality/rtxpt-performance.md)|

日志均在 `build/`。上述开发运行不是最终 JAR 身份；shader／ABI 稳定后，最终入口从打包 JAR 再执行必需测试。性能计数与计时分开，GPU测量不与重构建并行。

## 原修复清单与完整 API 映射

|需求|实现／验收入口|适用边界|
|---|---|---|
|A1／A5 原有正确性|保留完整RTXPT；native／budget／advanced／frame-preparation及组合回归|当前Windows x86_64设备与声明支持组合|
|A2 常驻解析灯|稳定记录／空间索引增量；StructuralLighting测试0至160k及混合更新|测CPU apply／submit，不以历史18秒计算跨轮倍数|
|A3 预编译|canonical key、源／编译器／binary哈希；六预算；host／analytic／small-point三分支严格覆盖|PlanOnly不代表编译完成；VerifyOnly及严格packaged测试才构成包证据|
|A4 完整submit／OFF|并发monitor、frame前失败、长scope、OFF／恢复；DEEP复用现有采样器时立即采样|不把队列timestamp叫shader忙时；丢失／覆盖／pending公开|
|A6 一次性交付|四模块、native／PDB、runtime SDK、许可、离仓消费者、SHA-256|GitHub及MavenLocal由实际命令完成后才报告|
|原第3节 worker|任务分块与独立完成条件；native task-batch检查worker1／2／4／8、0／1／3／63／64／256／4096／100k、串行参考及异常|窗口消息泵有界等待，不引入固定完成轮询下限|
|原第5节输出尺寸|逻辑视口／光照网格／物理面分别描述；奇数尺寸和50次resize压力；2560×1600逻辑／1280×800实测|宿主DPI／跨屏操作仍由真实宿主窗口集成验收|
|C01 模式／协商|完整、两种宿主归属、独立原生；普通工厂与完整专家重载；冲突提前拒绝|有限gain不假装RR；支持情况按实际信号协商|
|C02 主表面|8面split／10面augmentation，GPU写入；primary／fill复用，材质／identity／有效性校验|独立原生不要求这份输入；特殊投影与透明续追按声明处理|
|C03 分层／合成|真实BSDF AOV、按需分配；归属过滤；H×gain＋GI；straight RGB＋coverage|每项颜色域／emission／fog一次；raw AOV不冒充独立RR|
|C04 紧凑源|64位ID、增量树、RGB可见group-max／channel-max／sum、欧氏／L1、遮挡后选择；精确贡献上界剪枝|不取最近N源，不删被挡最强源后的可见次强源|
|C05 场景／历史|retained资源／基准／源增量；实际revision／clock；变换与材质更新参考|几何未变的属性更新不重建BLAS|
|C06 双向GPU|ready／completion fence、三槽、输入与输出generation、GL生产／消费示例|声明的同适配器D3D12／OpenGL Win32互操作；不泛称跨GPU可用|
|C07 质量／重建|分拆组合RR；独立原生漫反射GI的实际RR与运动guides；raw选项|RR在光照网格执行DLAA，宿主空间重建保持底图；gain不送RR|
|C08 诊断|CPU/GPU scope、完整submit、generation、射线／源查询计数、OFF／SUMMARY／DEEP|异步有界留存；显式capture只用于验收|
|C09 失败／恢复|拒绝不覆盖有效配置；扩展坏制品恢复；输入已提交后的安全退休；空世界与重复session|物理设备拔除／驱动丢失不由普通成功运行证明恢复|

## 原生 N01–N09 与 E01–E12

|需求|实现与验收|
|---|---|
|N01／E01 独立启动|不创建第二个Vulkan RT世界，不要求beauty／宿主G-buffer；原生core／empty-world检查|
|N02／E02 网格投影|独立网格；24组合CPU射线逐点GPU对照；目标1280×800实际计数1024000 primary|
|N03／E03 基准|逐顶点两项基准、16×16查找插值、逐实例变换；更新影响结果且不重建几何|
|N04／E04 角色|receiver／shadow／bounce独立，离屏贡献、背面／双面、visibility／fog、alpha矩阵|
|N04／E07 姿态|矩阵row-major及TRS修复；旋转／非均匀缩放、inverse transpose、烘焙参考、opaque快路等价|
|N05／E05 直接光|动态D与全体L分开；RGB三组合、两衰减、解析灯、光源增删与恢复|
|N05／E06 一跳GI|有限预算、确定性半球、有色反弹、albedo一次、源／几何关停恢复、visibility／fog数值参考|
|N05／E08 扩展|固定SDK ABI、离线DXC、反射／哈希、应用数据／clock运行期生效、失败保留有效pipeline|
|N06／E09 按需输出|默认双HDR；独立辅助面；compact格式／量化／转换真实元数据；应用buffer范围patch|
|N07／E09 调度|原生compute跳过完整beauty／material pipeline baker／后处理／present；公开执行／工作计数|
|N08 重建|明确每个信号的网格、编码与实际重建范围；GL双边引导合成示例，保持宿主底图|
|N09／E10 时序|sequence／revision／publication／clock身份；新源运动、seek／暂停、共同原点迁移、新空世界|
|E11 生命周期|三槽持有／归还、在途resize、扩展拒绝恢复；最终压力10session／50resize|
|E12 成本|同fixture的native优化及真实旧Vulkan adapter均已执行；[对照](quality/lighting-backend-comparison.md)记录图像误差与原始时间；[本批优化](quality/native-lighting-performance.md)记录600帧结果；高外部占用下速度倍率不判定，宿主内部耗时不冒填|

## T01–T20 最终包任务映射

以下是验收入口映射；执行结果以该次全量日志及result.json为准，不能仅根据本表或源码中的PASS字样判通过。

|编号|最终验收|
|---|---|
|T01 协商兼容|API契约、普通／专家、native／host配置拒绝与旧完整入口|
|T02 GPU主表面|HostSurface raw、RR与10面HostAugmentation|
|T03 主命中复用|实际supplied／fallback／fill计数与对应新帧图像|
|T04 尺寸清晰度|独立网格、奇数尺寸、projection矩阵、GL合成及目标E12|
|T05 归属颜色|HostSurfaceTransport的emission／direct／ambient归属；Augmentation黑底／颜色域／fog|
|T06 阴影彩光|原生source semantics、结构灯、稀疏源、roles／alpha、完整RTXPT回归|
|T07 GI|native-matrix GI解析参考、材质／源／几何动态恢复|
|T08 反射折射|HostSurfaceTransport与原完整native／budget／advanced／composition|
|T09 稀疏源|160k／200批、GPU参考、组合／衰减及可见性|
|T10 解析灯|StructuralLighting全规模0至160k与增量计数|
|T11 资源增量|retained场景／基准／材质／实例更新，不以接受revision代替新图像|
|T12 RR引导|HostSurfaceRr、BUILD／FILL复用、packaged frame preparation与完整RR路径|
|T13 历史|原完整frame preparation、原生time／origin、新输出时钟与revision|
|T14 worker|task-batch真实native selftest与串行／异常／诊断参考|
|T15 严格预编译|RequireReleaseCoverage、六预算包、独立缓存packaged测试，禁止runtimeDXC|
|T16 尺寸交接|input取消／提交退休、输出三槽耗尽、在途奇数resize与GL|
|T17 生命周期|NativeLightingStress的10session／50resize，加原有lease／关闭／恢复|
|T18 诊断|DiagnosticMonitor、OFF／恢复、CPU／GPU完整scope及新帧关联|
|T19 动态负载|NativeLightingStress连续动态≥60秒、混合编辑≥120秒；结果保存独立JSON|
|T20 完整回归|verifyApiClosure包含Vulkan／NVIDIA／RTXPT／gallery／composition／离仓Maven消费者及制品校验|

## 最终执行与解释

`verifyApiClosure --no-parallel --max-workers=1 -PrtxptTestPackaged` 不接受选择器或排除任务。测试使用隐藏窗口、Java25、无daemon、10GiB进程树；重构建与GPU测试串行。新SDK包括三个示例、完整相应说明、固定DXC及ABI清单；native compute二进制与PT permutation分别准备。

宿主DPI／跨屏、宿主实际consumer／重建成本、真实设备丢失恢复和旧后端同算法对照有各自边界，不能据本库小场景成功泛称全部通过。发布后的制品身份以本次全量报告及Maven/Git实际提交为准；性能只报告[量化记录](quality/rtxpt-performance.md)中的实测范围。
