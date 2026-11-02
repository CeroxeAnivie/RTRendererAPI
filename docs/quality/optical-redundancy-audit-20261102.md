# 六界面路径剩余重复工作审查

> 清理说明（2026-11-02）：历史 JSON／CSV／JSONL 等实验原始数据及一次性诊断脚本已清理；本文结论、参数和截图保留。原始文件见[清理前版本](https://github.com/CeroxeAnivie/RTRendererAPI/tree/eb3bc21c8b7559dd847c575ca3f392a2f896d86f/docs/quality)，当前工具与验证入口见[工具说明](../../tools/README.md)。

范围：已验收的 `RTR_RTXPT_SCENE_LIGHTING=1`、SR、1 SPP、六界面共享前缀缓存路径。该环境变量选择算法，不要求开启诊断。审查没有改渲染参数或重新运行 GPU。以下顺序是后续验证优先级，不是测量后的耗时排名。

后续验证规则（2026-11-02 用户更新）：所有渲染运行开启 FG；初始 GPU 0 占用低于 10% 视为空闲并允许比较绝对性能，达到 10% 则暂停绝对性能测试。原有证据中的实际占用和 FG 开关仍按当次记录保留。

| 顺序 | 工作 | 证据与判断 |
|---|---|---|
| 1 | 相同分叉点再次求交、再次加载材质 | `RtrSceneReplayCache::Save/Restore` 保存的是求交前的 `PathPayload`；恢复后仍进入 `PathTracerSample::nextHit`，执行 `Bridge::traceScatterRay` 和 `SER_INVOKE_HIT`。同一入射前缀的两个分支，因此仍重复处理分叉顶点。属于确定的算法级重复；去重需要复用原生命中或已生成的子路径状态，必须保留完整介质栈和引导层语义。 |
| 2 | 首射线和 VBuffer 初始化后被缓存覆盖 | raygen 每轮先执行 `SetupPathPrimaryRay`、`FirstHitFromVBuffer`，之后才尝试 `Restore`。恢复成功时这些路径字段被缓存完整覆盖。属于源码层面的冗余；实际 GPU 节省多少需排除编译器已经消除的部分。FILL 模式的 `StartPixel` 本身基本编译为空，不把它冒充昂贵清屏。 |
| 3 | 不会重放的路径也保存快照 | `RtrSceneReplayCache.hlsli` 的 `Save` 只检查顶点范围，未检查玻璃分叉是否出现。纯不透明路径的第二顶点也可能打包、保存状态，随后 raygen 因没有玻璃直接退出。可以考虑延迟缓存，但首个玻璃出现在后续反射中的情况必须保留正确回退。 |
| 4 | 原生采样灯光准备与自定义直接光并存 | `Sample::UpdateLighting` 仍调用 `LightsBaker::UpdateBegin`；当前直接光估计则由 `PathTracerNEE` 提前返回 `RtrSceneDirect`，读取独立灯表。部分采样权重、代理生成可能无人消费，但原生光源命中索引、发光几何和其他路径仍可能依赖它，尚不能认定整个灯光准备无用。当前 power 模式已跳过 NEE-AT 时域反馈，不能重复声称这段反馈仍在执行。 |

关键定位（以本轮配置生成的 SDK 源为准）：

- 持久缓存实现：`renderer-rtxpt/src/main/cpp/RtrSceneReplayCache.hlsli`，`Save` 第 13 行、`Restore` 第 21 行。
- 持久恢复插入点：`tools/Build-RtxptBridge.ps1`，第 820 行附近。
- 生成的 raygen：`build/rtxpt-sdk/Rtxpt/Shaders/PathTracerSample.hlsl`，第 127 行 `nextHit`、第 274 行首射线、第 285 行 VBuffer、第 291 行缓存恢复。
- 原生灯光更新：`build/rtxpt-sdk/Rtxpt/Sample.cpp`，第 2127 行 `UpdateLighting`、第 2185 行 `UpdateBegin`。
- 自定义直接光提前返回：`tools/Build-RtxptBridge.ps1` 第 463 行；实际照明实现 `renderer-rtxpt/src/main/cpp/RtrSceneDirect.hlsli`。

已保留的收益是 v186 的 172.30 原生 FPS，相对六界面无缓存 v180 的 154.36 提升 11.62%。上述剩余项尚无单独收益证据，不据此承诺还可增加多少 FPS。反射与折射分叉后方向不同的射线属于六界面设计所需计算，不作为重复工作删除。

## 本轮实测与验收状态

v188/v192：相同 2560×1440 输出、1485×835 输入、SR Balanced、1 SPP、六界面、FG 开启。工作记录只采样帧 320/321、间隔 128 像素的位置，不外推为全画面次数。原始日志和汇总保存在 `data/hexball-native-20261101/`。

| 审查项 | v188 → v192 | 结论 |
|---|---|---|
| 重复求交与材质加载 | 求交总数 112 → 112 | 未消除。NVAPI 命中重建候选不适用于本轮实际 DX SER 路径，已移除，未将其当作收益。 |
| 首命中初始化被恢复覆盖 | 10 → 0；初始化总数 180 → 170 | 恢复提前，消除这部分重复初始化。 |
| 不透明路径的提前快照 | 尚未出现玻璃标志时的保存 36 → 36 | 未消除。另消除恢复后的立即重复保存，全部快照写入 66 → 56；不能把这称作已解决纯不透明路径缓存。 |
| 原生灯光准备 | v188 相关阶段约 0.118 ms | 未删，仍有上游索引、发光几何依赖。优先处理路径追踪。 |

v188 的图形队列测得路径追踪 3.254072 ms、DLSS 1.326827 ms、互斥阶段合计 5.505986 ms。v192 分别为 2.982258 ms、1.257292 ms、5.084099 ms。v192 中途失去前台，FG 从 2× 变为 1×，不能把全程 8.35% 的 PathTrace 降幅当作这些修改独立的净收益。

FG：v188 3361 原生 / 6719 实际呈现，111.975072 / 223.850196 FPS；v189 3260 / 6517，108.632215 / 217.164461 FPS。两轮均接近完整 2×。v192 前台连续为 2×，后台连续为 1×，且 SDK 状态 0、失败 0。用户确认后台不生成帧属于正常行为，不再作为故障修复。

另外修正 `RendererConfig.gpuTimingsEnabled` 的 JNI 接线：关闭时不创建、轮询或提交 HUD GPU timer query，Java 诊断返回 unavailable；开启与否不再取决于 HUD 文本是否为空。该项未声称有单独 FPS 收益。

画质：v190 控制和 v191 候选各完成 364 帧、12 张静止/运动/停止图，生命周期成功且 Streamline shutdown=0。图像不是逐像素一致，平均通道差范围约 0.000459～0.001216/255，最大单通道差 11/255；不以此宣称数学一致或改变已验收的玻璃、灯光参数。v191 的稀疏日志因读取恢复前的未初始化像素号而溢出，该记录不能用于工作量排名；日志位置已修正，v192 无该溢出。

手动验收关闭 WorkTrace、TransportTrace、逐秒 FG 诊断、阶段 profile、PresentMon 和 API GPU timer；保留选择已验收照明算法的 `RTR_RTXPT_SCENE_LIGHTING=1`，该变量选择场景光照输入模式，不是日志开关。FG、HUD、SR 和已验收光学配置继续启用，窗口不设自动退出。
