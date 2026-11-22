# Changelog

## 0.3.10

- Detect streams on a web page finds relative stream paths in player scripts and honors the page's declared base URL.

## 0.3.9

- Browse Global under Networks for 142 live stations, including all feeds from the former Capital network.
- Radio Record uses verified 320 kbps MP3 feeds for all 117 catalog stations, including Future House, Trancemission, and Progressive. The Saint Petersburg, Moscow, and Moldova broadcasts are also listed by region.

## 0.3.8

- This list offers Flat playlist view and Grouped playlist view for each playlist. Flat view follows the file order. Refresh SerrebiRadio or reimport an older playlist into the same list to recover its file order.

## 0.3.7

- Detect streams on a web page finds scheme-relative stream links in player scripts. Relative links now resolve from the final page address after redirects.
- Stream detection reports an error if the search fails and times out after 90 seconds.

## 0.3.5

- Import folder of playlists: pick a folder of M3U or PLS files and it becomes a list, or a folder in Custom Stations. Each file becomes a folder named after it, and subfolders stay subfolders.
- Export as folder of playlists writes a list as a new folder holding one playlist file for each of its folders.
- Export as playlist file now keeps folders. Before, every station landed loose in one list.
- The version number matches BlindRadio for desktop.

## 0.3.4

- Last.fm scrobbling is gone: it needed an API key and secret of your own. Old Last.fm settings are dropped.
- Libre.fm scrobbling signs in with just your user name and password: Settings, Songs, Sign in to Libre.fm. It says whose account it signed in to and keeps only the sign-in, never the password. Clear the user name to sign out.
- ListenBrainz can scrobble to a server of your own, or to Maloja or Koito: type its address in ListenBrainz server. Blank is listenbrainz.org.
- Check ListenBrainz says whose token it is, or why the server refused it.
- A song whose title names no artist is no longer sent to be scrobbled. Both services refuse such listens.
- The version number matches BlindRadio for desktop.

## 0.3.3

- HLS stations that put commas in their song details, such as KCRW and iHeartRadio stations, no longer make BlindRadio request audio files that do not exist.
- A song title containing a comma is no longer cut off at the comma on HLS stations.
- HLS stations that send a segment number where the title goes no longer announce a new number every few seconds.
- Daily and weekly alarms and scheduled recordings keep their clock time when daylight saving changes. An 8:00 alarm used to move to 7:00 or 9:00.
- An HLS station whose encryption line is damaged is refused instead of playing noise.
- The version number now matches BlindRadio for desktop.

## 0.3.2

- Help, About now lists every project link as its own button (GitHub, Forgejo, Projects, Telegram, Full credits), so a keyboard or a screen reader reaches each one instead of URLs buried in text.

## 0.3.1

- Stations on older secure servers play, such as Radio Koszalin (https://stream6.nadaje.com:8503/). They said "Unreachable" while VLC played them: their servers only speak an old kind of encryption, and BlindRadio now falls back to one that speaks it.

## 0.3.0

- YouTube and Twitch live streams play: add a station by its link (a video, a channel's live page, a Twitch channel) and it plays the audio-only stream. No helper apps; a link that expires is looked up again on reconnect.
- Detect streams on a web page (Lists): paste a station's page, or a YouTube or Twitch link, and play or file what it finds.
- Folders copy, cut and paste whole, into another folder or list.
- Equalizer: save your own presets, apply and delete them.
- Settings: keep the time-shift buffer as the station sends it, for a much longer buffer in less memory.

## 0.2.0

- Settings: every desktop setting that applies on Android, including "Play a station as I move to it" to turn off tuning on TalkBack swipes, touch and arrow keys.
- Time-shift: pause holds the stream; back, forward, start of buffer, go live, previous and next song in the buffer.
- Recording: record, pause, songs as separate files, save the buffer or single songs; files go to Music/BlindRadio.
- Scheduled recordings and alarms, started by Android at their time even with BlindRadio closed.
- Sleep timer, volume (remembered per station), mute, equalizer with presets and ten bands, audio channels.
- Previous and Next Station, Go to the playing station.
- Your lists with their folders: new lists and folders, rename, move, delete, copy, cut and paste, add to any list, edit stations.
- Recent list; Song history; Open homepage; Copy stream address; per-station sound settings.
- Search filters (genre, country, language, sort) and sorting in Browse; Filter this list; letter keys jump.
- Import and export playlist files.
- The now-playing line shows play time, format, buffer position, recording and sleep timer; its TalkBack actions are the player.
- Plays are reported to Radio Browser, as its rules ask.
- Check for Updates, daily or from Menu, installs new releases.
- Help, What's new and About in Menu.
- Fixed: on 32-bit Android 10 TVs the app could close by itself; Android blocked a system call the engine tried first.

## 0.1.0

- First release: BlindRadio for Android phones, tablets and Android TV, Android 10 to 17.
- Landing on a station tunes it: TalkBack swipes and touch, or the D-pad on TV.
- Station actions in TalkBack's actions menu and on long press: Favorites, Station details, Delete.
- Lists, Search and Browse, from the desktop BlindRadio engine.
- Plays on with the screen off; notification, headset and remote keys stop and start it.
