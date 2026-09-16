package com.vendor.adbmanager;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Enumeration;
import java.util.Random;

public class MainActivity extends Activity {

    private static final String TAG = "AdbManager";
    private static final int WIRELESS_PORT = 5555;

    
    private static final String UPDATE_BASE_URL = "http://YOUR-UPDATE-SERVER/ADBManager/";
    private static final String UPDATE_VERSION_FILE = "version.json";

    private TextView tvStatus;
    private TextView tvSubtitle;
    private TextView tvUid;
    private TextView tvVersion;
    private Switch swAdb;
    private TextView tvUsbConnStatus;
    private Button btnExit;
    private View statusIndicator;

    private TextView tvWirelessStatus;
    private TextView tvIpPort;
    private Switch swWireless;
    private TextView tvWifiClients;

    private TextView tvWifiDebugStatus;
    private Switch swWifiDebug;

    private TextView tvPairCode;
    private TextView tvPairPort;
    private Button btnGenPair;
    private Button btnCheckUpdate;

    private Switch swMobileData;
    private TextView tvMobileStatus;

        private Switch swUsb2Power;
        private Switch swUsb31Power;

        private WirelessPairingHelper pairingHelper;

        private final android.os.Handler usbPollHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        private Runnable usbPollRunnable;

        private String currentPairCode = "";
    private int currentPairPort = 0;

    /** 防止程序化 setChecked 触发 OnCheckedChangeListener */
    private boolean suppressSwitch = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        
        
        getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_VISIBLE);

        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        tvSubtitle = findViewById(R.id.tv_subtitle);
        tvUid = findViewById(R.id.tv_uid);
        tvVersion = findViewById(R.id.tv_version);
        swAdb = findViewById(R.id.sw_adb);
        tvUsbConnStatus = findViewById(R.id.tv_usb_conn_status);
        btnExit = findViewById(R.id.btn_exit);
        statusIndicator = findViewById(R.id.status_indicator);

        tvWirelessStatus = findViewById(R.id.tv_wireless_status);
        tvIpPort = findViewById(R.id.tv_ip_port);
        swWireless = findViewById(R.id.sw_wireless);
        tvWifiClients = findViewById(R.id.tv_wifi_clients);

        tvWifiDebugStatus = findViewById(R.id.tv_wifi_debug_status);
        swWifiDebug = findViewById(R.id.sw_wifi_debug);

        tvPairCode = findViewById(R.id.tv_pair_code);
        tvPairPort = findViewById(R.id.tv_pair_port);
        btnGenPair = findViewById(R.id.btn_gen_pair);
        btnCheckUpdate = findViewById(R.id.btn_check_update);

        swMobileData = findViewById(R.id.sw_mobile_data);
        tvMobileStatus = findViewById(R.id.tv_mobile_status);

        swUsb2Power = findViewById(R.id.sw_usb2_power);
        swUsb31Power = findViewById(R.id.sw_usb31_power);

        tvVersion.setText("v" + appVersionName());

        grantAllRuntimePermissions();
        ensureAdbDisabledOnLaunch();
        ensureUsb2PowerDefaultOn();
        updateStatus();
        updateWirelessStatus();
        updateWifiDebugStatus();
        updateMobileDataStatus();
        updateUsbPowerStatus();
        updateConnectionStatus();

        pairingHelper = new WirelessPairingHelper(this);

        swAdb.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressSwitch) return;
            Log.i(TAG, "========== ADB切换: " + (isChecked ? "开启" : "关闭") + " ==========");

            swAdb.setEnabled(false);

            new Thread(() -> {
                setAdbEnabled(isChecked);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {}

                int actualState = Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, -1);
                boolean success = (isChecked && actualState == 1) || (!isChecked && actualState == 0);

                runOnUiThread(() -> {
                    updateStatus();
                    swAdb.setEnabled(true);
                    if (success) {
                        Toast.makeText(MainActivity.this, "ADB" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "ADB" + (isChecked ? "开启失败" : "关闭失败"), Toast.LENGTH_SHORT).show();
                    }
                });
            }).start();
        });

        swWireless.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressSwitch) return;
            Log.i(TAG, "========== " + (isChecked ? "开启" : "关闭") + "无线ADB ==========");
            swWireless.setEnabled(false);
            tvWirelessStatus.setText(isChecked ? "状态: 开启中..." : "状态: 关闭中...");
            tvWirelessStatus.setTextColor(Color.parseColor("#888888"));
            new Thread(() -> {
                setWirelessAdb(isChecked);
                runOnUiThread(() -> {
                    updateWirelessStatus();
                    swWireless.setEnabled(true);
                    Toast.makeText(MainActivity.this,
                            "无线ADB" + (isChecked ? "已开启" : "已关闭"),
                            Toast.LENGTH_SHORT).show();
                });
            }).start();
        });

        swWifiDebug.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressSwitch) return;
            Log.i(TAG, "========== 切换无线调试 ==========");
            toggleWifiDebug(isChecked);
        });

        btnGenPair.setOnClickListener(v -> {
            Log.i(TAG, "========== 生成配对码 ==========");
            generatePairCode();
        });

        btnExit.setOnClickListener(v -> {
            Log.i(TAG, "========== 点击退出 ==========");
            finish();
        });

        btnCheckUpdate.setOnClickListener(v -> {
            Log.i(TAG, "========== 点击检测更新 ==========");
            checkUpdateFromServer();
        });

        swUsb2Power.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressSwitch) return;
            toggleUsbPower("usb2power", swUsb2Power, "主驾USB1");
        });

        swUsb31Power.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressSwitch) return;
            toggleUsbPower("usb31power", swUsb31Power, "主驾USB2");
        });

        swMobileData.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressSwitch) return;
            Log.i(TAG, "========== 切换移动数据 ==========");
            toggleMobileData(isChecked);
        });

        
        try {
            startService(new Intent(this, AdbService.class));
            Log.i(TAG, "已启动 AdbService(状态监听)");
        } catch (Exception e) {
            Log.e(TAG, "启动 AdbService 失败", e);
        }
    }

    
    /** 当前包的版本名（与 AndroidManifest 的 versionName 一致），读取失败时占位。 */
    private String appVersionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName != null ? info.versionName : "-";
        } catch (Exception e) {
            Log.w(TAG, "读取版本号失败", e);
            return "-";
        }
    }

    private void ensureAdbDisabledOnLaunch() {
        // 若当前有客户端正通过 adbd 连接（无线/USB），跳过默认关闭策略，避免断开正在使用的连接
        java.util.List<String> clients = getWirelessClients();
        if (!clients.isEmpty()) {
            Log.i(TAG, "========== 启动默认策略：检测到活动连接(" + clients.size() + "个)，跳过关闭 ADB ==========");
            return;
        }
        int adbEnabled = Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0);
        if (adbEnabled == 1) {
            Log.i(TAG, "========== 启动默认策略：仅写 ADB_ENABLED=0（不切换底层）==========");
            try {
                Settings.Global.putInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0);
            } catch (Exception e) {
                Log.e(TAG, "启动默认策略：写入 ADB_ENABLED=0 失败", e);
            }
        } else {
            Log.i(TAG, "启动默认策略：ADB 已处于关闭状态，无需操作");
        }
    }

    private void grantAllRuntimePermissions() {
        try {
            PackageManager pm = getPackageManager();
            PackageInfo info = pm.getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions != null) {
                UserHandle userHandle = Process.myUserHandle();
                java.lang.reflect.Method m = PackageManager.class.getMethod("grantRuntimePermission", String.class, String.class, UserHandle.class);
                for (String perm : info.requestedPermissions) {
                    try {
                        m.invoke(pm, getPackageName(), perm, userHandle);
                        Log.i(TAG, "已授予权限: " + perm);
                    } catch (Exception e) {
                        Log.w(TAG, "授予权限失败: " + perm);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "grantAllRuntimePermissions 失败", e);
        }
    }

    private void setSwitchChecked(Switch sw, boolean checked) {
        if (sw.isChecked() == checked) return;
        suppressSwitch = true;
        sw.setChecked(checked);
        suppressSwitch = false;
    }

    private String execReadLine(String... cmd) {
        try {
            java.lang.Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            p.waitFor();
            return line != null ? line.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String execReadAll(String... cmd) {
        try {
            java.lang.Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 获取 adbd 监听端口集合（TCP 5555 + TLS 动态端口） */
    private java.util.Set<String> getAdbdPorts() {
        java.util.Set<String> ports = new java.util.HashSet<>();
        String tcp = execReadLine("getprop", "service.adb.tcp.port");
        String tls = execReadLine("getprop", "service.adb.tls.port");
        for (String p : new String[]{tcp, tls}) {
            if (p != null && p.matches("\\d+") && !p.equals("-1") && !p.equals("0")) {
                ports.add(p);
            }
        }
        return ports;
    }

    /** 当前是否有客户端连接在 adbd 端口上（USB 之外的网络连接） */
    private java.util.List<String> getWirelessClients() {
        java.util.List<String> clients = new java.util.ArrayList<>();
        java.util.Set<String> ports = getAdbdPorts();
        if (ports.isEmpty()) return clients;

        String netstat = execReadAll("netstat", "-tn");
        for (String line : netstat.split("\n")) {
            if (!line.contains("ESTABLISHED")) continue;
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 5) continue;
            String local = fields[3];
            String peer = fields[4];
            String localPort = local.substring(local.lastIndexOf(':') + 1);
            if (ports.contains(localPort)) {
                String peerIp = peer.substring(0, peer.lastIndexOf(':'));
                // 去掉 IPv6 映射前缀，便于阅读
                if (peerIp.startsWith("::ffff:")) {
                    peerIp = peerIp.substring(7);
                }
                if (!peerIp.equals("127.0.0.1") && !clients.contains(peerIp)) {
                    clients.add(peerIp);
                }
            }
        }
        return clients;
    }

    /** 主驾USB1（usb2power）状态 + 无线客户端检测 */
    private void updateConnectionStatus() {
        new Thread(() -> {
            String usb1 = execReadLine("sh", "-c", "cat /sys/devices/platform/usbpower/usb2power");
            java.util.List<String> clients = getWirelessClients();

            final String usbText;
            final int usbColor;
            if (usb1.equals("1")) {
                usbText = "主驾USB1: 供电已开启";
                usbColor = Color.parseColor("#27AE60");
            } else if (usb1.equals("0")) {
                usbText = "主驾USB1: 供电已关闭";
                usbColor = Color.parseColor("#E74C3C");
            } else {
                usbText = "主驾USB1: 状态未知";
                usbColor = Color.parseColor("#888888");
            }

            final String wifiText;
            final int wifiColor;
            if (clients.isEmpty()) {
                wifiText = "无线客户端: 无连接";
                wifiColor = Color.parseColor("#888888");
            } else {
                wifiText = "无线客户端: " + clients.size() + " 个 (" + android.text.TextUtils.join(", ", clients) + ")";
                wifiColor = Color.parseColor("#27AE60");
            }

            runOnUiThread(() -> {
                tvUsbConnStatus.setText(usbText);
                tvUsbConnStatus.setTextColor(usbColor);
                tvWifiClients.setText(wifiText);
                tvWifiClients.setTextColor(wifiColor);
            });
        }).start();
    }

    private void updateStatus() {
        int adbEnabled = Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0);
        Log.i(TAG, "当前ADB状态: " + (adbEnabled == 1 ? "已开启(1)" : "已关闭(0)"));

        if (adbEnabled == 1) {
            tvStatus.setText("ADB: 已开启");
            tvStatus.setTextColor(Color.parseColor("#27AE60"));
            statusIndicator.setBackgroundColor(Color.parseColor("#27AE60"));
            setSwitchChecked(swAdb, true);
        } else {
            tvStatus.setText("ADB: 已关闭");
            tvStatus.setTextColor(Color.parseColor("#E74C3C"));
            statusIndicator.setBackgroundColor(Color.parseColor("#E74C3C"));
            setSwitchChecked(swAdb, false);
        }
    }

    private void setAdbEnabled(boolean enabled) {
        ContentResolver resolver = getContentResolver();
        int value = enabled ? 1 : 0;

        try {
            Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, value);
            Log.i(TAG, "设置ADB状态: " + (enabled ? "开启" : "关闭") + "，值=" + value);

            int actual = Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, -1);
            Log.i(TAG, "设置后实际状态: " + actual);

            if (actual != value) {
                Log.w(TAG, "⚠️ 设置失败！期望=" + value + "，实际=" + actual);
            } else {
                Log.i(TAG, "✅ 设置成功！");
            }

            String usbConfig = enabled ? "adb" : "mtp";
            Log.i(TAG, "切换USB配置: setprop sys.usb.config " + usbConfig);
            try {
                java.lang.Process p1 = new ProcessBuilder("setprop", "sys.usb.config", usbConfig).redirectErrorStream(true).start();
                BufferedReader br1 = new BufferedReader(new InputStreamReader(p1.getInputStream()));
                String line1;
                while ((line1 = br1.readLine()) != null) {
                    Log.i(TAG, "usb config: " + line1);
                }
                p1.waitFor();
                Log.i(TAG, "USB配置已切换为: " + usbConfig);
            } catch (Exception e) {
                Log.e(TAG, "切换USB配置失败", e);
            }

            Thread.sleep(300);

            String svcAction = enabled ? "ctl.start" : "ctl.stop";
            Log.i(TAG, "执行服务控制: setprop " + svcAction + " adbd");
            try {
                java.lang.Process p2 = new ProcessBuilder("setprop", svcAction, "adbd").redirectErrorStream(true).start();
                int code = p2.waitFor();
                Log.i(TAG, "setprop " + svcAction + " adbd exit=" + code);
            } catch (Exception e) {
                Log.e(TAG, "setprop 执行失败: " + e.getMessage(), e);
            }

            Thread.sleep(500);

            String dwcMode = enabled ? "peripheral" : "host";
            Log.i(TAG, "切换dwc3控制器模式: " + dwcMode);
            try {
                java.lang.Process p3 = new ProcessBuilder("sh", "-c",
                        "echo " + dwcMode + " > /sys/devices/platform/soc/a600000.ssusb/mode").redirectErrorStream(true).start();
                BufferedReader br3 = new BufferedReader(new InputStreamReader(p3.getInputStream()));
                String line3;
                while ((line3 = br3.readLine()) != null) {
                    Log.i(TAG, "dwc3 mode: " + line3);
                }
                int code3 = p3.waitFor();
                Log.i(TAG, "dwc3模式切换 exit=" + code3 + " mode=" + dwcMode);
            } catch (Exception e) {
                Log.e(TAG, "dwc3模式切换失败", e);
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ 设置ADB状态失败: " + e.getMessage(), e);
        }
    }

    private String getLocalIpAddress() {
        String wifiIp = null;
        String ethIp = null;
        String usbIp = null;
        String otherIp = null;

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                String name = ni.getName();
                Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (name != null && name.startsWith("wlan")) {
                            wifiIp = ip;
                        } else if (name != null && (name.startsWith("eth") || name.startsWith("veth"))) {
                            ethIp = ip;
                        } else if (name != null && name.startsWith("usb")) {
                            usbIp = ip;
                        } else if (!name.startsWith("VLAN") && !name.startsWith("vt")) {
                            otherIp = ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取IP失败", e);
        }

        if (wifiIp != null) return wifiIp;
        if (ethIp != null) return ethIp;
        if (usbIp != null) return usbIp;
        if (otherIp != null) return otherIp;
        return "未知";
    }

    private void updateWifiDebugStatus() {
        int wifiDebug = Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0);
        Log.i(TAG, "当前无线调试状态: " + (wifiDebug == 1 ? "已开启(1)" : "已关闭(0)"));

        if (wifiDebug == 1) {
            tvWifiDebugStatus.setText("无线调试: 已开启");
            tvWifiDebugStatus.setTextColor(Color.parseColor("#27AE60"));
            setSwitchChecked(swWifiDebug, true);
        } else {
            tvWifiDebugStatus.setText("无线调试: 未开启");
            tvWifiDebugStatus.setTextColor(Color.parseColor("#888888"));
            setSwitchChecked(swWifiDebug, false);
        }
    }

    private void toggleWifiDebug(boolean targetEnabled) {
        Log.i(TAG, "切换无线调试: " + (targetEnabled ? "开启" : "关闭"));

        swWifiDebug.setEnabled(false);

        new Thread(() -> {
            try {
                Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", targetEnabled ? 1 : 0);
                Log.i(TAG, "✅ 无线调试" + (targetEnabled ? "已开启" : "已关闭"));
            } catch (Exception e) {
                Log.e(TAG, "❌ 切换无线调试失败", e);
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {}

            runOnUiThread(() -> {
                updateWifiDebugStatus();
                swWifiDebug.setEnabled(true);
                Toast.makeText(MainActivity.this,
                        "无线调试" + (targetEnabled ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void updateWirelessStatus() {
        try {
            java.lang.Process p = new ProcessBuilder("getprop", "service.adb.tcp.port").redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String port = br.readLine();
            p.waitFor();

            Log.i(TAG, "当前无线ADB端口: " + port);

            if (port != null && !port.isEmpty() && !port.equals("-1") && !port.equals("0")) {
                String ip = getLocalIpAddress();
                tvWirelessStatus.setText("状态: 已开启");
                tvWirelessStatus.setTextColor(Color.parseColor("#27AE60"));
                tvIpPort.setText("IP: " + ip + ":" + port);
                tvIpPort.setTextColor(Color.parseColor("#4CAF50"));
                setSwitchChecked(swWireless, true);
            } else {
                tvWirelessStatus.setText("状态: 未开启");
                tvWirelessStatus.setTextColor(Color.parseColor("#888888"));
                tvIpPort.setText("IP: -");
                tvIpPort.setTextColor(Color.parseColor("#888888"));
                setSwitchChecked(swWireless, false);
            }
        } catch (Exception e) {
            Log.e(TAG, "更新无线ADB状态失败", e);
        }
    }

    private void setWirelessAdb(boolean enabled) {
        try {
            String portValue = enabled ? String.valueOf(WIRELESS_PORT) : "-1";
            Log.i(TAG, "设置无线ADB端口: " + portValue);

            executeShellCommand("setprop", "service.adb.tcp.port", portValue);

            executeShellCommand("setprop", "ctl.stop", "adbd");
            Thread.sleep(300);
            executeShellCommand("setprop", "ctl.start", "adbd");
            Thread.sleep(300);

            Log.i(TAG, "✅ 无线ADB " + (enabled ? "已开启" : "已关闭"));
            // 有意不持久化：无线 ADB 每次都要用户手动开启，重启后不自动恢复
        } catch (Exception e) {
            Log.e(TAG, "❌ 设置无线ADB失败", e);
        }
    }

    private int executeShellCommand(String... args) {
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            java.lang.Process p = pb.start();
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

    private void generatePairCode() {
        Log.i(TAG, "========== 生成配对码（调用系统无线调试配对服务）==========");
        btnGenPair.setEnabled(false);
        tvPairCode.setText("生成中...");
        tvPairCode.setTextColor(Color.parseColor("#888888"));
        tvPairPort.setText("配对端口: 获取中...");
        String ip = getLocalIpAddress();

        pairingHelper.startPairing(new WirelessPairingHelper.PairingCallback() {
            @Override
            public void onPairingCode(String code, int port) {
                runOnUiThread(() -> {
                    currentPairCode = code != null ? code : "";
                    currentPairPort = port;
                    tvPairCode.setText(currentPairCode);
                    tvPairCode.setTextColor(Color.parseColor("#4CAF50"));
                    if (port > 0) {
                        tvPairPort.setText("配对端口: " + port + "   IP: " + ip);
                        Toast.makeText(MainActivity.this,
                                "电脑执行: adb pair " + ip + ":" + port, Toast.LENGTH_LONG).show();
                    } else {
                        tvPairPort.setText("配对端口: 获取中...   IP: " + ip);
                    }
                    btnGenPair.setEnabled(true);
                    updateWifiDebugStatus();
                });
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> {
                    tvPairCode.setText("失败");
                    tvPairCode.setTextColor(Color.parseColor("#E74C3C"));
                    tvPairPort.setText(msg);
                    btnGenPair.setEnabled(true);
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void updateMobileDataStatus() {
        int mobileData = Settings.Global.getInt(getContentResolver(), "mobile_data", 0);
        Log.i(TAG, "当前移动数据状态: " + (mobileData == 1 ? "已开启(1)" : "已关闭(0)"));

        if (mobileData == 1) {
            tvMobileStatus.setText("移动数据: 已开启");
            tvMobileStatus.setTextColor(Color.parseColor("#27AE60"));
            setSwitchChecked(swMobileData, true);
        } else {
            tvMobileStatus.setText("移动数据: 已关闭");
            tvMobileStatus.setTextColor(Color.parseColor("#E74C3C"));
            setSwitchChecked(swMobileData, false);
        }
    }

    private void toggleMobileData(boolean targetEnabled) {
        Log.i(TAG, "切换移动数据: " + (targetEnabled ? "开启" : "关闭"));

        swMobileData.setEnabled(false);

        new Thread(() -> {
            try {
                Settings.Global.putInt(getContentResolver(), "mobile_data", targetEnabled ? 1 : 0);
                Log.i(TAG, "✅ 移动数据" + (targetEnabled ? "已开启" : "已关闭"));
            } catch (Exception e) {
                Log.e(TAG, "❌ 切换移动数据失败", e);
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {}

            runOnUiThread(() -> {
                updateMobileDataStatus();
                swMobileData.setEnabled(true);
                Toast.makeText(MainActivity.this, "移动数据" + (targetEnabled ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    
    private void checkUpdateFromServer() {
        Log.i(TAG, "========== 检测服务器更新 ==========");
        btnCheckUpdate.setEnabled(false);
        btnCheckUpdate.setText("检测中...");
        Toast.makeText(this, "正在连接更新服务器...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            final String result = fetchServerVersion();
            runOnUiThread(() -> {
                btnCheckUpdate.setEnabled(true);
                btnCheckUpdate.setText("检测更新");
                Toast.makeText(MainActivity.this, result, Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    
    private String fetchServerVersion() {
        String localName = "";
        int localCode = 0;
        try {
            PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
            localCode = p.versionCode;
            localName = p.versionName;
        } catch (Exception e) {
            Log.w(TAG, "读取本机版本失败", e);
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL(UPDATE_BASE_URL + UPDATE_VERSION_FILE);
            Log.i(TAG, "请求版本信息: " + url);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "ADBManager/" + localName);
            int httpCode = conn.getResponseCode();
            if (httpCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "版本接口返回: HTTP " + httpCode);
                return "更新服务器异常(HTTP " + httpCode + ")";
            }

            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            JSONObject json = new JSONObject(sb.toString());
            int serverCode = json.optInt("versionCode", 0);
            String serverName = json.optString("versionName", "");
            String apkUrl = json.optString("url", "");
            Log.i(TAG, "本机 v" + localName + "(" + localCode + ") | 服务器 v"
                    + serverName + "(" + serverCode + ") url=" + apkUrl);

            if (serverCode <= localCode) {
                return "已是最新版本 (" + localName + ")";
            }
            if (apkUrl.isEmpty()) {
                return "发现新版本 " + serverName + "，但服务器未提供下载地址(占位)";
            }

            URL apkFullUrl = new URL(apkUrl.contains("://") ? apkUrl : UPDATE_BASE_URL + apkUrl);
            boolean ok = downloadAndInstall(apkFullUrl);
            return ok ? "更新完成，已安装 v" + serverName : "更新失败：下载或安装失败，请查看日志";
        } catch (JSONException e) {
            Log.e(TAG, "版本信息解析失败", e);
            return "更新服务器返回格式异常(占位)";
        } catch (Exception e) {
            Log.e(TAG, "检测更新失败（更新服务未部署或不可达）", e);
            return "更新服务不可达，请检查网络或服务器配置(占位)";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    
    private boolean downloadAndInstall(URL apkUrl) {
        HttpURLConnection conn = null;
        File apkFile = new File(getCacheDir(), "update.apk");
        try {
            Log.i(TAG, "开始下载: " + apkUrl + " -> " + apkFile);
            conn = (HttpURLConnection) apkUrl.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("GET");
            int httpCode = conn.getResponseCode();
            if (httpCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "APK 下载返回 HTTP " + httpCode);
                return false;
            }
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(apkFile)) {
                byte[] buf = new byte[8192];
                int n;
                long done = 0;
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    done += n;
                }
                Log.i(TAG, "下载完成 size=" + done + " 服务器声明=" + total);
            }
            return silentInstallApk(apkFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "下载失败: " + e.getMessage(), e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    
    private boolean silentInstallApk(String apkPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder("pm", "install", "-r", apkPath);
            pb.redirectErrorStream(true);
            java.lang.Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
                Log.i(TAG, "pm install: " + line);
            }
            int code = p.waitFor();
            String output = sb.toString();
            Log.i(TAG, "pm install exit=" + code + " output=" + output);
            return code == 0 && output.contains("Success");
        } catch (Exception e) {
            Log.e(TAG, "pm install 执行失败: " + e.getMessage(), e);
            return false;
        }
    }

    private void toggleUsbPower(String powerFile, Switch sw, String label) {
        String path = "/sys/devices/platform/usbpower/" + powerFile;
        new Thread(() -> {
            try {
                int currentValue = readUsbPowerValue(path);
                int targetValue = currentValue == 1 ? 0 : 1;

                
                int writeExit = writeUsbPower(path, targetValue);

                
                Thread.sleep(300);
                int actual = readUsbPowerValue(path);
                boolean applied = (actual == targetValue);

                Log.i(TAG, "USB供电切换: " + powerFile + " 期望=" + targetValue
                        + " 实际=" + actual + " 写exit=" + writeExit
                        + " 生效=" + applied);

                final int finalActual = actual;
                final boolean finalApplied = applied;
                runOnUiThread(() -> {
                    updateUsbPowerSwitch(powerFile, sw);
                    if (finalApplied) {
                        Toast.makeText(MainActivity.this,
                                label + (targetValue == 1 ? "供电已开启" : "供电已关闭"),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        String state = finalActual == 1 ? "开" : finalActual == 0 ? "关" : "读取失败";
                        Toast.makeText(MainActivity.this,
                                "切换未生效（实际状态: " + state + "）",
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "USB供电控制失败", e);
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "控制失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void updateUsbPowerStatus() {
        updateUsbPowerSwitch("usb2power", swUsb2Power);
        updateUsbPowerSwitch("usb31power", swUsb31Power);
    }

    /** 启动时若主驾USB1供电处于关闭，则默认打开 */
    private void ensureUsb2PowerDefaultOn() {
        new Thread(() -> {
            String path = "/sys/devices/platform/usbpower/usb2power";
            int value = readUsbPowerValue(path);
            if (value == 0) {
                int exit = writeUsbPower(path, 1);
                Log.i(TAG, "启动默认开启主驾USB1供电: 写入exit=" + exit);
            } else {
                Log.i(TAG, "启动检查主驾USB1供电: 当前=" + (value == 1 ? "已开启" : "未知"));
            }
            runOnUiThread(() -> new Thread(() -> updateUsbPowerStatus()).start());
        }).start();
    }

    private void updateUsbPowerSwitch(String powerFile, Switch sw) {
        String path = "/sys/devices/platform/usbpower/" + powerFile;
        int value = readUsbPowerValue(path);
        runOnUiThread(() -> {
            setSwitchChecked(sw, value == 1);
            sw.setEnabled(value != -1);
        });
    }

    
    private int readUsbPowerValue(String path) {
        try {
            java.lang.Process p = new ProcessBuilder("sh", "-c", "cat " + path).redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String current = br.readLine();
            p.waitFor();
            if (current == null) return -1;
            String t = current.trim();
            if (t.equals("1")) return 1;
            if (t.equals("0")) return 0;
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "读取USB供电失败: " + path, e);
            return -1;
        }
    }

    
    private int writeUsbPower(String path, int value) {
        try {
            java.lang.Process p = new ProcessBuilder("sh", "-c", "echo " + value + " > " + path).redirectErrorStream(true).start();
            return p.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "写入USB供电失败: " + path, e);
            return -1;
        }
    }

    
    private void startUsbPolling() {
        stopUsbPolling();
        usbPollRunnable = new Runnable() {
            @Override
            public void run() {
                new Thread(() -> {
                    updateUsbPowerStatus();
                    updateConnectionStatus();
                    usbPollHandler.postDelayed(this, 2000);
                }).start();
            }
        };
        usbPollHandler.postDelayed(usbPollRunnable, 2000);
    }

    private void stopUsbPolling() {
        if (usbPollRunnable != null) {
            usbPollHandler.removeCallbacks(usbPollRunnable);
            usbPollRunnable = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUsbPowerStatus();
        updateWifiDebugStatus();
        startUsbPolling();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopUsbPolling();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pairingHelper != null) {
            pairingHelper.stopPairing();
        }
    }
}