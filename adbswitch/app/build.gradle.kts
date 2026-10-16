import java.util.Properties

plugins {
    id("com.android.application")
}

// 构建时间戳（epoch millis）：注入 BuildConfig.BUILD_TIME_MS，App 底部格式化显示，
// 便于确认设备上装的是否为最新版本。此处只取原始毫秒数，格式化放到 App 里做
// （Gradle Kotlin DSL 顶层无法直接引用 java.text/java.time 做格式化）。
//
// 取「最新提交的 commit 时间」而不是 System.currentTimeMillis()：后者在配置阶段
// 每次构建都变，会让所有编译任务的 up-to-date 检查恒失效、Gradle 配置缓存完全失效
// （改一行代码也要全量重编）。commit 时间随源码状态单调变化，同一份代码重复构建
// 产物一致，缓存才留得住。拿不到 git（非 git 环境）时退回当前时间。
// 注意：未提交的改动不会推进这个时间戳，此时它以版本号（versionName）为准。
val buildTimeMs: Long = runCatching {
    // 用 ProcessBuilder 调 git（不依赖 project.exec，且避免脚本里写 java.* 被解析成 Gradle 的 java 扩展）。
    // ProcessBuilder 在自动导入的 java.lang 里；读输出走 Kotlin 的 bufferedReader()/readText()，
    // 全程不显式引用 java.io 类型，规避「脚本顶层 java 被当成工程 java 扩展」的坑。
    val proc = ProcessBuilder("git", "log", "-1", "--format=%ct")
        .directory(project.projectDir)
        .redirectErrorStream(true)
        .start()
    val out = proc.inputStream.bufferedReader().readText().trim()
    proc.waitFor()
    out.toLong() * 1000L
}.getOrDefault(System.currentTimeMillis())

// 签名密钥与口令只存 keystore/signing.properties（keystore/ 整个目录在 .gitignore，绝不入库）：
// 仓库公开，明文口令不能写进 gradle。缺该文件（公开仓库 clone 下来）时不算 release 签名，
// release 产物自然出不来——私钥本来就只在维护者机器上。
// （不能写 java.util.Properties：脚本里 java 会解析成工程的 java 扩展，必须 import。）
// 路径相对本模块（adbswitch/app），指向仓库根的 keystore/；Gradle 工程根是 adbswitch/，别用 rootProject 拼。
val signingProps = Properties().apply {
    file("../../keystore/signing.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.elleng.adbswitch"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    // AGP 8 默认不生成 BuildConfig，这里显式开启以便注入构建时间戳
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.elleng.adbswitch"
        minSdk = 27
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.3"
        buildConfigField("long", "BUILD_TIME_MS", "${buildTimeMs}L")

        // 蒲公英备通道密钥：从仓库外 pgyer.properties 注入 BuildConfig，避免明文进源码/公开仓库。
        // 文件缺失或字段为空时注入空串，蒲公英通道自动降级（未配置即不请求）。
        // 模板见 adbswitch/pgyer.properties.example；真实文件已被根 .gitignore 忽略。
        val pgyerPropsFile = rootProject.file("pgyer.properties")
        val pgyerProps = if (pgyerPropsFile.exists()) {
            Properties().apply { pgyerPropsFile.inputStream().use { load(it) } }
        } else null
        val pgyerApiKey = pgyerProps?.getProperty("PGYER_API_KEY").orEmpty()
        val pgyerAppKey = pgyerProps?.getProperty("PGYER_APP_KEY").orEmpty()
        buildConfigField("String", "PGYER_API_KEY", "\"$pgyerApiKey\"")
        buildConfigField("String", "PGYER_APP_KEY", "\"$pgyerAppKey\"")
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) {
            create("release") {
                storeFile = file("../../keystore/adbswitch.jks")
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
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
            if (signingProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    // 统一产物命名：包名-版本名-架构-构建类型（如 com.elleng.adbswitch-1.0.0-universal-release.apk）。
    // 架构段固定为 universal（本工程单包）；末段构建类型（release/debug）由 variant.name 决定，不写死。
    applicationVariants.all {
        val pkg = applicationId
        val ver = versionName
        val type = name
        outputs.all {
            if (this is com.android.build.gradle.internal.api.ApkVariantOutputImpl) {
                outputFileName = "${pkg}-${ver}-universal-${type}.apk"
            }
        }
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
    // M3 架构三件套：AppCompatActivity（壳）+ PreferenceFragmentCompat（设置列表）
    // + Material 组件（MaterialToolbar / MaterialSwitch / MaterialAlertDialog / Snackbar）
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference:1.2.1")
    // 标准下拉刷新控件（替代自研的下拉刷新轮子）；1.1.0 已在本机 Gradle 缓存，可离线构建
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
