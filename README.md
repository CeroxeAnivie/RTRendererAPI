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
| 普通输出 | 托管 display-ready RGBA8 `CpuFrame` |
| 专家输出 | Win32 Vulkan external-memory lease；可选 linear HDR RGBA16F |

AMD、Intel、Linux、macOS、移动平台、D3D12、Metal 和软件渲染器不属于当前发布范围。完整声明与实机证据见 [支持与验证矩阵](docs/SUPPORT.md)。

## 快速开始

### Maven 坐标

```xml
<dependency>
    <groupId>top.ceroxe.rt</groupId>
    <artifactId>renderer-api</artifactId>
    <version>0.1.1</version>
</dependency>
```

### Gradle Kotlin DSL：

```kotlin
dependencies {
implementation("top.ceroxe.rt:renderer-api:0.1.1")
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
