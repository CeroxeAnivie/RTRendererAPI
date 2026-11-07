# 原生资源所有权收口

当前候选 7.3.5；固定对照为正式 7.3.3。7.3.4 已有本地制品，不覆盖，也不作为本次交付版本。当前尚未完成流式对照和发布身份核验。

## 代码根因与改动

- NVRHI 已经提供持久映射、提交版本和完成队列驱动的上传页复用。原项目自定义的提交后裁池以及每个 recording 重置的自适应尺寸，反而打断了上游复用。删除自有上传 arena、保留队列与相应补丁；稀疏和属性数据直接交给原生 writeBuffer。使用公开的 1 MiB chunk 参数。
- mutable BLAS 的 result、scratch 和 update 内存交给 NVRHI 内建的 RTXMU 管理器。删除外部 BLAS heap 租约、备用缓存与回收记账；保留稳定 NVRHI handle、双队列次序和事务期 handle 传递，不新增第二层管理器。
- 上游当前把 AS 内存分配放在录制 build 时，不满足图发布前的失败准入。补丁仅在同一个 RTXMU 管理器中拆出 prepare 和 record，使 native handle 创建时完成容量准入；失败释放原管理器中的未提交 ID。
- 对现有 NVRHI UploadManager 增加容量预备入口，并由验证层转发。图发布前预备表上传空间，预算拒绝清理尚未发布的 BLAS、buffer 租约与 TLAS 状态。原生分配失败抛出具体 HRESULT/请求字节；临时预算不足不能永久停止后台 heap worker，也不能丢弃上传后报告成功。
- RTXMU 未实现的 opacity micromap、sphere 和 swept-sphere 能力明确不可用，相关输入被拒绝，不静默更改几何。

## 内存与残留职责

Resource 与 Upload 原生准入域均为 2 GiB，没有提高硬上限。Resource 计数覆盖保留 buffer heap、RTXMU result/scratch/update block 和 NVRHI scratch；Upload 覆盖原生上传块。它们不等于进程全部内存。任务仍受 10 标签的 Job 限制，实际子树硬上限 9,500,000,000 字节，宿主预留 500,000,000 字节。

原生 upload 的完成且空闲缓存软目标为全局 64 MiB；先匹配复用，随后才裁掉多余空闲块，不碰 current 或 in-flight。软缓存留存策略变化须与硬预算区分。旧自有 BLAS/arena 诊断槽保留二进制长度但返回零，这表示原分配口径不再适用，不能解释成真实 AS 内存为零。

普通 vertex/index 与 TLAS placed-buffer 切片同样调用 RTXMU 的通用 Suballocator 模板。删除本地 bitmap、best-run/free-run 搜索与重复 heap worker；仅保留 NVRHI heap Block、原生预算准入、事务回滚和最后 GPU reader 的切片租约。每个切片仍绑定独立 placed IBuffer，不创建同一底层 buffer 的多重 wrapper，不更改既有表 range。上游元数据在其实现内统一持有，free 后仍可查询 isFree，最终 release 只在不再持有该记录时销毁。光照计算、质量和本帧输出语义不改。

## 当前验证

当前原生构建通过，Job 峰值 6.689 GB。预算自测通过：AS prepare 拒绝后恢复；upload prepare/record 拒绝时不提交，归还额度后复用同一 command。8 轮每轮 4,608 次 4 KiB 写入、18 MiB GPU 逐字读回通过；首轮 18 次 native chunk 创建，后 7 轮均为零。容量增长/缩小/振荡与 25,000 源、2,198 probe 区域 oracle 通过。这些是正确性与复用机制证据，不是整体吞吐收益。

后续同一份测试字节覆盖 637 笔事务、1,387～2,910 resident mesh、5,444 mesh upsert 与观测到的源规模，并独立到 3,662 mesh、3,318,040 triangle 终点及关闭重开。几何/身份/源时刻是通用合成；未知删除类别仅保留未分类观察计数，不额外制造材质。冷启动、预热、静止、流式和终点分别报告，均值与 P95 分开。尚无本候选整体性能结论。
