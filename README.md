# RTRendererAPI

面向 Java 的通用渲染契约与 NVIDIA RTXPT 外部适配层。Java 25，Windows x86_64，D3D12/DXR。

当前版本 **8.0.0**。此前弹球与介质已由用户人工通过，本批补齐构建期着色器交付与运行时禁编译。新目录冷缓存介质、弹球与三种材质切换已通过隐藏出图；这不代表消费方整体性能已经验收。源码上传与覆盖 Maven Local 的当前身份见[禁止运行时编译](docs/quality/precompiled-runtime.md)。

## 原生优先的两模块结构

| 模块 | 职责 |
| --- | --- |
| renderer-api | 宿主无关的场景、帧身份、资源与生命周期契约；不反向依赖具体 provider |
| renderer-rtxpt | 消费官方 RTXPT 的外部适配层；正向依赖 renderer-api |

8.0.0 已删除本项目自己的 renderer-core、renderer-nvidia、旧 Vulkan 渲染路线与旧演示，不再构建或打包。通用互操作数据契约不等于仍有一个自研 Vulkan 后端。

适配层只保留必要的输入转换、资源身份映射、图形 API 间的同步契约、输出租约和错误传播。上游已有等价实现时直接调用，删除重复实现；资源、上传、加速结构和原生渲染循环交给 RTXPT／Donut／NVRHI。必要的独立视觉扩展留在适配层，不能称为 RTXPT 原生算法。

Renderer 核心只保留生命周期、SceneTransaction 的 apply／submit 与真实扩展查询。本轮退役 188 个孤立类型：旧 Vulkan、CPU 帧读取、通用 command／resource 提交、假健康与固定假指标等不再作为公开兼容壳。RendererConfig 收敛到六类已接线原生 feature，加 geometryBytes／textureBytes／totalAssetBytes 的真实资产字节准入预算；没有 GPU 枚举、CPU 回读或自定义 shader 的假开关。预算不是显存驻留或任务管理器内存统计。

质量控制只保留原版公开设置支持并已接入 JNI 的字段；exactOpticalInterfaces、SCENE_ANALYTIC、pointLimit、directionalStrata 及自定义 SHaRC 容量／训练配置已删除，原生 SHaRC 启停保留。新控制的实际效果仍待定向验证。通用 NativeLighting／Sparse 与共享同步是真实独立外桥，不因本轮收缩整族删除；宿主专属亮度曲线、画风与最终合成属于消费方，现有宿主策略迁出尚未完成。

## 上游基线与唯一补丁例外

当前锁定 NVIDIA RTXPT **v1.9.0**，commit **303b408ab81790a5051abd7573830b7a4112eb8e**。

- 官方源码位于 build/upstream-rtxpt/source，来源清单校验覆盖原文件内容及额外文件，禁止修改或注入自有源文件。
- 官方资源保留 bin/ 与相邻 Assets/ 布局；适配器 DLL、JVM 宿主及 AdapterShaders/ 位于外部运行时目录。
- 不使用旧 build/rtxpt-sdk overlay，不 patch 原 shader、默认算法、编译清单或 CRT 参数。
- 用户只授权一项内部补丁：构建工作副本的 SampleCommon::ProgressBarStart 在 HelpersIsNonInteractive() 为真时直接返回 -1；交互模式保持原样，不涉及渲染算法。官方 source 导出继续只读，构建副本这一处差异须单独审计。当前仅为授权实施范围，不能提前记为 GPU／控窗验收通过。
- 接入依据是锁定版本的实际公开入口与示例；单纯存在某个声明不代表有可执行的扩展入口。

详见[运行时与启动](docs/runtime-distribution.md)、[外部 shader 与 SDK 边界](docs/rtxpt-release-sdk.md)。

## 依赖与启动

正式发布完成后，消费者只需依赖 provider，它会带入纯契约模块：

    implementation("top.ceroxe.rt:renderer-rtxpt:8.0.0")

