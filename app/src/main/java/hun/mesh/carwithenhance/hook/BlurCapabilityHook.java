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
            XLog.i(">> 高级毛玻璃强制开启(Android 13)已关闭，跳过 Hook");
            return;
        }

        // 2. 仅适配 Android 13
        if (Build.VERSION.SDK_INT != 33) {
            XLog.i(">> 当前系统不是 Android 13，跳过高级毛玻璃注入 Hook");
            return;
        }

        try {
            // 获取目标类 em.d (HyperMaterialUtils)
            // 注意：调用 findClass 的瞬间，虚拟机会自动执行该类的 static 代码块 (<clinit>)
            final Class<?> hyperMaterialUtilsClass = XposedHelpers.findClass("em.d", cl);

            // 由于我们处于应用加载的最早期 (handleLoadPackage)，
            // 此时 Carwith 尚未开始真正运行，没有任何代码读取过这些变量。
            // 因此，我们只需在它自身的 <clinit> 运行完毕后，直接暴力覆盖静态变量的值即可！
            
            // 1. 强制设为支持高级材质/毛玻璃 (原为 f19075b -> b)
            XposedHelpers.setStaticObjectField(hyperMaterialUtilsClass, "b", Boolean.TRUE);
            // 2. 强制指定高级材质的版本号为 2 (原为 f19078e -> e)
            XposedHelpers.setStaticIntField(hyperMaterialUtilsClass, "e", 2);
            // 3. 强制关闭"渐变模糊"防崩溃 (原为 f19077d -> d)
            XposedHelpers.setStaticObjectField(hyperMaterialUtilsClass, "d", Boolean.FALSE);

            // 4. 直接 Hook d.f(Context) 方法，让它永远返回 true
            //    不能靠设置 c 字段来绕过，因为 c() 方法会随时将 c 重置为 null，
            //    导致 f() 重新去读 Settings.Secure 的 background_blur_enable，
            //    在 Android 13 上这个值是 0，所以会再次触发"请开启高级材质"提示！
            XposedHelpers.findAndHookMethod(hyperMaterialUtilsClass, "f",
                    android.content.Context.class,
                    new de.robv.android.xposed.XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            return true;
                        }
                    });

            XLog.i(">> CarWithEnhance: 成功在 Android 13 强行注入高级材质静态变量！");

        } catch (Throwable t) {
            XLog.e(">> CarWithEnhance Error hooking em.d: ", t);
        }
    }
}
