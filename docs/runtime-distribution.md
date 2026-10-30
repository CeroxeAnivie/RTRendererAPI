# RTRendererAPI 7.0.0 Windows 候选制品

本包提供统一公共入口 `renderer-api`、Vulkan 后端 `renderer-core`、NVIDIA provider、全部解析后的运行依赖、Maven 元数据、源码、Javadoc、文档、许可证清单与 SHA-256。介质组合的实现范围见 `docs/7.0.0.md`，当前验收结果统一记录于 `docs/quality/verification-report.md` 顶部。

## 使用

需要 Windows x86_64、Java 25、Vulkan 硬件光追，以及支持当前 CUDA/NVRTC 12.9 的 NVIDIA 驱动。介质后端使用真实 CUDA 设备；不提供 CPU 渲染降级。NVRTC 12.9.86、匹配的 builtins 和 NVIDIA 原始许可证已嵌入 core JAR；运行时从 classpath 自动校验并解包到临时目录，无需安装 CUDA Toolkit，不在运行时下载库，也不更改进程 PATH。

启动已有应用：

```powershell
java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow `
  -cp 'lib/*;your-application.jar' your.application.Main
```

应用通过 `Renderer.open("vulkan-rt", config)` 使用统一入口。普通 retained scene 与专家 command/resource 入口保持分层。Java 公共 API 不依赖 core 或 NVIDIA 的实现类型。

Maven 使用 `maven/` 中的仓库布局，依赖坐标为 `top.ceroxe.rt:renderer-api:7.0.0`；其 POM 自动拉入 core、NVIDIA provider 和 Windows 原生依赖。若使用本地 Maven 仓库，仍需从 Maven Central 解析公开第三方 Java 依赖；`lib/` 已包含这些依赖，可直接离线运行。

## 文件与许可证

- `lib/`：运行时 JAR，包含匹配的 Windows 原生库。
- `maven/`：三个模块的 runtime、sources、Javadoc JAR 与 POM/module、校验文件。
- `source/`：API、core、provider 的实现和测试、构建入口与校验工具。NVIDIA SDK 源码构建的外部依赖仍须按模块构建说明准备，不包含本机 SDK 安装目录。
- `docs/`：当前功能边界、API 文档与历史质量记录。历史记录不是本候选的完整验收结论。
- `renderer-runtime.cdx.json`、`runtime-licenses.json`：精确依赖及嵌入 NVRTC 的哈希与许可证清单。
- `SHA256SUMS`：本包文件的哈希；`maven/SHA256SUMS` 的路径相对 `maven/`。

项目代码使用根目录 Apache-2.0 许可证。NVIDIA NVRTC 二进制遵循其自身 CUDA Toolkit 许可证，位于 core JAR 的 `META-INF/native/cuda/windows-x86_64/LICENSE.txt`，不因项目许可证而重新授权。第三方介质源码的适用许可证、NOTICE、作者和来源完整性清单位于 core JAR 随附的介质源码资源中，应与制品一并保留。

## 构建与验证

```powershell
./gradlew.bat check verifyReleaseChecksums verifyPublishedMavenConsumer
./gradlew.bat :renderer-core:rendererCoreMediumNativeGate
./gradlew.bat :renderer-core:rendererCoreUnifiedApiNativeGate
./gradlew.bat verifyRuntimeDistribution
./gradlew.bat verifyApiClosure
```

两组原生门禁需要真实兼容 GPU，并应串行运行；分别验证当前支持的介质范围与统一入口的通用契约。`verifyRuntimeDistribution` 校验 ZIP 内全部文件哈希、源码构建布局、三模块 Maven 制品，再生成 ZIP 的 SHA-256。

`verifyApiClosure` 是完整当前平台验收入口，包含各模块 `check`、全部 core 原生测试、GPUScene、介质、统一 API、NVIDIA 功能、全部展厅变体、独立 Maven 消费者和分发包。原快速 `strictAcceptanceTest` 保留。完整入口拒绝 `-x` 跳测、只测单场景及 benchmark 等子集选择器，并在全部依赖成功后写入 `build/reports/api-closure/result.json`。硬件任务共用串行设备锁。入口存在不代表已经通过，结果对应本次制品及文档声明的 Windows/NVIDIA 范围，不能外推到未实现的平台或设备。

`verifyPublishedMavenConsumer` 在独立目录仅声明 `renderer-api`，通过已发布 POM 解析三模块和原生依赖，然后执行公共介质值、统一提交结果、SPI 服务发现及嵌入原生文件 SHA-256 检查。这一步不创建 GPU 设备，不能替代介质 HDR 与统一入口的硬件门禁。根构建的 `--offline` 会传给该独立构建；离线执行要求公开第三方依赖已在缓存中。分发 ZIP 的 `lib/` 还须与完整解析运行图逐项一致，文件缺失、同名冲突或字节不同均验收失败。

首次源码构建在构建期下载固定 SHA-256 的 NVIDIA 官方 NVRTC 归档。离线构建可传入 `-PcudaNvrtcArchive=<cuda_nvrtc-windows-x86_64-12.9.86-archive.zip>`。代理由调用进程配置，不应将某台机器的代理地址写入工程。

深目录解压可能触发 MSBuild/ShaderMake 的 Windows 路径长度限制。可以通过 `-PnvidiaBridgeBuildDirectory=<较短的绝对目录>` 指定独立的 CMake 构建目录；每份源码应使用自己的目录，不要在并行构建间共享。最终 DLL 仍复制到模块的标准输出目录，不改变发布物布局，也不会将该路径写入源码配置。
