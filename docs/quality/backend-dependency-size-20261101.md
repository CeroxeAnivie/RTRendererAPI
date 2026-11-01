# 双后端运行依赖体积

统计当前 Windows 7.0.0 Demo 的 Gradle `runtimeClasspath` 实际解析文件，单位 MiB（1,048,576 字节）。这是压缩运行 JAR 的文件大小之和，不是显存、运行内存、源码 checkout 或 SDK 大小。不同模块合并为一个 JAR 时会重新压缩，不能把下列和直接当作最终 fat JAR 的精确大小。

| 旧 Vulkan 路线组成 | 字节 | MiB |
|---|---:|---:|
| renderer-core | 14,532,999 | 13.86 |
| renderer-nvidia（含原生 NVIDIA 运行库） | 56,518,716 | 53.90 |
| fastutil 8.5.19 | 23,977,871 | 22.87 |
| JOML 1.10.8 | 778,846 | 0.74 |
| LWJGL 3.4.3 core + Windows native | 1,434,288 | 1.37 |
| LWJGL GLFW + Windows native | 326,054 | 0.31 |
| LWJGL Vulkan bindings | 8,866,042 | 8.46 |
| LWJGL VMA + Windows native | 239,610 | 0.23 |
| LWJGL shaderc + Windows native | 2,695,420 | 2.57 |
| Kotlin stdlib／common／jdk7／jdk8 + annotations | 1,911,428 | 1.82 |
| **旧路线，不含公共 API** | **111,281,274** | **106.13** |

第三方依赖小计 40,229,559 字节（38.37 MiB）。不带 NVIDIA 高级功能桥接时，Vulkan 核心与第三方依赖合计 52.23 MiB。

公共 `renderer-api` 当次为 681,459 字节（0.65 MiB），两条路线都需要；后续新增 API 会改变其大小。RTXPT 当次运行模块为 80,433,342 字节（76.71 MiB），包含它自己的原生运行时。两后端都需要的 DLSS／Streamline 技术在各自运行时中提供，不能因为撤下一端就删另一端使用的 DLL。只部署 RTXPT 的依赖排除示例见 [后端选择](../backend-routing.md)；完整能力差异见 [发布检查](api-release-review-20261101.md)。
