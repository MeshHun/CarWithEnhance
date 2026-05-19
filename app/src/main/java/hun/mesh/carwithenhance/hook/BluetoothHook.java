package hun.mesh.carwithenhance.hook;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import hun.mesh.carwithenhance.config.HookConfigs;
import hun.mesh.carwithenhance.utils.AppUtils;
import hun.mesh.carwithenhance.utils.BluetoothUtils;
import hun.mesh.carwithenhance.utils.XLog;

/**
 * Hook 5: 连接车机热点后自动断开蓝牙音频 (A2DP / HFP / 通话)
 */
public class BluetoothHook implements IHook {

    @Override
    public void onHook(final ClassLoader cl) throws Throwable {
        try {
            Class<?> autoConnectManagerClass = XposedHelpers.findClass("k9.b", cl);
            XposedBridge.hookAllMethods(autoConnectManagerClass, "B", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // 读取开关状态
                    Context appCtx = AppUtils.getAppContext(cl);
                    if (appCtx != null && !appCtx.getSharedPreferences(HookConfigs.PREFS_FILE, Context.MODE_PRIVATE)
                            .getBoolean(HookConfigs.DISCONNECT_BT.key, HookConfigs.DISCONNECT_BT.defaultValue)) {
                        XLog.i(">> [自动断开蓝牙] 功能已关闭，跳过执行");
                        return;
                    }

                    XLog.i("====== 拦截到连接 CarAP 热点请求，执行断开蓝牙音频控制 ======");
                    Object clInfo = param.args[1];
                    Object ecInfo = param.args[2];
                    String carMac = null;
                    if (clInfo != null) {
                        carMac = (String) XposedHelpers.callMethod(clInfo, "getCarBtMacAddress");
                    } else if (ecInfo != null) {
                        carMac = (String) XposedHelpers.callMethod(ecInfo, "getCarBtMacAddress");
                    }

                    final Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "b");
                    if (context != null && carMac != null) {
                        final String finalCarMac = carMac;
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                BluetoothUtils.disconnectBluetoothDevice(context, finalCarMac);
                            }
                        }, 1500);
                    }
                }
            });
            XLog.i(">> 自动断开蓝牙 Hook 注入成功！");
        } catch (Throwable t) {
            XLog.e("❌ Hook 5 (无线连接后断开蓝牙音频) 失败: ", t);
        }
    }
}
