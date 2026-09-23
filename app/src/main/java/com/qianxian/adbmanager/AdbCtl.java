package com.qianxian.adbmanager;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 无线 ADB 的公共控制入口：Service 与 Activity 共用同一份实现。
 *
 * 之前 `AdbService` 和 `MainActivity` 各写了一份 `setWirelessAdb`，行为还不完全一致，
 * 容易出现「界面开了、后台又给关掉」的互相打架。这里统一：
 *
 * - `setWirelessDesired()` 记录**用户的开启意愿**（持久化）；
 * - `setWirelessAdb()` 真正改属性并重启 adbd；
 * - `repair()` 由后台轮询调用：只有在「用户想开、但实际没监听」时才修复，
 *   且**有客户端在线时绝不重启 adbd**，避免把正在用的连接掐断。
 */
public final class AdbCtl {

    /** 属性里的「关闭」值。无线 ADB 已不再使用固定 5555，端口由 adbd 动态分配。 */
    private static final String PORT_OFF = "-1";

    /** 端口下发后等待 adbd 重新上报的轮次（每轮 300ms）。 */
    private static final int PORT_WAIT_ROUNDS = 20;

    private static final String TAG = "AdbManager.Ctl";
    private static final String PREF = "adbmanager_state";
    private static final String KEY_WIRELESS_DESIRED = "wireless_desired";

    /** 两次自动修复之间的最小间隔：adbd 重启有抖动，频繁重启会把连接反复打断。 */
    private static final long MIN_REPAIR_INTERVAL_MS = 15_000L;

    private static long lastRepairMs = 0L;

    private AdbCtl() {
    }

    // ---------- 开启意愿 ----------

    /** 用户是否希望无线 ADB 处于开启状态（界面开关闭时写入）。 */
    public static boolean isWirelessDesired(Context ctx) {
        return prefs(ctx).getBoolean(KEY_WIRELESS_DESIRED, false);
    }

    public static void setWirelessDesired(Context ctx, boolean desired) {
        prefs(ctx).edit().putBoolean(KEY_WIRELESS_DESIRED, desired).apply();
        // 立刻允许下一次修复生效（用户主动点了开关，不用等间隔）
        lastRepairMs = 0L;
    }

    // ---------- 属性读写 ----------

    /** `service.adb.tcp.port` 当前值：-1/空/0 表示未开启（保留给明文模式读取）。 */
    public static String wirelessPortProp() {
        String port = readProp("service.adb.tcp.port");
        return port == null ? "" : port.trim();
    }

    /** 无线调试（TLS）动态端口，即 adbd 随机分配并广播的那个端口。 */
    public static String tlsPortProp() {
        String port = readProp("service.adb.tls.port");
        return port == null ? "" : port.trim();
    }

    /**
     * 当前真正对外监听的端口：优先无线调试的动态端口，其次明文端口；都没有返回空串。
     * 不再固定 5555，所以任何显示/统计都必须从这里取。
     */
    public static String effectivePort() {
        String tls = tlsPortProp();
        if (isPortEnabled(tls)) return tls;
        return wirelessPortProp();
    }

    /** 无线 ADB 是否真的处于开启状态（系统无线调试已开且端口已分配）。 */
    public static boolean isWirelessOn(Context ctx) {
        if (!isPortEnabled(effectivePort())) return false;
        return adbWifiEnabled(ctx) || isPortEnabled(wirelessPortProp());
    }

