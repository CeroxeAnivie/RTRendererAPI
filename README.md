# RTRendererAPI

**由 Java 驱动的硬件光线追踪渲染库**

当前版本 **7.3.1**：修复重复树节点日志及区域成员删除，补齐容量分账；保留7.3.0增量诊断接口。见[本版根因与验收](docs/quality/retained-update-maintenance.md)。

本批仅执行受影响的定向检查，未重复历史完整矩阵。历史结果保留原制品身份，MC快飞 FPS 回升与完整 GPU P95≤4ms 均未宣称达成。

可编程场景 / Vulkan 通用图形、计算与硬件 RT / RTXPT 托管光学与 GPU 直显

![Java](https://img.shields.io/badge/Java-25%2B-orange?style=flat-square&logo=openjdk&logoColor=white)
![Platform](https://img.shields.io/badge/Platform-Windows%2010%2B-lightgrey?style=flat-square&logo=windows&logoColor=white)
![GPU](https://img.shields.io/badge/GPU-NVIDIA%20RTX-76B900?style=flat-square&logo=nvidia&logoColor=white)
![Vulkan](https://img.shields.io/badge/Vulkan-1.2%2B-AC162C?style=flat-square&logo=vulkan&logoColor=white)
![Build](https://img.shields.io/badge/Build-Gradle-02303A?style=flat-square&logo=gradle&logoColor=white)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg?style=flat-square)](LICENSE)

---

> **简介**
>
> RTRendererAPI 为 Java 桌面应用与引擎进程提供厂商中立、宿主无关的通用渲染语义契约。公共
> `renderer-api` 不包含游戏、引擎或 NVIDIA 专用场景字段。保留的 Vulkan 后端提供 retained scene、
> 显式资源与命令、可编程 shader、异步 CPU 帧和 GPU presenter；可选 `renderer-rtxpt` 将完整 3D 场景
> 交给 NVIDIA RTXPT，处理吸收、玻璃、嵌套介电材质及其原生高级能力。
> 两者可通过 GPU 组合入口协作：RTXPT 完整 3D → Vulkan 独立工作／叠层 → RTXPT 最终呈现。
> 每个后端只执行自己支持的语义，具体选择见[后端路由](docs/backend-routing.md)和[组合指南](docs/d3d12-vulkan-composition.md)。
> 运行依赖和对应 native runtime 随 Maven 依赖解析，无需手工部署 DLL。
>
> **推荐场景**：Java 桌面渲染器、离屏光追、实时 RTX 预览、已有 Vulkan 管线的 GPU 帧集成。

> **7.1.0**：新增独立低分辨率光照、宿主底图增补和主表面传输；提供双向 GPU 交接、真实 BSDF 分层、紧凑光源、增量应用数据和离线 GPU 扩展。
> 同算法精确剪枝实测将目标光照阶段 P50 从 34.79 ms 降到 12.59 ms，直接光与 GI 输出逐位一致。支持范围、测试与使用入口见
> [版本说明](docs/release-independent-native-and-host-lighting.md)、[验收账本](docs/rtxpt-acceptance.md)和[普通／专家用法](docs/ordinary-and-expert.md)。
> 历史 CUDA／LuxCore 报告记录原实现，不作为当前 RTXPT 验收结论。

---

### ✨ 核心亮点

- 🚀 **硬件光线追踪**：基于 Vulkan RT 的三角形与 procedural/AABB BLAS、TLAS、GPUScene 与预编译 SPIR-V 管线。
- 💧 **托管透明介质**：可选 `renderer-rtxpt` 使用 NVIDIA RTXPT 处理均匀吸收、嵌套介电材质、粗糙折射与全反射；旧散射和密度参数已删除，详见[原生与宿主光照合同](docs/release-independent-native-and-host-lighting.md)。
- 🧩 **可编程场景**：四个 RT 场景阶段支持 GLSL 或预编译 SPIR-V，raygen 支持独立 SDR/HDR 变体；复用 `ResourceData` 提交应用缓冲区。
- ☕ **现代 Java API**：以 Java 25 为基线，提供不可变模型、Builder、类型化异常和确定性生命周期。
- 🖼️ **两类托管输出**：作用域映射 `FrameView` 与显式 `FrameSnapshot` 保留原始 HDR/整数格式；官方 GPU presenter 无需 CPU 回读。
- 🔗 **专家级 Vulkan 互操作**：支持 Win32 external memory lease，并可选 linear HDR RGBA16F。
- 🧠 **普通与专家用法**：普通代码用预设、场景和帧接口；专家按需控制质量、程序、命令及 GPU 交接。光照／主表面快捷工厂自动准备兼容策略，完整配置入口保留显式控制，见[用法选择](docs/ordinary-and-expert.md)。
- 🧩 **明确 workload discriminator**：`RenderWorkload` 区分 retained RT、generic raster command 和有序 RT/raster 组合，不根据缺失字段猜测意图。
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
| Java | Java 25 或更高版本 |
| GPU | 兼容目标为 NVIDIA GeForce RTX 20 系或更新架构，并通过运行时能力探测 |
| 图形 API | Vulkan 1.2 或更高版本，并通过运行时 hardware RT capability probe |
| 简单输出 | 异步原生格式 `FrameView`，可显式复制为 `FrameSnapshot` |
| GPU 显示 | Vulkan swapchain presenter，或 RTXPT 原生 D3D12/DXGI 呈现；正常出帧不回读 CPU 图像 |
| 专家输出 | Win32 Vulkan external-memory lease；可选 linear HDR RGBA16F |

> 可选 RTXPT 后端使用 D3D12，普通路径使用 Vulkan。AMD、Intel、Linux、macOS、移动平台、Metal 与软件渲染器不属于当前验收范围。
> 兼容目标不是跨硬件验收结论；本轮真实 GPU 测试范围见版本说明与验收记录。

---

## 🚀 快速开始

### 📦 添加依赖

**Maven**

```xml
<dependency>
    <groupId>top.ceroxe.rt</groupId>
    <artifactId>renderer-api</artifactId>
    <version>7.3.1</version>
</dependency>
```

**Gradle Kotlin DSL**

```kotlin
dependencies {
    implementation("top.ceroxe.rt:renderer-api:7.3.1")
}
```

上面是普通 Vulkan 路径。需要 RTXPT 全场景、透明介质或 GPU 组合入口时，再添加可选模块：

```kotlin
implementation("top.ceroxe.rt:renderer-rtxpt:7.3.1")
```

Maven 同样添加 `top.ceroxe.rt:renderer-rtxpt:7.3.1`。只用公共场景接口时可作为运行依赖；使用
`RtxptAccess`／`RtxptComposedRenderer` 时需要编译依赖。只部署 RTXPT 的依赖排除方法见[后端路由](docs/backend-routing.md#只部署-rtxpt)。

既有介质、资源增量、连续照明与透明背景能力继续保留。本地构建和 Maven Local 发布命令：
`publishAllToLocalStagingRepository` 可生成 `build/repository`，供独立消费方验证使用。
同仓库 Demo 直接依赖项目模块，不要求先发布当前版本。

### ☕ 渲染第一帧

下面的最小示例只使用托管 CPU 帧，调用方不需要了解 Vulkan：

需要直接显示 GPU 帧、RTXPT 完整场景或 独立光照／主表面接入时，先看[普通入口与专家控制](docs/ordinary-and-expert.md)。GPU lease 集成不属于最小 CPU 帧流程。

```java
import java.time.Duration;

import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.FrameSnapshot;
import top.ceroxe.rt.renderer.api.Renderer;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.SceneTransaction;

public final class Main {
    public static void main(String[] args) throws Exception {
        try (Renderer renderer = Renderer.open()) {
            long sceneRevision = renderer.apply(SceneTransaction.empty(0L))
                    .acceptedSceneRevision();

            CameraState camera = CameraState.lookAt(
                    0.0, 1.0, 5.0,
                    0.0, 1.0, 0.0
            ).aspectRatio(16.0 / 9.0).build();

            renderer.submit(RenderFrameRequest.builder(0L, 1280, 720, camera)
                    .minimumSceneRevision(sceneRevision)
                    .build());

            FrameSnapshot frame = renderer.awaitFrame(Duration.ofSeconds(5))
                    .orElseThrow(() -> new IllegalStateException("frame timed out"));
            System.out.println(frame.width() + "x" + frame.height());
        }
    }
}
```

`Renderer` 必须确定性关闭。场景 revision 与帧 sequence 必须严格递增；等待 API 始终有界，超时返回
`Optional.empty()`。完整资产、材质、实例与灯光流程见 [Java 开发指南](docs/Java.md)；四阶段 shader、应用缓冲区与原生格式帧的使用见 [可编程场景指南](docs/Programmable-Scenes.md)。

### 🖥️ 直接显示 GPU 帧

桌面应用可关闭 CPU readback，并使用 renderer 绑定的官方 presenter。内置路径复用同一 Vulkan device 与受控 queue；
只有真正的外部 consumer 才进入 Win32 external-memory 协议。

```java
try (Renderer renderer = Renderer.open(RendererConfig.presentation());
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

### RTXPT 托管场景

> **默认推荐 RTXPT 单后端**完成完整 3D 与最终呈现，显式使用 `Renderer.open("rtxpt", RendererConfig.pathTracedPresentation())`。
> **RTXPT＋Vulkan 混合模式不推荐作为默认方案**：它增加整帧复制、跨 API 同步和显存成本，仅供确实需要 Vulkan 独立命令／颜色叠层的专家使用。当前以功能正确、稳定出帧为维护目标，不做混合模式性能优化。

使用 `Renderer.open("rtxpt", RendererConfig.pathTracedPresentation())`，提交完整场景和首帧后，
通过 `RtxptAccess.renderFrame()` 驱动原生窗口。必须使用 Vulkan 独立命令或颜色叠层时，才考虑专家组合入口
`RtxptComposedRenderer.open()`，其额外成本及限制见[组合指南](docs/d3d12-vulkan-composition.md)。可直接照着运行的场景与生命周期示例见 [Java 教程](docs/Java.md#rtxpt-透明介质与原生窗口)。

RTXPT 当前输出 GPU 原生 SDR 画面，不提供普通 `pollFrame()`／`awaitFrame()` CPU 帧，也不接收任意
Vulkan shader 或通用命令。组合提供独立 GPU 工作和颜色叠层，不能让两套渲染器拆分同一场景的反射／折射。

---

## ⚙️ RTX 能力与运行状态

`Renderer.open()` 是 CPU-readable 简单模式：它保留 balanced temporal path，
并以 `PREFERRED` 策略协商重建、SER 与 acceleration-structure memory optimization；NRD 通过配置显式启用。能力不可用时按各自明确
fallback 保留基础渲染。`RendererConfig.presentation()` 在同一策略上关闭 CPU readback，并在受支持时
请求普通 FG 2x 与低延迟 pacing。MFG 永不自动开启。安装 vendor runtime 或匹配 GPU 名称本身不能产生 `ACTIVE`。

`RendererConfig.pathTracedPresentation()` 用于 RTXPT，默认关闭 FG；三个交互 Demo 在此之上明确请求
2× FG。库默认值与 Demo 默认值分别说明，不能用开关状态代替实际生成帧证据。

三个推荐工厂均支持 `RendererPerformanceTier.LOW / MEDIUM / HIGH`，无参统一中档：1 SPP、RTXPT 12／2 深度、4 个精确界面预算、Performance 重建、2 在途帧与 4／1 原生 worker／compiler。GPU、CPU、缓存和原始资产限额均可经公共 API 调整；更弱设备从 LOW 开始，显式配置保留。具体范围、实际回读和六档预编译 shader 见[性能档位与资源预算](docs/performance-budgets.md)。

专家模式仍使用同一个 `RendererConfig.builder()`。Option value 是能力协商的唯一输入：

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

`RendererGpuDevice.hardwareCapabilities()` 只描述完整物理设备 probe 得到的厂商中立事实，
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
renderer-rtxpt/                  可选 RTXPT D3D12 托管后端、薄 JNI 与 GPU 组合入口
demos/hex-ball/                  交互 Demo 与有界 GPU 验收入口
gradle/published-consumer-smoke/ 独立 Maven 消费方编译门禁
docs/                            指南、API 参考、互操作与支持矩阵
```

模块边界是发布契约的一部分：场景代码依赖 `renderer-api`；需要 RTXPT 专家／组合入口时增加
`renderer-rtxpt`，不直接构造内部后端实现。

---

## 🛠️ 构建与验证

项目包含 Gradle Wrapper，并默认使用 JDK 25 toolchain 编译与运行测试；所有 `JavaCompile` 任务强制
`--release 25`，发布字节码与 API 以 Java 25 为基线。IDEA 的 Gradle JVM 指向本机 JDK 25 后即可直接同步，
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

### 🎮 打开三个 Demo

先在仓库根目录构建两个可执行 JAR；已有 JAR 可跳过构建。运行需要 Java 25 和受支持的 RTX GPU，
可执行包自带 native runtime，源码重建所需 SDK 见下文。

```powershell
.\gradlew.bat :demos:hex-ball:shadowJar :demos:hex-ball:mediumShadowJar

$demoVersion = (Get-Content .\gradle.properties -Encoding UTF8 | Select-String '^project_version=(.+)$').Matches.Groups[1].Value

# 1. 原 Vulkan 弹球
java -jar ".\demos\hex-ball\build\libs\RTRendererAPI-HexBallDemo-$demoVersion.jar" --windowed

# 2. RTXPT 新路径弹球
java -jar ".\demos\hex-ball\build\libs\RTRendererAPI-HexBallDemo-$demoVersion.jar" --rtxpt --windowed

# 3. 纯 RTXPT 介质（默认单后端）：六个场景，可在 HUD 下拉框或数字键 1–6 切换
java -jar ".\demos\hex-ball\build\libs\RTRendererAPI-MediumDemo-$demoVersion.jar"
```

三个入口默认窗口化、1 SPP、请求 2× FG。不带时限会持续运行，手动关闭窗口退出。
**HexBall JAR 无参数会打开 API 工作台**，直接看球请保留 `--windowed`。
RTXPT 新弹球默认使用中档 **DLSS SR Performance、1 SPP、2× FG、4 个精确光学界面预算**，通过公共质量 API 请求稳定的 `SCENE_ANALYTIC` 光照输入，不额外开启 RR、NRD、SHaRC 或 ReSTIR。玻璃、不透明 20% 正面反射率球与原金属球可在 HUD 下拉框切换。材质和工作负载不同，默认 FPS 不作为两后端的等条件快慢对照。单独使用 SR 不等于路径追踪降噪。

RTXPT 弹球专用入口无需任何应用参数，窗口、FG 和中档预算由入口默认配置：

```powershell
java -cp ".\demos\hex-ball\build\libs\RTRendererAPI-HexBallDemo-$demoVersion.jar" demo.RtxptHexBallDemo
```

`java -jar <HexBallDemo JAR>` 无参数仍打开 API 工作台；两种入口含义不同。

| 常用设置 | 两个弹球入口 | 介质入口 |
| --- | --- | --- |
| 指定 1080p 输出 | `--width=1920 --height=1080` | `--width=1920 --height=1080` |
| 运行 30 秒后退出 | `--duration-seconds=30` | `--seconds=30` |
| 场景切换 | 固定弹球场景 | `--scene=mixed\|glass\|nested\|absorption\|rough\|ior`，或窗口内选择 |

关闭 FG：在 `-jar` **之前**加 `-Ddemo.disable-fg=true`。请求 3×／4× MFG：加
`-Ddemo.fg-multiplier=3`／`4`；是否实际生效看 HUD 与 `frameGenerationEvidence()`。

RTXPT 窗口标题保留应用名称，API 的 `setWindowTitle` 不会再被上游逐帧改写成临时场景路径或调试信息。原生／生成／总 FPS 以 HUD 为准。`GPU RENDER TIME` 只显示 GPU 渲染计时：未开启时显示 `NOT MEASURED`，启用但尚无样本时显示 `WAITING FOR SAMPLES`；它不包含完整 FG／呈现成本，也不再用倒数冒充帧率上限。
Gradle 启动、完整参数、FG 验收及工作台用法见 [Demo 说明](demos/hex-ball/README.md)。

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
  -PjdkHome='<jdk-25-root>' `
  --no-daemon --console=plain
```

这些路径只参与本机构建，不会写入发布物或仓库配置。

---

## 📚 文档

- [用户工具](tools/README.md)：仅供源码构建使用的三个 PowerShell 工具、路径规则与内存上限。
- [完整文档清单](docs/documentation-inventory.md)：全部源码文档、许可证、来源清单、质量证据与生成 Javadoc 的分类索引。
- [Java 开发指南](docs/Java.md)：完整场景、配置、背压、诊断与各 RTX 能力示例。
- [三个 Demo](demos/hex-ball/README.md)：两个弹球入口、介质六场景、启动参数和默认 FG。
- [后端选择](docs/backend-routing.md)：固定整场景路由与只部署 RTXPT。
- [RTXPT 与 Vulkan 组合](docs/d3d12-vulkan-composition.md)：GPU 叠层、最终呈现、热切换和生命周期边界。
- [介质演示](docs/medium-gallery.md)：当前支持的光学场景及已删除能力。
- [通用命令与硬件光追](docs/Generic-Commands-and-Ray-Tracing.md)：资源 generation、通用 command transaction、BLAS/TLAS、SPIR-V、SBT、trace、完成证据与回收。
- [Procedural/AABB 光追](docs/Procedural-Geometry.md)：4.1.0 新增的包围盒布局、intersection/any-hit、BUILD/UPDATE 与生命周期。
- [整数纹理](docs/Integer-Textures.md)：4.2.0 的 18 种整数格式、类型化清除、shader 图像类型校验与 3D 上传修复。
- [质量验证与性能报告](docs/quality/verification-report.md#4-1-0-procedural-validation)：历史程序化几何验证、当前介质范围与性能证据索引。
- [Java API 参考](docs/Java-API-Reference.md)：公共类型与稳定契约。
- [Vulkan 专家互操作](docs/Vulkan-Interop.md)：external memory、semaphore 与 queue ownership。
- [兼容性与版本策略](docs/COMPATIBILITY.md)：SemVer、公共 API/SPI 边界、弃用与发布事实。

---

## 🤔 常见问题（FAQ）

**Q：应用需要同时依赖 `renderer-core` 或 `renderer-nvidia` 吗？**

A：普通 Vulkan 路径不需要。只声明 api 坐标即可，运行时实现与 native 依赖会传递解析。
RTXPT 是可选模块，需要另外添加 `renderer-rtxpt`；组合模式仍需保留旧 Vulkan 运行依赖。

**Q：为什么请求了 DLSS、NRD 或 FG，状态却不是 `ACTIVE`？**

A：请求成功只代表能力进入协商。只有真实 dispatch、evaluate 或 present 完成后才会发布 `ACTIVE`；请读取结构化
capability 与 diagnostics，而不是根据 GPU 型号或日志猜测。

**Q：`presentLatestFrame()` 暂时返回空，是否表示渲染失败？**

A：不一定。它是非阻塞轮询，空值通常表示当前没有可呈现的新帧。应用应通过事件循环稍后重试，并同时检查
`renderer.health()` 与 diagnostics。

**Q：AMD、Intel 或 Linux 能运行吗？**

A：当前未将它们列为受支持目标。当前兼容范围只包含 Windows x64 与通过运行时 capability gate 的 NVIDIA RTX GPU；具体实机证据以对应提交的验收结果为准。

---

## 🔐 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源发布。
