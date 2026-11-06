# 7.2.0-rc.7：独立 GI RR 网格预算候选

真实宿主 rc.6 的持续 GPU 大头为光照 dispatch 与每帧全光照网格 RR；原光照／重建共同使用同一网格。此版本新增通用 `lightingReconstructionScale(1|2)`，默认 1 保持原行为，显式 2 只减少 GI RR 评价网格。直接光、阴影、gain、原始一跳 GI 与两张 HDR 发布尺寸保留；调用方可另选择 GI samples，API 不带宿主尺寸或场景特化。

当前帧 raw GI 和同一 receiver 的必要 guides 一致降采样后，真实 DLSS RR 以低网格 DLAA 执行。再按当前 instance/geometry、距离、normal、roughness、albedo、validity 约束上采样；无兼容点使用当前 raw GI，coverage 精确保留。奇数尺寸向上取整，motion 保持 normalized，jitter 使用实际评价尺寸。比例、网格尺寸或显式 reset 重置历史；输出 lease 与命令列表的 GPU 生命周期不变。

`lightingReconstructionGrid()` 提供实际评价／输出尺寸和显式纹理逻辑字节；不把不透明网络内部分配算作已知值。GPU 分别记录降采样、真实 RR 和上采样时间。比例 2 是采样／画质预算候选，不与 rc.6 的等价资源修复混为同一批收益。

构建和定向正确性已通过。默认 divisor1 的 raw/GI 与真实 RR 各 54 份 HDR 捕获均与冻结 rc.6 逐字节相同。divisor2 的 32 帧实际低网格专项、16 帧投影专项通过：覆盖奇数尺寸、四种 depth 和两种 jitter 约定、normalized motion、比例／尺寸往返、reset、开关及旧输出 lease；直接 gain 与 divisor1 逐字节相同，544982 个 exposed／invalid 像素的 GI 精确为零。128 像素合成边缘专项中，身份、距离、normal、albedo 不匹配的回退和 partial alpha 检查均为零错误。这些是 API 专项，不代表宿主画质验收。

同 rc.7 DLL、719 mesh、1280×800 双 HDR、GI1、真实 RR、SUMMARY、禁用信号复用，按 divisor1／2／2／1 各采 3 秒。初始 GPU 为 4%／1%／0%／0%，计时覆盖准入前资源提交直到输出。合并全部逐帧样本：

| 完整请求 GPU | divisor1 | divisor2 | 新减旧 | 变化 |
| --- | ---: | ---: | ---: | ---: |
| mean ms | 6.318164 | 3.865595 | -2.452569 | -38.82% |
| P50 ms | 6.092383 | 3.736383 | -2.356000 | -38.67% |
| P95 ms | 7.726624 | 4.769087 | -2.957537 | -38.28% |
| P99 ms | 9.046527 | 4.844032 | -4.202495 | -46.45% |
| maximum ms | 9.676352 | 4.896639 | -4.779713 | -49.40% |

四窗吞吐依次为 151.79／258.28／257.46／164.17 FPS。A1 与 A2 有变化，全部样本保留；这是短时、同一候选的预算对照，不是完整矩阵或真实 MC FPS。divisor2 改变 GI 重建细节，不能称为等画质加速。完整请求 P95 仍未达到 4 ms。SUMMARY 分阶段 GPU profile 在这四窗未启用，阶段文件为空；完整请求 timer 独立有效，不据此编造 RR、降采样和上采样各自成本。原始证据与汇总在 `build/reports/resource-root-followup/rc7-check/`。

主线随后明确改为 direct-only（GI0、RR NONE），此版本按既定要求仅收尾四模块 Maven Local 与制品身份，不追加 GI/RR 优化或 GPU 测试。低网格接口保留为默认关闭的通用能力，不代表主线采用。当前未证明 MC 移动 180 FPS，未推 GitHub。
