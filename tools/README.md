# 用户工具

本目录保留源码构建、SDK 扩展、正式验收与证据分析工具。通过 Maven 或成品 JAR 使用渲染 API 时，不需要运行这些脚本，也不需要安装 RTXPT SDK、CMake 或 Python。

`Build-*`、`Stage-*` 和 `Invoke-BoundedBuild.ps1` 服务于构建与打包；`Test-*`、`Check-FileNaming.ps1` 和 `Verify-*` 是维护中的验收入口；`Analyze-*`、`Collect-*`、`Compare-*` 和光照成本工具用于读取正式验收数据。这些 `.ps1`／`.py` 是源码，不按扩展名当作临时文件删除。一次性日志、驱动脚本、实验覆盖层和缓存放在仓库外，已归档的正式 JSON／CSV 记录保留在 `docs/quality/data/`。

当前平台全量回归使用 `./gradlew.bat fullRegression --no-parallel --max-workers=1 --dependency-verification=strict --no-daemon --console=plain`，并按下文配置本机 SDK。该任务包含完整发布闭包、全部已注册 Java self-test 和 RTXPT C++ 契约测试；纯 benchmark 不自动加入。两个独立的 GPU buffer／pipeline 内存调查探针需要人工指定工作量，不作为功能回归入口。

| 工具 | 用途 | 常用参数 |
| --- | --- | --- |
| [Analyze-FlightResourceHotspots.py](Analyze-FlightResourceHotspots.py) | 将 JFR 快飞帧身份匹配到完整 native slow group，按 session/scope 去重并保留最慢父链。 | capture、--output |
| [Verify-FlightHotspotRelease.py](Verify-FlightHotspotRelease.py) | 核对当前分支源码已上传、四模块 Maven Local、受测 JNI 和未变 shader/依赖字节。 | --tested-native、--source-commit、--output |
| [Build-RtxptBridge.ps1](Build-RtxptBridge.ps1) | 对固定版本的 RTXPT 源码应用宿主适配，构建 JNI runtime 并收集上游许可证。 | `-SdkRoot`、`-CMakeExecutable`、`-StreamlineSdk`、`-MemoryLimitGB`、`-ConfigureOnly` |
| [Build-RtxptShaderBudgets.ps1](Build-RtxptShaderBudgets.ps1) | 使用已采集的上游编译清单生成 1～6 光学界面预算的 shader 包，或校验已有包。 | `-ManifestDirectory`、`-RuntimeDirectory`、`-OutputDirectory`、`-VerifyOnly` |
| [Invoke-BoundedBuild.ps1](Invoke-BoundedBuild.ps1) | 通过 Windows Job Object 限制构建进程及其子进程的总内存。 | `-FilePath`、`-ArgumentList`、`-WorkingDirectory`、`-MemoryLimitGB` |

上述三个构建工具均需 Windows 与 PowerShell 7，并提供 `-CheckPaths`：只解析、检查路径，不修改 RTXPT 源码、不构建、不启动目标进程。可用 `Get-Help <脚本路径> -Detailed` 查看参数。

所有相对 SDK、CMake、shader 和工作目录参数都以仓库根目录为基准；可从任意当前目录使用脚本的绝对路径调用。`Invoke-BoundedBuild.ps1` 的相对可执行文件路径则以选定的工作目录为基准；不带目录的命令从 `PATH` 查找。`Build-RtxptBridge.ps1` 通过自身目录定位 `Invoke-BoundedBuild.ps1`，请保留三者的目录关系。

## 构建 RTXPT runtime

准备官方 RTXPT `1.9.0` 固定提交 `303b408ab81790a5051abd7573830b7a4112eb8e`、CMake、Visual Studio 2022 C++ 构建工具和对应 Streamline SDK。先检查路径，再移除 `-CheckPaths` 执行构建：

```powershell
./tools/Build-RtxptBridge.ps1 `
  -SdkRoot '<RTXPT 源码目录>' `
  -CMakeExecutable '<CMake 目录>/bin/cmake.exe' `
  -StreamlineSdk '<Streamline SDK 目录>' `
  -MemoryLimitGB 10 -CheckPaths
```

