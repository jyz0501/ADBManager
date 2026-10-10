package com.qianxian.adbmanager;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
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

public class MainActivity extends Activity {

    private static final String TAG = "AdbManager";

    
    /** Toast 提示总开关：false = 关闭全部 Toast（仅写日志），true = 恢复提示 */
    private static final boolean TOAST_ENABLED = false;

    private static final String UPDATE_BASE_URL = "http://YOUR-UPDATE-SERVER/ADBManager/";
    private static final String UPDATE_VERSION_FILE = "version.json";

    private TextView tvStatus;
    private TextView tvVersion;
    private Switch swAdb;
    private TextView tvUsbDetail;
    private Button btnRevokeUsbAuth;
    private Button btnExit;
    private View statusIndicator;

    private TextView tvWirelessStatus;
    private TextView tvIpPort;
    private Switch swWireless;
    private TextView tvWifiClients;

    private TextView tvPairCode;
    private TextView tvPairPort;
    private Button btnGenPair;
    private Button btnPairQr;
    private Button btnCheckUpdate;
    private android.widget.ImageView ivPairQr;
    private android.widget.LinearLayout llPairedDevices;
    private TextView tvPairedEmpty;

    private Switch swUsb2Power;
    private Switch swUsb31Power;

    private WirelessPairingHelper pairingHelper;

        private final android.os.Handler usbPollHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        private Runnable usbPollRunnable;

        private String currentPairCode = "";

    /** 防止程序化 setChecked 触发 OnCheckedChangeListener */
    private boolean suppressSwitch = false;

    /**
     * USB 调试切换进行中。
     * 期间 2 秒轮询（updateStatus）不再回写开关，等真实状态确认后由切换流程一次性落定，
     * 否则会出现「先切过去、被校正回来、再被轮询掰一次」的来回弹。
     */
    private boolean adbSwitchBusy = false;

    /** 等底层 USB 调试真正生效的轮次（每轮 300ms，约 5 秒）。 */
    private static final int ADB_SETTLE_ROUNDS = 16;

    /** 切换失败时顶栏文案的保留时长：Toast 默认关闭，失败原因得在界面上停留一会儿才看得见。 */
    private static final long USB_FAIL_HINT_MS = 5000L;

    /** 最近一次切换失败的提示；在 usbAdbFailUntilMs 之前优先于常规状态显示。 */
    private String usbAdbFailMsg = "";
    private long usbAdbFailUntilMs = 0L;

    /** USB 供电节点写入后等真实值回落的轮次（每轮 150ms，约 1.5 秒）。 */
    private static final int USB_POWER_SETTLE_ROUNDS = 10;

    /**
     * 正在切换中的 USB 供电开关。
     * 期间 2 秒轮询不再回写开关，等真实值确认后再落定，避免先开后关地来回弹。
     * 只在主线程上增删与查询。
     */
    private final java.util.Set<Switch> usbPowerBusy = new java.util.HashSet<>();

    /** 统一出口：关闭 Toast 时只写日志，保留原文便于排查。 */
    private void toast(String msg) {
        toast(msg, false);
    }

