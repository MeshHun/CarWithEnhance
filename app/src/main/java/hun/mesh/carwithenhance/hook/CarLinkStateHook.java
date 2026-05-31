package hun.mesh.carwithenhance.hook;

import android.content.Context;
import android.view.Surface;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import hun.mesh.carwithenhance.utils.XLog;

/**
 * 监听 CarWith 互联状态，通过写入 Settings.Global 进行跨进程状态同步
 *
 * 支持三种协议：
 *  - CarLife（百度）: c2.f.i().n() == true
 *  - EasyConnect（亿连）: g0.i() == true
 *  - ICCOA（小米互联）: 上两者均为 false 的 else 分支
 *
 * Hook 点：CastController.displayConnected(...) / displayDisconnected()
 * 这两个方法是三种协议连接/断开的共同出口，覆盖最全面、最权威。
 */
public class CarLinkStateHook implements IHook {

    private static final String KEY_CONNECTED = "carwithenhance_is_connected";

    private final Context mContext;

    public CarLinkStateHook(Context context) {
        this.mContext = context;
    }

    @Override
    public void onHook(ClassLoader cl) throws Throwable {
        String className = hun.mesh.carwithenhance.dexkit.CarWithDexKitManager.INSTANCE.getCastControllerClass();
        if (className == null || className.isEmpty()) {
            XLog.e("[CarLinkStateHook] 警告：未找到 CastController 目标类名");
            return;
        }

        // 连接成功：使用 DexKit 获取的动态方法名进行拦截
        try {
            XposedHelpers.findAndHookMethod(
                    className,
                    cl,
                    hun.mesh.carwithenhance.dexkit.CarWithDexKitManager.INSTANCE.getCastControllerConnectMethod(),
                    Surface.class, int.class, int.class, int.class, int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            XLog.i("[CarLinkStateHook] 拦截 " + param.method.getName() + "()，三协议通用连接成功！");
                            updateGlobalState(1);
                        }
                    });
        } catch (Throwable t) {
            XLog.e("[CarLinkStateHook] Hook 连接方法失败", t);
        }

        // 断开：使用 DexKit 获取的动态方法名进行拦截
        try {
            XposedHelpers.findAndHookMethod(
                    className,
                    cl,
                    hun.mesh.carwithenhance.dexkit.CarWithDexKitManager.INSTANCE.getCastControllerDisconnectMethod(),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            XLog.i("[CarLinkStateHook] 拦截 " + param.method.getName() + "()，三协议通用断开！");
                            updateGlobalState(0);
                        }
                    });
        } catch (Throwable t) {
            XLog.e("[CarLinkStateHook] Hook 断开方法失败", t);
        }
    }

    private void updateGlobalState(int state) {
        if (mContext == null) {
            XLog.e("[CarLinkStateHook] 无法更新全局状态，Context 未初始化");
            return;
        }
        try {
            android.provider.Settings.Global.putInt(mContext.getContentResolver(), KEY_CONNECTED, state);
            XLog.i("[CarLinkStateHook] 已更新 Settings.Global carwithenhance_is_connected = " + state);
        } catch (Exception e) {
            XLog.e("[CarLinkStateHook] 更新 Settings 失败，请检查是否有 WRITE_SECURE_SETTINGS 权限", e);
        }
    }
}
