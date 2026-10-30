# 7.0.0 介质验收展厅

默认打开 2560×1440、1 SPP 的原生渲染窗口。窗口显示实际完成帧的 FPS 和帧间隔，可在旁边的控制窗口切换场景。FG 默认关闭，超分辨率默认关闭。

| 场景参数 | 展示内容 |
| --- | --- |
| `pbr-direct-uniform` | PBR 与均匀单次散射 |
| `pbr-direct-density` | PBR 与密度网格单次散射 |
| `pbr-multiple-uniform` | PBR 与均匀多次散射 |
| `pbr-multiple-density` | PBR 与密度网格多次散射 |
| `boundary-optical-scatter` | 显式折射边界、玻璃与散射 |
| `absorption-emission` | RGB 吸收与自发光 |
| `glass-scatter-caustics` | 玻璃、均匀散射、表面和体积焦散 |
| `glass-density-caustics` | 玻璃、云状密度和体积焦散 |
| `boundary-density-caustics` | 折射云盒、密度、优先级和体积焦散 |

从仓库根目录启动：

```powershell
.\gradlew.bat --offline :demos:hex-ball:mediumGalleryDemo '-PgalleryArgs=--scene=boundary-density-caustics --validation=true'
```

固定相机并运行 30 秒：

```powershell
.\gradlew.bat --offline :demos:hex-ball:mediumGalleryDemo '-PgalleryArgs=--scene=glass-density-caustics --width=1280 --height=720 --animate=false --seconds=30'
```

参数采用 `--名称=值`。`--scene` 选场景，`--width/--height` 选初始尺寸，`--spp` 选主样本数，`--photons` 选光子数量，`--bounces` 选额外散射次数。焦散预设可用 `--caustics=false` 做关闭对照。`--capture=绝对路径.png --fg=false` 使用 HDR 回读并保存经过显示变换的 PNG。

FPS 来自每秒实际完成帧数，包含介质生产链路。窗口里的“GPU输出阶段”只表示末端着色和输出命令的 GPU 时间；相机与光子在 CUDA/Vulkan 间的传输不在这个计时区间里。

CPU 场景合同：`:demos:hex-ball:mediumGallerySceneSelfTest`。GPU 图像验收：`:demos:hex-ball:mediumGalleryNativeSelfTest`，逐一运行上述预设及三个焦散关闭对照，检查 HDR、光照和图像差异，输出到 `demos/hex-ball/build/reports/medium-gallery/`。测试结果记录在[总验收清单](quality/verification-report.md#api-closure-20261030)。
