# 第三方许可及源码出处

| 组件 | 许可与出处 |
| --- | --- |
| 柘栯桌面版 | 提供原始模型分发包，操作解读参考其实现。各组件和改动的许可依其仓库声明。 [桌面版许可说明](https://github.com/zgozh/zheyou-majsoul/blob/main/THIRD_PARTY_LICENSES.md) |
| Akagi v3 与 AndroidX WebKit | Liqi 和 MJAI 参考 Akagi v3。Akagi v3 与 AndroidX WebKit 采用 Apache-2.0。 [Akagi](https://github.com/shinkuan/Akagi) · [AndroidX WebViewCompat](https://developer.android.com/reference/androidx/webkit/WebViewCompat) |
| Epsilon 和 Android 适配器 | GPL-3.0。仓库中 `tools/epsilon-android/EpsilonMjai.java` 标明 GPL-3.0-only，`epsilon-java.patch` 是构建时应用于上游源码的改动。 [上游源码](https://github.com/KoutaChan/Epsilon/tree/c3b4683dcc6c3dee29177c9f6e57f536078446d8) · [GPL-3.0 许可文本](licenses/GPL-3.0.txt) |
| Epsilon-Prism 3P u3-v413 权重 | MIT，以模型分发包内的许可为准。 [模型发布页](https://github.com/KoutaChan/Epsilon/releases/tag/epsilon-prism-3p-u3-v413) |
| DJL fork 和 PyTorch | DJL fork 使用 Apache-2.0，Android 改动见 `tools/epsilon-android/djl-android.patch`。PyTorch 主体采用 BSD-3-Clause，所含第三方组件依其各自许可。 [DJL fork](https://github.com/KoutaChan/djl-rocm/tree/c06319c0d78892606daf2c2136cd73c24cf8c1dc) · [PyTorch 2.7.1](https://github.com/pytorch/pytorch/tree/v2.7.1) |
| Mortal-S42.8k 权重 | Apache-2.0。ONNX 导出保留原模型权重出处。 [模型卡](https://huggingface.co/haor/Mortal-S42) |
| Mortal `libriichi` 和 Android JNI 适配 | AGPL-3.0-or-later。本项目的 `tools/mortal-android/` 包含源码准备脚本和 JNI 实现。 [对应上游源码](https://github.com/Equim-chan/Mortal/tree/0cff2b52982be5b1163aa9a62fb01f03ce91e0d2) |
| ONNX Runtime Android 1.23.2 | MIT，依发行包保留其第三方声明。 [上游源码](https://github.com/microsoft/onnxruntime/tree/v1.23.2) · [Maven 发行信息](https://central.sonatype.com/artifact/com.microsoft.onnxruntime/onnxruntime-android/1.23.2) |

如分发由本项目构建的 APK，请保留随依赖和模型提供的许可文本与版权声明，并按相关条款提供对应构建版本的源码、改动和构建脚本。

项目代码许可证的授权范围为适用的源代码。第三方品牌、商标和图像依各自权利人的条款。
