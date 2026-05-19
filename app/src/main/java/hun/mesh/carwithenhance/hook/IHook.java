package hun.mesh.carwithenhance.hook;

/**
 * 统一 Hook 加载模块接口
 */
public interface IHook {
    /**
     * 当 LoadPackage 拦截到目标进程时调用该接口挂载具体的钩子逻辑
     * @param cl 宿主 App 的 ClassLoader
     */
    void onHook(ClassLoader cl) throws Throwable;
}
