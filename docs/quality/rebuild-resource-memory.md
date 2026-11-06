# 全量重建资源包内存修复

2026-11-07，内部兼容修复 **7.2.3**，保持原分支 codex/resource-root-followup，不创建分支或 worktree。最终受测 JNI：

65124a82cb6f39dc7a1799b09292e08d3ac6bc2509ba0e8d7c489cb3049bd393

## 本次失败边界

7.2.2 主线同一地形快飞后，关闭 RT 截原生图，再重建 RT；第二次初始化在 RtxptNative.sparseLighting 抛 bad allocation，后续 renderer closed 是清理后的诊断异常。该 JNI 入口只做 CPU vector/稀疏事务，GPU buffer Ensure 在后续 frame；没有 GPU OOM 证据，也没有具体失败容器的分配栈。

原始主线证据位于 MCVulkanRT/build/renderer-audit/mainline-hotpath-clean-flight，父链和内存证据见其中 api-slow-root-evidence.json、rebuild-memory-evidence.json、jfr-buffer-evidence.csv。进程树提交峰值 9,504,124,928B，wrapper 上限 9.5e9B；客户端 private 峰值 8.121GB、working set 5.501GB。最后 GC 后 heap used 1.783GB/committed 2.967GB，不能把 private 全算 Java 活对象。

旧 native 关闭后 private 下降约 1.58GB；源码 shutdown 会 waitForIdle、app.reset、删除 Session，reclaimer 析构 join，不能把这次说成两个 native Session 并存。末次旧 session 退休代和 pending release 都为 0。

全 JVM DirectBufferStatistics 容量在重建前为 36,410,172B，重建中 572,911,900B；新大包幂次预留 512MiB 与此相容，但旧 capture 未记录具体 pool capacity 或全量 packet used 字节。RESOURCE_PLAN 的 160,974,560B 是 native planned GPU payload，不是原始序列化包长。**本次不能给出不存在的单包实测尺寸，也不能说修复解释了全部 8.121GB。**

## 通用实现

- PacketPool 原来 allocateDirect，增长和 clear 仅丢 Java 引用，依赖 Cleaner。改用模块已固定 JDK25 的标准 Arena；不用 Unsafe、额外 native allocator 或 System.gc。
- pool 明确拥有 native storage。resourcesMemory 进入同步 JNI 前借用、退出或异常时归还；借用未结束时拒绝清空、增长和覆盖。pending admission 重试保留同一不可变 packet。测试通过真实 scope 失效确认内存已关闭。
- 大于 1MiB 的包按 64KiB 对齐，额外空间小于 65,536B；小包保留现有复用档位。增长先关闭无借用旧块，再创建新块，异常后 pool 可安全清理。
- full rebuild 的 resourcesMemory 成功后，native 已完成同步借用并拥有自己的拷贝，立即释放整包；不再让它跨到 sparseLighting、区域索引和属性初始化。不能用这一释放点丢弃 pending retry 或提前释放 JNI 正在读的地址。
- reset 不分配完全不用的 128*N 增量祖先日志、不写无用 dirtyRecords。BuildLightTreeInto 复用目标 nodes，保留原空间分割、节点顺序、归一化和所有源。
- 新事件 top.ceroxe.rt.renderer.rtxpt.ResourcePacketMemory 提供 action/pool/usedBytes/capacityBytes/releasedBytes，诊断 OFF 不生成事件。Arena 内存不属于 JVM DirectBufferStatistics 池，后续必须同时看该事件和进程提交/任务管理器内存，不能拿统计池变化冒充总内存收益。

## 三组对照

| 场景 | 旧值 | 新值 | 绝对差 | 百分比 |
|---|---:|---:|---:|---:|
| 主线静止 FPS | 本报告未取该窗口 | 未测 | — | — |
| 主线快飞 FPS | 7.2.2 观测 75.60，整轮失败 | 7.2.3 未测 | — | — |

| 修复项 | 旧值 | 新值 | 绝对差 | 百分比 |
|---|---:|---:|---:|---:|
| 257MiB+1B 包容量示例，非本次实测 | 536,870,912B | 269,549,568B | -267,321,344B | -49.79% |
| 27,376 源 reset 无用 dirtyNodes 预留 | 14,016,512B | 0 | -14,016,512B | -100% |
| 27,376 源 bulk 临时 nodes | 3,504,064B | 0，复用目标 | -3,504,064B | -100% |
| 成功 full 包存活终点 | 后续初始化完成 | native 同步提交成功 | 未测实际峰值差 | — |

