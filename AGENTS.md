# BlindRadio Android

Android app for BlindRadio. Phones, tablets, Android TV. minSdk 29 (Android 10), target 37 (Android 17).
Engine = `blindradio-core` from `serrebi/blindradio` main, git dep. No engine code here.

## Layout
- `native/` = Rust cdylib `libblindradio_android.so`. JNI by hand: own fn-table slot numbers (jni.h order), no jni crate. Two calls: `init`, `call(op, json) -> json`. UTF-8 byte arrays both ways, never jstring (modified UTF-8 breaks emoji).
- `app/` = Kotlin, framework Views only, no androidx. `Engine.kt` = JNI + 100 ms poll. `MainActivity.kt` = one screen, screen stack (main list, Browse, Search). `PlaybackService.kt` = foreground service, MediaSession, audio focus, wake + wifi lock.
- Core change needed = commit to blindradio main first, then `cargo update` here.

## Build and test
- `./gradlew assembleRelease`. Gradle task `cargoNdk` builds armeabi-v7a, arm64-v8a into `app/src/main/jniLibs` (gitignored).
- Gate: `cd native && cargo test && cargo clippy --all-targets`, then `./gradlew lintDebug assembleRelease`.
- Needs NDK 27.3.13750724, `cargo install cargo-ndk`, rustup targets armv7-linux-androideabi aarch64-linux-android. No x86_64: emulator only, +14 MB per install.
- Owner TV = armeabi-v7a only, Android 10. Never drop armeabi-v7a.

## Rules
- Dial: landing on a station tunes it. TalkBack focus (`RowView.performAccessibilityAction` ACTION_ACCESSIBILITY_FOCUS), D-pad (`onItemSelected`). Click = play/stop toggle.
- Row extras = TalkBack custom actions (`res/values/ids.xml` act_0..5) + same list in long-press context menu (`actions()`). New row command = add to `actions()`, not a button.
- Screen open: row it lands on never tunes (`quietRow`), and nothing tunes for 600 ms (`QUIET_MS`): ListView fires its own first selection in D-pad mode.
- Messages = polite live region `message`. Same text twice: trailing space, else no announce.
- Rebind visible rows in place on play change. `notifyDataSetChanged` moves TalkBack.
- All engine calls on main thread. Core `Output` is not Send.
- Output paused while nothing plays (`Output::set_paused`): AAudio running = phone never sleeps.
- Deps never pinned (`*`). `cargo update` before release.

## Traps
- fdk-aac includes AOSP `log/log.h` on `__ANDROID__`. Stub in `native/android-include`, found via `CPLUS_INCLUDE_PATH` in `native/.cargo/config.toml`.
- sccache spawns rustc without argfile: Windows command line too long. Build with `RUSTC_WRAPPER=` (Gradle task sets it).
- cpal device list needs `ndk_context` set, else panic. `init` does it with the app context.
- jni-sys 0.3 vs 0.4 = different table shape, cargo flips between them: why slots are hand-numbered. New JNI call = check its slot in jni.h.
- adb `input keyevent` skips TalkBack. Owner tablet runs Backtalk.
- AGP 9: `android.ndkDirectory` gone. Use `androidComponents.sdkComponents.ndkDirectory`.

## Release
- Bump `versionCode` + `versionName` in `app/build.gradle.kts`, `CHANGELOG.md` `## X`.
- Versions stay 0.x: feature +0.1.0, fix +0.0.1. versionCode only rises.
- Signed with `~/.android/debug.keystore` (cert e686d194...bf10ca), same as owner's other apps. Other key = update fails on devices.
- Forgejo `serrebi/BlindRadio-Android`: tag vX.Y.Z, release, assets `BlindRadio-Android-vX.Y.Z.apk` + `.apk.sha256`. Stable, not prerelease. GitHub mirror syncs itself.
- TV sideload updater (`C:\Users\admin\Scripts\Update-TvSideloadedApps.ps1`) job `BlindRadio` reads releases/latest.
