package hun.mesh.carwithenhance.dexkit

import android.content.Context
import de.robv.android.xposed.callbacks.XC_LoadPackage
import hun.mesh.carwithenhance.utils.XLog
import org.luckypray.dexkit.DexKitBridge

object CarWithDexKitManager {

    private const val PREFS_DEXKIT = "carwithenhance_dexkit_cache"

    // 缓存的方法目标
    var autoPlayClass: String = ""
    var autoPlayMethod: String = ""

    var qpClass: String = ""
    var qpMethod: String = ""

    var blurClass: String = ""
    var blurMethod: String = ""

    var settingsClass: String = ""
    var settingsMethod: String = ""

    var settingsAdapterClass: String = ""
    var settingsAdapterMethods: String = ""

    var ucarSettingsFragmentClass: String = ""

    var carlinkStateMachineClass: String = ""
    var screenOnMethod: String = ""

    var bluetoothClass: String = ""
    var bluetoothMethod: String = ""

    var castControllerClass: String = ""
    var castControllerConnectMethod: String = ""
    var castControllerDisconnectMethod: String = ""

    fun initAndResolve(lpparam: XC_LoadPackage.LoadPackageParam, ctx: Context) {

        try {
            val packageInfo = ctx.packageManager.getPackageInfo(lpparam.packageName, 0)
            val currentAppVersionName = packageInfo.versionName
            // 获取模块每次编译时产生的唯一时间戳
            val currentModuleBuildTime = hun.mesh.carwithenhance.BuildConfig.BUILD_TIME

            val prefs = ctx.getSharedPreferences(PREFS_DEXKIT, Context.MODE_PRIVATE)
            val lastAppVersionName = prefs.getString("KEY_LAST_APP_VERSION_NAME", "")
            val lastModuleBuildTime = prefs.getLong("KEY_LAST_MODULE_BUILD_TIME", -1L)

            if (currentAppVersionName == lastAppVersionName && currentModuleBuildTime == lastModuleBuildTime) {
                // 缓存命中，直接读取
                XLog.i("[CarWithDexKit]: 宿主版本未变，且模块未重新编译，直接使用缓存。")
                loadFromCache(prefs)
                return
            }

            XLog.i("[CarWithDexKit]: 宿主升级或模块重新编译，开始全量搜索。")
            System.loadLibrary("dexkit")

            DexKitBridge.create(lpparam.appInfo.sourceDir).use { bridge ->
                resolveTargets(bridge)
                saveToCache(prefs, currentAppVersionName)
                XLog.i("[CarWithDexKit]: 全量搜索完成并已更新缓存。")
            }

        } catch (e: Throwable) {
            XLog.e("[CarWithDexKit]: 初始化异常: ", e)
        }
    }

