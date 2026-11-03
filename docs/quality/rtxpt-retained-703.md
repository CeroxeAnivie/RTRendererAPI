# RTXPT 7.0.3 验收

测试日期：2026-11-03。Intel Core Ultra 9 275HX、NVIDIA RTX 5080 Laptop GPU、驱动 616.92、Windows x64、Java 25。基线为已发布的 7.0.2，源码提交 `532940d8435c063f6fa6c997c3a1551fdba20b9c`。RTXPT 上游仍固定在 `303b408ab81790a5051abd7573830b7a4112eb8e`，原生修改由仓库中的 retained-resource patch 重建。

## 审查结论与修复

需求中的单灯更新昂贵、材质同步反复遍历、资源更新重建 pipeline、颜色动画误伤 alpha coverage，以及细微照明变化反复丢失重建历史，在实现中均有对应原因。7.0.3 修复公共场景事务与后端执行路径，不加入消费方专属对象或私有协议。外部文档中的游戏帧率和消费方日志未在本轮重新生成，下面只列本仓库实测。

| 问题 | 实际修复 | 验证与边界 |
| --- | --- | --- |
| 单灯编辑重新生成全部原生灯与索引 | 稳定 ID／类型只提交变化记录，保留原生对象，局部 refit 灯树祖先与 dirty GPU spans | 十万灯中移动一灯只提交一条记录；增删／类型改变仍完整重建 |
| 未变网格反复扫描三角形材质引用 | 按不可变 mesh 身份缓存依赖，替换对象后失效 | CPU 依赖拒绝及替换测试通过；并非所有事务都变成常数时间 |
| 材质／实例变化重复建立全部 PSO | 哈希集合检查成员；shader key 未变保留 permutation，仅映射变化重建 SBT | 完整 shader key／exports 纳入比较，shader／any-hit 改变仍重建 |
| masked 纹理只改 RGB 仍重建 OMM／AS | 比较真实 alpha、尺寸、寻址和材质 coverage 参数 | RGB-only 零 AS／pipeline／SBT 重建；alpha 改变只重建相关 BLAS |
| 日光细微变化不断清空 RR／NRD 历史 | 可查询的 `SceneLightingControl.CONTINUOUS`，真实输入每帧更新，突变自动重置 | analytic 且关闭 ReSTIR／SHaRC；默认仍使用兼容的离散策略 |
| 背景颜色与照明耦合 | 独立主射线 miss 颜色，物体、反射／折射和间接光继续使用环境 | analytic／raw 像素隔离通过；输出仍不透明，不提供宿主天空 alpha 合成 |
| 更新阶段与窗口响应缺少证据 | 实际执行点记录 history scope、pipeline／SBT、阶段时间和窗口 poll 间隔；首次初始化及全量灯循环也处理消息 | 包含 resize／最大化／最小化／恢复；首次创建仍存在秒级工作 |

资源发布仍保守等待旧 GPU 工作，使用稀疏 glTF 导入与文件翻译，没有实现无等待上传。灯树保留全部灯的采样支持，没有裁剪最近 N 灯。连续策略不冻结噪声或忽略小输入，路径积累仍失效；阈值、代码用法和能力查询见 [版本说明](../7.0.3.md)。

## 同负载性能对比

两版运行同一个 `RtxptMixedBenchmark`，基线加载 Maven Local 7.0.2 JAR 和对应旧 DLL。640×360 输出与内部渲染、NONE 重建、1 SPP、path depth 1、diffuse depth 0、SCENE_ANALYTIC、light samples 1，FG／ReSTIR／SHaRC 关闭。场景包含 256 个分别驻留的立方体 mesh、256 个材质、共享 32×32 纹理、100,000 个应用灯（99,999 静态＋1 移动），另有太阳槽。

首次提交单独记录，之后预热 16 帧并等待空闲。每阶段连续提交 16 个样本，计时循环不 readback、不 `awaitIdle`。`apply` 和 `submit` 分开计时，表中是 **submit 墙钟延迟**，包括 CPU 翻译／同步／记录及实际等待，不是纯 GPU 时间，也不能直接换算游戏 FPS。P50 采用两个中间样本的均值；16 个样本的 nearest-rank P95／P99 均为该阶段最大值，样本量不足以估计长期尾延迟。

