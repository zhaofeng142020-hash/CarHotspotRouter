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
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * CarHotspotRouter settings UI (v2.2).
 *
 * Wi-Fi: dropdown picker fed by the hook (system_server publishes all saved
 * network names into Settings.Global.carrouter_wifi_list every minute), so no
 * location permission or root is needed in this app and "unknown ssid" never
 * shows up. Single select; custom SSIDs can still be added manually.
 *
 * Apps: searchable picker over ALL installed apps (icon + name + package).
 * Selected apps appear on the card and can be removed by tapping.
 */
public class SettingsActivity extends Activity {

    private static final int C_BG        = 0xFF0E1116;
    private static final int C_CARD      = 0xFF171B22;
    private static final int C_CARD_HI   = 0xFF1D232D;
    private static final int C_ROW       = 0xFF131820;
    private static final int C_ACCENT    = 0xFF4C8DFF;
    private static final int C_ACCENT_DIM= 0xFF2A4A80;
    private static final int C_TEXT      = 0xFFE8EAF0;
    private static final int C_TEXT_SUB  = 0xFF9AA3B2;
    private static final int C_OK        = 0xFF5AD196;
    private static final int C_WARN      = 0xFFFFB454;

    private Switch swEnabled;
    private Switch swHide;
    private TextView tvPerm;
    private TextView tvStatus;
    private TextView tvWifiValue;
    private LinearLayout selectedAppsBox;
    private boolean permOk;

