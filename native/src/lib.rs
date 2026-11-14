//! BlindRadio's engine for Android: `blindradio-core` behind two JNI calls.
//!
//! Kotlin owns the screen; Rust owns playback, the library and the directories.
//! Every call is `call(op, json) -> json`, with UTF-8 byte arrays both ways so
//! station names never pass through JNI's modified UTF-8. All calls come from
//! the Android main thread: the core's audio output may not change threads.
//!
//! `lists.rs` holds the library operations (lists, folders, moves, import, export).

mod lists;

use std::cell::RefCell;
use std::collections::HashSet;
use std::ffi::c_void;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use blindradio_core::Engine;
use blindradio_core::import;
use blindradio_core::library::Library;
use blindradio_core::output::{DeviceEvent, MixerCmd, Output, output_devices};
use blindradio_core::record::WavSave;
use blindradio_core::schedule::{Repeat, ScheduledRecording};
use blindradio_core::segments::{Jump, Which};
use blindradio_core::settings::{ChannelMode, EqPreset, EqSettings, Settings, StationEq};
use blindradio_core::sleep::SleepTimer;
use blindradio_core::sources::{Endpoints, Node, SearchQuery, Sort, SortField, Sources};
use blindradio_core::station::{Source, Station};
use blindradio_core::store;
use blindradio_core::tuner::{LiveFactory, Sink, Tuner, TunerEvent};
use crossbeam_channel::{Receiver, Sender};
use serde_json::{Value, json};

/// Same ids as the desktop app, so a copied settings file means the same list.
pub const FAVORITES: &str = "favorites";
pub const ALL: &str = "all-playlists";
pub const RECENT: &str = "recent";
const DEVICE_POLL: Duration = Duration::from_secs(2);
/// A Radio Browser play counts once it has sounded this long (its rules).
const PLAY_COUNTS: Duration = Duration::from_secs(10);
const VOLUME_MAX_POINTS: i32 = 200;
/// Decoded time-shift costs about 11 MB a minute: a TV has 2 GB in all.
const ANDROID_SHIFT_MINUTES: u32 = 5;

/// Sorts offered in Search and Browse, with their labels.
pub const SORTS: [SortField; 9] = [
	SortField::Default,
	SortField::Name,
	SortField::Votes,
	SortField::ClickCount,
	SortField::ClickTrend,
	SortField::Bitrate,
	SortField::Country,
	SortField::Language,
	SortField::Random,
];

pub const PRESETS: [EqPreset; 26] = [
	EqPreset::Flat,
	EqPreset::BassBoost,
	EqPreset::TrebleBoost,
	EqPreset::BassAndTreble,
	EqPreset::Loudness,
	EqPreset::Classical,
	EqPreset::Club,
	EqPreset::Dance,
	EqPreset::FullBass,
	EqPreset::FullBassTreble,
	EqPreset::FullTreble,
	EqPreset::LargeHall,
	EqPreset::Live,
	EqPreset::Party,
	EqPreset::Pop,
	EqPreset::Reggae,
	EqPreset::Rock,
	EqPreset::Ska,
	EqPreset::Soft,
	EqPreset::SoftRock,
	EqPreset::Techno,
	EqPreset::Voice,
	EqPreset::Warm,
	EqPreset::Clarity,
	EqPreset::LaptopSpeakers,
	EqPreset::Custom,
];

struct Shared(Rc<RefCell<Output>>);

impl Sink for Shared {
	fn send(&self, cmd: MixerCmd) {
		self.0.borrow().send(cmd);
	}
}

/// Work finished on the network runtime, handed back to the main thread by `poll`.
enum Done {
	Starter(blindradio_core::Result<String>),
	Out(Value),
}

pub struct Core {
	data: PathBuf,
	settings: Settings,
	library: Library,
	sources: Arc<Sources>,
	output: Rc<RefCell<Output>>,
	tuner: Tuner<LiveFactory, Shared>,
	rendered: Receiver<(u64, Instant)>,
	events: Receiver<TunerEvent>,
	done_tx: Sender<Done>,
	done_rx: Receiver<Done>,
	/// The stations of the list on screen, for dial tuning by index.
	dial: Vec<Station>,
	song: Option<String>,
	/// The title at the playhead last poll: a change splits a song recording.
	heard: Option<String>,
	/// What Play/Pause plays after a stop.
	remembered: Option<Station>,
	/// Stations played this session, oldest first, for Previous and Next Station.
	played: Vec<Station>,
	played_at: Option<usize>,
	/// Previous/Next Station is playing one from `played`: not a new entry.
	stepping: bool,
	/// The station sounding now, and since when: drives the status line and play reports.
	playing_since: Option<(String, Instant)>,
	reported: bool,
	sleep: SleepTimer,
	/// Scheduled entries already started: (entry id, occurrence).
	sched_fired: HashSet<(String, u64)>,
	sched_active: Option<(String, u64)>,
	last_sched_poll: Instant,
	/// What the mixer was last told, so a dial move sends nothing new.
	heard_eq: EqSettings,
	heard_channels: ChannelMode,
	last_device_poll: Instant,
	/// The output is pulling from the device (paused while nothing plays).
	output_on: bool,
	// Dropped last: the tuner's streams run on its runtime.
	engine: Engine,
}

fn unix_now() -> u64 {
	SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs())
}

/// `3:20` under an hour, `1:02:33` over it.
pub fn fmt_dur(seconds: f32) -> String {
	let total = seconds.max(0.0) as u64;
	if total >= 3600 {
		format!("{}:{:02}:{:02}", total / 3600, total % 3600 / 60, total % 60)
	} else {
		format!("{}:{:02}", total / 60, total % 60)
	}
}

/// A station's own equalizer choice over the global one (desktop `with_own`).
pub fn eq_for(settings: &Settings, station_id: &str) -> EqSettings {
	let mut eq = settings.eq.clone();
	match settings.station_overrides.get(station_id).and_then(|o| o.eq.as_ref()) {
		None => {}
		Some(StationEq::Off) => eq.enabled = false,
		Some(StationEq::Preset(p)) => {
			apply_preset(&mut eq, *p);
			eq.enabled = true;
		}
		Some(StationEq::Saved(name)) => {
			if let Some(saved) = settings.eq.saved.iter().find(|s| &s.name == name) {
				eq.bands = saved.bands;
				eq.preamp_db = saved.preamp_db;
				eq.preamp_enabled = saved.preamp_enabled;
				eq.enabled = true;
			}
		}
	}
	eq
}

