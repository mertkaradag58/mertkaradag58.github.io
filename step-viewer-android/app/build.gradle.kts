plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// OCCT kurulum klasörü: scripts/build_occt_android.sh çıktısı.
// -PocctDir=/yol ile veya OCCT_DIR ortam değişkeniyle değiştirilebilir.
val occtDir: String = (project.findProperty("occtDir") as String?)
    ?: System.getenv("OCCT_DIR")
    ?: rootProject.file("third_party/occt-install").absolutePath

android {
    namespace = "com.mertkaradag.stepviewer"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.mertkaradag.stepviewer"
        minSdk = 24
        targetSdk = 36
        // Play'e her yüklemede sürüm kodu artmalı: CI çalışma numarasından türetilir.
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "1.0.0"

        // AdMob kimlikleri gizli değildir. Gerçek kimlikler verilmezse Google'ın test reklamları kullanılır.
        // -PadmobAppId=... veya ADMOB_APP_ID ortam değişkeniyle de değiştirilebilir.
        fun admob(prop: String, env: String, testId: String): String =
            (project.findProperty(prop) as String?)?.takeIf { it.isNotBlank() }
                ?: System.getenv(env)?.takeIf { it.isNotBlank() }
                ?: testId
        manifestPlaceholders["admobAppId"] =
            admob("admobAppId", "ADMOB_APP_ID", "ca-app-pub-3940256099942544~3347511713")
        buildConfigField("String", "ADMOB_BANNER_ID",
            "\"" + admob("admobBannerId", "ADMOB_BANNER_ID", "ca-app-pub-3940256099942544/9214589741") + "\"")
        buildConfigField("String", "ADMOB_INTERSTITIAL_ID",
            "\"" + admob("admobInterstitialId", "ADMOB_INTERSTITIAL_ID", "ca-app-pub-3940256099942544/1033173712") + "\"")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DOCCT_DIR=$occtDir",
                    "-DANDROID_STL=c++_static",
                )
            }
        }
    }

    // Google Play yükleme anahtarı: gizli tutulur, repoya konmaz.
    // CI'da GitHub gizli değerlerinden (STEP_KEYSTORE_BASE64 vb.) çözülür ve ortam değişkeniyle verilir.
    // Anahtar verilmezse APK yalnızca telefona doğrudan kurulum için debug anahtarıyla imzalanır.
    val uploadKeystore: String? = System.getenv("STEP_KEYSTORE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = System.getenv("STEP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("STEP_KEY_ALIAS")
                keyPassword = System.getenv("STEP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            // .so dosyası APK içinde sıkıştırılır; indirilen APK küçük kalır.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-ads:24.9.0")
    implementation("com.google.android.ump:user-messaging-platform:3.2.0")
}
