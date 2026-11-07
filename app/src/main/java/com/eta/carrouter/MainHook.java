package com.eta.carrouter;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
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
 * LSPosed module: while the phone is connected to the car head-unit Wi-Fi hotspot,
 * keep the cellular network as the system default (so every app still has internet),
 * and bind only the car-link app process back to that Wi-Fi for LAN access.
 *
 * Configuration (read at runtime, persist across reboot):
 *   setprop persist.carrouter.ssid "YourCarSSID"
 *   setprop persist.carrouter.apps "com.baidu.carlife,com.xxx.carlink"
 * If setprop is unavailable, edit DEFAULT_SSID / DEFAULT_APPS below and rebuild.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "CarRouter";
    private static final int CAP_INTERNET = 12;   // NetworkCapabilities.NET_CAPABILITY_INTERNET
    private static final int CAP_VALIDATED = 16;  // NET_CAPABILITY_VALIDATED
    private static final int CAP_CAPTIVE = 17;    // NET_CAPABILITY_CAPTIVE_PORTAL
    private static final int TRANSPORT_WIFI = 1;  // NetworkCapabilities.TRANSPORT_WIFI

    private static final String DEFAULT_SSID = "Cloudrive-020880";   // car head-unit hotspot
    private static final String DEFAULT_APPS =
            "com.baidu.carlife.xiaomi,com.mcarplay.app,com.baidu.carlife,com.qinggan.carlink,com.google.android.projection.gearhead";

    private static final WeakHashMap<Object, Boolean> STRIPPED = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> BUSY = new ThreadLocal<>();
    private static Object sCs;
    private static Context sContext;

    private static String prop(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class).invoke(null, key, def);
            return v == null ? def : (String) v;
        } catch (Throwable t) {
            return def;
        }
    }

    private static String unquote(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\""))
            s = s.substring(1, s.length() - 1);
        return s;
    }

    private static String targetSsid() {
        return unquote(prop("persist.carrouter.ssid", DEFAULT_SSID));
    }

    private static Set<String> carApps() {
        Set<String> out = new HashSet<>();
        String v = prop("persist.carrouter.apps", DEFAULT_APPS);
        if (v == null) return out;
        for (String p : v.split("[,;\\s]+")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp) {
        if ("android".equals(lp.packageName)) {
            hookSystemServer(lp.classLoader);
        } else if (carApps().contains(lp.packageName)) {
            hookCarApp(lp.classLoader);
        }
    }

    /* ---- system_server: strip INTERNET from the car Wi-Fi by SSID, keep cellular default ---- */

    private void hookSystemServer(ClassLoader cl) {
        Class<?> cs = null;
        for (String n : new String[]{
                "com.android.server.connectivity.ConnectivityService",  // Android 9+
                "com.android.server.ConnectivityService"}) {             // Android 7 / 8
            try {
                cs = XposedHelpers.findClass(n, cl);
                break;
            } catch (Throwable ignored) {
            }
        }
        if (cs == null) {
            XposedBridge.log(TAG + ": ConnectivityService not found");
            return;
        }

        XposedBridge.hookAllConstructors(cs, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                sCs = p.thisObject;
                sContext = findContext(p.thisObject, p.args);
            }
        });

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
        XposedBridge.log(TAG + ": hooked " + n + " ConnectivityService methods");
    }

    private static void enforce(XC_MethodHook.MethodHookParam p) {
        if (Boolean.TRUE.equals(BUSY.get())) return;
        Object cs = sCs != null ? sCs : (p == null ? null : p.thisObject);
        if (cs == null) return;
        Context ctx = sContext != null ? sContext : findContext(cs, null);
        String want = targetSsid();
        BUSY.set(true);
        try {
            boolean match = !want.isEmpty() && want.equals(currentSsid(ctx));
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
                            bindToCarWifi((Context) p.thisObject);
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

    /* ---- reflection helpers: locate NetworkAgentInfo across AOSP versions ---- */

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
