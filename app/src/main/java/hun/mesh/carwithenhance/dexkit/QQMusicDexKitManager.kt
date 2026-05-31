package hun.mesh.carwithenhance.dexkit

import android.content.Context
import org.luckypray.dexkit.DexKitBridge
import hun.mesh.carwithenhance.utils.XLog
import java.lang.reflect.Modifier

object QQMusicDexKitManager {
    private const val PREFS_DEXKIT = "carwithenhance_qqmusic_dexkit_cache"

    // 类名（全限定）
    var classAudioRouteManager: String = "com.tencent.qqmusic.business.bluetooth.AudioRouteManager"
    var classAudioGearInfo: String     = "com.tencent.qqmusic.business.bluetooth.AudioGearInfo"
    var classBluetoothReceiver: String = "" // AudioRouteManager$b，从 DexKit 动态确认

    // 方法名（混淆后的短名）
    var methodGetInstance: String    = ""
    var methodUpdateRoute: String    = ""
    var methodGetGearType: String    = ""
    var methodIsCarAudio: String     = ""
    var methodGetGearInfo: String    = ""

    // 字段名（AudioGearInfo 混淆后的字段）
    var fieldGearType: String = ""
    var fieldBrand: String    = ""
    var fieldModel: String    = ""
    var fieldMac: String      = ""
    var fieldIntG: String     = ""
    var fieldIntH: String     = ""

    fun initAndResolve(ctx: Context) {
        try {
            val apkPath = ctx.applicationInfo.sourceDir
            val packageInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val currentAppVersionCode = packageInfo.longVersionCode
            val prefs = ctx.getSharedPreferences(PREFS_DEXKIT, Context.MODE_PRIVATE)
            val currentModuleBuildTime = hun.mesh.carwithenhance.BuildConfig.BUILD_TIME

            val lastAppVersionCode  = prefs.getLong("KEY_LAST_VERSION_CODE", -1)
            val lastModuleBuildTime = prefs.getLong("KEY_LAST_MODULE_BUILD_TIME", -1L)

            if (currentAppVersionCode == lastAppVersionCode && currentModuleBuildTime == lastModuleBuildTime) {
                XLog.i("QQMusicDexKit: QQ音乐版本未变，使用缓存。")
                loadFromCache(prefs)
                return
            }

            XLog.i("QQMusicDexKit: QQ音乐版本升级，开始全量搜索核心方法...")
            System.loadLibrary("dexkit")

            DexKitBridge.create(apkPath).use { bridge ->
                val targetClass  = classAudioRouteManager
                val gearInfoClass = classAudioGearInfo

                // 1. g() 获取单例 (static)
                bridge.findMethod {
                    matcher {
                        declaredClass = targetClass
                        returnType = targetClass
                        paramCount = 0
                    }
                }.find { Modifier.isStatic(it.modifiers) }?.let { methodGetInstance = it.name }

                // 2. o(...) 强制更新路由
                bridge.findMethod {
                    matcher {
                        declaredClass = targetClass
                        paramTypes("int", gearInfoClass, "android.os.Bundle", "boolean")
                    }
                }.firstOrNull()?.let { methodUpdateRoute = it.name }

                // 3. f() 获取 GearType (int, 无参, 非静态)
                bridge.findMethod {
                    matcher {
                        declaredClass = targetClass
                        returnType = "int"
                        paramCount = 0
                    }
                }.find { !Modifier.isStatic(it.modifiers) }?.let { methodGetGearType = it.name }

                // 4. k() 是否车载 (boolean, 无参, 非静态)
                bridge.findMethod {
                    matcher {
                        declaredClass = targetClass
                        returnType = "boolean"
                        paramCount = 0
                    }
                }.find { !Modifier.isStatic(it.modifiers) }?.let { methodIsCarAudio = it.name }

                // 5. e() 获取 GearInfo (AudioGearInfo, 无参, 非静态)
                bridge.findMethod {
                    matcher {
                        declaredClass = targetClass
                        returnType = gearInfoClass
                        paramCount = 0
                    }
                }.find { !Modifier.isStatic(it.modifiers) }?.let { methodGetGearInfo = it.name }

                // 6. 确认内部广播接收器类名（$b）
                // 固定命名规律，直接缓存，不需要 DexKit 搜索
                classBluetoothReceiver = "$targetClass\$b"

                // 7. 解析 AudioGearInfoGearInfo 内部混淆字段名
                val gearFields = bridge.findField {
                    matcher {
                        declaredClass = gearInfoClass
                    }
                }.filter { !Modifier.isStatic(it.modifiers) }

                val intFields = gearFields.filter { it.type.name == "int" }.sortedBy { it.name }
                val stringFields = gearFields.filter { it.type.name == "java.lang.String" }.sortedBy { it.name }

                if (intFields.size >= 3) {
                    fieldGearType = intFields[0].name
                    fieldIntG = intFields[1].name
                    fieldIntH = intFields[2].name
                }
                if (stringFields.size >= 3) {
                    fieldBrand = stringFields[0].name
                    fieldModel = stringFields[1].name
                    fieldMac = stringFields[2].name
                }

                saveToCache(prefs, currentAppVersionCode)
                XLog.i("QQMusicDexKit: 搜索完成！getInstance=$methodGetInstance, updateRoute=$methodUpdateRoute, GearInfoFileds:$fieldGearType,$fieldIntG,$fieldIntH,$fieldBrand,$fieldModel,$fieldMac")
            }
        } catch (e: Throwable) {
            XLog.e("QQMusicDexKit 初始化异常: ", e)
        }
    }

