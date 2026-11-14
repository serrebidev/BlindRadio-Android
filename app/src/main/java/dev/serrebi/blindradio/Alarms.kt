package dev.serrebi.blindradio

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Scheduled recordings and alarms (desktop's Scheduled Recordings), woken by Android:
 * one alarm at the next start; the engine's own once-a-second check does the rest.
 * The time is kept in preferences too, so a reboot re-arms it without the engine.
 */
object Alarms {
    private const val PREFS = "alarms"
    private const val NEXT = "next"

    private fun intent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, AlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Exact alarms may start the playback service from the background; inexact ones may not. */
    fun exactAllowed(ctx: Context) =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun sync(ctx: Context) {
        val next = if (Engine.started) (Engine.call("next_schedule") as? Number)?.toLong() else null
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(NEXT, next ?: 0L).apply()
        arm(ctx, next)
    }

    fun arm(ctx: Context, nextUnix: Long?) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = intent(ctx)
        if (nextUnix == null || nextUnix <= 0) { am.cancel(pi); return }
        val at = maxOf(nextUnix * 1000, System.currentTimeMillis() + 1000)
        if (exactAllowed(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun rearm(ctx: Context) {
        val next = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(NEXT, 0L)
        arm(ctx, next.takeIf { it > 0 })
    }
}

/** A schedule is due: start the engine; its poll starts the recording or the alarm's station. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Engine.start(ctx) != null) return
        Engine.schedule()
        // The schedule check needs the poll running for a while: keep it alive like a recording.
        try {
            ctx.startForegroundService(Intent(ctx, PlaybackService::class.java))
        } catch (_: Exception) {}
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Alarms.rearm(ctx)
    }
}