    private static boolean adbWifiEnabled(Context ctx) {
        try {
            return android.provider.Settings.Global.getInt(
                    ctx.getContentResolver(), "adb_wifi_enabled", 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /** 属性值是否代表「端口已开启」。 */
    public static boolean isPortEnabled(String port) {
        return port != null && !port.isEmpty() && !port.equals(PORT_OFF) && !port.equals("0");
    }

    /** adbd 进程是否还活着；查不到进程列表时保守返回 true（宁肯不重启）。 */
    public static boolean isAdbdAlive() {
        List<String> out = shell("pidof", "adbd");
        if (out.isEmpty()) return true;
        String joined = String.join(" ", out).trim();
        // pidof 找不到时通常输出空串或 "1"（pidof 自身的退出行为）
        return !joined.isEmpty() && !joined.equals("1");
    }

    public static void startAdbd() {
        exec("setprop", "ctl.start", "adbd");
    }

    /**
     * 设置/关闭无线 ADB。
     *
     * 开启走系统「无线调试」（`adb_wifi_enabled=1`），端口由 adbd 随机分配并通过 mDNS 广播，
     * 不再写死 5555；同时把明文端口置为 -1，避免留下一个无需配对的明文入口。
     *
     * @return 开启后的实际端口；关闭或尚未分配到端口时返回空串
     */
    public static String setWirelessAdb(Context ctx, boolean enabled) {
        Log.i(TAG, "设置无线ADB: enabled=" + enabled);
        try {
            android.provider.Settings.Global.putInt(
                    ctx.getContentResolver(), "adb_wifi_enabled", enabled ? 1 : 0);
        } catch (Exception e) {
            Log.w(TAG, "切换 adb_wifi_enabled 失败，回退到属性方式", e);
        }
        exec("setprop", "service.adb.tcp.port", PORT_OFF);
        restartAdbd();
        if (!enabled) return "";
        return waitForPort();
    }

    /** 重启 adbd 让端口/开关生效。 */
    private static void restartAdbd() {
        exec("setprop", "ctl.stop", "adbd");
        sleep(500);
        exec("setprop", "ctl.start", "adbd");
        sleep(1000);
    }

    /** 等 adbd 把新端口写回属性（动态端口要等 adbd 起来才有）。 */
    private static String waitForPort() {
        for (int i = 0; i < PORT_WAIT_ROUNDS; i++) {
            String port = effectivePort();
            if (isPortEnabled(port)) {
                Log.i(TAG, "无线ADB端口已就绪: " + port);
                return port;
            }
            sleep(300);
        }
        Log.w(TAG, "等待无线ADB端口超时");
        return "";
    }

    // ---------- 自动修复 ----------

    /**
     * 后台轮询调用：在用户期望开启、但无线 ADB 实际已失效时把它拉回来。
     *
     * 判定顺序刻意保守：
     * 1. 没有开启意愿 → 什么都不做；
     * 2. 有客户端在线 → 什么都不做（不能重启 adbd，否则正在用的连接会断）；
     * 3. 属性被重置、或 adbd 进程没了、或端口没在监听 → 修复。
     *
     * @return 是否执行了修复动作
     */
    public static synchronized boolean repairIfNeeded(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - lastRepairMs < MIN_REPAIR_INTERVAL_MS) return false;

        String port = effectivePort();
        boolean adbdAlive = isAdbdAlive();
        boolean healthy = isWirelessOn(ctx) && isPortEnabled(port) && adbdAlive;

        if (healthy) return false;

        lastRepairMs = now;
        Log.w(TAG, String.format(
                "检测到无线ADB失效(port=%s, adbdAlive=%s)，正在自动重启无线ADB", port, adbdAlive));
        setWirelessAdb(ctx, true);
        return true;
    }

    // ---------- 授权管理 ----------

    /**
     * 撤销所有已授权的 USB 调试密钥，等价于开发者选项里的「撤销 USB 调试授权」。
     *
     * 走 `IAdbManager.clearDebuggingKeys()`（由 system_server 操作 adb keystore），
     * 而不是自己删 `/data/misc/adb/adb_keys`——后者会被 SELinux 拦掉，
     * 也不会触发系统侧的 keyStore 重新加载。<｜hy_place▁holder▁no▁813｜>
     * 注意：这里故意不重启 adbd，
     * 否则会掐断当前正在调试的连接；被撤销的电脑下次连接时需要重新确认授权。
     *
     * @return null 表示成功；否则返回失败原因
     */
    public static String revokeUsbDebuggingKeys() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "adb");
            if (binder == null) return "无法获取 adb 系统服务";

            Class<?> stub = Class.forName("android.debug.IAdbManager$Stub");
            Object svc = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            if (svc == null) return "无法获取 IAdbManager";

            Method clear = svc.getClass().getMethod("clearDebuggingKeys");
            clear.invoke(svc);
            Log.i(TAG, "已清空 USB 调试授权(adb_keys)");
            return null;
        } catch (NoSuchMethodException e) {
            return "系统不支持该接口(需 Android 11+)";
        } catch (Exception e) {
            Log.e(TAG, "clearDebuggingKeys 失败", e);
            return "调用失败: " + e.getMessage();
        }
    }

    // ---------- 有线 USB 调试（分档，只动调试口那一个控制器） ----------

    /** 只启停 adbd：不动 composition、不动控制器角色，调试口不会重新枚举。 */
    public static final int LEVEL_SILENT = 0;

    /** 在 SILENT 之上再切 sys.usb.config（adb ↔ none）：调试口会重新枚举一次。 */
    public static final int LEVEL_CONFIG = 1;

    /**
     * 在 CONFIG 之上再切控制器角色（peripheral ↔ host）。
     * 该口会整体失去 device 能力，同口的 CarLife/AOA 一样会失效，仅确有需要时使用。
     */
    public static final int LEVEL_ROLE = 2;

    /** 默认档位：动到 composition 为止，不再切控制器角色。 */
    public static final int LEVEL_DEFAULT = LEVEL_CONFIG;

    /**
     * 关闭时写回 sys.usb.config 的值。
     * 不用 mtp：这台车机 persist 就是纯 adb、没有 MTP function，
     * 写 mtp 要么切换失败、要么反而把一个 MTP 设备暴露给电脑。
     */
    public static final String OFF_CONFIG_VALUE = "none";

    /** 记住调试口是哪个控制器：一旦被切成 host 就认不出来了，靠这个不认错口。 */
    private static volatile String debugPortBase;

    public static String sysUsbConfig() {
        return readProp("sys.usb.config");
    }

    public static String sysUsbState() {
        return readProp("sys.usb.state");
    }

    /** adbd 服务是否在跑。 */
    public static boolean isAdbServiceRunning() {
        return "running".equals(readProp("init.svc.adbd"));
    }

    /**
     * 有线 USB 调试是否真的可用：adbd 在跑且当前 composition 含 adb。
     * 用来判断「现在重启 adbd 会不会掐断一条正在用的有线连接」——
     * 无线客户端能靠 netstat 查到，有线客户端查不到，只能这样保守判定。
     */
    public static boolean isUsbAdbActive() {
        return isAdbServiceRunning() && sysUsbState().contains("adb");
    }

    /**
     * 开启时写回 sys.usb.config 的值：以 persist 为准并补上 adb，
     * 免得把厂商默认带的其他 function 抹掉。
     */
    public static String onConfigValue() {
        String persist = readProp("persist.sys.usb.config");
        if (persist == null) persist = "";
        persist = persist.trim();
        if (persist.isEmpty() || persist.equals("none")) return "adb";
        if (!persist.contains("adb")) return persist + ",adb";
        return persist;
    }

    /** 调试口对应的控制器；探测不到返回 null（此时只能退化为 LEVEL_SILENT）。 */
    public static UsbHw.Controller debugPort() {
        if (debugPortBase != null) {
            for (UsbHw.Controller c : UsbHw.controllers()) {
                if (debugPortBase.equals(c.base)) return c;
            }
        }
        UsbHw.Controller c = UsbHw.guessDebugPort();
        if (c != null) debugPortBase = c.base;
        return c;
    }

    /**
     * 应用有线 USB 调试开关。
     *
     * 无论哪一档，都只会碰调试口那一个控制器：其他口（host 那一路的 U 盘 / 4G 模组）
     * 不经过 gadget，也不会被写角色节点，因此不受影响。
     *
     * @param level LEVEL_SILENT / LEVEL_CONFIG / LEVEL_ROLE
     * @return null 表示成功；否则返回失败原因
     */
    public static String applyUsbAdb(boolean enabled, int level) {
        StringBuilder warn = new StringBuilder();
        UsbHw.Controller port = debugPort();

        if (level >= LEVEL_ROLE && port != null) {
            String target = enabled ? UsbHw.MODE_PERIPHERAL : UsbHw.MODE_HOST;
            if (!UsbHw.setMode(port, target)) {
                warn.append("控制器角色未生效(").append(port.base).append(") ");
            }
        } else if (enabled && port != null && port.isHost()) {
            // 开启时控制器还停在 host，切 composition 不会生效，先拉回 peripheral
            if (!UsbHw.setMode(port, UsbHw.MODE_PERIPHERAL)) {
                warn.append("控制器仍在 host，无法开启调试 ");
            }
        }

        if (level >= LEVEL_CONFIG) {
            String cfg = enabled ? onConfigValue() : OFF_CONFIG_VALUE;
            exec("setprop", "sys.usb.config", cfg);
            sleep(300);
        }

        exec("setprop", enabled ? "ctl.start" : "ctl.stop", "adbd");
        sleep(600);

        boolean adbOk = isAdbServiceRunning() == enabled;
        Log.i(TAG, String.format("applyUsbAdb enabled=%s level=%d port=%s config=%s state=%s adbdRunning=%s",
                enabled, level, port, sysUsbConfig(), sysUsbState(), isAdbServiceRunning()));
        if (!adbOk) {
            warn.append("adbd 未").append(enabled ? "启动" : "停止");
        }

        String w = warn.toString().trim();
        return w.isEmpty() ? null : w;
    }

    /**
     * 有线 USB 调试的**唯一下发出口**：底层（composition / adbd / 必要的控制器角色）+ 设置项。
     * 手动开关与开机默认策略都走这里，避免「设置里显示关闭、底层 adbd 还在跑」的假关闭。
     *
     * 设置项放到最后写：不少 ROM（含这类车机）会监听 ADB_ENABLED 自行重算 sys.usb.config，
     * 先写设置项会触发它异步重算并可能覆盖我们刚写入的 composition；
     * 放最后写，它重算出来的结果与我们的目标一致，不会打架。
     *
     * @return null 表示成功；否则为失败原因（不阻断流程，仅提示）
     */
    public static String setUsbAdb(Context ctx, boolean enabled) {
        String err = applyUsbAdb(enabled, LEVEL_DEFAULT);
        try {
            android.provider.Settings.Global.putInt(
                    ctx.getContentResolver(), android.provider.Settings.Global.ADB_ENABLED,
                    enabled ? 1 : 0);
        } catch (Exception e) {
            Log.e(TAG, "写入 ADB_ENABLED 失败", e);
        }
        return err;
    }

    /** 只切调试口角色（peripheral ↔ host），供右栏独立开关使用。 */
    public static String setDebugPortRole(boolean peripheral) {
        UsbHw.Controller port = debugPort();
        if (port == null) return "未探测到 USB 控制器";
        String target = peripheral ? UsbHw.MODE_PERIPHERAL : UsbHw.MODE_HOST;
        if (UsbHw.setMode(port, target)) return null;
        return "角色切换未生效（当前 " + (port.mode.isEmpty() ? "未知" : port.mode) + "）";
    }

    // ---------- 通知（前台服务用） ----------

    public static final String CHANNEL_ID = "adb_keepalive";
    public static final int NOTI_ID = 4171;

    /** Android 8+ 必须先建渠道，否则通知不显示。这里直接用框架 API（minSdk 29）。 */
    public static void createChannel(Context ctx) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    CHANNEL_ID, ctx.getString(R.string.adb_channel_name),
                    android.app.NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(ctx.getString(R.string.adb_channel_desc));
            nm.createNotificationChannel(channel);
        } catch (Exception e) {
            Log.w(TAG, "创建通知渠道失败", e);
        }
    }

    public static Notification buildNotification(Context ctx, String text, String detail) {
        Intent intent = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = ctx.getString(R.string.adb_noti_title);
        String body = text;
        if (detail != null && !detail.isEmpty()) body = detail;
        return new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_adb)
                .setContentTitle(title)
                .setContentText(body)
                .setSubText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    // ---------- 内部工具 ----------

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int exec(String... args) {
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                Log.d(TAG, "命令输出: " + line);
            }
            return p.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "执行命令失败: " + java.util.Arrays.toString(args), e);
            return -1;
        }
    }

    /** 执行并把输出按行收回。 */
    private static List<String> shell(String... args) {
        List<String> lines = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
            p.waitFor();
        } catch (Exception e) {
            Log.w(TAG, "读取命令输出失败: " + java.util.Arrays.toString(args), e);
        }
        return lines;
    }

    private static String readProp(String key) {
        List<String> out = shell("getprop", key);
        return out.isEmpty() ? "" : out.get(0).trim();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
