# BlindRadio for Android

Screen-reader-first internet radio for Android phones, tablets and Android TV
(Android 10 to Android 17). The same engine as [BlindRadio for desktop](https://git.serrebiradio.com/serrebi/blindradio):
the same directories, the same gapless station changes with tuning hiss, the same song titles.

## Tuning like a dial

Landing on a station plays it. With TalkBack, swipe right or left through the
list, or touch a station: each one you land on tunes in, and its song is spoken
when it settles. On Android TV, arrow up and down with the remote. Without a
screen reader, tap a station to play it.

Double tap (or press OK, or tap) the station playing to stop it, and again to play it.

## Actions instead of buttons

Each station carries its own actions. With TalkBack, open TalkBack's Actions
menu on the station. Without TalkBack, long press the station (or hold OK on a
TV remote).

- Add to Favorites, or Remove from Favorites
- Station details: genre, country, format, what is playing, address
- Delete from this list (on the main list)

The screen has three buttons:

- Lists: pick which list the main screen shows (Favorites, All Lists, SerrebiRadio,
  Custom Stations), add a station by its address, or fetch the SerrebiRadio list again.
- Search: every directory at once, by station name.
- Browse: Radio Browser, TuneIn, iHeartRadio, SHOUTcast, Icecast, Live365 and the rest, folder by folder.

Back goes up one level. Playback keeps going with the screen off or in another
app; the notification, a headset button or the TV remote's play key stops it.

## Install

Download `BlindRadio-Android-vX.Y.Z.apk` from the
[releases](https://git.serrebiradio.com/serrebi/BlindRadio-Android/releases) and open it.
One APK runs on 32-bit and 64-bit ARM: phones, tablets and TVs.

## Building

Needs the Android SDK with NDK 27.3.13750724, JDK 21, Rust with
`rustup target add armv7-linux-androideabi aarch64-linux-android`,
and `cargo install cargo-ndk`. Then `./gradlew assembleRelease`: Gradle runs cargo-ndk
for the engine first.

## License

MIT.
