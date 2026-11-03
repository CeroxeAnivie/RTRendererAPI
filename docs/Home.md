# RTRendererAPI Java 开发指南

RTRendererAPI 是一个以 JDK 25 构建、以 Java 25 字节码与运行时为公开基线的多模块工程。所有 Java 编译任务强制
`--release 25`。`renderer-api` 保持后端无关，`renderer-core` 提供 Windows Vulkan RT，
可选 `renderer-rtxpt` 提供 D3D12 托管光学与原生呈现；应用使用公共入口，不构造内部实现类。

7.0.3 延续 7.0.0 的介质模型与执行结构合同，增加 RTXPT 资源增量更新、空间灯树及窗口等待修复，本轮只交付 GitHub 源码和本地 Maven 制品，不上传 Central。`publishAllToLocalStagingRepository` 生成完整本地 Maven 制品，并由独立消费方验证。

> **默认推荐 RTXPT 单后端**完成完整 3D 与最终呈现，显式使用 `Renderer.open("rtxpt", RendererConfig.pathTracedPresentation())`。

推荐配置无参统一中档；弱设备可从 `RendererPerformanceTier.LOW` 起步，显式调节 GPU、CPU 与资产预算。六界面 1～6 是公共配置，已捕获排列以六档 shader 随包发行。学习入口见[性能档位与资源预算](performance-budgets.md)。
> **RTXPT＋Vulkan 混合模式不推荐作为默认方案**：它增加整帧复制、跨 API 同步和显存成本，仅供确实需要 Vulkan 独立命令／颜色叠层的专家使用。当前以功能正确、稳定出帧为维护目标，不做混合模式性能优化。

## 阅读路线

先按自己的角色选择一条路径，不需要从头阅读全部文档：

| 你要完成的事 | 从这里开始 | 接下来阅读 |
| --- | --- | --- |
| 先打开窗口，看三个真实示例 | [三个 Demo](../demos/hex-ball/README.md) | [Java 开发指南](Java) |
| 第一次渲染一帧或维护 retained scene | [Java 开发指南](Java) | [Java API 参考](Java-API-Reference) |
| 玻璃、吸收、嵌套介电及 RTXPT 原生窗口 | [介质演示](medium-gallery.md) | [后端选择](backend-routing.md) |
| RTXPT 完整 3D＋Vulkan 独立工作／UI | [GPU 组合](d3d12-vulkan-composition.md) | [Java API 参考](Java-API-Reference) |
| 保留已有 resource、shader 和 command 顺序 | [通用命令与硬件光追](Generic-Commands-and-Ray-Tracing) | [通用渲染语义参考](Generic-Rendering-Semantics) |
| 消费 GPU frame 或管理 native synchronization | [Vulkan 专家互操作](Vulkan-Interop) | [Java API 参考](Java-API-Reference) |
| 升级、发布或实现 provider | [兼容性与版本策略](COMPATIBILITY) | `CONTRIBUTING.md` |

## 入口

- [Java 开发指南](Java)
- [三个 Demo 的打开方式](../demos/hex-ball/README.md)
- [后端选择与部署](backend-routing.md)
- [RTXPT 与 Vulkan 组合](d3d12-vulkan-composition.md)
- [当前介质场景](medium-gallery.md)
- [通用命令与硬件光追](Generic-Commands-and-Ray-Tracing)
- [Java API 参考](Java-API-Reference)
- [Vulkan 专家互操作](Vulkan-Interop)
- [兼容性与版本策略](COMPATIBILITY)

## 模块边界

- `renderer-api/`：Maven 公共契约。包含不可变场景模型、渲染器生命周期、托管 CPU 帧、稳定异常、SPI 和显式 Vulkan 扩展。
- `renderer-core/`：Windows Vulkan RT 后端。包含设备探测、GPUScene、BLAS/TLAS、帧资源、SPIR-V、外部内存和诊断实现。
- `renderer-nvidia/`：可选 NVIDIA provider。包含 DLSS、NIS、NRD、Reflex/PCL、SER、RTXMU 与随 JAR 分发的 native runtime。
- `renderer-rtxpt/`：可选 RTXPT D3D12 后端、薄 JNI、原生窗口与 Vulkan GPU 组合入口。
- `demos/hex-ball/`：直接消费同仓库 API 模块的交互示例、可编程场景示例、fat JAR 和 GPU smoke workload。
- `gradle/published-consumer-smoke/`：只从本地 Maven 制品解析依赖的独立消费方，用于阻止项目 classpath 掩盖发布缺陷。
- `docs/`：面向使用者的开发文档、兼容性事实和支持验证证据。

## 使用层级

| 层级          | 入口                                             | 调用方需要掌握                               |
|-------------|------------------------------------------------|---------------------------------------|
| 普通托管路径      | `Renderer` + `FrameSnapshot`              | Java 生命周期、场景 revision、帧 sequence      |
| RTXPT 托管光学 | `Renderer.open("rtxpt", ...)` + `RtxptAccess` | 创建线程、完整场景、原生窗口；无普通 CPU 帧 |
| GPU 组合 | `RtxptComposedRenderer` | 最终呈现归属、旧侧独立命令、叠层资源完成证据 |
| 通用命令与 RT 路径 | `submitResources` + `submitCommands` | resource generation、SPIR-V、同步与完成 evidence |
| 高级渲染控制      | 配置、设备枚举、HDR、抗锯齿、诊断                             | 显存预算、帧并发、输出策略                         |
| Vulkan 专家路径 | `renderer.extension(VulkanFrameInterop.class)` | 外部内存、队列所有权、semaphore、Win32 handle 所有权 |

## 当前能力与证据怎么读

当前 API／支持边界以 [7.0.3](7.0.3.md)、[Java API 参考](Java-API-Reference.md)和逐后端 capability 为准。已实现入口不等于每种硬件和功能组合都已验证；运行成功、GPU 完成和真正呈现也要分开读取。三个 Demo 默认请求 2× FG，库的 `pathTracedPresentation()` 默认关闭 FG。

RTXPT 接入、高级能力、组合与内存的实测入口见[文档清单](documentation-inventory.md)。旧 CUDA／LuxCore、散射／密度记录保留原时间与制品，已删除功能不能从历史报告推导为当前支持。

## 构建与测试

```powershell
.\gradlew.bat check --dependency-verification=strict --no-daemon --console=plain
```

完整硬件验收必须在符合支持范围的 RTX 主机上显式执行：

```powershell
.\gradlew.bat strictAcceptanceTest --dependency-verification=strict --no-daemon --console=plain
```

## 本地制品验证

```powershell
.\gradlew.bat verifyPublishedMavenConsumer --dependency-verification=strict --no-daemon --console=plain
```

该任务只向 `build/repository` 写入临时本地制品，不会发布到远程 Maven 仓库。
