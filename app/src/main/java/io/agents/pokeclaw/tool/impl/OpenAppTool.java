// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl;

import android.content.Context;
import android.content.Intent;
import android.view.accessibility.AccessibilityNodeInfo;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;
import io.agents.pokeclaw.i18n.AppLocaleManager;
import io.agents.pokeclaw.service.ClawAccessibilityService;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;
import io.agents.pokeclaw.utils.XLog;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class OpenAppTool extends BaseTool {

    private static final String TAG = "OpenAppTool";

    /**
     * Common "Allow" button labels on chain-launch intercept dialogs (covering major manufacturers).
     * These are the on-screen button text strings matched against the device UI — do not translate.
     * Xiaomi/MIUI: "允许" (Allow)
     * Huawei/EMUI/HarmonyOS: "允许" / "允许打开" (Allow / Allow to open)
     * OPPO/ColorOS: "允许" / "打开" (Allow / Open)
     * vivo/OriginOS: "允许" (Allow)
     * Samsung OneUI: "允许" (Allow)
     */
    private static final List<String> ALLOW_KEYWORDS = Arrays.asList(
            "允许", "允许打开", "打开", "Allow", "ALLOW"
    );
    private static final List<String> POSITIVE_BUTTON_IDS = Arrays.asList(
            "android:id/button1",
            "miuix.appcompat:id/button1",
            "com.android.permissioncontroller:id/permission_allow_button",
            "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
            "com.android.permissioncontroller:id/permission_allow_one_time_button"
    );

    @Override
    public String getName() {
        return "open_app";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_open_app);
    }

    @Override
    public String getDescriptionEN() {
        return "Open an application by its package name (e.g. 'com.android.settings').";
    }

    @Override
    public String getDescriptionCN() {
        return "按应用名称或包名打开应用，例如 Chrome 或 com.android.settings。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("package_name", "string", "The package name of the app to open", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String packageName = params.containsKey("package_name")
                ? requireString(params, "package_name")
                : requireString(params, "app_name");
        String originalName = packageName;

        // If LLM sends app name instead of package name, resolve it
        if (!packageName.contains(".")) {
            String resolved = resolveAppName(packageName);
            if (resolved != null) {
                XLog.i(TAG, "Resolved app name '" + packageName + "' → '" + resolved + "'");
                packageName = resolved;
            }
        }

        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return launchWithPackageManager(packageName, originalName);
        }

        boolean success = service.openApp(packageName);
        if (!success) {
            ToolResult fallback = launchWithPackageManager(packageName, originalName);
            if (!fallback.isSuccess()) {
                return fallback;
            }
            return fallback;
        }

        // Wait for possible chain-launch intercept dialog and auto-click "Allow"
        dismissChainLaunchDialog(service);

        return ToolResult.success(openedMessage(packageName));
    }

    private ToolResult launchWithPackageManager(String packageName, String originalName) {
        Context context = ClawApplication.Companion.getInstance();
        boolean chinese = AppLocaleManager.INSTANCE.shouldUseChinese(context);
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent == null) {
                String target = originalName == null || originalName.trim().isEmpty() ? packageName : originalName;
                return ToolResult.error(chinese
                        ? "未找到应用：" + target
                        : "App not found: " + target);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            XLog.i(TAG, "Launched app via PackageManager: " + packageName);
            return ToolResult.success(openedMessage(packageName));
        } catch (Exception e) {
            XLog.w(TAG, "Failed to launch app via PackageManager: " + packageName, e);
            return ToolResult.error(chinese
                    ? "无法打开应用：" + originalName
                    : "Failed to open app: " + originalName);
        }
    }

    private String openedMessage(String packageName) {
        Context context = ClawApplication.Companion.getInstance();
        return AppLocaleManager.INSTANCE.shouldUseChinese(context)
                ? "已打开应用：" + packageName
                : "Opened app: " + packageName;
    }

    /**
     * Some manufacturers (Xiaomi, Huawei, OPPO, vivo, etc.) show an intercept dialog
     * ("Allow xxx to open yyy?") when launching an app from the background.
     * This method waits for the dialog and auto-clicks the "Allow" button.
     * Checks up to 3 times, 500ms apart; silently returns if no dialog appears.
     */
    private void dismissChainLaunchDialog(ClawAccessibilityService service) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (tapPositiveDialogButton(service)) {
                return;
            }

            for (String keyword : ALLOW_KEYWORDS) {
                List<AccessibilityNodeInfo> nodes = service.findNodesByText(keyword);
                try {
                    for (AccessibilityNodeInfo node : nodes) {
                        CharSequence text = node.getText();
                        if (text != null && matchesAllowButton(text.toString())) {
                            boolean clicked = service.clickNode(node);
                            XLog.i(TAG, "Chain launch dialog: tapped \"" + text + "\" " + (clicked ? "success" : "failed"));
                            if (clicked) {
                                ClawAccessibilityService.recycleNodes(nodes);
                                return;
                            }
                        }
                    }
                } finally {
                    ClawAccessibilityService.recycleNodes(nodes);
                }
            }
        }
    }

    private boolean tapPositiveDialogButton(ClawAccessibilityService service) {
        for (String viewId : POSITIVE_BUTTON_IDS) {
            List<AccessibilityNodeInfo> nodes = service.findNodesById(viewId);
            try {
                for (AccessibilityNodeInfo node : nodes) {
                    if (!node.isVisibleToUser() || !node.isEnabled()) {
                        continue;
                    }
                    boolean clicked = service.clickNode(node);
                    XLog.i(TAG, "Chain launch dialog: tapped positive button id " + viewId + " " + (clicked ? "success" : "failed"));
                    if (clicked) {
                        return true;
                    }
                }
            } finally {
                ClawAccessibilityService.recycleNodes(nodes);
            }
        }
        return false;
    }

    /**
     * Resolve common app names to package names.
     * Falls back to searching installed apps by label.
     */
    /** Public static version for other tools to reuse */
    public static String resolveAppNameStatic(String appName) {
        return new OpenAppTool().resolveAppName(appName);
    }

    private String resolveAppName(String appName) {
        String lower = appName.toLowerCase().trim();
        // Common app name → package name mapping
        switch (lower) {
            case "whatsapp": return "com.whatsapp";
            case "telegram": return "org.telegram.messenger";
            case "instagram": return "com.instagram.android";
            case "youtube": return "com.google.android.youtube";
            case "chrome": return "com.android.chrome";
            case "camera": return "com.android.camera2";
            case "settings": return "com.android.settings";
            case "messages": return "com.google.android.apps.messaging";
            case "gmail": return "com.google.android.gm";
            case "maps": return "com.google.android.apps.maps";
            case "phone": return "com.google.android.dialer";
            case "contacts": return "com.google.android.contacts";
            case "calendar": return "com.google.android.calendar";
            case "clock": return "com.google.android.deskclock";
            case "calculator": return "com.google.android.calculator";
            case "files": return "com.google.android.documentsui";
            case "photos": return "com.google.android.apps.photos";
            case "spotify": return "com.spotify.music";
            case "twitter": case "x": return "com.twitter.android";
            case "facebook": return "com.facebook.katana";
            case "tiktok": return "com.zhiliaoapp.musically";
            case "snapchat": return "com.snapchat.android";
            case "reddit": return "com.reddit.frontpage";
            case "discord": return "com.discord";
            case "slack": return "com.Slack";
            case "wechat": return "com.tencent.mm";
            case "line": return "jp.naver.line.android";
            default: break;
        }
        // Try to find by searching installed app labels AND package names
        try {
            android.content.pm.PackageManager pm = ClawApplication.Companion.getInstance().getPackageManager();
            String bestMatch = null;
            int bestScore = 0;

            for (android.content.pm.ApplicationInfo app : pm.getInstalledApplications(0)) {
                // Skip system apps without launcher intent
                if (pm.getLaunchIntentForPackage(app.packageName) == null) continue;

                CharSequence label = pm.getApplicationLabel(app);
                String labelStr = label != null ? label.toString().toLowerCase() : "";
                String pkgLower = app.packageName.toLowerCase();

                // Exact label match = best
                if (labelStr.equalsIgnoreCase(appName)) {
                    return app.packageName;
                }
                // Label contains search term
                if (labelStr.contains(lower) && lower.length() >= 3) {
                    int score = 10 + lower.length();
                    if (score > bestScore) { bestScore = score; bestMatch = app.packageName; }
                }
                // Package name contains search term (e.g. "taobao" in "com.taobao.taobao")
                // Also try without spaces (user: "genshin impact" → pkg: "genshinimpact")
                String lowerNoSpace = lower.replace(" ", "");
                if ((pkgLower.contains(lower) || pkgLower.contains(lowerNoSpace)) && lower.length() >= 3) {
                    int score = 5 + lower.length();
                    // Bonus: last segment matches exactly (com.taobao.taobao → "taobao" = last segment)
                    String[] segments = pkgLower.split("\\.");
                    if (segments.length > 0 && segments[segments.length - 1].equals(lower)) {
                        score += 20;
                    }
                    if (score > bestScore) { bestScore = score; bestMatch = app.packageName; }
                }
            }
            if (bestMatch != null) {
                XLog.i(TAG, "resolveAppName: fuzzy matched '" + appName + "' → '" + bestMatch + "' (score=" + bestScore + ")");
                return bestMatch;
            }
        } catch (Exception e) {
            XLog.w(TAG, "resolveAppName: failed to search installed apps", e);
        }
        return null;
    }

    /**
     * Exact match for allow button labels, to avoid accidentally tapping other elements whose content contains the keyword
     */
    private boolean matchesAllowButton(String text) {
        String trimmed = text.trim();
        for (String keyword : ALLOW_KEYWORDS) {
            if (trimmed.equals(keyword)) return true;
        }
        return false;
    }
}
