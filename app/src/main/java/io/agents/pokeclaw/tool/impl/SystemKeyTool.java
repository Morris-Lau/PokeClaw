// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;
import io.agents.pokeclaw.i18n.AppLocaleManager;
import io.agents.pokeclaw.service.ClawAccessibilityService;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SystemKeyTool extends BaseTool {

    @Override
    public String getName() {
        return "system_key";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_system_key);
    }

    @Override
    public String getDescriptionEN() {
        return "Press a system key. Supported keys: back (navigate back), home (go to home screen), recent_apps (open task switcher), notifications (expand notification shade), collapse_notifications (collapse notification/quick settings), lock_screen (lock screen, Android 9+), unlock_screen (wake up and unlock screen), enter (press Enter/submit), tab (press Tab).";
    }

    @Override
    public String getDescriptionCN() {
        return "Press a system key. Supported keys: back (go back), home (go to home screen), recent_apps (open recent tasks), notifications (expand notification bar), collapse_notifications (collapse notification bar/quick settings), lock_screen (lock screen, requires Android 9+), unlock_screen (wake and unlock screen), enter (press Enter/confirm), tab (press Tab).";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter(
                        "key",
                        "string",
                        "The system key to press. Must be one of: back, home, recent_apps, notifications, collapse_notifications, lock_screen, unlock_screen, enter, tab.",
                        true
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        boolean chinese = AppLocaleManager.INSTANCE.shouldUseChinese(ClawApplication.Companion.getInstance());
        if (service == null) {
            return ToolResult.error(chinese ? "无障碍服务未运行" : "Accessibility service is not running");
        }

        String key = requireString(params, "key");
        boolean success;
        String successMsg;

        switch (key) {
            case "back":
                success = service.pressBack();
                successMsg = chinese ? "已返回" : "Pressed Back button";
                break;
            case "home":
                success = service.pressHome();
                successMsg = chinese ? "已回到主页" : "Pressed Home button";
                break;
            case "recent_apps":
                success = service.openRecentApps();
                successMsg = chinese ? "已打开最近任务" : "Opened recent apps";
                break;
            case "notifications":
                success = service.expandNotifications();
                successMsg = chinese ? "已展开通知栏" : "Expanded notifications";
                break;
            case "collapse_notifications":
                success = service.collapseNotifications();
                successMsg = chinese ? "已收起通知栏" : "Collapsed notifications";
                break;
            case "lock_screen":
                success = service.lockScreen();
                successMsg = chinese ? "已锁屏" : "Screen locked";
                break;
            case "unlock_screen":
                success = service.unlockScreen();
                successMsg = chinese ? "已请求解锁屏幕" : "Screen unlock requested";
                break;
            case "enter":
                try {
                    Runtime.getRuntime().exec(new String[]{"input", "keyevent", String.valueOf(android.view.KeyEvent.KEYCODE_ENTER)}).waitFor();
                    success = true;
                } catch (Exception e) { success = false; }
                successMsg = chinese ? "已按下回车键" : "Pressed Enter key";
                break;
            case "tab":
                try {
                    Runtime.getRuntime().exec(new String[]{"input", "keyevent", String.valueOf(android.view.KeyEvent.KEYCODE_TAB)}).waitFor();
                    success = true;
                } catch (Exception e) { success = false; }
                successMsg = chinese ? "已按下 Tab 键" : "Pressed Tab key";
                break;
            default:
                return ToolResult.error(chinese
                        ? "未知系统按键：" + key + "。必须是：back, home, recent_apps, notifications, collapse_notifications, lock_screen, unlock_screen, enter, tab。"
                        : "Unknown system key: " + key + ". Must be one of: back, home, recent_apps, notifications, collapse_notifications, lock_screen, unlock_screen, enter, tab.");
        }

        return success ? ToolResult.success(successMsg)
                : ToolResult.error(chinese ? "执行 " + key + " 失败" : "Failed to execute " + key);
    }
}
