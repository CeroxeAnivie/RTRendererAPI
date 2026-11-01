# RTXPT 内存与 FPS 联合验收

用户要求：内存优化不得牺牲 FPS、画质或高级能力。测前 GPU 0 基础占用必须为 0%；否则暂停吞吐测试。JFR、原生栈和虚拟内存 census 独立运行，不用诊断帧率报收益。

## 已有内存证据

相同 `-Xmx512m` 的 eager/lazy 对照：仅使用 RTXPT、尚未请求旧 Vulkan 时，`devices-open` 私有提交由 1127.617 MB 降为 691.601 MB，减少 436.015 MB；首次预热后减少 342.565 MB。真正使用旧设备后，不能再宣称节省整套旧设备内存。

Demo 的 `-Xms32m` 将 `before-open` 私有提交从约 685 MB 降至 180.265 MB。12 轮自然 GC 下 Java 堆使用约 13–27 MB，未显示持续增长；整进程仍含上游 GPU／驱动及神经模型资源，不能把它们全算成 Java 堆。

旧停用路径的三轮 JFR 每次记录 Vulkan 临时图释放 53,084,160 字节。这是停用资源退休证据，不能据此宣称进程峰值下降。为避免频繁叠层开关等待 GPU，现改为保留有界缓存，显式 `trimIdleCompositionResources()` 才在空闲维护窗口回收。

## 关闭后重开的确定代码缺陷

不带 JFR／NMT／Frida 的 `natural-no-probes` 运行在首次重开中异常退出，`hs_err_pid2144.log` 将调用点落实到 `rtrenderer_rtxpt.dll+0x224500`。同制品 PDB 解析为 Donut `StreamlineIntegration::SimStart`，`StreamlineIntegration.cpp:1643` 的 `slPCLSetMarker`；实际执行地址已不属于已加载模块。

Streamline 2.14.16 `include/sl.h:40` 的 `SL_FEATURE_FUN_IMPORT_STATIC` 把插件函数保存在函数内静态指针。官方 `docs/ProgrammingGuide.md` 2.15 说明 `slShutdown` 会卸载插件。单进程关闭／重开宿主不能继续使用旧地址。

宿主仅在 Donut 的 Streamline 翻译单元中替换该导入宏：保留 SDK 原有空指针检查和缓存调用，首次解析时登记缓存地址，关闭 SDK 后集中置空。正常帧不新增符号查询、分配、同步或计时。SDK 本体与渲染算法均不改。原生 CPU 自检检查 10,000 次调用只导入一次、重开后换用新地址和重复 reset。

## 本轮对照

固定 1920×1080 输出、1 SPP、RR Balanced、SHaRC／SER，FG 关闭；原生完成帧口径。`return-overlay` 测持续叠层 1800 帧，`return-toggle` 测每 30 帧切换叠层的 600 帧。每次测前检查 GPU 0，关闭全部内部观测，只使用外部低频进程计数。

原制品保存在 `build/reports/rtxpt-memory/fps-guard/before-artifact`，DLL SHA256 为 `4D5766F580A7AF2E0D7F3A2A42107F636D41557C463BD2D101A277C6DBAE5D73`。前三轮基线有明显波动，不能拿其中最快一轮认定收益。后续使用同机新旧制品交错对照，并记录内存、真实退出码和像素正确性；验收结果追加于此。
