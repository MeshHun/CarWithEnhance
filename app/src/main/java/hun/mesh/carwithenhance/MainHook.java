package hun.mesh.carwithenhance;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Field;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "carwithenhance";
    private static final String TARGET_PACKAGE = "com.miui.carlink";

    // SharedPreferences 文件名（复用 CarWith 自身已有的配置文件）
    private static final String PREFS_FILE = "file_prefer_app_091703";

    // 开关 Key 定义
    public static final String KEY_HOOK_AUTOPLAY      = "hook_autoplay_enabled";
    public static final String KEY_HOOK_QP            = "hook_qp_enabled";
    public static final String KEY_HOOK_DISCONNECT_BT = "hook_disconnect_bt_enabled";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {

        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            return;
        }

        xlog(">> 成功拦截小米 CarWith, 注入优化逻辑...");
        final ClassLoader cl = lpparam.classLoader;

        // =========================================================================
        // Hook 1: 完美续播 (跳过车机下发的强制歌单选择，直接触发手机 App 自动播放)
        // =========================================================================
        try {
            XposedHelpers.findAndHookMethod("ja.a$b", cl, "a",
                    boolean.class, int.class, java.util.List.class, int.class, String.class, android.os.Bundle.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            // 读取开关状态
                            Context ctx = getAppContext(cl);
                            if (ctx != null && !ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                                    .getBoolean(KEY_HOOK_AUTOPLAY, true)) {
                                xlog("QQ续播优化关闭，走默认逻辑");
                                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                            }

                            String pkgName = null;
                            boolean isAutoPlay = false;
                            Object ja_a_instance = null;

                            // 通过反射获取混淆后的内部类成员变量
                            for (Field f : param.thisObject.getClass().getDeclaredFields()) {
                                f.setAccessible(true);
                                if (f.getType() == String.class && pkgName == null) {
                                    pkgName = (String) f.get(param.thisObject);
                                } else if (f.getType() == boolean.class) {
                                    isAutoPlay = f.getBoolean(param.thisObject);
                                } else if (f.getName().startsWith("this$0")) {
                                    ja_a_instance = f.get(param.thisObject);
                                }
                            }

                            if ("com.tencent.qqmusic".equals(pkgName)) {
                                xlog(">> 拦截 QQ 音乐列表请求，交由手机端原生续播机制处理");

                                // 通知 CarLink 内部状态：流程已成功结束，防止逻辑卡死
                                if (ja_a_instance != null) {
                                    XposedHelpers.callMethod(ja_a_instance, "p", true, pkgName, isAutoPlay);
                                }
                                return null; // 阻断原方法执行
                            }

                            // 非 QQ 音乐应用，保留原有逻辑
                            xlog(pkgName + "！！跳过");
                            return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                        }
                    });
        } catch (Throwable t) {
            xlogE("❌ 续播 Hook 注入失败: ", t);
        }

        // =========================================================================
        // Hook 2: CarLife 视频流 QP 量化参数注入
        // =========================================================================
        try {
            XposedHelpers.findAndHookMethod("com.xiaomi.ucar.carlife.c", cl, "i",
                    boolean.class, android.content.Intent.class, android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            android.os.Bundle bundle = (android.os.Bundle) param.args[2];
                            if (bundle == null) return;

                            // 读取开关状态
                            try {
                                Context ctx = getAppContext(cl);
                                if (ctx != null && !ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                                        .getBoolean(KEY_HOOK_QP, true)) {
                                    xlog(">> [画质解锁] QP 注入开关已关闭，跳过");
                                    return;
                                }
                            } catch (Throwable ignored) {}

                            xlog(">> [画质解锁] 拦截到 CarLife 编码配置请求...");
                            xlog(">> [画质解锁] 📦 注入前原始 Bundle: " + bundleToString(bundle));

                            // 注入高通扩展 QP 参数
                            bundle.putInt("vendor.qti-ext-enc-qp-range.qp-i-min", 10);
                            bundle.putInt("vendor.qti-ext-enc-qp-range.qp-p-min", 10);
                            bundle.putInt("vendor.qti-ext-enc-qp-range.qp-i-max", 28);
                            bundle.putInt("vendor.qti-ext-enc-qp-range.qp-p-max", 28);

                            xlog(">> [画质解锁] 📦 注入后最终 Bundle: " + bundleToString(bundle));
                        }
                    });
        } catch (Throwable t) {
            xlogE("❌ Hook 2 (CarLife QP 注入) 失败: ", t);
        }

        // =========================================================================
        // Hook 3: 向 CarWith 车机设置界面注入二级菜单和优化开关
        // 策略：
        //   3-1. Hook Gson.fromJson，在 JSON 底层注入「优化hook」二级菜单节点。
        //   3-2. Hook m6.i0.k()，为我们自定义的 key 提供初始开关状态（读 SP）。
        //        注意：i0.k() 内部是 switch-string，未知 key 会走 default 分支返回 false，
        //        所以必须在此拦截，否则开关永远显示 OFF。
        //   3-3. Hook SettingsIndexAdapter.x0()，在车机端 ViewHolder 绑定后覆盖 itemView
        //        的 OnClickListener，实现点击保存到 SP 并同步 UI。
        //        — 不 hook w0()：w0 是纯 switch-string 路由，未知 key 走 default 不执行任何操作，
        //          beforeHook 中 setResult(null) 也因为运行时原方法依然会被继续调度而无效。
        //        — 不 hook setSpValue：该方法仅修改内存中的数据模型，不写 SP，且 JSON 注入的
        //          节点的 spValue 也未必会被调用到此方法（取决于 Gson 反序列化是否走 setter）。
        //   3-4. Hook UCarScreenSettingsFragment.onCreatePreferences()，
        //        向手机端设置页面注入相同 key 的 SwitchPreference，实现双端 SP 共享同步。
        // =========================================================================
        try {
            // 3-1. 拦截 Gson 解析，通过 JSON 注入原生二级菜单
            Class<?> gsonClass = XposedHelpers.findClass("com.google.gson.Gson", cl);
            XposedHelpers.findAndHookMethod(gsonClass, "fromJson", String.class, java.lang.reflect.Type.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String json = (String) param.args[0];
                    // 识别特征：确保是 CarWith 的 settings_config.json
                    if (json != null && json.contains("carwith_ui_size") && json.contains("user_setting_framerate")) {
                        // 检查是否已经注入过，防止重复
                        if (json.contains("mesh_hook_optimization_menu")) return;

                        // 构造二级菜单 JSON 节点 (spValue 字段只影响 JSON 解析时的初始内存值，
                        // 真正的显示状态由 i0.k() 决定，所以 spValue 随便写 1 即可)
                        String hookMenuJson = "{"
                                + "\"type\":\"router\","
                                + "\"title\":\"CarWith Enhance\","
                                + "\"router\":\"mesh_hook_optimization_menu\","
                                + "\"value\":["
                                + "{"
                                + "\"type\":\"switch\","
                                + "\"title\":\"QQ音乐完美续播\","
                                + "\"subTitle\":\"开启后阻止上车续播播放歌单《巅峰潮流榜》\","
                                + "\"key\":\"" + KEY_HOOK_AUTOPLAY + "\","
                                + "\"spValue\":1"
                                + "},"
                                + "{"
                                + "\"type\":\"switch\","
                                + "\"title\":\"高画质 QP 注入\","
                                + "\"subTitle\":\"限制视频编码QP量化参数区间，改善高帧率场景下画面突变导致的画面模糊\","
                                + "\"key\":\"" + KEY_HOOK_QP + "\","
                                + "\"spValue\":1"
                                + "},"
                                + "{"
                                + "\"type\":\"switch\","
                                + "\"title\":\"无感触发后断开蓝牙\","
                                + "\"subTitle\":\"无感连接触发后自动断开手机对车机的蓝牙音频(A2DP/通话)，防止音频输出设备冲突。作者自用，非必要不开启！！\","
                                + "\"key\":\"" + KEY_HOOK_DISCONNECT_BT + "\","
                                + "\"spValue\":1"
                                + "}"
                                + "]"
                                + "}";

                        int lastBracket = json.lastIndexOf(']');
                        if (lastBracket != -1) {
                            param.args[0] = json.substring(0, lastBracket) + "," + hookMenuJson + json.substring(lastBracket);
                            xlog(">> [设置注入] 成功通过 Gson.fromJson 注入【优化hook】二级菜单！");
                        }
                    }
                }
            });

            // 3-2. 接管 i0.k()，为自定义 key 提供正确初始开关状态
            // 注意：该方法内部是 switch-string，未知 key 走 default 返回 false，
            // 必须在此 beforeHook 中提前 setResult，否则车机端开关永远初始显示 OFF。
            Class<?> i0Class = XposedHelpers.findClass("m6.i0", cl);
            XposedHelpers.findAndHookMethod(i0Class, "k",
                    android.content.Context.class, String.class, android.content.SharedPreferences.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String key = (String) param.args[1];
                            if (KEY_HOOK_AUTOPLAY.equals(key) || KEY_HOOK_QP.equals(key) || KEY_HOOK_DISCONNECT_BT.equals(key)) {
                                // 优先从我们自己的 SP 读，不用 i0.k 内部的 sharedPreferences 参数
                                // 因为 i0.k 传入的 sp 是 CarWith 自己的 SP，正好和 PREFS_FILE 相同，
                                // 可以直接用 param.args[2] 读取，这样就完全不需要 BaseApplication
                                SharedPreferences sp = (SharedPreferences) param.args[2];
                                boolean val = sp.getBoolean(key, false); // 默认 true = 开
                                param.setResult(val);
                                xlog(">> [设置注入] i0.k 拦截：" + key + " = " + val);
                            }
                        }
                    });

            // 3-3. Hook SettingsIndexAdapter.x0()，在 ViewHolder 绑定后接管 itemView 点击
            // x0() 是车机设置列表中专门处理 switch 类型的绑定方法，每次 RecyclerView 复用时调用。
            // 它会在末尾通过 itemView.setOnClickListener(new ...) 设定点击逻辑。
            // 我们在 afterHook 里再次覆盖 itemView 的 OnClickListener，就能完整接管点击事件，
            // 且与 switch-string 路由完全无关，不存在内联/混淆问题。
            Class<?> adapterClass = XposedHelpers.findClass(
                    "com.carwith.launcher.settings.car.adapter.SettingsIndexAdapter", cl);
            XposedBridge.hookAllMethods(adapterClass, "x0", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args == null || param.args.length < 2) return;

                    // x0(SettingViewHolderTypeSwitch, SettingCategory)
                    Object viewHolder = param.args[0];
                    Object category  = param.args[1];

                    final String key = (String) XposedHelpers.callMethod(category, "getKey");
                    if (!KEY_HOOK_AUTOPLAY.equals(key) && !KEY_HOOK_QP.equals(key) && !KEY_HOOK_DISCONNECT_BT.equals(key)) return;

                    // 从 ViewHolder 中找到 itemView（RecyclerView.ViewHolder 的 public final View itemView 字段）
                    final android.view.View itemView = (android.view.View) XposedHelpers.getObjectField(viewHolder, "itemView");
                    if (itemView == null) return;

                    // 找到 SwitchCompat（ViewHolder 里的 f7003d 字段）
                    // 因为字段名是混淆的，用反射遍历找类型为 CompoundButton 的字段
                    android.widget.CompoundButton switchView = null;
                    for (Field f : viewHolder.getClass().getDeclaredFields()) {
                        f.setAccessible(true);
                        Object val = f.get(viewHolder);
                        if (val instanceof android.widget.CompoundButton) {
                            switchView = (android.widget.CompoundButton) val;
                            break;
                        }
                    }

                    final android.widget.CompoundButton finalSwitch = switchView;

                    // 覆盖 itemView 的 OnClickListener，完整接管点击逻辑
                    itemView.setOnClickListener(new android.view.View.OnClickListener() {
                        @Override
                        public void onClick(android.view.View v) {
                            if (finalSwitch == null) return;

                            boolean newVal = !finalSwitch.isChecked();
                            // 先更新 UI
                            finalSwitch.setChecked(newVal);

                            // 持久化写入 SharedPreferences
                            Context ctx = v.getContext();
                            SharedPreferences sp = ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
                            sp.edit().putBoolean(key, newVal).apply();

                            xlog(">> [车机端设置] 开关 " + key + " 点击，写入 SP: " + newVal);
                        }
                    });

                    xlog(">> [车机端设置] x0 绑定完成，已接管 itemView 点击: key=" + key);
                }
            });

        } catch (Throwable t) {
            xlogE("❌ Hook 3 (车机端设置界面注入与交互) 失败: ", t);
        }

        // =========================================================================
        // Hook 4: 向 CarWith 手机端设置界面注入开关条目
        // 与车机端共享同一个 SP 文件 + 同一组 key，实现双端实时同步。
        // 手机端用 Preference 体系，状态由 onResume 刷新，无需广播。
        // =========================================================================
        try {
            Class<?> ucarScreenSettingsFragmentClass = XposedHelpers.findClass(
                    "com.carwith.launcher.settings.phone.UCarScreenSettingsActivity$UCarScreenSettingsFragment", cl);

            XposedHelpers.findAndHookMethod(ucarScreenSettingsFragmentClass, "onCreatePreferences",
                    android.os.Bundle.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            android.app.Activity activity = (android.app.Activity) XposedHelpers.callMethod(param.thisObject, "getActivity");
                            if (activity == null) return;

                            Object preferenceScreen = XposedHelpers.callMethod(param.thisObject, "getPreferenceScreen");
                            if (preferenceScreen == null) return;

                            final Context ctx = activity;
                            // 手机端和车机端共用同一个 SP 文件，天然同步
                            final SharedPreferences prefs = ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);

                            // 创建 PreferenceCategory
                            Class<?> categoryClass = XposedHelpers.findClass("androidx.preference.PreferenceCategory", cl);
                            Object category = XposedHelpers.newInstance(categoryClass, ctx);
                            XposedHelpers.callMethod(category, "setTitle", "CarWith Enhance");

                            // 创建 QQ音乐完美续播 开关
                            Class<?> switchPrefClass = XposedHelpers.findClass("androidx.preference.SwitchPreference", cl);
                            Object autoPlayPref = XposedHelpers.newInstance(switchPrefClass, ctx);
                            XposedHelpers.callMethod(autoPlayPref, "setKey", KEY_HOOK_AUTOPLAY);
                            XposedHelpers.callMethod(autoPlayPref, "setTitle", "QQ音乐完美续播");
                            XposedHelpers.callMethod(autoPlayPref, "setSummary", "连接车机后自动恢复手机端播放状态");
                            XposedHelpers.callMethod(autoPlayPref, "setDefaultValue", true);
                            // 注意：setDefaultValue 只在第一次使用该 key 时生效。
                            // 如果 SP 里已有值（可能由车机端写入），必须强制 setChecked 同步显示。
                            XposedHelpers.callMethod(autoPlayPref, "setChecked", prefs.getBoolean(KEY_HOOK_AUTOPLAY, true));

                            Class<?> changeListenerClass = XposedHelpers.findClass("androidx.preference.Preference$OnPreferenceChangeListener", cl);

                            Object listenerAutoPlay = java.lang.reflect.Proxy.newProxyInstance(
                                    cl,
                                    new Class<?>[]{ changeListenerClass },
                                    new java.lang.reflect.InvocationHandler() {
                                        @Override
                                        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                                            if ("onPreferenceChange".equals(method.getName())) {
                                                boolean val = (Boolean) args[1];
                                                prefs.edit().putBoolean(KEY_HOOK_AUTOPLAY, val).apply();
                                                xlog(">> [手机端设置] 开关已更新: " + KEY_HOOK_AUTOPLAY + " = " + val);
                                                return true;
                                            }
                                            return null;
                                        }
                                    }
                            );
                            XposedHelpers.callMethod(autoPlayPref, "setOnPreferenceChangeListener", listenerAutoPlay);

                            // 创建 QP 画质注入开关
                            Object qpPref = XposedHelpers.newInstance(switchPrefClass, ctx);
                            XposedHelpers.callMethod(qpPref, "setKey", KEY_HOOK_QP);
                            XposedHelpers.callMethod(qpPref, "setTitle", "CarLife高画质 QP注入");
                            XposedHelpers.callMethod(qpPref, "setSummary", "强制设定视频编码QP区间，改善画面模糊");
                            XposedHelpers.callMethod(qpPref, "setDefaultValue", true);
                            XposedHelpers.callMethod(qpPref, "setChecked", prefs.getBoolean(KEY_HOOK_QP, true));

                            Object listenerQP = java.lang.reflect.Proxy.newProxyInstance(
                                    cl,
                                    new Class<?>[]{ changeListenerClass },
                                    new java.lang.reflect.InvocationHandler() {
                                        @Override
                                        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                                            if ("onPreferenceChange".equals(method.getName())) {
                                                boolean val = (Boolean) args[1];
                                                prefs.edit().putBoolean(KEY_HOOK_QP, val).apply();
                                                xlog(">> [手机端设置] 开关已更新: " + KEY_HOOK_QP + " = " + val);
                                                return true;
                                            }
                                            return null;
                                        }
                                    }
                            );
                            XposedHelpers.callMethod(qpPref, "setOnPreferenceChangeListener", listenerQP);

                            // 创建 连接后断开蓝牙音频 开关
                            Object btPref = XposedHelpers.newInstance(switchPrefClass, ctx);
                            XposedHelpers.callMethod(btPref, "setKey", KEY_HOOK_DISCONNECT_BT);
                            XposedHelpers.callMethod(btPref, "setTitle", "无感触发后断开蓝牙");
                            XposedHelpers.callMethod(btPref, "setSummary", "无感连接触发后自动断开手机对车机的蓝牙音频(A2DP/通话)，防止音频输出设备冲突。作者自用，非必要不开启！！");
                            XposedHelpers.callMethod(btPref, "setDefaultValue", false); // 默认关闭
                            XposedHelpers.callMethod(btPref, "setChecked", prefs.getBoolean(KEY_HOOK_DISCONNECT_BT, false));

                            Object listenerBT = java.lang.reflect.Proxy.newProxyInstance(
                                    cl,
                                    new Class<?>[]{ changeListenerClass },
                                    new java.lang.reflect.InvocationHandler() {
                                        @Override
                                        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                                            if ("onPreferenceChange".equals(method.getName())) {
                                                boolean val = (Boolean) args[1];
                                                prefs.edit().putBoolean(KEY_HOOK_DISCONNECT_BT, val).apply();
                                                xlog(">> [手机端设置] 开关已更新: " + KEY_HOOK_DISCONNECT_BT + " = " + val);
                                                return true;
                                            }
                                            return null;
                                        }
                                    }
                            );
                            XposedHelpers.callMethod(btPref, "setOnPreferenceChangeListener", listenerBT);

                            // 将 Preference 组装并添加到 PreferenceScreen
                            XposedHelpers.callMethod(preferenceScreen, "addPreference", category);
                            XposedHelpers.callMethod(category, "addPreference", autoPlayPref);
                            XposedHelpers.callMethod(category, "addPreference", qpPref);
                            XposedHelpers.callMethod(category, "addPreference", btPref);

                            xlog(">> [手机端设置] 成功注入画质优化开关分组");
                        }
                    });

            // 手机端设置页面 onResume 时刷新开关状态，防止从车机端切换后状态陈旧
            XposedHelpers.findAndHookMethod(ucarScreenSettingsFragmentClass, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            android.app.Activity activity = (android.app.Activity) XposedHelpers.callMethod(param.thisObject, "getActivity");
                            if (activity == null) return;

                            SharedPreferences prefs = activity.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);

                            Object preferenceScreen = XposedHelpers.callMethod(param.thisObject, "getPreferenceScreen");
                            if (preferenceScreen == null) return;

                            // 通过 key 找到对应的 Preference 并刷新选中状态
                            refreshSwitchPref(preferenceScreen, KEY_HOOK_AUTOPLAY, prefs.getBoolean(KEY_HOOK_AUTOPLAY, true));
                            refreshSwitchPref(preferenceScreen, KEY_HOOK_QP, prefs.getBoolean(KEY_HOOK_QP, true));
                            refreshSwitchPref(preferenceScreen, KEY_HOOK_DISCONNECT_BT, prefs.getBoolean(KEY_HOOK_DISCONNECT_BT, false));
                            xlog(">> [手机端设置] onResume 刷新开关状态完成");
                        }
                    });

        } catch (Throwable t) {
            xlogE("❌ Hook 4 (手机端设置界面注入) 失败: ", t);
        }

        // =========================================================================
        // Hook 5: 连接车机热点后自动断开蓝牙音频 (A2DP / HFP / 通话)
        // 解决部分车机无线投屏与蓝牙通话/音乐声道冲突的问题
        // =========================================================================
        try {
            Class<?> autoConnectManagerClass = XposedHelpers.findClass("k9.b", cl);
            XposedBridge.hookAllMethods(autoConnectManagerClass, "B", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // 读取开关状态
                    Context appCtx = getAppContext(cl);
                    if (appCtx != null && !appCtx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                            .getBoolean(KEY_HOOK_DISCONNECT_BT, false)) {
                        xlog(">> [自动断开蓝牙] 功能已关闭，跳过执行");
                        return;
                    }

                    xlog("====== 拦截到连接 CarAP 热点请求，执行断开蓝牙音频控制 ======");
                    Object clInfo = param.args[1];
                    Object ecInfo = param.args[2];
                    String carMac = null;
                    if (clInfo != null) {
                        carMac = (String) XposedHelpers.callMethod(clInfo, "getCarBtMacAddress");
                    } else if (ecInfo != null) {
                        carMac = (String) XposedHelpers.callMethod(ecInfo, "getCarBtMacAddress");
                    }

                    final Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "b");
                    if (context != null && carMac != null) {
                        final String finalCarMac = carMac;
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                disconnectBluetoothDevice(context, finalCarMac);
                            }
                        }, 1500);
                    }
                }
            });
        } catch (Throwable t) {
            xlogE("❌ Hook 5 (无线连接后断开蓝牙音频) 失败: ", t);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 工具方法 & 辅助方法
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 自动断开指定蓝牙设备的音频和通话Profile
     */
    private static void disconnectBluetoothDevice(final Context context, String mac) {
        final android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return;

        final android.bluetooth.BluetoothDevice targetDevice;
        try {
            targetDevice = adapter.getRemoteDevice(mac);
        } catch (IllegalArgumentException e) {
            return;
        }

        // 断开通话音频 (HFP / HEADSET)
        adapter.getProfileProxy(context, new android.bluetooth.BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, android.bluetooth.BluetoothProfile proxy) {
                try {
                    java.lang.reflect.Method disconnectMethod = proxy.getClass().getMethod("disconnect", android.bluetooth.BluetoothDevice.class);
                    disconnectMethod.setAccessible(true);
                    disconnectMethod.invoke(proxy, targetDevice);
                    adapter.closeProfileProxy(profile, proxy);
                } catch (Exception ignored) {}
            }

            @Override
            public void onServiceDisconnected(int profile) {}
        }, android.bluetooth.BluetoothProfile.HEADSET);

        // 断开媒体音频 (A2DP)
        adapter.getProfileProxy(context, new android.bluetooth.BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, android.bluetooth.BluetoothProfile proxy) {
                try {
                    java.lang.reflect.Method disconnectMethod = proxy.getClass().getMethod("disconnect", android.bluetooth.BluetoothDevice.class);
                    disconnectMethod.setAccessible(true);
                    disconnectMethod.invoke(proxy, targetDevice);
                    adapter.closeProfileProxy(profile, proxy);
                } catch (Exception ignored) {}
            }

            @Override
            public void onServiceDisconnected(int profile) {}
        }, android.bluetooth.BluetoothProfile.A2DP);
    }

    /**
     * 获取 CarWith 应用的全局 Context。
     * 使用 BaseApplication.c() 而非 f()，后者返回 List<String> 而非 Context。
     */
    private static Context getAppContext(ClassLoader cl) {
        try {
            return (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.carwith.common.BaseApplication", cl), "c");
        } catch (Throwable t) {
            xlogE("getAppContext 失败: ", t);
            return null;
        }
    }

    /**
     * 通过 PreferenceScreen.findPreference(key) 找到对应 SwitchPreference 并刷新选中状态。
     */
    private static void refreshSwitchPref(Object preferenceScreen, String key, boolean checked) {
        try {
            Object pref = XposedHelpers.callMethod(preferenceScreen, "findPreference", key);
            if (pref != null) {
                XposedHelpers.callMethod(pref, "setChecked", checked);
            }
        } catch (Throwable ignored) {}
    }

    private static String bundleToString(android.os.Bundle bundle) {
        if (bundle == null) return "null";
        StringBuilder sb = new StringBuilder("{");
        for (String key : bundle.keySet()) {
            sb.append(key).append("=").append(bundle.get(key)).append(", ");
        }
        if (sb.length() > 1) sb.setLength(sb.length() - 2);
        sb.append("}");
        return sb.toString();
    }

    private static void xlog(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }

    private static void xlogE(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        XposedBridge.log(TAG + " ERROR: " + msg + (t != null ? " (" + t.getMessage() + ")" : ""));
        if (t != null) {
            XposedBridge.log(t);
        }
    }
}