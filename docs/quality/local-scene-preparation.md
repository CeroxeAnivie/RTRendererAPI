# 局部场景准备成本与验收

日期：2026-11-04。候选版本 7.1.2；同夹具基线是实际 Maven Local 7.1.1 四 JAR。只修改通用资源准备和诊断，不改原生光照 shader、采样或 RR 算法。

## 方法和边界

每个 mesh 使用独立材质与 1×1 纹理，分别准备 256／4096 资源。两次预热后测五次；P50 为 owner Java 线程实际分配及墙钟。列表包装器直接计数旧／当前 item 读取。baseline 实现从四个已发布 JAR 加载，同一测试 class 执行观测模式；candidate 执行断言。原消费方无纹理夹具的数据保留，本表是新的同夹具比较。

这里只量化 `RtxptDelta.prepare`，墙钟包含导出与文件 I/O；不是全 submit、native kernel 或 MC 帧率。纯姿态和灯事务在正常 renderer 中绕过 importer，表中直接调用 planner 仅检查其退化路径。短样本波动不能外推绝对帧率。

## 4096 资源的准备结果

| 编辑 | 分配 P50，B（旧→新） | 分配下降 | 墙钟 P50，ms（旧→新） |
| --- | --- | --- | --- |
| material | 3,226,816 → 41,584 | 98.71% | 4.5015 → 1.6213 |
| texture-rgb | 3,268,664 → 84,496 | 97.41% | 5.5367 → 2.2393 |
| texture-alpha | 3,269,088 → 84,808 | 97.41% | 5.5872 → 2.1016 |
| vertex-color | 3,210,944 → 2,656 | 99.92% | 4.2924 → 0.0146 |
| mesh-baseline | 3,210,968 → 2,656 | 99.92% | 4.0679 → 0.0132 |
| pose | 2,795,152 → 27,016 | 99.03% | 3.2453 → 1.5792 |
| light | 2,792,216 → 1,984 | 99.93% | 3.6859 → 0.0059 |

稳定同列表结构比较：旧版 8192 次读取，新版 0。单材质 delta：旧／当前列表旧版 12288／4096 次，新版 0／0。独立材质的真实依赖访问为 6，独立纹理为 7；256→4096 的 map 实际检查节点为 172→356（包含 diff 查找路径），无需读取无关 item。256 资源的小场景墙钟并非每项都更快，完整两档原始数值见 JSON。

共享资源检查另覆盖 256 个真实用户：更新不漏用户并去重 variants；同事务修改另一材质和一个共享 mesh 实例姿态，不扫描该 mesh 的其他实例。500 轮随机 persistent-map 增删改／旋转／快照比较通过；悬空引用和预算超额继续拒绝。

## GPU 与 native 资源证据

`RtxptLocalResourceNativeSelfTest`：256／4096 各 14 个新帧，共 28 项。涵盖 stable、材质、纹理 RGB／alpha／恢复、vertex tint、baseline、pose、light、4 B patch、混合材质分组／实例映射、增删恢复；检查真实 center gain、sequence／revision、BLAS 与映射身份，并检查 OFF 冻结。

两种规模的独立局部变化均为 Java item reads=0，native index rebuilds=0。材质／纹理每次 native material visit=1；RGB 材质变更 mesh visit=0，alpha coverage 变更 mesh visit=1。baseline／染色只访问一个属性用户，转换 6 个角点，上传对应的 96 B 顶点属性 range；pose 只提交一个 transform，纯灯和 stable 提交零 transform；应用 patch 精确上传 4 B。真实拓扑变化执行一次 native 索引重建和完整属性映射重建，不能将这部分称为局部常量成本。

这些 native 计数仅覆盖资源准备，不代表整个渲染／AS 更新没有全局工作。姿态涉及上游场景图和 TLAS，alpha 变化涉及依赖 BLAS 与 TLAS；保留必要更新和 GPU 等待。核心光照、宿主 GL、重建、全帧性能仍使用各自测量，既有干净环境 Vulkan/native 数字不重跑、不改写。

最终包补验还覆盖 `RtxptMixedResourceNativeSelfTest`：同事务替换几何并改变共享 alpha，打开及恢复均更新两个真实依赖 BLAS，后续普通材质提交继续使用正确常驻索引。最终包离仓共享资源、染色／OFF、vertex alpha／texture、真实 GI RR、host augmentation 及 analytic beauty retained 的记录按各自制品身份保留；最后这个分支修正只追加受影响用例，不重跑其他已通过组。

## 原始证据

- [基线观测](data/local-scene-preparation/baseline.json)
- [候选 CPU 结果](data/local-scene-preparation/candidate.json)
- [28 项 native 矩阵](data/local-scene-preparation/native-resource-matrix.json)
- 可复核源码：`RtxptLocalPreparationSelfTest`、`RtxptLocalResourceNativeSelfTest`。运行入口为同名首字母小写 Gradle task。
- 原始日志：`build/local-resource-cpu.log`、`build/local-resource-gpu.log`。最终包身份与离仓补验见 `build/reports/local-scene-release/result.json`。
