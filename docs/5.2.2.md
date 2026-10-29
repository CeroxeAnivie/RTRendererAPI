# RTRendererAPI 5.2.2

5.2.2 是针对 5.2 medium transport API 的性能修正版本，仅交付到 GitHub 和本地 Maven repository；Maven Central 仍为 5.1.0。公共 API 保留完整 medium 契约：nested solid/liquid boundaries、absorption、emission、single scattering、Snell refraction、Fresnel、total internal reflection 和运行时 quality controls。

当 frame 声明没有 medium boundary、transmission 或 bulk medium 时，普通 opaque path 现在会恢复 direct first-hit shadow visibility fast path。需要介质的 frame 仍可使用有序 medium shadows。Generic pipeline 也保留保守的 SPIR-V 检查：对于能够证明只用单层 trace 的 custom programs，可省略未使用的 internal shadow-service miss；动态和嵌套 traces 仍保留完整 depth-two service。

API topology admission path 使用 compact edge pairing 和 vertex-star checks，并缓存不可变结果。它在减少大型 closed meshes 的临时分配时，仍会执行 seam welding、winding、non-manifold 和 non-zero-volume checks。

本版本验证包括 API checks、NVIDIA GeForce RTX 5080 Laptop GPU 上的 native Vulkan pipeline creation、全部 34 个 pinned shader variants，以及 six-lane medium transport benchmark。Benchmark 对每条 lane 使用相同的 960x540、1 SPP workload，并报告 hardware timestamps。交互式 FG/MFG 和 Minecraft 视觉验收属于其他活动。

5.1.1 与 5.2.1 默认 Demo 对比发现了本版本要修复的回归：同一个 2560x1494 scene 上，5.1.1 的 GPU time 为 3.01 ms，5.2.1 为 7.08 ms。不包含 Central publication。
