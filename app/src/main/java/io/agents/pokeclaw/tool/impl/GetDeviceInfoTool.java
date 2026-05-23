// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.i18n.AppLocaleManager;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;
import io.agents.pokeclaw.utils.XLog;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * Direct system queries without UI navigation.
 * Supports: battery, wifi, storage, bluetooth, screen.
 */
public class GetDeviceInfoTool extends BaseTool {

    private static final String TAG = "GetDeviceInfoTool";

    @Override
    public String getName() { return "get_device_info"; }

    @Override
    public String getDisplayName() { return "Device Info"; }

    @Override
    public String getDescriptionEN() {
        return "Get device system info directly without navigating Settings UI. "
                + "Categories: battery, wifi, storage, bluetooth, screen, device, time. "
                + "Much faster than opening Settings — use this first for system queries.";
    }

    @Override
    public String getDescriptionCN() {
        return "Get device system info directly without navigating Settings UI. "
                + "Categories: battery, wifi, storage, bluetooth, screen, device, time.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("category", "string",
                        "Info category: 'battery', 'wifi', 'storage', 'bluetooth', or 'screen'", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String category = requireString(params, "category").toLowerCase().trim();
        Context ctx = ClawApplication.Companion.getInstance();

        try {
            switch (category) {
                case "battery": return getBatteryInfo(ctx);
                case "wifi": return getWifiInfo(ctx);
                case "storage": return getStorageInfo(ctx);
                case "bluetooth": return getBluetoothInfo(ctx);
                case "screen": return getScreenInfo(ctx);
                case "device": return getDeviceDetails(ctx);
                case "time": return getCurrentTime(ctx);
                default:
                    return ToolResult.error(chinese(ctx)
                            ? "未知类别：" + category + "。请使用：battery, wifi, storage, bluetooth, screen, device, time"
                            : "Unknown category: " + category + ". Use: battery, wifi, storage, bluetooth, screen, device, time");
            }
        } catch (Exception e) {
            XLog.e(TAG, "Failed to get " + category + " info", e);
            return ToolResult.error(chinese(ctx)
                    ? "获取 " + category + " 信息失败：" + e.getMessage()
                    : "Failed to get " + category + " info: " + e.getMessage());
        }
    }

    private ToolResult getBatteryInfo(Context ctx) {
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        int status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;

        // Get battery temperature from sticky broadcast
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryIntent = ctx.registerReceiver(null, filter);
        int tempRaw = batteryIntent != null ? batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) : 0;
        float tempC = tempRaw / 10.0f;

        StringBuilder sb = new StringBuilder();
        if (chinese(ctx)) {
            sb.append("电量：").append(level).append("%");
            sb.append(charging ? "，正在充电" : "，未充电");
        } else {
            sb.append("Battery: ").append(level).append("%");
            sb.append(charging ? ", charging" : ", not charging");
        }
        if (tempC > 0) sb.append(", ").append(String.format("%.1f°C", tempC));

