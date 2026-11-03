# Native 光照性能修复

本批针对通用原生执行成本修改：候选交点先执行轻量角色／透明度判定，仅最终命中读取完整接收面；遮挡射线遇到有效不透明命中即结束；GI 入射源查询不再计算未使用的 dynamic 子集；按实际非加法组数、解析灯存在性和统计开关选择等价预编译变体。多组、任意组号和解析灯仍走完整实现。统计 OFF 的内置 raw／RR 变体不含逐射线计数代码。没有删灯、降低样本、换算法、裁覆盖或复用旧帧。

候选判定和 shadow 改动有 alpha／角色／GI 补验。raw 与 RR 各八个选择变体均已实际执行；开关计数后 raw 三个信号、RR gain／raw GI／全部 guides 逐位一致，RR 共八次成功 SDK evaluate。

## 同机受干扰对照

按用户要求保留其他 GPU 程序。同一 fixture，1280×800 光照、2560×1600 逻辑视口、64 源、2 GI、24 帧预热和每轮 600 个新帧。基线是本批 GPU 性能修改前保存的 native runtime；不是旧 Vulkan。逐射线计数、PFM 捕获在计时之外。

| GPU dispatch | 修改前 ms | 修改后 ms | 本轮耗时减少 |
| --- | ---: | ---: | ---: |
| P50 | 11.351488 | 8.303168 | 26.8539% |
| P95 | 14.173664 | 10.746080 | 24.1828% |
| P99 | 15.831552 | 11.494144 | 27.3972% |
| 最大值 | 17.014368 | 11.750144 | 30.9398% |

前测外部 GPU 利用率 34/34/40%，后测 37/32/32%，graphics clock 分别 1305/1305/1380 与 1372/1200/1200 MHz。timestamp 包含调度和抢占。这些是本轮观测改善，不能据此承诺固定倍率、游戏 FPS 或 native 超过旧 Vulkan。旧 Vulkan 的历史 4.920352 ms 与本轮 8.303168 ms 不属于可比较轮次，不计算比值。

两张输出共 6,144,000 个 RGB 数值逐位一致，maxAbs=0、RMSE=0。direct SHA-256 `8fe72e2eca30ca0c9e0ce90ebe970eb867301db20dce1ee768764e15c945fb75`；GI `62aa0e61ed5bce968ece057e18b7a6fffd4ffea59b31a5a672a4c1ad6b985093`。fixture SHA-256 `87f664fb54367f43426778920a3f2e9127b7f87c3fd1e59e6018d04631502697`。计数保持 primary=1,024,000、receivers=799,347、shadow=11,069,335、bounce=1,598,694、sourceNodes=153,521,363、sourceCandidates=68,894,962。

原始证据 `build/reports/lighting-uninstrumented-{old,new}/` 包含逐帧 CPU／GPU CSV、环境、run、fixture、PFM。机器可读记录见 [数据](data/native-lighting-performance.json)。更早的 120 帧轮次 8.451520→6.764480 ms 同样图像逐位一致，但不替代本轮结果。

## CPU 局部事务

4096 mesh fixture 的灯事务 P50 523200→4300 ns，单材质事务 528000→2000 ns；Java 分配分别 1332992→2740 B、1332976→3328 B。结构共享与反向依赖索引消除了无关表复制／引用扫描。121.7×／264× 是这两个 CPU 事务的观测值，不是 GPU 或游戏提速。

## 诊断边界

DEEP 显式拆分 receiver、direct（含 sources/shadows）、GI（含 sources/shadows），采用额外接收面存储并改变调度，只用于诊断。阶段各自使用独立常量缓冲；大网格计数已与生产路径一致。source/shadow 内部仍未独立计时，不能把计数折算毫秒，也不能将诊断拆分的分位数相加来解释生产 fused kernel。

实际 GL 消费、宿主重建和游戏其他绘制由真实宿主计时。库提供 sequence／revision／generation 关联及共享发布范围；这些缺项不是零耗时。