    private final Set<String> selectedApps = new LinkedHashSet<>();
    private String selectedSsid = "";
    private String currentSsid = "";
    private List<String> knownSsids = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getWindow() != null)
            getWindow().requestFeature(Window.FEATURE_NO_TITLE);

        permOk = Config.hasWriteSecureSettings(this);
        Set<String> ssids = Config.ssids(this);
        selectedSsid = ssids.isEmpty() ? "" : ssids.iterator().next();
        selectedApps.addAll(Config.apps(this));
        currentSsid = readCurrentSsid();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int m = dp(18);
        page.setPadding(m, dp(28), m, dp(28));
        scroll.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        buildHeader(page);
        buildStatusCard(page);
        buildWifiCard(page);
        buildAppCard(page);
        buildHideCard(page);
        buildActions(page);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadWifiList();
        refreshStatusText();
    }

    // ================= header / status =================

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
        swEnabled.getTrackDrawable().setColorFilter(C_ACCENT_DIM,
                android.graphics.PorterDuff.Mode.SRC_ATOP);
        row.addView(swEnabled);
        card.addView(row);

        tvPerm = label("", 12, false);
        tvPerm.setPadding(0, dp(8), 0, 0);
        card.addView(tvPerm);

        tvStatus = label("", 12, false);
        tvStatus.setPadding(0, dp(4), 0, 0);
        card.addView(tvStatus);

        refreshPermText();
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
        boolean on = Config.isEnabled(this);
        if (on && !selectedSsid.isEmpty() && selectedSsid.equals(currentSsid)) {
            tvStatus.setText("● 分流中：" + currentSsid + " 走车机局域网，其余应用走流量");
            tvStatus.setTextColor(C_OK);
        } else if (on && !selectedSsid.isEmpty()) {
            tvStatus.setText("○ 已选择：" + selectedSsid + "（当前连接："
                    + (currentSsid.isEmpty() ? "无" : currentSsid) + "）");
            tvStatus.setTextColor(C_TEXT_SUB);
        } else {
            tvStatus.setText("○ 请在下拉菜单中选择车机热点");
            tvStatus.setTextColor(C_TEXT_SUB);
        }
    }

    // ================= wifi card =================

    private void buildWifiCard(LinearLayout page) {
        LinearLayout card = card(page);
        card.addView(label("车机互联热点", 16, true));
        card.addView(label("从下拉菜单选择连接时要继续使用手机流量的 WiFi", 12, false));
        card.addView(gap(12));

        // dropdown trigger row
        LinearLayout dd = h();
        dd.setGravity(Gravity.CENTER_VERTICAL);
        dd.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_ROW);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), C_ACCENT);
        dd.setBackground(bg);

        LinearLayout texts = v();
        tvWifiValue = label("", 15, true);
        texts.addView(tvWifiValue);
        TextView cap = label("点击选择 WiFi", 11, false);
        cap.setTextColor(C_TEXT_SUB);
        texts.addView(cap);
        texts.setLayoutParams(weight1());
        dd.addView(texts);

        TextView arrow = label("▾", 18, true);
        arrow.setTextColor(C_ACCENT);
        dd.addView(arrow);
        dd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showWifiDropdown(); }
        });
        card.addView(dd);

        TextView hint = label("列表由模块从系统自动同步（含你连接过的所有 WiFi）；"
                + "若列表不全可手动输入。", 11, false);
        hint.setTextColor(C_TEXT_SUB);
        hint.setPadding(0, dp(8), 0, 0);
        card.addView(hint);
        page.addView(gap(14));

        updateWifiValue();
        loadWifiList();
    }

    private void updateWifiValue() {
        tvWifiValue.setText(selectedSsid.isEmpty()
                ? (currentSsid.isEmpty() ? "未选择" : "未选择（当前：" + currentSsid + "）")
                : selectedSsid);
    }

    /** read the hook-synced list from Settings.Global */
    private void loadWifiList() {
        new Thread(new Runnable() {
            @Override public void run() {
                Set<String> set = new LinkedHashSet<>();
                try {
                    String raw = Settings.Global.getString(getContentResolver(),
                            "carrouter_wifi_list");
                    if (raw != null) {
                        for (String s : raw.split("[\\r\\n]+")) {
                            s = Config.unquote(s.trim());
                            if (!s.isEmpty()) set.add(s);
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (!currentSsid.isEmpty()) set.add(currentSsid);
                if (!selectedSsid.isEmpty()) set.add(selectedSsid);
                final List<String> list = new ArrayList<>(set);
                Collections.sort(list, String.CASE_INSENSITIVE_ORDER);
                runOnUiThread(new Runnable() {
                    @Override public void run() { knownSsids = list; }
                });
            }
        }).start();
    }

    private void showWifiDropdown() {
        LinkedHashSet<String> all = new LinkedHashSet<>(knownSsids);
        if (!currentSsid.isEmpty()) all.add(currentSsid);
        if (!selectedSsid.isEmpty()) all.add(selectedSsid);
        if (all.isEmpty()) {
            promptManualSsid();
            return;
        }
        final List<String> items = new ArrayList<>(all);
        Collections.sort(items, String.CASE_INSENSITIVE_ORDER);
        // current connection first
        if (items.contains(currentSsid)) {
            items.remove(currentSsid);
            items.add(0, currentSsid);
        }
        String[] arr = items.toArray(new String[0]);
        int checked = -1;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(selectedSsid)) { checked = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle("选择车机互联热点（当前连接：" + (currentSsid.isEmpty() ? "无" : currentSsid) + "）")
                .setSingleChoiceItems(arr, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        selectedSsid = items.get(which);
                        persistSsid();
                        updateWifiValue();
                        refreshStatusText();
                        d.dismiss();
                    }
                })
                .setNeutralButton("手动输入…", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { promptManualSsid(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptManualSsid() {
        final EditText et = new EditText(this);
        et.setHint("SSID 名称");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setTextColor(C_TEXT);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), 0, dp(24), 0);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("手动输入 WiFi 名称")
                .setView(box)
                .setPositiveButton("选定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String s = Config.unquote(et.getText().toString());
                        if (!s.isEmpty()) {
                            selectedSsid = s;
                            persistSsid();
                            updateWifiValue();
                            refreshStatusText();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================= app card =================

    private void buildAppCard(LinearLayout page) {
        LinearLayout card = card(page);
        LinearLayout head = h();
        LinearLayout ht = v();
        ht.addView(label("车机互联应用", 16, true));
        ht.addView(label("选中应用走车机局域网，其余应用走手机流量", 12, false));
        ht.setLayoutParams(weight1());
        head.addView(ht);

        View add = pillButton("＋ 添加 / 搜索");
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAppPicker(); }
        });
        head.addView(add);
        card.addView(head);
        card.addView(gap(10));

        selectedAppsBox = new LinearLayout(this);
        selectedAppsBox.setOrientation(LinearLayout.VERTICAL);
        card.addView(selectedAppsBox);
        rebuildSelectedApps();
        page.addView(gap(14));
    }

    private void rebuildSelectedApps() {
        selectedAppsBox.removeAllViews();
        PackageManager pm = getPackageManager();
        if (selectedApps.isEmpty()) {
            TextView t = label("尚未选择应用。点击右上角「添加 / 搜索」，"
                    + "在全部应用中勾选车机互联类应用。", 12, false);
            t.setTextColor(C_TEXT_SUB);
            selectedAppsBox.addView(t);
            return;
        }
        List<String> ordered = new ArrayList<>(selectedApps);
        Collections.sort(ordered, String.CASE_INSENSITIVE_ORDER);
        for (final String pkg : ordered) {
            String name = pkg;
            android.graphics.drawable.Drawable icon = null;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                name = String.valueOf(pm.getApplicationLabel(ai));
                icon = ai.loadIcon(pm);
            } catch (Throwable ignored) {
            }
            selectedAppsBox.addView(appRow(icon, name, pkg, true, pkg));
        }
    }

    /** searchable dialog listing ALL installed apps */
    private void showAppPicker() {
        final PackageManager pm = getPackageManager();
        final List<ApplicationInfo> all = new ArrayList<>();
        try {
            all.addAll(pm.getInstalledApplications(0));
            Collections.sort(all, new Comparator<ApplicationInfo>() {
                @Override public int compare(ApplicationInfo a, ApplicationInfo b) {
                    String la, lb;
                    try { la = String.valueOf(pm.getApplicationLabel(a)); }
                    catch (Throwable t) { la = a.packageName; }
                    try { lb = String.valueOf(pm.getApplicationLabel(b)); }
                    catch (Throwable t) { lb = b.packageName; }
                    return la.compareToIgnoreCase(lb);
                }
            });
        } catch (Throwable ignored) {
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(8);
        box.setPadding(p, 0, p, dp(8));
        scroll.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText search = new EditText(this);
        search.setHint("搜索应用名或包名…");
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setTextColor(C_TEXT);
        search.setSingleLine(true);
        LinearLayout sw = new LinearLayout(this);
        sw.setPadding(dp(16), dp(8), dp(16), dp(4));
        sw.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(sw);

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        box.addView(list);

        final AlertDialog[] dialogHolder = new AlertDialog[1];
        final Set<String> working = new LinkedHashSet<>(selectedApps);

        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = new Runnable() {
            @Override public void run() {
                String q = search.getText().toString().trim().toLowerCase();
                list.removeAllViews();
                int shown = 0;
                for (final ApplicationInfo ai : all) {
                    String label;
                    try { label = String.valueOf(pm.getApplicationLabel(ai)); }
                    catch (Throwable t) { label = ai.packageName; }
                    if (!q.isEmpty()
                            && !label.toLowerCase().contains(q)
                            && !ai.packageName.toLowerCase().contains(q)) continue;
                    list.addView(appRow(ai.loadIcon(pm), label, ai.packageName,
                            working.contains(ai.packageName), ai.packageName,
                            new Runnable() {
                                @Override public void run() {
                                    // toggle handled inside appRow via callback below
                                }
                            }, working, new Runnable() {
                                @Override public void run() {
                                    // refresh statuses by rebuilding
                                    rebuild[0].run();
                                }
                            }));
                    shown++;
                    if (shown >= 400) break;
                }
                if (shown == 0) {
                    TextView t = label("没有匹配的应用", 13, false);
                    t.setPadding(dp(16), dp(12), dp(16), dp(12));
                    list.addView(t);
                }
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void afterTextChanged(Editable s) { rebuild[0].run(); }
        });
        rebuild[0].run();

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("选择车机互联应用")
                .setView(scroll)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        selectedApps.clear();
                        selectedApps.addAll(working);
                        persist();
                        rebuildSelectedApps();
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dialogHolder[0] = dlg;
        dlg.show();
        if (dlg.getWindow() != null)
            dlg.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.85));
    }

    // ================= hide card =================

    private void buildHideCard(LinearLayout page) {
        LinearLayout card = card(page);
        LinearLayout row = h();
        LinearLayout texts = v();
        texts.addView(label("从桌面隐藏图标", 15, true));
        texts.addView(label("模块继续生效；可从 LSPosed 管理器或\n"
                + "am start -n com.eta.carrouter/.SettingsActivity 重新打开", 11, false));
        texts.setLayoutParams(weight1());
        row.addView(texts);
        swHide = new Switch(this);
        swHide.setChecked(Config.isHidden(this));
        swHide.getTrackDrawable().setColorFilter(C_ACCENT_DIM,
                android.graphics.PorterDuff.Mode.SRC_ATOP);
        row.addView(swHide);
        card.addView(row);
        page.addView(gap(14));
    }

    // ================= actions =================

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

        TextView hint = label("原理：选中 WiFi 连接时，模块移除其「可上网」能力，"
                + "系统保持手机流量为默认网络；被勾选的车机应用单独绑定回该 WiFi 访问车机。",
                12, false);
        hint.setTextColor(C_TEXT_SUB);
        page.addView(hint);
    }

    private void saveAll() {
        Config.setEnabled(this, swEnabled.isChecked());
        Set<String> ss = new LinkedHashSet<>();
        if (!selectedSsid.isEmpty()) ss.add(selectedSsid);
        Config.setSsids(this, ss);
        Config.setApps(this, selectedApps);
        Config.setHidden(this, swHide.isChecked());
        syncScopeToLsposed();
        permOk = Config.hasWriteSecureSettings(this);
        refreshPermText();
        refreshStatusText();
        if (permOk) toast("已保存：" + (selectedSsid.isEmpty() ? 0 : 1) + " 个热点 · "
                + selectedApps.size() + " 个车机应用");
        else toast("已保存，但缺少写入权限，可能未生效");
    }

    private void persistSsid() {
        Set<String> ss = new LinkedHashSet<>();
        if (!selectedSsid.isEmpty()) ss.add(selectedSsid);
        Config.setSsids(this, ss);
        syncScopeToLsposed();
    }

    private void persist() {
        persistSsid();
        Config.setApps(this, selectedApps);
    }

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

    // ================= ui builders =================

    /** app row used both on the main card (removable) and in the picker (toggleable) */
    private View appRow(android.graphics.drawable.Drawable icon, String name, String sub,
                        boolean checked, final String pkg) {
        return appRow(icon, name, sub, checked, pkg, null, null, null);
    }

    private View appRow(final android.graphics.drawable.Drawable icon, final String name,
                        final String sub, final boolean checked, final String pkg,
                        final Runnable noop, final Set<String> workingSet,
                        final Runnable onChange) {
        LinearLayout row = h();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));

        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
            lp.rightMargin = dp(10);
            iv.setLayoutParams(lp);
            row.addView(iv);
        } else {
            ImageView iv = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
            lp.rightMargin = dp(10);
            iv.setLayoutParams(lp);
            row.addView(iv);
        }

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
        check.setTag("check");
        check.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
        row.addView(check);

        paintRow(row, checked);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean nowChecked;
                if (workingSet != null) {
                    if (workingSet.contains(pkg)) workingSet.remove(pkg);
                    else workingSet.add(pkg);
                    nowChecked = workingSet.contains(pkg);
                    TextView c = v.findViewWithTag("check");
                    if (c != null) c.setText(nowChecked ? "✓" : "");
                    paintRow((LinearLayout) v, nowChecked);
                    if (onChange != null) onChange.run();
                } else {
                    selectedApps.remove(pkg);
                    persist();
                    rebuildSelectedApps();
                    nowChecked = false;
                    toast("已移除：" + name);
                }
            }
        });
        return row;
    }

    private void paintRow(LinearLayout row, boolean checked) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(checked ? C_CARD_HI : C_ROW);
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
        b.setPadding(dp(14), dp(7), dp(14), dp(7));
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

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setCornerRadius(dp(18));
        c.setBackground(bg);
        int p = dp(16);
        c.setPadding(p, p, p, p);
        parent.addView(c, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
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

    private String readCurrentSsid() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
            String s = wi == null ? "" : Config.unquote(wi.getSSID());
            return "<unknown ssid>".equalsIgnoreCase(s) ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }
}
