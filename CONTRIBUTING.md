# 贡献指南

提交前必须使用仓库 Gradle Wrapper 执行：

```powershell
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
.\gradlew.bat clean check assemble --no-daemon --console=plain
```

涉及 Vulkan、VMA、shader ABI、外部句柄、队列所有权或性能路径的修改，还必须在受支持硬件上执行
`strictAcceptanceTest`。变更应说明根因、异常路径、资源所有者、同步关系与验证证据。
公开 API 变更必须遵循 SemVer，并同步 Javadoc、中文 README、示例和兼容性基线。
