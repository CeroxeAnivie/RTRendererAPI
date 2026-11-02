# LuxCore 托管介质执行约定

> 清理说明（2026-11-02）：历史 JSON／CSV／JSONL 等实验原始数据及一次性诊断脚本已清理；本文结论、参数和截图保留。原始文件见[清理前版本](https://github.com/CeroxeAnivie/RTRendererAPI/tree/eb3bc21c8b7559dd847c575ca3f392a2f896d86f/docs/quality)，当前工具与验证入口见[工具说明](../../tools/README.md)。

迁移前完整备份：main `68abf71`。旧实现、测试和性能证据可从 Git 历史恢复。

## 执行边界

- 介质场景的全部几何、表面材质、灯光和介质一起交给完整 LuxCore 引擎。
- Java 仅维护公开场景契约、资产增量转换、JNI 调用和结果发布。
- JNI 只调用上游公开 Scene / RenderConfig / RenderSession / Film API。
- 积分器、采样、嵌套体积、求交、焦散、GPU 内存及调度均由 LuxCore 拥有。
- 不编写介质 CUDA 核函数，不维护 Vulkan/CUDA 射线往返链，不接管上游内部队列。
- 使用固定版本的官方二进制及匹配头文件，不修改引擎内部，不在 Java 里补积分器。
- 原常规 Vulkan 后端和显式命令能力继续保留。其介质入口明确拒绝并提示选择 LuxCore。
- 后端显式选择；不按猜测切换，不把两个后端的独立画面叠成正确混合输运。

## 版本和来源

LuxCore 2.11.2，commit `b872186d2fe995a762f73708c606847eed55843e`，Apache-2.0。
官方 SDK：https://github.com/LuxCoreRender/LuxCore/releases/tag/samples-v2.11.2
源码仅用于匹配公开头文件及查阅官方用法；不作为本项目维护的积分器源码。

## 性能与语义

持久化 Scene 和 RenderSession；只提交变更。相同场景不逐帧重新上传网格或重建会话。
渲染结果使用 LuxCore 原生 Film，明确记录其真实采样量。原生渐进 Film 的显示刷新率
不能冒充旧基准中“无累积、恰好 1 SPP”的完成帧率。功能和采样语义核实后再报告性能。
桥接计时仅记录场景转换、JNI 场景提交、会话启动、取帧和格式转换等自有阶段；
引擎内部性能问题交给上游，不重新实现其调度器。

## 迁移状态

正在接入与验证；本文件不声明功能已全部完成。旧 54 个场景及 micro-all 场景继续保留，
用于新的场景映射和混合功能验证。任何无法对应的语义必须明确报告，不静默丢弃。

## 2026-11-01 官方独立进程对照

状态纠正：`light` 目前只是几何较少的全覆盖候选，**没有通过“本来就应该很快”的
轻量场景验收**。官方入口同样慢，不能替代这项验收，也不能证明 6～7 FPS 合理。

通过上游 `RenderConfig::Export` 导出轻量混合场景的完整配置、网格和纹理，然后退出
Java。独立运行 SDK 内未经修改的 `luxcoreconsole.exe`；该进程不加载 Java、JNI 桥或
本项目渲染模块。导出入口只在计时外使用，不增加正常提交/渲染路径开销。

两条路径均为 1920×1080、RTPATHOCL、TILEPATHSAMPLER、AA=1、原尺寸、无过滤、
无降噪、无帧生成，756 个三角形；实际设备为 RTX 5080 Laptop GPU CUDA/OptiX。
FPS 以真实完成采样量除以 2,073,600 像素换算，表示每帧 1 SPP 的渲染量；不把
Film 显示刷新算作独立渲染帧。

| 路径 | FPS | 每帧 1 SPP 渲染量耗时 |
|---|---:|---:|
| 纯官方 LuxCore console，最后报告的累计吞吐 | 6.68 | 149.61 ms |
| 本项目薄桥接，三轮采样增量/总时间 | 6.55 | 152.56 ms |

桥接三轮分别为 6.00 / 6.33 / 7.33 FPS；官方 console 最后十次累计吞吐报告约为
6.65～6.68 FPS。两者起测 GPU 占用分别为 43% / 38%，存在其他任务；本次能看到
两条路径都处于 6～7 FPS 的档位，不能据此计算隔离的 JNI 开销或空闲 GPU 提升率。
绕过项目并未出现数量级加速；当前应继续检查上游渲染配置和实际工作量。

官方 console 会固定设置 `screen.refresh.interval=2500`，该入口差异已记入证据。
RTPATHOCL 在达到 `batch.halttime=12` 后仍持续渲染，本次收集到第 58 秒后停止该
自有进程。导出的完整场景、配置、官方日志、桥接原始结果和资产 SHA-256 均保存在
[`data/luxcore-official-control-20261101`](https://github.com/CeroxeAnivie/RTRendererAPI/blob/eb3bc21c8b7559dd847c575ca3f392a2f896d86f/docs/quality/data/luxcore-official-control-20261101/comparison.json)。

官方入口复现（在仓库根目录执行；RTPATHOCL 需要手动或外部限时结束）：

```powershell
& build/luxcore-full/LuxCore/bin/luxcoreconsole.exe -s `
  -D batch.halttime 12 -D batch.haltspp 0 `
  docs/quality/data/luxcore-official-control-20261101/scene/render.cfg
```

随后用 `tools/Measure-LuxCoreLightControls.ps1` 追加独立原生对照，保留相同分辨率、
引擎和采样配置；最小场景只有一个漫反射立方体和环境光，完全没有介质。

| 对照 | FPS | 每帧 1 SPP 渲染量耗时 | 起测 GPU 占用 |
|---|---:|---:|---:|
| 最小原生场景：12 三角形，无介质 | 11.20 | 89.26 ms | 36% |
| 全覆盖候选移除相机周围介质盒 | 6.91 | 144.80 ms | 33% |
| 原全覆盖候选 | 6.14 | 162.89 ms | 66% |

不同外部负载下不计算跨场景降幅，也不据此归因各个功能的精确成本。最小场景同样
慢，说明必须先检查原生引擎模式/配置的基础成本，不能直接把低帧率归咎于全覆盖内容。
上游 `src/slg/engines/tilepathocl/tilepathoclthread.cpp` 的 `RenderTileWork` 固定执行
`2 * pathDepth - 1` 轮完整任务池求交/推进；RTPATHOCL 默认深度为 5，即 9 轮。
这是已确认的执行机制，尚不是对全部耗时的归因证明。
结果和最小场景保存在 [`data/luxcore-light-controls-20261101`](https://github.com/CeroxeAnivie/RTRendererAPI/blob/eb3bc21c8b7559dd847c575ca3f392a2f896d86f/docs/quality/data/luxcore-light-controls-20261101/result.json)。
