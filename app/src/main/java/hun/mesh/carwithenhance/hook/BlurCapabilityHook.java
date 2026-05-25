package hun.mesh.carwithenhance.hook;

import android.content.Context;
import android.os.Build;

import de.robv.android.xposed.XposedHelpers;
import hun.mesh.carwithenhance.config.HookConfigs;
import hun.mesh.carwithenhance.utils.AppUtils;
import hun.mesh.carwithenhance.utils.XLog;

public class BlurCapabilityHook implements IHook {

    @Override
    public void onHook(final ClassLoader cl) throws Throwable {
        // 1. 读取开关状态
        Context ctx = AppUtils.getAppContext(cl);
        if (ctx != null && !ctx.getSharedPreferences(HookConfigs.PREFS_FILE, Context.MODE_PRIVATE)
                .getBoolean(HookConfigs.ADVANCED_BLUR.key, HookConfigs.ADVANCED_BLUR.defaultValue)) {
            XLog.i(">> 高级毛玻璃强制开启已关闭，跳过 Hook");
            return;
        }

        // 2. 仅支持 Android 13 及以上系统 (SDK_INT >= 33)
        int sdk = Build.VERSION.SDK_INT;
        if (sdk < 33) {
            XLog.i(">> 当前系统版本低于 Android 13，跳过高级毛玻璃注入 Hook");
            return;
        }

        try {
            // 获取目标类 em.d (HyperMaterialUtils)
            final Class<?> hyperMaterialUtilsClass = XposedHelpers.findClass("em.d", cl);

            if (sdk == 33) {
                // ============================================
                // 【Android 13 专有逻辑】：系统支持检测混杂，强行覆写各项静态物理配置
                // ============================================
                XposedHelpers.setStaticObjectField(hyperMaterialUtilsClass, "b", Boolean.TRUE);  // 强制设为支持高级材质/毛玻璃
                XposedHelpers.setStaticIntField(hyperMaterialUtilsClass, "e", 2);               // 强制指定高级材质的版本号为 2
                XposedHelpers.setStaticObjectField(hyperMaterialUtilsClass, "d", Boolean.FALSE); // 强制关闭"渐变模糊"防崩溃
                XLog.i(">> CarWithEnhance: 成功在 Android 13 强行注入高级材质静态变量！");
            } else {
                // ============================================
                // 【Android 14 及以上专有逻辑 (sdk > 33)】：
                // 完美保留系统原生的高级材质软硬件配置，不对静态属性做任何侵入性篡改
                // ============================================
                XLog.i(">> 当前系统为 Android 14+，完美保留原生系统属性参数");
            }

            // 4. 【Android 13 & 14+ 核心 Hook】
            //    直接 Hook d.f(Context) 方法，阻断其读取 Settings.Secure 中的 background_blur_enable (0)，
            //    使用 XC_MethodReplacement 强行使其永远返回 true！
            //    以此彻底解决在手机上不想使用高级材质时，车机内照样强制呈现毛玻璃的要求。
            XposedHelpers.findAndHookMethod(hyperMaterialUtilsClass, "f",
                    android.content.Context.class,
                    new de.robv.android.xposed.XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            return true;
                        }
                    });

            XLog.i(">> CarWithEnhance: 成功注入 em.d.f Hook，全局高级材质开关已被欺骗开启！");

        } catch (Throwable t) {
            XLog.e(">> CarWithEnhance Error hooking em.d: ", t);
        }
    }
}
