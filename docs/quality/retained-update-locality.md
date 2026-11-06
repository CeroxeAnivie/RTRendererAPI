# 7.3.2：稳定资源更新与有界托管

2026-11-07，当前分支 codex/resource-root-followup；不切分支、不新建 worktree、不启动 MC。公开 API ABI 不变。证据见 [JSON](data/retained-update-locality.json)，原始日志在 data/retained-locality/；不提交生成 DLL、JAR 或 PFM。

## 代码根因与修改

- 兼容 resident mesh 每次新建并 clone AS，再 refit，制造额外 GPU 工作及退休积压。现在使用同一个 mutable AS；旧 snapshot 仅保留旧 vertex/index 双 fence 退休，不再冒充拥有活 AS。双向 compute→graphics→compute ticket 保证顺序，不加入 CPU idle/fence 等待；所有准入检查均先于原地更新。
- positions/indices/layout 完全相同的 normal/UV 更新仍触发 AS。现在对当前 CPU positions 精确 memcmp，属性更新不做 AS；真实形变 PerformUpdate，原 16 次周期 rebuild 不变；拓扑变化保留新建/退休。spare count 64→1024、仍限 64 MiB，回收 batch 8→128。
- 区域 topology 更新重复处理共享祖先。先完整规划最终 treelet roots，再统一解除旧 ownership；epoch 去重只访问局部 frontier。25k sources 的 8 次单源删插最多访问 23/49,999 活节点。早期重复排序候选曾退化约 8%，已替换，失败不删。
- resource census 默认逐资源查询/字符串/容器开销从普通 memory diagnostics 中拆出，须两个显式开关同时开启；两端宏观测试均为 MEMORY=0/CENSUS=0。
- pending patch 无界保留 caller buffer。改为最多 16 MiB 权威 shadow、512 KiB dirty words、最多 256 连续上传；更碎时一次上传完整当前 shadow。成功才清标记，失败保留可重试。Java public generation 仍严格递增；同一次内部多段允许相同 generation。
- 非 owner diagnostics 原来可能 JNI 读现场。改为 volatile 不可变 snapshot；owner 在现有调用边界发布，close 发布 closed 状态。
- 移除生产临时 glTF/PNG/文件树链，完整导出器迁 test；Donut 原生空场景沿用框架加载生命周期。公开真实文件 load 保留；用户文件与内部 token 同名也必须真实导入。

## 最终整链配对（CPU 包含耗时）

正式旧 7.3.1 Java+JNI 对当前 7.3.2 Java+JNI，candidate→baseline 单配对，非 ABBA；两端 GPU 初始 0%，DEEP、MEMORY=0/CENSUS=0、JVMCI 关闭、CICompilerCount=2。3300 resident mesh、45000 source；每步 128 真形变和 150 真实 source 位置 delta，4 warmup+16 samples。输出 128×96、光照 64×48，多数 mesh 离屏。owner 边界为 apply 前到当次 sequence/revision 新 lease 可取得，含准入重试，不等 producer GPU 完成。各阶段重叠，不可相加或称 GPU 时间。

|指标 ms|旧 mean|新 mean|差值|变化|旧 P95|新 P95|差值|变化|
|---|---:|---:|---:|---:|---:|---:|---:|---:|
|owner apply→fresh lease|13.456881|7.520794|−5.936087|−44.11%|34.4202|11.6608|−22.7594|−66.12%|
|FRAME|1.509956|1.632531|+0.122575|+8.12%|2.1028|2.6800|+0.5772|+27.45%|
|TRANSLATION|0.476094|0.513844|+0.037750|+7.93%|0.6030|0.9594|+0.3564|+59.10%|
|RESOURCES|4.459031|3.130925|−1.328106|−29.78%|5.2523|5.2320|−0.0203|−0.39%|
|SPARSE_REGIONS|0.659931|0.670813|+0.010882|+1.65%|1.3207|1.1683|−0.1524|−11.54%|
|SPARSE_QUERY_PREPARATION|0.024294|0.023344|−0.000950|−3.91%|0.0356|0.0319|−0.0037|−10.39%|

两端 refit 均 2432；clone 2432→0。LIGHTING/LIGHT_TREE 没有独立 scope，未观测，不能写 0ms。CPU 规模匹配不代表 MC 场景布局或 GPU 工作量，不能据此宣称 MC 180 FPS。最终三份 mesh PFM 及 scale-final PFM 对旧版逐字节相同，JSON 保留四组 SHA256。

## 局部 CPU 阶段

同冻结 mixed 夹具、42191 sources、4096 refit、128 删插、32 samples；两端 oracle 均通过。tree/prepare 未改，不对其涨幅归因。

|阶段 ms|旧 mean|新 mean|差值|变化|旧 P95|新 P95|差值|变化|
|---|---:|---:|---:|---:|---:|---:|---:|---:|
|tree|1.491720|1.640850|+0.149130|+10.00%|1.7254|1.9270|+0.2016|+11.68%|
|prepare|0.692997|0.777409|+0.084412|+12.18%|0.7721|1.0860|+0.3139|+40.66%|
|regions|6.784280|6.445900|−0.338380|−4.99%|8.6087|8.2968|−0.3119|−3.62%|

## 步骤延迟与 FPS 边界

|步骤|旧值|新值|绝对差|百分比|
|---|---:|---:|---:|---:|
|静止/移动 MC FPS|未测|未测|不适用|不适用|
|45k scale 完整 Job|6.715s|6.635s|−0.080s|−1.19%|
|最终 native build|无配对旧值|16.421s|不适用|不适用|
|最终 patch GPU case|无配对旧值|10.390s|不适用|不适用|
|最终 BLAS case|无配对旧值|19.415s|不适用|不适用|

Job wall time含启动/初始化，不能代替稳态请求延迟。步骤新旧项不齐的地方不编造百分比。旧128 mesh实验独立保存自身 native、Java 和默认编译线程参数，不代签最终版本收益；其属性 FRAME P95 +2.48% 保留。

## 正确性与失败

16 项受影响检查通过：区域 oracle/locality、memory OFF/default/census、两端 mixed、1927 admission/500 generations/3 levels、adapter、dynamic resources、有界补丁12项、bootstrap、GPU patch、BLAS、两端规模检查。BLAS 覆盖 deferral/retry identity、活资源不入池、不兼容替换；129 删除入池后新128全部命中，attributes allocations/refits/builds/clone 均0。

空场景新路径初次两次 native crash 保留：Sample 把 Assets 拼到 token 前，错误走文件 load，GameScene::SceneLoaded 在空 scene 解引用。修为显式私有 memory flag，不再猜文件名；随后补回旧 JSON 隐含的 managedLighting=true。最终 fresh bootstrap 覆盖 initial、empty/populated reset、公开文件导入、真实同名文件 material、跨线程及 close snapshot。此失败是本批新增 API 路径，不归为原 MC 闪退。只保留 hs_err 前70行及匹配符号栈，不提交环境变量。

GPU patch 首次使用过期扩展 ABI 被正确拒绝，使用正式离线工具重编后通过，未放松校验或改生产 shader。最终 native SHA256 为 4960e5284e256c032338e0d5f3d5003305bc76065708fdb55b9a428320e7147a；最终 Java 仅有末尾注释/unused import 清理导致重新编译的行号表变化，行为测试不重复。

发布顺序为 package-only 身份核验→源码/工具/文档提交 GitHub→四模块 Maven Local→独立 Local 身份回执。依赖和 executable shader 对旧7.3.1逐字节锁定，只有本批 JNI/Java 和 SDK 元数据按版本变化。最终回执另存；不发 Central、不部署、不启动 MC。
