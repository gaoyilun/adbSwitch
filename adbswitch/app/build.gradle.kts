plugins {
    id("com.android.application")
}

// 构建时间戳（epoch millis）：注入 BuildConfig.BUILD_TIME_MS，App 底部格式化显示，
// 便于确认设备上装的是否为最新版本。此处只取原始毫秒数，格式化放到 App 里做
// （Gradle Kotlin DSL 顶层无法直接引用 java.text/java.time 做格式化）。
val buildTimeMs = System.currentTimeMillis()

android {
    namespace = "com.elleng.devswitch"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    // AGP 8 默认不生成 BuildConfig，这里显式开启以便注入构建时间戳
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.elleng.devswitch"
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
            // 开启 R8 混淆 + 资源压缩。
            // 本项目零三方依赖、且所有反射都打在 Android framework 类上
            // （Notification.Builder / NotificationManager 的 Live Updates 相关方法），
            // bootclasspath 不会被混淆，故无需为反射加 keep。
            isMinifyEnabled = true
            isShrinkResources = true
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

// 主开关的真实写入走双后端：root（su）或 Shizuku（shell uid 2000）。
// Shizuku 的三个工件（api / provider / aidl）13.1.5 已在本机 Gradle 缓存，可离线构建。
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
    // 原生底部提示控件 Snackbar（替代手写的悬浮卡片）
    implementation("com.google.android.material:material:1.12.0")
}
