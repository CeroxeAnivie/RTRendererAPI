# 7.0.1 通用环境与灯光增量更新

`EnvironmentState` 的太阳／天空变化，以及 `SceneTransaction` 中全部 `SceneLight` 的变化，现在独立于几何重建。应用继续提交原来的 API，无须私有 DLL、反射字段或新提交接口。

后端在每次 `submit` 前取得最终灯光状态。同一帧前的多个事务合并成一批；相同状态不重复上传。灯光批次先提交，再更新实例变换，最后渲染，原有 revision、minimumSceneRevision 和 frame sequence 检查不变。材质、纹理、网格、实例结构和 reset 仍保留完整导出／装载路径。

## 完整覆盖范围

两条 lighting input 都处理天空 RGB、ambient、太阳方向／RGB／强度，以及 POINT / SPOT / DIRECTIONAL 的位置、方向、RGB、强度、range、内外锥角和 castsShadow，包括新增、删除、同 ID 类型替换、零强度和全部清空。不存在最近 N 灯截取。

`SCENE_ANALYTIC` 更新持有的 light buffer 和常量。`BACKEND_DEFAULT` 更新上游 graph 中的灯节点与灯光采样器，用独立常量生成天空，避免改写 HDR 文件。应用的方向灯进入采样器；`EnvironmentState` 的太阳仍保留原生环境中的可见太阳盘。这避免了固定 16 灯烘焙数组对应用方向灯的限制，也避免把太阳照明计算两遍。

同时修复了默认路线原来没有消费的有限 range、castsShadow 和零半径聚光灯锥角，并为托管灯修正 CPU 与 shader 不一致的方向编码。非托管 native scene 继续使用原来的导入语义。

每次实际变化重置累积、重建／降噪历史、SHaRC 和 ReSTIR 历史，以及自适应灯光采样历史。原先明确不支持的组合仍在创建／配置时拒绝：解析灯光不与 SHaRC / ReSTIR 组合，DI + GI + SHaRC 三项同时开启也不受支持。

## 生命周期与诊断

所有数据先在 CPU 完整验证，再替换托管灯子树；失败后 Java 关闭 session，不渲染半批状态。只消费灯节点产生的 graph 结构标记，实例变换随后照常刷新，避免因此重建 mesh buffers。

light buffer 按两倍容量增长，需求低于容量四分之一时缩容。替换时清理绑定缓存，旧 buffer 持有到 graphics 和 compute 两条队列的事件都完成；关闭仍按原有 GPU／Streamline 退出顺序收尾。

`RtxptAccess.lightingTimings()` 返回实际 native 执行计数：

| 字段 | 含义 |
| --- | --- |
| nativeSceneLoads | 完成的 native SceneLoaded 次数，含初始装载 |
| committedBatches | 实际提交的完整灯光批次 |
| recordedUploads | 已进入 native 常量／buffer 更新的批次 |
| uploadRecordingNanos | CPU 记录上传命令所用时间；不是 GPU 时间 |
| historyInvalidations | 实际批次导致的历史失效次数 |

结合原有 `bridgeTimings().sceneExports()` 判断是否导出了几何。既有 `BridgeTimings` 六参数构造不变，renderer-api 公共合同与 7.0.0 保持一致。

生产默认不输出诊断。`RTR_RTXPT_LIGHTING_DIAGNOSTICS=1` 打开实际执行日志；`RTR_RTXPT_DIAGNOSTIC_FREEZE_NOISE=1` 仅供固定噪声的画面诊断，正常运行不要设置。`RTR_RTXPT_BACKGROUND_WINDOW=1` 用于后台验收窗口，关闭创建／显示时的自动聚焦；未设置时保留正常窗口行为。

## 验收方法

运行 `:renderer-rtxpt:rtxptAdapterSelfTest` 验证 CPU 分类、完整字段打包、稳定 ID、类型替换、混合灯光／变换事务及快照独立性。

