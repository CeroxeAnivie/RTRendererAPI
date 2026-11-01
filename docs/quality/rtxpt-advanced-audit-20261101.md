# RTXPT 高级功能与时域输入审计（2026-11-01）

本次针对 RTXPT 弹球 SR 画面的模糊、拖影、噪声和旧路径性能差距复查。只采用仓库实际调用链和本机官方 SDK 的说明；没有把请求开关当作执行证据。审计代理没有运行 GPU 测试、原生构建或旧 Demo，也没有单独提交代码。GPU 验收由主线程串行完成。

## 本次已落代码

| 项目 | 发现与处理 | 验证状态 |
|---|---|---|
| RR/NRD 能力报告 | 通用 `DENOISING` 原来固定写 `nvidia.nrd`，RR 实际执行时也会错报。现按实际 standalone 状态报告 NRD 或 RR，和具体技术项一致。 | CPU 模拟 RR/NRD 与禁用状态通过 |
| 原生场景换相机 | 上游加载场景会重设相机，Java 仍保留旧相机缓存。再次提交同值 API 相机时会跳过应用。现 `loadNativeScene` 成功后清相机缓存，下次提交重新应用。 | 上游场景加载与提交条件已核对；硬件回归待主线程 |
| RELAX + SR 入口 | 原策略只要开启降噪且不启 ReSTIR 就强制 RR，无法单独选择原生 NRD + SR。现 `DenoisingOptions.Strategy.RESPONSIVE` 选 RTXPT 原生 RELAX，随后执行 SR/DLAA，不顺带开启 DI/GI。BALANCED/STABLE 保持原 RR 默认。 | CPU 策略合同通过；实际图像/耗时待主线程 |
| 实际 RELAX 反馈 | JNI 读取实际 standalone 状态和 `NRDMethod`，在 `RuntimeStatus.relaxDenoiser` 报告是否真的走 RELAX；高级硬件验收已增加 `dlss-relax-sharc` 组合和实际方法断言。 | 已编译测试源码；硬件组合尚未由本代理运行 |
| REQUIRED SR/DLAA | 原校验只要求 AA 非 NONE，TAA 降级也能通过 REQUIRED SR，甚至用残留 DLAA mode 冒充 DLAA。现同时核对实际 AA 家族和实际 DLAA mode；TAA 不再通过 REQUIRED DLSS。 | CPU 模拟 TAA 降级通过 |
| TAA 能力标签 | DLSS 不支持时不再把 TAA 写成 DLSS ACTIVE；仅用户明确允许 BUILT_IN_TEMPORAL 时报告 `nvidia.rtxpt.taa` FALLBACK。 | CPU 能力反馈合同通过 |

### RELAX 接入边界

- 仅新增向上游传递 `NRDMethod=RELAX/REBLUR`，没有新增自研降噪或调整 NRD 数学逻辑。
- 降噪策略变化仍由 `RtxptFeatureController` 要求重建 renderer；不能在既有实例中偷偷重建整套管线。
- 不把上游 `NRDModeChanged` 长期置 true。这个字段在官方 UI 中每帧由 Combo 更新，长期置 true 会反复清理 NRD 并请求重建。
- 本次 JNI `settings` 增加一个 boolean；`runtimeStatus` 数组从 19 项变为 20 项。Java 和 native 必须一起重新构建，不能混用旧 DLL。
- `relaxDenoiser=true` 只在 standalone NRD 实际启用且方法为 RELAX 时成立，RR 或禁用路径不会假报 RELAX。

## 高级功能逐项核对

| 能力 | 请求→上游→实际反馈 | 本次结论 |
|---|---|---|
| DLSS SR | options 选择 AA=2；上游设置模式、查询输入尺寸并评估 DLSS；runtime 读取实际 AA、mode 与 render size | 已核对。不能仅凭 SR 名字认定两条路径的 DLL、模型及输入一样 |
| DLSS RR | options 默认 AA=3；在后续时域消费前准备当前帧颜色、法线、反照率、深度与运动信息 | 原合成先后问题由主线程修复；本审计不重复改这个块 |
| DLAA | native AA 请求映射 DLAA mode，实际必须还是 DLSS/RR 家族 | 已修验收误认 TAA 的漏洞 |
| 原生 NRD | standalone 开关和实际 AA 家族共同决定执行；Denoise 按 REBLUR/RELAX 创建 NRD | 已补 RESPONSIVE→RELAX 映射，实际表现待测 |
| ReSTIR DI/GI | options 选择 standalone NRD + DLSS；上游 `ActualUseReSTIRDI/GI` 决定真正执行，JNI 读取 Actual 值 | 已核对；仍限制 1 SPP，不能通过强开它们绕出 NRD 入口 |
| SHaRC | 请求传 `RadianceCacheMode`，反馈读 `ActualRadianceCacheMode`，不把参考模式中的配置误报执行 | 已核对 |
| SER | 创建时决定 `--noSER`；实际反馈读上游 hit-object 执行状态；开关变化要求 renderer 重建 | 执行状态已核对。支持字段沿用 `IsSERSupported()`，它还含 `!disableSER`，目前不是纯硬件可用性字段；这点不代表实际 SER 执行错误 |
| Reflex | settings 设置模式，反馈读 ActualReflexMode；FG 可按上游要求强制启用低延迟 | 已核对。用户未请求的通用 feature 仍按公共合同显示 DISABLED，runtime 可看到 FG 导致的实际低延迟状态 |
| FG 2×/3×/4× | live settings 在 native 帧边界应用；上游检查最大生成帧数；resize/loadScene 先暂停 FG，尺寸更新后恢复 | 调用与生命周期已核对；实际生成交付仍应读 SDK telemetry，不按请求倍率推算成功 |
| FG 统计 | SDK `numFramesActuallyPresented` 累计是真实 SDK 呈现；生成帧拆分用 SDK 总数减 host Present，可能有一帧相位差 | 官方指南与调用点已核对；不能把两种计数完全同相作为测试前提 |
| 自适应 FG | required 拒绝，preferred 回退原生呈现 | CPU 现有合同已覆盖 |
| NIS/spatial | required 拒绝，preferred 规范化为禁用，不伪装上游已有接口 | 保持明确收缩 |
| RTXMU | 属于普通 Vulkan 后端，RTXPT required 拒绝，preferred 规范化为禁用 | 保持明确收缩 |
| 关闭/顺序重开 | create 重置 UI 状态；关闭先 GPU idle，再 Streamline shutdown 并释放 app；重载与 resize 处理 FG | 源码已核对，先前硬件日志不是本次修改后的验证结果 |

