package hun.mesh.carwithenhance.hook;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.lang.reflect.Field;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import hun.mesh.carwithenhance.config.HookConfigs;
import hun.mesh.carwithenhance.config.HookItem;
import hun.mesh.carwithenhance.utils.XLog;
import hun.mesh.carwithenhance.dexkit.CarWithDexKitManager;

/**
 * Hook 3 & Hook 4: 车机端与手机端设置面板注入、状态同步和点击接管
 */
public class SettingsHook implements IHook {

    @Override
    public void onHook(final ClassLoader cl) throws Throwable {
        
        // =========================================================================
        // 1. 车机端界面注入：Gson 解析拦截 & 二级菜单节点构造
        // =========================================================================
        try {
            Class<?> gsonClass = XposedHelpers.findClass("com.google.gson.Gson", cl);
            XposedHelpers.findAndHookMethod(gsonClass, "fromJson", String.class, java.lang.reflect.Type.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String json = (String) param.args[0];
                    if (json != null && json.contains("carwith_ui_size") && json.contains("user_setting_framerate")) {
                        if (json.contains("mesh_hook_optimization_menu")) return; // 防止重复注入

                        // 全动态构建 JSON 二级菜单
                        StringBuilder sb = new StringBuilder();
                        sb.append("{");
                        sb.append("\"type\":\"router\",");
                        sb.append("\"title\":\"CarWith Enhance\",");
                        sb.append("\"router\":\"mesh_hook_optimization_menu\",");
                        sb.append("\"value\":[");
                        List<HookItem> items = HookConfigs.getAllItems();
                        for (int i = 0; i < items.size(); i++) {
                            HookItem item = items.get(i);
                            sb.append("{");
                            sb.append("\"type\":\"switch\",");
                            sb.append("\"title\":\"").append(item.title).append("\",");
                            sb.append("\"subTitle\":\"").append(item.subTitle).append("\",");
                            sb.append("\"key\":\"").append(item.key).append("\",");
                            sb.append("\"spValue\":1");
                            sb.append("}");
                            if (i < items.size() - 1) {
                                sb.append(",");
                            }
                        }
                        sb.append("]");
                        sb.append("}");

                        String hookMenuJson = sb.toString();
                        int lastBracket = json.lastIndexOf(']');
                        if (lastBracket != -1) {
                            param.args[0] = json.substring(0, lastBracket) + "," + hookMenuJson + json.substring(lastBracket);
                            XLog.i(">> [车机端UI] 成功动态注入[CarWith Enhance]二级菜单！");
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XLog.e("❌ Hook 3-1 (车机端 JSON 二级菜单注入) 失败: ", t);
        }

        // =========================================================================
        // 2. 车机端状态读取：拦截以准确返回自定义配置
        // =========================================================================
        try {
            String className = CarWithDexKitManager.INSTANCE.getSettingsClass();
            String methodName = CarWithDexKitManager.INSTANCE.getSettingsMethod();
            if (className == null || className.isEmpty()) {
                XLog.e("SettingsHook 动态目标未找到，跳过 Hook");
                return;
            }
            Class<?> settingsStateClass = XposedHelpers.findClass(className, cl);
            XposedHelpers.findAndHookMethod(settingsStateClass, methodName,
                    Context.class, String.class, SharedPreferences.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String key = (String) param.args[1];
                            HookItem matchedItem = HookConfigs.findByKey(key);
                            if (matchedItem != null) {
                                Context ctx = (Context) param.args[0];
                                SharedPreferences mySp = ctx.getSharedPreferences(HookConfigs.PREFS_FILE, Context.MODE_PRIVATE);
                                boolean val = mySp.getBoolean(key, matchedItem.defaultValue);
                                param.setResult(val);
                                XLog.i(">> [车机端UI] 拦截读取状态：" + key + " = " + val);
                            }
                        }
                    });
        } catch (Throwable t) {
            XLog.e("❌ Hook 3-2 (车机端UI状态同步) 失败: ", t);
        }

        // =========================================================================
        // 3. 车机端点击交互：Hook SettingsIndexAdapter接管开关点击
        // =========================================================================
        try {
            String adapterClassName = CarWithDexKitManager.INSTANCE.getSettingsAdapterClass();
            String adapterMethodsStr = CarWithDexKitManager.INSTANCE.getSettingsAdapterMethods();
            if (adapterClassName == null || adapterClassName.isEmpty() || adapterMethodsStr == null || adapterMethodsStr.isEmpty()) {
                XLog.e("SettingsHook 动态目标 adapter 未找到，跳过 Hook 3-3");
            } else {
                Class<?> adapterClass = XposedHelpers.findClass(adapterClassName, cl);
                String[] targetMethods = adapterMethodsStr.split(",");
                
                // 遍历 DexKit 精准定位出的绑定方法名（例如 s0, x0）并逐个进行 Hook
                for (String methodName : targetMethods) {
                    if (methodName.trim().isEmpty()) continue;
                    
                    XposedBridge.hookAllMethods(adapterClass, methodName.trim(), new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.args == null || param.args.length < 2) return;
    
                            Object viewHolder = param.args[0];
                            Object category  = param.args[1];
                            if (viewHolder == null || category == null) return;
    
                            String key = null;
                            try {
                                key = (String) XposedHelpers.callMethod(category, "getKey");
                            } catch (Throwable ignore) {
                                return;
                            }
                            if (key == null || HookConfigs.findByKey(key) == null) return; // 仅接管我们定义的 Key
    
                            final android.view.View itemView = (android.view.View) XposedHelpers.getObjectField(viewHolder, "itemView");
                            if (itemView == null) return;
    
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
                            final String finalKey = key;
                            itemView.setOnClickListener(new android.view.View.OnClickListener() {
                                @Override
                                public void onClick(android.view.View v) {
                                    if (finalSwitch == null) return;
    
                                    boolean newVal = !finalSwitch.isChecked();
                                    finalSwitch.setChecked(newVal);
    
                                    Context ctx = v.getContext();
                                    SharedPreferences sp = ctx.getSharedPreferences(HookConfigs.PREFS_FILE, Context.MODE_PRIVATE);
                                    sp.edit().putBoolean(finalKey, newVal).apply();
    
                                    XLog.i(">> [车机端UI] 开关 " + finalKey + " 点击翻转，写入 SP: " + newVal);
                                }
                            });
    
                            XLog.i(">> [车机端UI] 绑定完成，已成功接管 itemView 点击: key=" + key);
                        }
                    });
                }
            }
        } catch (Throwable t) {
            XLog.e("❌ Hook 3-3 (车机端UI点击接管) 失败: ", t);
        }

        // =========================================================================
        // 4. 手机端设置界面注入：动态创建 SwitchPreference 形成双端互控
        // =========================================================================
        try {
            String fragmentClassName = CarWithDexKitManager.INSTANCE.getUcarSettingsFragmentClass();
            if (fragmentClassName == null || fragmentClassName.isEmpty()) {
                XLog.e("SettingsHook 动态目标 UCarScreenSettingsFragment 未找到，跳过 Hook 4");
            } else {
                Class<?> ucarScreenSettingsFragmentClass = XposedHelpers.findClass(fragmentClassName, cl);

                XposedHelpers.findAndHookMethod(ucarScreenSettingsFragmentClass, "onCreatePreferences",
                    Bundle.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            android.app.Activity activity = (android.app.Activity) XposedHelpers.callMethod(param.thisObject, "getActivity");
                            if (activity == null) return;

                            Object preferenceScreen = XposedHelpers.callMethod(param.thisObject, "getPreferenceScreen");
                            if (preferenceScreen == null) return;

                            final Context ctx = activity;
                            final SharedPreferences prefs = ctx.getSharedPreferences(HookConfigs.PREFS_FILE, Context.MODE_PRIVATE);

                            // 1. 创建 PreferenceCategory 分组
                            Class<?> categoryClass = XposedHelpers.findClass("androidx.preference.PreferenceCategory", cl);
                            Object category = XposedHelpers.newInstance(categoryClass, ctx);
                            XposedHelpers.callMethod(category, "setTitle", "CarWith Enhance");
                            XposedHelpers.callMethod(preferenceScreen, "addPreference", category);

                            // 2. 遍历统一数据配置表，全动态注入所有的 SwitchPreference 开关
                            Class<?> switchPrefClass = XposedHelpers.findClass("androidx.preference.SwitchPreference", cl);
                            Class<?> changeListenerClass = XposedHelpers.findClass("androidx.preference.Preference$OnPreferenceChangeListener", cl);

                            for (final HookItem item : HookConfigs.getAllItems()) {
                                Object pref = XposedHelpers.newInstance(switchPrefClass, ctx);
                                XposedHelpers.callMethod(pref, "setKey", item.key);
                                XposedHelpers.callMethod(pref, "setTitle", item.title);
                                XposedHelpers.callMethod(pref, "setSummary", item.subTitle);
                                XposedHelpers.callMethod(pref, "setDefaultValue", item.defaultValue);
                                XposedHelpers.callMethod(pref, "setChecked", prefs.getBoolean(item.key, item.defaultValue));

                                Object listener = java.lang.reflect.Proxy.newProxyInstance(
                                        cl,
                                        new Class<?>[]{ changeListenerClass },
                                        new java.lang.reflect.InvocationHandler() {
                                            @Override
                                            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                                                if ("onPreferenceChange".equals(method.getName())) {
                                                    boolean val = (Boolean) args[1];
                                                    prefs.edit().putBoolean(item.key, val).apply();
                                                    XLog.i(">> [手机端UI] 开关已更新: " + item.key + " = " + val);
                                                    return true;
                                                }
                                                return null;
                                            }
                                        }
                                );
                                XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", listener);
                                XposedHelpers.callMethod(category, "addPreference", pref);
                            }
                            XLog.i(">> [手机端UI] 成功全动态注入[CarWith Enhance]优化设置UI");
                        }
                    });

            // 5. 手机端设置页面 onResume 时自动刷新选中状态，防止与车机修改冲突
            XposedHelpers.findAndHookMethod(ucarScreenSettingsFragmentClass, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            android.app.Activity activity = (android.app.Activity) XposedHelpers.callMethod(param.thisObject, "getActivity");
                            if (activity == null) return;

                            SharedPreferences prefs = activity.getSharedPreferences(HookConfigs.PREFS_FILE, Context.MODE_PRIVATE);
                            Object preferenceScreen = XposedHelpers.callMethod(param.thisObject, "getPreferenceScreen");
                            if (preferenceScreen == null) return;

                            // 动态刷新所有配置项的开关展示值
                            for (HookItem item : HookConfigs.getAllItems()) {
                                refreshSwitchPref(preferenceScreen, item.key, prefs.getBoolean(item.key, item.defaultValue));
                            }
                            XLog.i(">> [手机端UI] onResume 动态刷新所有开关状态完成");
                        }
                    });
            }
        } catch (Throwable t) {
            XLog.e("❌ Hook 4 (手机端UI界面动态注入与同步) 失败: ", t);
        }
    }

    /**
     * 通过 PreferenceScreen.findPreference(key) 找到对应 Preference 并刷新状态
     */
    private static void refreshSwitchPref(Object preferenceScreen, String key, boolean checked) {
        try {
            Object pref = XposedHelpers.callMethod(preferenceScreen, "findPreference", key);
            if (pref != null) {
                XposedHelpers.callMethod(pref, "setChecked", checked);
            }
        } catch (Throwable ignored) {}
    }
}
