import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.rktrl.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.rktrl.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "2.2"
    }

    // Clé de signature : JAMAIS dans le dépôt. Sur GitHub, elle vient des « secrets » du dépôt
    // (voir .github/workflows/build-apk.yml). Sans ces variables (build local), Android Studio
    // signe avec sa propre clé de debug.
    val ksFile = System.getenv("RKTRL_KEYSTORE_FILE")
    val ksPass = System.getenv("RKTRL_KEYSTORE_PASSWORD")
    val hasKey = !ksFile.isNullOrBlank() && !ksPass.isNullOrBlank()
    if (hasKey) {
        signingConfigs {
            create("rktrl") {
                storeFile = file(ksFile!!)
                storePassword = ksPass
                keyAlias = "androiddebugkey"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        debug {
            if (hasKey) signingConfig = signingConfigs.getByName("rktrl")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
