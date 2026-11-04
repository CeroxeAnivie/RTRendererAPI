# 动态资源性能与验收

日期：2026-11-04。版本：7.1.3。交付渠道为 GitHub main 和 Maven Local，不发布 Central。制品身份见 [artifact.json](data/dynamic-resources/artifact.json)，发布校验见 [publication.json](data/dynamic-resources/publication.json)。

## 最终包 CPU 收益

| mesh 数 | mesh 更新 P50，旧→新 ms | 加速 | 新 P95 ms | 纹理更新 P50，旧→新 ms | 加速 | 新 P95 ms |
|---|---:|---:|---:|---:|---:|---:|
| 719 | 354.38 → 2.08 | 170.6× | 6.09 | 71.50 → 2.27 | 31.5× | 3.13 |
| 977 | 486.28 → 3.12 | 155.6× | 7.59 | 92.42 → 3.20 | 28.9× | 6.23 |
| 4096 | 5611.34 → 3.67 | 1530.9× | 7.45 | 135.20 → 3.14 | 43.0× | 5.66 |

旧版为 Maven Local 7.1.2 的实际四个 JAR，新版为最终四个 JAR，运行时从其嵌入 ZIP 解出并核对 DLL。保留了首次局部更新的冷分配／JIT 样本。108 次普通更新仅 2 次超过 7 ms（7.454／7.592 ms）；此次普通更新最大值 7.592 ms，>16.7／50／100 ms 均为 0。P50、P95、P99 与每次 mesh 更新原始值见 [最终数据](data/dynamic-resources/final-result.json)。**P95 ≤4 ms 并未全部达成**；用户允许忽略低频的 7 ms 以下尖峰，超过 7 ms 的实测值仍完整保留。此前已发送的收益表来自补充兼容修复前的预验收，见 preflight-summary.json；此表使用最终包新一轮结果，不拼接各轮最好值。

CPU 计量边界为已接受的 `trySubmit`：含验证、翻译、资源／属性更新及 native frame 命令录制。`apply`、首次初始化、lease 归还和显式背压不计入该值。初始化与背压在运行日志／JSON 单列；它不是整帧 FPS，也不是 GPU busy。基线每类 4 次，新版 mesh 16 次、纹理 20 次，P95／P99 的小样本估计接近最大值，倍率用于同夹具阶段对照。

## 场景与环境

每 mesh 768 个三角形、425 个顶点、两个材质组；719／977／4096 mesh 为 552,192／750,336／3,145,728 个三角形。63 张四 mip 小纹理、17,408 稀疏源；宿主 2560×1600、光照 1280×800、samples=2、独立 HDR gain／GI、实际 DLSS_RR，beauty frame=0。六帧预热、十二稳定帧，随后每批 1～4 mesh 和 1～9 纹理变化。

977 档覆盖原移动重负载的规模，不复刻消费方地形／源空间分布。4096 档检查增长关系。RTX 5080 Laptop、驱动 616.92，开始前 GPU 0%、148 MiB、P8、约 8 W；CPU 与运行期间外部负载未持续隔离。没有重跑旧版本，也没有启动 MC 客户端。进程树内存限制 10 GB，最终运行峰值 3.784 GB，包含 native 与 JVM commit；这不是 GPU 显存峰值。

## 工作量下降

- 一次 1～4 mesh 更新仅转换 2,304～9,216 个角点；16 次总计 92,160，三档相同。977 档旧版每次重转 2,251,008，平均角点工作量减少 99.744%；4096 档减少 99.939%。未变角点转换和实例属性重提为 0。
- 批量属性 JNI 每个 mesh 更新批次一次；常驻身份直接查找。全局属性 clear 仅初始化一次。局部几何上传每 mesh 21,116 B，baseline／RGBA 每 mesh 27,648 B，外加必要表条目；扩容迁移独立计数。
- 普通更新 device drains=0、同步资源等待=0、属性迁移=0、importer／textureFinalize=0、pipeline／shaderTable rebuild=0、RR reset=0。完整初始场景导出单列一次，不把初始化写成无文件。
- 纹理像素相同不再上传，RGB-only 不重建 coverage BLAS；alpha 更新处理真实依赖。GPU 分配与上传命令在 graphics／compute 完成事件后才回收，保留 command-list 自有 staging／scratch。
- 最终运行每档待退休资源峰值见 nativeStatistics（预验收均为两代）；完整池容量和失败语义见 [接口说明](../release-dynamic-resources.md)。

| mesh 数 | 更新阶段 Java 累计分配 B | mesh submit 线程 CPU P50 ms | texture submit 线程 CPU P50 ms |
|---|---:|---:|---:|
| 719 | 3,170,120 | 0.000 | 0.000 |
| 977 | 3,416,760 | 0.000 | 0.000 |
| 4096 | 9,259,280 | 0.000 | 0.000 |

