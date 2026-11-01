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
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.mertkaradag.stepviewer"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"

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

    buildTypes {
        release {
            isMinifyEnabled = false
            // Şimdilik telefona doğrudan kurulabilmesi için debug anahtarıyla imzalanır.
            // Play Store'a yüklemeden önce gerçek bir yükleme anahtarı tanımlanmalı.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
}
