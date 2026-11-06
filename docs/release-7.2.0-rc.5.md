# 7.2.0-rc.5：固定区域布局与有界 sparse 上传候选

本候选修复真实宿主完整慢组暴露的同一条链：区域 hash 增长或墓碑压缩触发整池重包，随后 16MiB+256B 的 writeBuffer 使常规 16MiB staging chunk 无法容纳，转而在提交线程创建大 committed upload resource。原证据 session 2 / sequence 105 / revision 38 的完整提交为 53.5655ms，其中 SPARSE_REGIONS 19.7680ms，STAGING_ALLOCATE 21.9332ms。该次 GPU 初始占用 50%，这里只定位实际工作和代码路径，不据其 FPS 判断收益。

区域 hash 按现有 CellBudget 一次保留 262,144 槽，global 前缀和列表地址固定；普通覆盖变化保留原链接并只修改差集。删除采用 backward shift，另行跟踪 16 探测溢出项，在出现空槽后重插相关 home 桶的溢出格；保留原精确整树 fallback，不能因提前遇到空槽漏光。全量初始化只用于真正初始化或显式重置，普通移动、列表增长和删除不再重包区域池。全部 sparse 大写入拆成不超过 1MiB 的 writeBuffer，避免单次请求超过常规 staging chunk。常规 in-flight 压力下仍可能需要分配普通 chunk，后续以日志实测为准。

CPU 区域 packed reserve 上界为 51,380,224 字节（49MiB），实际列表仍按需增长。只有启用区域索引时，GPU source 与 support-index 两个 buffer 按已有源/区域上界保留，合计 125,829,216 字节（约120MiB）；未启用区域的小场景沿用原按需容量。首次激活时旧 buffer 保留到既有 graphics/compute fence 完成，不能提前回收。上述数值是这几个明确资源的容量上界，不是进程总 commit 或显存实测。

新增 SPARSE_REGION_INITIALIZE、SPARSE_HASH_ERASE、SPARSE_HASH_RECOVER、SPARSE_REGION_PUBLISH 和 SPARSE_UPLOAD scopes，后者区分全量初始化与常规池路径并记录区域字节数。已有光源、可见性、GI、真实 RR、分辨率、同帧语义与完整计时边界不变。

定向 CPU/原生/Java 及输出对照、制品身份见 `build/reports/resource-root-followup/rc5-check/` 与 `rc5-package/`。rc.4 保留供真实宿主同入口对照；只发布 Maven Local，未证明 FPS 或长尾收益前不升级默认依赖，不作为正式 7.2.0。
