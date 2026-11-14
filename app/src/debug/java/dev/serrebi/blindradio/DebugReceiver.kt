package dev.serrebi.blindradio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONTokener

/** `adb shell am broadcast -a dev.serrebi.blindradio.DEBUG --es op X --es arg JSON`: debug builds only. */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Engine.start(ctx)
        val op = intent.getStringExtra("op") ?: return
        val arg = intent.getStringExtra("arg")?.let { JSONTokener(it).nextValue() }
        val r = try { Engine.call(op, arg) } catch (e: Exception) { "ERR " + e.message }
        Engine.schedule()
        Alarms.sync(ctx)
        Log.i("BRDBG", "$op -> ${r.toString().take(3000)} | line=${Engine.line}")
    }
}
