package dev.serrebi.blindradio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * The one engine for the process, shared by the screen, the playback service and
 * the schedule alarm. Every call runs on the main thread: the Rust audio output must
 * stay on one thread.
 */
// Only the application context is kept: no activity leaks.
@SuppressLint("StaticFieldLeak")
object Engine {
    private const val POLL_MS = 100L
    private const val ALARM_SYNC_MS = 60_000L
    private var handle = 0L
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private var polling = false
    private var lastAlarmSync = 0L

    /** The screen is showing: keep polling even while stopped. */
    var visible = false
        set(v) { field = v; if (v) schedule() }

    /** Events for the screen: `say`, `title`, `saved`, `search`, `browse`, `lists`, `state`... */
    var listener: ((JSONObject) -> Unit)? = null

    /** The station playing (or connecting), and the line that says what is on. */
    var playing: JSONObject? = null
        private set
    var line = ""
        private set
    /** A recording runs with nothing playing (a schedule): the service stays up for it. */
    var busy = false
        private set

    /** The settings as last saved, for the screen's own choices (announce actions, dial). */
    var settings = JSONObject()
        private set

    /** Null on success, else why the engine could not start. */
    fun start(context: Context): String? {
        if (handle != 0L) return null
        app = context.applicationContext
        return try {
            handle = Native.init(context.applicationContext, context.filesDir.path.toByteArray())
            if (handle == 0L) "no audio output" else { reloadSettings(); null }
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

    /** A command whose reply is messages: they go to the screen like poll events. */
    fun run(op: String, arg: Any? = null) {
        (call(op, arg) as? JSONArray)?.let { a -> a.objects().forEach { listener?.invoke(it) } }
        schedule()
    }

    fun reloadSettings() { settings = call("settings") as JSONObject }

    fun saveSettings(s: JSONObject) {
        call("set_settings", s)
        reloadSettings()
    }

    fun flag(name: String) = settings.optBoolean(name, true)

    fun schedule() {
        if (polling || handle == 0L) return
        polling = true
        main.postDelayed(::tick, POLL_MS)
    }

    private fun tick() {
        polling = false
        val r = call("poll") as JSONObject
        r.getJSONArray("events").objects().forEach { listener?.invoke(it) }
        val now = r.optJSONObject("playing")
        val newLine = r.getString("line")
        val newBusy = r.optBoolean("busy")
        val changed = now?.optString("id") != playing?.optString("id") || newLine != line || newBusy != busy
        val wasUp = playing != null || busy
        playing = now
        line = newLine
        busy = newBusy
        val ctx = app
        if (ctx != null && (now != null || newBusy) && !wasUp) {
            try {
                ctx.startForegroundService(Intent(ctx, PlaybackService::class.java))
            } catch (_: Exception) {
                // Android 12+ refuses a start from the background (an inexact alarm): play on regardless.
            }
        }
        if (changed) {
            PlaybackService.instance?.update()
            listener?.invoke(JSONObject().put("k", "state"))
        }
        val t = SystemClock.uptimeMillis()
        if (ctx != null && t - lastAlarmSync > ALARM_SYNC_MS) {
            lastAlarmSync = t
            Alarms.sync(ctx)
        }
        if (visible || now != null || newBusy) schedule()
    }

    fun toggle() = run("toggle")

    fun stop() = run("stop")
}
