plugins {
    id("com.android.application")
}

android {
    namespace = "com.cycy.scenehook"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cycy.scenehook"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "2.0.0"
    }

    // 固定签名：CI 每次用同一份 keystore，覆盖安装才不会 INSTALL_FAILED_UPDATE_INCOMPATIBLE，
    // 也不会因为卸载重装把 LSPosed 里的启用状态和作用域清掉。
    signingConfigs {
        create("stable") {
            storeFile = file("keystore.jks")
            storePassword = "scenehook123"
            keyAlias = "scenehook"
            keyPassword = "scenehook123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("stable")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // 不加这句，META-INF/xposed/* 会被合并策略吃掉，LSPosed 认不出这是模块
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // 由框架在运行时注入，绝不能打进 APK
    compileOnly("io.github.libxposed:api:102.0.0")
    compileOnly("io.github.libxposed:annotation:1.0.0")
}
