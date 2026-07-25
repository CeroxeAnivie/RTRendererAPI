# RTRendererAPI Java

RTRendererAPI 是面向 Java 25 的硬件光线追踪渲染库。当前发布范围只包含 Windows 10 x64 或更高版本、NVIDIA GeForce RTX 20 系或更新架构，以及 Vulkan 1.2 或更高版本。

应用只需要依赖 `renderer-api`。Windows Vulkan 后端、LWJGL 和对应 Windows natives 会通过 Maven 传递依赖自动解析；业务代码不需要再单独声明 `renderer-core`。

## 目录结构

```text
renderer-api/                    公共 API、不可变模型、异常、SPI 与 Vulkan 专家扩展
renderer-core/                   Windows NVIDIA Vulkan RT 后端与预编译 SPIR-V
gradle/published-consumer-smoke/ 独立 Maven 消费方编译门禁
docs/                            Java 指南、API 参考、互操作与支持矩阵
```

## 支持范围

| 项目 | 要求 |
| --- | --- |
| 操作系统 | Windows 10 x64 或更高版本 |
| Java | Java 25 |
| GPU | NVIDIA GeForce RTX 20 系或更新架构 |
| 图形 API | Vulkan 1.2 或更高版本，并通过运行时 hardware RT capability probe |
| 简单输出 | 异步托管 display-ready RGBA8 `CpuFrame`；无需 Vulkan 知识 |
| GPU 显示 | 官方托管 Vulkan swapchain presenter；无 CPU 图像回读 |
| 专家输出 | Win32 Vulkan external-memory lease；可选 linear HDR RGBA16F |

AMD、Intel、Linux、macOS、移动平台、D3D12、Metal 和软件渲染器不属于当前发布范围。完整声明与实机证据见 [支持与验证矩阵](docs/SUPPORT.md)。

## 快速开始

### Maven 坐标

```xml
<dependency>
    <groupId>top.ceroxe.rt</groupId>
    <artifactId>renderer-api</artifactId>
    <version>0.2.0</version>
</dependency>
```

### Gradle Kotlin DSL：

```kotlin
dependencies {
    implementation("top.ceroxe.rt:renderer-api:0.2.0")
}
```

### 示例代码

```java
import java.time.Duration;

import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.CpuFrame;
import top.ceroxe.rt.renderer.api.RayTracingRenderer;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererBootstrap;
import top.ceroxe.rt.renderer.api.SceneTransaction;

public final class Main {
    public static void main(String[] args) throws Exception {
        try (RayTracingRenderer renderer = RendererBootstrap.open()) {
            long sceneRevision = renderer.apply(SceneTransaction.empty(0L))
                    .acceptedSceneRevision();

            CameraState camera = CameraState.lookAt(
                    0.0, 1.0, 5.0,
                    0.0, 1.0, 0.0
            ).aspectRatio(16.0 / 9.0).build();

            renderer.submit(RenderFrameRequest.builder(0L, 1280, 720, camera)
                    .minimumSceneRevision(sceneRevision)
                    .build());

            CpuFrame frame = renderer.awaitLatestCpuFrame(Duration.ofSeconds(5))
                    .orElseThrow(() -> new IllegalStateException("frame timed out"));
            System.out.println(frame.width() + "x" + frame.height());
        }
    }
}
```

这段代码只使用托管 CPU 帧，不要求调用方了解 Vulkan。场景资产、材质、实例和灯光的完整发布流程见 [Java 开发指南](docs/Java.md)。已经拥有 Vulkan device/queue 的调用方再阅读 [Vulkan 专家互操作](docs/Vulkan-Interop.md)。

### 官方 GPU 显示路径

普通桌面应用无需自己编写 Vulkan import/swapchain 代码。关闭 CPU readback 后，通过 renderer 绑定的官方 presenter 直接显示 external-memory 帧：

```java
RayTracingRendererConfig config = RayTracingRendererConfig.builder()
        .cpuFrameReadbackEnabled(false)
        .build();

try (RayTracingRenderer renderer = RendererBootstrap.open(config);
     VulkanFramePresenter presenter = VulkanFramePresenter.open(
             renderer,
             VulkanFramePresenterConfig.builder()
                     .presentMode(VulkanFramePresenterConfig.PresentMode.UNCAPPED)
                     .maximumFramesQueuedAhead(2)
                     .build())) {
    VulkanFrameInterop interop = renderer.extension(VulkanFrameInterop.class).orElseThrow();
    // apply scene, then submit with trySubmit(...)
    if (interop.pollLatestFrame() instanceof VulkanFrameInterop.FrameAvailable frame) {
        VulkanFramePresenter.PresentationResult result =
                presenter.presentAndRelease(frame.lease());
        // Only Outcome.PRESENTED counts as an actually visible/present-queued frame.
    }
    System.out.println(presenter.activePresentMode());
}
```

`maximumFramesQueuedAhead` 限制的是尚未被 presenter 消费的生产者领先量，不是时间型 FPS 锁。`trySubmit(...)` 在队列满时返回 `FrameSubmissionDeferred`，不会为正常背压构造异常；同一 sequence 只有成功提交后才能递增。`activePresentMode()` 返回平台最终选择的 `IMMEDIATE`、`MAILBOX` 或 `FIFO`，不能用配置偏好冒充实际模式。

### FPS 口径与当前实机证据

- **Present FPS**：仅在 `presentAndRelease(...)` 返回 `Outcome.PRESENTED` 后计数，表示进入平台 presentation queue 的实际帧。
- **Trace capacity**：由 GPU ray-trace timing 的倒数估算，只描述光追阶段理论吞吐；它不包含 acquire、external-memory import/copy、queue ownership 和 present，不能当作肉眼看到的 FPS。

Windows 11、RTX 5080 Laptop GPU、2560×1600、三球动态场景、`IMMEDIATE`、每档 121 个实际 present 的本轮证据为：1 spp 166.5 FPS，2 spp 117.4 FPS，4 spp 56.6 FPS，8 spp 24.7 FPS。该数字是当前代码和机器的诊断证据，不是跨驱动/硬件性能承诺。当前 presenter 与 renderer 使用两个 Vulkan logical device，剩余成本包含 external-memory 同步与 swapchain copy；未来同 device surface-aware 路径仍有优化空间。

## 构建与测试

```cmd
.\gradlew.bat check --dependency-verification=strict --no-daemon --console=plain
```

在符合支持范围的 RTX 主机上运行短 GPU 验收：

```cmd
.\gradlew.bat strictAcceptanceTest --dependency-verification=strict --no-daemon --console=plain
```

验证“只依赖 `renderer-api`”的独立 Maven 消费方：

```cmd
.\gradlew.bat verifyPublishedMavenConsumer --dependency-verification=strict --no-daemon --console=plain
```

## 发布

本地 staging 与发布门禁不会自动上传远程仓库：

```cmd
.\gradlew.bat verifyReleaseArtifacts --dependency-verification=strict --no-daemon --console=plain
```

发布前必须人工确认版本、POM、签名、校验和、ABI baseline、Javadoc、独立 consumer 和支持矩阵证据。
