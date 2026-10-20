# Banana TV

给安卓电视一个更安静的首页：中文设置、可调 Dock、动态壁纸、天气和配套屏保。

[下载 0.1.11](https://github.com/sogud/banana-tv/releases/tag/v0.1.11) · [English](README.md) · [安装与恢复](docs/install.md)

![首页布局示意，使用演示应用](docs/images/home-preview.svg)

*布局示意图，非真实电视截图；应用列表取决于电视上已经安装的软件。*

## 已有功能

- 四个可编辑的常用应用入口，以及全部应用列表。Dock 默认 90%，可在 75%–110% 间调整；图标不显示名称标题，保留辅助功能标签。
- 遥控器方向键导航、焦点效果、中文设置和随壁纸取色的面板。
- 五种按日期和时间变色的原生动态背景，加上本地视频、四张静态帧和闲置/系统屏保，共十个内置壁纸选项。
- 输入源、网络和电视设置入口；已针对一款 Redmi 固件适配。
- 可选 Home 兼容功能及系统偏好设置，具体权限与恢复办法见安装文档。
- 可选天气：自动按网络出口识别城市，也可手动搜索。图标与温度显示在时间旁，默认关闭，不申请 GPS 权限。
- 可将系统允许接管的应用/媒体快捷键设为打开应用，保留电源、音量、语音、Home、返回和方向键。需要辅助功能；厂商专用键可能无法接管。
- 可隐藏应用或进入电视的应用设置；隐藏不等于卸载。
- 桌面没有广告位、账号系统或分析 SDK。仅天气启用后访问 Open-Meteo，自动城市定位另访问 ipwho.is。

## 安装

需要 Android 9 或更新版本、电脑和电视在同一可信网络，并开启电视的网络调试。先从发布页下载 APK 和校验文件，再安装：

```sh
adb connect TV_IP:5555
adb -s TV_IP:5555 install -r banana-tv-0.1.11.apk
adb -s TV_IP:5555 shell am start -n com.sogud.bbtv/.MainActivity
```

把 `TV_IP` 换成电视的实际地址；端口和调试方式以电视为准。先试用，再按[文档](docs/install.md#native-home-on-the-development-tv)配置默认 Home。小米部分固件需要电脑 ADB 才能切回原桌面，不能把安装 APK 当成已接管 Home。维护后关闭不再需要的网络调试，不向公网开放 ADB。

也可以克隆项目，让 Agent 先阅读 [AGENTS.md](AGENTS.md) 与安装文档，检查机型后逐步安装；修改系统包和重启前需取得设备所有者的明确同意。

## 当前边界

这是首个公开预览版。主要适配目标是 Redmi A55 2025，Android 9 / `mulan` / HyperOS `OS3.0.101.0.PSTAATV`，其他型号需实测。

0.1.10 已验证原生 Home、壁纸播放器连续性、部分输入源/设置入口及系统屏保。0.1.11 新增上述外观、天气与按键设置，已完成构建检查，尚未做这版的实体电视验收。开机、唤醒、开机广告实际消失、投屏、米家遥控及 HDMI 实际音画仍待验证。

它运行在电视现有的安卓系统上，不提供片源、影视账号或 TVBox 配置。影视库、搜索、播放、续看和手机配对见路线。窗口请求 60 Hz，内置视频 30 fps，不承诺全场景稳定 60 fps。

公开预览包保留原开发证书以支持现有安装升级。自己编译的 APK 通常签名不同；遇到签名冲突先找原密钥，不要直接卸载清数据。

## 构建与来源

使用 JDK 17、Android SDK Platform 34 和 Build Tools 34.0.0：

```sh
./gradlew --no-daemon :app:assembleDebug
```

代码 MIT，保留上游原版权声明；Outfit 字体 SIL OFL 1.1；内置 *Spring* 视频与静态帧 CC BY 4.0，© Blender Foundation。原私人插画包不随公开源码或 APK 分发。素材修改与哈希见 [WALLPAPERS.md](WALLPAPERS.md)。
