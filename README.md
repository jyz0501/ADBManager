# ADBManager

车机（Android 系统）上的 ADB / 无线调试管理工具，附带 USB 供电控制、移动数据开关、开机自启等系统级能力。

## 界面布局（三栏）

| 左栏 | 中栏 | 右栏 |
|---|---|---|
| USB ADB 开关 | 无线 ADB（状态 / IP:端口 / 开关） | 移动数据开关 |
| 主驾USB1 供电状态 | 无线调试开关 | USB 供电（主驾USB1 / 主驾USB2） |
| 检测更新 / 退出 | 无线调试配对（配对码 / 配对端口） | |

## 功能

- **USB ADB 开关**：切换系统 ADB 及 `adbd` 服务，启动时默认关闭（有活动连接则跳过）
- **无线 ADB 开关**：5555 端口开启/关闭 TCP 调试，异步执行不阻塞 UI，实时显示已连接客户端；**不记忆状态**，每次冷启动/开机一律回到关闭，需要时手动开启
- **无线调试开关**：控制系统「无线调试」（`adb_wifi_enabled`，Android 11+），开启后可配合配对码使用
- **无线调试配对码**：生成 6 位配对码（Android 11+）
- **USB 供电控制**：主驾USB1 / 主驾USB2 两路供电节点滑块控制，带过流（OC）状态反馈
- **主驾USB1 供电**：启动时默认开启；该口为 USB 调试端口，供电（VBUS 5V）与数据线独立，关闭供电不影响 ADB 调试
- **移动数据开关**
- **服务器更新检测（占位）**：从更新服务器拉取 `version.json` 检测新版本并自动下载安装（服务器地址暂为占位，见 `MainActivity.UPDATE_BASE_URL`）
- **开机自启常驻**：开机自动拉起后台服务监听 ADB 状态变化，并确保无线 ADB 处于关闭

## 下载

前往 [Releases](https://github.com/jyz0501/ADBManager/releases) 获取最新版本 APK（打 tag 后由 CI 自动构建发布）。

> 本应用为 platform 签名的系统应用，需用目标设备 platform 私钥签名，通过 ROM 预置或 `adb install` 安装，无法上架应用商店。

## 构建

```bash
bash ./build.sh
```

产物：`bin/apk/ADBManager_signed.apk`

依赖：Android SDK（`ANDROID_HOME`，build-tools 36 + platform 36）、JDK 11+。无需 Gradle / Android Studio。

## 安装

```bash
adb install -r bin/apk/ADBManager_signed.apk
```

## 版本历史

- **v1.6.1**（2026-09-15）：顶栏显示版本号（右侧小字 `v<versionName>`）
- **v1.6.0**（2026-09-15）：三栏布局重构；按钮全部改为滑块并修复拉伸变形；修复 USB 连接状态误报；主驾USB1 供电启动默认开启；移除 USB3.2 控制；无线 ADB 开关异步化即时响应
- v1.5.1：AdbService 后台监听、状态栏遮挡修复、APP 内置静默安装
- v1.4.0：ADB 默认关闭策略、横屏三栏 UI 初版
