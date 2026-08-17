# RTRendererAPI Java 开发指南

RTRendererAPI 是一个以 JDK 25 构建、以 Java 21 字节码与运行时为公开基线的多模块工程。所有 Java 编译任务强制
`--release 21`。`renderer-api` 保持后端无关，`renderer-core` 提供 Windows Vulkan RT 实现；应用通过
`RendererBootstrap` 发现后端，不直接构造实现类。

Maven Central 是发布制品的唯一事实源。Git tag 只标识对应制品的源码 provenance，不承载第二套 GitHub Release 二进制分发。

## 阅读路线

先按自己的角色选择一条路径，不需要从头阅读全部文档：

| 你要完成的事 | 从这里开始 | 接下来阅读 |
| --- | --- | --- |
| 第一次渲染一帧或维护 retained scene | [Java 开发指南](Java) | [Java API 参考](Java-API-Reference) |
| 保留已有 resource、shader 和 command 顺序 | [通用命令与硬件光追](Generic-Commands-and-Ray-Tracing) | [通用渲染语义参考](Generic-Rendering-Semantics) |
| 消费 GPU frame 或管理 native synchronization | [Vulkan 专家互操作](Vulkan-Interop) | [Java API 参考](Java-API-Reference) |
| 升级、发布或实现 provider | [兼容性与版本策略](COMPATIBILITY) | `CONTRIBUTING.md` |

## 入口

- [Java 开发指南](Java)
- [通用命令与硬件光追](Generic-Commands-and-Ray-Tracing)
- [Java API 参考](Java-API-Reference)
- [Vulkan 专家互操作](Vulkan-Interop)
- [兼容性与版本策略](COMPATIBILITY)

## 模块边界

- `renderer-api/`：Maven 公共契约。包含不可变场景模型、渲染器生命周期、托管 CPU 帧、稳定异常、SPI 和显式 Vulkan 扩展。
- `renderer-core/`：Windows Vulkan RT 后端。包含设备探测、GPUScene、BLAS/TLAS、帧资源、SPIR-V、外部内存和诊断实现。
- `renderer-nvidia/`：可选 NVIDIA provider。包含 DLSS、NIS、NRD、Reflex/PCL、SER、RTXMU 与随 JAR 分发的 native runtime。
- `demos/hex-ball/`：只消费 Maven Central 正式制品的交互示例、fat JAR 和 GPU smoke workload。
- `gradle/published-consumer-smoke/`：只从本地 Maven 制品解析依赖的独立消费方，用于阻止项目 classpath 掩盖发布缺陷。
- `docs/`：面向使用者的开发文档、兼容性事实和支持验证证据。

## 使用层级

| 层级          | 入口                                             | 调用方需要掌握                               |
|-------------|------------------------------------------------|---------------------------------------|
| 普通托管路径      | `Renderer` + `CpuFrame`              | Java 生命周期、场景 revision、帧 sequence      |
| 通用命令与 RT 路径 | `submitResources` + `submitCommands` | resource generation、SPIR-V、同步与完成 evidence |
| 高级渲染控制      | 配置、设备枚举、HDR、抗锯齿、诊断                             | 显存预算、帧并发、输出策略                         |
| Vulkan 专家路径 | `renderer.extension(VulkanFrameInterop.class)` | 外部内存、队列所有权、semaphore、Win32 handle 所有权 |

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