    private void toast(String msg, boolean longDuration) {
        Log.i(TAG, "Toast: " + msg);
        if (!TOAST_ENABLED) return;
        Toast.makeText(this, msg, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        
        
        getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_VISIBLE);

        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        tvVersion = findViewById(R.id.tv_version);
        swAdb = findViewById(R.id.sw_adb);
        tvUsbDetail = findViewById(R.id.tv_usb_detail);
        btnRevokeUsbAuth = findViewById(R.id.btn_revoke_usb_auth);
        btnExit = findViewById(R.id.btn_exit);
        statusIndicator = findViewById(R.id.status_indicator);

        tvWirelessStatus = findViewById(R.id.tv_wireless_status);
        tvIpPort = findViewById(R.id.tv_ip_port);
        swWireless = findViewById(R.id.sw_wireless);
        tvWifiClients = findViewById(R.id.tv_wifi_clients);

        tvPairCode = findViewById(R.id.tv_pair_code);
        tvPairPort = findViewById(R.id.tv_pair_port);
        btnGenPair = findViewById(R.id.btn_gen_pair);
        btnPairQr = findViewById(R.id.btn_pair_qr);
        ivPairQr = findViewById(R.id.iv_pair_qr);
        llPairedDevices = findViewById(R.id.ll_paired_devices);
        tvPairedEmpty = findViewById(R.id.tv_paired_empty);
        btnCheckUpdate = findViewById(R.id.btn_check_update);

        swUsb2Power = findViewById(R.id.sw_usb2_power);
        swUsb31Power = findViewById(R.id.sw_usb31_power);



        tvVersion.setText("v" + appVersionName());

        grantAllRuntimePermissions();
        updateStatus();
        updateWirelessStatus();
        updateUsbPowerStatus();
        updateConnectionStatus();
        ensureUsb2PowerDefaultOn();

        pairingHelper = new WirelessPairingHelper(this);

        swAdb.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressSwitch) return;
            Log.i(TAG, "========== ADB切换: " + (isChecked ? "开启" : "关闭") + " ==========");
            applyUsbAdbSwitch(isChecked);
        });
        swWireless.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressSwitch) return;
            // USB 与无线是同一个 adbd 上的两个独立 transport：切无线走热切换（只改
            // adb_wifi_enabled，adbd 自己起/停 TLS 监听），不杀进程，因此不会牵连有线 USB 调试，
            // 也就不需要「会断开有线连接」的二次确认。只有极少数 ROM 热切换不生效才会重启兜底。
            // 下发失败（端口没切过来）时回退到拨动前的状态，避免开关停在假状态上。
            applyWirelessAdb(isChecked, !isChecked);
        });

        btnRevokeUsbAuth.setOnClickListener(v -> confirmRevokeUsbAuth());

        btnGenPair.setOnClickListener(v -> {
            Log.i(TAG, "========== 生成配对码 ==========");
            generatePairCode(false);
        });

        btnPairQr.setOnClickListener(v -> {
            Log.i(TAG, "========== 二维码配对 ==========");
            generatePairCode(true);
        });

        renderPairedDevices();

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
            toggleUsbPower("usb2power", swUsb2Power, "主驾USB1", isChecked);
        });

        swUsb31Power.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressSwitch) return;
            toggleUsbPower("usb31power", swUsb31Power, "主驾USB2", isChecked);
        });


        try {
            // Android 12+ 起后台/前台切换受限，统一用 startForegroundService 拉保活服务
            // Android 12+ 起后台不能裸 startService，统一走前台启动
            startForegroundService(new Intent(this, AdbService.class));
            Log.i(TAG, "已启动 AdbService(保活/状态监听)");
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

    /**
     * 有线 USB 调试的唯一下发出口：设置项 + 底层（composition / adbd / 必要的控制器角色）。
     * 手动开关与冷启动默认策略都走这里，避免「设置里显示关闭、底层 adbd 还在跑」的假关闭。
     *
     * @return null 表示成功；否则为失败原因
     */
    private String applyAdbState(boolean enabled) {
        return AdbCtl.setUsbAdb(this, enabled);
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

    /** 无线客户端检测 */
    private void updateConnectionStatus() {
        new Thread(() -> {
            java.util.List<String> clients = getWirelessClients();

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
                tvWifiClients.setText(wifiText);
                tvWifiClients.setTextColor(wifiColor);
            });
        }).start();
    }

    /**
     * USB 调试开关：不再「先切 UI 再校正」，而是**拿到真实状态后才切**。
     *
     * 拨动后开关会被系统切成新状态，这里先拨回拨动前的状态并置灰、文案显示「开启中/关闭中」，
     * 等底层状态确认后再由 {@link #updateStatus()} 一次性落到真实状态：
     * - 成功 → 只切一次，切到目标状态；
     * - 失败 → 真实状态仍是拨动前的状态，开关原地不动，只提示失败。
     * 期间置 adbSwitchBusy，2 秒轮询不会再把开关掰回去。
     */
    private void applyUsbAdbSwitch(boolean target) {
        if (adbSwitchBusy) return;
        adbSwitchBusy = true;

        // 开关先回到拨动前的状态：真实状态没确认前不假装已经生效
        setSwitchChecked(swAdb, !target);
        swAdb.setEnabled(false);
        tvStatus.setText("ADB: " + (target ? "开启中..." : "关闭中..."));
        tvStatus.setTextColor(Color.parseColor("#888888"));
        statusIndicator.setBackgroundColor(Color.parseColor("#888888"));

        new Thread(() -> {
            final String err = setAdbEnabled(target);
            // 成败只看底层真实状态（adbd 在跑 且 composition 真的带上/摘掉 adb）。
            // 不能只看我们写入的设置项——设置项几乎必然写成功，那样「成功」判定就永远为真。
            final boolean ok = waitForUsbAdbActive(target);
            Log.i(TAG, "ADB切换结果: 目标=" + (target ? "开" : "关") + " 成功=" + ok
                    + " 告警=" + err);

            runOnUiThread(() -> {
                // 先解除 busy，让 updateStatus() 把开关落到真实状态
                adbSwitchBusy = false;
                swAdb.setEnabled(true);
                updateStatus();

                // 动过 adbd 就可能换了无线端口、作废了配对码：一并刷新，别让界面停在旧值
                if (ok && !target) {
                    updateWirelessStatus();
                    markPairCodeStale();
                }

                if (!ok) {
                    // 底层没切过去就拨回拨动前的状态，别让开关停在假状态上
                    setSwitchChecked(swAdb, !target);
                    String msg = "ADB" + (target ? "开启失败" : "关闭失败")
                            + (err != null ? "(" + err.trim() + ")" : "");
                    usbAdbFailMsg = msg;
                    usbAdbFailUntilMs = System.currentTimeMillis() + USB_FAIL_HINT_MS;
                    tvStatus.setText(msg);
                    tvStatus.setTextColor(Color.parseColor("#E74C3C"));
                    statusIndicator.setBackgroundColor(Color.parseColor("#E74C3C"));
                    toast(msg, true);
                } else if (err != null) {
                    toast("ADB 已切换，但底层有告警: " + err);
                } else {
                    toast("ADB" + (target ? "已开启" : "已关闭"));
                }
            });
        }).start();
    }

    /**
     * 轮询等底层 USB 调试真正切到目标状态（每轮 300ms，约 5 秒）。
     *
     * 判定用 {@link AdbCtl#isUsbAdbActive()}：adbd 在跑 且 生效的 composition 带上/摘掉 adb。
     * 这类车机从下发到真正生效有抖动（要重新枚举一次），不能只查一次。
     */
    private boolean waitForUsbAdbActive(boolean target) {
        for (int i = 0; i < ADB_SETTLE_ROUNDS; i++) {
            if (AdbCtl.isUsbAdbActive() == target) return true;
            sleepQuietly(300);
        }
        return AdbCtl.isUsbAdbActive() == target;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void updateStatus() {
        int adbEnabled = Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0);
        Log.i(TAG, "当前ADB状态: " + (adbEnabled == 1 ? "已开启(1)" : "已关闭(0)"));

        // 状态行与开关都由 updateUsbDetail 在子线程读到底层真实状态后回主线程落定。
        // 这里不再按设置项直接切开关——否则「设置项开着、底层没开」会一直被显示成已开启。
        updateUsbDetail(adbEnabled == 1);
    }

    /**
     * 按**底层真实状态**落定 USB 调试开关与顶栏文案。
     * 切换进行中（adbSwitchBusy）保持不动，等切换流程自己收尾。
     */
    private void applyUsbAdbUi(boolean usbActive) {
        if (adbSwitchBusy) return;
        setSwitchChecked(swAdb, usbActive);
        // 刚切换失败时优先显示失败原因，别被常规状态文案立刻盖掉
        if (System.currentTimeMillis() < usbAdbFailUntilMs) {
            tvStatus.setText(usbAdbFailMsg);
            tvStatus.setTextColor(Color.parseColor("#E74C3C"));
            statusIndicator.setBackgroundColor(Color.parseColor("#E74C3C"));
            return;
        }
        if (usbActive) {
            tvStatus.setText("ADB: 已开启");
            tvStatus.setTextColor(Color.parseColor("#27AE60"));
            statusIndicator.setBackgroundColor(Color.parseColor("#27AE60"));
        } else {
            tvStatus.setText("ADB: 已关闭");
            tvStatus.setTextColor(Color.parseColor("#E74C3C"));
            statusIndicator.setBackgroundColor(Color.parseColor("#E74C3C"));
        }
    }

    /**
     * 底层真实状态：composition / adbd / 调试口角色。
     * 只看 ADB_ENABLED 会出现「设置显示关、adbd 还在跑」的假关闭，这里把它显示出来。
     */
    private void updateUsbDetail(final boolean settingOn) {
        new Thread(() -> {
            String config = AdbCtl.sysUsbConfig();
            boolean running = AdbCtl.isAdbServiceRunning();
            UsbHw.Controller port = AdbCtl.debugPort();
            // 关了有线但无线还在用时，会故意保留 adbd：此时「设置项关 + adbd 在跑」是正常的，不算不一致
            final boolean keepAdbd = !settingOn && AdbCtl.wirelessNeedsAdbd(MainActivity.this);
            // 底层真实状态：adbd 在跑 且 生效的 composition 真的带上了 adb。
            // keepAdbd 时 adbd 继续跑但 composition 里没有 adb，usbActive 仍为 false，
            // 正好等于「有线确实关了」，因此不需要为它开特例。
            final boolean usbActive = AdbCtl.isUsbAdbActive();
            final boolean consistent = (usbActive == settingOn);
            final String state = AdbCtl.sysUsbState();
            final String text = "底层: config=" + (config.isEmpty() ? "-" : config)
                    + " | state=" + (state.isEmpty() ? "-" : state)
                    + " | adbd=" + (running ? "在跑" : "已停")
                    + " | 调试口=" + (port == null ? "未探测到" : port.toString())
                    + (keepAdbd ? " | adbd 保留给无线ADB" : "");
            Log.i(TAG, "USB 底层状态: " + text + " | 设置项=" + (settingOn ? "开" : "关")
                    + " | 有线可用=" + usbActive + " | 一致=" + consistent);
            runOnUiThread(() -> {
                if (tvUsbDetail == null) return;
                tvUsbDetail.setText(text);
                tvUsbDetail.setTextColor(Color.parseColor(consistent ? "#888888" : "#E67E22"));
                applyUsbAdbUi(usbActive);
            });
        }).start();
    }

    /** 撤销 USB 调试授权前先确认：清空密钥后所有已授权的电脑都要重新确认。 */
    private void confirmRevokeUsbAuth() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("撤销 USB 调试授权")
                .setMessage("将清除所有已授权电脑的调试密钥，下次连接需重新确认。是否继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("撤销", (dialog, which) -> revokeUsbAuth())
                .show();
    }

    private void revokeUsbAuth() {
        Log.i(TAG, "========== 撤销 USB 调试授权 ==========");
        btnRevokeUsbAuth.setEnabled(false);
        btnRevokeUsbAuth.setText("撤销中...");
        new Thread(() -> {
            final String err = AdbCtl.revokeUsbDebuggingKeys();
            runOnUiThread(() -> showRevokeResult(err));
        }).start();
    }

    /** Toast 已全局关闭，执行结果直接回写到按钮上，几秒后恢复原标题。 */
    private void showRevokeResult(String err) {
        btnRevokeUsbAuth.setEnabled(true);
        btnRevokeUsbAuth.setText(err == null ? "已撤销 USB 调试授权" : err);
        usbPollHandler.postDelayed(() -> btnRevokeUsbAuth.setText("撤销 USB 调试授权"), 2500);
    }

    /**
     * 切换 USB 调试。底层动作全部交给 AdbCtl 分档处理：
     * 默认只到 composition 档（LEVEL_CONFIG），不再把调试口切成 host，
     * 免得同口的 CarLife/AOA 一起失效。
     *
     * @return null 表示成功；否则为失败原因
     */
    private String setAdbEnabled(boolean enabled) {
        ContentResolver resolver = getContentResolver();
        int value = enabled ? 1 : 0;

        String err = applyAdbState(enabled);

        int actual = Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, -1);
        Log.i(TAG, "设置ADB状态: " + (enabled ? "开启" : "关闭") + " 期望=" + value + " 实际=" + actual);
        if (actual != value) {
            Log.w(TAG, "⚠️ 设置项写入失败！期望=" + value + "，实际=" + actual);
        }
        Log.i(TAG, err == null ? "✅ 设置成功！" : "⚠️ 设置完成但有告警: " + err);
        return err;
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
        setSwitchChecked(swWireless, isPortOn(refreshWirelessText()));
        renderPairedDevices();
    }

    /**
     * 只刷新状态文案与 IP:端口，**不动开关**，返回当前端口。
     * 取消弹窗时要按「拨动前的状态」回退开关，不能让真实状态再把它覆盖回去。
     */
    private String refreshWirelessText() {
        String port = "";
        try {
            // 端口由 adbd 动态分配：优先无线调试的 TLS 端口，不再是固定 5555
            port = AdbCtl.effectivePort();
            Log.i(TAG, "当前无线ADB端口: " + port);

            if (isPortOn(port)) {
                String ip = getLocalIpAddress();
                tvWirelessStatus.setText("状态: 已开启");
                tvWirelessStatus.setTextColor(Color.parseColor("#27AE60"));
                tvIpPort.setText("IP: " + ip + ":" + port);
                tvIpPort.setTextColor(Color.parseColor("#4CAF50"));
            } else {
                tvWirelessStatus.setText("状态: 未开启");
                tvWirelessStatus.setTextColor(Color.parseColor("#888888"));
                tvIpPort.setText("IP: -");
                tvIpPort.setTextColor(Color.parseColor("#888888"));
            }
        } catch (Exception e) {
            Log.e(TAG, "更新无线ADB状态失败", e);
        }
        return port == null ? "" : port;
    }

    /** 端口属性是否代表无线 ADB 已开启：-1/0/空都算关。 */
    private static boolean isPortOn(String port) {
        return port != null && !port.isEmpty() && !port.equals("-1") && !port.equals("0");
    }

    private void setWirelessAdb(boolean enabled) {
        // 记录用户意愿：后台 AdbService 据此在无线 ADB 被系统重置后自动重开；
        // 用户手动关闭时意愿同步置 false，不会被服务再拉起来。
        AdbCtl.setWirelessDesired(this, enabled);
        AdbCtl.setWirelessAdb(this, enabled);
    }

    /** 回退：开关拨回拨动前的状态，并按真实状态刷新文案。 */
    private void revertWirelessSwitch(boolean previous) {
        Log.i(TAG, "========== 切换无线ADB未生效，回退到: " + (previous ? "开" : "关") + " ==========");
        setSwitchChecked(swWireless, previous);
        refreshWirelessText();
    }

    private void applyWirelessAdb(boolean enabled, boolean previous) {
        Log.i(TAG, "========== " + (enabled ? "开启" : "关闭") + "无线ADB ==========");
        swWireless.setEnabled(false);
        tvWirelessStatus.setText(enabled ? "状态: 开启中..." : "状态: 关闭中...");
        tvWirelessStatus.setTextColor(Color.parseColor("#888888"));
        new Thread(() -> {
            setWirelessAdb(enabled);
            runOnUiThread(() -> {
                final boolean ok = isPortOn(refreshWirelessText()) == enabled;
                // 下发失败就回退到拨动前的状态，避免开关停在假状态上
                if (!ok) revertWirelessSwitch(previous);
                swWireless.setEnabled(true);
                renderPairedDevices();
                toast(ok ? "无线ADB" + (enabled ? "已开启" : "已关闭")
                        : "无线ADB" + (enabled ? "开启失败" : "关闭失败"));
            });
        }).start();
    }

    /** @param withQr true=二维码配对，false=配对码配对 */
    private void generatePairCode(boolean withQr) {
        Log.i(TAG, "========== 生成配对码（withQr=" + withQr + "）==========");
        btnGenPair.setEnabled(false);
        btnPairQr.setEnabled(false);
        ivPairQr.setVisibility(View.GONE);
        tvPairCode.setText("生成中...");
        tvPairCode.setTextColor(Color.parseColor("#888888"));
        tvPairPort.setText("配对端口: 获取中...");
        String ip = getLocalIpAddress();
        final boolean qr = withQr;

        pairingHelper.startPairing(new WirelessPairingHelper.PairingCallback() {
            @Override
            public void onPairingCode(String code, int port) {
                runOnUiThread(() -> {
                    currentPairCode = code != null ? code : "";
                    tvPairCode.setText(currentPairCode);
                    tvPairCode.setTextColor(Color.parseColor("#4CAF50"));
                    if (qr) showPairQr(currentPairCode);
                    if (port > 0) {
                        tvPairPort.setText("配对端口: " + port + "   IP: " + ip);
                        toast("电脑执行: adb pair " + ip + ":" + port, true);
                    } else {
                        tvPairPort.setText("配对端口: 获取中...   IP: " + ip);
                    }
                    btnGenPair.setEnabled(true);
                    btnPairQr.setEnabled(true);
                });
            }

            @Override
            public void onPaired(String ip2) {
                runOnUiThread(() -> {
                    String client = ip2 != null ? ip2 : latestClientIp();
                    if (client != null && !client.isEmpty()) {
                        PairedDeviceStore.save(MainActivity.this, client, client);
                    }
                    pairingHelper.stopPairing();
                    ivPairQr.setVisibility(View.GONE);
                    tvPairCode.setText("已配对");
                    tvPairCode.setTextColor(Color.parseColor("#4CAF50"));
                    renderPairedDevices();
                    updateWirelessStatus();
                });
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> {
                    tvPairCode.setText("失败");
                    tvPairCode.setTextColor(Color.parseColor("#E74C3C"));
                    tvPairPort.setText(msg);
                    btnGenPair.setEnabled(true);
                    btnPairQr.setEnabled(true);
                    ivPairQr.setVisibility(View.GONE);
                    toast(msg, true);
                });
            }
        });
    }

    /** 展示配对二维码；生成失败时隐藏并提示。 */
    private void showPairQr(String code) {
        android.graphics.Bitmap bmp = QrUtil.pairingQr(pairingServiceName(), code, 512);
        if (bmp == null) {
            ivPairQr.setVisibility(View.GONE);
            toast("二维码生成失败，请使用配对码配对");
            return;
        }
        ivPairQr.setImageBitmap(bmp);
        ivPairQr.setVisibility(View.VISIBLE);
    }

    /** adbd 重启后配对码与配对端口都作废，界面上不能继续挂着旧值误导人。 */
    private void markPairCodeStale() {
        if (currentPairCode.isEmpty()) return;
        currentPairCode = "";
        ivPairQr.setVisibility(View.GONE);
        tvPairCode.setText("已失效，请重新生成");
        tvPairCode.setTextColor(Color.parseColor("#E67E22"));
        tvPairPort.setText("配对端口: -");
        Log.i(TAG, "adbd 已重启，配对码作废");
    }

    /** 配对二维码里的服务名：adb-<serial>，与系统 mDNS 配对服务命名一致。 */
    private String pairingServiceName() {
        String serial = execReadLine("getprop", "ro.serialno");
        if (serial == null) serial = "";
        serial = serial.trim();
        return serial.isEmpty() ? "adb" : "adb-" + serial;
    }

    /** 最近一个无线客户端 IP；没有连接时返回空串。 */
    private String latestClientIp() {
        java.util.List<String> clients = getWirelessClients();
        return clients.isEmpty() ? "" : clients.get(0);
    }

    /** 渲染「已配对的设备」列表，在线状态以当前客户端连接为准。 */
    private void renderPairedDevices() {
        java.util.List<PairedDeviceStore.Device> all = PairedDeviceStore.list(this);
        java.util.List<String> online = getWirelessClients();
        llPairedDevices.removeAllViews();
        tvPairedEmpty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (10 * density);
        for (PairedDeviceStore.Device d : all) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setPadding(0, pad, 0, pad);

            android.widget.TextView name = new android.widget.TextView(this);
            name.setText(d.name);
            name.setTextSize(16);
            name.setTextColor(Color.parseColor("#FFFFFF"));
            name.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            android.widget.TextView state = new android.widget.TextView(this);
            boolean isOnline = online.contains(d.ip);
            state.setText(isOnline ? "已连接" : "未连接");
            state.setTextSize(14);
            state.setTextColor(Color.parseColor(isOnline ? "#4CAF50" : "#888888"));

            android.widget.TextView forget = new android.widget.TextView(this);
            forget.setText("忘记");
            forget.setTextSize(14);
            forget.setTextColor(Color.parseColor("#1976D2"));
            forget.setPadding((int) (14 * density), 0, 0, 0);
            forget.setOnClickListener(v -> {
                PairedDeviceStore.remove(MainActivity.this, d.name);
                renderPairedDevices();
            });

            row.addView(name);
            row.addView(state);
            row.addView(forget);
            llPairedDevices.addView(row);
        }
    }

    
    private void checkUpdateFromServer() {
        Log.i(TAG, "========== 检测服务器更新 ==========");
        btnCheckUpdate.setEnabled(false);
        btnCheckUpdate.setText("检测中...");
        toast("正在连接更新服务器...");

        new Thread(() -> {
            final String result = fetchServerVersion();
            runOnUiThread(() -> {
                btnCheckUpdate.setEnabled(true);
                btnCheckUpdate.setText("检测更新");
                toast(result, true);
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

    private void startUsbPolling() {
        stopUsbPolling();
        usbPollRunnable = new Runnable() {
            @Override
            public void run() {
                new Thread(() -> {
                    updateUsbPowerStatus();
                    updateConnectionStatus();
                    // updateStatus() 会直接改 tvStatus 等控件，必须回主线程
                    runOnUiThread(() -> updateStatus());
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

    /**
     * USB 供电开关：与 USB 调试开关同一套机制——**等真实值确认后才切**。
     *
     * 拨动后开关会被系统切成新状态，这里先拨回拨动前的状态并置灰，
     * 等供电节点读到目标值后由 {@link #updateUsbPowerSwitch} 一次性落定：
     * - 成功 → 只切一次，切到目标状态；
     * - 未生效 → 真实值还是拨动前的状态，开关原地不动，只提示未生效。
     */
    private void toggleUsbPower(String powerFile, Switch sw, String label, boolean target) {
        String path = "/sys/devices/platform/usbpower/" + powerFile;
        if (usbPowerBusy.contains(sw)) return;
        usbPowerBusy.add(sw);

        // 真实状态没确认前不假装生效
        setSwitchChecked(sw, !target);
        sw.setEnabled(false);

        new Thread(() -> {
            final int want = target ? 1 : 0;
            int writeExit;
            int actual;
            try {
                writeExit = writeUsbPower(path, want);
                actual = waitForUsbPowerValue(path, want);
            } catch (Exception e) {
                Log.e(TAG, "USB供电控制失败", e);
                final String msg = e.getMessage();
                runOnUiThread(() -> {
                    usbPowerBusy.remove(sw);
                    updateUsbPowerSwitch(powerFile, sw);
                    toast("控制失败: " + msg);
                });
                return;
            }

            final boolean applied = (actual == want);
            final int finalActual = actual;
            Log.i(TAG, "USB供电切换: " + powerFile + " 期望=" + want
                    + " 实际=" + finalActual + " 写exit=" + writeExit
                    + " 生效=" + applied);

            runOnUiThread(() -> {
                // 先解除 busy，让 updateUsbPowerSwitch 把开关落到真实状态
                usbPowerBusy.remove(sw);
                updateUsbPowerSwitch(powerFile, sw);
                if (applied) {
                    toast(label + (target ? "供电已开启" : "供电已关闭"));
                } else {
                    String state = finalActual == 1 ? "开" : finalActual == 0 ? "关" : "读取失败";
                    toast("切换未生效（实际状态: " + state + "）", true);
                }
            });
        }).start();
    }

    /** 供电节点写入后要过一会儿才回落，轮询等真实值到位，不能只 sleep 一次就判定。 */
    private int waitForUsbPowerValue(String path, int want) {
        for (int i = 0; i < USB_POWER_SETTLE_ROUNDS; i++) {
            sleepQuietly(150);
            int v = readUsbPowerValue(path);
            if (v == want) return v;
        }
        return readUsbPowerValue(path);
    }

    private void updateUsbPowerStatus() {
        updateUsbPowerSwitch("usb2power", swUsb2Power);
        updateUsbPowerSwitch("usb31power", swUsb31Power);
    }

    private void updateUsbPowerSwitch(String powerFile, Switch sw) {
        String path = "/sys/devices/platform/usbpower/" + powerFile;
        int value = readUsbPowerValue(path);
        runOnUiThread(() -> {
            // 切换进行中：开关停在拨动前的状态，等真实值确认后再落定
            if (usbPowerBusy.contains(sw)) return;
            setSwitchChecked(sw, value == 1);
            sw.setEnabled(value != -1);
        });
    }

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

    private int writeUsbPower(String path, int value) {
        try {
            java.lang.Process p = new ProcessBuilder("sh", "-c", "echo " + value + " > " + path).redirectErrorStream(true).start();
            return p.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "写入USB供电失败: " + path, e);
            return -1;
        }
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

}