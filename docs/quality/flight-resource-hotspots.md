# 快飞资源热点与旧 lookup 兼容修复

2026-11-07，兼容内部修复版本 **7.2.2**。保持原分支 `codex/resource-root-followup`，不创建分支或 worktree。本批起点为 `eb29fcc5`（保留此前验收窗口修正），依赖基线为 7.2.1 / `353751c8`，最终受测 JNI SHA256：

`ca1611e9562b63aad615b2743761992d618a669a593a648530a161773cd779cb`。

本批集中处理属性范围管理、稀疏更新日志、内存 mesh 导入，以及验收实际发现的旧 publication 兼容错误。公开 Java API、JNI 参数、shader ABI、光照算法、源方向/衰减、射线与本帧语义不变。上一批已完成的自适应上传页和拓扑成员复用继续保留，不重复计为本批成果。

## 真实快飞证据

宿主证据目录：`D:/Engineering/code/MCVulkanRT/build/renderer-audit/mainline-light-response-clean-flight`。初始 GPU **0%**；755 个 JFR 帧身份，无缺失帧、复用或回退，四类诊断队列丢失均为零。旧 29% GPU 场次的 133.86 FPS 工作量不同，不能计算严格退化比例。

| FPS 场景 | 旧值 | 本批新值 | 绝对差 | 百分比 |
|---|---:|---:|---:|---:|
| 静止 | 本证据未测 | 待宿主 | 未测 | 未测 |
| 正常地形快飞 | 45.5870 | 待宿主 | 未测 | 未测 |

| 全量宿主边界 | 样本 | 旧平均 ms | 旧 P95 ms | 本批新值/差值 |
|---|---:|---:|---:|---|
| API_JOIN | 755 | 11.2394 | 35.1274 | 待宿主测量 |
| UPDATE submit | 483 | 15.3854 | 40.1586 | 待宿主测量 |

正式分析工具 [Analyze-FlightResourceHotspots.py](../../tools/Analyze-FlightResourceHotspots.py) 用 JFR 快飞的 frame 集合、完整慢组及 `(session, scope_id)` 去重。匹配序列 5910～6664，保留 77,967 个独立事件；整捕获有 83 个完整慢组和 2 个不完整慢组，后者不进入分析。保留慢树是选择性慢样本，不是全部帧；不同阶段样本数不同，不能相加平均值或 P95，当作每帧总成本。

| 选择性原生慢样本 | 数量 | 平均 ms | P95 ms | 最大 ms | self 总 ms |
|---|---:|---:|---:|---:|---:|
| IMPORT | 19 | 8.8550 | 24.1842 | 24.1842 | 153.0103 |
| STAGING_ALLOCATE | 81 | 1.0683 | 1.1387 | 40.6736 | 86.5290 |
| SPARSE_REGIONS | 12 | 9.4824 | 54.3341 | 54.3341 | 76.7596 |
| SPARSE_UPLOAD | 12 | 11.4591 | 33.1013 | 33.1013 | 61.1628 |
| RESOURCE_INDEX | 858 | 0.1003 | 0.0026 | 13.0073 | 72.5270 |

## 代码根因与本批修改

### 属性范围：每删一段就重新排序全表

`rtxpt_native_lighting.h::UploadChangedAttributes → FreeAttributeRangeAt`。序列 6518 的父链为 SUBMIT→FRAME→RESOURCE_INDEX，inclusive 12.526 ms、self 11.890 ms；不是此前猜测的 CommitResources 逐 mesh gather。旧范围管理每次释放均 push_back、全量 sort、全量合并，再裁尾。一个事务删除很多几何时，重复排序/扫描不断增长的同一张空闲表。

新增 [rtxpt_attribute_ranges.h](../../renderer-rtxpt/src/main/cpp/rtxpt_attribute_ranges.h)：按地址有序保留空闲区，释放仅检查前后两个邻居并合并，尾部直接裁掉；分配仍按原地址 first-fit，未改成 best-fit，因而现有属性偏移保持一致。节点句柄修改空闲区键，避免分配后再抛出造成中间状态。批量释放不再反复对所有碎片排序。基线没有此函数单独 scope，不能将上述整个 11.890 ms 当作它的独占成本。

