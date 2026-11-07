# 原生光照数值验收矩阵

> 历史记录：本文保留 7.x 路线与验收记录，不是 8.0.0 当前用法。当前原生优先的两模块接入见[文档首页](Home.md)和[项目 README](../README.md)。

状态：CPU 参考与五组真实 GPU 数值矩阵通过。`710-directed-matrix.log` 记录 GI、roles、pose、projection、time 的实际 PASS；最终发布仍以同版 packaged 全量结果为准。

入口是 `RtxptNativeLightingMatrixNativeSelfTest`，每个用例都提交新 sequence，读取这次实际 GPU 信号，并核对输出包的 revision/clock。数值读回仅用于验收，不进入正常交付帧。PFM 底部起始行显式转为公共投影使用的左上原点。

|参数|覆盖与断言|
|---|---|
|`gi`|2 样本、距离 6、强度 .35；bounce-only 大平面、白色有限源与 CPU 确定性射线参考；反弹红转绿、receiver albedo 减半结果恰好减半、visibility/fog 一次应用、源关停/恢复、反弹几何移除/恢复；固定输入新帧逐位一致|
|`roles`|前景 shadow-only 不成为 receiver 但实际遮挡后景；排除角色；单面背面不投影/双面投影；真实 alpha 贴图 255→0→255 的遮挡变化；淡入/fog 修正；切换前景 receiver 后真正变更 primary；相机可见半空间外遮挡物仍投影；receiver 背面 cull/双面规则|
|`pose`|倾斜法线+旋转+非均匀缩放，与 CPU inverse-transpose 及烘焙几何参考比较；opaque fast path 与强制完整 candidate 检查逐位比较|
|`projection`|四种 depth、常规/非对称透视、三种 jitter，2560×1600/97×65 逻辑视口与 33×25 独立网格；以公开 CPU `rayForPixel` 和可插值二维基准的解析值逐点对照，不只检查命中|
|`time`|源移动确实改变当前新帧；源/相机/实例一起原点迁移后保持同一光场，不重建 BLAS；负时间 seek/暂停的时钟身份、新执行计数；空世界发布当帧中性输出|
|`cpu`|不启动渲染器，校验 GI 解析参考、albedo 线性与四种深度投影的中心射线|

所有用例只用通用场景、材质、实例、光源和时钟 API，不依赖游戏类型、存档、模型解释器或另一套 RT 场景。`time` 不代替已有扩展时钟 GPU 测试；`roles` 的单 texel alpha 动画不代替复杂图集/蒙皮覆盖。`pose` 需要诊断捕获函数支持正式 NORMAL_DISTANCE 的 RGBA32_FLOAT 格式。

## 运行

已导出测试 classpath 时，可用脚本只编译或按组运行；脚本不重建后端、不联网下载、不自动运行全量回归：

```powershell
./tools/Test-NativeLightingMatrix.ps1 -CompileOnly
./tools/Test-NativeLightingMatrix.ps1 -Cases cpu
./tools/Test-NativeLightingMatrix.ps1 -Cases gi,roles -RuntimeDirectory build/rtxpt-sdk/bin -BridgePath build/rtxpt-sdk/bin/rtrenderer_rtxpt.dll
```

脚本将 GPU 进程隐藏并纳入 10 GiB 进程树上限。开始前须确保没有其他重构建或 GPU 测量。最终制品验收使用隔离 classpath 中的发布 JAR，省略两个 native override，开启严格预编译要求；开发目录运行结果不冒充最终包证据。

Gradle 可加入如下轻量入口，由统一 JavaExec 配置管理 JVM 参数和 packaged 模式：

```kotlin
tasks.register<JavaExec>("rtxptNativeLightingMatrixNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
    args(providers.gradleProperty("nativeMatrixCases").getOrElse("gi,roles,pose,projection,time").split(","))
}
```

实际结果：GI 参考 0.705863962、GPU 0.705566406；姿态与烘焙参考比较 491 个表面像素；opaque 与完整 candidate 路径逐位一致；24 个投影组合共比较 3960 个像素；源移动、原点迁移、负时间 seek、暂停和空世界均执行新帧并通过断言。

`RtxptNativeLightingStressNativeSelfTest` 另覆盖 60 秒连续动态、120 秒混合编辑、10 session 与 50 次 resize。该测试接入最终全量入口，结果独立保存于 `build/reports/rtxpt-710-stress/result.json`，没有该次结果时不能称持续压力通过。
