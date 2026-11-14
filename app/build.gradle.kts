plugins {
    id("com.android.application")
}

android {
    namespace = "com.klyro.recorder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.klyro.recorder"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("signing/klyro-release.jks")
            storePassword = "klyro-dev-2026"
            keyAlias = "klyro"
            keyPassword = "klyro-dev-2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
