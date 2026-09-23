# ADBManager

车机（Android 系统）上的 ADB / 无线调试管理工具，附带 USB 供电控制、开机自启等系统级能力。

## 界面布局（三栏）

| 左栏 | 中栏 | 右栏 |
|---|---|---|
| USB 调试开关 + 底层状态 | 配对设备 / 配对码 / 配对端口 / 二维码 | USB 供电（主驾USB1 / 主驾USB2） |
| 无线 ADB 开关 | 已配对设备 | 主驾USB1 角色（device / host） |
| 无线 ADB 状态（状态、IP:端口、无线客户端） | | （底部）检测更新 / 退出、作者信息 |

> 中栏与右栏各自独立上下滑动；三栏从上到下依次为：标题区 → 内容区（左：USB 调试 / 无线 ADB 开关；状态信息固定在左栏底部）→ 操作区。

## 功能

- **USB ADB 开关**：切换系统 ADB 及 `adbd` 服务，开机时默认关闭一次。默认只切 `sys.usb.config`（`adb` ↔ `none`）与 `adbd`，**不再把调试口切成 host**；左栏实时显示底层 `config` / `adbd` / 调试口角色，设置项与底层不一致时该行变橙
- **主驾USB1 角色（device / host）**：独立开关，带二次确认。切成 host 会让该口失去 device 能力（USB 调试与同口 CarLife/AOA 一起失效），日常无需使用
- **无线 ADB 开关**：开启/关闭系统无线调试（端口由 adbd 动态分配并 mDNS 广播，不再固定 5555），异步执行不阻塞 UI，实时显示已连接客户端
- **无线 ADB 后台保活**：开关的状态会被记住（仅记住「用户上次是否希望开启」）。用户手动开启后，若被系统重置（熄屏省电、`adbd` 被杀、ROM 清理属性），`AdbService` 会在 10 秒级检测到并自动重开；用户手动关闭则不会被拉起
- **撤销 USB 调试授权**：等同开发者选项里的同名按钮，通过 `IAdbManager.clearDebuggingKeys()` 清空系统 adb keystore；点按钮会先二次确认。执行后不重启 adbd（避免掐断正在调试的连接），被撤销的电脑下次连接时需重新确认授权
- **无线调试配对码**：生成 6 位配对码（Android 11+）
- **USB 供电控制**：主驾USB1 / 主驾USB2 两路供电节点滑块控制，带过流（OC）状态反馈
- **主驾USB1 供电**：启动时默认开启；该口为 USB 调试端口，供电（VBUS 5V）与数据线独立，关闭供电不影响 ADB 调试
- **服务器更新检测（占位）**：从更新服务器拉取 `version.json` 检测新版本并自动下载安装（服务器地址暂为占位，见 `MainActivity.UPDATE_BASE_URL`）
- **开机自启常驻**：开机通过前台服务拉起并监听 ADB 状态变化；开机时关闭一次 USB 调试（默认安全策略不变），此后不再反复关闭

## USB 硬件接口边界

车机上通常有多个 USB 控制器，开关 USB 调试**只碰调试口那一个**：

| 控制器 | 角色 | 对应 | 本应用是否操作 |
|---|---|---|---|
| 调试口（如 `a600000.ssusb`） | peripheral / device | 主驾USB1、USB 调试 | 是 |
| 其他口（如 `a800000.ssusb`） | host | U 盘、4G 模组等外设 | **否** |

- **控制器不写死**：启动时从 `/sys/class/udc` 探测，再按候选路径解析出 `mode` 节点，适配不同内核布局；写入后回读校验，失败会在界面上报。
- `sys.usb.config` 虽然是全局属性，但 gadget 只挂在调试口那个 UDC 上，所以重新枚举只发生在调试口；host 那一路的 U 盘 / 4G 模组不经过 gadget，不受影响。
- 关闭 USB 调试写 `none` 而不是 `mtp`：这类车机默认 composition 就是纯 `adb`，写 `mtp` 要么切换失败、要么反而把 MTP 暴露给电脑。
- 供电（`usb2power` / `usb31power`）与数据角色是两套独立节点，关供电不影响 ADB 调试。

### 底层动作分档

| 档位 | 动作 | 影响面 |
|---|---|---|
| `LEVEL_SILENT` | 只 `ctl.stop/start adbd` | 不重枚举，电脑侧仅 offline |
| `LEVEL_CONFIG`（默认） | + `sys.usb.config` `adb` ↔ `none` | 调试口重新枚举一次，其他口不受影响 |
| `LEVEL_ROLE` | + 控制器角色 `peripheral` ↔ `host` | 该口失去 device 能力，同口 CarLife/AOA 失效 |

### 设置项的写入顺序

部分 ROM（含这类车机）会监听 `Settings.Global.ADB_ENABLED` 并**自行重算** `sys.usb.config`。
实测在这台车机上：只要写入 `adb_enabled=0`，系统就立刻把 composition 切到 `none` 并停掉 `adbd`。

所以本应用**先切底层、最后再写设置项**。顺序反过来时，系统异步重算的结果会覆盖刚写入的 composition，导致开关结果与预期不符。

### 默认关闭策略（仅开机执行一次）