pub fn apply_preset(eq: &mut EqSettings, preset: EqPreset) {
	eq.preset = preset;
	eq.saved_name = None;
	if preset == EqPreset::Custom {
		eq.bands = eq.custom_bands;
		eq.preamp_db = eq.custom_preamp_db;
		eq.preamp_enabled = eq.custom_preamp_enabled;
	} else if let Some(bands) = preset.bands() {
		eq.bands = bands;
		eq.preamp_db = preset.preamp_db();
	}
}

impl Core {
	pub fn new(data: &Path) -> blindradio_core::Result<Core> {
		let _ = std::fs::create_dir_all(data);
		let first_run = store::first_run(data);
		let mut settings = Settings::load(data);
		if !data.join(store::SETTINGS_FILE).exists() {
			settings.timeshift.minutes = ANDROID_SHIFT_MINUTES;
		}
		let library = Library::load(data);
		settings.buffers.apply();
		let output = Output::start(None)?;
		output.send(MixerCmd::Volume(settings.volume));
		output.send(MixerCmd::Mute(settings.muted));
		output.send(MixerCmd::Eq(settings.eq.clone()));
		output.send(MixerCmd::Hiss(settings.fm_sound));
		let engine = Engine::new(&data.join(store::RESOLVE_FILE), output.sample_rate)?;
		let rendered = output.rendered().clone();
		let output = Rc::new(RefCell::new(output));
		let (tx, events) = crossbeam_channel::unbounded();
		let (done_tx, done_rx) = crossbeam_channel::unbounded();
		let sources = Sources::new(engine.ctx.client.clone(), Endpoints::default());
		sources.set_disabled(settings.disabled_sources.clone());
		let mut tuner = Tuner::new(LiveFactory(engine.ctx.clone()), Shared(output.clone()), tx);
		tuner.set_settings(Arc::new(parking_lot::Mutex::new(settings.clone())));
		let core = Core {
			data: data.to_path_buf(),
			remembered: settings.last_station.clone(),
			heard_eq: settings.eq.clone(),
			heard_channels: ChannelMode::Stereo,
			settings,
			library,
			sources: Arc::new(sources),
			output,
			tuner,
			rendered,
			events,
			done_tx,
			done_rx,
			dial: Vec::new(),
			song: None,
			heard: None,
			played: Vec::new(),
			played_at: None,
			stepping: false,
			playing_since: None,
			reported: false,
			sleep: SleepTimer::default(),
			sched_fired: HashSet::new(),
			sched_active: None,
			last_sched_poll: Instant::now(),
			last_device_poll: Instant::now(),
			output_on: true,
			engine,
		};
		if first_run {
			core.get_starter();
		}
		Ok(core)
	}

	fn spawn<F>(&self, work: F)
	where
		F: Future<Output = Done> + Send + 'static,
	{
		let tx = self.done_tx.clone();
		self.engine.ctx.rt.spawn(async move {
			let _ = tx.send(work.await);
		});
	}

	fn get_starter(&self) {
		let client = self.engine.ctx.client.clone();
		self.spawn(async move { Done::Starter(import::fetch_starter(&client).await) });
	}

	fn save_library(&self) {
		let _ = self.library.save(&self.data);
	}

	/// Saved, and handed to the tuner (time-shift, recording, recognition, scrobbling).
	fn save_settings(&mut self) {
		let _ = self.settings.save(&self.data);
		self.tuner
			.set_settings(Arc::new(parking_lot::Mutex::new(self.settings.clone())));
	}

	fn mixer(&self, cmd: MixerCmd) {
		self.output.borrow().send(cmd);
	}

	/// The mixer takes the EQ and channels the playing station plays through.
	fn refresh_sound(&mut self) {
		let id = self.tuner.playing().map_or(String::new(), |p| p.id.clone());
		let eq = eq_for(&self.settings, &id);
		if eq != self.heard_eq {
			self.heard_eq = eq.clone();
			self.mixer(MixerCmd::Eq(eq));
		}
		let ch = self.settings.channels_for(&id);
		if ch != self.heard_channels {
			self.heard_channels = ch;
			self.mixer(MixerCmd::Channels(ch));
		}
	}

	/// Everything a settings change can touch, after it is saved.
	fn apply_settings(&mut self) {
		let b = self.settings.buffers;
		b.apply();
		self.mixer(MixerCmd::Buffers { start_ms: b.start_ms, refill_limit_ms: b.refill_limit_seconds * 1000 });
		self.mixer(MixerCmd::Hiss(self.settings.fm_sound));
		self.mixer(MixerCmd::Mute(self.settings.muted));
		self.mixer(MixerCmd::Volume(self.settings.volume));
		self.sources.set_disabled(self.settings.disabled_sources.clone());
		// Sent whatever the mixer had: Settings may have changed either.
		let id = self.tuner.playing().map_or(String::new(), |p| p.id.clone());
		self.heard_eq = eq_for(&self.settings, &id);
		self.mixer(MixerCmd::Eq(self.heard_eq.clone()));
		self.heard_channels = self.settings.channels_for(&id);
		self.mixer(MixerCmd::Channels(self.heard_channels));
	}

	fn volume_points(&self) -> i32 {
		(self.settings.volume * 100.0).round() as i32
	}

	/// The level set while a station plays is that station's, applied again when it starts.
	fn set_volume(&mut self, points: i32) {
		self.settings.volume = points.clamp(0, VOLUME_MAX_POINTS) as f32 / 100.0;
		self.mixer(MixerCmd::Volume(self.settings.volume));
		if let Some(id) = self.tuner.playing().map(|p| p.id.clone()) {
			self.settings.station_overrides.entry(id).or_default().volume = Some(self.settings.volume);
		}
		self.save_settings();
	}

	/// Landing on a station with a remembered volume makes it the current volume.
	fn apply_station_volume(&mut self, station: &Station) {
		let v = self.settings.volume_for(&station.id);
		if (v - self.settings.volume).abs() > f32::EPSILON {
			self.settings.volume = v;
			self.mixer(MixerCmd::Volume(v));
		}
	}

