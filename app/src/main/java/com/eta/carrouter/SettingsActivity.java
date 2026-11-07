package com.eta.carrouter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CarHotspotRouter settings UI (v2.1).
 *
 * Dark card design. Wi-Fi networks are picked by name from the list of networks
 * the phone has connected to before (read with root from WifiConfigStore.xml,
 * with a framework fallback). Car apps are shown as icon + label + package.
 * Selections are written to Settings.Global and read live by the hook.
 */
public class SettingsActivity extends Activity {

    // ---- palette ----
    private static final int C_BG        = 0xFF0E1116;
    private static final int C_CARD      = 0xFF171B22;
    private static final int C_CARD_HI   = 0xFF1D232D;
    private static final int C_ACCENT    = 0xFF4C8DFF;
    private static final int C_ACCENT_DIM= 0xFF2A4A80;
    private static final int C_TEXT      = 0xFFE8EAF0;
    private static final int C_TEXT_SUB  = 0xFF9AA3B2;
    private static final int C_OK        = 0xFF5AD196;
    private static final int C_WARN      = 0xFFFFB454;

    private static final int CARD_RADIUS = 18;

    private static final Pattern SSID_XML = Pattern.compile(
            "<string name=\"SSID\">([^<]*)</string>");

    private LinearLayout content;
    private LinearLayout wifiList;
    private LinearLayout appList;
    private Switch swEnabled;
    private Switch swHide;
    private TextView tvPerm;
    private TextView tvStatus;
    private boolean permOk;

