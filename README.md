# BlindRadio for Android

Screen-reader-first internet radio for Android phones, tablets and Android TV
(Android 10 to Android 17). The same engine as [BlindRadio for desktop](https://git.serrebiradio.com/serrebi/blindradio):
the same directories, gapless station changes with tuning hiss, song titles,
time-shift, recording, the equalizer and scheduled recordings.

[![Join SerrebiProjects on Telegram](https://img.shields.io/badge/Telegram-SerrebiProjects-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/SerrebiProjects)

**Questions, bugs, or release news?** Join the [SerrebiProjects Telegram group](https://t.me/SerrebiProjects), the fastest place to get help.

More serrebi projects: [projects.serrebiradio.com](https://projects.serrebiradio.com)

YouTube and Twitch live streams play too: add a station by its link (Lists, Add
station by address) and BlindRadio plays the stream's audio only. A video link,
a channel's live page (youtube.com/@channel/live) and a Twitch channel
(twitch.tv/channel) all work.

## Tuning like a dial

Landing on a station plays it. With TalkBack, swipe right or left through the
list, or touch a station: each one you land on tunes in, and its song is spoken
when it settles. On Android TV, arrow up and down with the remote, or use the
channel keys. Without a screen reader, tap a station to play it.

Turn this off in Settings, General, "Play a station as I move to it": moving then
only selects, and a double tap (or OK, or a tap) plays.

Double tap the station playing to pause it, and again to resume. With the
time-shift buffer on (the default) pause holds the stream: resume picks up
where you left off.

## Actions instead of buttons

Each row carries its own actions. With TalkBack, open TalkBack's Actions menu on
it. Without TalkBack, long press it (or hold OK on a TV remote).

A station's actions:

- Add to Favorites, or Remove from Favorites
- Add to list: any of your lists or their folders
- Station details: genre, country, format, what is playing, address
- Song history: what the station played lately
- Open homepage, Copy stream address
- Edit station (name, address, info), for stations in your lists
- Station sound: this station's own equalizer, audio channels, song title delay, and song recognition
- Schedule recording or alarm
- Copy, Cut, Paste here, Move up, Move down, Delete (in your lists)

A folder's actions: Open, New folder, Paste into this folder, Copy folder, Cut
folder, Paste folder into this folder, Rename, Move up, Move down, Delete, Export
as playlist file, Export as folder of playlists. This list (in Menu) has Paste folder here too. In All Lists each list is a row with
Show this list on the main screen, Rename, Move up, Move down, Delete and Export.

The now-playing line at the top is a control too. Double tap it to pause or play.
Its TalkBack actions (or a long press) are the player: Stop, Back and Forward,
Go live, Previous and Next station, Volume up and down, Mute, Record, Song
history, and Say what is playing.

## The four buttons

- Lists: pick which list the main screen shows (Favorites, Recent, All Lists,
  SerrebiRadio, Custom Stations, your own lists). Also New list, Add station by
  address, Detect streams on a web page, including scheme-relative links in player scripts.
  You can also detect streams from YouTube and Twitch links. Play or file the results.
  Menu also offers Import playlist file, Import folder of
  playlists, and Get the SerrebiRadio list.
- Search: every directory at once, by station name. Menu, Search filters adds
  genre, country, language and sort.
- Browse: Radio Browser, TuneIn, iHeartRadio, SHOUTcast, Icecast, Live365 and the
  rest, folder by folder. Menu, Sort changes a directory's order.
- Menu: everything else, below.

Back goes up one level. Playback keeps going with the screen off or in another
app; the notification, a headset button or the TV remote's media keys control it.

## Folders of playlists

Import folder of playlists picks a folder on the device: as a new list named after
it, or into Custom Stations. Each playlist file in it (M3U, M3U8, PLS) becomes a
folder named after the file, and each subfolder stays a subfolder. A station already
in the list lands only once. Export as folder of playlists makes a new folder,
named after the list, inside the folder you pick: one playlist file for each top
folder, with its subfolders kept inside the file. Importing it back brings the same
folders. Export as playlist file keeps folders too.

## Menu

- Player: the same commands as the now-playing line.
- Time-shift: Pause or resume, Back and Forward by the seek step, Start of buffer,
  Go live, Previous and Next song in the buffer.
- Recording: Record or Stop recording, Pause recording, Record songs as separate
  files, Save the whole buffer, Save this song, Save the previous song, Save every
  song in the buffer, and Where are my recordings. Recordings go to Music/BlindRadio
  (on Android 10, the app's own Music folder), where file and music apps find them.
- Sound: Volume (a slider TalkBack moves with swipes up and down), Mute, the
  Equalizer (presets, ten bands, and presets of your own to save, apply and delete), Audio channels (stereo, mono, left, right),
  and the Sleep timer.
- **Flat playlist view** and **Grouped playlist view** are in **This list** at
  a playlist's top level. Each playlist remembers its view. Flat view uses the
  original file order, then lists other stations in library order. To recover
  the file order for an older playlist, use **Get the SerrebiRadio list** or
  reimport the original file into the same list. Existing stations keep their
  folders. Switch to grouped view to move stations within their folders.

- This list: Filter this list, Go to the playing station, New folder, Paste here,
  New list, Import playlist file, Import folder of playlists, Export as playlist
  file, Export as folder of playlists.
- Scheduled recordings: each one a row; its actions turn it on or off or delete it.
  Schedule new ones from a station's actions. A schedule starts BlindRadio by itself
  at its time, records in the background without interrupting what you hear, and
  stops when its time is up. An alarm plays the station instead.
- Settings, Check for updates, Help, What's new, About.

## Settings

General: Play a station as I move to it; Announce song titles; Announce
connection status; Announce actions; Close Browse and Search when a station
plays; Play the last station at startup; Show every station of a list in one
list (off: lists open as their folders, to walk into); Check for updates every
day; Report plays to Radio Browser.

Playback: FM sound between stations; Audio channels; Start buffer; Refill limit
after dropouts; HLS segments behind live; Time-shift buffer on or off, its
minutes (5 by default on Android: each minute holds about 11 MB of memory) and
the seek step. Or keep the buffer as the station sends it: hours of audio in a
size you set (200 MB by default).

Recording: format (as the station sends it, MP3, WAV, FLAC), MP3 bitrate and
variable bitrate, Record songs as separate files, and the recordings folder.

Songs: song recognition with AudD for stations that send no titles (paste an
AudD token from audd.io), and scrobbling of songs that play a minute or more.
ListenBrainz: paste your user token. ListenBrainz server stays blank for
listenbrainz.org; for your own server, or Maloja or Koito, type its address up
to the part before /1/. Check ListenBrainz says whose token it is, or why it was
refused. Libre.fm: type your user name and password, then Sign in to Libre.fm.
Only the sign-in is kept, never the password. Clear the user name to sign out.

Directories: turn any directory off; it leaves Browse and Search.

## Keys

On a TV remote or a keyboard: Play/Pause, Stop, Rewind and Fast forward (seek),
Previous and Next (station), Record, Channel up and down (the dial), Menu. A letter
key in a list jumps to the next row starting with it.

## Install

Download `BlindRadio-Android-vX.Y.Z.apk` from the
[releases](https://git.serrebiradio.com/serrebi/BlindRadio-Android/releases) and open it.
One APK runs on 32-bit and 64-bit ARM: phones, tablets and TVs. Check for updates
in Menu downloads and installs new releases (allow BlindRadio to install apps
the first time).

## Building

Needs the Android SDK with NDK 27.3.13750724, JDK 21, Rust with
`rustup target add armv7-linux-androideabi aarch64-linux-android`,
and `cargo install cargo-ndk`. Then `./gradlew assembleRelease`: Gradle runs cargo-ndk
for the engine first.

## License

MIT.

## Credits

See [CREDITS.md](CREDITS.md) for where the ideas behind this project came from, and the third-party projects it builds on.
