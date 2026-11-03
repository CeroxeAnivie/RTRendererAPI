# 三个交互 Demo

本模块提供原 Vulkan 弹球、RTXPT 新路径弹球和 RTXPT 介质演示。三个入口默认窗口化、1 SPP，明确请求 **2× FG**；是否真正生成并呈现看 HUD 的原生／生成／总 FPS 和运行证据。可执行 JAR 自带运行时，需要 Java 25、Windows x64 和通过能力检查的 NVIDIA RTX；运行无需安装 SDK，源码重建需要仓库要求的原生工具链。

## 第一步：生成可执行包

在仓库根目录执行；已有对应 JAR 时可以跳过：

```powershell
.\gradlew.bat :demos:hex-ball:shadowJar :demos:hex-ball:mediumShadowJar
```

输出在 `demos/hex-ball/build/libs/`。两个弹球共用 HexBall JAR，介质使用独立 Medium JAR。启动器会为子 JVM 启用 native access、UTF-8 和 `-Xms32m -Xmx512m`；显存及原生内存不包含在 Java 堆上限中。

## 第二步：打开需要的 Demo

以下命令均在仓库根目录执行，单独运行需要的那一条即可。

```powershell
# 原 Vulkan 弹球
java -jar .\demos\hex-ball\build\libs\RTRendererAPI-HexBallDemo-7.0.4.jar --windowed

# RTXPT 新路径弹球
java -jar .\demos\hex-ball\build\libs\RTRendererAPI-HexBallDemo-7.0.4.jar --rtxpt --windowed

# RTXPT 介质，默认 mixed 场景
java -jar .\demos\hex-ball\build\libs\RTRendererAPI-MediumDemo-7.0.4.jar
```

介质入口默认直接使用 `Renderer.open("rtxpt", config)`，完整 3D、HUD 和最终呈现均由 RTXPT 承担，不经过组合宿主，也不创建 Vulkan 叠层。窗口标注 `RTXPT ONLY`；`mixed` 仅表示吸收、玻璃等光学内容的综合场景，不表示混合后端。RTXPT＋Vulkan 混合演示使用下方独立入口显式启动。

**HexBall JAR 不带参数会打开 API 工作台。** 看原弹球请带 `--windowed`，看新弹球可带 `--rtxpt`，或使用下方专用类入口。未指定时限持续运行，关闭渲染窗口正常退出。RTXPT 新弹球默认采用中档 **DLSS SR Performance、1 SPP、2× FG、4 个精确光学界面预算**，通过公共 API 使用稳定的 `SCENE_ANALYTIC` 光照输入，不自动增加 RR、NRD、SHaRC 或 ReSTIR。新弹球复用球场几何、物理与灯光强度，采用上游 -1.5 EV 曝光补偿；HUD 可切玻璃、不透明 20% 正面反射率球和原金属球。原 Vulkan 弹球使用金属材质，默认工作负载不同。历史修复过程见[弹球修复报告](../../docs/quality/hexball-noise-fix-20261102.md)，当前配置以本页和[预算指南](../../docs/performance-budgets.md)为准。

专用 RTXPT 弹球入口**不传任何应用参数**，默认即窗口化、1 SPP、2× FG 和中档：

```powershell
java -cp .\demos\hex-ball\build\libs\RTRendererAPI-HexBallDemo-7.0.4.jar demo.RtxptHexBallDemo
```

三个 Demo 未指定的 API 字段都继承 `MEDIUM`，显式字段只覆盖该项。例如追加 `--api.pathTracing.exactOpticalInterfaces=2` 或 `--api.resources.workerThreads=2` 不重置其他中档值。普通消费者可用 `RendererConfig.pathTracedPresentation(RendererPerformanceTier.LOW)` 选低档，再经 `copyBuilder()` 细调。六界面预算 1～6 对应随 JAR 预编译的已捕获上游排列，未捕获的新组合保留随包 DXC fallback；范围及重建条件见[性能档位与资源预算](../../docs/performance-budgets.md)。

也可以通过 Gradle 直接启动：

