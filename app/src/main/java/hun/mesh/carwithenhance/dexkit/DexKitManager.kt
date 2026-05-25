package hun.mesh.carwithenhance.dexkit

import android.content.Context
import de.robv.android.xposed.callbacks.XC_LoadPackage
import hun.mesh.carwithenhance.utils.AppUtils
import hun.mesh.carwithenhance.utils.XLog
import org.luckypray.dexkit.DexKitBridge

object DexKitManager {

    private const val PREFS_DEXKIT = "carwithenhance_dexkit_cache"
    private const val KEY_LAST_VERSION = "last_version_code"

    // 缓存的方法目标
    var autoPlayClass: String = ""
    var autoPlayMethod: String = ""

    var qpClass: String = ""
    var qpMethod: String = ""

    var blurClass: String = ""
    var blurMethod: String = ""

    var settingsClass: String = ""
    var settingsMethod: String = ""

    var bluetoothClass: String = ""
    var bluetoothMethod: String = ""

    fun initAndResolve(lpparam: XC_LoadPackage.LoadPackageParam, ctx: Context) {
        val classLoader = lpparam.classLoader

        try {
            val packageInfo = ctx.packageManager.getPackageInfo(lpparam.packageName, 0)
            val currentVersionCode = packageInfo.longVersionCode

            val prefs = ctx.getSharedPreferences(PREFS_DEXKIT, Context.MODE_PRIVATE)
            val lastVersionCode = prefs.getLong(KEY_LAST_VERSION, -1)

            if (currentVersionCode == lastVersionCode) {
                // 缓存命中，直接读取
                XLog.i("DexKitManager: App版本未变 ($currentVersionCode)，直接使用缓存。")
                loadFromCache(prefs)
                return
            }

            XLog.i("DexKitManager: App版本变化或首次启动 ($lastVersionCode -> $currentVersionCode)，开始全量搜索。")
            System.loadLibrary("dexkit")
            
            DexKitBridge.create(lpparam.appInfo.sourceDir)?.use { bridge ->
                resolveTargets(bridge)
                saveToCache(prefs, currentVersionCode)
                XLog.i("DexKitManager: 全量搜索完成并已更新缓存。")
            } ?: run {
                XLog.e("DexKitManager: DexKitBridge 创建失败！")
            }

        } catch (e: Throwable) {
            XLog.e("DexKitManager 初始化异常: ", e)
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
        autoPlayResults.firstOrNull()?.let {
            autoPlayClass = it.className
            autoPlayMethod = it.name
            XLog.i("DexKit: 找到 AutoPlayHook 目标 -> $autoPlayClass.$autoPlayMethod")
        }

        // 2. QP Hook 目标 (com.xiaomi.ucar.carlife.c.i(boolean, Intent, Bundle))
        val qpResults = bridge.findMethod {
            searchPackages("com.xiaomi.ucar.carlife")
            matcher {
                returnType = "void"
                paramTypes("boolean", "android.content.Intent", "android.os.Bundle")
            }
        }
        qpResults.firstOrNull()?.let {
            qpClass = it.className
            qpMethod = it.name
            XLog.i("DexKit: 找到 QPHook 目标 -> $qpClass.$qpMethod")
        }

        // 3. BlurCapabilityHook 目标 (em.d.f(Context), 读取 background_blur_enable)
        val blurResults = bridge.findMethod {
            matcher {
                returnType = "boolean"
                paramTypes("android.content.Context")
                usingStrings("background_blur_enable")
            }
        }
        blurResults.firstOrNull()?.let {
            blurClass = it.className
            blurMethod = it.name
            XLog.i("DexKit: 找到 BlurCapabilityHook 目标 -> $blurClass.$blurMethod")
        }

        // 4. SettingsHook 目标 (m6.i0.k(Context, String, SharedPreferences))
        val settingsResults = bridge.findMethod {
            matcher {
                returnType = "boolean"
                paramTypes("android.content.Context", "java.lang.String", "android.content.SharedPreferences")
            }
        }
        settingsResults.firstOrNull()?.let {
            settingsClass = it.className
            settingsMethod = it.name
            XLog.i("DexKit: 找到 SettingsHook 目标 -> $settingsClass.$settingsMethod")
        }

        // 5. BluetoothHook 目标 (k9.b.B(String, CarlifeConnectInfo, EasyConnectInfo))
        val bluetoothResults = bridge.findMethod {
            matcher {
                returnType = "void"
                paramCount = 3
                usingStrings("target carSsid: ")
            }
        }
        bluetoothResults.firstOrNull()?.let {
            bluetoothClass = it.className
            bluetoothMethod = it.name
            XLog.i("DexKit: 找到 BluetoothHook 目标 -> $bluetoothClass.$bluetoothMethod")
        } ?: run {
            XLog.e("DexKit: 未能精确找到 BluetoothHook 目标，请检查匹配条件")
        }
    }

    private fun saveToCache(prefs: android.content.SharedPreferences, versionCode: Long) {
        prefs.edit().apply {
            putLong(KEY_LAST_VERSION, versionCode)
            putString("autoPlayClass", autoPlayClass)
            putString("autoPlayMethod", autoPlayMethod)
            putString("qpClass", qpClass)
            putString("qpMethod", qpMethod)
            putString("blurClass", blurClass)
            putString("blurMethod", blurMethod)
            putString("settingsClass", settingsClass)
            putString("settingsMethod", settingsMethod)
            putString("bluetoothClass", bluetoothClass)
            putString("bluetoothMethod", bluetoothMethod)
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
        bluetoothClass = prefs.getString("bluetoothClass", "") ?: ""
        bluetoothMethod = prefs.getString("bluetoothMethod", "") ?: ""
    }
}
