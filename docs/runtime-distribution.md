# RTRendererAPI Windows 制品布局

当前交付版本为7.1.7。[本版说明](release-7.1.7.md)记录必要正确性、资源机制和制品身份，不将历史完整性能矩阵转签本版。四模块、runtime和匹配符号仅发布Maven Local；GitHub仅发布源码。7.1.6四模块与矩阵记录 `quality/data/resource-architecture-716-release.json` 及7.1.5的[最终验收](quality/resource-latency.md)保留原身份。

普通 Vulkan 路径由 renderer-api、renderer-core、renderer-nvidia 提供。renderer-rtxpt 是显式可选的 D3D12 全场景／透明介质托管后端，包含原生最终呈现和 Vulkan GPU 组合入口；发行目录同时携带这个模块，但 renderer-api 不默认依赖它。

## 使用

需要 Windows x86_64、Java 25 和通过后端能力检查的 NVIDIA RTX。普通路径使用 Vulkan，RTXPT 使用 D3D12/DXR。应用加入 top.ceroxe.rt:renderer-rtxpt:7.3.2 后，通过 Renderer.open("rtxpt", config) 选择托管路径。

```powershell
java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow `
  -cp 'lib/*;your-application.jar' your.application.Main
```

RTXPT JAR 自带 JNI、Streamline/DLSS、Agility、shader 和原始许可证。首次运行校验 SHA-256 并解包至用户缓存，之后复用；不需要安装 CUDA，不从开发机路径取 DLL，不在运行时下载 SDK。支持矩阵内 shader 已预编译；首次 PSO 创建仍需准备，普通材质更新复用通用运行期材质路径。具体支持范围见 release-independent-native-and-host-lighting.md。

公共预算支持 LOW／MEDIUM／HIGH，工厂无参与三 Demo 缺省字段统一中档。JAR 内包含已捕获上游动态排列的真实 DXIL，以及每个已捕获场景解析光照排列的 1～6 精确界面六档；未捕获的新组合仍由随包上游 DXC 编译缓存。打包校验源码指纹和二进制哈希，仓库外独立缓存验收使用 `:renderer-rtxpt:rtxptPackagedBudgetNativeSelfTest` 禁止 fallback，检查被测排列均从包加载。构建命令与范围见[预算指南](performance-budgets.md)。

### 三个可执行 Demo

在仓库根目录运行 `./gradlew.bat :demos:hex-ball:shadowJar :demos:hex-ball:mediumShadowJar` 后：

```powershell
$demoVersion = (Get-Content .\gradle.properties -Encoding UTF8 | Select-String '^project_version=(.+)$').Matches.Groups[1].Value

# 原 Vulkan 弹球
java -jar ".\demos\hex-ball\build\libs\RTRendererAPI-HexBallDemo-$demoVersion.jar" --windowed
# RTXPT 新路径弹球
java -jar ".\demos\hex-ball\build\libs\RTRendererAPI-HexBallDemo-$demoVersion.jar" --rtxpt --windowed
# RTXPT 介质
java -jar ".\demos\hex-ball\build\libs\RTRendererAPI-MediumDemo-$demoVersion.jar"
```

三个交互入口默认请求 2× FG，HexBall JAR 无参数打开 API 工作台。两个弹球使用 `--duration-seconds=30`，介质使用 `--seconds=30`；不传时限持续运行。完整参数和真实交付判断见 [Demo 说明](../demos/hex-ball/README.md)。启动器会为子 JVM 配置 native access，普通库消费者仍自行管理 JVM 参数。

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
