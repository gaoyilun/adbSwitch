plugins {
    id("com.android.application")
}

android {
    namespace = "com.devhider.lsp"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.devhider.lsp"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
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

/*
 * Xposed API 用本地 jar：mavenCentral 在本机被网络策略拦(403)，
 * 直接从 Gradle 缓存里取已经下载好的 api-82.jar。
 * 如果换机器构建，把 jar 放到 app/libs/ 并把这里改成 files("libs/api-82.jar") 即可。
 */
val xposedJar = providers.gradleProperty("xposedJarPath").orNull?.let { file(it) }
    ?: file("${System.getProperty("user.home")}/.gradle/caches/modules-2/files-2.1/de.robv.android.xposed/api/82/35866b507b360d4789ff389ad7386b6e8bbf6cc4/api-82.jar")
    .let { if (it.exists()) it else file("libs/api-82.jar") }

// 刻意保持零外部依赖：只用 framework 自带控件，避免任何三方库拉取失败。
dependencies {
    compileOnly(files(xposedJar))
}
