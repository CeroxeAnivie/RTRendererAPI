# micro-all sequence 172 边界次序修复

## 故障证据

正式 1080p、1 SPP、关闭诊断的 `micro-all` 在 frameSequence=172、path=1607471、boundary=65 报 optical error=9。CUDA 当前介质栈为空，但 Vulkan 返回了离开边界；`remove()` 拒绝不存在的介质是正确行为，不应吞错或发布不完整辐射。

定点捕获位于 `build/medium-micro-all-20261101/error9-sequence172-capture/capture/sequence-172-sample-0-branch-0/`。散射后起点为 `(-3.2866134643554688,-1.8500804901123047,7.664741039276123)`，方向为 `(0.6562535762786865,-0.2838139832019806,-0.6991286277770996)`。使用精确 float 输入的独立 slab 求交得到：

| 真实交点 | t |
| --- | ---: |
| x 左侧进入 | 10.570020021601968 |
| y 底部离开 | 10.570020193806357 |
| 两者的 float 舍入值 | 10.570019721984863 |

这是两次真实交点，不能按切线忽略。原主射线只确认硬件候选本身有效，未像光源连接路径一样修复最近真实交点，因此合法 exit 掩盖了更早的 entry。

## 候选与成熟实现依据

在生产 any-hit、closest-hit 中，将已有 `gsBoundaryConnectionHit` 最近交点修复应用于主相机/散射和光子射线的边缘不确定带；普通内部交点仍走原路径。复用两段 float 精确分类及向外舍入区间，不改变 CUDA 栈校验，不降低路径预算。

参考 PBRT-v4 固定 commit `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f`（Apache-2.0），`src/pbrt/shapes.cpp` 的稳健三角形边测试、`src/pbrt/util/float.h` 的区间向外舍入，以及 `src/pbrt/ray.h` 的误差界起点偏移。PBRT 的原则是正确分类交点并保证跨界继续；本项目必须另外适配 Vulkan float 硬件候选和 CUDA 介质栈，不能直接照搬 CPU double fallback。

新增 `BoundaryRadianceTraversalNativeSelfTest`：运行时相机输入，直接调用生产 any-hit/closest-hit，覆盖三角形六种排列、镜像、volume/surface、相机/光子/光源连接、opaque 与 any-hit 路径，共 144 条断言。固定故障帧重放后，仍须完整重跑 4 帧预热及 16×16 帧正式窗口；不能用缩短窗口代替验证。

## 验收记录

候选待硬件回归验证。本次是正确性修复，不能提前记作性能收益或内存问题修复。运行进程树上限维持 3 GB，测试前全量提交、推送 main。
