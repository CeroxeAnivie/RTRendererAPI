# 性能档位与资源预算

默认配置面向普通消费机。`MEDIUM` 是统一起点，开发目标约为调试机 RTX 5080 Laptop 的 65% 性能档位；这不是按显卡型号检测，也不表示 FPS 可以线性换算。更弱的设备从 `LOW` 开始，更强的设备可选 `HIGH`，再单独调节开销。项目不会因为运行机器较强就暗中提高默认预算。

## 从预设开始

```java
var low = RendererConfig.pathTracedPresentation(RendererPerformanceTier.LOW);
var medium = RendererConfig.pathTracedPresentation(); // 等同 MEDIUM
var high = RendererConfig.pathTracedPresentation(RendererPerformanceTier.HIGH);

// 普通 Vulkan 与 CPU 可读帧同样有三档入口。
var vulkan = RendererConfig.presentation(RendererPerformanceTier.LOW);
var readable = RendererConfig.recommended(RendererPerformanceTier.MEDIUM);
```

`recommended()`、`presentation()`、`pathTracedPresentation()` 的无参形式均使用中档。`builder()` 也采用中档工作量／资源预算，但仍保持可选重建、降噪、FG 等技术默认关闭。预设不会重写随后通过 `copyBuilder()` 显式指定的字段。库的 `pathTracedPresentation()` 默认关闭 FG，三个交互 Demo 明确请求 2× FG。

| 项目 | LOW | MEDIUM（默认） | HIGH |
| --- | ---: | ---: | ---: |
| 相机 SPP | 1 | 1 | 1 |
| RTXPT 总／漫反射深度 | 8／1 | 12／2 | 20／2 |
| 场景光照精确介电界面预算 | 2 | 4 | 6 |
| DLSS 重建质量 | Performance | Performance | Balanced |
| RTXPT frame worker／shader compiler 线程 | 2／1 | 4／1 | 8／2 |
| 原始几何 payload 上限 | 64 MiB | 256 MiB | 1024 MiB |
| 原始纹理 payload 上限 | 64 MiB | 256 MiB | 1024 MiB |
| 原始资产总 payload 上限 | 128 MiB | 384 MiB | 1536 MiB |
| SHaRC 条目容量 | 1,048,576 | 2,097,152 | 4,194,304 |
| SHaRC 训练 downscale | 5 | 5 | 4 |
| 降噪引导层数／最大深度 | 2／5 | 3／9 | 3／9 |
| Bloom | 关闭 | 开启 | 开启 |
| Vulkan 表面事件数／光学样本 | 4／1 | 8／1 | 12／1 |
| 在途帧 | 2 | 2 | 2 |

这些档位控制预算，不改变后端支持范围。RTXPT 光学界面预算与 Vulkan 表面事件预算是不同算法的参数，不能当作相同画质刻度。

## RTXPT 细调与实际回读

```java
var base = RendererConfig.pathTracedPresentation(RendererPerformanceTier.LOW);
var path = base.pathTracing().withExactOpticalInterfaces(3);
var quality = path.quality()
    .withCache(path.quality().cache().withCapacity(1 << 20))
    .withDenoisingLayers(2, 5)
    .withPostProcessing(path.quality().postProcessing().withBloom(false));
var config = base.copyBuilder()
    .pathTracing(path.withQuality(quality))
    .resources(base.resources().withThreads(2, 1))
    .build();
try (var renderer = Renderer.open("rtxpt", config)) {
    renderer.apply(initialScene);
    renderer.submit(firstFrame); // 本例为 RTXPT；Vulkan 首次场景收敛应按 trySubmit 结果等待并重试。
    var host = renderer.extension(RtxptAccess.class).orElseThrow();
    System.out.println(host.runtimeStatus());
    System.out.println(host.pathQualityStatus());
    System.out.println(host.presentationStatus());
}
```

`PathTracingOptions` 包含相机 SPP、总／漫反射路径深度、SHaRC／ReSTIR 意图、`exactOpticalInterfaces` 和 `quality`。SPP 与总深度均为 1～64，漫反射深度为 0～总深度；ReSTIR 要求 1 SPP。精确界面数量为 **1～6**，使用 `withExactOpticalInterfaces(n)` 设置；它不是 `maxBounces` 的别名。只有 `SCENE_ANALYTIC` 光照输入路径使用该精确分支，后续界面保留随机估计。减少该预算可减少精确分支工作，也可能增加玻璃噪声。HUD／`runtimeStatus().activeExactOpticalInterfaces()` 在未激活时返回 0。

