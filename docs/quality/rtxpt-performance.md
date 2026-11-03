# 原生同算法性能记录

下述剪枝数据属于 7.1.0 历史运行。本批 7.1.1 的新优化、600 帧受干扰对照与限制见 [native 性能记录](native-lighting-performance.md)。真实旧 Vulkan adapter 已另外执行，见 [后端对照](lighting-backend-comparison.md)。

日期：2026-11-03。GPU：NVIDIA GeForce RTX 5080 Laptop GPU，驱动 616.92。两轮开始前各三次 GPU 利用率采样均为 0%，隐藏窗口、无 VSync／限帧。GPU timestamp 包含队列调度与抢占。功率上限查询为 N/A，以下为同机同轮观测值。

## 精确最大值组合剪枝

`RtrCanImprove` 先用尚未遮挡的非负 RGB 贡献作为可见贡献上界。只有这个上界不能改善已找到的最大值时，才跳过阴影射线；全体源与动态子集分别检查。物理求和仍评估每个有贡献的源，luminance 平手保留稳定 ID 决策。被挡最强源不能提前淘汰可见次强源。

输入由 `RtxptNativeLightingCostBenchmark` 导出。固定宿主 2560×1600、光照 1280×800、64 个 channel-max 动态光源、L1 衰减、两条确定性 GI 射线、距离 6、强度 0.35、双 RGBA16F。fixture SHA-256：`87f664fb54367f43426778920a3f2e9127b7f87c3fd1e59e6018d04631502697`。每轮 24 帧预热、120 个新帧，计数采样与计时分开。

|观测|剪枝前|剪枝后|
|---|---:|---:|
|光照 GPU P50|34.787296 ms|12.585856 ms|
|P95|40.625184 ms|16.944064 ms|
|P99|43.131520 ms|17.129984 ms|
|max|43.234144 ms|17.162720 ms|
|CPU submit P50|0.967600 ms|0.761400 ms|
|阴影射线|66,718,019|11,069,335|
|primary 射线|1,024,000|1,024,000|
|反弹射线|1,598,694|1,598,694|
|光源候选|68,894,962|68,894,962|
|GPU timestamp 丢失／pending|0／0|0／0|

光照阶段 P50 耗时减少 63.8205%，观测速度比 2.764；P95 耗时减少 58.2917%。两张 PFM 各 3,072,000 个 RGB 分量，逐位一致，maxAbs=0、RMSE=0。没有使用调低采样或复用旧帧取得收益。

原始证据：`build/reports/rtxpt-710-e12-before-pruning/`、`build/reports/rtxpt-710-e12/`、`build/reports/rtxpt-710-pruning-comparison.json`。每轮保留 fixture、环境、CPU／GPU CSV、run.json 和两张实际 PFM；剪枝前源码保留为 `build/710-native-before-pruning.hlsl`。

这是同一个 7.1 原生有限算法的优化前后对照。当时旧后端 E12 adapter 尚未运行；随后补齐的旧后端证据单独列于上面的后端对照，不能将历史剪枝速度比转成旧后端倍数或整机 FPS。OpenGL 消费和宿主重建不在该 GPU 范围内。

## 结构与传输量

解析灯 160k 常驻、12 批局部编辑的开发运行：apply＋submit P50 2.3066 ms，P95／max 8.0168 ms；138 upsert、72 removal、116032 B 增量上传，完整提交、树重建和 PSO 不增长。这是 CPU 更新成本，不与历史 18 秒直接计算跨版本倍率。

应用缓冲 4096 B 中编辑 4 B，实际上传 4 B，减少 99.90234375%；重叠／相邻区间合并有真实 GPU 对照。compact 两张编码输出从每像素 16 B 降到 8 B；100001 个量化样本的低段 gain 绝对误差 0.003906、高段相对误差 0.01088916、GI 绝对误差 0.00391。运行仍保留 HDR 工作面并增加一次转换，不能宣称总显存或总帧耗时减半。
