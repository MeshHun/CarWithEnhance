package hun.mesh.carwithenhance.hook;

import android.content.Context;
import java.lang.reflect.Field;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import hun.mesh.carwithenhance.config.HookConfigs;
import hun.mesh.carwithenhance.utils.AppUtils;
import hun.mesh.carwithenhance.utils.XLog;

/**
 * Hook 1: QQ音乐完美续播
 */
public class AutoPlayHook implements IHook {

    @Override
    public void onHook(final ClassLoader cl) throws Throwable {
        try {
            String className = hun.mesh.carwithenhance.dexkit.DexKitManager.INSTANCE.getAutoPlayClass();
            String methodName = hun.mesh.carwithenhance.dexkit.DexKitManager.INSTANCE.getAutoPlayMethod();
            if (className == null || className.isEmpty()) {
                XLog.e("AutoPlayHook 动态目标未找到，跳过 Hook");
                return;
            }
            
            XposedHelpers.findAndHookMethod(className, cl, methodName,
                    boolean.class, int.class, java.util.List.class, int.class, String.class, android.os.Bundle.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            // 读取开关状态
                            Context ctx = AppUtils.getAppContext(cl);
                            if (ctx != null && !ctx.getSharedPreferences(HookConfigs.PREFS_FILE, Context.MODE_PRIVATE)
                                     .getBoolean(HookConfigs.AUTOPLAY.key, HookConfigs.AUTOPLAY.defaultValue)) {
                                XLog.i("QQ续播优化关闭，走默认逻辑");
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
                                XLog.i(">> 拦截 QQ 音乐列表请求，交由手机端原生续播机制处理");

                                // 通知 CarLink 内部状态：流程已成功结束，防止逻辑卡死
                                if (ja_a_instance != null) {
                                    XposedHelpers.callMethod(ja_a_instance, "p", true, pkgName, isAutoPlay);
                                }
                                return null; // 阻断原方法执行
                            }

                            // 非 QQ 音乐应用，保留原有逻辑
                            XLog.i(pkgName + "！！跳过");
                            return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                        }
                    });
            XLog.i(">> 续播 Hook 注入成功！");
        } catch (Throwable t) {
            XLog.e("❌ 续播 Hook 注入失败: ", t);
        }
    }
}
