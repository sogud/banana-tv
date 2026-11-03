# 开始使用

Banana TV 是原生 Android TV 桌面与影视应用，运行在电视现有的 Android 系统上。当前源码要求 **Android 9 / API 28 及以上**。

## 编译安装包

准备 JDK 17、Android SDK Platform 35 和 Build Tools 34.0.0。设置 `JAVA_HOME`、`ANDROID_HOME` 后，在项目根目录运行：

```sh
./gradlew --no-daemon :app:assembleDebug
```

安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。源码包名为 `com.sogud.bananatv`。GitHub 的旧版 v0.1.11 不包含当前影视、直播等开发中功能。

## 安装到电视

让电脑和电视处于同一个可信网络，打开电视的网络调试并接受授权。用实际电视地址替换 `TV_IP`：

```sh
adb connect TV_IP:5555
adb -s TV_IP:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s TV_IP:5555 shell am start -n com.sogud.bananatv/.MainActivity
```

覆盖更新必须保持包名与签名一致。遇到签名不一致时找回原来的签名文件，避免通过卸载丢失数据。

## 配置内容

- **影视**：影视 → 设置 → 来源配置 → 扫码配置来源，手机填写 TVBox / CatVod JSON 配置地址。[完整接入步骤与格式说明](subscriptions.md#手机扫码接入)。
- **直播**：影视 → 直播 → 直播订阅，添加 M3U / TVBox TXT 地址。
- **主屏幕**：长按 OK 或按菜单键，整理应用与文件夹；控制中心 → 手机配置，可以从手机操作。
- **私有打包**：在根目录放置 `banana-tv.config.json`，之后编译会自动内置订阅。[生成配置文件](subscriptions.md)。

没有配置文件时，安装包不内置影视源、直播订阅或个人地址。

## 设置 Home

先确认应用运行正常，再设置为 Home。原生 Android TV 通常提供默认桌面选择器，部分厂商固件需要额外设置。安装 APK 本身不会接管 Home，也不会替换电视固件。

按 [安装与恢复](install.md) 操作，保存原桌面的恢复方式。不同固件需要分别验证开机、待机和实体遥控器 Home；不要直接套用其他机型的系统应用停用命令。
