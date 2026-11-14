package dev.serrebi.blindradio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/** The Rust side: `native/src/lib.rs`. Byte arrays carry UTF-8 JSON both ways. */
object Native {
    init { System.loadLibrary("blindradio_android") }

    @JvmStatic external fun init(context: Context, dir: ByteArray): Long

    @JvmStatic external fun call(handle: Long, op: ByteArray, arg: ByteArray): ByteArray
}

class EngineError(message: String) : Exception(message)

/**
 * The one engine for the process, shared by the screen and the playback service.
 * Every call runs on the main thread: the Rust audio output must stay on one thread.
 */
// Only the application context is kept: no activity leaks.
@SuppressLint("StaticFieldLeak")
object Engine {
    private const val POLL_MS = 100L
    private var handle = 0L
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private var polling = false

    /** The screen is showing: keep polling even while stopped. */
    var visible = false
        set(v) { field = v; if (v) schedule() }

    /** Events for the screen: `say`, `search`, `browse`, `lists`, `state`. */
    var listener: ((JSONObject) -> Unit)? = null

    /** The station playing (or connecting), and the line that says what is on. */
    var playing: JSONObject? = null
        private set
    var line = ""
        private set

    /** Null on success, else why the engine could not start. */
    fun start(context: Context): String? {
        if (handle != 0L) return null
        app = context.applicationContext
        return try {
            handle = Native.init(context.applicationContext, context.filesDir.path.toByteArray())
            if (handle == 0L) "no audio output" else null
        } catch (e: Throwable) {
            e.message ?: e.toString()
        }
    }

    val started get() = handle != 0L

    /** `arg` is a JSONObject, JSONArray, String, Number, Boolean or null. */
    fun call(op: String, arg: Any? = null): Any? {
        val json = when (arg) {
            null -> "null"
            is String -> JSONObject.quote(arg)
            else -> arg.toString()
        }
        val reply = JSONObject(String(Native.call(handle, op.toByteArray(), json.toByteArray())))
        if (reply.has("err")) throw EngineError(reply.getString("err"))
        return reply.opt("ok").takeUnless { it == JSONObject.NULL }
    }

    fun schedule() {
        if (polling || handle == 0L) return
        polling = true
        main.postDelayed(::tick, POLL_MS)
    }

    private fun tick() {
        polling = false
        val r = call("poll") as JSONObject
        val events: JSONArray = r.getJSONArray("events")
        for (i in 0 until events.length()) listener?.invoke(events.getJSONObject(i))
        val now = r.optJSONObject("playing")
        val newLine = r.getString("line")
        val changed = now?.optString("id") != playing?.optString("id") || newLine != line
        val wasPlaying = playing != null
        playing = now
        line = newLine
        val ctx = app
        if (ctx != null && now != null && !wasPlaying) {
            ctx.startForegroundService(Intent(ctx, PlaybackService::class.java))
        }
        if (changed) {
            PlaybackService.instance?.update()
            listener?.invoke(JSONObject().put("k", "state"))
        }
        if (visible || now != null) schedule()
    }

    fun toggle() { call("toggle"); schedule() }

    fun stop() { call("stop"); schedule() }
}
