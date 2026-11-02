# 2026-11-01 bounded game workload: completed-image evidence

> 清理说明（2026-11-02）：历史 JSON／CSV／JSONL 等实验原始数据及一次性诊断脚本已清理；本文结论、参数和截图保留。原始文件见[清理前版本](https://github.com/CeroxeAnivie/RTRendererAPI/tree/eb3bc21c8b7559dd847c575ca3f392a2f896d86f/docs/quality)，当前工具与验证入口见[工具说明](../../tools/README.md)。

RTX 5080 Laptop; 1920x1080; 1 SPP; no denoising, FG or resolution reduction. All recorded starts had 0% target-GPU utilization. Workload: 120 objects, 1440 triangles; 32 local volumes excluding a tiny bounded camera absorption medium; projected local-volume area 10.8402%, at most two intersected local volumes and 0.4627 m cumulative primary-ray distance. This is a feature-rich lightweight game performance fixture, not production AAA artwork.

The accepted endpoint is a completed whole-scene image. No window-presentation measurement has been made. Functional differences recorded in each engine JSON still apply: these are not full API-equivalent renders.

| Renderer | Medium off | Medium on | On frame latency | Added latency |
|---|---:|---:|---:|---:|
| Cycles OptiX, independent 1-SPP complete render calls | 13.4205 FPS | 8.3331 FPS | 120.0039 ms | 45.4911 ms |
| Mitsuba CUDA, independent 1-SPP complete render calls | 5.8554 FPS | 4.9605 FPS | 201.5915 ms | 30.8076 ms |

Cycles saved comparison images are from the final 64-SPP batch; independent-call timings above are from four verified 1-SPP calls. Mitsuba saved images are 1 SPP. PNG writes are outside timing. These calls produce complete-scene image data; neither measures OS window presentation.

## Measurements excluded from completed-image FPS

- Flow: correct Vulkan adapter is index 1, UUID 576a173b4d32e49e5c4917231e7a07ef, matching nvidia-smi. Native volume-stage measurements are 250.774 FPS / 3.98765 ms static and 222.860 FPS / 4.48712 ms with per-frame density/shadow refresh. No surface renderer is present in that host. These are not whole-scene FPS. Previous approximately 30 FPS ran on Intel Vulkan adapter 0 and is invalid as an RTX 5080 result.
- LuxCore RTPATHOCL 5.30764, PATHOCL 5.05085, TILEPATHOCL 0.291824 are completed progressive samples divided by full-image pixel count and time. They are whole-image sampling throughput, not independently completed visual-output FPS; excluded from accepted endpoint.
- RTXPT has not produced new runtime measurements. The checked source bridge explicitly sets sigmaS=0, so it must not be represented as an equivalent scattering-volume benchmark.
- UE testing was cancelled by the user before any build or GPU test. GitDependencies download process was stopped. No UE FPS exists.

Scene corrections explicitly initialize PLY color/alpha defaults and create bounded local effects; original transport coefficients are retained. Unsupported cross-engine behavior is declared rather than rebuilt in a custom integrator. Tools are on main; raw sample/phase measurements remain in comparison.json only for analysis.
