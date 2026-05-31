package hun.mesh.carwithenhance.hook;

import android.content.Context;
import java.lang.reflect.Field;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import hun.mesh.carwithenhance.config.HookConfigs;
import hun.mesh.carwithenhance.dexkit.CarWithDexKitManager;
import hun.mesh.carwithenhance.utils.AppUtils;
import hun.mesh.carwithenhance.utils.XLog;

/**
 * Hook 1: QQ音乐完美续播
 */
public class AutoPlayHook implements IHook {

    @Override
    public void onHook(final ClassLoader cl) throws Throwable {
        try {
            String className = CarWithDexKitManager.INSTANCE.getAutoPlayClass();
            String methodName = CarWithDexKitManager.INSTANCE.getAutoPlayMethod();
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
                                XLog.i(">> [续播优化] QQ续播优化关闭，走默认逻辑");
                                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                            }

                            String pkgName = null;

                            // 必须通过反射获取包名，因为此回调是所有应用共用的大动脉！
                            for (Field f : param.thisObject.getClass().getDeclaredFields()) {
                                f.setAccessible(true);
                                if (f.getType() == String.class && pkgName == null) {
                                    pkgName = (String) f.get(param.thisObject);
                                    break;
                                }
                            }

                            if ("com.tencent.qqmusic".equals(pkgName)) {
                                XLog.i(">> [续播优化] 拦截 QQ 音乐列表请求，丢弃指定歌单，保留监听器");
                                return null; // 阻断原方法执行，绝不通知原内部状态机
                            }

                            // 非 QQ 音乐应用，绝不越权，原样放行！
                            XLog.i(pkgName + "！！跳过，放行非 QQ 音乐的合法回调");
                            return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                        }
                    });
            XLog.i(">> [续播优化] 续播 Hook 注入成功！");
        } catch (Throwable t) {
            XLog.e("❌[续播优化] 续播 Hook 注入失败: ", t);
        }
    }
}