| 阶段 | 7.0.2 P50 / P95 (ms) | 7.0.3 P50 / P95 (ms) | P50 加速比 | 耗时降低 |
| --- | ---: | ---: | ---: | ---: |
| 稳定帧 | 31.154 / 32.684 | 5.930 / 10.187 | 5.25× | 80.97% |
| 纹理动画 | 164.237 / 207.706 | 57.729 / 81.343 | 2.84× | 64.85% |
| 单网格替换 | 649.316 / 2344.048 | 39.126 / 46.423 | 16.60× | 93.97% |
| 实例增删 | 660.753 / 2362.055 | 38.612 / 45.080 | 17.11× | 94.16% |
| 单灯移动 | 15997.759 / 18418.882 | 26.672 / 31.476 | 599.81× | 99.83% |
| 环境变化 | 62.237 / 65.578 | 25.147 / 27.052 | 2.47× | 59.59% |
| 纹理＋网格＋灯＋环境混合 | 15934.724 / 18345.849 | 109.654 / 120.181 | 145.32× | 99.31% |

112 个预热后样本中，超过 1 秒的提交由 **35 次降至 0 次**；超过 100 ms 由 79 次降至 15 次。混合更新仍有约 110 ms 延迟，尚未达到每帧 16.7 ms 的目标。首次提交由 41.234 s 降至 28.516 s，仅各测一次，包含首次 scene／shader／PSO 等工作，受缓存与调度影响，不能当作稳定性能收益。

额外的 138,457 应用灯测试：稳定帧 P50 6.224 ms，单灯 35.790 ms（最大 43.576 ms），混合 128.830 ms（最大 151.176 ms）；同样 112 个预热后样本没有超过 1 秒。该规模没有独立 7.0.2 基线，不计算加速比。两轮 7.0.3 在预热后实际最长窗口 poll 间隔分别为 **64.451 ms、94.172 ms**，不是以帧耗时猜测消息泵响应。

7.0.3 两次测试起始 GPU 利用率分别为 0% 和 5%；7.0.2 未保存独立的起始 GPU 快照。最终 7.0.3 测量与发行 CPU／核心检查存在时间重叠，调度和少量其他 GPU 工作可能影响结果，未进行随机交替多轮测量。表格描述本次提交延迟，不承诺复杂宿主或其他硬件的相同倍数。立方体测试也不替代大三角形场景测试。

原始 [702 CSV](data/rtxpt-703-702-100000.csv)、[703 CSV](data/rtxpt-703-703-100000.csv)、[138457 灯 CSV](data/rtxpt-703-703-138457.csv) 和 [汇总 JSON](data/rtxpt-retained-703.json) 随源码保存。JSON 还包含 apply P50、P99、各阈值计数、原始行和最终执行阶段快照。

## 正确性与画面

`rtxptRetainedNativeSelfTest` 在 analytic、raw、DLSS-RR、ReSTIR DI/GI 四条路线通过纹理动画、单 mesh／BLAS、实例增删、reset 参考、非法依赖拒绝、空场景恢复与窗口状态切换。每条单 mesh 更新只建 1 个 BLAS，纹理不建 AS，兼容纹理 allocation 复用 8 次。参考条件与 7.0.2 相同：320×180，先稳定 32 帧，再取 48 帧 SDR 均值，要求 16×16 块 RGB MAE < 3、全图均值偏差 < 1。

| 路线 | 块 RGB MAE | 全图均值偏差 | 结果 |
| --- | ---: | ---: | --- |
| analytic | 0.000016 | 0.000017 | PASS |
| raw | 0.158458 | 0.026146 | PASS |
| RR | 0.204042 | 0.000822 | PASS |
| DI/GI | 1.300266 | 0.416921 | PASS |

最终灯树 refit 对 fresh exhaustive 的线性 HDR 对照：相对能量误差 **0.04818%**、相对 RMSE **1.41822%**，通过既定 3%／10% 阈值。此为小灯集高采样质量参考，不能解释为十万灯单帧逐像素等同于完整求和。

连续照明比较固定输入、每帧 1 ULP 强度变化且重置历史、同样变化且保留历史三种情况。每阶段先预热 48 帧，保存 32 帧，ROI 为 `[210,180,430,290]`，统计第 8～31 帧的逐像素时间亮度标准差（0～255 LDR）。噪声持续推进，不使用冻结种子。

| 路线 | 每帧重置标准差 | 连续模式标准差 | 降低 | 连续与固定均值图 MAE / 255 | 边缘能量比 |
| --- | ---: | ---: | ---: | ---: | ---: |
| RR | 0.857342 | 0.213814 | 75.06% | 0.160439 | 0.974916 |
| SR＋NRD | 0.598823 | 0.287556 | 51.98% | 0.353358 | 0.975306 |

连续模式记录 32 次真实变化／上传，重建／NRD 重置为 0；离散参考首帧相同、其余 31 次变化各重置。日光、移动点灯序列的首次切换重置，之后平滑变化保留历史；关灯／明显明暗突变会重置，dark 首帧地板 ROI 已为零，未持续残留旧光。这只验证所测场景的稳定性与响应，不代表任意运动与光照序列都无拖影。原始 [RR history CSV](data/rtxpt-703-continuity-rr.csv)、[NRD CSV](data/rtxpt-703-continuity-nrd.csv) 与 [图像指标](data/rtxpt-703-image-metrics.json) 可核对执行动作与数值。

