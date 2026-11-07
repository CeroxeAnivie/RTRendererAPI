# 当前视觉恢复结果

2026-11-07 后续主线程已实现独立解析通道：隐藏 2560×1494 动态玻璃球实测 231.965 原生 FPS，FG off，提交 P95 4.8992 ms。代码根因、通用入口、三组对照表及未签收项见 [独立解析光路恢复](analytic-visual-recovery.md)。下文保留更早候选记录，不能覆盖最新执行状态或代签人工验收。

日期：2026-11-07。本文整理现有报告，不重跑GPU、不构建、不发布，不重复历史审计。当前目标是人眼可接受的旧观感、正确性与高性能；不要求逐像素或所有渲染数值相同。正常的原版量化差异不自动算视觉缺陷，真实错误也不能因不追求逐值一致而忽略。

四场均完成到limit-reached，failure=null，执行最终GPU drain，报告与15张PNG完整。它们证明这些模式能跑完并留下输出，不代表用户已经认可画面，也不证明消费方移动180FPS恢复。全部初始GPU为31%–45%，FPS只作忙GPU下观测；没有同条件7.x基线。

## 1. 静止／移动FPS表

本表新值为报告completedNativeSubmitFps：暖机后的完成提交数／wall时间，包含capture读回、材质／预设切换、HUD和最终drain。它不是显示器present/generated FPS，也不是MC摄像机快飞FPS。balls为动态球体场景；optics是预设切换，不能冒充移动负载。未单独测静止窗口，因此静止值全部N/A。

| 场景／窗口 | 旧FPS | 新FPS | 绝对差 | 变化 | 初始GPU | 暖机后帧／wall秒 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 各场静止窗口 | N/A | N/A | N/A | N/A | — | 未单独采样 | 未测，不补零 |
| balls SR／无去噪动态窗口 | N/A | 135.554660 | N/A | N/A | 39% | 240／1.770504 | 忙GPU观测，无修复收益结论 |
| balls SR＋RELAX动态窗口 | N/A | 134.449435 | N/A | N/A | 45% | 240／1.785058 | 忙GPU观测，无修复收益结论 |
| balls DLSS RR动态窗口 | N/A | 136.837459 | N/A | N/A | 45% | 240／1.753906 | 忙GPU观测，无修复收益结论 |
| optics 六预设／实际RR切换窗口 | N/A | 137.435578 | N/A | N/A | 31% | 600／4.365682 | 忙GPU观测，无修复收益结论 |

每场先暖机120帧：三材质balls各360帧、每材质120帧，故FPS的240帧窗口覆盖后两材质，不代表首个glass材质独立FPS；三张PNG仍覆盖全部材质。optics共720帧、六预设，600帧窗口不包含首个mixed预设的独立稳态测量。不得把这些整窗结果拆出未测的逐材质／逐预设FPS。

模式之间可列观察差，但不是旧新版本收益，不能解释成去噪器本身造成的因果变化：

| 观察A | 观察B | A FPS | B FPS | B−A | 相对A |
| --- | --- | --- | --- | --- | --- |
| balls SR／off，GPU39% | balls SR＋RELAX，GPU45% | 135.554660 | 134.449435 | -1.105225 | -0.8153% |
| balls SR／off，GPU39% | balls DLSS RR，GPU45% | 135.554660 | 136.837459 | +1.282799 | +0.9463% |

## 2. 修复项与实际状态表

没有合格旧测量的字段为N/A；文字前后状态不是性能改善量。专项PASS只覆盖所列契约，不转签完整视觉。

