plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.jing.jdriver.keygen"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.jing.jdriver.keygen"
        minSdk = 26
        targetSdk = 34
        versionCode = 100
        versionName = "1.0.0-ADMIN"
    }

    signingConfigs {
        create("officialRelease") {
            storeFile = rootProject.file("jdriver_release.keystore")
            storePassword = "JDriverPro2026!"
            keyAlias = "jdriver_official"
            keyPassword = "JDriverPro2026!"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("officialRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("officialRelease")
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.cardview:cardview:1.0.0")
}
