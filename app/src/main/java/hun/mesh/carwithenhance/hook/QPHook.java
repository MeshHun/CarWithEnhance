package hun.mesh.carwithenhance.hook;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import hun.mesh.carwithenhance.config.HookConfigs;
import hun.mesh.carwithenhance.utils.AppUtils;
import hun.mesh.carwithenhance.utils.XLog;

/**
 * Hook 2: CarLife 视频流 QP 量化参数注入
 */
public class QPHook implements IHook {

    @Override
    public void onHook(final ClassLoader cl) throws Throwable {
        try {
            XposedHelpers.findAndHookMethod("com.xiaomi.ucar.carlife.c", cl, "i",
                    boolean.class, Intent.class, Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Bundle bundle = (Bundle) param.args[2];
                            if (bundle == null) return;

                            // 读取开关状态
                            try {
                                Context ctx = AppUtils.getAppContext(cl);
                                if (ctx != null && !ctx.getSharedPreferences(HookConfigs.PREFS_FILE, Context.MODE_PRIVATE)
                                        .getBoolean(HookConfigs.QP.key, HookConfigs.QP.defaultValue)) {
                                    XLog.i(">> [画质解锁] QP 注入开关已关闭，跳过");
                                    return;
                                }
                            } catch (Throwable ignored) {}

                            XLog.i(">> [画质解锁] 拦截到 CarLife 编码配置请求...");
                            XLog.i(">> [画质解锁] 📦 注入前原始 Bundle: " + AppUtils.bundleToString(bundle));

                            // 注入高通扩展 QP 参数
                            bundle.putInt("vendor.qti-ext-enc-qp-range.qp-i-min", 10);
                            bundle.putInt("vendor.qti-ext-enc-qp-range.qp-p-min", 10);
                            bundle.putInt("vendor.qti-ext-enc-qp-range.qp-i-max", 28);
                            bundle.putInt("vendor.qti-ext-enc-qp-range.qp-p-max", 28);

                            XLog.i(">> [画质解锁] 📦 注入后最终 Bundle: " + AppUtils.bundleToString(bundle));
                        }
                    });
            XLog.i(">> QP 画质 Hook 注入成功！");
        } catch (Throwable t) {
            XLog.e("❌ Hook 2 (CarLife QP 注入) 失败: ", t);
        }
    }
}