| `PathTracingQualityOptions` 字段 | 范围与实际作用 |
| --- | --- |
| `lighting.input` | `BACKEND_DEFAULT` 使用上游传输；`SCENE_ANALYTIC` 使用稳定的场景解析光照输入，不与 ReSTIR／SHaRC 组合 |
| `lighting.sampler` | `AUTO / UNIFORM / POWER / ADAPTIVE`；AUTO 保留场景协商，显式枚举对应上游 NEE sampler |
| `lighting.samples / candidates` | 各 1～63，完整可见性样本与较便宜的候选数量 |
| `lighting.localCandidateRatio` | 0～1，本地候选比例 |
| `lighting.adaptiveQuality` | 0～2，上游 NEE-AT 质量档，仅 adaptive sampler 使用 |
| `lighting.exactPointLightLimit` | 0～4096，启用精确点光估计的解析灯数量上限；CPU gate 与 shader 使用同一个值，0 禁止激活 |
| `lighting.directionalStrata` | 可选方向光分层，仅无独立降噪／ReSTIR／SHaRC 的 SR 路径，且场景有有效方向光；不能与 `SCENE_ANALYTIC` 同开 |
| `denoisingLayers / denoisingLayerMaxDepth` | 1～3 层、1～15 深度；执行深度还受总路径深度限制 |
| `nestedDielectricsQuality` | 0／1／2，关闭／快速／高质量的上游嵌套介电追踪 |
| `subSampling` | FULL／THREE_QUARTERS／HALF／QUARTER，内部重建网格的逐帧采样比例；与输出尺寸及相机 SPP 分开 |
| `textureLodBias` | −8～8，纹理细节与访问成本取舍 |
| `halfPrecision` | 可选上游 FP16 类型，默认关闭 |
| `postProcessing` | Bloom 开关、半径 0～128、强度 0～100，以及 tone mapping 开关；半径或强度为 0 时不执行 Bloom |

SHaRC `RadianceCache` 的容量为 65,536～16,777,216 且必须是 2 的幂。其余可调项为训练 downscale 2～5、训练总深度 1～64、漫反射深度 0～训练总深度、直接光样本 0～63、终止阈值 0.01～10、额外纹理 LOD −8～8、场景尺度 0.0001～1,000,000、累积窗口 1～255、过期窗口 **8～128**、最小粗糙度 0～1。低于八帧的过期值会被上游钳制，因此 API 直接拒绝。缓存设置只有对应技术激活时参与训练／查询；当前上游即使停用缓存技术，也可能为其管线保留容量，不能把 `radianceCache=DISABLED` 当作显存已全部释放。

默认灯光预算为 1 个完整样本、5 个候选、本地比例 0.65、adaptive quality 0、点光上限 4、方向分层关闭。默认 SHaRC 训练深度为 8／4、1 个灯光样本、终止阈值 3.5、额外 LOD 1、场景尺度 35、累积／过期窗口 10／64、最小粗糙度 0.4。三档均默认 FULL 内部采样、LOD −1、FP16 关闭、嵌套质量 1。Bloom 开启时默认半径 8、强度 0.004，tone mapping 默认开启。

上述路径／质量预算是 renderer 生命周期配置；运行期改档通过 `RendererFeatureController.plan` 明确返回 `REQUIRES_RENDERER_REBUILD`，关闭后用新配置创建。FG／MFG 等已有动态能力仍按控制器报告的边界应用。`pathQualityStatus()` 读取原生设置、实际 sampler、真实缓存条目分配、帧环和线程池大小，不同步 GPU；技术是否激活另看 `runtimeStatus()` 和通用能力快照。Bloom／tone mapping 的实际值也在 `presentationStatus()` 中。

## CPU 与内存范围

`RendererResourceOptions` 提供 worker 1～64、compiler 1～16，以及三个正数字节限额。线程数由 RTXPT 原生线程池采用；RTXPT 与 Vulkan retained-scene 都执行原始资产字节准入。几何限额统计原始网格的全部 buffer，纹理限额统计实际 RGBA8 payload。几何、纹理、合计三个上限同时成立，超额在发布新场景前明确拒绝，保留原场景，不静默删资产。纯变换更新不重复扫描全部资产。

