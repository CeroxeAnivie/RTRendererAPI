# 运行时与 JVM 宿主

本文对应已合入 GitHub main 并发布 Maven Local 的 **8.0.0 人工验收候选**。两模块制品及内嵌运行时身份已核对，见主分支收拢记录；人眼效果与介质尚未签收。不能混用 7.x 的 native DLL、私有 shader ABI 或 overlay。

## 两模块运行图

消费者依赖 renderer-rtxpt，它以 compile 依赖带入同版本 renderer-api。renderer-api 不反向依赖具体 provider。旧 renderer-core、renderer-nvidia、hex-ball 演示及本项目自己的 Vulkan 渲染路线不再构建或打包。

目标坐标：

    top.ceroxe.rt:renderer-rtxpt:8.0.0

当前平台是 Windows x86_64、Java 25、D3D12/DXR 与受支持的 NVIDIA GPU。普通 CPU 帧、旧 Vulkan presenter 和旧 RTXPT／Vulkan 组合器不是本版 provider 的输出承诺。

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
    Assets/

bin 中的原版依赖及资源与 Assets 同源；自有 bridge、宿主和 AdapterShaders 独立放置。AdapterShaders 不是向上游 ShaderDynamic/Source 注入的文件。

官方 source 导出仍只读。用户允许构建工作副本保留唯一内部补丁：SampleCommon::ProgressBarStart 在 HelpersIsNonInteractive() 为真时返回 -1，不创建进度窗口；interactive 保持原版，渲染算法、shader、默认质量和编译清单不因此改变。此项按唯一预期差异审计，不能将最终构建写成“零 patch”。该构建已完成隐藏原生场景运行，具体身份见解析光路恢复报告。

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

native nonInteractive 属于原生 process argv／原生配置，不是 NativeSceneDemo 的 Java 参数。原版该模式只关闭需输入的错误弹框，进度窗口由前述唯一授权 guard 补齐；不能靠隐藏宿主、伪造 fullscreen 或 WinEvent 藏窗替代。原版 nonInteractive 编译失败会调用 HelpersExitProcess(1) 终止整个宿主进程，外层须保留真实日志和非零退出码，不能把它写成 JNI 可捕获的恢复异常。

## 源码构建与发布

官方源码位于 build/upstream-rtxpt/source，来源清单位于 build/upstream-rtxpt/source-manifest.json。tools/Assert-RtxptUpstream.ps1 对官方导出同时拒绝原文件变化及清单外新增文件。外桥构建入口是 tools/Build-RtxptBridge.ps1，自有 shader 构建入口是 tools/Build-AdapterShaders.ps1。官方导出继续只读；只有构建工作副本的 ProgressBarStart 非交互 guard 获准例外，须明确记录来源基线与这一处差异，不放宽其它源码／shader 的校验。

所有构建与客户端使用有界进程树入口，合计内存上限 10 GiB；Gradle --no-daemon，重任务串行。来源／唯一补丁差异、CPU、原生出图、正式包及完整负载分别核验。历史生产 29 帧与 beauty 24 帧 proof 不为后续变更转签。最新两模块 Java 主源码和测试源码编译、演示默认值检查、独立公开消费者阴影开关三帧隐藏运行已通过；玻璃稳态完成提交 231.965 FPS、三材质切换 720 帧通过采集，具体边界见解析光路恢复报告。用户尚未人眼签收，介质未启动；最终分发和 Maven Local 身份以主分支收拢记录为准。

人工[原生场景示例](../examples/native-scenes/README.md)以 top.ceroxe.rt.renderer.rtxpt.examples.NativeSceneDemo 为 Java main。先 balls，待用户关闭且进程退出后再单独 mixed；Java 场景参数的每个 flag 和 value 各占一个 RTR_JVM_ARG_n，不把整条参数串塞进一个字段。--denoising 只接受 scene、off、relax、rr；光学比较的 --native 必须带 true/false，balls 不接受 --native true，--native true 与 --denoising rr 也不能组合。不自动启动第二个窗口，也不以 FG 请求代替原生激活／实际呈现测量。

验证完成后先提交并上传全部源码、正式工具与文档，再发布两模块 Maven Local；本次不发布 Maven Central。项目适配代码的 Apache-2.0 与 NVIDIA／Microsoft 等运行时许可证分别保留，不能将所有原生文件当作项目许可证授权。
