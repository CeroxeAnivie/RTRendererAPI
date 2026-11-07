# 属性 arena 与稀疏整批上传

2026-11-07，兼容补丁 7.3.4，基线 7.3.3。保持当前 codex/resource-root-followup，不切分支、不新建 worktree。API 负责通用资源与灯光提交；MC 场景捕获、正常地形快飞及人工窗口由宿主负责。本批仍须通过同一 709 格快飞、终点地形收敛和关闭重开，才能判断实际收益。

## 确定的代码问题

终点完整场景为 3,318,040 个三角形。receiver 属性按三角角点存储，每角 16B，共 159,265,920B。原 EnsureAttributeBuffer 无论冷启动还是增长，都向上翻倍为 268,435,456B；冷启动多申请 109,169,536B。扩容还同时保留旧块并复制旧容量。真实关闭重开后 CreateCommittedResource 对 Native receiver attributes arena 返回 0x8007000e。

旧日志没有该次申请的字节数、DXGI local/non-local 预算、当前进程提交与 Job 当前/峰值/违规状态，因而不能仅凭 E_OUTOFMEMORY 判断是物理显存耗尽还是进程树提交限制。宿主 Windows Job 的实际树上限为 9.5×10^9B，另留 0.5×10^9B 给 host；本批不改变该限制。

真实快飞保留慢树 seq6357：SPARSE_UPLOAD 22.5969ms，其直属 STAGING_ACQUIRE 为 9.1260、2.9016、1.8844ms 等。稀疏 CPU 数据早已常驻，但多个不连续区间仍各自调用 NVRHI writeBuffer。上游已有 UploadManager；本批复用项目已有的 session 持久映射 uploadArena 进行整批传送，不把它称为 RTXPT 原生托管。BLAS/TLAS、资源句柄、复制命令与队列继续使用上游 NVRHI/Donut。

SPARSE_REGIONS 在每个事务新建受影响 cell 的 unordered_set，Link/Unlink 每次将常驻身份再插入临时哈希表。改为常驻 entry 的 epoch 加可复用连续 journal，继续执行同样的精确 Coverage、treelet 前沿、排序后的 root 列表和 hash overflow fallback，不扩大支持域或省略场景。

## 本批修改

- 大属性冷启动按 64KiB 页对齐申请，增长保留不超过 16MiB 加一页的余量；512MiB 逻辑上限不变。示例新冷启动 159,318,016B，比旧申请少 109,117,440B（40.65%）。较小的增长余量会增加扩容次数/累计复制，不把容量减少直接写成时延收益。
- 分配失败时才查询进程 private/working set、系统提交/上限、Job limit/current/peak/violation、DXGI local/non-local usage/budget，并附 requested/live/old/retired/reclaimer 字节与查询状态。成功帧不增加这些系统查询。
- 稀疏上传先规划同一份权威 CPU 数据的所有区间，再一次租借已有持久映射 arena；memcpy 后以不超过 1MiB 的 copyBuffer 命令上传。lease 只在 Graphics 完成事件后归还。冷/压力下 arena 尚未就绪时完整走既有 writeBuffer，同时通知已有 worker 备好容量，不等待、不跳帧、不复用旧信号。
- ResourceReason 的 16384 位标记持久映射 batch 请求/复制，以区分整批租借大小与每条复制命令大小。公开 Java ABI、shader、输出编码、光照公式与质量配置不变。

## 当前验证

新增容量 CPU 检查覆盖终点真实需求、300MiB 连续增长、512MiB 上限及超限拒绝。既有 50,000 次 first-fit、25,000 源/2,198 查询/移动删除/旋转/哈希碰撞 oracle 通过。构建和定向原生检查结果将由完整证据 JSON 与发布回执记录，不以 CPU 检查代替 MC FPS。

最终 JNI 的 24 个源更新帧、128→4096→删除3072、每帧 sequence/revision 身份、无新源的 steady 帧完成后 lease 归零通过；属性拓扑 8 批×3 信号的 roles/colors/baseline 精确检查通过。四份新 PFM 与正式旧包逐字节相同。初始 GPU 0%，最终检查 Job 峰值 2.398×10^9B。最终受测 JNI 为 89e2ea6c87d2f2c4b08e65a98829e72862cff182bee50f2cf344affe3dce08ca。

完整机器证据：[receiver-arena-and-batched-upload.json](data/receiver-arena-and-batched-upload.json)。本批新增正式收集工具 Collect-ReceiverArenaUploadEvidence.py，制品校验使用 Verify-IncrementalDiagnosticsRelease.py 的独立新分支，不以旧 bootstrap 九项冒签。

| 项目 | 旧值 | 本候选 | 绝对差 | 百分比 |
|---|---:|---:|---:|---:|
| 终点属性冷启动申请 B | 268,435,456 | 159,318,016 | -109,117,440 | -40.65% |
| 同入口静止 FPS | 249.38 | 待宿主 | 未测 | 未测 |
| 同入口 709 格快飞 FPS | 39.9638 | 待宿主 | 未测 | 未测 |
| 180 FPS 目标 | 未达 | 待宿主 | 未测 | 未测 |

上述旧快飞因终点重开失败而整轮无效，637 个移动身份帧本身完整；数据作为定位和同入口候选对照，不称为验收通过。NativeLightingFrameFull 的跨命令 timer 包含 CPU 未及时提交造成的队列空隙，不能将其 7.07ms 全归为 GPU 计算；同命令 Dispatch 平均为 0.92ms。