### 稀疏脏日志：同一节点按每个 word 重复入队

`rtxpt_sparse_regions.h::WriteWord → Mark`。一个 64B 节点内多个 4B 根编号改变时，旧 Mark 每次都 push；`rtxpt_sparse_lighting.h::Upload → UploadRanges::ForEach` 最后又排序/去重。序列 6450 的 SPARSE_REGIONS inclusive/self 为 54.3341/39.8559 ms，SPARSE_UPLOAD 为 33.1013/18.5981 ms。实际覆盖变动和上传仍是必要工作，不能把两段 self 都宣称会消失。

本批在节点所有者处保留一个 queued 标记：每个节点到成功 Uploaded 前最多入队一次；成功上传清标记，完整上传无需再生成单节点日志；Clear/FullPack 处理标记生命周期。额外标记容量上界 802,816B（约 0.766 MiB）。不跳过任何变化，不复制旧帧，不改变脏区合并的 GPU 字节内容。

旧 SPARSE_UPLOAD 诊断 bytes 来自排序去重前的 dirtyNodes.size，可能包含重复，不应直接当成实际 GPU 上传 bytes。实际数量应读取 work/index_uploaded_bytes 等累计差分。

### Import：四份临时属性与材质反复线扫

`rtxpt_memory_resources.inl::ApplyMemoryResources` 旧代码先分配并复制 position/normal/tangent/UV 四个 float vector，随后又复制/转换到最终 BufferGroup；每个 geometry 还 find_if 整批 materialInputs，决定是否需要 tangent；索引逐个 push_back。

本批 `RtrResourceReader::floatView` 借用当前同步 native 调用中的已验证 packet，不保留 JVM 地址。视图用 memcpy 访问未对齐 float，继续完整校验有限值与边界，最终位置、packed normal/UV/tangent 仍保存到自有 CPU/GPU 资源。索引按已验证区间 resize/copy 后检查顶点界限。材质查找使用本批权威 material map/normal set；旧 resident material 的 normalTexture 语义保持不变。取消四个临时属性 vector 的额外分配/复制，以及 geometry×本批材料数量的线性查找；未缓存已解析事务，也未改变异常重试。

### 实际失败：旧 512-float publication 读到未初始化响应尾部

首次真实 Mixed 检查抛出 `Invalid baseline or host response lookup value`。根因为 Donut `vector.h:45` 的 `vector() { }` 不初始化分量，而 7.2.1 的 640×float2 默认 lookup 只给前 256 项赋值。旧 5 参 publication 只覆盖 512 floats，新增的 384 对 response 尾部保持随机数据；JNI 对全部 640 项校验，因而旧调用可能被随机拒绝。上一批显式提供完整 response 的检查未覆盖这一旧入口。

新增 [rtxpt_host_response_lookup.h](../../renderer-rtxpt/src/main/cpp/rtxpt_host_response_lookup.h) 显式初始化每对 float2 后恢复原 16×16 baseline。新增 poison 默认构造 CPU 检查保证 384 对尾部全部定义为零。修后原 Mixed 用例通过，未放宽 lookup 校验，未更改用户响应表。

## 工作量与时延

| 优化项/同一输入 | 旧值 | 新值 | 绝对差 | 百分比 |
|---|---:|---:|---:|---:|
| 合成节点 32 次 word 改动的日志条目 | 32 | 1 | -31 | -96.88% |
| 每 mesh 四份临时 float 属性 vector | 4 | 0 | -4 | -100% |
| 每次释放空闲区的全表排序 | 1 | 0 | -1 | -100% |
| 8192 段碎片释放 CPU，最后一次单次观测 ms | 58.6108 | 0.4965 | -58.1143 | -99.15% |

