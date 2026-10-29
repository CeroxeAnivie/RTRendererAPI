# 7.0.0 介质展厅

> 展厅中的部分介质组合仍处于实现和验收中。实际支持范围见 [7.0.0](7.0.0.md) 与 [候选制品验收](quality/verification-report.md#7-0-0-package-validation-20261029)。

这个演示在一个程序化场景中展示介质 API。默认原生 **2560×1440、1 spp、请求 2× FG**，不启用超分辨率；FG 允许在硬件或运行时不支持时呈现原生帧。是否实际生成帧以 `FrameGenerationEvidence` 为准。

以下启动、截图和 native 测试命令均会执行 GPU 工作。编译提交时尚未运行这些入口；GPU 运行、图像和性能结果在后续验收中记录。源码、CPU fixture 测试和打包检查与运行时验收分开记录。

## 场景

相机从高处看向棋盘地面和暗色背景墙。初始视图从左到右：

| 区域 | 内容 | 观察点 |
| --- | --- | --- |
| 左侧 | 外层玻璃球与优先级更高的内部水球 | 两层折射、界面反射、RGB 吸收、退出后介质恢复 |
| 中间 | 带粗糙界面的琥珀色介质方体 | 粗糙折射与路径长度相关的颜色衰减 |
| 右侧 | 32×32×32 密度图集中的蓝色雾团与聚光灯 | 异质密度、HG 前向散射、额外体积碰撞、体积阴影 |
| 后方 | 带异质密度和反向各向异性的橙色发光体积 | 消光、发光积分与散射耦合 |
| 前方 | 金属球及棋盘接收面 | 反射/折射后的表面光子焦散、接收面纹理 |

默认额外体积散射次数为 2，光子数为 131072，表面查询半径为 0.16，发射球为中心 `(0,2,0)`、半径 12。纯体积边界默认使用逐帧图元；玻璃和水体使用持久实例。封闭网格使用共享索引，球体焊接极点和接缝，避免以重叠顶点伪装流形。

实现入口：[`MediumGalleryDemo`](../demos/hex-ball/src/main/java/demo/MediumGalleryDemo.java)、[`MediumGalleryScene`](../demos/hex-ball/src/main/java/demo/MediumGalleryScene.java)。该 demo 使用公开的 `Renderer`、场景/帧类型和 managed Vulkan presenter，不访问 core 内部状态。

## 启动入口

可从项目根目录运行：

```powershell
.\gradlew.bat --offline :demos:hex-ball:mediumGalleryDemo
```

或使用已打包的演示：

```powershell
java --enable-native-access=ALL-UNNAMED -jar demos/hex-ball/build/libs/RTRendererAPI-HexBallDemo-7.0.0.jar --medium-gallery
```

窗口初始为 2560×1440，之后按实际 framebuffer 尺寸提交。需要固定 2K 口径时，应保持窗口 framebuffer 为 2560×1440，并在记录中保存实际尺寸。

参数均使用 `--name=value`。例如，固定相机、关闭 FG、开启验证层并运行 120 个原生帧：

```powershell
.\gradlew.bat --offline :demos:hex-ball:mediumGalleryDemo '-PgalleryArgs=--fg=false --animate=false --validation=true --frames=120'
```

| 参数 | 默认 | 用途 |
| --- | --- | --- |
| `width` / `height` | 2560 / 1440 | 初始 framebuffer 请求尺寸 |
| `spp` | 1 | 每像素主射线样本数，1–8 |
| `fg` | true | 请求 2× FG；false 明确关闭 |
| `photons` | 131072 | 每原生帧发射光子数，最大 1048576 |
| `bounces` | 2 | 首次散射之后的额外体积碰撞预算，0–8 |
| `caustics` / `absorption` / `scattering` / `heterogeneous` / `emission` / `refraction` / `nested` | true | 分别控制对应功能，用于消融对照 |
| `transient` | true | 雾与发光体积使用逐帧边界；false 使用持久实例 |
| `inward` | false | 方体介质边界使用内向绕序与匹配方向声明 |
| `inside` | false | 相机进入右侧雾团，检查初始介质集合 |
| `animate` | true | 小幅移动相机，为运动与 FG 输入提供变化 |
| `validation` | false | 显式启用 Vulkan 验证层 |
| `frames` / `seconds` | 0 / 0 | 原生提交数量/秒数上限；零表示不设相应上限 |
| `capture` | 未设置 | 启用 CPU readback 并写出色调映射 PNG；必须同时 `fg=false` |

无 FG 截图示例：

```powershell
.\gradlew.bat --offline :demos:hex-ball:mediumGalleryDemo '-PgalleryArgs=--fg=false --animate=false --frames=64 --capture=build/reports/gallery.png'
```

截图固定相机并保留线性 HDR 检查，再将最终帧映射为 PNG。CPU readback 与串行捕获的耗时不作为正常呈现 FPS。

## 验证与计时

可以在不启动 GPU 的情况下运行场景检查：

```powershell
.\gradlew.bat --offline :demos:hex-ball:mediumGallerySceneSelfTest :renderer-core:coreCausticTransportSelfTest
```

下面的独立入口会执行 GPU 工作，当前仅编译：

```powershell
.\gradlew.bat --offline :demos:hex-ball:mediumGalleryNativeSelfTest
```

它在 160×90、32 个独立原生样本、关闭 FG/时间累积的条件下，保存全部功能和逐项消融图像，检查 HDR 有限性、功能差异、持久/逐帧边界及正反绕序的一致性，并保存 Vulkan 验证日志。阈值是该夹具的回归门槛，需要结合首次 GPU 执行结果和图像复核评估；该测试无法单独证明物理收敛或 2K 性能。

展厅分别输出场景生成、`Renderer.open`、场景接受、首个提交与首次 proxy present 的主机时间。`sceneAdmissionMs` 不包含全部异步 GPU 上传与加速结构收敛；`firstProxyPresentMs` 也不等于首次屏幕扫描完成时间。

HUD 中的 `native completed` 来自完成的 GPU timestamp 样本计数差值；`FG` 和 `observed output` 来自运行时实际呈现证据的窗口差值。GPU 整帧累计平均耗时单独展示，不取其倒数冒充显示 FPS。FG 首次尚无有效窗口、不可用或 epoch 变化时明确显示等待证据。

性能比较应继续使用 core 的 `mediumTransportBenchmark`：其原生完成批次吞吐包含队列填充、排空、主机提交和轮询，不含生成帧。完整方法与限制见 [7.0.0 设计与质量报告](quality/verification-report.md#7-0-0-medium-design)。
