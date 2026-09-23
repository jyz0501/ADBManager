package com.qianxian.adbmanager;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * USB 硬件接口（控制器）探测与角色操作。
 *
 * 车机上通常不止一个 USB 控制器，例如：
 *   a600000.ssusb → peripheral（USB 调试口，对应主驾USB1）
 *   a800000.ssusb → host（挂 U 盘 / 4G 模组那一路）
 *
 * 开关 USB 调试只允许动「调试口」这一个控制器，其余口一概不碰，
 * 所以 host 那一路的 U 盘 / 4G 模组不会因为开关调试而掉线。
 *
 * 控制器布局（数量、sysfs 路径）因平台而异，这里不硬编码 a600000，
 * 而是从 /sys/class/udc 探测，再按候选路径解析出 mode 节点。
 */
public final class UsbHw {

    private static final String TAG = "AdbManager.UsbHw";

    /** 角色：device（连电脑被调试）。 */
    public static final String MODE_PERIPHERAL = "peripheral";
    /** 角色：host（读 U 盘等外设）。 */
    public static final String MODE_HOST = "host";

    private static final String UDC_DIR = "/sys/class/udc";
    private static final String SOC_DIR = "/sys/devices/platform/soc";

    /** sysfs 前缀候选：不同内核挂载位置不同。 */
    private static final String[] PATH_PREFIXES = {
            "/sys/devices/platform/soc/",
            "/sys/devices/platform/",
    };

    /** mode 节点后缀候选：ssusb 是高通平台常见命名，其余作为回退。 */
    private static final String[] PATH_SUFFIXES = {
            ".ssusb/mode",
            ".usb/mode",
            ".dwc3/mode",
            "/mode",
    };

    /** 探测结果缓存：控制器数量和路径不会变，轮询时只重新读 mode 值。 */
    private static volatile List<Controller> cached;

    public static final class Controller {
        /** /sys/class/udc 下的名字，如 a600000.dwc3。 */
        public final String udc;
        /** 去掉后缀的基名，如 a600000。 */
        public final String base;
        /** 角色节点路径，如 /sys/devices/platform/soc/a600000.ssusb/mode。 */
        public final String modePath;
        /** 当前角色：peripheral / host；空串表示读不到。 */
        public String mode = "";

        Controller(String udc, String base, String modePath) {
            this.udc = udc;
            this.base = base;
            this.modePath = modePath;
        }

        public boolean isPeripheral() {
            return MODE_PERIPHERAL.equals(mode);
        }

        public boolean isHost() {
            return MODE_HOST.equals(mode);
        }

        @Override
        public String toString() {
            return base + "(" + (mode.isEmpty() ? "未知" : mode) + ")";
        }
    }

    private UsbHw() {
    }

    /** 所有控制器（含当前角色）。结果缓存，重复调用只刷新 mode。 */
    public static synchronized List<Controller> controllers() {
        if (cached == null) {
            cached = probe();
        } else {
            for (Controller c : cached) {
                c.mode = readMode(c);
            }
        }
        return cached;
    }

    /** 按角色找控制器；找不到返回 null。 */
    public static Controller findByMode(String mode) {
        for (Controller c : controllers()) {
            if (mode.equals(c.mode)) return c;
        }
        return null;
    }

    /**
     * 调试口：优先取当前处于 peripheral 的控制器。
     * 若一个都没有（例如刚被切成 host），退回第一个，由调用方记住 base 避免认错口。
     */
    public static Controller guessDebugPort() {
        Controller c = findByMode(MODE_PERIPHERAL);
        if (c != null) return c;
        List<Controller> all = controllers();
        return all.isEmpty() ? null : all.get(0);
    }

    /** 读取单个控制器的当前角色；读不到返回空串。 */
    public static String readMode(Controller c) {
        if (c == null) return "";
        String v = firstLine("cat", c.modePath);
        if (v.isEmpty() || v.contains("No such") || v.contains("denied")
                || v.contains("not found") || v.contains("Invalid")) {
            return "";
        }
        return v;
    }

    /**
     * 切换角色并回读校验。已经是目标值则直接返回成功（不重复写，避免控制器无谓复位）。
     */
    public static synchronized boolean setMode(Controller c, String target) {
        if (c == null) return false;
        String current = readMode(c);
        if (target.equals(current)) {
            c.mode = current;
            return true;
        }
        Log.i(TAG, "切换控制器角色: " + c.base + " " + current + " -> " + target);
        exec("sh", "-c", "echo " + target + " > " + c.modePath);
        for (int i = 0; i < 10; i++) {
            sleep(200);
            String now = readMode(c);
            if (target.equals(now)) {
                c.mode = now;
                Log.i(TAG, "角色切换已生效: " + c.base + "=" + target);
                return true;
            }
        }
        c.mode = readMode(c);
        Log.w(TAG, "角色切换未生效: " + c.base + " 期望=" + target + " 实际=" + c.mode);
        return false;
    }

    // ---------- 内部实现 ----------

    private static List<Controller> probe() {
        List<Controller> out = new ArrayList<>();

        for (String udc : execLines("ls", UDC_DIR)) {
            String base = stripSuffix(udc);
            if (base.isEmpty()) continue;
            String path = resolveModePath(base);
            if (path == null) continue;
            Controller c = new Controller(udc, base, path);
            c.mode = readMode(c);
            out.add(c);
        }

        // 兜底：某些 ROM 的 /sys/class/udc 不可读，直接扫 soc 目录下的控制器节点
        if (out.isEmpty()) {
            for (String node : execLines("ls", SOC_DIR)) {
                if (!node.endsWith(".ssusb") && !node.endsWith(".usb")) continue;
                String base = stripSuffix(node);
                String path = resolveModePath(base);
                if (path == null) continue;
                Controller c = new Controller(node, base, path);
                c.mode = readMode(c);
                out.add(c);
            }
        }

        Log.i(TAG, "探测到 USB 控制器: " + out);
        return out;
    }

    /** a600000.dwc3 -> a600000；没有点号则原样返回。 */
    private static String stripSuffix(String name) {
        if (name == null) return "";
        String s = name.trim();
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    /** 按候选路径找出可读的 mode 节点。 */
    private static String resolveModePath(String base) {
        for (String prefix : PATH_PREFIXES) {
            for (String suffix : PATH_SUFFIXES) {
                String path = prefix + base + suffix;
                String v = firstLine("cat", path);
                if (v.isEmpty() || v.contains("No such") || v.contains("denied")
                        || v.contains("not found") || v.contains("Invalid")) {
                    continue;
                }
                return path;
            }
        }
        return null;
    }

    private static String firstLine(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            p.waitFor();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void exec(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.start().waitFor();
        } catch (Exception e) {
            Log.w(TAG, "执行失败: " + java.util.Arrays.toString(cmd) + " → " + e.getMessage());
        }
    }

    /** 执行并回收非空输出行；失败返回空列表。 */
    private static List<String> execLines(String... cmd) {
        List<String> lines = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty()) lines.add(t);
            }
            p.waitFor();
        } catch (Exception e) {
            Log.w(TAG, "执行失败: " + java.util.Arrays.toString(cmd) + " → " + e.getMessage());
        }
        return lines;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
