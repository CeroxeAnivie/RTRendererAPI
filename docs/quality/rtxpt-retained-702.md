# 7.0.2 资源增量更新、灯树与窗口验收

测试日期：2026-11-03。测试机器为 Intel Core Ultra 9 275HX、RTX 5080 Laptop GPU（16 GB），驱动 616.92，Windows 11 x64，Java 25。RTXPT 固定在 `303b408ab81790a5051abd7573830b7a4112eb8e`，7.0.2 的原生修改通过仓库中的 retained-resource patch 重建。

## 对原提案的判断

提案指出的两条代码路径属实：7.0.1 的网格、材质、纹理或实例结构编辑会走完整场景导出／加载；`SCENE_ANALYTIC` 直接光照按 shading receiver 遍历全部灯光。它们属于通用 retained-scene 后端的扩展问题，修复无需应用专属类型或私有 ABI。

提案引用的外部消费方日志没有随本次源码验收重新生成，因此其中的帧耗时、场景装载次数与游戏运行数据不作为本报告的实测数据。下面的结果来自仓库中的独立测试及同机版本对比。高频太阳变化仍按真实光照变化重置历史，7.0.2 没有保留过期照明来换取速度。

## 修复内容与实际边界

- 首次发布和显式 reset 保留完整导出／加载。资源编辑只导出变化的 mesh variant 和受影响的材质／纹理依赖；稳定材质保留 GPU 槽位，上传 dirty spans。
- 未变 BLAS 保留；单 mesh 更新仅构建该 mesh 的 BLAS，实例结构编辑只在必要时更换 TLAS。相同身份、兼容尺寸／格式的纹理复用已有 GPU allocation。
- 使用全队列 drain 后更新资源的保守发布策略；旧帧完成后才替换或退休依赖。CPU 校验失败保持原 revision，原生发布失败关闭 session，避免继续呈现部分状态。
- 大集合通过平衡空间灯树采样，保留全部灯的叶节点。分支概率含 5% 均匀支持，并按实际概率补偿；小集合完整求和。环境单独变化不重打包全部灯，仅上传太阳和树祖先路径。
- 诊断明确区分 CPU 命令记录、同步等待和 GPU 队列时间区间。资源 GPU interval 可能含 BLAS 压缩期间的 CPU 提交间隙，不能视作纯 GPU busy time。
- 路径仍使用稀疏 glTF 导入，并在资源更新时等待旧 GPU 工作；本次没有实现零拷贝或多代并行更新。灯光编辑仍重建整个 CPU 索引。

## 窗口未响应

复现中新增实例后，光追 pipeline baker 仍保留旧 shader binding table；后续 GPU 工作无法完成，最大化触发的 resize 又等待该工作。另有交换链等待只依赖 auto-reset event 的问题，即使 fence 已完成，也可能等待已被消费的通知。

7.0.2 在拓扑／BLAS／hit-group 变化后使命中表失效并重新生成，按各后台缓冲区的 fence 值确认完成。创建线程在 shader 任务、GPU drain 和交换链等待中处理窗口消息；设备错误与 30 秒超时会向 Java 传播。删空实例时保留有效的最小缓冲区，空命中表不会每帧重复重建。实测还发现 ReSTIR 的 geometry-instance-to-light 缓冲区没有处理零实例，现已保留最小有效 allocation 并跳过空上传。纹理缓存也改为可转换的 shader-resource 初始状态，兼容纹理复用时的 copy-source／copy-destination 转换不再触发永久状态错误。

## 验收方法

`rtxptRetainedNativeSelfTest` 分别使用 analytic、raw、DLSS-RR、ReSTIR DI/GI，覆盖稳定纹理动画、单网格替换、实例增加／删除、完整 reset 参考、resize、三轮最大化／还原／最小化、非法资源依赖拒绝、删空实例后恢复。要求稀疏编辑不增加完整导出／native load，材质／纹理编辑不建 BLAS/TLAS，单网格替换只建一个 BLAS。

画面参考使用 320×180、持续推进的噪声：每次参考拍摄先额外稳定 32 帧，再取 48 帧 SDR 平均；显式 reset 后要求 16×16 块 RGB MAE < 3、全图均值偏差 < 1。冻结种子的单帧 DI/GI 对照曾超过均值阈值，冻结帧也不能充当独立样本平均，因此最终采用上述多帧条件并保留原误差阈值。带时间历史的重建路线允许少量差异。

`rtxptLightTreeNativeSelfTest` 使用 100,000 个零半径点灯、一个聚光灯、一个方向灯及环境太阳槽；覆盖移动、类型替换、颜色／范围／阴影开关和全部移除。通过 GPU counters 验证每个 receiver 的实际选灯数与访问节点数。另以 35 灯、96×54、63 samples、48 帧线性 HDR 平均比较完整求和与灯树，要求能量误差 < 3%、相对 RMSE < 10%。

`rtxptLightingNativeSelfTest` 的 fields-analytic／fields-raw 分别验证两种 lighting input 的各字段变化确实改变像素，连续环境更新不导出几何。

## 版本速度对比

由 `RtxptScalabilityBenchmark` 使用相同公共 7.0.1 接口执行。基线加载 Maven Local 7.0.1 JAR 和对应旧 DLL；7.0.2 使用本次最终构建。每个案例独立 JVM。先以 701→702→702→701 顺序做交替测量；随后修补空场景和纹理状态转换后，对最终 7.0.2 DLL 独立重测两轮。表中采用初次交替测量的两轮 7.0.1 基线及最终两轮 7.0.2，不混入中间 DLL 的结果。GPU 测试串行，关闭 FG 和逐帧截图；最终复测前轮询 GPU 利用率，保留状态及实际制品哈希。

