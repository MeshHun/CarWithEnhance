package hun.mesh.carwithenhance.hook;

import android.content.Context;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import hun.mesh.carwithenhance.config.HookConfigs;
import hun.mesh.carwithenhance.utils.AppUtils;
import hun.mesh.carwithenhance.utils.XLog;

/**
 * Hook: 禁用 CarWith 20 分钟自动亮屏
 */
public class ScreenOnHook implements IHook {

    @Override
    public void onHook(final ClassLoader cl) throws Throwable {
        try {
            Class<?> carlinkStateMachineClass = XposedHelpers.findClass("com.miui.carlink.castfwk.CarlinkStateMachine", cl);
            
            XposedBridge.hookAllMethods(carlinkStateMachineClass, "u1", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    // 读取开关状态
                    Context appCtx = AppUtils.getAppContext(cl);
                    if (appCtx != null && !appCtx.getSharedPreferences(HookConfigs.PREFS_FILE, Context.MODE_PRIVATE)
                            .getBoolean(HookConfigs.SCREEN_ON_DISABLE.key, HookConfigs.SCREEN_ON_DISABLE.defaultValue)) {
                        // 开关没开，执行原逻辑
                        return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                    }

                    XLog.i(">> [唤醒阻止] 拦截到 20 分钟亮屏唤醒 (u1)，已禁用");
                    return null; // 直接返回空，不再调度下一次亮屏
                }
            });
            XLog.i(">> [画质解锁] 禁用20分钟自动亮屏 Hook 注入成功！");
        } catch (Throwable t) {
            XLog.e("❌ 禁用20分钟自动亮屏 Hook 失败: ", t);
        }
    }
}