    private fun saveToCache(prefs: android.content.SharedPreferences, appVersionCode: Long) {
        prefs.edit().apply {
            putLong("KEY_LAST_VERSION_CODE", appVersionCode)
            putLong("KEY_LAST_MODULE_BUILD_TIME", hun.mesh.carwithenhance.BuildConfig.BUILD_TIME)
            putString("classAudioRouteManager",  classAudioRouteManager)
            putString("classAudioGearInfo",      classAudioGearInfo)
            putString("classBluetoothReceiver",  classBluetoothReceiver)
            putString("mGetInstance",   methodGetInstance)
            putString("mUpdateRoute",   methodUpdateRoute)
            putString("mGetGearType",   methodGetGearType)
            putString("mIsCarAudio",    methodIsCarAudio)
            putString("mGetGearInfo",   methodGetGearInfo)
            putString("fieldGearType",  fieldGearType)
            putString("fieldBrand",     fieldBrand)
            putString("fieldModel",     fieldModel)
            putString("fieldMac",       fieldMac)
            putString("fieldIntG",      fieldIntG)
            putString("fieldIntH",      fieldIntH)
        }.apply()
    }

    private fun loadFromCache(prefs: android.content.SharedPreferences) {
        classAudioRouteManager  = prefs.getString("classAudioRouteManager",  classAudioRouteManager)  ?: classAudioRouteManager
        classAudioGearInfo      = prefs.getString("classAudioGearInfo",       classAudioGearInfo)      ?: classAudioGearInfo
        classBluetoothReceiver  = prefs.getString("classBluetoothReceiver",   "")                      ?: ""
        methodGetInstance  = prefs.getString("mGetInstance",  "") ?: ""
        methodUpdateRoute  = prefs.getString("mUpdateRoute",  "") ?: ""
        methodGetGearType  = prefs.getString("mGetGearType",  "") ?: ""
        methodIsCarAudio   = prefs.getString("mIsCarAudio",   "") ?: ""
        methodGetGearInfo  = prefs.getString("mGetGearInfo",  "") ?: ""
        fieldGearType      = prefs.getString("fieldGearType", "") ?: ""
        fieldBrand         = prefs.getString("fieldBrand",    "") ?: ""
        fieldModel         = prefs.getString("fieldModel",    "") ?: ""
        fieldMac           = prefs.getString("fieldMac",      "") ?: ""
        fieldIntG          = prefs.getString("fieldIntG",     "") ?: ""
        fieldIntH          = prefs.getString("fieldIntH",     "") ?: ""
    }
}
