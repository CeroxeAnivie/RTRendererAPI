# RTRendererAPI 7.0.0 Windows 候选制品

普通 Vulkan 路径由 renderer-api、renderer-core、renderer-nvidia 提供。renderer-rtxpt 是显式可选的 D3D12 托管透明介质后端，发行目录同时携带这个模块，但 renderer-api 不默认依赖它。

## 使用

需要 Windows x86_64、Java 25 和通过后端能力检查的 NVIDIA RTX。普通路径使用 Vulkan，RTXPT 使用 D3D12/DXR。应用加入 top.ceroxe.rt:renderer-rtxpt:7.0.0 后，通过 Renderer.open("rtxpt", config) 选择托管路径。

```powershell
java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow `
  -cp 'lib/*;your-application.jar' your.application.Main
```

RTXPT JAR 自带 JNI、Streamline/DLSS、Agility、shader 和原始许可证。首次运行校验 SHA-256 并解包至用户缓存，之后复用；不需要安装 CUDA，不从开发机路径取 DLL，不在运行时下载 SDK。首次 shader/PSO 编译可能需要等待，进度写日志，不创建独立弹窗。具体支持范围见 7.0.0.md。

## 文件与许可证

- lib/：完整 API 运行图及可选 RTXPT JAR。
- maven/：四模块 runtime、sources、Javadoc、POM/module 和校验文件。
- source/：四模块实现、测试、构建入口与校验工具。源码重建原生后端仍需固定版本的上游依赖。
- docs/：当前合同和历史记录；历史 CUDA/LuxCore 报告不作为 RTXPT 验收结果。
- SHA256SUMS 与 maven/SHA256SUMS：目录内文件哈希。

项目适配代码采用 Apache-2.0；NVIDIA、Microsoft 和其他第三方运行时遵循各自许可证。RTXPT JAR 的 META-INF/NOTICE-RTXPT.txt 与嵌套 runtime 内 ThirdPartyLicenses/ 必须随包保留，不得将全部原生组件视为 Apache 授权。

## 验证

```powershell
./gradlew.bat :renderer-rtxpt:check
./gradlew.bat :renderer-rtxpt:rtxptNativeSelfTest
./gradlew.bat :renderer-rtxpt:rtxptPackagedNativeSelfTest
./gradlew.bat :demos:hex-ball:rtxptMediumBenchmark
./gradlew.bat verifyRuntimeDistribution
```

packaged 测试只使用打包 JAR，在仓库外临时工作目录中验证自动解包、实际 DLSS-RR、真实出帧、变换增量、场景切换、截图和输出尺寸变化。显式 native 路径覆盖会使此测试失败。硬件测试串行执行。基准记录真实 Present、实际内部分辨率与重建设置，FG 关闭；预热和一次性编译不计入稳态 FPS。

从源码构建 RTXPT 时，运行 tools/Build-RtxptBridge.ps1，并通过 -StreamlineSdk 指定官方 SDK 目录。代理仅通过调用进程环境配置，不写入项目。具体命令及本轮实测见 quality/rtxpt-managed-medium-migration.md。