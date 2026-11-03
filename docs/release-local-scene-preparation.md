# 局部场景准备与稳定版本身份

版本：**7.1.2**。当前交接只要求继续完成 H04，文档中的 H01／H02 旧失败属于历史状态；已有真实 GI RR、原生光照路线和宿主边界继续保留。本批发布目标为 GitHub 和 Maven Local，不发布 Maven Central。

## 审核结论

候选文档 C 节属实：只修改一个独立材质时，旧实现仍复制完整纹理映射、扫描旧／当前实例和重建 variant 表；同一列表的结构比较与稳定姿态检查也未直接复用。baseline／染色发布遍历全部 item。另发现资源预算每次全表重计，以及 native 资源提交重新索引材质／网格、扫描纹理存活并全量同步材质。

这些属于 API 内部的通用准备成本，不需要解释宿主世界，也不需要改变光照算法。Java 准备改善不能直接等同于 native／GPU／宿主全帧提速。

## 完整修复路径

| 问题 | 实现 |
| --- | --- |
| 全资源 delta 扫描 | persistent map 跳过共享子树，以 dirty 资源及纹理→材质→网格→实例反向依赖选择实际 variants；纹理路径表结构共享。 |
| 全实例结构／姿态比较 | 不可变 item 快照和独立结构身份；稳定／纯灯／相机帧直接复用，姿态只提交变化实例，混合材质＋姿态也不枚举未变化 variant 的其他用户。 |
| 局部属性走导入器 | baseline／顶点色访问变化 mesh 的实际用户和驻留 ranges；无导入工作就不创建 delta 目录或模型。4 B 应用 patch 保持局部上传。 |
| 全场景预算重计 | 随事务维护几何／纹理总字节；快照隔离、引用验证和超额拒绝保留。 |
| native 重建全索引 | 复用常驻材质／网格／依赖和纹理引用索引；局部材质只刷新对应 Donut buffer range，兼容变化不重建全材质表；alpha 只选择依赖 BLAS。JNI 节点映射随增删更新。 |
| 稳定 native 刷新 | managed 稳定帧跳过无变化的场景 buffer 刷新；真实结构／姿态仍执行必要的场景图和加速结构更新。 |
| 无法复核准备量 | `RtxptAccess.preparationStatistics()` 记录 Java item／依赖／map 节点／导出／姿态／属性用户／owner-submit 分配；`nativePreparationStatistics()` 记录 native 资源访问和索引重建。OFF 不增加遍历或字符串，冻结计数。 |
| 版本绑定命名 | 日志、Demo 标题、generator、pipeline 和内部路径使用功能名；HUD 从构建元数据读取版本；新增 `verifyVersionIndependentNaming`。 |

真实拓扑／映射改变仍正确失效并重建，不将这类工作隐藏为零；实际共享资源仍处理全部真实用户。shader、GI 算法、采样、RR 配置和扩展 ABI 没有变化。native CPU 修改通过独立可重放 overlay 构建。

另补齐“几何替换＋共享纹理 alpha”同事务边界：旧反向依赖不能覆盖已选中的新几何 generation，确保当前几何和其他真实 coverage 依赖均重建。源码构建脚本同时修复重复执行时 shader 配置留下孤立 `-D` 行的问题，配置统一规范化并仅在内容变化时写入。

## 验收与量化

详见[准备成本报告](quality/local-scene-preparation.md)。CPU 夹具使用 256／4096 独立纹理、材质、网格和实例，与 Maven Local 原 7.1.1 四 JAR 的同一夹具比较。新 GPU 矩阵覆盖 28 个真实新帧及 OFF 冻结，原共享资源矩阵、染色、OFF 和 RR 沿受影响路径补验；不跑 FG/MFG，不重复不受影响的历史压力与性能。

公开 `renderer-api` ABI 保持原快照；`renderer-rtxpt` 的高级诊断只做加法。最终四 JAR、native、shader、SDK 身份和离仓验收记录保存在 `build/reports/local-scene-release/result.json`，历史证据不改写成新结果。

## 版本号保留位置

`gradle.properties` 是版本来源；制品坐标、manifest、构建元数据、发布索引、兼容性与文档记录保留版本。第三方 SDK／协议版本保留。文档允许版本号，历史日志和 ABI 快照保持原身份。
