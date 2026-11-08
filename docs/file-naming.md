# 项目文件命名

项目源码、类名、测试任务、工具和文档按功能命名。版本从 `gradle.properties` 读取，不嵌入日常维护文件名。Maven 制品使用坐标规定的标准名称。

ABI 快照按 SHA-256 内容寻址，由 [ABI 索引](../renderer-api/abi/release-index.json)记录版本对应关系。同 major 的兼容性检查使用该 major 的基线；不得通过更新基线掩盖未解释的 ABI 变化。

执行 `pwsh -NoProfile -File tools/Check-FileNaming.ps1` 检查 Git 管理或未忽略的项目路径。构建输出、下载工具链、第三方源树和本地记录不属于项目源码命名范围。

开发过程、临时交接、原始捕获、跑分和本机发布回执不提交到源码仓库。`docs/quality/` 仅用于本地记录，由 Git 忽略。
