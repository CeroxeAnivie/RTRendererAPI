# 更新日志

本文记录 RTRendererAPI 面向消费方可见的变化。版本遵循语义化版本；公共二进制兼容性由同版本 ABI baseline 与发布门禁共同验证。

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
