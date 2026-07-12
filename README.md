# ADBManager

车机（Android 系统）上的 **ADB / 无线调试管理工具**，同时具备 USB 供电控制、移动数据开关、开机自启等系统级能力。

> 它是一个 **platform 签名的系统应用**（`sharedUserId=android.uid.system` + `persistent=true`），
> 因此能操作普通 App 无法触及的隐藏 API 与 sysfs 节点。**不能用普通方式安装或上架应用商店**，详见下文「重要说明」。

---

## 功能特性

| 功能 | 说明 |
|---|---|
| **USB ADB 开关** | 切换 `Settings.Global.ADB_ENABLED`，并联动 `sys.usb.config` / `adbd` 服务、dwc3 控制器模式 |
| **无线 ADB 开关** | 通过 `service.adb.tcp.port` 属性在 5555 端口开启/关闭 `adbd` 的 TCP 监听 |
| **无线调试配对码** | 反射调用系统隐藏 `IAdbManager.enablePairingByPairingCode()`，监听 `WIRELESS_DEBUG_STATUS` 广播获取系统生成的 6 位配对码与端口（需 Android 11+ 且已开开发者选项/无线调试） |
| **USB 供电控制** | 控制 3 路 USB 供电节点 `/sys/devices/platform/usbpower/{usb2power,usb31power,usb32power}`，写入后读回实际值做实时反馈 |
| **移动数据开关** | 写 `Settings.Global.mobile_data` |
| **开机自启 / 常驻** | `BootReceiver` 监听 `BOOT_COMPLETED` 拉起 `AdbService`；`AdbService` 返回 `START_STICKY`，结合 `persistent=true` 强常驻；可按保存的偏好自动恢复无线 ADB |
| **系统浮层能力** | 已声明 `SYSTEM_ALERT_WINDOW`，platform 签名下自动授权，可用于系统级浮层（遮挡类用途） |

- 包名：`com.vendor.adbmanager`
- 版本：`1.4.0`（versionCode 14）
- 最低 / 目标 SDK：29 / 33

---

## 重要说明（必读）

1. **它必须是系统应用**：`AndroidManifest.xml` 含 `android:sharedUserId="android.uid.system"`，
   只能用**目标设备的 platform 私钥**签名才能安装，否则签名不匹配、安装失败。
2. **无法上架公开应用商店**：第三方市场（沙发市场、蜂鸟等）只能装出普通用户 App，
   拿不到 system UID，所有核心功能失效。正确分发方式是 **ROM 预置**或 OEM 白名单集成。
3. **本机实测分区只读**：production 构建下 `/system` 受 dm-verity 保护为只读，
   `adb root` / `remount` 均不可用，运行时无法把 APK 写入 `/system/priv-app`。
   但本应用即便装在 `/data/app`，因 platform 签名 + `sharedUserId`，PackageManager 仍会将其提为 system UID、授予特权——
   即在同密钥 ROM 家族设备上 `adb install` 即可获得完整系统能力。

---

## 环境要求（本地构建）

- **Android SDK**：`ANDROID_HOME` 指向 SDK 根目录
  - `build-tools/36.0.0`（含 `aapt`、`d8`、`zipalign`）
  - `platforms/android-36`（含 `android.jar`）
  - 两者版本可用环境变量 `BUILD_TOOLS_VERSION` / `COMPILE_SDK_VERSION` 覆盖
- **JDK 11+**（`javac` 用于编译，`apksigner` 为 jar 运行）
- **platform 签名密钥**：放在仓库根 `sign/` 目录（见「安全与密钥管理」）

无需 Gradle / Android Studio，全程使用 AOSP 命令行工具。

---

## 本地构建

```bash
# 1. 准备 sign/ 目录（私钥不入库，本地保管）
#    sign/platform.pk8
#    sign/platform.x509.pem

# 2. 执行构建脚本
bash ./build.sh
```

产物：

```
bin/apk/ADBManager_signed.apk      # 仅保留最终签名包（V1+V2 签名）
```

如需自定义 SDK 路径/版本：

