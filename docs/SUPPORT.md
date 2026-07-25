# 支持与验证矩阵

本文区分“声明支持”和“已经实机验证”。只有对应操作系统、GPU、驱动和压力条件真实执行完整门禁，才能产生该组合的验证证据。

## 0.1.1 支持边界

| 维度                  | 声明支持                                                                    |
|---------------------|-------------------------------------------------------------------------|
| 操作系统                | Windows 10 x64 或更高版本                                                    |
| Java                | Java 25                                                                 |
| 图形 API              | Vulkan 1.2 或更高版本                                                        |
| GPU                 | NVIDIA GeForce RTX 20 系或更新架构，并通过运行时 hardware RT capability gate         |
| Native 外部帧          | Win32 `OPAQUE_WIN32` external memory，显式 external queue-family ownership |
| Native 输出           | SDR RGBA8；能力满足时可选 linear scene-referred HDR RGBA16F                     |
| Consumer completion | `CpuCompleted`；以及当前 lease capability 协商接受的 Win32 external semaphore     |
| 显存治理                | `VK_EXT_memory_budget` 可用时采用驱动预算；VMA allocation 强制 within-budget        |

以下平台和后端不在 0.1.1 支持范围：AMD、Intel、Linux、macOS、移动平台、D3D12、Metal 和软件渲染器。

## 当前实机证据

| 组合                                              | 证据状态          |
|-------------------------------------------------|---------------|
| Windows 11 + NVIDIA GeForce RTX 5080 Laptop GPU | 当前仓库完整原生门禁曾通过 |
| Windows 10                                      | 未验证           |
| RTX 20 / Turing                                 | 未验证           |
| RTX 30 / Ampere                                 | 未验证           |
| RTX 40 / Ada                                    | 未验证           |
| 旧版受支持驱动                                         | 未验证           |
| 低显存、WDDM 压力、多 NVIDIA GPU/eGPU                   | 未验证           |
| 休眠恢复、设备移除、8 小时 soak                             | 未验证           |

单机结果只证明执行时的硬件和软件组合，不能推导为同系列、同架构或其他 Windows 版本已经通过。

## 能力探测

`RendererBootstrap.availableGpuDevices()` 只返回满足 provider 硬件 RT admission 的设备。每个 `RayTracingGpuDevice` 还发布具体
capability 和限制；应用必须以 capability 与 `open(...)` 结果为准，不能根据 GPU 名称猜测支持。

选择 `LINEAR_HDR_RGBA16F` 时，provider 会验证 RGBA16F storage image 与 Win32 export。缺少能力会在 probe/open 明确拒绝，不会静默降级成
SDR。

## 自动门禁

普通 Windows CI 执行：

```cmd
.\gradlew.bat clean check assemble --dependency-verification=strict --no-daemon --console=plain
```

该门禁覆盖 Java 编译、contract self-tests、API ABI、Javadoc `-Werror`、依赖校验、发布 POM、隔离 Maven 消费者、runtime native
classpath 和仓库规则，但不产生 RTX 实机证据。

带 `Windows`、`X64`、`rtrenderer-rtx` 标签的 self-hosted runner 执行：

```cmd
.\gradlew.bat strictAcceptanceTest --dependency-verification=strict --no-daemon --console=plain
```

该统一命令包含确定性 contract、发布与隔离消费者门禁，以及有界的 Vulkan RT device、BLAS/TLAS、GPUScene、复杂场景读回和
1920×1080 吞吐验证。它不执行长时 soak；没有受支持 GPU 的托管 runner 不能替代这部分实机证据。

## 性能证据要求

任何性能结论必须同时记录 GPU stable ID/UUID、驱动、Windows、JDK、代码版本、场景版本、分辨率、平均值、P95/P99、低窗口、最大停顿、冷启动和原始报告。缺少这些上下文的单次
FPS 数字不构成发布承诺。
