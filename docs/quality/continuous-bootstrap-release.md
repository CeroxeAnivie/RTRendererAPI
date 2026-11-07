# 连续光照初始化：发布回执

2026-11-07，7.3.3 已按源码/工具/文档 GitHub→四模块 Maven Local→最终身份核验完成。源码提交：3c613625222322dcb05346fa6e551931d77a3738。本回执为后续纯文档提交，不重编制品。

package 53/53、Local 75/75 全部通过；九项定向 JNI 通过，正式旧版首帧同错失败保留。Java 主 class 与旧版一致，1621 个依赖/可执行 shader 文件保持旧版字节，NVIDIA 21 文件未变，SDK 81 文件逐项核验。

[根因与限制](continuous-lighting-bootstrap.md) · [完整身份 JSON](data/continuous-bootstrap-release.json)

|模块主 JAR|SHA256|
|---|---|
|renderer-api|fcd22ffc4dc3fc6fbeed809e43e3bb596e4b3db8bc769863670e591829bd399c|
|renderer-core|a2e59ce9b4f84846827b3b0c255101fda9945e27d1379c38123c8129c55f3a69|
|renderer-nvidia|39b9f11445013704465776361dabccadc6a381e35d2923970ecd306266835095|
|renderer-rtxpt|4ed409f8243b41d3fc0d109c9c92a1e6932a1e3662e9d176c20afe75a664112f|

JNI：4220b80e7a00ffda75e9dc10c2f7499f73f84d93d4dd13f1d37f912f52b117c4
runtime ZIP：9bca338444a5bcdbb0c4f919bc5f304304139334666832a95574de4f0fcd4a32

Local 发布重新封装了 RTXPT runtime，导致主 JAR/runtime ZIP 与预 push package 的哈希不同；完整 JSON 同时保留两阶段身份，最终以 Local 身份为准。Local 当前包内的 Java class、受测 JNI、1621运行依赖/可执行 shader 全部重新严格核验通过，没有把旧 package 哈希冒充正式 Local 字节。

保持 codex/resource-root-followup；未切分支、未创建 worktree、未发 Central、未上传生成二进制到 GitHub、未运行 MC。API 重任务已释放，宿主可进行唯一修后隐藏验收。原 MC 首帧 FALLBACK 是配置资格误判，与单次构建 Javadoc JVMCI 崩溃分别记录。

|步骤|旧值|新值|差值|百分比|
|---|---:|---:|---:|---:|
|package 成功轮|无同条件旧值|70.998s|不适用|不适用|
|package 身份|无同条件旧值|16.547s|不适用|不适用|
|Local 发布|无同条件旧值|62.492s|不适用|不适用|
|Local 身份|无同条件旧值|16.341s|不适用|不适用|