```powershell
.\gradlew.bat :demos:hex-ball:run --args="--windowed"
.\gradlew.bat :demos:hex-ball:rtxptHexBallDemo --args="--windowed"
.\gradlew.bat :demos:hex-ball:rtxptMediumDemo '-PrtxptArgs=--scene=nested'
```

## 输出尺寸和运行时限

| 参数 | 原 Vulkan 弹球 | RTXPT 弹球 | RTXPT 介质 |
| --- | --- | --- | --- |
| 输出分辨率 | `--width=1920 --height=1080`，必须一起传 | 同左 | `--width=1920 --height=1080`，默认 1920×1080 |
| 默认尺寸 | 显示器尺寸，窗口模式 | 按显示器缩小，最大 1920×1080 | 1920×1080，窗口模式 |
| SPP | `--spp=1\|2\|4\|8`，默认 1 | 仅 `--spp=1` | 固定 1，不接收 `--spp` |
| 30 秒后退出 | `--duration-seconds=30` | `--duration-seconds=30` | `--seconds=30` |
| 按基础帧数退出 | `--frames=300`，与时限互斥 | 同左 | 不接收 `--frames` |
| 一次显式捕获 | 使用 presenter 的诊断接口 | `--capture=画面.png` | `--capture=画面.png` |

例如打开新弹球 30 秒：

```powershell
java -jar .\demos\hex-ball\build\libs\RTRendererAPI-HexBallDemo-7.0.4.jar `
  --rtxpt --windowed --width=1920 --height=1080 --spp=1 --duration-seconds=30
```

新弹球的 `--original-balls` 恢复原球材质，`--original-settings` 使用旧 Demo 的配置作为对照输入；这些是专项对照参数。两个后端的采样、材质和后期实现不同，性能比较还需核对真实内部尺寸、重建模式、SPP 和实际出帧。

## RTXPT 弹球材质切换

窗口下拉框可随时选择玻璃球、低负载不透明球或原金属球，无需重启。低负载球保留相同几何、物理、灯光和全局画质，关闭球的透射、吸收与介质边界跟踪；切换回玻璃时恢复这些属性。切换只更新球材质及必要的原生场景结构，不重置球的位置或运动。

不透明球的正面反射率为 20%（Fresnel F0，IOR 约 2.618），斜视反射率随角度增加，并非所有角度固定反射 20%。可用 `--opaque-balls` 指定启动材质；不带材质参数仍默认玻璃，渲染预算沿用统一中配。

## 介质的六个场景

窗口内 HUD 的 `Scene` 下拉框、数字键和控制台使用同一组场景：

| 按键 | `--scene` | 展示内容 |
| --- | --- | --- |
| `1` | `mixed`（默认） | 吸收、嵌套介电、粗糙透射、金属、表面发光 |
| `2` | `glass` | 有色吸收玻璃、反射和折射 |
| `3` | `nested` | 嵌套介电材质与优先级 |
| `4` | `absorption` | 相同吸收系数，不同厚度 |
| `5` | `rough` | 不同粗糙度的透射 |
| `6` | `ior` | 不同折射率 |
| `Esc` | 退出 | 关闭原生窗口和会话 |

按键需要渲染窗口有焦点；控制台也可输入场景名或 `quit`。`--native=true` 关闭重建、使用原生内部分辨率。默认配置请求 DLSS-RR 和 SHaRC，实际采用的设置显示在 HUD 中。截图是显式单次读回，正常出帧不截图。更多说明见[介质演示](../../docs/medium-gallery.md)。

旧 `--media`／`--medium-gallery`／`--medium-certification` 标志仍转向现在这一个介质演示，不再打开 CUDA 实验室。旧散射、密度、HG、体积发光、相机初始介质、不可见边界及其调节项已删除，没有对应菜单或占位场景。

## FG、MFG 与真实运行证据

系统属性必须放在 `-jar` 之前：

```powershell
# 原生出帧对照：明确关闭 FG
java -Ddemo.disable-fg=true -jar .\demos\hex-ball\build\libs\RTRendererAPI-HexBallDemo-7.0.4.jar --rtxpt --windowed

