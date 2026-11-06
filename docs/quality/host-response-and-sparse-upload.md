# 宿主亮度响应与稀疏上传修复

本批仍是当前分支未发布的 7.2.1，不切分支、不新建 worktree。旧默认光照和旧 compact 字节含义保留。下面把代码机制和实测结果分开；不以工作量变化冒充 FPS 收益。

## 宿主亮度响应

- 新增 NativeLightingHostResponse：16×16 RGB，768 个有限 [0,1] 浮点数，索引 (sky*16+local)*3+channel，坐标乘 15 后双线性采样。宿主提供已解析的本帧响应，不要求 API 解释宿主时间、天气或 gamma。
- NativeLightingPublication 新增尾部 hostResponse，原五参数构造保留；null 保留已有表。表发布独立于 scene revision，不重建 mesh/BVH，也不占 applicationData。HOST_RESPONSE_RATIO_V1 必须先有显式响应表，否则提交报明确错误。
- 进入静态源的正值遮挡前支撑范围时，以可见源组合 local 替换原生局部等级；没有静态候选时用 max(baseline,dynamic) 保留未捕获的烘焙光。静态覆盖在遮挡前判断，不把被遮住的静态源误认为缺失。
- 天空等级按当前有效 sun（优先）或 moon 权重及逐通道可见度调整。结果是目标响应/原始响应，visibility 与 fog 只收敛一次，不使用旧 12 倍增亮及末端高光压缩。
- COMPACT_RESPONSE_UNORM8 保留中性码 128，把高段扩到 255 倍：128+127*log(gain)/log(255)。旧 COMPACT_UNORM8 最大 15.65 不变。严格黑底不能靠乘法恢复非零；零/零为 1，零到正值使用声明的有限 gain 上限。
- SparseLightSource.withVisibility(CENTER_SPHERE_SUPPORT_V1,radius) 把阴影球半径与 BOX 发光范围分开，允许不同对象共用相同距离曲线。旧构造和旧 withVisibility 仍使用原半径规则。

原生 t10 的前 256 个 float2 仍是 baseline；后 384 个 float2 装 RGB 响应。Java/JNI 接受旧 512 或新 1280 个浮点数，缓冲与上传实际大小同步扩大。稀疏源保持 96 字节，独立半径放在非 SPOT 的 cone.x，并由 flags bit16 明确声明。

## 已有性能证据

宿主采样目录为 MCVulkanRT/build/renderer-audit/manual-lighting-performance-root。完整 API submit 中，17014 个 STEADY：平均 0.7938ms、P95 1.1589ms；3766 个 UPDATE：平均 4.6434ms、P95 10.6066ms。初始 GPU 2%，但另一个 fullRegression 从 21:52:31 起覆盖本次 22:10～22:13，CPU 竞争存在；不能把本轮绝对 FPS 全归给一处算法。

| 更新帧 | 完整提交 | 关键独占 wall | 同 scope 线程 CPU |
|---|---:|---|---|
| 20189 | 292.8354ms | SparseRegions 112.6681ms；RegionPublish 31.7012ms；SparseQueryPreparation 25.6265ms | 109.375／31.25／31.25ms |
| 20191 | 122.383ms | 249 次 StagingAllocate 累计 61.4047ms | 累计 31.25ms |
| 20169 | 180.1104ms | InstanceCache 内 TaskWait 163.8403ms | 等待线程 0ms |

Windows 线程 CPU 是 15.625ms 粒度。父子 inclusive 不能直接相加；表中只列各自 self。20189 的区域维护确有 CPU 工作；20191 同时含驱动/调度时间；20169 为工作任务等待，不冒充资源命令分配算法成本。

保留事件中 821 次 StagingAllocate、累计 179.5854ms、84,320,256 字节，父链均为 SparseUpload→StagingAcquire→CreateCommittedResource。20191 有 1537 次 copy、18,522,784 字节，249 次 committed 创建、17,305,600 字节；默认 64KiB 小页在长 recording 中不断耗尽，超过每命令池 4MiB warm 的页在 fence 后释放。

