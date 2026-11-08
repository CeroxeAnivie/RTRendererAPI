# 源码工具

使用 Maven 或成品 JAR 接入渲染 API，无需运行这些脚本，也无需安装 RTXPT SDK、CMake 或 Python。这里的工具供源码构建、扩展开发、测试和打包使用。

## 构建

| 工具 | 用途 |
| --- | --- |
| [Prepare-RtxptUpstream.py](Prepare-RtxptUpstream.py) | 从锁定的 Git 对象导出 RTXPT 与子模块 |
| [Assert-RtxptUpstream.ps1](Assert-RtxptUpstream.ps1) | 检查官方源码及子模块身份 |
| [Assert-RtxptAuthorizedPatches.ps1](Assert-RtxptAuthorizedPatches.ps1) | 审计授权构建副本的非交互进度窗与网络策略差异 |
| [Build-RtxptUpstream.ps1](Build-RtxptUpstream.ps1) | 构建上游 RTXPT 目标 |
| [Build-RtxptBridge.ps1](Build-RtxptBridge.ps1) | 构建外部 JNI 适配层与运行时 |
| [Build-AdapterShaders.ps1](Build-AdapterShaders.ps1) | 编译独立适配层 shader |
| [Build-PrecompiledRuntime.py](Build-PrecompiledRuntime.py) | 准备并校验发行包预编译缓存输入 |
| [Invoke-BoundedBuild.ps1](Invoke-BoundedBuild.ps1) | 设置构建命令的进程树内存上限 |

各脚本的 `param` 或 `--help` 定义输入。SDK 和工具链路径通过参数或脚本支持的环境字段传入，不把机器专用路径写入工程。运行时布局见[运行时与 JVM 宿主](../docs/runtime-distribution.md)，扩展边界见[SDK说明](../docs/rtxpt-release-sdk.md)。

## 运行与检查

| 工具 | 用途 |
| --- | --- |
| [Invoke-RtxptHost.ps1](Invoke-RtxptHost.ps1) | 从专用宿主启动指定 Java 主类 |
| [Invoke-NativeSceneDemo.ps1](Invoke-NativeSceneDemo.ps1) | 启动一个原生场景示例 |
| [Check-FileNaming.ps1](Check-FileNaming.ps1) | 检查项目路径命名 |
| [Compare-HdrSignals.py](Compare-HdrSignals.py) | 比较 HDR PFM 信号与数值误差 |

当前平台完整回归入口为 `./gradlew.bat fullRegression --no-parallel --max-workers=1 --dependency-verification=strict --no-daemon --console=plain`。原生检查需要匹配的 SDK、运行时和 NVIDIA GPU；重构建与图形验证串行执行。

`Test-*` 提供专项检查；`Analyze-*`、`Collect-*`、`Compare-*` 和 `Verify-*` 处理相应 schema 的测量或制品证据。使用前核对脚本要求的版本、类路径和输入，旧 schema 工具不能自动用于新诊断格式。需要历史证据的工具应由调用者准备本地输入；这些数据不随源码仓库分发。

## 构建内存

```powershell
./tools/Invoke-BoundedBuild.ps1 `
  -FilePath (Get-Command pwsh).Source `
  -ArgumentList @('-NoProfile', '-Command', './gradlew.bat check --no-daemon --console=plain --no-parallel --max-workers=1') `
  -MemoryLimitGB 10
```

默认工作目录为仓库根目录。工具为 PowerShell 宿主预留 0.5 GiB，其余作为子进程树的提交内存上限。

生成包、临时脚本、原始日志和测量不提交到源码仓库；本地质量记录可放在被忽略的 `docs/quality/`。