	fn status_line(&self) -> String {
		let Some(st) = self.tuner.playing() else {
			return "Stopped".into();
		};
		let mut line = st.name.clone();
		if let Some(song) = self.tuner.heard_title().or_else(|| self.song.clone()) {
			line = format!("{line} - {song}");
		}
		let mut tail = Vec::new();
		if let Some((_, since)) = &self.playing_since {
			tail.push(fmt_dur(since.elapsed().as_secs_f32()));
		}
		if let Some((buffer, status)) = self.tuner.shift() {
			if status.paused.load(Ordering::Relaxed) {
				tail.push("paused".into());
			}
			let behind = status.behind_seconds(&buffer);
			if behind >= 1.0 {
				tail.push(format!("{} behind live", fmt_dur(behind)));
			}
		}
		if self.tuner.is_recording() {
			tail.push(if self.tuner.recording_paused() { "recording paused" } else { "recording" }.into());
		}
		if let Some(f) = self.tuner.format() {
			let ch = match f.channels {
				1 => "mono".to_string(),
				2 => "stereo".to_string(),
				n => format!("{n} channels"),
			};
			let khz = f.sample_rate as f32 / 1000.0;
			tail.push(match f.bitrate_kbps {
				Some(k) => format!("{}, {k} kbps, {khz} kHz, {ch}", f.codec),
				None => format!("{}, {khz} kHz, {ch}", f.codec),
			});
		}
		if let Some(left) = self.sleep.remaining(Instant::now()) {
			tail.push(format!("sleep in {}", fmt_dur(left.as_secs_f32())));
		}
		if tail.is_empty() { line } else { format!("{line}, {}", tail.join(", ")) }
	}

	fn details(&self, s: &Station) -> String {
		let d = &s.details;
		let mut lines = vec![s.name.clone()];
		let mut add = |label: &str, v: Option<String>| {
			if let Some(v) = v.filter(|v| !v.is_empty()) {
				lines.push(format!("{label}: {v}"));
			}
		};
		add("Info", s.info.clone());
		add("Slogan", d.slogan.clone());
		add("Genre", d.genre.clone());
		add("Country", d.country.clone());
		add("Location", d.location.clone());
		add("Language", d.language.clone());
		add("Call sign", d.call_sign.clone());
		let playing_here = self.tuner.playing().is_some_and(|p| p.id == s.id);
		match self.tuner.format().filter(|_| playing_here) {
			Some(f) => add(
				"Format",
				Some(match f.bitrate_kbps {
					Some(k) => format!("{} {k}k, {} Hz, {} channels", f.codec, f.sample_rate, f.channels),
					None => format!("{}, {} Hz, {} channels", f.codec, f.sample_rate, f.channels),
				}),
			),
			None => add(
				"Format",
				d.codec.clone().map(|c| match d.bitrate_kbps {
					Some(k) => format!("{c} {k}k"),
					None => c,
				}),
			),
		}
		if playing_here {
			add("Now playing", self.tuner.heard_title().or_else(|| self.song.clone()));
		}
		add("Unavailable", d.unavailable.clone());
		add("Homepage", d.homepage.clone());
		add("Source", Some(s.source.label().to_string()));
		add("Address", Some(s.url.clone()));
		lines.join("\n")
	}

	fn play(&mut self, st: &Station) {
		self.song = None;
		self.remembered = Some(st.clone());
		self.apply_station_volume(st);
		self.tuner.play(st, Instant::now());
		self.refresh_sound();
	}

	fn stop(&mut self) -> Option<String> {
		if let Some(p) = self.tuner.playing() {
			self.remembered = Some(p.clone());
		}
		let saved = self.finish_recording();
		self.song = None;
		self.playing_since = None;
		self.tuner.stop();
		saved
	}

	/// Ends the recording, if any: what to say about it.
	fn finish_recording(&mut self) -> Option<String> {
		match self.tuner.stop_recording()? {
			Ok(path) => Some(format!("Recording saved: {}", path.display())),
			Err(e) => Some(e.to_string()),
		}
	}

	/// Pause holds the stream in the time-shift buffer; without one it stops.
	fn play_pause(&mut self, out: &mut Vec<Value>) {
		if self.tuner.playing().is_some()
			&& let Some((_, status)) = self.tuner.shift()
		{
			let paused = status.paused.load(Ordering::Relaxed);
			self.tuner.shift_pause(!paused);
			self.say_status(out, if paused { "Resumed" } else { "Paused" });
			return;
		}
		if self.tuner.playing().is_some() {
			if let Some(t) = self.stop() {
				say(out, t);
			}
		} else if let Some(st) = self.remembered.clone() {
			self.play(&st);
		} else {
			say(out, "Nothing to play".into());
		}
	}

	fn say_status(&self, out: &mut Vec<Value>, text: &str) {
		if self.settings.announce_status {
			say(out, text.into());
		}
	}

	fn behind_text(behind: f32) -> String {
		if behind >= 1.0 { format!("{} behind live", fmt_dur(behind)) } else { "Live".into() }
	}

	fn seek(&mut self, out: &mut Vec<Value>, direction: i32) {
		let Some((buffer, status)) = self.tuner.shift() else {
			return say(out, "No time-shift buffer".into());
		};
		let step = i32::try_from(self.settings.timeshift.seek_step_seconds).unwrap_or(15);
		let before = status.behind_seconds(&buffer);
		let depth = self.tuner.buffer_depth_seconds();
		self.tuner.shift_seek(direction * step);
		let after = (before - (direction * step) as f32).clamp(0.0, depth.max(0.0));
		self.say_status(out, &Core::behind_text(after));
	}

	fn segment_jump(&mut self, out: &mut Vec<Value>, forward: bool) {
		match self.tuner.segment_jump(forward) {
			Jump::To(segment) => {
				let behind = self.tuner.live_ms().map_or(0, |l| l.saturating_sub(segment.start_ms));
				say(out, format!("{} - {}", segment.title, Core::behind_text(behind as f32 / 1000.0)));
			}
			Jump::Live => {
				self.tuner.shift_live();
				say(out, "Live".into());
			}
			Jump::Nowhere if self.tuner.segments().is_empty() => say(out, "No songs in the buffer".into()),
			Jump::Nowhere if forward => say(out, "No next song".into()),
			Jump::Nowhere => say(out, "No previous song".into()),
		}
	}