最后一行是孤立 CPU 夹具单次观测，非平均/P95、非 MC FPS 收益。先前同代码一次为 58.8397→0.4739 ms；不挑最快值或拼成长期分位数。普通 first-fit 搜索仍可能线性扫描碎片；有序 map 的每个空闲区节点比原 vector 元素更大，内存开销没有伪装成已测下降。GPU 慢帧、宿主实际 FPS 和回收/上传常态时延须由主线实测。

## 定向验收与失败记录

- 新 CPU：50,000 次随机分配/释放逐步与旧 first-fit 完全一致；乱序全部释放归零、溢出拒绝；4096 个未对齐 float 与旧解码一致，NaN/截断仍拒绝；poison lookup 256 baseline 对和 384 tail 对通过。
- 受影响区域 oracle：25,000 源、2,198 查询、256 步移动；新增 dirty 唯一性、同节点 32 次写入、下一事务重入队、全量上传/重置通过；CPU 模拟 GPU 部分上传与全部可读 bucket/list word 相同。
- 拓扑检查：6 帧、3 信号、1025 行扩容/删除和材质/几何编号迁移逐字节一致。使用 lookup 修正前的 B36B DLL；该次结果保留原身份，不冒称最终 CA16 全部重跑。
- 最终 CA16 DLL：Mixed 共享两依赖、替换/alpha 还原、旧 512 lookup publication 和下一次局部提交通过；24 帧稀疏源 128→4096→删除3072、新帧身份与一次初始化通过，4 份新捕获。初始 GPU 0%；该检查用于正确性，没有做前后 FPS 对照。
- 最终稀疏检查：1726 staging 请求、24,241,748 bytes；record/index bytes 为 675,840/20,516,416。单写入≤1MiB，最大 staging allocation 2MiB。前者是 write bound，后者是内部页容量，不混淆。
- 新测试输出字符串转义导致首编译失败、直接 bat 启动命令引号失败均保留；未改生产语义来迁就夹具。真实旧 lookup 失败单列后修复，只补失败项。

原始定向输出及完整身份位于 `build/reports/flight-resource-hotspots/`，正式汇总 [data/flight-resource-hotspots.json](data/flight-resource-hotspots.json)。CMake 重配置重写了无关 NRD 和动态 shader cache；测试前从 7.2.1 发布包恢复其原字节。最终所有既有依赖/shader 与旧 runtime 一致，只有本批 JNI 实现改变。旧 NRD SHA256 为 `55b64cd8a144865c2b3c0bc03016db2eae93e6f73bd6e71c11e33316fae34bad`。

| 执行步骤 | 结果 | wall s | Job 峰值 GiB | 旧/差值 |
|---|---|---:|---:|---|
| 首 native + 新夹具 | 编译失败 | 38.434 | 0.860 | 不作性能对照 |
| 修正夹具增量构建 | 通过 | 9.534 | 0.562 | 不同任务，不算收益 |
| CPU/区域检查 | 通过 | 5.127 | 0.257 | 未测旧任务耗时 |
| 拓扑/Mixed 首次 | lookup 兼容失败 | 81.087 | 2.327 | 不作性能对照 |
| 修正 lookup 构建 | 通过 | 16.236 | 0.760 | 不同目标，不算收益 |
| 最终 CPU/Mixed/24帧 sparse | 通过 | 64.180 | 2.613 | 未测旧任务耗时 |

所有重任务串行，Windows Job 总上限 10GiB、Gradle 使用 --no-daemon，图形检查隐藏，不使用 computer-use。未运行旧亮度完整矩阵或历史性能矩阵。

## 未消除的大头与发布顺序

序列 6386 的 40.6736 ms STAGING_ALLOCATE 父链明确为 SUBMIT→RESOURCES→RESOURCE_STAGING→UPLOAD→TEXTURE_UPLOAD（1024B）→64KiB CreateCommittedResource。它不是稀疏大上传，也不能归因于本批已修的排序。驱动调用内部原因没有展开，本批未改该分配语义，也不将上一批 adaptive 当作它已解决的证明。