运行 `:renderer-rtxpt:rtxptLightingNativeSelfTest` 做短时后台验收，通过 `-PlightingCases=...` 选择案例。测试窗结束即关闭，不启用 FG、不抢前台，不报告绝对 FPS。

`fields-analytic` 和 `fields-raw` 用小尺寸、固定噪声和 power sampler，分别开关强度、range、castsShadow、位置、RGB、聚光灯锥角和方向，并只点亮第 20 个方向灯，要求最终 PNG 有相应变化。随后同一 revision 连续提交 10 个不同环境，要求几何导出／native 装载保持不变，每帧实际批次及上传各增长一次，sequence 正常完成。

完整场景案例覆盖 20 个方向灯、32 个点灯和聚光灯、两次事务合并、同 ID 类型替换、混合变换、结构 reset、全部清空、resize 和连续 session。另检查 SR、RR、NRD、DI/GI、DI/SHaRC、GI/SHaRC 的实际 runtime status，防止静默关闭请求功能。

重新装载同一最终状态的参照同时报告逐像素 RGB MAE、固定 16×16 块均值 MAE 和全图均值偏差。raw 1 SPP 的不同采样噪声不能称为像素无损；低通 LDR 对照要求块 MAE < 3、全图均值偏差 < 1 个 RGB 单位。独立字段开关另行验证实际效果，不能用低通对照代替。

最终执行结果与日志见本次构建的 `build/reports/rtxpt-lighting` 和 `build/lighting-*.log`；冻结的内存修复数据仍单独保存在[内存验收报告](rtxpt-demand-memory.md)，不与灯光更新开销混算。

## 本次实际结果

两条输入的独立字段检查均通过：阴影、range、位置、RGB、锥角、方向、仅第 20 盏方向灯、黑天空下的环境太阳和天空颜色均确实改变像素。两条输入各连续提交 10 帧不同环境，最终均为 27 次实际批次、27 次实际上传，几何导出及 native 装载各保持 1 次。

| 场景 | 16×16 块 RGB MAE | 全图均值偏差 | 结果 |
| --- | ---: | ---: | --- |
| 通用 raw | 0 | 0 | 通过 |
| SR | 0.3285 | 0.0569 | 通过，退出 0 |
| RR | 0.2471 | 0.0129 | 通过，退出 0 |
| NRD | 0.0317 | 0.0079 | 通过，退出 0 |
| ReSTIR DI + GI | 1.6063 | 0.0687 | 通过，退出 0 |
| ReSTIR DI + SHaRC | 1.9550 | 0.5570 | 通过，退出 0 |
| ReSTIR GI + SHaRC | 0.1347 | 0.0173 | 通过，退出 0 |

解析路线的重新装载对照也通过。上述误差是 LDR PNG 的 RGB 差异，不是 HDR 能量误差。高级组合沿用实际请求功能，未静默关闭。三种 ReSTIR／SHaRC 组合还分别在零天空、无发光材质时只启用第 20 盏方向灯，平均 RGB 增量为 33.4147、33.4416、45.6788，确认它确实进入采样和照明。

独立检查发现并修复了两处接入问题：常量天空原先借用全局 ScaleColor，黑天空会连烘焙太阳一并乘黑；现改为独立背景常量。SR 在重载／resize 后关闭时曾返回 Streamline 24，正常尺寸重试仍出现；关闭时原先只保留 RR 回调模块，现同时保留 SR 模块直到 SDK 关闭队列清空，成员 RAII 保证异常时也释放引用。相同 SR 场景及随后同 JVM 的 RR／NRD 和三种高级组合均正常退出。初次失败日志保留，未计为通过。

独立字段日志为 `build/lighting-native-complete.log`、`build/lighting-native-post-sun.log`；修复后 SR／RR／NRD、三种高级组合及方向灯证据为 `build/lighting-native-sr-module-retained.log`（整体退出 0）。仅第 20 盏灯的三项案例均为 1 次场景装载、2 次灯光批次、2 次实际上传。
