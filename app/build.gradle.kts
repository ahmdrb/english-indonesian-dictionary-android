plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ahmdrb.dictionary"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ahmdrb.dictionary"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}
