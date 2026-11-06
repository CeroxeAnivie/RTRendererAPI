# 原生双信号的 GI 重建

原生模式现在有两个明确入口：`openNativeLighting(plan)` 保留确定性 raw 输出；`openReconstructedNativeLighting(plan)` 默认启用真实 NVIDIA DLSS RR。两者共用驻留场景和 TLAS。重建入口在第一次光照 dispatch 前检查设备支持，失败会报错，不静默换成 NONE。

```java
var plan = NativeLightingPlan.defaults(hostWidth / 2, hostHeight / 2);
try (var renderer = RtxptRenderers.openReconstructedNativeLighting(plan)) {
    var lighting = renderer.extension(NativeLightingControl.class).orElseThrow();
    // 发布通用场景、相机和源，正常 submit。
    var status = lighting.lightingReconstruction();
    // requested 是配置；actual 只有实际 SDK evaluate 成功后才成为 DLSS_RR。
}
```

RR 只接收 `INDIRECT_DIFFUSE` 的线性漫反射 GI radiance。材质色、纹理色和顶点色在原始光照中乘一次；RR 输出已经包含 receiver 的漫反射颜色，宿主不能再次乘 albedo。`DIRECT_GAIN` 是无量纲增益，不送入 RR。原生底图、透明层、手部和 UI 继续由宿主负责。

SDK 映射依据 Streamline `ProgrammingGuideDLSS_RR.md` 第 4.1 节：

| SDK 输入 | 实际来源 |
| --- | --- |
| ScalingInputColor | 原生一跳漫反射 GI，线性 HDR |
| Albedo | 同一 primary hit 的线性漫反射 albedo |
| SpecularAlbedo | 零；该层没有 specular radiance |
| NormalRoughness | 同一 hit 的世界法线和线性材质 roughness |
| MotionVectors | 当前／上一相机矩阵、同一 barycentric 的上一顶点位置和实例变换，包含相机与物体运动 |
| Depth | 与上述位置一致的硬件深度；负一到一深度先转换为零到一 |
| SpecularHitDistance | 零；该层未执行 specular ray |
| ScalingOutputColor | 独立 RGBA16F RR 目标 |

曝光固定为 scene-linear 的 `preExposure=1`、`exposureScale=1`。运动为 previous UV minus current UV，未包含 jitter，`mvecScale=(1,1)`；相机矩阵不包含 jitter，jitter 单独提交。SDK constants、options、资源 tags 和 evaluate 的返回值逐项检查，只有成功调用才推进执行计数。这里没有调用完整 beauty、NRD、ReSTIR 或第二套光追场景。

RR 使用 DLAA 尺寸模式，在光照网格内完成降噪和时间重建。所有发布 plane 保持同一光照尺寸，随后由宿主空间重建到原生底图尺寸。这个入口不声称已经产生宿主全尺寸的 DLSS 超分结果。`GlGuidedLightingComposer` 示例可使用低分辨率 normal/distance、identity 和可选宿主 guides 做边缘约束；宿主底图始终在宿主分辨率采样。没有宿主 guides 时使用显式空间回退，不要求完整分辨率宿主 G-buffer。

7.2.0 新增可选 `lightingReconstructionScale(2)`：每轴把 GI 的实际 RR 输入／DLAA 输出缩为光照网格的一半，奇数边向上取整。默认值 1 保持原执行路径。这是明确的 GI 重建细节／性能取舍，不是等画质修复；直接光、阴影、gain 以及两张发布 HDR plane 的尺寸和计算不变。每个低网格 texel 从本帧选择同一 primary receiver 的 raw GI、albedo、normal、depth、normalized motion；jitter 按实际评价尺寸换算。真实 RR 之后，以当前 receiver 的 instance/geometry 身份、距离、normal、roughness、albedo、validity 限制四邻点上采样；没有匹配点时使用本帧 raw GI，alpha 精确保留本帧 coverage。没有新引入旧帧缓存、宿主专用数据或全尺寸宿主 G-buffer。

`lightingReconstructionGrid()` 返回 divisor、实际 inputWidth/inputHeight、发布 outputWidth/outputHeight 和这组纹理的逻辑 resourceBytes（不含 Streamline 不公开的网络内部分配）。现有 `lightingReconstruction().width/height` 继续表示发布光照网格。GPU profile 分别记录 `NativeGiRRCurrentFrameReduce`、真实 `NativeDiffuseGiDLSSRR`、`NativeGiRRGeometryUpsample`，reason 是 divisor。低网格仍使用合法同输入／输出尺寸的 DLAA，不静默切换为 raw。切换比例使下一帧重置历史，关闭 RR 停止评价并释放这组纹理引用；先前已提交的命令列表和输出 lease 保留各自资源直到完成。

发布前单独恢复 primary coverage，并清零没有 receiver 的 GI；紧凑输出编码发生在 RR 之后。最终合成是 `hostBase * gain + reconstructedGI`，不得再加 raw GI。coverage 的部分覆盖混合遵守 plane 契约。gain 的采样与重建仍由空间组合器负责，不能将 gain 当作线性颜色重复 RR。

`lightingReconstruction()` 返回 requested、actual、工作尺寸、成功 evaluate 数、history reset 数和 guides 有效状态。初始化前 actual 为 NONE。切回 `NativeLightingReconstruction.NONE` 后停止 RR 调用并释放其纹理引用。空场景直接发布中性增益／零 GI，重置历史。网格尺寸变化和显式 temporal reset 同样重置历史；宿主发生未通过场景或相机描述的跳变时调用 `resetLightingReconstruction()`。

当前重建入口要求请求 `INDIRECT_DIFFUSE` 和默认 fused evaluator。任意自定义扩展的输出没有可自动推断的物理颜色语义，选择扩展或 DEEP 拆分前须先关闭 GI RR。普通全图 SR/RR 的配置继续由原有 feature profile 管理，不能与独立光照层的状态混淆。

定向 GPU 验收已实际完成 28 次 SDK evaluate，并核对 albedo、法线、深度、零 specular guide、相机／物体运动、显式 reset 和紧凑输出。初次验收最后一个 gain 断言因测试移动实例后未恢复夹具而失败，原始记录保留；独立补验在同一场景和相机下验证 gain 逐位不变、raw 停止 evaluate，未重跑前面的通过项。此证据与最终打包验收分开记录。

后续新增 32 帧薄接收面及相机持续运动，3392 个新暴露像素的无接收面 GI 精确清零，最大积分能量误差 0.05918844，无额外绿／蓝贡献；对应成功 evaluate 32 次。统计、解析灯存在性和非加法组容量的八种重建变体另执行八次真实 evaluate，gain、raw GI 和全部 guides 在计数开关之间逐位一致。验收配置、历史失败及最终制品状态见 [修复记录](quality/lighting-repair-validation.md)。
