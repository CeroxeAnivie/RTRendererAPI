# NVIDIA 运行时与模型核对

2026-11-02 通过官方 GitHub release API 核对：最新发布为 [Streamline 2.14.16](https://github.com/NVIDIA-RTX/Streamline/releases/tag/v2.14.16)，发布时间 2026-10-16。项目已经使用此版本。

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

NIS 0.15 候选 v124 已完成 364 帧及退出生命周期检查，官方插件实际运行且 `slShutdown=0`。它把局部阴影边缘宽度从 2.407 降到 2.328 px，但静态帧间波动从 0.760 升到 1.418/255，静态参照误差从 5.560 升到 6.627/255；1:1 截图也显示噪声被放大。画质回归不通过，不进入默认配置，也不将更锐的噪声算作细节修复。证据：`data/hexball-native-20261101/sr-balanced-nis015-analysis-v124.json` 及同名 PNG。后续保留已接受的 K 模型画面，独立排查上游后处理是否造成额外的微糊。
