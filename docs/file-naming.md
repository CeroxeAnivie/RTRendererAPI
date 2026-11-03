# 项目文件命名

项目维护的源码、类名、测试任务、工具、文档与输出入口按功能命名。发布版本从 `gradle.properties` 读取，不嵌进这些名称。

历史发布文档使用描述变更内容的名称，由 [发布索引](release-index.json) 保留版本对应关系。ABI 快照按完整 SHA-256 内容寻址，由 [ABI 索引](../renderer-api/abi/release-index.json) 保留版本对应关系；相同二进制接口可以共用同一份不可变快照。同 major 的兼容性检查仍读取该 major 首次发布的快照。

`generateRendererApiAbi` 使用稳定输出文件名；`updateRendererApiAbiBaseline` 写入内容寻址快照和索引。SDK staging 默认读取统一版本配置。Maven 发布产物继续使用 Maven 坐标规定的标准名称。

执行 `pwsh -NoProfile -File tools/Check-FileNaming.ps1` 检查所有 Git 管理或尚未忽略的项目文件。CI 在构建前执行同一检查。忽略的历史运行日志、构建缓存、下载工具链与第三方源树不属于项目命名迁移范围。

本轮迁移了 99 个已有路径，另将三个新增测试类改为功能名；55 个历史 ABI 版本和 17 份历史发布说明均保留索引。原始测量数值和 ABI 内容没有改写。
