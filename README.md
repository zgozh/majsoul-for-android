# 雀魂安卓辅助

AndroidX WebKit 在系统横屏窗口中全屏加载雀魂网页。网页正常登录后，应用根据当前账号的座位读取本人视角的牌局动作，将当前一局转换为 MJAI。默认 APK 内嵌三麻 Epsilon-Prism u3-v413 和四麻 Mortal-S42，按人数选择本机模型。可拖动的悬浮提示显示模型首选，点开可查看向听、有效牌和操作要点。出牌及副露由玩家自行操作。登录与游戏网页仍需联网。

## 使用

- 在 App 内的雀魂网页登录，进入允许辅助的练习场景。轮到本人操作时，浮层显示一条推荐。
- 按住浮层可以移动位置。轻点浮层展开或收起解读，位置和展开状态会保存在设备上。
- 默认构建在 Android 设备上运行两个模型。可选的 Windows 开发版会通过 ADB 转发，把当前决策的 MJAI 状态发送给电脑上的柘栯推理服务。

## 构建和安装

默认 APK 需要 Android 8.0 或以上的 ARM64 或 x86_64 设备，以及支持文档起始脚本和网页消息接口的 Android System WebView。当前构建脚本面向 Windows，使用 PowerShell 7、JDK 21、Android SDK Platform 36、Android Build Tools 36、NDK r28c、Python 3.12、Git、CMake、Ninja 和支持 Edition 2024 的 Rust。设置 `JAVA_HOME`，在 `local.properties` 中填写 `sdk.dir` 或设置 `ANDROID_HOME`。

源码仓库通过构建脚本准备模型和原生库。先解压柘栯 Windows 分发包并找到它的 `app` 目录。三麻使用其中 `models/epsilon-3p` 的原始 JAR、`policy.json`、`m0` 和权重许可证。四麻从同一分发包导出 ONNX。 [柘栯 Windows 分发包](https://github.com/zgozh/zheyou-majsoul/releases)

下例先用本机 Python 3.12 将 ONNX 导出依赖安装到构建目录，再用分发包自带的 Python 和 PyTorch 导出。将示例路径替换为本机路径，确保 Cargo、CMake 和 Ninja 在 PATH 中。

```powershell
$desktopApp = 'D:\zheyou-majsoul-windows-x64\app'
$ndk = 'D:\android-ndk-r28c'
py -3.12 -m pip install --target build\onnx-export-deps onnx==1.20.1 onnxruntime==1.23.2
& "$desktopApp\runtime\python\python.exe" -c 'import runpy,sys; sys.path.append("build/onnx-export-deps"); runpy.run_path("tools/export_mortal_onnx.py", run_name="__main__")' $desktopApp build\models\mortal-s42.onnx
rustup target add aarch64-linux-android x86_64-linux-android
py tools/epsilon-android/prepare.py --model "$desktopApp\models\epsilon-3p" --ndk $ndk
py tools/mortal-android/prepare.py --model build\models\mortal-s42.onnx --ndk $ndk
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

三麻脚本链接 PyTorch 2.7.1 Android CPU 静态库并沿用原版 u3-v413 权重。四麻脚本复用桌面版同一提交的 `libriichi` 局面编码和动作映射，通过 JNI 调用 Android ONNX Runtime 打分。

## 可选的 Windows 开发版

使用 `.\gradlew.bat --project-cache-dir build\gradle-windows -PwindowsInference=true :app:assembleDebug` 编译，输出位于 `build\app-windows-inference\outputs\apk\debug\app-debug.apk`。

1. 打开现有的 Windows 柘栯，等三麻和四麻模型加载。
2. 用 USB 连上允许调试的安卓设备，执行 `adb reverse tcp:25034 tcp:25034`。如果同时连接多个设备，先用 `adb devices -l` 查看设备，再用 `adb -s <serial> reverse tcp:25034 tcp:25034` 指定目标。
3. 打开安卓应用，登录雀魂并进入牌桌。转发在设备断开或重启后需要重新设置。

Android 11 及以上也可以在开发者选项中开启无线调试，用 `adb pair <地址:配对端口>` 配对，再用 `adb connect <地址:调试端口>` 连接。同样使用 `adb reverse tcp:25034 tcp:25034` 转发。依设备界面提供的地址和端口填写。

可在 PowerShell 中用 `Invoke-RestMethod http://127.0.0.1:25034/readyz` 查询 Windows 模型是否就绪。Windows 推理服务通过 ADB 访问自己的回环端口 `25034`。

## 验证

```powershell
node --test tests/training.test.mjs
```

如需一并核对 Windows 推理接口，可先启动桌面版服务，再运行以下命令。

```powershell
$env:ZHEYOU_VERIFY_WINDOWS = '1'
node --test tests/training.test.mjs
```

网页测试覆盖三麻和四麻开局、本人座位识别、同场换小局、重连补帧、重复步骤、缺帧、暗杠宝牌及拔北。安装包回放在设备上核对系统横屏窗口、两条推荐路由、解读展开状态，以及四麻观察张量、合法动作掩码和首选动作。运行时关闭 ADB 反向转发。

```powershell
.\tests\android\verify.ps1 -DesktopApp "D:\zheyou-majsoul-windows-x64\app" -Apk "app\build\outputs\apk\debug\app-debug.apk" -Sdk "D:\develop\tools\android-sdk"
```

## 数据和网络

登录与游戏连接由 App 内嵌的雀魂网页处理。默认构建在设备内处理用于推荐的 MJAI 状态。浮层位置与展开偏好由 Android 应用保存。可选的 Windows 开发版通过 ADB 转发访问用户电脑上的本地服务。

## 使用范围与免责声明

本项目是独立第三方工具，用于技术研究和明确允许辅助的练习。使用前请确认所在区服、房间或赛事的现行规则，以及适用法律。使用者自行决定牌局操作并承担使用本工具可能产生的账号或赛事后果。 [雀魂官网](https://mahjongsoul.com/)

模型推荐及规则解读仅供参考，可能因牌局同步、模型判断或设备兼容性等因素出现错误。软件按适用许可条款所述按现状提供。在适用法律允许的范围内，发布者对可用性、兼容性和推荐准确性不作保证。

本项目与雀魂运营方及相关品牌权利人无隶属或授权关系。第三方名称、商标和图像的权利归各自权利人所有。

## 许可证

本项目代码以 AGPL-3.0-or-later 为主。标有其他 SPDX 标识的文件、第三方模型、依赖和素材分别适用对应条款。 [AGPL-3.0 许可文本](LICENSE) · [第三方许可及源码出处](THIRD_PARTY_LICENSES.md)
