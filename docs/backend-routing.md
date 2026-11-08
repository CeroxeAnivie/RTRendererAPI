# provider 与接入边界

8.0.0 使用 `renderer-api` 和 `renderer-rtxpt` 两个模块。消费者依赖 `top.ceroxe.rt:renderer-rtxpt:8.0.0`，由 provider 带入同版本 `renderer-api`。纯契约模块不隐式加载 provider。

## 应用负责

- 提供场景、纹理、材质、相机、时钟和变更。
- 保持资源身份、场景 revision 与帧 sequence 合法。
- 导入共享 GPU 输出，并在消费完成后按 fence 与 lease 契约归还。
- 按实际公开输入提供宿主亮度响应，并完成应用自身的画风、亮度映射与最终合成。

## provider 负责

- 必要的数据适配、资源身份映射、输入检查和错误传播。
- 接入 RTXPT／Donut／NVRHI 的场景资源、上传、加速结构及原生渲染生命周期。
- 暴露已实现的 NativeLighting／Sparse 扩展及共享同步；这些独立扩展与原版 RTXPT 算法分别说明。

`Renderer` 提供生命周期、场景/帧提交与扩展查询。配置使用已接线的原生 feature 与资产字节准入预算。8.0.0 不提供旧双后端自动路由、Vulkan 渲染实现、CPU 帧读取或通用 GPU 命令提交。

启动方式见[运行时](runtime-distribution.md)，调用顺序见[Java 接入](Java.md)，升级范围见[兼容性](COMPATIBILITY.md)。
