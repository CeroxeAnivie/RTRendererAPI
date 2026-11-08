# 原生场景示例

此目录是消费方示例，通过公开 API 提交场景。弹球显式选择 BeautyTransport.ANALYTIC_DIRECT：独立桥接调度、原生相交／材质／BSDF／介质／后处理，不修改 RTXPT 本体。其它场景和 API 默认仍为 NATIVE。主类为 top.ceroxe.rt.renderer.rtxpt.examples.NativeSceneDemo；源文件由 renderer-rtxpt 的示例 source set 编译，应用仍须从[标准 JNI 宿主](../../docs/runtime-distribution.md)启动，不把普通 Java 参数交给原版 RTXPT 解析。

## 场景与操作

准备好 :renderer-rtxpt:rtxptReleaseValidationClasspath 后，使用 tools/Invoke-NativeSceneDemo.ps1 -Scene balls 启动一场；用户关闭后才另行 -Scene mixed。脚本默认显示普通窗口，保留所有演示默认值，不自动串场；后台验证显式加 -Hidden。加 -CheckPaths 只核对可启动输入，不创建进程或窗口。

- 第一轮使用 --scene balls。窗口按 1／2／3 切换玻璃、不透明、金属球，运动状态保留；控制台可输入材质名或 quit。
- 用户关闭第一轮窗口、确认进程退出后，再由启动方单独启动 --scene mixed。程序不会自动启动第二个场景或进程。
- 光学预设为 mixed、glass、nested、absorption、rough、ior；窗口按 1–6 或控制台输入预设名切换，Esc／quit 退出。
- 光学场景展示真实折射、透射、RGB 吸收与嵌套介质；没有对外散射参数，不能称作体散射验收。

默认设置：两场均请求 2×FG、SR Performance、1 SPP、无软件帧上限、无自动退出。弹球默认玻璃、解析光路、关闭降噪和 SHaRC、曝光 −1.5 EV；介质默认 mixed、原生光路、RR／SHaRC 按能力请求、曝光 0 EV。弹球未指定尺寸时沿用屏幕尺寸减 160 并上限 1920×1080；介质默认 1920×1080。后台性能测试显式传 --fg off 不改变交互默认值。

人工窗口可显式指定 1600×1000，VSync 在原生启动配置中关闭。第一场 Java main 参数示例：

    --scene balls --material glass --width 1600 --height 1000 --fg 2 --frames 0 --seconds 0 --denoising scene --diagnostics off

这些参数的每个 flag 和 value 各占一个 RTR_JVM_ARG_n，不能把整条字符串放到一个字段。第二场只在用户结束第一场且进程退出后另行启动，将 --scene 改为 mixed；程序不自动串场。--frames 0 和 --seconds 0 表示不设置自动结束限制；两者不能同时为正值。

| 参数 | 实际支持与语义 |
| --- | --- |
| --fg | off、2、3、4；是请求档位，不是测得的输出倍率 |
| --denoising | scene、off、relax、rr；默认 scene 保留本场景配置。弹球解析模式仅 scene/off；relax/rr 会明确拒绝。其它原生场景保留去噪选择 |
| --hidden | true/false；默认 false。后台验证显式 true，保留原生渲染及 Present，只隐藏窗口 |
| --cycle-frames | 默认0；至少120时，在同一个renderer中依次切换全部3种球材质或6个光学预设，每项运行指定帧数后自动结束。--capture按材质／预设分别保存最终窗口图像；用于完整后台巡检，不是无读回性能场次 |
| --native | 必须带 true 或 false，仅用于光学场景；balls 不支持 true。true 与 denoising=rr 不能组合，原生分辨率对照可显式用 relax |
| --diagnostics | off、summary、deep；默认 off |
| --output／--capture | 分别指定 JSON 报告／显式 DisplayColor 读回；capture 有额外开销，不应混入无读回对照 |
| --spp | 这里只接受 1，不靠增加 SPP 掩盖旧画面差异 |

VSync 在启动链／原生配置中控制，示例没有 Java --vsync 参数。原生 nonInteractive 也不属于 Java main 参数。VSync 与 FG 以实际原生状态为准，请求 2×FG 不代表最终呈现 FPS 翻倍。

示例区分冷首帧和稳态 submit 延迟，读取实际 window／framebuffer／render／display 尺寸、曝光及 RenderingState。Scene Combo、键盘和控制台切换同步，HUD 每500ms刷新。原生去噪选择／启用策略／已初始化对象不等于本帧 GPU 执行；未知项显示 NOT MEASURED。日志中的 completed submissions 是原生提交吞吐，SDK 查询区间计数不是 FG 倍率或实际 generated-frame FPS。