	fn played_step(&mut self, out: &mut Vec<Value>, delta: i32) {
		let next = self.played_at.map_or(-1, |at| at as i32 + delta);
		if next < 0 || next as usize >= self.played.len() {
			return say(out, (if delta < 0 { "No earlier station" } else { "No later station" }).into());
		}
		self.played_at = Some(next as usize);
		let st = self.played[next as usize].clone();
		self.stepping = true;
		self.play(&st);
	}

	fn record(&mut self, out: &mut Vec<Value>) {
		if self.tuner.is_recording() {
			if let Some(t) = self.finish_recording() {
				say(out, t);
			}
			return;
		}
		if self.tuner.playing().is_none() {
			return say(out, "Nothing to record".into());
		}
		let song = self.tuner.heard_title().or_else(|| self.song.clone());
		match self.tuner.start_recording(song.as_deref()) {
			Ok(_) => say(out, "Recording".into()),
			Err(e) => say(out, e.to_string()),
		}
	}

	fn record_dir(&self) -> PathBuf {
		self.settings
			.recording
			.dir
			.as_deref()
			.map(PathBuf::from)
			.unwrap_or_else(|| self.data.join("recordings"))
	}

	/// The whole time-shift buffer to a WAV in the recordings folder, in 30-second pieces.
	fn save_buffer(&self) -> Result<PathBuf, String> {
		let station = self.tuner.playing().ok_or("No buffer")?;
		let (buffer, _) = self.tuner.shift().ok_or("No buffer")?;
		if buffer.buffered_seconds() < 1.0 {
			return Err("No buffer".into());
		}
		let mut save = WavSave::create(&self.record_dir(), &station.name, buffer.rate()).map_err(|e| e.to_string())?;
		let (start, edge) = (buffer.start(), buffer.live_edge());
		let chunk = u64::from(buffer.rate()) * 30;
		let mut at = start;
		let mut piece = vec![0.0f32; (chunk * 2) as usize];
		while at < edge {
			let n = buffer.read_at(at, &mut piece);
			if n == 0 {
				break;
			}
			save.write(&piece[..n]).map_err(|e| e.to_string())?;
			at += (n / 2) as u64;
		}
		save.finish().map_err(|e| e.to_string())
	}

	/// Song segments of the buffer to files, off the main thread.
	fn write_segments(&self, out: &mut Vec<Value>, which: Option<Which>) {
		let segments = match which {
			Some(w) => match self.tuner.segment(w) {
				Some(s) => vec![s],
				None => return say(out, "No such song in the buffer".into()),
			},
			None => self.tuner.segments(),
		};
		if segments.is_empty() {
			return say(out, "No songs in the buffer".into());
		}
		let Some(writer) = self.tuner.segment_writer() else {
			return say(out, "No songs in the buffer".into());
		};
		say(out, format!("Saving {} song{}", segments.len(), if segments.len() == 1 { "" } else { "s" }));
		self.spawn(async move {
			let done = tokio::task::spawn_blocking(move || writer.write(&segments).map_err(|e| e.to_string()))
				.await
				.map_err(|e| e.to_string())
				.and_then(|w| w);
			Done::Out(match done {
				Ok(paths) => json!({
					"k": "saved",
					"paths": paths.iter().map(|p| p.display().to_string()).collect::<Vec<_>>(),
					"text": format!("Saved {} song{}", paths.len(), if paths.len() == 1 { "" } else { "s" }),
				}),
				Err(e) => json!({ "k": "say", "text": e }),
			})
		});
	}

	/// The desktop's scheduled recordings and alarms, checked about once a second.
	fn check_schedules(&mut self, out: &mut Vec<Value>) {
		let now = unix_now();
		if let Some((id, occ)) = self.sched_active.clone() {
			let end = self.settings.schedules.iter().find(|e| e.id == id).map(|e| e.end_of(occ));
			if end.is_some_and(|e| now >= e) || !self.tuner.is_recording() {
				if let Some(t) = self.finish_recording() {
					saved_event(out, &t);
				}
				self.sched_active = None;
				self.once_done(&id);
			}
			return;
		}
		for entry in self.settings.schedules.clone() {
			if !entry.enabled {
				continue;
			}
			if entry.alarm {
				let Some(occ) = entry.alarm_due(now) else { continue };
				if !self.sched_fired.insert((entry.id.clone(), occ)) {
					continue;
				}
				say(out, format!("Alarm: {}", entry.station.name));
				self.play(&entry.station);
				self.once_done(&entry.id);
				continue;
			}
			let Some(occ) = entry.occurrence_at(now) else { continue };
			if now >= entry.end_of(occ) || !self.sched_fired.insert((entry.id.clone(), occ)) {
				continue;
			}
			if self.tuner.is_recording() {
				say(out, "Scheduled recording skipped: already recording".into());
				continue;
			}
			match self.tuner.start_background_recording(&entry.station) {
				Ok(_) => {
					say(out, format!("Scheduled recording started: {}", entry.station.name));
					self.sched_active = Some((entry.id.clone(), occ));
				}
				Err(e) => say(out, e.to_string()),
			}
			break;
		}
	}

	fn once_done(&mut self, id: &str) {
		if let Some(e) = self.settings.schedules.iter_mut().find(|e| e.id == id)
			&& e.repeat == Repeat::Once
		{
			e.enabled = false;
			self.save_settings();
		}
	}

	/// When the next schedule starts (or one is under way now), for Android's alarm.
	fn next_schedule(&self) -> Option<u64> {
		let now = unix_now();
		self.settings
			.schedules
			.iter()
			.filter(|e| e.enabled)
			.filter_map(|e| if e.occurrence_at(now).is_some() { Some(now) } else { e.next_after(now) })
			.min()
	}

