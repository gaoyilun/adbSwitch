plugins {
    id("com.android.application")
}

android {
    namespace = "com.devhider.probe"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.devhider.probe"
        minSdk = 27
        // 刻意保持 27：targetSdk < 28 时系统不启用 hidden API 限制，
        // 这里才能像 RASP 那样直接反射 android.os.SystemProperties 做真实读取。
        targetSdk = 27
        versionCode = 1
        versionName = "1.0.0"
        ndkVersion = "27.3.13750724"
        externalNativeBuild {
            ndkBuild { abiFilters("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
        }
    }

    externalNativeBuild {
        ndkBuild { path = file("src/main/cpp/Android.mk") }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../../keystore/devhider.jks")
            storePassword = "devhider123"
            keyAlias = "devhider"
            keyPassword = "devhider123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
}
