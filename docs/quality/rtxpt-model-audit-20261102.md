# NVIDIA 运行时与模型核对

> 清理说明（2026-11-02）：历史 JSON／CSV／JSONL 等实验原始数据及一次性诊断脚本已清理；本文结论、参数和截图保留。原始文件见[清理前版本](https://github.com/CeroxeAnivie/RTRendererAPI/tree/eb3bc21c8b7559dd847c575ca3f392a2f896d86f/docs/quality)，当前工具与验证入口见[工具说明](../../tools/README.md)。

2026-11-02 通过官方 GitHub release API 核对：最新发布为 [Streamline 2.14.16](https://github.com/NVIDIA-RTX/Streamline/releases/tag/v2.14.16)，发布时间 2026-10-16。RTXPT 路径已经使用此版本；旧 Vulkan 路径的补充核对见下文，不能把前者的核对扩大成整个项目。

| 功能 | 运行时版本 | 当前官方模型推荐 |
| --- | --- | --- |
| DLSS SR | nvngx_dlss 310.9.16.0 / sl.dlss 2.14.16.0 | DLAA、Quality、Balanced：K；Performance：M；Ultra Performance：L |
| DLSS RR | nvngx_dlssd 310.9.16.0 / sl.dlss_d 2.14.16.0 | F，SDK 头文件明确标注 latest and default |
| FG / MFG | nvngx_dlssg 310.9.16.0 / sl.dlss_g 2.14.16.0 | 使用原生实现和官方自动模型策略；没有伪造 SR 的 preset 给 FG |

运行时六个文件的 SHA-256 均与最新 SDK 本地安装相同，记录为 `data/hexball-native-20261101/rtxpt-model-runtime-audit-v121.json`。本机日志加载的是 NVIDIA 签名的 2.14.16 插件；缓存目录里残留旧版本不代表程序加载了旧版本。上游默认允许官方 OTA 和已下载插件。

来源：[SR 官方集成文档](https://github.com/NVIDIA-RTX/Streamline/blob/main/docs/ProgrammingGuideDLSS.md)、[SR 模型定义](https://github.com/NVIDIA-RTX/Streamline/blob/main/include/sl_dlss.h)、[RR 模型定义](https://github.com/NVIDIA-RTX/Streamline/blob/main/include/sl_dlss_d.h)。RR 指南示例仍写 D，但同一最新版本头文件明确 F 是最新默认，故不倒退到示例的旧选择。

这次没有发现“组件版本落后”可以解释微糊。将 M 强制用于不变的 Balanced 输入进行隔离对照，阴影边缘 2.407→2.079 px、静态参照误差 5.560→4.256/255，但玻璃球原生 FPS 221.792→185.819，降低 16.22%，不满足保帧率要求，因此不设为默认。

当前采用的仍是 SR Balanced、上游默认模型、1 SPP、精确点光与 power sampling。跳过环境前缀的无效索引查找后，金属球 208.915→238.871 FPS（+14.34%）；打包 JAR 的静止、运动、停止共 12 张截图与用户认可的前一版本逐像素一致。玻璃球当前 30 秒原生平均 221.792 FPS。以上均为 2560×1440 输出、1485×835 输入、FG 关闭，初始 GPU 为 0%。

下一候选是 [NVIDIA NIS 官方仅锐化模式](https://github.com/NVIDIA-RTX/Streamline/blob/main/docs/ProgrammingGuideNIS.md)。保持 SR 输入尺寸及模型，不实现自制锐化器，不复制整帧回 CPU。它目前只是诊断候选，必须同时检查画面、噪声和 FPS，尚未启用为默认。

NIS 0.15 候选 v124 已完成 364 帧及退出生命周期检查，官方插件实际运行且 `slShutdown=0`。它把局部阴影边缘宽度从 2.407 降到 2.328 px，但静态帧间波动从 0.760 升到 1.418/255，静态参照误差从 5.560 升到 6.627/255；1:1 截图也显示噪声被放大。画质回归不通过，接入代码已撤回，不进入默认配置，也不将更锐的噪声算作细节修复。证据：`data/hexball-native-20261101/sr-balanced-nis015-analysis-v124.json` 及同名 PNG；需要重现该候选可检出证据记录的提交。

Bloom 隔离 v125 的局部边缘仅从 2.407 变为 2.379 px，静态参照误差略升，不能解释主要差异，也没有改变 Bloom 默认设置。下一项是实际调用链中已确认的曝光差异：旧 Vulkan shader 写入 1x1 的固定曝光 1，RTXPT 原来没有曝光资源且打开内部自动曝光。按照官方指南，只关闭 `useAutoExposure` 但不提供纹理仍会启用自动曝光，因此对照必须同时提供真实 R32F 纹理。此项仍是隔离候选，没有据此宣称根因已确定或微糊已修复。

补充核对发现旧 Vulkan 的打包目录仍是 Streamline 2.12.0、DLSS 310.7.0，且 `streamline_sdk.cpp` 覆盖默认 flags，清掉了 NVIDIA 的 OTA 和已下载插件策略。正在统一其 SDK 发现路径、最低编译版本与官方模型策略。旧路线升级独立于 RTXPT 微糊验收，不能计作微糊修复。

曝光隔离 v126 在动态 shader 初始化期间异常退出，没有图像数据；v127 重试完成 364 帧及生命周期验证，但原生 CRT 日志尾部未落盘，未满足候选启用的日志证据要求，不能算正式通过。其图片分析暂存用于排查。现只在关闭渲染器时刷新原生 stdout/stderr，避免丢失 SDK 退出状态，正常帧循环不增加刷新操作。

旧路径的另一个明确差异是 `gpuscene_raygen.glsl` 在无 NRD 时会把合格的静态表面历史融合到 SR 输入。默认 `TemporalRenderingOptions.balanced()` 上限为 8 帧，而 RTXPT 当前纯 SR 配置直接合并当帧路径追踪结果。`VulkanHexBallTemporalEvidence` 用相同场景和 Balanced SR 隔离这项历史；必须看实际图片变化，不能仅凭这段代码断言它就是主要根因。
