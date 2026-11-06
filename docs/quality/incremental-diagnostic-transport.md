# 增量诊断传输修复

2026-11-07，兼容次版本 7.3.0（新增公开接口）。保持当前 codex/resource-root-followup 分支和目录，不创建 worktree。

## 本次证据与边界

MC 7.2.3 人工运行在 G1 提交 2MiB 失败时退出；Job 峰值 9,519,140,864B，边界 9.5e9B。恢复 JFR 的全轮分配抽样权重 77.56GB，其中诊断调用栈 24.96GB（32.19%）；这是累计分配估计，不是常驻内存。资源包唯一 pool 峰值 capacity 6,029,312B、used 5,975,030B，不是此前全量重建的大包故障。

保守人工移动片段 2102 帧：46.7446FPS、owner mean 15.4357ms/P95 34.5911ms；暂停片段 8178 帧：272.64FPS、owner mean 0.8156ms。不是快飞 AB，不用暂停帧率冒充移动收益。区域更新等真实工作仍存在，诊断复制不能单独解释全部活动 UPDATE 或数GB private。

## 代码根因

CPU Monitor 在 Java 维护 event cursor，却只把 slow trigger cursor 传 JNI。native 每次复制最近8192条事件和1024条提交；Java 在宿主过滤前已分配全部 CpuEvent、SubmissionSample 和分布数组。两路独立监控重复承担这部分成本。慢组本来已按 trigger cursor 增量，不把它误报为每轮重复四组。

GPU 环存储4096条CSV文本，每次快照重新构建 string vector、JNI jstring，再由 Java strip/split 和数值解析。宿主在 owner 线程调用时，这些开销进入提交关键路径。

## 通用兼容实现

- Monitor.snapshot() 保留滚动快照；新增 poll() 真正传 event/submission/slow 三个 cursor，独立 reader 互不消费；missedSubmissions() 记录提交窗口缺口。
- v7 增量 header 明确提交 first/next ordinal；旧 JNI 签名和 v6 完整输出保持。generation、OFF、重置、覆盖和慢组完整性继续显式检查。
- GPU 环改存结构化数值记录；新增 diagnosticGpuMonitor().poll()，只返回未读 scope，missedScopes() 记录覆盖。创建和调用都在 renderer owner 线程；关闭后拒绝访问。
- 旧 diagnosticGpuSnapshot() 保持完整窗口，生产 Java 改用 primitive packet 解码；原 String[] JNI 仍兼容。CSV仅在实际文本导出边界生成，原始时间戳和资源64位身份保留。
- 不更改采样频率、渲染或光照算法，不实现尚无净收益证据的pool收缩。

## 定向验收

本批诊断 Java CPU、native 合同、隐藏 monitor/GPU 端到端已通过；源码和制品发布身份另行记录。旧通过光照矩阵不重跑。

| 检查 | 结果 | 全程耗时 s | Job 进程树峰值 GB（10⁹B） |
|---|---|---:|---:|
| Java 诊断 CPU | v2/v3/v5/v6 兼容、v7、GPU 数值/身份、slow/enum 通过 | 22.958 | 1.643 |
| native 增量构建 | JNI 与诊断合同目标通过 | 72.058 | 2.843 |
| native 诊断合同 | OFF 零开销、并发/覆盖/慢组、独立增量 reader 通过 | 0.745 | 0.109 |
| 隐藏 native monitor | 并发、failed、OFF/恢复、关闭/重建、真实满环冻结读取通过 | 156.762 | 3.288 |

### 冻结满环读取成本

同一进程，96×54 隐藏场景填满 CPU 8192/GPU 4096 历史，OFF 并 drain 后冻结；8 次预热、32 次测量，P95 为排序后第31个样本。GPU 初始占用10%。Java ThreadMXBean 只测读取线程分配量，不代表进程驻留。对照是同一修复包内完整窗口与增量窗口，未切回7.2.3整包；CPU完整窗口仍使用原滚动语义，GPU旧CSV JNI和decoder保留作读取对照。

| 项目/指标 | 旧值 | 新值 | 绝对差 | 百分比 |
|---|---:|---:|---:|---:|
| CPU 完整窗→空闲 poll，B/read | 3,733,386 | 1,392 | -3,731,994 | -99.96% |
| CPU 均值，µs/read | 2489.928 | 10.425 | -2479.503 | -99.58% |
| CPU P95，µs/read | 3339.4 | 26.2 | -3313.2 | -99.22% |
| GPU CSV→完整 packed，B/read | 6,068,328 | 1,000,696 | -5,067,632 | -83.51% |
| GPU CSV→完整 packed 均值，µs/read | 7420.766 | 638.284 | -6782.481 | -91.40% |
| GPU CSV→完整 packed P95，µs/read | 9894.2 | 1320.3 | -8573.9 | -86.66% |
| GPU CSV→空闲 poll，B/read | 6,068,328 | 1,200 | -6,067,128 | -99.98% |
| GPU CSV→空闲 poll 均值，µs/read | 7420.766 | 5.153 | -7415.613 | -99.93% |
| GPU CSV→空闲 poll P95，µs/read | 9894.2 | 11.8 | -9882.4 | -99.88% |