    private final Set<String> selectedSsids = new LinkedHashSet<>();
    private final Set<String> selectedApps = new LinkedHashSet<>();
    private final List<String> knownSsids = new ArrayList<>();
    private String currentSsid = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        permOk = Config.hasWriteSecureSettings(this);
        selectedSsids.addAll(Config.ssids(this));
        selectedApps.addAll(Config.apps(this));
        currentSsid = readCurrentSsid();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);
        scroll.setFillViewport(true);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int m = dp(18);
        page.setPadding(m, dp(24), m, dp(28));
        scroll.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        buildHeader(page);
        buildStatusCard(page);
        buildWifiCard(page);
        buildAppCard(page);
        buildHideCard(page);
        buildActions(page);

        loadKnownNetworksAsync();
        loadAppListAsync();
    }

    // ======================================================================
    // header + status
    // ======================================================================

    private void buildHeader(LinearLayout page) {
        TextView title = new TextView(this);
        title.setText("CarHotspotRouter");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(C_TEXT);
        page.addView(title);

        TextView sub = new TextView(this);
        sub.setText("选中的 WiFi 只做车机互联，其它应用继续走手机流量");
        sub.setTextSize(13);
        sub.setTextColor(C_TEXT_SUB);
        sub.setPadding(0, dp(4), 0, 0);
        page.addView(sub);
        page.addView(gap(16));
    }

    private void buildStatusCard(LinearLayout page) {
        LinearLayout card = card(page);

        LinearLayout row = h();
        TextView t = label("模块", 15, true);
        t.setLayoutParams(weight1());
        row.addView(t);
        swEnabled = new Switch(this);
        swEnabled.setChecked(Config.isEnabled(this));
        swEnabled.getTrackDrawable().setColorFilter(C_ACCENT_DIM, android.graphics.PorterDuff.Mode.SRC_ATOP);
        row.addView(swEnabled);
        card.addView(row);

        tvPerm = label("", 12, false);
        tvPerm.setPadding(0, dp(8), 0, 0);
        card.addView(tvPerm);
        refreshPermText();

        tvStatus = label("", 12, false);
        tvStatus.setPadding(0, dp(4), 0, 0);
        card.addView(tvStatus);
        refreshStatusText();
    }

    private void refreshPermText() {
        if (permOk) {
            tvPerm.setText("✓ 配置写入权限已授予，保存即生效");
            tvPerm.setTextColor(C_OK);
        } else {
            tvPerm.setText("⚠ 缺少写入权限，请执行：\n"
                    + "su -c 'pm grant com.eta.carrouter android.permission.WRITE_SECURE_SETTINGS'");
            tvPerm.setTextColor(C_WARN);
        }
    }

    private void refreshStatusText() {
        boolean active = Config.isEnabled(this) && !selectedSsids.isEmpty();
        String cur = currentSsid.isEmpty() ? "未连接 WiFi" : currentSsid;
        if (active && selectedSsids.contains(currentSsid)) {
            tvStatus.setText("● 分流中：" + cur + " 走车机局域网，其余应用走流量");
            tvStatus.setTextColor(C_OK);
        } else if (active) {
            tvStatus.setText("○ 已就绪，等待连接：\n" + joinNames(selectedSsids));
            tvStatus.setTextColor(C_TEXT_SUB);
        } else {
            tvStatus.setText("○ 模块未启用或未选择热点");
            tvStatus.setTextColor(C_TEXT_SUB);
        }
    }

    // ======================================================================
    // wifi card
    // ======================================================================

    private void buildWifiCard(LinearLayout page) {
        LinearLayout card = card(page);
        card.addView(label("车机互联热点", 16, true));
        card.addView(label("勾选的 WiFi 只承载车机互联；连接它们时其它应用全部走手机流量",
                12, false));

        LinearLayout curRow = h();
        curRow.setPadding(0, dp(12), 0, 0);
        TextView cur = label("当前连接：" + (currentSsid.isEmpty() ? "无" : currentSsid),
                13, false);
        cur.setLayoutParams(weight1());
        curRow.addView(cur);

        if (!currentSsid.isEmpty()) {
            View btnUse = pillButton("设为热点");
            btnUse.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    toggleSsid(currentSsid);
                }
            });
            curRow.addView(btnUse);
        }
        card.addView(curRow);

        TextView listTitle = label("我连接过的 WiFi", 12, false);
        listTitle.setPadding(0, dp(14), 0, dp(6));
        card.addView(listTitle);

        wifiList = new LinearLayout(this);
        wifiList.setOrientation(LinearLayout.VERTICAL);
        card.addView(wifiList);

        TextView loading = label("正在读取已保存的网络…", 12, false);
        loading.setId(101);
        wifiList.addView(loading);

        View manual = simpleRow("＋  手动添加 SSID", null);
        manual.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { promptAddSsid(); }
        });
        LinearLayout wrap = cardWrap();
        wrap.addView(manual);
        card.addView(wrap);
        page.addView(gap(14));
    }

    private void rebuildWifiList() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                wifiList.removeAllViews();
                LinkedHashSet<String> all = new LinkedHashSet<>();
                if (!currentSsid.isEmpty()) all.add(currentSsid);
                all.addAll(knownSsids);
                all.addAll(selectedSsids);

                if (all.isEmpty()) {
                    wifiList.addView(label("读取不到已保存网络，请手动添加", 12, false));
                    return;
                }
                List<String> sorted = new ArrayList<>(all);
                Collections.sort(sorted, new Comparator<String>() {
                    @Override public int compare(String a, String b) {
                        boolean sa = selectedSsids.contains(a), sb = selectedSsids.contains(b);
                        if (sa != sb) return sa ? -1 : 1;
                        return a.compareToIgnoreCase(b);
                    }
                });
                for (String s : sorted) {
                    String sub = s.equals(currentSsid) ? "当前连接"
                            : selectedSsids.contains(s) ? "已选中"
                            : knownSsids.contains(s) ? "已保存的网络" : "自定义";
                    wifiList.addView(selectableRow(s, sub, selectedSsids.contains(s),
                            new Runnable() { public void run() { } }, s));
                }
                refreshStatusText();
            }
        });
    }

    private void promptAddSsid() {
        final EditText et = new EditText(this);
        et.setHint("SSID 名称");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setTextColor(C_TEXT);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), 0, dp(24), 0);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("添加 WiFi 名称")
                .setView(box)
                .setPositiveButton("添加", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String s = Config.unquote(et.getText().toString());
                        if (!s.isEmpty()) toggleSsid(s);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toggleSsid(String ssid) {
        if (selectedSsids.contains(ssid)) selectedSsids.remove(ssid);
        else selectedSsids.add(ssid);
        persist();
        rebuildWifiList();
    }

    /** root-read the list of networks this phone has connected to before */
    private void loadKnownNetworksAsync() {
        new Thread(new Runnable() {
            @Override public void run() {
                Set<String> found = new LinkedHashSet<>();

                // 1) root: parse WifiConfigStore.xml (most reliable, no location permission)
                String xml = su("cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml");
                if (xml == null || xml.isEmpty()) {
                    xml = su("cat /data/misc/wifi/WifiConfigStore.xml");
                }
                if (xml != null && !xml.isEmpty()) {
                    Matcher m = SSID_XML.matcher(xml);
                    while (m.find()) {
                        String s = Config.unquote(htmlDecode(m.group(1)));
                        if (!s.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(s)) found.add(s);
                    }
                }

                // 2) framework fallback (works if platform allows)
                try {
                    WifiManager wm = (WifiManager) getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
                    List<WifiConfiguration> cfgs = wm == null ? null : wm.getConfiguredNetworks();
                    if (cfgs != null) {
                        for (WifiConfiguration c : cfgs) {
                            if (c.SSID != null) {
                                String s = Config.unquote(c.SSID);
                                if (!s.isEmpty()) found.add(s);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }

                synchronized (knownSsids) {
                    knownSsids.clear();
                    knownSsids.addAll(found);
                }
                rebuildWifiList();
            }
        }).start();
    }

    // ======================================================================
    // app card
    // ======================================================================

    private void buildAppCard(LinearLayout page) {
        LinearLayout card = card(page);
        card.addView(label("车机互联应用", 16, true));
        card.addView(label("这些应用连上车机热点时走车机局域网，其余应用一律走流量。以名称+包名显示。",
                12, false));
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        appList.setPadding(0, dp(10), 0, 0);
        card.addView(appList);

        View manual = simpleRow("＋  手动添加包名", null);
        manual.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { promptAddApp(); }
        });
        LinearLayout wrap = cardWrap();
        wrap.addView(manual);
        card.addView(wrap);
        page.addView(gap(14));
    }

    private void loadAppListAsync() {
        new Thread(new Runnable() {
            @Override public void run() {
                final List<ApplicationInfo> found = new ArrayList<>();
                try {
                    PackageManager pm = getPackageManager();
                    for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                        String p = ai.packageName;
                        String lbl;
                        try { lbl = String.valueOf(pm.getApplicationLabel(ai)); }
                        catch (Throwable t) { lbl = p; }
                        String hay = (p + " " + lbl).toLowerCase();
                        if (hay.contains("carlife") || hay.contains("carplay")
                                || hay.contains("carlink") || hay.contains("gearhead")
                                || hay.contains("hicar") || hay.contains("carbit")
                                || hay.contains("cloudrive") || hay.contains("chery")
                                || hay.contains("carconnect") || hay.contains("projection")
                                || hay.contains("mcarplay") || hay.contains("welink")
                                || hay.contains("jixin") || hay.contains("车机")
                                || hay.contains("互联") || hay.contains("投屏")) {
                            found.add(ai);
                        }
                    }
                    Collections.sort(found, new Comparator<ApplicationInfo>() {
                        @Override public int compare(ApplicationInfo a, ApplicationInfo b) {
                            return a.packageName.compareToIgnoreCase(b.packageName);
                        }
                    });
                } catch (Throwable ignored) {
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() { rebuildAppList(found); }
                });
            }
        }).start();
    }

    private void rebuildAppList(List<ApplicationInfo> found) {
        appList.removeAllViews();
        PackageManager pm = getPackageManager();

        LinkedHashSet<String> shown = new LinkedHashSet<>(selectedApps);
        for (ApplicationInfo ai : found) shown.add(ai.packageName);

        List<String> ordered = new ArrayList<>();
        // selected first
        for (String p : selectedApps) ordered.add(p);
        for (ApplicationInfo ai : found) {
            if (!selectedApps.contains(ai.packageName)) ordered.add(ai.packageName);
        }

        for (final String pkg : ordered) {
            String name = pkg, sub = pkg;
            android.graphics.drawable.Drawable icon = null;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                name = String.valueOf(pm.getApplicationLabel(ai));
                icon = ai.loadIcon(pm);
                sub = pkg;
            } catch (Throwable ignored) {
            }
            final boolean checked = selectedApps.contains(pkg);
            appList.addView(appRow(icon, name, sub, checked, pkg));
        }
    }

    private void promptAddApp() {
        final EditText et = new EditText(this);
        et.setHint("例如 com.baidu.carlife");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setTextColor(C_TEXT);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), 0, dp(24), 0);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("添加应用包名")
                .setView(box)
                .setPositiveButton("添加", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String s = et.getText().toString().trim();
                        if (!s.isEmpty()) {
                            selectedApps.add(s);
                            persist();
                            loadAppListAsync();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ======================================================================
    // hide card
    // ======================================================================

    private void buildHideCard(LinearLayout page) {
        LinearLayout card = card(page);
        LinearLayout row = h();
        LinearLayout texts = v();
        TextView t = label("从桌面隐藏图标", 15, true);
        texts.addView(t);
        texts.addView(label("模块继续生效；可从 LSPosed 管理器或\nam start -n com.eta.carrouter/.SettingsActivity 重新打开",
                12, false));
        texts.setLayoutParams(weight1());
        row.addView(texts);
        swHide = new Switch(this);
        swHide.setChecked(Config.isHidden(this));
        swHide.getTrackDrawable().setColorFilter(C_ACCENT_DIM, android.graphics.PorterDuff.Mode.SRC_ATOP);
        row.addView(swHide);
        card.addView(row);
        page.addView(gap(14));
    }

    // ======================================================================
    // actions
    // ======================================================================

    private void buildActions(LinearLayout page) {
        View save = bigButton("保存并应用", C_ACCENT, Color.WHITE);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveAll(); }
        });
        page.addView(save);
        page.addView(gap(10));

        View reboot = bigButton("保存并重启系统", C_CARD_HI, C_TEXT);
        reboot.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                saveAll();
                toast("正在重启…");
                try {
                    Runtime.getRuntime().exec(new String[]{"su", "-c", "svc power reboot"});
                } catch (Throwable t) {
                    try { Runtime.getRuntime().exec(new String[]{"su", "-c", "reboot"}); }
                    catch (Throwable ignored) {}
                }
            }
        });
        page.addView(reboot);
        page.addView(gap(16));

        TextView hint = label("原理：选中 WiFi 连接时，模块在 system_server 中移除其"
                + "「可上网」能力，系统保持手机流量为默认网络；被勾选的车机应用则单独绑定"
                + "回该 WiFi 访问车机。", 12, false);
        hint.setTextColor(C_TEXT_SUB);
        page.addView(hint);
    }

    private void saveAll() {
        Config.setEnabled(this, swEnabled.isChecked());
        Config.setSsids(this, selectedSsids);
        Config.setApps(this, selectedApps);
        Config.setHidden(this, swHide.isChecked());
        syncScopeToLsposed();
        permOk = Config.hasWriteSecureSettings(this);
        refreshPermText();
        refreshStatusText();
        if (permOk) toast("已保存：" + selectedSsids.size() + " 个热点 · "
                + selectedApps.size() + " 个车机应用");
        else toast("已保存，但缺少写入权限，可能未生效");
    }

    /** best-effort: mirror selected apps into LSPosed scope db via root sqlite3 */
    private void syncScopeToLsposed() {
        try {
            StringBuilder sql = new StringBuilder();
            sql.append("INSERT OR IGNORE INTO scope(module_pkg_name,app_pkg_name,user_id)")
               .append(" VALUES('com.eta.carrouter','system',0);");
            for (String p : selectedApps) {
                sql.append("INSERT OR IGNORE INTO scope(module_pkg_name,app_pkg_name,user_id)")
                   .append(" VALUES('com.eta.carrouter','").append(p.replace("'", "''"))
                   .append("',0);");
            }
            sql.append("PRAGMA wal_checkpoint(TRUNCATE);");
            String cmd = "/data/local/tmp/sqlite3.arm64 "
                    + "/data/adb/lspd/config/modules_config.db \"" + sql + "\"";
            Process pr = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            try { pr.waitFor(); } catch (InterruptedException ignored) {}
        } catch (Throwable ignored) {
        }
    }

    // ======================================================================
    // ui builders
    // ======================================================================

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setCornerRadius(dp(CARD_RADIUS));
        c.setBackground(bg);
        int p = dp(16);
        c.setPadding(p, p, p, p);
        parent.addView(c, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }

    private LinearLayout cardWrap() {
        LinearLayout w = new LinearLayout(this);
        w.setOrientation(LinearLayout.VERTICAL);
        w.setPadding(0, dp(8), 0, 0);
        return w;
    }

    /** selectable wifi row: name + subtitle + check mark, whole row tappable */
    private View selectableRow(final String title, String sub, final boolean checked,
                               final Runnable dummy, final Object tag) {
        LinearLayout row = h();
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout texts = v();
        TextView t = label(title, 14, true);
        t.setTextColor(checked ? C_ACCENT : C_TEXT);
        texts.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = label(sub, 11, false);
            s.setTextColor(checked ? C_ACCENT : C_TEXT_SUB);
            texts.addView(s);
        }
        texts.setLayoutParams(weight1());
        row.addView(texts);

        TextView check = label(checked ? "✓" : "", 16, true);
        check.setTextColor(C_ACCENT);
        check.setGravity(Gravity.CENTER);
        check.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
        row.addView(check);

        paintRow(row, checked);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleSsid((String) tag); }
        });
        return row;
    }

    /** app row: icon + name + package + check */
    private View appRow(final android.graphics.drawable.Drawable icon, final String name,
                        final String sub, final boolean checked, final String pkg) {
        LinearLayout row = h();
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
        lp.rightMargin = dp(10);
        iv.setLayoutParams(lp);
        row.addView(iv);

        LinearLayout texts = v();
        TextView t = label(name, 14, true);
        t.setTextColor(checked ? C_ACCENT : C_TEXT);
        texts.addView(t);
        TextView s = label(sub, 11, false);
        s.setTextColor(C_TEXT_SUB);
        texts.addView(s);
        texts.setLayoutParams(weight1());
        row.addView(texts);

        TextView check = label(checked ? "✓" : "", 16, true);
        check.setTextColor(C_ACCENT);
        check.setGravity(Gravity.CENTER);
        check.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
        row.addView(check);

        paintRow(row, checked);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (selectedApps.contains(pkg)) selectedApps.remove(pkg);
                else selectedApps.add(pkg);
                persist();
                loadAppListAsync();
            }
        });
        return row;
    }

    private View simpleRow(String title, String sub) {
        LinearLayout row = h();
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = v();
        texts.addView(label(title, 14, true));
        if (sub != null) texts.addView(label(sub, 11, false));
        texts.setLayoutParams(weight1());
        row.addView(texts);
        paintRow(row, false);
        return row;
    }

    private void paintRow(LinearLayout row, boolean checked) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(checked ? C_CARD_HI : 0xFF131820);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), checked ? C_ACCENT : 0x22FFFFFF);
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
    }

    private View pillButton(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_ACCENT);
        bg.setCornerRadius(dp(20));
        b.setBackground(bg);
        return b;
    }

    private View bigButton(String text, int bg, int fg) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(fg);
        b.setGravity(Gravity.CENTER);
        int p = dp(14);
        b.setPadding(p, p, p, p);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(16));
        b.setBackground(d);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private TextView label(String text, int size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(C_TEXT);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout h() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout v() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private View gap(int d) {
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

    private String joinNames(Set<String> set) {
        StringBuilder b = new StringBuilder();
        for (String s : set) {
            if (b.length() > 0) b.append("、");
            b.append(s);
        }
        return b.toString();
    }

    // ======================================================================
    // helpers
    // ======================================================================

    private String readCurrentSsid() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
            return wi == null ? "" : Config.unquote(wi.getSSID());
        } catch (Throwable t) {
            return "";
        }
    }

    private static String htmlDecode(String s) {
        return s.replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    /** run a command as root and return stdout (null on failure) */
    private static String su(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            r.close();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private void persist() {
        Config.setEnabled(this, swEnabled.isChecked());
        Config.setSsids(this, selectedSsids);
        Config.setApps(this, selectedApps);
        syncScopeToLsposed();
    }
}
