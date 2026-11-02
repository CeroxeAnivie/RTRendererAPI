# Vulkan FG 窗口启动修复

Streamline 2.14.16 的 Vulkan 手动接入要求窗口 Surface 的创建和销毁也经过 interposer。项目原先只代理交换链及出帧，GLFW 直接创建 Surface，未登记 Surface 到 HWND 的映射。

实测错误为 `vkCreateSwapchainKHR` 返回 `-3`；SDK 日志明确报告 `Could not find a window corresponding to this application`。官方 `wrapper.cpp` 的 `vkCreateWin32SurfaceKHR` 成功后调用 `setSurfaceWindow`，销毁时调用 `removeSurfaceWindow`。

修复让 presenter 在存在 vendor WSI 接入时，通过同一 SDK 创建 Surface，并记住创建方，在交换链销毁后由原创建方销毁 Surface；即便 FG 中途回退也不跳过注销。无 vendor 接入时保持 GLFW 创建路径。桥接仅发生在窗口生命周期，不增加每帧命令或同步。

依据：官方 `ProgrammingGuideManualHooking.md` 的 Vulkan mandatory hooks，以及 `source/core/sl.interposer/vulkan/wrapper.cpp`。Java 编译、RTXPT adapter CPU 合同和 NVIDIA 原生桥接构建已通过；窗口实测结果保存在本机 `build/reports/demo-acceptance-20261102/`。