## 修改范围与机会边界

1. NVRHI 非 scratch 上传页只在本次 recording 连续耗尽且无可用复用页时几何增长，64→128→256→512→1024KiB；上限 max(default,1MiB)，原大默认值不缩小，小任务不预先扩容。4MiB warm 和 fence 退休语义不变。同制品旧路径开关为 RTR_RTXPT_DISABLE_ADAPTIVE_UPLOAD=1。按 18MiB 冷写入的机制模拟，预计从 288 次小页创建降到约 22 次，非时延测量。
2. 区域根的成员/所有权随拓扑子链接变化重建，普通 bounds/refit 复用已存成员。仍计算本帧 coverage、更新真正变化的格和 GPU 数据；不复用旧帧。每 treelet 最多 15 节点，此项只消除确定重复工作，不能单独解释 112ms。
3. 原生命令回收修复：逐对象淘汰可在 Available() 后填满 reclaimer；旧代码已把命令移动入池、随后 Enqueue 失败，下一次重试再把空句柄入池。现在命令只交还一次、取放池过滤空句柄、创建失败明确抛错。主代理独立故障注入 10000 次重试通过，旧逻辑可必现 null；这不是 OOM。

## 本批验证

新增/受影响检查已完成，全部隐藏或无窗口。最终JNI SHA256为b7faab55677bc5a16806ab00a66c6d2a00a221029e17ed7b5ccfea69b03537ee；API ABI为16d0c753bb7adc5e7538843d39b62285ebc93cf8c0f0dde02d8e088db3ee6571。机器可读证据见docs/quality/data/host-response-and-sparse-upload.json。

- CPU：响应768组、新compact合同、独立radius/copy/packing31项、ABI通过。
- 最终DLL新增7帧：缺表报错、静态/动态一致、radius不改衰减、缺静态保留、遮挡静态替换、仅表更新不重建BLAS、实际compact packet通过。GPU宽编码最大相对误差0.02205597＜0.023。
- GL：HDR/旧compact/新response compact三编码，8x4、9x5到19x11，共3762个RGB数值检查通过。
- 区域oracle：25000源、2198 probes、256步travel、成员复用及本帧脏区GPU上传通过。
- 同一最终DLL，ABBA各24帧，共96帧，增长128→4096、移除3072、移动与fence退休通过；12份实际输出对A1逐字节完全相同。两路径每窗1726次acquire、上传数据和场景语义相同。
- 新rtxpt_upload_pool_selftest直接用实际NVRHI D3D12 writeBuffer：每trial4608次4KiB，共18MiB，逐字GPU读回；ABBA32个trial全部正确，没有创建窗口。

### 工作量与代价

| 项目 | 旧值 | 新值 | 绝对差 | 百分比 |
|---|---:|---:|---:|---:|
| 真实18MiB碎写committed页 | 288 | 22 | -266 | -92.36% |
| 同碎写实际创建bytes | 18,874,368 | 19,857,408 | +983,040 | +5.21% |
| 原24帧夹具创建页 | 25 | 25 | 0 | 0% |
| 原24帧夹具创建bytes | 19,070,976 | 20,512,768 | +1,441,792 | +7.56% |
| 原24帧夹具retained bytes | 4,259,840 | 5,701,632 | +1,441,792 | +33.85% |

原24帧场景大多是1MiB写，不是原MC慢帧1537次copy/249次小页的碎写形状，所以创建页数没有下降，内存与分配时间退化照报。新增18MiB夹具才验证实际小页链；CPU模拟不代替实测。

### 时延观测

以下均为ms；GPU初始24帧夹具99/38/48/49%，真实碎写47/52/44/48%，均超过10%。这里只报告同制品短窗观测，不归因为MC FPS收益，不把copy/fence叫作GPU shader时间。