	fn poll(&mut self) -> Value {
		let now = Instant::now();
		let rendered: Vec<_> = self.rendered.try_iter().collect();
		self.tuner.poll(now, &rendered);
		let mut out = Vec::new();
		while let Ok(ev) = self.events.try_recv() {
			let playing_id = self.tuner.playing().map(|p| p.id.clone());
			let here = |id: &str| playing_id.as_deref() == Some(id);
			match ev {
				TunerEvent::Playing { station_id } if here(&station_id) => {
					if let Some(st) = self.tuner.playing().cloned() {
						self.playing_since = Some((st.id.clone(), now));
						self.reported = false;
						if !std::mem::take(&mut self.stepping) {
							let cut = self.played_at.map_or(0, |a| a + 1);
							self.played.truncate(cut);
							if self.played.last().is_none_or(|l| l.id != st.id) {
								self.played.push(st.clone());
							}
							self.played_at = Some(self.played.len() - 1);
						}
						self.library.add_recent(&st);
						self.settings.last_station = Some(st);
						self.save_library();
						self.save_settings();
						self.say_status(&mut out, "Playing");
					}
					self.refresh_sound();
				}
				TunerEvent::Title { station_id, title, .. } if here(&station_id) => {
					if self.settings.announce_titles
						&& let Some(t) = &title
						&& self.song.as_ref() != Some(t)
						&& self.tuner.shift().is_none_or(|(b, s)| s.behind_seconds(&b) < 1.0)
					{
						// Announce events cover settled dial titles; a plain song change is said here.
						out.push(json!({ "k": "title", "text": t }));
					}
					self.song = title;
				}
				TunerEvent::Announce(text) if self.settings.announce_titles => {
					out.push(json!({ "k": "title", "text": text }))
				}
				TunerEvent::Failed { name, reason, station_id } => {
					if self.playing_since.as_ref().is_some_and(|(id, _)| *id == station_id) {
						self.playing_since = None;
					}
					say(&mut out, format!("Failed: {name}: {reason}"))
				}
				TunerEvent::Reconnecting { station_id } if here(&station_id) => {
					say(&mut out, "Connection lost. Reconnecting.".into())
				}
				TunerEvent::Reconnected { station_id } if here(&station_id) => say(&mut out, "Reconnected".into()),
				TunerEvent::RecordingFailed { .. } => say(&mut out, "Recording stream failed; file saved".into()),
				_ => {}
			}
		}
		// A song recording follows the audio heard: in the buffer that is the playhead's song.
		let heard = self.tuner.heard_title().or_else(|| self.song.clone());
		if heard != self.heard {
			if let Some(song) = &heard
				&& let Some(done) = self.tuner.split_recording(song)
			{
				match done {
					Ok(path) => saved_event(&mut out, &format!("Recording saved: {}", path.display())),
					Err(e) => say(&mut out, e.to_string()),
				}
			}
			self.heard = heard;
		}
		while let Ok(done) = self.done_rx.try_recv() {
			match done {
				Done::Out(v) => out.push(v),
				Done::Starter(Ok(body)) => {
					import::import_starter(&mut self.library, &body);
					self.save_library();
					out.push(json!({ "k": "lists" }));
				}
				Done::Starter(Err(e)) => say(&mut out, format!("Starter list not loaded: {e}")),
			}
		}
		// Radio Browser asks every app to report a play after a few seconds of sound.
		if !self.reported
			&& let Some((id, since)) = &self.playing_since
			&& now.duration_since(*since) >= PLAY_COUNTS
		{
			self.reported = true;
			let is_rb = self.tuner.playing().is_some_and(|p| p.source == Source::RadioBrowser);
			if self.settings.report_plays && is_rb {
				let (sources, id) = (self.sources.clone(), id.clone());
				self.engine.ctx.rt.spawn(async move {
					let _ = sources.report_play(&id).await;
				});
			}
		}
		if self.sleep.due(now) {
			self.stop();
			say(&mut out, "Sleep timer: stopped".into());
		}
		if now.duration_since(self.last_sched_poll) >= Duration::from_secs(1) {
			self.last_sched_poll = now;
			self.check_schedules(&mut out);
		}
		if now.duration_since(self.last_device_poll) >= DEVICE_POLL {
			self.last_device_poll = now;
			match self.output.borrow_mut().poll_devices(&output_devices) {
				Some(DeviceEvent::Lost) => say(&mut out, "Audio device lost".into()),
				Some(DeviceEvent::Back(name)) => say(&mut out, format!("Using {name}")),
				None => {}
			}
		}
		let rate = self.output.borrow_mut().rate_changed();
		if let Some(rate) = rate {
			let playing = self.tuner.playing().cloned();
			self.tuner.set_rate(rate);
			if let Some(st) = playing {
				self.tuner.play(&st, Instant::now());
			}
		}
		json!({
			"events": out,
			"playing": self.tuner.playing(),
			"line": self.status_line(),
			// Keeps the playback service (and its wake lock) up for a recording with nothing playing.
			"busy": self.tuner.is_recording() || self.sched_active.is_some(),
		})
	}

	pub fn call(&mut self, op: &str, arg: Value) -> Result<Value, String> {
		let reply = self.dispatch(op, arg);
		// Nothing playing: let the device sleep. Something tuned: pull again.
		let on = self.tuner.playing().is_some();
		if on != self.output_on && self.output.borrow().set_paused(!on) {
			self.output_on = on;
		}
		reply
	}

