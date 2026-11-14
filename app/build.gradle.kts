plugins { id("com.android.application") }

android {
    namespace = "dev.serrebi.blindradio"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.serrebi.blindradio"
        // Android 10 (TV and phone) through Android 17.
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }
    ndkVersion = "27.3.13750724"
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    // English-only first release; ChromeOS is not a target.
    lint { disable += setOf("SetTextI18n", "ChromeOsAbiSupport") }
    // Release shares the debug key, like the owner's other Android apps: updates install over each other.
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
}

// The engine: native/ (blindradio-core behind JNI) built into jniLibs. ARM only: every
// phone and TV is ARM; x86_64 (emulators) would add 14 MB to each TV install.
val cargoNdk = tasks.register<Exec>("cargoNdk") {
    val ndk = androidComponents.sdkComponents.ndkDirectory
    workingDir = file("../native")
    // sccache spawns rustc without an argument file: the long link line fails on Windows.
    environment("RUSTC_WRAPPER", "")
    commandLine(
        "cargo", "ndk", "-t", "armeabi-v7a", "-t", "arm64-v8a", "-P", "29",
        "-o", file("src/main/jniLibs").absolutePath, "build", "--release",
    )
    doFirst { environment("ANDROID_NDK_HOME", ndk.get().asFile.absolutePath) }
}
tasks.named("preBuild") { dependsOn(cargoNdk) }
