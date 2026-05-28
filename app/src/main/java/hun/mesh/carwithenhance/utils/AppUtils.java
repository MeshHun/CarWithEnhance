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