两路空闲 poll 均返回0条；native CPU空窗21words，旧滚动不被消费，eventGap2824/submissionGap200 明确可见。数据见 `docs/quality/data/incremental-diagnostic-transport.json`。构建 stdout 存于执行会话回执，未留下独立 `.log` 文件，不虚构日志文件身份。

静止/移动 FPS 的修复后值仍未测；上述读取成本不能换算 MC FPS、任务管理器内存或完整 UPDATE 收益。新旧客户端快飞由 MC 宿主后续验证。

### 使用方式

CPU `diagnosticMonitor()` 可在独立 watchdog 线程读取；注册后用 `poll()` 获取未读 events/submissions/slow groups，active scopes 每次仍为未完成观察。`snapshot()` 继续是完整滚动窗口，和 `poll()` 的游标互不消费。分别检查 snapshot 的 `missedSinceRegistration()`、`missedSlowFrameGroups()` 与 `monitor.missedSubmissions()`。

GPU `diagnosticGpuMonitor()` 在 renderer owner 线程创建并 `poll()`；`missedScopes()` 报告未读记录被覆盖的数量。它不承诺后台线程访问，也不阻塞等待GPU查询；关闭 renderer 后再 poll 会经 `ready()` 拒绝。完整 `diagnosticGpuSnapshot()` 接口继续保留。

本次意外无SDK参数的Gradle配置曾清理NVIDIA staging；已用完整SDK参数恢复并重建，发布检查核对当前实际字节并报告与7.2.3差异，不预称NVIDIA DLL逐字相同。

### 提交前打包身份

四模块 assemble、sources/Javadoc、POM/module 打包通过，wall 223.377s、Job峰值2.065GB。正式工具 `tools/Verify-IncrementalDiagnosticsRelease.py` 的 package-only 47项通过，wall38.947s、峰值1.804GB：受测源码/108个RTXPT类/3个测试类、实际fixture/cost、JNI/PDB age264、四模块类/源码/元数据、81项SDK与1621项RTXPT执行依赖和shader均核验。核验工具首次把现有NVIDIA清单误按双空格切分，修为通用空白分隔后仅重试本次失败核验，没有重跑通过的渲染检查。

- JNI `1f37ad395dacc5a9745c9208a18b43f26b5f1972e46e1380742c8ebd5861e749`
- runtime ZIP `cb17ddb97e85a9bf57ea2a52ed991fa535a7c48fa9028dc7fab9e1522f1ddba0`
- RTXPT JAR `e281bdb65eaffc1cb1d0402b61244fcde1a8b8c7c5c2eb0712caa703a7ecf413`

NVIDIA 21项runtime库存保留；仅重建的 `rtrenderer_nvidia.dll` 和 `runtime-files.sha256` 与7.2.3不同，当前NVIDIA DLL为 `d8067ff0102895ec8bddff24065ffa53afdd1b582b7b7d0beb5f561980ff6633`。所有RTXPT执行依赖/shader保持旧包字节，不把NVIDIA重新链接误写为全包不变。

### 最终 Maven Local 交付

已按授权先将完整源码/正式工具/文档推送 GitHub，源码提交 `65810faff901330c455ff9f9a2550023dc2d7413`；随后四模块 Maven Local 7.3.0 发布通过，wall209.636s、Job峰值1.185GB。发布显式跳过已完成的NVIDIA configure/compile，仍提供完整SDK参数；没有再次运行已通过的CPU/GPU检查。

最终 `--local` 69项逐字核验通过，wall32.486s、峰值1.804GB。正式记录为 `docs/quality/data/incremental-diagnostic-release.json`。发布任务刷新SDK归档，因此最终RTXPT JAR/ZIP与提交前package-only身份不同；受测JNI、生产类与1621项执行依赖/shader未改变，以本表为最终消费身份：

| 制品 | SHA256 |
|---|---|
| renderer-api 7.3.0 | `fcd22ffc4dc3fc6fbeed809e43e3bb596e4b3db8bc769863670e591829bd399c` |
| renderer-core 7.3.0 | `a2e59ce9b4f84846827b3b0c255101fda9945e27d1379c38123c8129c55f3a69` |
| renderer-nvidia 7.3.0 | `39b9f11445013704465776361dabccadc6a381e35d2923970ecd306266835095` |
| renderer-rtxpt 7.3.0 | `5ab1f06570ec28536241bd2dc540f37761415440aa41b35374197c33b568aa98` |
| RTXPT runtime ZIP | `b32313dbe767c34d600ad56b302ecd5d28e51e51766f6656966dec92f43f8538` |
| RTXPT JNI | `1f37ad395dacc5a9745c9208a18b43f26b5f1972e46e1380742c8ebd5861e749` |

最后仅补发布回执文档，不重建或重新发布成功制品。未发布Central或GitHub二进制，未开MC客户端或使用computer-use；MC快飞、任务管理器内存与人工验收由宿主继续完成。
