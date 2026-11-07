package com.eta.carrouter;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared configuration between the settings UI (app process) and the hook
 * (system_server). Stored in Settings.Global so it is readable from both without
 * any IPC, file or socket. The UI writes via WRITE_SECURE_SETTINGS; the hook
 * reads directly through the framework (no permission needed as system_server).
 *
 * Keys are also overridable through system properties as a fallback.
 */
public final class Config {

    public static final String KEY_ENABLED = "carrouter_enabled";
    public static final String KEY_SSIDS = "carrouter_ssids";         // newline separated
    public static final String KEY_APPS = "carrouter_apps";           // comma separated
    public static final String KEY_HIDE = "carrouter_hide_launcher";

    private Config() {}

    private static String get(Context ctx, String key, String def) {
        // Settings.Global (written by the UI) wins. System property is only a
        // fallback for headless/advanced use when Global has no value yet.
        try {
            String v = Settings.Global.getString(ctx.getContentResolver(), key);
            if (v != null && !v.isEmpty()) return v;
        } catch (Throwable ignored) {
        }
        String p = sysProp(propName(key), null);
        if (p != null && !p.isEmpty()) return p;
        return def;
    }

    private static void put(Context ctx, String key, String value) {
        try {
            Settings.Global.putString(ctx.getContentResolver(), key, value);
        } catch (Throwable ignored) {
        }
    }

    private static String propName(String key) {
        if (KEY_SSIDS.equals(key)) return "persist.carrouter.ssid";
        if (KEY_APPS.equals(key)) return "persist.carrouter.apps";
        return "persist." + key;
    }

    private static String sysProp(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class).invoke(null, key, def);
            return v == null ? def : (String) v;
        } catch (Throwable t) {
            return def;
        }
    }

    // ---- enabled ----
    public static boolean isEnabled(Context ctx) {
        String v = get(ctx, KEY_ENABLED, "1");
        return !"0".equals(v);
    }

    public static void setEnabled(Context ctx, boolean on) {
        put(ctx, KEY_ENABLED, on ? "1" : "0");
    }

    // ---- selected car hotspot SSIDs ----
    public static Set<String> ssids(Context ctx) {
        Set<String> out = new LinkedHashSet<>();
        String raw = get(ctx, KEY_SSIDS, "");
        if (raw == null) return out;
        for (String s : raw.split("[\\r\\n]+")) {
            s = unquote(s.trim());
            if (!s.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(s)) out.add(s);
        }
        return out;
    }

    public static void setSsids(Context ctx, Set<String> ssids) {
        StringBuilder b = new StringBuilder();
        for (String s : ssids) {
            s = unquote(s.trim());
            if (s.isEmpty()) continue;
            if (b.length() > 0) b.append('\n');
            b.append(s);
        }
        put(ctx, KEY_SSIDS, b.toString());
    }

    // ---- car-link app packages ----
    public static Set<String> apps(Context ctx) {
        Set<String> out = new LinkedHashSet<>();
        String raw = get(ctx, KEY_APPS, defaultApps());
        if (raw == null) return out;
        for (String s : raw.split("[,;\\s]+")) {
            s = s.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    public static void setApps(Context ctx, Set<String> apps) {
        StringBuilder b = new StringBuilder();
        for (String s : apps) {
            s = s.trim();
            if (s.isEmpty()) continue;
            if (b.length() > 0) b.append(',');
            b.append(s);
        }
        put(ctx, KEY_APPS, b.toString());
    }

    private static String defaultApps() {
        return "com.baidu.carlife.xiaomi,com.mcarplay.app,com.baidu.carlife,"
                + "com.qinggan.carlink,com.google.android.projection.gearhead";
    }

    // ---- launcher icon ----
    public static boolean isHidden(Context ctx) {
        return "1".equals(get(ctx, KEY_HIDE, "0"));
    }

    public static void setHidden(Context ctx, boolean hidden) {
        put(ctx, KEY_HIDE, hidden ? "1" : "0");
        applyLauncherState(ctx, hidden);
    }

    public static void applyLauncherState(Context ctx, boolean hidden) {
        PackageManager pm = ctx.getPackageManager();
        int state = hidden
                ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        // Disable only the launcher alias. The SettingsActivity itself stays
        // enabled so LSPosed Manager / am start can still open it.
        try {
            pm.setComponentEnabledSetting(
                    new android.content.ComponentName(ctx, "com.eta.carrouter.Launcher"),
                    state, PackageManager.DONT_KILL_APP);
        } catch (Throwable ignored) {
        }
    }

    public static boolean hasWriteSecureSettings(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static String unquote(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\""))
            s = s.substring(1, s.length() - 1);
        return s;
    }

    public static List<String> toList(Set<String> set) {
        return new ArrayList<>(set);
    }
}
