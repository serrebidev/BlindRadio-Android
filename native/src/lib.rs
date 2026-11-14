//! BlindRadio's engine for Android: `blindradio-core` behind two JNI calls.
//!
//! Kotlin owns the screen; Rust owns playback, the library and the directories.
//! Every call is `call(op, json) -> json`, with UTF-8 byte arrays both ways so
//! station names never pass through JNI's modified UTF-8. All calls come from
//! the Android main thread: the core's audio output may not change threads.

use std::cell::RefCell;
use std::ffi::c_void;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::sync::Arc;
use std::time::{Duration, Instant};

use blindradio_core::Engine;
use blindradio_core::import;
use blindradio_core::library::Library;
use blindradio_core::output::{DeviceEvent, MixerCmd, Output, output_devices};
use blindradio_core::settings::Settings;
use blindradio_core::sources::{Endpoints, Node, SearchQuery, Sort, Sources};
use blindradio_core::station::Station;
use blindradio_core::store;
use blindradio_core::tuner::{LiveFactory, Sink, Tuner, TunerEvent};
use crossbeam_channel::{Receiver, Sender};
use serde_json::{Value, json};

/// Same ids as the desktop app, so a copied settings file means the same list.
pub const FAVORITES: &str = "favorites";
pub const ALL: &str = "all-playlists";
const DEVICE_POLL: Duration = Duration::from_secs(2);

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
	/// What Play/Pause plays after a stop.
	remembered: Option<Station>,
	last_device_poll: Instant,
	/// The output is pulling from the device (paused while nothing plays).
	output_on: bool,
	// Dropped last: the tuner's streams run on its runtime.
	engine: Engine,
}