同一序列的 Import 在 arena pending 后还会重新解码；区域覆盖生成、真实资源索引和上传也未被完全消除。主线使用本批最终制品后继续快飞，不能保证回到 180/200 FPS。

本批全部生产源码、正式工具和文档已先在当前分支提交并上传 GitHub。API/core 发布时源码为 eab5c7c，最终包装与身份工具源码为 93f95b0763b7b7aa97b02bc8df40e48ac939c878，均先确认 push 成功。四模块 **7.2.2 Maven Local 已完成**；不覆盖 7.2.1，不发 Maven Central。发布后只补本节记录，不重发正确制品。

## 最终发布身份

正式 [Verify-FlightHotspotRelease.py](../../tools/Verify-FlightHotspotRelease.py) **38 项通过**：四模块 main/sources/javadoc 与 POM/module 均与 Maven Local 相同；543 个公开 API class 与 7.2.1 逐字节相同；1621 个实际 runtime 依赖/shader 文件与旧包逐字节相同；NVIDIA 21 个 native 文件未变；81 项 SDK 文件、版本和 shader ABI 正确。runtime 中 JNI 与最终受测 CA16 完全一致。

| 制品 | SHA256 |
|---|---|
| renderer-api main JAR | fcd22ffc4dc3fc6fbeed809e43e3bb596e4b3db8bc769863670e591829bd399c |
| renderer-core main JAR | a2e59ce9b4f84846827b3b0c255101fda9945e27d1379c38123c8129c55f3a69 |
| renderer-nvidia main JAR | 8e076fb5e98711e4ef599e47116f25c2c050fabc4ae8d4f6d09bdf03a0827f71 |
| renderer-rtxpt main JAR | 417b4abc0820d89ef248f42bb3a279b15adffbb634243e917315e96f62a40e6c |
| 内嵌 rtxpt-runtime.zip | b7da9590a9125553d39d84f54472d7beaf4594bde8e631d96bc34ca8ea86b163 |
| native symbols ZIP | db7a3d219eeeb0cbbb336ceff327a463ae1452c3d1ad145b6b6f576cb367104e |
| PDB | 1eb1981e044c448b69d79e122475963eb640b1597a838bb0f2967d28ac9030c2 |

Maven Local 根目录为 D:/.m2/repository/top/ceroxe/rt；C:/Users/Administrator/.m2 是其 junction。symbols ZIP 的构建/安装字节一致，PDB 与当前 CA16 构建目录相同。

NVIDIA 未改生产源码，原 DLL 与 7.2.1 发布包逐字节相同。按 package-only 复用现有 DLL，显式核对全部本机 SDK 契约文件与 RTXMU 固定 revision；只跳过 configure/compile，没有跳过 verifyPackagedNvidiaRuntime、shader 六预算或制品身份门禁。SDK 仅由进程环境传入，未下载或重建。

| 发布步骤 | 结果 | wall s | Job 峰值 GiB | 旧/差值 |
|---|---|---:|---:|---|
| 首 Maven 配置 | 缺当前 release-index，失败 | 6.779 | 1.307 | 任务不同，不算收益 |
| api/core 发布 + NVIDIA SDK 门禁 | 前两模块成功，缺 SDK 环境失败 | 26.874 | 2.086 | 不作性能对照 |
| 仅剩余 nvidia/rtxpt 发布 | 通过 | 77.675 | 1.434 | 未重发 api/core |
| 四模块正式身份核验 | 38 项通过 | 19.172 | 1.800 | 无旧任务对照 |

前两轮失败日志均保留。最后一次 ignored 包装脚本中，Windows PowerShell 无法自动加载 Get-FileHash，出现非终止辅助预检错误；外层发布前已核对精确哈希，发布后的正式 38 项核验独立确认全部已安装字节，未以该错误掩盖门禁或重发成功任务。Javadoc 的缺注释警告未作为测试失败。

这批仍没有新 MC 快飞 FPS，主线须使用本批完整身份实测。未把夹具改善写成已恢复 180/200 FPS。
