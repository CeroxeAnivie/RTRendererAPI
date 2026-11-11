# 运行时与 JVM 宿主

本文适用于 **8.0.0**，说明运行时目录和标准 JVM 宿主启动契约。不要混用不同版本的 JAR、native DLL 或 shader ABI。

## 两模块运行图

消费者依赖 renderer-rtxpt，它以 compile 依赖带入同版本 renderer-api。renderer-api 不反向依赖具体 provider。旧 renderer-core、renderer-nvidia、hex-ball 演示及本项目自己的 Vulkan 渲染路线不再构建或打包。

目标坐标：

    top.ceroxe.rt:renderer-rtxpt:8.0.0

当前平台是 Windows x86_64、Java 25、D3D12/DXR 与受支持的 NVIDIA GPU。普通 CPU 帧、旧 Vulkan presenter 和旧 RTXPT／Vulkan 组合器不是本版 provider 的输出承诺。

原生光照会话不需要 Streamline 时，默认按系统高性能顺序选择满足 D3D12 要求的硬件显卡；旧系统按专用显存选择。显式指定显卡仍优先，启用 Streamline 的会话继续使用其原版选卡流程。

## 官方布局

锁定上游为 RTXPT v1.9.0，commit 303b408ab81790a5051abd7573830b7a4112eb8e。官方二进制包 SHA-256 为 d03749c06c4b89befebf1a8938ed88a4aca714ef1053649a6c32f4e7947380dc。

运行时 ZIP 保留以下相对关系：

    bin/
      rtrenderer_jvm_host.exe
      rtrenderer_rtxpt.dll
      D3D12/
      ShaderDynamic/
      ShaderPrecompiled/
      AdapterShaders/
      PrecompiledShaders/
    Assets/

bin 中的原版依赖及资源与 Assets 同源；自有 bridge、宿主和 AdapterShaders 独立放置。AdapterShaders 不是向上游 ShaderDynamic/Source 注入的文件。

默认核心包只携带运行时所需的 Fonts、loading_splash.png、许可说明，以及物理天空使用的 5 张固定查找表／云层／噪声贴图；不携带官方示例模型、示例场景、示例材质覆盖文件、大型 HDR 环境示例或 SampleGame。StandaloneTextures 仅允许这 5 个明确文件，不通配收录。Assets/Materials 中的预置覆盖随可选示例包提供；没有覆盖文件时，官方 MaterialsBaker 从实际场景材质导入，不依赖该目录。消费者自己的网格、纹理和环境仍由公开 API 提供，预编译着色器、降噪、DLSS 和画质功能保持完整。构建会检查核心包内容，禁止示例资产重新混入。
原版初始化会枚举 EnvironmentMaps，因此核心包保留该空目录。ShaderDynamic/Source 用于预编译源码摘要校验；原版初始化仍检查真实 dxc.exe 路径，因此保留该可执行文件，但不携带仅供构建期编译的 dxcompiler.dll／dxil.dll。宿主禁止创建编译器子进程，发行包没有运行时编译回退。

需要运行官方示例时，显式执行 `:renderer-rtxpt:packageRtxptExampleAssets`，将生成的 `renderer-rtxpt/build/example-package/rtxpt-example-assets.zip` 解压到外部运行时根目录，使其 Assets 与 bin 同级；再通过 `top.ceroxe.rt.rtxpt.runtime` 指向该外部 bin。此可选任务不属于默认 jar 或 Maven 发布依赖。

运行时不调用源码编译器。`PrecompiledShaders` 包含构建期二进制、真实命令模板、配置摘要和源文件摘要；ZIP 排除机器运行产生的 `ShaderDynamic/Bin` 缓存。首次渲染仅校验并复制二进制，按原版公开哈希函数重建安装路径对应的缓存键。所有源文件和二进制摘要必须匹配。

专用宿主和 native 会话启用 Windows `ProcessChildProcessPolicy.NoChildProcessCreation`，由操作系统禁止 DXC 等子进程启动。该进程也不能启动其它子进程；需要外部程序的消费方须在渲染进程之外启动它们。此策略没有修改或拦截上游代码、没有伪造编译成功，也不依赖环境开关。驱动加载二进制／创建 GPU pipeline 和 JVM 自身 JIT 不是离线 HLSL/native 源码编译。

预编译覆盖以运行时随附清单为准。未收录的配置明确失败，不降低画质或回退运行时编译；扩充组合须在构建期更新清单并重新分发。

JAR 中的 runtime ZIP 与 SHA-256 位于 META-INF/native/windows-x86_64。RtxptRuntime.binDirectory() 校验并返回 bin，RtxptRuntime.jvmHostExecutable() 返回宿主 EXE；两者不创建设备。打包提取保留摘要校验、进程间锁、安全路径检查和原子 staging；热缓存只复用完整内容。显式 top.ceroxe.rt.rtxpt.runtime 指向 bin，不完整就失败，不回退到旧 build/rtxpt-sdk。

## JVM 宿主启动契约

原版 ShaderCompiler、shader 和资产定位依赖真实进程 EXE 目录。rtrenderer_jvm_host.exe 位于官方 bin，使用公开 JNI_CreateJavaVM；不 hook CRT、不改上游路径，不依赖 Java launcher 私有接口或 IPC 服务。

只在子进程环境中设置：

| 字段 | 含义 |
| --- | --- |
| RTR_JVM_DLL | 绝对 jvm.dll 路径 |
| RTR_JVM_CLASSPATH | 完整 Java classpath |
| RTR_JVM_MAIN | Java binary class 名称 |
| RTR_JVM_OPTION_COUNT | VM 选项数，省略表示 0 |
| RTR_JVM_OPTION_0…N-1 | 每个字段保存一个完整 VM 选项 |
| RTR_JVM_ARG_COUNT | Java main 参数数，省略表示 0 |
| RTR_JVM_ARG_0…N-1 | 每个字段保存一个完整 main 参数，可为空 |

参数按字段传递，不再经过一套引号／分隔符解析。数量上限为 4096；classpath 单独传入，不在 VM 选项中重复设置 -Djava.class.path。中文类名、路径、参数与 VM 属性由 Unicode 入口处理。

**宿主没有 --jvm、--classpath、--main 启动旗标。**真实 process argv 归原版 RTXPT 解析，应用 Java 参数走 RTR_JVM_ARG_*。宿主调用 main 后由标准 DestroyJavaVM 等待非 daemon 线程；异常、参数错误及 VM 创建失败返回明确退出码。

原生 nonInteractive 属于 process argv／原生配置，不是 Java main 参数。原生错误可能终止整个宿主进程；外层应保留真实日志和非零退出码，不假定所有失败都能作为 JNI 异常捕获。

源码构建与 SDK 配置见[工具说明](../tools/README.md)，场景参数见[原生场景示例](../examples/native-scenes/README.md)。
