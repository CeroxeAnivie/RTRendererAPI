# 更新日志

本文记录 RTRendererAPI 面向消费方可见的变化。版本遵循语义化版本；公共二进制兼容性由同版本 ABI baseline 与发布门禁共同验证。

## 0.3.0

### 新增

- `SceneInstance` 新增实例级光照坐标。无逐顶点 lightmap 坐标的 mesh 可通过 `lightmapCoordinates(first, second)` 或 `packedLight(...)` 参与统一 lightmap 调制，而无需重建不可变 mesh 或 BLAS。
- 公共契约定义 `MAX_LIGHT_COORDINATE` 与 `FULL_BRIGHT_PACKED_LIGHT`，并拒绝越界的 packed 或未打包坐标。

### 修复

- GPUScene 可选 vertex stream 在删除精简 mesh 时只回收真实存在的 arena allocation，避免把缺失的 normal、tangent、UV 或 color stream 误判为非法删除。
- LWJGL 3.3.3 兼容释放路径使用 native allocation 基址，避免 buffer position 改变后释放偏移地址。

### 兼容性

- 将编译与运行基线从 Java 25 降至 Java 21；API 与 Core 发布物固定为 Java 21 classfile（major version 65），Java 21–25 消费方无需预览特性即可加载。
- Gradle daemon 可继续运行在 PATH 中的 Java 25；JavaCompile、Javadoc、JavaExec 和隔离发布消费者显式使用 Java 21 toolchain，并通过 `--release 21` 锁定标准库 API 与字节码目标。
- Java 21 启动器不再接收仅由 Java 24 及更高版本识别的 `--sun-misc-unsafe-memory-access=allow`；`--enable-native-access=ALL-UNNAMED` 保持启用。

### 验证

- 在真实 Oracle GraalVM JDK 21 上通过 API/Core 编译、契约测试和 RTX 5080 Vulkan RT 原生渲染门禁。

## 0.2.0

### 新增

- 增加 renderer-bound `VulkanFramePresenter`、`VulkanFramePresenterConfig` 与 `VulkanFramePresenterFactory`，为普通桌面应用提供官方 GPU external-memory import、swapchain copy、present、consumer completion 和 lease 关闭路径。
- 增加 `RayTracingRenderer.trySubmit(...)` 及穷尽的 `FrameSubmitted`/`FrameSubmissionDeferred` 结果，正常容量背压不再依赖异常控制流。
- external image descriptor 增加稳定 `resourceId` 与显式 `memoryTypeIndex`，允许 presenter 安全复用 import，同时拒绝 identity/metadata 冲突。
- presenter 公开平台实际选中的 `IMMEDIATE`、`MAILBOX` 或 `FIFO` present mode，并提供可配置的 producer lead 上限。
- 简单模式增加 `VulkanFramePresenter.presentLatestFrame()`：官方 presenter 可在同一 logical device 上以 GPU timeline 同步直接消费最新 managed frame，无需应用手写 external-memory/semaphore 生命周期。
- 官方 GLFW presenter 增加 dedicated presentation queue、GPU managed-copy 快速路径、窗口内性能 HUD 与可查询的分阶段 present timing；专家模式 `VulkanFrameInterop.pollLatestFrame()` 继续保留 completed external lease 语义。

### 修复

- 修复 GPU scene session 丢弃已完成但尚未显示帧的问题。官方 presenter 现在以实际 lease 消费回调退休提交占位，阻止生产端计算数千个永远不可见的帧并饿死 swapchain。
- 修复 presenter 关闭和 Vulkan device recovery 后残留生产者占位的问题；两条路径都确定性清空不可再消费的队列状态。
- 修复 host wait/CPU completed-frame 语义把可见呈现串行化在 producer fence 之后的问题；普通调用改为同 logical-device timeline 依赖，presentation queue 可与下一帧 ray dispatch 并行推进。
- 修复 consumer completion 发布顺序：外部完成信号提交先进入 Vulkan queue，lease 才允许释放，避免在 NVIDIA 驱动上提前复用 frame slot 导致 device lost。
- 动态场景 TLAS 首次以 `ALLOW_UPDATE` 构建，变换更新使用持久化 instance/scratch 资源与 `MODE_UPDATE`，并通过 descriptor-safe 目标轮换复用避免逐代初始构建。
- 本地 `check`/staging 不再错误触发 GPG；签名任务只在显式 `-PcentralRelease=true` 的远程发布图中接线。