        XLog.d(TAG, "Battery info: " + sb);
        return ToolResult.success(sb.toString());
    }

    private ToolResult getWifiInfo(Context ctx) {
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled()) {
            return ToolResult.success(chinese(ctx) ? "WiFi：已关闭" : "WiFi: disabled");
        }

        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network activeNetwork = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities caps = activeNetwork != null ? cm.getNetworkCapabilities(activeNetwork) : null;

        WifiInfo info = wm.getConnectionInfo();
        if (info == null || info.getNetworkId() == -1) {
            return ToolResult.success(chinese(ctx) ? "WiFi：已开启但未连接" : "WiFi: enabled but not connected");
        }

        String ssid = info.getSSID();
        if (ssid != null) ssid = ssid.replace("\"", "");
        int rssi = info.getRssi();
        int freq = info.getFrequency();
        int speed = info.getLinkSpeed();
        String band = freq > 4900 ? "5GHz" : "2.4GHz";

        StringBuilder sb = new StringBuilder();
        if (chinese(ctx)) {
            sb.append("WiFi：已连接到「").append(ssid).append("」");
            sb.append("，").append(band);
            sb.append("，信号 ").append(rssi).append("dBm");
            sb.append("，").append(speed).append("Mbps");
        } else {
            sb.append("WiFi: connected to '").append(ssid).append("'");
            sb.append(", ").append(band);
            sb.append(", signal ").append(rssi).append("dBm");
            sb.append(", ").append(speed).append("Mbps");
        }

        XLog.d(TAG, "WiFi info: " + sb);
        return ToolResult.success(sb.toString());
    }

    private ToolResult getStorageInfo(Context ctx) {
        StatFs stat = new StatFs(Environment.getDataDirectory().getAbsolutePath());
        long totalBytes = stat.getTotalBytes();
        long freeBytes = stat.getAvailableBytes();
        long usedBytes = totalBytes - freeBytes;

        String total = formatBytes(totalBytes);
        String used = formatBytes(usedBytes);
        String free = formatBytes(freeBytes);
        int pct = (int) (usedBytes * 100 / totalBytes);

        String result = chinese(ctx)
                ? "存储：" + total + " 共已用 " + used + "（" + pct + "%），剩余 " + free
                : "Storage: " + used + " used of " + total + " (" + pct + "%), " + free + " free";
        XLog.d(TAG, "Storage info: " + result);
        return ToolResult.success(result);
    }

    private ToolResult getBluetoothInfo(Context ctx) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return ToolResult.success(chinese(ctx) ? "蓝牙：此设备不可用" : "Bluetooth: not available on this device");
        }
        if (!adapter.isEnabled()) {
            return ToolResult.success(chinese(ctx) ? "蓝牙：已关闭" : "Bluetooth: disabled");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(chinese(ctx) ? "蓝牙：已开启" : "Bluetooth: enabled");

        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null && !bonded.isEmpty()) {
                sb.append(chinese(ctx) ? "，已配对设备：" : ", paired devices: ");
                int i = 0;
                for (BluetoothDevice device : bonded) {
                    if (i > 0) sb.append(", ");
                    sb.append(device.getName() != null ? device.getName() : device.getAddress());
                    i++;
                    if (i >= 5) { sb.append("..."); break; }
                }
            }
        } catch (SecurityException e) {
            sb.append(chinese(ctx) ? "（缺少权限，无法列出设备）" : " (cannot list devices — permission denied)");
        }

        XLog.d(TAG, "Bluetooth info: " + sb);
        return ToolResult.success(sb.toString());
    }

    private ToolResult getScreenInfo(Context ctx) {
        StringBuilder sb = new StringBuilder();

        // Brightness
        try {
            int brightness = Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            int maxBrightness = 255;
            int pct = brightness * 100 / maxBrightness;
            sb.append(chinese(ctx) ? "亮度：" : "Brightness: ").append(pct).append("%");
        } catch (Settings.SettingNotFoundException e) {
            sb.append(chinese(ctx) ? "亮度：未知" : "Brightness: unknown");
        }

        // Dark mode
        int nightMode = ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        boolean isDark = nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        sb.append(chinese(ctx) ? "，深色模式：" : ", Dark mode: ");
        sb.append(isDark ? (chinese(ctx) ? "开启" : "ON") : (chinese(ctx) ? "关闭" : "OFF"));

        // Auto-brightness
        try {
            int autoBrightness = Settings.System.getInt(ctx.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE);
            sb.append(chinese(ctx) ? "，自动亮度：" : ", Auto-brightness: ");
            sb.append(autoBrightness == 1 ? (chinese(ctx) ? "开启" : "ON") : (chinese(ctx) ? "关闭" : "OFF"));
        } catch (Settings.SettingNotFoundException ignored) {}

        XLog.d(TAG, "Screen info: " + sb);
        return ToolResult.success(sb.toString());
    }

    private ToolResult getDeviceDetails(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Android ").append(android.os.Build.VERSION.RELEASE);
        sb.append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")");
        sb.append(chinese(ctx) ? "，型号：" : ", Model: ").append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL);
        sb.append(chinese(ctx) ? "，版本：" : ", Build: ").append(android.os.Build.DISPLAY);
        String security = android.os.Build.VERSION.SECURITY_PATCH;
        if (security != null && !security.isEmpty()) {
            sb.append(chinese(ctx) ? "，安全补丁：" : ", Security patch: ").append(security);
        }
        XLog.d(TAG, "Device info: " + sb);
        return ToolResult.success(sb.toString());
    }

    private ToolResult getCurrentTime(Context ctx) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.getDefault());
        String localTime = sdf.format(new java.util.Date());
        java.util.TimeZone tz = java.util.TimeZone.getDefault();
        String result = chinese(ctx)
                ? "当前时间：" + localTime + "（时区：" + tz.getID()
                + "，UTC 偏移：" + (tz.getRawOffset() / 3600000) + "h）"
                : "Current time: " + localTime + " (timezone: " + tz.getID()
                + ", UTC offset: " + (tz.getRawOffset() / 3600000) + "h)";
        XLog.d(TAG, "Time info: " + result);
        return ToolResult.success(result);
    }

    private boolean chinese(Context ctx) {
        return AppLocaleManager.INSTANCE.shouldUseChinese(ctx);
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1_000_000_000L) {
            return String.format("%.1f GB", bytes / 1_000_000_000.0);
        } else {
            return String.format("%.0f MB", bytes / 1_000_000.0);
        }
    }
}
