package hun.mesh.carwithautoplay;

import android.util.Log;

import java.lang.reflect.Field;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "carwithautoplay";
    private static final String TARGET_PACKAGE = "com.miui.carlink";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {

        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            return;
        }

        xlog(">> 成功拦截小米 CarWith, 注入优化逻辑...");
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

        try {
            Class<?> carlifeControllerClass = XposedHelpers.findClass("com.xiaomi.ucar.carlife.c", cl);
            XposedBridge.hookAllMethods(carlifeControllerClass, "j", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    android.content.Intent intent = (android.content.Intent) param.args[0];
                    if (intent == null || !"carlife_encode_format".equals(intent.getAction())) {
                        String act = intent != null ? intent.getAction() : "null";
                        android.os.Bundle resBundle = (android.os.Bundle) param.getResult();
                        xlog( ">> [画质解锁] 过滤非编码通信包, Action: " + act + ", 返回值: " + bundleToString(resBundle));
                        return; // 仅过滤并精准拦截真实的视频流编码格式配置请求
                    }
                    android.os.Bundle bundle = (android.os.Bundle) param.getResult();

                    if (bundle != null) {
                        xlog(">> [画质解锁] 拦截到 CarLife 编码配置请求...");
                        xlog(">> [画质解锁] 📦 注入前原始 Bundle: " + bundleToString(bundle));

                        // 2. 注入针对高通芯片的扩展 QP 键值对 (QP_min = 10, QP_max = 28)
                        bundle.putInt("vendor.qti-ext-enc-qp-range.qp-i-min", 10);
                        bundle.putInt("vendor.qti-ext-enc-qp-range.qp-p-min", 10);
                        bundle.putInt("vendor.qti-ext-enc-qp-range.qp-i-max", 28);
                        bundle.putInt("vendor.qti-ext-enc-qp-range.qp-p-max", 28);

                        // 3. 同时注入标准 MediaFormat 通用 QP 键值对 (确保全机型兼容)
//                        bundle.putInt("video-qp-i-min", 10);
//                        bundle.putInt("video-qp-p-min", 10);
//                        bundle.putInt("video-qp-i-max", 28);
//                        bundle.putInt("video-qp-p-max", 28);

                        xlog(">> [画质解锁] 📦 注入后最终 Bundle: " + bundleToString(bundle));
                    }
                }
            });
        } catch (Throwable t) {
            xlogE ("❌ Hook 3 (CarLife QP 注入) 失败: " ,t);
        }
    }
    // ══════════════════════════════════════════════════════════════════════════
    // 日志系统：同时输出到 Logcat 和 XposedBridge
    // ══════════════════════════════════════════════════════════════════════════
    private static String bundleToString(android.os.Bundle bundle) {
        if (bundle == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        for (String key : bundle.keySet()) {
            sb.append(key).append("=").append(bundle.get(key)).append(", ");
        }
        if (sb.length() > 1) {
            sb.setLength(sb.length() - 2);
        }
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