# 旧 Vulkan 与 RTXPT 的 SR 输入对照

修复对象是渲染器的光照信号与重建契约，不按 Demo 名称、球的位置或灯光比例选择实现。
输出 2560×1440、输入 1485×835、Balanced SR、1 SPP、FG 关闭。

| 环节 | 旧 Vulkan | RTXPT 当前生产路径 | 本轮判断 |
| --- | --- | --- | --- |
| 模型与运行库 | Streamline 2.14.16、DLSS 310.9.16 | 相同官方版本 | 不是旧模型更清楚；JAR 的双方 DLL 已校验 |
| SR 前历史 | 合格表面最多融合 8 帧 | 纯 SR 合并当前路径追踪结果 | 旧路径关闭这层后仍稳定，不能当作主要根因 |
| 点光直射 | 逐灯计算 | 合格少灯集合逐灯求和 | 已修复的同类问题；不是 Demo 名称特例 |
| 太阳直射 | 固定方向求值与遮挡 | 方向光先烘进环境图，再参与环境采样 | 信号不同；不能简单再加一个太阳造成重复计光 |
| 环境直射 | 解析漫反射环境项 | 默认取环境分块的平均 radiance | 上游已有逐方向取真实环境纹理的编译选项，先做隔离 |
| BSDF 逃逸到环境 | 旧积分模型 | 通常取环境 MIP 0，第二次漫反射后取较低 MIP | 必须与 NEE 的环境目标和 MIS 一起核对 |
| 曝光输入 | 显式 1×1 固定曝光纹理 | 内部自动曝光 | 已测固定曝光只带来小改善，仍未改变正式默认 |
| 光照负载 | 稳定的解析环境与直接光 | 完整的随机环境积分和间接反弹 | 不能删除 RTXPT 的反弹或光源来假装修复 |

源码定位：`gpuscene_lighting.glsl::gsEvaluatePbrSurface`、
`Rtxpt/Sample.cpp::UpdateLighting`、`EnvMapBaker.hlsl::ComputeLightContribution`、
`PolymorphicLight.hlsli::EnvironmentQuadLight::CalcSample`、
`PathTracer.hlsli` 的 environment miss、`Sample.cpp::PostProcessAA`。
RTXPT 的生成源固定在上游 1.9.0 提交 `303b408ab81790a5051abd7573830b7a4112eb8e`。

## 同材质隔离结果

同一静止金属场景，同一底板区域 `(780,180)-(1780,1260)`，连续四张完成帧：

| 方案 | 连续帧 RGB MAE /255 |
| --- | ---: |
| 旧 Vulkan，历史融合开启 v134 | 0.051629 |
| 旧 Vulkan，历史融合关闭 v135 | 0.042891 |
| RTXPT，当前默认 v136 | 0.584063 |

这个指标证明 RTXPT 输入中的残余随机光照仍明显，不等于单独证明 SR 集成有错误。
旧路线和 RTXPT 的光照积分模型并不完全相同，不能把二者像素误差算成 RTXPT 正确性误差。

## 当前隔离候选：真实环境方向取样

仅诊断开关 `RTR_RTXPT_DIAGNOSTIC_EXACT_ENVIRONMENT=1`：

- 使用 NVIDIA 原生 `NEE_AT_SAMPLE_BAKED_ENVIRONMENT=0` 分支；仍用原生光源选择、BSDF、遮挡与 MIS。
- NEE 和 BSDF 环境求值都使用 MIP 0，避免同时混用环境平均值和较清晰的逃逸环境。
- 不更改太阳大小、灯光强度、材质、路径深度、SPP、SR 输入分辨率和模型。
- 不增加降噪、锐化和画面读回阶段；每个环境候选会多一次原生纹理取样，性能必须实测。

该候选尚未验收，不是已完成的通用微糊修复。若噪声、动态细节或 FPS 退化则撤回，保留证据。

v137/v138 因 DXC 宏定义冲突未产生画质数据，已修为替换上游已有定义。
v139 冷启动首次编译候选时，364 次提交只有 209 个原生完成帧，静止截图重复，
**其局部分析器输出的 PASS 作废**。画质测试和光源族回归现改为每次提交后确认原生帧计数增长，
未完成时只推进同一姿态，不能继续物理仿真或截图；超时直接失败。

v140 因 DXC 内部异常未产生数据；重试 v142/v143 均完成 364 个实际原生帧。
逐方向环境求值：边缘 2.406992→2.432333 px，静态帧间 MAE 0.760473→0.795619/255，
静态参照 MAE 5.559873→5.746483/255，没有改善，因此候选接入代码已撤回。
不能仅因源代码中有平均值近似，就断言它是本轮微糊根因。

## 下一隔离：NEE 样本序列

上游 `SampleSequenceGenerator` 已实现 Burley 的 hash-based Owen-scrambled Sobol，
并用于 BSDF；NEE 仍使用白噪声序列。本轮只把已有原生序列接到 NEE 候选和 reservoir 的随机输入。
光源选择、PDF、MIS、遮挡、太阳烘焙与反弹都保持原样；完整候选数和遮挡射线数不变。
使用独立 effect seed，并以 `fullSamples*candidateSampleCount` 划分样本索引，
避免与 BSDF、反馈、Russian roulette 的随机输入耦合；沿用上游漫反射次数截止策略。
诊断开关 `RTR_RTXPT_DIAGNOSTIC_LD_NEE=1`，默认未启用。
此项是全光源类型的候选，不依赖少灯资格或任何 Demo 身份；仍需测量其计算开销。
