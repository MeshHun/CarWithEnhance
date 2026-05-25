package hun.mesh.carwithenhance;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import hun.mesh.carwithenhance.hook.AutoPlayHook;
import hun.mesh.carwithenhance.hook.BluetoothHook;
import hun.mesh.carwithenhance.hook.BlurCapabilityHook;
import hun.mesh.carwithenhance.hook.IHook;
import hun.mesh.carwithenhance.hook.QPHook;
import hun.mesh.carwithenhance.hook.SettingsHook;
import hun.mesh.carwithenhance.hook.ScreenOnHook;
import hun.mesh.carwithenhance.utils.XLog;

/**
 * CarWithEnhance 模块入口路由器
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.miui.carlink";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            return;
        }
        XLog.i(">> [CarWith Enhance] 成功拦截小米 CarWith, 开始挂载各优化模块...");
        
        final ClassLoader cl = lpparam.classLoader;

        // 构建 Hook 加载队列
        IHook[] hookQueue = new IHook[]{
                new AutoPlayHook(),
                new QPHook(),
                new SettingsHook(),
                new BluetoothHook(),
                new BlurCapabilityHook(),
                new ScreenOnHook()
        };

        // 统一循环调度与捕获
        for (IHook hook : hookQueue) {
            String hookName = hook.getClass().getSimpleName();
            try {
                XLog.i(">> [路由器] 正在挂载: " + hookName);
                hook.onHook(cl);
            } catch (Throwable t) {
                XLog.e(">> [路由器] 挂载 " + hookName + " 异常失败: ", t);
            }
        }
        XLog.i(">> [CarWith Enhance] 所有优化子模块全部挂载处理完成！");
    }
}