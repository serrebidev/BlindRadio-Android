# Changelog

## 0.3.21

- The SerrebiRadio list follows itself: at start and every six hours BlindRadio asks whether it changed, downloads it only when it has, and adds its new stations, saying how many. Nothing is removed, and a deleted SerrebiRadio list stays deleted.
- Polska (rstreams.ovh) keeps its copy between runs and downloads the file again only when it has changed. Without a connection, the kept copy still browses and searches.

## 0.3.20

- Playback speed for the time-shift buffer, 0.5x to 2x: Faster, Slower and Normal speed in Menu, Time-shift, and Faster and Slower on the now-playing line. Faster catches up on a program you went back in, then plays live at normal speed. Slower falls behind live. Voices keep their pitch; Settings, Speed keeps the pitch off switches to tape speed.
- Station sound has Hide song titles from this station, for a station whose title changes too often to be useful.
- Changing the volume while muted unmutes and says so.
- Browse folders have Add folder to list: the whole folder with its subfolders goes into a list or folder you pick.
- Update from directory, on a list or folder filed from a directory, reads it again: new stations arrive in the same subfolders and changed addresses are followed. Nothing is removed. This list, Update directory folders does every such folder at once.
- Add to list and Add folder to list pick the destination as a tree: a list first, then a folder inside it.
- Add station and Detect streams start with the address on the clipboard when it holds one whole web address.
- Networks in Browse is sorted by language: English, Polish and Russian. Radio Record is under Russian.
- New under Polish: Polska (rstreams.ovh), the licensed Polish stations on air, in their author's groups. Search finds them by name.
- Detect streams follows a site's Listen Live links and pop-up players, and reads Triton (player.listenlive.co) and SecureNet players, used by many North American stations.
- The version number matches BlindRadio for desktop.

## 0.3.16

- Menu, Report an issue on GitHub asks for a title and a description, then opens GitHub's new issue page with both filled in and your BlindRadio version and device added. Sign in on GitHub and press Create.

## 0.3.15

- Updated the native engine and stream playlist parser.
- Updated Android build tools to remove a Gradle deprecation.

## 0.3.14

- When YouTube requires sign-in, detection and playback explain that you can open the stream in your browser.

## 0.3.13

- A link copied from a web page or a chat that carries unseen characters in front of it, such as a BOM or a direction mark, plays again. The engine now strips those before the address.

## 0.3.12

- Fix decompression errors during YouTube live playback.
- Detect Football360 live audio through its player feed and report errors when the provider blocks access.
- Detect MPEG-TS links and direct video player sources that expose supported audio streams.

## 0.3.11

- Detect streams on a web page can find streams in same-site player frames that require a referrer, including Shabakeh 3 on Parsa TV.

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
