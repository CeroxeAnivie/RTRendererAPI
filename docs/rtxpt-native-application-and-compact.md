# 应用字节补丁与紧凑光照输出

> 历史记录：本文保留 7.x 路线与验收记录，不是 8.0.0 当前用法。当前原生优先的两模块接入见[文档首页](Home.md)和[项目 README](../README.md)。

## 应用数据局部更新

`NativeLightingApplicationPatch(generation, offset, data)` 在构造时复制 `ByteBuffer.remaining()`，不改变原游标；返回只读、独立游标的视图。generation 必须正数，offset／长度为 4 字节对齐，长度非零，范围不超过 16 MiB。控制器再核对已接受 publication 的真实容量和当前离线扩展最低容量。

`patchLightingApplicationData(patch)` 与完整 `publishLightingAttributes` 共用严格递增的 generation 时间线。补丁不能扩容或缩容；格式、范围、容量或 generation 被拒绝时，不改变已接受数据、待上传列表或 generation。扩缩容仍提交完整 publication。

若完整 publication 尚未上传，补丁并入其必要的首次上传；已有 GPU 数据时，只排补丁。native 保留 CPU 字节副本，并合并重叠／相邻 dirty range。提交以 `writeBuffer(buffer, data+offset, size, offset)` 上传实际范围，保留不相邻范围之间的未修改字节。metadata／scene 变更不强制重传整份 application。

示例：4096 字节 publication 的 offset=12 处更新一个 float，仅产生 4 字节 application 上传，较重传 4096 字节少 99.90234375%。这是应用数据上传量，不等于总帧上传量或 FPS。`RtxptNativeLightingApplicationPatchNativeSelfTest` 验证实际扩展输出、上传计数、邻接／重叠、容量保持及拒绝后的恢复；尚未运行时不得作为 GPU 验收结果。

已完成独立 CPU 校验：400 个随机 range 的精确 union、非法范围保持原状态，及 12 项 Java 不可变／对齐检查。可通过 `tools/Test-NativeLightingApplication.ps1` 重现。

## PSO 激活原子性

已有 device 时，`activateLightingExtension` 先验证离线包并建立实际候选 PSO，成功后再切换 Java 的 manifest／hash／signals／最低容量。待上传的 publication 使用其已接受容量进行验证，不为建 PSO 强制上传数据。失败保留原 native PSO 和 Java 合同。首次尚无 device 的激活仍在提交时准备，失败恢复最近 active Java 状态。native 切换前已准备身份字符串及旧 PSO 的退休记录，避免切换后再发生这类分配失败。

## GPU UNORM8 输出

默认 `NativeLightingPlan.Encoding.HDR_FLOAT16`，现有构造器行为不变。调用 `withEncoding(COMPACT_UNORM8)` 才为请求的 DIRECT_GAIN／INDIRECT_DIFFUSE 分配 RGBA8_UNORM 输出，并增加一次 GPU 转换。离线扩展仍写原有 RGBA16F UAV，无需更换其资源 ABI。未请求的颜色输出没有对应紧凑全屏纹理；normal／identity 保留原始精确格式。

gain 低段 `round(128*g)`，高段 `round(128+32*log2(g))`，neutral=128；GI 为 `round(sqrt(clamp(GI/maximumGi,0,1))*255)`。alpha 为线性 UNORM coverage。选紧凑模式时声明最大 gain 不得超过 15.65。消费者按每个 frame 携带的 encoding 与 maximumGi 解码，不能取当前 plan 解码旧 lease。

native-lighting packet ABI 2 保留原 16 项头和每 plane 四项，并尾加 encoding ordinal 与 maximumGi 的 float32 原始位。format wire 4=RGBA8_UNORM。Java 同时读取 ABI 1／2；host-surface 的独立八 plane ring ABI 不受此尾部影响。通用 GL consumer 已支持导入与 GPU 解码。

两张颜色输出的逻辑体积从 16 降至 8 字节／像素，输出 copy 体积减少 50%。生产计算仍保留 HDR working textures，还增加转换 pass；实际分配受 D3D12 对齐影响。这不是总显存减半或 FPS 翻倍的承诺。

`RtxptNativeLightingCompactNativeSelfTest` 在实际 GPU 上执行 100001 输入的编码 sweep，检查 neutral=128、GI=0、alpha、低 gain 绝对误差、高 gain 相对误差、GI 误差，以及真实 compact packet、切回 HDR 和 resize/direct-only。probe 的 readback／GPU wait 仅在该显式诊断入口，生产帧没有这些操作。新 encoder 已通过固定 DXC 的 `cs_6_6 -O3 -Ges -WX` 编译；实际 GPU 验收结果以集成日志为准。

## GPU 时间范围

`NativeLightingUpload` 包围实际资源和参数上传，`NativeLightingDispatch` 包围融合光照 compute，按需的 `NativeLightingEncode` 包围真实转换。receiver／source／shadow／GI 共用融合 shader，不虚构其独立 GPU 耗时。关闭诊断后不开始这些 timestamp query。
