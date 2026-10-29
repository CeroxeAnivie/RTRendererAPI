# RTRendererAPI 6.0.3

6.0.3 修复所有 retained-scene programs 和 bootstrap pipeline 的 driver pipeline-cache 持久化。渲染算法、material/medium semantics、shader quality、public Java entry points 和 6.0.2 ABI 均保持不变。

此前所有 GPUScene programs 都累积到单一 device cache。生成的 blob 超过 64 MiB 后，提取会被静默跳过，导致重复执行开销很高的 driver compilation。现在 cache 使用 SHA-256 identities，对完整且有序的 stage binaries 和 descriptor/group ABI 计算摘要，并按 vendor、device 和 driver pipeline-cache UUID 区分。SDR/HDR、custom programs 和可选的 shadow-service stage 分别缓存。

Opaque driver payload 使用独立版本的 envelope，并记录长度和 SHA-256 checksum。截断、损坏、来自其他 driver 或超大 payload 都会在 driver creation 前被拒绝。在文件系统支持时，写入会通过原子替换完整文件。并发 writers 会发布完整且兼容的条目；cache I/O failures 属于可选功能，并会产生有界 warning。

默认限制为每个 payload 128 MiB、受管理 cache files 总计 512 MiB，以及 128 个 entries。成功发布后会淘汰最旧的受管理 entries；无关文件和旧 aggregate caches 保持不动。Renderer 只加载其自身 program 的 entry，不再加载完整的历史集合。可选 JVM properties（不是应用专属的 repository settings）：

- `top.ceroxe.rt.pipelineCache.directory`：cache 目录。
- `top.ceroxe.rt.pipelineCache.maxEntryMiB`：1–1024，默认 128。
- `top.ceroxe.rt.pipelineCache.maxDirectoryMiB`：至少为 entry budget + 1，最多 16384；默认 512。
- `top.ceroxe.rt.pipelineCache.maxEntries`：1–4096，默认 128。

Cache miss 仍需 driver compilation。本版本不声称能消除新 program 首次编译的开销。Shader 或 driver 发生变化时会选择新的 identity。

只交付到 GitHub 和本地 Maven staging repository；不发布 Maven Central。历史 6.0.0 和 6.0.2 ABI snapshots 保留不变。

在 NVIDIA GeForce RTX 5080 Laptop GPU 上的验证：root `check`、demo `check`、native precompiled SDR/HDR exact pixels、application-data generations 和 frame-slot reuse，以及完整 local Maven publication 均通过（163 tasks）。Cache tests 覆盖 stage/ABI identity、device separation、round trips、损坏/截断/超大 payload、有效 entries 的保留、按 entry count 和 byte budget 淘汰，以及无关文件保护。Native precompiled gate 中的 Vulkan validation 没有报告 warning、error 或 dropped messages。
