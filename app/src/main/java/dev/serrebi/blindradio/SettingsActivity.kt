package dev.serrebi.blindradio

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * The desktop's Settings tabs as one scrolling form with headings: General, Playback,
 * Recording, Songs, Sources. Saved when the screen closes, applied at once.
 */
class SettingsActivity : Activity() {
    private lateinit var s: JSONObject
    private val saves = ArrayList<() -> Unit>()

    /** `a.b` inside the settings object. */
    private fun obj(path: String): Pair<JSONObject, String> {
        val parts = path.split('.')
        var o = s
        for (p in parts.dropLast(1)) o = o.optJSONObject(p) ?: JSONObject().also { o.put(p, it) }
        return o to parts.last()
    }

    private fun Form.flag(label: Int, path: String) {
        val (o, k) = obj(path)
        val c = check(getString(label), o.optBoolean(k))
        saves += { o.put(k, c.isChecked) }
    }

    private fun Form.num(label: Int, path: String, min: Int, max: Int) {
        val (o, k) = obj(path)
        val f = number(getString(label) + " ($min to $max)", o.optInt(k))
        saves += { f.text.toString().toIntOrNull()?.let { o.put(k, it.coerceIn(min, max)) } }
    }

    /** A text setting; blank saves as none. */
    private fun Form.str(label: Int, path: String) {
        val (o, k) = obj(path)
        val f = text(getString(label), o.optString(k).takeUnless { o.isNull(k) } ?: "")
        saves += { f.text.toString().trim().let { o.put(k, if (it.isEmpty()) JSONObject.NULL else it) } }
    }

    /** A choice among JSON values (enum names), shown with labels. */
    private fun Form.pick(label: Int, path: String, values: List<String>, labels: List<String>) {
        val (o, k) = obj(path)
        var at = values.indexOf(o.optString(k)).coerceAtLeast(0)
        button("${getString(label)}: ${labels[at]}") { b ->
            choose(getString(label), labels, at) { at = it; b.text = "${getString(label)}: ${labels[it]}" }
        }
        saves += { o.put(k, values[at]) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings)
        if (Engine.start(this) != null) { finish(); return }
        s = Engine.call("settings") as JSONObject
        val f = Form(this)
        f.heading(getString(R.string.settings))

        f.heading(getString(R.string.general))
        f.flag(R.string.play_on_move, "play_on_move")
        f.flag(R.string.announce_titles, "announce_titles")
        f.flag(R.string.announce_status, "announce_status")
        f.flag(R.string.announce_actions, "announce_actions")
        f.flag(R.string.close_lists, "close_lists_when_playing")
        f.flag(R.string.play_last, "play_last_at_startup")
        f.flag(R.string.flat_lists, "expand_dial_folders")
        f.flag(R.string.check_updates, "check_updates")
        f.flag(R.string.report_plays, "report_plays")

        f.heading(getString(R.string.playback))
        f.flag(R.string.fm_sound, "fm_sound")
        f.pick(R.string.channels, "channels", listOf("stereo", "mono", "left", "right"),
            listOf(getString(R.string.stereo), getString(R.string.mono), getString(R.string.left), getString(R.string.right)))
        f.num(R.string.start_buffer, "buffers.start_ms", 0, 30000)
        f.num(R.string.refill_limit, "buffers.refill_limit_seconds", 1, 120)
        f.num(R.string.hls_behind, "buffers.hls_segments_behind", 1, 10)
        f.flag(R.string.timeshift, "timeshift.enabled")
        f.num(R.string.timeshift_minutes, "timeshift.minutes", 1, 240)
        f.num(R.string.seek_step, "timeshift.seek_step_seconds", 1, 600)
        f.flag(R.string.encoded_ring, "timeshift.encoded_ring")
        f.num(R.string.encoded_mb, "timeshift.max_mb", 0, 2000)

        f.heading(getString(R.string.recording))
        f.pick(R.string.record_format, "recording.format", listOf("original", "mp3", "wav", "flac"),
            listOf(getString(R.string.fmt_original), "MP3", "WAV", "FLAC"))
        f.num(R.string.mp3_bitrate, "recording.mp3_bitrate_kbps", 32, 320)
        f.flag(R.string.mp3_vbr, "recording.mp3_vbr")
        f.flag(R.string.split_songs, "recording.split_songs")
        f.str(R.string.record_dir, "recording.dir")

        f.heading(getString(R.string.songs))
        f.flag(R.string.recognize, "recognition.enabled")
        f.pick(R.string.recognizer, "recognition.provider", listOf("metadata", "audd"),
            listOf(getString(R.string.metadata_only), "AudD"))
        f.str(R.string.audd_token, "recognition.audd_token")
        f.flag(R.string.only_no_metadata, "recognition.only_when_no_metadata")
        f.flag(R.string.announce_recognized, "recognition.announce")
        f.flag(R.string.scrobble, "scrobble.enabled")
        f.str(R.string.listenbrainz, "scrobble.listenbrainz_token")
        f.str(R.string.lastfm_key, "scrobble.lastfm_api_key")
        f.str(R.string.lastfm_secret, "scrobble.lastfm_secret")
        f.str(R.string.lastfm_session, "scrobble.lastfm_session_key")

        f.heading(getString(R.string.sources))
        val off = s.optJSONArray("disabled_sources")?.strings()?.toMutableSet() ?: mutableSetOf()
        for (d in (Engine.call("directories") as JSONArray).objects()) {
            val id = d.getString("id")
            val c = f.check(d.getString("name"), id !in off)
            saves += { if (c.isChecked) off -= id else off += id }
        }
        saves += { s.put("disabled_sources", JSONArray(off.sorted())) }

        f.scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            }
            insets
        }
        setContentView(f.scroll)
    }

    override fun onPause() {
        super.onPause()
        if (!::s.isInitialized) return
        saves.forEach { it() }
        try {
            Engine.saveSettings(s)
        } catch (e: EngineError) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }
}