这些限额**不是 JVM 总堆、进程私有提交或总显存上限**。BLAS/TLAS、驱动、重建模型、帧缓冲、资产导出／解码副本和上游其他分配不按原始 payload 等比占用内存。专家通用资源／命令接口由消费者显式指定资源大小；`RtxptAccess.loadNativeScene` 则绕过 Java retained-scene 准入，外部原生资产由调用者与上游管理。

`maxFramesInFlight` 默认 2：Vulkan 支持 2～16，RTXPT 支持 2～3，RTXPT 对更大值直接拒绝。core 的 `SceneCacheBudget` 属于单独的区段场景工具链，不是 `Renderer.open(...)` 的 Vulkan retained-scene 限额，也不控制 RTXPT；不能把该工具链的 384 MiB BLAS 默认值算作主后端已执行的总显存限制。

## Vulkan 表面预算

```java
var config = RendererConfig.presentation(RendererPerformanceTier.LOW).copyBuilder()
    .surfaceTransport(new SurfaceTransportOptions(4, 1))
    .build();
```

`maxEvents` 为 1～64，包含首个表面事件；`samples` 为 1～16，仅表示光学表面样本，不等于相机 SPP。该值进入现有 Vulkan frame uniform 与表面反射／透射路径，不增加另一套算法。缩短路径可能省略后续反射；不支持的完整介质仍须走 RTXPT。RTXPT 使用自己的 `PathTracingOptions`，不消费此 Vulkan 表面配置。

## 六档预编译与发行包

JAR 打包的是上游真实 DXC 输出和完整排列清单。每个已捕获的 `SCENE_ANALYTIC` 路径排列均生成 1～6 界面六档，包含 raygen、uber／材质特化，以及已捕获的动态 compute 排列；不是六份源码或六个占位文件。缓存键去掉安装绝对路径，ZIP／解包保留可复用的源码与二进制时间关系。

材质宏、SER、SR／RR、ReSTIR、SHaRC 等还会产生其他排列，因此六档不意味着任意未来组合都无需编译。**未包含的排列仍由随包的上游 DXC 编译并缓存**；新组合应先预热，再测稳态。正常消费者无需安装 SDK。包内清单给出覆盖排列、六档值、二进制 SHA-256 与源码指纹；打包前校验不允许陈旧源码或缺档。

源码构建者先原生验证并收集命令，再离线打包：

```powershell
$env:RTR_RTXPT_SHADER_MANIFEST_DIR=(Join-Path (Get-Location) 'build/rtxpt-shader-manifests')
./gradlew.bat :renderer-rtxpt:rtxptBudgetNativeSelfTest
./tools/Build-RtxptShaderBudgets.ps1
Remove-Item Env:RTR_RTXPT_SHADER_MANIFEST_DIR -ErrorAction SilentlyContinue
./gradlew.bat :renderer-rtxpt:jar :renderer-rtxpt:rtxptPackagedBudgetNativeSelfTest
```

最后一项在仓库外的新缓存目录中运行真实 JAR，显式禁止动态编译，验证已覆盖的预算排列确实从包加载。命令收集属于构建期诊断，默认不开；六档验证不替代游戏全场景的性能测试。

7.0.0 本轮已完成该异地验收：17 项原生预算／像素检查通过，包内 361 份二进制前后无变化，包含 32 组各具完整 1～6 档的光学排列。具体制品身份与修复经过见[联合验收记录](quality/rtxpt-memory-fps-20261102.md#消费级预算与组合正确性补验)。

## 三个 Demo

原 Vulkan 弹球、RTXPT 弹球、RTXPT 介质的无参数／缺字段均继承中档预算，保留窗口化、1 SPP、2× FG。RTXPT 弹球使用 SR、稳定场景光照输入，不额外开启 RR／NRD／SHaRC／ReSTIR；介质使用中档 RR＋SHaRC。消费方可以用 `--api.pathTracing.exactOpticalInterfaces=2`、`--api.pathTracing.quality.postProcessing.bloom=false`、`--api.resources.workerThreads=2` 等显式覆盖单项，其余字段继续继承中档，非法组合明确报错。

RTXPT 弹球下拉框可切玻璃、不透明 20% 正面反射率球与原金属球，物理连续运行。不透明球不追踪玻璃透射／吸收边界，是弱 GPU 可选的较低负载材质，不暗中降低全局画质。完整启动命令见 [Demo 说明](../demos/hex-ball/README.md)。混合模式仍不推荐作为默认方案，只维护正确性；预算配置不消除跨 API 复制与同步成本。
