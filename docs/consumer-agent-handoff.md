# 消费方接入与复验交接

本文件是消费方 agent 的固定入口，文件名不随发布版本变化。发布版本见 `gradle.properties`，历史文档见 `release-index.json`，公共 ABI 历史见 `renderer-api/abi/release-index.json`。不要从文件名推断版本或把本机代理写入项目。

当前批次为 **7.1.3**。本批解决局部 mesh 触发全属性重提、重复 JNI 查找和动态资源经文件导入的问题，增加内存事务、mip／矩形 patch、异步退休和 GPU 资源复用。完整 beauty 的 OMM 表随内存拓扑扩容；退化法线的切线回退保持有限值。详见[本批修复](release-dynamic-resources.md)和[仓库量化证据](https://github.com/CeroxeAnivie/RTRendererAPI/blob/main/docs/quality/dynamic-resources.md)。交付渠道为 GitHub 和 Maven Local，不发布 Central；发布状态、分位数和制品身份以仓库量化报告为准。

此前 2026-11-04 的光源遍历优化使用本地坐标 7.1.1，按用户要求删除旧 Maven Local 包后重新发布。消费方重新解析同版本依赖时使用 `--refresh-dependencies`，并核对发布记录中的 JAR/runtime hash，不能仅凭版本号认定已更新。新 native DLL SHA-256 为 `b8b4bca550f2dce38fc169e3edb4f8249e5a3a5f78695556396acd7337f2fd57`；内置 raw shader 为 `6a28236824a82f958539fbcaf1cfc586ca2a7225fd4dec301d80d836328b6c57`。扩展使用新包内 SDK 重编译。432 张信号／guide 逐位一致，36 次真实 RR 通过；性能数据见 [最新报告](quality/native-lighting-performance.md)。该次交付已发布 GitHub 和 Maven Local。

## 已修复的接入问题

| 审核项 | 修改与使用方式 |
| --- | --- |
| H01 顶点染色 | 原生 primary／bounce 命中读取 RGBA8，barycentric 插值，执行 material × texture × vertex 一次；alpha 参与可见性，纯颜色变化不重建 BLAS。宿主表面已有 albedo 时仍由宿主乘一次。 |
| H02 OFF | 统一诊断 OFF 压住独立逐射线统计开关；requested 意图保留，effective 为 false，在途读回有界排空后停止新 copy/query/poll。业务 fence 和正常出图继续。 |
| H03 双信号 RR | 使用 `openReconstructedNativeLighting`，默认对合法漫反射 GI 调用真实 RR。直接增益独立，宿主底图保持原尺寸。详细信号／guides／尺寸和归属见 [重建契约](native-lighting-reconstruction.md)。 |
| H04 局部成本 | 准备阶段通过 persistent diff 和反向依赖选择实际 variants，稳定帧复用结构／实例快照；局部属性无导出目录，姿态只提交变化实例。原生材质／纹理更新复用常驻索引，alpha 只选择依赖 BLAS。拓扑变化正确失效并重建映射。 |
| H05 诊断 | recorder v4 区分墙钟与实际线程 CPU；DEEP 可显式拆为 receiver、direct、GI 三个真实 GPU dispatch，改变调度和存储，只用于诊断。direct 和 GI 各自仍包括内部源遍历及 shadow，不能把它们当独立 shadow 时间。 |
| H06 旧后端对照 | 已提供并实际运行加载旧发布 core JAR 的通用 fixture adapter，与 native 对照两个信号；原始记录、图像误差、环境限制见 [对照报告](quality/lighting-backend-comparison.md)。 |

普通入口按功能给兼容配置；专家配置不偷偷覆盖不兼容选项。原生 raw 和重建入口并存。完整 beauty、宿主 split transport 与 host augmentation 的 requested／actual 状态各自保留，不把一条路线的 RR 结果记到另一条上。

## 宿主仍需执行的步骤

1. 按通用 MeshAsset／MaterialAsset／SceneInstance 发布正确数据，透明语义与 receiver／shadow／bounce roles 明确。顶点色按 raw numeric RGBA8 输入，不预烘焙 AO／光照。
2. 使用 `LightingFrameLease` 一次取得同 generation／sequence／revision 的各 plane。导入成功后 `markImported()`，GPU 等待 readyValue，读完后在同一 GPU 队列排入 completionValue，再 `completionSignalEnqueued()`。完成销毁导入对象后再关闭 renderer。
3. 解码紧凑增益／GI 后再插值；组合一次 `hostBase * gain + GI`。重建 GI 已含 receiver albedo，不能再次乘底图或重复叠加 raw GI。宿主透明层、手部和 UI 继续按原分辨率处理。
4. 对真正的相机切换／外部世界跳变使用 temporal reset。持续正常运动保留相邻帧历史；不要把每帧源移动误当强制相机切换。
5. 性能报告区分 CPU 事务、核心 GPU 光照、共享复制、宿主 GL 消费、重建与全帧。高外部 GPU 占用的数据不能宣布为纯净速度倍率；固定 fixture 不能替代最终世界验收。

shader 数据布局的身份已经变化。离线扩展必须用随包 SDK 重编译，旧 ABI 扩展会在激活前拒绝，不能复用旧 hash 或覆盖 manifest 规避检查。

## 证据使用规则

保留已经通过且未受改动影响的测试结果。本批新补验不跑 FG/MFG；原始失败、修复后补验与最终制品验收分列，不能合并成“一次全量全绿”。带真实等待或未展开的 GPU 区间如实标明，不把 ray count 当作毫秒，也不把 inclusive wall time 当线程 CPU。

当前开发证据包含 256 mesh 属性局部更新、材质／纹理／顶点 alpha、实际 GL 引导边缘、依赖删除拒绝、group specialization 与完整组逐位一致、真实 GI RR guides 和 DEEP GPU／CPU v4。最后打包与窗口验收的完成状态以最终发布验收记录为准。