收到 `BOOT_COMPLETED` / `QUICKBOOT_POWERON` 时关闭一次 USB 调试，**之后不再重复关闭**——打开界面、进程被系统回收后重建、升级 APK，都不再动 USB 调试。

实现上由 `BootReceiver` 用 `goAsync()` 在后台线程执行，统一走 `AdbCtl.setUsbAdb()`（底层 + 设置项，设置项最后写）。

> 早先这段挂在 `MainActivity.onCreate` 上，导致**每次打开界面都把 USB 调试掐断一遍**，调试时得反复手动重开。

不给有线调试留豁免：开机时不判断是否有客户端在线。有线调试如需长期保留，请改用无线 ADB。

## 后台保活说明

`AdbService` 以前台服务常驻（常态通知 + `PARTIAL_WAKE_LOCK` + Wi-Fi 锁），职责：

1. 轮询 `Settings.Global.ADB_ENABLED` 与 `service.adb.tcp.port`，变化时提示；
2. **自动重开**：`AdbCtl.repairIfNeeded()` 每 10 秒检查一次（内部再做 15 秒节流），
   仅当「用户期望开启 且 没有客户端在线 且（属性被重置 / `adbd` 不在 / 端口没监听）」时才重启 adbd；
3. 网络从休眠恢复（Wi-Fi 重连）时立刻做一次修复检查。

> 有客户端在线时绝不重启 adbd，避免把正在使用的连接掐断。

## 下载

前往 [Releases](https://github.com/jyz0501/ADBManager/releases) 获取最新版本 APK（打 tag 后由 CI 自动构建发布）。

> 本应用为 platform 签名的系统应用，通过 ROM 预置或 `adb install` 安装，无法上架应用商店。

## 构建

```bash
bash ./build.sh
```

产物：`bin/apk/ADBManager_v<version>_<date>.apk`（如 `ADBManager_v1.7.0_20260921.apk`）

依赖：Android SDK（`ANDROID_HOME`，build-tools 36 + platform 36）、JDK 11+、可联网环境。无需 Gradle / Android Studio。

### 签名

构建时用 **AOSP 公开的 platform 测试密钥**（`CN=Android` / `android@android.com`）签名：

- 首次构建会自动从 AOSP 仓库下载并缓存到 `sign/`（该目录已在 `.gitignore` 中，不会入库）；后续构建直接复用；
- 想换源：设置环境变量 `AOSP_KEY_BASE`（备用源 `AOSP_KEY_BASE_MIRROR`）；
- 若目标 ROM 用的是**厂商私有 platform key**，把自己的 `platform.pk8` / `platform.x509.pem` 放进 `sign/` 即可覆盖——存在且非空时优先使用本地密钥。签名不匹配会导致 `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`。

## 安装

```bash
adb install -r "$(ls -t bin/apk/ADBManager_v*.apk | head -1)"
```

## 版本历史

- **v2.0.0**（2026-09-23）：USB 调试按硬件接口分档，默认不再把调试口切成 host（避免同口 CarLife/AOA 失效）；新增 `UsbHw` 动态探测控制器（不再硬编码 `a600000`）并回读校验；关闭时 `sys.usb.config` 由 `mtp` 改为 `none`；默认关闭改走与手动开关同一出口（`AdbCtl.setUsbAdb`），并**改为只在开机广播执行一次**（原先挂在 Activity 启动上，每次开界面都掐断 USB 调试）；设置项改为**最后写入**（避免 ROM 监听 `adb_enabled` 自行重算 composition 覆盖我们的写入）；左栏新增底层状态行；右栏新增「主驾USB1 角色」独立开关；切换无线 ADB 前若有线调试在用的会二次确认
- **v1.9.0**（2026-09-23）：新增「撤销 USB 调试授权」（左栏 USB 调试区下方，走系统 `IAdbManager.clearDebuggingKeys()`，带二次确认）
- **v1.8.0**（2026-09-23）：UI 重排（左栏：USB 调试 / 无线 ADB 开关 + 底部状态；右栏：USB 供电 + 底部检测更新/退出）；中栏精简为单纯配对功能并支持上下滑动；移除 USB 供电的状态描述文案；关闭全部 Toast 提示（`TOAST_ENABLED` 开关控制，可恢复）
- **v1.7.0**（2026-09-21）：包名改为 `com.qianxian.adbmanager`（旧包名需先卸载）；构建改用 AOSP 公开 platform 测试密钥并自动下载；清理冗余资源与死代码；修复 CI 产物路径失效
- **v1.6.3**（2026-09-21）：合并「无线 ADB」与「无线调试」两个开关（同为 `adb_wifi_enabled`），中栏只保留只读状态行
- **v1.6.2**（2026-09-21）：移除移动数据开关；右栏改为可滚动；构建产物名带版本号与日期
- **v1.6.1**（2026-09-15）：顶栏显示版本号（右侧小字 `v<versionName>`）
- **v1.6.0**（2026-09-15）：三栏布局重构；按钮全部改为滑块并修复拉伸变形；修复 USB 连接状态误报；主驾USB1 供电启动默认开启；移除 USB3.2 控制；无线 ADB 开关异步化即时响应
- v1.5.1：AdbService 后台监听、状态栏遮挡修复、APP 内置静默安装
- v1.4.0：ADB 默认关闭策略、横屏三栏 UI 初版
