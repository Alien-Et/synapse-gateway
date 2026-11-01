import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 仅使用 Android 上同样可用的 API（HttpURLConnection + 纯 Java 库），使 :core 既能
    // 在本机 JVM 直接跑测试/联调，也能原样打进 Android 应用。
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // 国密 SM2/SM4：移动云盘的登录接口用它们加密请求体（SM2 包裹随机 SM4 密钥 +
    // SM4-ECB/PKCS7 加密业务数据）。Android 自带的 JCE 不提供 SM2/SM4，
    // 必须用 BouncyCastle；这里只用轻量 bcpkix 之外的 bcprov，体积可控。
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
