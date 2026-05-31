package hun.mesh.carwithenhance.hook;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import java.util.Arrays;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import hun.mesh.carwithenhance.config.HookConfigs;
import hun.mesh.carwithenhance.dexkit.CarWithDexKitManager;
import hun.mesh.carwithenhance.utils.AppUtils;
import hun.mesh.carwithenhance.utils.XLog;


/**
 * Hook 2: CarLife 视频流 QP 量化参数注入
 */
public class QPHook implements IHook {

    public static final List<String> MTK_SOC = Arrays.asList("MTK6989", "MTK6985", "MTK6985T", "MTK6983", "MTK6983T", "MTK6891", "MTK6833", "MTK6833P", "MTK6896", "MTK6853", "MTK6893", "MTK6855", "MTK6877", "MTK6875", "MTK6886", "MTK6895", "MTK6889", "MTK6991", "MTK6899");
    public static final List<String> QCOM_SOC_NEW = Arrays.asList("SM8550", "SM8475", "SM8650", "SM8450", "SM7550", "SM8635", "SM6450", "SM8750", "SM4450");
    public static final List<String> QCOM_SOC_OLD = Arrays.asList("SM8350AC", "SM7325 pro", "SM8350", "SM7325", "SM7325 pro+", "SDM768", "SM8150", "SM8150_4G", "SM8150_5G","SM7250", "SDM870", "SM8250", "SM6375", "SDM768");

    @Override
    public void onHook(final ClassLoader cl) throws Throwable {
        try {
            String className = CarWithDexKitManager.INSTANCE.getQpClass();
            String methodName = CarWithDexKitManager.INSTANCE.getQpMethod();
            if (className == null || className.isEmpty()) {
                XLog.e("QPHook 动态目标未找到，跳过 Hook");
                return;
            }

            XposedHelpers.findAndHookMethod(className, cl, methodName,
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
                            String socinfo=AppUtils.getSystemProperty("ro.soc.model", "");
                            XLog.i(">> [画质解锁] 读取到SoC 型号: " + socinfo);
                            if(MTK_SOC.contains(socinfo)){
                                if(Build.VERSION.SDK_INT>=31){
                                    bundle.putInt("video-qp-i-min", 10);
                                    bundle.putInt("video-qp-p-min", 10);
                                    bundle.putInt("video-qp-i-max", 32);
                                    bundle.putInt("video-qp-p-max", 32);
                                }
                            }else if (QCOM_SOC_OLD.contains(socinfo)){
                                bundle.putInt("vendor.qti-ext-enc-qp-range.qp-i-min", 10);
                                bundle.putInt("vendor.qti-ext-enc-qp-range.qp-p-min", 10);
                                bundle.putInt("vendor.qti-ext-enc-qp-range.qp-i-max", 24);
                                bundle.putInt("vendor.qti-ext-enc-qp-range.qp-p-max", 24);
                                bundle.putInt("bitrate", 15000000);
                            }else if (QCOM_SOC_NEW.contains(socinfo)){
                                bundle.putInt("video-qp-i-min", 10);
                                bundle.putInt("video-qp-p-min", 10);
                                bundle.putInt("video-qp-i-max", 26);
                                bundle.putInt("video-qp-p-max", 26);
                                bundle.putInt("bitrate", 15000000);
                            }else{
                                bundle.putInt("video-qp-i-min", 16);
                                bundle.putInt("video-qp-p-min", 16);
                                bundle.putInt("video-qp-i-max", 32);
                                bundle.putInt("video-qp-p-max", 32);
                                bundle.putInt("bitrate", 15000000);
                            }                        
                            // 注入高通扩展 QP 参数
                            // bundle.putInt("vendor.qti-ext-enc-qp-range.qp-i-min", 10);
                            // bundle.putInt("vendor.qti-ext-enc-qp-range.qp-p-min", 10);
                            // bundle.putInt("vendor.qti-ext-enc-qp-range.qp-i-max", 28);
                            // bundle.putInt("vendor.qti-ext-enc-qp-range.qp-p-max", 28);

                            XLog.i(">> [画质解锁] 📦 注入后最终 Bundle: " + AppUtils.bundleToString(bundle));
                        }
                    });
            XLog.i(">> QP 画质 Hook 注入成功！");
        } catch (Throwable t) {
            XLog.e("❌ Hook 2 (CarLife QP 注入) 失败: ", t);
        }
    }
}