独立背景在 analytic／raw 均改变 129,023 个背景像素，保持 101,377 个像素完全一致，内部表面区域绝对误差为 **0**。环境改变仍影响场景，但不覆盖指定的 primary 背景。该像素隔离对照冻结噪声，未用于上述时间稳定性结论。透明 coverage 能力明确返回 false，不能以 opaque overwrite blit 保留消费方天空。

## 发行验证与复现

四模块与 Demo 的 check 已执行；API ABI 基线包含新增 extension 并保留旧声明。完整原生回归包含 coverage、连续照明、四条 retained 路线、两种 lighting input 字段测试与全部 17 个预算／预设，执行 19 个任务、8 分 8 秒通过。最终灯树与性能使用最终 Java classes／DLL 重新验证；源码 shader bundle 包含 802 个排列和六档光学预算。最终 JAR 在仓库外独立缓存运行全部 17 个预算／预设案例，禁止 DXC fallback，并检查运行前后预编译二进制未增改；普通 JAR 原生验收也通过 DLSS-RR、FG、场景切换、transform batch、截图和 resize。两个 Demo 已重新打包。整包验收 25 个任务、1 分 49 秒，进程树峰值提交内存 4.682 GB。

```powershell
./gradlew.bat :renderer-rtxpt:rtxptCoverageNativeSelfTest
./gradlew.bat :renderer-rtxpt:rtxptContinuityNativeSelfTest
./gradlew.bat :renderer-rtxpt:rtxptBackgroundNativeSelfTest
./gradlew.bat :renderer-rtxpt:rtxptRetainedNativeSelfTest
./gradlew.bat :renderer-rtxpt:rtxptLightTreeNativeSelfTest -PtreeCase=refit
./gradlew.bat :renderer-rtxpt:rtxptMixedBenchmark -PbenchmarkBaseline=7.0.2 -PmixedCases=100000,702
./gradlew.bat :renderer-rtxpt:rtxptMixedBenchmark -PmixedCases=100000,703
./gradlew.bat :renderer-rtxpt:rtxptPackagedBudgetNativeSelfTest :renderer-rtxpt:rtxptPackagedNativeSelfTest
python -X utf8 tools/Analyze-Rtxpt703.py build/reports/rtxpt-mixed-703/702-100000.csv build/reports/rtxpt-mixed-703/703-100000.csv --output comparison.json
```

原生构建需要按 [运行时分发说明](../runtime-distribution.md) 配置 vendor SDK；消费方使用发布 JAR，无需 SDK。基线必须选择对应旧 DLL，不能把新 DLL 用于旧版测量。测试入口与参数以 `renderer-rtxpt/build.gradle.kts` 为准。

## 修复中出现的问题

最初独立背景按 `vertexIndex == 0` 判断 miss，像素测试失败；上游主射线 miss 实际为 1，修正并重编后通过。最初 NRD 从每帧 reset 直接切连续模式时，32 帧样本混入收敛过渡，标准差约 1.073，高于 reset 参考约 0.602；最终稳定性测量对每阶段统一增加 48 帧预热，不将过渡当作稳态收益。首次原生链接曾因基线进程占用 NRD.dll 失败，基线退出后构建成功。发行版本检查还捕获了四份文档中的旧依赖坐标，已统一为 7.0.3。

版本一致性、major release contract、仓库中立性和许可证最终检查全部通过（28 个任务、46 秒）。最终打包 JNI 与性能测试使用的 DLL 字节一致。制品 SHA-256：

| 制品 | SHA-256 |
| --- | --- |
| `rtrenderer_rtxpt.dll` | `7cf0e664bf8e73f06ce32d5fc58ff924e308ebe2130b0944e0c2914e1f987a46` |
| `renderer-api-7.0.3.jar` | `9a7a927644ee3731f2038b9915647dd48a4b9508d57a3a7d552df4275a9d52bd` |
| `renderer-core-7.0.3.jar` | `3a884fdc8d45759a248df6ede3d2e05588c11c532995f90e380e2b11c6cd9660` |
| `renderer-nvidia-7.0.3.jar` | `d312ff29208f5fc86be46d88b1c838783850646572a1480dbfd4bf399552d757` |
| `renderer-rtxpt-7.0.3.jar` | `4cce2c84dd304c5991b3ea8086b3396514e009029804ad3ed612d1aba7d5c84f` |
| JAR 内 runtime ZIP | `5dcc07c36e16a5ee5c1fb5ed122a1a5a6af1a8939532875629fb59bd607f10b1` |