impl Core {
	pub fn new(data: &Path) -> blindradio_core::Result<Core> {
		let _ = std::fs::create_dir_all(data);
		let settings = Settings::load(data);
		let library = Library::load(data);
		settings.buffers.apply();
		let output = Output::start(None)?;
		output.send(MixerCmd::Volume(settings.volume));
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
		let first_run = store::first_run(data);
		let core = Core {
			data: data.to_path_buf(),
			remembered: settings.last_station.clone(),
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

	fn save_settings(&self) {
		let _ = self.settings.save(&self.data);
	}

	/// The list the main screen shows: the chosen one, else the starter list,
	/// else the first list with stations, else Favorites.
	fn main_list(&self) -> (String, String, Vec<Station>) {
		let lib = &self.library;
		match self.settings.main_list.as_deref() {
			Some(ALL) => {
				return (
					ALL.into(),
					"All Lists".into(),
					lib.collections.iter().flat_map(|c| c.all_stations()).collect(),
				);
			}
			Some(FAVORITES) => return (FAVORITES.into(), "Favorites".into(), lib.all_favorites()),
			_ => {}
		}
		let chosen = self
			.settings
			.main_list
			.as_deref()
			.and_then(|id| lib.collections.iter().find(|c| c.id == id))
			.or_else(|| {
				lib.collections
					.iter()
					.find(|c| c.name.eq_ignore_ascii_case(import::STARTER_COLLECTION))
			})
			.or_else(|| lib.collections.iter().find(|c| !c.all_stations().is_empty()));
		match chosen {
			Some(c) => (c.id.clone(), c.name.clone(), c.all_stations()),
			None => (FAVORITES.into(), "Favorites".into(), lib.all_favorites()),
		}
	}

	fn main_json(&self) -> Value {
		let (id, name, stations) = self.main_list();
		// Where the list opens: the station playing, else the one from last time.
		let current = self.tuner.playing().or(self.remembered.as_ref()).map(|s| s.id.clone());
		json!({ "id": id, "name": name, "stations": stations, "current": current })
	}

	fn lists(&self) -> Value {
		let mut out = vec![
			json!({ "id": FAVORITES, "name": "Favorites" }),
			json!({ "id": ALL, "name": "All Lists" }),
		];
		out.extend(
			self.library
				.collections
				.iter()
				.map(|c| json!({ "id": c.id, "name": c.name })),
		);
		Value::Array(out)
	}

	fn status_line(&self) -> String {
		match (self.tuner.playing(), &self.song) {
			(Some(st), Some(song)) => format!("{}, {song}", st.name),
			(Some(st), None) => st.name.clone(),
			(None, _) => "Stopped".into(),
		}
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
			add("Now playing", self.song.clone());
		}
		add("Homepage", d.homepage.clone());
		add("Source", Some(s.source.label().to_string()));
		add("Address", Some(s.url.clone()));
		lines.join("\n")
	}

	fn play(&mut self, st: &Station) {
		self.song = None;
		self.remembered = Some(st.clone());
		self.tuner.play(st, Instant::now());
	}

	fn stop(&mut self) {
		if let Some(p) = self.tuner.playing() {
			self.remembered = Some(p.clone());
		}
		self.song = None;
		self.tuner.stop();
	}

	fn poll(&mut self) -> Value {
		let now = Instant::now();
		let rendered: Vec<_> = self.rendered.try_iter().collect();
		self.tuner.poll(now, &rendered);
		let mut out = Vec::new();
		let say = |out: &mut Vec<Value>, text: String| out.push(json!({ "k": "say", "text": text }));
		while let Ok(ev) = self.events.try_recv() {
			let playing_id = self.tuner.playing().map(|p| p.id.clone());
			let here = |id: &str| playing_id.as_deref() == Some(id);
			match ev {
				TunerEvent::Playing { station_id } if here(&station_id) => {
					if let Some(st) = self.tuner.playing().cloned() {
						self.library.add_recent(&st);
						self.settings.last_station = Some(st);
						self.save_library();
						self.save_settings();
					}
				}
				TunerEvent::Title { station_id, title, .. } if here(&station_id) => self.song = title,
				TunerEvent::Announce(text) => say(&mut out, text),
				TunerEvent::Failed { name, reason, .. } => say(&mut out, format!("{name}: {reason}")),
				TunerEvent::Reconnecting { station_id } if here(&station_id) => {
					say(&mut out, "Reconnecting".into())
				}
				TunerEvent::Reconnected { station_id } if here(&station_id) => {
					say(&mut out, "Reconnected".into())
				}
				_ => {}
			}
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
		if now.duration_since(self.last_device_poll) >= DEVICE_POLL {
			self.last_device_poll = now;
			if let Some(DeviceEvent::Lost) = self.output.borrow_mut().poll_devices(&output_devices) {
				say(&mut out, "Audio device lost".into());
			}
		}
		// A device that could not keep the rate: decode again at the one it took.
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
		Ok(match op {
			"poll" => self.poll(),
			"lists" => self.lists(),
			"main" => self.main_json(),
			"set_main" => {
				self.settings.main_list = arg.as_str().map(String::from);
				self.save_settings();
				self.main_json()
			}
			"dial" => {
				self.dial = serde_json::from_value(arg).map_err(|e| e.to_string())?;
				Value::Null
			}
			"focus" => {
				let i = arg.as_u64().ok_or("index")? as usize;
				if let Some(st) = self.dial.get(i).cloned() {
					if self.tuner.playing().is_none_or(|p| p.id != st.id) {
						self.song = None;
					}
					self.remembered = Some(st);
					self.tuner.focus(&self.dial, i, Instant::now());
				}
				Value::Null
			}
			"play" => {
				self.play(&station(&arg)?);
				Value::Null
			}
			"stop" => {
				self.stop();
				Value::Null
			}
			"toggle" => {
				if self.tuner.playing().is_some() {
					self.stop();
				} else if let Some(st) = self.remembered.clone() {
					self.play(&st);
				}
				Value::Null
			}
			"is_fav" => json!(self.library.is_favorite_station(&station(&arg)?)),
			"fav" => {
				let now = self.library.toggle_favorite(&station(&arg)?);
				self.save_library();
				json!(now)
			}
			"remove" => {
				let st = station(&arg["station"])?;
				match arg["list"].as_str() {
					Some(FAVORITES) => self.library.remove_favorite(&st.id),
					Some(ALL) | None => return Err("pick one list to delete from".into()),
					Some(list) => self.library.remove_from_collection(list, &st.id),
				}
				self.save_library();
				self.main_json()
			}
			"add_custom" => {
				let name = arg["name"].as_str().unwrap_or_default().trim();
				let url = arg["url"].as_str().unwrap_or_default().trim();
				if url.is_empty() {
					return Err("no address".into());
				}
				let st = self.library.add_custom(if name.is_empty() { url } else { name }, url);
				self.save_library();
				serde_json::to_value(st).map_err(|e| e.to_string())?
			}
			"details" => json!(self.details(&station(&arg)?)),
			"search" => {
				let q = arg.as_str().unwrap_or_default().to_string();
				let sources = self.sources.clone();
				self.spawn(async move {
					let query = SearchQuery { name: q.clone(), ..Default::default() };
					let stations = sources.search(&query).await;
					Done::Out(json!({ "k": "search", "q": q, "stations": stations }))
				});
				Value::Null
			}
			"browse" => match arg.as_str() {
				None => nodes_json(&Sources::directories()),
				Some(id) => {
					let id = id.to_string();
					let sources = self.sources.clone();
					self.spawn(async move {
						Done::Out(match sources.children(&id, Sort::default()).await {
							Ok(nodes) => json!({ "k": "browse", "id": id, "nodes": nodes_json(&nodes) }),
							Err(e) => json!({ "k": "browse", "id": id, "error": e.to_string() }),
						})
					});
					Value::Null
				}
			},
			"starter" => {
				self.get_starter();
				Value::Null
			}
			_ => return Err(format!("unknown op {op}")),
		})
	}
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
}
