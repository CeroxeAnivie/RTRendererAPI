# 用户工具

本页介绍从源码构建本项目使用的三个 PowerShell 工具。通过 Maven 或成品 JAR 使用渲染 API 时，不需要运行这些脚本，也不需要安装 RTXPT SDK、CMake 或 Python。

| 工具 | 用途 | 常用参数 |
| --- | --- | --- |
| [Build-RtxptBridge.ps1](Build-RtxptBridge.ps1) | 对固定版本的 RTXPT 源码应用宿主适配，构建 JNI runtime 并收集上游许可证。 | `-SdkRoot`、`-CMakeExecutable`、`-StreamlineSdk`、`-MemoryLimitGB`、`-ConfigureOnly` |
| [Build-RtxptShaderBudgets.ps1](Build-RtxptShaderBudgets.ps1) | 使用已采集的上游编译清单生成 1～6 光学界面预算的 shader 包，或校验已有包。 | `-ManifestDirectory`、`-RuntimeDirectory`、`-OutputDirectory`、`-VerifyOnly` |
| [Invoke-BoundedBuild.ps1](Invoke-BoundedBuild.ps1) | 通过 Windows Job Object 限制构建进程及其子进程的总内存。 | `-FilePath`、`-ArgumentList`、`-WorkingDirectory`、`-MemoryLimitGB` |

三者均需 Windows 与 PowerShell 7。保留工具均提供 `-CheckPaths`：只解析、检查路径，不修改 RTXPT 源码、不构建、不启动目标进程。可用 `Get-Help <脚本路径> -Detailed` 查看参数。

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
