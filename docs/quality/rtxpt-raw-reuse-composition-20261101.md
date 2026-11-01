# RTXPT 无降噪颜色合成修复

用户验收：纯 SR 已追上旧路径开启诊断的帧率，但画质仍不合格；暂停 FPS 主线，先修画质。

## 已证实的错误

`quality-reuse-raw-v9` 虽然 runtime 报告 ReSTIR DI 启用、正常关闭，但截图明显变暗。仅检查功能开关和 exit 0 不足以通过画质验收。

沿上游代码能确定直接光丢失的具体顺序：

1. `RTXDI/DIFinalShading.hlsl` 无降噪分支向 `u_OutputColor` **累加** ReSTIR 直接光；`GIFinalShading.hlsl` 同样使用输出颜色累加。
2. `Sample::PostProcessAA` 随后执行 `NoDenoiserFinalMerge`。
3. `ProcessingPasses/PostProcess.hlsl` 的该 pass 用层辐射 **赋值覆盖** `u_OutputColor`，因此刚累加的 ReSTIR 光照丢失。单纯调采样、滤波、锐度都不能修复这个问题。

修复：仅在无降噪 RTXDI 路径中，把已有原生 merge 提前到主追踪之后、RTXDI 之前；后置 merge 在该路径中跳过。保持每帧唯一一次合并，SR/TAA/无 AA 都读取当前完整颜色。NRD/RR 和未启用 RTXDI 的顺序不变。

这不代表用户提出的所有模糊已解决，也不把增加降噪冒充同配置修复。原始 SR、原生 ReSTIR+SR 和 RELAX+SR 分开留图验证。额外 32 SPP 模式仅用于画质参照，不能用于宣称 1 SPP 帧率或验收结果。

## 上游对照

- 固定 RTXPT 1.9.0 源码的 `Docs/Design.md`、RTXDI final shading 和 NRD integration。
- NVIDIA NRD 官方 README 明确 RELAX 面向 RTXDI；候选采用上游重采样与降噪机制，不实现第二套算法。
- DLSS jitter 按本项目实际相机射线的符号推导。新路径的采样偏移与旧路径相反，不能直接照搬旧路径常量负号。

硬件画质结果待本轮截图对照后补充。