    private fun resolveTargets(bridge: DexKitBridge) {
        // 1. AutoPlay Hook 目标 (ja.a$b.a(boolean, int, List, int, String, Bundle))
        val autoPlayResults = bridge.findMethod {
            matcher {
                returnType = "void"
                paramTypes("boolean", "int", "java.util.List", "int", "java.lang.String", "android.os.Bundle")
                usingStrings("MediaAutoPlayHelper")
            }
        }
        autoPlayResults.singleOrNull()?.let {
            autoPlayClass = it.className
            autoPlayMethod = it.name
            XLog.i("[CarWithDexKit]: 找到 AutoPlayHook 目标 -> $autoPlayClass.$autoPlayMethod")
        } ?: run{
            XLog.e("[CarWithDexKit]: 未找到或匹配到多个 AutoPlayHook 目标，请检查匹配条件")
        }

        // 2. QP Hook 目标 (com.xiaomi.ucar.carlife.c.i(boolean, Intent, Bundle))
        val qpResults = bridge.findMethod {
            searchPackages("com.xiaomi.ucar.carlife")
            matcher {
                returnType = "void"
                paramTypes("boolean", "android.content.Intent", "android.os.Bundle")
                usingStrings("params", "default_width") // 增加特征，提升唯一性
            }
        }
        qpResults.singleOrNull()?.let {
            qpClass = it.className
            qpMethod = it.name
            XLog.i("[CarWithDexKit]: 找到 QPHook 目标 -> $qpClass.$qpMethod")
        } ?: run {
            XLog.e("[CarWithDexKit]: 未找到或匹配到多个 QPHook 目标，请检查匹配条件")
        }

        // 3. BlurCapabilityHook 目标 (em.d.f(Context), 读取 background_blur_enable)
        val blurResults = bridge.findMethod {
            matcher {
                returnType = "boolean"
                paramTypes("android.content.Context")
                usingStrings("background_blur_enable")
            }
        }
        blurResults.singleOrNull()?.let {
            blurClass = it.className
            blurMethod = it.name
            XLog.i("[CarWithDexKit]: 找到 BlurCapabilityHook 目标 -> $blurClass.$blurMethod")
        } ?: run {
            XLog.e("[CarWithDexKit]: 未找到或匹配到多个 BlurCapabilityHook 目标，请检查匹配条件")
        }

        // 4. SettingsHook 目标 (m6.i0.k(Context, String, SharedPreferences))
        val settingsResults = bridge.findMethod {
            matcher {
                returnType = "boolean"
                paramTypes("android.content.Context", "java.lang.String", "android.content.SharedPreferences")
            }
        }
        settingsResults.singleOrNull()?.let {
            settingsClass = it.className
            settingsMethod = it.name
            XLog.i("[CarWithDexKit]: 找到 SettingsHook 目标 -> $settingsClass.$settingsMethod")
        } ?: run {
            XLog.e("[CarWithDexKit]: 未找到或匹配到多个 SettingsHook 目标，请检查匹配条件")
        }

        // 3. SettingsHook 动态目标 (SettingsIndexAdapter 及其对应的绑定方法 s0 / x0)
        val settingsAdapterClassResults = bridge.findClass {
            searchPackages("com.carwith.launcher.settings")
            matcher {
                usingStrings("prefer_dock_style")
            }
        }
        // 使用 singleOrNull(predicate) 筛选不包含 $ 的主类，并确保唯一性
        settingsAdapterClassResults.singleOrNull { !it.name.substringAfterLast(".").contains("$") }?.let { clazz ->
            settingsAdapterClass = clazz.name
            XLog.i("[CarWithDexKit]: 找到 SettingsAdapter 类 -> $settingsAdapterClass")
            
            // 动态查找 SettingsAdapter 中的 Radio 绑定方法 (s0)
            var radioMethodName = ""
            val settingsAdapterRadioMethodResults = bridge.findMethod {
                matcher {
                    declaredClass = settingsAdapterClass
                    usingStrings("prefer_dock_style")
                }
            }
            settingsAdapterRadioMethodResults.singleOrNull()?.let { method ->
                radioMethodName = method.name
            }

            // 动态查找 SettingsAdapter 中的 Switch 绑定方法 (x0)
            var switchMethodName = ""
            val settingsAdapterSwitchMethodResults = bridge.findMethod {
                matcher {
                    declaredClass = settingsAdapterClass
                    // x0 中调用了 CompoundButton.setOnCheckedChangeListener
                    addInvoke("Landroid/widget/CompoundButton;->setOnCheckedChangeListener(Landroid/widget/CompoundButton\$OnCheckedChangeListener;)V")
                }
            }
            settingsAdapterSwitchMethodResults.singleOrNull()?.let { method ->
                switchMethodName = method.name
            }

            val methodsList = listOf(radioMethodName, switchMethodName).filter { it.isNotEmpty() }
            settingsAdapterMethods = methodsList.joinToString(",")
            XLog.i("[CarWithDexKit]: 找到 SettingsAdapter 目标方法 -> $settingsAdapterMethods")

        } ?: run {
            XLog.e("[CarWithDexKit]: 未找到或匹配到多个 SettingsAdapter 类目标，请检查匹配条件")
        }

        // 6. UCarScreenSettingsFragment 目标
        val ucarFragmentResults = bridge.findMethod {
            matcher {
                name = "onCreatePreferences"
                paramTypes("android.os.Bundle", "java.lang.String")
                usingStrings("UCarScreenSettingsFragment")
            }
        }
        // 由于增加了 usingStrings 约束，能安全地匹配到该 Fragment 内部的方法
        ucarFragmentResults.singleOrNull()?.let {
            ucarSettingsFragmentClass = it.className
            XLog.i("[CarWithDexKit]: 找到 UCarScreenSettingsFragment 目标 -> $ucarSettingsFragmentClass")
        } ?: run {
            XLog.e("[CarWithDexKit]: 未找到或匹配到多个 UCarScreenSettingsFragment 目标，请检查匹配条件")
        }

        // 7. ScreenOnHook 目标
        val screenOnResults = bridge.findMethod {
            matcher {
                usingStrings("start ScreenOnTask")
                returnType = "void"
            }
        }
        screenOnResults.singleOrNull()?.let {
            carlinkStateMachineClass = it.className
            screenOnMethod = it.name
            XLog.i("[CarWithDexKit]: 找到 ScreenOnHook 目标 -> $carlinkStateMachineClass.$screenOnMethod")
        } ?: run {
            XLog.e("[CarWithDexKit]: 未找到或匹配到多个 ScreenOnHook 目标，请检查匹配条件")
        }

        // 8. BluetoothHook 目标 (k9.b.B(String, CarlifeConnectInfo, EasyConnectInfo))
        val bluetoothResults = bridge.findMethod {
            matcher {
                returnType = "void"
                paramCount = 3
                usingStrings("target carSsid: ")
            }
        }
        bluetoothResults.singleOrNull()?.let {
            bluetoothClass = it.className
            bluetoothMethod = it.name
            XLog.i("[CarWithDexKit]: 找到 BluetoothHook 目标 -> $bluetoothClass.$bluetoothMethod")
        } ?: run {
            XLog.e("[CarWithDexKit]: 未找到或匹配到多个 BluetoothHook 目标，请检查匹配条件")
        }

        // 9. CastController 目标 (匹配 1 个 Surface 和 6 个 int 的连接方法)
        val castControllerResults = bridge.findMethod {
            searchPackages("com.miui.carlink")
            matcher {
                paramTypes("android.view.Surface", "int", "int", "int", "int", "int", "int")
                usingStrings("displayConnected:")
            }
        }
        castControllerResults.singleOrNull()?.let {
            castControllerClass = it.className
            castControllerConnectMethod = it.name
            XLog.i("[CarWithDexKit]: 找到 CastController 连接目标 -> $castControllerClass.$castControllerConnectMethod")
            
            // 同一个类下，寻找断开方法 displayDisconnected (无参方法且无返回值)
            val disconnectResults = bridge.findMethod {
                matcher {
                    declaredClass = castControllerClass
                    returnType = "void"
                    paramCount = 0
                    usingStrings("displayDisconnected start: ")
                }
            }
            castControllerDisconnectMethod = disconnectResults.singleOrNull()?.name ?: "displayDisconnected"
            
        } ?: run {
            XLog.e("[CarWithDexKit]: 未找到 CastController 目标")
        }
    }

