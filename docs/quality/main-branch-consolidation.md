# API 主分支收拢

2026-11-07。范围仅 D:/Engineering/code/APIs/RTRendererAPI，不修改 MC 仓库。用户授权保存全部工作、合入 main、仅保留一个分支并删除其它分支，停止在人工验收启动前。

合并前分支尖端：main 本地 729f0e9、origin/main 与 codex/7.1.6-resource-architecture 为 9f36478、codex/7.1.7-resource-capacity 为 fc72bb4、当前 codex/resource-root-followup 为 fe0e0af。上述历史均已是当前分支祖先。唯一独有远端提交为 dependabot 的 a9b9924（Gradle GitHub Action 6.4.1）；将正常合并其 CI 修改，不使用 ours 策略吞掉改动。

工作区包含本批 8.0.0 API 重建、旧 Vulkan 路线删除、薄桥接、诊断、视觉恢复、演示和文档改动。先整体提交保全，再收拢 main；仅在所有分支尖端均可从 main 到达、GitHub main 上传成功后删除多余分支。既有提交历史保留，不 reset、不创建 worktree，不上传生成运行时二进制。

启动前已完成：官方源 4426 文件校验、最终桥接构建、Java 主源码和测试源码编译、两场演示默认值 CPU 回归、独立公开消费者三帧阴影开关回归。此前玻璃和三材质验证保留各自身份，详见 analytic-visual-recovery.md；不重跑已通过性能场，不启动介质或人工验收窗口。

两场默认请求 2×FG，SR Performance，1 SPP，不限软件帧率／运行时长；弹球玻璃解析光路，介质 mixed 原生光路。窗口与降噪／缓存默认值已对照旧源码恢复。自动跑分显式 FG off 不影响交互默认值；实际 FG 是否执行仍由原生状态决定。

GitHub 与 Maven Local 最终状态在实际完成后追加，不将准备阶段写成发布完成。
