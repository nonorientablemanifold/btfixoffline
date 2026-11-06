package com.example.btfixoffline;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class Hook implements IXposedHookLoadPackage {

    private static final String BT_UTILS = "com.android.settings.bluetooth.MiuiBTUtils";
    private static final String WN_CHECK = "miui.securitycenter.utils.WNCheckManager";
    private static final String DIALOG = "com.android.settings.bluetooth.LocalDeviceNameDialogFragment";
    private static final String BT_BINDER = "com.android.bluetooth.btservice.AdapterServiceBinder";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        String pkg = lpparam.packageName;
        final ClassLoader cl = lpparam.classLoader;

        if ("com.android.settings".equals(pkg)) {
            installSettingsHooks(cl);
            installFakeNetworkHooks(cl);
        }

        if ("com.android.bluetooth".equals(pkg)) {
            installBluetoothPropSync(cl);
        }
    }

    /**
     * 关键：让设置进程始终认为"有活动网络"。
     * 框架方法不会被 R8 内联，所以对任何 isNetwork()/isNetworkAvailable() 实现都无效化。
     */
    /** 标记哪些 NetworkCapabilities 是我们伪造的，hasTransport 兜底只对它们生效 */
    private static final java.util.WeakHashMap<Object, Boolean> FAKE_CAPS =
            new java.util.WeakHashMap<Object, Boolean>();

    private void installFakeNetworkHooks(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.net.ConnectivityManager", cl,
                    "getActiveNetwork", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Object real = pickRealNetwork(param.thisObject);
                            if (real != null) {
                                param.setResult(real);
                                return;
                            }
                            XposedBridge.log("BTFIX: getActiveNetwork -> fake");
                            param.setResult(fakeNetwork());
                        }
                    });
            XposedBridge.log("BTFIX: hooked getActiveNetwork");
        } catch (Throwable t) {
            XposedBridge.log("BTFIX: getActiveNetwork hook failed -> " + t);
        }

        try {
            Class<?> networkCls = Class.forName("android.net.Network");
            XposedHelpers.findAndHookMethod("android.net.ConnectivityManager", cl,
                    "getNetworkCapabilities", networkCls, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (isFakeNetwork(param.args[0])) {
                                param.setResult(fakeCapabilities());
                            }
                        }
                    });
            XposedBridge.log("BTFIX: hooked getNetworkCapabilities");
        } catch (Throwable t) {
            XposedBridge.log("BTFIX: getNetworkCapabilities hook failed -> " + t);
        }

        // 假网络没有真实的链路信息，getLinkProperties 会返回 null，
        // 设置里"无线调试"的 IP 地址就会显示"无法获取"。这里借真实 WLAN 的链路信息。
        try {
            Class<?> networkCls2 = Class.forName("android.net.Network");
            XposedHelpers.findAndHookMethod("android.net.ConnectivityManager", cl,
                    "getLinkProperties", networkCls2, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!isFakeNetwork(param.args[0])) return;
                            Object lp = pickWifiLinkProperties(param.thisObject);
                            if (lp != null) {
                                param.setResult(lp);
                            }
                        }
                    });
            XposedBridge.log("BTFIX: hooked getLinkProperties");
        } catch (Throwable t) {
            XposedBridge.log("BTFIX: getLinkProperties hook failed -> " + t);
        }

        // 老接口也一并兜住
        try {
            XposedHelpers.findAndHookMethod("android.net.ConnectivityManager", cl,
                    "getActiveNetworkInfo", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(fakeNetworkInfo());
                        }
                    });
            XposedBridge.log("BTFIX: hooked getActiveNetworkInfo");
        } catch (Throwable t) {
            XposedBridge.log("BTFIX: getActiveNetworkInfo hook failed -> " + t);
        }

        // 兜底：即使 transport 字段反射失败，也强制 hasTransport(WIFI) 为 true
        try {
            XposedHelpers.findAndHookMethod("android.net.NetworkCapabilities", cl,
                    "hasTransport", int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!FAKE_CAPS.containsKey(param.thisObject)) return;
                            if (param.args[0] instanceof Integer
                                    && ((Integer) param.args[0]).intValue() == 1) {
                                param.setResult(Boolean.TRUE);
                            }
                        }
                    });
            XposedBridge.log("BTFIX: hooked hasTransport(WIFI)");
        } catch (Throwable t) {
            XposedBridge.log("BTFIX: hasTransport hook failed -> " + t);
        }
    }

        private boolean isFakeNetwork(Object net) {
        if (net == null) return false;
        try {
            return String.valueOf(net).contains("9999");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 优先返回真实的 WLAN 网络；没有 WLAN 时返回 null，由调用方回退到假网络 */
    private Object pickRealNetwork(Object cm) {
        try {
            Object netsObj = XposedHelpers.callMethod(cm, "getAllNetworks");
            if (!(netsObj instanceof Object[])) return null;
            for (Object n : (Object[]) netsObj) {
                if (n == null) continue;
                Object lp = XposedHelpers.callMethod(cm, "getLinkProperties", n);
                if (lp == null) continue;
                Object iface = XposedHelpers.callMethod(lp, "getInterfaceName");
                if (String.valueOf(iface).startsWith("wlan")) return n;
            }
        } catch (Throwable t) {
            XposedBridge.log("BTFIX: pickRealNetwork failed " + t.getClass().getName());
        }
        return null;
    }

    /** 取真实 WLAN 的 LinkProperties，给假网络借用，让 IP 能显示出来 */
    private Object pickWifiLinkProperties(Object cm) {
        try {
            Object netsObj = XposedHelpers.callMethod(cm, "getAllNetworks");
            if (!(netsObj instanceof Object[])) return null;
            for (Object n : (Object[]) netsObj) {
                if (n == null) continue;
                Object lp = XposedHelpers.callMethod(cm, "getLinkProperties", n);
                if (lp == null) continue;
                Object iface = XposedHelpers.callMethod(lp, "getInterfaceName");
                if (String.valueOf(iface).startsWith("wlan")) return lp;
            }
        } catch (Throwable t) {
            XposedBridge.log("BTFIX: pickWifiLinkProperties failed " + t.getClass().getName());
        }
        return null;
    }

    private Object fakeNetwork() {
        try {
            Class<?> c = Class.forName("android.net.Network");
            Constructor<?> ctor = c.getConstructor(int.class);
            return ctor.newInstance(Integer.valueOf(9999));
        } catch (Throwable t) {
            XposedBridge.log("BTFIX: fakeNetwork failed " + t.getClass().getName());
            return null;
        }
    }

    private Object fakeCapabilities() {
        try {
            Class<?> c = Class.forName("android.net.NetworkCapabilities");
            Object caps = c.getConstructor().newInstance();
            Method add = c.getMethod("addCapability", int.class);
            add.invoke(caps, Integer.valueOf(12)); // NET_CAPABILITY_INTERNET
            add.invoke(caps, Integer.valueOf(16)); // NET_CAPABILITY_VALIDATED
            add.invoke(caps, Integer.valueOf(11)); // NET_CAPABILITY_NOT_RESTRICTED

            // 关键修复：假网络必须声明自己是 Wi-Fi 链路，
            // 否则设置里的"无线调试"会判定未连接 WLAN，开关变灰点不动。
            boolean wifiTransport = false;
            try {
                java.lang.reflect.Field tf = c.getDeclaredField("mTransportTypes");
                tf.setAccessible(true);
                tf.setLong(caps, 1L << 1); // TRANSPORT_WIFI = 1
                wifiTransport = true;
            } catch (Throwable t1) {
                try {
                    Method st = c.getMethod("setTransportType", int.class);
                    st.invoke(caps, Integer.valueOf(1));
                    wifiTransport = true;
                } catch (Throwable t2) {
                    try {
                        Method sts = c.getMethod("setTransportTypes", int[].class);
                        sts.invoke(caps, (Object) new int[]{1});
                        wifiTransport = true;
                    } catch (Throwable t3) {
                        XposedBridge.log("BTFIX: setTransport failed " + t3.getClass().getName());
                    }
                }
            }
            XposedBridge.log("BTFIX: fakeCaps wifiTransport=" + wifiTransport);
            FAKE_CAPS.put(caps, Boolean.TRUE);
            return caps;
        } catch (Throwable t) {
            XposedBridge.log("BTFIX: fakeCaps failed " + t.getClass().getName());
            return null;
        }
    }

    private Object fakeNetworkInfo() {
        try {
            Class<?> c = Class.forName("android.net.NetworkInfo");
            Constructor<?> ctor = c.getConstructor(int.class, int.class, String.class, String.class);
            return ctor.newInstance(Integer.valueOf(1), Integer.valueOf(1), "WIFI", "BTFixFake");
        } catch (Throwable t) {
            return null;
        }
    }

    private void installBluetoothPropSync(final ClassLoader cl) {
        try {
            Class<?> binder = Class.forName(BT_BINDER, false, cl);
            int count = 0;
            for (Method m : binder.getDeclaredMethods()) {
                if (!"setName".equals(m.getName())) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args == null || param.args.length == 0) return;
                        Object arg = param.args[0];
                        if (!(arg instanceof String)) return;
                        String name = (String) arg;
                        boolean ok = writeProp("persist.bluetooth.device_name", name);
                        writeProp("persist.sys.bt_local_name", name);
                        XposedBridge.log("BTFIX: prop sync btdevice=" + ok);
                    }
                });
                count++;
            }
            XposedBridge.log("BTFIX: hooked AdapterServiceBinder.setName x" + count);
        } catch (Throwable t) {
            XposedBridge.log("BTFIX: btprop hook failed -> " + t);
        }
    }

    private boolean writeProp(String key, String value) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method set = sp.getMethod("set", String.class, String.class);
            set.invoke(null, key, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void installSettingsHooks(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(DIALOG, cl, "setDeviceName", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String name = (String) param.args[0];
                            XposedBridge.log("BTFIX: rename start");
                            try {
                                Object self = param.thisObject;
                                Context ctx = (Context) XposedHelpers.callMethod(self, "getContext");
                                Object adapter = XposedHelpers.getObjectField(self, "mBluetoothAdapter");
                                XposedHelpers.callMethod(adapter, "setName", name);

                                Class<?> btUtils = XposedHelpers.findClass(BT_UTILS, cl);
                                XposedHelpers.callStaticMethod(btUtils, "setBluetoothName", name);

                                Class<?> miuiUtils = XposedHelpers.findClass(
                                        "com.android.settings.MiuiUtils", cl);
                                XposedHelpers.callStaticMethod(miuiUtils, "setDeviceName", ctx, name);

                                XposedBridge.log("BTFIX: rename applied");
                                scheduleLogClear();
                            } catch (Throwable t) {
                                XposedBridge.log("BTFIX: rename error " + t.getClass().getName());
                            }
                            param.setResult(null);
                        }
                    });
            XposedBridge.log("BTFIX: hooked dialog.setDeviceName");
        } catch (Throwable t) {
            XposedBridge.log("BTFIX: dialog hook failed -> " + t);
        }

        // 兜底：不论从哪个页面改名，只要写入 Secure 里的名字，就安排清日志
        try {
            XposedHelpers.findAndHookMethod("android.provider.Settings$Secure", cl, "putString",
                    android.content.ContentResolver.class, String.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object key = param.args[1];
                            if (!(key instanceof String)) return;
                            String k = (String) key;
                            if ("bluetooth_name".equals(k) || "device_name".equals(k)
                                    || "bluetooth_name".equals(k.replace("persist.", ""))) {
                                XposedBridge.log("BTFIX: secure name write (" + k + ") -> schedule clear");
                                scheduleLogClear();
                            }
                        }
                    });
            XposedBridge.log("BTFIX: hooked Settings.Secure.putString");
        } catch (Throwable t) {
            XposedBridge.log("BTFIX: secure putString hook failed -> " + t);
        }

        hookTrue(cl, BT_UTILS, "isInternationalBuild");
        hookTrue(cl, BT_UTILS, "isNetwork", Context.class);
        hookTrue(cl, BT_UTILS, "isNetworkAvailable", Context.class);
        hookTrue(cl, BT_UTILS, "checkDeviceNameA", Context.class, String.class);

        try {
            XposedHelpers.findAndHookMethod(BT_UTILS, cl, "checkDeviceName",
                    Context.class, String.class,
                    "com.android.settings.bluetooth.MiuiBTUtils$CheckDeviceNameCallback",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Object cb = param.args[2];
                            if (cb != null) deliverSuccess(cb);
                            param.setResult(Boolean.TRUE);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("BTFIX: checkDeviceName hook failed -> " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(WN_CHECK, cl, "getCheckResultASync",
                    Context.class, int.class, String.class, Handler.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Handler h = (Handler) param.args[3];
                            if (h != null) {
                                Bundle b = new Bundle();
                                b.putInt("key_state", 0);
                                b.putBoolean("key_has_ques", false);
                                Message m = Message.obtain(h);
                                m.setData(b);
                                h.sendMessage(m);
                            }
                            param.setResult(null);
                        }
                    });
            XposedBridge.log("BTFIX: hooked WNCheckManager.async");
        } catch (Throwable t) {
            XposedBridge.log("BTFIX: async hook failed -> " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(WN_CHECK, cl, "getCheckResultSync",
                    Context.class, int.class, String.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            return new Object[]{Boolean.TRUE, Integer.valueOf(0)};
                        }
                    });
            XposedBridge.log("BTFIX: hooked WNCheckManager.sync");
        } catch (Throwable t) {
            XposedBridge.log("BTFIX: sync hook failed -> " + t);
        }
    }

    /**
      * 改名成功后延迟清空系统日志缓冲，避免"改过什么名字"被记录在 logcat 里。
      * 设置进程持有 android.permission.READ_LOGS，可直接清缓冲，无需 root。
      */
     private void scheduleLogClear() {
         try {
             new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                 @Override
                 public void run() {
                     clearLogs();
                 }
             }, 1500);
         } catch (Throwable t) {
             XposedBridge.log("BTFIX: schedule log clear failed " + t.getClass().getName());
         }
     }

     private void clearLogs() {
         try {
             Process p = Runtime.getRuntime().exec(new String[]{
                     "/system/bin/logcat", "-b", "main", "-b", "system",
                     "-b", "events", "-b", "crash", "-c"});
             int rc = p.waitFor();
             XposedBridge.log("BTFIX: logs cleared rc=" + rc);
         } catch (Throwable t) {
             XposedBridge.log("BTFIX: log clear failed " + t.getClass().getName());
         }
     }

     private void deliverSuccess(final Object cb) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Method m = cb.getClass().getMethod("onCheckResult", boolean.class, int.class);
                    m.setAccessible(true);
                    m.invoke(cb, Boolean.TRUE, Integer.valueOf(0));
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
    }

    private void hookTrue(ClassLoader cl, String cls, String method, Object... paramTypes) {
        int n = paramTypes.length;
        Object[] args = new Object[n + 1];
        System.arraycopy(paramTypes, 0, args, 0, n);
        args[n] = new ReplaceTrue();
        try {
            XposedHelpers.findAndHookMethod(cls, cl, method, args);
            XposedBridge.log("BTFIX: hooked " + method);
        } catch (Throwable t) {
            XposedBridge.log("BTFIX: hook failed " + method + " -> " + t);
        }
    }
}