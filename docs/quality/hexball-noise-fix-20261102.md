# RTXPT 弹球噪声与细节修复

## 当前验收状态（v113）

**尚未达到“相同参数、保持旧帧率并消除过度发糊”的要求。** 下文 RR Quality 是历史缓解实验，不能当作 SR Balanced 同配置修复通过。当前锁定 2560×1440 输出、1485×835 输入、1 SPP、SR Balanced、FG 关闭；金属球与玻璃球分开比较。

实际局部灯光表在每个重复槽位存储整段长度。原条件采样重复累加段长，使采样概率与返回的 PDF 不匹配；现按物理槽位计数并使用上游查找恢复概率。CPU 穷举覆盖重复槽位、128/256/512 容量和空残余分布。金属球同配置 v106→v112 为 163.545443→177.551655 FPS（+8.56%），尚未恢复旧 v22 的 249.338277 FPS。

v110→v111 的玻璃球静止帧间 MAE 为 0.714707→0.688227/255，但相对 v113 的 32-SPP exact-MIS 参照，静态误差 4.960173→5.335115、运动误差 6.018126→6.389126，不能宣称画质已改善。阴影边缘检查 PASS 只代表该局部细节未超阈值，不代表整幅画面或拖影验收通过。

v108/v109 GPU 计时确认主要差异在 PathTrace：自定义条件采样 2.9685 ms，上游采样对照 0.9127 ms。下一候选是对照旧快版本使用的上游 power sampling，保持正确的残余采样概率；该候选尚未验收，不改变 Demo 默认值。

## 结论

噪声来自弹球场景在 1 SPP 下的路径追踪照明方差；原先纯 SR 不做去噪，环境光和间接反射都把采样噪声直接留在画面里。原生 DLSS RR 把静止画面的帧间 RGB MAE 从 0.714 降至约 0.071/255，噪声源得到压制。Balanced 输入档仍会把玻璃内部的小细节轻微抹软，因此弹球 Demo 默认改为 RR + Quality 输入档。没有改灯光能量、材质、场景或 RTXPT 的内部实现。

RTXPT 默认 RR preset F 保留。preset E 的同场景复测没有改善清晰度，故不采用。默认 2× FG 保留；以下画质与原生 FPS 对照均关闭 FG，避免把生成帧当成渲染帧。

## 同场景对照

场景为相同的玻璃弹球、相机、环境、灯光和 1 SPP。清晰度比较采用同一组 32 SPP 参考帧。`shadow edge` 是弹球青色阴影的 10–90% 边缘宽度，数值越低越锐；静态时域 MAE 越低，连续静止帧闪动越少。

| 配置 | 输出 / RTXPT 输入 | 1 SPP | shadow edge | 静态时域 MAE | 移动帧对 32 SPP MAE |
|---|---:|---:|---:|---:|---:|
| RR Balanced，preset F | 2560×1440 / 1485×835 | 是 | 2.140 px | 0.071/255 | 4.182/255 |
| RR Quality，preset F | 2560×1440 / 1920×1080 | 是 | 1.991 px | 0.054/255 | 4.189/255 |
| RR Balanced，preset E | 2560×1440 / 1485×835 | 是 | 2.330 px | 0.068/255 | 4.265/255 |

Quality 相对 Balanced 把阴影边缘宽度收窄约 7%，静态时域误差降低约 23%；移动帧误差基本持平。它不是锐化滤镜，也没有引入自制降噪器。E 档在同分辨率下边缘反而宽约 9%，因此继续使用上游默认 F。

## 性能代价

RTX 5080 Laptop GPU、1080p 输出、1 SPP、RR、FG 关闭，同一玻璃弹球场景各测三轮：

| 输入档 | RTXPT 输入 | 三轮 FPS | 中位 FPS | 相对 Balanced |
|---|---:|---:|---:|---:|
| RR Balanced | 1114×626 | 156.85 / 171.60 / 170.66 | 170.66 | 基准 |
| RR Quality | 1440×810 | 122.41 / 123.94 / 126.34 | 123.94 | -27.4% |

Quality 档增加输入像素数，带来约 124 FPS 的原生 1080p 吞吐。交互 Demo 仍请求 2× FG；生成帧数不计入上表。

2.5K 输出下，另以实际呈现计数验证 Quality 档的 FG/MFG 生命周期。以下是 360 个原生帧窗口，包含生成帧的呈现吞吐；1× 行用于同次运行的无 FG 原生对照：

| 输出 | 模式 | 实际生成帧 | 原生 FPS | 实际呈现 FPS |
|---|---:|---:|---:|---:|
| 2560×1440 | 1× | 0 | 62.05 | 62.05 |
| 2560×1440 | 2× | 360 | 52.31 | 104.63 |
| 2560×1440 | 3× | 720 | 46.78 | 140.35 |
| 2560×1440 | 4× | 1080 | 38.74 | 154.94 |
| 1920×1080 | 4× | 1080 | 67.08 | 268.31 |

## 验收边界

RR Quality + 1 SPP 通过实际运行时档位检查、静止与移动帧捕获、弹球生命周期检查。它是当前 Demo 的有效缓解，不宣称已经消除任意面积光、环境光和间接反弹场景的采样噪声；那些仍需按统一配置单独验收。帧图实际位于忽略的 `build/reports/hexball-quality/`；参数、日志、GPU 初始占用和分析 JSON/PNG 在 `data/hexball-native-20261101/`。Balanced 与 Quality 的分析为 `residual-glass-rr-quality-v80-analysis`，preset E 对照为 `residual-glass-rr-preset-e-v82-analysis`；实际 FG/MFG 计数为 `residual-glass-rr-quality-generation-v83-generation.csv`。

