package com.eta.carrouter;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Settings UI for CarHotspotRouter.
 *
 * - Pick which Wi-Fi networks are "car head-unit hotspots" (selected networks
 *   lose INTERNET capability so all apps keep using mobile data, while the
 *   car-link app is bound back to that Wi-Fi LAN).
 * - Toggle the module, pick car-link apps, hide / show the launcher icon.
 *
 * Configuration is written to Settings.Global (needs WRITE_SECURE_SETTINGS,
 * grant once with: pm grant com.eta.carrouter android.permission.WRITE_SECURE_SETTINGS).
 * The hook in system_server reads the same keys, so changes take effect on the
 * next network event without a reboot.
 */
public class SettingsActivity extends Activity {

    private LinearLayout root;
    private final Set<String> selectedSsids = new LinkedHashSet<>();
    private final Set<String> selectedApps = new LinkedHashSet<>();
    private LinearLayout ssidBox;
    private LinearLayout appBox;
    private Switch swEnabled;
    private Switch swHide;
    private TextView tvGrant;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("CarHotspotRouter");

        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);

        selectedSsids.addAll(Config.ssids(this));
        selectedApps.addAll(Config.apps(this));

        buildPermissionBanner();
        buildMasterSwitch();
        buildWifiSection();
        buildAppSection();
        buildHideSection();
        buildActions();
    }

    // ---------------- permission ----------------
    private void buildPermissionBanner() {
        tvGrant = new TextView(this);
        tvGrant.setTextSize(13);
        if (Config.hasWriteSecureSettings(this)) {
            tvGrant.setText("✓ 已授予写入权限，配置可直接生效");
        } else {
            tvGrant.setText("⚠ 缺少写入权限。请用 root 执行一次：\n"
                    + "pm grant com.eta.carrouter android.permission.WRITE_SECURE_SETTINGS\n"
                    + "（或本模块已内置 Xposed 自授权，重启后自动生效）");
            tvGrant.setTextColor(0xFFB00020);
        }
        root.addView(tvGrant);
        root.addView(space(8));
    }

    // ---------------- master switch ----------------
    private void buildMasterSwitch() {
        root.addView(section("模块"));
        swEnabled = new Switch(this);
        swEnabled.setText("启用分流（关闭后模块不干预任何网络）");
        swEnabled.setChecked(Config.isEnabled(this));
        root.addView(swEnabled);
        root.addView(space(6));
    }

    // ---------------- wifi selection ----------------
    private void buildWifiSection() {
        root.addView(section("车机互联热点（选中的 WiFi 只做车机互联，应用走手机流量）"));

        Button btnCurrent = new Button(this);
        btnCurrent.setText("把【当前连接的 WiFi】设为车机热点");
        btnCurrent.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String ssid = currentSsid();
                if (ssid.isEmpty()) {
                    toast("当前未连接 WiFi（或无法读取 SSID）");
                    return;
                }
                if (selectedSsids.add(ssid)) {
                    rebuildSsidList();
                    toast("已添加：" + ssid);
                } else {
                    toast("已在列表中：" + ssid);
                }
            }
        });
        root.addView(btnCurrent);

        TextView cur = new TextView(this);
        cur.setText("当前连接：" + (currentSsid().isEmpty() ? "（无）" : currentSsid()));
        cur.setTextSize(12);
        root.addView(cur);
        root.addView(space(4));

        TextView saved = new TextView(this);
        saved.setText("已保存/周围的 WiFi（勾选即生效）：");
        saved.setTextSize(13);
        root.addView(saved);

        ssidBox = new LinearLayout(this);
        ssidBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(ssidBox);
        rebuildSsidList();

        // manual add
        final EditText et = new EditText(this);
        et.setHint("手动输入 SSID（精确匹配）");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(et);
        Button add = new Button(this);
        add.setText("添加 SSID");
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String s = Config.unquote(et.getText().toString());
                if (s.isEmpty()) { toast("请输入 SSID"); return; }
                if (selectedSsids.add(s)) { rebuildSsidList(); et.setText(""); toast("已添加：" + s); }
                else toast("已在列表中：" + s);
            }
        });
        root.addView(add);
        root.addView(space(6));
    }

    private void rebuildSsidList() {
        ssidBox.removeAllViews();
        // candidate list = saved networks + currently connected + already selected
        LinkedHashSet<String> candidates = new LinkedHashSet<>(selectedSsids);
        String cur = currentSsid();
        if (!cur.isEmpty()) candidates.add(cur);
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                List<WifiConfiguration> cfgs = wm.getConfiguredNetworks();
                if (cfgs != null) {
                    for (WifiConfiguration c : cfgs) {
                        if (c.SSID != null) candidates.add(Config.unquote(c.SSID));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        for (final String s : candidates) {
            CheckBox cb = new CheckBox(this);
            cb.setText(s);
            cb.setChecked(selectedSsids.contains(s));
            cb.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selectedSsids.add(s); else selectedSsids.remove(s);
            });
            ssidBox.addView(cb);
        }
        if (candidates.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("（读取不到已保存网络，可用上方按钮或手动输入添加）");
            t.setTextSize(12);
            ssidBox.addView(t);
        }
    }

    // ---------------- car app selection ----------------
    private void buildAppSection() {
        root.addView(section("车机互联应用（这些应用单独绑定回车机 WiFi 局域网）"));
        TextView hint = new TextView(this);
        hint.setText("勾选你手机上的车机互联 / 投屏 App；被勾选的 App 走车机局域网，其余 App 一律走手机流量。");
        hint.setTextSize(12);
        root.addView(hint);

        appBox = new LinearLayout(this);
        appBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(appBox);
        rebuildAppList();

        final EditText et = new EditText(this);
        et.setHint("手动输入包名，如 com.baidu.carlife");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(et);
        Button add = new Button(this);
        add.setText("添加包名");
        add.setOnClickListener(v -> {
            String s = et.getText().toString().trim();
            if (s.isEmpty()) { toast("请输入包名"); return; }
            if (selectedApps.add(s)) { rebuildAppList(); et.setText(""); toast("已添加：" + s); }
            else toast("已在列表中：" + s);
        });
        root.addView(add);
        root.addView(space(6));
    }

    private void rebuildAppList() {
        appBox.removeAllViews();
        PackageManager pm = getPackageManager();
        LinkedHashSet<String> candidates = new LinkedHashSet<>(selectedApps);
        // add installed apps whose name hints at car / projection / mirroring
        List<ApplicationInfo> infos = pm.getInstalledApplications(0);
        List<String> found = new ArrayList<>();
        for (ApplicationInfo ai : infos) {
            String p = ai.packageName;
            String label = String.valueOf(pm.getApplicationLabel(ai));
            String hay = (p + " " + label).toLowerCase();
            if (hay.contains("carlife") || hay.contains("carplay") || hay.contains("carlink")
                    || hay.contains("car") || hay.contains("gearhead")
                    || hay.contains("projection") || hay.contains("mirror")
                    || hay.contains("hicar") || hay.contains("jixin")
                    || hay.contains("cloudrive") || hay.contains("chery")) {
                found.add(p);
            }
        }
        Collections.sort(found);
        for (String p : found) candidates.add(p);

        for (final String p : candidates) {
            CheckBox cb = new CheckBox(this);
            String label = p;
            try { label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0)))
                    + "\n" + p; } catch (Throwable ignored) {}
            cb.setText(label);
            cb.setChecked(selectedApps.contains(p));
            cb.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selectedApps.add(p); else selectedApps.remove(p);
            });
            appBox.addView(cb);
        }
    }

    // ---------------- hide launcher ----------------
    private void buildHideSection() {
        root.addView(section("桌面图标"));
        swHide = new Switch(this);
        swHide.setText("从桌面隐藏图标");
        swHide.setChecked(Config.isHidden(this));
        root.addView(swHide);
        TextView note = new TextView(this);
        note.setText("隐藏后模块仍正常生效。可用以下方式再次打开设置：\n"
                + "· LSPosed 管理器 → 模块 → CarHotspotRouter\n"
                + "· 终端：am start -n com.eta.carrouter/.SettingsActivity\n"
                + "· 或直接重新运行本模块安装脚本恢复");
        note.setTextSize(12);
        root.addView(note);
        root.addView(space(4));
    }

    // ---------------- actions ----------------
    private void buildActions() {
        Button save = new Button(this);
        save.setText("保存并应用");
        save.setOnClickListener(v -> saveAll());
        root.addView(save);

        Button reboot = new Button(this);
        reboot.setText("保存并重启系统（使 hook 立即注入）");
        reboot.setOnClickListener(v -> {
            saveAll();
            try { Runtime.getRuntime().exec(new String[]{"su", "-c", "svc power reboot"}); }
            catch (Throwable t) { try { Runtime.getRuntime().exec("reboot"); } catch (Throwable ignored) {} }
        });
        root.addView(reboot);
        root.addView(space(8));

        TextView info = new TextView(this);
        info.setText("原理：选中 WiFi 连上后，模块在 system_server 里剥掉该 WiFi 的 "
                + "NET_CAPABILITY_INTERNET，系统继续把手机流量当默认网络；"
                + "被勾选的车机 App 则单独 bind 回该 WiFi 访问车机局域网。");
        info.setTextSize(12);
        root.addView(info);
    }

    private void saveAll() {
        boolean ok = Config.hasWriteSecureSettings(this);
        Config.setEnabled(this, swEnabled.isChecked());
        Config.setSsids(this, selectedSsids);
        Config.setApps(this, selectedApps);
        Config.setHidden(this, swHide.isChecked()); // also applies launcher state
        syncScopeToLsposed();                       // best-effort: add selected apps to LSPosed scope
        if (!ok) {
            toast("已尝试写入，但可能缺少 WRITE_SECURE_SETTINGS 权限，请用 root 授权");
        } else {
            toast("已保存：" + selectedSsids.size() + " 个车机热点，"
                    + selectedApps.size() + " 个车机 App");
        }
        tvGrant.setText(Config.hasWriteSecureSettings(this)
                ? "✓ 已授予写入权限，配置可直接生效"
                : "⚠ 缺少写入权限，请用 root 执行 pm grant ...");
    }

    /**
     * Best-effort: try to add the selected car-link packages into LSPosed's
     * scope database so the hook is actually injected into them. Requires root
     * (su). If unavailable, the user can tick them manually in LSPosed Manager.
     * Uses the sqlite3 binary shipped by the installer at /data/local/tmp/.
     */
    private void syncScopeToLsposed() {
        try {
            StringBuilder sql = new StringBuilder();
            sql.append("INSERT OR IGNORE INTO scope(module_pkg_name,app_pkg_name,user_id) VALUES('com.eta.carrouter','system',0);");
            for (String p : selectedApps) {
                sql.append("INSERT OR IGNORE INTO scope(module_pkg_name,app_pkg_name,user_id) VALUES('com.eta.carrouter','")
                        .append(p.replace("'", "''")).append("',0);");
            }
            sql.append("PRAGMA wal_checkpoint(TRUNCATE);");
            String cmd = "/data/local/tmp/sqlite3.arm64 /data/adb/lspd/config/modules_config.db \""
                    + sql.toString().replace("\"", "\\\"") + "\"";
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            try { p.waitFor(); } catch (InterruptedException ignored) {}
        } catch (Throwable ignored) {
        }
    }

    // ---------------- helpers ----------------
    private String currentSsid() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return "";
            WifiInfo wi = wm.getConnectionInfo();
            return wi == null ? "" : Config.unquote(wi.getSSID());
        } catch (Throwable t) {
            return "";
        }
    }

    private TextView section(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private View space(int d) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(d)));
        return v;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
