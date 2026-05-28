package hun.mesh.carwithenhance.hook;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import hun.mesh.carwithenhance.utils.XLog;

/**
 * 监听 CarWith 互联状态并发送全局系统广播给其他模块（如 QQ音乐）
 */
public class CarLinkStateHook implements IHook {

    private static final String TARGET_CLASS = "com.miui.carlink.castfwk.CastController";
    
    // 我们约定好的跨进程系统广播 ACTION
    public static final String ACTION_CARWITH_CONNECTED = "hun.mesh.carwithenhance.ACTION_CARWITH_CONNECTED";
    public static final String ACTION_CARWITH_DISCONNECTED = "hun.mesh.carwithenhance.ACTION_CARWITH_DISCONNECTED";
    public static final String ACTION_QUERY_CARWITH_STATE = "hun.mesh.carwithenhance.ACTION_QUERY_CARWITH_STATE";

    private Context mContext;
    private static volatile boolean isConnected = false;

    public void initContextAndRegister(Context context) {
        this.mContext = context;
        
        // 仅监听外部（如 QQ 音乐）发来的状态主动查询请求
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_QUERY_CARWITH_STATE);


        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (ACTION_QUERY_CARWITH_STATE.equals(intent.getAction())) {
                    XLog.i("[CarLinkStateHook] 收到外部插件的状态查询请求，立刻回发当前状态: " + isConnected);
                    if (isConnected) {
                        sendStateBroadcast(ACTION_CARWITH_CONNECTED);
                    } else {
                        sendStateBroadcast(ACTION_CARWITH_DISCONNECTED);
                    }
                }
            }
        }, filter, 0x2); // Context.RECEIVER_EXPORTED (0x2) Android 13+
    }

    @Override
    public void onHook(ClassLoader cl) throws Throwable {
        // Context 已经在 MainHook 中被提前注入并注册了广播接收器

        // 3. 拦截最权威的连接成功标志方法
        Class<?> castControllerCls = XposedHelpers.findClassIfExists(TARGET_CLASS, cl);
        if (castControllerCls != null) {
            // fusionOnProjectionConnected() 是所有底层通道汇总后的最终成功回调
            XposedHelpers.findAndHookMethod(castControllerCls, "fusionOnProjectionConnected", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    XLog.i("[CarLinkStateHook] 成功拦截 fusionOnProjectionConnected，CarWith 已成功截取音频/建立投屏");
                    isConnected = true;
                    sendStateBroadcast(ACTION_CARWITH_CONNECTED);
                }
            });

            // 4. 同一个类下的总断开出口（改为拦截真实的实例方法，防止被静态方法绕过）
            XposedHelpers.findAndHookMethod(castControllerCls, "displayDisconnected", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    XLog.i("[CarLinkStateHook] 成功拦截 CastController.displayDisconnected，底层互联彻底断开");
                    isConnected = false;
                    sendStateBroadcast(ACTION_CARWITH_DISCONNECTED);
                }
            });
        } else {
            XLog.e("[CarLinkStateHook] 警告：找不到 CastController 类，可能导致部分连接事件漏判");
        }

        // 备用连接拦截点：亿连底层连接接口
        Class<?> pxcCls = XposedHelpers.findClassIfExists("net.easyconn.carman.utils.SystemNotifyManager", cl);
        if (pxcCls != null) {
            try {
                XposedHelpers.findAndHookMethod(pxcCls, "onProjectionConnected", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XLog.i("[CarLinkStateHook] 成功拦截 SystemNotifyManager.onProjectionConnected，底层互联成功");
                        isConnected = true;
                        sendStateBroadcast(ACTION_CARWITH_CONNECTED);
                    }
                });
            } catch (Throwable t) {
                // ignore
            }
        }
    }

    private void sendStateBroadcast(String action) {
        if (mContext == null) {
            XLog.e("[CarLinkStateHook] 无法发送状态广播，Context 未初始化");
            return;
        }
        Intent intent = new Intent(action);
        // 添加后台接收标志位 (等同于隐藏的 Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND = 0x01000000)
        // 确保跨进程且接收端在后台也能收到
        intent.addFlags(0x01000000);
        mContext.sendBroadcast(intent);
        XLog.i("[CarLinkStateHook] 已发送全局系统广播: " + action);
    }
}
