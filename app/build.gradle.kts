plugins {
    id("com.android.application")
    kotlin("android")
}
android {
    compileSdk = 34
    defaultConfig {
        applicationId = "com.ck.wwmd222"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    namespace = "com.ck.wwmd222"
}
dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
