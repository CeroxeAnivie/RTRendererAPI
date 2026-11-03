# RTRendererAPI 5.1.1

重复查询 GPU arena diagnostics 以前会遍历全部 free 和 retired ranges，即使 ownership 没有变化也会分配新的 statistics record。现在 backend 会复用不可变 statistics snapshot，直到 arena 现有的 lifecycle version 前进。该行为适用于所有 GPUScene stream arenas，不包含应用专属条件。

已提交的 allocations、replacements、removals、resets 和完成的 retirements 会推进该 version。Preparation、被拒绝的 admission 以及没有释放资源的 completion polls 会保留当前 snapshot。较早的 snapshots 仍不可变。Transaction validation、native ownership、completion epochs 和 free-range reuse 的语义保持不变。

公共 Java API 和 GPU ABI 与 5.1.0 一致。三个 modules 均为 5.1.1。测试覆盖稳定读取、rejected/stale plans、保留的 snapshots，以及跨 resets、alignment 和 free-range coalescing 的随机 allocation/retirement accounting。

此版本通过 GitHub source 和本地 Maven artifacts 交付。按照 release 指令，5.1.1 不上传至 Maven Central；此前发布的 5.1.0 仍在 Central。

Windows x64、Java 25、NVIDIA GeForce RTX 5080 Laptop GPU 上的验证：

```text
gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 strictAcceptanceTest :demos:hex-ball:check
BUILD SUCCESSFUL in 21m 15s
190 actionable tasks: 179 executed, 11 up-to-date
```

此 gate 包括 public ABI 和 core contracts、native GPUScene/acceleration/SER checks、带 camera motion 的 NRD、history resets、resizing 和恢复的 device sessions、DLSS/DLAA/NIS、本地 Maven metadata/runtime closure/checksums，以及独立 published-artifact consumer。5.1.1 public ABI snapshot 与 5.1.0 的 SHA-256 相同：

```text
c8bbb11c91484746507137f4821435c73b58b938495044a64eef816707f49c9f
```

Frame-generation 验收使用真实前台窗口。FG 2x 生成 302 帧，MFG 3x 生成 604 帧，NRD + DLSS SR + 推荐 FG 2x 生成 302 帧。每条 lane 都保留了连续 302 个 generated samples，且 state queries 均成功。Windows foreground handle observations 记录于 `build/acceptance-foreground-5.1.1.log`；窗口切到前台之前的初始化不计入 generated-frame evidence。完整执行过程记录于 `build/release-5.1.1-check.log`。已接受的 Demo 推荐设置仍为 1 SPP。

通过 `publishAllToLocalStagingRepository`（`build/repository`）构建本地 Maven artifacts，或通过 `publishToMavenLocal` 安装。它们都是本地操作，不要求上传 Central。硬件证据只覆盖此设备和 driver，不代表未执行的兼容性矩阵。