```bash
ANDROID_HOME=/your/sdk BUILD_TOOLS_VERSION=36.0.0 COMPILE_SDK_VERSION=36 \
  JAVAC=/usr/bin/javac bash ./build.sh
```

构建流程（`build.sh` 内部）：生成 `R.java` → `javac` 编译 → `d8` 转 dex →
`aapt` 打包并加入 dex → `zipalign` 对齐 → `apksigner` 用 platform 密钥签名（V1+V2）→ 清理中间产物。

---

## CI 自动构建（GitHub Actions）

仓库已配置 `.github/workflows/build.yml`：推送 `main` 或手动 `Run workflow` 即触发。
它在 Ubuntu 上自动安装 JDK、Android SDK，并从 **加密 Secrets** 还原 platform 私钥后签名，
最后把 `ADBManager_signed.apk` 作为 **Artifacts** 产出——协作者无需本地持有私钥即可拿到签名包。

### 配置 Secrets（一次性）

在仓库 `Settings → Secrets and variables → Actions` 新建两个 **Repository secret**，值为本地私钥的 base64：

```bash
base64 -i sign/platform.pk8       # 复制整行 -> 变量名 PLATFORM_PK8_B64
base64 -i sign/platform.x509.pem   # 复制整行 -> 变量名 PLATFORM_PEM_B64
```

> 变量名必须一字不差（含下划线）。粘贴时确保首尾无多余空格、无换行。

配置后，到 `Actions → Build Signed ADBManager → Run workflow` 触发，跑完在 Artifacts 下载 `ADBManager_signed`。

---

## 安装到设备

```bash
adb install -r bin/apk/ADBManager_signed.apk
```

安装后建议重启（或等待 `BOOT_COMPLETED`）以确保 `AdbService` 常驻生效。
验证系统身份与权限：

```bash
adb shell dumpsys package com.vendor.adbmanager | grep -iE 'uid=|SYSTEM_ALERT_WINDOW|sharedUser'
```

---

## 目录结构

```
ADBManager/
├── app/src/main/
│   ├── AndroidManifest.xml            # 系统应用声明 + 权限
│   ├── java/com/vendor/adbmanager/
│   │   ├── MainActivity.java          # 主界面：ADB/无线/配对/USB供电/移动数据
│   │   ├── WirelessPairingHelper.java # 反射 IAdbManager 生成无线调试配对码
│   │   ├── AdbService.java            # 常驻后台服务，开机恢复无线 ADB
│   │   └── BootReceiver.java          # 开机广播拉起服务
│   └── res/                           # 布局 / 字符串 / 图标
├── sign/                              # platform 私钥（不入库，仅本地 + CI Secrets）
├── tools/apksigner.jar                # 签名工具（已入库）
├── build.sh                           # 纯 SDK 命令行构建脚本
└── .github/workflows/build.yml        # CI 签名构建
```

---

## 安全与密钥管理

- `sign/` 目录（含 `platform.pk8` 私钥）**已被 `.gitignore` 忽略，绝不入库**；
  它只存在于你的本地机器与 GitHub 加密 Secrets 中。
- **私钥即系统信任根**：任何拿到 `platform.pk8` 的人都能签出同 ROM 家族的任意系统级 App。
  务必单独备份（U 盘 / 密码管理器），丢失将无法签出兼容本车 ROM 的更新包。
- 反编译 `ADBManager_signed.apk` **只能得到代码与公钥证书，无法还原私钥**；但代码逻辑会暴露，
  请勿将 APK 随意分发给不可信方。
- 切勿把 `sign/` 加进版本库，也不要在 issue / PR 中粘贴私钥内容。

---

## 已知限制

- 无线调试配对码依赖系统 `IAdbManager` 隐藏接口与 `WIRELESS_DEBUG_STATUS` 广播，
  部分 ROM 接口名/广播行为可能不同，会提示"调用系统配对服务失败"。
- USB 供电节点路径 `/sys/devices/platform/usbpower/*` 为特定硬件平台节点，不同车机可能不存在或差异。
- 仅 V1+V2 签名（无 V3/V4 密钥轮转）；该 APK 不能被普通安装器当作第三方 App 分发。
