# 连续光照首帧初始化修复

2026-11-07，兼容补丁 7.3.3，以正式 7.3.2 为基线。分支仍为 codex/resource-root-followup，不切分支、不新建 worktree、不启动 MC。完整证据见 [JSON](data/continuous-lighting-bootstrap.json)，失败与成功原始日志位于 data/continuous-bootstrap/。

## 真实根因

宿主首帧在 RtxptNative.lightingPolicy→RtxptRenderer.submitFrame→trySubmit 误抛 Continuous lighting requires SCENE_ANALYTIC without ReSTIR or radiance cache。它是 Java 配置 guard 的 native 对应错误，不是 JVM/native crash；尚未采到 MC FPS。

内存场景 LoadScene→SceneLoaded→LoadSceneLighting 的顺序本身成立：私有 memory 标志消费后创建空 graph，SceneLoaded 沿用 Donut/Sample 生命周期，LoadSceneLighting 设置 managedLighting=true；initial pathQuality 在创建 pass 时已设置，Java applyNativeSettings 再应用同一配置。

错误在 SetLightingContinuity 使用 sceneLightingActive() 判断配置资格。该函数除 SCENE_ANALYTIC、managedLighting、无 ReSTIR/cache 外，还检查 sceneLights 非空。Java 按既有契约先设 continuity 再发布本批灯光；新内存 bootstrap/reset 此刻数组自然为空，合法配置就被误拒绝。旧临时 JSON 曾预塞环境 sun 记录，因此掩盖了这一依赖。已有 bootstrap 测试使用默认 DISCONTINUOUS；旧 continuity 测试先 submit，再选择 CONTINUOUS，都未覆盖首帧。

## 最小修复

新增配置谓词 supportsManagedSceneLighting()，仍逐项验证 SCENE_ANALYTIC、managedLighting、实际 ReSTIR 与实际 cache。SetLightingContinuity 改用该谓词；sceneLightingActive() 继续在同一配置谓词上要求非空灯数组。

没有删除 guard、调换 Java 灯光提交顺序或回退 DISCONTINUOUS，没有改变实际 shader 选择、光照公式、空场景执行语义、GI/RR、输出格式或历史策略。Java 主 class 与 7.3.2 逐字节核验，公开 ABI 不变。

## 定向 JNI 回归

同一个新用例先运行正式 7.3.2 JNI，首帧复现相同异常、exit1；随后只重编 JNI，九项通过。两端初始 GPU 均0%。两端都隐藏原生窗口、JVMCI关闭、CICompilerCount=2、MEMORY=0/CENSUS=0。

采用与当前宿主相同的 nativeLightingConfiguration、2 frames in flight、资源准入上限、GPU delivery、DEEP、CONTINUOUS、SCENE_ANALYTIC、direct-only/GI0、HOST_RESPONSE_RATIO_V1、COMPACT_RESPONSE_UNORM8、无全图重建/ReSTIR/cache。夹具为96×64输出、48×32光照与常量有效 host response，专测生命周期，不能代替宿主画质或性能。

|检查|7.3.2|7.3.3|
|---|---|---|
|首帧预选 CONTINUOUS|guard 误拒绝|通过，取得当前 sequence/revision 单 RGBA8 输出|
|empty / populated / repeated empty reset|首帧已失败，未继续|三项通过，策略一直为 CONTINUOUS|
|带 managed metadata 的真实文件|未重复旧版|native policy 通过|
|无 managed metadata 的普通文件|未重复旧版|native guard 正确拒绝|
|文件后回内存空场景|未重复旧版|未发布灯光前即可设置 CONTINUOUS|
|BACKEND 光照输入 / 恢复 SCENE_ANALYTIC|未重复旧版|前者正确拒绝，后者恢复通过|

新用例加入维护工具默认清单与 Gradle 定向入口，本次只运行该用例，不重复已有矩阵。测试初次编译误用了不存在的 getter，已改为 test-only 私有字段观察，重编通过；生产 Java 未改。首轮 package 的 NVIDIA Javadoc JVMCI 编译器单次 access violation，65.594s 后失败；按约关闭 JVMCI、设 CICompilerCount=2 和英文构建消息直接重试，不调查旧 MC 闪退。只保留该 hs_err 前70行，完整环境变量不入项目；其后打包/Local 使用同一调整参数，最终步骤时间另见发布回执。

## 三组结果与限制

|FPS|旧值|新值|绝对差|百分比|
|---|---|---|---|---|
|静止 / 快速移动 MC|首帧失败，未采样|API 线未运行 MC|不适用|不适用|

|改动项|旧值|新值|绝对差|百分比|
|---|---|---|---|---|
|合法首帧 CONTINUOUS|错误拒绝|接受|功能修复|不适用|
|配置 guard / shader active 条件|混用一个谓词|配置资格与非空执行分开|保留全部配置限制|不适用|

|步骤|旧值|新值|绝对差|百分比|
|---|---:|---:|---|---|
|同 JNI 回归 Job|4.922s 后失败|30.662s 完成九项|不比较|失败短路不是性能收益|
|必要 JNI 重编|无同条件旧值|16.521s|不适用|不适用|
|修正后 test compile|无同条件旧值|7.298s|不适用|不适用|

这些是步骤 wall time，没有稳态性能样本，不编造 mean/P95、MC FPS 或优化百分比。Javadoc 重试后，命名检查另发现旧 managed 验证分支与本次新分支在工具源码直接写基线版本；改为由 release 元数据推导上一 patch，保留基线检查，不绕过命名规则。该次10.661s失败也保留在JSON。

最终发布顺序：package-only身份核验→源码/工具/文档 GitHub→四模块 Maven Local→独立 Local 字节回执。运行依赖与 executable shader 锁定正式7.3.2；只交付本批 JNI 修复及测试/文档，不发 Central、不部署、不打开 MC。
