plugins {
    id("com.android.application")
}

// 构建时间戳（epoch millis）：注入 BuildConfig.BUILD_TIME_MS，App 底部格式化显示，
// 便于确认设备上装的是否为最新版本。此处只取原始毫秒数，格式化放到 App 里做
// （Gradle Kotlin DSL 顶层无法直接引用 java.text/java.time 做格式化）。
val buildTimeMs = System.currentTimeMillis()

android {
    namespace = "com.elleng.usbdebug"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    // AGP 8 默认不生成 BuildConfig，这里显式开启以便注入构建时间戳
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.elleng.usbdebug"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("long", "BUILD_TIME_MS", "${buildTimeMs}L")
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

// 刻意保持零外部依赖：只用 framework 自带控件，避免任何三方库拉取失败。
dependencies {
}
