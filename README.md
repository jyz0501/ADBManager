# ADBManager

车机（Android 系统）上的 ADB / 无线调试管理工具，附带 USB 供电控制、移动数据开关、开机自启等系统级能力。

## 功能

- **USB ADB 开关**：切换系统 ADB 及 `adbd` 服务
- **无线 ADB 开关**：5555 端口开启/关闭 TCP 调试
- **无线调试配对码**：生成 6 位配对码（Android 11+）
- **USB 供电控制**：控制 3 路 USB 供电节点
- **移动数据开关**
- **开机自启常驻**：开机自动拉起后台服务并恢复无线 ADB

> 本应用为 platform 签名的系统应用，需用目标设备 platform 私钥签名，通过 ROM 预置或 `adb install` 安装，无法上架应用商店。

## 构建

```bash
# 准备签名文件到 sign/ 目录：
#   sign/platform.pk8
#   sign/platform.x509.pem

bash ./build.sh
```

产物：`bin/apk/ADBManager_signed.apk`

依赖：Android SDK（`ANDROID_HOME`，build-tools 36 + platform 36）、JDK 11+。无需 Gradle / Android Studio。

## 安装

```bash
adb install -r bin/apk/ADBManager_signed.apk
```
