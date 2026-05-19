package hun.mesh.carwithenhance.utils;

import android.content.Context;
import android.os.Bundle;
import de.robv.android.xposed.XposedHelpers;

/**
 * 进程与 Context 相关环境辅助工具
 */
public class AppUtils {

    /**
     * 安全地反射获取 CarWith (com.miui.carlink) 应用的全局 ApplicationContext
     */
    public static Context getAppContext(ClassLoader cl) {
        try {
            return (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.carwith.common.BaseApplication", cl), "c");
        } catch (Throwable t) {
            XLog.e("getAppContext 失败: ", t);
            return null;
        }
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
}
