package hun.mesh.carwithenhance.utils;

import android.util.Log;
import de.robv.android.xposed.XposedBridge;

/**
 * 混合日志输出工具类
 */
public class XLog {
    private static final String TAG = "carwithenhance";

    public static void i(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }

    public static void e(String msg) {
        Log.e(TAG, msg);
        XposedBridge.log(TAG + " ERROR: " + msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        XposedBridge.log(TAG + " ERROR: " + msg + (t != null ? " (" + t.getMessage() + ")" : ""));
        if (t != null) {
            XposedBridge.log(t);
        }
    }
}
