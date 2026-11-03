# RTRendererAPI 6.0.1

6.0.1 是在有意不兼容的 6.x 版本线上发布的首个 patch release。它保持 6.0.0 的 public Java API、provider SPI、retained-scene GPU ABI 以及 material/medium semantics 不变。

此版本为应用 GPUScene shader source 增加内容寻址缓存：完整展开后的 UTF-8 source、resource path、shader stage、HDR output 和 SER options 共同构成缓存 identity。经过验证的 SPIR-V 会保留在进程内，并持久化到 `%LOCALAPPDATA%\RTRenderer\shader-cache`（或文档指定的 override 目录）。缓存读取和写入尽力而为；缓存不可用或损坏不会改变渲染正确性。同一 JVM 中并发打开 renderer 会共享一个 compilation future。

此缓存用于降低冷启动时 shaderc 的 CPU 开销。Vulkan pipeline cache 与此 SPIR-V cache 是两个独立层：前者缓存 driver pipeline state；本版本避免重复编译相同的 application GLSL。缓存通过 source 或 option 变更对应的 SHA-256 identity 失效。

此版本仅交付到 GitHub 和本地 staging repository，未上传 Maven Central。可用以下命令在本地构建和使用：

```powershell
.\gradlew.bat publishAllToLocalStagingRepository --dependency-verification=strict --no-daemon --console=plain
```

6.0.0 ABI snapshot 仍是全部 6.x patch releases 的 major baseline。
