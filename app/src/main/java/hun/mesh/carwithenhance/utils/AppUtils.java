package hun.mesh.carwithenhance.utils;

import android.content.Context;
import android.os.Bundle;
import java.lang.reflect.Method;
import de.robv.android.xposed.XposedHelpers;

/**
 * 进程与 Context 相关环境辅助工具
 */
public class AppUtils {

    /**
     * 反射获取 CarWith (com.miui.carlink) 全局 ApplicationContext
     */
    public static Context getAppContext(ClassLoader cl) {
        try {
            //直接通过 Android 底层标准的 ActivityThread 获取全局 Context
            Object currentActivityThread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread");
            if (currentActivityThread != null) {
                return (Context) XposedHelpers.callMethod(currentActivityThread, "getApplication");
            }
        } catch (Throwable t) {
            XLog.e("getAppContext 失败: ", t);
        }
        return null;
    }

    /**
     * 将 Bundle 解析为人类易读的 Key-Value 拼接字符串
     */
    public static String bundleToString(Bundle bundle) {
        if (bundle == null) return "null";
        StringBuilder sb = new StringBuilder("{");
        for (String key : bundle.keySet()) {
            sb.append(key).append("=").append(bundle.get(key)).append(", ");
        }
        if (sb.length() > 1) sb.setLength(sb.length() - 2);
        sb.append("}");
        return sb.toString();
    }

    /**
     * 向 MIUI Settings.System 的 locked_apps 写入白名单（模拟用户在最近任务长按加锁）
     */
    public static void lockAppInRecentTasks(Context context, String pkgName) {
        try {
            android.content.ContentResolver resolver = context.getContentResolver();
            String jsonFormatText = android.provider.Settings.System.getString(resolver, "locked_apps");
            org.json.JSONArray userSpaceArray;
            if (jsonFormatText != null && !jsonFormatText.isEmpty()) {
                userSpaceArray = new org.json.JSONArray(jsonFormatText);
            } else {
                userSpaceArray = new org.json.JSONArray();
            }

            boolean foundSpace = false;
            boolean added = false;
            int userId = android.os.Process.myUserHandle().hashCode(); // 通常为 0
            
            for (int i = 0; i < userSpaceArray.length(); i++) {
                org.json.JSONObject userSpaceObject = userSpaceArray.getJSONObject(i);
                if (userSpaceObject.has("userId") && userSpaceObject.getInt("userId") == userId) {
                    foundSpace = true;
                    org.json.JSONArray packageNames = userSpaceObject.optJSONArray("packageNames");
                    if (packageNames == null) {
                        packageNames = new org.json.JSONArray();
                        userSpaceObject.put("packageNames", packageNames);
                    }
                    
                    // 检查是否已经存在
                    boolean exists = false;
                    for (int j = 0; j < packageNames.length(); j++) {
                        if (pkgName.equals(packageNames.optString(j))) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        packageNames.put(pkgName);
                        added = true;
                    }
                    break;
                }
            }

            if (!foundSpace) {
                org.json.JSONObject newSpace = new org.json.JSONObject();
                newSpace.put("userId", userId);
                org.json.JSONArray packageNames = new org.json.JSONArray();
                packageNames.put(pkgName);
                newSpace.put("packageNames", packageNames);
                userSpaceArray.put(newSpace);
                added = true;
            }

            if (added) {
                try {
                    // 降级：如果不能用 MiuiSettings，且原生 System 会抛 IllegalArgumentException，则尝试写入 Secure
                    android.provider.Settings.Secure.putString(resolver, "locked_apps", userSpaceArray.toString());
                    XLog.i(">> [后台加锁] 成功通过 Settings.Secure 将 " + pkgName + " 写入白名单！");
                } catch (Throwable e) {
                    XLog.e("❌ [后台加锁] 写入白名单彻底失败: ", e);
                }
            }

        } catch (Throwable t) {
            XLog.e("❌ [后台加锁] 解析后台白名单异常: ", t);
        }
    }
    private static Method systemPropertiesGetMethod;
     public static String getSystemProperty(String key, String defaultValue) {
        String result;
        try {
            // 1. 如果还没拿到 get 方法，就先通过反射获取
            if (systemPropertiesGetMethod == null) {
                // o0.c.f1694a 混淆前其实就是常量 "android.os.SystemProperties"
                Class<?> clazz = Class.forName("android.os.SystemProperties");
                systemPropertiesGetMethod = clazz.getMethod("get", String.class);
            }
            
            // 2. 执行 SystemProperties.get(key)
            result = (String) systemPropertiesGetMethod.invoke(null, key);
            
        } catch (Exception e) {
            // 如果反射失败（比如没有权限或发生异常），记录日志并使用默认值
            XLog.e("AppUtils,读取系统属性异常", e);
            result = defaultValue;
        }
        
        // 3. 如果读出来是空的，就返回默认值
        return (result == null || result.length() == 0) ? defaultValue : result;
    }
}
