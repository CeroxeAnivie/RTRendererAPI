# RTRendererAPI 5.1.0

此增量版本提供由应用管理的纳秒时间线和不可变的局部资源快照。两项功能都不携带应用专属的 scene 或 lighting 语义。交付渠道为 GitHub 源码和已签名的 Maven Central 制品。提交前会先完成本地 staging 和独立 consumer 验证；upload、validation 和 publication 是彼此独立的状态，应依据实际 Central deployment 证据报告。

## 时间线

`RenderFrameRequest.Builder.clock(FrameClock)` 会快照显式时间和 elapsed nanoseconds。默认值为 `(0,0)`，因此旧式 request 构造仍然确定。`FrameClock.system()` 采样 `System.nanoTime()`；`fromPrevious` 在该时间线上派生非负 delta。支持有符号 timestamp，以及长时间回绕时的短时间差。回退时间通过带非负 delta 的新快照显式表达；pause、seek 和 history reset policy 由应用负责。同一个不可变 request 的 admission retries 会保留原时间。

标准 GPU ABI 在 words 400/401 追加时间，在 402/403 追加 delta。之前各 word 的偏移不变。请使用版本匹配的 API/core/provider。GLSL helper `gsFrameTimeNanos`、`gsFrameDeltaSeconds` 和 `gsFrameSecondsSince` 会在转换为 float 前先做整数减法；未来的起始时刻会钳制为零。Timestamp 差值必须短于 2^63 nanoseconds。

## 资源局部更新

`ResourceData.withUpdates(List<Update>)` 会防御性快照替换范围。所有范围会在修改前验证，重叠时后续更新优先，size 和顺序保持不变。每个快照会共享未更改的 16 KiB pages，且不会保留父快照链。`bytes()` 仍返回独立的只读连续 view，并在需要时惰性物化。空更新列表复用原快照；空替换范围和越界写入会被拒绝。

每个 GPU frame slot 都与自身上次上传的精确快照比较。跳过若干版本、分支版本，以及重新使用旧快照的情况都能正确处理。只有 slot 空闲后才写入已更改的 pages；上传失败会使该 slot 的比较缓存失效。仍可通过完整 payload 构造支持任意 resizing 和无关数据源。

## Demo 与证据

可执行 Demo 会打开一个 feature workbench，其中 renderer 和 frame policy 可以分别配置。`--controls` 可显式打开该界面。`--programmable` 会打开可编辑的 retained GLSL stages、HDR preview、pause/seek/rate 和不可变 buffer 局部更新。现有 CLI workloads 仍可使用。原生 HDR bytes 会保留；preview tone mapping 是显示选择。

聚焦的 API contracts、frame packing 以及真实 GPU timeline/partial-update 验收已在 NVIDIA GeForce RTX 5080 Laptop GPU 上通过，记录于 `build/timeline-snapshot-check.log`。完整 release、feature 和 application performance 验收会在分别完成后记录。5.0.0 的历史结果不构成本候选版本的证据。FG/MFG 验收要求实际的 Windows 前台 render window 和持续的权威 generated-frame count。

5.1.0 前台运行中，2x 在连续 302 个样本内生成 302 帧；固定 3x 在连续 302 个样本内生成 604 帧。两者均观测到 302/302 foreground presents、零 state-query failures 和零 generation-request misses。Windows 前台窗口句柄观测记录于 `build/acceptance-foreground.log`；SDK 证据记录于 `build/release-native-check.log`。用户也通过目视验收了推荐的 hex-ball Demo 设置：1 SPP。该目视验收独立于生成帧测量。

最终 release 验证通过，记录于 `build/release-final-check.log`：最新 timeline 和 partial-update contracts 与 native tests、完整 core GPUScene native gate、Demo 检查、签名 Central bundle、release checksums、Maven dependency topology，以及独立 staged Maven consumer。较早的完整 CPU check 记录于 `build/release-contract-check.log`；NVIDIA feature matrix 记录于 `build/release-native-check.log`。更早的组合命令在 NVIDIA 验收之后因本地 signing-daemon 故障停止；最终运行完成了签名和其余 gate。

Bundle 的全部 90 个条目均与本地 staging 的 SHA-256 逐字节核对，全部 15 个 detached OpenPGP signatures 均验证通过。已签名 bundle 的 SHA-256 为：

```text
a2995fa00bd4d90cc1d5b1cb7b1b950207caeb4c3e5cfb912bfc936cc25ad497
```
