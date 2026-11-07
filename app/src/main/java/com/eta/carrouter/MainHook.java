package com.eta.carrouter;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module: while the phone is connected to a Wi-Fi the user picked as a
 * "car head-unit hotspot", keep the cellular network as the system default (so
 * every app still has internet), and bind only the selected car-link apps back
 * to that Wi-Fi for LAN access.
 *
 * Configuration lives in Settings.Global (written by SettingsActivity, readable
 * here from system_server with no permission). A system property fallback is kept
 * for headless use. Changes take effect on the next network event, no reboot.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "CarRouter";
    private static final int CAP_INTERNET = 12;   // NetworkCapabilities.NET_CAPABILITY_INTERNET
    private static final int CAP_VALIDATED = 16;  // NET_CAPABILITY_VALIDATED
    private static final int CAP_CAPTIVE = 17;    // NET_CAPABILITY_CAPTIVE_PORTAL
    private static final int TRANSPORT_WIFI = 1;  // NetworkCapabilities.TRANSPORT_WIFI

    private static final String KEY_ENABLED = "carrouter_enabled";
    private static final String KEY_SSIDS = "carrouter_ssids";   // newline separated
    private static final String KEY_APPS = "carrouter_apps";     // comma separated

    private static final String DEFAULT_SSIDS = "Cloudrive-020880";
    private static final String DEFAULT_APPS =
            "com.baidu.carlife.xiaomi,com.mcarplay.app,com.baidu.carlife,"
                    + "com.qinggan.carlink,com.google.android.projection.gearhead";

    private static final WeakHashMap<Object, Boolean> STRIPPED = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> BUSY = new ThreadLocal<>();
    private static Object sCs;
    private static Context sContext;
    private static Handler sSyncHandler;

    private static final String KEY_WIFI_LIST = "carrouter_wifi_list"; // newline separated

    // ---------------- configuration (shared with SettingsActivity) ----------------

    private static String prop(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class).invoke(null, key, def);
            return v == null ? def : (String) v;
        } catch (Throwable t) {
            return def;
        }
    }

    private static String cfg(Context ctx, String key, String def, String propKey) {
        // Settings.Global (UI) wins; system property is a fallback for headless use.
        if (ctx != null) {
            try {
                String v = Settings.Global.getString(ctx.getContentResolver(), key);
                if (v != null && !v.isEmpty()) return v;
            } catch (Throwable ignored) {
            }
        }
        String p = prop(propKey, null);
        if (p != null && !p.isEmpty()) return p;
        return def;
    }

    private static String unquote(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\""))
            s = s.substring(1, s.length() - 1);
        return s;
    }

    private static Set<String> splitSet(String raw, String sep) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (String s : raw.split(sep)) {
            s = unquote(s.trim());
            if (!s.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(s)) out.add(s);
        }
        return out;
    }

    private static boolean enabled(Context ctx) {
        if (ctx == null) return true;
        return !"0".equals(cfg(ctx, KEY_ENABLED, "1", "persist.carrouter.enabled"));
    }

    private static Set<String> carSsids(Context ctx) {
        if (ctx == null) return splitSet(DEFAULT_SSIDS, "[\\r\\n]+");
        return splitSet(cfg(ctx, KEY_SSIDS, DEFAULT_SSIDS, "persist.carrouter.ssid"), "[\\r\\n]+");
    }

    private static Set<String> carApps(Context ctx) {
        if (ctx == null) return splitSet(DEFAULT_APPS, "[,;\\s]+");
        return splitSet(cfg(ctx, KEY_APPS, DEFAULT_APPS, "persist.carrouter.apps"), "[,;\\s]+");
    }

    private static boolean isCarSsid(Context ctx, String ssid) {
        return !unquote(ssid).isEmpty() && carSsids(ctx).contains(unquote(ssid));
    }

    private static String currentSsid(Context ctx) {
        if (ctx == null) return "";
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return "";
            WifiInfo wi = wm.getConnectionInfo();
            return wi == null ? "" : unquote(wi.getSSID());
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp) {
        if ("android".equals(lp.packageName)) {
            hookSystemServer(lp.classLoader);
        } else {
            // Injected into every scoped app. Decide at runtime (on Application
            // creation) whether this package is one of the selected car-link apps,
            // so changing the selection in the UI takes effect on next app launch.
            hookCarApp(lp.classLoader);
        }
    }

    /* ---- periodic sync of saved Wi-Fi names into Settings.Global ----
     * Runs inside system_server, which can enumerate saved networks and write
     * Global settings without any permission. The settings UI just reads the
     * list, so it needs no location permission and no root of its own. */

    private static void startWifiListSync() {
        HandlerThread t = new HandlerThread("CarRouterSync");
        t.start();
        sSyncHandler = new Handler(t.getLooper());
        sSyncHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    syncWifiListOnce();
                } catch (Throwable th) {
                    XposedBridge.log(TAG + ": sync: " + th);
                }
                sSyncHandler.postDelayed(this, 60_000L);
            }
        }, 15_000L);
    }

    private static void syncWifiListOnce() {
        Context ctx = sContext;
        if (ctx == null) return;
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                List<WifiConfiguration> cfgs = wm.getConfiguredNetworks();
                if (cfgs != null) {
                    for (WifiConfiguration c : cfgs) {
                        String s = unquote(c.SSID);
                        if (!s.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(s)) names.add(s);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        String cur = currentSsid(ctx);
        if (!cur.isEmpty()) names.add(cur);

        // write only when changed to avoid settings churn
        String prev = null;
        try {
            prev = Settings.Global.getString(ctx.getContentResolver(), KEY_WIFI_LIST);
        } catch (Throwable ignored) {
        }
        StringBuilder b = new StringBuilder();
        for (String s : names) {
            if (b.length() > 0) b.append('\n');
            b.append(s);
        }
        if (!b.toString().equals(prev == null ? "" : prev)) {
            try {
                Settings.Global.putString(ctx.getContentResolver(), KEY_WIFI_LIST, b.toString());
                XposedBridge.log(TAG + ": wifi list synced (" + names.size() + " networks)");
            } catch (Throwable th) {
                XposedBridge.log(TAG + ": wifi list write failed: " + th);
            }
        }
    }

    /* ---- system_server: strip INTERNET from the selected car Wi-Fi, keep cellular default ---- */

    private void hookSystemServer(ClassLoader cl) {
        Class<?> cs = null;
        for (String n : new String[]{
                "com.android.server.connectivity.ConnectivityService",  // Android 9-11
                "com.android.server.ConnectivityService"}) {             // Android 7 / 8
            try {
                cs = XposedHelpers.findClass(n, cl);
                break;
            } catch (Throwable ignored) {
            }
        }
        if (cs != null) {
            // older ROMs: class is on the boot classpath, hook constructors to
            // capture the instance right away
            hookCsClass(cs);
            XposedBridge.hookAllConstructors(cs, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    sCs = p.thisObject;
                    sContext = findContext(p.thisObject, p.args);
                }
            });
            XposedBridge.log(TAG + ": hooked ConnectivityService from boot classloader");
        } else {
            // Android 12+: ConnectivityService lives in the com.android.tethering
            // APEX, invisible to the boot classloader. Discover it through the
            // registered "connectivity" binder once system_server is up.
            XposedBridge.log(TAG + ": not on boot classloader, starting binder discovery");
            startBinderDiscovery();
        }
        startWifiListSync();
    }

    /** hook the interesting ConnectivityService methods on a class found anywhere */
    private void hookCsClass(final Class<?> cs) {
        final String[] keys = {
                "updateCapabilities", "registerNetworkAgent", "rematchAllNetworksAndRequests",
                "rematchNetworkAndRequests", "updateNetworkInfo", "handleNetworkUnvalidated",
                "handleNetworkValidated", "handleUpdateLinkProperties",
                "sendUpdatedScoresToFactories", "handleConnectivityChange",
                "handleNetworkConnectivityChanged"
        };
        int n = 0;
        for (Method m : cs.getDeclaredMethods()) {
            String mn = m.getName().toLowerCase(Locale.US);
            for (String k : keys) {
                String kk = k.toLowerCase(Locale.US);
                if (mn.equals(kk) || mn.contains(kk)) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                enforce(p);
                            }

                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                enforce(p);
                            }
                        });
                        n++;
                    } catch (Throwable ignored) {
                    }
                    break;
                }
            }
        }
        XposedBridge.log(TAG + ": hooked " + n + " ConnectivityService methods on "
                + cs.getName());
    }

    /**
     * Poll ServiceManager for the connectivity binder. The binder either IS the
     * ConnectivityService (then capture it directly) or lives in the same
     * classloader (then we can finally load the class and hook it), and if it is
     * a wrapper we look for the real service instance among its fields.
     */
    private void startBinderDiscovery() {
        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 30; i++) {
                    try {
                        Object binder = serviceManagerGet("connectivity");
                        if (binder == null) {
                            Thread.sleep(5000);
                            continue;
                        }
                        Class<?> binderClass = binder.getClass();
                        String name = binderClass.getName();

                        if (name.endsWith("ConnectivityService")) {
                            sCs = binder;
                            hookCsClass(binderClass);
                            sContext = findContext(binder, null);
                            XposedBridge.log(TAG + ": found ConnectivityService instance via binder");
                            kickOnce();
                            return;
                        }

                        // wrapper: its classloader can load the real class
                        try {
                            Class<?> real = XposedHelpers.findClass(
                                    "com.android.server.connectivity.ConnectivityService",
                                    binderClass.getClassLoader());
                            hookCsClass(real);
                            Object inst = findInstanceOf(binder, real);
                            if (inst != null) sCs = inst;
                            sContext = findContext(binder, null);
                            XposedBridge.log(TAG + ": hooked via wrapper classloader, name=" + name);
                            kickOnce();
                            return;
                        } catch (Throwable tt) {
                            XposedBridge.log(TAG + ": wrapper load failed: " + tt);
                        }
                    } catch (Throwable e) {
                        XposedBridge.log(TAG + ": discovery: " + e);
                    }
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
                XposedBridge.log(TAG + ": binder discovery gave up");
            }
        }, "CarRouterDiscovery");
        t.setDaemon(true);
        t.start();
    }

    private static Object serviceManagerGet(String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            return sm.getMethod("getService", String.class).invoke(null, name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** depth-first search for a field holding an instance of targetClass */
    private static Object findInstanceOf(Object root, Class<?> targetClass) {
        if (root == null) return null;
        if (targetClass.isInstance(root)) return root;
        ArrayList<Object> seen = new ArrayList<>();
        ArrayList<Object> queue = new ArrayList<>();
        queue.add(root);
        int scanned = 0;
        while (!queue.isEmpty() && scanned < 300) {
            Object o = queue.remove(0);
            if (o == null || seen.contains(o)) continue;
            seen.add(o);
            scanned++;
            Class<?> c = o.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (!f.getType().isAssignableFrom(targetClass)
                            && !f.getType().isInstance(targetClass)) {
                        // still inspect non-primitive fields recursively below
                    }
                    try {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v == null || seen.contains(v)) continue;
                        if (targetClass.isInstance(v)) return v;
                        if (v.getClass().getName().startsWith("android.")
                                || v.getClass().getName().startsWith("com.android.")) {
                            queue.add(v);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** run enforce once from the background thread (no hook context needed) */
    private static void kickOnce() {
        try {
            enforce(null);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": kick: " + t);
        }
    }

    private static void enforce(XC_MethodHook.MethodHookParam p) {
        if (Boolean.TRUE.equals(BUSY.get())) return;
        Object cs = sCs != null ? sCs : (p == null ? null : p.thisObject);
        if (cs == null) return;
        Context ctx = sContext != null ? sContext : findContext(cs, null);
        BUSY.set(true);
        try {
            if (!enabled(ctx)) return;   // master switch off -> do nothing
            boolean match = isCarSsid(ctx, currentSsid(ctx));
            boolean changed = false;
            for (Object nai : allAgentInfos(cs)) {
                NetworkCapabilities nc = (NetworkCapabilities) getField(nai, "networkCapabilities");
                if (nc == null || !nc.hasTransport(TRANSPORT_WIFI)) continue;
                boolean noInternet = !nc.hasCapability(CAP_INTERNET);
                if (match && !noInternet) {
                    setCap(nc, CAP_INTERNET, false);
                    setCap(nc, CAP_VALIDATED, false);
                    setCap(nc, CAP_CAPTIVE, false);
                    STRIPPED.put(nai, Boolean.TRUE);
                    changed = true;
                } else if (!match && noInternet && STRIPPED.containsKey(nai)) {
                    setCap(nc, CAP_INTERNET, true);   // restore after leaving the car hotspot
                    setCap(nc, CAP_VALIDATED, true);
                    STRIPPED.remove(nai);
                    changed = true;
                }
            }
            if (changed) requestRematch(cs);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": enforce: " + t);
        } finally {
            BUSY.remove();
        }
    }

    /* ---- car app process: bind whole process back to the Wi-Fi LAN ---- */

    private void hookCarApp(ClassLoader cl) {
        XposedHelpers.findAndHookMethod("android.app.Application", cl, "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            final Context ctx = (Context) p.thisObject;
                            final String pkg = ctx.getPackageName();
                            if (!enabled(ctx) || !carApps(ctx).contains(pkg)) return;
                            bindToCarWifi(ctx);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": bind: " + t);
                        }
                    }
                });
    }

    private static void bindToCarWifi(final Context ctx) {
        final ConnectivityManager cm =
                (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        cm.registerNetworkCallback(req, new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network net) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(net);
                if (nc != null && !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                    attach(cm, net, true, ctx);
            }

            @Override
            public void onLost(Network net) {
                attach(cm, null, false, ctx);
            }
        });

        // Fallback: hotspot was up before the app started, scan existing networks now.
        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(net);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                attach(cm, net, true, ctx);
                break;
            }
        }
    }

    private static void attach(ConnectivityManager cm, Network net, boolean on, Context ctx) {
        if (Build.VERSION.SDK_INT >= 23) cm.bindProcessToNetwork(net);
        else ConnectivityManager.setProcessDefaultNetwork(net);
        XposedBridge.log(TAG + (on ? ": bound " : ": unbound ")
                + ctx.getPackageName() + " -> " + net);
    }

    /* ---- reflection helpers ---- */

    /**
     * NetworkCapabilities.addCapability/removeCapability are @hide in the public
     * SDK stub, so call them reflectively. They exist on a real device (the module
     * runs inside system_server, which can access hidden APIs).
     */
    private static void setCap(NetworkCapabilities nc, int cap, boolean add) {
        try {
            String name = add ? "addCapability" : "removeCapability";
            Method m = NetworkCapabilities.class.getMethod(name, int.class);
            m.invoke(nc, cap);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setCap(" + cap + "," + add + "): " + t);
        }
    }

    private static Object getField(Object o, String name) {
        Class<?> c = o.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static Collection<Object> allAgentInfos(Object cs) {
        ArrayList<Object> out = new ArrayList<>();
        for (Field f : cs.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            Object v;
            try {
                f.setAccessible(true);
                v = f.get(cs);
            } catch (Throwable t) {
                continue;
            }
            if (v == null) continue;
            if (v instanceof Iterable) {
                for (Object o : (Iterable<?>) v) if (isAgent(o)) out.add(o);
            } else if (v instanceof Map) {
                for (Object o : ((Map<?, ?>) v).values()) if (isAgent(o)) out.add(o);
            } else if (v.getClass().isArray()) {
                int len = Array.getLength(v);
                for (int i = 0; i < len; i++) {
                    Object o = Array.get(v, i);
                    if (isAgent(o)) out.add(o);
                }
            } else {
                try {   // android.util.SparseArray / SparseArrayCompat
                    Method size = v.getClass().getMethod("size");
                    Method valueAt = v.getClass().getMethod("valueAt", int.class);
                    int len = (Integer) size.invoke(v);
                    for (int i = 0; i < len; i++) {
                        Object o = valueAt.invoke(v, i);
                        if (isAgent(o)) out.add(o);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    private static boolean isAgent(Object o) {
        if (o == null) return false;
        Class<?> c = o.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if ("networkCapabilities".equals(f.getName())
                        && "android.net.NetworkCapabilities".equals(f.getType().getName()))
                    return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }

    private static Context findContext(Object cs, Object[] args) {
        if (args != null) for (Object a : args) if (a instanceof Context) return (Context) a;
        if (cs != null) for (Field f : cs.getClass().getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object v = f.get(cs);
                if (v instanceof Context) return (Context) v;
            } catch (Throwable ignored) {
            }
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object th = at.getMethod("systemMain").invoke(null);
            return (Context) at.getMethod("getSystemContext").invoke(th);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void requestRematch(Object cs) {
        for (Method m : cs.getClass().getDeclaredMethods()) {
            if (!"rematchAllNetworksAndRequests".equals(m.getName())) continue;
            try {
                m.setAccessible(true);
                Class<?>[] ps = m.getParameterTypes();
                Object[] args = new Object[ps.length];
                for (int i = 0; i < ps.length; i++) args[i] = def(ps[i]);
                m.invoke(cs, args);
                return;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": rematch: " + t);
            }
        }
    }

    private static Object def(Class<?> c) {
        if (!c.isPrimitive()) return null;
        if (c == boolean.class) return false;
        if (c == int.class) return 0;
        if (c == long.class) return 0L;
        if (c == short.class) return (short) 0;
        if (c == byte.class) return (byte) 0;
        if (c == float.class) return 0f;
        if (c == double.class) return 0d;
        if (c == char.class) return (char) 0;
        return null;
    }
}