## 动球→运动向量→Streamline 的证据链

| 层 | 对应位置 | 核对结果 |
|---|---|---|
| Java 动画 | `RtxptRenderer.updateTransforms` | 仅变换改变时发送一个 native batch；不因此重导出静态场景 |
| JNI 变换 | `rtxpt_jni.cpp` 的 `RtxptNative_transforms` | 按 API 行主序矩阵构造 Donut affine，分解后调用节点 SetTransform，保留上游 dirty/刷新机制 |
| 场景刷新 | `Rtxpt/Sample.cpp` 的 EarlyUpdate/SceneRefresh | Render 内调用一次 `m_scene->Refresh`，没有发现 host 提前刷新而丢上一帧变换 |
| 上一帧变换 | `External/Donut/src/engine/SceneGraph.cpp` 的 `SceneGraph::Refresh` | 先保存旧 global/float transform，再更新当前 local/global；停止运动后的 PrevTransform dirty 也继续刷新 |
| 实例上传 | `External/Donut/src/engine/Scene.cpp` 的 `Scene::UpdateInstance` | 同时上传 current transform 和 prevTransform，前者供当前世界位置，后者供上一帧位置 |
| 世界运动 | `Shaders/PathTracerSurfaceBridgeDonut.hlsli` 的 `Bridge::loadSurface` | 当前点用 transform，上一帧点用 prevTransform；不是仅相机运动 |
| 屏幕运动 | `Shaders/SampleConstantBuffer.h` 的 `ComputeMotionVector` | 使用两帧 NoOffset 矩阵，输出 previous-current 像素差，不混入采样 jitter |
| SL 常量 | `Sample::PostProcessAA` 与 `StreamlineIntegration::SetConstants` | jitter 为当前实际像素偏移；mvecScale=1/renderSize；motionVectorsJittered=false；投影/reprojection 不含 jitter |

上述链路符合本机 Streamline 官方 `ProgrammingGuideDLSS.md` 对像素运动向量、缩放和无 jitter 矩阵的要求。**本轮没有找到足以证明某一处运动向量输入错误的证据，因此未凭猜测修改它。** 这不等于已经完成运动画面的定量 GPU 验证；噪声、反射内容运动和遮挡变化仍须用运动图像验收。

`ResetAccumulation` 也不能单独当作实时拖影根因：上游实时本来每帧重置参考累积；NRD/RR 历史另由 `ResetRealtimeCaches` 控制。本次没有为消除拖影而删除正常相机更新。

## 旧路径差距的已证事实与待核对项

1. v5 现有 GPU 分段证据中，纯 SR 的 `DenoiseSpecHitT` 为 **0.18894 ms/帧**。调用在 `AdvancedSample.cpp` 无条件发生；可见消费者是 NRD 准备、RR guides、调试图。纯 SR、无 NRD、无调试时没有消费者，属于能直接排除的冗余两次滤波 dispatch。主线程已收到此候选；本审计不改其上游/Build 脚本。**NRD→SR 仍需要它，不能随便砍。**
2. 本机旧 Streamline 目录的 `nvngx_dlss.dll` 是 **310.7.0.0**，RTXPT 运行目录的是 **310.9.16.0**。两者都请求 default preset，不足以证明实际模型相同。这是需要控制的调用链差异，尚未证明它造成多少毫秒差距。
3. RTXPT v5 的 SR GPU 段约 **1.47867 ms/帧**，是其本轮第一名；旧 Vulkan 现有总 trace 计时没有把 SR 单独拆出来，不能拿整段相减就宣称某个 API 或 JNI 慢了 70 FPS。主线程正在补旧路径低开销分段，按用户新授权重测。
4. 生成的 `Sample.cpp` 中出现多份相同 FG 超倍率 fallback guard，提示 Build 脚本注入需要幂等。已报告主线程。它不是本轮 70 FPS 的已证根因，不能夸大收益。

## 本次执行的 CPU 回归

命令：`gradlew.bat :renderer-rtxpt:rtxptAdapterSelfTest --offline --console=plain`

结果：**PASS**。包含 Java 主源码和全部测试源码编译，以及新增 RR/NRD 标签、RESPONSIVE→RELAX→SR、不隐式启 ReSTIR、TAA 不能假通过 REQUIRED DLSS、明确 TAA fallback 标签等合同。

尚待主线程完成：native 重建后的高级组合实际执行、场景切换同值相机、SR/RELAX 动画画质、实际 FG 交付与关闭异常、最终关闭诊断的性能数据。不能把这份源码/CPU 审计当作上述 GPU 验收已全部通过。