## 实际工作量和微夹具时延

以下只是同一个 24 更新小夹具，含首帧约 5 秒初始化，非 MC 快飞/正式性能 ABBA。两边 GPU 初始为 0%，诊断 DEEP；平均和 P95 分开，退化保留。

| 优化项/观测 | 旧值 | 新值 | 绝对差 | 百分比 |
|---|---:|---:|---:|---:|
| 普通 staging 请求 | 1720 | 112 | -1608 | -93.49% |
| 普通请求＋新整批租借请求 | 1720 | 136 | -1584 | -92.09% |
| STAGING_ALLOCATE 事件数（含 worker） | 27 | 31 | +4 | +14.81% |
| DEFAULT_BUFFER_COMMITTED 事件数 | 42 | 46 | +4 | +9.52% |
| 累计 index 上传 B | 20,492,096 | 20,442,432 | -49,664 | -0.24% |
| 匹配请求平均 ms（含冷启动） | 224.22758 | 212.72033 | -11.50725 | -5.13% |
| 匹配请求 P95 ms | 8.5987 | 9.3994 | +0.8007 | +9.31% |
| SPARSE_REGIONS self 平均 ms | 0.15691 | 0.17109 | +0.01418 | +9.04% |
| SPARSE_REGIONS self P95 ms | 0.6019 | 0.5181 | -0.0838 | -13.92% |

普通 staging 与整批租借的大小不是一个口径，不能把新 17,862,912B 整批租借当作超出单写入 1MiB；实际 mapped copy 最大 524,224B，普通写入最大 1,048,576B。新持久页最大 18,874,368B，等于实际整批需求向 2MiB 页对齐；这不是旧瞬态 chunk。DEFAULT_BUFFER_COMMITTED 是 NVRHI 路径事件名，包含新持久上传 buffer 的创建，不据此声称 device-local 净增加四块。请求数减少不是驱动创建数减少，分配工作转到已有 worker 也不是消失。

较小增长余量的代价必须保留：容量 CPU 夹具从16B起、每次增长310,123B到300MiB，当前26次增长、累计复制3,017,081,088B。它是合成容量工作量，不是 MC 本轮实际迁移，也不证明性能收益。宿主下一轮同时检查完整资源峰值、迁移 bytes、常态与 P95，不只看更小的冷启动申请。

## 上游机制与自维护边界

NVRHI 的 UploadManager 原本就持久 Map，并依据 submittedVersion 与 queue.lastCompletedInstance 复用每个 CommandList 的 chunk；公开 CommandListParameters.uploadChunkSize 默认64KiB。项目之前自加 rtxpt-upload-retention.patch 在 submitChunks 后通过 RetireExcess 将可复用池压到4MiB，超额转交该 command-instance 的 fence 退休链，完成后销毁而不回池；rtxpt-adaptive-upload.patch 在新 recording 的小首写又从64KiB开始，只有同一 recording 耗尽才递增到1MiB。该自加内存策略会丢掉上游本来可复用的容量，下一批缺少已完成且足够大的块就重新 CreateCommittedResource/Map。

真实慢树确认进过冷分配分支，但没有单独 A/B 撤去此 overlay，所以不能把39.96FPS全部归因给它。只调大公开 uploadChunkSize 可减少首批小块数，仍保留4MiB裁池和队列未完成不可复用的条件；本批未另起参数跑分。后续优先评估直接沿用 NVRHI 的完成版本回收加公开 chunk 参数，在同样有界内存下减少自维护面。本批的 mapped arena 是项目已有 adapter 管理，不能称 RTXPT 原生托管；复制、buffer句柄和Graphics完成事件仍使用NVRHI，BLAS/TLAS、队列同步没有另造实现。

## 失败记录与执行耗时

首次 GPU 候选在24帧和四份capture完成后，被旧测试的“所有 staging allocation 都≤16MiB”拒绝。原日志完整保留；修正诊断分类后分别检查瞬态 chunk、整批规划页和单 copy 边界，未放宽 Job、输出身份或场景。随后发现无新源帧必须仍轮询完成事件，已将 PollUploads 移到 headerDirty 早退前；最终 steady lease 归零用例通过。最初直接用 bounded runner 启动 bat 的命令行失败、缺新 release-index 的配置失败也如实保留，不归为渲染失败。

| 执行步骤 | 旧值 | 本次耗时 s | 绝对差 | 百分比 |
|---|---|---:|---|---|
| 首 native＋两项CPU目标构建 | 无同条件旧值 | 22.275 | 不适用 | 不适用 |
| 容量/分配器CPU可执行检查 | 无同条件旧值 | 1.297 | 不适用 | 不适用 |
| 区域 oracle CPU检查 | 无同条件旧值 | 0.894 | 不适用 | 不适用 |
| 受影响诊断修正构建 | 无同条件旧值 | 16.997 | 不适用 | 不适用 |
| 最终 native构建 | 无同条件旧值 | 16.112 | 不适用 | 不适用 |
| 最终测试类编译 | 无同条件旧值 | 5.746 | 不适用 | 不适用 |
| 最终两项GPU回归＋锁依赖 | 无同条件旧值 | 52.557 | 不适用 | 不适用 |

本批新增的失败内存域快照只做编译和代码检查，尚未故意制造 OOM 来运行该分支；不把它写成已确定原始失败的内存域。大属性冷启动及终点关闭重开仍由原709格宿主验证。