两版均为 160×90、SCENE_ANALYTIC、1 SPP、1 bounce、0 diffuse bounce。每进程预热 12 帧；材质／纹理／网格每轮各 24 个样本，十万灯和环境变化各 12 个样本，两轮合并后报告 P50/P95。测量包含 apply（需要时）、submit 和 awaitIdle，因此为完成帧的墙钟耗时，含场景翻译、CPU 工作、GPU 工作和等待；不等同于纯 GPU 帧时间。小分辨率用于控制旧版十万灯的运行时间，不能直接推算 1440p 或游戏 FPS。

| 案例 | 7.0.1 P50 / P95 (ms) | 7.0.2 P50 / P95 (ms) | P50 加速比 | 耗时降低 |
| --- | ---: | ---: | ---: | ---: |
| 单材质 / 256 meshes | 550.816 / 581.157 | 12.322 / 15.877 | 44.70× | 97.76% |
| 单纹理 / 256 meshes | 539.144 / 590.927 | 15.317 / 19.649 | 35.20× | 97.16% |
| 单网格 / 256 meshes | 553.018 / 585.374 | 198.155 / 216.981 | 2.79× | 64.17% |
| 十万灯稳定帧 | 110.852 / 289.018 | 0.711 / 3.829 | 155.91× | 99.36% |
| 十万灯 + 太阳变化 | 5972.876 / 6182.252 | 19.819 / 23.152 | 301.37× | 99.67% |

原始样本、分位数、GPU 起始状态和制品 SHA-256 见 [JSON 数据](data/rtxpt-retained-702.json)。材质／纹理／网格每轮完整场景导出由 25 次降至 1 次，减少 96%；灯光／环境两版均仅初次导出一次。

最终 native DLL SHA-256：`2815e8374abb016bb47ff5cffe603f8add51b349892c3a0a34000620c5c44e0c`。

单资源案例包含 256 个分别驻留的 mesh 和实例。材质／纹理／mesh 每次仅修改一个资源。`lights-100000` 保持灯与环境不变并提交完成帧；`environment-100000` 在相同十万灯集合中只改变太阳强度。每轮的首次场景发布不计入样本。 十万灯收益来自将全部灯的确定性求和改为每个 receiver 的 63 次重要性采样；单帧噪声分布不同。质量对照来自上述 35 灯、48 帧 HDR 平均，不能把它解释为十万灯每一帧都与完整求和逐像素一致。

## 实际验收结果

| 路线 | 48 帧均值的块 RGB MAE | 全图均值偏差 | 结果 |
| --- | ---: | ---: | --- |
| analytic | 0.000016 | 0.000017 | PASS |
| di-gi | 1.013200 | 0.028131 | PASS |
| raw | 0.158458 | 0.026146 | PASS |
| rr | 0.204042 | 0.000822 | PASS |

四条路线均通过纹理动画、单 mesh / BLAS 更新、实例增删、显式 reset、非法依赖拒绝、删空后恢复和窗口状态切换。每条路线记录 8 次兼容纹理 allocation 复用；材质／纹理编辑不建 BLAS/TLAS，单 mesh 编辑只构建一个 BLAS。最终资源回归日志没有原生资源状态错误。

十万灯 GPU 实测：`mode=SPATIAL_TREE, representedLights=100003, indexNodes=200005, measured=true, shadedReceivers=436, selectedLights=27468, visitedTreeNodes=483423, shadowTests=14861`；每 receiver 63 次选灯，每次平均访问 17.60 个树节点。移除全部应用灯后，只保留环境太阳槽。

HDR 参考相对能量误差 **0.0082%**，相对 RMSE **1.51%**。两种 lighting input 的独立字段开关及连续环境更新均通过。

发行检查通过，159 个任务完成；包括四模块 check、核心 ABI、版本一致性、仓库中立性、两个 Demo 的依赖闭包和 NVIDIA runtime 完整性。六档 shader 共 658 个预编译排列通过源码／制品哈希校验；修复构建脚本重复插入 guard 的问题后，重复 configure 的全部 shader 字节保持一致。

最终 JAR 在仓库外的全新目录安装后，17 个预算／预设案例全部通过。测试明确禁止 DXC 回退，运行前后均为同一组 658 个二进制，没有新增或改写。另一项普通 JAR 原生检查通过 DLSS-RR、FG 开关、场景切换、transform batch、截图、resize 及三种不透明材质 IOR 的 native readback；任意新材质仍可使用随包编译器。两个 Demo 已重新打包为最终制品。整包验收共 25 个任务，耗时 2 分 10 秒，进程树峰值提交内存 4.731 GB。

## 复现入口

```powershell
./gradlew.bat :renderer-rtxpt:rtxptRetainedNativeSelfTest
./gradlew.bat :renderer-rtxpt:rtxptLightTreeNativeSelfTest
./gradlew.bat :renderer-rtxpt:rtxptLightingNativeSelfTest -PlightingCases=fields-analytic,fields-raw
./gradlew.bat :renderer-rtxpt:rtxptScalabilityBenchmark -PbenchmarkCases=material,256
./gradlew.bat :renderer-rtxpt:rtxptPackagedBudgetNativeSelfTest
```

源码运行可用 `-Dtop.ceroxe.rt.rtxpt.runtime=<runtime目录>` 指向构建；packaged gate 必须使用 JAR 内制品，不接受覆盖。基线测速加 `-PbenchmarkBaseline=7.0.1` 并指向对应旧 runtime。统计计数只在诊断测试开启，测速不启用 GPU counter readback。
