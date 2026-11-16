# 官方完整渲染与解析光照扩展

API 的通用渲染基于 NVIDIA RTXPT v1.9.0，锁定提交 `303b408ab81790a5051abd7573830b7a4112eb8e`。官方源码及子模块导出只读；外部适配负责 Java 输入、场景身份、生命周期和公开配置。构建副本仅保留经审计的非交互进度窗和网络策略差异。

## 选择入口

```java
try (Renderer renderer = RtxptRenderers.openOfficial(RendererConfig.pathTracedPresentation())) {
    // 提交通用 SceneTransaction、SceneLight 和 RenderFrameRequest。
}
```

解析光照作为长期保留的显式入口与官方路径并存：

```java
var config = RtxptRenderers.analyticConfiguration();
try (Renderer renderer = RtxptRenderers.openAnalytic(config)) {
    // 与官方路径一样，使用通用场景和帧事务。
}
```

`openAnalyticHiddenWindow` 提供同一解析路径的隐藏窗口形式。配置工厂保持现有弹球的 1 SPP、SR、关闭随机降噪/SHaRC/ReSTIR 等策略；曝光、路径深度、窗口尺寸及 FG 仍由调用者按公开配置指定。`openAnalytic` 要求显式 ANALYTIC_DIRECT，并在加载原生库前拒绝不兼容配置，不自动删掉调用者要求的功能。

调用方（包括未来的 MC 接入）在创建 renderer 时自行选择入口，不按场景名称自动路由。输运模式固定在该 renderer 的配置中；切换时应结束原会话，正确释放资源/输出租约后重新创建，不把两条管线在同一个时序历史中热切换。解析 beauty 与 `openNativeLighting` 的独立增益输出仍是不同契约，不能混称为一个模式。

`openOfficialHiddenWindow` 执行相同的原生渲染与呈现，只隐藏窗口。默认 `Renderer.open()` 的配置也是 NATIVE；显式 official 入口额外封闭自有光照扩展，防止后续误切换。

| 能力 | official 会话 | 显式扩展会话 |
| --- | --- | --- |
| 完整输运 | 原始 AdvancedSample 的 PathTrace、Denoise、重建及后处理 | NATIVE 时同左；ANALYTIC_DIRECT 时使用外部解析输运 |
| 场景、共享网格与实例、材质、灯、介质 | 保留通用 API 输入，映射到 Donut/RTXPT | 保留 |
| RR、NRD、SHaRC、SER、FG | 经配置与硬件能力选择；不承诺每个组合可用 | 解析输运不声称支持其未执行的功能 |
| NativeLightingControl / SparseLightingControl | 不暴露、不接受 | 单独选择并遵守其契约 |
| ANALYTIC_DIRECT | 打开前拒绝 | 允许显式选择；属于本项目扩展 |
| 逐灯关闭阴影 | 拒绝非零光强请求 | 按扩展契约执行 |
| 不支持的功能 | 报错或不提供扩展 | 不用占位结果冒充支持 |

官方入口不会把传入的扩展配置静默改成另一种模式。原生会话也校验输运选择，Java 与 DLL 的 ABI 必须一致。`executionState()` 返回原始渲染钩子的完成调用数，区分官方完整管线、RR guides、解析光路与独立光照；这些是执行证据，不是 GPU 毫秒、呈现次数或插帧数，且可包含初始化帧。

## 官方主线的含义

参照 [README](https://github.com/NVIDIA-RTX/RTXPT/blob/303b408ab81790a5051abd7573830b7a4112eb8e/README.md) 与 [Design](https://github.com/NVIDIA-RTX/RTXPT/blob/303b408ab81790a5051abd7573830b7a4112eb8e/Docs/Design.md)：完整纯路径追踪、共享几何与实例、静态 BLAS 加载时构建、动态形变适时 refit、逐帧 GPU TLAS，以及官方 NEE、路径分解、可选重用、降噪和后处理。统一输运并不表示整帧只有一个 dispatch。

IntroSample 是教学入口，官方明确不以性能与最终画质为目标，不能以它替代 AdvancedSample 作为生产性能依据。推荐模式也不保证某一场景必定快于专用算法。

## 画质修复的保留边界

| 既有修复或目标 | official 路径的处理 | 验收边界 |
| --- | --- | --- |
| 反射、折射、粗糙度、IOR、RGB 吸收、嵌套优先级 | 保留材质及介质映射，使用官方 BSDF 与嵌套介质 | 用介质 demo 的六个预设检查；出图不等于定量等价 |
| 闭合球、显式法线、三种球材质 | 保留场景几何和材质 | 同场景检查玻璃、非金属和金属；运动时序另验 |
| 完整精度法线、非均匀缩放、UV/切线 | 保留通用输入转换与原生 GPU 表 | CPU 数据契约和原有 GPU 法线差分检查；不降低精度换帧率 |
| 透明裁剪、材质域修改、首个 masked 材质入场 | 保留首 live masked OMM 与完整材质重导入修复 | 原生像素断言覆盖入场、透明洞和移除后重新加入 |
| 无降噪时本帧 radiance 先合并再 AA | 保留对官方 NoDenoiserFinalMerge 的调用 | 不重写输运，避免黑帧或上一帧输出 |
| 曝光、尺寸、当前帧身份、关闭和资源寿命 | 保留桥接修复 | 查看实际原生状态和帧身份，不以请求值冒充生效 |
| 前若干玻璃界面确定性 Fresnel 双分支 | 只在 ANALYTIC_DIRECT 扩展保留 | 官方随机输运没有相同的零噪声承诺，不能标为等价迁移 |
| 彩色透明阴影与可见性全反射修复 | 解析扩展保留 RGB 可见性近似 | 官方 bool visibility 不是同一算法；需要单独验光学结果 |
| 只采镜面 lobe、抑制解析路径的漫反射颗粒 | 扩展内保留；官方执行完整输运 | 亮度、间接光、噪声与时序外观可能不同 |
| 自有 pre-SR history / 宿主响应比 / 光照增益输出 | official 不宣称提供等价功能 | 不把换名、RGB 相减或旧帧复用算支持 |

当前弹球默认保留 ANALYTIC_DIRECT，介质默认使用完整 NATIVE 路径。两种算法及显式入口并存；`--transport scene` 使用当前 demo 默认，`--transport official` 选择完整官方路径。官方路径不会覆盖已验收的弹球默认值，也不作为删除解析路线的理由。两种图像的亮度、间接光和阴影输运本来可能不同，简单像素相等不适合作为统一标准。

## 验收方式

源码检查、DLL 构建、真实执行、画质与性能分别报告。两个 demo 应使用相同输出尺寸、SPP、路径深度和 FG 状态比较。带 capture 的场次只用于画质；无读回场次报告预热后的完整消费帧时间、P99、最大帧、最终 GPU drain 与进程内存，并保留原始计时边界。

运动玻璃在 1 SPP 下可能出现随机噪声及 RR 时序失真。静态截图、去噪启用标志或很小的帧间变化，都不能单独证明没有拖影或“融化”。官方候选必须通过实际运动画面验收后，才可以替换当前默认。

显式硬件契约入口为 `:renderer-rtxpt:rtxptOfficialSessionNativeSelfTest`，不会随普通 CPU `check` 自动打开图形场景。画质巡检仍由两个 demo 和专项像素测试完成。