## 最新重建对照与收口（v86–v89）

统一条件：玻璃弹球、2560×1440 输出、1 SPP、FG 关闭；静态/运动/停止帧序列相同。32 SPP 只作为画质参照。边缘宽度越小越锐，静态时域 MAE 越低代表静止画面越稳定。

| RTXPT 路径 | 阴影边缘 10–90% | 静态时域 MAE /255 | 静态参照 MAE /255 | 运动参照 MAE /255 | 结论 |
|---|---:|---:|---:|---:|---|
| RR Quality，1 SPP | 1.991 px | 0.0544 | 3.6679 | 4.1888 | 当前最佳综合默认 |
| RR + DLAA，1 SPP | 2.068 px | 0.0716 | 3.6230 | 4.2065 | 原生尺寸没有改善整体表现 |
| RR Quality + SHaRC，1 SPP | 2.039 px | 0.0700 | 3.2096 | 4.0503 | 更接近参照，但时域稳定性较差，综合不替换默认 |
| DLSS SR Quality + NRD RELAX，1 SPP | 10.769 px | 0.0523 | 3.4561 | 5.0537 | 明显抹宽阴影并损害运动细节，淘汰 |

v89 的 RELAX 结果已由分析器确认不是“只少一点噪声”：边缘宽度是 RR Quality 的 **5.4 倍**，运动参照误差也高约 **20.7%**。静态闪动略低不能抵消细节损失，因此不启用 RELAX。DLAA 与 SHaRC 的数据也没有推翻 RR Quality 作为 Demo 默认的决定。

RTXPT 1.9 的 RR + Ultra Quality 组合曾进入 native 崩溃路径。适配层现在在创建/更新 native renderer 前直接拒绝这一组合，并提示改用 RR Quality 或 DLAA；普通 DLSS SR 的 Ultra Quality 仍保留。CPU-only adapter test 覆盖两边的行为。

结论按实际证据收口：弹球 Demo 当前采用 **RR Quality + 1 SPP**，已把此前严重噪声/拖影压到可用画面，且不再使用会糊边的 RELAX。纯 1-SPP 路径仍存在环境/间接反弹的残余采样误差，RR 也会轻微平滑细节；本轮没有证据支持“所有光照与动态场景的噪声完全消失”，所以不把这个状态写成全场景、零残噪。对应完整对照图和原始运行记录为 `data/hexball-native-20261101/residual-glass-rr-dlaa-v86-*`、`residual-glass-rr-quality-sharc-v87-*`、`residual-glass-relax-quality-v89-*`；多类光源整帧检查为 `rtxpt-rr-quality-v88-*`。

## 输出合成修复后的 ReSTIR 复核（v90–v96）

在 RTXPT 无降噪合成修复之后，用同一打包 JAR、1920×1080 输出、1 SPP、FG 关闭，复测上游 ReSTIR 路径。画质参考为同一混合玻璃/面积灯场景的 32-SPP 图；参照 MAE 不能单独区分噪声与积分偏差，因此同时保留连续帧差异和平均亮度。默认 RR Quality 的相同场景数据来自 v88。

| 路径 | 重建/去噪 | 整图参照 MAE /255 | 连续静止帧 MAE /255 | 原生 FPS | 对 RR Quality 的判断 |
|---|---|---:|---:|---:|---|
| RR Quality | DLSS RR + Quality | 1.2213 | 0.0387 | 136.69 | 基线 |
| ReSTIR DI | DLSS SR Quality + NRD | 13.7819 | 0.0478 | 108.76 | 明显偏亮、误差大，慢 20.4% |
| ReSTIR GI | DLSS SR Quality + NRD | 1.4921 | 0.0190 | 134.66 | 连续帧更稳，但参照更差，慢 1.5% |
| ReSTIR DI + GI | DLSS SR Quality + NRD | 13.8964 | 0.0418 | 93.73 | 偏亮、误差大，慢 31.4% |
| ReSTIR GI + SHaRC | DLSS SR Quality + NRD | 1.4171 | 0.0185 | 130.16 | 仍比 RR 误差大，慢 4.8% |

这些数据说明低帧间波动不等于画质更正确；当前 RTXPT 1.9 的 ReSTIR 组合没有解决这张混合图的残噪，DI 路径还明显改变了能量。它们不替换 RR Quality 默认，也不把“上游功能状态显示 ACTIVE”当成验收通过。

完整 ReSTIR DI + GI + SHaRC 在 shader 编译期触发 RTXPT 1.9 内置 DXC 访问冲突，没有产出帧或有效 FPS；分拆后 DI+GI、GI+SHaRC 都能完成混合场景。现 `RtxptOptions` 在进入 native 前拒绝三者齐开，CPU adapter test 覆盖被拒组合和仍可用的两组配对。原始记录为 `rtxpt-full-reuse-quality-v90-*`、`rtxpt-reuse-di-mixed-v91-*`、`rtxpt-reuse-gi-mixed-v92-*`、`rtxpt-reuse-di-gi-mixed-v94-*`、`rtxpt-reuse-gi-sharc-mixed-v95-*`；逐帧对照图与指标为 `rtxpt-postmerge-mixed-*-v93/v96.*`。
