import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.agp)
}

android {
    namespace = "com.ohyooo.lvtu"
    buildToolsVersion = libs.versions.build.tools.get()
    ndkVersion = libs.versions.ndk.get()
    compileSdk {
        version = release(libs.versions.compile.sdk.get().toInt()) {
            minorApiLevel = libs.versions.compile.minor.get().toInt()
        }
    }
    defaultConfig {
        applicationId = "com.ohyooo.lvtu"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
        signingConfig = signingConfigs.getByName("debug")
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("signkey.jks")
            storePassword = "123456"
            keyPassword = "123456"
            keyAlias = "demo"
            enableV3Signing = true
            enableV4Signing = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
    buildFeatures {
        buildConfig = false
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jdk.get()))
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.coroutines.android)
}
