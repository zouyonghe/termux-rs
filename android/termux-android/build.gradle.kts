import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.termux.rust"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("rustJni"))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.26.2")
    testImplementation(kotlin("test"))
    testImplementation("org.robolectric:robolectric:4.15.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

val buildRustJni = tasks.register<Exec>("buildRustJni") {
    val outputDirectory = layout.buildDirectory.dir("rustJni")
    val projectRoot = rootProject.projectDir.parentFile

    workingDir(projectRoot)
    commandLine(
        "sh",
        projectRoot.resolve("scripts/build-android-jni.sh"),
        "arm64-v8a",
        outputDirectory.get().asFile.absolutePath,
    )
    inputs.dir(projectRoot.resolve("crates/termux-ffi"))
    inputs.file(projectRoot.resolve("Cargo.lock"))
    inputs.file(projectRoot.resolve("scripts/build-android-jni.sh"))
    inputs.file(projectDir.resolve("src/main/cpp/bootstrap_jni.c"))
    outputs.dir(outputDirectory)
}

tasks.named("preBuild") {
    dependsOn(buildRustJni)
}