# 请求 3× MFG；4× 使用 -Ddemo.fg-multiplier=4
java -Ddemo.fg-multiplier=3 -jar .\demos\hex-ball\build\libs\RTRendererAPI-MediumDemo-7.0.4.jar
```

Gradle 启动也接受 `-Ddemo.disable-fg=true` 和 `-Ddemo.fg-multiplier=3`／`4`。这些是 Demo 策略，**库的 `RendererConfig.pathTracedPresentation()` 默认 FG 关闭**；`RendererConfig.presentation()` 默认优选 2× FG。MFG 不会由库自动开启。

请求倍率和成功交付分别看。记录 `renderer.diagnostics().frameGenerationEvidence()` 中的请求／配置倍率、真实基础帧与生成帧、request miss、state-query failure、native status，再看技术是否达到 `ACTIVE`。2×／3×／4× 分别应有每基础帧 1／2／3 张实际生成帧；仅开关为真或窗口不卡不能证明通过。Windows 后台窗口可能停用生成帧，验收时需保持渲染窗口在前台。

原生基准 `:demos:hex-ball:rtxptMediumBenchmark` 明确关闭 FG，和交互 Demo 的总输出 FPS 分开报告。诊断、截图、冷启动 shader／PSO 准备不计入稳态吞吐。历史实测只代表记录中的制品与配置，见[性能记录](../../docs/quality/hexball-rtxpt-performance-20261101.md)。

## API 工作台和专家学习入口

HexBall JAR 无参数或带 `--controls` 打开 API 工作台。普通使用先运行上述三个窗口，再按[Java 教程](../../docs/Java.md)学习场景提交、后端选择、配置和关闭。

工作台可以独立编辑 renderer 与 frame 策略，非法组合显示实际 API 校验错误。`--api.<policy>.<field>=value`／`--frame.<policy>.<field>=value` 可复现覆盖项，向量用逗号分隔，例如 `--api.frameGeneration.mode=MULTI_FRAME_GENERATION --api.frameGeneration.multiplier=THREE_X`。这些覆盖项仍须符合所选后端的输入合同，不会把 Vulkan 专属属性翻译成 RTXPT 能力。

`--programmable` 打开 Vulkan 可编程场景工作台，支持四个场景阶段的 GLSL／SPIR-V、HDR 预览、应用时间轴和只读应用数据。Gradle 的 `programmableSceneDemo` 固定带 `--verify`，执行有界原生 HDR 校验，和交互工作台不同。通用资源、计算、图形与 BLAS/TLAS 命令请按[通用命令教程](../../docs/Generic-Commands-and-Ray-Tracing.md)使用；RTXPT 原生后端不执行任意 Vulkan shader。

需要 RTXPT 完整 3D 与 Vulkan 独立工作／UI 协作时，阅读[组合指南](../../docs/d3d12-vulkan-composition.md)。原生 RTXPT 最终呈现保留其请求的 RR＋FG/MFG；同窗热切换完整 3D 后端的 Vulkan 最终呈现是另一个显式入口，能力约束不同。
## RTXPT + Vulkan 混合场景验收

> **默认推荐 RTXPT 单后端**完成完整 3D 与最终呈现，显式使用 `Renderer.open("rtxpt", RendererConfig.pathTracedPresentation())`。
> **RTXPT＋Vulkan 混合模式不推荐作为默认方案**：它增加整帧复制、跨 API 同步和显存成本，仅供确实需要 Vulkan 独立命令／颜色叠层的专家使用。当前以功能正确、稳定出帧为维护目标，不做混合模式性能优化。

运行 `./gradlew :demos:hex-ball:rtxptFusionDemo`。默认 1080p、1 SPP、2× FG，窗口由用户手动关闭。
RTXPT 渲染透明介质与完整 3D；Vulkan 实时绘制跟随场景物体的标记、连线和加法光圈，通过 GPU 合成回 RTXPT 最终呈现。
鼠标左键拖动视角，WASD 移动；默认由 RTXPT 原生控制相机。数字键 1–6 切换光学场景，V 切换混合／仅 RTXPT／隔离 Vulkan，P 开关自动摆动。两后端共享相机与位置数据，叠层不参与 RTXPT 的三维遮挡、反射、折射或投影。
