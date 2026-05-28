package hun.mesh.carwithenhance.hook;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import hun.mesh.carwithenhance.utils.XLog;

/**
 * QQ 音乐设备路由与音效伪装模块
 */
public class QQMusicRouteHook implements IHook {

    public static final String ACTION_CARWITH_CONNECTED = "hun.mesh.carwithenhance.ACTION_CARWITH_CONNECTED";
    public static final String ACTION_CARWITH_DISCONNECTED = "hun.mesh.carwithenhance.ACTION_CARWITH_DISCONNECTED";

    private static volatile boolean isCarWithConnected = false;

    private static final String AUDIO_ROUTE_MANAGER_CLASS = "com.tencent.qqmusic.business.bluetooth.AudioRouteManager";
    private static final String AUDIO_GEAR_INFO_CLASS = "com.tencent.qqmusic.business.bluetooth.AudioGearInfo";

    private static Context sAppContext;
    private static Object sVirtualCarGearInfo = null;
    private static Object sSpeakerGearInfo = null;

    @Override
    public void onHook(ClassLoader cl) throws Throwable {

        // 1. Hook Application 以注册状态监听广播
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Application app = (Application) param.thisObject;
                if (!"com.tencent.qqmusic".equals(app.getPackageName())) return;
                
                // 仅主进程注册
                if (!app.getApplicationInfo().processName.equals("com.tencent.qqmusic")) return;

                sAppContext = app;
                XLog.i("[QQMusicRouteHook] 正在 QQ 音乐中注册 CarWith 状态监听器...");
                
                IntentFilter filter = new IntentFilter();
                filter.addAction(ACTION_CARWITH_CONNECTED);
                filter.addAction(ACTION_CARWITH_DISCONNECTED);
                
                app.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if (ACTION_CARWITH_CONNECTED.equals(action)) {
                            XLog.i("[QQMusicRouteHook] 收到 CarWith 互联成功广播，准备强制刷新 QQ 音乐设备路由...");
                            isCarWithConnected = true;
                            forceRefreshAudioRoute(cl);
                        } else if (ACTION_CARWITH_DISCONNECTED.equals(action)) {
                            XLog.i("[QQMusicRouteHook] 收到 CarWith 断开互联广播，释放 QQ 音乐设备控制权...");
                            isCarWithConnected = false;
                            forceRefreshAudioRoute(cl); // 断开后同样需要刷新以恢复原状
                        }
                    }
                }, filter, 0x2); // Context.RECEIVER_EXPORTED (0x2) Android 13+

                // 主动发起一次状态查询 (Ping-Pong)
                XLog.i("[QQMusicRouteHook] 启动完成，向 CarWith 发送状态查询请求...");
                Intent queryIntent = new Intent("hun.mesh.carwithenhance.ACTION_QUERY_CARWITH_STATE");
                queryIntent.addFlags(0x01000000); // FLAG_RECEIVER_INCLUDE_BACKGROUND
                app.sendBroadcast(queryIntent);
            }
        });

        // 2. 核心AudioRouteManager 的 Getter 拦截
        Class<?> audioRouteManagerCls = XposedHelpers.findClassIfExists(AUDIO_ROUTE_MANAGER_CLASS, cl);
        if (audioRouteManagerCls != null) {
            
            // 拦截 f() -> int GearType
            XposedHelpers.findAndHookMethod(audioRouteManagerCls, "f", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (isCarWithConnected) {
                        param.setResult(2); // 2 == carAudio
                    }
                }
            });

            // 拦截 k() -> boolean isCarAudio
            XposedHelpers.findAndHookMethod(audioRouteManagerCls, "k", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (isCarWithConnected) {
                        param.setResult(true);
                    }
                }
            });

            // 拦截 e() -> AudioGearInfo
            XposedHelpers.findAndHookMethod(audioRouteManagerCls, "e", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (isCarWithConnected) {
                        param.setResult(getVirtualCarGearInfo(cl));
                    }
                }
            });
            
            // 3. 拦截 BroadcastReceiver onReceive 防止真实蓝牙事件串位
            Class<?> receiverCls = XposedHelpers.findClassIfExists(AUDIO_ROUTE_MANAGER_CLASS + "$b", cl);
            if (receiverCls != null) {
                XposedHelpers.findAndHookMethod(receiverCls, "onReceive", Context.class, Intent.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (isCarWithConnected) {
                            XLog.i("[QQMusicRouteHook] 拦截并屏蔽底层真实系统蓝牙广播，保护车载模式...");
                            param.setResult(null); // 直接阻断
                        }
                    }
                });
            } else {
                XLog.e("[QQMusicRouteHook] 警告：未找到 AudioRouteManager$b (广播接收器)，物理防串位机制可能失效");
            }
            
        } else {
            XLog.e("[QQMusicRouteHook] 找不到核心类 AudioRouteManager，QQ音乐 Hook 彻底失败");
        }
    }

    /**
     * 强行调用 o(...) 派发方法，主动驱动 QQ 音乐内部刷新
     */
    private void forceRefreshAudioRoute(ClassLoader cl) {
        try {
            Class<?> managerCls = XposedHelpers.findClass(AUDIO_ROUTE_MANAGER_CLASS, cl);
            Object instance = XposedHelpers.callStaticMethod(managerCls, "g"); // 获取单例 AudioRouteManager.g()
            if (instance != null) {
                // 防止 QQ音乐 AudioRouteManager 尚未完全初始化导致其内部 Context 为 null 而崩溃
                if (sAppContext != null) {
                    for (java.lang.reflect.Field field : managerCls.getDeclaredFields()) {
                        if (Context.class.isAssignableFrom(field.getType())) {
                            field.setAccessible(true);
                            if (field.get(instance) == null) {
                                field.set(instance, sAppContext);
                                XLog.i("[QQMusicRouteHook] 成功为 AudioRouteManager 强行注入缺失的 Context");
                            }
                            break;
                        }
                    }
                }

                if (isCarWithConnected) {
                    Object virtualGear = getVirtualCarGearInfo(cl);
                    XLog.i("[QQMusicRouteHook] 反射调用 o()，注入虚拟车机...");
                    XposedHelpers.callMethod(instance, "o", 2, virtualGear, new Bundle(), true);
                } else {
                    Object speakerGear = getSpeakerGearInfo(cl);
                    XLog.i("[QQMusicRouteHook] 反射调用 o()，恢复手机内置扬声器状态...");
                    XposedHelpers.callMethod(instance, "o", 4, speakerGear, new Bundle(), true);
                }
            }
        } catch (Throwable t) {
            XLog.e("[QQMusicRouteHook] forceRefreshAudioRoute 调用异常: ", t);
        }
    }

    /**
     * 获取固定的虚拟车载设备信息 (带缓存以解决卡顿)
     */
    private Object getVirtualCarGearInfo(ClassLoader cl) {
        if (sVirtualCarGearInfo == null) {
            try {
                Class<?> gearInfoCls = XposedHelpers.findClass(AUDIO_GEAR_INFO_CLASS, cl);
                Object gearInfo = XposedHelpers.newInstance(gearInfoCls);
                XposedHelpers.setIntField(gearInfo, "b", 2); // gearType = carAudio
                XposedHelpers.setObjectField(gearInfo, "d", Build.BRAND); // brand 使用真实手机品牌以提升 UI 兼容
                XposedHelpers.setObjectField(gearInfo, "e", "CarWith互联"); // model，会在部分 UI 显示
                XposedHelpers.setObjectField(gearInfo, "f", "CW:00:11:22:33:44:55"); // uniqueId
                XposedHelpers.setIntField(gearInfo, "g", 2); // audioRoute = a2dp
                XposedHelpers.setIntField(gearInfo, "h", 2); // realGearType = carAudio
                sVirtualCarGearInfo = gearInfo;
            } catch (Throwable t) {
                XLog.e("[QQMusicRouteHook] getVirtualCarGearInfo 失败", t);
            }
        }
        return sVirtualCarGearInfo;
    }

    private Object getSpeakerGearInfo(ClassLoader cl) {
        if (sSpeakerGearInfo == null) {
            try {
                Class<?> gearInfoCls = XposedHelpers.findClass(AUDIO_GEAR_INFO_CLASS, cl);
                Object gearInfo = XposedHelpers.newInstance(gearInfoCls);
                XposedHelpers.setIntField(gearInfo, "b", 3); // internalSpeaker
                XposedHelpers.setObjectField(gearInfo, "d", Build.BRAND);
                XposedHelpers.setObjectField(gearInfo, "e", Build.MODEL);
                XposedHelpers.setIntField(gearInfo, "g", 4); // audioRoute = speaker
                XposedHelpers.setIntField(gearInfo, "h", 3);
                sSpeakerGearInfo = gearInfo;
            } catch (Throwable t) {
                XLog.e("[QQMusicRouteHook] getSpeakerGearInfo 失败", t);
            }
        }
        return sSpeakerGearInfo;
    }
}