### 性能证据

- Windows 11、RTX 5080 Laptop GPU、2560×1600、三球场景、2 spp、`IMMEDIATE`、独显显示路径：rolling Present 189.1 FPS、全程 Present 176.1 FPS、trace 4.20 ms / 238.3 FPS。
- 同一验收运行中 acquire 为 0.632 ms、native present 为 0.400 ms；dedicated copy 的 GPU timing 没有取得有效样本，因此不以 `NaN` 推导或宣称 copy 成本。
- 2 spp 回归场景由 6–9 visible FPS 恢复到约 176–189 actual present FPS；统计同时公开 Present 与 Trace capacity，不再用 producer throughput 冒充肉眼可见帧率。

## 0.1.2

### 修复

- 将托管 `CpuFrame` 从逐帧 staging buffer、独立提交和 queue-idle 等待改为 frame-slot 常驻异步 readback ring；图像复制与 ray dispatch 位于同一 producer command buffer，CPU 只读取已完成 slot。
- 为不透明 metallic PBR 材质增加一次有界、roughness-aware 的 GGX 场景反射；反射使用 Fresnel 与 Smith visibility 权重，二次命中不递归。

### 新增

- 增加 renderer-lifetime 配置 `cpuFrameReadbackEnabled(boolean)`；默认开启普通托管帧，纯 Vulkan interop 消费方可显式关闭 host readback 分配与复制。

## 0.1.1

### 变更

- 升级 fastutil 至 8.5.19，并将全部 LWJGL 模块与 Windows natives 对齐至 3.4.2。
- 升级并继续以完整 commit SHA 固定 GitHub Actions 供应链依赖。
- 将宿主机墙钟性能数据与确定性正确性门禁解耦，避免共享 CI runner 抖动造成伪失败。
- 补全 Maven Central 所需的 Javadoc、OpenPGP 签名和 Portal bundle 发布链路。

## 0.1.0

### 新增

- 提供单一 Maven 入口 `top.ceroxe.rt:renderer-api:0.1.0`，Windows Vulkan 后端、LWJGL 与 Windows natives 通过传递依赖解析。
- 提供 Windows 10+、NVIDIA GeForce RTX 20 系或更新架构、Vulkan 1.2+ 的硬件光线追踪后端。
- 提供不可变场景事务、纹理、材质、网格、实例、灯光、相机和帧请求 API。
- 提供 Builder-only 的 renderer 配置与帧请求，支持 GPU 枚举和稳定 identity 选择。
- 提供托管 `CpuFrame` 普通路径，以及显式 opt-in 的 Vulkan external-memory 专家扩展。
- 提供 SDR RGBA8 与能力受控的 linear HDR RGBA16F native 输出。
- 提供确定性空间抗锯齿、temporal reconstruction 与显式 history reset reason。
- 提供结构化诊断、设备恢复信息、资源欠账与稳定异常分类。
- 提供 ABI baseline、Javadoc、独立 Maven consumer、依赖验证、可重现归档、校验和与短 GPU 验收门禁。

### 约束

- 当前版本不支持 AMD、Intel、Linux、macOS、移动平台、D3D12、Metal 或软件渲染器。
- Java 运行与编译基线固定为 Java 25。
- Vulkan 零拷贝扩展要求调用方自行正确实现 Win32 handle、external semaphore 与 queue-family ownership 生命周期。
