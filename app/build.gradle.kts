plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "hun.mesh.carwithenhance"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "hun.mesh.carwithenhance"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "1.4"
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("org.luckypray:dexkit:2.2.0")
//    implementation(libs.appcompat)
//    implementation(libs.material)
//    testImplementation(libs.junit)
//    androidTestImplementation(libs.ext.junit)
//    androidTestImplementation(libs.espresso.core)
}