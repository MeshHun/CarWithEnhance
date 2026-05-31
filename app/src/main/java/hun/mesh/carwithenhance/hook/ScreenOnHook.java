package hun.mesh.carwithenhance.hook;

import android.content.Context;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import hun.mesh.carwithenhance.config.HookConfigs;
import hun.mesh.carwithenhance.utils.AppUtils;
import hun.mesh.carwithenhance.utils.XLog;
import hun.mesh.carwithenhance.dexkit.CarWithDexKitManager;

/**
 * Hook: 禁用 CarWith 20 分钟自动亮屏
 */
public class ScreenOnHook implements IHook {

    @Override
    public void onHook(final ClassLoader cl) throws Throwable {
        try {
            String smClassName = CarWithDexKitManager.INSTANCE.getCarlinkStateMachineClass();
            String smMethodName = CarWithDexKitManager.INSTANCE.getScreenOnMethod();
            if (smClassName == null || smClassName.isEmpty() || smMethodName == null || smMethodName.isEmpty()) {
                XLog.e("ScreenOnHook 动态目标未找到，跳过 Hook");
                return;
            }
            Class<?> carlinkStateMachineClass = XposedHelpers.findClass(smClassName, cl);
            
            XposedBridge.hookAllMethods(carlinkStateMachineClass, smMethodName, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    // 读取开关状态
                    Context appCtx = AppUtils.getAppContext(cl);
                    if (appCtx != null && !appCtx.getSharedPreferences(HookConfigs.PREFS_FILE, Context.MODE_PRIVATE)
                            .getBoolean(HookConfigs.CARLIFE_KEEPALIVE.key, HookConfigs.CARLIFE_KEEPALIVE.defaultValue)) {
                        // 开关没开，执行原逻辑
                        return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                    }

                    XLog.i(">> [唤醒阻止] 拦截到 20 分钟亮屏唤醒任务，已禁用");
                    return null; // 直接返回空，不再调度下一次亮屏
                }
            });
            XLog.i(">> [唤醒阻止] 禁用20分钟自动亮屏 Hook 注入成功！");
        } catch (Throwable t) {
            XLog.e("❌ [唤醒阻止] 禁用20分钟自动亮屏 Hook 失败: ", t);
        }
    }
}
