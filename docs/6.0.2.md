# RTRendererAPI 6.0.2

6.0.2 是在有意不兼容的 6.x 版本线上发布的 patch release。它保持 6.0.x public Java entry points、provider SPI、retained-scene GPU ABI 和 material/medium semantics 兼容。新增的 precompiled-scene entry points 为增量内容。

Application retained-scene programs 除 GLSL 外，现在也可提供经过验证的 SPIR-V stage binaries。Raygen 接受独立的 SDR 和 HDR binaries；hit/miss stages 使用不依赖 output 的 binaries。若缺少对应 variant，会编译调用方提供的 GLSL；若没有提供 source，则显式失败。Runtime 不会用内置 shader 替代不完整的 application variant。这样应用可以采用标准、适合生产的方式随包提供经 shaderc 优化的 modules，并在不削弱 ABI validation 的前提下，消除大型 ray-tracing shaders 病态的冷启动优化开销。

6.0.1 内容寻址的 GLSL/SPIR-V cache 仍作为尽力而为的 fallback。Vulkan pipeline cache 与 application shader binaries 属于不同层。进程内 shader cache 的保留量限制为 64 MiB；磁盘 payload 带有 SHA-256 checksums；缓存 identity 包含 source、compiler binding version、target environment 和 compilation options。Registration 阶段会拒绝无效 binary headers；创建 pipeline 前检查 execution model 和 public descriptor ABI。GLSL 和 binary stages 都保留现有 custom-raygen SER policy。

此 release 只交付到 GitHub 和本地 staging repository，未上传 Maven Central。可用以下命令在本地构建和使用：

```powershell
.\gradlew.bat publishAllToLocalStagingRepository --dependency-verification=strict --no-daemon --console=plain
```

6.0.0 ABI snapshot 仍是全部 6.x patch releases 的 major baseline。

在 NVIDIA GeForce RTX 5080 Laptop GPU 上的验证：

- Root `check`、demo `check` 和完整 local staging publication 通过（161 tasks）。
- Native precompiled-stage checks 在开启 Vulkan validation 时验证了精确 SDR/HDR pixels、application buffer generations 和重复 frame-slot reuse；没有 warnings、errors 或 dropped messages。
- Four-stage contract checks 覆盖防御性所有权、匹配的 output variants、supplied GLSL fallback、缺少 variant 时拒绝、错误的 execution models 和无效 output-image formats。
- 历史 6.0.0 ABI 文件保持不变；单独的 6.0.2 snapshot 只审查兼容新增项。

这些 checks 证明 API 行为。Host-specific image correctness、cold driver pipeline time、frame rate 和长帧测量仍需单独的应用验收。
