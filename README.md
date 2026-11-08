# RTRendererAPI

面向 Java 的通用渲染契约与 NVIDIA RTXPT 外部适配层。当前版本 **8.0.0**，运行平台为 Java 25、Windows x86_64、D3D12/DXR 与受支持的 NVIDIA GPU。

## 模块

| 模块 | 职责 |
| --- | --- |
| renderer-api | 场景、帧身份、资源与生命周期契约 |
| renderer-rtxpt | NVIDIA RTXPT provider，依赖同版本 renderer-api |

资源、上传、加速结构及原生渲染循环使用 RTXPT／Donut／NVRHI。外部适配层负责输入转换、资源身份、跨图形 API 同步、输出租约和错误传播；独立光照扩展与 RTXPT 原生算法分别说明。

## 依赖与启动

使用已安装到 Maven Local 的依赖：

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    implementation("top.ceroxe.rt:renderer-rtxpt:8.0.0")
}
```

provider 会带入同版本的纯契约模块，不要混用不同版本的 JAR 或原生 DLL。

应用通过运行时提供的 `rtrenderer_jvm_host.exe` 启动。`RtxptRuntime.jvmHostExecutable()` 返回校验、提取后的宿主路径；Java 配置由 `RTR_JVM_*` 环境字段传入，实际进程命令行留给 RTXPT。详细字段见[运行时与 JVM 宿主](docs/runtime-distribution.md)。

场景和帧通过 `SceneTransaction` 与 `RenderFrameRequest` 提交。共享光照输出使用 GPU fence 与租约交接，消费完成前不得释放资源。调用顺序和配置见 [Java 接入指南](docs/Java.md)。

## 文档

- [文档首页](docs/Home.md)：接入、配置、运行时与兼容性。
- [API 参考入口](docs/Java-API-Reference.md)：公开类型与源码。
- [原生场景示例](examples/native-scenes/README.md)：场景启动与操作。
- [诊断接口](docs/rtxpt-diagnostics.md)：采集级别、事件游标与计时边界。
- [源码构建工具](tools/README.md)：构建、测试与打包入口；使用成品依赖无需运行这些脚本。

8.0.0 不提供旧自研 Vulkan 后端、双后端组合器或旧私有 shader ABI。升级范围见[兼容性](docs/COMPATIBILITY.md)。性能取决于实际场景、配置与消费方完整链路，不承诺固定帧率。

适配代码采用 [Apache-2.0](LICENSE)。上游组件遵守各自许可证，见[第三方声明](THIRD_PARTY_NOTICES.md)。漏洞报告方式见[安全策略](SECURITY.md)。
