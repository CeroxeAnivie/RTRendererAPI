# 7.0.4 透明背景定向验收

2026-11-03，Windows x64、Java 25、RTX 5080 Laptop。按要求仅验证本次透明输出与 GPU 合成，未执行全量回归。消费方 7.0.3 的评价成立；只替换 primary miss 的颜色不能保留宿主背景。

| 检查 | 结果 |
| --- | --- |
| 无重建背景 | 129,023 像素 Alpha=0，101,377 像素 Alpha=255，零 Alpha 残色为 0 |
| RR 背景与 AA | 127,605 像素 Alpha=0，100,737 像素 Alpha=255，2,058 像素部分 coverage，零 Alpha 残色为 0 |
| 无重建物体内部 | 与固定黑色主背景参考 RGB 完全一致，284,427 通道样本 |
| RR 物体内部 | 平均绝对色差 0.000219/255，最大 1/255，282,636 通道样本 |
| SR／SR＋NRD | 背景、有效表面、镂空、resize、恢复 opaque 通过；本机未生成部分 Alpha 边缘 |
| 镂空纹理 | 同一张卡片两侧实测 Alpha=0 和 255，无宿主几何／深度参与 |
| 玻璃、金属、嵌套吸收 | 混合场景内部颜色对照通过；首表面保留 coverage，secondary 环境不清零 |
| RTXPT 最终呈现＋背景 | 与同帧 CPU 线性预乘公式最大误差 1/255，平均 0.000253/255 |
| Vulkan 最终呈现＋背景 | 最大误差 1/255，平均 0.000253/255 |
| 两条路径＋上层 UI | 最大误差 1/255，平均 0.004835/255；最终 Alpha=255 |
| 重复 3D 场景 | 两条合成路径 Vulkan 3D 帧数均为 0，只消费独立 GPU 纹理 |

覆盖率／光照对照冻结随机噪声以隔离空间像素差异，不据此声称时间稳定性或动态相机质量。内部对照在 coverage 内侵蚀四像素；RR 的背景／边缘和所有合成像素另行逐像素检查。截图仅用于显式诊断，生产帧不增加 CPU readback。

640×360、RR、每组 48 完成帧的最终 JAR 短测记录如下；测试前 GPU 利用率为 0%。计时在截图、初始化及预热之后，包含实际 GPU 消费等待：

| 最终呈现 | 透明源，不合成背景 | 加 GPU 背景 | 增量成本 |
| --- | ---: | ---: | ---: |
| RTXPT 原生回交 | 5.751 ms/帧 | 6.318 ms/帧 | +0.568 ms，约 9.9% |
| Vulkan | 5.573 ms/帧 | 5.866 ms/帧 | +0.293 ms，约 5.3% |

这是同一版本新增合成功能的短测成本，不是 7.0.3→7.0.4 的等功能加速比。7.0.3 没有透明合成，无法做该功能的同条件 FPS 比较；此版量化收益是覆盖率与宿主背景组合能力，并无据此宣称提速。不同呈现方式的两行也不作为后端速度排名。

修正记录：RR 输入准备曾强制 Alpha=1；Streamline 不会因 Alpha 创建标志变化自动重建；极小 Alpha 量化为零时曾留下 RGB。三项均修复后通过。合成验证最初使用未发布为 GPU 输出的上传纹理，被 mutation gate 正确拒绝；改为明确完成并发布 GPU 输出。随后发现跨帧对照把 RR 历史变化算入误差，改为捕获同一生产帧后通过，没有放宽误差阈值。

复验入口：`:renderer-rtxpt:rtxptTransparentNativeSelfTest`；`:renderer-rtxpt:rtxptTransparentCompositionSelfTest -PtransparentPresentation=native` 或 `vulkan`。透明测试默认无重建／RR／NRD，可用 `-PtransparentCases=sr` 单测 SR。API ABI、旧 D3D12 descriptor 构造函数及不支持的 presenter 默认方法检查通过。SDK overlay 已通过官方 `ConfigureOnly` 回放。发布包需使用当前 shader 源码与真实重编二进制，不能沿用 7.0.3 的源码指纹。

最终 JAR 已在 `C:/Temp/rtrenderer-704-release-cache` 独立缓存中完成 RR 透明、镂空、resize／opaque 恢复及 native／Vulkan 两条背景＋UI 合成抽检，设置 `RTR_RTXPT_REQUIRE_PRECOMPILED=1`，禁止 fallback；未使用源码树 native override。包内含 802 个真实重编排列及六档光学预算，源码／二进制指纹校验通过。

| 7.0.4 制品 | SHA-256 |
| --- | --- |
| `renderer-api` | `91dd3ba4c38f5a29014acdc17d11bc96c50ef69d9756c3aaf58e3124cde427e6` |
| `renderer-core` | `f7e470ce7909f2100914ccfd58202c09ab5b900a06e8e9c943e185c56d3d4cc7` |
| `renderer-nvidia` | `d312ff29208f5fc86be46d88b1c838783850646572a1480dbfd4bf399552d757` |
| `renderer-rtxpt` | `e9ed2704cf7dde197c876a4ccaefdddb301bc225f423c0ade5746e8b9954c805` |

Native runtime archive SHA-256：`6f1caf0e408580afd6b95f27a6e43e3a26ee323ac7c5cd4949bb47d5db542b22`。JNI DLL SHA-256：`379893f931f5a074725e7cf5fb4196da14534a18f0e5a6747805c72b68a21890`。
