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

    /** composition 下发后等它真正生效的轮次（每轮 200ms，约 3 秒），对齐 UsbHw.setMode 的回读校验。 */
    private static final int CONFIG_SETTLE_ROUNDS = 15;

    private static final String TAG = "AdbManager.Ctl";
    private static final String PREF = "adbmanager_state";
    private static final String KEY_WIRELESS_DESIRED = "wireless_desired";

    /** 两次自动修复之间的最小间隔：adbd 重启有抖动，频繁重启会把连接反复打断。 */
    private static final long MIN_REPAIR_INTERVAL_MS = 15_000L;

    /** netstat 不可用时的回退来源（toybox 精简的 ROM 上 netstat 可能不存在）。 */
    private static final String PROC_NET_TCP = "/proc/net/tcp";

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

    // ---------- 无线客户端（修复判定与界面共用同一套解析） ----------

    /** adbd 当前监听的端口集合（无线调试的动态 TLS 端口 + 明文端口）。 */
    public static java.util.Set<String> adbdPorts() {
        java.util.Set<String> ports = new java.util.HashSet<>();
        for (String p : new String[]{tlsPortProp(), wirelessPortProp()}) {
            if (isPortEnabled(p)) ports.add(p);
        }
        return ports;
    }

    /**
     * 当前连在 adbd 上的客户端 IP。
     *
     * 优先 `netstat -tn`；部分 ROM（含部分车机）的 toybox 不带 netstat，
     * 此时回退解析 `/proc/net/tcp`（state=01 即 ESTABLISHED）。
     */
    public static java.util.List<String> activeClients() {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.Set<String> ports = adbdPorts();
        if (ports.isEmpty()) return out;

        List<String> lines = shell("netstat", "-tn");
        if (lines.isEmpty()) {
            lines = readLines(PROC_NET_TCP);
        } else {
            for (String line : lines) {
                if (!line.contains("ESTABLISHED")) continue;
                String[] f = line.trim().split("\\s+");
                if (f.length < 5) continue;
                collectClient(f[3], f[4], ports, out);
            }
            return out;
        }

        for (String line : lines) {
            // /proc/net/tcp 格式： sl local_address rem_address st ...
            String[] f = line.trim().split("\\s+");
            if (f.length < 4 || !"01".equals(f[3])) continue;
            collectClient(hexToIpPort(f[1]), hexToIpPort(f[2]), ports, out);
        }
        return out;
    }

    /** 是否已有客户端连在 adbd 上（有则不能重启 adbd，否则会掐断连接）。 */
    public static boolean hasActiveClients() {
        return !activeClients().isEmpty();
    }

    /** 从 "ip:port" 里取端口，命中 adbd 端口就把对端 IP 记下来。 */
    private static void collectClient(String local, String peer,
                                      java.util.Set<String> ports, java.util.List<String> out) {
        if (local == null || peer == null) return;
        int li = local.lastIndexOf(':');
        int pi = peer.lastIndexOf(':');
        if (li < 0 || pi < 0) return;
        String localPort = local.substring(li + 1);
        if (!ports.contains(localPort)) return;
        String peerIp = peer.substring(0, pi);
        // 去掉 IPv6 映射前缀，便于阅读
        if (peerIp.startsWith("::ffff:")) peerIp = peerIp.substring(7);
        if (!peerIp.equals("127.0.0.1") && !out.contains(peerIp)) out.add(peerIp);
    }

    /** /proc/net/tcp 里的 "010310AC:8AE0" → "1.3.16.172:35552"。 */
    private static String hexToIpPort(String hex) {
        if (hex == null) return "";
        int i = hex.indexOf(':');
        if (i < 0) return "";
        try {
            long ip = Long.parseLong(hex.substring(0, i), 16);
            int port = Integer.parseInt(hex.substring(i + 1), 16);
            // 地址在文件里是小端序
            return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF)
                    + "." + ((ip >> 24) & 0xFF) + ":" + port;
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static List<String> readLines(String path) {
        List<String> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) out.add(line);
        } catch (Exception e) {
            Log.w(TAG, "读取失败: " + path + " → " + e.getMessage());
        }
        return out;
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

        // USB 与无线是同一个 adbd 上的两个独立 transport，本来就不该互相牵连。
        // 先热切换：只让 adbd 自己开/关 TLS 监听，进程不动，有线 USB 调试不受影响。
        if (!applyWirelessHot(enabled)) {
            // 有些 ROM 只在 adbd 启动时才决定要不要监听无线，热切换不生效，重启兜底。
            Log.w(TAG, "热切换未生效，重启 adbd 兜底（会掐断有线调试连接）");
            restartAdbd();
        }
        if (!enabled) return "";
        return waitForPort();
    }

    /** 热切换：改完设置项后等 adbd 自己把监听/端口切过来，全程不碰 adbd 进程。 */
    private static boolean applyWirelessHot(boolean enabled) {
        for (int i = 0; i < PORT_WAIT_ROUNDS; i++) {
            if (isPortEnabled(effectivePort()) == enabled) return true;
            sleep(300);
        }
        return isPortEnabled(effectivePort()) == enabled;
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

    /**
     * 等 composition 真正生效：生效值里是否已带上/摘掉 adb。
     *
     * 切换 composition 会触发调试口重新枚举，不是写完属性就立刻生效，所以要回读确认。
     *
     * @param wantAdb true=等「含 adb」（开启），false=等「不含 adb」（关闭）
     */
    private static boolean waitForUsbState(boolean wantAdb) {
        for (int i = 0; i < CONFIG_SETTLE_ROUNDS; i++) {
            if (hasAdbFunction(effectiveUsbConfig()) == wantAdb) return true;
            sleep(200);
        }
        return hasAdbFunction(effectiveUsbConfig()) == wantAdb;
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

        // 有客户端在线就绝不动 adbd：修复动作会重启 adbd，正在用的连接会被掐断。
        // 不依赖「端口还在监听」间接判断——属性被重置但连接还没断时也可能走到这里。
        if (hasActiveClients()) {
            Log.i(TAG, "有客户端在线，跳过自动修复（避免掐断正在使用的连接）");
            return false;
        }

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
     * 也不会触发系统侧的 keyStore 重新加载。
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

    /** 取 adb 系统服务的 Binder 代理；拿不到返回 null（缺少签名/ROM 不支持）。 */
    private static Object adbService() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "adb");
            if (binder == null) return null;
            Class<?> stub = Class.forName("android.debug.IAdbManager$Stub");
            return stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        } catch (Exception e) {
            Log.w(TAG, "获取 adb 系统服务失败: " + e.getMessage());
            return null;
        }
    }

    /** 系统侧已配对设备的凭证 key 列表（开发者选项里那份）；读取失败返回空列表。 */
    public static java.util.List<String> pairedDeviceKeys() {
        java.util.List<String> keys = new java.util.ArrayList<>();
        try {
            Object svc = adbService();
            if (svc == null) return keys;
            Object res = svc.getClass().getMethod("getPairedDevices").invoke(svc);
            if (!(res instanceof java.util.Map)) return keys;
            for (Object k : ((java.util.Map<?, ?>) res).keySet()) {
                if (k != null) keys.add(String.valueOf(k));
            }
        } catch (Exception e) {
            Log.w(TAG, "读取系统已配对设备失败: " + e.getMessage());
        }
        return keys;
    }

    /**
     * 忘记（取消配对）某台设备：删除**系统侧**的配对凭证，等价于开发者选项里的「忘记」。
     * 只删本地显示记录的话，电脑那边凭证还在、照样能连上，属于假解绑。
     *
     * @return null 表示成功；否则返回失败原因
     */
    public static String unpairDevice(String key) {
        if (key == null || key.isEmpty()) return "缺少配对凭证 key";
        try {
            Object svc = adbService();
            if (svc == null) return "无法获取 adb 系统服务";
            svc.getClass().getMethod("unpairDevice", String.class).invoke(svc, key);
            Log.i(TAG, "已删除系统侧配对凭证: " + key);
            return null;
        } catch (NoSuchMethodException e) {
            return "系统不支持 unpairDevice(需 Android 11+)";
        } catch (Exception e) {
            Log.e(TAG, "unpairDevice 失败", e);
            return "取消配对失败: " + e.getMessage();
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

    /** 摘掉 adb 后 composition 为空时写入的值。 */
    public static final String OFF_CONFIG_VALUE = "none";

    /**
     * 关闭时写回 sys.usb.config 的值：**从 persist 里摘掉 adb**，其余 function 原样保留。
     *
     * 这与原生开发者选项一致——原生是「移除 adb function」而不是把整口清空；
     * 写死 none 会把 MTP 等一并关掉（这台车 persist 就是纯 adb，摘完为空才落到 none，行为不变）。
     */
    public static String offConfigValue() {
        String persist = readProp("persist.sys.usb.config");
        if (persist == null) persist = "";
        persist = persist.trim();
        if (persist.isEmpty()) return OFF_CONFIG_VALUE;
        StringBuilder sb = new StringBuilder();
        for (String f : persist.split(",")) {
            String t = f.trim();
            if (t.isEmpty() || "adb".equals(t) || OFF_CONFIG_VALUE.equals(t)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(t);
        }
        String v = sb.toString();
        return v.isEmpty() ? OFF_CONFIG_VALUE : v;
    }

    /**
     * composition 里是否真的有 adb function。
     * 按逗号切分后精确匹配，避免 `contains("adb")` 命中厂商自定义的相似 function 名。
     */
    public static boolean hasAdbFunction(String cfg) {
        if (cfg == null || cfg.isEmpty()) return false;
        for (String f : cfg.split(",")) {
            if ("adb".equals(f.trim())) return true;
        }
        return false;
    }

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
     * 当前真正生效的 composition。
     *
     * `sys.usb.state` 是实际生效值，`sys.usb.config` 只是下发下去的期望值，所以优先看 state；
     * 少数 ROM 不回写 state，此时退化为看 config，免得恒判成「没开」。
     */
    public static String effectiveUsbConfig() {
        String state = sysUsbState();
        return state.isEmpty() ? sysUsbConfig() : state;
    }

    /**
     * 有线 USB 调试是否真的可用：adbd 在跑 且 当前生效的 composition 含 adb。
     *
     * 这是开关成败与界面显示的**唯一**判定依据——只查设置项的话，
     * 「设置里开着、但底层 adbd 没起来 / composition 没生效」会被当成已开启。
     */
    public static boolean isUsbAdbActive() {
        return isAdbServiceRunning() && hasAdbFunction(effectiveUsbConfig());
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
     * 无线 ADB 是否还需要 adbd 进程活着。
     *
     * 无线调试跑在**同一个 adbd 进程**里：`ctl.stop adbd` 会把无线调试一起掐掉
     * （TLS 端口重新分配、配对码作废、已连的无线客户端断开）。
     * 所以「用户希望开着」（含被系统重置、后台即将自动重开）或「当前确实在监听」都算还需要。
     */
    public static boolean wirelessNeedsAdbd(Context ctx) {
        if (ctx == null) return false;
        return isWirelessDesired(ctx) || isWirelessOn(ctx);
    }

    /**
     * 关闭有线 USB 调试时是否要保留 adbd 进程（无线调试跑在同一个进程里）。
     *
     * 抽成方法是为了让 `applyUsbAdb` 与 `setUsbAdb` 用同一把尺子，
     * 否则两处各判一次、结果漂移，就会出现「底层保住了、写完设置项又被停掉」的互相抵消。
     */
    private static boolean shouldKeepAdbd(Context ctx, boolean enabled) {
        return !enabled && wirelessNeedsAdbd(ctx);
    }

    /**
     * 应用有线 USB 调试开关。
     *
     * 无论哪一档，都只会碰调试口那一个控制器：其他口（host 那一路的 U 盘 / 4G 模组）
     * 不经过 gadget，也不会被写角色节点，因此不受影响。
     *
     * 关闭时有个特例：无线 ADB 还在用就不 stop adbd，只把 adb function 从 composition 里摘掉，
     * 让 adbd 继续服务无线（见 {@link #wirelessNeedsAdbd}）。
     *
     * @param level LEVEL_SILENT / LEVEL_CONFIG / LEVEL_ROLE
     * @return null 表示成功；否则返回失败原因
     */
    public static String applyUsbAdb(Context ctx, boolean enabled, int level) {
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
            String cfg = enabled ? onConfigValue() : offConfigValue();
            exec("setprop", "sys.usb.config", cfg);
            sleep(300);
        }

        // 关闭时不无脑 ctl.stop adbd：无线调试跑在同一个进程里，一停就把无线一起掐断。
        // 无线还在用时只摘掉 composition 里的 adb function（有线口不再暴露 adb），adbd 留给无线继续跑。
        final boolean keepAdbd = shouldKeepAdbd(ctx, enabled);
        if (keepAdbd) {
            Log.i(TAG, "无线 ADB 仍在使用，关闭 USB 调试时保留 adbd 进程（仅切 composition）");
        } else {
            exec("setprop", enabled ? "ctl.start" : "ctl.stop", "adbd");
            sleep(600);
        }

        if (keepAdbd && !isAdbServiceRunning()) {
            // 这类车机的 init 会因为 composition 里没有 adb 而把 adbd 一起停掉，
            // 光靠「不主动 stop」拦不住。这里立刻把它拉回来：有线仍是关的（config=none），
            // 但无线调试能在 1 秒内恢复，不用等 AdbService 那 15 秒的修复节流。
            // 端口由 adbd 重新分配，所以上层必须跟着刷新端口显示。
            Log.w(TAG, "adbd 被 composition 变更带停，立即拉起以恢复无线 ADB");
            startAdbd();
            sleep(600);
        }

        // composition 是否真的生效：sys.usb.config 只是「期望值」，真正生效要看 sys.usb.state。
        // 写完必须回读校验，否则「写了但没生效」会被当成成功（对齐 UsbHw.setMode 的做法）。
        // 刻意放在 adbd 启停之后——部分 ROM 要等 adbd 起来才会把 state 更新过来。
        if (level >= LEVEL_CONFIG && !waitForUsbState(enabled)) {
            warn.append("composition 未生效(config=").append(sysUsbConfig())
                    .append(" state=").append(sysUsbState()).append(") ");
        }

        // 判定必须落在「底层真实状态」上，不能只看 adbd 在不在：
        // - 普通场景：有线 USB 调试是否真的可用（adbd 在跑 且 composition 真的带上/摘掉 adb）；
        // - keepAdbd（关有线、保无线）：有线必须真的关掉，且 adbd 必须还活着给无线用。
        final boolean usbActive = isUsbAdbActive();
        boolean adbOk = keepAdbd
                ? (isAdbServiceRunning() && !usbActive)
                : (usbActive == enabled);
        Log.i(TAG, String.format(
                "applyUsbAdb enabled=%s level=%d keepAdbd=%s port=%s config=%s state=%s "
                        + "adbdRunning=%s usbActive=%s adbOk=%s",
                enabled, level, keepAdbd, port, sysUsbConfig(), sysUsbState(),
                isAdbServiceRunning(), usbActive, adbOk));
        if (!adbOk) {
            if (keepAdbd) {
                warn.append("无线 ADB 依赖的 adbd 未存活，无线已中断 ");
            } else {
                warn.append("USB 调试未真正").append(enabled ? "开启" : "关闭")
                        .append("(config=").append(sysUsbConfig())
                        .append(" state=").append(sysUsbState())
                        .append(" adbd=").append(isAdbServiceRunning() ? "在跑" : "已停").append(") ");
            }
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
        String err = applyUsbAdb(ctx, enabled, LEVEL_DEFAULT);
        try {
            android.provider.Settings.Global.putInt(
                    ctx.getContentResolver(), android.provider.Settings.Global.ADB_ENABLED,
                    enabled ? 1 : 0);
        } catch (Exception e) {
            Log.e(TAG, "写入 ADB_ENABLED 失败", e);
        }
        // 这台车机会监听 ADB_ENABLED 自行重算 composition 并连带停掉 adbd，
        // 于是 applyUsbAdb 里刚保住的 adbd 可能在这一步又被停掉、无线被掐——两处逻辑互相抵消。
        // 无线还依赖 adbd 时，写完后补一次存活校验并拉起。
        if (shouldKeepAdbd(ctx, enabled) && !isAdbServiceRunning()) {
            Log.w(TAG, "设置项写入后 adbd 被停，立即拉起以保住无线 ADB");
            startAdbd();
            sleep(600);
            if (!isAdbServiceRunning()) {
                String extra = "adbd 未能拉起，无线 ADB 已中断";
                err = (err == null || err.isEmpty()) ? extra : err + extra;
            }
        }
        return err;
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
