# 7.0.5 帧准备与离屏输出定向验收

2026-11-03，Windows x64、Java 25、RTX 5080 Laptop。按用户要求优先完成定向验证与 GitHub／Maven Local 交付，不跑全量矩阵，不启动消费方。测到的外部 GPU 基线曾为 43%／18%，不报告 FPS 或跨轮整帧提速倍数。

| 修复范围 | 验证与结果 |
| --- | --- |
| 短任务调度 | 1／2／4／8 worker；元素 0、1、3、63、64、256、4096、100000；串行内容、索引、异常传播、独立批次、关闭与有界记录通过 |
| 材质准备 | RR、SR＋NRD、native 的名称、纹理、MASKED／TRANSLUCENT 切换，12 次增量均无新 DXC／PSO，首批 PSO=6；实测提交 30.765～103.275ms，包含导入／上传／GPU 交接，不是纯 shader 时间 |
| 静态缓存 | 256 实例，连续 16 次未变提交均无额外帧任务派发；姿态／资源／材质修改使 generation 失效 |
| 离屏与消费 | 三条重建路径；960×540、640×360、2560×1600、641×361、3001×1703、返回 960×540 均精确；真实 Vulkan 消费／completion；每次 resize 都有新 completed sequence |
| 发布制品 | 从真实 JAR 的全新外部缓存启动，严格禁用运行期编译；模拟损坏一个 shader 后从 JAR 自动修复；非源码树 runtime override |
| 资源增量与图像 | 同版本前一候选 JAR 的 RR 资源增量／reset 参考通过；block MAE=0.195795／255，mean error=0.011204／255；12 个资源批次仅重建 1 个受影响 BLAS，保留 59 个 BLAS，8 次纹理分配复用 |
| 透明合成 | 同版本前一候选 JAR 的 RR 原生回交＋Vulkan GPU 背景／UI：与同帧线性公式最大误差 1／255；MAE=0.000253／0.004835；不是跨帧图像比较 |
| 十万灯 | 实际 GPU 统计：100003 灯、200005 树节点；436 接收点、27468 选灯、483423 访问节点；新增／修改／移除后无重复几何导出。严格包首次发现 63 样本诊断配置缺排列，已补入完整六预算包后复验 |
| 诊断 | 同帧 sequence／revision、owner／worker、cache／compile／library／PSO／SBT、任务等待／消息泵及实际 GPU command scopes；OFF 停止新记录，查询非阻塞收尾；8192 事件覆盖与最近慢帧保留测试通过 |
| 合同与符号 | renderer-api ABI／生命周期、adapter、预算、版本合同通过；DLL 与 PDB 的 CodeView GUID／age 严格匹配；PDB 通过 native-symbols ZIP 交付 |

上述资源增量与透明合成图像对照使用 7.0.5 前一候选包（457 排列、PDB age 107）。最终发布包为 553 排列、PDB age 108，复验覆盖三路线帧准备／材质／尺寸、缓存修复与十万灯，未重复这两组图像对照。

CPU 调度独立对照使用 4 worker、同一简单数组变换、各 64 个暖机后样本。旧调度重现 hardware_concurrency 分块＋2ms sleep；本机默认 Windows 定时器使该 sleep 常落在约 15ms 台阶。结果仅用于调度开销量化，不能外推为渲染器 FPS 收益。

| 元素 | 旧 P50 / P95 (ms) | 新 P50 / P95 (ms) | >2ms：旧→新 |
| ---: | ---: | ---: | ---: |
| 16 | 15.395000 / 15.875500 | 0.000800 / 0.001200 | 64→0 |
| 64 | 15.448200 / 15.932200 | 0.000900 / 0.001300 | 64→0 |
| 256 | 15.332200 / 15.824700 | 0.037500 / 0.154800 | 62→0 |
| 4096 | 15.381500 / 15.613200 | 0.039700 / 0.111000 | 63→0 |

256 元素诊断开销对照，各 10000 批次：OFF／SUMMARY／DEEP 的每批 P50 为 0.004057／0.003666／0.003847ms。差异小于本轮调度噪声，不能据此说开启诊断更快；三者均在约 4µs 量级。CPU 记录为固定存储，worker 细节仅 DEEP，GPU query 上限与采样间隔显式。

对原 802 排列作相同内容的名称规范化时降至 361，随后补充本轮原生及 63 样本诊断配置，最终 553 排列，六档光学预算仍在。相比 7.0.4 发布包的排列数净少 249（31.0%）；扩展了回归配置，数量变化不是减少材质或光学能力。只在初次准备或配置／resize 时创建 PSO，动态常见材质用完整 StandardBSDF 运行期数据。resize 仍测到约 0.5～1.12s 的重建，单独归类，不当作稳态成本或宣称已消除全部长帧。

本轮未执行：DPI／跨物理屏、最小化／恢复、消费方宿主、连续太阳／1 ULP 全矩阵、256 个独立驻留 mesh＋混合编辑全矩阵、灯树 HDR 穷举／refit 图像对照、其他 GPU／驱动，以及诊断 GPU 开销的隔离测量。256 实例测试不冒充 256 个独立 mesh。透明合成的 native 回交内部使用 Vulkan 消费；Vulkan 最终 swapchain 路径在本轮离屏尺寸测试中覆盖，未重跑其完整透明背景公式矩阵。未执行项保留已有实现，不能称本轮已验证。

复验入口：rtxpt_task_batch_selftest；rtxptPackagedFramePreparationNativeSelfTest；rtxptRetainedNativeSelfTest -PretainedCases=rr；rtxptTransparentCompositionSelfTest；rtxptLightTreeNativeSelfTest -PtreeCase=large。JavaExec 可加 -PrtxptTestPackaged=true，使用 JAR 并禁止 fallback。日志：build/705-native-final.log、build/705-final-validation.log、build/705-tree-source.log、build/705-release-smoke-*.log。最终发布包已直接启动并通过全部三路线严格检查及十万灯抽检，零运行期编译。未把整个 Gradle 成功替代像素断言。

最终 RTXPT JAR：360501133 bytes；7.0.4 为 510665961 bytes，减少 29.41%。这是磁盘／传输体积收益，不是 FPS。

| 7.0.5 制品 | SHA-256 |
| --- | --- |
| renderer-api | `91dd3ba4c38f5a29014acdc17d11bc96c50ef69d9756c3aaf58e3124cde427e6` |
| renderer-core | `f7e470ce7909f2100914ccfd58202c09ab5b900a06e8e9c943e185c56d3d4cc7` |
| renderer-nvidia | `42fefb4a6e97ec977353e51e65352473cfb8677635059fc4e551ab65c248e37a` |
| renderer-rtxpt | `ee4e5071515bfad2aa79000cfbe8662ec99b9039ddd9d12bb6ec41999c3ada4e` |
| JNI DLL | `285ad1653d6880f850efbcaa450d466d34e01ace09224cdba1aa9eeaa34a08c1` |
| PDB | `c4c30d174c36d27762b4996309a89b843977e0456cb1afbc1798e15a8041b9c0` |
| Runtime ZIP | `b13ca353e5f5621d6e0000030bcb0a941e2259b929c3c3a1cf24716a5cb70fef` |

DLL／PDB：GUID fe5ab742-921d-4291-be32-e164448828b6，age 108。原始日志保留本地；不上传 SDK、缓存、测试机器路径或代理配置。

最终包三路线（各含首次准备、材质变化与 6 次 resize）的 native FRAME 最大值为 RR 643.827ms、SR＋NRD 650.810ms、native 643.569ms，均没有 >1s 样本；这组混合范围不作为稳态性能。
