# 稳定资源更新：正式发布回执

2026-11-07，7.3.2 已按源码 GitHub→四模块 Maven Local 的顺序完成。当前分支 codex/resource-root-followup，未切分支、未创建 worktree、未启动 MC。

受测源码提交：e4c755398fda349d6789e83d0f826f10f048dc3f。本回执是后续纯文档提交，不重新打包，也不把回执提交冒充制品来源。

package-only 55/55、Local 77/77 身份检查通过；16 项受影响正确性检查及四份像素逐字节等价见 [本批报告](retained-update-locality.md)。完整 [回执 JSON](data/retained-locality-release.json) 保留全部 classifier/POM/module/源码/class/JNI/PDB/runtime/SDK 校验。

|模块主 JAR|SHA256|
|---|---|
|renderer-api|fcd22ffc4dc3fc6fbeed809e43e3bb596e4b3db8bc769863670e591829bd399c|
|renderer-core|a2e59ce9b4f84846827b3b0c255101fda9945e27d1379c38123c8129c55f3a69|
|renderer-nvidia|39b9f11445013704465776361dabccadc6a381e35d2923970ecd306266835095|
|renderer-rtxpt|d09bc3985af2587906c2f7014b350573c7c5cfa1824def3f18398c16e38d3d9d|

JNI SHA256：4960e5284e256c032338e0d5f3d5003305bc76065708fdb55b9a428320e7147a
runtime ZIP SHA256：08b87a2bbe9d33af4090409dd30d1d66fe9fa7c79d30d0c37689e7d95af672f5

锁定旧7.3.1的 1621 个运行依赖/可执行 shader 文件逐字节一致；NVIDIA runtime 21 文件未变，SDK 81 文件逐项通过。shader ABI：f7671628cf8d938358c45819bff01276c5c3eb76a3398e4ca3f25f490a9cb3ac

本次只做 package/Local/身份收尾；不发 Central，不上传生成二进制到 GitHub，不部署、不打开 MC。修后 MC FPS 未测，FRAME/regions 退化和 producer GPU 未完成的测量边界均保留于本批报告。

|步骤|旧值|新值|差值|百分比|
|---|---:|---:|---:|---:|
|package-only|无同条件旧值|88.591s|不适用|不适用|
|package 身份核验|无同条件旧值|16.027s|不适用|不适用|
|Maven Local 发布|无同条件旧值|11.653s|不适用|不适用|
|Local 身份核验|无同条件旧值|16.496s|不适用|不适用|