SDK 路径只通过参数或环境变量传入，不写入仓库配置。构建脚本读取 `STREAMLINE_SDK` 作为 `-StreamlineSdk` 的可选默认值；代理由调用进程自行配置。

## RTXPT 原生内存诊断

生产默认关闭。只对待诊断的进程设置以下环境变量，再从同一终端启动应用：

```powershell
$env:RTR_RTXPT_MEMORY_DIAGNOSTICS='1'
$env:RTR_RTXPT_MEMORY_LOG=Join-Path (Get-Location) 'rtxpt-memory.log'
# 在此启动应用；不指定日志路径时写 stderr。
```

诊断记录资源名称、对齐后的容量、heap 类型、创建／释放、较大分配的模块栈，以及进程私有提交、工作集和 DXGI 显存数据。`trackedDefault` 是记录到的资源容量，不是物理 RAM，也不应与 DXGI 显存相加。诊断数据只用于归因，不用于 FPS 基准。

诊断开关在进程内首次读取后固定。下次正常启动前移除变量：

```powershell
Remove-Item Env:RTR_RTXPT_MEMORY_DIAGNOSTICS -ErrorAction SilentlyContinue
Remove-Item Env:RTR_RTXPT_MEMORY_LOG -ErrorAction SilentlyContinue
```

原生构建会运行关闭／开启两种 CPU 自检。资源按需分配修复保存在 `tools/patches/rtxpt-demand-resources.patch`，由构建脚本在固定版本宿主适配后应用；重复配置会验证补丁已完整应用。

## 构建 Vulkan 的 NVIDIA 桥接

该桥接使用 Gradle 任务 `:renderer-nvidia:compileNvidiaBridge`，配置入口如下。Gradle 属性通过 `-P名称=值` 传入，优先于对应环境变量；CMake 未指定时从 `PATH` 查找。

| Gradle 属性 | 环境变量 | 用途 |
| --- | --- | --- |
| `cmakeExecutable` | `CMAKE_EXECUTABLE` | CMake 可执行文件。 |
| `nrdSdkRoot` | `NRD_ROOT` | NRD SDK 根目录。 |
| `nriSdkRoot` | `NRI_ROOT` | NRI SDK 根目录。 |
| `rtxmuSdkRoot` | `RTXMU_ROOT` | RTXMU SDK 根目录。 |
| `streamlineSdkRoot` | `STREAMLINE_ROOT` | Vulkan NVIDIA 桥接的 Streamline SDK 根目录。 |
| `jdkHome` | `JAVA_HOME` | JNI 使用的 JDK 根目录；均未指定时回退到 Gradle JVM 的 `java.home`。 |

这里的 `STREAMLINE_ROOT` 供 Vulkan 桥接的 Gradle 任务使用；上面的 RTXPT PowerShell 工具使用 `-StreamlineSdk`／`STREAMLINE_SDK`。两套入口分别配置，不隐式猜测本机安装目录。

## 校验 shader 包

```powershell
./tools/Build-RtxptShaderBudgets.ps1 -VerifyOnly
```

默认目录位于仓库 `build/`。生成 shader 包还需要上游原生验收采集的 `.rtrdxc` 清单；具体覆盖与打包要求见[性能预算](../docs/performance-budgets.md)。`-VerifyOnly` 校验源码和二进制哈希，不运行 GPU。

## 限制构建内存

```powershell
./tools/Invoke-BoundedBuild.ps1 `
  -FilePath (Get-Command pwsh).Source `
  -ArgumentList @('-NoProfile', '-Command', './gradlew.bat check --no-daemon --console=plain') `
  -MemoryLimitGB 10
```

默认工作目录为仓库根目录。总预算中为 PowerShell 宿主保留 0.5 GB，其余作为子进程树的提交内存上限；超过预算时构建失败，不会悄悄放宽限制。

## 历史脚本

2026-11-02 删除了 38 个已追踪的一次性性能实验、诊断和回归脚本，并清理本地旧实验脚本及旧发行源码副本。历史质量报告中的命令用于解释当次证据，不再作为当前工具入口；需要追溯时从清理前提交 `eb3bc21` 查阅。正式验证仍使用 Gradle 中维护的 `check` 和对应 native self-test 任务。第三方 SDK 内部的构建脚本属于依赖源码，不是本项目的用户工具。
