package com.qianxian.adbmanager;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 已配对设备记录（对应系统无线调试里的「已配对的设备」）。
 * 系统凭证存于 adbd 私有目录，应用读不到，这里只维护展示用的档案。
 */
public final class PairedDeviceStore {

    private static final String PREF = "adbmanager_paired";
    private static final String KEY_LIST = "devices";
    private static final int MAX_KEEP = 20;

    /** 读取全部档案，按配对时间倒序。 */
    public static List<Device> list(Context ctx) {
        List<Device> out = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY_LIST, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new Device(o.optString("name"), o.optString("ip"),
                        o.optLong("at"), o.optString("sysKey")));
            }
        } catch (Exception e) {
            return out;
        }
        out.sort((a, b) -> Long.compare(b.pairedAt, a.pairedAt));
        return out;
    }

    /** 记录一次配对：同名设备只更新 IP 与时间。 */
    public static void save(Context ctx, String name, String ip) {
        save(ctx, name, ip, "");
    }

    /**
     * 记录一次配对，并记下系统侧的配对凭证 key。
     * 「忘记设备」要用这个 key 调 `IAdbManager.unpairDevice()`，否则删的只是本地显示。
     */
    public static void save(Context ctx, String name, String ip, String sysKey) {
        List<Device> all = list(ctx);
        all.removeIf(d -> d.name.equals(name));
        all.add(0, new Device(name, ip == null ? "" : ip, System.currentTimeMillis(),
                sysKey == null ? "" : sysKey));
        while (all.size() > MAX_KEEP) all.remove(all.size() - 1);
        write(ctx, all);
    }

    /** 配对完成后补写系统凭证 key（key 要等系统侧写盘后才拿得到）。 */
    public static void updateKey(Context ctx, String name, String sysKey) {
        if (name == null || sysKey == null || sysKey.isEmpty()) return;
        List<Device> all = list(ctx);
        boolean hit = false;
        for (Device d : all) {
            if (name.equals(d.name)) {
                d.sysKey = sysKey;
                hit = true;
                break;
            }
        }
        if (hit) write(ctx, all);
    }

    /** 本地已记录的系统凭证 key，用于识别「这次新配对的是哪一台」。 */
    public static java.util.List<String> knownKeys(Context ctx) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (Device d : list(ctx)) {
            if (d.sysKey != null && !d.sysKey.isEmpty()) keys.add(d.sysKey);
        }
        return keys;
    }


    /** 忘记（取消配对）某台设备。 */
    public static void remove(Context ctx, String name) {
        List<Device> all = list(ctx);
        all.removeIf(d -> d.name.equals(name));
        write(ctx, all);
    }

    private static void write(Context ctx, List<Device> all) {
        JSONArray arr = new JSONArray();
        for (Device d : all) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", d.name);
                o.put("ip", d.ip);
                o.put("at", d.pairedAt);
                o.put("sysKey", d.sysKey == null ? "" : d.sysKey);
            } catch (Exception ignored) {
                continue;
            }
            arr.put(o);
        }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static final class Device {
        public final String name;
        public final String ip;
        public final long pairedAt;
        /** 系统侧配对凭证 key（IAdbManager.unpairDevice 用）；空表示没拿到。 */
        public String sysKey;

        public Device(String name, String ip, long pairedAt, String sysKey) {
            this.name = name;
            this.ip = ip;
            this.pairedAt = pairedAt;
            this.sysKey = sysKey == null ? "" : sysKey;
        }
    }
}
