package hun.mesh.carwithautoplay;

import android.util.Log;

import java.lang.reflect.Field;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "carwithautoplay";
    private static final String TARGET_PACKAGE = "com.miui.carlink";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {

        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            return;
        }

        xlog(">> 成功拦截小米 CarWith, 注入续播优化逻辑...");
        final ClassLoader cl = lpparam.classLoader;

        // =========================================================================
        // 核心逻辑: 完美续播 (跳过车机下发的强制歌单选择，直接触发手机 App 自动播放)
        // =========================================================================
        try {
            XposedHelpers.findAndHookMethod("ja.a$b", cl, "a",
                    boolean.class, int.class, java.util.List.class, int.class, String.class, android.os.Bundle.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
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
                                xlog( ">> [完美续播] 拦截 QQ 音乐列表请求，交由手机端原生续播机制处理");

                                // 通知 CarLink 内部状态：流程已成功结束，防止逻辑卡死
                                if (ja_a_instance != null) {
                                    XposedHelpers.callMethod(ja_a_instance, "p", true, pkgName, isAutoPlay);
                                }
                                return null; // 返回 null 彻底阻断原方法执行（即不执行原生的“点击指定歌单”逻辑）
                            }

                            // 非 QQ 音乐应用，保留原有逻辑
                            xlog(pkgName+"！！跳过");
                            return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                        }
                    });
        } catch (Throwable t) {
            xlogE("❌ 续播 Hook 注入失败: " , t);
        }
    }
    // ══════════════════════════════════════════════════════════════════════════
    // 日志系统：同时输出到 Logcat 和 XposedBridge
    // ══════════════════════════════════════════════════════════════════════════

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