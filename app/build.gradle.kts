import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * 签名配置：从根目录 keystore.properties 读取，debug 与 release **共用同一套密钥**。
 *
 * - 本地：keystore.properties 指向工作区里的 release.keystore
 * - CI：工作流从 Secrets 还原出同名文件，并生成内容相同的 keystore.properties
 *
 * 文件缺失时不在配置阶段报错，构建出的包就是未签名的（release 装不上）——
 * 这是期望行为，避免把「没配密钥」误当成成功。
 */
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val hasReleaseKeystore = rootProject.file("keystore.properties").exists()

fun keystoreValue(key: String): String =
    keystoreProps.getProperty(key)
        ?: error("缺少签名配置 $key：请检查根目录 keystore.properties")

android {
    namespace = "io.synapse.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.synapse.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 301
        versionName = "3.0.1"
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        // 统一签名：debug 与 release 都用 keystore.properties 指向的**同一个 keystore**。
        //
        // 为什么必须统一（历史教训）：
        // - 曾用 AGP 默认 debug key 签 debug 包、release keystore 签 release 包，
        //   两者证书不同 → 装过 debug 包就再也装不上 release 包（INSTALL_FAILED_UPDATE_INCOMPATIBLE），
        //   反之亦然，每次换包都要卸载重装、丢账号与配置。
        // - 容器（PRoot rootfs）重置还会重新生成 ~/.android/debug.keystore，
        //   debug 签名随之漂移，之前装好的包连原地升级都做不到。
        //
        // 现在本地与 CI 共用同一套密钥：
        // - 本地：keystore.properties 指向工作区里的 release.keystore
        // - CI：工作流从 Secrets 还原出同名文件并生成同样的 keystore.properties
        // 因此本地 debug / 本地 release / CI release 三种包签名完全一致，可互相覆盖升级。
        if (hasReleaseKeystore) {
            create("unified") {
                storeFile = file(keystoreValue("storeFile"))
                storePassword = keystoreValue("storePassword")
                keyAlias = keystoreValue("keyAlias")
                keyPassword = keystoreValue("keyPassword")
            }
        }
    }

    buildTypes {
        // 两个变体都开 R8。注意 AGP 对 debuggable 构建会禁用「优化与混淆」
        // （构建时会提示 “All code optimizations and obfuscation are disabled for
        // debuggable builds”），但**代码裁剪照常生效**——这才是体积的大头：
        // material-icons-extended 的 10660 个未使用图标类会被裁掉。
        // 裁剪后 debug 3.5MB / release 1.4MB（未开前 debug 是 16.8MB）。
        //
        // debug = 本地开发包：**与 release 同包名、同签名**（可互相覆盖安装）。
        // 不加 applicationIdSuffix——加了好处只是能同机共存，代价是装完出现两个图标、
        // 配置/账号/日志全部另起一份，用户还得在两者间来回切。
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 与 release 用同一个 keystore（见 signingConfigs.unified）
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("unified")
        }
        // release = 本地/云端都能构建；云端由 push v* 标签触发并发版。
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("unified")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    // :core 用 implementation 引 gson，不传递；app 里要直接构造 JsonObject（如动作 payload）
    implementation("com.google.code.gson:gson:2.11.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
