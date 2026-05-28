package hun.mesh.carwithenhance.hook;

import android.app.AndroidAppHelper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import hun.mesh.carwithenhance.utils.XLog;

/**
 * Android 13 (及以下) 的 MIUI / HyperOS 系统级免杀金牌 Hook。
 * 作用于 android (system_server) 核心进程。
 */
public class SystemServerHook {

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        // 由于 CarWith 源码中亮屏保活任务只在 Android 13 及以下执行，故防杀逻辑也保持同频
        if (android.os.Build.VERSION.SDK_INT <= 33) {
            try {
                // 跨进程读取宿主 (com.miui.carlink) 的配置
                boolean isScreenOnDisabled = true; // 默认为 true
                try {
                    java.io.File prefFile = new java.io.File(android.os.Environment.getDataDirectory(), 
                            "user_de/0/com.miui.carlink/shared_prefs/" + hun.mesh.carwithenhance.config.HookConfigs.PREFS_FILE + ".xml");
                    if (!prefFile.exists()) {
                        prefFile = new java.io.File(android.os.Environment.getDataDirectory(), 
                                "data/com.miui.carlink/shared_prefs/" + hun.mesh.carwithenhance.config.HookConfigs.PREFS_FILE + ".xml");
                    }
                    if (prefFile.exists()) {
                        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(prefFile));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        reader.close();
                        String content = sb.toString();
                        if (content.contains("name=\"" + hun.mesh.carwithenhance.config.HookConfigs.CARLIFE_KEEPALIVE.key + "\" value=\"false\"")) {
                            isScreenOnDisabled = false;
                        }
                    }
                } catch (Throwable t) {
                    XLog.e(">> [进程保护] 直接读取配置文件失败，默认继续执行 Hook", t);
                }

                if (!isScreenOnDisabled) {
                    XLog.i(">> [进程保护] 禁用自动亮屏未开启，自动跳过免杀 Hook");
                    return;
                }

                XLog.i(">> [进程保护] 成功拦截系统底层 (system_server)，准备注入carlife进程保护");

                // Hook ProcessCleanerBase 里的 isAudioOrGPSApp 方法
                XposedHelpers.findAndHookMethod(
                        "com.android.server.am.ProcessCleanerBase",
                        lpparam.classLoader,
                        "isAudioOrGPSApp",
                        int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                int uid = (int) param.args[0];

                                // 获取当前正在被判定生死的 uid 对应的所有包名
                                android.app.Application app = AndroidAppHelper.currentApplication();
                                if (app == null) return;

                                String[] pkgs = app.getPackageManager().getPackagesForUid(uid);
                                if (pkgs != null) {
                                    for (String pkg : pkgs) {
                                        if ("com.baidu.carlife.xiaomi".equals(pkg)) {

                                            XLog.i(">> [进程保护] 拦截到carlife组件判定");
                                            XLog.i(">> [进程保护] 强行将 " + pkg + " 伪装为音频/导航应用！");

                                            // 核心魔法：强行把返回值篡改为 true！
                                            // 系统会瞬间认为它在听歌或导航，直接跳过耗电查杀逻辑！
                                            param.setResult(true);
                                            return;
                                        }
                                    }
                                }
                            }
                        }
                );
                XLog.i(">> [进程保护] Hook isAudioOrGPSApp (音频/导航白名单) 注册成功！");
            } catch (Throwable e) {
                XLog.e("❌ [进程保护] Hook isAudioOrGPSApp 失败", e);
            }
        }

    }
}