Java 分配为整个 16＋20 次更新累计值，非峰值常驻内存。Windows 短区间线程 CPU 有计时粒度限制，0 不代表没有计算。native 临时分配、GPU live bytes 与消费方完整进程树峰值没有单独采集，不填 0。

## 诊断与 GPU 成本

同一预验收二进制、977 档，不同轮依次切换，未做随机交错；下表体现实际范围，不能用几毫秒的差异推出精确诊断开销比例。

| 等级 | mesh P50 ms | P95 ms | texture P50 ms | P95 ms |
|---|---:|---:|---:|---:|
| SUMMARY | 4.526 | 11.476 | 3.578 | 8.116 |
| OFF | 5.564 | 9.201 | 4.481 | 6.907 |
| DEEP | 3.410 | 7.949 | 3.182 | 6.572 |

OFF 后诊断计数冻结、没有新增 GPU scope；原始 preflight-off.json 的 Java 分配差值 0 表示未采集，非零分配证明。夹具现已输出 null。DEEP 使用 fused dispatch，未开启 receiver／direct／GI 拆分，不能把它写成三段独立 GPU 时间。

977 档预验收 DEEP 的已完成 GPU scope（排除初始化及六帧预热）：

| GPU scope | 样本数 | P50 ms | P95 ms |
|---|---:|---:|---:|
| NativeLightingDispatch | 48 | 83.0322 | 91.4953 |
| NativeDiffuseGiDLSSRR | 48 | 2.3591 | 2.5079 |
| NativeLightingUpload | 48 | 0.0009 | 0.0060 |
| TLAS Update | 48 | 0.1061 | 0.1162 |

此压力夹具的稀疏源空间密度造成很重的 GPU 光照成本，**没有达到整帧 4 ms／180 FPS**。该 GPU 表与消费方约 3.7 ms dispatch 的地形录制内容不同，不能计算跨场景倍率。资源 telemetry 的 GPU queue interval 也不当作 GPU busy。本批确认的主要收益为 CPU 更新停顿消除；宿主高帧率仍需同场景验收。

## 正确性与累计验收

公共 TexturePatch 复制／行距／mip／越界／旧父 generation、719／977／4096 局部准备、retained 依赖及 Vulkan residency 已通过。ABI、版本与无版本绑定命名门禁通过；六预算／1,143 个上游 shader 排列包检查通过。未运行 FG／MFG。

预验收四 JAR 的混合资源、原生 patch 九组、资源矩阵、alpha／顶点色、真实 GI RR、10 session／50 resize 均通过。后续完整 beauty 补验发现并修复 OMM 调试表容量与零实例 TLAS 构建；analytic／raw／RR 图像及空场景恢复通过，DI＋GI 在最终 DLL 上用 256 帧均值保持原误差上限通过（block MAE=0.473405、mean error=0.327668）。没有重跑未受影响的已通过项目，也没有把累计验收写成一次全量全绿。

完整 beauty 的差分计数改为扣除初始化基线；异步纹理池只要求复用已完成资源，不能保留旧版每次原地复用的断言。DI／GI 图像均值增加样本，未放宽原 block<3／mean<1 门槛。原失败记录保留在 tests.jsonl 和对应日志。D3D12 SDK debug layer 未安装，本批不声称完整 GPU validation 通过。完整 beauty 的所有 nearest／clamp 组合未逐项验收。

R01～R08 的通用实现和组合功能已落地；R09 提供 CPU scope／计数与 GPU scope，仍缺独立 native 临时峰值等细分证据；R10 的规模、局部增长和正确性覆盖已完成，但全部性能分位和端到端高帧率目标未全部验收达标。不能把本次交付称为整份消费方验收指标全绿。

## 制品与复现

native DLL SHA-256：`a2ddd307dc41e17a8e0f2f1d8be28eb5058d13163fc06420a55d447450c1300a`。

runtime.zip SHA-256：`61f6652e8113ebce0a2598497dbbb8e582705c6152bcc4f37204a6cfc79524ac`。四 JAR 的大小和哈希见 artifact.json。预验收包身份单列 preflight-artifact.json，功能补验使用的 DLL 身份见 tests.jsonl。最终 CPU 表对应最终制品。

构建：`tools/Build-RtxptBridge.ps1 -StreamlineSdk <SDK目录>`，再运行四模块 jar／publishToMavenLocal。通用夹具为 `RtxptDynamicResourcesNativeSelfTest`，使用 `dynamicResources.level`／`dynamicResources.output` 系统属性，参数 719、977、4096。GPU profile 环境变量必须是输出 CSV 路径；warmup 使用正数，OFF 不生成 scope。复测遵守独占 GPU、相同内容及诊断等级，并保留冷更新样本。

发布时补齐 NVIDIA 模块的既有 native runtime，包闭包校验通过；其全部既有 Java／资源条目与性能夹具使用的包逐项一致。最终 api／core／rtxpt JAR 与被测包 SHA-256 完全一致；性能包四模块身份另存 performance-artifact.json，完整发布身份见 artifact.json。
