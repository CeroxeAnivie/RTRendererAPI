# 六界面路径剩余重复工作审查

范围：已验收的 `RTR_RTXPT_DIAGNOSTIC_LEGACY_LIGHTING=1`、SR、1 SPP、六界面共享前缀缓存路径。该环境变量选择算法，不要求开启诊断。审查没有改渲染参数或重新运行 GPU。以下顺序是后续验证优先级，不是测量后的耗时排名。

后续验证规则（2026-11-02 用户更新）：所有渲染运行开启 FG；初始 GPU 0 占用低于 10% 视为空闲并允许比较绝对性能，达到 10% 则暂停绝对性能测试。原有证据中的实际占用和 FG 开关仍按当次记录保留。

| 顺序 | 工作 | 证据与判断 |
|---|---|---|
| 1 | 相同分叉点再次求交、再次加载材质 | `RtrLegacyReplayCache::Save/Restore` 保存的是求交前的 `PathPayload`；恢复后仍进入 `PathTracerSample::nextHit`，执行 `Bridge::traceScatterRay` 和 `SER_INVOKE_HIT`。同一入射前缀的两个分支，因此仍重复处理分叉顶点。属于确定的算法级重复；去重需要复用原生命中或已生成的子路径状态，必须保留完整介质栈和引导层语义。 |
| 2 | 首射线和 VBuffer 初始化后被缓存覆盖 | raygen 每轮先执行 `SetupPathPrimaryRay`、`FirstHitFromVBuffer`，之后才尝试 `Restore`。恢复成功时这些路径字段被缓存完整覆盖。属于源码层面的冗余；实际 GPU 节省多少需排除编译器已经消除的部分。FILL 模式的 `StartPixel` 本身基本编译为空，不把它冒充昂贵清屏。 |
| 3 | 不会重放的路径也保存快照 | `RtrLegacyReplayCache.hlsli` 的 `Save` 只检查顶点范围，未检查玻璃分叉是否出现。纯不透明路径的第二顶点也可能打包、保存状态，随后 raygen 因没有玻璃直接退出。可以考虑延迟缓存，但首个玻璃出现在后续反射中的情况必须保留正确回退。 |
| 4 | 原生采样灯光准备与自定义直接光并存 | `Sample::UpdateLighting` 仍调用 `LightsBaker::UpdateBegin`；当前直接光估计则由 `PathTracerNEE` 提前返回 `RtrLegacyDirect`，读取独立灯表。部分采样权重、代理生成可能无人消费，但原生光源命中索引、发光几何和其他路径仍可能依赖它，尚不能认定整个灯光准备无用。当前 power 模式已跳过 NEE-AT 时域反馈，不能重复声称这段反馈仍在执行。 |

关键定位（以本轮配置生成的 SDK 源为准）：

- 持久缓存实现：`renderer-rtxpt/src/main/cpp/RtrLegacyReplayCache.hlsli`，`Save` 第 13 行、`Restore` 第 21 行。
- 持久恢复插入点：`tools/Build-RtxptBridge.ps1`，第 820 行附近。
- 生成的 raygen：`build/rtxpt-sdk/Rtxpt/Shaders/PathTracerSample.hlsl`，第 127 行 `nextHit`、第 274 行首射线、第 285 行 VBuffer、第 291 行缓存恢复。
- 原生灯光更新：`build/rtxpt-sdk/Rtxpt/Sample.cpp`，第 2127 行 `UpdateLighting`、第 2185 行 `UpdateBegin`。
- 自定义直接光提前返回：`tools/Build-RtxptBridge.ps1` 第 463 行；实际照明实现 `renderer-rtxpt/src/main/cpp/RtrLegacyDirect.hlsli`。

已保留的收益是 v186 的 172.30 原生 FPS，相对六界面无缓存 v180 的 154.36 提升 11.62%。上述剩余项尚无单独收益证据，不据此承诺还可增加多少 FPS。反射与折射分叉后方向不同的射线属于六界面设计所需计算，不作为重复工作删除。
