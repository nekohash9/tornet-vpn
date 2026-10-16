plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Релизный ключ передаётся через переменные окружения (в CI — из секретов GitHub).
// Без них релиз подписывается debug-ключом — годится только для локальной проверки.
val releaseKeystore = System.getenv("TORNET_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "dev.nekohash.tornet"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.nekohash.tornet"
        minSdk = 24
        targetSdk = 36
        // В CI версия берётся из тега (v1.2.3 → 1.2.3).
        versionCode = System.getenv("TORNET_VERSION_CODE")?.toIntOrNull() ?: 4
        versionName = System.getenv("TORNET_VERSION_NAME") ?: "1.3"

        externalNativeBuild {
            ndkBuild {
                // JNI-методы hev-socks5-tunnel регистрируются на dev.nekohash.tornet.TProxyService.
                cFlags += listOf("-DPKGNAME=dev/nekohash/tornet", "-DCLSNAME=TProxyService")
                targets += listOf("hev-socks5-tunnel")
            }
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("jni/Android.mk")
        }
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = releaseKeystore
                storePassword = System.getenv("TORNET_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TORNET_KEY_ALIAS")
                keyPassword = System.getenv("TORNET_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    // Отдельный APK под каждую архитектуру (меньше размер) плюс универсальный.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        // libtor.so должен лежать распакованным, иначе tor не стартует на части устройств.
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Встроенный Tor от Guardian Project (тот же, что в Orbot).
    // 0.4.9.5 — последняя версия, которой не нужен compileSdk 37.
    implementation("info.guardianproject:tor-android:0.4.9.5")
    implementation("info.guardianproject:jtorctl:0.4.5.7")
    // Мосты: obfs4, snowflake, meek, webtunnel, dnstt (та же библиотека, что в Orbot).
    implementation("com.netzarchitekten:IPtProxy:5.7.0")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
