# RTXPT 最终验收 TODO

按顺序执行，只有拿到运行证据才打勾。

- [x] 修复 Streamline 关闭时插件提前卸载；关闭失败必须传回 Java，不能仅打印错误后通过。
- [x] 最终正确性：适配层合同、原生场景、变换更新、场景切换、截图、缩放、关闭和重建。
- [x] 打包 JAR 在源码目录外通过同样的生命周期测试。
- [x] 高级组合：回读实际 DLSS-RR / DLSS / NRD、SHaRC、ReSTIR DI/GI、SER、Reflex 与 2x/3x/4x FG；逐项验证生效及上游互斥规则。
- [x] 性能：同版本、同场景、同相机和功能设置，对比独立原生 RTXPT 与发行 JAR；1080p 输出、1 SPP、FG 关闭，预热后按真实完成帧计时。
- [x] 检查初始 GPU 占用，交替重复原生/JAR 测量；报告 FPS、ms/帧、内部尺寸及桥接损耗，验收损耗不超过 5%。
- [x] 更新开发文档和验收证据；全部成果提交并上传 main，工作区干净；历史未提交内容在 main 历史中保留。
- [x] 清理两个非当前 worktree。
- [ ] 当前聊天绑定的旧 checkout 待应用切换后删除。应用拒绝删除任务的 primary checkout；主实现已在 `D:/Engineering/code/APIs/RTRendererAPI` 的 main。
