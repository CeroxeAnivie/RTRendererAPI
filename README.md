# RTRendererAPI

**由 Java 驱动的硬件光线追踪渲染库**

托管 CPU 帧 / Vulkan GPU 直显 / NVIDIA RTX 技术扩展

![Java](https://img.shields.io/badge/Java-21%2B-orange?style=flat-square&logo=openjdk&logoColor=white)
![Platform](https://img.shields.io/badge/Platform-Windows%2010%2B-lightgrey?style=flat-square&logo=windows&logoColor=white)
![GPU](https://img.shields.io/badge/GPU-NVIDIA%20RTX-76B900?style=flat-square&logo=nvidia&logoColor=white)
![Vulkan](https://img.shields.io/badge/Vulkan-1.2%2B-AC162C?style=flat-square&logo=vulkan&logoColor=white)
![Build](https://img.shields.io/badge/Build-Gradle-02303A?style=flat-square&logo=gradle&logoColor=white)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg?style=flat-square)](LICENSE)

---

> **简介**
>
> RTRendererAPI 为 Java 桌面应用与引擎进程提供厂商中立、宿主无关的光线追踪契约。公共 `renderer-api`
> 不包含游戏、引擎或 NVIDIA 专用场景字段；当前随 1.0.2 发布的实现范围是 Windows NVIDIA Vulkan RT。
> 应用只需依赖 `renderer-api`，即可获得后端发现、场景提交、异步 CPU 帧、官方 GPU presenter 与显式
> Vulkan 专家互操作。Windows Vulkan 后端、LWJGL、对应 Windows natives 与 NVIDIA provider 均通过
> Maven 传递依赖解析，无需手工部署 DLL。
>
> **推荐场景**：Java 桌面渲染器、离屏光追、实时 RTX 预览、已有 Vulkan 管线的 GPU 帧集成。

---

### ✨ 核心亮点

- 🚀 **硬件光线追踪**：基于 Vulkan RT 的 BLAS/TLAS、GPUScene 与预编译 SPIR-V 管线。
- ☕ **现代 Java API**：以 Java 21 为基线，提供不可变模型、Builder、类型化异常和确定性生命周期。
- 🖼️ **两类托管输出**：支持异步 display-ready RGBA8 `CpuFrame` 与无 CPU 回读的官方 GPU presenter。
- 🔗 **专家级 Vulkan 互操作**：支持 Win32 external memory lease，并可选 linear HDR RGBA16F。
- 🧠 **显式 RTX 能力协商**：可独立请求 DLSS SR、DLAA、NIS、NRD、FG/MFG、Reflex/PCL、SER 与 RTXMU。
- 🔎 **分层能力事实**：物理硬件、格式/handle 互操作、session 协商和真实执行状态互不混淆。
- 🔁 **事务式功能变更**：专家 controller 先给出精确同步或 rebuild 边界，只有 `APPLIED` 才表示提交成功。
- 🛡️ **可验证的降级策略**：能力拒绝、运行失败、等待证据和实际 fallback 使用不同状态表达。
- 📊 **结构化诊断**：性能、资源义务与帧生成证据均通过不可变快照发布，不依赖日志文本解析。
- 📦 **方便接入**：业务应用只需导入坐标，无需手工部署 DLL。

### 🧩 支持范围

| 项目 | 当前要求 |
| --- | --- |
| 操作系统 | 兼容目标为 Windows 10 x64 或更高版本 |
| Java | Java 21 或更高版本 |
| GPU | 兼容目标为 NVIDIA GeForce RTX 20 系或更新架构，并通过运行时能力探测 |
| 图形 API | Vulkan 1.2 或更高版本，并通过运行时 hardware RT capability probe |
| 简单输出 | 异步托管 display-ready RGBA8 `CpuFrame` |
| GPU 显示 | 官方 Vulkan swapchain presenter，无 CPU 图像回读 |
| 专家输出 | Win32 Vulkan external-memory lease；可选 linear HDR RGBA16F |

> AMD、Intel、Linux、macOS、移动平台、D3D12、Metal 与软件渲染器不属于 `1.0.2` 发布范围。兼容目标不是实机验收结论；本文不把尚未运行的 1.0.2 GPU smoke、特定宿主集成或跨硬件验证声明为已通过。

---

## 🚀 快速开始

### 📦 添加依赖

**Maven**

```xml
<dependency>
    <groupId>top.ceroxe.rt</groupId>
    <artifactId>renderer-api</artifactId>
    <version>1.0.2</version>
</dependency>
```

**Gradle Kotlin DSL**

```kotlin
dependencies {
    implementation("top.ceroxe.rt:renderer-api:1.0.2")
}
```

Maven Central 是发布制品的唯一事实源。GitHub 仅保存源码与对应的 `vMAJOR.MINOR.PATCH` provenance tag，
不维护另一套 Release 二进制资产。

### ☕ 渲染第一帧

下面的最小示例只使用托管 CPU 帧，调用方不需要了解 Vulkan：

```java
import java.time.Duration;

import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.CpuFrame;
import top.ceroxe.rt.renderer.api.RayTracingRenderer;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererBootstrap;
import top.ceroxe.rt.renderer.api.RendererPreset;
import top.ceroxe.rt.renderer.api.SceneTransaction;

public final class Main {
    public static void main(String[] args) throws Exception {
        try (RayTracingRenderer renderer = RendererBootstrap.open(RendererPreset.CPU_READBACK)) {
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

`RayTracingRenderer` 必须确定性关闭。场景 revision 与帧 sequence 必须严格递增；等待 API 始终有界，超时返回
`Optional.empty()`。完整资产、材质、实例与灯光流程见 [Java 开发指南](docs/Java.md)。

### 🖥️ 直接显示 GPU 帧

桌面应用可关闭 CPU readback，并使用 renderer 绑定的官方 presenter。内置路径复用同一 Vulkan device 与受控 queue；
只有真正的外部 consumer 才进入 Win32 external-memory 协议。

```java
try (RayTracingRenderer renderer = RendererBootstrap.open(RendererPreset.MANAGED_GPU_PRESENTATION);
     VulkanFramePresenter presenter = VulkanFramePresenter.open(
             renderer,
             VulkanFramePresenterConfig.builder()
                     .presentMode(VulkanFramePresenterConfig.PresentMode.UNCAPPED)
                     .maximumFramesQueuedAhead(2)
                     .build())) {
    presenter.pollEvents();
    presenter.presentLatestFrame().ifPresent(result -> {
        if (result.outcome() != VulkanFramePresenter.Outcome.PRESENTED) {
            throw new IllegalStateException("frame was not presented: " + result.outcome());
        }
    });
}
```

`presentLatestFrame()` 是普通应用的主推入口。`maximumFramesQueuedAhead` 控制尚未被 presenter 消费的生产者领先量，
不是 FPS 限制器。已有 Vulkan device/queue 的调用方再阅读 [Vulkan 专家互操作](docs/Vulkan-Interop.md)。

---

## ⚙️ RTX 能力与运行状态

`RendererBootstrap.open(RendererPreset.CPU_READBACK)` 是 CPU-readable 简单模式：它保留 balanced temporal path，
并以 `PREFERRED` 策略协商重建、降噪、SER 与 acceleration-structure memory optimization；能力不可用时按各自明确
fallback 保留基础渲染。`RendererPreset.MANAGED_GPU_PRESENTATION` 在同一策略上关闭 CPU readback，并在受支持时
请求普通 FG 2x 与低延迟 pacing。MFG 永不自动开启。安装 vendor runtime 或匹配 GPU 名称本身不能产生 `ACTIVE`。

专家模式仍使用同一个 `RayTracingRendererConfig.expertBuilder()`。Option value 是能力协商的唯一输入：

- `disabled()`：明确禁用该能力。
- `PREFERRED`：能力不可用时按显式 fallback 降级，否则保留基础路径并报告 `NOT_SUPPORTED`。
- `REQUIRED`：无法满足且未配置 fallback 时，在请求边界失败。

应用通过 `RenderingFeatureCapabilities` 读取功能域和具体技术状态：

```java
RenderingFeatureCapabilities capabilities = renderer
        .extension(RenderingFeatureCapabilities.class)
        .orElseThrow();

for (var technology : RenderingFeatureCapabilities.Technology.values()) {
    var entry = capabilities.technology(technology);
    System.out.println(technology + ": " + entry.status() + " (" + entry.reason() + ")");
}
```

| 状态 | 含义 |
| --- | --- |
| `AVAILABLE` | 能力已协商，尚无首帧执行证据 |
| `ACTIVE` | 已获得真实 dispatch、evaluate 或 present 证据 |
| `NOT_SUPPORTED` | 硬件、驱动、SDK 或当前渲染路径不支持 |
| `BLOCKED` | 初始化或运行期执行失败 |
| `FALLBACK_PENDING` | 已选择替代实现，尚无其执行证据 |
| `FALLBACK` | 替代实现已经实际接管 |

帧生成倍率与实际产出必须读取类型化证据，不得从 `reason()` 或日志反向解析：

```java
FrameGenerationEvidence generation = renderer.diagnostics().frameGenerationEvidence();
if (generation.reported()) {
    System.out.printf(
            "requested=%dx configured=%dx proxy=%d generated=%d misses=%d%n",
            generation.requestedPresentationMultiplier(),
            generation.configuredPresentationMultiplier(),
            generation.proxyPresentCalls(),
            generation.generatedFramesActuallyPresented(),
            generation.generationRequestMisses()
    );
}
```

`generatedFramesActuallyPresented` 是 provider/SDK 的累计呈现证据，不是显示器 scanout 测量。
Capability 与 diagnostics 都是不可变时间点快照；长期监控必须重新查询。

`RayTracingGpuDevice.hardwareCapabilities()` 只描述完整物理设备 probe 得到的厂商中立事实，
包括 RT prerequisites、limits、memory budget、timestamp，以及按 output format 和 native handle
区分的 external memory/semaphore 证据。它不表示某项 DLSS、NRD 或 FG 技术已请求或已运行。

需要运行期改变高级功能时，专家调用方通过 `RendererFeatureController` 先 `plan(target)`，再根据
`RendererFeaturePlan.disposition()` 和 `boundary()` 决定是否调用 `apply(plan)`。swapchain、pipeline、
scene 或 renderer rebuild 永远由结果显式返回，不会被库静默执行；只有 `APPLIED` 证明 profile 已提交。

---

## 🗂️ 项目结构

```text
renderer-api/                    公共 API、不可变模型、异常、SPI 与 Vulkan 专家扩展
renderer-core/                   Windows NVIDIA Vulkan RT 后端与预编译 SPIR-V
renderer-nvidia/                 可选 NVIDIA Streamline、NRD、SER 与 RTXMU 集成
demos/hex-ball/                  交互 Demo 与有界 GPU 验收入口
gradle/published-consumer-smoke/ 独立 Maven 消费方编译门禁
docs/                            指南、API 参考、互操作与支持矩阵
```

模块边界是发布契约的一部分：业务代码依赖 `renderer-api`，不直接构造或编译耦合后端实现。

---

## 🛠️ 构建与验证

项目包含 Gradle Wrapper，并默认使用 JDK 25 toolchain 编译与运行测试；所有 `JavaCompile` 任务强制
`--release 21`，因此发布字节码与 API 仍兼容 Java 21。IDEA 的 Gradle JVM 指向本机 JDK 25 后即可直接同步，
无需为日常构建附加版本属性。

Windows PowerShell 下执行完整 CPU、发布拓扑、ABI、Javadoc 与独立消费方门禁：

```powershell
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
.\gradlew.bat clean check assemble --dependency-verification=strict --no-daemon --console=plain
```

在符合支持范围的 RTX 主机上运行完整有界硬件验收：

```powershell
.\gradlew.bat strictAcceptanceTest --dependency-verification=strict --no-daemon --console=plain
```

单独验证“消费方只声明 `renderer-api`”的 Maven 发布拓扑：

```powershell
.\gradlew.bat verifyPublishedMavenConsumer --dependency-verification=strict --no-daemon --console=plain
```

### 🎮 运行 Hex Ball Demo

```powershell
.\gradlew.bat :demos:hex-ball:run --args="--width=2560 --height=1440 --spp=2"
.\gradlew.bat :demos:hex-ball:shadowJar
java -jar .\demos\hex-ball\build\libs\RTRendererAPI-HexBallDemo-1.0.2.jar `
  --width=2560 --height=1440 --spp=2
```

使用 `--duration-seconds=90` 可执行有界验收并走正常关闭路径。FG/MFG 的通过条件与证据字段见
[Demo 说明](demos/hex-ball/README.md)。

### 🔧 构建 NVIDIA 原生发布物

普通使用者不需要 NVIDIA SDK 或 CMake。只有维护者构建原生发布物时，才需要外部 NRD、NRI、
Streamline 与 RTXMU SDK。RTXMU 必须为官方 `v1.4` checkout 的固定 commit
`0c9ce1177000d5923e2cc6a35ae9cb7ff03748d2`。

```powershell
.\gradlew.bat :renderer-nvidia:compileNvidiaBridge `
  -PcmakeExecutable='<cmake-root>\bin\cmake.exe' `
  -PnrdSdkRoot='<nrd-root>' `
  -PnriSdkRoot='<nri-root>' `
  -PstreamlineSdkRoot='<streamline-root>' `
  -PrtxmuSdkRoot='<rtxmu-root>' `
  -PjdkHome='<jdk-21-root>' `
  --no-daemon --console=plain
```

这些路径只参与本机构建，不会写入发布物或仓库配置。

---

## 📚 文档

- [Java 开发指南](docs/Java.md)：完整场景、配置、背压、诊断与各 RTX 能力示例。
- [Java API 参考](docs/Java-API-Reference.md)：公共类型与稳定契约。
- [Vulkan 专家互操作](docs/Vulkan-Interop.md)：external memory、semaphore 与 queue ownership。
- [兼容性与版本策略](docs/COMPATIBILITY.md)：SemVer、公共 API/SPI 边界、弃用与发布事实。

---

## 🤔 常见问题（FAQ）

**Q：应用需要同时依赖 `renderer-core` 或 `renderer-nvidia` 吗？**

A：不需要。只声明 api 坐标即可，运行时实现与 native 依赖会传递解析。

**Q：为什么请求了 DLSS、NRD 或 FG，状态却不是 `ACTIVE`？**

A：请求成功只代表能力进入协商。只有真实 dispatch、evaluate 或 present 完成后才会发布 `ACTIVE`；请读取结构化
capability 与 diagnostics，而不是根据 GPU 型号或日志猜测。

**Q：`presentLatestFrame()` 暂时返回空，是否表示渲染失败？**

A：不一定。它是非阻塞轮询，空值通常表示当前没有可呈现的新帧。应用应通过事件循环稍后重试，并同时检查
`renderer.health()` 与 diagnostics。

**Q：AMD、Intel 或 Linux 能运行吗？**

A：不能把它们视为 `1.0.2` 的受支持目标。当前兼容范围只包含 Windows x64 与通过运行时 capability gate 的 NVIDIA RTX GPU；具体实机证据以对应提交的验收结果为准。

---

## 🔐 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源发布。