	fn dispatch(&mut self, op: &str, arg: Value) -> Result<Value, String> {
		let station = |v: &Value| serde_json::from_value::<Station>(v.clone()).map_err(|e| e.to_string());
		// Commands that only report: their messages, as poll events would be.
		let mut out = Vec::new();
		let said = |out: Vec<Value>| Value::Array(out);
		Ok(match op {
			"poll" => self.poll(),
			"status" => json!(self.status_line()),
			"dial" => {
				self.dial = serde_json::from_value(arg).map_err(|e| e.to_string())?;
				Value::Null
			}
			"focus" => {
				let i = arg.as_u64().ok_or("index")? as usize;
				if let Some(st) = self.dial.get(i).cloned() {
					if self.tuner.playing().is_none_or(|p| p.id != st.id) {
						self.song = None;
						if let Some(t) = self.finish_recording() {
							saved_event(&mut out, &t);
						}
					}
					self.remembered = Some(st.clone());
					self.apply_station_volume(&st);
					self.tuner.focus(&self.dial, i, Instant::now());
					self.refresh_sound();
				}
				said(out)
			}
			"play" => {
				let st = station(&arg)?;
				if self.tuner.playing().is_some_and(|p| p.id != st.id)
					&& let Some(t) = self.finish_recording()
				{
					saved_event(&mut out, &t);
				}
				self.play(&st);
				said(out)
			}
			"stop" => {
				if let Some(t) = self.stop() {
					saved_event(&mut out, &t);
				}
				said(out)
			}
			"toggle" => {
				self.play_pause(&mut out);
				said(out)
			}
			"seek" => {
				self.seek(&mut out, arg.as_i64().unwrap_or(-1).signum() as i32);
				said(out)
			}
			"seek_start" => {
				if self.tuner.shift().is_some() {
					let depth = self.tuner.buffer_depth_seconds();
					self.tuner.shift_start();
					self.say_status(&mut out, &Core::behind_text(depth));
				}
				said(out)
			}
			"live" => {
				self.tuner.shift_live();
				if self.tuner.shift().is_some() {
					self.say_status(&mut out, "Live");
				}
				said(out)
			}
			"segment" => {
				self.segment_jump(&mut out, arg.as_bool().unwrap_or(true));
				said(out)
			}
			"prev_station" => {
				self.played_step(&mut out, -1);
				said(out)
			}
			"next_station" => {
				self.played_step(&mut out, 1);
				said(out)
			}
			"volume" => {
				if let Some(delta) = arg["delta"].as_i64() {
					self.set_volume(self.volume_points() + delta as i32);
				} else if let Some(v) = arg["set"].as_i64() {
					self.set_volume(v as i32);
				}
				json!(self.volume_points())
			}
			"mute" => {
				self.settings.muted = !self.settings.muted;
				self.mixer(MixerCmd::Mute(self.settings.muted));
				self.save_settings();
				json!(self.settings.muted)
			}
			"sleep" => {
				let minutes = arg.as_u64().unwrap_or(0) as u32;
				json!(if minutes == 0 { self.sleep.cancel() } else { self.sleep.set(minutes, Instant::now()) })
			}
			"record" => {
				self.record(&mut out);
				said(out)
			}
			"record_pause" => {
				if !self.tuner.is_recording() {
					say(&mut out, "Not recording".into());
				} else {
					let pause = !self.tuner.recording_paused();
					self.tuner.set_recording_paused(pause);
					say(&mut out, (if pause { "Recording paused" } else { "Recording resumed" }).into());
				}
				said(out)
			}
			"record_songs" => {
				self.settings.recording.split_songs = !self.settings.recording.split_songs;
				self.save_settings();
				json!(self.settings.recording.split_songs)
			}
			"save_buffer" => {
				match self.save_buffer() {
					Ok(p) => saved_event(&mut out, &format!("Buffer saved: {}", p.display())),
					Err(e) => say(&mut out, e),
				}
				said(out)
			}
			"write_segment" => {
				let which = match arg.as_str() {
					Some("previous") => Some(Which::Previous),
					Some("next") => Some(Which::Next),
					Some("all") => None,
					_ => Some(Which::Current),
				};
				self.write_segments(&mut out, which);
				said(out)
			}
			"details" => {
				let st = station(&arg)?;
				if let Some(guide) = st.id.strip_prefix("tunein:").map(String::from) {
					let (sources, mut full) = (self.sources.clone(), st.clone());
					self.spawn(async move {
						if let Ok(d) = sources.tunein_details(&guide).await {
							full.details = d;
						}
						Done::Out(json!({ "k": "details", "id": full.id, "station": full }))
					});
				}
				json!(self.details(&st))
			}
			"details_text" => json!(self.details(&station(&arg)?)),
			"history" => {
				let st = station(&arg)?;
				let sources = self.sources.clone();
				self.spawn(async move {
					let items = sources.history(&st).await.unwrap_or_default();
					let rows: Vec<String> = items
						.iter()
						.map(|i| match i.played_at.and_then(|t| SystemTime::now().duration_since(t).ok()) {
							Some(ago) => format!("{}, {} ago", i.title, fmt_dur(ago.as_secs_f32())),
							None => i.title.clone(),
						})
						.collect();
					Done::Out(json!({ "k": "history", "name": st.name, "items": rows }))
				});
				Value::Null
			}
			"search" => {
				let pick = |k: &str| arg[k].as_str().filter(|s| !s.is_empty()).map(String::from);
				let query = SearchQuery {
					name: arg["name"].as_str().unwrap_or_default().to_string(),
					genre: pick("genre"),
					country: pick("country"),
					language: pick("language"),
					sort: sort_of(&arg["sort"]),
				};
				let (sources, seq) = (self.sources.clone(), arg["seq"].clone());
				self.spawn(async move {
					let stations = sources.search(&query).await;
					Done::Out(json!({ "k": "search", "seq": seq, "stations": stations }))
				});
				Value::Null
			}
			"detect" => {
				// Detect Audio Stream on Page: a web page (or a YouTube or Twitch link) to its streams.
				let (client, page) = (self.engine.ctx.client.clone(), arg.as_str().unwrap_or_default().to_string());
				self.spawn(async move {
					Done::Out(match blindradio_core::page::detect(&client, &page).await {
						Ok(stations) => json!({ "k": "detected", "stations": stations }),
						Err(e) => json!({ "k": "say", "text": e.to_string() }),
					})
				});
				Value::Null
			}
			"eq_save" => {
				let name = arg.as_str().unwrap_or_default().trim().to_string();
				if name.is_empty() {
					return Err("no name".into());
				}
				let eq = &mut self.settings.eq;
				let saved = blindradio_core::settings::SavedEqPreset {
					name: name.clone(),
					bands: eq.bands,
					preamp_db: eq.preamp_db,
					preamp_enabled: eq.preamp_enabled,
					..serde_json::from_value(json!({ "name": name })).map_err(|e| e.to_string())?
				};
				eq.saved.retain(|p| p.name != name);
				eq.saved.push(saved);
				eq.saved_name = Some(name);
				self.save_settings();
				Value::Null
			}
			"eq_delete" => {
				let name = arg.as_str().unwrap_or_default();
				let eq = &mut self.settings.eq;
				eq.saved.retain(|p| p.name != name);
				if eq.saved_name.as_deref() == Some(name) {
					eq.saved_name = None;
				}
				self.save_settings();
				Value::Null
			}
			"eq_apply_saved" => {
				let name = arg.as_str().unwrap_or_default();
				let eq = &mut self.settings.eq;
				let p = eq.saved.iter().find(|p| p.name == name).cloned().ok_or("no such preset")?;
				eq.bands = p.bands;
				eq.preamp_db = p.preamp_db;
				eq.preamp_enabled = p.preamp_enabled;
				// Like the desktop: a saved preset becomes the Custom store too.
				eq.custom_bands = p.bands;
				eq.custom_preamp_db = p.preamp_db;
				eq.custom_preamp_enabled = p.preamp_enabled;
				eq.preset = EqPreset::Custom;
				eq.saved_name = Some(p.name);
				eq.enabled = true;
				self.save_settings();
				self.refresh_sound();
				Value::Null
			}
			"filters" => {
				let sources = self.sources.clone();
				self.spawn(async move {
					let f = sources.filters().await;
					Done::Out(json!({ "k": "filters", "genres": f.genres, "countries": f.countries, "languages": f.languages }))
				});
				Value::Null
			}
			"sorts" => Value::Array(SORTS.iter().enumerate().map(|(i, s)| json!({ "i": i, "name": s.label() })).collect()),
			"browse" => match arg["id"].as_str().or(arg.as_str()) {
				None => nodes_json(&Sources::directories()),
				Some(id) => {
					let (id, sort) = (id.to_string(), sort_of(&arg["sort"]));
					let sources = self.sources.clone();
					self.spawn(async move {
						Done::Out(match sources.children(&id, sort).await {
							Ok(nodes) => json!({ "k": "browse", "id": id, "nodes": nodes_json(&nodes) }),
							Err(e) => json!({ "k": "browse", "id": id, "error": e.to_string() }),
						})
					});
					Value::Null
				}
			},
			"directories" => Value::Array(
				Sources::directories()
					.iter()
					.filter_map(|n| match n {
						Node::Folder { id, name } => Some(json!({ "id": id, "name": name })),
						_ => None,
					})
					.collect(),
			),
			"starter" => {
				self.get_starter();
				Value::Null
			}
			"settings" => serde_json::to_value(&self.settings).map_err(|e| e.to_string())?,
			"set_settings" => {
				let mut s: Settings = serde_json::from_value(arg).map_err(|e| e.to_string())?;
				s.buffers = s.buffers.clamped();
				self.settings = s;
				self.save_settings();
				self.apply_settings();
				Value::Null
			}
			"android" => {
				// Recordings go where the phone's file apps look, unless Settings says otherwise.
				if self.settings.recording.dir.is_none()
					&& let Some(dir) = arg["record_dir"].as_str()
				{
					self.settings.recording.dir = Some(dir.into());
					self.save_settings();
				}
				json!({ "play_last": self.settings.play_last_at_startup, "first": self.played.is_empty() })
			}
			"presets" => Value::Array(
				PRESETS
					.iter()
					.map(|p| json!({ "id": serde_json::to_value(p).unwrap_or_default(), "name": p.label() }))
					.collect(),
			),
			"set_preset" => {
				let p: EqPreset = serde_json::from_value(arg).map_err(|e| e.to_string())?;
				apply_preset(&mut self.settings.eq, p);
				self.settings.eq.enabled = true;
				self.save_settings();
				self.refresh_sound();
				Value::Null
			}
			"eq" => {
				// Bands moved by hand: the Custom preset holds them.
				let mut eq: EqSettings = serde_json::from_value(arg).map_err(|e| e.to_string())?;
				if eq.preset == EqPreset::Custom {
					eq.custom_bands = eq.bands;
					eq.custom_preamp_db = eq.preamp_db;
					eq.custom_preamp_enabled = eq.preamp_enabled;
				}
				self.settings.eq = eq;
				self.save_settings();
				self.refresh_sound();
				Value::Null
			}
			"station_settings" => {
				let id = arg.as_str().unwrap_or_default();
				serde_json::to_value(self.settings.station_overrides.get(id).cloned().unwrap_or_default())
					.map_err(|e| e.to_string())?
			}
			"set_station_settings" => {
				let id = arg["id"].as_str().ok_or("id")?.to_string();
				let s = serde_json::from_value(arg["settings"].clone()).map_err(|e| e.to_string())?;
				self.settings.station_overrides.insert(id, s);
				self.save_settings();
				self.refresh_sound();
				Value::Null
			}
			"schedules" => serde_json::to_value(&self.settings.schedules).map_err(|e| e.to_string())?,
			"schedule_add" => {
				let mut e: ScheduledRecording = serde_json::from_value(arg).map_err(|e| e.to_string())?;
				e.id = uuid_like();
				e.enabled = true;
				self.settings.schedules.push(e);
				self.save_settings();
				Value::Null
			}
			"schedule_remove" => {
				let id = arg.as_str().unwrap_or_default();
				self.settings.schedules.retain(|e| e.id != id);
				self.save_settings();
				Value::Null
			}
			"schedule_toggle" => {
				let id = arg.as_str().unwrap_or_default();
				if let Some(e) = self.settings.schedules.iter_mut().find(|e| e.id == id) {
					e.enabled = !e.enabled;
				}
				self.save_settings();
				Value::Null
			}
			"next_schedule" => json!(self.next_schedule()),
			_ => return self.list_op(op, arg),
		})
	}
}