8.0.0 不保留 7.x 的双后端路由、组合器或私有诊断 ABI。本批保持相同版本覆盖 Maven Local，消费者须重新核对制品摘要。

原版 RTXPT 按进程 EXE 的位置查找 DXC、shader 与资源。运行时提供 rtrenderer_jvm_host.exe，使用标准 JNI Invocation API 启动应用，不修改上游路径解析。可先调用 RtxptRuntime.jvmHostExecutable() 获取校验／提取后的宿主路径；该调用不创建设备。JVM 路径、classpath、main 和 Java 参数通过明确的进程环境字段传入；真实命令行保留给原版 RTXPT。完整契约见[启动说明](docs/runtime-distribution.md#jvm-宿主启动契约)。

## 诊断与验证

通用流式负载入口为 [Invoke-StreamingSceneLoad.ps1](tools/Invoke-StreamingSceneLoad.ps1)，CPU 入口检查为 [Test-StreamingScenePerformance.ps1](tools/Test-StreamingScenePerformance.ps1)。本轮[分窗结果与三组对照表](docs/quality/streaming-scene-results.md)分别记录完整新包矩阵、同 bytecode 短窗控制与 fast20 前后观测；机器数据见 [streaming-scene-results.json](docs/quality/data/streaming-scene-results.json)。完整移动尚未达到 180，不能将无 tile 跨界短窗的倍数当成完整移动收益。

诊断只记录适配层实际调用边界；RTXPT 官方没有可读取数值的内部 observer 时，明确写“未展开”。RTXPT_FRAME 是包含原生等待的墙钟耗时，不能当成 GPU 忙时，也不能与嵌套耗时相加。[诊断契约](docs/rtxpt-diagnostics.md)说明采集级别、固定容量及游标。

截至 2026-11-07，官方 source 导出的 20 个仓库／4426 个原文件保持原样；构建工作副本允许前述唯一非交互进度补丁，不能再把最终构建概括为“零 patch”。历史生产 29 帧及 beauty 24 帧 proof、此前 CPU／宿主／shader／提取／POM／符号记录均保留各自受测身份。此前两模块 Java 编译及 API 边界检查记录为 12.918s／采集峰值 1.220GB（构建口径，不是客户端内存）；不证明新增控窗逻辑的 GPU 行为或画面效果。本轮通用分窗负载已通过正确性；完整移动180目标、用户人工验收和消费方整体性能仍未通过。[视觉迁移总账](docs/quality/visual-migration-audit.md)保留 finite fade、coverage、host-primary、full-float 等未恢复缺口；[修复迁移映射](docs/consumer-agent-handoff.md#既有修复的迁移映射)区分保留、原生接管、退役和待验证。

当前人工场景入口为 [examples/native-scenes](examples/native-scenes/README.md)，主类 top.ceroxe.rt.renderer.rtxpt.examples.NativeSceneDemo。先运行 balls，由用户关闭且进程退出后再运行 mixed；目标为 1600×1000 窗口、VSync 关闭、无软件帧上限、请求 2×FG，不自动接管窗口。Java 参数支持 --denoising scene|off|relax|rr；默认 scene 保留场景策略，不能为遮盖差异擅自改去噪或 SPP。光学 --native 必须显式取 true 或 false，balls 不支持 --native true。请求 FG 不等于已激活或呈现帧率，须看真实原生状态；光学场景不宣称体散射。

源码构建入口是 tools/Build-RtxptBridge.ps1；所有重任务使用 tools/Invoke-BoundedBuild.ps1、Gradle --no-daemon，任务进程树合计上限 10 GiB。已通过项无新原因不重复运行。发布按“验证完成 → 全量源码／工具／文档上传 GitHub → Maven Local”执行，本次不发布 Maven Central；生成二进制不上传源码仓库。

项目适配代码采用 [Apache-2.0](LICENSE)。NVIDIA、Microsoft 等上游组件仍按各自许可证分发。[文档入口](docs/Home.md)区分当前指南和历史证据。