| 项目 | 旧值／旧状态 | 新值／实际证据 | 绝对差／变化 | 当前判定 |
| --- | --- | --- | --- | --- |
| 首submit前标题设置 | setWindowTitle抛Submit first，首场未正常开始 | root改为缓存title、创建后应用；javac1.110s完成；随后四场完整结束 | N/A | 启动故障已修并复验；不属于FPS收益 |
| 唯一内部补丁：非交互progress | N/A（未保留同条件旧窗口计数） | summary.progress.pass=true，state=[0,0,1,0,-1,0,0,1]；专项wall0.562s／treeCPU0.531s／Job提交峰0.666GB | N/A | 专项PASS；仅ProgressBarStart的nonInteractive guard，interactive保持原样；不代表全部窗口或画面验收 |
| direct masked receiver scalar | N/A | 每状态25点；opacity0.49999时receiver Z=0，0.50001时Z=0.99999994..1；material-only update | N/A | 独立direct专项PASS；原生beauty masked anyhit scalar缺口仍在 |
| direct masked shadow | 首夹具选择空response LUT且零sky baseline，exit1；不能当合法旧值 | 纠正夹具为现有classic gain mode1，未改renderer：clear=1，blocked=0.03997802734375，expected0.04，tol0.002，25点／状态，2帧 | N/A | 复验PASS；保留首轮失败及原因，不计修复收益 |
| emission原版量化 | 原rawFloatEquality断言exit1；input=[0.0003,0.1234567,3.14159]，GPU中心=[0,0.129150390625,3.12890625] | 原版RGB10 log＋fp16端点CPU解释通过25/25点，expected=[0,0.1292724609375,3.12890625]，非新增GPU运行 | N/A | 正常原版实现；不要求raw逐值一致，不因此宣称整体人眼等价 |
| 原生去噪状态 | N/A | SR/off：DLSS、standalone=false、0对象；RELAX：DLSS、standalone=true、3对象；RR：DLSS_RR、standalone=false、0对象 | N/A | 实际模式／启用／初始化已读到；不是逐帧GPU执行计数 |
| 场景与输出覆盖 | N/A | balls三模式各360帧／2次材质切换／3PNG；optics720帧／5次预设切换／6PNG；四场failure=null | N/A | 运行及产物完整；人眼旧观感仍需判断 |
| 实际尺寸／原生分辨率含义 | N/A | 四场window/framebuffer/display=1600×1000，render=800×500；FG关闭，原生SPP1 | N/A | optics-native仅是目录名：argv无--native true，实际是RR半分辨率，不能标原生分辨率对照 |
| 其余旧视觉恢复 | 无同条件完整旧新对照 | finite fade、coverage、host-primary等仍未完整验证；full-float不再要求raw逐值相同，但需判断实际观感和正确性 | N/A | 未整体PASS，不以纯上游出图／局部PASS替代 |

唯一补丁专项使用的DLL SHA-256：37e0ed7648d5c1649c4bae56ea5b8c1668581e14f6d8f79f711f92ffac87cbc4。官方导出仍为RTXPT303b408ab81790a5051abd7573830b7a4112eb8e，20仓库／4426原文件、无额外源文件、officialExportUnmodified=true；构建副本唯一progress差异另有manifest。这里引用专项身份，不冒充四场各自已经做了额外二进制身份审计。

## 3. 步骤延迟表

单位ms，mean与P95分别列。旧值、旧新差和百分比均N/A，因为没有同条件旧版本基线。submitCall是API提交调用wall；consumerFrameThroughCompletedSubmit还包含提交前场景更新及selector等工作。两者是嵌套范围，不能相加。

| 场景／步骤 | 旧mean | 新mean | mean绝对差 | mean变化 | 旧P95 | 新P95 | P95绝对差 | P95变化 | 样本 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| balls SR／无去噪／submitCall | N/A | 3.472721 | N/A | N/A | N/A | 4.972200 | N/A | N/A | 240 |
| balls SR／无去噪／consumerFrame→submit完成 | N/A | 3.853748 | N/A | N/A | N/A | 5.160300 | N/A | N/A | 240 |
| balls SR＋RELAX／submitCall | N/A | 4.357168 | N/A | N/A | N/A | 5.918700 | N/A | N/A | 240 |
| balls SR＋RELAX／consumerFrame→submit完成 | N/A | 4.882135 | N/A | N/A | N/A | 6.555000 | N/A | N/A | 240 |
| balls DLSS RR／submitCall | N/A | 4.222505 | N/A | N/A | N/A | 5.963500 | N/A | N/A | 240 |
| balls DLSS RR／consumerFrame→submit完成 | N/A | 4.726185 | N/A | N/A | N/A | 6.330900 | N/A | N/A | 240 |
| optics 六预设／实际RR／submitCall | N/A | 4.715402 | N/A | N/A | N/A | 7.210100 | N/A | N/A | 600 |
| optics 六预设／实际RR／consumerFrame→submit完成 | N/A | 5.256990 | N/A | N/A | N/A | 7.333600 | N/A | N/A | 600 |

四场延迟样本均全保留、droppedSamples=0，P95为nearest-rank。capture读回与提交后的HUD/state query不在上表局部延迟样本内，却在第1组FPS的wall时间里；因此不能简单对mean取倒数当本场FPS。诊断级别均OFF，VALIDATION/TRANSLATION/RESOURCES/LIGHTING/RTXPT_FRAME内部边界与GPU忙时未采集，不能补成0或从submit总量硬拆。

下面仅列不同模式之间的观察差，不是版本优化收益；GPU初始负载不同，另含模式自身工作差别：

| 步骤／SR-off→另一模式 | A mean | B mean | 差ms | 差% | A P95 | B P95 | 差ms | 差% |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| submitCall→balls SR＋RELAX | 3.472721 | 4.357168 | +0.884447 | +25.4684% | 4.972200 | 5.918700 | +0.946500 | +19.0358% |
| consumerFrame→balls SR＋RELAX | 3.853748 | 4.882135 | +1.028387 | +26.6854% | 5.160300 | 6.555000 | +1.394700 | +27.0275% |
| submitCall→balls DLSS RR | 3.472721 | 4.222505 | +0.749784 | +21.5907% | 4.972200 | 5.963500 | +0.991300 | +19.9368% |
| consumerFrame→balls DLSS RR | 3.853748 | 4.726185 | +0.872438 | +22.6387% | 5.160300 | 6.330900 | +1.170600 | +22.6847% |

