plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.io.ByteArrayOutputStream

android {
    namespace = "tech.shroyer.q25trackpadcustomizer"
    compileSdk = 34

    defaultConfig {
        applicationId = "tech.shroyer.q25trackpadcustomizer"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.4.2"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
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
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/trackpadHelperAssets"))
}

val trackpadHelperAssetsDir = layout.buildDirectory.dir("generated/trackpadHelperAssets/trackpad_helper")
val trackpadHelperObjDir = layout.buildDirectory.dir("trackpadHelper/obj")
val trackpadHelperLibDir = layout.buildDirectory.dir("trackpadHelper/libs")

val buildTrackpadHelper by tasks.registering(Exec::class) {
    val ndkBuildCmd = "/opt/homebrew/bin/ndk-build"
    val helperRoot = file("src/main/nativehelper")

    inputs.dir(helperRoot)
    outputs.dir(trackpadHelperLibDir)
    outputs.dir(trackpadHelperAssetsDir)

    commandLine(
        ndkBuildCmd,
        "NDK_PROJECT_PATH=${helperRoot.absolutePath}",
        "APP_BUILD_SCRIPT=${File(helperRoot, "Android.mk").absolutePath}",
        "NDK_APPLICATION_MK=${File(helperRoot, "Application.mk").absolutePath}",
        "NDK_OUT=${trackpadHelperObjDir.get().asFile.absolutePath}",
        "NDK_LIBS_OUT=${trackpadHelperLibDir.get().asFile.absolutePath}"
    )

    doLast {
        val builtBinary = trackpadHelperLibDir.get().file("arm64-v8a/trackpad_helper").asFile
        val assetsOut = trackpadHelperAssetsDir.get().asFile
        assetsOut.mkdirs()
        copy {
            from(builtBinary)
            into(assetsOut)
            rename { "trackpad_helper_arm64-v8a" }
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(buildTrackpadHelper)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
