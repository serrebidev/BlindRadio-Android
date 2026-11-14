//! Library operations: the lists, their folders, moving and filing stations,
//! import and export. A place is `{"list": id, "path": [folder indexes]}`, where
//! the list is Favorites, Recent, All Lists or a collection id.

use blindradio_core::import::{self, ImportTarget};
use blindradio_core::library::{Folder, StationDest};
use blindradio_core::station::Station;
use serde_json::{Value, json};

use crate::{ALL, Core, FAVORITES, RECENT};

pub struct Place {
	pub list: String,
	pub path: Vec<usize>,
}

impl Place {
	pub fn of(v: &Value) -> Result<Place, String> {
		Ok(Place {
			list: v["list"].as_str().ok_or("no list")?.to_string(),
			path: v["path"]
				.as_array()
				.map(|a| a.iter().filter_map(|i| i.as_u64().map(|i| i as usize)).collect())
				.unwrap_or_default(),
		})
	}
}

fn place_json(list: &str, path: &[usize]) -> Value {
	json!({ "list": list, "path": path })
}

fn dest(p: &Place) -> Option<StationDest> {
	match p.list.as_str() {
		RECENT | ALL => None,
		FAVORITES => Some(StationDest::Favorites(p.path.clone())),
		id => Some(StationDest::Collection { id: id.to_string(), path: p.path.clone() }),
	}
}

fn all_in(stations: &[Station], folders: &[Folder]) -> Vec<Station> {
	let mut out = stations.to_vec();
	for f in folders {
		out.extend(f.all_stations());
	}
	out
}

impl Core {
	/// A list's flat station order, as the desktop dial plays it.
	pub(crate) fn main_list(&self) -> (String, String, Vec<Station>) {
		let lib = &self.library;
		match self.settings.main_list.as_deref() {
			Some(ALL) => {
				return (ALL.into(), "All Lists".into(), lib.collections.iter().flat_map(|c| c.all_stations()).collect());
			}
			Some(FAVORITES) => return (FAVORITES.into(), "Favorites".into(), lib.all_favorites()),
			Some(RECENT) => return (RECENT.into(), "Recent".into(), lib.recent.clone()),
			_ => {}
		}
		let chosen = self
			.settings
			.main_list
			.as_deref()
			.and_then(|id| lib.collections.iter().find(|c| c.id == id))
			.or_else(|| lib.collections.iter().find(|c| c.name.eq_ignore_ascii_case(import::STARTER_COLLECTION)))
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
		json!({
			"id": id, "name": name, "stations": stations, "current": current,
			// Off = the list opens as its folders, to walk into.
			"flat": self.settings.expand_dial_folders,
		})
	}

	/// One level of a list: its stations, then its folders (the desktop tree's order).
	fn level(&self, p: &Place) -> Result<Value, String> {
		let lib = &self.library;
		let folder_rows = |folders: &[Folder], list: &str| -> Vec<Value> {
			folders
				.iter()
				.enumerate()
				.map(|(i, f)| {
					let mut path = p.path.clone();
					path.push(i);
					json!({ "t": "lib", "name": f.name, "place": place_json(list, &path), "count": f.all_stations().len() })
				})
				.collect()
		};
		let (title, stations, folders): (String, Vec<Station>, Vec<Value>) = match p.list.as_str() {
			RECENT => ("Recent".into(), lib.recent.clone(), Vec::new()),
			ALL => (
				"All Lists".into(),
				Vec::new(),
				lib.collections
					.iter()
					.map(|c| json!({ "t": "lib", "name": c.name, "place": place_json(&c.id, &[]), "count": c.all_stations().len(), "list": true }))
					.collect(),
			),
			FAVORITES if p.path.is_empty() => ("Favorites".into(), lib.favorites.clone(), folder_rows(&lib.fav_folders, FAVORITES)),
			FAVORITES => {
				let f = lib.fav_folder_at(&p.path).ok_or("no such folder")?;
				(f.name.clone(), f.stations.clone(), folder_rows(&f.folders, FAVORITES))
			}
			id => {
				let c = lib.collections.iter().find(|c| c.id == id).ok_or("no such list")?;
				if p.path.is_empty() {
					(c.name.clone(), c.stations.clone(), folder_rows(&c.folders, id))
				} else {
					let f = c.folder_at(&p.path).ok_or("no such folder")?;
					(f.name.clone(), f.stations.clone(), folder_rows(&f.folders, id))
				}
			}
		};
		let mut rows: Vec<Value> = stations.iter().enumerate().map(|(i, s)| json!({ "t": "station", "s": s, "i": i })).collect();
		rows.extend(folders);
		Ok(json!({ "title": title, "rows": rows }))
	}

	fn stations_mut(&mut self, p: &Place) -> Option<&mut Vec<Station>> {
		match p.list.as_str() {
			RECENT => Some(&mut self.library.recent),
			ALL => None,
			FAVORITES => self.library.fav_stations_at_mut(&p.path),
			id => self.library.collection_mut(id)?.stations_at_mut(&p.path),
		}
	}

