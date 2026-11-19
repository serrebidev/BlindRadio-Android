# BlindRadio Android

Android app for BlindRadio. Phones, tablets, Android TV. minSdk 29 (Android 10), target 37 (Android 17).
Engine = `blindradio-core` from `serrebi/blindradio` main, git dep. No engine code here.

## Layout
- `native/` = Rust cdylib `libblindradio_android.so`. JNI by hand: own fn-table slot numbers (jni.h order), no jni crate. Two calls: `init`, `call(op, json) -> json`. UTF-8 byte arrays both ways, never jstring (modified UTF-8 breaks emoji).
- `native/src/lib.rs` = Core: playback, time-shift, recording, schedules, settings, sources ops. `lists.rs` = library ops. Place = `{"list", "path"}`.
- `app/` = Kotlin, framework Views only, no androidx. `Engine.kt` = JNI + 100 ms poll + settings cache. `MainActivity.kt` = one screen, screen stack (main/level/Browse/Search/Filter/Schedules), row actions, Menu. `SettingsActivity.kt` = form from settings JSON paths. `Ui.kt` = dialogs, Form. `Alarms.kt` = schedule alarm + boot rearm. `Updater.kt` = Forgejo latest + sha256 + PackageInstaller. `PlaybackService.kt` = foreground service, MediaSession, audio focus (only when playing), wake + wifi lock.
- `app/src/debug` = DebugReceiver, debug builds only: `adb shell am broadcast -n dev.serrebi.blindradio/.DebugReceiver --es op X --es arg JSON`, result in logcat tag BRDBG. Tap-driving dialogs over adb is racy: use this.
- Core change needed = commit to blindradio main first, then `cargo update` here.

## Build and test
- `./gradlew assembleRelease`. Gradle task `cargoNdk` builds armeabi-v7a, arm64-v8a into `app/src/main/jniLibs` (gitignored).
- Gate: `cd native && cargo test && cargo clippy --all-targets`, then `./gradlew lintDebug assembleRelease`.
- Needs NDK 27.3.13750724, `cargo install cargo-ndk`, rustup targets armv7-linux-androideabi aarch64-linux-android. No x86_64: emulator only, +14 MB per install.
- Owner TV = armeabi-v7a only, Android 10. Never drop armeabi-v7a.
- Needs MSYS2 at `C:\msys64` with `pacman -S perl make`. Core compiles OpenSSL in (old-TLS fallback, `net.rs` LEGACY). Git perl lacks modules (Locale::Maketext::Simple, Pod::Usage), Strawberry perl writes `\` paths: both fail. `cargoNdk` puts MSYS2 first on PATH.

## Rules
- Dial: landing on a station tunes it. TalkBack focus (`RowView.performAccessibilityAction` ACTION_ACCESSIBILITY_FOCUS), D-pad (`onItemSelected`). Click = play/stop toggle.
- Row extras = TalkBack custom actions (`res/values/ids.xml` act_0..5) + same list in long-press context menu (`actions()`). New row command = add to `actions()`, not a button.
- Screen open: row it lands on never tunes (`quietRow`), and nothing tunes for 600 ms (`QUIET_MS`): ListView fires its own first selection in D-pad mode.
- Messages = polite live region `message`. Same text twice: trailing space, else no announce.
- Rebind visible rows in place on play change. `notifyDataSetChanged` moves TalkBack.
- All engine calls on main thread. Core `Output` is not Send.
- Output paused while nothing plays (`Output::set_paused`): AAudio running = phone never sleeps.
- Time-shift default 5 min on Android (first run only): ~11 MB/min RAM, TV has 2 GB.
- New player command = `playerCommands()` (now-line actions) and Menu. New row command = `stationActions`/`libActions`. Max 20 actions per node (act_0..19).
- Recordings: Music/BlindRadio on 11+ (direct file path ok for own media), app music dir on 10. `saved` event = MediaScanner.
- YouTube, Twitch: core `live_direct.rs` (cfg android in `live::lookup`). Nothing in this repo. Breaks = update its client version / Twitch query hash from yt-dlp `_base.py` / streamlink `twitch.py`. GPL extractors (NewPipeExtractor, rustypipe, youtubedl-android) rejected: app MIT.
- Schedules: exact alarm only (USE_EXACT_ALARM 33+, SCHEDULE_EXACT_ALARM 31-32). Inexact alarm = FGS start DENIED from background, recording dies with process. Proven 2026-11-14 tablet.
- Deps never pinned (`*`). `cargo update` before release.

## Traps
- fdk-aac includes AOSP `log/log.h` on `__ANDROID__`. Stub in `native/android-include`, found via `CPLUS_INCLUDE_PATH` in `native/.cargo/config.toml`.
- sccache spawns rustc without argfile: Windows command line too long. Build with `RUSTC_WRAPPER=` (Gradle task sets it).
- cpal device list needs `ndk_context` set, else panic. `init` does it with the app context.
- jni-sys 0.3 vs 0.4 = different table shape, cargo flips between them: why slots are hand-numbered. New JNI call = check its slot in jni.h.
- adb `input keyevent` skips TalkBack. Owner tablet runs Backtalk.
- parking_lot_core 0.10 tries futex_time64 first on 32-bit; Android 10 seccomp = SIGSYS kill (syscall 422), not ENOSYS. Patched copy `native/patches/parking_lot_core` via `[patch.crates-io]`. New parking_lot_core version = recopy + re-apply the android arm. Crash only on lock contention: rapid dial on armv7 TV shows it.
- zip 9.7.1+ fails armv7: `mode_t` = u16 on 32-bit Android, `st_mode` u32 (`u32 & u16`, extract.rs). Patched: fork `serrebidev/zip2` branch `android-mode-t` (clone ~/git/zip2), `[patch.crates-io]` in native/Cargo.toml. New zip release = rebase branch on its tag, `cargo update -p zip`. Upstream compiles armv7 = drop patch, delete fork.
- AGP 9: `android.ndkDirectory` gone. Use `androidComponents.sdkComponents.ndkDirectory`.

## Release
- GitHub runners only: `.github/workflows/release.yml`, publish to Forgejo. Never build a release on this PC.
- Bump `versionCode` + `versionName` in `app/build.gradle.kts`, `CHANGELOG.md` `## X`, push to Forgejo main (GitHub mirror syncs itself). Then `gh workflow run release.yml -R serrebidev/BlindRadio-Android --ref main -f version=X`. `-f dry_run=true` = build + sign, artifact only.
- Workflow: checks version committed + tag free, native gate (`cargo test`, `clippy`), `lintDebug assembleRelease`, checks signer e686d194, makes `.apk.sha256`, creates tag + release on Forgejo with both assets, notes = changelog section.
- Secrets on GitHub repo: `ANDROID_DEBUG_KEYSTORE` (base64 of debug.keystore), `FORGEJO_TOKEN`. Copies in owner's SigningThings store (`android\`, `forgejo-api-token.txt`). Never commit them.
- README = in-app Help, CHANGELOG = What's new (copied to assets at build): keep both current.
- In-app updater picks the first `.apk` asset of releases/latest and checks `.apk.sha256`. Keep exactly one APK per release.
- Versions follow desktop BlindRadio. versionCode only rises.
- Signed with `~/.android/debug.keystore` (cert e686d194...bf10ca), same as owner's other apps. Other key = update fails on devices.
- TV sideload updater (`C:\Users\admin\Scripts\Update-TvSideloadedApps.ps1`) job `BlindRadio` reads releases/latest.