| 边界 | 统计 | 旧值 | 新值 | 绝对差 | 百分比 |
|---|---|---:|---:|---:|---:|
| 真实碎写CPU录制，含实际分配/写入 | 平均 | 47.5724 | 14.3989 | -33.1735 | -69.73% |
| 同边界 | P95 | 79.7037 | 20.1568 | -59.5469 | -74.71% |
| 同碎写至GPU copy/fence完成，不含读回CPU循环 | 平均 | 107.1575 | 74.2085 | -32.9490 | -30.75% |
| 同边界 | P95 | 139.0064 | 80.0694 | -58.9370 | -42.40% |
| 原24帧完整Java submit，去掉初始化，每组46帧 | 平均 | 6.3347 | 4.5277 | -1.8070 | -28.53% |
| 同边界 | P95 | 24.2265 | 20.0661 | -4.1604 | -17.17% |
| 原24帧StagingAllocate self，含初始化 | 平均 | 0.5477 | 0.8886 | +0.3408 | +62.23% |
| 同边界 | P95 | 0 | 0 | 0 | 不适用 |

碎写每组16个trial，nearest-rank P95恰是该组最大值，不当作长期P95。原夹具仅初始化时分配，其余23帧分配为0；普通移动观测差不能单独归因自适应分配。区域复用两边都开启，ABBA并非它的开关对照。含初始化完整submit平均658.3527→396.9430、P95 46.3076→31.8322、最大18278.8417→10179.9854；初始化与后台竞争占主导，不当稳态收益。

### FPS与身份

本API批次未测MC静止/快飞FPS，180/200FPS均未证明恢复。宿主旧日志还存在另一fullRegression并发；主线使用最终制品后另测本批改动，不能用此夹具代替快飞。

首native构建因新增getenv触发MSVC /WX C4996，改_dupenv_s后通过。旧模式半球背面早退恢复，避免额外算衰减；新recording首次页恢复64KiB有单独断言。初次7帧后共享bin的DLL曾消失，外部原因未确认；未取旧包，同源码/OBJ重链接后重新验最终7帧/96帧，B7FA身份稳定。PowerShell启动、初始化脚本和旧“两模式”断言失败保留日志，修后只补失败项。

全部串行、10GiB Job、Gradle --no-daemon。native主重建178.447s/6.486GiB；头文件/区域增量10.530s/0.742GiB；GPU+GL55.377s/2.069GiB；96帧ABBA132.488s/2.466GiB；真实碎写ABBA5.790s/0.260GiB。未重跑历史完整矩阵。

六预算shader包1143个上游排列、所有已捕获配置、源码与命令身份核对通过（45.065s/0.127GiB）。源码/正式工具/文档已在原分支codex/resource-root-followup完整提交e14289c161061820cc7d7fb509f5703be581423b并上传GitHub，随后四模块7.2.1已发布Maven Local。发布233.790s、Job峰1.497GiB；28项包/源码/文档/POM/module/SDK与native身份检查通过，包内JNI仍为最终已测B7FA字节。生成制品未上传，不切分支、不新建worktree。

## 最终发布身份

| 主JAR | SHA256 |
|---|---|
| renderer-api | fcd22ffc4dc3fc6fbeed809e43e3bb596e4b3db8bc769863670e591829bd399c |
| renderer-core | a2e59ce9b4f84846827b3b0c255101fda9945e27d1379c38123c8129c55f3a69 |
| renderer-nvidia | 8e076fb5e98711e4ef599e47116f25c2c050fabc4ae8d4f6d09bdf03a0827f71 |
| renderer-rtxpt | f04ecd0581cf4b0b6a9d10bd6a161d838d7b22baf8e083c642b42974cd143144 |

运行时ZIP SHA256：afff76bd4cf9788e03e59802629e223de371147f375c8cb045fa021c11099166。完整分类包、大小和28项清单已写入对应JSON。首次身份脚本误把SDK backend源码当作动态源码路径，按正式ZIP契约纠正后通过；制品没有为此改动或重发。Javadoc既有缺注释警告不阻塞发布，不作新的完整回归通过声明。