这些是机制和容量，不是 MC FPS 或真实进程峰值收益。保留全部源、覆盖与同帧语义；公开 Java/JNI/shader ABI 不变。

| 执行步骤 | 旧值 | 新值 | 绝对差 | 百分比 |
|---|---|---|---|---|
| 受影响 Java CPU | 无同任务旧对照 | 19.598s，Job峰 2.082GiB，通过 | — | — |
| native 增量构建 | 无同任务旧对照 | 21.910s，0.854GiB，通过 | — | — |
| bulk/空间/区域/Mixed/Sparse | 旧批覆盖范围不同 | 86.827s，2.574GiB，通过 | 不算收益 | — |

4组 Java 检查通过，涵盖真实 Arena scope 失效、JNI borrow 保护、pending 重试字节、异常归还、精确对齐和诊断 OFF=0。27376 源连续4次 bulk 同地址/容量且节点 bytes 一致；22000源/320 receiver 覆盖 oracle、25000源/2198 query 区域 oracle 通过。

最终 DLL 的隐藏真实 Mixed（共享依赖、替换/alpha还原和下一次局部提交）和24帧 Sparse128→4096→删除3072通过，同帧输出、一次区域初始化。初始 GPU 0%；最大单 write 1MiB、staging allocation 2MiB，1726次/24,241,748B；没有把这些正确性数据写成性能收益。1027个本地既有运行依赖/shader 在测试前与7.2.2逐字节相同；正式打包还会核对完整闭包。

没有重新跑旧完整亮度/性能矩阵。首次只读 CMake cache 选择器因大小写匹配不到而中止，未启动构建；改用正式脚本已有 build-host 路径，无 SDK 下载或重新配置。

## 发布与剩余项

本批全部源码、正式工具和文档已先推 GitHub（d9e867d657670b2dbd4165f2e5491436d63652fb），随后四模块 Maven Local 7.2.3 已完成；不覆盖7.2.2，不发Central。发布后只补记录，不重发成功制品。

MC 7.2.3 重建和性能未测，不能预签故障消失或180/200FPS。下次 MC 是用户人工窗口验收。移动热点仍包含真实上传页分配尾部、区域拓扑/覆盖工作、mesh import/索引，不能把本批内存修复当作这些耗时已消失。

## 最终制品身份

正式 Verify-FlightHotspotRelease.py 对比7.2.2，38项通过：四模块main/sources/javadoc与POM/module匹配Maven Local；543个公开API class逐字节不变，1621个runtime依赖/shader及NVIDIA21个native文件逐字节不变，81项SDK清单/版本/ABI正确。JNI等于本批最终受测65124。

| 制品 | SHA256 |
|---|---|
| renderer-api main JAR | fcd22ffc4dc3fc6fbeed809e43e3bb596e4b3db8bc769863670e591829bd399c |
| renderer-core main JAR | a2e59ce9b4f84846827b3b0c255101fda9945e27d1379c38123c8129c55f3a69 |
| renderer-nvidia main JAR | 8e076fb5e98711e4ef599e47116f25c2c050fabc4ae8d4f6d09bdf03a0827f71 |
| renderer-rtxpt main JAR | 93d7e02aa4c55962e60f113eb7238ae3f4cddd30dfcbefee33ee50bfa96aebdf |
| runtime ZIP | 83de423bac82c088b23e2e9e31c589b0b4069543788d7690e8ce34339d92ec60 |
| native symbols ZIP | be4bbc369c439d32b8dc19656f065be63d461ffa2666d979cc9100deba9046d1 |
| PDB | c40762d0a60bfb464dd1cb45d72a000e97706ca5fff94b2a7df81910c9f4055f |

Maven Local 根目录为 D:/.m2/repository/top/ceroxe/rt。symbols ZIP 的构建/安装字节一致，PDB与当前受测构建目录相同。NVIDIA未改生产源码，package-only复用已发布相同native字节，正式闭包门禁保留，SDK仅经进程环境传入。

| 发布步骤 | 旧值 | 新值 | 绝对差 | 百分比 |
|---|---|---|---|---|
| 四模块发布 | 无同任务旧对照 | 92.538s，Job峰1.716GiB，通过 | — | — |
| 正式身份核验 | 无同任务旧对照 | 14.933s，1.801GiB，38项通过 | — | — |

构建输出中的Javadoc缺注释警告不影响任务成功；未为此重复已通过检查。原始证据集中在 build/reports/rebuild-resource-memory。当前分支未切换，没有worktree或启动器部署。