fn say(out: &mut Vec<Value>, text: String) {
	out.push(json!({ "k": "say", "text": text }));
}

/// A file was written: said, and Android's media index learns of it.
fn saved_event(out: &mut Vec<Value>, text: &str) {
	let path = text.split_once(": ").map(|(_, p)| p.to_string());
	out.push(json!({ "k": "saved", "text": text, "paths": path.into_iter().collect::<Vec<_>>() }));
}

fn sort_of(v: &Value) -> Sort {
	match v.as_u64().and_then(|i| SORTS.get(i as usize)) {
		Some(&field) => Sort { field, reverse: !matches!(field, SortField::Name | SortField::Country | SortField::Language) },
		None => Sort::default(),
	}
}

fn uuid_like() -> String {
	format!("{:x}", SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_nanos()))
}

pub fn nodes_json(nodes: &[Node]) -> Value {
	Value::Array(
		nodes
			.iter()
			.map(|n| match n {
				Node::Folder { id, name } => json!({ "t": "folder", "id": id, "name": name }),
				Node::Station(s) => json!({ "t": "station", "s": s }),
				Node::LoadMore { id } => json!({ "t": "more", "id": id }),
			})
			.collect(),
	)
}

// ---- JNI ----
//
// By hand, by slot in the JNI function table: the table's layout is fixed by the
// JNI spec, and jni-sys changes shape between versions cargo picks.

