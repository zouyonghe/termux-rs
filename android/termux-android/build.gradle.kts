plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.termux.rust"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }
}

dependencies {
    testImplementation(kotlin("test"))
}
