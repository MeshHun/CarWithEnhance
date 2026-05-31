package hun.mesh.carwithenhance.hook;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import hun.mesh.carwithenhance.dexkit.QQMusicDexKitManager;
import hun.mesh.carwithenhance.utils.XLog;

/**
 * QQ音乐音频路由伪装：利用 DexKit 缓存所有类/方法名，通过 Settings.Global 感知车机互联状态。
 * Hook 顺序：initAndResolve → hookAudioRouteManager → 注册 ContentObserver → 读取初始状态
 */
public class QQMusicRouteHook implements IHook {

    private static final String TAG = "[QQMusicRouteHook]";

    private static volatile boolean isCarWithConnected = false;
    private static Object sVirtualCarGearInfo = null;
    private static Object sSpeakerGearInfo    = null;
    private static Context sAppContext;
    private static Class<?> sManagerCls;
    private static String sMethodUpdateRoute;

    @Override
    public void onHook(ClassLoader cl) throws Throwable {
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Application app = (Application) param.thisObject;
                sAppContext = app;

                XLog.i(TAG + " 启动，初始化 DexKit...");
                QQMusicDexKitManager.INSTANCE.initAndResolve(app);

                hookAudioRouteManager(cl);

                //监听 Settings.Global 变化
                app.getContentResolver().registerContentObserver(
                        android.provider.Settings.Global.getUriFor("carwithenhance_is_connected"),
                        false,
                        new android.database.ContentObserver(
                                new android.os.Handler(android.os.Looper.getMainLooper())) {
                            @Override
                            public void onChange(boolean selfChange) {
                                try {
                                    int val = android.provider.Settings.Global.getInt(
                                            app.getContentResolver(), "carwithenhance_is_connected", 0);
                                    isCarWithConnected = (val == 1);
                                    XLog.i(TAG + " Settings 变化: " + isCarWithConnected);
                                    forceRefreshAudioRoute();
                                } catch (Exception e) {
                                    XLog.e(TAG + " 读取 Settings 失败", e);
                                }
                            }
                        }
                );

                //读取初始状态（模块安装前可能已经连接）
                try {
                    int val = android.provider.Settings.Global.getInt(
                            app.getContentResolver(), "carwithenhance_is_connected", 0);
                    isCarWithConnected = (val == 1);
                    if (isCarWithConnected) {
                        forceRefreshAudioRoute();
                    }
                } catch (Exception e) {
                    XLog.e(TAG + " 初始化读取 Settings 失败", e);
                }
            }
        });
    }

    private void hookAudioRouteManager(ClassLoader cl) {
        // 通过 DexKit 缓存的类名加载，无需 findClassIfExists 二次验证
        String managerClassName  = QQMusicDexKitManager.INSTANCE.getClassAudioRouteManager();
        String gearInfoClassName = QQMusicDexKitManager.INSTANCE.getClassAudioGearInfo();
        String receiverClassName = QQMusicDexKitManager.INSTANCE.getClassBluetoothReceiver();

        sMethodUpdateRoute = QQMusicDexKitManager.INSTANCE.getMethodUpdateRoute();
        String mGetGearType = QQMusicDexKitManager.INSTANCE.getMethodGetGearType();
        String mIsCarAudio  = QQMusicDexKitManager.INSTANCE.getMethodIsCarAudio();
        String mGetGearInfo = QQMusicDexKitManager.INSTANCE.getMethodGetGearInfo();

        try {
            sManagerCls = cl.loadClass(managerClassName);
        } catch (ClassNotFoundException e) {
            XLog.e(TAG + " 无法加载 AudioRouteManager 类: " + managerClassName, e);
            return;
        }

        Class<?> gearInfoCls = null;
        try {
            gearInfoCls = cl.loadClass(gearInfoClassName);
            buildGearInfos(gearInfoCls);
        } catch (ClassNotFoundException e) {
            XLog.e(TAG + " 无法加载 AudioGearInfo 类: " + gearInfoClassName, e);
        }

        // Hook 1: 路由总调度 o(int, AudioGearInfo, Bundle, boolean)
        if (!sMethodUpdateRoute.isEmpty() && gearInfoCls != null) {
            try {
                final Class<?> finalGearInfoCls = gearInfoCls;
                XposedHelpers.findAndHookMethod(sManagerCls, sMethodUpdateRoute,
                        int.class, finalGearInfoCls, Bundle.class, boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (isCarWithConnected && sVirtualCarGearInfo != null) {
                                    param.args[0] = 2; // routeType = carAudio
                                    param.args[1] = sVirtualCarGearInfo;
                                    XLog.i(TAG + " --> 拦截 " + sMethodUpdateRoute + "()，注入虚拟车载参数");
                                }
                            }
                        });
            } catch (Throwable t) {
                XLog.e(TAG + " Hook 路由分发口失败", t);
            }
        }

        // Hook 2: Getters 欺骗
        if (!mGetGearType.isEmpty()) {
            XposedHelpers.findAndHookMethod(sManagerCls, mGetGearType, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (isCarWithConnected) param.setResult(2);
                }
            });
        }
        if (!mIsCarAudio.isEmpty()) {
            XposedHelpers.findAndHookMethod(sManagerCls, mIsCarAudio, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (isCarWithConnected) param.setResult(true);
                }
            });
        }
        if (!mGetGearInfo.isEmpty()) {
            XposedHelpers.findAndHookMethod(sManagerCls, mGetGearInfo, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (isCarWithConnected && sVirtualCarGearInfo != null) {
                        param.setResult(sVirtualCarGearInfo);
                    }
                }
            });
        }

        // Hook 3: 屏蔽真实蓝牙广播（防止在车载模式中被真实设备事件打断）
        if (!receiverClassName.isEmpty()) {
            try {
                Class<?> receiverCls = cl.loadClass(receiverClassName);
                XposedHelpers.findAndHookMethod(receiverCls, "onReceive",
                        Context.class, Intent.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (isCarWithConnected) {
                                    XLog.i(TAG + " --> 屏蔽真实蓝牙广播，保护车载模式");
                                    param.setResult(null);
                                }
                            }
                        });
            } catch (ClassNotFoundException e) {
                XLog.e(TAG + " 无法加载蓝牙广播接收器: " + receiverClassName, e);
            }
        }
    }

    private void buildGearInfos(Class<?> gearInfoCls) {
        if (sVirtualCarGearInfo != null) return;
        try {
            hun.mesh.carwithenhance.dexkit.QQMusicDexKitManager kit = hun.mesh.carwithenhance.dexkit.QQMusicDexKitManager.INSTANCE;

            Object carGear = XposedHelpers.newInstance(gearInfoCls);
            XposedHelpers.setIntField(carGear, kit.getFieldGearType(), 2); // gearType = carAudio
            XposedHelpers.setObjectField(carGear, kit.getFieldBrand(), android.os.Build.BRAND);
            XposedHelpers.setObjectField(carGear, kit.getFieldModel(), "CarWith互联");
            XposedHelpers.setObjectField(carGear, kit.getFieldMac(), "CW:00:11:22:33:44:55");
            XposedHelpers.setIntField(carGear, kit.getFieldIntG(), 2);
            XposedHelpers.setIntField(carGear, kit.getFieldIntH(), 2);
            sVirtualCarGearInfo = carGear;

            Object speakerGear = XposedHelpers.newInstance(gearInfoCls);
            XposedHelpers.setIntField(speakerGear, kit.getFieldGearType(), 3); // internalSpeaker
            XposedHelpers.setObjectField(speakerGear, kit.getFieldBrand(), android.os.Build.BRAND);
            XposedHelpers.setObjectField(speakerGear, kit.getFieldModel(), android.os.Build.MODEL);
            XposedHelpers.setIntField(speakerGear, kit.getFieldIntG(), 4);
            XposedHelpers.setIntField(speakerGear, kit.getFieldIntH(), 3);
            sSpeakerGearInfo = speakerGear;

            XLog.i(TAG + " GearInfo 缓存构建完成");
        } catch (Throwable t) {
            XLog.e(TAG + " 构造 GearInfo 失败", t);
        }
    }

    private void forceRefreshAudioRoute() {
        if (sManagerCls == null || sMethodUpdateRoute == null || sMethodUpdateRoute.isEmpty()) return;
        try {
            String mGetInstance = QQMusicDexKitManager.INSTANCE.getMethodGetInstance();
            if (mGetInstance.isEmpty()) return;

            Object instance = XposedHelpers.callStaticMethod(sManagerCls, mGetInstance);
            if (instance == null) return;

            // 注入 Context 防内部 NPE
            if (sAppContext != null) {
                for (java.lang.reflect.Field field : sManagerCls.getDeclaredFields()) {
                    if (Context.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        if (field.get(instance) == null) field.set(instance, sAppContext);
                        break;
                    }
                }
            }

            if (isCarWithConnected && sVirtualCarGearInfo != null) {
                XLog.i(TAG + " 主动驱动刷新 -> 虚拟车载");
                XposedHelpers.callMethod(instance, sMethodUpdateRoute, 2, sVirtualCarGearInfo, new Bundle(), true);
            } else if (!isCarWithConnected && sSpeakerGearInfo != null) {
                XLog.i(TAG + " 主动驱动刷新 -> 恢复外放");
                XposedHelpers.callMethod(instance, sMethodUpdateRoute, 4, sSpeakerGearInfo, new Bundle(), true);
            }
        } catch (Throwable t) {
            XLog.e(TAG + " forceRefreshAudioRoute 异常", t);
        }
    }
}