冷启动单次观测单列，不冒称mean/P95，也不混入稳态样本：

| 场景 | renderer.open单次ms | coldFirstSubmit单次ms | 含启动/关闭总秒 | 旧值／差值 |
| --- | --- | --- | --- | --- |
| balls SR／无去噪 | 316.5892 | 7023.5572 | 10.2053460 | N/A |
| balls SR＋RELAX | 312.8517 | 9575.4014 | 12.8399530 | N/A |
| balls DLSS RR | 306.7999 | 9929.8539 | 13.2746398 | N/A |
| optics 六预设／实际RR | 314.4021 | 13669.7653 | 19.4496580 | N/A |

冷首submit约7–14秒确实存在，但本场diagnostics=OFF无法把它继续归因到shader编译、PSO、NGX或其它内部阶段；不能套之前某一热点充当本轮已测根因。

## 4. 原始证据与产物

- balls SR／无去噪：build/reports/visual-recovery/20261107-130838-balls-sr/balls-sr.json；同目录gpu-before.txt与3张PNG。
- balls SR＋RELAX：build/reports/visual-recovery/20261107-130849-balls-relax/balls-relax.json；同目录gpu-before.txt与3张PNG。
- balls DLSS RR：build/reports/visual-recovery/20261107-131001-balls-rr/balls-rr.json；同目录gpu-before.txt与3张PNG。
- optics 六预设／实际RR：build/reports/visual-recovery/20261107-131016-optics-native/optics-native.json；同目录gpu-before.txt与6张PNG。
- 进度／masked／shadow／emission专项：build/reports/upstream-material-progress/20261107-130139/summary.json。
- 唯一内部补丁manifest：build/upstream-rtxpt/native/rtrenderer-patches/progress-noninteractive/manifest.json。
- 7.0.0之后历史与当前缺口：docs/quality/visual-migration-audit.md；本报告不重复该历史审计。

本次只整理已完成产物。没有新的运行、测试或发布，也没有代替用户作人眼验收。

## 2026-11-07 补充：旧包参考与原生质量候选

旧正式 7.2.1 fat JAR 已在隐藏后台真实运行并输出参考图：

a) build/reports/visual-recovery/20261107-131348-legacy-reference/legacy-glass.png；800×500 → 1600×1000，SR Performance，1 SPP，无 NRD/RR/FG，SCENE_ANALYTIC，4 个 exact optical interfaces。旧观感能够由留档包复现。

b) build/reports/visual-recovery/20261107-131914-balls-power-quality/；3 材质 360 帧，exit0、failure=null，POWER + RR Quality，1200×750 → 1600×1000，1 SPP、FG off。初始 GPU 38%，仅作观测。玻璃内部细节仍柔化，整体发白，没有达到旧观感；此配置不设为默认。

| 指标（忙 GPU 观测，非同条件性能收益） | 原 RR Performance | POWER + RR Quality | 绝对差 | 变化 |
| --- | ---: | ---: | ---: | ---: |
| 动态场景完成提交 FPS | 136.837459 | 71.109611 | -65.727848 | -48.03% |
| submit mean ms | 4.222505 | 8.717333 | +4.494828 | +106.45% |
| submit P95 ms | 5.963500 | 16.699900 | +10.736400 | +180.04% |
| 静止／MC 快飞 FPS | N/A | N/A | N/A | 未测 |

这不是原 POWER 采样单因素测量，重建输入像素由 40 万增至 90 万；不能把变化归因给 POWER。采集包含截图、材质切换、HUD；暖机后的 240 帧覆盖后两材质，不能冒充独立 glass 稳态性能。

代码对照确认：旧、新 packed base color 都是 /255，玻璃 roughness .025、IOR 2.618/F0 .2 和吸收输入相同。旧 SCENE_ANALYTIC 以 RtrSceneDirect.hlsli 提供自有有限距离衰减、确定性少灯遍历和透射阴影；RtrSceneLightingInputs.hlsli 提供自有光学分支。新原生 beauty 没有调用这些定制；相同输入数值不等于相同渲染路径。这是已证实的结构差异，不把它直接说成每项视觉退步的唯一实测原因。当前不以提高 RR 质量或浮点逐值匹配替代修复。

新规则禁止直接恢复旧 RTXPT 内部补丁；不禁止独立桥接修复。只有编译进度窗这一处内部补丁已获准。尚未证明每项旧定制均有原生等价公开入口，也不宣称已完整迁移。以人眼观感、正确性和完整负载性能决定交付，正常原生精度差异不单独阻断。