type Env = *const *const *const c_void;
type Obj = *mut c_void;

/// The JNI function in table slot `index` (slots per jni.h, reserved slots counted).
unsafe fn jni<F: Copy>(env: Env, index: usize) -> F {
	unsafe { std::mem::transmute_copy(&*(*env).add(index)) }
}

const NEW_GLOBAL_REF: usize = 21;
const GET_ARRAY_LENGTH: usize = 171;
const NEW_BYTE_ARRAY: usize = 176;
const GET_BYTE_ARRAY_REGION: usize = 200;
const SET_BYTE_ARRAY_REGION: usize = 208;
const GET_JAVA_VM: usize = 219;

unsafe fn read_bytes(env: Env, arr: Obj) -> Vec<u8> {
	if arr.is_null() {
		return Vec::new();
	}
	unsafe {
		let len: extern "system" fn(Env, Obj) -> i32 = jni(env, GET_ARRAY_LENGTH);
		let get: extern "system" fn(Env, Obj, i32, i32, *mut u8) = jni(env, GET_BYTE_ARRAY_REGION);
		let n = len(env, arr).max(0);
		let mut buf = vec![0u8; n as usize];
		get(env, arr, 0, n, buf.as_mut_ptr());
		buf
	}
}

unsafe fn new_bytes(env: Env, data: &[u8]) -> Obj {
	unsafe {
		let new: extern "system" fn(Env, i32) -> Obj = jni(env, NEW_BYTE_ARRAY);
		let set: extern "system" fn(Env, Obj, i32, i32, *const u8) = jni(env, SET_BYTE_ARRAY_REGION);
		let arr = new(env, data.len() as i32);
		if !arr.is_null() {
			set(env, arr, 0, data.len() as i32, data.as_ptr());
		}
		arr
	}
}

fn panic_text(e: Box<dyn std::any::Any + Send>) -> String {
	e.downcast_ref::<&str>()
		.map(|s| s.to_string())
		.or_else(|| e.downcast_ref::<String>().cloned())
		.unwrap_or_else(|| "engine panic".into())
}

/// `Native.init(context, dataDir)`: the engine handle, or 0 with nothing started.
///
/// # Safety
/// Called by the JVM only, on the main thread.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_serrebi_blindradio_Native_init(
	env: Env,
	_class: Obj,
	context: Obj,
	dir: Obj,
) -> i64 {
	unsafe {
		// cpal asks Android's AudioManager for devices through this context.
		static CONTEXT: std::sync::Once = std::sync::Once::new();
		CONTEXT.call_once(|| {
			let get_vm: extern "system" fn(Env, *mut Obj) -> i32 = jni(env, GET_JAVA_VM);
			let global_ref: extern "system" fn(Env, Obj) -> Obj = jni(env, NEW_GLOBAL_REF);
			let mut vm: Obj = std::ptr::null_mut();
			get_vm(env, &mut vm);
			ndk_context::initialize_android_context(vm, global_ref(env, context));
		});
		let dir = PathBuf::from(String::from_utf8_lossy(&read_bytes(env, dir)).into_owned());
		match catch_unwind(|| Core::new(&dir)) {
			Ok(Ok(core)) => Box::into_raw(Box::new(core)) as i64,
			_ => 0,
		}
	}
}

/// `Native.call(handle, op, json)`: `{"ok": value}` or `{"err": text}`, as UTF-8 JSON.
///
/// # Safety
/// Called by the JVM only, on the main thread, with a handle from `init`.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_serrebi_blindradio_Native_call(
	env: Env,
	_class: Obj,
	handle: i64,
	op: Obj,
	arg: Obj,
) -> Obj {
	unsafe {
		let op = String::from_utf8_lossy(&read_bytes(env, op)).into_owned();
		let arg = read_bytes(env, arg);
		let arg: Value = serde_json::from_slice(&arg).unwrap_or(Value::Null);
		let reply = if handle == 0 {
			json!({ "err": "engine not started" })
		} else {
			let core = &mut *(handle as *mut Core);
			match catch_unwind(AssertUnwindSafe(|| core.call(&op, arg))) {
				Ok(Ok(v)) => json!({ "ok": v }),
				Ok(Err(e)) => json!({ "err": e }),
				Err(p) => json!({ "err": panic_text(p) }),
			}
		};
		new_bytes(env, reply.to_string().as_bytes())
	}
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn nodes_carry_kind_and_station() {
		let st = Station {
			id: "custom:1".into(),
			name: "A".into(),
			url: "http://a".into(),
			source: Default::default(),
			info: None,
			details: Default::default(),
		};
		let v = nodes_json(&[
			Node::Folder { id: "f".into(), name: "F".into() },
			Node::Station(st.clone()),
			Node::LoadMore { id: "m".into() },
		]);
		assert_eq!(v[0]["t"], "folder");
		assert_eq!(v[1]["s"]["name"], "A");
		assert_eq!(serde_json::from_value::<Station>(v[1]["s"].clone()).unwrap(), st);
		assert_eq!(v[2]["t"], "more");
	}

	#[test]
	fn durations_and_sorts() {
		assert_eq!(fmt_dur(200.0), "3:20");
		assert_eq!(fmt_dur(3753.0), "1:02:33");
		assert_eq!(sort_of(&json!(1)).field, SortField::Name);
		assert!(!sort_of(&json!(1)).reverse);
		assert_eq!(sort_of(&Value::Null), Sort::default());
	}

	#[test]
	fn saved_event_carries_path() {
		let mut out = Vec::new();
		saved_event(&mut out, "Recording saved: /x/y.mp3");
		assert_eq!(out[0]["paths"][0], "/x/y.mp3");
	}
}
