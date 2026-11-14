package dev.serrebi.blindradio

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Check for Updates: the newest Forgejo release, its APK checked against the
 * release's SHA-256 file, then Android's own installer. Same key, so it installs over.
 */
object Updater {
    private const val LATEST = "https://git.serrebiradio.com/api/v1/repos/serrebi/BlindRadio-Android/releases/latest"
    private const val DAY = 24 * 60 * 60L
    private val main = Handler(Looper.getMainLooper())

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "Blind-Radio-Android")
        return c.inputStream.use { it.readBytes() }
    }

    /** `[1, 2, 0]` from `v1.2.0`. */
    fun parts(v: String) = v.trimStart('v').split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    fun newer(a: String, b: String): Boolean {
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** The daily check at start, when Settings allows it. */
    fun daily(a: Activity, say: (String) -> Unit) {
        val s = Engine.settings
        if (!s.optBoolean("check_updates", true)) return
        val now = System.currentTimeMillis() / 1000
        if (now - s.optLong("last_update_check", 0) < DAY) return
        Engine.saveSettings(JSONObject(s.toString()).put("last_update_check", now))
        check(a, quiet = true, say = say)
    }

    fun check(a: Activity, quiet: Boolean, say: (String) -> Unit) {
        val mine = a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "0"
        if (!quiet) say(a.getString(R.string.update_checking))
        Thread {
            val result = runCatching {
                val r = JSONObject(String(get(LATEST)))
                val tag = r.getString("tag_name")
                val assets = r.getJSONArray("assets").objects()
                val apk = assets.firstOrNull { it.getString("name").endsWith(".apk") }
                val sum = assets.firstOrNull { it.getString("name").endsWith(".apk.sha256") }
                Triple(tag, apk?.getString("browser_download_url"), sum?.getString("browser_download_url"))
            }
            main.post {
                val (tag, apk, sum) = result.getOrElse { if (!quiet) say(a.getString(R.string.update_failed, it.message)); return@post }
                if (!newer(tag, mine) || apk == null) {
                    if (!quiet) say(a.getString(R.string.update_none, mine))
                    return@post
                }
                AlertDialog.Builder(a)
                    .setTitle(a.getString(R.string.update_available, tag))
                    .setMessage(a.getString(R.string.update_ask, mine))
                    .setPositiveButton(R.string.update_install) { _, _ -> install(a, apk, sum, say) }
                    .setNegativeButton(R.string.later, null)
                    .show()
            }
        }.start()
    }

    private fun install(a: Activity, apkUrl: String, sumUrl: String?, say: (String) -> Unit) {
        if (!a.packageManager.canRequestPackageInstalls()) {
            say(a.getString(R.string.update_allow))
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            return
        }
        say(a.getString(R.string.update_downloading))
        Thread {
            val result = runCatching {
                val bytes = get(apkUrl)
                if (sumUrl != null) {
                    val want = String(get(sumUrl)).trim().split(Regex("\\s+"))[0].lowercase()
                    val got = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    require(want == got) { "SHA-256 does not match the release" }
                }
                val f = File(a.cacheDir, "update.apk")
                f.writeBytes(bytes)
                f
            }
            main.post {
                val f = result.getOrElse { say(a.getString(R.string.update_failed, it.message)); return@post }
                val pi = a.packageManager.packageInstaller
                val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
                pi.openSession(id).use { s ->
                    s.openWrite("blindradio.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                    val done = PendingIntent.getBroadcast(
                        a, id, Intent(a, UpdateReceiver::class.java),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    s.commit(done.intentSender)
                }
            }
        }.start()
    }
}

/** Android's installer asks the user to confirm: show its screen. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, 0) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
