# 更新日志

本文记录 RTRendererAPI 面向消费方可见的变化。版本遵循语义化版本；公共二进制兼容性由同版本 ABI baseline 与发布门禁共同验证。

## 0.2.0

### 新增

- 增加 renderer-bound `VulkanFramePresenter`、`VulkanFramePresenterConfig` 与 `VulkanFramePresenterFactory`，为普通桌面应用提供官方 GPU external-memory import、swapchain copy、present、consumer completion 和 lease 关闭路径。
- 增加 `RayTracingRenderer.trySubmit(...)` 及穷尽的 `FrameSubmitted`/`FrameSubmissionDeferred` 结果，正常容量背压不再依赖异常控制流。
- external image descriptor 增加稳定 `resourceId` 与显式 `memoryTypeIndex`，允许 presenter 安全复用 import，同时拒绝 identity/metadata 冲突。
- presenter 公开平台实际选中的 `IMMEDIATE`、`MAILBOX` 或 `FIFO` present mode，并提供可配置的 producer lead 上限。

### 修复

- 修复 GPU scene session 丢弃已完成但尚未显示帧的问题。官方 presenter 现在以实际 lease 消费回调退休提交占位，阻止生产端计算数千个永远不可见的帧并饿死 swapchain。
- 修复 presenter 关闭和 Vulkan device recovery 后残留生产者占位的问题；两条路径都确定性清空不可再消费的队列状态。
- 动态场景 TLAS 首次以 `ALLOW_UPDATE` 构建，变换更新使用持久化 instance/scratch 资源与 `MODE_UPDATE`，并通过 descriptor-safe 目标轮换复用避免逐代初始构建。
- 本地 `check`/staging 不再错误触发 GPG；签名任务只在显式 `-PcentralRelease=true` 的远程发布图中接线。

### 性能证据

- Windows 11、RTX 5080 Laptop GPU、2560×1600、三球场景、`IMMEDIATE`、每档 121 次实际 present：1/2/4/8 spp 分别为 166.5/117.4/56.6/24.7 FPS。
- 2 spp 回归场景由 6–9 visible FPS 恢复到约 117–120 present FPS；提交数与显示数保持同量级，不再用 trace capacity 冒充可见 FPS。

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
