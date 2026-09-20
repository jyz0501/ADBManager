# ADBManager

车机（Android 系统）上的 ADB / 无线调试管理工具，附带 USB 供电控制、开机自启等系统级能力。

## 界面布局（三栏）

| 左栏 | 中栏 | 右栏 |
|---|---|---|
| 无线 ADB 开关与信息 | 无线调试开关与状态 | 检测更新 / 退出 |
| | 配对端口 / 配对码 / 二维码 | USB 调试状态 |
| | 已配对设备 | USB 供电（主驾USB1 / 主驾USB2） / 作者信息 |

## 功能

- **USB ADB 开关**：切换系统 ADB 及 `adbd` 服务，启动时默认关闭（有活动连接则跳过）
- **无线 ADB 开关**：开启/关闭系统无线调试（端口由 adbd 动态分配并 mDNS 广播，不再固定 5555），异步执行不阻塞 UI，实时显示已连接客户端
- **无线 ADB 后台保活**：开关的状态会被记住（仅记住「用户上次是否希望开启」）。用户手动开启后，若被系统重置（熄屏省电、`adbd` 被杀、ROM 清理属性），`AdbService` 会在 10 秒级检测到并自动重开；用户手动关闭则不会被拉起
- **无线调试开关**：控制系统「无线调试」（`adb_wifi_enabled`，Android 11+），开启后可配合配对码使用
- **无线调试配对码**：生成 6 位配对码（Android 11+）
- **USB 供电控制**：主驾USB1 / 主驾USB2 两路供电节点滑块控制，带过流（OC）状态反馈
- **主驾USB1 供电**：启动时默认开启；该口为 USB 调试端口，供电（VBUS 5V）与数据线独立，关闭供电不影响 ADB 调试
- **服务器更新检测（占位）**：从更新服务器拉取 `version.json` 检测新版本并自动下载安装（服务器地址暂为占位，见 `MainActivity.UPDATE_BASE_URL`）
- **开机自启常驻**：开机通过前台服务拉起并监听 ADB 状态变化；用户未开启过无线 ADB 时，冷启动一律保持关闭（默认安全策略不变）

## 后台保活说明

`AdbService` 以前台服务常驻（常态通知 + `PARTIAL_WAKE_LOCK` + Wi-Fi 锁），职责：

1. 轮询 `Settings.Global.ADB_ENABLED` 与 `service.adb.tcp.port`，变化时提示；
2. **自动重开**：`AdbCtl.repairIfNeeded()` 每 10 秒检查一次（内部再做 15 秒节流），
   仅当「用户期望开启 且 没有客户端在线 且（属性被重置 / `adbd` 不在 / 端口没监听）」时才重启 adbd；
3. 网络从休眠恢复（Wi-Fi 重连）时立刻做一次修复检查。

> 有客户端在线时绝不重启 adbd，避免把正在使用的连接掐断。

## 下载

前往 [Releases](https://github.com/jyz0501/ADBManager/releases) 获取最新版本 APK（打 tag 后由 CI 自动构建发布）。

> 本应用为 platform 签名的系统应用，需用目标设备 platform 私钥签名，通过 ROM 预置或 `adb install` 安装，无法上架应用商店。

## 构建

```bash
bash ./build.sh
```

产物：`bin/apk/ADBManager_v<version>_<date>.apk`（如 `ADBManager_v1.6.2_20260921.apk`）

依赖：Android SDK（`ANDROID_HOME`，build-tools 36 + platform 36）、JDK 11+。无需 Gradle / Android Studio。

## 安装

```bash
adb install -r "$(ls -t bin/apk/ADBManager_v*.apk | head -1)"
```

## 版本历史

- **v1.6.2**（2026-09-21）：移除移动数据开关；右栏改为可滚动；构建产物名带版本号与日期
- **v1.6.1**（2026-09-15）：顶栏显示版本号（右侧小字 `v<versionName>`）
- **v1.6.0**（2026-09-15）：三栏布局重构；按钮全部改为滑块并修复拉伸变形；修复 USB 连接状态误报；主驾USB1 供电启动默认开启；移除 USB3.2 控制；无线 ADB 开关异步化即时响应
- v1.5.1：AdbService 后台监听、状态栏遮挡修复、APP 内置静默安装
- v1.4.0：ADB 默认关闭策略、横屏三栏 UI 初版
