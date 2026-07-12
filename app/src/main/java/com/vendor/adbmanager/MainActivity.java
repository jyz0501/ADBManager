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
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Random;

public class MainActivity extends Activity {

    private static final String TAG = "AdbManager";
    private static final int WIRELESS_PORT = 5555;

    private TextView tvStatus;
    private TextView tvSubtitle;
    private TextView tvUid;
    private Button btnToggleAdb;
    private Button btnExit;
    private View statusIndicator;

    private TextView tvWirelessStatus;
    private TextView tvIpPort;
    private Button btnWirelessEnable;
    private Button btnWirelessDisable;

    private TextView tvPairCode;
    private TextView tvPairPort;
    private Button btnGenPair;

    private Button btnMobileData;
    private TextView tvMobileStatus;

        private Button btnUsb2Power;
        private Button btnUsb31Power;
        private Button btnUsb32Power;

        private WirelessPairingHelper pairingHelper;

        private final android.os.Handler usbPollHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        private Runnable usbPollRunnable;

        private String currentPairCode = "";
    private int currentPairPort = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_VISIBLE);

        android.view.WindowManager.LayoutParams params = getWindow().getAttributes();
        params.height = 1080;
        params.y = 0;
        getWindow().setAttributes(params);

        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        tvSubtitle = findViewById(R.id.tv_subtitle);
        tvUid = findViewById(R.id.tv_uid);
        btnToggleAdb = findViewById(R.id.btn_toggle_adb);
        btnExit = findViewById(R.id.btn_exit);
        statusIndicator = findViewById(R.id.status_indicator);

        tvWirelessStatus = findViewById(R.id.tv_wireless_status);
        tvIpPort = findViewById(R.id.tv_ip_port);
        btnWirelessEnable = findViewById(R.id.btn_wireless_enable);
        btnWirelessDisable = findViewById(R.id.btn_wireless_disable);

        tvPairCode = findViewById(R.id.tv_pair_code);
        tvPairPort = findViewById(R.id.tv_pair_port);
        btnGenPair = findViewById(R.id.btn_gen_pair);

        btnMobileData = findViewById(R.id.btn_mobile_data);
        tvMobileStatus = findViewById(R.id.tv_mobile_status);

        btnUsb2Power = findViewById(R.id.btn_usb2_power);
        btnUsb31Power = findViewById(R.id.btn_usb31_power);
        btnUsb32Power = findViewById(R.id.btn_usb32_power);

        grantAllRuntimePermissions();
        updateStatus();
        updateWirelessStatus();
        updateMobileDataStatus();
        updateUsbPowerStatus();

        pairingHelper = new WirelessPairingHelper(this);

        btnToggleAdb.setOnClickListener(v -> {
            int currentState = Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0);
            boolean targetEnabled = currentState == 0;
            Log.i(TAG, "========== ADB切换: " + (targetEnabled ? "开启" : "关闭") + " ==========");
            
            btnToggleAdb.setEnabled(false);
            
            new Thread(() -> {
                setAdbEnabled(targetEnabled);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {}
                
                int actualState = Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, -1);
                boolean success = (targetEnabled && actualState == 1) || (!targetEnabled && actualState == 0);
                
                runOnUiThread(() -> {
                    updateStatus();
                    btnToggleAdb.setEnabled(true);
                    if (success) {
                        Toast.makeText(MainActivity.this, "ADB" + (targetEnabled ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "ADB" + (targetEnabled ? "开启失败" : "关闭失败"), Toast.LENGTH_SHORT).show();
                    }
                });
            }).start();
        });

        btnWirelessEnable.setOnClickListener(v -> {
            Log.i(TAG, "========== 开启无线ADB ==========");
            setWirelessAdb(true);
            updateWirelessStatus();
        });

        btnWirelessDisable.setOnClickListener(v -> {
            Log.i(TAG, "========== 关闭无线ADB ==========");
            setWirelessAdb(false);
            updateWirelessStatus();
        });

        btnGenPair.setOnClickListener(v -> {
            Log.i(TAG, "========== 生成配对码 ==========");
            generatePairCode();
        });

        btnExit.setOnClickListener(v -> {
            Log.i(TAG, "========== 点击退出 ==========");
            finish();
        });

        btnUsb2Power.setOnClickListener(v -> {
            toggleUsbPower("usb2power", btnUsb2Power);
        });

        btnUsb31Power.setOnClickListener(v -> {
            toggleUsbPower("usb31power", btnUsb31Power);
        });

        btnUsb32Power.setOnClickListener(v -> {
            toggleUsbPower("usb32power", btnUsb32Power);
        });

        btnMobileData.setOnClickListener(v -> {
            Log.i(TAG, "========== 切换移动数据 ==========");
            toggleMobileData();
        });
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

    private void updateStatus() {
        int adbEnabled = Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0);
        Log.i(TAG, "当前ADB状态: " + (adbEnabled == 1 ? "已开启(1)" : "已关闭(0)"));

        if (adbEnabled == 1) {
            tvStatus.setText("ADB: 已开启");
            tvStatus.setTextColor(Color.parseColor("#27AE60"));
            statusIndicator.setBackgroundColor(Color.parseColor("#27AE60"));
            btnToggleAdb.setText("关闭");
            btnToggleAdb.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#E74C3C")));
        } else {
            tvStatus.setText("ADB: 已关闭");
            tvStatus.setTextColor(Color.parseColor("#E74C3C"));
            statusIndicator.setBackgroundColor(Color.parseColor("#E74C3C"));
            btnToggleAdb.setText("开启");
            btnToggleAdb.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#27AE60")));
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
                btnWirelessEnable.setEnabled(false);
                btnWirelessDisable.setEnabled(true);
            } else {
                tvWirelessStatus.setText("状态: 未开启");
                tvWirelessStatus.setTextColor(Color.parseColor("#888888"));
                tvIpPort.setText("IP: -");
                tvIpPort.setTextColor(Color.parseColor("#888888"));
                btnWirelessEnable.setEnabled(true);
                btnWirelessDisable.setEnabled(false);
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
            Thread.sleep(500);
            executeShellCommand("setprop", "ctl.start", "adbd");
            Thread.sleep(1000);

            Log.i(TAG, "✅ 无线ADB " + (enabled ? "已开启" : "已关闭"));
            getSharedPreferences("adb_prefs", MODE_PRIVATE)
                    .edit().putBoolean("auto_wireless_adb", enabled).apply();
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
                    tvPairPort.setText("配对端口: " + port + "   IP: " + ip);
                    btnGenPair.setEnabled(true);
                    Toast.makeText(MainActivity.this,
                            "电脑执行: adb pair " + ip + ":" + port, Toast.LENGTH_LONG).show();
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
            btnMobileData.setText("关闭");
            btnMobileData.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#E74C3C")));
        } else {
            tvMobileStatus.setText("移动数据: 已关闭");
            tvMobileStatus.setTextColor(Color.parseColor("#E74C3C"));
            btnMobileData.setText("开启");
            btnMobileData.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#27AE60")));
        }
    }

    private void toggleMobileData() {
        int currentState = Settings.Global.getInt(getContentResolver(), "mobile_data", 0);
        boolean targetEnabled = currentState == 0;
        Log.i(TAG, "切换移动数据: " + (targetEnabled ? "开启" : "关闭"));

        btnMobileData.setEnabled(false);

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
                btnMobileData.setEnabled(true);
                Toast.makeText(MainActivity.this, "移动数据" + (targetEnabled ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void toggleUsbPower(String powerFile, Button btn) {
        String path = "/sys/devices/platform/usbpower/" + powerFile;
        new Thread(() -> {
            try {
                int currentValue = readUsbPowerValue(path);
                int targetValue = currentValue == 1 ? 0 : 1;

                // 写入目标值
                int writeExit = writeUsbPower(path, targetValue);

                // 实时反馈检测：等待硬件生效后读回实际状态，确认是否真正切换
                Thread.sleep(300);
                int actual = readUsbPowerValue(path);
                boolean applied = (actual == targetValue);

                Log.i(TAG, "USB供电切换: " + powerFile + " 期望=" + targetValue
                        + " 实际=" + actual + " 写exit=" + writeExit
                        + " 生效=" + applied);

                final int finalActual = actual;
                final boolean finalApplied = applied;
                runOnUiThread(() -> {
                    updateUsbPowerButton(powerFile, btn);
                    if (finalApplied) {
                        Toast.makeText(MainActivity.this,
                                powerFile.toUpperCase().replace("POWER", "")
                                        + (targetValue == 1 ? "供电已开启" : "供电已关闭"),
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
        updateUsbPowerButton("usb2power", btnUsb2Power);
        updateUsbPowerButton("usb31power", btnUsb31Power);
        updateUsbPowerButton("usb32power", btnUsb32Power);
    }

    private void updateUsbPowerButton(String powerFile, Button btn) {
        String path = "/sys/devices/platform/usbpower/" + powerFile;
        int value = readUsbPowerValue(path);
        // 1=绿(开启)，0=深灰(关闭)，-1=橙(读取失败/节点不存在)
        int color = value == 1 ? Color.parseColor("#4CAF50")
                : value == -1 ? Color.parseColor("#E67E22")
                : Color.parseColor("#424242");
        btn.setBackgroundTintList(ColorStateList.valueOf(color));
    }

    /** 读取 USB 供电节点实际值：1=开，0=关，-1=读取失败 */
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

    /** 写入 USB 供电节点，返回进程退出码 */
    private int writeUsbPower(String path, int value) {
        try {
            java.lang.Process p = new ProcessBuilder("sh", "-c", "echo " + value + " > " + path).redirectErrorStream(true).start();
            return p.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "写入USB供电失败: " + path, e);
            return -1;
        }
    }

    /** 实时轮询：周期性读回 USB 供电状态，反映硬件/外部变化 */
    private void startUsbPolling() {
        stopUsbPolling();
        usbPollRunnable = new Runnable() {
            @Override
            public void run() {
                updateUsbPowerStatus();
                usbPollHandler.postDelayed(this, 2000);
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