plugins { id("com.android.application") }

android {
    namespace = "dev.serrebi.blindradio"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.serrebi.blindradio"
        // Android 10 (TV and phone) through Android 17.
        minSdk = 29
        targetSdk = 37
        versionCode = 18
        versionName = "0.3.16"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }
    ndkVersion = "27.3.13750724"
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    // English-only first release; ChromeOS is not a target.
    lint { disable += setOf("SetTextI18n", "ChromeOsAbiSupport") }
    // CI points at the owner's key file: the runner's Android home is not ~/.android, so the
    // default debug key there is a fresh one that cannot update installed copies.
    System.getenv("BLINDRADIO_KEYSTORE")?.let { signingConfigs.getByName("debug").storeFile = file(it) }
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
    // The core compiles OpenSSL in (old-TLS fallback). Its build wants a Unix perl with every
    // core module, and Git for Windows' perl lacks some: MSYS2's perl and make go first.
    val msys = file("C:/msys64/usr/bin")
    if (msys.isDirectory) {
        environment("PATH", msys.absolutePath + File.pathSeparator + System.getenv("PATH"))
        environment("OPENSSL_SRC_PERL", File(msys, "perl.exe").absolutePath)
    }
    commandLine(
        "cargo", "ndk", "-t", "armeabi-v7a", "-t", "arm64-v8a", "-P", "29",
        "-o", file("src/main/jniLibs").absolutePath, "build", "--release", "--locked",
    )
    // Forward slashes: OpenSSL's MSYS make runs the compiler through sh, which strips
    // backslashes ("C:UsersadminAppData...: not found"). cargo-ndk always sets CC_/AR_ to
    // paths joined with backslashes, so cargo's forced [env] overrides them for build scripts.
    doFirst {
        val home = ndk.get().asFile.absolutePath.replace('\\', '/')
        environment("ANDROID_NDK_HOME", home)
        if (System.getProperty("os.name").startsWith("Windows")) {
            val bin = "$home/toolchains/llvm/prebuilt/windows-x86_64/bin"
            for (triple in listOf("armv7-linux-androideabi", "aarch64-linux-android")) {
                for ((tool, exe) in listOf("CC" to "clang", "CXX" to "clang++", "AR" to "llvm-ar", "RANLIB" to "llvm-ranlib")) {
                    // Single-quoted TOML literal string: ProcessBuilder and PowerShell
                    // both strip embedded double quotes before cargo ever sees them.
                    args("--config", "env.${tool}_$triple.value='$bin/$exe.exe'", "--config", "env.${tool}_$triple.force=true")
                }
            }
        }
    }
}
// Help and What's New read the repo's README and CHANGELOG, copied in at build time.
val docs = tasks.register<Copy>("docs") {
    from("../README.md", "../CHANGELOG.md")
    into("src/main/assets")
}
tasks.named("preBuild") { dependsOn(cargoNdk, docs) }