    private fun saveToCache(prefs: android.content.SharedPreferences, appVersionName: String?) {
        prefs.edit().apply {
            putString("KEY_LAST_APP_VERSION_NAME", appVersionName ?: "")
            putLong("KEY_LAST_MODULE_BUILD_TIME", hun.mesh.carwithenhance.BuildConfig.BUILD_TIME)
            putString("autoPlayClass", autoPlayClass)
            putString("autoPlayMethod", autoPlayMethod)

            putString("qpClass", qpClass)
            putString("qpMethod", qpMethod)
            putString("blurClass", blurClass)
            putString("blurMethod", blurMethod)
            putString("settingsClass", settingsClass)
            putString("settingsMethod", settingsMethod)
            putString("settingsAdapterClass", settingsAdapterClass)
            putString("settingsAdapterMethods", settingsAdapterMethods)
            putString("ucarSettingsFragmentClass", ucarSettingsFragmentClass)
            putString("carlinkStateMachineClass", carlinkStateMachineClass)
            putString("screenOnMethod", screenOnMethod)
            putString("bluetoothClass", bluetoothClass)
            putString("bluetoothMethod", bluetoothMethod)
            putString("castControllerClass", castControllerClass)
            putString("castControllerConnectMethod", castControllerConnectMethod)
            putString("castControllerDisconnectMethod", castControllerDisconnectMethod)
        }.apply()
    }

    private fun loadFromCache(prefs: android.content.SharedPreferences) {
        autoPlayClass = prefs.getString("autoPlayClass", "") ?: ""
        autoPlayMethod = prefs.getString("autoPlayMethod", "") ?: ""
        qpClass = prefs.getString("qpClass", "") ?: ""
        qpMethod = prefs.getString("qpMethod", "") ?: ""
        blurClass = prefs.getString("blurClass", "") ?: ""
        blurMethod = prefs.getString("blurMethod", "") ?: ""
        settingsClass = prefs.getString("settingsClass", "") ?: ""
        settingsMethod = prefs.getString("settingsMethod", "") ?: ""
        settingsAdapterClass = prefs.getString("settingsAdapterClass", "") ?: ""
        settingsAdapterMethods = prefs.getString("settingsAdapterMethods", "") ?: ""
        ucarSettingsFragmentClass = prefs.getString("ucarSettingsFragmentClass", "") ?: ""
        carlinkStateMachineClass = prefs.getString("carlinkStateMachineClass", "") ?: ""
        screenOnMethod = prefs.getString("screenOnMethod", "") ?: ""
        bluetoothClass = prefs.getString("bluetoothClass", "") ?: ""
        bluetoothMethod = prefs.getString("bluetoothMethod", "") ?: ""
        castControllerClass = prefs.getString("castControllerClass", "") ?: ""
        castControllerConnectMethod = prefs.getString("castControllerConnectMethod", "") ?: ""
        castControllerDisconnectMethod = prefs.getString("castControllerDisconnectMethod", "") ?: ""
    }
}
