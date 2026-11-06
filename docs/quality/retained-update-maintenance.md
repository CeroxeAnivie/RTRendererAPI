# 保留更新维护修复

2026-11-07，7.3.1 兼容内部实现修复，直接在 `codex/resource-root-followup` 修改，不切分支、不建worktree。基线为已发布7.3.0；公开Java接口不新增，不覆盖旧制品。

## 根因与修复

1. 树的每次refit/rotate都把祖先追加到dirty vector；单事务几千个修改共同祖先反复追加，且等待上传时跨Apply累计。Query准备又复制整个待上传日志、sort/unique后再按高度sort。现在按上传代次保持一次待传节点，另用Apply代次只准备本事务最终节点；删除/复用同一index仍只发布最终状态，上传排序或失败不消费日志。删除 `修改数×128` reserve，以可存在节点数量为上限。
2. 区域维护原本已复用bounds-only成员，并只改coverage对称差，但每个cell的删除仍 `std::find` 整个root list。现在root和cell各保留反向槽位，swap-pop后直接修正被移动者；发布时才排序一个复用scratch，保留原treelet、成员边界、hash溢出fallback与最终事务cell合并发布。它不是此前已撤回的source-leaf frontier。新增槽位容量有界，实际空间和时延均须实测报告。
3. 添加已有显式内存诊断口的容量分账：`sparse.records/tree/dirty/regions` 为CPU可见payload容量；`sparse.buffers`、`attributes.vertices`、resource arena为device-local，upload arena为mapped-upload。并记录sparse退休buffer与两个arena无人占用的完整页。只在用户显式memory诊断开启、已有1Hz采样时统计，普通路径不新增场景遍历、文件写出或GPU等待。

属性buffer现按可用前缀增长、旧buffer随GPU读者退休；resource arena的idle整页属于device-local。未证明它们是本次额外RAM主因，不盲目收缩或把其容量当任务管理器内存。上传arena原本已保留4MiB热页并释放超出热预算的无人使用整页，本批不重复“修复”该已有机制。

## 定向验收

正式入口 `tools/Test-RetainedUpdate.ps1`：固定Git基线6eacb19并编译相同CPU成本夹具，不改生产运行开关；新脏队列60事务覆盖重复refit、删除/复用、跨Apply未上传、失败上传排序及rebuild；区域支持oracle/毒化GPU slack后的真实脏span复制校验；显式内存诊断ON/OFF。42191个源、每事务4096修改及128删插，8预热/32采样，tree/query准备/regions分别报告均值与P95。

本批受影响检查已通过，未重复旧完整光照矩阵。API 子代理未启动 MC。主代理按用户最新授权随后只开一次人工验收；真实快飞 FPS、任务管理器内存及闪退稳定性尚无本版结论。

### 额外发现的真实遗漏

旧7.3.0在同一42191源混合夹具结束后，支持点 `(56,1,84)` 漏掉源2114；完整query与增量query逐字节一致，故不是夹具更新遗漏。区域维护按旧owner边删边更新；旋转/删插交换两个仍存活frontier的后代时，旧owner会删掉本事务已更新的另一最终root。现在任何拓扑变化先从最终树确定frontier集合，统一撤销废弃root，再用最终成员关系更新coverage；bounds-only仍走已有局部快速路径。候选同夹具、支持oracle、双向incidence和毒化GPU slack的部分上传检查均通过。旧混合结果功能错误，不用于计算优化速度。

### 同夹具 CPU 成本

初始GPU0%；42191源、每事务4096 refit、无删插、8预热/32样本。旧新各一轮顺序对照，没有交错复核；不是MC FPS，也不能据此宣称区域性能全面修好。单位ms。

|阶段均值|旧|新|绝对差|变化|
|---|---:|---:|---:|---:|
|tree|0.996647|1.190830|+0.194183|+19.48%|
|query准备|2.270070|0.624666|-1.645404|-72.48%|
|regions|3.796080|4.686610|+0.890530|+23.46%|
|三阶段均值合计|7.062797|6.502106|-0.560691|-7.94%|

|阶段P95|旧|新|绝对差|变化|
|---|---:|---:|---:|---:|
|tree|1.4474|1.4031|-0.0443|-3.06%|
|query准备|3.1298|0.7791|-2.3507|-75.11%|
|regions|4.6323|7.3488|+2.7165|+58.64%|

纯refit32窗累计dirty条数2155719→607177（-71.83%），这是工作量，不是时延。独立60事务journal尝试77411次，实际入队7232次（-90.66%）。候选混合树/准备/区域均值为1.551390/0.734834/7.064630ms，P95为2.3154/1.0403/8.6678ms；旧混合有遗漏，只单列候选成本。

### 检查与步骤延迟

|步骤|旧值|本批结果|绝对差/变化|
|---|---|---|---|
|受影响JNI和区域构建|无同条件旧值|43.334s|不计算|
|25000源/2198探针区域oracle|无同条件旧值|通过，2.370s|不计算|
|新混合42191源/4096改/128删插|旧版严格遗漏失败|通过，0.621s|功能修复，不计算速度|
|真实JNI sparse上传|无同条件旧值|24帧通过，5.778s|不计算|
|四模块package-only|无同条件旧值|通过，75.170s|不计算|

真实JNI覆盖128→4096增长、删除3072、24个fresh同身份输出、仅初始化一次、每次写入不超过1MiB及上传池生命周期。Job进程树峰2.156GB；打包1.973GB；不是任务管理器驻留内存。首次辅助PowerShell在AMSI扫描崩溃，重启同一检查通过；本批没有API/JVM崩溃。JNI/打包仅保留实际工具执行回执，不冒充完整stdout。Javadoc已有缺注释警告，包构建成功。

正式证据：[机器记录](data/retained-update-maintenance.json)、[定向日志](data/retained-update/)。发布身份由`tools/Verify-IncrementalDiagnosticsRelease.py --evidence docs/quality/data/retained-update-maintenance.json`核对；源码/正式工具/文档先GitHub，然后Maven Local，最终回执另存。
