# 7.1.6 资源架构修复与交付

本版按代码根因交接 P01～P09 完成功能修复与源码／制品交付。用户最后明确排除继续性能优化和扩跑性能验收；完整 GPU P95 4 ms、完整 CPU 预算及最终36格未全部通过，不能据此宣称性能目标完成。[实施记录](quality/7.1.6-implementation.md)列出逐项结果，[机器证据](quality/data/resource-architecture-716.json)保留实际输入身份与缺测格，制品核对结果见 `quality/data/resource-architecture-716-release.json`。

内存mesh的vertex、index和BLAS storage使用总容量上限2 GiB的共享heap与placed resource。后台准备heap，事务整体预约，再绑定、上传与发布；未准备好时 `trySubmit` 返回 `SCENE_UPDATE_BACKLOG`，不接受帧、不半发布。同一不可变request复用Java准备结果；同步 `submit` 的等待有上限，超限明确失败。

同事务小mesh按上限共享vertex／index缓冲页，保留独立BLAS及lease。buffer对象按容量best-fit复用；clone/refit兼容规则保持严格，不同拓扑仅复用已无reader的存储。lease随真实graphics／compute reader和退休代保活，复用前建立aliasing依赖。buffer／BLAS缓存各64项／64 MiB，池满移交固定槽后台；每代回收用可续跑cursor覆盖第9项及以后。

metadata按scene journal同步插入、删除和实际dense迁移。无normal texture时避免无用tangent，后期开启normal map正确补齐。`RtxptAccess.resourceArenaStatistics()`分别报告heap容量、lease、payload和pool，不将引用字节冒充物理VRAM。扩展pipeline退休同时创建graphics／compute completion query，修复空compute query。

独立BLAS阶段批量调度保留全部依赖与真实GI RR。事务页主要减少容量；BLAS批量调度的流入延迟收益及稳定场景回退见[实测](quality/independent-blas-batching.md)。receiver/shading分拆和提前BLAS提交候选已完整撤回，失败方案及证据保留，不原样恢复。

四模块坐标统一为 `top.ceroxe.rt:renderer-api:7.1.6`、`top.ceroxe.rt:renderer-core:7.1.6`、`top.ceroxe.rt:renderer-nvidia:7.1.6`、`top.ceroxe.rt:renderer-rtxpt:7.1.6`。Maven Local交付普通JAR、sources、Javadoc、POM、Gradle metadata；RTXPT额外提供独立native-symbols，普通JAR嵌入runtime与SDK manifest。GitHub只上传完整源码、正式工具和文档，不上传生成制品或临时脚本。

当前DLL为 `ce9c1c65fd4abf3e851a4f73d206dfc70bcbfce4f4fcbc07e00ff2a4ed281868`，匹配PDB age237。现有30项功能记录按原身份保留，其中7项为ce9受影响回归，另准备取消检查已通过；请求Full的历史窗口因本机D3D debug／GPU-based validation不可用，不宣称已启用验证层。最终DLL只归集三个不同性能格，旧e2f的26格保留原身份。

生命周期、完整请求边界、OFF／SUMMARY／DEEP、故障与互操作功能按既有通过证据收尾；冷暖启动／resize／取消的完整性能分布、缓存命中与细分耗时属于本次性能例外。MC工程未修改，MC捕获、writer、实际场景和180FPS仍由消费方负责，API夹具不代签。