	/// Everything under a place, folders included, for export.
	fn stations_under(&self, p: &Place) -> Vec<Station> {
		let lib = &self.library;
		match p.list.as_str() {
			RECENT => lib.recent.clone(),
			ALL => lib.collections.iter().flat_map(|c| c.all_stations()).collect(),
			FAVORITES if p.path.is_empty() => lib.all_favorites(),
			FAVORITES => lib.fav_folder_at(&p.path).map(|f| f.all_stations()).unwrap_or_default(),
			id => match lib.collections.iter().find(|c| c.id == id) {
				Some(c) if p.path.is_empty() => all_in(&c.stations, &c.folders),
				Some(c) => c.folder_at(&p.path).map(|f| f.all_stations()).unwrap_or_default(),
				None => Vec::new(),
			},
		}
	}

	fn add_to(&mut self, p: &Place, s: &Station) -> bool {
		match p.list.as_str() {
			RECENT | ALL => false,
			FAVORITES => self.library.add_favorite_to(&p.path, s),
			id => self.library.add_to_collection_folder(id, &p.path, s),
		}
	}

	pub(crate) fn list_op(&mut self, op: &str, arg: Value) -> Result<Value, String> {
		let station = |v: &Value| serde_json::from_value::<Station>(v.clone()).map_err(|e| e.to_string());
		let place = || Place::of(&arg["place"]);
		let v = match op {
			"lists" => {
				let mut out = vec![
					json!({ "id": FAVORITES, "name": "Favorites" }),
					json!({ "id": RECENT, "name": "Recent" }),
					json!({ "id": ALL, "name": "All Lists" }),
				];
				out.extend(self.library.collections.iter().map(|c| json!({ "id": c.id, "name": c.name, "collection": true })));
				Value::Array(out)
			}
			"main" => self.main_json(),
			"set_main" => {
				self.settings.main_list = arg.as_str().map(String::from);
				self.save_settings();
				self.main_json()
			}
			"level" => self.level(&Place::of(&arg)?)?,
			"is_fav" => json!(self.library.is_favorite_station(&station(&arg)?)),
			"in_library" => json!(self.library.holds_station(&station(&arg)?.id)),
			"fav" => {
				let now = self.library.toggle_favorite(&station(&arg)?);
				self.save_library();
				json!(now)
			}
			// The flat main list: by id, as the desktop deletes from its dial.
			"remove" => {
				let st = station(&arg["station"])?;
				match arg["list"].as_str() {
					Some(FAVORITES) => self.library.remove_favorite(&st.id),
					Some(RECENT) => self.library.recent.retain(|s| s.id != st.id),
					Some(ALL) | None => return Err("pick one list to delete from".into()),
					Some(list) => self.library.remove_from_collection(list, &st.id),
				}
				self.save_library();
				self.main_json()
			}
			"remove_at" => {
				let (p, i) = (place()?, arg["index"].as_u64().ok_or("index")? as usize);
				let v = self.stations_mut(&p).ok_or("cannot delete here")?;
				if i < v.len() {
					v.remove(i);
				}
				self.save_library();
				Value::Null
			}
			"move" => {
				let (p, i, up) = (place()?, arg["index"].as_u64().ok_or("index")? as usize, arg["up"].as_bool().unwrap_or(true));
				let v = self.stations_mut(&p).ok_or("cannot move here")?;
				let j = if up { i.checked_sub(1) } else { Some(i + 1).filter(|&j| j < v.len()) };
				let at = match j {
					Some(j) if i < v.len() => {
						v.swap(i, j);
						j
					}
					_ => i,
				};
				self.save_library();
				json!(at)
			}
			"add_to" => {
				let (p, s) = (place()?, station(&arg["station"])?);
				let added = self.add_to(&p, &s);
				self.save_library();
				json!(added)
			}
			"paste" => {
				let (p, s) = (place()?, station(&arg["station"])?);
				// A cut leaves first: the list already holding it would refuse a move within itself.
				let cut = Place::of(&arg["cut"]["place"]).ok().map(|from| (from, arg["cut"]["index"].as_u64().unwrap_or(u64::MAX) as usize));
				let taken = cut.as_ref().and_then(|(from, i)| {
					let v = self.stations_mut(from)?;
					v.get(*i).is_some_and(|x| x.id == s.id).then(|| v.remove(*i))
				});
				let added = self.add_to(&p, &s);
				if !added
					&& let (Some(back), Some((from, i))) = (taken, &cut)
					&& let Some(v) = self.stations_mut(from)
				{
					v.insert((*i).min(v.len()), back);
				}
				self.save_library();
				json!(added)
			}
			// A whole folder, everything under it, copied or moved into another list or folder.
			"folder_paste" => {
				let (from, to, cut) = (Place::of(&arg["from"])?, place()?, arg["cut"].as_bool().unwrap_or(false));
				if from.list == to.list && to.path.starts_with(&from.path) {
					return Err("a folder cannot go inside itself".into());
				}
				let (src, dst) = (dest(&from).ok_or("cannot copy from here")?, dest(&to).ok_or("cannot paste here")?);
				let folder = self.library.folder_snapshot(&src).ok_or("no such folder")?;
				let added = self.library.add_folder_to(&dst, folder).is_some();
				if added && cut {
					match from.list.as_str() {
						FAVORITES => self.library.remove_fav_folder(&from.path),
						id => self.library.collection_mut(id).is_some_and(|c| c.remove_folder(&from.path)),
					};
				}
				self.save_library();
				json!(added)
			}
			"destinations" => Value::Array(
				self.library
					.destinations()
					.into_iter()
					.map(|(name, d)| match d {
						StationDest::Favorites(path) => json!({ "name": name, "place": place_json(FAVORITES, &path) }),
						StationDest::Collection { id, path } => json!({ "name": name, "place": place_json(&id, &path) }),
					})
					.collect(),
			),
			"folder_new" => {
				let (p, name) = (place()?, arg["name"].as_str().unwrap_or("New folder").to_string());
				let made = match p.list.as_str() {
					RECENT | ALL => None,
					FAVORITES => self.library.add_fav_folder(&p.path, &name),
					id => self.library.collection_mut(id).and_then(|c| c.add_folder(&p.path, &name)),
				};
				self.save_library();
				json!(made.is_some())
			}
			"folder_rename" => {
				let (p, name) = (place()?, arg["name"].as_str().unwrap_or_default().to_string());
				let ok = match p.list.as_str() {
					FAVORITES => self.library.rename_fav_folder(&p.path, &name),
					id => self.library.collection_mut(id).is_some_and(|c| c.rename_folder(&p.path, &name)),
				};
				self.save_library();
				json!(ok)
			}
			"folder_delete" => {
				let p = place()?;
				let ok = match p.list.as_str() {
					FAVORITES => self.library.remove_fav_folder(&p.path),
					id => self.library.collection_mut(id).is_some_and(|c| c.remove_folder(&p.path)),
				};
				self.save_library();
				json!(ok)
			}
			"folder_move" => {
				let (p, up) = (place()?, arg["up"].as_bool().unwrap_or(true));
				let moved = match p.list.as_str() {
					FAVORITES => self.library.move_fav_folder(&p.path, up),
					id => self.library.collection_mut(id).and_then(|c| c.move_folder(&p.path, up)),
				};
				self.save_library();
				json!(moved.map(|path| place_json(&p.list, &path)))
			}
			"list_new" => {
				let id = self.library.create_collection(arg.as_str().unwrap_or("New list"));
				self.save_library();
				json!(id)
			}
			"list_rename" => {
				let r = self.library.rename_collection(arg["id"].as_str().unwrap_or_default(), arg["name"].as_str().unwrap_or_default());
				self.save_library();
				r.map_err(|e| e.to_string())?;
				Value::Null
			}
			"list_delete" => {
				let id = arg.as_str().unwrap_or_default();
				self.library.delete_collection(id).map_err(|e| e.to_string())?;
				if self.settings.main_list.as_deref() == Some(id) {
					self.settings.main_list = None;
					self.save_settings();
				}
				self.save_library();
				Value::Null
			}
			"list_move" => {
				let ok = self.library.move_collection(arg["id"].as_str().unwrap_or_default(), arg["up"].as_bool().unwrap_or(true));
				self.save_library();
				json!(ok)
			}
			"edit" => {
				let info = arg["info"].as_str().filter(|i| !i.trim().is_empty());
				let ok = self.library.update_station(
					arg["id"].as_str().ok_or("id")?,
					arg["name"].as_str().unwrap_or_default().trim(),
					arg["url"].as_str().unwrap_or_default().trim(),
					info,
				);
				self.save_library();
				json!(ok)
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
			"import" => {
				let name = arg["name"].as_str().unwrap_or("Imported").to_string();
				let target = if arg["as_list"].as_bool().unwrap_or(false) {
					ImportTarget::Collection(name)
				} else {
					ImportTarget::CustomFolder(name)
				};
				let report = import::import_playlist_text(&mut self.library, arg["text"].as_str().unwrap_or_default(), None, target);
				self.save_library();
				json!(report.message())
			}
			"export" => {
				let mut text = String::from("#EXTM3U\n");
				for s in self.stations_under(&Place::of(&arg)?) {
					text.push_str(&format!("#EXTINF:-1,{}\n{}\n", s.name.replace('\n', " "), s.url));
				}
				json!(text)
			}
			_ => return Err(format!("unknown op {op}")),
		};
		Ok(v)
	}
}
